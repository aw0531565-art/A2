package com.example.training

import android.os.Build
import com.example.model.BotConfig
import com.example.model.BotLogEntry
import com.example.model.FormulaMode
import com.example.model.JumpOutcome
import com.example.model.JumpTelemetryRecord
import com.example.model.VisionAnalysisResult
import java.util.Locale
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.roundToInt

object JumpTrainer {

    private const val MIN_K = 0.85f
    private const val MAX_K = 2.15f
    private const val MIN_B = -15f
    private const val MAX_B = 75f
    private const val ANCHOR_K = 1.388f
    private const val MAX_NON_FATAL_K_STEP = 0.014f
    private const val MAX_FATAL_K_STEP = 0.016f
    private const val MAX_CONTEXT_BIAS_MS = 105f
    private const val MAX_MISTAKE_GUARD_MS = 38f
    private const val MAX_STORED_BIASES = 48

    fun distBucket(dNorm: Float): Int = ((dNorm / 50f).roundToInt() * 50).coerceIn(100, 1200)

    fun distBandKey(dNorm: Float): String = when {
        dNorm < 235f -> "DIST:NEAR200"
        dNorm < 308f -> "DIST:NEAR270"
        else -> ""
    }

    private fun computeDistBandAdjustmentMs(biases: Map<String, Float>, dNorm: Float): Float {
        if (dNorm >= 310f) return 0f
        val d200 = biases["DIST:NEAR200"] ?: 0f
        val d270 = biases["DIST:NEAR270"] ?: 0f
        return when {
            dNorm <= 200f -> d200
            dNorm < 265f -> {
                val t = (dNorm - 200f) / 65f
                d200 * (1f - t) + d270 * t
            }
            else -> {
                val t = (dNorm - 265f) / 45f
                d270 * (1f - t)
            }
        }
    }

    fun profileKey(targetProfile: String): String = "BLK:${targetProfile.ifBlank { "MEDIUM" }}"

    fun signatureKey(targetProfile: String, colorGroup: String): String =
        if (colorGroup.isNotBlank()) "SIG:${targetProfile.ifBlank { "MEDIUM" }}_$colorGroup" else ""

    fun stanceKey(pawnStance: String): String =
        if (pawnStance.isNotBlank() && pawnStance != "UNKNOWN") "STANCE:$pawnStance" else ""

    fun mistakeKey(targetProfile: String, pawnStance: String, dNorm: Float): String =
        "MIST:${targetProfile.ifBlank { "MEDIUM" }}:${pawnStance.ifBlank { "CENTER" }}:d${distBucket(dNorm)}"

    /**
     * Computes learned millisecond adjustment for a specific target block profile/color,
     * pawn source stance (CENTER vs SIDE_FRONT/SIDE_BACK/SIDE_EDGE), smooth short-distance spring curve,
     * and exact spot mistake guard.
     */
    fun computeContextAdjustmentMs(
        config: BotConfig,
        normalizedDistancePx: Float,
        targetBlockProfile: String,
        targetColorGroup: String,
        pawnStance: String
    ): Long {
        val biases = config.adaptiveBiasesMs
        if (biases.isEmpty()) return 0L

        val blkBias = if (targetBlockProfile == "MEDIUM" || targetBlockProfile.isBlank()) {
            0f
        } else {
            biases[profileKey(targetBlockProfile)] ?: 0f
        }
        val sigKey = signatureKey(targetBlockProfile, targetColorGroup)
        val sigBias = if (sigKey.isNotEmpty()) (biases[sigKey] ?: 0f) * 0.80f else 0f
        val stKey = stanceKey(pawnStance)
        val stanceBias = if (stKey.isNotEmpty()) (biases[stKey] ?: 0f) else 0f
        val distBias = computeDistBandAdjustmentMs(biases, normalizedDistancePx)
        val mistBias = biases[mistakeKey(targetBlockProfile, pawnStance, normalizedDistancePx)] ?: 0f

        return (blkBias + sigBias + stanceBias + distBias + mistBias)
            .roundToInt()
            .toLong()
            .coerceIn(-MAX_CONTEXT_BIAS_MS.toLong(), MAX_CONTEXT_BIAS_MS.toLong())
    }

    /**
     * Measures how far the pawn landed along the exact jump vector from pre-jump pawn to
     * pre-jump target block center. 1.0 = dead center, < 1.0 = short, > 1.0 = past center / overshoot.
     */
    fun computeLandingRatio(
        pre: VisionAnalysisResult,
        landing: VisionAnalysisResult?
    ): Float? {
        if (landing == null || !pre.pawnFound || !pre.blockFound || !landing.pawnFound) return null
        val block = pre.blockBounds ?: return null
        val vx = block.center.x - pre.pawnCenterX
        val vy = block.center.y - pre.pawnBaseY
        val distSq = vx * vx + vy * vy
        val dist = pre.rawDistancePx
        if (dist < 40f || distSq < 1600f) return null

        val ux = landing.pawnCenterX - pre.pawnCenterX
        val uy = landing.pawnBaseY - pre.pawnBaseY
        val ratio = (ux * vx + uy * vy) / distSq

        // Reject completely unrelated jumps (e.g. pawn reset / UI overlap perpendicular to jump axis)
        val cross = abs(ux * vy - uy * vx) / dist
        val perpFraction = cross / dist
        if (ratio !in 0.25f..1.85f || perpFraction > 0.65f) return null
        return ratio
    }

