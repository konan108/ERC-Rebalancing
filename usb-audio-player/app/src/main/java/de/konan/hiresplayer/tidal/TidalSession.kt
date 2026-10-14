package de.konan.hiresplayer.tidal

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

sealed interface TidalState {
    data object NotConfigured : TidalState
    data object LoggedOut : TidalState
    data object Busy : TidalState
    data class LoggedIn(val userId: String, val userName: String, val countryCode: String) : TidalState
}

class TidalHttpException(val status: Int, body: String) :
    IOException("TIDAL-API antwortet mit HTTP $status: ${body.take(300)}")

/**
 * TIDAL-Login (OAuth 2.0 Authorization Code + PKCE) und Zugriff auf die offizielle
 * Web-API (openapi.tidal.com/v2). Nur Katalog- und Bibliotheksdaten – Wiedergabe
 * vollständiger Titel erfordert eine Freigabe durch TIDAL.
 */
class TidalSession(context: Context) {
    private val prefs = context.getSharedPreferences("tidal", Context.MODE_PRIVATE)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val refreshMutex = Mutex()

    private val _state = MutableStateFlow<TidalState>(TidalState.NotConfigured)
    val state: StateFlow<TidalState> = _state.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    val clientId: String get() = prefs.getString(KEY_CLIENT_ID, null).orEmpty()
    val scopes: String get() = prefs.getString(KEY_SCOPES, null) ?: DEFAULT_SCOPES

    init {
        _state.value = restoredState()
    }

    fun configure(clientId: String, scopes: String) {
        prefs.edit()
            .putString(KEY_CLIENT_ID, clientId.trim())
            .putString(KEY_SCOPES, scopes.trim().ifBlank { DEFAULT_SCOPES })
            .apply()
        if (_state.value is TidalState.NotConfigured) _state.value = TidalState.LoggedOut
    }

    fun clearError() {
        _error.value = null
    }

    /** Liefert den Intent, der die TIDAL-Anmeldeseite im Browser öffnet. */
    fun loginIntent(): Intent {
        val verifier = Pkce.newVerifier()
        val state = Pkce.newState()
        prefs.edit().putString(KEY_VERIFIER, verifier).putString(KEY_STATE, state).apply()
        val uri = Uri.parse(AUTHORIZE_URL).buildUpon()
            .appendQueryParameter("response_type", "code")
            .appendQueryParameter("client_id", clientId)
            .appendQueryParameter("redirect_uri", REDIRECT_URI)
            .appendQueryParameter("scope", scopes)
            .appendQueryParameter("code_challenge_method", "S256")
            .appendQueryParameter("code_challenge", Pkce.challenge(verifier))
            .appendQueryParameter("state", state)
            .build()
        return Intent(Intent.ACTION_VIEW, uri)
    }

    /** Verarbeitet den Redirect `hiresplayer://tidal/callback?code=…`. */
    fun handleRedirect(uri: Uri?): Boolean {
        if (uri == null || uri.scheme != REDIRECT_SCHEME || uri.host != REDIRECT_HOST) return false
        val expectedState = prefs.getString(KEY_STATE, null)
        val verifier = prefs.getString(KEY_VERIFIER, null)
        val code = uri.getQueryParameter("code")
        val errorParam = uri.getQueryParameter("error")
        when {
            errorParam != null ->
                _error.value = "Anmeldung abgelehnt: $errorParam ${uri.getQueryParameter("error_description").orEmpty()}"
            code == null || verifier == null -> _error.value = "Anmeldung unvollständig, bitte erneut versuchen."
            uri.getQueryParameter("state") != expectedState -> _error.value = "Sicherheitsprüfung (state) fehlgeschlagen."
            else -> scope.launch { exchangeCode(code, verifier) }
        }
        prefs.edit().remove(KEY_STATE).remove(KEY_VERIFIER).apply()
        return true
    }

