package com.example.ui.screens

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ScreenShare
import androidx.compose.material.icons.filled.AccessibilityNew
import androidx.compose.material.icons.filled.CenterFocusStrong
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material.icons.filled.TouchApp
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.model.BotConfig
import com.example.model.BotLogEntry
import com.example.model.JumpTelemetryRecord
import com.example.model.VisionAnalysisResult
import com.example.ui.components.SelfTrainingCard
import com.example.ui.theme.JetBrainsMonoFontFamily
import com.example.viewmodel.PermissionReadiness
import kotlin.math.roundToInt

@Composable
fun DashboardScreen(
    permissions: PermissionReadiness,
    config: BotConfig,
    liveAnalysis: VisionAnalysisResult?,
    logs: List<BotLogEntry>,
    trainingRecords: List<JumpTelemetryRecord>,
    isDebugVisionEnabled: Boolean,
    onStartCapture: (Int, Intent) -> Unit,
    onStopCapture: () -> Unit,
    onToggleOverlay: () -> Unit,
    onToggleDebugVision: () -> Unit,
    onToggleAutoLoop: () -> Unit,
    onTriggerSingleStep: () -> Unit,
    onCooldownChange: (Long) -> Unit,
    onTapXChange: (Float) -> Unit,
    onTapYChange: (Float) -> Unit,
    onToggleAutoTrain: (Boolean) -> Unit,
    onToggleHumanizeTiming: (Boolean) -> Unit,
    onToggleUltraPrecision: (Boolean) -> Unit,
    onManualFeedback: (Boolean) -> Unit,
    onBuildReport: () -> String,
    onClearTrainingHistory: () -> Unit,
    onLogReportCopied: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current

    val mediaProjectionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK && result.data != null) {
            onStartCapture(result.resultCode, result.data!!)
        }
    }

    val notificationLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { _ ->
        // Handled cleanly via ON_RESUME permission refresh in MainActivity
    }

    Box(
        modifier = modifier.fillMaxSize(),
        contentAlignment = Alignment.TopCenter
    ) {
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .widthIn(max = 720.dp),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            item {
                HeroTelemetryBanner(permissions = permissions, liveAnalysis = liveAnalysis)
            }

            item {
                Text(
                    text = "1. System Permissions & Services",
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onBackground
                )
            }

            item {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    PermissionStepCard(
                        icon = Icons.Filled.AccessibilityNew,
                        title = "Accessibility Touch Service",
                        subtitle = when {
                            permissions.accessibilityEnabled ->
                                "Connected — GestureDescription dispatch ready"
                            permissions.accessibilitySettingOn ->
                                "Unbound after app install/update. Open Settings → turn JumpAssist OFF, then ON again."
                            else ->
                                "Required to perform timed press-and-hold jumps"
                        },
                        isReady = permissions.accessibilityEnabled,
                        actionLabel = when {
                            permissions.accessibilityEnabled -> "Settings"
                            permissions.accessibilitySettingOn -> "Re-enable"
                            else -> "Enable"
                        },
                        testTag = "btn_enable_accessibility",
                        onAction = {
                            context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                        }
                    )

                    PermissionStepCard(
                        icon = Icons.AutoMirrored.Filled.ScreenShare,
                        title = "Screen Capture (MediaProjection)",
                        subtitle = if (permissions.captureRunning) {
                            "Streaming frames to BlockVisionAnalyzer"
                        } else {
                            "Captures live game screen to measure block distance"
                        },
                        isReady = permissions.captureRunning,
                        actionLabel = if (permissions.captureRunning) "Stop" else "Grant",
                        testTag = "btn_toggle_capture",
                        onAction = {
                            if (permissions.captureRunning) {
                                onStopCapture()
                            } else {
                                val mgr = context.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
                                mediaProjectionLauncher.launch(mgr.createScreenCaptureIntent())
                            }
                        }
                    )

                    PermissionStepCard(
                        icon = Icons.Filled.Layers,
                        title = "Floating HUD Overlay",
                        subtitle = if (permissions.overlayRunning) {
                            "Overlay pill visible over installed game"
                        } else if (permissions.overlayGranted) {
                            "Permission granted — launch floating controls"
                        } else {
                            "Allows Step/Auto controls while playing the game"
                        },
                        isReady = permissions.overlayGranted,
                        actionLabel = when {
                            !permissions.overlayGranted -> "Allow"
                            permissions.overlayRunning -> "Hide HUD"
                            else -> "Show HUD"
                        },
                        testTag = "btn_toggle_overlay",
                        onAction = {
                            if (!permissions.overlayGranted) {
                                val intent = Intent(
                                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                    Uri.parse("package:${context.packageName}")
                                )
                                context.startActivity(intent)
                            } else {
                                onToggleOverlay()
                            }
                        }
                    )

                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        PermissionStepCard(
                            icon = Icons.Filled.NotificationsActive,
                            title = "Foreground Notification",
                            subtitle = if (permissions.notificationsGranted) {
                                "Granted for persistent MediaProjection status"
                            } else {
                                "Required on Android 13+ for screen capture service"
                            },
                            isReady = permissions.notificationsGranted,
                            actionLabel = if (permissions.notificationsGranted) "Granted" else "Allow",
                            testTag = "btn_request_notifications",
                            onAction = {
                                if (!permissions.notificationsGranted) {
                                    notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                                }
                            }
                        )
                    }
                }
            }

            item {
                AutomationControlsCard(
                    permissions = permissions,
                    config = config,
                    isDebugVisionEnabled = isDebugVisionEnabled,
                    onToggleDebugVision = onToggleDebugVision,
                    onToggleHumanizeTiming = onToggleHumanizeTiming,
                    onToggleUltraPrecision = onToggleUltraPrecision,
                    onToggleAutoLoop = onToggleAutoLoop,
                    onTriggerSingleStep = onTriggerSingleStep,
                    onCooldownChange = onCooldownChange,
                    onTapXChange = onTapXChange,
                    onTapYChange = onTapYChange
                )
            }

            item {
                SelfTrainingCard(
                    config = config,
                    records = trainingRecords,
                    onToggleAutoTrain = onToggleAutoTrain,
                    onManualFeedback = onManualFeedback,
                    onBuildReport = onBuildReport,
                    onClearHistory = onClearTrainingHistory,
                    onLogCopied = onLogReportCopied
                )
            }

            item {
                TelemetryLogCard(logs = logs)
            }
        }
    }
}

