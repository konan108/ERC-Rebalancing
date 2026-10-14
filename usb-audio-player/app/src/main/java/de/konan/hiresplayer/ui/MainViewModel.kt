package de.konan.hiresplayer.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import de.konan.hiresplayer.library.MusicLibrary
import de.konan.hiresplayer.library.Track
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class MainViewModel(app: Application) : AndroidViewModel(app) {
    private val tracks = MutableStateFlow<List<Track>>(emptyList())

    private val _loading = MutableStateFlow(false)
    val loading: StateFlow<Boolean> = _loading.asStateFlow()

    private val _query = MutableStateFlow("")
    val query: StateFlow<String> = _query.asStateFlow()

    val visibleTracks: StateFlow<List<Track>> = combine(tracks, _query) { all, q ->
        if (q.isBlank()) all
        else all.filter {
            it.title.contains(q, true) || it.artist.contains(q, true) || it.album.contains(q, true)
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun setQuery(value: String) {
        _query.value = value
    }

    fun loadLibrary() {
        if (_loading.value) return
        viewModelScope.launch {
            _loading.value = true
            tracks.value = runCatching { MusicLibrary.load(getApplication()) }.getOrDefault(emptyList())
            _loading.value = false
        }
    }
}