    fun logout() {
        prefs.edit()
            .remove(KEY_ACCESS).remove(KEY_REFRESH).remove(KEY_EXPIRES)
            .remove(KEY_USER_ID).remove(KEY_USER_NAME).remove(KEY_COUNTRY)
            .apply()
        _state.value = if (clientId.isBlank()) TidalState.NotConfigured else TidalState.LoggedOut
    }

    // ---- Bibliothek -------------------------------------------------------------------------

    suspend fun search(query: String): SearchResult {
        val doc = getWithFallback(
            "/searchResults",
            mapOf("filter[query]" to query),
            listOf("tracks", "tracks.artists", "tracks.albums.coverArt", "albums", "albums.artists", "albums.coverArt", "playlists"),
        )
        val result = doc.primary().firstOrNull() ?: return SearchResult(emptyList(), emptyList(), emptyList())
        return SearchResult(
            tracks = doc.related(result, "tracks").map(doc::toTrack),
            albums = doc.related(result, "albums").map(doc::toAlbum),
            playlists = doc.related(result, "playlists").map(doc::toPlaylist),
        )
    }

    suspend fun favoriteTracks(next: String? = null): Page<TidalTrack> =
        collectionPage("tracks", listOf("tracks", "tracks.artists", "tracks.albums.coverArt"), next) { doc, r -> doc.toTrack(r) }

    suspend fun favoriteAlbums(next: String? = null): Page<TidalAlbum> =
        collectionPage("albums", listOf("albums", "albums.artists", "albums.coverArt"), next) { doc, r -> doc.toAlbum(r) }

    suspend fun favoritePlaylists(next: String? = null): Page<TidalPlaylist> =
        collectionPage("playlists", listOf("playlists", "playlists.coverArt"), next) { doc, r -> doc.toPlaylist(r) }

    suspend fun albumTracks(albumId: String, next: String? = null): Page<TidalTrack> {
        val doc = next?.let { getNext(it) }
            ?: getWithFallback("/albums/$albumId/relationships/items", emptyMap(), listOf("items", "items.artists", "items.albums.coverArt"))
        return Page(doc.primary().map(doc::toTrack), doc.nextLink())
    }

    suspend fun playlistTracks(playlistId: String, next: String? = null): Page<TidalTrack> {
        val doc = next?.let { getNext(it) }
            ?: getWithFallback("/playlists/$playlistId/relationships/items", emptyMap(), listOf("items", "items.artists", "items.albums.coverArt"))
        // Playlists können auch Videos enthalten – nur Tracks anzeigen.
        return Page(doc.primary().filter { it.optString("type") == "tracks" }.map(doc::toTrack), doc.nextLink())
    }

    private suspend fun <T> collectionPage(
        relation: String,
        includes: List<String>,
        next: String?,
        map: (JsonApiDocument, JSONObject) -> T,
    ): Page<T> {
        val user = (_state.value as? TidalState.LoggedIn)?.userId ?: throw IOException("Nicht bei TIDAL angemeldet")
        val doc = next?.let { getNext(it) }
            ?: getWithFallback("/userCollections/$user/relationships/$relation", mapOf("locale" to "de-DE"), includes)
        return Page(doc.primary().map { map(doc, it) }, doc.nextLink())
    }

    // ---- HTTP -------------------------------------------------------------------------------

    /**
     * Fragt mit allen [includes] an; lehnt die API verschachtelte Includes ab (HTTP 400),
     * wird nur mit dem ersten (Haupt-)Include erneut gefragt.
     */
    private suspend fun getWithFallback(path: String, params: Map<String, String>, includes: List<String>): JsonApiDocument {
        return try {
            get(path, params + ("include" to includes.joinToString(",")))
        } catch (e: TidalHttpException) {
            if (e.status != 400 || includes.size <= 1) throw e
            Log.w(TAG, "Includes abgelehnt, reduziere: ${e.message}")
            get(path, params + ("include" to includes.first()))
        }
    }

