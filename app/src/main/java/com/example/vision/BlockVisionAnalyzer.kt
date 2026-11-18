package com.example.vision

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.Shader
import com.example.model.BlockBounds
import com.example.model.BotConfig
import com.example.model.FormulaMode
import com.example.model.PointF2D
import com.example.model.VisionAnalysisResult
import com.example.training.JumpTrainer
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.roundToLong

object BlockVisionAnalyzer {

    private data class PawnCandidate(
        val xs: List<Int>,
        val ys: List<Int>,
        val minX: Int,
        val maxX: Int,
        val minY: Int,
        val maxY: Int,
        val collarHits: Int,
        val collarCenterY: Float,
        val score: Int
    )

    private data class BlockTrace(
        val apexX: Float,
        val apexY: Int,
        val widestRowY: Int,
        val maxTopWidth: Int,
        val widestLeftX: Float,
        val widestRightX: Float,
        val lastTopRowY: Int
    )

    private data class SourcePlatformStance(
        val leftX: Float,
        val rightX: Float,
        val centerX: Float,
        val centerY: Float,
        val widthPx: Float,
        val stance: String,
        val offsetFrac: Float
    )

    data class UpperRoiSnapshot(
        val gridWidth: Int,
        val gridHeight: Int,
        val stepPx: Int,
        val roiTop: Int,
        val roiBottom: Int,
        val pawnX: Float,
        val pawnY: Float,
        val pixels: IntArray
    )

    fun analyze(
        bitmap: Bitmap,
        config: BotConfig,
        excludedRect: Rect? = null,
        preferredTargetHint: PointF2D? = null
    ): VisionAnalysisResult {
        val startNs = System.nanoTime()
        val width = bitmap.width
        val height = bitmap.height

        if (width < 40 || height < 40) {
            return VisionAnalysisResult(
                imageWidth = width,
                imageHeight = height,
                roiTopY = 0f,
                roiBottomY = height.toFloat(),
                pawnFound = false,
                pawnCenterX = 0f,
                pawnBaseY = 0f,
                pawnPixelCount = 0,
                blockFound = false,
                blockBounds = null,
                rawDistancePx = 0f,
                normalizedDistancePx = 0f,
                computedDurationMs = 0L,
                safeMarginMs = 0L,
                analysisTimeMs = 0L,
                statusMessage = "Frame too small for analysis"
            )
        }

        val roiTop = (height * config.roiTopFraction.coerceIn(0.05f, 0.70f)).roundToInt()
        val roiBottom = (height * config.roiBottomFraction.coerceIn(0.30f, 0.95f)).roundToInt()
            .coerceAtLeast(roiTop + 20)
            .coerceAtMost(height - 1)

        val roiHeight = roiBottom - roiTop + 1
        val pixels = IntArray(width * roiHeight)
        bitmap.getPixels(pixels, 0, width, 0, roiTop, width, roiHeight)

        val step = max(1, width / 540)
        val marginX = max(8, (width * 0.03f).roundToInt())
        val bgThresholdSq = config.bgDiffThreshold * config.bgDiffThreshold * 3

        if (isGameOverOrModalOverlay(pixels, width, roiHeight, marginX, excludedRect)) {
            val elapsedMs = (System.nanoTime() - startNs) / 1_000_000L
            return VisionAnalysisResult(
                imageWidth = width,
                imageHeight = height,
                roiTopY = roiTop.toFloat(),
                roiBottomY = roiBottom.toFloat(),
                pawnFound = false,
                pawnCenterX = 0f,
                pawnBaseY = 0f,
                pawnPixelCount = 0,
                blockFound = false,
                blockBounds = null,
                rawDistancePx = 0f,
                normalizedDistancePx = 0f,
                computedDurationMs = 0L,
                safeMarginMs = 0L,
                analysisTimeMs = elapsedMs,
                statusMessage = "Game Over / popup overlay — waiting replay"
            )
        }

        // Step 1: Detect the Pawn using connected-component clustering + collar/silhouette scoring
        val pawn = detectPawn(
            pixels = pixels,
            width = width,
            height = height,
            roiTop = roiTop,
            roiHeight = roiHeight,
            marginX = marginX,
            step = step,
            config = config,
            bgThresholdSq = bgThresholdSq,
            excludedRect = excludedRect
        )

        if (pawn == null) {
            val elapsedMs = (System.nanoTime() - startNs) / 1_000_000L
            return VisionAnalysisResult(
                imageWidth = width,
                imageHeight = height,
                roiTopY = roiTop.toFloat(),
                roiBottomY = roiBottom.toFloat(),
                pawnFound = false,
                pawnCenterX = 0f,
                pawnBaseY = 0f,
                pawnPixelCount = 0,
                blockFound = false,
                blockBounds = null,
                rawDistancePx = 0f,
                normalizedDistancePx = 0f,
                computedDurationMs = 0L,
                safeMarginMs = 0L,
                analysisTimeMs = elapsedMs,
                statusMessage = "Pawn not found. Sample pawn color in Calibrate."
            )
        }

        val scoreReading = ScoreDigitReader.readScore(bitmap, excludedRect)

        val sortedXs = pawn.xs.sorted()
        val sortedYs = pawn.ys.sorted()
        val p10X = sortedXs[(sortedXs.size * 0.10f).toInt().coerceIn(0, sortedXs.lastIndex)]
        val p90X = sortedXs[(sortedXs.size * 0.90f).toInt().coerceIn(0, sortedXs.lastIndex)]
        val p05Y = sortedYs[(sortedYs.size * 0.05f).toInt().coerceIn(0, sortedYs.lastIndex)]
        val p95Y = sortedYs[(sortedYs.size * 0.95f).toInt().coerceIn(0, sortedYs.lastIndex)]

        val pawnCenterX = (p10X + p90X) / 2f
        val pawnTopY = p05Y.toFloat()
        val observedHeight = (p95Y - p05Y).toFloat()
        val expectedPawnHeight = width * 0.164f
        val coneVisibleBelowCollar = pawn.collarHits >= 3 &&
            pawn.collarCenterY > 0f &&
            (p95Y - pawn.collarCenterY) >= (width * 0.052f)
        val isBottomOccluded = !coneVisibleBelowCollar && observedHeight < expectedPawnHeight * 0.72f
        val pawnBaseY = if (isBottomOccluded) {
            // Pawn lower cone is genuinely occluded behind a tall foreground block (e.g., roof/chest);
            // project true resting foot from the golden collar or head top.
            if (pawn.collarHits >= 3 && pawn.collarCenterY > 0f) {
                (pawn.collarCenterY + width * 0.095f).coerceAtMost(roiBottom.toFloat())
            } else {
                (pawnTopY + expectedPawnHeight).coerceAtMost(roiBottom.toFloat())
            }
        } else {
            p95Y.toFloat()
        }

        val pawnWidthEstimate = (p90X - p10X).coerceAtLeast((width * 0.045f).roundToInt())
        val pawnExclusionRadius = max((pawnWidthEstimate * 0.85f).roundToInt(), (width * 0.048f).roundToInt())

        // Step 2: Three-pass target block detection (opaque, translucent/glass, and subtle background-matching blocks)
        val minRunLength = max(5, (width * 0.010f).roundToInt())
        val minValidBlockWidth = max(40, (width * 0.058f).roundToInt())
        val minValidBlockHeight = max(16, (height * 0.011f).roundToInt())
        val translucentThreshold = max(12, (config.bgDiffThreshold * 0.36f).roundToInt())
        val translucentThresholdSq = translucentThreshold * translucentThreshold * 3
        val subtleThreshold = max(8, (config.bgDiffThreshold * 0.24f).roundToInt())
        val subtleThresholdSq = subtleThreshold * subtleThreshold * 3

        val preSourceStance = detectSourcePlatformAndStance(
            pixels = pixels,
            width = width,
            roiTop = roiTop,
            roiHeight = roiHeight,
            marginX = marginX,
            pawnCenterX = pawnCenterX,
            pawnBaseY = pawnBaseY,
            pawnWidthEstimate = pawnWidthEstimate,
            targetCenterX = pawnCenterX,
            excludedRect = excludedRect,
            thresholdSq = translucentThresholdSq
        )

        var block = scanForTargetBlock(
            pixels = pixels,
            width = width,
            roiTop = roiTop,
            roiHeight = roiHeight,
            marginX = marginX,
            step = step,
            pawnCenterX = pawnCenterX,
            pawnTopY = pawnTopY,
            pawnBaseY = pawnBaseY,
            pawnExclusionRadius = pawnExclusionRadius,
            excludedRect = excludedRect,
            thresholdSq = bgThresholdSq,
            minRunLength = minRunLength,
            minValidBlockWidth = minValidBlockWidth,
            minValidBlockHeight = minValidBlockHeight,
            rejectDarkShadows = true,
            requireSharpBoundary = true,
            allowShadowLeftOffset = false,
            preferredTargetHint = preferredTargetHint,
            sourcePlatform = preSourceStance
        )

        if (block != null) {
            // Check upward for a semi-transparent / subtle top half above a colored lower half
            block = extendTranslucentTopApex(
                pixels = pixels,
                width = width,
                roiHeight = roiHeight,
                marginX = marginX,
                excludedRect = excludedRect,
                roiTop = roiTop,
                baseBlock = block,
                translucentThresholdSq = translucentThresholdSq,
                minValidBlockWidth = minValidBlockWidth
            )
        } else {
            // Pass 2: Translucent / chromatic low-contrast block fallback (rejecting neutral drop shadows & smooth sky glow)
            block = scanForTargetBlock(
                pixels = pixels,
                width = width,
                roiTop = roiTop,
                roiHeight = roiHeight,
                marginX = marginX,
                step = step,
                pawnCenterX = pawnCenterX,
                pawnTopY = pawnTopY,
                pawnBaseY = pawnBaseY,
                pawnExclusionRadius = pawnExclusionRadius,
                excludedRect = excludedRect,
                thresholdSq = translucentThresholdSq,
                minRunLength = minRunLength,
                minValidBlockWidth = minValidBlockWidth,
                minValidBlockHeight = minValidBlockHeight,
                rejectDarkShadows = true,
                requireSharpBoundary = true,
                allowShadowLeftOffset = true,
                preferredTargetHint = preferredTargetHint,
                sourcePlatform = preSourceStance
            )
        }

        if (block == null) {
            // Pass 3: Background-matching subtle cube fallback (verifies internal 3D shading contrast & sharp edges)
            block = scanForTargetBlock(
                pixels = pixels,
                width = width,
                roiTop = roiTop,
                roiHeight = roiHeight,
                marginX = marginX,
                step = step,
                pawnCenterX = pawnCenterX,
                pawnTopY = pawnTopY,
                pawnBaseY = pawnBaseY,
                pawnExclusionRadius = pawnExclusionRadius,
                excludedRect = excludedRect,
                thresholdSq = subtleThresholdSq,
                minRunLength = minRunLength,
                minValidBlockWidth = minValidBlockWidth,
                minValidBlockHeight = max(minValidBlockHeight, (height * 0.015f).roundToInt()),
                rejectDarkShadows = true,
                requireInternalContrast = true,
                requireSharpBoundary = true,
                allowShadowLeftOffset = true,
                preferredTargetHint = preferredTargetHint,
                sourcePlatform = preSourceStance
            )
        }

        if (block == null) {
            // Pass 4: Dark charcoal/slate/indigo 3D block fallback (only when bright/subtle passes miss; requires 3D face contrast + sharp edges)
            block = scanForTargetBlock(
                pixels = pixels,
                width = width,
                roiTop = roiTop,
                roiHeight = roiHeight,
                marginX = marginX,
                step = step,
                pawnCenterX = pawnCenterX,
                pawnTopY = pawnTopY,
                pawnBaseY = pawnBaseY,
                pawnExclusionRadius = pawnExclusionRadius,
                excludedRect = excludedRect,
                thresholdSq = max(translucentThresholdSq, bgThresholdSq / 2),
                minRunLength = minRunLength,
                minValidBlockWidth = minValidBlockWidth,
                minValidBlockHeight = max(minValidBlockHeight, (height * 0.015f).roundToInt()),
                rejectDarkShadows = false,
                requireInternalContrast = true,
                requireSharpBoundary = true,
                allowShadowLeftOffset = true,
                preferredTargetHint = preferredTargetHint,
                sourcePlatform = preSourceStance
            )
        }

        if (block == null) {
            val elapsedMs = (System.nanoTime() - startNs) / 1_000_000L
            return VisionAnalysisResult(
                imageWidth = width,
                imageHeight = height,
                roiTopY = roiTop.toFloat(),
                roiBottomY = roiBottom.toFloat(),
                pawnFound = true,
                pawnCenterX = pawnCenterX,
                pawnBaseY = pawnBaseY,
                pawnPixelCount = pawn.xs.size,
                blockFound = false,
                blockBounds = null,
                rawDistancePx = 0f,
                normalizedDistancePx = 0f,
                computedDurationMs = 0L,
                safeMarginMs = 0L,
                analysisTimeMs = elapsedMs,
                statusMessage = "Pawn locked at (${pawnCenterX.roundToInt()}, ${pawnBaseY.roundToInt()}), but next block not found.",
                scoreReading = scoreReading
            )
        }

        // Step 3: Compute isometric geometric landing center & structural profile
        val isIsometricDiamond = block.widestRowY > block.apexY + 6
        val midpointX = (block.widestLeftX + block.widestRightX) / 2f
        val blockCenterX = block.apexX * 0.35f + midpointX * 0.65f
        val blockCenterYRel = if (isIsometricDiamond) {
            val projectedEquator = block.apexY + block.maxTopWidth * 0.285f
            val cappedWidestY = min(block.widestRowY.toFloat(), block.apexY + block.maxTopWidth * 0.35f)
            (cappedWidestY * 0.5f + projectedEquator * 0.5f)
        } else {
            (block.apexY + block.lastTopRowY) / 2f
        }

        val blockCenterY = roiTop + blockCenterYRel
        val blockTopY = (roiTop + block.apexY).toFloat()
        val blockBottomY = (roiTop + block.lastTopRowY).toFloat()
        val blockWidthPx = block.maxTopWidth.coerceAtLeast(minRunLength).toFloat()
        val blockHeightPx = (block.lastTopRowY - block.apexY).coerceAtLeast(1).toFloat()

        val scaleTo1080 = 1080f / width.toFloat()
        val normalizedBlockWidth = blockWidthPx * scaleTo1080
        val targetBlockProfile = classifyBlockProfile(normalizedBlockWidth)
        val targetColorGroup = classifyBlockTopColorGroup(
            pixels = pixels,
            width = width,
            roiTop = roiTop,
            roiHeight = roiHeight,
            centerX = blockCenterX,
            centerY = blockCenterY,
            topY = blockTopY
        )

        val sourceStance = detectSourcePlatformAndStance(
            pixels = pixels,
            width = width,
            roiTop = roiTop,
            roiHeight = roiHeight,
            marginX = marginX,
            pawnCenterX = pawnCenterX,
            pawnBaseY = pawnBaseY,
            pawnWidthEstimate = pawnWidthEstimate,
            targetCenterX = blockCenterX,
            excludedRect = excludedRect,
            thresholdSq = translucentThresholdSq,
            targetBlock = block
        )

        // Step 4: Distance & Contextual Duration Calculation
        val dx = blockCenterX - pawnCenterX
        val dy = blockCenterY - pawnBaseY
        val rawDistancePx = hypot(dx, dy)
        val normalizedDistancePx = rawDistancePx * scaleTo1080

        val baseDurationMs = computeDurationMs(normalizedDistancePx, config)
        val contextBiasMs = JumpTrainer.computeContextAdjustmentMs(
            config = config,
            normalizedDistancePx = normalizedDistancePx,
            targetBlockProfile = targetBlockProfile,
            targetColorGroup = targetColorGroup,
            pawnStance = sourceStance.stance
        )
        val durationMs = (baseDurationMs + contextBiasMs).coerceIn(config.minPressMs, config.maxPressMs)
        val safeMarginMs = (config.kFactor * (normalizedBlockWidth * 0.35f)).roundToLong().coerceAtLeast(5L)

        val elapsedMs = (System.nanoTime() - startNs) / 1_000_000L
        val biasTag = if (contextBiasMs != 0L) " (${if (contextBiasMs > 0) "+" else ""}${contextBiasMs}ms)" else ""
        return VisionAnalysisResult(
            imageWidth = width,
            imageHeight = height,
            roiTopY = roiTop.toFloat(),
            roiBottomY = roiBottom.toFloat(),
            pawnFound = true,
            pawnCenterX = pawnCenterX,
            pawnBaseY = pawnBaseY,
            pawnPixelCount = pawn.xs.size,
            blockFound = true,
            blockBounds = BlockBounds(
                apex = PointF2D(block.apexX, blockTopY),
                center = PointF2D(blockCenterX, blockCenterY),
                leftEdgeX = block.widestLeftX,
                rightEdgeX = block.widestRightX,
                topY = blockTopY,
                bottomY = blockBottomY,
                widthPx = blockWidthPx,
                heightPx = blockHeightPx
            ),
            rawDistancePx = rawDistancePx,
            normalizedDistancePx = normalizedDistancePx,
            computedDurationMs = durationMs,
            safeMarginMs = safeMarginMs,
            analysisTimeMs = elapsedMs,
            statusMessage = "Locked $targetBlockProfile($targetColorGroup) · Stance=${sourceStance.stance} · D=${rawDistancePx.roundToInt()}px → Hold ${durationMs}ms$biasTag",
            sourceLeftX = sourceStance.leftX,
            sourceRightX = sourceStance.rightX,
            sourceCenterX = sourceStance.centerX,
            sourceCenterY = sourceStance.centerY,
            sourceWidthPx = sourceStance.widthPx,
            pawnStance = sourceStance.stance,
            pawnStanceOffsetFrac = sourceStance.offsetFrac,
            targetBlockProfile = targetBlockProfile,
            targetColorGroup = targetColorGroup,
            appliedContextBiasMs = contextBiasMs,
            scoreReading = scoreReading
        )
    }