    fun classifyOutcome(
        pre: VisionAnalysisResult,
        landingRatio: Float?,
        postSettled: VisionAnalysisResult?,
        scoreDelta: Int? = null
    ): JumpOutcome {
        if (postSettled != null && !postSettled.pawnFound) {
            return when {
                landingRatio != null && landingRatio < 0.94f -> JumpOutcome.OUT_SHORT
                landingRatio != null && landingRatio > 1.06f -> JumpOutcome.OUT_OVERSHOOT
                else -> JumpOutcome.OUT_UNKNOWN
            }
        }

        // Exact score confirmation from top-right counter:
        // 0 pts while still alive means pawn never reached the next block (stayed on source).
        if (scoreDelta == 0 && postSettled?.pawnFound == true && (landingRatio == null || landingRatio < 1.06f)) {
            return JumpOutcome.SHORT
        }
        // >=2 pts means dead-center bullseye combo (+2, +4, +6, +8...) was awarded.
        if (scoreDelta != null && scoreDelta >= 2 && postSettled?.pawnFound == true) {
            return JumpOutcome.CENTER
        }

        if (landingRatio != null) {
            // A pawn that survived on the block cannot physically land at r < 0.72;
            // values below 0.72 after surviving mean camera pan already slid the block back.
            if (landingRatio < 0.72f && postSettled?.pawnFound == true) {
                return JumpOutcome.CENTER
            }
            return when {
                landingRatio < 0.89f -> JumpOutcome.SHORT
                landingRatio <= 1.11f -> JumpOutcome.CENTER
                else -> JumpOutcome.PAST_CENTER
            }
        }

        // Fallback if post-settled stayed right on the pre-jump platform (very short press).
        // Ignore stagnant UNKNOWN/phantom frames where neither scoreDelta nor landingRatio moved.
        val preBlk = pre.blockBounds
        val postBlk = postSettled?.blockBounds
        if (postSettled != null && postSettled.pawnFound && preBlk != null && postBlk != null) {
            val samePawn = hypot(postSettled.pawnCenterX - pre.pawnCenterX, postSettled.pawnBaseY - pre.pawnBaseY) < 28f
            val sameBlock = hypot(postBlk.center.x - preBlk.center.x, postBlk.center.y - preBlk.center.y) < 28f
            if (samePawn && sameBlock) {
                val frozenModalScreen = landingRatio == null && scoreDelta == null &&
                    (pre.pawnStance == "UNKNOWN" || pre.pawnPixelCount < 350)
                return if (!frozenModalScreen && (scoreDelta == 0 || landingRatio != null)) {
                    JumpOutcome.SHORT
                } else {
                    JumpOutcome.UNKNOWN
                }
            }
        }

        return if (postSettled?.pawnFound == true) JumpOutcome.CENTER else JumpOutcome.UNKNOWN
    }

