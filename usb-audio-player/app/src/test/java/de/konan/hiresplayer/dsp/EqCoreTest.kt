package de.konan.hiresplayer.dsp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.sin
import kotlin.math.sqrt

class EqCoreTest {

    @Test
    fun flatSettingsAreBitTransparent() {
        val core = EqCore(44_100, 2)
        core.apply(EqSettings.flat().copy(enabled = true))
        val input = FloatArray(2048) { i -> sin(i * 0.05).toFloat() * 0.5f }
        val buffer = input.copyOf()
        core.process(buffer, buffer.size / 2)
        input.indices.forEach { assertEquals(input[it], buffer[it], 0f) }
    }

    @Test
    fun peakFilterHitsTargetGainAtCenterFrequency() {
        val band = EqBand(FilterType.PEAK, 1000.0, 6.0, 1.0)
        val db = BiquadCoefficients.design(band, 48_000).magnitudeDb(1000.0, 48_000)
        assertEquals(6.0, db, 0.01)
    }

    @Test
    fun shelvesReachGainAtTheExtremes() {
        val low = BiquadCoefficients.design(EqBand(FilterType.LOW_SHELF, 100.0, -4.0, 0.71), 48_000)
        val high = BiquadCoefficients.design(EqBand(FilterType.HIGH_SHELF, 8000.0, 3.0, 0.71), 48_000)
        assertEquals(-4.0, low.magnitudeDb(10.0, 48_000), 0.1)
        assertEquals(3.0, high.magnitudeDb(22_000.0, 48_000), 0.2)
    }

    @Test
    fun processedSineMatchesAnalyticResponse() {
        val fs = 48_000
        val f = 1000.0
        val settings = EqSettings(true, 0.0, listOf(EqBand(FilterType.PEAK, f, -9.0, 2.0)))
        val core = EqCore(fs, 1)
        core.apply(settings)
        val n = fs
        val buffer = FloatArray(n) { i -> sin(2 * PI * f * i / fs).toFloat() * 0.5f }
        core.process(buffer, n)
        // Einschwingen ignorieren, RMS der zweiten Hälfte messen.
        val tail = buffer.copyOfRange(n / 2, n)
        val rms = sqrt(tail.sumOf { (it * it).toDouble() } / tail.size)
        val measuredDb = 20 * log10(rms / (0.5 / sqrt(2.0)))
        assertTrue("gemessen $measuredDb dB", abs(measuredDb - (-9.0)) < 0.1)
    }

    @Test
    fun settingsSurviveJsonRoundTrip() {
        val s = EqSettings.flat().copy(enabled = true, preampDb = -3.5)
        assertEquals(s, EqSettings.fromJson(s.toJson()))
    }
}
