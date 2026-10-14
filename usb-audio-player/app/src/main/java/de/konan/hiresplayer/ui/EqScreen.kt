package de.konan.hiresplayer.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import de.konan.hiresplayer.dsp.EqBand
import de.konan.hiresplayer.dsp.EqSettings
import de.konan.hiresplayer.dsp.FilterType
import de.konan.hiresplayer.dsp.responseDb
import de.konan.hiresplayer.playback.OutputMode
import de.konan.hiresplayer.playback.PlaybackEngine
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.roundToInt

private const val F_MIN = 20.0
private const val F_MAX = 20_000.0
private const val DB_RANGE = 15f

private val presets: List<Pair<String, List<Double>>> = listOf(
    "Flat" to List(10) { 0.0 },
    "Bass+" to listOf(5.0, 4.0, 2.5, 1.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0),
    "Klarheit" to listOf(0.0, 0.0, -1.0, -1.5, 0.0, 1.0, 2.0, 2.5, 2.0, 1.0),
    "Loudness" to listOf(4.0, 3.0, 1.0, 0.0, -1.0, 0.0, 0.0, 1.0, 2.5, 3.0),
    "Vocal" to listOf(-2.0, -1.0, 0.0, 1.0, 2.5, 3.0, 2.0, 0.5, 0.0, -1.0),
)

@Composable
fun EqScreen(engine: PlaybackEngine, onSwitchToStandard: () -> Unit, modifier: Modifier = Modifier) {
    val eq by engine.eq.collectAsStateWithLifecycle()
    val mode by engine.mode.collectAsStateWithLifecycle()

    fun update(transform: (EqSettings) -> EqSettings) = engine.setEq(transform(eq))

    Column(
        modifier
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Parametrischer EQ", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                Text(
                    "10 Bänder · 64-bit Biquads",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(checked = eq.enabled, onCheckedChange = { on -> update { it.copy(enabled = on) } })
        }

        if (mode != OutputMode.STANDARD) {
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh)) {
                Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "Im Modus „${mode.title}“ ist jede DSP deaktiviert, damit das Signal unverändert bleibt.",
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = onSwitchToStandard) { Text("Ausgabe") }
                }
            }
        }

        ResponseCurve(eq)

        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            presets.forEach { (name, gains) ->
                FilterChip(
                    selected = false,
                    onClick = {
                        update { s ->
                            val bands = EqSettings.flat().bands.mapIndexed { i, b -> b.copy(gainDb = gains[i]) }
                            val maxBoost = gains.maxOrNull()?.coerceAtLeast(0.0) ?: 0.0
                            s.copy(enabled = true, bands = bands, preampDb = -maxBoost)
                        }
                    },
                    label = { Text(name) },
                )
            }
        }

        LabeledSlider(
            label = "Preamp",
            valueText = "%+.1f dB".format(eq.preampDb),
            value = eq.preampDb.toFloat(),
            range = -15f..6f,
            onChange = { v -> update { it.copy(preampDb = (v * 2).roundToInt() / 2.0) } },
        )

        eq.bands.forEachIndexed { index, band ->
            BandEditor(index, band) { newBand ->
                update { s -> s.copy(bands = s.bands.toMutableList().also { it[index] = newBand }) }
            }
        }
    }
}

@Composable
private fun ResponseCurve(eq: EqSettings) {
    val primary = MaterialTheme.colorScheme.primary
    val grid = MaterialTheme.colorScheme.outline
    Canvas(
        Modifier
            .fillMaxWidth()
            .height(160.dp)
    ) {
        val w = size.width
        val h = size.height
        fun yOf(db: Double) = (h / 2f - (db.toFloat() / DB_RANGE) * (h / 2f)).coerceIn(0f, h)
        fun xOf(f: Double) = (log10(f / F_MIN) / log10(F_MAX / F_MIN)).toFloat() * w

        listOf(-12.0, -6.0, 0.0, 6.0, 12.0).forEach { db ->
            drawLine(grid, Offset(0f, yOf(db)), Offset(w, yOf(db)), strokeWidth = if (db == 0.0) 2f else 1f)
        }
        listOf(100.0, 1000.0, 10_000.0).forEach { f ->
            drawLine(grid, Offset(xOf(f), 0f), Offset(xOf(f), h), strokeWidth = 1f)
        }

        val path = Path()
        val steps = 160
        for (i in 0..steps) {
            val f = F_MIN * (F_MAX / F_MIN).pow(i.toDouble() / steps)
            val x = xOf(f)
            val y = yOf(eq.responseDb(f))
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        drawPath(path, if (eq.enabled) primary else grid, style = Stroke(width = 4f))
    }
}

@Composable
private fun BandEditor(index: Int, band: EqBand, onChange: (EqBand) -> Unit) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Band ${index + 1}", fontWeight = FontWeight.SemiBold, modifier = Modifier.width(64.dp))
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    FilterType.entries.forEach { t ->
                        FilterChip(selected = band.type == t, onClick = { onChange(band.copy(type = t)) }, label = { Text(t.label) })
                    }
                }
            }
            LabeledSlider(
                label = "Frequenz",
                valueText = if (band.frequencyHz >= 1000) "%.1f kHz".format(band.frequencyHz / 1000) else "${band.frequencyHz.roundToInt()} Hz",
                value = (log10(band.frequencyHz / F_MIN) / log10(F_MAX / F_MIN)).toFloat(),
                range = 0f..1f,
                onChange = { v -> onChange(band.copy(frequencyHz = (F_MIN * (F_MAX / F_MIN).pow(v.toDouble())).roundToInt().toDouble())) },
            )
            LabeledSlider(
                label = "Gain",
                valueText = "%+.1f dB".format(band.gainDb),
                value = band.gainDb.toFloat(),
                range = -DB_RANGE..DB_RANGE,
                onChange = { v -> onChange(band.copy(gainDb = (v * 2).roundToInt() / 2.0)) },
            )
            LabeledSlider(
                label = "Q",
                valueText = "%.2f".format(band.q),
                value = band.q.toFloat(),
                range = 0.3f..8f,
                onChange = { v -> onChange(band.copy(q = (v * 100).roundToInt() / 100.0)) },
            )
        }
    }
}

@Composable
private fun LabeledSlider(
    label: String,
    valueText: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    onChange: (Float) -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.bodySmall, modifier = Modifier.width(64.dp))
        Slider(value = value.coerceIn(range), onValueChange = onChange, valueRange = range, modifier = Modifier.weight(1f))
        Spacer(Modifier.width(8.dp))
        Text(valueText, style = MaterialTheme.typography.labelMedium, modifier = Modifier.width(72.dp))
    }
}
