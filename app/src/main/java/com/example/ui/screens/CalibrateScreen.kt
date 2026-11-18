package com.example.ui.screens

import android.graphics.Bitmap
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
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
import androidx.compose.material.icons.filled.AutoFixHigh
import androidx.compose.material.icons.filled.Colorize
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.RestartAlt
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.example.model.BotConfig
import com.example.model.FormulaMode
import com.example.model.VisionAnalysisResult
import com.example.ui.theme.ElectricCyan
import com.example.ui.theme.JetBrainsMonoFontFamily
import com.example.ui.theme.KineticAmber
import com.example.ui.theme.ReadyEmerald
import java.util.Locale
import kotlin.math.roundToInt

@Composable
fun CalibrateScreen(
    config: BotConfig,
    sampleBitmap: Bitmap?,
    analysis: VisionAnalysisResult?,
    isEyedropperActive: Boolean,
    onToggleEyedropper: (Boolean) -> Unit,
    onSamplePawnColor: (Float, Float) -> Unit,
    onPickScreenshot: (Uri) -> Unit,
    onGenerateSynthetic: () -> Unit,
    onFormulaModeChange: (FormulaMode) -> Unit,
    onKFactorChange: (Float) -> Unit,
    onOffsetMsChange: (Float) -> Unit,
    onQuadFactorChange: (Float) -> Unit,
    onPawnToleranceChange: (Int) -> Unit,
    onBgThresholdChange: (Int) -> Unit,
    onRoiTopChange: (Float) -> Unit,
    onRoiBottomChange: (Float) -> Unit,
    onResetDefaults: () -> Unit,
    modifier: Modifier = Modifier
) {
    val photoPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        if (uri != null) {
            onPickScreenshot(uri)
        }
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
                VisionPreviewCard(
                    sampleBitmap = sampleBitmap,
                    analysis = analysis,
                    pawnColorRgb = config.pawnColorRgb,
                    isEyedropperActive = isEyedropperActive,
                    onToggleEyedropper = onToggleEyedropper,
                    onSamplePawnColor = onSamplePawnColor,
                    onPickScreenshot = {
                        photoPickerLauncher.launch(
                            PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                        )
                    },
                    onGenerateSynthetic = onGenerateSynthetic
                )
            }

            if (analysis != null) {
                item {
                    TelemetryMetricsRow(analysis = analysis)
                }
            }

            item {
                FormulaCalibrationCard(
                    config = config,
                    onFormulaModeChange = onFormulaModeChange,
                    onKFactorChange = onKFactorChange,
                    onOffsetMsChange = onOffsetMsChange,
                    onQuadFactorChange = onQuadFactorChange,
                    onPawnToleranceChange = onPawnToleranceChange,
                    onBgThresholdChange = onBgThresholdChange,
                    onRoiTopChange = onRoiTopChange,
                    onRoiBottomChange = onRoiBottomChange,
                    onResetDefaults = onResetDefaults
                )
            }
        }
    }
}