    private suspend fun getNext(next: String): JsonApiDocument =
        if (next.startsWith("cursor:")) throw IOException("Seitenweises Laden ohne Link wird nicht unterstützt")
        else getUrl(if (next.startsWith("http")) next else API_BASE + next)

    private suspend fun get(path: String, params: Map<String, String>): JsonApiDocument {
        val country = (_state.value as? TidalState.LoggedIn)?.countryCode ?: "DE"
        val query = (params + ("countryCode" to country)).entries.joinToString("&") { (k, v) ->
            "${encode(k)}=${encode(v)}"
        }
        return getUrl("$API_BASE$path?$query")
    }

    private suspend fun getUrl(url: String): JsonApiDocument {
        var token = validAccessToken()
        var response = request(url, token)
        if (response.first == 401) {
            token = refreshAccessToken(force = true)
            response = request(url, token)
        }
        val (status, body) = response
        if (status !in 200..299) throw TidalHttpException(status, body)
        return withContext(Dispatchers.Default) { JsonApiDocument(body) }
    }

    private suspend fun request(url: String, token: String): Pair<Int, String> = withContext(Dispatchers.IO) {
        val conn = URL(url).openConnection() as HttpURLConnection
        try {
            conn.setRequestProperty("Authorization", "Bearer $token")
            conn.setRequestProperty("Accept", "application/vnd.api+json")
            conn.connectTimeout = 15_000
            conn.readTimeout = 20_000
            val status = conn.responseCode
            val stream = if (status in 200..299) conn.inputStream else conn.errorStream
            status to (stream?.bufferedReader()?.use { it.readText() }.orEmpty())
        } finally {
            conn.disconnect()
        }
    }

    private suspend fun postForm(url: String, form: Map<String, String>): Pair<Int, String> = withContext(Dispatchers.IO) {
        val conn = URL(url).openConnection() as HttpURLConnection
        try {
            conn.requestMethod = "POST"
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
            conn.connectTimeout = 15_000
            conn.readTimeout = 20_000
            val body = form.entries.joinToString("&") { (k, v) -> "${encode(k)}=${encode(v)}" }
            conn.outputStream.use { it.write(body.toByteArray()) }
            val status = conn.responseCode
            val stream = if (status in 200..299) conn.inputStream else conn.errorStream
            status to (stream?.bufferedReader()?.use { it.readText() }.orEmpty())
        } finally {
            conn.disconnect()
        }
    }

    // ---- Tokens -----------------------------------------------------------------------------

    private suspend fun exchangeCode(code: String, verifier: String) {
        _state.value = TidalState.Busy
        try {
            val (status, body) = postForm(
                TOKEN_URL,
                mapOf(
                    "grant_type" to "authorization_code",
                    "client_id" to clientId,
                    "code" to code,
                    "redirect_uri" to REDIRECT_URI,
                    "code_verifier" to verifier,
                ),
            )
            if (status !in 200..299) throw TidalHttpException(status, body)
            storeTokens(JSONObject(body))
            loadUser()
        } catch (e: Exception) {
            Log.w(TAG, "Login fehlgeschlagen", e)
            _error.value = "Login fehlgeschlagen: ${e.message}"
            logout()
        }
    }

    private suspend fun loadUser() {
        // Vorläufig einloggen, damit get() das Token nutzt; Land folgt aus dem Profil.
        _state.value = TidalState.LoggedIn(userId = "me", userName = "", countryCode = "DE")
        val user = runCatching { getUrl("$API_BASE/users/me").primary().firstOrNull() }
            .onFailure { Log.w(TAG, "Profil nicht lesbar", it) }
            .getOrNull()
        val attributes = user?.optJSONObject("attributes")
        val id = user?.optString("id")?.takeIf { it.isNotBlank() } ?: "me"
        val name = attributes?.optString("username")?.takeIf { it.isNotBlank() && it != "null" }
            ?: attributes?.optString("email")?.takeIf { it.isNotBlank() && it != "null" }
            ?: "TIDAL-Konto"
        val country = attributes?.optString("country")?.takeIf { it.length == 2 } ?: "DE"
        prefs.edit().putString(KEY_USER_ID, id).putString(KEY_USER_NAME, name).putString(KEY_COUNTRY, country).apply()
        _state.value = TidalState.LoggedIn(id, name, country)
    }