    data class FrameDiagnostics(
        val skyTopHex: String,
        val skyMidHex: String,
        val pawnSampleHex: String,
        val blockTopHex: String = "",
        val blockSideHex: String = ""
    )

    fun sampleFrameDiagnostics(
        bitmap: Bitmap,
        roiTopY: Float,
        roiBottomY: Float,
        pawnX: Float,
        pawnY: Float,
        blockX: Float = 0f,
        blockY: Float = 0f,
        blockBottomY: Float = 0f
    ): FrameDiagnostics {
        val w = bitmap.width
        val h = bitmap.height
        if (w < 10 || h < 10) return FrameDiagnostics("#000000", "#000000", "#000000")
        fun hexAt(x: Int, y: Int): String {
            val px = bitmap.getPixel(x.coerceIn(0, w - 1), y.coerceIn(0, h - 1)) and 0xFFFFFF
            return String.format(java.util.Locale.US, "#%06X", px)
        }
        val topY = roiTopY.roundToInt().coerceIn(0, h - 1)
        val midY = ((roiTopY + roiBottomY) * 0.5f).roundToInt().coerceIn(0, h - 1)
        val samplePawnY = (pawnY - w * 0.045f).roundToInt().coerceIn(0, h - 1)
        val hasBlock = blockX > 1f && blockY > 1f
        val sideY = if (blockBottomY > blockY + 4f) blockBottomY.roundToInt() else (blockY + w * 0.055f).roundToInt()
        return FrameDiagnostics(
            skyTopHex = hexAt((w * 0.04f).roundToInt(), topY),
            skyMidHex = hexAt((w * 0.96f).roundToInt(), midY),
            pawnSampleHex = if (pawnX > 1f) hexAt(pawnX.roundToInt(), samplePawnY) else "#000000",
            blockTopHex = if (hasBlock) hexAt(blockX.roundToInt(), blockY.roundToInt()) else "",
            blockSideHex = if (hasBlock) hexAt((blockX - w * 0.04f).roundToInt(), sideY) else ""
        )
    }

    fun captureUpperRoiSnapshot(
        bitmap: Bitmap,
        roiTopY: Float,
        roiBottomY: Float,
        pawnX: Float,
        pawnY: Float
    ): UpperRoiSnapshot? {
        val w = bitmap.width
        val h = bitmap.height
        if (w < 64 || h < 64 || pawnY <= roiTopY + 32f) return null
        val stepPx = max(6, w / 90)
        val top = roiTopY.roundToInt().coerceIn(0, h - 1)
        val bottom = min(roiBottomY.roundToInt(), (pawnY - w * 0.05f).roundToInt()).coerceIn(top + stepPx, h - 1)
        val gw = ((w - 1) / stepPx) + 1
        val gh = ((bottom - top) / stepPx) + 1
        val grid = IntArray(gw * gh)
        val rowBuf = IntArray(w)
        var gy = 0
        while (gy < gh) {
            val sy = (top + gy * stepPx).coerceAtMost(h - 1)
            bitmap.getPixels(rowBuf, 0, w, 0, sy, w, 1)
            var gx = 0
            val off = gy * gw
            while (gx < gw) {
                val sx = (gx * stepPx).coerceAtMost(w - 1)
                grid[off + gx] = rowBuf[sx]
                gx++
            }
            gy++
        }
        return UpperRoiSnapshot(gw, gh, stepPx, top, bottom, pawnX, pawnY, grid)
    }

    /**
     * Detects the centroid of a newly arrived block in the upper board when comparing
     * a post-touchdown snapshot (before/as the new block dropped in) with the current settled frame.
     * Returns null if camera pan shifted the pawn significantly so panning isn't mistaken for a new block.
     */
    fun detectNewlyAppearedHint(
        prevSnapshot: UpperRoiSnapshot?,
        currentBitmap: Bitmap,
        currPawnX: Float,
        currPawnY: Float
    ): PointF2D? {
        if (prevSnapshot == null) return null
        val w = currentBitmap.width
        val h = currentBitmap.height
        if (abs(prevSnapshot.pawnX - currPawnX) > w * 0.045f || abs(prevSnapshot.pawnY - currPawnY) > w * 0.045f) {
            return null
        }
        val stepPx = prevSnapshot.stepPx
        val pawnExclR = w * 0.14f
        val rowBuf = IntArray(w)
        var sumX = 0.0
        var sumY = 0.0
        var hits = 0
        for (gy in 1 until prevSnapshot.gridHeight - 1) {
            val sy = (prevSnapshot.roiTop + gy * stepPx).coerceIn(0, h - 1)
            currentBitmap.getPixels(rowBuf, 0, w, 0, sy, w, 1)
            val off = gy * prevSnapshot.gridWidth
            for (gx in 1 until prevSnapshot.gridWidth - 1) {
                val sx = (gx * stepPx).coerceAtMost(w - 1)
                if (hypot(sx - currPawnX, sy - currPawnY) < pawnExclR) continue
                val dSq = colorDistSq(prevSnapshot.pixels[off + gx], rowBuf[sx])
                if (dSq > 1100) {
                    sumX += sx
                    sumY += sy
                    hits++
                }
            }
        }
        if (hits < 6) return null
        return PointF2D((sumX / hits).toFloat(), (sumY / hits).toFloat())
    }