@Composable
private fun VisionPreviewCard(
    sampleBitmap: Bitmap?,
    analysis: VisionAnalysisResult?,
    pawnColorRgb: Int,
    isEyedropperActive: Boolean,
    onToggleEyedropper: (Boolean) -> Unit,
    onSamplePawnColor: (Float, Float) -> Unit,
    onPickScreenshot: () -> Unit,
    onGenerateSynthetic: () -> Unit
) {
    val hexColor = "#${Integer.toHexString(pawnColorRgb and 0xFFFFFF).uppercase().padStart(6, '0')}"

    Card(
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(18.dp))
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = "Vision Detection Preview",
                    style = MaterialTheme.typography.titleLarge
                )
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(14.dp)
                            .clip(CircleShape)
                            .background(Color(0xFF000000 or pawnColorRgb.toLong()))
                            .border(1.dp, Color.White.copy(alpha = 0.7f), CircleShape)
                    )
                    Text(
                        text = hexColor,
                        fontFamily = JetBrainsMonoFontFamily,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            if (sampleBitmap != null) {
                val aspect = (sampleBitmap.width.toFloat() / sampleBitmap.height.toFloat()).coerceIn(0.42f, 1.8f)
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(340.dp)
                        .clip(RoundedCornerShape(14.dp))
                        .background(Color(0xFF060B13)),
                    contentAlignment = Alignment.Center
                ) {
                    Box(
                        modifier = Modifier
                            .aspectRatio(aspect)
                            .clip(RoundedCornerShape(10.dp))
                            .pointerInput(isEyedropperActive, sampleBitmap) {
                                detectTapGestures { offset ->
                                    if (isEyedropperActive && size.width > 0 && size.height > 0) {
                                        onSamplePawnColor(
                                            offset.x / size.width.toFloat(),
                                            offset.y / size.height.toFloat()
                                        )
                                    }
                                }
                            }
                    ) {
                        Image(
                            bitmap = sampleBitmap.asImageBitmap(),
                            contentDescription = "Analyzed game frame",
                            contentScale = ContentScale.FillBounds,
                            modifier = Modifier.fillMaxSize()
                        )

                        if (analysis != null && analysis.imageWidth > 0 && analysis.imageHeight > 0) {
                            Canvas(modifier = Modifier.fillMaxSize()) {
                                val sx = size.width / analysis.imageWidth.toFloat()
                                val sy = size.height / analysis.imageHeight.toFloat()

                                // ROI Band
                                val roiTop = analysis.roiTopY * sy
                                val roiBot = analysis.roiBottomY * sy
                                drawRect(
                                    color = ElectricCyan.copy(alpha = 0.08f),
                                    topLeft = Offset(0f, roiTop),
                                    size = Size(size.width, (roiBot - roiTop).coerceAtLeast(1f))
                                )
                                drawLine(
                                    color = ElectricCyan.copy(alpha = 0.6f),
                                    start = Offset(0f, roiTop),
                                    end = Offset(size.width, roiTop),
                                    strokeWidth = 2f,
                                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 8f))
                                )
                                drawLine(
                                    color = ElectricCyan.copy(alpha = 0.6f),
                                    start = Offset(0f, roiBot),
                                    end = Offset(size.width, roiBot),
                                    strokeWidth = 2f,
                                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 8f))
                                )

                                // Source Platform & Pawn Stance Marker
                                if (analysis.pawnFound) {
                                    if (analysis.sourceWidthPx > 10f) {
                                        val sLeft = analysis.sourceLeftX * sx
                                        val sRight = analysis.sourceRightX * sx
                                        val sCx = analysis.sourceCenterX * sx
                                        val sCy = analysis.sourceCenterY * sy
                                        drawLine(
                                            color = ElectricCyan.copy(alpha = 0.7f),
                                            start = Offset(sLeft, sCy),
                                            end = Offset(sRight, sCy),
                                            strokeWidth = 2.5f
                                        )
                                        drawCircle(
                                            color = ElectricCyan.copy(alpha = 0.85f),
                                            radius = 5f,
                                            center = Offset(sCx, sCy),
                                            style = Stroke(width = 2f)
                                        )
                                    }

                                    val px = analysis.pawnCenterX * sx
                                    val py = analysis.pawnBaseY * sy
                                    drawCircle(
                                        color = ReadyEmerald,
                                        radius = 12f,
                                        center = Offset(px, py),
                                        style = Stroke(width = 3f)
                                    )
                                    drawCircle(
                                        color = ReadyEmerald,
                                        radius = 4f,
                                        center = Offset(px, py)
                                    )
                                }

                                // Target Block & Distance Vector
                                val bounds = analysis.blockBounds
                                if (analysis.blockFound && bounds != null) {
                                    val bx = bounds.center.x * sx
                                    val by = bounds.center.y * sy
                                    val leftX = bounds.leftEdgeX * sx
                                    val rightX = bounds.rightEdgeX * sx
                                    val topY = bounds.topY * sy
                                    val botY = bounds.bottomY * sy

                                    drawRect(
                                        color = KineticAmber,
                                        topLeft = Offset(leftX, topY),
                                        size = Size((rightX - leftX).coerceAtLeast(4f), (botY - topY).coerceAtLeast(4f)),
                                        style = Stroke(width = 3f)
                                    )
                                    drawLine(
                                        color = KineticAmber,
                                        start = Offset(leftX, by),
                                        end = Offset(rightX, by),
                                        strokeWidth = 3f
                                    )
                                    drawCircle(
                                        color = KineticAmber,
                                        radius = 9f,
                                        center = Offset(bx, by)
                                    )

                                    if (analysis.pawnFound) {
                                        drawLine(
                                            color = ElectricCyan,
                                            start = Offset(analysis.pawnCenterX * sx, analysis.pawnBaseY * sy),
                                            end = Offset(bx, by),
                                            strokeWidth = 4f,
                                            pathEffect = PathEffect.dashPathEffect(floatArrayOf(12f, 8f))
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }

            if (isEyedropperActive) {
                Text(
                    text = "Eyedropper active: tap directly on the pawn in the image above to sample its RGB color.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.secondary
                )
            } else if (analysis != null) {
                Text(
                    text = analysis.statusMessage,
                    fontFamily = JetBrainsMonoFontFamily,
                    style = MaterialTheme.typography.labelSmall,
                    color = if (analysis.blockFound) ReadyEmerald else MaterialTheme.colorScheme.secondary
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                FilledTonalButton(
                    onClick = onPickScreenshot,
                    modifier = Modifier
                        .weight(1f)
                        .minimumInteractiveComponentSize()
                        .testTag("btn_pick_screenshot")
                ) {
                    Icon(Icons.Filled.PhotoLibrary, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Screenshot")
                }

                OutlinedButton(
                    onClick = onGenerateSynthetic,
                    modifier = Modifier
                        .weight(1f)
                        .minimumInteractiveComponentSize()
                        .testTag("btn_demo_frame")
                ) {
                    Icon(Icons.Filled.AutoFixHigh, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Demo Frame")
                }

                FilterChip(
                    selected = isEyedropperActive,
                    onClick = { onToggleEyedropper(!isEyedropperActive) },
                    label = { Text("Pick Color") },
                    leadingIcon = {
                        Icon(Icons.Filled.Colorize, contentDescription = null, modifier = Modifier.size(16.dp))
                    },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = MaterialTheme.colorScheme.secondaryContainer,
                        selectedLabelColor = MaterialTheme.colorScheme.onSecondaryContainer
                    ),
                    modifier = Modifier
                        .minimumInteractiveComponentSize()
                        .testTag("chip_eyedropper")
                )
            }
        }
    }
}

@Composable
private fun TelemetryMetricsRow(analysis: VisionAnalysisResult) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            MetricChipCard(
                label = "Distance D",
                value = "${analysis.rawDistancePx.roundToInt()} px",
                accent = ElectricCyan,
                modifier = Modifier.weight(1f)
            )
            MetricChipCard(
                label = "Block Type",
                value = if (analysis.blockFound) {
                    "${analysis.targetBlockProfile.take(3)} (${analysis.blockBounds?.widthPx?.roundToInt() ?: 0}px)"
                } else "--",
                accent = KineticAmber,
                modifier = Modifier.weight(1.1f)
            )
            MetricChipCard(
                label = "Pawn Stance",
                value = if (analysis.pawnFound) analysis.pawnStance else "--",
                accent = if (analysis.pawnStance == "CENTER") ReadyEmerald else KineticAmber,
                modifier = Modifier.weight(1f)
            )
            MetricChipCard(
                label = "Hold Time",
                value = "${analysis.computedDurationMs} ms",
                accent = ReadyEmerald,
                modifier = Modifier.weight(1f)
            )
        }
    }
}