    /**
     * Context-aware online self-training:
     * - Standard CENTER + MEDIUM jumps refine global (kFactor, offsetMs) + clean OLS blend.
     * - Off-center stances (SIDE_FRONT/BACK/EDGE) and special target block shapes (NARROW/WIDE/color)
     *   learn dedicated millisecond biases so specialty power differences don't corrupt base calibration.
     * - Fatal misses record explicit MIST guards so the same failed duration is never blindly repeated.
     */
    fun applyOnlineUpdate(
        config: BotConfig,
        dNorm: Float,
        holdMs: Long,
        outcome: JumpOutcome,
        landingRatio: Float?,
        history: List<JumpTelemetryRecord>,
        targetProfile: String = "MEDIUM",
        targetColorGroup: String = "",
        pawnStance: String = "CENTER",
        scoreDelta: Int? = null
    ): Pair<BotConfig, String> {
        if (!config.autoTrainEnabled || dNorm < 80f || outcome == JumpOutcome.UNKNOWN) {
            return config to if (outcome == JumpOutcome.UNKNOWN) "Unconfirmed frame (skipped)" else "Self-train skipped"
        }

        var newK = config.kFactor
        var newB = config.offsetMs
        val newBiases = LinkedHashMap(config.adaptiveBiasesMs)
        val isFatalOut = outcome == JumpOutcome.OUT_SHORT ||
            outcome == JumpOutcome.OUT_OVERSHOOT ||
            outcome == JumpOutcome.OUT_UNKNOWN
        val isMiniBlock = targetProfile == "MINI"
        val isSpecialtyContext = targetProfile != "MEDIUM" ||
            (pawnStance != "CENTER" && pawnStance != "UNKNOWN")
        val singlePointOffCenter = !isFatalOut && scoreDelta == 1
        val globalShare = when {
            isMiniBlock && singlePointOffCenter -> 0.12f
            isMiniBlock -> 0.18f
            isSpecialtyContext && singlePointOffCenter -> 0.22f
            isSpecialtyContext -> 0.35f
            else -> 1.0f
        }
        var allowLargeRecovery = false
        var note = "Keep"
        var learnedErrorMs = 0f

        val pKey = profileKey(targetProfile)
        val sKey = signatureKey(targetProfile, targetColorGroup)
        val stKey = stanceKey(pawnStance)
        val dKey = distBandKey(dNorm)
        val mKey = mistakeKey(targetProfile, pawnStance, dNorm)

        val comboConfirmed = !isFatalOut && scoreDelta != null && scoreDelta >= 2

        when {
            comboConfirmed -> {
                // +2/+4/+6/+8 combo proves dead-center bullseye: lock calibration & decay stale MIST guards.
                val ratioTag = landingRatio?.let { String.format(Locale.US, " (r=%.2f)", it) } ?: ""
                note = String.format(Locale.US, "Combo +%d center%s", scoreDelta, ratioTag)
                val wonMist = newBiases[mKey]
                if (wonMist != null && abs(wonMist) >= 4f) {
                    if (targetProfile != "MEDIUM") {
                        newBiases[pKey] = ((newBiases[pKey] ?: 0f) + wonMist * 0.15f).coerceIn(-80f, 80f)
                    }
                    newBiases[mKey] = (wonMist * 0.55f)
                }
            }

            outcome == JumpOutcome.OUT_SHORT -> {
                val shortDistDamp = if (dNorm < 260f) 0.25f else 1.0f
                if (landingRatio != null && landingRatio in 0.45f..0.95f) {
                    val safeRatio = landingRatio.coerceIn(0.72f, 0.92f)
                    val idealHoldMs = (holdMs.toFloat() / safeRatio)
                        .coerceIn(config.minPressMs.toFloat(), config.maxPressMs.toFloat())
                    val deltaMs = ((idealHoldMs - holdMs.toFloat()) * 0.45f).coerceIn(18f, 40f)
                    learnedErrorMs = deltaMs
                    newK += ((deltaMs * globalShare * shortDistDamp) * 0.80f) / dNorm.coerceAtLeast(180f)
                    newB += (deltaMs * globalShare * shortDistDamp) * 0.20f
                    note = String.format(Locale.US, "Out-short LMS r=%.2f +%.0fms", landingRatio, deltaMs)
                } else {
                    learnedErrorMs = 32f
                    newK += 0.022f * globalShare * shortDistDamp
                    newB += 3f * globalShare * shortDistDamp
                    note = "Out-short guard (+k,+b)"
                }
            }

            outcome == JumpOutcome.OUT_OVERSHOOT -> {
                allowLargeRecovery = true
                val shortDistDamp = if (dNorm < 260f) 0.25f else 1.0f
                if (landingRatio != null && landingRatio in 1.06f..1.65f) {
                    val safeRatio = landingRatio.coerceIn(1.07f, 1.32f)
                    val idealHoldMs = (holdMs.toFloat() / safeRatio)
                        .coerceIn(config.minPressMs.toFloat(), config.maxPressMs.toFloat())
                    val deltaMs = ((idealHoldMs - holdMs.toFloat()) * 0.42f).coerceIn(-40f, -20f)
                    learnedErrorMs = deltaMs
                    newK += ((deltaMs * globalShare * shortDistDamp) * 0.80f) / dNorm.coerceAtLeast(180f)
                    newB += (deltaMs * globalShare * shortDistDamp) * 0.20f
                    note = String.format(Locale.US, "Out-far LMS r=%.2f %.0fms", landingRatio, deltaMs)
                } else {
                    learnedErrorMs = -34f
                    newK -= 0.022f * globalShare * shortDistDamp
                    newB -= 3f * globalShare * shortDistDamp
                    note = "Out-far guard (-k,-b)"
                }
            }

            outcome == JumpOutcome.OUT_UNKNOWN -> {
                allowLargeRecovery = true
                val effK = holdMs.toFloat() / dNorm.coerceAtLeast(150f)
                val prevSameSpot = history.firstOrNull {
                    (it.outcome == JumpOutcome.OUT_UNKNOWN || it.outcome == JumpOutcome.OUT_OVERSHOOT || it.outcome == JumpOutcome.OUT_SHORT) &&
                        it.targetProfile == targetProfile &&
                        abs(it.normalizedDistancePx - dNorm) <= 45f
                }
                val existingMist = newBiases[mKey] ?: 0f
                val isRunawayHighK = config.kFactor > ANCHOR_K + 0.08f ||
                    (!isMiniBlock && dNorm >= 260f && effK > ANCHOR_K + 0.12f) ||
                    (isMiniBlock && effK > ANCHOR_K + 0.28f)
                val isRunawayLowK = config.kFactor < ANCHOR_K - 0.12f ||
                    (!isMiniBlock && dNorm >= 260f && effK < ANCHOR_K - 0.14f)

                when {
                    isRunawayHighK -> {
                        val pull = ((config.kFactor - ANCHOR_K) * 0.45f).coerceIn(0.055f, 0.14f)
                        newK -= pull * globalShare
                        newB -= if (prevSameSpot != null) 8f * globalShare else 4f * globalShare
                        learnedErrorMs = -40f
                        note = String.format(Locale.US, "Out-over recovery (-%.3f k)", pull)
                    }
                    isRunawayLowK -> {
                        val pull = ((ANCHOR_K - config.kFactor) * 0.45f).coerceIn(0.055f, 0.14f)
                        newK += pull * globalShare
                        newB += if (prevSameSpot != null) 8f * globalShare else 4f * globalShare
                        learnedErrorMs = 40f
                        note = String.format(Locale.US, "Out-short recovery (+%.3f k)", pull)
                    }
                    else -> {
                        val nearRim = landingRatio != null && landingRatio in 0.96f..1.04f
                        val rimStep = if (isMiniBlock) 14f else 18f
                        // Never repeat the exact same failed hold: prefer pre-fall ratio direction, then stance / history
                        val guardDelta = when {
                            existingMist <= -42f -> 30f // previous downward guards still missed, flip to avoid trap
                            existingMist >= 42f -> -30f
                            nearRim -> if ((landingRatio ?: 1f) < 1.0f) rimStep else -rimStep
                            landingRatio != null && landingRatio < 0.97f -> 26f
                            landingRatio != null && landingRatio > 1.03f -> -26f
                            pawnStance == "SIDE_BACK" -> 24f
                            pawnStance == "SIDE_FRONT" -> -24f
                            prevSameSpot != null && prevSameSpot.holdMs >= holdMs -> -24f
                            else -> -24f
                        }
                        learnedErrorMs = guardDelta
                        newK += (guardDelta * 0.25f * globalShare) / dNorm.coerceAtLeast(150f)
                        note = String.format(Locale.US, "Mistake guard (%+.0fms on %s/%s)", guardDelta, targetProfile, pawnStance)
                    }
                }
            }

            landingRatio != null && landingRatio < 0.72f -> {
                // Survived with r < 0.72 => camera already panned back before burst captured pawn
                note = if (singlePointOffCenter) {
                    String.format(Locale.US, "Edge +1pt (pan r=%.2f skipped)", landingRatio)
                } else {
                    String.format(Locale.US, "Pan-drifted (r=%.2f ignored)", landingRatio)
                }
            }

            landingRatio != null && landingRatio in 0.72f..1.65f -> {
                val errorRatio = 1.0f - landingRatio
                val deadCenterTol = if (singlePointOffCenter) 0.012f else 0.025f
                if (outcome == JumpOutcome.CENTER && abs(errorRatio) <= deadCenterTol) {
                    if (singlePointOffCenter) {
                        note = String.format(Locale.US, "Edge +1pt (r=%.2f)", landingRatio)
                    } else {
                        note = String.format(Locale.US, "Dead center (r=%.2f)", landingRatio)
                        val wonMist = newBiases[mKey]
                        if (wonMist != null && abs(wonMist) >= 4f) {
                            if (targetProfile != "MEDIUM") {
                                newBiases[pKey] = ((newBiases[pKey] ?: 0f) + wonMist * 0.15f).coerceIn(-80f, 80f)
                            }
                            newBiases[mKey] = (wonMist * 0.60f)
                        }
                    }
                } else if (outcome == JumpOutcome.SHORT && abs(errorRatio) <= 0.045f) {
                    // 0pt failure even when touchdown read r≈0.99: nudge gently upward without pretending Dead center
                    learnedErrorMs = 14f
                    newK += (10f * globalShare) / dNorm.coerceAtLeast(180f)
                    newB += 2f * globalShare
                    note = String.format(Locale.US, "0pt rim miss (r=%.2f) +trim", landingRatio)
                } else {
                    val safeRatio = landingRatio.coerceIn(0.76f, 1.36f)
                    val idealHoldMs = (holdMs.toFloat() / safeRatio)
                        .coerceIn(config.minPressMs.toFloat(), config.maxPressMs.toFloat())
                    val gain = when {
                        singlePointOffCenter -> if (outcome == JumpOutcome.SHORT || outcome == JumpOutcome.PAST_CENTER) 0.34f else 0.30f
                        abs(errorRatio) <= 0.060f -> 0.18f
                        outcome == JumpOutcome.SHORT || outcome == JumpOutcome.PAST_CENTER -> 0.34f
                        else -> 0.26f
                    }
                    // Prevent a single noisy jump (or long-range camera pan lag) from slashing k/STANCE
                    var deltaMs = ((idealHoldMs - holdMs.toFloat()) * gain).coerceIn(-28f, 28f)
                    val panLagZone = singlePointOffCenter && dNorm >= 500f && landingRatio in 0.92f..0.97f
                    if (panLagZone) {
                        deltaMs *= 0.42f
                    } else if (singlePointOffCenter && abs(deltaMs) < 6f) {
                        deltaMs = if (errorRatio > 0f) 6f else -6f
                    }
                    learnedErrorMs = deltaMs
                    val shortDistDamp = if (dNorm < 280f) 0.45f else 1.0f

                    newK += ((deltaMs * globalShare * shortDistDamp) * 0.80f) / dNorm.coerceAtLeast(180f)
                    newB += (deltaMs * globalShare) * 0.20f
                    note = when {
                        singlePointOffCenter -> String.format(Locale.US, "Edge +1pt trim r=%.2f Δ=%.0fms", landingRatio, deltaMs)
                        abs(errorRatio) <= 0.045f -> String.format(Locale.US, "Center trim r=%.2f Δ=%.0fms", landingRatio, deltaMs)
                        else -> String.format(Locale.US, "LMS r=%.2f Δ=%.0fms", landingRatio, deltaMs)
                    }
                }
            }

            outcome == JumpOutcome.SHORT -> {
                learnedErrorMs = 28f
                newK += 0.014f * globalShare
                newB += 4f * globalShare
                note = "Short fallback (+k,+b)"
            }

            singlePointOffCenter -> {
                note = "Edge +1pt (r=NA)"
            }
        }

        // Keep MEDIUM as the pure 0ms baseline reference; only learn BLK: shape biases for MINI/NARROW/WIDE
        newBiases.remove("BLK:MEDIUM")
        if (abs(learnedErrorMs) >= 3f) {
            val isShortBand = dKey.isNotEmpty()
            val ctxWeight = when {
                isMiniBlock -> 0.85f
                isSpecialtyContext -> 0.65f
                else -> 0.25f
            }
            val ctxDelta = learnedErrorMs * ctxWeight

            if (isShortBand && (landingRatio != null || isFatalOut)) {
                val curDist = newBiases[dKey] ?: 0f
                val distMin = if (dKey == "DIST:NEAR270") -24f else -38f
                val distMax = if (dKey == "DIST:NEAR270") 8f else 6f
                val stepShare = if (isFatalOut) 0.55f else 0.50f
                newBiases[dKey] = (curDist + learnedErrorMs * stepShare).coerceIn(distMin, distMax)
            }
            val distAtten = if (isShortBand) 0.45f else 1.0f

            if (targetProfile != "MEDIUM") {
                val curBlk = newBiases[pKey] ?: 0f
                val skipNegativeMiniEdgeTrim = singlePointOffCenter &&
                    isMiniBlock &&
                    outcome == JumpOutcome.CENTER &&
                    learnedErrorMs < 0f &&
                    curBlk <= 4f
                if (!skipNegativeMiniEdgeTrim) {
                    val blkShare = when {
                        isMiniBlock && (stKey.isEmpty() || pawnStance == "CENTER") -> 0.78f
                        isMiniBlock -> 0.55f
                        stKey.isNotEmpty() && pawnStance != "CENTER" -> 0.40f
                        else -> 0.60f
                    }
                    val blkMin = if (isMiniBlock) -65f else -24f
                    val blkMax = if (isMiniBlock) 75f else 14f
                    val rawBlk = (curBlk + ctxDelta * blkShare * distAtten).coerceIn(blkMin, blkMax)
                    newBiases[pKey] = if (singlePointOffCenter && isMiniBlock && learnedErrorMs < 0f) {
                        rawBlk.coerceAtLeast(4f)
                    } else {
                        rawBlk
                    }
                }
            }

            if (sKey.isNotEmpty()) {
                val sigCap = if (targetProfile == "MEDIUM") 12f else 16f
                val curSig = newBiases[sKey] ?: 0f
                newBiases[sKey] = (curSig + ctxDelta * 0.16f * distAtten).coerceIn(-sigCap, sigCap)
            }

            if (stKey.isNotEmpty() && pawnStance != "CENTER") {
                val curSt = newBiases[stKey] ?: 0f
                val maxStStep = if (isFatalOut) 8f else 5f
                val stDelta = (ctxDelta * 0.22f * distAtten).coerceIn(-maxStStep, maxStStep)
                newBiases[stKey] = (curSt + stDelta).coerceIn(-14f, 14f)
                if (!note.contains(pawnStance)) {
                    note += " [$pawnStance]"
                }
            }

            if (isFatalOut || outcome == JumpOutcome.SHORT || outcome == JumpOutcome.PAST_CENTER) {
                val mistDelta = if (isFatalOut) learnedErrorMs.coerceIn(-36f, 36f) else (learnedErrorMs * 0.38f)
                val curMist = newBiases[mKey] ?: 0f
                newBiases[mKey] = (curMist + mistDelta).coerceIn(-MAX_MISTAKE_GUARD_MS, MAX_MISTAKE_GUARD_MS)
            } else if (singlePointOffCenter) {
                val curMist = newBiases[mKey]
                if (curMist != null && curMist * learnedErrorMs < 0f) {
                    newBiases[mKey] = (curMist * 0.45f + learnedErrorMs * 0.65f).coerceIn(-MAX_MISTAKE_GUARD_MS, MAX_MISTAKE_GUARD_MS)
                }
            }
        }

        // Robust Huber IRLS regression over recent clean baseline & bullseye jumps
        val validForOls = !isFatalOut && !comboConfirmed && !isSpecialtyContext && (landingRatio == null || landingRatio >= 0.72f)
        if (validForOls) {
            val fitSamples = history.asSequence()
                .takeWhile { it.outcome != JumpOutcome.USER_SHORT && it.outcome != JumpOutcome.USER_LONG }
                .filter { rec ->
                    val isBullseye = (rec.scoreDelta ?: 0) >= 2 && rec.outcome == JumpOutcome.CENTER
                    val hasCleanRatio = (rec.outcome == JumpOutcome.CENTER || rec.outcome == JumpOutcome.SHORT || rec.outcome == JumpOutcome.PAST_CENTER) &&
                        rec.landingRatio != null &&
                        rec.landingRatio in 0.72f..1.40f
                    val baseOk = rec.normalizedDistancePx >= 240f &&
                        rec.targetProfile == "MEDIUM" &&
                        (rec.pawnStance == "CENTER" || rec.pawnStance == "UNKNOWN")
                    baseOk && (isBullseye || hasCleanRatio)
                }
                .take(20)
                .toList()

            if (fitSamples.size >= 4) {
                val minD = fitSamples.minOf { it.normalizedDistancePx }
                val maxD = fitSamples.maxOf { it.normalizedDistancePx }
                if (maxD - minD >= 100f) {
                    val fitted = weightedLinearFit(fitSamples, config)
                    if (fitted != null) {
                        newK = newK * 0.88f + fitted.first * 0.12f
                        newB = newB * 0.88f + fitted.second * 0.12f
                        note += " + Huber"
                    }
                }
            }
        }

        val maxStep = when {
            allowLargeRecovery && abs(config.kFactor - ANCHOR_K) > 0.14f -> 0.15f
            isFatalOut -> MAX_FATAL_K_STEP
            else -> MAX_NON_FATAL_K_STEP
        }
        val clampedStepK = (newK - config.kFactor).coerceIn(-maxStep, maxStep)
        newK = (((config.kFactor + clampedStepK).coerceIn(MIN_K, MAX_K)) * 1000f).roundToInt() / 1000f
        newB = ((newB.coerceIn(MIN_B, MAX_B)) * 10f).roundToInt() / 10f

        val cleanedBiases = compactBiases(newBiases)

        val updated = config.copy(
            formulaMode = if (config.formulaMode == FormulaMode.QUADRATIC) FormulaMode.QUADRATIC else FormulaMode.LINEAR,
            kFactor = newK,
            offsetMs = newB,
            adaptiveBiasesMs = cleanedBiases
        )
        return updated to note
    }