    private fun detectPawn(
        pixels: IntArray,
        width: Int,
        height: Int,
        roiTop: Int,
        roiHeight: Int,
        marginX: Int,
        step: Int,
        config: BotConfig,
        bgThresholdSq: Int,
        excludedRect: Rect?
    ): PawnCandidate? {
        val targetR = (config.pawnColorRgb shr 16) and 0xFF
        val targetG = (config.pawnColorRgb shr 8) and 0xFF
        val targetB = config.pawnColorRgb and 0xFF
        val pawnTolSq = config.pawnColorTolerance * config.pawnColorTolerance * 3

        val cellSize = max(12, width / 48)
        val cellBuckets = HashMap<Long, Pair<MutableList<Int>, MutableList<Int>>>()

        val requireCoolChroma = targetB >= targetR + 12 && targetR < 110

        var y = 0
        while (y < roiHeight) {
            val rowOffset = y * width
            val absY = roiTop + y
            val leftBg = pixels[rowOffset + marginX / 2]
            val rightBg = pixels[rowOffset + width - 1 - marginX / 2]
            var x = marginX
            while (x < width - marginX) {
                if (!isExcluded(x, absY, excludedRect)) {
                    val c = pixels[rowOffset + x]
                    if (isForegroundPixel(c, leftBg, rightBg, x, width, bgThresholdSq / 5)) {
                        val r = (c shr 16) and 0xFF
                        val g = (c shr 8) and 0xFF
                        val b = c and 0xFF
                        val pawnShadeOk = !requireCoolChroma || (
                            (b - min(r, g)) >= 4 &&
                                (r - g) <= 22 &&
                                (b - g) <= 58 &&
                                b <= 145
                            )
                        if (pawnShadeOk) {
                            val dr = r - targetR
                            val dg = g - targetG
                            val db = b - targetB
                            if (dr * dr + dg * dg + db * db <= pawnTolSq) {
                                val gx = x / cellSize
                                val gy = absY / cellSize
                                val key = (gx.toLong() shl 32) or (gy.toLong() and 0xFFFFFFFFL)
                                val pair = cellBuckets.getOrPut(key) {
                                    ArrayList<Int>(16) to ArrayList(16)
                                }
                                pair.first.add(x)
                                pair.second.add(absY)
                            }
                        }
                    }
                }
                x += step
            }
            y += step
        }

        if (cellBuckets.isEmpty()) return null

        val visited = HashSet<Long>(cellBuckets.size)
        val queue = ArrayDeque<Long>()
        var bestCandidate: PawnCandidate? = null

        for (startKey in cellBuckets.keys) {
            if (!visited.add(startKey)) continue
            queue.clear()
            queue.add(startKey)

            val compXs = ArrayList<Int>(128)
            val compYs = ArrayList<Int>(128)
            var minX = Int.MAX_VALUE
            var maxX = Int.MIN_VALUE
            var minY = Int.MAX_VALUE
            var maxY = Int.MIN_VALUE

            while (queue.isNotEmpty()) {
                val key = queue.removeFirst()
                val data = cellBuckets[key] ?: continue
                for (i in data.first.indices) {
                    val px = data.first[i]
                    val py = data.second[i]
                    compXs.add(px)
                    compYs.add(py)
                    if (px < minX) minX = px
                    if (px > maxX) maxX = px
                    if (py < minY) minY = py
                    if (py > maxY) maxY = py
                }

                val gx = (key shr 32).toInt()
                val gy = (key and 0xFFFFFFFFL).toInt()
                for (dx in -1..1) {
                    for (dy in -1..1) {
                        if (dx == 0 && dy == 0) continue
                        val neighbor = ((gx + dx).toLong() shl 32) or ((gy + dy).toLong() and 0xFFFFFFFFL)
                        if (cellBuckets.containsKey(neighbor) && visited.add(neighbor)) {
                            queue.add(neighbor)
                        }
                    }
                }
            }

            val minPawnPixels = max(24, (width * height) / 42000)
            val maxPawnPixels = max(3500, (width * height * 0.0038f).roundToInt())
            if (compXs.size !in minPawnPixels..maxPawnPixels) continue

            val compW = maxX - minX
            val compH = maxY - minY

            // Reject massive wide blocks (e.g., dark block walls)
            if (compW > width * 0.14f) continue
            if (compW > compH * 1.6f && compW > width * 0.08f) continue

            // Check for warm gold collar signature near this component
            var collarHits = 0
            var collarSumY = 0L
            val scanMinX = (minX - 6).coerceIn(marginX, width - marginX - 1)
            val scanMaxX = (maxX + 6).coerceIn(marginX, width - marginX - 1)
            val scanMinY = (minY - roiTop - 10).coerceIn(0, roiHeight - 1)
            val scanMaxY = (maxY - roiTop + 28).coerceIn(0, roiHeight - 1)
            var cy = scanMinY
            while (cy <= scanMaxY) {
                val rowOff = cy * width
                var cx = scanMinX
                while (cx <= scanMaxX) {
                    val px = pixels[rowOff + cx]
                    val r = (px shr 16) and 0xFF
                    val g = (px shr 8) and 0xFF
                    val b = px and 0xFF
                    if (r > 160 && g > 115 && b < 130 && (r - b) > 50) {
                        collarHits++
                        collarSumY += (roiTop + cy)
                    }
                    cx += 2
                }
                cy += 2
            }

            // Reject off-playfield edges, ROI-bottom clipped objects, or warm brown/orange non-pawn faces
            val roiBottomLimit = roiTop + roiHeight - (width * 0.025f).roundToInt()
            val clippedAtRoiBottom = maxY >= roiBottomLimit
            val compCenterX = (minX + maxX) * 0.5f
            val extremeSideMargin = compCenterX < width * 0.145f || compCenterX > width * 0.855f
            if (clippedAtRoiBottom || extremeSideMargin) continue
            if (collarHits < 2 && compXs.size < max(75, width / 8)) continue
            if (collarHits < 3 && (compXs.size < max(90, width / 7) || compH < (width * 0.115f).roundToInt() || maxY >= roiTop + roiHeight - (width * 0.045f).roundToInt())) continue

            val enforceCoolPurple = targetB >= targetR && targetR < 110
            if (enforceCoolPurple && !hasCoolPurpleBodySignature(pixels, roiTop, width, compXs, compYs)) {
                continue
            }

            val collarCenterY = if (collarHits > 0) collarSumY.toFloat() / collarHits else -1f
            var headAboveCollar = 0
            var coneBelowCollar = 0
            if (collarHits >= 3 && collarCenterY > 0f) {
                for (i in compXs.indices) {
                    if (abs(compXs[i] - compCenterX) <= width * 0.040f) {
                        val dyCollar = compYs[i] - collarCenterY
                        if (dyCollar in (-width * 0.065f)..(-3f)) {
                            headAboveCollar++
                        } else if (dyCollar in 3f..(width * 0.130f)) {
                            coneBelowCollar++
                        }
                    }
                }
            }
            val validCollarPlacement = collarHits >= 3 && compW <= width * 0.115f && (
                (headAboveCollar >= 5 && coneBelowCollar >= 4) ||
                    (coneBelowCollar >= 12) ||
                    (headAboveCollar >= 8 && compW <= width * 0.095f)
                )
            val effectiveCollarHits = if (validCollarPlacement) collarHits else 0

            var score = compXs.size.coerceAtMost(2800)
            if (compW <= width * 0.09f) score += 1200
            if (compH >= compW * 1.1f) score += 1000
            if (validCollarPlacement) score += 6000
            if (compW > width * 0.105f) score -= 3000

            val candidate = PawnCandidate(
                xs = compXs,
                ys = compYs,
                minX = minX,
                maxX = maxX,
                minY = minY,
                maxY = maxY,
                collarHits = effectiveCollarHits,
                collarCenterY = collarCenterY,
                score = score
            )
            if (bestCandidate == null || candidate.score > bestCandidate.score) {
                bestCandidate = candidate
            }
        }

        if (bestCandidate != null && bestCandidate.collarHits >= 3) {
            return bestCandidate
        }

        val collarRecovered = detectPawnByCollarAnchor(
            pixels = pixels,
            width = width,
            roiTop = roiTop,
            roiHeight = roiHeight,
            marginX = marginX,
            pawnColorRgb = config.pawnColorRgb,
            pawnTolSq = pawnTolSq,
            excludedRect = excludedRect
        )
        return collarRecovered ?: bestCandidate
    }

