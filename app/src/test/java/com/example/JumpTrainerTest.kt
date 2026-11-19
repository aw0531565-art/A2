package com.example

import com.example.model.BlockBounds
import com.example.model.BotConfig
import com.example.model.BotLogEntry
import com.example.model.JumpOutcome
import com.example.model.JumpTelemetryRecord
import com.example.model.PointF2D
import com.example.model.VisionAnalysisResult
import com.example.training.JumpTrainer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class JumpTrainerTest {

    private fun makeResult(
        pawnFound: Boolean = true,
        px: Float = 218f,
        py: Float = 954f,
        blockFound: Boolean = true,
        bx: Float = 548f,
        by: Float = 755f,
        dRaw: Float = 385f,
        dNorm: Float = 577f,
        holdMs: Long = 765L
    ): VisionAnalysisResult {
        return VisionAnalysisResult(
            imageWidth = 720,
            imageHeight = 1600,
            roiTopY = 448f,
            roiBottomY = 1152f,
            pawnFound = pawnFound,
            pawnCenterX = px,
            pawnBaseY = py,
            pawnPixelCount = if (pawnFound) 2600 else 0,
            blockFound = blockFound,
            blockBounds = if (blockFound) {
                BlockBounds(
                    apex = PointF2D(bx, by - 72f),
                    center = PointF2D(bx, by),
                    leftEdgeX = bx - 130f,
                    rightEdgeX = bx + 130f,
                    topY = by - 72f,
                    bottomY = by + 16f,
                    widthPx = 260f,
                    heightPx = 88f
                )
            } else null,
            rawDistancePx = dRaw,
            normalizedDistancePx = dNorm,
            computedDurationMs = holdMs,
            safeMarginMs = 150L,
            analysisTimeMs = 22L,
            statusMessage = "ok"
        )
    }

    @Test
    fun `computeLandingRatio measures short center and overshoot accurately`() {
        val pre = makeResult(px = 218f, py = 954f, bx = 548f, by = 755f, dRaw = 385.3f)

        val shortLand = makeResult(px = 482f, py = 795f) // 80% of jump vector
        val shortRatio = JumpTrainer.computeLandingRatio(pre, shortLand)
        assertNotNull(shortRatio)
        assertEquals(0.80f, shortRatio!!, 0.03f)

        val centerLand = makeResult(px = 548f, py = 755f)
        val centerRatio = JumpTrainer.computeLandingRatio(pre, centerLand)
        assertNotNull(centerRatio)
        assertEquals(1.00f, centerRatio!!, 0.02f)

        val overLand = makeResult(px = 630f, py = 706f) // ~125%
        val overRatio = JumpTrainer.computeLandingRatio(pre, overLand)
        assertNotNull(overRatio)
        assertEquals(1.25f, overRatio!!, 0.03f)

        // Orthogonal unrelated artifact should be rejected
        val bogusLand = makeResult(px = 218f, py = 500f)
        assertNull(JumpTrainer.computeLandingRatio(pre, bogusLand))
    }

    @Test
    fun `online update raises k on short jumps and lowers k on overshoots`() {
        val cfg = BotConfig(kFactor = 1.26f, offsetMs = 38f, autoTrainEnabled = true)

        val (raised, shortNote) = JumpTrainer.applyOnlineUpdate(
            config = cfg,
            dNorm = 577f,
            holdMs = 765L,
            outcome = JumpOutcome.SHORT,
            landingRatio = 0.80f,
            history = emptyList()
        )
        assertTrue("Short jump should increase kFactor ($shortNote)", raised.kFactor > cfg.kFactor)
        assertTrue("Short jump should increase offsetMs", raised.offsetMs > cfg.offsetMs)

        val (lowered, overNote) = JumpTrainer.applyOnlineUpdate(
            config = cfg,
            dNorm = 577f,
            holdMs = 765L,
            outcome = JumpOutcome.PAST_CENTER,
            landingRatio = 1.22f,
            history = emptyList()
        )
        assertTrue("Overshoot jump should lower kFactor ($overNote)", lowered.kFactor < cfg.kFactor)
        assertTrue("Overshoot jump should lower offsetMs", lowered.offsetMs < cfg.offsetMs)

        val (unchanged, centerNote) = JumpTrainer.applyOnlineUpdate(
            config = cfg,
            dNorm = 577f,
            holdMs = 765L,
            outcome = JumpOutcome.CENTER,
            landingRatio = 0.99f,
            history = emptyList()
        )
        assertEquals("Dead center jump should keep k steady ($centerNote)", cfg.kFactor, unchanged.kFactor, 0.001f)
    }

    @Test
    fun `manual feedback and report formatting produce copyable diagnostics`() {
        val cfg = BotConfig()
        val rec = JumpTelemetryRecord(
            id = 1L,
            timestamp = "12:00:01",
            pawnX = 218f,
            pawnY = 954f,
            pawnPixels = 2600,
            blockX = 548f,
            blockY = 755f,
            blockWidth = 260f,
            blockHeight = 88f,
            rawDistancePx = 385f,
            normalizedDistancePx = 577f,
            holdMs = 765L,
            landingRatio = 0.85f,
            outcome = JumpOutcome.OUT_SHORT,
            kBefore = 1.26f,
            bBefore = 38f,
            kAfter = 1.32f,
            bAfter = 42f,
            skyTopHex = "#5E5C9D",
            skyMidHex = "#828ACB",
            pawnSampleHex = "#3E3353",
            note = "Test"
        )
        val (updatedCfg, updatedRec, msg) = JumpTrainer.applyManualFeedback(cfg, rec, isShort = true)
        assertTrue("Manual short feedback increases k", updatedCfg.kFactor > cfg.kFactor)
        assertEquals(JumpOutcome.USER_SHORT, updatedRec?.outcome)
        assertTrue(msg.contains("USR_SHORT"))

        val report = JumpTrainer.formatTelemetryReport(
            config = updatedCfg,
            records = listOf(updatedRec!!),
            logs = listOf(BotLogEntry(1L, "12:00:02", "Self-Train active", isError = false)),
            screenW = 720,
            screenH = 1600
        )
        assertTrue(report.contains("=== JumpAssist Training Report ==="))
        assertTrue(report.contains("720x1600"))
        assertTrue(report.contains("#5E5C9D"))
        assertTrue(report.contains("D=385(n=577)"))
        assertTrue(report.contains("Self-Train active"))
    }

    @Test
    fun `fatal OUT_OVERSHOOT with near-1 edge ratio still lowers k and skips OLS undo`() {
        val cfg = BotConfig(kFactor = 1.56f, offsetMs = 10f, autoTrainEnabled = true)
        val staleHistory = (1..5).map { idx ->
            JumpTelemetryRecord(
                id = idx.toLong(),
                timestamp = "12:00:0$idx",
                pawnX = 220f,
                pawnY = 950f,
                pawnPixels = 2700,
                blockX = 500f,
                blockY = 740f,
                blockWidth = 236f,
                blockHeight = 80f,
                rawDistancePx = 220f + idx * 40f,
                normalizedDistancePx = 330f + idx * 60f,
                holdMs = (530 + idx * 95).toLong(),
                landingRatio = 0.90f, // biased upward
                outcome = JumpOutcome.CENTER,
                kBefore = 1.56f,
                bBefore = 10f,
                kAfter = 1.56f,
                bAfter = 10f
            )
        }

        val (afterOut, note) = JumpTrainer.applyOnlineUpdate(
            config = cfg,
            dNorm = 410f,
            holdMs = 591L,
            outcome = JumpOutcome.OUT_OVERSHOOT,
            landingRatio = 1.04f,
            history = staleHistory
        )

        assertTrue("OUT_OVERSHOOT must reduce k even if pre-fall ratio was 1.04 ($note)", afterOut.kFactor < cfg.kFactor)
        assertTrue("OUT_OVERSHOOT must not label Dead center ($note)", !note.contains("Dead center"))
        assertTrue("OUT_OVERSHOOT must skip stale OLS blend ($note)", !note.contains("OLS"))
    }

    @Test
    fun `manual correction clears stale landingRatio and blocks older history from undoing fix`() {
        val cfg = BotConfig(kFactor = 1.48f, offsetMs = 2f, autoTrainEnabled = true)
        val misleadingRecord = JumpTelemetryRecord(
            id = 10L,
            timestamp = "16:21:46",
            pawnX = 247f,
            pawnY = 932f,
            pawnPixels = 3763,
            blockX = 373f,
            blockY = 754f,
            blockWidth = 236f,
            blockHeight = 80f,
            rawDistancePx = 219f,
            normalizedDistancePx = 328f,
            holdMs = 473L,
            landingRatio = 1.05f,
            outcome = JumpOutcome.CENTER,
            kBefore = 1.48f,
            bBefore = 2f,
            kAfter = 1.48f,
            bAfter = 2f
        )

        val (manualCfg, manualRec, _) = JumpTrainer.applyManualFeedback(cfg, misleadingRecord, isShort = true)
        assertNull("Manual correction must clear stale sensor landingRatio", manualRec?.landingRatio)
        assertTrue(manualCfg.kFactor > cfg.kFactor)

        val olderLowHistory = listOf(manualRec!!) + (1..5).map { idx ->
            misleadingRecord.copy(
                id = idx.toLong(),
                normalizedDistancePx = 300f + idx * 70f,
                holdMs = (400 + idx * 80).toLong(),
                landingRatio = 1.15f,
                outcome = JumpOutcome.CENTER
            )
        }

        val (afterCenter, note) = JumpTrainer.applyOnlineUpdate(
            config = manualCfg,
            dNorm = 500f,
            holdMs = 740L,
            outcome = JumpOutcome.CENTER,
            landingRatio = 1.00f,
            history = olderLowHistory
        )
        assertEquals("Older pre-manual records must not drag k back down via OLS ($note)", manualCfg.kFactor, afterCenter.kFactor, 0.001f)
    }

    @Test
    fun `OUT_UNKNOWN with high hold immediately recovers k downward and never repeats fatal hold`() {
        val highCfg = BotConfig(kFactor = 2.069f, offsetMs = 42f, autoTrainEnabled = true)
        val (recovered, note) = JumpTrainer.applyOnlineUpdate(
            config = highCfg,
            dNorm = 576f,
            holdMs = 1234L,
            outcome = JumpOutcome.OUT_UNKNOWN,
            landingRatio = null,
            history = emptyList()
        )
        assertTrue("OUT_UNKNOWN with k=2.069 must pull k down ($note)", recovered.kFactor <= highCfg.kFactor - 0.055f)
        assertTrue("OUT_UNKNOWN must pull b down ($note)", recovered.offsetMs < highCfg.offsetMs)
    }

    @Test
    fun `surviving pan-drifted ratio below 0_72 does not spike k upward`() {
        val cfg = BotConfig(kFactor = 1.48f, offsetMs = 2f, autoTrainEnabled = true)
        val pre = makeResult(px = 218f, py = 954f, bx = 548f, by = 755f, dRaw = 385f, dNorm = 577f)
        val postSettled = makeResult(px = 218f, py = 954f, bx = 520f, by = 770f)

        val outcome = JumpTrainer.classifyOutcome(pre, landingRatio = 0.57f, postSettled = postSettled)
        assertEquals("Surviving with r=0.57 must be classified as pan-drifted CENTER, not SHORT", JumpOutcome.CENTER, outcome)

        val (updated, note) = JumpTrainer.applyOnlineUpdate(
            config = cfg,
            dNorm = 544f,
            holdMs = 808L,
            outcome = outcome,
            landingRatio = 0.57f,
            history = emptyList()
        )
        assertEquals("Pan-drifted ratio 0.57 must not spike k ($note)", cfg.kFactor, updated.kFactor, 0.001f)
    }

    @Test
    fun `learns separate biases for NARROW blocks and SIDE_BACK stance without distorting MEDIUM base`() {
        val baseCfg = BotConfig(kFactor = 1.40f, offsetMs = 20f, autoTrainEnabled = true)

        val (afterNarrowFar, note1) = JumpTrainer.applyOnlineUpdate(
            config = baseCfg,
            dNorm = 450f,
            holdMs = 650L,
            outcome = JumpOutcome.OUT_OVERSHOOT,
            landingRatio = 1.18f,
            history = emptyList(),
            targetProfile = "NARROW",
            targetColorGroup = "BLUE",
            pawnStance = "CENTER"
        )

        val narrowAdj = JumpTrainer.computeContextAdjustmentMs(afterNarrowFar, 450f, "NARROW", "BLUE", "CENTER")
        val mediumAdj = JumpTrainer.computeContextAdjustmentMs(afterNarrowFar, 450f, "MEDIUM", "WHITE", "CENTER")
        assertTrue("NARROW overshoot must learn negative NARROW bias ($note1), got $narrowAdj", narrowAdj < -20L)
        assertEquals("MEDIUM/WHITE must remain unaffected by NARROW specialty bias", 0L, mediumAdj)

        val (afterSideShort, note2) = JumpTrainer.applyOnlineUpdate(
            config = afterNarrowFar,
            dNorm = 450f,
            holdMs = 630L,
            outcome = JumpOutcome.SHORT,
            landingRatio = 0.84f,
            history = emptyList(),
            targetProfile = "MEDIUM",
            targetColorGroup = "WHITE",
            pawnStance = "SIDE_BACK"
        )

        val sideBackAdj = JumpTrainer.computeContextAdjustmentMs(afterSideShort, 450f, "MEDIUM", "WHITE", "SIDE_BACK")
        assertTrue("SIDE_BACK short must learn positive SIDE_BACK bias ($note2), got $sideBackAdj", sideBackAdj > 12L)
    }

    @Test
    fun `OUT_UNKNOWN near nominal anchor records MIST guard so failed hold is never repeated`() {
        val nominalCfg = BotConfig(kFactor = 1.38f, offsetMs = 40f, autoTrainEnabled = true)

        val (guardedCfg, note) = JumpTrainer.applyOnlineUpdate(
            config = nominalCfg,
            dNorm = 444f,
            holdMs = 653L,
            outcome = JumpOutcome.OUT_UNKNOWN,
            landingRatio = null,
            history = emptyList(),
            targetProfile = "NARROW",
            targetColorGroup = "BLUE",
            pawnStance = "SIDE_FRONT"
        )

        val nextAdj = JumpTrainer.computeContextAdjustmentMs(guardedCfg, 444f, "NARROW", "BLUE", "SIDE_FRONT")
        assertTrue("Mistake guard must shift next hold on same spot by >= 25ms ($note), got $nextAdj", kotlin.math.abs(nextAdj) >= 25L)

        val report = JumpTrainer.formatTelemetryReport(
            config = guardedCfg,
            records = listOf(
                JumpTelemetryRecord(
                    id = 1L,
                    timestamp = "09:20:00",
                    pawnX = 359f,
                    pawnY = 783f,
                    pawnPixels = 2900,
                    blockX = 259f,
                    blockY = 504f,
                    blockWidth = 159f,
                    blockHeight = 80f,
                    rawDistancePx = 296f,
                    normalizedDistancePx = 444f,
                    holdMs = 653L,
                    landingRatio = null,
                    outcome = JumpOutcome.OUT_UNKNOWN,
                    kBefore = nominalCfg.kFactor,
                    bBefore = nominalCfg.offsetMs,
                    kAfter = guardedCfg.kFactor,
                    bAfter = guardedCfg.offsetMs,
                    pawnStance = "SIDE_FRONT",
                    stanceOffsetFrac = 0.34f,
                    sourceWidthPx = 220f,
                    targetProfile = "NARROW",
                    targetColorGroup = "BLUE",
                    appliedBiasMs = 0L,
                    note = note
                )
            ),
            logs = emptyList(),
            screenW = 720,
            screenH = 1600
        )
        assertTrue("Report must contain AdaptiveBiases section", report.contains("AdaptiveBiases:"))
        assertTrue("Report must log stance S=SIDE_FRONT", report.contains("S=SIDE_FRONT"))
        assertTrue("Report must log target profile NARROW:BLUE", report.contains("NARROW:BLUE"))
    }

    @Test
    fun `MINI block learns distinct power bias quickly with minimal global k distortion`() {
        val baseCfg = BotConfig(kFactor = 1.38f, offsetMs = 40f, autoTrainEnabled = true)

        val (afterMiniShort, note) = JumpTrainer.applyOnlineUpdate(
            config = baseCfg,
            dNorm = 390f,
            holdMs = 578L,
            outcome = JumpOutcome.SHORT,
            landingRatio = 0.82f,
            history = emptyList(),
            targetProfile = "MINI",
            targetColorGroup = "WHITE",
            pawnStance = "CENTER"
        )

        val miniBias = JumpTrainer.computeContextAdjustmentMs(afterMiniShort, 390f, "MINI", "WHITE", "CENTER")
        val medBias = JumpTrainer.computeContextAdjustmentMs(afterMiniShort, 390f, "MEDIUM", "WHITE", "CENTER")
        assertTrue("MINI short jump must build strong +MINI bias ($note), got $miniBias", miniBias >= 30L)
        assertEquals("MEDIUM block must stay clean of MINI bias", 0L, medBias)
        assertTrue(
            "Global kFactor must shift very gently on MINI specialty block (1.38 -> ${afterMiniShort.kFactor})",
            kotlin.math.abs(afterMiniShort.kFactor - baseCfg.kFactor) <= 0.03f
        )
    }

    @Test
    fun `single short-distance PAST outlier cannot slash k sharply or wipe SIDE_BACK bias`() {
        val trainedCfg = BotConfig(
            kFactor = 1.363f,
            offsetMs = 45f,
            autoTrainEnabled = true,
            adaptiveBiasesMs = mapOf("STANCE:SIDE_BACK" to 24f)
        )
        val (afterPast, note) = JumpTrainer.applyOnlineUpdate(
            config = trainedCfg,
            dNorm = 243f,
            holdMs = 384L,
            outcome = JumpOutcome.PAST_CENTER,
            landingRatio = 1.34f,
            history = emptyList(),
            targetProfile = "MEDIUM",
            targetColorGroup = "CYAN",
            pawnStance = "SIDE_BACK"
        )
        assertTrue(
            "Non-fatal PAST outlier must never drop k by more than 0.014 ($note: 1.363 -> ${afterPast.kFactor})",
            trainedCfg.kFactor - afterPast.kFactor <= 0.0141f
        )
        val sideBackAfter = afterPast.adaptiveBiasesMs["STANCE:SIDE_BACK"] ?: 0f
        assertTrue(
            "Single PAST outlier must not wipe positive SIDE_BACK bias (remained $sideBackAfter)",
            sideBackAfter >= 12f
        )
    }

    @Test
    fun `slight short drift r=0_96 trims k gently and OUT_UNKNOWN r=0_95 recovers upward`() {
        val cfg = BotConfig(kFactor = 1.314f, offsetMs = 43f, autoTrainEnabled = true)
        val (trimmed, trimNote) = JumpTrainer.applyOnlineUpdate(
            config = cfg,
            dNorm = 413f,
            holdMs = 578L,
            outcome = JumpOutcome.CENTER,
            landingRatio = 0.96f,
            history = emptyList()
        )
        assertTrue("Slightly short r=0.96 should trim k gently upward ($trimNote)", trimmed.kFactor > cfg.kFactor)

        val (recovered, outNote) = JumpTrainer.applyOnlineUpdate(
            config = cfg,
            dNorm = 346f,
            holdMs = 491L,
            outcome = JumpOutcome.OUT_UNKNOWN,
            landingRatio = 0.95f,
            history = emptyList(),
            targetProfile = "MINI",
            targetColorGroup = "SLATE",
            pawnStance = "CENTER"
        )
        val nextMiniBias = JumpTrainer.computeContextAdjustmentMs(recovered, 346f, "MINI", "SLATE", "CENTER")
        assertTrue("OUT_UNKNOWN with pre-fall r=0.95 must recover upward ($outNote), got bias=$nextMiniBias", nextMiniBias >= 25L)
    }

    @Test
    fun `never stores BLK_MEDIUM bias and caps single OUT_FAR k drop`() {
        val cfg = BotConfig(
            kFactor = 1.326f,
            offsetMs = 46f,
            autoTrainEnabled = true,
            adaptiveBiasesMs = mapOf("BLK:MEDIUM" to -37f, "BLK:MINI" to 15f)
        )
        assertEquals(
            "BLK:MEDIUM must always be ignored as 0ms reference",
            0L,
            JumpTrainer.computeContextAdjustmentMs(cfg, 450f, "MEDIUM", "", "CENTER")
        )
        val (updated, note) = JumpTrainer.applyOnlineUpdate(
            config = cfg,
            dNorm = 732f,
            holdMs = 1036L,
            outcome = JumpOutcome.OUT_OVERSHOOT,
            landingRatio = 1.49f,
            history = emptyList(),
            targetProfile = "MEDIUM",
            targetColorGroup = "WHITE",
            pawnStance = "SIDE_BACK"
        )
        assertTrue("Legacy BLK:MEDIUM must be removed ($note)", !updated.adaptiveBiasesMs.containsKey("BLK:MEDIUM"))
        assertTrue(
            "Single extreme OUT_FAR must cap k step to <= 0.022 (1.326 -> ${updated.kFactor})",
            cfg.kFactor - updated.kFactor <= 0.0221f
        )
    }

    @Test
    fun `scoreDelta combo locks calibration and +1pt off-center warning actively trims toward bullseye`() {
        val cfg = BotConfig(kFactor = 1.365f, offsetMs = 49f, autoTrainEnabled = true)

        // +4pt combo: even if vision landingRatio has slight 0.95 noise, do not disturb calibration
        val (comboCfg, comboNote) = JumpTrainer.applyOnlineUpdate(
            config = cfg,
            dNorm = 480f,
            holdMs = 704L,
            outcome = JumpOutcome.CENTER,
            landingRatio = 0.95f,
            history = emptyList(),
            targetProfile = "MEDIUM",
            targetColorGroup = "WHITE",
            pawnStance = "CENTER",
            scoreDelta = 4
        )
        assertEquals("Combo +4 must keep kFactor untouched ($comboNote)", cfg.kFactor, comboCfg.kFactor, 0.0001f)
        assertEquals("Combo +4 must keep offsetMs untouched ($comboNote)", cfg.offsetMs, comboCfg.offsetMs, 0.01f)

        // +1pt single point: r=0.98 used to be ignored as Dead center; now actively trimmed toward 1.00
        val (edgeCfg, edgeNote) = JumpTrainer.applyOnlineUpdate(
            config = cfg,
            dNorm = 480f,
            holdMs = 704L,
            outcome = JumpOutcome.CENTER,
            landingRatio = 0.98f,
            history = emptyList(),
            targetProfile = "MEDIUM",
            targetColorGroup = "WHITE",
            pawnStance = "CENTER",
            scoreDelta = 1
        )
        assertTrue("+1pt warning at r=0.98 must trim kFactor upward ($edgeNote)", edgeCfg.kFactor > cfg.kFactor)
        assertTrue("+1pt note should flag Edge +1pt ($edgeNote)", edgeNote.contains("Edge +1pt"))

        // 0pt while still alive with pawnFound=true classifies as SHORT
        val pre = makeResult(px = 218f, py = 954f, bx = 548f, by = 755f, dRaw = 385f, dNorm = 577f)
        val postSame = makeResult(px = 225f, py = 950f, bx = 548f, by = 755f)
        assertEquals(
            "0 score delta while alive must classify as SHORT",
            JumpOutcome.SHORT,
            JumpTrainer.classifyOutcome(pre, landingRatio = 0.55f, postSettled = postSame, scoreDelta = 0)
        )
    }

    @Test
    fun `MINI block OUT_UNKNOWN near nominal k does not falsely fire -40ms out-over recovery`() {
        // Reproduces log #94: k=1.365 b=49 dNorm=446 hold=667 effK=1.495 on MINI:PURPLE with r=1.02
        val cfg = BotConfig(kFactor = 1.365f, offsetMs = 49f, autoTrainEnabled = true)
        val (updated, note) = JumpTrainer.applyOnlineUpdate(
            config = cfg,
            dNorm = 446f,
            holdMs = 667L,
            outcome = JumpOutcome.OUT_UNKNOWN,
            landingRatio = 1.02f,
            history = emptyList(),
            targetProfile = "MINI",
            targetColorGroup = "PURPLE",
            pawnStance = "SIDE_BACK"
        )
        assertTrue("MINI OUT_UNKNOWN at k=1.365 must not fire Out-over recovery ($note)", !note.contains("Out-over recovery"))
        val mistBias = updated.adaptiveBiasesMs["MIST:MINI:SIDE_BACK:d450"] ?: 0f
        assertTrue("Near-rim MINI guard should stay gentle (>= -25ms), got $mistBias ($note)", mistBias >= -25f)
    }

    @Test
    fun `plus-1pt edge trim on MINI block does not pull shared BLK_MINI negative`() {
        val cfg = BotConfig(
            kFactor = 1.38f,
            offsetMs = 41f,
            autoTrainEnabled = true,
            adaptiveBiasesMs = mapOf("BLK:MINI" to 4f)
        )
        val (updated, note) = JumpTrainer.applyOnlineUpdate(
            config = cfg,
            dNorm = 420f,
            holdMs = 620L,
            outcome = JumpOutcome.CENTER,
            landingRatio = 1.03f,
            history = emptyList(),
            targetProfile = "MINI",
            targetColorGroup = "PURPLE",
            pawnStance = "SIDE_BACK",
            scoreDelta = 1
        )
        val miniBiasAfter = updated.adaptiveBiasesMs["BLK:MINI"] ?: 0f
        assertTrue(
            "+1pt trim on one MINI block must never erode BLK:MINI negative (was 4f, got $miniBiasAfter, note=$note)",
            miniBiasAfter >= 4f
        )
    }

    @Test
    fun `plus-1pt edge trim heals opposing stale MIST bias`() {
        val cfg = BotConfig(
            kFactor = 1.392f,
            offsetMs = 43f,
            autoTrainEnabled = true,
            adaptiveBiasesMs = mapOf("MIST:NARROW:SIDE_EDGE:d300" to -15f)
        )
        val (updated, note) = JumpTrainer.applyOnlineUpdate(
            config = cfg,
            dNorm = 292f,
            holdMs = 424L,
            outcome = JumpOutcome.CENTER,
            landingRatio = 0.92f,
            history = emptyList(),
            targetProfile = "NARROW",
            targetColorGroup = "PINK",
            pawnStance = "SIDE_EDGE",
            scoreDelta = 1
        )
        val healedMist = updated.adaptiveBiasesMs["MIST:NARROW:SIDE_EDGE:d300"] ?: 0f
        assertTrue(
            "Short +1pt (r=0.92) should heal opposing -15ms MIST toward 0, got $healedMist ($note)",
            healedMist > -12f
        )
    }

    @Test
    fun `short distance NEAR200 overshoot learns negative DIST bias and bloated BLK_MINI trims down on +1pt`() {
        // 1. Short-distance overshoot (dNorm=185, r=1.28) learns negative DIST:NEAR200 without distorting mid/long jumps
        val cfg = BotConfig(kFactor = 1.376f, offsetMs = 38f, autoTrainEnabled = true)
        val (afterShortOver, shortNote) = JumpTrainer.applyOnlineUpdate(
            config = cfg,
            dNorm = 185f,
            holdMs = 313L,
            outcome = JumpOutcome.PAST_CENTER,
            landingRatio = 1.28f,
            history = emptyList(),
            targetProfile = "MEDIUM",
            targetColorGroup = "PINK",
            pawnStance = "SIDE_BACK"
        )
        val nearBias = JumpTrainer.computeContextAdjustmentMs(afterShortOver, 185f, "MEDIUM", "WHITE", "CENTER")
        val farBias = JumpTrainer.computeContextAdjustmentMs(afterShortOver, 550f, "MEDIUM", "WHITE", "CENTER")
        assertTrue("dNorm=185 PAST overshoot must learn negative DIST:NEAR200 bias ($shortNote), got $nearBias", nearBias <= -10L)
        assertEquals("Long jump (dNorm=550) must stay unaffected by DIST:NEAR200", 0L, farBias)

        // 2. Bloated BLK:MINI (+19ms) must actively trim downward when MINI keeps overshooting +1pt
        val bloatedMiniCfg = BotConfig(
            kFactor = 1.376f,
            offsetMs = 38f,
            autoTrainEnabled = true,
            adaptiveBiasesMs = mapOf(
                "BLK:MINI" to 19f,
                "MIST:NARROW:CENTER:d450" to 40f
            )
        )
        val (trimmedMini, miniNote) = JumpTrainer.applyOnlineUpdate(
            config = bloatedMiniCfg,
            dNorm = 440f,
            holdMs = 665L,
            outcome = JumpOutcome.CENTER,
            landingRatio = 1.03f,
            history = emptyList(),
            targetProfile = "MINI",
            targetColorGroup = "WHITE",
            pawnStance = "CENTER",
            scoreDelta = 1
        )
        val miniAfter = trimmedMini.adaptiveBiasesMs["BLK:MINI"] ?: 0f
        assertTrue("Bloated BLK:MINI (+19) should trim down on r=1.03 +1pt ($miniNote), got $miniAfter", miniAfter in 6f..17f)

        // 3. Stale opposing MIST (+40ms) heals rapidly on a +4 combo or opposing -1pt trim
        val (healedCombo, _) = JumpTrainer.applyOnlineUpdate(
            config = bloatedMiniCfg,
            dNorm = 450f,
            holdMs = 660L,
            outcome = JumpOutcome.CENTER,
            landingRatio = 1.00f,
            history = emptyList(),
            targetProfile = "NARROW",
            targetColorGroup = "WHITE",
            pawnStance = "CENTER",
            scoreDelta = 4
        )
        val mistAfterCombo = healedCombo.adaptiveBiasesMs["MIST:NARROW:CENTER:d450"] ?: 0f
        assertTrue("Combo +4 should decay MIST +40 down to <= 24ms, got $mistAfterCombo", mistAfterCombo <= 24f)
    }

    @Test
    fun `smooth NEAR200-270 curve prevents step cliff and OUT_FAR at n=217 updates DIST instead of crashing k`() {
        val cfg = BotConfig(
            kFactor = 1.376f,
            offsetMs = 31f,
            autoTrainEnabled = true,
            adaptiveBiasesMs = mapOf("DIST:NEAR200" to -12f, "DIST:NEAR270" to -14f)
        )
        val adj200 = JumpTrainer.computeContextAdjustmentMs(cfg, 200f, "MEDIUM", "WHITE", "CENTER")
        val adj235 = JumpTrainer.computeContextAdjustmentMs(cfg, 235f, "MEDIUM", "WHITE", "CENTER")
        val adj295 = JumpTrainer.computeContextAdjustmentMs(cfg, 295f, "MEDIUM", "WHITE", "CENTER")
        assertEquals(-12L, adj200)
        assertTrue("Interpolated 235px should be near -13ms, got $adj235", adj235 in -14L..-12L)
        assertTrue("295px should fade smoothly between -14ms and 0ms, got $adj295", adj295 in -10L..-3L)

        // Reproduces #260 OUT_FAR at n=217 r=1.24: must shift DIST:NEAR200 strongly negative while protecting global kFactor
        val (after260, note260) = JumpTrainer.applyOnlineUpdate(
            config = cfg,
            dNorm = 217f,
            holdMs = 317L,
            outcome = JumpOutcome.OUT_OVERSHOOT,
            landingRatio = 1.24f,
            history = emptyList(),
            targetProfile = "MINI",
            targetColorGroup = "PINK",
            pawnStance = "SIDE_FRONT"
        )
        val dist200After = after260.adaptiveBiasesMs["DIST:NEAR200"] ?: 0f
        assertTrue("OUT_OVERSHOOT at n=217 must deepen DIST:NEAR200 ($note260), got $dist200After", dist200After <= -24f)
        assertTrue(
            "Short MINI OUT_FAR should protect global kFactor from crashing (1.376 -> ${after260.kFactor})",
            cfg.kFactor - after260.kFactor <= 0.005f
        )

        // Reproduces #254 pan-lagged +1pt at n=561 r=0.93: must not spike k sharply
        val (after254, note254) = JumpTrainer.applyOnlineUpdate(
            config = BotConfig(kFactor = 1.370f, offsetMs = 31f, autoTrainEnabled = true),
            dNorm = 561f,
            holdMs = 807L,
            outcome = JumpOutcome.CENTER,
            landingRatio = 0.93f,
            history = emptyList(),
            targetProfile = "NARROW",
            targetColorGroup = "CYAN",
            pawnStance = "SIDE_BACK",
            scoreDelta = 1
        )
        assertTrue(
            "Pan-lagged +1pt at n=561 r=0.93 should keep k spike <= 0.003 ($note254 -> ${after254.kFactor})",
            after254.kFactor - 1.370f <= 0.0031f
        )
    }

    @Test
    fun `stagnant UNKNOWN frame skips Short fallback and 0pt r=0_99 never claims Dead center`() {
        val cfg = BotConfig(kFactor = 1.388f, offsetMs = 32.5f, autoTrainEnabled = true)
        val stagnantPre = makeResult(px = 459f, py = 1043f, bx = 302f, by = 913f, dRaw = 203f, dNorm = 305f)
            .copy(pawnPixelCount = 223, pawnStance = "UNKNOWN", targetBlockProfile = "MINI")
        val stagnantPost = stagnantPre.copy()

        val stagnantOutcome = JumpTrainer.classifyOutcome(
            pre = stagnantPre,
            landingRatio = null,
            postSettled = stagnantPost,
            scoreDelta = null
        )
        assertEquals("Unmoved UNKNOWN modal frame must classify as UNKNOWN, not SHORT", JumpOutcome.UNKNOWN, stagnantOutcome)

        val (afterUnknown, noteUnknown) = JumpTrainer.applyOnlineUpdate(
            config = cfg,
            dNorm = 305f,
            holdMs = 451L,
            outcome = stagnantOutcome,
            landingRatio = null,
            history = emptyList(),
            targetProfile = "MINI",
            targetColorGroup = "PINK",
            pawnStance = "UNKNOWN"
        )
        assertEquals("UNKNOWN frame must keep kFactor unchanged ($noteUnknown)", cfg.kFactor, afterUnknown.kFactor, 0.0001f)
        assertEquals("UNKNOWN frame must keep adaptiveBiases untouched", cfg.adaptiveBiasesMs, afterUnknown.adaptiveBiasesMs)

        // Reproduces #141: SHORT (scoreDelta=0) with r=0.99 must NOT label Dead center
        val (afterRimMiss, rimNote) = JumpTrainer.applyOnlineUpdate(
            config = cfg,
            dNorm = 553f,
            holdMs = 796L,
            outcome = JumpOutcome.SHORT,
            landingRatio = 0.99f,
            history = emptyList(),
            targetProfile = "NARROW",
            targetColorGroup = "PURPLE",
            pawnStance = "SIDE_BACK",
            scoreDelta = 0
        )
        assertTrue("0pt SHORT with r=0.99 must never claim Dead center ($rimNote)", !rimNote.contains("Dead center"))
        assertTrue("0pt SHORT with r=0.99 should nudge calibration gently ($rimNote)", afterRimMiss.kFactor >= cfg.kFactor)
    }
}