@Composable
private fun MetricChipCard(
    label: String,
    value: String,
    accent: Color,
    modifier: Modifier = Modifier
) {
    Card(
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        modifier = modifier.border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(14.dp))
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = value,
                fontFamily = JetBrainsMonoFontFamily,
                style = MaterialTheme.typography.labelMedium,
                color = accent
            )
        }
    }
}

@Composable
private fun FormulaCalibrationCard(
    config: BotConfig,
    onFormulaModeChange: (FormulaMode) -> Unit,
    onKFactorChange: (Float) -> Unit,
    onOffsetMsChange: (Float) -> Unit,
    onQuadFactorChange: (Float) -> Unit,
    onPawnToleranceChange: (Int) -> Unit,
    onBgThresholdChange: (Int) -> Unit,
    onRoiTopChange: (Float) -> Unit,
    onRoiBottomChange: (Float) -> Unit,
    onResetDefaults: () -> Unit
) {
    Card(
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(18.dp))
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
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(Icons.Filled.Tune, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                    Text("Formula & Vision Tuners", style = MaterialTheme.typography.titleLarge)
                }

                TextButton(
                    onClick = onResetDefaults,
                    modifier = Modifier.testTag("btn_reset_defaults")
                ) {
                    Icon(Icons.Filled.RestartAlt, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Reset")
                }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FormulaMode.entries.forEach { mode ->
                    FilterChip(
                        selected = config.formulaMode == mode,
                        onClick = { onFormulaModeChange(mode) },
                        label = { Text(mode.name.lowercase().replaceFirstChar { it.uppercase() }) },
                        modifier = Modifier.minimumInteractiveComponentSize()
                    )
                }
            }

            Text(
                text = if (config.formulaMode == FormulaMode.LINEAR) {
                    String.format(
                        Locale.US,
                        "T = %.2f × D_norm + %.0f ms",
                        config.kFactor,
                        config.offsetMs
                    )
                } else {
                    String.format(
                        Locale.US,
                        "T = %.5f × D_norm² + %.2f × D_norm + %.0f ms",
                        config.quadFactor,
                        config.kFactor,
                        config.offsetMs
                    )
                },
                fontFamily = JetBrainsMonoFontFamily,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary
            )

            LabeledSlider(
                label = "Sensitivity Slope (k)",
                valueText = String.format(Locale.US, "%.2f ms/px", config.kFactor),
                value = config.kFactor,
                range = 0.50f..2.80f,
                onValueChange = onKFactorChange,
                testTag = "slider_k_factor"
            )

            LabeledSlider(
                label = "Touch Latency Offset (b)",
                valueText = "${config.offsetMs.roundToInt()} ms",
                value = config.offsetMs,
                range = -80f..180f,
                onValueChange = onOffsetMsChange,
                testTag = "slider_offset_ms"
            )

            if (config.formulaMode == FormulaMode.QUADRATIC) {
                LabeledSlider(
                    label = "Quadratic Drag Term (a)",
                    valueText = String.format(Locale.US, "%.5f", config.quadFactor),
                    value = config.quadFactor,
                    range = -0.0010f..0.0015f,
                    onValueChange = onQuadFactorChange,
                    testTag = "slider_quad_factor"
                )
            }

            LabeledSlider(
                label = "Pawn RGB Tolerance",
                valueText = "${config.pawnColorTolerance}",
                value = config.pawnColorTolerance.toFloat(),
                range = 15f..95f,
                onValueChange = { onPawnToleranceChange(it.roundToInt()) },
                testTag = "slider_pawn_tolerance"
            )

            LabeledSlider(
                label = "Block Edge Contrast Threshold",
                valueText = "${config.bgDiffThreshold}",
                value = config.bgDiffThreshold.toFloat(),
                range = 10f..80f,
                onValueChange = { onBgThresholdChange(it.roundToInt()) },
                testTag = "slider_bg_threshold"
            )

            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Box(modifier = Modifier.weight(1f)) {
                    LabeledSlider(
                        label = "ROI Top",
                        valueText = "${(config.roiTopFraction * 100).roundToInt()}%",
                        value = config.roiTopFraction,
                        range = 0.10f..0.55f,
                        onValueChange = onRoiTopChange,
                        testTag = "slider_roi_top"
                    )
                }
                Box(modifier = Modifier.weight(1f)) {
                    LabeledSlider(
                        label = "ROI Bottom",
                        valueText = "${(config.roiBottomFraction * 100).roundToInt()}%",
                        value = config.roiBottomFraction,
                        range = 0.45f..0.92f,
                        onValueChange = onRoiBottomChange,
                        testTag = "slider_roi_bottom"
                    )
                }
            }
        }
    }
}

@Composable
private fun LabeledSlider(
    label: String,
    valueText: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    onValueChange: (Float) -> Unit,
    testTag: String
) {
    Column {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(label, style = MaterialTheme.typography.bodyMedium)
            Text(
                text = valueText,
                fontFamily = JetBrainsMonoFontFamily,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.secondary
            )
        }
        Slider(
            value = value.coerceIn(range.start, range.endInclusive),
            onValueChange = onValueChange,
            valueRange = range,
            modifier = Modifier.testTag(testTag)
        )
    }
}