    /**
     * Fallback pawn detector anchored by the compact warm-gold collar ring between the dark head
     * sphere and the cone body. Recovers the pawn when adjacent dark brick/stone walls merge
     * with the cone inside coarse grid buckets.
     */
    private fun detectPawnByCollarAnchor(
        pixels: IntArray,
        width: Int,
        roiTop: Int,
        roiHeight: Int,
        marginX: Int,
        pawnColorRgb: Int,
        pawnTolSq: Int,
        excludedRect: Rect?
    ): PawnCandidate? {
        val targetR = (pawnColorRgb shr 16) and 0xFF
        val targetG = (pawnColorRgb shr 8) and 0xFF
        val targetB = pawnColorRgb and 0xFF

        val requireCoolChroma = targetB >= targetR + 12 && targetR < 110

        fun matchesPawn(px: Int): Boolean {
            val r = (px shr 16) and 0xFF
            val g = (px shr 8) and 0xFF
            val b = px and 0xFF
            if (requireCoolChroma && ((b - min(r, g)) < 4 || (r - g) > 22 || (b - g) > 58 || b > 145)) {
                return false
            }
            val dr = r - targetR
            val dg = g - targetG
            val db = b - targetB
            return dr * dr + dg * dg + db * db <= pawnTolSq
        }

        val goldCell = max(6, width / 110)
        val goldBuckets = HashMap<Long, MutableList<Int>>()
        val aboveProbeMin = max(3, (width * 0.005f).roundToInt())
        val aboveProbeMax = max(26, (width * 0.042f).roundToInt())
        val belowProbeMin = max(3, (width * 0.005f).roundToInt())
        val belowProbeMax = max(30, (width * 0.046f).roundToInt())
        var y = 0
        while (y < roiHeight) {
            val absY = roiTop + y
            val rowOff = y * width
            var x = marginX
            while (x < width - marginX) {
                if (!isExcluded(x, absY, excludedRect)) {
                    val px = pixels[rowOff + x]
                    val r = (px shr 16) and 0xFF
                    val g = (px shr 8) and 0xFF
                    val b = px and 0xFF
                    if (r > 150 && g > 105 && b < 135 && (r - b) > 42 && r >= g) {
                        var headHitsAbove = 0
                        var pyUp = (y - aboveProbeMin).coerceAtLeast(0)
                        val pyUpEnd = (y - aboveProbeMax).coerceAtLeast(0)
                        while (pyUp >= pyUpEnd) {
                            if (matchesPawn(pixels[pyUp * width + x])) {
                                headHitsAbove++
                            }
                            pyUp -= 2
                        }
                        var coneHitsBelow = 0
                        if (headHitsAbove >= 1) {
                            var pyDn = (y + belowProbeMin).coerceAtMost(roiHeight - 1)
                            val pyDnEnd = (y + belowProbeMax).coerceAtMost(roiHeight - 1)
                            while (pyDn <= pyDnEnd) {
                                if (matchesPawn(pixels[pyDn * width + x])) {
                                    coneHitsBelow++
                                }
                                pyDn += 2
                            }
                        }
                        // Accept standard collar between head & cone OR occluded-cone pawn with thick head sphere above
                        if ((headHitsAbove >= 1 && coneHitsBelow >= 1) || headHitsAbove >= 4) {
                            val key = ((x / goldCell).toLong() shl 32) or ((absY / goldCell).toLong() and 0xFFFFFFFFL)
                            goldBuckets.getOrPut(key) { ArrayList(8) }.add((x shl 16) or (y and 0xFFFF))
                        }
                    }
                }
                x += 2
            }
            y += 2
        }
        if (goldBuckets.isEmpty()) return null

        val maxCollarW = (width * 0.090f).roundToInt()
        val maxCollarH = (width * 0.055f).roundToInt()
        val maxHeadUp = (width * 0.062f).roundToInt()
        val maxHeadHalfW = (width * 0.032f).roundToInt()
        val maxConeDown = (width * 0.132f).roundToInt()
        val maxConeHalfW = (width * 0.044f).roundToInt()
        val maxConeRowWidth = (width * 0.068f).roundToInt()

        val visited = HashSet<Long>(goldBuckets.size)
        val queue = ArrayDeque<Long>()
        var best: PawnCandidate? = null

        for (startKey in goldBuckets.keys) {
            if (!visited.add(startKey)) continue
            queue.clear()
            queue.add(startKey)

            var gMinX = Int.MAX_VALUE
            var gMaxX = Int.MIN_VALUE
            var gMinRelY = Int.MAX_VALUE
            var gMaxRelY = Int.MIN_VALUE
            var goldHits = 0
            var goldSumX = 0L
            var goldSumY = 0L

            while (queue.isNotEmpty()) {
                val key = queue.removeFirst()
                val pts = goldBuckets[key] ?: continue
                for (packed in pts) {
                    val px = packed ushr 16
                    val py = packed and 0xFFFF
                    if (px < gMinX) gMinX = px
                    if (px > gMaxX) gMaxX = px
                    if (py < gMinRelY) gMinRelY = py
                    if (py > gMaxRelY) gMaxRelY = py
                    goldHits++
                    goldSumX += px
                    goldSumY += (roiTop + py)
                }
                val gx = (key shr 32).toInt()
                val gy = (key and 0xFFFFFFFFL).toInt()
                for (dx in -1..1) {
                    for (dy in -1..1) {
                        if (dx == 0 && dy == 0) continue
                        val neighbor = ((gx + dx).toLong() shl 32) or ((gy + dy).toLong() and 0xFFFFFFFFL)
                        if (goldBuckets.containsKey(neighbor) && visited.add(neighbor)) {
                            queue.add(neighbor)
                        }
                    }
                }
            }

            if (goldHits < 4) continue
            if ((gMaxX - gMinX) > maxCollarW || (gMaxRelY - gMinRelY) > maxCollarH) continue

            val collarCx = (goldSumX.toFloat() / goldHits).roundToInt()
            val collarRelY = (goldSumY.toFloat() / goldHits - roiTop).roundToInt()
            if (collarRelY < 6 || collarRelY >= roiHeight - 6) continue

            val xs = ArrayList<Int>(160)
            val ys = ArrayList<Int>(160)

            // Trace sphere head above the collar, rejecting wide dark walls above a yellow roof trim
            var headHits = 0
            var wideHeadWallRows = 0
            val headTopRelY = (collarRelY - maxHeadUp).coerceAtLeast(0)
            val headBotRelY = (gMinRelY - 1).coerceAtLeast(0)
            var hy = headTopRelY
            while (hy <= headBotRelY) {
                val absY = roiTop + hy
                val rowOff = hy * width
                val lxBound = (collarCx - maxHeadHalfW).coerceAtLeast(marginX)
                val rxBound = (collarCx + maxHeadHalfW).coerceAtMost(width - marginX - 1)
                var hMinX = Int.MAX_VALUE
                var hMaxX = Int.MIN_VALUE
                var rowH = 0
                for (hx in lxBound..rxBound step 2) {
                    if (!isExcluded(hx, absY, excludedRect) && matchesPawn(pixels[rowOff + hx])) {
                        if (hx < hMinX) hMinX = hx
                        if (hx > hMaxX) hMaxX = hx
                        xs.add(hx)
                        ys.add(absY)
                        headHits++
                        rowH++
                    }
                }
                if (rowH > 0 && hMinX <= lxBound + 2 && hMaxX >= rxBound - 2) {
                    val leftOut = (lxBound - 4).coerceAtLeast(marginX)
                    val rightOut = (rxBound + 4).coerceAtMost(width - marginX - 1)
                    if (matchesPawn(pixels[rowOff + leftOut]) && matchesPawn(pixels[rowOff + rightOut])) {
                        wideHeadWallRows++
                    }
                }
                hy += 2
            }
            if (wideHeadWallRows >= 3) continue

            // Trace cone body below the collar, stopping before blooming into dark block walls
            var coneHits = 0
            var emptyConeRows = 0
            val coneStartRelY = (gMaxRelY + 1).coerceAtMost(roiHeight - 1)
            val coneEndRelY = (collarRelY + maxConeDown).coerceAtMost(roiHeight - 1)
            var cy = coneStartRelY
            while (cy <= coneEndRelY) {
                val absY = roiTop + cy
                val rowOff = cy * width
                val lxBound = (collarCx - maxConeHalfW).coerceAtLeast(marginX)
                val rxBound = (collarCx + maxConeHalfW).coerceAtMost(width - marginX - 1)

                var rowMinX = Int.MAX_VALUE
                var rowMaxX = Int.MIN_VALUE
                var rowHits = 0
                for (cxPos in lxBound..rxBound step 2) {
                    if (!isExcluded(cxPos, absY, excludedRect) && matchesPawn(pixels[rowOff + cxPos])) {
                        if (cxPos < rowMinX) rowMinX = cxPos
                        if (cxPos > rowMaxX) rowMaxX = cxPos
                        rowHits++
                    }
                }

                // Check outer margin to ensure this row didn't bleed into a wide horizontal stone wall
                var wallBleedLeft = 0
                var wallBleedRight = 0
                if (rowHits > 0 && rowMinX <= lxBound + 2) {
                    var wx = lxBound - 2
                    while (wx >= max(marginX, collarCx - maxConeRowWidth)) {
                        if (matchesPawn(pixels[rowOff + wx])) wallBleedLeft += 2 else break
                        wx -= 2
                    }
                }
                if (rowHits > 0 && rowMaxX >= rxBound - 2) {
                    var wx = rxBound + 2
                    while (wx <= min(width - marginX - 1, collarCx + maxConeRowWidth)) {
                        if (matchesPawn(pixels[rowOff + wx])) wallBleedRight += 2 else break
                        wx += 2
                    }
                }
                val apparentRowW = if (rowHits > 0) (rowMaxX - rowMinX) + wallBleedLeft + wallBleedRight else 0
                if (apparentRowW > maxConeRowWidth) {
                    break
                }

                if (rowHits >= 2) {
                    emptyConeRows = 0
                    for (cxPos in lxBound..rxBound step 2) {
                        if (!isExcluded(cxPos, absY, excludedRect) && matchesPawn(pixels[rowOff + cxPos])) {
                            xs.add(cxPos)
                            ys.add(absY)
                            coneHits++
                        }
                    }
                } else if (cy - coneStartRelY > 6) {
                    emptyConeRows++
                    if (emptyConeRows >= 4) break
                }
                cy += 2
            }

            val standardCollarBody = headHits >= 9 && coneHits >= 12 && xs.size >= 42
            val occludedConeBody = headHits >= 14 && goldHits >= 5 && (gMaxX - gMinX) in 4..(width * 0.055f).roundToInt() && xs.size >= 24
            if (!standardCollarBody && !occludedConeBody) continue

            var pMinX = Int.MAX_VALUE
            var pMaxX = Int.MIN_VALUE
            var pMinY = Int.MAX_VALUE
            var pMaxY = Int.MIN_VALUE
            for (i in xs.indices) {
                val px = xs[i]
                val py = ys[i]
                if (px < pMinX) pMinX = px
                if (px > pMaxX) pMaxX = px
                if (py < pMinY) pMinY = py
                if (py > pMaxY) pMaxY = py
            }

            val candCx = (pMinX + pMaxX) * 0.5f
            if (pMaxY >= roiTop + roiHeight - (width * 0.025f).roundToInt()) continue
            if (candCx < width * 0.145f || candCx > width * 0.855f) continue
            if (targetB >= targetR && targetR < 110 && !hasCoolPurpleBodySignature(pixels, roiTop, width, xs, ys)) {
                continue
            }

            val score = (xs.size * 2) + (goldHits * 180) + (if (coneHits >= 12) 4500 else 3600)
            val cand = PawnCandidate(
                xs = xs,
                ys = ys,
                minX = pMinX,
                maxX = pMaxX,
                minY = pMinY,
                maxY = pMaxY,
                collarHits = goldHits,
                collarCenterY = goldSumY.toFloat() / goldHits,
                score = score
            )
            if (best == null || cand.score > best.score) {
                best = cand
            }
        }
        return best
    }

    private fun hasCoolPurpleBodySignature(
        pixels: IntArray,
        roiTop: Int,
        width: Int,
        xs: List<Int>,
        ys: List<Int>
    ): Boolean {
        if (xs.isEmpty()) return false
        val stride = max(1, xs.size / 64)
        var sumR = 0L
        var sumG = 0L
        var sumB = 0L
        var count = 0
        var i = 0
        while (i < xs.size) {
            val relY = ys[i] - roiTop
            val px = pixels[relY * width + xs[i]]
            sumR += (px shr 16) and 0xFF
            sumG += (px shr 8) and 0xFF
            sumB += px and 0xFF
            count++
            i += stride
        }
        if (count == 0) return false
        val avgR = sumR / count
        val avgG = sumG / count
        val avgB = sumB / count
        return avgR <= 102 && avgG <= 92 && avgB >= avgR + 6 && (avgB - avgG) >= 8
    }