    private fun compactBiases(source: Map<String, Float>): Map<String, Float> {
        val rounded = LinkedHashMap<String, Float>()
        source.forEach { (k, v) ->
            val clean = (v * 10f).roundToInt() / 10f
            if (abs(clean) >= 1.5f) {
                rounded[k] = clean
            }
        }
        if (rounded.size <= MAX_STORED_BIASES) return rounded
        // Always keep BLK:, STANCE:, and small-target MIST:MINI / MIST:NARROW entries; trim oldest others first
        val kept = LinkedHashMap<String, Float>()
        val isCore: (String) -> Boolean = { key ->
            key.startsWith("BLK:") ||
                key.startsWith("STANCE:") ||
                key.startsWith("DIST:") ||
                key.startsWith("MIST:MINI:") ||
                key.startsWith("MIST:NARROW:")
        }
        rounded.entries.filter { isCore(it.key) }.forEach { kept[it.key] = it.value }
        val budget = (MAX_STORED_BIASES - kept.size).coerceAtLeast(0)
        rounded.entries.filterNot { isCore(it.key) }.takeLast(budget).forEach { kept[it.key] = it.value }
        return kept
    }

    fun applyManualFeedback(
        config: BotConfig,
        lastRecord: JumpTelemetryRecord?,
        isShort: Boolean
    ): Triple<BotConfig, JumpTelemetryRecord?, String> {
        val dNorm = lastRecord?.normalizedDistancePx?.takeIf { it >= 100f } ?: 500f
        val deltaHoldMs = if (isShort) 42f else -42f
        val newK = (((config.kFactor + (deltaHoldMs * 0.82f) / dNorm).coerceIn(MIN_K, MAX_K)) * 1000f).roundToInt() / 1000f
        val newB = (((config.offsetMs + deltaHoldMs * 0.18f).coerceIn(MIN_B, MAX_B)) * 10f).roundToInt() / 10f

        val updatedBiases = LinkedHashMap(config.adaptiveBiasesMs)
        if (lastRecord != null) {
            val ctxShift = deltaHoldMs * if (lastRecord.targetProfile == "MINI") 0.75f else 0.55f
            val pKey = profileKey(lastRecord.targetProfile)
            val blkCap = if (lastRecord.targetProfile == "MINI") 110f else 90f
            updatedBiases[pKey] = ((updatedBiases[pKey] ?: 0f) + ctxShift * 0.55f).coerceIn(-blkCap, blkCap)
            val sKey = signatureKey(lastRecord.targetProfile, lastRecord.targetColorGroup)
            if (sKey.isNotEmpty()) {
                updatedBiases[sKey] = ((updatedBiases[sKey] ?: 0f) + ctxShift * 0.35f).coerceIn(-75f, 75f)
            }
            val stKey = stanceKey(lastRecord.pawnStance)
            if (stKey.isNotEmpty() && lastRecord.pawnStance != "CENTER") {
                updatedBiases[stKey] = ((updatedBiases[stKey] ?: 0f) + ctxShift * 0.45f).coerceIn(-75f, 75f)
            }
            val mKey = mistakeKey(lastRecord.targetProfile, lastRecord.pawnStance, dNorm)
            updatedBiases[mKey] = ((updatedBiases[mKey] ?: 0f) + ctxShift).coerceIn(-MAX_MISTAKE_GUARD_MS, MAX_MISTAKE_GUARD_MS)
        }

        val updatedCfg = config.copy(
            kFactor = newK,
            offsetMs = newB,
            adaptiveBiasesMs = compactBiases(updatedBiases)
        )
        val outcome = if (isShort) JumpOutcome.USER_SHORT else JumpOutcome.USER_LONG
        // Clear stale sensor ratio on manually overridden records so it never pulls OLS the wrong way
        val updatedRec = lastRecord?.copy(
            outcome = outcome,
            landingRatio = null,
            kAfter = newK,
            bAfter = newB,
            note = "Manual HUD: ${if (isShort) "Too Short (+k)" else "Too Long (-k)"}"
        )
        val msg = String.format(
            Locale.US,
            "Trained [%s]: k %.2f→%.2f, b %.0f→%.0f",
            outcome.shortLabel,
            config.kFactor,
            newK,
            config.offsetMs,
            newB
        )
        return Triple(updatedCfg, updatedRec, msg)
    }

