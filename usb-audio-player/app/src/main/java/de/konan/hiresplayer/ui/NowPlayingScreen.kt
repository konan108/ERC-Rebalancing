package de.konan.hiresplayer.ui

import android.content.ContentUris
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.util.Size
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.RepeatOne
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.Player
import de.konan.hiresplayer.playback.MixerStatus
import de.konan.hiresplayer.playback.OutputMode
import de.konan.hiresplayer.playback.PlaybackEngine
import de.konan.hiresplayer.playback.SignalPath
import de.konan.hiresplayer.playback.UsbDacInfo
import de.konan.hiresplayer.playback.formatSampleRate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

@Composable
fun NowPlayingScreen(engine: PlaybackEngine, modifier: Modifier = Modifier) {
    val state by engine.playerState.collectAsStateWithLifecycle()
    val signal by engine.signal.collectAsStateWithLifecycle()
    val mode by engine.mode.collectAsStateWithLifecycle()
    val dac by engine.usb.dac.collectAsStateWithLifecycle()

    if (state.currentMediaId == null) {
        Box(modifier, contentAlignment = Alignment.Center) {
            Text("Wähle einen Titel in der Bibliothek", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        return
    }

    Column(
        modifier
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp, vertical = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Artwork(state.currentMediaId, state.artworkUri)
        Spacer(Modifier.height(20.dp))
        Text(
            state.title,
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.SemiBold,
            textAlign = TextAlign.Center,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            listOf(state.artist, state.album).filter { it.isNotBlank() }.joinToString(" · "),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(12.dp))
        SeekBar(engine, state.isPlaying, state.durationMs, state.currentMediaId)
        Transport(engine)
        Spacer(Modifier.height(16.dp))
        SignalPathCard(signal, mode, dac)
    }
}

@Composable
private fun Artwork(mediaId: String?, artworkUri: Uri?) {
    val context = LocalContext.current
    var bitmap by remember { mutableStateOf<Bitmap?>(null) }
    LaunchedEffect(mediaId) {
        bitmap = null
        bitmap = withContext(Dispatchers.IO) {
            runCatching {
                val id = mediaId?.toLongOrNull()
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && id != null) {
                    val uri = ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, id)
                    context.contentResolver.loadThumbnail(uri, Size(768, 768), null)
                } else {
                    artworkUri?.let { u -> context.contentResolver.openInputStream(u)?.use { BitmapFactory.decodeStream(it) } }
                }
            }.getOrNull()
        }
    }
    Box(
        Modifier
            .fillMaxWidth(0.85f)
            .aspectRatio(1f)
            .clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center,
    ) {
        val b = bitmap
        if (b != null) {
            Image(b.asImageBitmap(), contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxWidth())
        } else {
            Icon(Icons.Filled.MusicNote, contentDescription = null, modifier = Modifier.size(96.dp), tint = Muted)
        }
    }
}