    private fun isGameOverOrModalOverlay(
        pixels: IntArray,
        width: Int,
        roiHeight: Int,
        marginX: Int,
        excludedRect: Rect?
    ): Boolean {
        if (roiHeight < 40) return false
        val leftCol = (marginX / 2).coerceIn(0, width - 1)
        val rightCol = (width - 1 - marginX / 2).coerceIn(0, width - 1)
        var marginDivergence = 0
        var redCoralHits = 0
        var paleBlueHits = 0
        val probeFracs = floatArrayOf(0.05f, 0.14f, 0.24f, 0.34f, 0.44f, 0.54f)
        for (f in probeFracs) {
            val ry = (roiHeight * f).roundToInt().coerceIn(0, roiHeight - 1)
            val rowOff = ry * width
            val lp = pixels[rowOff + leftCol]
            val rp = pixels[rowOff + rightCol]
            if (colorDistSq(lp, rp) > 5500) {
                marginDivergence++
            }
            for (c in intArrayOf(lp, rp)) {
                val r = (c shr 16) and 0xFF
                val g = (c shr 8) and 0xFF
                val b = c and 0xFF
                if (r > 215 && g in 80..145 && b < 145) redCoralHits++
                if (r > 185 && g > 195 && b > 225) paleBlueHits++
            }
        }
        val bandStartY = (roiHeight * 0.04f).roundToInt().coerceAtLeast(0)
        val bandEndY = (roiHeight * 0.48f).roundToInt().coerceAtMost(roiHeight - 1)
        var cardCoral = 0
        var cardPaleBlue = 0
        var y = bandStartY
        while (y <= bandEndY) {
            val rowOff = y * width
            var x = (width * 0.22f).roundToInt()
            val xEnd = (width * 0.85f).roundToInt()
            while (x <= xEnd) {
                val c = pixels[rowOff + x]
                val r = (c shr 16) and 0xFF
                val g = (c shr 8) and 0xFF
                val b = c and 0xFF
                if (r > 220 && g in 85..140 && b in 80..140) cardCoral++
                if (r in 185..215 && g in 200..228 && b > 232) cardPaleBlue++
                x += 24
            }
            y += 20
        }
        if (cardCoral >= 6 && cardPaleBlue >= 10) return true
        if (redCoralHits >= 2 && paleBlueHits >= 2 && cardPaleBlue >= 6) return true
        return marginDivergence >= 4 && cardPaleBlue >= 8
    }

    private fun scanForTargetBlock(
        pixels: IntArray,
        width: Int,
        roiTop: Int,
        roiHeight: Int,
        marginX: Int,
        step: Int,
        pawnCenterX: Float,
        pawnTopY: Float,
        pawnBaseY: Float,
        pawnExclusionRadius: Int,
        excludedRect: Rect?,
        thresholdSq: Int,
        minRunLength: Int,
        minValidBlockWidth: Int,
        minValidBlockHeight: Int,
        rejectDarkShadows: Boolean,
        requireInternalContrast: Boolean = false,
        requireSharpBoundary: Boolean = false,
        allowShadowLeftOffset: Boolean = false,
        preferredTargetHint: PointF2D? = null,
        sourcePlatform: SourcePlatformStance? = null
    ): BlockTrace? {
        val maxBlockScanY = (pawnBaseY - roiTop - 10).roundToInt().coerceIn(0, roiHeight - 1)
        val maxValidBlockWidth = (width * 0.45f).roundToInt()
        val minVerticalClimb = width * 0.072f

        var bestBlock: BlockTrace? = null
        var bestScore = Float.NEGATIVE_INFINITY
        // Track valid [minX, maxX, minY, maxY] footprints and rejected apex seeds [apexX, startY]
        // so rejected sky glows never mask overlapping real blocks nearby.
        val validRegions = ArrayList<IntArray>(8)
        val rejectedSeeds = ArrayList<IntArray>(24)

        fun evaluateRun(ry: Int, apexX: Float) {
            val ax = apexX.roundToInt()
            for (r in validRegions) {
                if (ax in r[0]..r[1] && ry in r[2]..r[3]) return
            }
            for (s in rejectedSeeds) {
                if (abs(ax - s[0]) <= 16 && ry in s[1]..(s[1] + 18)) return
            }

            val traced = traceBlockDownward(
                pixels = pixels,
                width = width,
                roiTop = roiTop,
                roiHeight = roiHeight,
                marginX = marginX,
                startY = ry,
                apexX = apexX,
                minValidBlockWidth = minValidBlockWidth,
                excludedRect = excludedRect,
                thresholdSq = thresholdSq,
                rejectDarkShadows = rejectDarkShadows,
                pawnCenterX = pawnCenterX,
                pawnTopY = pawnTopY,
                pawnBaseY = pawnBaseY,
                pawnMaskHalfW = max((pawnExclusionRadius * 0.72f).roundToInt(), (width * 0.032f).roundToInt())
            )

            val cHeight = traced.lastTopRowY - ry
            val centerX = traced.apexX * 0.35f + ((traced.widestLeftX + traced.widestRightX) * 0.5f) * 0.65f
            val isIsometricDiamond = traced.widestRowY > traced.apexY + 6
            val centerYRel = if (isIsometricDiamond) {
                val projectedEquator = traced.apexY + traced.maxTopWidth * 0.285f
                val cappedWidestY = min(traced.widestRowY.toFloat(), traced.apexY + traced.maxTopWidth * 0.35f)
                (cappedWidestY * 0.5f + projectedEquator * 0.5f)
            } else {
                (traced.apexY + traced.lastTopRowY) / 2f
            }
            val centerY = roiTop + centerYRel

            val dxAbs = abs(centerX - pawnCenterX)
            val dyAbs = pawnBaseY - centerY
            val distFromPawn = hypot(dxAbs, dyAbs)
            val overlapsPawnBase = pawnCenterX in (traced.widestLeftX + 10f)..(traced.widestRightX - 10f) &&
                pawnBaseY in (roiTop + ry - 8f)..(roiTop + traced.lastTopRowY + 10f)

            val contrastOk = !requireInternalContrast || hasInternalBlockContrast(
                pixels = pixels,
                width = width,
                roiHeight = roiHeight,
                traced = traced
            )
            val sharpBoundaryOk = (!requireSharpBoundary && !requireInternalContrast) || hasSharpBoundaryEdge(
                pixels = pixels,
                width = width,
                roiHeight = roiHeight,
                traced = traced,
                marginX = marginX,
                allowShadowLeftOffset = allowShadowLeftOffset
            )

            val effectiveMinHeight = if (traced.maxTopWidth < (width * 0.145f).roundToInt()) {
                max(minValidBlockHeight, (traced.maxTopWidth * 0.24f).roundToInt())
            } else {
                max(minValidBlockHeight, (width * 0.040f).roundToInt())
            }
            val overlapsSourceTop = sourcePlatform != null &&
                sourcePlatform.widthPx >= minValidBlockWidth &&
                (roiTop + traced.apexY) >= pawnTopY - width * 0.02f &&
                abs(centerX - sourcePlatform.centerX) < sourcePlatform.widthPx * 0.34f &&
                (roiTop + traced.lastTopRowY) >= sourcePlatform.centerY - sourcePlatform.widthPx * 0.36f &&
                centerY >= pawnBaseY - width * 0.055f

            val validGeometry = traced.maxTopWidth in minValidBlockWidth..maxValidBlockWidth &&
                cHeight >= effectiveMinHeight &&
                distFromPawn >= width * 0.145f &&
                !overlapsPawnBase &&
                !overlapsSourceTop &&
                dyAbs >= minVerticalClimb &&
                dxAbs >= dyAbs * 0.85f &&
                contrastOk &&
                sharpBoundaryOk

            if (validGeometry) {
                val sideWallPad = max(12, (traced.maxTopWidth * 0.42f).roundToInt())
                validRegions.add(
                    intArrayOf(
                        (traced.widestLeftX - 8f).roundToInt(),
                        (traced.widestRightX + 8f).roundToInt(),
                        ry,
                        max(ry + 6, traced.lastTopRowY) + sideWallPad
                    )
                )
                // Prefer true 30-degree isometric blocks closest to the pawn and near newly arrived hints
                val isoRatio = dxAbs / dyAbs.coerceAtLeast(1f)
                val isoBonus = when {
                    isoRatio in 1.25f..1.95f -> 500f
                    isoRatio in 0.95f..2.20f -> 380f
                    else -> 120f
                }
                val sizeBonus = if (traced.maxTopWidth in (width * 0.18f).roundToInt()..(width * 0.42f).roundToInt()) 45f else 0f
                val hintBonus = if (preferredTargetHint != null &&
                    hypot(centerX - preferredTargetHint.x, centerY - preferredTargetHint.y) <= width * 0.18f
                ) 320f else 0f
                val score = isoBonus + sizeBonus + hintBonus - distFromPawn
                if (score > bestScore) {
                    bestScore = score
                    bestBlock = traced
                }
            } else {
                rejectedSeeds.add(intArrayOf(ax, ry))
            }
        }

        val topLeftSky = pixels[marginX / 2]
        val topRightSky = pixels[width - 1 - marginX / 2]
        var ry = 0
        while (ry <= maxBlockScanY) {
            val rowOffset = ry * width
            val absY = roiTop + ry
            val rawLeftBg = pixels[rowOffset + marginX / 2]
            val rawRightBg = pixels[rowOffset + width - 1 - marginX / 2]
            val (leftBg, rightBg) = cleanRowSkyPair(rawLeftBg, rawRightBg, topLeftSky, topRightSky)

            var runStart = -1

            var x = marginX
            while (x < width - marginX) {
                val inPawnColumn = abs(x - pawnCenterX) < pawnExclusionRadius &&
                    absY >= (pawnTopY - 6f) &&
                    absY <= (pawnBaseY + 14f)
                val excluded = isExcluded(x, absY, excludedRect)
                val isFg = !inPawnColumn && !excluded && isValidBlockPixel(
                    pixels[rowOffset + x],
                    leftBg,
                    rightBg,
                    x,
                    width,
                    thresholdSq,
                    rejectDarkShadows
                )
                if (isFg) {
                    if (runStart < 0) runStart = x
                } else {
                    if (runStart >= 0 && (x - runStart) >= minRunLength) {
                        evaluateRun(ry, (runStart + (x - step)) / 2f)
                    }
                    runStart = -1
                }
                x += step
            }
            if (runStart >= 0 && ((width - marginX) - runStart) >= minRunLength) {
                evaluateRun(ry, (runStart + (width - marginX)) / 2f)
            }
            ry += 2
        }
        return bestBlock
    }

    private fun cleanRowSkyPair(leftBg: Int, rightBg: Int, topLeftSky: Int, topRightSky: Int): Pair<Int, Int> {
        if (colorDistSq(leftBg, rightBg) <= 4000) return leftBg to rightBg
        val leftDrift = min(colorDistSq(leftBg, topLeftSky), colorDistSq(leftBg, topRightSky))
        val rightDrift = min(colorDistSq(rightBg, topLeftSky), colorDistSq(rightBg, topRightSky))
        return if (leftDrift > rightDrift) rightBg to rightBg else leftBg to leftBg
    }

    private fun colorDistSq(a: Int, b: Int): Int {
        val dr = ((a shr 16) and 0xFF) - ((b shr 16) and 0xFF)
        val dg = ((a shr 8) and 0xFF) - ((b shr 8) and 0xFF)
        val db = (a and 0xFF) - (b and 0xFF)
        return dr * dr + dg * dg + db * db
    }

