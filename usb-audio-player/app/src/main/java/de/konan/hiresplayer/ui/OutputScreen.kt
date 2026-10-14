package de.konan.hiresplayer.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Usb
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import de.konan.hiresplayer.playback.OutputMode
import de.konan.hiresplayer.playback.PlaybackEngine
import de.konan.hiresplayer.playback.UsbDacInfo
import de.konan.hiresplayer.playback.formatSampleRate

@Composable
fun OutputScreen(engine: PlaybackEngine, modifier: Modifier = Modifier) {
    val dac by engine.usb.dac.collectAsStateWithLifecycle()
    val mode by engine.mode.collectAsStateWithLifecycle()
    val signal by engine.signal.collectAsStateWithLifecycle()

    Column(
        modifier
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        DacCard(dac, mixerApi = engine.usb.isMixerApiAvailable, onRefresh = engine.usb::refresh)

        Text("Ausgabemodus", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        OutputMode.entries.forEach { m ->
            Card(
                Modifier
                    .fillMaxWidth()
                    .clickable { engine.setMode(m) },
                colors = CardDefaults.cardColors(
                    containerColor = if (m == mode) MaterialTheme.colorScheme.surfaceContainerHigh else MaterialTheme.colorScheme.surfaceContainer,
                ),
            ) {
                Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(selected = m == mode, onClick = { engine.setMode(m) })
                    Spacer(Modifier.width(8.dp))
                    Column {
                        Text(m.title, fontWeight = FontWeight.SemiBold)
                        Text(m.description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }

        if (mode != OutputMode.STANDARD) {
            Text(
                "Tipp: Im Bit-Perfect-Modus regelt Android die Lautstärke nicht digital – " +
                    "nutze den Lautstärkeregler deines DACs bzw. Verstärkers.",
                style = MaterialTheme.typography.bodySmall,
                color = Amber,
            )
        }

        SignalPathCard(signal, mode, dac)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun DacCard(dac: UsbDacInfo?, mixerApi: Boolean, onRefresh: () -> Unit) {
    Card(
        Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.Usb, contentDescription = null, tint = if (dac != null) Amber else Muted)
                Spacer(Modifier.width(8.dp))
                Text(
                    dac?.name ?: "Kein USB-DAC erkannt",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = onRefresh) { Icon(Icons.Filled.Refresh, contentDescription = "Aktualisieren") }
            }
            if (dac == null) {
                Text(
                    "Schließe einen USB-DAC per USB-C bzw. OTG-Adapter an.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                return@Column
            }
            Spacer(Modifier.height(8.dp))
            Capability("Abtastraten", dac.sampleRates.joinToString(", ") { formatSampleRate(it) }.ifEmpty { "beliebig" })
            Capability("Formate", dac.encodings.joinToString(", ") { it.label }.ifEmpty { "–" })
            Capability("Kanäle", dac.channelCounts.joinToString(", ").ifEmpty { "–" })
            Capability(
                "Bit-Perfect",
                when {
                    !mixerApi -> "erst ab Android 14"
                    dac.supportsBitPerfect -> "unterstützt"
                    else -> "vom Gerät nicht angeboten"
                },
            )
            val bitPerfect = dac.mixerOptions.filter { it.bitPerfect }
            if (bitPerfect.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                Text("Bit-Perfect-Formate", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    bitPerfect.forEach { o ->
                        AssistChip(
                            onClick = {},
                            label = { Text("${formatSampleRate(o.sampleRate)} · ${o.kind.label}") },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun Capability(label: String, value: String) {
    Row(Modifier.padding(vertical = 2.dp)) {
        Text(label, modifier = Modifier.width(100.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodySmall)
    }
}
