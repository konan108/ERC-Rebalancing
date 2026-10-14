package de.konan.hiresplayer.tidal

import org.json.JSONArray
import org.json.JSONObject
import java.time.Duration

data class TidalTrack(
    val id: String,
    val title: String,
    val artists: String,
    val album: String,
    val durationMs: Long,
    val mediaTags: List<String>,
    val artworkUrl: String?,
) {
    /** Höchste verfügbare Qualität laut TIDAL-Media-Tags. */
    val qualityLabel: String?
        get() = when {
            mediaTags.any { it.equals("HIRES_LOSSLESS", true) } -> "Hi-Res"
            mediaTags.any { it.equals("LOSSLESS", true) } -> "Lossless"
            mediaTags.any { it.contains("DOLBY", true) } -> "Atmos"
            else -> null
        }

    val webUrl: String get() = "https://tidal.com/browse/track/$id"
}

data class TidalAlbum(
    val id: String,
    val title: String,
    val artists: String,
    val numberOfItems: Int,
    val artworkUrl: String?,
) {
    val webUrl: String get() = "https://tidal.com/browse/album/$id"
}

data class TidalPlaylist(
    val id: String,
    val name: String,
    val description: String,
    val numberOfItems: Int,
    val artworkUrl: String?,
) {
    val webUrl: String get() = "https://tidal.com/browse/playlist/$id"
}

data class Page<T>(val items: List<T>, val next: String?)

/**
 * Minimaler JSON:API-Leser für openapi.tidal.com/v2. Bewusst tolerant:
 * fehlende Relationen oder Attribute führen zu leeren Feldern statt zu Fehlern.
 */
class JsonApiDocument(json: String) {
    private val root = JSONObject(json)
    private val included: Map<Pair<String, String>, JSONObject> =
        buildMap {
            val arr = root.optJSONArray("included") ?: JSONArray()
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                put(o.optString("type") to o.optString("id"), o)
            }
        }

    /** Ressourcen aus `data` (Objekt oder Array); Identifier werden über `included` aufgelöst. */
    fun primary(): List<JSONObject> {
        val list = when (val data = root.opt("data")) {
            is JSONArray -> (0 until data.length()).mapNotNull { data.optJSONObject(it) }
            is JSONObject -> listOf(data)
            else -> emptyList()
        }
        return list.map { resolve(it) }
    }

    /** Folgt der Relation [name] einer Ressource und liefert die aufgelösten Ressourcen. */
    fun related(resource: JSONObject, name: String): List<JSONObject> {
        val data = resource.optJSONObject("relationships")?.optJSONObject(name)?.opt("data") ?: return emptyList()
        val ids = when (data) {
            is JSONArray -> (0 until data.length()).mapNotNull { data.optJSONObject(it) }
            is JSONObject -> listOf(data)
            else -> emptyList()
        }
        return ids.map { resolve(it) }
    }

    fun nextLink(): String? {
        val links = root.optJSONObject("links") ?: return null
        links.optString("next").takeIf { it.isNotBlank() && it != "null" }?.let { return it }
        val cursor = links.optJSONObject("meta")?.optString("nextCursor")
        return cursor?.takeIf { it.isNotBlank() && it != "null" }?.let { "cursor:$it" }
    }

    private fun resolve(identifier: JSONObject): JSONObject =
        if (identifier.has("attributes")) identifier
        else included[identifier.optString("type") to identifier.optString("id")] ?: identifier

    fun toTrack(r: JSONObject): TidalTrack {
        val a = r.optJSONObject("attributes") ?: JSONObject()
        val album = related(r, "albums").firstOrNull()
        return TidalTrack(
            id = r.optString("id"),
            title = a.optString("title").ifBlank { "Titel ${r.optString("id")}" }
                .let { t -> a.optString("version").takeIf { it.isNotBlank() && it != "null" }?.let { "$t ($it)" } ?: t },
            artists = artistNames(r),
            album = album?.optJSONObject("attributes")?.optString("title").orEmpty(),
            durationMs = parseIsoDuration(a.optString("duration")),
            mediaTags = stringList(a.optJSONArray("mediaTags") ?: a.optJSONArray("media_tags")),
            artworkUrl = album?.let { artworkOf(it, "coverArt") },
        )
    }

    fun toAlbum(r: JSONObject): TidalAlbum {
        val a = r.optJSONObject("attributes") ?: JSONObject()
        return TidalAlbum(
            id = r.optString("id"),
            title = a.optString("title"),
            artists = artistNames(r),
            numberOfItems = a.optInt("numberOfItems"),
            artworkUrl = artworkOf(r, "coverArt"),
        )
    }

    fun toPlaylist(r: JSONObject): TidalPlaylist {
        val a = r.optJSONObject("attributes") ?: JSONObject()
        return TidalPlaylist(
            id = r.optString("id"),
            name = a.optString("name"),
            description = a.optString("description").takeIf { it != "null" }.orEmpty(),
            numberOfItems = a.optInt("numberOfItems"),
            artworkUrl = artworkOf(r, "coverArt"),
        )
    }

    private fun artistNames(r: JSONObject): String =
        related(r, "artists").mapNotNull { it.optJSONObject("attributes")?.optString("name")?.takeIf(String::isNotBlank) }
            .joinToString(", ")

    /** Wählt die kleinste Bildvariante ab 320 px, sonst die größte. */
    private fun artworkOf(r: JSONObject, relation: String): String? {
        val artwork = related(r, relation).firstOrNull() ?: return null
        val files = artwork.optJSONObject("attributes")?.optJSONArray("files") ?: return null
        val candidates = (0 until files.length()).mapNotNull { files.optJSONObject(it) }
            .map { it.optString("href") to (it.optJSONObject("meta")?.optInt("width") ?: 0) }
            .filter { it.first.isNotBlank() }
        return (candidates.filter { it.second >= 320 }.minByOrNull { it.second } ?: candidates.maxByOrNull { it.second })?.first
    }

    private fun stringList(arr: JSONArray?): List<String> =
        if (arr == null) emptyList() else (0 until arr.length()).map { arr.optString(it) }

    companion object {
        fun parseIsoDuration(value: String?): Long =
            if (value.isNullOrBlank()) 0L else runCatching { Duration.parse(value).toMillis() }.getOrDefault(0L)
    }
}