    private fun hasInternalBlockContrast(
        pixels: IntArray,
        width: Int,
        roiHeight: Int,
        traced: BlockTrace
    ): Boolean {
        val cx = ((traced.widestLeftX + traced.widestRightX) * 0.5f).roundToInt().coerceIn(8, width - 9)
        val topSampleY = ((traced.apexY + traced.widestRowY) * 0.5f).roundToInt().coerceIn(0, roiHeight - 1)
        val quarterW = (traced.maxTopWidth / 4).coerceIn(10, 48)
        val sideDrop = max(14, (traced.maxTopWidth * 0.22f).roundToInt())
        val sideSampleY = min(roiHeight - 1, traced.widestRowY + sideDrop)
        val fallbackSideY = min(roiHeight - 1, traced.widestRowY + max(8, (traced.lastTopRowY - traced.apexY) / 3))
        val topPx = pixels[topSampleY * width + cx]
        val leftSidePx = pixels[sideSampleY * width + (cx - quarterW).coerceAtLeast(0)]
        val rightSidePx = pixels[sideSampleY * width + (cx + quarterW).coerceAtMost(width - 1)]
        val midLeftPx = pixels[fallbackSideY * width + (cx - 12).coerceAtLeast(0)]
        val midRightPx = pixels[fallbackSideY * width + (cx + 12).coerceAtMost(width - 1)]

        // Real 3D blocks have strong top-vs-side or left-vs-right face contrast, unlike vertical sky gradients
        return maxOf(
            colorDistSq(topPx, leftSidePx),
            colorDistSq(topPx, rightSidePx),
            colorDistSq(leftSidePx, rightSidePx),
            colorDistSq(topPx, midLeftPx),
            colorDistSq(topPx, midRightPx),
            colorDistSq(midLeftPx, midRightPx)
        ) >= 300
    }

    private fun hasSharpBoundaryEdge(
        pixels: IntArray,
        width: Int,
        roiHeight: Int,
        traced: BlockTrace,
        marginX: Int,
        allowShadowLeftOffset: Boolean = false
    ): Boolean {
        val apexX = traced.apexX.roundToInt().coerceIn(8, width - 9)
        val insetH = (traced.maxTopWidth / 4).coerceIn(5, 10)
        val insideApexOffset = (traced.lastTopRowY - traced.apexY).coerceIn(5, 12)
        val insideApexY = (traced.apexY + insideApexOffset).coerceIn(0, roiHeight - 1)
        val apexProbeOffset = min(6, insetH)
        val apexJump = if (traced.apexY <= 8) {
            // Far block sits right at ROI top border; compare interior apex against side sky at row 0
            val skyLeft = pixels[marginX / 2]
            val skyRight = pixels[width - 1 - marginX / 2]
            maxOf(
                colorDistSq(skyLeft, pixels[insideApexY * width + apexX]),
                colorDistSq(skyRight, pixels[insideApexY * width + apexX])
            )
        } else {
            val aboveApexY = (traced.apexY - 10).coerceIn(0, roiHeight - 1)
            maxOf(
                colorDistSq(pixels[aboveApexY * width + apexX], pixels[insideApexY * width + apexX]),
                colorDistSq(pixels[aboveApexY * width + (apexX - apexProbeOffset)], pixels[insideApexY * width + (apexX - apexProbeOffset)]),
                colorDistSq(pixels[aboveApexY * width + (apexX + apexProbeOffset)], pixels[insideApexY * width + (apexX + apexProbeOffset)])
            )
        }
        if (apexJump < 180) return false

        val leftX = traced.widestLeftX.roundToInt()
        val rightX = traced.widestRightX.roundToInt()
        val atLeftBorder = leftX <= marginX + 6
        val atRightBorder = rightX >= width - marginX - 6

        val outLeftX = (leftX - 10).coerceIn(marginX / 2, width - 1)
        val inLeftX = (leftX + insetH).coerceIn(0, width - 1)
        val inLeftDeepX = (leftX + max(insetH + 8, 22)).coerceIn(0, width - 1)
        val outRightX = (rightX + 10).coerceIn(0, width - 1 - marginX / 2)
        val inRightX = (rightX - insetH).coerceIn(0, width - 1)

        val rowA = traced.widestRowY.coerceIn(0, roiHeight - 1)
        val rowB = (traced.widestRowY + 8).coerceIn(0, roiHeight - 1)

        val leftJumpDirect = max(
            colorDistSq(pixels[rowA * width + inLeftX], pixels[rowA * width + outLeftX]),
            colorDistSq(pixels[rowB * width + inLeftX], pixels[rowB * width + outLeftX])
        )
        val leftJump = if (allowShadowLeftOffset) {
            maxOf(
                leftJumpDirect,
                colorDistSq(pixels[rowA * width + inLeftDeepX], pixels[rowA * width + outLeftX]),
                colorDistSq(pixels[rowB * width + inLeftDeepX], pixels[rowB * width + outLeftX])
            )
        } else {
            leftJumpDirect
        }
        val rightJump = max(
            colorDistSq(pixels[rowA * width + inRightX], pixels[rowA * width + outRightX]),
            colorDistSq(pixels[rowB * width + inRightX], pixels[rowB * width + outRightX])
        )

        if (atLeftBorder && !atRightBorder) return rightJump >= 240
        if (atRightBorder && !atLeftBorder) return leftJump >= 240
        // Smooth radial sky vignette changes <= 50 across 20px; real block polygons jump sharply at top, left, and right
        return rightJump >= 180 && leftJump >= 110 && max(leftJump, rightJump) >= 240
    }

    private fun traceBlockDownward(
        pixels: IntArray,
        width: Int,
        roiTop: Int,
        roiHeight: Int,
        marginX: Int,
        startY: Int,
        apexX: Float,
        minValidBlockWidth: Int,
        excludedRect: Rect?,
        thresholdSq: Int,
        rejectDarkShadows: Boolean,
        pawnCenterX: Float = -1000f,
        pawnTopY: Float = -1000f,
        pawnBaseY: Float = -1000f,
        pawnMaskHalfW: Int = 0
    ): BlockTrace {
        var cWidestRowY = startY
        var cMaxTopWidth = 0
        var cWidestLeftX = apexX
        var cWidestRightX = apexX
        var cLastTopRowY = startY
        var plateauRows = 0
        var shrinkingRows = 0
        var prevRowWidth = 0
        val maxTraceY = min(roiHeight - 1, startY + (width * 0.18f).roundToInt())
        val maxWideningY = startY + (width * 0.145f).roundToInt()
        val maxReasonableWidth = (width * 0.45f).roundToInt()
        val maxSpikeJump = max(20, (width * 0.048f).roundToInt())
        var traceCenterX = apexX.roundToInt().coerceIn(marginX, width - marginX - 1)
        val guardPawnBody = pawnMaskHalfW > 0 && abs(apexX - pawnCenterX) > pawnMaskHalfW

        val topLeftSky = pixels[marginX / 2]
        val topRightSky = pixels[width - 1 - marginX / 2]

        for (ty in startY..maxTraceY) {
            val tOffset = ty * width
            val absY = roiTop + ty
            val (tLeftBg, tRightBg) = cleanRowSkyPair(
                pixels[tOffset + marginX / 2],
                pixels[tOffset + width - 1 - marginX / 2],
                topLeftSky,
                topRightSky
            )
            val pawnInRowBand = guardPawnBody && absY >= (pawnTopY - width * 0.042f) && absY <= (pawnBaseY + 10f)

            val spanL: Int
            val spanR: Int
            if (cMaxTopWidth >= minValidBlockWidth / 2) {
                spanL = (cWidestLeftX.roundToInt() - 10).coerceIn(marginX, width - marginX - 1)
                spanR = (cWidestRightX.roundToInt() + 10).coerceIn(spanL, width - marginX - 1)
            } else {
                val halfSearch = max(10, cMaxTopWidth / 2 + 8)
                spanL = (traceCenterX - halfSearch).coerceIn(marginX, width - marginX - 1)
                spanR = (traceCenterX + halfSearch).coerceIn(spanL, width - marginX - 1)
            }

            var firstFgX = -1
            var lastFgX = -1
            var sx = spanL
            while (sx <= spanR) {
                val inPawn = pawnInRowBand && abs(sx - pawnCenterX) < pawnMaskHalfW
                if (!inPawn && !isExcluded(sx, absY, excludedRect) &&
                    isValidBlockPixel(pixels[tOffset + sx], tLeftBg, tRightBg, sx, width, thresholdSq, rejectDarkShadows)
                ) {
                    if (firstFgX < 0) firstFgX = sx
                    lastFgX = sx
                }
                sx++
            }

            if (firstFgX < 0) {
                if (ty - startY > 6) break
                continue
            }

            var lx = firstFgX
            while (lx > marginX) {
                if (pawnInRowBand && abs((lx - 1) - pawnCenterX) < pawnMaskHalfW) break
                if (isExcluded(lx - 1, absY, excludedRect)) break
                val px = pixels[tOffset + lx - 1]
                if (!isValidBlockPixel(px, tLeftBg, tRightBg, lx - 1, width, thresholdSq, rejectDarkShadows)) break
                lx--
            }

            var rx = lastFgX
            while (rx < width - marginX - 1) {
                if (pawnInRowBand && abs((rx + 1) - pawnCenterX) < pawnMaskHalfW) break
                if (isExcluded(rx + 1, absY, excludedRect)) break
                val px = pixels[tOffset + rx + 1]
                if (!isValidBlockPixel(px, tLeftBg, tRightBg, rx + 1, width, thresholdSq, rejectDarkShadows)) break
                rx++
            }

            // Clamp asymmetric horizontal bleed into touching neighbor blocks once both halves are solidly established
            if (ty - startY >= 8 && cMaxTopWidth >= minValidBlockWidth) {
                val leftHalf = apexX - lx
                val rightHalf = rx - apexX
                if (min(leftHalf, rightHalf) >= 36f) {
                    val maxAllowedLeft = rightHalf * 1.32f + 16f
                    val maxAllowedRight = leftHalf * 1.32f + 16f
                    if (leftHalf > maxAllowedLeft) {
                        lx = max(lx, (apexX - maxAllowedLeft).roundToInt())
                    } else if (rightHalf > maxAllowedRight) {
                        rx = min(rx, (apexX + maxAllowedRight).roundToInt())
                    }
                }
            }

            val rowWidth = rx - lx
            if (rowWidth >= 4) {
                // Stop if tracing downward bled sideways into an adjacent drop shadow or neighboring block
                if (cMaxTopWidth >= minValidBlockWidth &&
                    (rowWidth > maxReasonableWidth || rowWidth > cMaxTopWidth + maxSpikeJump)
                ) {
                    break
                }
                if (cMaxTopWidth >= (width * 0.16f).roundToInt() &&
                    (ty - cWidestRowY) >= 8 &&
                    shrinkingRows >= 3 &&
                    rowWidth > prevRowWidth + 14
                ) {
                    break
                }
                if (cMaxTopWidth >= minValidBlockWidth && rowWidth < cMaxTopWidth - 6) {
                    shrinkingRows++
                } else {
                    shrinkingRows = 0
                }
                prevRowWidth = rowWidth

                cLastTopRowY = ty
                traceCenterX = ((lx + rx) / 2).coerceIn(marginX, width - marginX - 1)

                val pastEquatorWall = cMaxTopWidth >= minValidBlockWidth &&
                    ty > startY + (cMaxTopWidth * 0.36f).roundToInt() &&
                    (ty - cWidestRowY) >= 6
                if (!pastEquatorWall && ty <= maxWideningY && rowWidth > cMaxTopWidth + 3 && rowWidth <= maxReasonableWidth) {
                    cMaxTopWidth = rowWidth
                    cWidestRowY = ty
                    cWidestLeftX = lx.toFloat()
                    cWidestRightX = rx.toFloat()
                    plateauRows = 0
                } else if (cMaxTopWidth >= minValidBlockWidth) {
                    plateauRows++
                    if (plateauRows >= 16) {
                        break
                    }
                }
            }
        }

        return BlockTrace(
            apexX = apexX,
            apexY = startY,
            widestRowY = cWidestRowY,
            maxTopWidth = cMaxTopWidth,
            widestLeftX = cWidestLeftX,
            widestRightX = cWidestRightX,
            lastTopRowY = cLastTopRowY
        )
    }