@Composable
private fun SeekBar(engine: PlaybackEngine, isPlaying: Boolean, durationMs: Long, mediaId: String?) {
    var position by remember { mutableLongStateOf(0L) }
    var dragging by remember { mutableStateOf(false) }
    var dragValue by remember { mutableFloatStateOf(0f) }

    LaunchedEffect(isPlaying, mediaId) {
        while (true) {
            if (!dragging) position = engine.player.currentPosition
            if (!isPlaying) break
            delay(500)
        }
    }

    val fraction = if (durationMs > 0) (position.toFloat() / durationMs).coerceIn(0f, 1f) else 0f
    Slider(
        value = if (dragging) dragValue else fraction,
        onValueChange = {
            dragging = true
            dragValue = it
        },
        onValueChangeFinished = {
            val target = (dragValue * durationMs).toLong()
            engine.player.seekTo(target)
            position = target
            dragging = false
        },
    )
    Row(Modifier.fillMaxWidth()) {
        val shown = if (dragging) (dragValue * durationMs).toLong() else position
        Text(formatDuration(shown), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.weight(1f))
        Text(formatDuration(durationMs), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun Transport(engine: PlaybackEngine) {
    val state by engine.playerState.collectAsStateWithLifecycle()
    val p = engine.player
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = { p.shuffleModeEnabled = !p.shuffleModeEnabled }) {
            Icon(
                Icons.Filled.Shuffle,
                contentDescription = "Zufall",
                tint = if (state.shuffle) MaterialTheme.colorScheme.primary else Muted,
            )
        }
        IconButton(onClick = { p.seekToPrevious() }) {
            Icon(Icons.Filled.SkipPrevious, contentDescription = "Zurück", modifier = Modifier.size(36.dp))
        }
        FilledIconButton(
            onClick = { if (state.isPlaying) p.pause() else p.play() },
            modifier = Modifier.size(72.dp),
            shape = CircleShape,
            colors = IconButtonDefaults.filledIconButtonColors(containerColor = MaterialTheme.colorScheme.primary),
        ) {
            Icon(
                if (state.isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                contentDescription = "Play/Pause",
                modifier = Modifier.size(40.dp),
            )
        }
        IconButton(onClick = { p.seekToNextMediaItem() }, enabled = state.hasNext) {
            Icon(Icons.Filled.SkipNext, contentDescription = "Weiter", modifier = Modifier.size(36.dp))
        }
        IconButton(onClick = {
            p.repeatMode = when (p.repeatMode) {
                Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ALL
                Player.REPEAT_MODE_ALL -> Player.REPEAT_MODE_ONE
                else -> Player.REPEAT_MODE_OFF
            }
        }) {
            Icon(
                if (state.repeatMode == Player.REPEAT_MODE_ONE) Icons.Filled.RepeatOne else Icons.Filled.Repeat,
                contentDescription = "Wiederholen",
                tint = if (state.repeatMode != Player.REPEAT_MODE_OFF) MaterialTheme.colorScheme.primary else Muted,
            )
        }
    }
}

fun mixerColor(status: MixerStatus): Color = when (status) {
    is MixerStatus.BitPerfect -> BitPerfectGreen
    is MixerStatus.NativeRate -> NativeBlue
    else -> Muted
}

/** Zeigt den kompletten Weg vom Dateiformat bis zum Ausgabegerät – das Herzstück eines Audiophilen-Players. */
@Composable
fun SignalPathCard(signal: SignalPath, mode: OutputMode, dac: UsbDacInfo?) {
    Card(
        Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier
                        .size(10.dp)
                        .clip(CircleShape)
                        .background(mixerColor(signal.mixer))
                )
                Spacer(Modifier.width(8.dp))
                Text(signal.mixer.headline, fontWeight = FontWeight.Bold, color = mixerColor(signal.mixer))
                Spacer(Modifier.weight(1f))
                Text(mode.title, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Spacer(Modifier.height(12.dp))
            val source = buildList {
                add(signal.codec)
                if (signal.sourceSampleRate > 0) add(formatSampleRate(signal.sourceSampleRate))
                if (signal.sourceChannels > 0) add(if (signal.sourceChannels == 2) "Stereo" else "${signal.sourceChannels} ch")
                if (signal.bitrate > 0) add("${signal.bitrate / 1000} kbit/s")
            }.joinToString(" · ")
            PathRow("Quelle", source)
            PathRow("Decoder", signal.decodedKind?.label ?: "–")
            PathRow("DSP", if (signal.eqApplied) "Parametrischer EQ" else "aus (unverändert)")
            PathRow("Ausgabe-PCM", signal.outputKind?.label ?: "von Media3 bestimmt")
            PathRow("Mixer", signal.mixer.detail)
            PathRow("Gerät", dac?.name ?: "Interner Ausgang / Bluetooth")
        }
    }
}

@Composable
private fun PathRow(label: String, value: String) {
    Row(Modifier.padding(vertical = 3.dp)) {
        Text(
            label,
            modifier = Modifier.width(104.dp),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(value, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
    }
}
