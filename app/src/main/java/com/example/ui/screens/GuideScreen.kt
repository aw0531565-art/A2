package com.example.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Architecture
import androidx.compose.material.icons.filled.Calculate
import androidx.compose.material.icons.filled.CenterFocusStrong
import androidx.compose.material.icons.filled.Science
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.example.ui.theme.JetBrainsMonoFontFamily

@Composable
fun GuideScreen(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier.fillMaxSize(),
        contentAlignment = Alignment.TopCenter
    ) {
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .widthIn(max = 720.dp),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            item {
                Text(
                    text = "Implementation & Calibration Guide",
                    style = MaterialTheme.typography.headlineMedium,
                    color = MaterialTheme.colorScheme.onBackground
                )
                Text(
                    text = "How JumpAssist reads your installed game screen, measures block distance & size, and dispatches exact timed holds.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp)
                )
            }

            item {
                GuideSectionCard(
                    icon = Icons.Filled.Architecture,
                    title = "1. System Architecture",
                    accent = MaterialTheme.colorScheme.primary,
                    bullets = listOf(
                        "MediaProjection + VirtualDisplay + ImageReader captures RGBA_8888 frames inside a foreground service (mediaProjection type).",
                        "BlockVisionAnalyzer crops the playable ROI (28%–72% height) to skip HUD scores and bottom touch zones.",
                        "JumpAccessibilityService uses Path + GestureDescription.StrokeDescription(path, 0, durationMs) via dispatchGesture() on API 24+.",
                        "FloatingOverlayService hosts a draggable HUD pill with Step Once and Auto-Loop toggles over the target game."
                    )
                )
            }

            item {
                GuideSectionCard(
                    icon = Icons.Filled.CenterFocusStrong,
                    title = "2. Screen-Reading & Block Size Logic",
                    accent = MaterialTheme.colorScheme.secondary,
                    bullets = listOf(
                        "Smart Pawn Lock: Uses BFS connected-component clustering with silhouette & golden-collar scoring so wide dark blocks never steal the pawn lock, and projects true foot Y if occluded behind a tall block.",
                        "HUD Masking & Dual-Frame Stability: Automatically masks the Floating HUD bounds from frame analysis and verifies two consecutive frames match before auto-jumping.",
                        "Two-Pass Translucent Block Detection: Scans with standard contrast first, then checks upward and falls back to a shadow-rejecting translucent pass for half-transparent glass blocks."
                    )
                )
            }

            item {
                GuideSectionCard(
                    icon = Icons.Filled.Calculate,
                    title = "3. Distance-to-Touch-Duration Formula",
                    accent = MaterialTheme.colorScheme.tertiary,
                    codeBlock = """
                        D_raw  = hypot(Bx - Px, By - Py)
                        D_norm = D_raw * (1080f / screenWidthPx)

                        Linear:    T_ms = k * D_norm + offsetMs
                        Quadratic: T_ms = a * D_norm^2 + k * D_norm + offsetMs

                        Landing Tolerance: ±(0.35 * W_norm * k) ms
                    """.trimIndent(),
                    bullets = listOf(
                        "Resolution Normalization: Scaling D_raw to a 1080px reference width keeps your calibrated k slope consistent across 720p, 1080p, and 1440p displays.",
                        "Baseline Starting Values: k = 1.38 ms/px and offset = 40 ms (tuned from real multi-distance telemetry; auto-learns and adjusts in the Calibrate tab)."
                    )
                )
            }

            item {
                GuideSectionCard(
                    icon = Icons.Filled.Science,
                    title = "4. Device Calibration Workflow",
                    accent = MaterialTheme.colorScheme.primary,
                    bullets = listOf(
                        "Take one screenshot of your installed jumping game and open it in the Calibrate tab via Screenshot.",
                        "Tap Pick Color (eyedropper) and touch your pawn's body so the analyzer locks its exact RGB signature.",
                        "Adjust ROI Top/Bottom and Edge Threshold until the amber box tightly frames the next block's top face.",
                        "Auto-Training Loop: Enable Self-Training (on by default) so JumpAssist checks your landing position right after each jump and auto-tunes k and offsetMs.",
                        "Manual Nudge & Data Export: Use '◀ Too Short' or 'Too Far ▶' on the Floating HUD if needed, or tap 'Copy Training Data' on the Setup tab after a run to share exact telemetry for custom tuning."
                    )
                )
            }
        }
    }
}

@Composable
private fun GuideSectionCard(
    icon: ImageVector,
    title: String,
    accent: Color,
    bullets: List<String>,
    codeBlock: String? = null
) {
    Card(
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(16.dp))
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(accent.copy(alpha = 0.14f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(icon, contentDescription = null, tint = accent, modifier = Modifier.size(20.dp))
                }
                Text(title, style = MaterialTheme.typography.titleLarge)
            }

            if (codeBlock != null) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(Color(0xFF070C14))
                        .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(12.dp))
                        .padding(12.dp)
                ) {
                    Text(
                        text = codeBlock,
                        fontFamily = JetBrainsMonoFontFamily,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }

            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                bullets.forEach { bullet ->
                    Text(
                        text = "• $bullet",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}