    /**
     * Handles half-transparent blocks where the upper half is faint translucent glass and the
     * lower half is solid/colored. Looks upward from [baseBlock.apexY] for a brighter/translucent top apex.
     */
    private fun extendTranslucentTopApex(
        pixels: IntArray,
        width: Int,
        roiHeight: Int,
        marginX: Int,
        excludedRect: Rect?,
        roiTop: Int,
        baseBlock: BlockTrace,
        translucentThresholdSq: Int,
        minValidBlockWidth: Int
    ): BlockTrace {
        val climbAllowance = max((baseBlock.maxTopWidth * 0.75f).roundToInt(), (width * 0.08f).roundToInt())
        val searchStart = (baseBlock.apexY - climbAllowance).coerceAtLeast(0)
        val probeHalfW = max((baseBlock.maxTopWidth * 0.75f).roundToInt(), (width * 0.11f).roundToInt())
        val centerX = ((baseBlock.widestLeftX + baseBlock.widestRightX) / 2f).roundToInt()
        val scanMinX = (centerX - probeHalfW).coerceAtLeast(marginX)
        val scanMaxX = (centerX + probeHalfW).coerceAtMost(width - marginX - 1)

        var newApexY = baseBlock.apexY
        var newApexX = baseBlock.apexX
        var consecutiveEmpty = 0

        for (ry in (baseBlock.apexY - 1) downTo searchStart) {
            val rowOffset = ry * width
            val absY = roiTop + ry
            val leftBg = pixels[rowOffset + marginX / 2]
            val rightBg = pixels[rowOffset + width - 1 - marginX / 2]

            var runStart = -1
            var runLen = 0
            var bestRunStart = -1
            var bestRunLen = 0

            for (x in scanMinX..scanMaxX) {
                if (!isExcluded(x, absY, excludedRect) &&
                    isValidBlockPixel(pixels[rowOffset + x], leftBg, rightBg, x, width, translucentThresholdSq, rejectDarkShadows = true)
                ) {
                    if (runStart < 0) runStart = x
                    runLen++
                    if (runLen > bestRunLen) {
                        bestRunLen = runLen
                        bestRunStart = runStart
                    }
                } else {
                    runStart = -1
                    runLen = 0
                }
            }

            if (bestRunLen >= 6) {
                newApexY = ry
                newApexX = bestRunStart + bestRunLen / 2f
                consecutiveEmpty = 0
            } else {
                consecutiveEmpty++
                if (consecutiveEmpty >= 4) break
            }
        }

        if (baseBlock.apexY - newApexY < 6) return baseBlock

        val baseCenterX = (baseBlock.widestLeftX + baseBlock.widestRightX) * 0.5f
        val maxReasonableW = (width * 0.45f).toFloat()
        return traceBlockDownward(
            pixels = pixels,
            width = width,
            roiTop = roiTop,
            roiHeight = roiHeight,
            marginX = marginX,
            startY = newApexY,
            apexX = newApexX,
            minValidBlockWidth = minValidBlockWidth,
            excludedRect = excludedRect,
            thresholdSq = translucentThresholdSq,
            rejectDarkShadows = true
        ).let { extended ->
            val alignedOrHalfWall = baseCenterX in (extended.widestLeftX - 8f)..(extended.widestRightX + 8f)
            val reasonableW = extended.maxTopWidth >= baseBlock.maxTopWidth * 0.92f &&
                extended.maxTopWidth <= minOf(maxReasonableW, max(baseBlock.maxTopWidth * 2.35f, width * 0.35f))
            val sharpEdges = hasSharpBoundaryEdge(
                pixels = pixels,
                width = width,
                roiHeight = roiHeight,
                traced = extended,
                marginX = marginX,
                allowShadowLeftOffset = false
            )
            if (alignedOrHalfWall && reasonableW && sharpEdges) extended else baseBlock
        }
    }

    fun computeDurationMs(normalizedDistancePx: Float, config: BotConfig): Long {
        val raw = when (config.formulaMode) {
            FormulaMode.LINEAR -> config.kFactor * normalizedDistancePx + config.offsetMs
            FormulaMode.QUADRATIC ->
                config.quadFactor * normalizedDistancePx * normalizedDistancePx +
                    config.kFactor * normalizedDistancePx +
                    config.offsetMs
        }
        return raw.roundToLong().coerceIn(config.minPressMs, config.maxPressMs)
    }

    private fun classifyBlockProfile(normalizedBlockWidth: Float): String {
        return when {
            normalizedBlockWidth < 205f -> "MINI"
            normalizedBlockWidth < 330f -> "NARROW"
            normalizedBlockWidth <= 435f -> "MEDIUM"
            else -> "WIDE"
        }
    }

    private fun classifyBlockTopColorGroup(
        pixels: IntArray,
        width: Int,
        roiTop: Int,
        roiHeight: Int,
        centerX: Float,
        centerY: Float,
        topY: Float
    ): String {
        val sampleY = (((topY + centerY) * 0.5f) - roiTop).roundToInt().coerceIn(0, roiHeight - 1)
        val cx = centerX.roundToInt().coerceIn(8, width - 9)
        val offsets = intArrayOf(-10, 0, 10)
        var sumR = 0
        var sumG = 0
        var sumB = 0
        for (dx in offsets) {
            val px = pixels[sampleY * width + (cx + dx).coerceIn(0, width - 1)]
            sumR += (px shr 16) and 0xFF
            sumG += (px shr 8) and 0xFF
            sumB += px and 0xFF
        }
        val r = sumR / offsets.size
        val g = sumG / offsets.size
        val b = sumB / offsets.size
        val maxC = maxOf(r, g, b)
        val minC = minOf(r, g, b)
        val sat = maxC - minC

        return when {
            maxC < 95 -> "DARK"
            sat < 34 -> if (maxC > 205) "WHITE" else "SLATE"
            r >= g && r >= b -> when {
                g > 155 && b < 135 -> "GOLD"
                b > 130 -> "PINK"
                else -> "CORAL"
            }
            g >= r && g >= b -> when {
                b > r + 18 -> "CYAN"
                r > b + 35 -> "LIME"
                else -> "MINT"
            }
            else -> when {
                r > 150 && r > g - 15 -> "PURPLE"
                g > 165 -> "CYAN"
                else -> "BLUE"
            }
        }
    }

    private fun detectSourcePlatformAndStance(
        pixels: IntArray,
        width: Int,
        roiTop: Int,
        roiHeight: Int,
        marginX: Int,
        pawnCenterX: Float,
        pawnBaseY: Float,
        pawnWidthEstimate: Int,
        targetCenterX: Float,
        excludedRect: Rect?,
        thresholdSq: Int,
        targetBlock: BlockTrace? = null
    ): SourcePlatformStance {
        val scanStartRel = (pawnBaseY - roiTop - width * 0.075f).roundToInt().coerceIn(0, roiHeight - 1)
        val scanEndRel = (pawnBaseY - roiTop + width * 0.065f).roundToInt().coerceIn(scanStartRel, roiHeight - 1)
        val minSrcW = max(max(40, (width * 0.058f).roundToInt()), (pawnWidthEstimate * 1.45f).roundToInt())
        val maxSrcW = (width * 0.44f).roundToInt()
        val maxHalfTravel = (width * 0.28f).roundToInt()
        val bridgeHalf = max(12, (pawnWidthEstimate * 0.56f).roundToInt())

        val blockSideLeftX = targetBlock?.widestLeftX?.roundToInt() ?: Int.MAX_VALUE
        val blockSideRightX = targetBlock?.widestRightX?.roundToInt() ?: Int.MIN_VALUE
        val blockEndRelY = targetBlock?.let { it.lastTopRowY + max(24, (it.maxTopWidth * 0.45f).roundToInt()) } ?: -1
        fun entersTargetBlock(x: Int, ry: Int): Boolean {
            if (targetBlock == null) return false
            return ry in targetBlock.apexY..blockEndRelY && x in (blockSideLeftX + 2)..(blockSideRightX - 2)
        }

        var bestWidth = 0
        var bestLeftX = pawnCenterX
        var bestRightX = pawnCenterX
        var bestWidestRelY = (pawnBaseY - roiTop).roundToInt()
        var topSurfaceRelY = Int.MAX_VALUE
        var seedX = pawnCenterX.roundToInt().coerceIn(marginX, width - marginX - 1)

        var ry = scanStartRel
        while (ry <= scanEndRel) {
            val absY = roiTop + ry
            val rowOff = ry * width
            val leftBg = pixels[rowOff + marginX / 2]
            val rightBg = pixels[rowOff + width - 1 - marginX / 2]
            val pawnInFootBand = absY >= (pawnBaseY - width * 0.065f) && absY <= (pawnBaseY + 8f)

            var anchorX = seedX
            if (!pawnInFootBand || abs(anchorX - pawnCenterX) > bridgeHalf || entersTargetBlock(anchorX, ry) ||
                !isForegroundPixel(pixels[rowOff + anchorX], leftBg, rightBg, anchorX, width, thresholdSq / 3)
            ) {
                var foundAnchor = -1
                for (d in 0..20 step 2) {
                    val xR = (seedX + d).coerceAtMost(width - marginX - 1)
                    if (!entersTargetBlock(xR, ry) && !isExcluded(xR, absY, excludedRect) &&
                        isValidBlockPixel(pixels[rowOff + xR], leftBg, rightBg, xR, width, thresholdSq, rejectDarkShadows = true)
                    ) {
                        foundAnchor = xR
                        break
                    }
                    val xL = (seedX - d).coerceAtLeast(marginX)
                    if (!entersTargetBlock(xL, ry) && !isExcluded(xL, absY, excludedRect) &&
                        isValidBlockPixel(pixels[rowOff + xL], leftBg, rightBg, xL, width, thresholdSq, rejectDarkShadows = true)
                    ) {
                        foundAnchor = xL
                        break
                    }
                }
                if (foundAnchor < 0) {
                    ry += 2
                    continue
                }
                anchorX = foundAnchor
            }

            var lx = anchorX
            var missLeft = 0
            val minScanX = max(marginX, anchorX - maxHalfTravel)
            while (lx > minScanX) {
                val nx = lx - 1
                if (entersTargetBlock(nx, ry) || isExcluded(nx, absY, excludedRect)) break
                val inBridge = pawnInFootBand && abs(nx - pawnCenterX) <= bridgeHalf &&
                    isForegroundPixel(pixels[rowOff + nx], leftBg, rightBg, nx, width, thresholdSq / 3)
                val fg = inBridge || isValidBlockPixel(pixels[rowOff + nx], leftBg, rightBg, nx, width, thresholdSq, rejectDarkShadows = true)
                if (fg) {
                    lx = nx
                    missLeft = 0
                } else {
                    missLeft++
                    if (missLeft >= 2) break
                    lx = nx
                }
            }

            var rx = anchorX
            var missRight = 0
            val maxScanX = min(width - marginX - 1, anchorX + maxHalfTravel)
            while (rx < maxScanX) {
                val nx = rx + 1
                if (entersTargetBlock(nx, ry) || isExcluded(nx, absY, excludedRect)) break
                val inBridge = pawnInFootBand && abs(nx - pawnCenterX) <= bridgeHalf &&
                    isForegroundPixel(pixels[rowOff + nx], leftBg, rightBg, nx, width, thresholdSq / 3)
                val fg = inBridge || isValidBlockPixel(pixels[rowOff + nx], leftBg, rightBg, nx, width, thresholdSq, rejectDarkShadows = true)
                if (fg) {
                    rx = nx
                    missRight = 0
                } else {
                    missRight++
                    if (missRight >= 2) break
                    rx = nx
                }
            }

            val rowW = rx - lx
            if (rowW >= 18 && abs((lx + rx) * 0.5f - pawnCenterX) <= max(rowW * 0.58f, bridgeHalf * 1.4f)) {
                if (ry < topSurfaceRelY) {
                    topSurfaceRelY = ry
                }
                val spikeLimit = if (bestWidth >= (width * 0.14f).roundToInt()) bestWidth + (width * 0.08f).roundToInt() else maxSrcW
                if (rowW in minSrcW..min(maxSrcW, spikeLimit) && rowW > bestWidth + 3) {
                    bestWidth = rowW
                    bestLeftX = lx.toFloat()
                    bestRightX = rx.toFloat()
                    bestWidestRelY = ry
                    seedX = ((lx + rx) / 2).coerceIn(marginX, width - marginX - 1)
                }
            }
            ry += 2
        }

        if (bestWidth < minSrcW) {
            return SourcePlatformStance(0f, 0f, 0f, 0f, 0f, "UNKNOWN", 0f)
        }

        val srcCenterX = (bestLeftX + bestRightX) * 0.5f
        val projectedCenterYRel = if (topSurfaceRelY < bestWidestRelY - 6) {
            (bestWidestRelY * 0.65f) + ((topSurfaceRelY + bestWidth * 0.255f) * 0.35f)
        } else {
            bestWidestRelY.toFloat()
        }
        val srcCenterY = roiTop + projectedCenterYRel

        val halfW = (bestWidth * 0.5f).coerceAtLeast(30f)
        val halfH = (bestWidth * 0.26f).coerceAtLeast(16f)
        val normDx = (pawnCenterX - srcCenterX) / halfW
        val normDy = (pawnBaseY - srcCenterY) / halfH
        val radialOffset = hypot(normDx, normDy).coerceIn(0f, 1.4f)
        val jumpDirX = if (targetCenterX >= pawnCenterX) 1f else -1f
        val signedAlongJump = normDx * jumpDirX

        val stance = when {
            radialOffset <= 0.25f -> "CENTER"
            signedAlongJump >= 0.24f -> "SIDE_FRONT"
            signedAlongJump <= -0.24f -> "SIDE_BACK"
            else -> "SIDE_EDGE"
        }

        return SourcePlatformStance(
            leftX = bestLeftX,
            rightX = bestRightX,
            centerX = srcCenterX,
            centerY = srcCenterY,
            widthPx = bestWidth.toFloat(),
            stance = stance,
            offsetFrac = radialOffset
        )
    }