@Composable
private fun HeroTelemetryBanner(
    permissions: PermissionReadiness,
    liveAnalysis: VisionAnalysisResult?
) {
    val statusColor = when {
        permissions.autoLoopRunning -> MaterialTheme.colorScheme.tertiary
        permissions.accessibilityEnabled && permissions.captureRunning -> MaterialTheme.colorScheme.primary
        else -> MaterialTheme.colorScheme.secondary
    }
    val statusText = when {
        permissions.autoLoopRunning -> "AUTO-LOOP ACTIVE"
        permissions.accessibilityEnabled && permissions.captureRunning -> "PIPELINE READY"
        else -> "SETUP REQUIRED"
    }

    Card(
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(20.dp))
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(176.dp)
        ) {
            Image(
                painter = painterResource(id = R.drawable.img_hero_telemetry),
                contentDescription = "JumpAssist telemetry trajectory graphic",
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        Brush.verticalGradient(
                            colors = listOf(
                                Color(0x5509101B),
                                Color(0xE609101B)
                            )
                        )
                    )
            )

            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(18.dp),
                verticalArrangement = Arrangement.SpaceBetween
            ) {
                Surface(
                    shape = RoundedCornerShape(50),
                    color = statusColor.copy(alpha = 0.18f),
                    modifier = Modifier.border(1.dp, statusColor.copy(alpha = 0.6f), RoundedCornerShape(50))
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 5.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(8.dp)
                                .clip(CircleShape)
                                .background(statusColor)
                        )
                        Text(
                            text = statusText,
                            style = MaterialTheme.typography.labelSmall,
                            color = statusColor,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }

                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        text = "JumpAssist Vision Bot",
                        style = MaterialTheme.typography.headlineMedium,
                        color = MaterialTheme.colorScheme.onBackground
                    )
                    Text(
                        text = when {
                            liveAnalysis != null && liveAnalysis.blockFound ->
                                "Last Frame: D=${liveAnalysis.rawDistancePx.roundToInt()}px · Block W=${liveAnalysis.blockBounds?.widthPx?.roundToInt() ?: 0}px · Hold ${liveAnalysis.computedDurationMs}ms"
                            !permissions.accessibilityEnabled && permissions.accessibilitySettingOn ->
                                "Accessibility unbound after install — tap Re-enable and toggle JumpAssist OFF → ON."
                            else ->
                                "Grant Accessibility + Screen Capture, then switch to your jumping game."
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

@Composable
private fun PermissionStepCard(
    icon: ImageVector,
    title: String,
    subtitle: String,
    isReady: Boolean,
    actionLabel: String,
    testTag: String,
    onAction: () -> Unit
) {
    val accent = if (isReady) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.secondary

    Card(
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(16.dp))
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(42.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(accent.copy(alpha = 0.14f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = title,
                    tint = accent,
                    modifier = Modifier.size(22.dp)
                )
            }

            Column(modifier = Modifier.weight(1f)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Icon(
                        imageVector = if (isReady) Icons.Filled.CheckCircle else Icons.Filled.RadioButtonUnchecked,
                        contentDescription = if (isReady) "Ready" else "Pending",
                        tint = accent,
                        modifier = Modifier.size(16.dp)
                    )
                }
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            FilledTonalButton(
                onClick = onAction,
                modifier = Modifier
                    .minimumInteractiveComponentSize()
                    .testTag(testTag)
            ) {
                Text(text = actionLabel)
            }
        }
    }
}

@Composable
private fun AutomationControlsCard(
    permissions: PermissionReadiness,
    config: BotConfig,
    isDebugVisionEnabled: Boolean,
    onToggleDebugVision: () -> Unit,
    onToggleHumanizeTiming: (Boolean) -> Unit,
    onToggleUltraPrecision: (Boolean) -> Unit,
    onToggleAutoLoop: () -> Unit,
    onTriggerSingleStep: () -> Unit,
    onCooldownChange: (Long) -> Unit,
    onTapXChange: (Float) -> Unit,
    onTapYChange: (Float) -> Unit
) {
    val canRun = permissions.accessibilityEnabled && permissions.captureRunning

    Card(
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(16.dp))
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(
                    imageVector = Icons.Filled.TouchApp,
                    contentDescription = "Touch automation controls",
                    tint = MaterialTheme.colorScheme.primary
                )
                Text(
                    text = "2. Touch Automation & Timing",
                    style = MaterialTheme.typography.titleLarge
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Button(
                    onClick = onToggleAutoLoop,
                    enabled = canRun,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (permissions.autoLoopRunning) {
                            MaterialTheme.colorScheme.secondary
                        } else {
                            MaterialTheme.colorScheme.primary
                        }
                    ),
                    modifier = Modifier
                        .weight(1f)
                        .minimumInteractiveComponentSize()
                        .testTag("btn_auto_loop")
                ) {
                    Icon(
                        imageVector = if (permissions.autoLoopRunning) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(if (permissions.autoLoopRunning) "Pause Auto" else "Start Auto")
                }

                OutlinedButton(
                    onClick = onTriggerSingleStep,
                    enabled = canRun,
                    modifier = Modifier
                        .weight(1f)
                        .minimumInteractiveComponentSize()
                        .testTag("btn_step_jump")
                ) {
                    Icon(
                        imageVector = Icons.Filled.SkipNext,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Step Once")
                }
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant)
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Icon(
                    imageVector = if (isDebugVisionEnabled) Icons.Filled.Visibility else Icons.Filled.VisibilityOff,
                    contentDescription = "On-Screen Bot Vision Overlay",
                    tint = if (isDebugVisionEnabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(20.dp)
                )
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "On-Screen Bot Vision (👁)",
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = "Shows ROI, Pawn stance, Target brackets & Stop reasons over game",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(
                    checked = isDebugVisionEnabled,
                    onCheckedChange = { onToggleDebugVision() },
                    modifier = Modifier.testTag("switch_debug_vision")
                )
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant)
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Icon(
                    imageVector = Icons.Filled.Speed,
                    contentDescription = "Human-Like Jump Pacing",
                    tint = if (config.humanizeTiming) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(20.dp)
                )
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Human-Like Jump Pacing",
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = "Mixes quick hops, natural rhythm, occasional short pauses & tap drift",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(
                    checked = config.humanizeTiming,
                    onCheckedChange = onToggleHumanizeTiming,
                    modifier = Modifier.testTag("switch_humanize_timing")
                )
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant)
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Icon(
                    imageVector = Icons.Filled.CenterFocusStrong,
                    contentDescription = "Ultra-Precision Mode (Slow)",
                    tint = if (config.ultraPrecisionMode) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(20.dp)
                )
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Ultra-Precision Mode (Slow)",
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = "Multi-frame averaging, edge-snapped contour & slow settle check to prevent +1pt misses",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(
                    checked = config.ultraPrecisionMode,
                    onCheckedChange = onToggleUltraPrecision,
                    modifier = Modifier.testTag("switch_ultra_precision")
                )
            }

            Column {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text("Jump Settle Delay", style = MaterialTheme.typography.bodyMedium)
                    Text(
                        text = "${config.jumpCooldownMs} ms",
                        fontFamily = JetBrainsMonoFontFamily,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
                Slider(
                    value = config.jumpCooldownMs.toFloat(),
                    onValueChange = { onCooldownChange(it.roundToInt().toLong()) },
                    valueRange = 650f..2500f,
                    colors = SliderDefaults.colors(thumbColor = MaterialTheme.colorScheme.primary),
                    modifier = Modifier.testTag("slider_cooldown")
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text("Tap X", style = MaterialTheme.typography.bodyMedium)
                        Text(
                            text = "${(config.tapXFraction * 100).roundToInt()}%",
                            fontFamily = JetBrainsMonoFontFamily,
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.secondary
                        )
                    }
                    Slider(
                        value = config.tapXFraction,
                        onValueChange = onTapXChange,
                        valueRange = 0.15f..0.85f,
                        modifier = Modifier.testTag("slider_tap_x")
                    )
                }

                Column(modifier = Modifier.weight(1f)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text("Tap Y", style = MaterialTheme.typography.bodyMedium)
                        Text(
                            text = "${(config.tapYFraction * 100).roundToInt()}%",
                            fontFamily = JetBrainsMonoFontFamily,
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.secondary
                        )
                    }
                    Slider(
                        value = config.tapYFraction,
                        onValueChange = onTapYChange,
                        valueRange = 0.50f..0.92f,
                        modifier = Modifier.testTag("slider_tap_y")
                    )
                }
            }
        }
    }
}

@Composable
private fun TelemetryLogCard(logs: List<BotLogEntry>) {
    Card(
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF070C14)),
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(16.dp))
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(
                    imageVector = Icons.Filled.Terminal,
                    contentDescription = "Telemetry console",
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(18.dp)
                )
                Text(
                    text = "Live Telemetry Log",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }

            if (logs.isEmpty()) {
                Text(
                    text = "> Awaiting frame capture or calibration test...",
                    fontFamily = JetBrainsMonoFontFamily,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    logs.take(8).forEach { entry ->
                        Text(
                            text = "[${entry.timestamp}] ${entry.message}",
                            fontFamily = JetBrainsMonoFontFamily,
                            style = MaterialTheme.typography.labelSmall,
                            color = if (entry.isError) {
                                MaterialTheme.colorScheme.error
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            }
                        )
                    }
                }
            }
        }
    }
}
