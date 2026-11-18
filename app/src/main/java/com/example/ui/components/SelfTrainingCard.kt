package com.example.ui.components

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoGraph
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.model.BotConfig
import com.example.model.JumpOutcome
import com.example.model.JumpTelemetryRecord
import com.example.ui.theme.ElectricCyan
import com.example.ui.theme.JetBrainsMonoFontFamily
import com.example.ui.theme.KineticAmber
import com.example.ui.theme.ReadyEmerald
import java.util.Locale
import kotlin.math.roundToInt

@Composable
fun SelfTrainingCard(
    config: BotConfig,
    records: List<JumpTelemetryRecord>,
    onToggleAutoTrain: (Boolean) -> Unit,
    onManualFeedback: (Boolean) -> Unit,
    onBuildReport: () -> String,
    onClearHistory: () -> Unit,
    onLogCopied: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val total = records.size
    val centerHits = records.count { it.outcome == JumpOutcome.CENTER }
    val validRatios = records.mapNotNull { it.landingRatio }
    val avgRatio = if (validRatios.isNotEmpty()) validRatios.average() else null

    Card(
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        modifier = modifier
            .fillMaxWidth()
            .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(16.dp))
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(
                        imageVector = Icons.Filled.AutoGraph,
                        contentDescription = "Self training analytics",
                        tint = ReadyEmerald
                    )
                    Column {
                        Text(
                            text = "3. Self-Training & Data Export",
                            style = MaterialTheme.typography.titleLarge
                        )
                        Text(
                            text = "Measures landing accuracy & auto-adjusts k/b live",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                Switch(
                    checked = config.autoTrainEnabled,
                    onCheckedChange = onToggleAutoTrain,
                    modifier = Modifier.testTag("switch_auto_train")
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                StatPill(
                    label = "Jumps",
                    value = "$total",
                    accent = ElectricCyan,
                    modifier = Modifier.weight(1f)
                )
                StatPill(
                    label = "Center",
                    value = if (total > 0) "${(centerHits * 100f / total).roundToInt()}%" else "--",
                    accent = ReadyEmerald,
                    modifier = Modifier.weight(1f)
                )
                StatPill(
                    label = "Avg Ratio",
                    value = avgRatio?.let { String.format(Locale.US, "%.2f", it) } ?: "--",
                    accent = KineticAmber,
                    modifier = Modifier.weight(1f)
                )
                StatPill(
                    label = "Trained k/b",
                    value = String.format(Locale.US, "%.2f / %.0f", config.kFactor, config.offsetMs),
                    accent = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1.2f)
                )
            }

            if (config.adaptiveBiasesMs.isNotEmpty()) {
                val activeProfiles = config.adaptiveBiasesMs.entries
                    .take(4)
                    .joinToString(" · ") { (k, v) ->
                        String.format(Locale.US, "%s %+.0fms", k.removePrefix("BLK:").removePrefix("STANCE:"), v)
                    }
                Text(
                    text = "Power Memory (${config.adaptiveBiasesMs.size}): $activeProfiles",
                    fontFamily = JetBrainsMonoFontFamily,
                    style = MaterialTheme.typography.labelSmall,
                    color = ElectricCyan
                )
            }

            if (records.isNotEmpty()) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(Color(0xFF070C14))
                        .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(12.dp))
                        .padding(10.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    records.take(4).forEach { rec ->
                        val ratioTxt = rec.landingRatio?.let { String.format(Locale.US, "%.2f", it) } ?: "NA"
                        val outcomeColor = when (rec.outcome) {
                            JumpOutcome.CENTER -> ReadyEmerald
                            JumpOutcome.SHORT, JumpOutcome.OUT_SHORT, JumpOutcome.USER_SHORT -> KineticAmber
                            JumpOutcome.PAST_CENTER, JumpOutcome.OUT_OVERSHOOT, JumpOutcome.USER_LONG -> Color(0xFFFB7185)
                            else -> MaterialTheme.colorScheme.onSurfaceVariant
                        }
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "#${rec.id} ${rec.targetProfile.take(3)}/${rec.pawnStance.take(4)} D=${rec.normalizedDistancePx.roundToInt()}",
                                fontFamily = JetBrainsMonoFontFamily,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Text(
                                text = "${rec.outcome.shortLabel} (r=$ratioTxt) · ${rec.holdMs}ms",
                                fontFamily = JetBrainsMonoFontFamily,
                                style = MaterialTheme.typography.labelSmall,
                                color = outcomeColor,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                    }
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedButton(
                    onClick = { onManualFeedback(true) },
                    modifier = Modifier
                        .weight(1f)
                        .minimumInteractiveComponentSize()
                        .testTag("btn_feedback_short")
                ) {
                    Text("◀ Too Short (+k)")
                }
                OutlinedButton(
                    onClick = { onManualFeedback(false) },
                    modifier = Modifier
                        .weight(1f)
                        .minimumInteractiveComponentSize()
                        .testTag("btn_feedback_long")
                ) {
                    Text("Too Far (-k) ▶")
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(
                    onClick = {
                        val report = onBuildReport()
                        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        clipboard.setPrimaryClip(ClipData.newPlainText("JumpAssist Training Data", report))
                        onLogCopied()
                        Toast.makeText(context, "Training data copied ($total jumps)", Toast.LENGTH_SHORT).show()
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
                    modifier = Modifier
                        .weight(1f)
                        .minimumInteractiveComponentSize()
                        .testTag("btn_copy_training_data")
                ) {
                    Icon(Icons.Filled.ContentCopy, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Copy Training Data")
                }

                FilledTonalButton(
                    onClick = {
                        val report = onBuildReport()
                        val shareIntent = Intent(Intent.ACTION_SEND).apply {
                            type = "text/plain"
                            putExtra(Intent.EXTRA_SUBJECT, "JumpAssist Training Telemetry")
                            putExtra(Intent.EXTRA_TEXT, report)
                        }
                        context.startActivity(Intent.createChooser(shareIntent, "Share Training Data"))
                    },
                    modifier = Modifier
                        .minimumInteractiveComponentSize()
                        .testTag("btn_share_training_data")
                ) {
                    Icon(Icons.Filled.Share, contentDescription = "Share data", modifier = Modifier.size(18.dp))
                }

                IconButton(
                    onClick = onClearHistory,
                    enabled = records.isNotEmpty(),
                    modifier = Modifier
                        .minimumInteractiveComponentSize()
                        .testTag("btn_clear_training_data")
                ) {
                    Icon(
                        imageVector = Icons.Filled.DeleteSweep,
                        contentDescription = "Clear history",
                        tint = if (records.isNotEmpty()) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

@Composable
private fun StatPill(
    label: String,
    value: String,
    accent: Color,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(10.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(10.dp))
            .padding(horizontal = 8.dp, vertical = 7.dp)
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = value,
                fontFamily = JetBrainsMonoFontFamily,
                style = MaterialTheme.typography.labelSmall,
                color = accent,
                fontWeight = FontWeight.Bold
            )
        }
    }
}
