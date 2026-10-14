package de.konan.hiresplayer.playback

enum class OutputMode(val title: String, val description: String) {
    STANDARD(
        "Standard + EQ",
        "Android-Mixer, parametrischer EQ aktiv. Kompatibel mit jedem Ausgabegerät.",
    ),
    BIT_PERFECT(
        "Bit-Perfect (USB, Android 14+)",
        "Ganzzahliger PCM-Pfad ohne DSP. 16-bit-Quellen gehen unverändert zum DAC; " +
            "andere Quellen laufen mit nativer Abtastrate.",
    ),
    HI_RES(
        "Hi-Res Float (native Abtastrate)",
        "32-bit-Float-Pfad ohne DSP, kein Resampling auf 48 kHz. Ideal für 24-bit/Hi-Res-Dateien.",
    ),
}

sealed interface MixerStatus {
    val headline: String
    val detail: String

    data class BitPerfect(val sampleRate: Int, val kind: PcmKind) : MixerStatus {
        override val headline = "Bit-Perfect"
        override val detail = "${formatSampleRate(sampleRate)} · ${kind.label} unverändert zum DAC"
    }

    data class NativeRate(val sampleRate: Int, val kind: PcmKind) : MixerStatus {
        override val headline = "Native Abtastrate"
        override val detail = "Mixer läuft mit ${formatSampleRate(sampleRate)} · ${kind.label}, kein Resampling"
    }

    data class SystemMixer(override val detail: String) : MixerStatus {
        override val headline = "Android-Mixer"
    }

    data object Idle : MixerStatus {
        override val headline = "Bereit"
        override val detail = "Noch keine Wiedergabe"
    }
}
