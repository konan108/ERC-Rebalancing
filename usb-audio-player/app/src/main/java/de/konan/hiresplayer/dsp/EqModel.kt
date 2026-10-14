package de.konan.hiresplayer.dsp

import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

enum class FilterType(val label: String) {
    PEAK("Peak"),
    LOW_SHELF("Low-Shelf"),
    HIGH_SHELF("High-Shelf"),
}

data class EqBand(
    val type: FilterType,
    val frequencyHz: Double,
    val gainDb: Double,
    val q: Double,
)

data class EqSettings(
    val enabled: Boolean,
    val preampDb: Double,
    val bands: List<EqBand>,
) {
    fun toJson(): String = JSONObject()
        .put("enabled", enabled)
        .put("preampDb", preampDb)
        .put("bands", JSONArray().apply {
            bands.forEach {
                put(
                    JSONObject()
                        .put("type", it.type.name)
                        .put("f", it.frequencyHz)
                        .put("g", it.gainDb)
                        .put("q", it.q)
                )
            }
        })
        .toString()

    companion object {
        val DEFAULT_FREQUENCIES = listOf(31.0, 62.0, 125.0, 250.0, 500.0, 1000.0, 2000.0, 4000.0, 8000.0, 16000.0)

        fun flat(): EqSettings = EqSettings(
            enabled = false,
            preampDb = 0.0,
            bands = DEFAULT_FREQUENCIES.mapIndexed { i, f ->
                val type = when (i) {
                    0 -> FilterType.LOW_SHELF
                    DEFAULT_FREQUENCIES.lastIndex -> FilterType.HIGH_SHELF
                    else -> FilterType.PEAK
                }
                EqBand(type, f, 0.0, if (type == FilterType.PEAK) 1.41 else 0.71)
            },
        )

        fun fromJson(json: String?): EqSettings {
            if (json.isNullOrBlank()) return flat()
            return try {
                val o = JSONObject(json)
                val arr = o.getJSONArray("bands")
                EqSettings(
                    enabled = o.getBoolean("enabled"),
                    preampDb = o.getDouble("preampDb"),
                    bands = (0 until arr.length()).map { i ->
                        val b = arr.getJSONObject(i)
                        EqBand(
                            FilterType.valueOf(b.getString("type")),
                            b.getDouble("f"),
                            b.getDouble("g"),
                            b.getDouble("q"),
                        )
                    },
                )
            } catch (e: Exception) {
                flat()
            }
        }
    }
}

/** Normalisierte Biquad-Koeffizienten (a0 = 1), RBJ Audio-EQ-Cookbook. */
data class BiquadCoefficients(
    val b0: Double,
    val b1: Double,
    val b2: Double,
    val a1: Double,
    val a2: Double,
) {
    companion object {
        fun design(band: EqBand, sampleRate: Int): BiquadCoefficients {
            val f = band.frequencyHz.coerceIn(10.0, sampleRate * 0.45)
            val q = band.q.coerceIn(0.1, 20.0)
            val a = 10.0.pow(band.gainDb / 40.0)
            val w0 = 2.0 * PI * f / sampleRate
            val cosW = cos(w0)
            val alpha = sin(w0) / (2.0 * q)
            val b0: Double
            val b1: Double
            val b2: Double
            val a0: Double
            val a1: Double
            val a2: Double
            when (band.type) {
                FilterType.PEAK -> {
                    b0 = 1 + alpha * a
                    b1 = -2 * cosW
                    b2 = 1 - alpha * a
                    a0 = 1 + alpha / a
                    a1 = -2 * cosW
                    a2 = 1 - alpha / a
                }
                FilterType.LOW_SHELF -> {
                    val s = 2 * sqrt(a) * alpha
                    b0 = a * ((a + 1) - (a - 1) * cosW + s)
                    b1 = 2 * a * ((a - 1) - (a + 1) * cosW)
                    b2 = a * ((a + 1) - (a - 1) * cosW - s)
                    a0 = (a + 1) + (a - 1) * cosW + s
                    a1 = -2 * ((a - 1) + (a + 1) * cosW)
                    a2 = (a + 1) + (a - 1) * cosW - s
                }
                FilterType.HIGH_SHELF -> {
                    val s = 2 * sqrt(a) * alpha
                    b0 = a * ((a + 1) + (a - 1) * cosW + s)
                    b1 = -2 * a * ((a - 1) + (a + 1) * cosW)
                    b2 = a * ((a + 1) + (a - 1) * cosW - s)
                    a0 = (a + 1) - (a - 1) * cosW + s
                    a1 = 2 * ((a - 1) - (a + 1) * cosW)
                    a2 = (a + 1) - (a - 1) * cosW - s
                }
            }
            return BiquadCoefficients(b0 / a0, b1 / a0, b2 / a0, a1 / a0, a2 / a0)
        }
    }

    /** Betrag des Frequenzgangs in dB bei [frequencyHz]. */
    fun magnitudeDb(frequencyHz: Double, sampleRate: Int): Double {
        val w = 2.0 * PI * frequencyHz / sampleRate
        val c1 = cos(w)
        val s1 = sin(w)
        val c2 = cos(2 * w)
        val s2 = sin(2 * w)
        val numRe = b0 + b1 * c1 + b2 * c2
        val numIm = -(b1 * s1 + b2 * s2)
        val denRe = 1 + a1 * c1 + a2 * c2
        val denIm = -(a1 * s1 + a2 * s2)
        val num = numRe * numRe + numIm * numIm
        val den = denRe * denRe + denIm * denIm
        return 10 * log10(num / den)
    }
}

/** Gesamtfrequenzgang inkl. Preamp, für die Kurve in der UI. */
fun EqSettings.responseDb(frequencyHz: Double, sampleRate: Int = 48_000): Double {
    if (!enabled) return 0.0
    return preampDb + bands.sumOf { BiquadCoefficients.design(it, sampleRate).magnitudeDb(frequencyHz, sampleRate) }
}
