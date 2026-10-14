package de.konan.hiresplayer.playback

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.media.AudioMixerAttributes
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.annotation.RequiresApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class MixerOption(
    val sampleRate: Int,
    val kind: PcmKind,
    val channelCount: Int,
    val bitPerfect: Boolean,
)

data class UsbDacInfo(
    val name: String,
    val sampleRates: List<Int>,
    val channelCounts: List<Int>,
    val encodings: List<PcmKind>,
    val mixerOptions: List<MixerOption>,
) {
    val supportsBitPerfect: Boolean get() = mixerOptions.any { it.bitPerfect }
}

/**
 * Erkennt USB-DACs und konfiguriert auf Android 14+ den Mixer des USB-Ausgangs
 * (bit-perfect bzw. native Abtastrate) über AudioManager.setPreferredMixerAttributes.
 */
class UsbOutputManager(context: Context) {
    private val audioManager = context.getSystemService(AudioManager::class.java)
    private val _dac = MutableStateFlow<UsbDacInfo?>(null)
    val dac: StateFlow<UsbDacInfo?> = _dac.asStateFlow()

    val mediaAttributes: AudioAttributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_MEDIA)
        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
        .build()

    private val callback = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>) = refresh()
        override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>) = refresh()
    }

    init {
        audioManager.registerAudioDeviceCallback(callback, Handler(Looper.getMainLooper()))
        refresh()
    }

    val isMixerApiAvailable: Boolean get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE

    fun refresh() {
        val device = findUsbDevice()
        _dac.value = device?.let { d ->
            UsbDacInfo(
                name = d.productName?.toString()?.takeIf { it.isNotBlank() } ?: "USB-Audio",
                sampleRates = d.sampleRates.sorted().distinct(),
                channelCounts = d.channelCounts.sorted().distinct(),
                encodings = d.encodings.map(::pcmKindOf).filter { it != PcmKind.OTHER }.distinct().sortedBy { it.rank },
                mixerOptions = if (isMixerApiAvailable) Api34.mixerOptions(audioManager, d) else emptyList(),
            )
        }
    }

    private fun findUsbDevice(): AudioDeviceInfo? =
        audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS).firstOrNull {
            it.type == AudioDeviceInfo.TYPE_USB_DEVICE || it.type == AudioDeviceInfo.TYPE_USB_HEADSET
        }

    /**
     * Wählt die Mixer-Konfiguration für den nächsten AudioTrack.
     * [exactKind] ist das PCM-Format, das garantiert unverändert beim AudioTrack ankommt
     * (null = nicht garantiert, dann kein Bit-Perfect-Versuch).
     */
    fun configureFor(sampleRate: Int, channelCount: Int, exactKind: PcmKind?): MixerStatus {
        val device = findUsbDevice() ?: run {
            clear()
            return MixerStatus.SystemMixer("Kein USB-DAC verbunden")
        }
        if (!isMixerApiAvailable) {
            return MixerStatus.SystemMixer("Mixer-Steuerung erst ab Android 14 verfügbar")
        }
        return Api34.configure(audioManager, device, mediaAttributes, sampleRate, channelCount, exactKind)
    }

    fun clear() {
        if (!isMixerApiAvailable) return
        val device = findUsbDevice() ?: return
        Api34.clear(audioManager, device, mediaAttributes)
    }

    @RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
    private object Api34 {
        fun mixerOptions(am: AudioManager, device: AudioDeviceInfo): List<MixerOption> =
            am.getSupportedMixerAttributes(device).map { it.toOption() }.distinct()

        fun configure(
            am: AudioManager,
            device: AudioDeviceInfo,
            attributes: AudioAttributes,
            sampleRate: Int,
            channelCount: Int,
            exactKind: PcmKind?,
        ): MixerStatus {
            val supported = am.getSupportedMixerAttributes(device)
            val matchingRate = supported.filter {
                it.format.sampleRate == sampleRate && Integer.bitCount(it.format.channelMask) == channelCount
            }

            if (exactKind != null) {
                val bitPerfect = matchingRate.firstOrNull {
                    it.mixerBehavior == AudioMixerAttributes.MIXER_BEHAVIOR_BIT_PERFECT &&
                        pcmKindOf(it.format.encoding) == exactKind
                }
                if (bitPerfect != null && trySet(am, device, attributes, bitPerfect)) {
                    return MixerStatus.BitPerfect(sampleRate, exactKind)
                }
            }

            val native = matchingRate
                .filter { it.mixerBehavior == AudioMixerAttributes.MIXER_BEHAVIOR_DEFAULT }
                .maxByOrNull { pcmKindOf(it.format.encoding).rank }
            if (native != null && trySet(am, device, attributes, native)) {
                return MixerStatus.NativeRate(sampleRate, pcmKindOf(native.format.encoding))
            }

            clear(am, device, attributes)
            return MixerStatus.SystemMixer("DAC bietet ${formatSampleRate(sampleRate)} nicht als Mixer-Format an")
        }

        fun clear(am: AudioManager, device: AudioDeviceInfo, attributes: AudioAttributes) {
            runCatching { am.clearPreferredMixerAttributes(attributes, device) }
        }

        private fun trySet(
            am: AudioManager,
            device: AudioDeviceInfo,
            attributes: AudioAttributes,
            mixer: AudioMixerAttributes,
        ): Boolean = try {
            am.setPreferredMixerAttributes(attributes, device, mixer)
        } catch (e: RuntimeException) {
            Log.w(TAG, "setPreferredMixerAttributes fehlgeschlagen", e)
            false
        }

        private fun AudioMixerAttributes.toOption() = MixerOption(
            sampleRate = format.sampleRate,
            kind = pcmKindOf(format.encoding),
            channelCount = Integer.bitCount(format.channelMask),
            bitPerfect = mixerBehavior == AudioMixerAttributes.MIXER_BEHAVIOR_BIT_PERFECT,
        )
    }

    private companion object {
        const val TAG = "UsbOutputManager"
    }
}
