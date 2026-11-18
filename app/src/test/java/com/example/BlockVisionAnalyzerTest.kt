package com.example

import com.example.model.BotConfig
import com.example.model.FormulaMode
import com.example.vision.BlockVisionAnalyzer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36])
class BlockVisionAnalyzerTest {

    @Test
    fun `detects pawn and next block accurately on synthetic frame`() {
        val config = BotConfig(
            formulaMode = FormulaMode.LINEAR,
            kFactor = 1.40f,
            offsetMs = 20f
        )
        val bitmap = BlockVisionAnalyzer.generateSyntheticTestFrame(
            width = 720,
            height = 1280,
            pawnColorRgb = config.pawnColorRgb,
            targetBlockWidthPx = 170f,
            distanceFactor = 0.70f
        )

        val result = BlockVisionAnalyzer.analyze(bitmap, config)

        assertTrue("Pawn should be detected", result.pawnFound)
        assertTrue("Target block should be detected", result.blockFound)
        assertTrue("Measured distance should be > 150px", result.rawDistancePx > 150f)
        val blockWidth = result.blockBounds?.widthPx ?: 0f
        assertTrue("Block width should be near 170px, got $blockWidth", blockWidth in 140f..190f)
        assertTrue("Duration should be within bounds", result.computedDurationMs in config.minPressMs..config.maxPressMs)
    }

    @Test
    fun `detects half-transparent top block with colored base`() {
        val config = BotConfig()
        val bitmap = BlockVisionAnalyzer.generateSyntheticTestFrame(
            width = 720,
            height = 1280,
            pawnColorRgb = config.pawnColorRgb,
            targetBlockWidthPx = 180f,
            distanceFactor = 0.72f,
            halfTransparentTarget = true
        )

        val result = BlockVisionAnalyzer.analyze(bitmap, config)
        println("HALF TRANSPARENT => $result")
        assertTrue("Pawn should be detected", result.pawnFound)
        assertTrue("Half-transparent block should be detected", result.blockFound)
        val width = result.blockBounds?.widthPx ?: 0f
        assertTrue("Half-transparent block width should be >= 140px, got $width", width >= 140f)
    }

    @Test
    fun `linear and quadratic duration formulas respect bounds`() {
        val linearCfg = BotConfig(
            formulaMode = FormulaMode.LINEAR,
            kFactor = 1.5f,
            offsetMs = 25f,
            minPressMs = 90L,
            maxPressMs = 1500L
        )
        assertEquals(325L, BlockVisionAnalyzer.computeDurationMs(200f, linearCfg))
        assertEquals(90L, BlockVisionAnalyzer.computeDurationMs(10f, linearCfg))
        assertEquals(1500L, BlockVisionAnalyzer.computeDurationMs(1500f, linearCfg))

        val quadCfg = BotConfig(
            formulaMode = FormulaMode.QUADRATIC,
            quadFactor = 0.001f,
            kFactor = 1.0f,
            offsetMs = 20f
        )
        // 0.001 * 200^2 + 1.0 * 200 + 20 = 40 + 200 + 20 = 260ms
        assertEquals(260L, BlockVisionAnalyzer.computeDurationMs(200f, quadCfg))
    }

    @Test
    fun `lighter pawn head with visible collar and cone does not falsely project baseY downward`() {
        val config = BotConfig()
        val fullBmp = BlockVisionAnalyzer.generateSyntheticTestFrame(
            width = 720,
            height = 1280,
            pawnColorRgb = config.pawnColorRgb,
            targetBlockWidthPx = 220f,
            distanceFactor = 0.72f
        )
        // Repaint only the sphere head lighter (#625B84) while keeping the collar and cone intact
        val painted = fullBmp.copy(android.graphics.Bitmap.Config.ARGB_8888, true)
        fullBmp.recycle()
        val canvas = android.graphics.Canvas(painted)
        val lightHeadPaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            color = android.graphics.Color.rgb(98, 91, 132)
        }
        val curCx = 720f * 0.28f
        val curCy = 1280f * 0.63f
        canvas.drawCircle(curCx, curCy - 72f, 13f, lightHeadPaint)

        val result = BlockVisionAnalyzer.analyze(painted, config)
        painted.recycle()

