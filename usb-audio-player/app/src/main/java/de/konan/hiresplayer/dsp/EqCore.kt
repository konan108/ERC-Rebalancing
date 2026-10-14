package de.konan.hiresplayer.dsp

import kotlin.math.pow

/**
 * Kaskade aus Biquads (Transposed Direct Form II, Double-Präzision) für
 * interleavte Float-Samples. Unabhängig von Android, damit unit-testbar.
 */
class EqCore(private val sampleRate: Int, private val channelCount: Int) {
    private var coefficients: Array<BiquadCoefficients> = emptyArray()
    private var preampGain = 1.0
    private var z1 = DoubleArray(0)
    private var z2 = DoubleArray(0)

    fun apply(settings: EqSettings) {
        val active = settings.bands.filter { it.gainDb != 0.0 }
        val newCoefficients = active.map { BiquadCoefficients.design(it, sampleRate) }.toTypedArray()
        if (newCoefficients.size != coefficients.size) {
            z1 = DoubleArray(newCoefficients.size * channelCount)
            z2 = DoubleArray(newCoefficients.size * channelCount)
        }
        coefficients = newCoefficients
        preampGain = 10.0.pow(settings.preampDb / 20.0)
    }

    fun reset() {
        z1.fill(0.0)
        z2.fill(0.0)
    }

    /** Verarbeitet [frameCount] Frames in-place. */
    fun process(samples: FloatArray, frameCount: Int) {
        val bandCount = coefficients.size
        var i = 0
        for (frame in 0 until frameCount) {
            for (ch in 0 until channelCount) {
                var x = samples[i] * preampGain
                for (b in 0 until bandCount) {
                    val c = coefficients[b]
                    val s = b * channelCount + ch
                    val y = c.b0 * x + z1[s]
                    z1[s] = c.b1 * x - c.a1 * y + z2[s]
                    z2[s] = c.b2 * x - c.a2 * y
                    x = y
                }
                samples[i] = x.toFloat()
                i++
            }
        }
    }
}