    private fun weightedLinearFit(
        samples: List<JumpTelemetryRecord>,
        config: BotConfig
    ): Pair<Float, Float>? {
        val xs = DoubleArray(samples.size)
        val ys = DoubleArray(samples.size)
        val baseWeights = DoubleArray(samples.size)
        var count = 0

        for ((idx, rec) in samples.withIndex()) {
            val isBullseye = (rec.scoreDelta ?: 0) >= 2 && rec.outcome == JumpOutcome.CENTER
            val idealY = if (isBullseye) {
                (rec.holdMs - rec.appliedBiasMs).toDouble()
            } else {
                val r = (rec.landingRatio ?: continue).coerceIn(0.60f, 1.45f)
                (rec.holdMs.toDouble() / r) - rec.appliedBiasMs.toDouble()
            }.coerceIn(config.minPressMs.toDouble(), config.maxPressMs.toDouble())
            xs[count] = rec.normalizedDistancePx.toDouble()
            ys[count] = idealY
            val recencyDecay = Math.pow(0.92, idx.toDouble())
            val qualityW = when {
                isBullseye -> 2.2
                rec.outcome == JumpOutcome.CENTER -> 1.4
                else -> 0.9
            }
            baseWeights[count] = qualityW * recencyDecay
            count++
        }
        if (count < 4) return null

        var slope = config.kFactor.toDouble()
        var intercept = config.offsetMs.toDouble()
        val huberDelta = 14.0

        // 3 iterations of IRLS Huber Regression (equivalent to sklearn HuberRegressor)
        for (iter in 0 until 3) {
            var sw = 0.0
            var sx = 0.0
            var sy = 0.0
            var sxx = 0.0
            var sxy = 0.0
            for (i in 0 until count) {
                val residual = abs(ys[i] - (slope * xs[i] + intercept))
                val huberW = if (iter == 0 || residual <= huberDelta) 1.0 else huberDelta / residual
                val w = baseWeights[i] * huberW
                val x = xs[i]
                val y = ys[i]
                sw += w
                sx += w * x
                sy += w * y
                sxx += w * x * x
                sxy += w * x * y
            }
            val denom = sw * sxx - sx * sx
            if (abs(denom) < 1e-3) return null
            val nextSlope = (sw * sxy - sx * sy) / denom
            val nextIntercept = (sy - nextSlope * sx) / sw
            if (nextSlope !in MIN_K.toDouble()..MAX_K.toDouble() ||
                nextIntercept !in MIN_B.toDouble()..MAX_B.toDouble()
            ) {
                return null
            }
            slope = nextSlope
            intercept = nextIntercept
        }
        return slope.toFloat() to intercept.toFloat()
    }

