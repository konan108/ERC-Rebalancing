package de.konan.hiresplayer.playback

import androidx.media3.common.Format
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.ForwardingAudioSink

/**
 * Meldet das dekodierte PCM-Format, bevor der AudioTrack (neu) erzeugt wird.
 * So kann der USB-Mixer passend zum Track konfiguriert werden.
 */
@UnstableApi
class MixerAwareAudioSink(
    sink: AudioSink,
    private val onConfigure: (Format) -> Unit,
) : ForwardingAudioSink(sink) {
    override fun configure(inputFormat: Format, specifiedBufferSize: Int, outputChannels: IntArray?) {
        onConfigure(inputFormat)
        super.configure(inputFormat, specifiedBufferSize, outputChannels)
    }
}
