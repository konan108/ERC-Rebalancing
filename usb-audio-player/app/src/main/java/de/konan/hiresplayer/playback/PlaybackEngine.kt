package de.konan.hiresplayer.playback

import android.content.Context
import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DecoderReuseEvaluation
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.RenderersFactory
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.exoplayer.audio.MediaCodecAudioRenderer
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import de.konan.hiresplayer.dsp.EqSettings
import de.konan.hiresplayer.dsp.ParametricEqAudioProcessor
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

data class SignalPath(
    val codec: String = "–",
    val sourceSampleRate: Int = 0,
    val sourceChannels: Int = 0,
    val bitrate: Int = 0,
    val decodedKind: PcmKind? = null,
    val outputKind: PcmKind? = null,
    val eqApplied: Boolean = false,
    val mixer: MixerStatus = MixerStatus.Idle,
)

data class PlayerUiState(
    val isPlaying: Boolean = false,
    val title: String = "",
    val artist: String = "",
    val album: String = "",
    val artworkUri: Uri? = null,
    val durationMs: Long = 0,
    val currentMediaId: String? = null,
    val hasNext: Boolean = false,
    val hasPrevious: Boolean = false,
    val shuffle: Boolean = false,
    val repeatMode: Int = Player.REPEAT_MODE_OFF,
)

/**
 * Besitzt den ExoPlayer und den kompletten Audio-Pfad. Bei einem Wechsel des
 * Ausgabemodus wird der Player neu aufgebaut (die Sink-Konfiguration ist fix),
 * Warteschlange und Position bleiben erhalten.
 */
@OptIn(UnstableApi::class)
class PlaybackEngine(private val context: Context) {
    private val prefs = context.getSharedPreferences("engine", Context.MODE_PRIVATE)

    val usb = UsbOutputManager(context)
    private val eqProcessor = ParametricEqAudioProcessor()

    private val _mode = MutableStateFlow(
        runCatching { OutputMode.valueOf(prefs.getString(KEY_MODE, null) ?: "") }.getOrDefault(OutputMode.STANDARD)
    )
    val mode: StateFlow<OutputMode> = _mode.asStateFlow()

    private val _eq = MutableStateFlow(EqSettings.fromJson(prefs.getString(KEY_EQ, null)))
    val eq: StateFlow<EqSettings> = _eq.asStateFlow()

    private val _signal = MutableStateFlow(SignalPath())
    val signal: StateFlow<SignalPath> = _signal.asStateFlow()

    private val _playerState = MutableStateFlow(PlayerUiState())
    val playerState: StateFlow<PlayerUiState> = _playerState.asStateFlow()

    private val playerChangeListeners = mutableListOf<(Player) -> Unit>()

    var player: ExoPlayer = buildPlayer(_mode.value)
        private set

    init {
        eqProcessor.updateSettings(_eq.value)
    }

    fun addPlayerChangeListener(listener: (Player) -> Unit) {
        playerChangeListeners += listener
    }

    fun removePlayerChangeListener(listener: (Player) -> Unit) {
        playerChangeListeners -= listener
    }

    fun playQueue(items: List<MediaItem>, startIndex: Int) {
        player.setMediaItems(items, startIndex, 0L)
        player.prepare()
        player.play()
    }

    fun setEq(settings: EqSettings) {
        _eq.value = settings
        eqProcessor.updateSettings(settings)
        prefs.edit().putString(KEY_EQ, settings.toJson()).apply()
        _signal.update { it.copy(eqApplied = _mode.value == OutputMode.STANDARD && settings.enabled) }
    }

    fun setMode(newMode: OutputMode) {
        if (newMode == _mode.value) return
        _mode.value = newMode
        prefs.edit().putString(KEY_MODE, newMode.name).apply()

        val old = player
        val items = (0 until old.mediaItemCount).map { old.getMediaItemAt(it) }
        val index = old.currentMediaItemIndex
        val position = old.currentPosition
        val playWhenReady = old.playWhenReady
        val shuffle = old.shuffleModeEnabled
        val repeat = old.repeatMode
        old.release()
        usb.clear()

        player = buildPlayer(newMode).apply {
            shuffleModeEnabled = shuffle
            repeatMode = repeat
            if (items.isNotEmpty()) {
                setMediaItems(items, index, position)
                this.playWhenReady = playWhenReady
                prepare()
            }
        }
        _signal.value = SignalPath()
        playerChangeListeners.forEach { it(player) }
    }

