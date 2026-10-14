package de.konan.hiresplayer.ui

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import de.konan.hiresplayer.tidal.TidalAlbum
import de.konan.hiresplayer.tidal.TidalPlaylist
import de.konan.hiresplayer.tidal.TidalSession
import de.konan.hiresplayer.tidal.TidalState
import de.konan.hiresplayer.tidal.TidalTrack

@Composable
fun TidalScreen(modifier: Modifier = Modifier, vm: TidalViewModel = viewModel()) {
    val state by vm.session.state.collectAsStateWithLifecycle()
    val sessionError by vm.session.error.collectAsStateWithLifecycle()
    var editingSetup by rememberSaveable { mutableStateOf(false) }

    Column(modifier) {
        sessionError?.let { ErrorBanner(it, onDismiss = vm::dismissError) }
        when (val s = state) {
            TidalState.NotConfigured -> SetupView(vm.session, onDone = { editingSetup = false })
            TidalState.LoggedOut ->
                if (editingSetup) SetupView(vm.session, onDone = { editingSetup = false })
                else LoginView(vm.session, onEditSetup = { editingSetup = true })
            TidalState.Busy -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            is TidalState.LoggedIn -> LibraryView(vm, s)
        }
    }
}

@Composable
private fun SetupView(session: TidalSession, onDone: () -> Unit) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    var clientId by rememberSaveable { mutableStateOf(session.clientId) }
    var scopes by rememberSaveable { mutableStateOf(session.scopes) }

    Column(
        Modifier
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("TIDAL verbinden", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
        Text(
            "Einmalig nötig: eine eigene App im TIDAL-Developer-Portal anlegen. Das ist kostenlos.",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Step("1", "developer.tidal.com öffnen, mit deinem TIDAL-Konto anmelden und im Dashboard eine App anlegen.")
        OutlinedButton(onClick = { context.openUrl("https://developer.tidal.com/dashboard") }) {
            Icon(Icons.AutoMirrored.Filled.OpenInNew, contentDescription = null)
            Spacer(Modifier.width(8.dp))
            Text("Developer-Portal öffnen")
        }
        Step("2", "In den App-Einstellungen diese Redirect-URI eintragen und die Scopes unten aktivieren:")
        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh)) {
            Row(Modifier.padding(start = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(TidalSession.REDIRECT_URI, fontFamily = FontFamily.Monospace, modifier = Modifier.weight(1f))
                IconButton(onClick = { clipboard.setText(AnnotatedString(TidalSession.REDIRECT_URI)) }) {
                    Icon(Icons.Filled.ContentCopy, contentDescription = "Kopieren")
                }
            }
        }
        Step("3", "Die Client-ID deiner TIDAL-App hier einfügen. Ein Client-Secret ist nicht nötig, die App nutzt PKCE.")
        OutlinedTextField(
            value = clientId,
            onValueChange = { clientId = it },
            label = { Text("Client-ID") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = scopes,
            onValueChange = { scopes = it },
            label = { Text("Scopes") },
            supportingText = { Text("Nur Scopes, die im Portal für deine App freigeschaltet sind.") },
            modifier = Modifier.fillMaxWidth(),
        )
        Button(
            onClick = {
                session.configure(clientId, scopes)
                onDone()
                context.startActivity(session.loginIntent())
            },
            enabled = clientId.isNotBlank(),
            modifier = Modifier.fillMaxWidth(),
        ) { Text("Speichern & bei TIDAL anmelden") }
    }
}

@Composable
private fun Step(number: String, text: String) {
    Row {
        Text("$number.", fontWeight = FontWeight.Bold, color = Amber, modifier = Modifier.width(24.dp))
        Text(text)
    }
}

@Composable
private fun LoginView(session: TidalSession, onEditSetup: () -> Unit) {
    val context = LocalContext.current
    Column(
        Modifier
            .fillMaxSize()
            .padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("TIDAL", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(8.dp))
        Text("Melde dich an, um Favoriten, Alben und Playlists zu sehen.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(24.dp))
        Button(onClick = { context.startActivity(session.loginIntent()) }) { Text("Bei TIDAL anmelden") }
        TextButton(onClick = onEditSetup) { Text("Einrichtung ändern") }
    }
}

@Composable
private fun LibraryView(vm: TidalViewModel, user: TidalState.LoggedIn) {
    val ui by vm.ui.collectAsStateWithLifecycle()
    val context = LocalContext.current

    LaunchedEffect(Unit) { vm.ensureLoaded() }
    BackHandler(enabled = ui.detail != null) { vm.closeDetail() }

    Column(Modifier.fillMaxSize()) {
        Row(Modifier.padding(start = 16.dp, end = 4.dp, top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("TIDAL", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                Text(
                    "${user.userName} · ${user.countryCode}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            IconButton(onClick = vm::refresh) { Icon(Icons.Filled.Refresh, contentDescription = "Aktualisieren") }
            IconButton(onClick = vm.session::logout) { Icon(Icons.AutoMirrored.Filled.Logout, contentDescription = "Abmelden") }
        }
        Text(
            "Wiedergabe in dieser App ist erst nach Freigabe durch TIDAL möglich. Bis dahin öffnet ein Tipp den Titel in TIDAL.",
            style = MaterialTheme.typography.bodySmall,
            color = Amber,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
        )

        val detail = ui.detail
        if (detail != null) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = vm::closeDetail) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Zurück") }
                Text(
                    detail.title,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = { context.openUrl(detail.webUrl) }) {
                    Icon(Icons.AutoMirrored.Filled.OpenInNew, contentDescription = "In TIDAL öffnen")
                }
            }
        } else {
            ScrollableTabRow(selectedTabIndex = ui.section.ordinal, edgePadding = 8.dp) {
                TidalSection.entries.forEach { section ->
                    Tab(
                        selected = ui.section == section,
                        onClick = { vm.selectSection(section) },
                        text = { Text(section.label) },
                    )
                }
            }
        }

        if (ui.loading) LinearProgressIndicator(Modifier.fillMaxWidth()) else Spacer(Modifier.height(4.dp))
        ui.error?.let { ErrorBanner(it, onDismiss = vm::dismissError) }

        if (detail == null && ui.section == TidalSection.SEARCH) {
            OutlinedTextField(
                value = ui.query,
                onValueChange = vm::setQuery,
                singleLine = true,
                leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                placeholder = { Text("Titel, Album, Interpret") },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { vm.runSearch() }),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
            )
        }

        val openTrack: (TidalTrack) -> Unit = { context.openUrl(it.webUrl) }
        val openAlbum: (TidalAlbum) -> Unit = { vm.openDetail(TidalDetail.Album(it)) }
        val openPlaylist: (TidalPlaylist) -> Unit = { vm.openDetail(TidalDetail.Playlist(it)) }

        LazyColumn(Modifier.fillMaxSize()) {
            when {
                detail != null -> {
                    trackItems(ui.detailTracks.items, openTrack)
                    moreItem(ui.detailTracks.next, ui.loading, vm::loadMore)
                }
                ui.section == TidalSection.SEARCH -> ui.search?.let { result ->
                    if (result.tracks.isNotEmpty()) header("Titel")
                    trackItems(result.tracks, openTrack)
                    if (result.albums.isNotEmpty()) header("Alben")
                    items(result.albums, key = { "a" + it.id }) { AlbumRow(it, openAlbum) }
                    if (result.playlists.isNotEmpty()) header("Playlists")
                    items(result.playlists, key = { "p" + it.id }) { PlaylistRow(it, openPlaylist) }
                }
                ui.section == TidalSection.TRACKS -> {
                    trackItems(ui.tracks.items, openTrack)
                    emptyHint(ui.tracks.loaded && ui.tracks.items.isEmpty(), "Keine Favoriten-Titel gefunden")
                    moreItem(ui.tracks.next, ui.loading, vm::loadMore)
                }
                ui.section == TidalSection.ALBUMS -> {
                    items(ui.albums.items, key = { "a" + it.id }) { AlbumRow(it, openAlbum) }
                    emptyHint(ui.albums.loaded && ui.albums.items.isEmpty(), "Keine Favoriten-Alben gefunden")
                    moreItem(ui.albums.next, ui.loading, vm::loadMore)
                }
                ui.section == TidalSection.PLAYLISTS -> {
                    items(ui.playlists.items, key = { "p" + it.id }) { PlaylistRow(it, openPlaylist) }
                    emptyHint(ui.playlists.loaded && ui.playlists.items.isEmpty(), "Keine Playlists gefunden")
                    moreItem(ui.playlists.next, ui.loading, vm::loadMore)
                }
            }
        }
    }
}

private fun LazyListScope.trackItems(tracks: List<TidalTrack>, onClick: (TidalTrack) -> Unit) {
    items(tracks.size, key = { "t$it-" + tracks[it].id }) { TrackRow(tracks[it], onClick) }
}

private fun LazyListScope.header(text: String) {
    item {
        Text(
            text,
            style = MaterialTheme.typography.titleSmall,
            color = Amber,
            modifier = Modifier.padding(start = 16.dp, top = 12.dp, bottom = 4.dp),
        )
    }
}

private fun LazyListScope.emptyHint(show: Boolean, text: String) {
    if (!show) return
    item {
        Text(text, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(16.dp))
    }
}

private fun LazyListScope.moreItem(next: String?, loading: Boolean, onMore: () -> Unit) {
    if (next == null) return
    item {
        Box(Modifier.fillMaxWidth().padding(12.dp), contentAlignment = Alignment.Center) {
            OutlinedButton(onClick = onMore, enabled = !loading) { Text("Mehr laden") }
        }
    }
}

@Composable
private fun TrackRow(track: TidalTrack, onClick: (TidalTrack) -> Unit) {
    ItemRow(
        artworkUrl = track.artworkUrl,
        title = track.title,
        subtitle = listOf(track.artists, track.album).filter { it.isNotBlank() }.joinToString(" · "),
        badge = track.qualityLabel,
        trailing = if (track.durationMs > 0) formatDuration(track.durationMs) else null,
        onClick = { onClick(track) },
    )
}

@Composable
private fun AlbumRow(album: TidalAlbum, onClick: (TidalAlbum) -> Unit) {
    ItemRow(
        artworkUrl = album.artworkUrl,
        title = album.title,
        subtitle = album.artists,
        badge = null,
        trailing = if (album.numberOfItems > 0) "${album.numberOfItems} Titel" else null,
        onClick = { onClick(album) },
    )
}

@Composable
private fun PlaylistRow(playlist: TidalPlaylist, onClick: (TidalPlaylist) -> Unit) {
    ItemRow(
        artworkUrl = playlist.artworkUrl,
        title = playlist.name,
        subtitle = playlist.description,
        badge = null,
        trailing = if (playlist.numberOfItems > 0) "${playlist.numberOfItems} Titel" else null,
        onClick = { onClick(playlist) },
    )
}

@Composable
private fun ItemRow(
    artworkUrl: String?,
    title: String,
    subtitle: String,
    badge: String?,
    trailing: String?,
    onClick: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RemoteImage(artworkUrl, 48.dp)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.Medium)
            if (subtitle.isNotBlank()) {
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        badge?.let {
            Spacer(Modifier.width(8.dp))
            Text(it, style = MaterialTheme.typography.labelSmall, color = Amber)
        }
        trailing?.let {
            Spacer(Modifier.width(8.dp))
            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun ErrorBanner(message: String, onDismiss: () -> Unit) {
    Card(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
    ) {
        Row(Modifier.padding(start = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                message,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onErrorContainer,
                modifier = Modifier
                    .weight(1f)
                    .padding(vertical = 8.dp),
            )
            TextButton(onClick = onDismiss) { Text("OK") }
        }
    }
    HorizontalDivider(color = MaterialTheme.colorScheme.outline)
}

private fun Context.openUrl(url: String) {
    try {
        startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
    } catch (_: ActivityNotFoundException) {
        // Kein Browser/TIDAL installiert – nichts zu tun.
    }
}