        assertTrue("Pawn should still lock via cone and golden collar", result.pawnFound)
        assertTrue("Target block should still be found", result.blockFound)
        // Standing foot is around curCy - 6 (~800px), not projected down by +60px into block side
        assertTrue(
            "pawnBaseY should stay near pawn foot (~790..815), not falsely projected downward; got ${result.pawnBaseY}",
            result.pawnBaseY in 780f..820f
        )
    }

    @Test
    fun `detects low-contrast background-matching lavender block on purple sky`() {
        val config = BotConfig(bgDiffThreshold = 38)
        val bmp = BlockVisionAnalyzer.generateSyntheticTestFrame(
            width = 720,
            height = 1280,
            pawnColorRgb = config.pawnColorRgb,
            targetBlockWidthPx = 210f,
            distanceFactor = 0.68f
        )
        // Paint over the bright yellow block with a subtle slate-purple block very close to the sky gradient (~#7A7DB2)
        val painted = bmp.copy(android.graphics.Bitmap.Config.ARGB_8888, true)
        bmp.recycle()
        val canvas = android.graphics.Canvas(painted)

        // Wipe the synthetic sky behind the target region cleanly first
        val bgPaint = android.graphics.Paint().apply {
            shader = android.graphics.LinearGradient(
                0f, 0f, 0f, 1280f,
                intArrayOf(android.graphics.Color.rgb(94, 92, 157), android.graphics.Color.rgb(141, 148, 201)),
                null,
                android.graphics.Shader.TileMode.CLAMP
            )
        }
        canvas.drawRect(300f, 450f, 710f, 750f, bgPaint)

        val curCx = 720f * 0.28f
        val curCy = 1280f * 0.63f
        val cx = curCx + 720f * 0.48f * 0.68f
        val cy = curCy - 1280f * 0.22f * 0.68f
        val hw = 105f
        val hh = 54f
        val depth = 82f

        val topPath = android.graphics.Path().apply {
            moveTo(cx, cy - hh); lineTo(cx + hw, cy); lineTo(cx, cy + hh); lineTo(cx - hw, cy); close()
        }
        val leftPath = android.graphics.Path().apply {
            moveTo(cx - hw, cy); lineTo(cx, cy + hh); lineTo(cx, cy + hh + depth); lineTo(cx - hw, cy + depth); close()
        }
        val rightPath = android.graphics.Path().apply {
            moveTo(cx + hw, cy); lineTo(cx, cy + hh); lineTo(cx, cy + hh + depth); lineTo(cx + hw, cy + depth); close()
        }
        val p = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
        // Only ~12-18 RGB units away from the purple sky behind it!
        p.color = android.graphics.Color.rgb(138, 144, 198); canvas.drawPath(topPath, p)
        p.color = android.graphics.Color.rgb(112, 118, 178); canvas.drawPath(leftPath, p)
        p.color = android.graphics.Color.rgb(96, 102, 165); canvas.drawPath(rightPath, p)

        val result = BlockVisionAnalyzer.analyze(painted, config)
        val diag = BlockVisionAnalyzer.sampleFrameDiagnostics(
            bitmap = painted,
            roiTopY = result.roiTopY,
            roiBottomY = result.roiBottomY,
            pawnX = result.pawnCenterX,
            pawnY = result.pawnBaseY,
            blockX = result.blockBounds?.center?.x ?: 0f,
            blockY = result.blockBounds?.center?.y ?: 0f,
            blockBottomY = result.blockBounds?.bottomY ?: 0f
        )
        painted.recycle()

        assertTrue("Pawn should still lock", result.pawnFound)
        assertTrue("Low-contrast background-matching block should be detected: ${result.statusMessage}", result.blockFound)
        assertTrue("Measured block width should be >= 160px, got ${result.blockBounds?.widthPx}", (result.blockBounds?.widthPx ?: 0f) >= 160f)
        assertTrue("Block top color telemetry should be sampled", diag.blockTopHex.startsWith("#"))
    }

    @Test
    fun `ignores radial center sky glow #816ACF on #7D65C1 sky and locks onto close wide block`() {
        val config = BotConfig(bgDiffThreshold = 38, roiTopFraction = 0.28f, roiBottomFraction = 0.72f)
        val w = 720
        val h = 1600
        val bmp = android.graphics.Bitmap.createBitmap(w, h, android.graphics.Bitmap.Config.ARGB_8888)
        val canvas = android.graphics.Canvas(bmp)

        // Draw #4F448A -> #7D65C1 gradient sky matching real vivo telemetry
        val skyPaint = android.graphics.Paint().apply {
            shader = android.graphics.LinearGradient(
                0f, 448f, 0f, 1152f,
                intArrayOf(android.graphics.Color.rgb(0x4F, 0x44, 0x8A), android.graphics.Color.rgb(0x7D, 0x65, 0xC1)),
                null,
                android.graphics.Shader.TileMode.CLAMP
            )
        }
        canvas.drawRect(0f, 0f, w.toFloat(), h.toFloat(), skyPaint)

        // Draw smooth strong radial center sky vignette (#8F74DF center over #4F448A -> #7D65C1 sky so center vs edge distSq > 5000 exceeds Pass 1!)
        val glowPaint = android.graphics.Paint().apply {
            shader = android.graphics.RadialGradient(
                360f, 690f, 240f,
                intArrayOf(android.graphics.Color.argb(245, 0x8F, 0x74, 0xDF), android.graphics.Color.argb(0, 0x5B, 0x48, 0x94)),
                null,
                android.graphics.Shader.TileMode.CLAMP
            )
        }
        canvas.drawCircle(360f, 690f, 240f, glowPaint)

        // Draw pawn at (218, 954) so sky glow at (360, 690) is right in the false target zone
        val pawnPaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            color = android.graphics.Color.rgb(0x3E, 0x33, 0x53)
        }
        val px = 218f
        val py = 954f
        val bodyPath = android.graphics.Path().apply {
            moveTo(px - 14f, py); lineTo(px + 14f, py); lineTo(px + 9f, py - 46f); lineTo(px - 9f, py - 46f); close()
        }
        canvas.drawPath(bodyPath, pawnPaint)
        canvas.drawOval(px - 16f, py - 10f, px + 16f, py + 4f, pawnPaint)
        val collarPaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            color = android.graphics.Color.rgb(210, 175, 98)
        }
        canvas.drawOval(px - 12f, py - 56f, px + 12f, py - 46f, collarPaint)
        canvas.drawCircle(px, py - 70f, 12f, pawnPaint)

        // Verify empty sky glow frame does NOT falsely lock onto (360, 690) even in Pass 1
        val emptySkyResult = BlockVisionAnalyzer.analyze(bmp, config)
        assertTrue("Pawn should be detected on radial glow frame", emptySkyResult.pawnFound)
        assertTrue("Smooth radial sky glow #8F74DF must NOT be detected as a block, got ${emptySkyResult.blockBounds}", !emptySkyResult.blockFound)

        // Now paint a wide (308px) & vertically close (dy = 82px) real chest block at (500, 872)
        val bx = 500f
        val by = 872f
        val hw = 154f
        val hh = 76f
        val depth = 64f
        val blockPaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
        val topPath = android.graphics.Path().apply {
            moveTo(bx, by - hh); lineTo(bx + hw, by); lineTo(bx, by + hh); lineTo(bx - hw, by); close()
        }
        val leftPath = android.graphics.Path().apply {
            moveTo(bx - hw, by); lineTo(bx, by + hh); lineTo(bx, by + hh + depth); lineTo(bx - hw, by + depth); close()
        }
        val rightPath = android.graphics.Path().apply {
            moveTo(bx + hw, by); lineTo(bx, by + hh); lineTo(bx, by + hh + depth); lineTo(bx + hw, by + depth); close()
        }
        blockPaint.color = android.graphics.Color.rgb(185, 118, 56); canvas.drawPath(leftPath, blockPaint)
        blockPaint.color = android.graphics.Color.rgb(155, 92, 42); canvas.drawPath(rightPath, blockPaint)
        blockPaint.color = android.graphics.Color.rgb(235, 194, 88); canvas.drawPath(topPath, blockPaint)

        val closeWideResult = BlockVisionAnalyzer.analyze(bmp, config)
        bmp.recycle()

        assertTrue("Close wide chest block (w=308, dy=82) should be detected", closeWideResult.blockFound)
        val lockedCenter = closeWideResult.blockBounds?.center
        assertTrue(
            "Analyzer should lock onto real block near (500, 872) rather than sky glow (360, 690); got $lockedCenter",
            lockedCenter != null && kotlin.math.abs(lockedCenter.x - 500f) < 35f && kotlin.math.abs(lockedCenter.y - 872f) < 35f
        )
    }

    @Test
    fun `detects newly appeared upper block centroid between touchdown and settled frames`() {
        val config = BotConfig(roiTopFraction = 0.28f, roiBottomFraction = 0.72f)
        val bmpPre = BlockVisionAnalyzer.generateSyntheticTestFrame(
            width = 720,
            height = 1600,
            pawnColorRgb = config.pawnColorRgb,
            targetBlockWidthPx = 220f,
            distanceFactor = 0.72f
        )
        val preRes = BlockVisionAnalyzer.analyze(bmpPre, config)
        assertTrue("Pawn should lock on touchdown frame", preRes.pawnFound)

        // Wipe the synthetic target block in bmpTouchdown so it simulates the frame BEFORE the new block drops in
        val bmpTouchdown = bmpPre.copy(android.graphics.Bitmap.Config.ARGB_8888, true)
        val wipeCanvas = android.graphics.Canvas(bmpTouchdown)
        val wipePaint = android.graphics.Paint().apply { color = android.graphics.Color.rgb(112, 118, 178) }
        val bx = preRes.blockBounds!!.center.x
        val by = preRes.blockBounds!!.center.y
        wipeCanvas.drawRect(bx - 130f, by - 90f, bx + 130f, by + 130f, wipePaint)

        val snap = BlockVisionAnalyzer.captureUpperRoiSnapshot(
            bitmap = bmpTouchdown,
            roiTopY = preRes.roiTopY,
            roiBottomY = preRes.roiBottomY,
            pawnX = preRes.pawnCenterX,
            pawnY = preRes.pawnBaseY
        )

        // Compare touchdown (without block) against bmpPre (with newly dropped block)
        val hint = BlockVisionAnalyzer.detectNewlyAppearedHint(
            prevSnapshot = snap,
            currentBitmap = bmpPre,
            currPawnX = preRes.pawnCenterX,
            currPawnY = preRes.pawnBaseY
        )
        val hintedRes = BlockVisionAnalyzer.analyze(bmpPre, config, preferredTargetHint = hint)
        bmpTouchdown.recycle()
        bmpPre.recycle()

        assertTrue("Newly appeared hint should point near ($bx, $by), got $hint", hint != null && kotlin.math.abs(hint.x - bx) < 45f && kotlin.math.abs(hint.y - by) < 85f)
        assertTrue("Hinted analysis should lock cleanly onto new block", hintedRes.blockFound)
    }

    @Test
    fun `detects pawn stance on source block (CENTER vs SIDE_BACK vs SIDE_FRONT) and applies contextual bias`() {
        val w = 720
        val h = 1280
        val baseCfg = BotConfig(kFactor = 1.40f, offsetMs = 20f)
        val centerBmp = BlockVisionAnalyzer.generateSyntheticTestFrame(
            width = w,
            height = h,
            pawnColorRgb = baseCfg.pawnColorRgb,
            targetBlockWidthPx = 150f, // on 720px => normalized 225px => NARROW
            distanceFactor = 0.68f
        )
        val centerRes = BlockVisionAnalyzer.analyze(centerBmp, baseCfg)
        centerBmp.recycle()

        assertTrue("Pawn should lock on center frame", centerRes.pawnFound)
        assertTrue("Target block should lock", centerRes.blockFound)
        assertEquals("Small 150px block on 720p must classify as NARROW", "NARROW", centerRes.targetBlockProfile)
        assertEquals("Centered pawn should be CENTER", "CENTER", centerRes.pawnStance)
        assertTrue("Source block width should be measured (~190px), got ${centerRes.sourceWidthPx}", centerRes.sourceWidthPx in 150f..230f)

        // Draw frame with pawn shifted to the far-left edge (SIDE_BACK) of the current platform
        val sideBmp = android.graphics.Bitmap.createBitmap(w, h, android.graphics.Bitmap.Config.ARGB_8888)
        val canvas = android.graphics.Canvas(sideBmp)
        val bgPaint = android.graphics.Paint().apply {
            shader = android.graphics.LinearGradient(
                0f, 0f, 0f, h.toFloat(),
                intArrayOf(android.graphics.Color.rgb(94, 92, 157), android.graphics.Color.rgb(141, 148, 201)),
                null,
                android.graphics.Shader.TileMode.CLAMP
            )
        }
        canvas.drawRect(0f, 0f, w.toFloat(), h.toFloat(), bgPaint)

        val curCx = w * 0.28f
        val curCy = h * 0.63f
        val curW = 240f
        val curH = 120f
        val topPath = android.graphics.Path().apply {
            moveTo(curCx, curCy - curH / 2f); lineTo(curCx + curW / 2f, curCy); lineTo(curCx, curCy + curH / 2f); lineTo(curCx - curW / 2f, curCy); close()
        }
        val leftWall = android.graphics.Path().apply {
            moveTo(curCx - curW / 2f, curCy); lineTo(curCx, curCy + curH / 2f); lineTo(curCx, curCy + curH / 2f + 90f); lineTo(curCx - curW / 2f, curCy + 90f); close()
        }
        val rightWall = android.graphics.Path().apply {
            moveTo(curCx + curW / 2f, curCy); lineTo(curCx, curCy + curH / 2f); lineTo(curCx, curCy + curH / 2f + 90f); lineTo(curCx + curW / 2f, curCy + 90f); close()
        }
        val blkPaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
        blkPaint.color = android.graphics.Color.rgb(30, 142, 149); canvas.drawPath(leftWall, blkPaint)
        blkPaint.color = android.graphics.Color.rgb(22, 118, 125); canvas.drawPath(rightWall, blkPaint)
        blkPaint.color = android.graphics.Color.rgb(250, 250, 250); canvas.drawPath(topPath, blkPaint)

        // Target block up-right
        val tCx = curCx + 320f
        val tCy = curCy - 180f
        val tW = 310f // normalized 465px => WIDE
        val tH = 155f
        val tTop = android.graphics.Path().apply {
            moveTo(tCx, tCy - tH / 2f); lineTo(tCx + tW / 2f, tCy); lineTo(tCx, tCy + tH / 2f); lineTo(tCx - tW / 2f, tCy); close()
        }
        val tLeft = android.graphics.Path().apply {
            moveTo(tCx - tW / 2f, tCy); lineTo(tCx, tCy + tH / 2f); lineTo(tCx, tCy + tH / 2f + 90f); lineTo(tCx - tW / 2f, tCy + 90f); close()
        }
        val tRight = android.graphics.Path().apply {
            moveTo(tCx + tW / 2f, tCy); lineTo(tCx, tCy + tH / 2f); lineTo(tCx, tCy + tH / 2f + 90f); lineTo(tCx + tW / 2f, tCy + 90f); close()
        }
        blkPaint.color = android.graphics.Color.rgb(225, 162, 65); canvas.drawPath(tLeft, blkPaint)
        blkPaint.color = android.graphics.Color.rgb(190, 128, 45); canvas.drawPath(tRight, blkPaint)
        blkPaint.color = android.graphics.Color.rgb(255, 196, 92); canvas.drawPath(tTop, blkPaint)

        // Draw pawn near BACK/LEFT side (curCx - 68px) on current block
        val pawnX = curCx - 68f
        val pawnY = curCy
        val pawnPaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            color = android.graphics.Color.rgb(0x3E, 0x33, 0x53)
        }
        val bodyPath = android.graphics.Path().apply {
            moveTo(pawnX - 14f, pawnY - 12f); lineTo(pawnX + 14f, pawnY - 12f)
            lineTo(pawnX + 9f, pawnY - 52f); lineTo(pawnX - 9f, pawnY - 52f); close()
        }
        canvas.drawPath(bodyPath, pawnPaint)
        canvas.drawOval(pawnX - 16f, pawnY - 18f, pawnX + 16f, pawnY - 4f, pawnPaint)
        val collarPaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply { color = android.graphics.Color.rgb(210, 175, 98) }
        canvas.drawOval(pawnX - 12f, pawnY - 60f, pawnX + 12f, pawnY - 50f, collarPaint)
        canvas.drawCircle(pawnX, pawnY - 72f, 12f, pawnPaint)

        val biasedCfg = baseCfg.copy(
            adaptiveBiasesMs = mapOf(
                "BLK:WIDE" to -15f,
                "STANCE:SIDE_BACK" to 35f
            )
        )
        val sideNoBias = BlockVisionAnalyzer.analyze(sideBmp, baseCfg)
        val sideWithBias = BlockVisionAnalyzer.analyze(sideBmp, biasedCfg)
        sideBmp.recycle()

        assertEquals("Wide block should classify as WIDE", "WIDE", sideNoBias.targetBlockProfile)
        assertEquals("Off-center back pawn must classify as SIDE_BACK", "SIDE_BACK", sideNoBias.pawnStance)
        assertTrue("Offset frac must be > 0.25, got ${sideNoBias.pawnStanceOffsetFrac}", sideNoBias.pawnStanceOffsetFrac > 0.25f)
        assertEquals("Net bias (-15 + 35) = +20ms should apply", 20L, sideWithBias.appliedContextBiasMs)
        assertEquals(sideNoBias.computedDurationMs + 20L, sideWithBias.computedDurationMs)
    }

    @Test
    fun `rejects Game Over popup overlay and bottom-edge brown phantom pawn artifacts`() {
        val config = BotConfig(roiTopFraction = 0.28f, roiBottomFraction = 0.72f)
        val w = 720
        val h = 1600

        // 1. Simulate Game Over modal card (#C6D4F4 + #FF7874 coral button + #5082B5 Replay button)
        val modalBmp = android.graphics.Bitmap.createBitmap(w, h, android.graphics.Bitmap.Config.ARGB_8888)
        val modalCanvas = android.graphics.Canvas(modalBmp)
        modalCanvas.drawColor(android.graphics.Color.rgb(0x46, 0x48, 0x8E))
        val paint = android.graphics.Paint()
        paint.color = android.graphics.Color.rgb(0xC6, 0xD4, 0xF4) // pale blue card
        modalCanvas.drawRect(80f, 460f, 640f, 920f, paint)
        paint.color = android.graphics.Color.rgb(0xFF, 0x78, 0x74) // coral close/share banner
        modalCanvas.drawRect(440f, 520f, 680f, 680f, paint)
        paint.color = android.graphics.Color.rgb(0x50, 0x82, 0xB5) // blue button right where #63 detected fake pawn
        modalCanvas.drawRect(330f, 700f, 390f, 795f, paint)
        val modalResult = BlockVisionAnalyzer.analyze(modalBmp, config)
        modalBmp.recycle()
        assertTrue("Game Over popup must not lock onto fake pawn", !modalResult.pawnFound)
        assertTrue("Game Over popup must not fire jumps", !modalResult.blockFound)

        // 2. Simulate normal sky with warm brown block corner at bottom-left margin (97, 1147, #68414A) + gold trim
        val phantomBmp = android.graphics.Bitmap.createBitmap(w, h, android.graphics.Bitmap.Config.ARGB_8888)
        val phantomCanvas = android.graphics.Canvas(phantomBmp)
        phantomCanvas.drawColor(android.graphics.Color.rgb(0x46, 0x48, 0x8E))
        paint.color = android.graphics.Color.rgb(0x68, 0x41, 0x4A)
        phantomCanvas.drawRect(65f, 1080f, 128f, 1152f, paint)
        paint.color = android.graphics.Color.rgb(215, 170, 90)
        phantomCanvas.drawRect(78f, 1105f, 118f, 1118f, paint)
        val phantomResult = BlockVisionAnalyzer.analyze(phantomBmp, config)
        phantomBmp.recycle()
        assertTrue("Bottom-left brown artifact at (97, 1147) must not trigger false pawn lock", !phantomResult.pawnFound)
    }

    @Test
    fun `tall cylindrical MINI cup keeps center Y on upper oval rim without drifting down sleeve wall`() {
        val w = 720
        val h = 1600
        val bmp = android.graphics.Bitmap.createBitmap(w, h, android.graphics.Bitmap.Config.ARGB_8888)
        val canvas = android.graphics.Canvas(bmp)
        canvas.drawColor(android.graphics.Color.rgb(0x46, 0x4F, 0x82))

        val blkPaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
        // Draw current block on right (around 500, 952)
        val curCx = 500f
        val curCy = 952f
        val curW = 240f
        val curH = 120f
        val curTop = android.graphics.Path().apply {
            moveTo(curCx, curCy - curH / 2f)
            lineTo(curCx + curW / 2f, curCy)
            lineTo(curCx, curCy + curH / 2f)
            lineTo(curCx - curW / 2f, curCy)
            close()
        }
        val curBody = android.graphics.Path().apply {
            moveTo(curCx - curW / 2f, curCy)
            lineTo(curCx, curCy + curH / 2f)
            lineTo(curCx + curW / 2f, curCy)
            lineTo(curCx + curW / 2f, curCy + 80f)
            lineTo(curCx, curCy + curH / 2f + 80f)
            lineTo(curCx - curW / 2f, curCy + 80f)
            close()
        }
        blkPaint.color = android.graphics.Color.rgb(22, 130, 145)
        canvas.drawPath(curBody, blkPaint)
        blkPaint.color = android.graphics.Color.rgb(245, 245, 245)
        canvas.drawPath(curTop, blkPaint)

        // Draw pawn at (499, 952)
        val pawnPaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            color = android.graphics.Color.rgb(0x3E, 0x33, 0x53)
        }
        val pawnX = 499f
        val pawnY = 952f
        val bodyPath = android.graphics.Path().apply {
            moveTo(pawnX - 14f, pawnY - 12f)
            lineTo(pawnX + 14f, pawnY - 12f)
            lineTo(pawnX + 9f, pawnY - 52f)
            lineTo(pawnX - 9f, pawnY - 52f)
            close()
        }
        canvas.drawPath(bodyPath, pawnPaint)
        canvas.drawOval(pawnX - 16f, pawnY - 18f, pawnX + 16f, pawnY - 4f, pawnPaint)
        val collarPaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            color = android.graphics.Color.rgb(210, 175, 98)
        }
        canvas.drawOval(pawnX - 12f, pawnY - 60f, pawnX + 12f, pawnY - 50f, collarPaint)
        canvas.drawCircle(pawnX, pawnY - 72f, 12f, pawnPaint)

        // Draw tall cylindrical MINI cup up-left along 30-degree isometric line:
        // true top oval center at (279, 822), width 91px, rim ellipse height 52px (topY = 796).
        // Below equator, add a tall teal/cream sleeve that slightly flares from 91px to 98px
        // around y=860..895 (mimicking rim bevel + cup wall noise).
        val tCx = 279f
        val tCy = 822f
        val tW = 91f
        val tH = 52f
        blkPaint.color = android.graphics.Color.rgb(22, 165, 172)
        canvas.drawRect(tCx - 45f, tCy, tCx + 45f, tCy + 85f, blkPaint)
        blkPaint.color = android.graphics.Color.rgb(198, 168, 120)
        canvas.drawRect(tCx - 49f, tCy + 34f, tCx + 49f, tCy + 68f, blkPaint)
        blkPaint.color = android.graphics.Color.rgb(244, 244, 244)
        canvas.drawOval(tCx - tW / 2f, tCy - tH / 2f, tCx + tW / 2f, tCy + tH / 2f, blkPaint)

        val cfg = BotConfig(roiTopFraction = 0.28f, roiBottomFraction = 0.72f)
        val result = BlockVisionAnalyzer.analyze(bmp, cfg)
        bmp.recycle()

        assertTrue("Pawn should be detected", result.pawnFound)
        assertTrue("MINI cylindrical block should be detected", result.blockFound)
        val bounds = result.blockBounds!!
        assertEquals("Should classify as MINI", "MINI", result.targetBlockProfile)
        val centerOffsetRatio = (bounds.center.y - bounds.topY) / bounds.widthPx.coerceAtLeast(1f)
        assertTrue(
            "Cylinder center.y (${bounds.center.y}) drifted down wall! topY=${bounds.topY}, w=${bounds.widthPx}, ratio=$centerOffsetRatio",
            centerOffsetRatio <= 0.34f && kotlin.math.abs(bounds.center.y - tCy) <= 10f
        )
    }

    @Test
    fun `detects ultra-small 48px target block cleanly even when distant large block is visible`() {
        val w = 720
        val h = 1600
        val bmp = android.graphics.Bitmap.createBitmap(w, h, android.graphics.Bitmap.Config.ARGB_8888)
        val canvas = android.graphics.Canvas(bmp)
        canvas.drawColor(android.graphics.Color.rgb(0x46, 0x4F, 0x82))

        val blkPaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
        // Current platform at (220, 950)
        val curCx = 220f
        val curCy = 950f
        val curTop = android.graphics.Path().apply {
            moveTo(curCx, curCy - 60f); lineTo(curCx + 120f, curCy); lineTo(curCx, curCy + 60f); lineTo(curCx - 120f, curCy); close()
        }
        val curBody = android.graphics.Path().apply {
            moveTo(curCx - 120f, curCy); lineTo(curCx, curCy + 60f); lineTo(curCx + 120f, curCy)
            lineTo(curCx + 120f, curCy + 85f); lineTo(curCx, curCy + 145f); lineTo(curCx - 120f, curCy + 85f); close()
        }
        blkPaint.color = android.graphics.Color.rgb(22, 130, 145); canvas.drawPath(curBody, blkPaint)
        blkPaint.color = android.graphics.Color.rgb(245, 245, 245); canvas.drawPath(curTop, blkPaint)

        // Pawn on current block
        val pawnPaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            color = android.graphics.Color.rgb(0x3E, 0x33, 0x53)
        }
        val bodyPath = android.graphics.Path().apply {
            moveTo(curCx - 14f, curCy - 12f); lineTo(curCx + 14f, curCy - 12f)
            lineTo(curCx + 9f, curCy - 52f); lineTo(curCx - 9f, curCy - 52f); close()
        }
        canvas.drawPath(bodyPath, pawnPaint)
        canvas.drawOval(curCx - 16f, curCy - 18f, curCx + 16f, curCy - 4f, pawnPaint)
        val collarPaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply { color = android.graphics.Color.rgb(210, 175, 98) }
        canvas.drawOval(curCx - 12f, curCy - 60f, curCx + 12f, curCy - 50f, collarPaint)
        canvas.drawCircle(curCx, curCy - 72f, 12f, pawnPaint)

        // Ultra-small 48px target block along 30° up-right at (405, 842)
        val smallCx = 405f
        val smallCy = 842f
        val smallW = 48f
        val smallH = 26f
        blkPaint.color = android.graphics.Color.rgb(40, 165, 175)
        canvas.drawRect(smallCx - 24f, smallCy, smallCx + 24f, smallCy + 56f, blkPaint)
        blkPaint.color = android.graphics.Color.rgb(252, 232, 130)
        canvas.drawOval(smallCx - smallW / 2f, smallCy - smallH / 2f, smallCx + smallW / 2f, smallCy + smallH / 2f, blkPaint)

        // Distant large block further away at (590, 620) that must NOT steal focus from the close 48px block
        val bigCx = 590f
        val bigCy = 620f
        val bigTop = android.graphics.Path().apply {
            moveTo(bigCx, bigCy - 60f); lineTo(bigCx + 115f, bigCy); lineTo(bigCx, bigCy + 60f); lineTo(bigCx - 115f, bigCy); close()
        }
        blkPaint.color = android.graphics.Color.rgb(210, 140, 235)
        canvas.drawPath(bigTop, blkPaint)

        val result = BlockVisionAnalyzer.analyze(bmp, BotConfig(roiTopFraction = 0.28f, roiBottomFraction = 0.72f))
        bmp.recycle()

        assertTrue("Pawn should be detected", result.pawnFound)
        assertTrue("Ultra-small 48px block should be detected", result.blockFound)
        val blk = result.blockBounds!!
        assertEquals("Should classify 48px target as MINI", "MINI", result.targetBlockProfile)
        assertTrue(
            "Should lock onto close 48px target around x=405, got x=${blk.center.x} w=${blk.widthPx}",
            kotlin.math.abs(blk.center.x - smallCx) <= 12f && kotlin.math.abs(blk.center.y - smallCy) <= 10f
        )
    }

    @Test
    fun `rejects steep off-axis block above pawn and neutral gray false pawn`() {
        val w = 720
        val h = 1600
        val bmp = android.graphics.Bitmap.createBitmap(w, h, android.graphics.Bitmap.Config.ARGB_8888)
        val canvas = android.graphics.Canvas(bmp)
        canvas.drawColor(android.graphics.Color.rgb(0x46, 0x4F, 0x82))

        val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
        // Draw pawn cleanly at (470, 897) on right platform
        val px = 470f
        val py = 897f
        paint.color = android.graphics.Color.rgb(22, 130, 145)
        canvas.drawOval(px - 80f, py - 35f, px + 80f, py + 45f, paint)
        paint.color = android.graphics.Color.rgb(0x3E, 0x33, 0x53)
        canvas.drawOval(px - 15f, py - 18f, px + 15f, py - 3f, paint)
        canvas.drawRect(px - 10f, py - 52f, px + 10f, py - 12f, paint)
        paint.color = android.graphics.Color.rgb(210, 175, 98)
        canvas.drawOval(px - 12f, py - 60f, px + 12f, py - 50f, paint)
        paint.color = android.graphics.Color.rgb(0x3E, 0x33, 0x53)
        canvas.drawCircle(px, py - 72f, 12f, paint)

        // Draw a steep impossible off-axis cyan block at (544, 776) (dx=+74, dy=-121, ratio=0.61)
        val bx = 544f
        val by = 776f
        val steepTop = android.graphics.Path().apply {
            moveTo(bx, by - 44f); lineTo(bx + 84f, by); lineTo(bx, by + 44f); lineTo(bx - 84f, by); close()
        }
        paint.color = android.graphics.Color.rgb(0x2C, 0xB4, 0xC0)
        canvas.drawPath(steepTop, paint)

        val res = BlockVisionAnalyzer.analyze(bmp, BotConfig(roiTopFraction = 0.28f, roiBottomFraction = 0.72f))
        bmp.recycle()
        assertTrue("Pawn should be detected", res.pawnFound)
        assertTrue("Steep off-axis target dx=74 dy=-121 must be rejected, got ${res.blockBounds}", !res.blockFound)

        // Now verify a neutral gray #8B8B8B block with a yellow dot does NOT falsely lock as a pawn
        val grayBmp = android.graphics.Bitmap.createBitmap(w, h, android.graphics.Bitmap.Config.ARGB_8888)
        val grayCanvas = android.graphics.Canvas(grayBmp)
        grayCanvas.drawColor(android.graphics.Color.rgb(0x46, 0x4F, 0x82))
        paint.color = android.graphics.Color.rgb(0x8B, 0x8B, 0x8B)
        grayCanvas.drawRect(445f, 830f, 495f, 898f, paint)
        paint.color = android.graphics.Color.rgb(215, 170, 80)
        grayCanvas.drawRect(460f, 838f, 480f, 848f, paint)
        val grayRes = BlockVisionAnalyzer.analyze(grayBmp, BotConfig(roiTopFraction = 0.28f, roiBottomFraction = 0.72f))
        grayBmp.recycle()
        assertTrue("Neutral gray #8B8B8B artifact must not trigger fake pawn lock", !grayRes.pawnFound)
    }

    @Test
    fun `detects dark charcoal 3D block via Pass 4 and rejects edge-block Game Over false positive`() {
        val w = 720
        val h = 1600
        val bmp = android.graphics.Bitmap.createBitmap(w, h, android.graphics.Bitmap.Config.ARGB_8888)
        val canvas = android.graphics.Canvas(bmp)
        canvas.drawColor(android.graphics.Color.rgb(0x52, 0x56, 0x98))

        val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)

        // Bright coral strip along left screen edge (simulates large colorful edge block, NOT a modal dialog)
        paint.color = android.graphics.Color.rgb(240, 105, 95)
        canvas.drawRect(0f, 520f, 38f, 920f, paint)

        // Draw current white/teal source block at (230, 945)
        val curCx = 230f
        val curCy = 945f
        val curTop = android.graphics.Path().apply {
            moveTo(curCx, curCy - 52f); lineTo(curCx + 105f, curCy); lineTo(curCx, curCy + 52f); lineTo(curCx - 105f, curCy); close()
        }
        val curSide = android.graphics.Path().apply {
            moveTo(curCx - 105f, curCy); lineTo(curCx, curCy + 52f); lineTo(curCx + 105f, curCy)
            lineTo(curCx + 105f, curCy + 75f); lineTo(curCx, curCy + 127f); lineTo(curCx - 105f, curCy + 75f); close()
        }
        paint.color = android.graphics.Color.rgb(28, 138, 150); canvas.drawPath(curSide, paint)
        paint.color = android.graphics.Color.rgb(245, 245, 245); canvas.drawPath(curTop, paint)

        // Draw real pawn at (230, 945)
        paint.color = android.graphics.Color.rgb(0x3E, 0x33, 0x53)
        canvas.drawOval(curCx - 15f, curCy - 18f, curCx + 15f, curCy - 3f, paint)
        canvas.drawRect(curCx - 10f, curCy - 52f, curCx + 10f, curCy - 12f, paint)
        paint.color = android.graphics.Color.rgb(210, 175, 98)
        canvas.drawOval(curCx - 12f, curCy - 60f, curCx + 12f, curCy - 50f, paint)
        paint.color = android.graphics.Color.rgb(0x3E, 0x33, 0x53)
        canvas.drawCircle(curCx, curCy - 72f, 12f, paint)

        // Draw zero-collar dark shadow patch near ROI bottom-right (mimics #141 phantom pawn at y=1135)
        canvas.drawRect(540f, 1085f, 578f, 1132f, paint)

        // Draw dark slate-indigo 3D block (#343662 top, #202242 left, #161830 right) matching #525698 sky hue
        val bx = 485f
        val by = 795f
        val hw = 100f
        val hh = 50f
        val depth = 72f
        val darkTop = android.graphics.Path().apply {
            moveTo(bx, by - hh); lineTo(bx + hw, by); lineTo(bx, by + hh); lineTo(bx - hw, by); close()
        }
        val darkLeft = android.graphics.Path().apply {
            moveTo(bx - hw, by); lineTo(bx, by + hh); lineTo(bx, by + hh + depth); lineTo(bx - hw, by + depth); close()
        }
        val darkRight = android.graphics.Path().apply {
            moveTo(bx + hw, by); lineTo(bx, by + hh); lineTo(bx, by + hh + depth); lineTo(bx + hw, by + depth); close()
        }
        paint.color = android.graphics.Color.rgb(0x20, 0x22, 0x42); canvas.drawPath(darkLeft, paint)
        paint.color = android.graphics.Color.rgb(0x16, 0x18, 0x30); canvas.drawPath(darkRight, paint)
        paint.color = android.graphics.Color.rgb(0x34, 0x36, 0x62); canvas.drawPath(darkTop, paint)

        val result = BlockVisionAnalyzer.analyze(bmp, BotConfig(roiTopFraction = 0.28f, roiBottomFraction = 0.72f))
        bmp.recycle()

        assertTrue("Edge block must not trigger Game Over overlay: ${result.statusMessage}", result.pawnFound)
        assertTrue(
            "Should lock onto real pawn (~230) instead of bottom-right phantom (559), got ${result.pawnCenterX}",
            kotlin.math.abs(result.pawnCenterX - curCx) <= 15f
        )
        assertTrue("Dark slate 3D block matching sky hue should be detected by Pass 4: ${result.statusMessage}", result.blockFound)
        val lockedCenter = result.blockBounds?.center
        assertTrue(
            "Dark 3D block center should be near (485, 795), got $lockedCenter",
            lockedCenter != null && kotlin.math.abs(lockedCenter.x - bx) <= 20f && kotlin.math.abs(lockedCenter.y - by) <= 22f
        )
    }

    @Test
    fun `separates close touching blocks without merging source and target footprints`() {
        val w = 720
        val h = 1600
        val bmp = android.graphics.Bitmap.createBitmap(w, h, android.graphics.Bitmap.Config.ARGB_8888)
        val canvas = android.graphics.Canvas(bmp)
        canvas.drawColor(android.graphics.Color.rgb(0x53, 0x40, 0x7D))

        val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
        // Current cylinder on left at (260, 922, w=150) whose right wall reaches x=335
        val curCx = 260f
        val curCy = 922f
        paint.color = android.graphics.Color.rgb(190, 125, 250)
        canvas.drawRect(curCx - 75f, curCy, curCx + 75f, curCy + 75f, paint)
        paint.color = android.graphics.Color.rgb(245, 240, 255)
        canvas.drawOval(curCx - 75f, curCy - 36f, curCx + 75f, curCy + 36f, paint)

        // Close target diamond at (435, 818, w=250) whose left tip touches x=310 right beside pawn!
        val tCx = 435f
        val tCy = 818f
        val tHw = 125f
        val tHh = 62f
        val tDepth = 88f
        val tTop = android.graphics.Path().apply {
            moveTo(tCx, tCy - tHh); lineTo(tCx + tHw, tCy); lineTo(tCx, tCy + tHh); lineTo(tCx - tHw, tCy); close()
        }
        val tLeft = android.graphics.Path().apply {
            moveTo(tCx - tHw, tCy); lineTo(tCx, tCy + tHh); lineTo(tCx, tCy + tHh + tDepth); lineTo(tCx - tHw, tCy + tDepth); close()
        }
        val tRight = android.graphics.Path().apply {
            moveTo(tCx + tHw, tCy); lineTo(tCx, tCy + tHh); lineTo(tCx, tCy + tHh + tDepth); lineTo(tCx + tHw, tCy + tDepth); close()
        }
        paint.color = android.graphics.Color.rgb(112, 68, 172); canvas.drawPath(tLeft, paint)
        paint.color = android.graphics.Color.rgb(92, 54, 148); canvas.drawPath(tRight, paint)
        paint.color = android.graphics.Color.rgb(215, 165, 255); canvas.drawPath(tTop, paint)

        // Pawn standing at (280, 922) right next to touching target block
        val px = 280f
        val py = 922f
        paint.color = android.graphics.Color.rgb(0x3E, 0x33, 0x53)
        canvas.drawOval(px - 15f, py - 18f, px + 15f, py - 3f, paint)
        canvas.drawRect(px - 10f, py - 52f, px + 10f, py - 12f, paint)
        paint.color = android.graphics.Color.rgb(210, 175, 98)
        canvas.drawOval(px - 12f, py - 60f, px + 12f, py - 50f, paint)
        paint.color = android.graphics.Color.rgb(0x3E, 0x33, 0x53)
        canvas.drawCircle(px, py - 72f, 12f, paint)

        val result = BlockVisionAnalyzer.analyze(bmp, BotConfig(roiTopFraction = 0.28f, roiBottomFraction = 0.72f))
        bmp.recycle()

        assertTrue("Pawn should lock on touching blocks frame", result.pawnFound)
        assertTrue("Close touching target block should be detected", result.blockFound)
        val blk = result.blockBounds!!
        assertTrue(
            "Target block center.x should stay near 435 without bleeding across pawn; got ${blk.center.x}",
            blk.center.x in 420f..455f
        )
        assertTrue(
            "Target block width should stay near 250px without merging into source; got ${blk.widthPx}",
            blk.widthPx in 225f..275f
        )
    }
}


