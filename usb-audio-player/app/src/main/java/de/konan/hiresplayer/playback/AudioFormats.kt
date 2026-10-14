package de.konan.hiresplayer.playback

import android.media.AudioFormat
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi

/** Vereinheitlicht Media3- (C.ENCODING_*) und Android-Encodings (AudioFormat.ENCODING_*). */
enum class PcmKind(val label: String, val rank: Int) {
    I16("16-bit", 1),
    I24("24-bit", 2),
    I32("32-bit int", 3),
    FLOAT("32-bit float", 4),
    OTHER("unbekannt", 0),
}

@OptIn(UnstableApi::class)
fun pcmKindOf(encoding: Int): PcmKind {
    if (encoding == C.ENCODING_PCM_16BIT || encoding == AudioFormat.ENCODING_PCM_16BIT) return PcmKind.I16
    if (encoding == C.ENCODING_PCM_FLOAT || encoding == AudioFormat.ENCODING_PCM_FLOAT) return PcmKind.FLOAT
    if (encoding == C.ENCODING_PCM_24BIT || encoding == AudioFormat.ENCODING_PCM_24BIT_PACKED) return PcmKind.I24
    if (encoding == C.ENCODING_PCM_32BIT || encoding == AudioFormat.ENCODING_PCM_32BIT) return PcmKind.I32
    return PcmKind.OTHER
}

fun formatSampleRate(hz: Int): String =
    if (hz <= 0) "–" else if (hz % 1000 == 0) "${hz / 1000} kHz" else "%.1f kHz".format(hz / 1000.0)

fun codecLabel(mimeType: String?): String = when (mimeType) {
    null -> "–"
    "audio/flac" -> "FLAC"
    "audio/alac" -> "ALAC"
    "audio/raw" -> "PCM/WAV"
    "audio/mpeg" -> "MP3"
    "audio/mp4a-latm" -> "AAC"
    "audio/opus" -> "Opus"
    "audio/vorbis" -> "Vorbis"
    "audio/ac3" -> "AC-3"
    "audio/eac3" -> "E-AC-3"
    else -> mimeType.removePrefix("audio/").uppercase()
}
