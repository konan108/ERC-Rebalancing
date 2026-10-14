package de.konan.hiresplayer.dsp

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import java.nio.ByteBuffer
import kotlin.math.roundToInt

/**
 * Media3-AudioProcessor für den parametrischen EQ (16-bit und Float-PCM).
 * Einstellungen können jederzeit aus dem UI-Thread gesetzt werden.
 */
@UnstableApi
class ParametricEqAudioProcessor : BaseAudioProcessor() {
    @Volatile
    private var pendingSettings: EqSettings = EqSettings.flat()
    private var appliedSettings: EqSettings? = null
    private var core: EqCore? = null
    private var scratch = FloatArray(0)

    fun updateSettings(settings: EqSettings) {
        pendingSettings = settings
    }

    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        if (inputAudioFormat.encoding != C.ENCODING_PCM_16BIT && inputAudioFormat.encoding != C.ENCODING_PCM_FLOAT) {
            throw AudioProcessor.UnhandledAudioFormatException(inputAudioFormat)
        }
        return inputAudioFormat
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        val size = inputBuffer.remaining()
        if (size == 0) return
        val output = replaceOutputBuffer(size)
        val settings = pendingSettings
        val format = inputAudioFormat
        if (!settings.enabled) {
            output.put(inputBuffer)
            output.flip()
            return
        }

        val engine = core ?: EqCore(format.sampleRate, format.channelCount).also { core = it }
        if (settings != appliedSettings) {
            engine.apply(settings)
            appliedSettings = settings
        }

        val isFloat = format.encoding == C.ENCODING_PCM_FLOAT
        val sampleCount = if (isFloat) size / 4 else size / 2
        if (scratch.size < sampleCount) scratch = FloatArray(sampleCount)
        val samples = scratch

        if (isFloat) {
            for (i in 0 until sampleCount) samples[i] = inputBuffer.getFloat()
        } else {
            for (i in 0 until sampleCount) samples[i] = inputBuffer.getShort() / 32768f
        }

        engine.process(samples, sampleCount / format.channelCount)

        if (isFloat) {
            for (i in 0 until sampleCount) output.putFloat(samples[i])
        } else {
            for (i in 0 until sampleCount) {
                val v = (samples[i] * 32768f).roundToInt().coerceIn(-32768, 32767)
                output.putShort(v.toShort())
            }
        }
        output.flip()
    }

    override fun onFlush() {
        core = if (inputAudioFormat.sampleRate > 0) EqCore(inputAudioFormat.sampleRate, inputAudioFormat.channelCount) else null
        appliedSettings = null
    }

    override fun onReset() {
        core = null
        appliedSettings = null
        scratch = FloatArray(0)
    }
}