    private fun buildPlayer(mode: OutputMode): ExoPlayer {
        val sink = DefaultAudioSink.Builder(context)
            .setEnableFloatOutput(mode == OutputMode.HI_RES)
            .setAudioProcessors(if (mode == OutputMode.STANDARD) arrayOf(eqProcessor) else emptyArray())
            .build()
        val audioSink = MixerAwareAudioSink(sink) { format -> onSinkConfigured(mode, format) }

        val renderersFactory = RenderersFactory { handler, _, audioListener, _, _ ->
            arrayOf(MediaCodecAudioRenderer(context, MediaCodecSelector.DEFAULT, handler, audioListener, audioSink))
        }

        val attributes = AudioAttributes.Builder()
            .setUsage(C.USAGE_MEDIA)
            .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
            .build()

        return ExoPlayer.Builder(context, renderersFactory)
            .setAudioAttributes(attributes, true)
            .setHandleAudioBecomingNoisy(true)
            .build()
            .also { p ->
                p.addListener(object : Player.Listener {
                    override fun onEvents(player: Player, events: Player.Events) = publishPlayerState(player)
                })
                p.addAnalyticsListener(object : AnalyticsListener {
                    override fun onAudioInputFormatChanged(
                        eventTime: AnalyticsListener.EventTime,
                        format: Format,
                        decoderReuseEvaluation: DecoderReuseEvaluation?,
                    ) {
                        _signal.update {
                            it.copy(
                                codec = codecLabel(format.sampleMimeType),
                                sourceSampleRate = format.sampleRate,
                                sourceChannels = format.channelCount,
                                bitrate = maxOf(format.bitrate, format.averageBitrate),
                            )
                        }
                    }
                })
            }
    }

    /** Läuft auf dem Playback-Thread, bevor der AudioTrack erzeugt wird. */
    private fun onSinkConfigured(mode: OutputMode, format: Format) {
        val decoded = pcmKindOf(format.pcmEncoding)
        val output: PcmKind?
        val mixer: MixerStatus
        when (mode) {
            OutputMode.STANDARD -> {
                usb.clear()
                output = PcmKind.I16
                mixer = MixerStatus.SystemMixer("Standardmodus: Android mischt und resampelt")
            }
            OutputMode.BIT_PERFECT -> {
                // Nur 16-bit-PCM durchläuft den Integer-Pfad garantiert unverändert;
                // für andere Formate entscheidet Media3 über die Wandlung.
                output = PcmKind.I16.takeIf { decoded == it }
                mixer = usb.configureFor(format.sampleRate, format.channelCount, output)
            }
            OutputMode.HI_RES -> {
                output = if (decoded == PcmKind.I16) PcmKind.I16 else PcmKind.FLOAT
                mixer = usb.configureFor(format.sampleRate, format.channelCount, output)
            }
        }
        _signal.update {
            it.copy(
                decodedKind = decoded,
                outputKind = output,
                eqApplied = mode == OutputMode.STANDARD && _eq.value.enabled,
                mixer = mixer,
            )
        }
    }

    private fun publishPlayerState(p: Player) {
        val md = p.mediaMetadata
        _playerState.value = PlayerUiState(
            isPlaying = p.isPlaying,
            title = md.title?.toString().orEmpty(),
            artist = md.artist?.toString().orEmpty(),
            album = md.albumTitle?.toString().orEmpty(),
            artworkUri = md.artworkUri,
            durationMs = p.duration.takeIf { it != C.TIME_UNSET } ?: 0L,
            currentMediaId = p.currentMediaItem?.mediaId,
            hasNext = p.hasNextMediaItem(),
            hasPrevious = p.hasPreviousMediaItem(),
            shuffle = p.shuffleModeEnabled,
            repeatMode = p.repeatMode,
        )
    }

    private companion object {
        const val KEY_MODE = "output_mode"
        const val KEY_EQ = "eq"
    }
}