    private fun isExcluded(x: Int, absY: Int, excludedRect: Rect?): Boolean {
        if (excludedRect == null) return false
        return x in (excludedRect.left - 10)..(excludedRect.right + 10) &&
            absY in (excludedRect.top - 10)..(excludedRect.bottom + 10)
    }

    private fun isValidBlockPixel(
        pixel: Int,
        leftBg: Int,
        rightBg: Int,
        x: Int,
        width: Int,
        thresholdSq: Int,
        rejectDarkShadows: Boolean
    ): Boolean {
        val t = x.toFloat() / width.toFloat()
        val bgR = (((leftBg shr 16) and 0xFF) * (1f - t) + ((rightBg shr 16) and 0xFF) * t).roundToInt()
        val bgG = (((leftBg shr 8) and 0xFF) * (1f - t) + ((rightBg shr 8) and 0xFF) * t).roundToInt()
        val bgB = ((leftBg and 0xFF) * (1f - t) + (rightBg and 0xFF) * t).roundToInt()

        val r = (pixel shr 16) and 0xFF
        val g = (pixel shr 8) and 0xFF
        val b = pixel and 0xFF

        val dr = r - bgR
        val dg = g - bgG
        val db = b - bgB

        if (rejectDarkShadows) {
            val pxLum = r * 3 + g * 6 + b
            val bgLum = bgR * 3 + bgG * 6 + bgB
            if (pxLum < bgLum - 40 && bgLum > 80) {
                val maxChromaShift = maxOf(abs(dr - dg), abs(dg - db), abs(dr - db))
                val scale256 = (pxLum * 256) / bgLum
                val expR = (bgR * scale256) shr 8
                val expG = (bgG * scale256) shr 8
                val expB = (bgB * scale256) shr 8
                val shadowHueErr = maxOf(abs(r - expR), abs(g - expG), abs(b - expB))
                if (maxChromaShift < 14 || shadowHueErr <= 11) return false
            }
        }

        return (dr * dr + dg * dg + db * db) > thresholdSq
    }

    private fun isForegroundPixel(
        pixel: Int,
        leftBg: Int,
        rightBg: Int,
        x: Int,
        width: Int,
        thresholdSq: Int
    ): Boolean {
        return isValidBlockPixel(pixel, leftBg, rightBg, x, width, thresholdSq, rejectDarkShadows = false)
    }

    /**
     * Generates a synthetic arcade block-jumping frame for testing vision & formula calibration
     * directly inside the app before testing on a live third-party game.
     */
    fun generateSyntheticTestFrame(
        width: Int = 720,
        height: Int = 1280,
        pawnColorRgb: Int = 0x3E3353,
        targetBlockWidthPx: Float = 170f,
        distanceFactor: Float = 0.65f,
        halfTransparentTarget: Boolean = false
    ): Bitmap {
        val bmp = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)

        val bgPaint = Paint().apply {
            shader = LinearGradient(
                0f, 0f, 0f, height.toFloat(),
                intArrayOf(Color.rgb(94, 92, 157), Color.rgb(141, 148, 201)),
                null,
                Shader.TileMode.CLAMP
            )
        }
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), bgPaint)

        // Current platform (bottom-left isometric block)
        val curCx = width * 0.28f
        val curCy = height * 0.63f
        drawIsometricBlock(canvas, curCx, curCy, 190f, 95f, Color.rgb(250, 250, 250), Color.rgb(30, 142, 149), Color.rgb(22, 118, 125))

        // Next target block (upper-right isometric block)
        val targetCx = curCx + (width * 0.48f * distanceFactor.coerceIn(0.35f, 1.0f))
        val targetCy = curCy - (height * 0.22f * distanceFactor.coerceIn(0.35f, 1.0f))
        val targetH = targetBlockWidthPx * 0.52f
        if (halfTransparentTarget) {
            drawHalfTransparentBlock(canvas, targetCx, targetCy, targetBlockWidthPx, targetH)
        } else {
            drawIsometricBlock(canvas, targetCx, targetCy, targetBlockWidthPx, targetH, Color.rgb(255, 196, 92), Color.rgb(225, 162, 65), Color.rgb(190, 128, 45))
        }

        // Pawn standing on current block
        val pawnPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(
                (pawnColorRgb shr 16) and 0xFF,
                (pawnColorRgb shr 8) and 0xFF,
                pawnColorRgb and 0xFF
            )
        }
        val bodyPath = Path().apply {
            moveTo(curCx - 14f, curCy - 12f)
            lineTo(curCx + 14f, curCy - 12f)
            lineTo(curCx + 9f, curCy - 52f)
            lineTo(curCx - 9f, curCy - 52f)
            close()
        }
        canvas.drawPath(bodyPath, pawnPaint)
        canvas.drawOval(curCx - 16f, curCy - 18f, curCx + 16f, curCy - 4f, pawnPaint)
        val collarPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(210, 175, 98) }
        canvas.drawOval(curCx - 12f, curCy - 60f, curCx + 12f, curCy - 50f, collarPaint)
        canvas.drawCircle(curCx, curCy - 72f, 12f, pawnPaint)

        return bmp
    }

    private fun drawHalfTransparentBlock(
        canvas: Canvas,
        cx: Float,
        cy: Float,
        w: Float,
        h: Float
    ) {
        val hw = w / 2f
        val hh = h / 2f
        val depth = h * 0.85f

        val topGlass = Path().apply {
            moveTo(cx, cy - hh)
            lineTo(cx + hw, cy)
            lineTo(cx, cy + hh)
            lineTo(cx - hw, cy)
            close()
        }
        val leftSide = Path().apply {
            moveTo(cx - hw, cy)
            lineTo(cx, cy + hh)
            lineTo(cx, cy + hh + depth)
            lineTo(cx - hw, cy + depth)
            close()
        }
        val rightSide = Path().apply {
            moveTo(cx + hw, cy)
            lineTo(cx, cy + hh)
            lineTo(cx, cy + hh + depth)
            lineTo(cx + hw, cy + depth)
            close()
        }

        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        paint.color = Color.argb(230, 56, 189, 248)
        canvas.drawPath(leftSide, paint)
        paint.color = Color.argb(230, 14, 116, 144)
        canvas.drawPath(rightSide, paint)
        paint.color = Color.argb(72, 255, 255, 255)
        canvas.drawPath(topGlass, paint)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 3f
        paint.color = Color.argb(145, 255, 255, 255)
        canvas.drawPath(topGlass, paint)
    }

    private fun drawIsometricBlock(
        canvas: Canvas,
        cx: Float,
        cy: Float,
        w: Float,
        h: Float,
        topColor: Int,
        leftColor: Int,
        rightColor: Int
    ) {
        val hw = w / 2f
        val hh = h / 2f
        val depth = h * 0.9f

        val topPath = Path().apply {
            moveTo(cx, cy - hh)
            lineTo(cx + hw, cy)
            lineTo(cx, cy + hh)
            lineTo(cx - hw, cy)
            close()
        }
        val leftPath = Path().apply {
            moveTo(cx - hw, cy)
            lineTo(cx, cy + hh)
            lineTo(cx, cy + hh + depth)
            lineTo(cx - hw, cy + depth)
            close()
        }
        val rightPath = Path().apply {
            moveTo(cx + hw, cy)
            lineTo(cx, cy + hh)
            lineTo(cx, cy + hh + depth)
            lineTo(cx + hw, cy + depth)
            close()
        }

        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        paint.color = leftColor
        canvas.drawPath(leftPath, paint)
        paint.color = rightColor
        canvas.drawPath(rightPath, paint)
        paint.color = topColor
        canvas.drawPath(topPath, paint)
    }
}
