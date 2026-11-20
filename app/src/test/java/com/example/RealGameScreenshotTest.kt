package com.example

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Rect
import com.example.model.BotConfig
import com.example.vision.BlockVisionAnalyzer
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36])
class RealGameScreenshotTest {

    private fun loadWorkspaceImageOrSkip(relativePath: String): Bitmap {
        val candidates = listOf(
            File(relativePath),
            File("..", relativePath)
        )
        val file = candidates.firstOrNull { it.exists() }
        assumeTrue("Optional screenshot fixture not present: $relativePath", file != null)
        val opts = BitmapFactory.Options().apply {
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        return BitmapFactory.decodeFile(file!!.absolutePath, opts)
            ?: error("Failed to decode ${file.absolutePath}")
    }

    @Test
    fun `analyzes user game screenshot 1 (right jump)`() {
        val bmp = loadWorkspaceImageOrSkip("public/assets/aistudio/Screenshot_20261116_073732_4fd83fcee830ed007aacc9cec66270f7_30a41bc7.jpg")
        val result = BlockVisionAnalyzer.analyze(bmp, BotConfig())
        println("SCREENSHOT 1 => $result")
        assertTrue("Pawn should be detected on screenshot 1: ${result.statusMessage}", result.pawnFound)
        assertTrue("Block should be detected on screenshot 1: ${result.statusMessage}", result.blockFound)
        assertTrue("S1 block width should be > 200px, got ${result.blockBounds?.widthPx}", (result.blockBounds?.widthPx ?: 0f) > 200f)
        assertTrue("S1 pawn X should be on left side (< 300), got ${result.pawnCenterX}", result.pawnCenterX < 300f)
        assertTrue("S1 block X should be on right side (> 450), got ${result.blockBounds?.center?.x}", (result.blockBounds?.center?.x ?: 0f) > 450f)
    }

    @Test
    fun `analyzes user game screenshot 2 (left chest jump without platform rim drift)`() {
        val bmp = loadWorkspaceImageOrSkip("public/assets/aistudio/Screenshot_20261116_073749_4fd83fcee830ed007aacc9cec66270f7_227dd93d.jpg")
        val result = BlockVisionAnalyzer.analyze(bmp, BotConfig())
        println("SCREENSHOT 2 => $result")
        assertTrue("Pawn should be detected on screenshot 2: ${result.statusMessage}", result.pawnFound)
        assertTrue("S2 pawn base Y should not drift onto blue rim (< 1005), got ${result.pawnBaseY}", result.pawnBaseY < 1005f)
        assertTrue("Block should be detected on screenshot 2: ${result.statusMessage}", result.blockFound)
        assertTrue("S2 chest width should be > 200px, got ${result.blockBounds?.widthPx}", (result.blockBounds?.widthPx ?: 0f) > 200f)
        assertTrue("S2 pawn X should be on right side (> 420), got ${result.pawnCenterX}", result.pawnCenterX > 420f)
        assertTrue("S2 chest X should be on left side (< 260), got ${result.blockBounds?.center?.x}", (result.blockBounds?.center?.x ?: 999f) < 260f)
    }

    @Test
    fun `analyzes user game screenshot 3 (score 47 occluded pawn, ninja block, HUD excluded)`() {
        val bmp = loadWorkspaceImageOrSkip("public/assets/aistudio/Screenshot_20261116_084330_4fd83fcee830ed007aacc9cec66270f7_41ad5824.jpg")
        // Exclude the floating HUD rectangle shown in screenshot 3
        val hudRect = Rect(30, 280, 490, 475)
        val result = BlockVisionAnalyzer.analyze(bmp, BotConfig(), excludedRect = hudRect)
        println("SCREENSHOT 3 => $result")
        assertTrue("Pawn should be detected on screenshot 3: ${result.statusMessage}", result.pawnFound)
        assertTrue("S3 pawn X should lock on real pawn (~326), not ninja block (~520), got ${result.pawnCenterX}", result.pawnCenterX in 295f..360f)
        assertTrue("Block should be detected on screenshot 3: ${result.statusMessage}", result.blockFound)
        assertTrue("S3 target block X should lock on ninja block (~505), got ${result.blockBounds?.center?.x}", (result.blockBounds?.center?.x ?: 0f) in 460f..560f)
        assertTrue("S3 ninja block width should be > 200px, got ${result.blockBounds?.widthPx}", (result.blockBounds?.widthPx ?: 0f) > 200f)
    }

    @Test
    fun `analyzes user small coffee cup screenshots without stalling`() {
        val cases = listOf(
            // (filename, expectedPawnRightOfCup, hudRect)
            Triple("public/assets/aistudio/Screenshot_20261116_211831_4fd83fcee830ed007aacc9cec66270f7_46c588a8.jpg", true, Rect(30, 110, 420, 405)),
            Triple("public/assets/aistudio/Screenshot_20261116_215020_4fd83fcee830ed007aacc9cec66270f7_3eefc20f.jpg", true, null),
            Triple("public/assets/aistudio/Screenshot_20261116_223218_4fd83fcee830ed007aacc9cec66270f7_2fb0f917.jpg", false, Rect(30, 145, 455, 430)),
            Triple("public/assets/aistudio/Screenshot_20261117_060923_4fd83fcee830ed007aacc9cec66270f7_32305e80.jpg", false, null)
        )
        for ((idx, spec) in cases.withIndex()) {
            val (path, cupIsLeft, hudRect) = spec
            val bmp = loadWorkspaceImageOrSkip(path)
            val res = BlockVisionAnalyzer.analyze(bmp, BotConfig(), excludedRect = hudRect)
            println("CUP SHOT ${idx + 4} => $res")
            assertTrue("Cup shot ${idx + 4}: Pawn should be detected (${res.statusMessage})", res.pawnFound)
            assertTrue("Cup shot ${idx + 4}: Small coffee cup block should be detected (${res.statusMessage})", res.blockFound)
            val blk = res.blockBounds!!
            assertTrue(
                "Cup shot ${idx + 4}: width should be in MINI range (68..136px), got ${blk.widthPx}",
                blk.widthPx in 68f..138f
            )
            assertTrue(
                "Cup shot ${idx + 4}: targetBlockProfile should be MINI, got ${res.targetBlockProfile}",
                res.targetBlockProfile == "MINI"
            )
            if (cupIsLeft) {
                assertTrue("Cup shot ${idx + 4}: cup (${blk.center.x}) must be left of pawn (${res.pawnCenterX})", blk.center.x < res.pawnCenterX - 100f)
            } else {
                assertTrue("Cup shot ${idx + 4}: cup (${blk.center.x}) must be right of pawn (${res.pawnCenterX})", blk.center.x > res.pawnCenterX + 100f)
            }
        }
    }

    @Test
    fun `analyzes waterfall close-block screenshots score 91 and score 32`() {
        val shots = listOf(
            // (path, expectedPawnXRange, expectedBlockXRange, hudRect)
            listOf(
                "public/assets/aistudio/Screenshot_20261117_193305_4fd83fcee830ed007aacc9cec66270f7_aca36631.jpg",
                280f..355f,
                390f..485f,
                Rect(30, 145, 455, 430)
            ),
            listOf(
                "public/assets/aistudio/Screenshot_20261117_192934_4fd83fcee830ed007aacc9cec66270f7_5a4db4ae.jpg",
                455f..535f,
                205f..290f,
                Rect(30, 145, 455, 430)
            )
        )
        for ((idx, item) in shots.withIndex()) {
            val path = item[0] as String
            @Suppress("UNCHECKED_CAST")
            val expectedPawnRange = item[1] as ClosedFloatingPointRange<Float>
            @Suppress("UNCHECKED_CAST")
            val expectedBlockRange = item[2] as ClosedFloatingPointRange<Float>
            val hudRect = item[3] as Rect
            val bmp = loadWorkspaceImageOrSkip(path)
            val res = BlockVisionAnalyzer.analyze(bmp, BotConfig(), excludedRect = hudRect)
            println("WATERFALL SHOT ${idx + 8} => $res")
            assertTrue("Waterfall shot ${idx + 8}: Pawn should be detected (${res.statusMessage})", res.pawnFound)
            assertTrue("Waterfall shot ${idx + 8}: Pawn X (${res.pawnCenterX}) should be in $expectedPawnRange", res.pawnCenterX in expectedPawnRange)
            assertTrue("Waterfall shot ${idx + 8}: Next block should be detected (${res.statusMessage})", res.blockFound)
            val bx = res.blockBounds?.center?.x ?: 0f
            val bw = res.blockBounds?.widthPx ?: 0f
            assertTrue("Waterfall shot ${idx + 8}: Target block center X ($bx) should be in $expectedBlockRange", bx in expectedBlockRange)
            assertTrue("Waterfall shot ${idx + 8}: Target block width ($bw) should be > 200px", bw > 200f)
        }
    }

    @Test
    fun `human pre-jump pacing produces fast medium and thoughtful tiers`() {
        val fast = com.example.service.ScreenCaptureService.computeHumanPreJumpPauseMs(0.10f, 0.50f)
        val medium = com.example.service.ScreenCaptureService.computeHumanPreJumpPauseMs(0.70f, 0.50f)
        val slow = com.example.service.ScreenCaptureService.computeHumanPreJumpPauseMs(0.95f, 0.50f)
        assertTrue("Fast tier should be in 30..105ms, got $fast", fast in 30L..105L)
        assertTrue("Medium tier should be in 115..240ms, got $medium", medium in 115L..240L)
        assertTrue("Thoughtful pause tier should be in 260..420ms, got $slow", slow in 260L..420L)
    }

    @Test
    fun `reads top-right golden score digits accurately on real screenshots`() {
        data class ScoreCase(val path: String, val expected: Int, val hud: Rect?)
        val cases = listOf(
            ScoreCase("public/assets/aistudio/Screenshot_20261116_084330_4fd83fcee830ed007aacc9cec66270f7_41ad5824.jpg", 47, Rect(30, 280, 490, 475)),
            ScoreCase("public/assets/aistudio/Screenshot_20261116_211831_4fd83fcee830ed007aacc9cec66270f7_46c588a8.jpg", 153, Rect(30, 110, 420, 405)),
            ScoreCase("public/assets/aistudio/Screenshot_20261116_215020_4fd83fcee830ed007aacc9cec66270f7_3eefc20f.jpg", 114, null),
            ScoreCase("public/assets/aistudio/Screenshot_20261116_223218_4fd83fcee830ed007aacc9cec66270f7_2fb0f917.jpg", 107, Rect(30, 145, 455, 430)),
            ScoreCase("public/assets/aistudio/Screenshot_20261117_060923_4fd83fcee830ed007aacc9cec66270f7_32305e80.jpg", 75, null),
            ScoreCase("public/assets/aistudio/Screenshot_20261117_193305_4fd83fcee830ed007aacc9cec66270f7_aca36631.jpg", 91, Rect(30, 145, 455, 430)),
            ScoreCase("public/assets/aistudio/Screenshot_20261117_192934_4fd83fcee830ed007aacc9cec66270f7_5a4db4ae.jpg", 32, Rect(30, 145, 455, 430))
        )
        val failures = ArrayList<String>()
        for (c in cases) {
            val bmp = loadWorkspaceImageOrSkip(c.path)
            val actual = BlockVisionAnalyzer.analyze(bmp, BotConfig(), excludedRect = c.hud).scoreReading
            println("SCORE OCR [${c.expected}] => $actual")
            if (c.expected != actual) {
                failures.add("${c.expected} != $actual")
            }
        }
        assertTrue("Score mismatches: $failures", failures.isEmpty())
    }

    @Test
    fun `analyzes user stall screenshot score 573 (teal-white cylinder over green floor)`() {
        val bmp = loadWorkspaceImageOrSkip("public/assets/aistudio/Screenshot_20261117_235827_4fd83fcee830ed007aacc9cec66270f7_aeb20dca.jpg")
        val hudRect = Rect(30, 145, 455, 430)
        val res = BlockVisionAnalyzer.analyze(bmp, BotConfig(), excludedRect = hudRect)
        println("STALL SHOT 573 => $res | score=${res.scoreReading}")
        assertTrue("Pawn should be detected on 573 shot: ${res.statusMessage}", res.pawnFound)
        assertTrue("Teal-white cylinder should be detected on 573 shot: ${res.statusMessage}", res.blockFound)
        val bx = res.blockBounds?.center?.x ?: 0f
        assertTrue("Cylinder center X should be around 490, got $bx", bx in 445f..540f)
    }

    @Test
    fun `analyzes user stall screenshot score 68 (vinyl turntable + purple crown block)`() {
        val bmp = loadWorkspaceImageOrSkip("public/assets/aistudio/Screenshot_20261117_235025_4fd83fcee830ed007aacc9cec66270f7_e25ad306.jpg")
        val res = BlockVisionAnalyzer.analyze(bmp, BotConfig())
        assertTrue("Pawn on vinyl turntable should be detected on 68 shot: ${res.statusMessage}", res.pawnFound)
        assertTrue("Purple crown block should be detected on 68 shot: ${res.statusMessage}", res.blockFound)
        val bx = res.blockBounds?.center?.x ?: 0f
        assertTrue("Purple crown block center X should be around 215, got $bx", bx in 175f..265f)
    }

    @Test
    fun `analyzes user stall screenshots scores 570, 412, 411, and 498`() {
        data class Case(val path: String, val score: Int, val hud: Rect?, val pawnX: ClosedFloatingPointRange<Float>, val blockX: ClosedFloatingPointRange<Float>, val probeX: Int)
        val cases = listOf(
            Case("public/assets/aistudio/Screenshot_20261118_020752_4fd83fcee830ed007aacc9cec66270f7_ba509ecf.jpg", 570, null, 250f..315f, 385f..495f, 285),
            Case("public/assets/aistudio/Screenshot_20261118_020357_4fd83fcee830ed007aacc9cec66270f7_90e9c2ad.jpg", 412, null, 210f..280f, 430f..520f, 245),
            Case("public/assets/aistudio/Screenshot_20261118_020325_4fd83fcee830ed007aacc9cec66270f7_7b2a7373.jpg", 411, null, 215f..285f, 420f..515f, 250),
            Case("public/assets/aistudio/Screenshot_20261118_015227_4fd83fcee830ed007aacc9cec66270f7_bffc0ddc.jpg", 498, Rect(15, 55, 430, 350), 180f..250f, 460f..550f, 214)
        )
        val failures = ArrayList<String>()
        for (c in cases) {
            val bmp = loadWorkspaceImageOrSkip(c.path)
            val sb = StringBuilder("DIAG ${c.score} x=${c.probeX}: ")
            for (y in 840..980 step 14) {
                sb.append("$y=${String.format("#%06X", bmp.getPixel(c.probeX, y) and 0xFFFFFF)} ")
            }
            if (c.score == 570) {
                sb.append("| x=375: ")
                for (y in 850..960 step 14) sb.append("$y=${String.format("#%06X", bmp.getPixel(375, y) and 0xFFFFFF)} ")
            }
            if (c.score == 411) {
                sb.append("| x=432: ")
                for (y in 810..930 step 14) sb.append("$y=${String.format("#%06X", bmp.getPixel(432, y) and 0xFFFFFF)} ")
            }
            println(sb.toString())
            val res = BlockVisionAnalyzer.analyze(bmp, BotConfig(), excludedRect = c.hud)
            println("STALL ${c.score} => $res | score=${res.scoreReading}")
            if (!res.pawnFound) failures.add("${c.score}: pawn not found (${res.statusMessage})")
            else if (res.pawnCenterX !in c.pawnX) failures.add("${c.score}: pawnCenterX=${res.pawnCenterX} not in ${c.pawnX}")
            if (!res.blockFound) failures.add("${c.score}: block not found (${res.statusMessage})")
            else if ((res.blockBounds?.center?.x ?: 0f) !in c.blockX) failures.add("${c.score}: blockX=${res.blockBounds?.center?.x} not in ${c.blockX}")
            if (c.score == 570 && (res.blockBounds?.widthPx ?: 0f) > 278f) {
                failures.add("570: target width bled into source (${res.blockBounds?.widthPx})")
            }
        }
        assertTrue("Failures: $failures", failures.isEmpty())
    }
}