    fun formatTelemetryReport(
        config: BotConfig,
        records: List<JumpTelemetryRecord>,
        logs: List<BotLogEntry>,
        screenW: Int,
        screenH: Int
    ): String {
        val total = records.size
        val centerCount = records.count { it.outcome == JumpOutcome.CENTER }
        val shortCount = records.count { it.outcome == JumpOutcome.SHORT || it.outcome == JumpOutcome.OUT_SHORT || it.outcome == JumpOutcome.USER_SHORT }
        val farCount = records.count { it.outcome == JumpOutcome.PAST_CENTER || it.outcome == JumpOutcome.OUT_OVERSHOOT || it.outcome == JumpOutcome.USER_LONG }
        val validRatios = records.mapNotNull { it.landingRatio }
        val avgRatioStr = if (validRatios.isNotEmpty()) {
            String.format(Locale.US, "%.2f", validRatios.average())
        } else {
            "NA"
        }

        val sb = StringBuilder()
        sb.appendLine("=== JumpAssist Training Report ===")
        sb.appendLine("Device: ${Build.MANUFACTURER} ${Build.MODEL} (API ${Build.VERSION.SDK_INT}) | Screen: ${screenW}x${screenH}")
        sb.appendLine(
            String.format(
                Locale.US,
                "Config: mode=%s k=%.3f b=%.1f pawn=#%06X tol=%d bgDiff=%d roi=%.2f..%.2f cooldown=%dms autoTrain=%s humanTiming=%s",
                config.formulaMode.name,
                config.kFactor,
                config.offsetMs,
                config.pawnColorRgb and 0xFFFFFF,
                config.pawnColorTolerance,
                config.bgDiffThreshold,
                config.roiTopFraction,
                config.roiBottomFraction,
                config.jumpCooldownMs,
                config.autoTrainEnabled,
                config.humanizeTiming
            )
        )
        if (config.adaptiveBiasesMs.isNotEmpty()) {
            val biasSummary = config.adaptiveBiasesMs.entries.joinToString(" ") { (k, v) ->
                String.format(Locale.US, "%s=%+.0fms", k, v)
            }
            sb.appendLine("AdaptiveBiases: $biasSummary")
        }
        sb.appendLine("Summary: total=$total center=$centerCount short=$shortCount far=$farCount avgRatio=$avgRatioStr")

        if (records.isNotEmpty()) {
            sb.appendLine("--- Jump History (newest first, up to 35) ---")
            records.take(35).forEach { r ->
                val ratioStr = r.landingRatio?.let { String.format(Locale.US, "%.2f", it) } ?: "NA"
                val blkTag = if (r.blockTopHex.isNotEmpty()) " blk=[${r.blockTopHex},${r.blockSideHex}]" else ""
                val biasTag = if (r.appliedBiasMs != 0L) String.format(Locale.US, "(%+d)", r.appliedBiasMs) else ""
                val stanceTag = String.format(Locale.US, "S=%s(%.2f,sw=%.0f)", r.pawnStance, r.stanceOffsetFrac, r.sourceWidthPx)
                val targetTag = if (r.targetColorGroup.isNotEmpty()) "${r.targetProfile}:${r.targetColorGroup}" else r.targetProfile
                val ptTag = r.scoreDelta?.let { " pt=+$it" } ?: ""
                sb.appendLine(
                    String.format(
                        Locale.US,
                        "#%d %s %s D=%.0f(n=%.0f) hold=%dms%s r=%s%s P=(%.0f,%.0f,%dpx) %s B=(%.0f,%.0f,w=%.0f,%s) k=%.3f->%.3f b=%.0f->%.0f rgb=[%s,%s,%s]%s %s",
                        r.id,
                        r.timestamp,
                        r.outcome.shortLabel,
                        r.rawDistancePx,
                        r.normalizedDistancePx,
                        r.holdMs,
                        biasTag,
                        ratioStr,
                        ptTag,
                        r.pawnX,
                        r.pawnY,
                        r.pawnPixels,
                        stanceTag,
                        r.blockX,
                        r.blockY,
                        r.blockWidth,
                        targetTag,
                        r.kBefore,
                        r.kAfter,
                        r.bBefore,
                        r.bAfter,
                        r.skyTopHex,
                        r.skyMidHex,
                        r.pawnSampleHex,
                        blkTag,
                        r.note
                    )
                )
            }
        }

        if (logs.isNotEmpty()) {
            sb.appendLine("--- Recent Logs (up to 12) ---")
            logs.take(12).forEach { l ->
                sb.appendLine("[${l.timestamp}]${if (l.isError) " ERR:" else ""} ${l.message}")
            }
        }
        return sb.toString().trim()
    }
}
