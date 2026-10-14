package de.konan.hiresplayer.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import de.konan.hiresplayer.HiResApp
import de.konan.hiresplayer.tidal.SearchResult
import de.konan.hiresplayer.tidal.TidalAlbum
import de.konan.hiresplayer.tidal.TidalPlaylist
import de.konan.hiresplayer.tidal.TidalTrack
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class TidalSection(val label: String) {
    SEARCH("Suche"),
    TRACKS("Titel"),
    ALBUMS("Alben"),
    PLAYLISTS("Playlists"),
}

sealed interface TidalDetail {
    val title: String
    val webUrl: String

    data class Album(val album: TidalAlbum) : TidalDetail {
        override val title get() = album.title
        override val webUrl get() = album.webUrl
    }

    data class Playlist(val playlist: TidalPlaylist) : TidalDetail {
        override val title get() = playlist.name
        override val webUrl get() = playlist.webUrl
    }
}

data class Paged<T>(val items: List<T> = emptyList(), val next: String? = null, val loaded: Boolean = false)

data class TidalUiState(
    val section: TidalSection = TidalSection.TRACKS,
    val query: String = "",
    val search: SearchResult? = null,
    val tracks: Paged<TidalTrack> = Paged(),
    val albums: Paged<TidalAlbum> = Paged(),
    val playlists: Paged<TidalPlaylist> = Paged(),
    val detail: TidalDetail? = null,
    val detailTracks: Paged<TidalTrack> = Paged(),
    val loading: Boolean = false,
    val error: String? = null,
)

class TidalViewModel(app: Application) : AndroidViewModel(app) {
    val session = (app as HiResApp).tidal

    private val _ui = MutableStateFlow(TidalUiState())
    val ui: StateFlow<TidalUiState> = _ui.asStateFlow()

    private var searchJob: Job? = null

    fun selectSection(section: TidalSection) {
        _ui.update { it.copy(section = section, detail = null) }
        ensureLoaded()
    }

    fun setQuery(query: String) {
        _ui.update { it.copy(query = query) }
    }

    fun runSearch() {
        val q = _ui.value.query.trim()
        if (q.isEmpty()) return
        searchJob?.cancel()
        searchJob = load { _ui.update { it.copy(search = session.search(q)) } }
    }

    fun ensureLoaded() {
        val s = _ui.value
        when (s.section) {
            TidalSection.TRACKS -> if (!s.tracks.loaded) loadMore()
            TidalSection.ALBUMS -> if (!s.albums.loaded) loadMore()
            TidalSection.PLAYLISTS -> if (!s.playlists.loaded) loadMore()
            TidalSection.SEARCH -> Unit
        }
    }

    fun loadMore(force: Boolean = false) {
        val s = _ui.value
        if (s.loading && !force) return
        s.detail?.let { detail ->
            load {
                val page = when (detail) {
                    is TidalDetail.Album -> session.albumTracks(detail.album.id, s.detailTracks.next)
                    is TidalDetail.Playlist -> session.playlistTracks(detail.playlist.id, s.detailTracks.next)
                }
                _ui.update { it.copy(detailTracks = it.detailTracks.append(page.items, page.next)) }
            }
            return
        }
        when (s.section) {
            TidalSection.TRACKS -> load {
                val page = session.favoriteTracks(s.tracks.next)
                _ui.update { it.copy(tracks = it.tracks.append(page.items, page.next)) }
            }
            TidalSection.ALBUMS -> load {
                val page = session.favoriteAlbums(s.albums.next)
                _ui.update { it.copy(albums = it.albums.append(page.items, page.next)) }
            }
            TidalSection.PLAYLISTS -> load {
                val page = session.favoritePlaylists(s.playlists.next)
                _ui.update { it.copy(playlists = it.playlists.append(page.items, page.next)) }
            }
            TidalSection.SEARCH -> Unit
        }
    }

    fun openDetail(detail: TidalDetail) {
        _ui.update { it.copy(detail = detail, detailTracks = Paged()) }
        loadMore(force = true)
    }

    fun closeDetail() {
        _ui.update { it.copy(detail = null) }
    }

    fun refresh() {
        _ui.update { TidalUiState(section = it.section, query = it.query) }
        if (_ui.value.section == TidalSection.SEARCH) runSearch() else ensureLoaded()
    }

    fun dismissError() {
        _ui.update { it.copy(error = null) }
        session.clearError()
    }

    private fun load(block: suspend () -> Unit): Job = viewModelScope.launch {
        _ui.update { it.copy(loading = true, error = null) }
        try {
            block()
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            _ui.update { it.copy(error = e.message ?: e.toString()) }
        } finally {
            _ui.update { it.copy(loading = false) }
        }
    }

    private fun <T> Paged<T>.append(more: List<T>, next: String?) =
        Paged(items + more, next?.takeUnless { it.startsWith("cursor:") }, loaded = true)
}
