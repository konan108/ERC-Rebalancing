package de.konan.hiresplayer.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Equalizer
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.Usb
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import de.konan.hiresplayer.engine
import de.konan.hiresplayer.playback.PlaybackEngine

private enum class Tab(val label: String, val icon: ImageVector) {
    LIBRARY("Bibliothek", Icons.Filled.LibraryMusic),
    PLAYER("Wiedergabe", Icons.Filled.PlayCircle),
    OUTPUT("Ausgabe", Icons.Filled.Usb),
    EQ("EQ", Icons.Filled.Equalizer),
}

private val audioPermission =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) Manifest.permission.READ_MEDIA_AUDIO
    else Manifest.permission.READ_EXTERNAL_STORAGE

@Composable
fun AppRoot(vm: MainViewModel = viewModel()) {
    val context = LocalContext.current
    val engine = context.engine
    var tab by rememberSaveable { mutableStateOf(Tab.LIBRARY) }
    var hasPermission by rememberSaveable {
        mutableStateOf(ContextCompat.checkSelfPermission(context, audioPermission) == PackageManager.PERMISSION_GRANTED)
    }
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        hasPermission = result[audioPermission] == true
    }
    val requestPermissions = {
        val permissions = buildList {
            add(audioPermission)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) add(Manifest.permission.POST_NOTIFICATIONS)
        }
        permissionLauncher.launch(permissions.toTypedArray())
    }

    LaunchedEffect(hasPermission) {
        if (hasPermission) vm.loadLibrary() else requestPermissions()
    }

    val playerState by engine.playerState.collectAsStateWithLifecycle()

    Scaffold(
        bottomBar = {
            Column {
                if (tab != Tab.PLAYER && playerState.currentMediaId != null) {
                    MiniPlayer(engine, onOpen = { tab = Tab.PLAYER })
                }
                NavigationBar {
                    Tab.entries.forEach { t ->
                        NavigationBarItem(
                            selected = tab == t,
                            onClick = { tab = t },
                            icon = { Icon(t.icon, contentDescription = t.label) },
                            label = { Text(t.label) },
                        )
                    }
                }
            }
        },
    ) { padding ->
        val modifier = Modifier
            .fillMaxSize()
            .padding(padding)
        when (tab) {
            Tab.LIBRARY -> LibraryScreen(vm, engine, hasPermission, requestPermissions, modifier)
            Tab.PLAYER -> NowPlayingScreen(engine, modifier)
            Tab.OUTPUT -> OutputScreen(engine, modifier)
            Tab.EQ -> EqScreen(engine, onSwitchToStandard = { tab = Tab.OUTPUT }, modifier = modifier)
        }
    }
}

@Composable
private fun MiniPlayer(engine: PlaybackEngine, onOpen: () -> Unit) {
    val state by engine.playerState.collectAsStateWithLifecycle()
    val signal by engine.signal.collectAsStateWithLifecycle()
    Column {
        HorizontalDivider(color = MaterialTheme.colorScheme.outline)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onOpen)
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(state.title, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    "${state.artist} · ${signal.mixer.headline}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.width(8.dp))
            IconButton(onClick = { if (state.isPlaying) engine.player.pause() else engine.player.play() }) {
                Icon(if (state.isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow, contentDescription = "Play/Pause")
            }
            IconButton(onClick = { engine.player.seekToNextMediaItem() }, enabled = state.hasNext) {
                Icon(Icons.Filled.SkipNext, contentDescription = "Weiter")
            }
        }
    }
}