    private fun storeTokens(json: JSONObject) {
        val expiresIn = json.optLong("expires_in", 3600)
        val editor = prefs.edit()
            .putString(KEY_ACCESS, json.getString("access_token"))
            .putLong(KEY_EXPIRES, System.currentTimeMillis() + expiresIn * 1000)
        // Beim Refresh liefert TIDAL nicht immer ein neues Refresh-Token.
        json.optString("refresh_token").takeIf { it.isNotBlank() }?.let { editor.putString(KEY_REFRESH, it) }
        editor.apply()
    }

    private suspend fun validAccessToken(): String {
        val token = prefs.getString(KEY_ACCESS, null) ?: throw IOException("Nicht bei TIDAL angemeldet")
        val expires = prefs.getLong(KEY_EXPIRES, 0)
        return if (System.currentTimeMillis() > expires - 60_000) refreshAccessToken(force = false) else token
    }

    private suspend fun refreshAccessToken(force: Boolean): String = refreshMutex.withLock {
        val current = prefs.getString(KEY_ACCESS, null)
        if (!force && current != null && System.currentTimeMillis() < prefs.getLong(KEY_EXPIRES, 0) - 60_000) {
            return current
        }
        val refresh = prefs.getString(KEY_REFRESH, null)
        if (refresh == null) {
            logout()
            throw IOException("Sitzung abgelaufen, bitte neu anmelden")
        }
        val (status, body) = postForm(
            TOKEN_URL,
            mapOf("grant_type" to "refresh_token", "client_id" to clientId, "refresh_token" to refresh),
        )
        if (status !in 200..299) {
            if (status in 400..401) logout()
            throw TidalHttpException(status, body)
        }
        storeTokens(JSONObject(body))
        prefs.getString(KEY_ACCESS, null)!!
    }

    private fun restoredState(): TidalState = when {
        clientId.isBlank() -> TidalState.NotConfigured
        prefs.getString(KEY_REFRESH, null) == null && prefs.getString(KEY_ACCESS, null) == null -> TidalState.LoggedOut
        else -> TidalState.LoggedIn(
            prefs.getString(KEY_USER_ID, null) ?: "me",
            prefs.getString(KEY_USER_NAME, null) ?: "TIDAL-Konto",
            prefs.getString(KEY_COUNTRY, null) ?: "DE",
        )
    }

    private fun encode(value: String) = URLEncoder.encode(value, "UTF-8").replace("+", "%20")

    companion object {
        private const val TAG = "TidalSession"
        const val REDIRECT_SCHEME = "hiresplayer"
        const val REDIRECT_HOST = "tidal"
        const val REDIRECT_URI = "hiresplayer://tidal/callback"
        const val DEFAULT_SCOPES = "user.read collection.read playlists.read search.read"
        private const val AUTHORIZE_URL = "https://login.tidal.com/authorize"
        private const val TOKEN_URL = "https://auth.tidal.com/v1/oauth2/token"
        private const val API_BASE = "https://openapi.tidal.com/v2"

        private const val KEY_CLIENT_ID = "client_id"
        private const val KEY_SCOPES = "scopes"
        private const val KEY_VERIFIER = "pkce_verifier"
        private const val KEY_STATE = "oauth_state"
        private const val KEY_ACCESS = "access_token"
        private const val KEY_REFRESH = "refresh_token"
        private const val KEY_EXPIRES = "expires_at"
        private const val KEY_USER_ID = "user_id"
        private const val KEY_USER_NAME = "user_name"
        private const val KEY_COUNTRY = "country"
    }
}

data class SearchResult(
    val tracks: List<TidalTrack>,
    val albums: List<TidalAlbum>,
    val playlists: List<TidalPlaylist>,
)
