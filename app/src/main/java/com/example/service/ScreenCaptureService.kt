package com.example.service

import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.IBinder
import android.util.DisplayMetrics
import android.view.WindowManager
import androidx.core.app.NotificationCompat
import com.example.MainActivity
import com.example.R
import com.example.data.BotPreferencesRepository
import com.example.model.BotConfig
import com.example.model.JumpOutcome
import com.example.model.JumpTelemetryRecord
import com.example.model.VisionAnalysisResult
import com.example.model.VisionDebugStatus
import com.example.training.JumpTrainer
import com.example.vision.BlockVisionAnalyzer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Locale
import kotlin.coroutines.resume
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.roundToLong

class ScreenCaptureService : Service() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var loopJob: Job? = null
    private var stepJob: Job? = null
    private var feedbackJob: Job? = null

    private lateinit var repository: BotPreferencesRepository

    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null

    private val bitmapLock = Any()
    private var cachedBitmap: Bitmap? = null
    @Volatile
    private var manualConfigEpoch = 0L
    @Volatile
    private var lastTouchdownSnapshot: BlockVisionAnalyzer.UpperRoiSnapshot? = null
    private var consecutiveJitterRetries = 0

    private var screenWidth = 1080
    private var screenHeight = 2400
    private var screenDensity = 420

    private val projectionCallback = object : MediaProjection.Callback() {
        override fun onStop() {
            AutomationBridge.appendLog("MediaProjection session stopped by system")
            stopCaptureResources()
            stopSelf()
        }
    }

    override fun onCreate() {
        super.onCreate()
        repository = BotPreferencesRepository(applicationContext)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, Activity.RESULT_CANCELED)
                val dataIntent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableExtra(EXTRA_DATA, Intent::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra(EXTRA_DATA)
                }

                if (resultCode != Activity.RESULT_OK || dataIntent == null) {
                    AutomationBridge.appendLog("MediaProjection permission denied", isError = true)
                    stopSelf()
                    return START_NOT_STICKY
                }

                startAsForeground()
                initializeProjection(resultCode, dataIntent)
            }

            ACTION_STOP -> {
                AutomationBridge.appendLog("Screen capture service stopped")
                stopCaptureResources()
                stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    private fun startAsForeground() {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.notification_channel_name),
                NotificationManager.IMPORTANCE_LOW
            )
            manager.createNotificationChannel(channel)
        }

        val launchIntent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            launchIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val notification: Notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.notification_title))
            .setContentText(getString(R.string.notification_text))
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun initializeProjection(resultCode: Int, data: Intent) {
        stopCaptureResources()
        resolveScreenMetrics()
        AutomationBridge.setScreenDimensions(screenWidth, screenHeight)

        val projectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        val projection = projectionManager.getMediaProjection(resultCode, data)
        if (projection == null) {
            AutomationBridge.appendLog("Failed to acquire MediaProjection instance", isError = true)
            stopSelf()
            return
        }

        projection.registerCallback(projectionCallback, null)
        mediaProjection = projection

        imageReader = ImageReader.newInstance(
            screenWidth,
            screenHeight,
            PixelFormat.RGBA_8888,
            2
        )

        virtualDisplay = projection.createVirtualDisplay(
            "JumpAssistCapture",
            screenWidth,
            screenHeight,
            screenDensity,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            imageReader?.surface,
            null,
            null
        )

        AutomationBridge.setScreenCaptureActive(true)
        AutomationBridge.appendLog("Screen capture started (${screenWidth}x${screenHeight})")

        startWorkers()
    }

    private fun resolveScreenMetrics() {
        val wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val bounds = wm.currentWindowMetrics.bounds
            screenWidth = bounds.width().coerceAtLeast(360)
            screenHeight = bounds.height().coerceAtLeast(640)
            screenDensity = resources.configuration.densityDpi
        } else {
            @Suppress("DEPRECATION")
            val metrics = DisplayMetrics()
            @Suppress("DEPRECATION")
            wm.defaultDisplay.getRealMetrics(metrics)
            screenWidth = metrics.widthPixels.coerceAtLeast(360)
            screenHeight = metrics.heightPixels.coerceAtLeast(640)
            screenDensity = metrics.densityDpi
        }
    }

    private fun startWorkers() {
        loopJob?.cancel()
        stepJob?.cancel()
        feedbackJob?.cancel()

        loopJob = serviceScope.launch {
            while (isActive) {
                if (AutomationBridge.isAutoLoopActive.value) {
                    val waitMs = try {
                        executeSingleCycle()
                    } catch (ce: CancellationException) {
                        throw ce
                    } catch (t: Throwable) {
                        val errMsg = t.message ?: t.javaClass.simpleName
                        AutomationBridge.appendLog("Loop recovered from error: $errMsg", isError = true)
                        AutomationBridge.updateVisionDebugStatus(
                            VisionDebugStatus(
                                phase = "ERROR",
                                message = "Recovered error: ${errMsg.take(48)}",
                                showTargetHighlight = false
                            )
                        )
                        450L
                    }
                    delay(waitMs)
                } else {
                    if (AutomationBridge.isOverlayActive.value && AutomationBridge.isDebugVisionEnabled.value) {
                        runCatching { publishLivePreviewFrame() }
                        delay(420L)
                    } else {
                        delay(200L)
                    }
                }
            }
        }

        stepJob = serviceScope.launch {
            AutomationBridge.singleStepRequests.collect {
                try {
                    executeSingleCycle(requireStability = false)
                } catch (ce: CancellationException) {
                    throw ce
                } catch (t: Throwable) {
                    val errMsg = t.message ?: t.javaClass.simpleName
                    AutomationBridge.appendLog("Step error: $errMsg", isError = true)
                    AutomationBridge.updateVisionDebugStatus(
                        VisionDebugStatus("ERROR", "Step error: ${errMsg.take(48)}", showTargetHighlight = false)
                    )
                }
            }
        }

        feedbackJob = serviceScope.launch {
            AutomationBridge.manualFeedbackRequests.collect { isShort ->
                handleManualCorrection(isShort)
            }
        }
    }

    private suspend fun handleManualCorrection(isShort: Boolean) {
        if (!AutomationBridge.isAccessibilityConnected.value) {
            AutomationBridge.updateTrainingBanner("Enable Accessibility Service first!")
            AutomationBridge.appendLog("Ignored correction: Accessibility Service is not bound yet", isError = true)
            return
        }
        manualConfigEpoch++
        val cfg = AutomationBridge.currentConfig.value
        val latest = AutomationBridge.trainingRecords.value.firstOrNull()
        val (updatedCfg, updatedRec, msg) = JumpTrainer.applyManualFeedback(cfg, latest, isShort)
        AutomationBridge.updateConfig(updatedCfg)
        repository.updateConfig { updatedCfg }
        if (updatedRec != null) {
            AutomationBridge.replaceLatestTrainingRecord(updatedRec, msg)
            repository.saveTrainingHistory(AutomationBridge.trainingRecords.value)
        } else {
            AutomationBridge.updateTrainingBanner(msg)
        }
        AutomationBridge.appendLog(msg)
    }

    private fun publishLivePreviewFrame() {
        val config = AutomationBridge.currentConfig.value
        val overlayRect = AutomationBridge.overlayBounds.value
        val pair = analyzeLatestFrameWithDiag(config, overlayRect, allowCached = true)
        if (pair == null) {
            AutomationBridge.updateVisionDebugStatus(
                VisionDebugStatus(
                    phase = "NO_FRAME",
                    message = "PAUSED · Waiting screen image from system",
                    showTargetHighlight = false
                )
            )
            return
        }
        val (res, diag) = pair
        AutomationBridge.updateAnalysis(res)
        val statusMsg = when {
            !res.pawnFound -> "PAUSED · No Pawn found [sky=${diag.skyTopHex}/${diag.skyMidHex}]"
            !res.blockFound -> "PAUSED · Pawn locked, no Block [sky=${diag.skyTopHex}/${diag.skyMidHex}]"
            else -> "PREVIEW · ${res.targetBlockProfile.take(3)}/${res.pawnStance.take(4)} D=${res.rawDistancePx.roundToInt()} → ${res.computedDurationMs}ms"
        }
        AutomationBridge.updateVisionDebugStatus(
            VisionDebugStatus(
                phase = if (res.pawnFound && res.blockFound) "LOCKED" else "NO_BOARD",
                message = statusMsg,
                showTargetHighlight = res.pawnFound && res.blockFound
            )
        )
    }

    private suspend fun executeSingleCycle(requireStability: Boolean = true): Long {
        val config = AutomationBridge.currentConfig.value
        val overlayRect = AutomationBridge.overlayBounds.value

        var initialPair = analyzeLatestFrameWithDiag(
            config = config,
            overlayRect = overlayRect,
            allowCached = true,
            touchdownSnapshot = lastTouchdownSnapshot
        )
        if (initialPair == null) {
            delay(65L)
            initialPair = analyzeLatestFrameWithDiag(
                config = config,
                overlayRect = overlayRect,
                allowCached = true,
                touchdownSnapshot = lastTouchdownSnapshot
            )
        }
        if (initialPair == null) {
            AutomationBridge.updateVisionDebugStatus(
                VisionDebugStatus(
                    phase = "NO_FRAME",
                    message = "Waiting screen frame (ImageReader empty)",
                    showTargetHighlight = false
                )
            )
            return 220L
        }

        var stableResult = initialPair.first
        var stableDiag = initialPair.second

        val startManualEpoch = manualConfigEpoch

        if (requireStability && AutomationBridge.isAutoLoopActive.value) {
            var checks = 0
            var lockedStable = false
            var missingBoardStreak = if (!stableResult.pawnFound || !stableResult.blockFound) 1 else 0
            var lastDistDrift = Float.MAX_VALUE
            var lastPawnDrift = Float.MAX_VALUE

            while (checks < 8 && AutomationBridge.isAutoLoopActive.value) {
                delay(80L)
                val nextPair = analyzeLatestFrameWithDiag(
                    config = config,
                    overlayRect = overlayRect,
                    allowCached = true,
                    touchdownSnapshot = lastTouchdownSnapshot
                )
                val nextResult = nextPair?.first
                val prevW = stableResult.blockBounds?.widthPx ?: 0f
                val nextW = nextResult?.blockBounds?.widthPx ?: 0f
                if (nextResult != null &&
                    stableResult.pawnFound && nextResult.pawnFound &&
                    stableResult.blockFound && nextResult.blockFound
                ) {
                    missingBoardStreak = 0
                    lastDistDrift = abs(stableResult.rawDistancePx - nextResult.rawDistancePx)
                    lastPawnDrift = maxOf(
                        abs(stableResult.pawnCenterX - nextResult.pawnCenterX),
                        abs(stableResult.pawnBaseY - nextResult.pawnBaseY)
                    )
                    val widthDrift = abs(prevW - nextW)
                    if (lastDistDrift <= 8f && lastPawnDrift <= 5f && widthDrift <= 14f) {
                        stableResult = nextResult
                        stableDiag = nextPair.second
                        lockedStable = true
                        break
                    }
                    AutomationBridge.updateAnalysis(nextResult)
                    AutomationBridge.updateVisionDebugStatus(
                        VisionDebugStatus(
                            phase = "WAITING_STABILITY",
                            message = "Stabilizing (${checks + 1}/8): ΔD=${lastDistDrift.roundToInt()}px ΔP=${lastPawnDrift.roundToInt()}px",
                            showTargetHighlight = true
                        )
                    )
                } else {
                    missingBoardStreak++
                    lastDistDrift = Float.MAX_VALUE
                    lastPawnDrift = Float.MAX_VALUE
                    if (missingBoardStreak >= 2) {
                        if (nextPair != null) {
                            stableResult = nextPair.first
                            stableDiag = nextPair.second
                        }
                        break
                    }
                }
                if (nextPair != null) {
                    stableResult = nextPair.first
                    stableDiag = nextPair.second
                }
                checks++
            }
            if (!lockedStable) {
                if (!stableResult.pawnFound || !stableResult.blockFound) {
                    consecutiveJitterRetries = 0
                    lastTouchdownSnapshot = null
                    AutomationBridge.updateAnalysis(stableResult)
                    AutomationBridge.updateTrainingBanner("Standby (waiting for game board...)")
                    AutomationBridge.updateVisionDebugStatus(
                        VisionDebugStatus(
                            phase = "NO_BOARD",
                            message = "${if (!stableResult.pawnFound) "No Pawn" else "No Target Block"} [sky=${stableDiag.skyTopHex}/${stableDiag.skyMidHex}]",
                            showTargetHighlight = false
                        )
                    )
                    return 260L
                }
                if (lastPawnDrift > 6f || lastDistDrift > 11f) {
                    val minorAnimJitter = lastPawnDrift <= 18f && lastDistDrift <= 22f
                    consecutiveJitterRetries = if (minorAnimJitter) consecutiveJitterRetries + 1 else 0
                    if (consecutiveJitterRetries < 2) {
                        AutomationBridge.updateAnalysis(stableResult)
                        AutomationBridge.updateVisionDebugStatus(
                            VisionDebugStatus(
                                phase = "WAITING_STABILITY",
                                message = "Waiting settle: ΔD=${lastDistDrift.roundToInt()}px ΔP=${lastPawnDrift.roundToInt()}px",
                                showTargetHighlight = true
                            )
                        )
                        return 100L
                    }
                }
            }
            if (lockedStable) {
                consecutiveJitterRetries = 0
            }
        }
        lastTouchdownSnapshot = null

        AutomationBridge.updateAnalysis(stableResult)

        if (!stableResult.pawnFound || !stableResult.blockFound) {
            AutomationBridge.updateTrainingBanner("Standby (waiting for game board...)")
            AutomationBridge.appendLog(
                "${stableResult.statusMessage} [sky=${stableDiag.skyTopHex}/${stableDiag.skyMidHex}]",
                isError = true
            )
            AutomationBridge.updateVisionDebugStatus(
                VisionDebugStatus(
                    phase = "NO_BOARD",
                    message = "${if (!stableResult.pawnFound) "No Pawn" else "No Target Block"} [sky=${stableDiag.skyTopHex}/${stableDiag.skyMidHex}]",
                    showTargetHighlight = false
                )
            )
            return 280L
        }

        if (!AutomationBridge.isAccessibilityConnected.value) {
            AutomationBridge.updateTrainingBanner("⚠ Enable Accessibility Touch!")
            AutomationBridge.appendLog("Waiting: toggle Accessibility Service OFF→ON in Settings", isError = true)
            AutomationBridge.updateVisionDebugStatus(
                VisionDebugStatus(
                    phase = "TOUCH_OFF",
                    message = "Accessibility Touch OFF — Re-enable in Settings",
                    showTargetHighlight = true
                )
            )
            return 1000L
        }

        val baseTapX = screenWidth * config.tapXFraction
        val baseTapY = screenHeight * config.tapYFraction

        if (requireStability && config.humanizeTiming && AutomationBridge.isAutoLoopActive.value) {
            val humanPauseMs = computeHumanPreJumpPauseMs()
            AutomationBridge.updateVisionDebugStatus(
                VisionDebugStatus(
                    phase = "HUMAN_PACING",
                    message = "Human pace (${humanPauseMs}ms) → D=${stableResult.rawDistancePx.roundToInt()}px",
                    showTargetHighlight = true
                )
            )
            delay(humanPauseMs)
            if (!AutomationBridge.isAutoLoopActive.value) {
                return 160L
            }
            // Re-verify fresh frame after waiting so we never jump using stale pre-pause geometry
            val postPausePair = analyzeLatestFrameWithDiag(config, overlayRect, allowCached = true)
            val postPause = postPausePair?.first
            if (postPause == null || !postPause.pawnFound || !postPause.blockFound) {
                return 140L
            }
            val distShift = abs(postPause.rawDistancePx - stableResult.rawDistancePx)
            val pawnShift = abs(postPause.pawnCenterX - stableResult.pawnCenterX)
            val largeShift = distShift > 26f || pawnShift > 16f
            if (largeShift || ((distShift > 12f || pawnShift > 8f) && consecutiveJitterRetries < 2)) {
                consecutiveJitterRetries = if (!largeShift) consecutiveJitterRetries + 1 else 0
                AutomationBridge.updateAnalysis(postPause)
                return 90L
            }
            stableResult = postPause
            stableDiag = postPausePair.second
            AutomationBridge.updateAnalysis(stableResult)
        }
        consecutiveJitterRetries = 0

        if (stableResult.normalizedDistancePx !in 85f..940f) {
            AutomationBridge.updateVisionDebugStatus(
                VisionDebugStatus(
                    phase = "WAITING_STABILITY",
                    message = "Out-of-range D=${stableResult.rawDistancePx.roundToInt()}px — re-locking",
                    showTargetHighlight = false
                )
            )
            return 180L
        }

        val holdMs = stableResult.computedDurationMs

        val biasNote = if (stableResult.appliedContextBiasMs != 0L) String.format(Locale.US, "%+d", stableResult.appliedContextBiasMs) else "0"
        AutomationBridge.appendLog(
            String.format(
                Locale.US,
                "D=%.0fpx (n=%.0f) %s:%s [%s] → Hold %dms (bias=%sms, k=%.2f)",
                stableResult.rawDistancePx,
                stableResult.normalizedDistancePx,
                stableResult.targetBlockProfile,
                stableResult.targetColorGroup.ifBlank { "STD" },
                stableResult.pawnStance,
                holdMs,
                biasNote,
                config.kFactor
            )
        )

        val tapSpread = if (config.humanizeTiming) 18 else 6
        var completed = false
        for (attempt in 0..1) {
            val jitterX = kotlin.random.Random.nextInt(-tapSpread, tapSpread + 1).toFloat()
            val jitterY = kotlin.random.Random.nextInt(-tapSpread, tapSpread + 1).toFloat()
            val tapX = (baseTapX + jitterX).coerceIn(40f, screenWidth - 40f)
            val tapY = (baseTapY + jitterY).coerceIn(40f, screenHeight - 40f)

            // Hide target bracket right before touching so the post-jump sky never has stale brackets
            AutomationBridge.updateVisionDebugStatus(
                VisionDebugStatus(
                    phase = "HOLDING",
                    message = "HOLD ${holdMs}ms → ${stableResult.targetBlockProfile.take(3)}/${stableResult.pawnStance.take(4)} (D=${stableResult.rawDistancePx.roundToInt()})",
                    showTargetHighlight = false,
                    holdingTapX = tapX,
                    holdingTapY = tapY,
                    isHolding = true
                )
            )
            clearCachedFrame()
            completed = withTimeoutOrNull(holdMs + 2400L) {
                suspendCancellableCoroutine { cont ->
                    AutomationBridge.performHoldGesture(tapX, tapY, holdMs) { ok ->
                        if (cont.isActive) cont.resume(ok)
                    }
                }
            } ?: false
            if (completed || !AutomationBridge.isAccessibilityConnected.value) break
            if (attempt == 0) {
                delay(120L)
            }
        }

        if (!completed) {
            AutomationBridge.appendLog("Gesture dispatch failed, cancelled, or timed out", isError = true)
            AutomationBridge.updateVisionDebugStatus(
                VisionDebugStatus(
                    phase = "ERROR",
                    message = "Touch cancelled/timed out — retrying",
                    showTargetHighlight = false,
                    isHolding = false
                )
            )
            return 350L
        }

        AutomationBridge.updateVisionDebugStatus(
            VisionDebugStatus(
                phase = "SETTLING",
                message = "Jumping... sampling landing & waiting settle",
                showTargetHighlight = false,
                isHolding = false
            )
        )

        // Flush any frame from during the hold so post-jump burst captures the real touchdown
        clearCachedFrame()

        // Sample right at touchdown: avoid premature mid-air capture on short/mid holds and camera pan on long holds
        val burst1DelayMs = (400L + (holdMs * 0.032f).roundToLong()).coerceIn(406L, 434L)
        delay(burst1DelayMs)
        val burst1 = analyzeLatestFrameWithDiag(config, overlayRect, allowCached = false)?.first
        val r1 = JumpTrainer.computeLandingRatio(stableResult, burst1)

        delay(70L)
        val burst2Triple = analyzeLatestFrameWithSnapshot(config, overlayRect, allowCached = true)
        val burst2 = burst2Triple?.first
        val r2 = JumpTrainer.computeLandingRatio(stableResult, burst2)
        lastTouchdownSnapshot = burst2Triple?.third

        val landingRatio = when {
            r1 != null && r2 != null -> when {
                abs(r1 - r2) <= 0.05f -> (r1 + r2) / 2f
                r1 > 1.07f && r2 in 0.86f..1.07f && holdMs < 640L -> r2
                r1 > 1.14f && r2 in 0.85f..1.10f -> r2
                r2 < r1 - 0.05f -> r1
                else -> r2
            }
            r1 != null -> r1
            else -> r2
        }

        // Wait remaining cooldown for camera pan / Game Over popup to finish settling
        val cooldownJitter = if (config.humanizeTiming) kotlin.random.Random.nextLong(-45L, 165L) else 0L
        val elapsedWaitMs = burst1DelayMs + 70L
        val remainingCooldown = (config.jumpCooldownMs - elapsedWaitMs + cooldownJitter).coerceAtLeast(320L)
        delay(remainingCooldown)
        val postSettled = analyzeLatestFrameWithDiag(config, overlayRect, allowCached = true)?.first

        val preScore = stableResult.scoreReading
        val postScore = postSettled?.scoreReading
        val scoreDelta = if (preScore != null && postScore != null && postScore >= preScore && (postScore - preScore) <= 64) {
            postScore - preScore
        } else {
            null
        }

        val outcome = JumpTrainer.classifyOutcome(stableResult, landingRatio, postSettled, scoreDelta)
        val existingHistory = AutomationBridge.trainingRecords.value
        val activeCfg = AutomationBridge.currentConfig.value
        val userOverrodeMidJump = manualConfigEpoch != startManualEpoch
        val (updatedConfig, trainNote) = if (userOverrodeMidJump) {
            activeCfg to "Kept manual HUD fix"
        } else {
            JumpTrainer.applyOnlineUpdate(
                config = activeCfg,
                dNorm = stableResult.normalizedDistancePx,
                holdMs = holdMs,
                outcome = outcome,
                landingRatio = landingRatio,
                history = existingHistory,
                targetProfile = stableResult.targetBlockProfile,
                targetColorGroup = stableResult.targetColorGroup,
                pawnStance = stableResult.pawnStance,
                scoreDelta = scoreDelta
            )
        }

        val blk = stableResult.blockBounds!!
        val ratioSummary = landingRatio?.let { String.format(Locale.US, "r=%.2f", it) } ?: "r=NA"
        val ptSummary = scoreDelta?.let { " +${it}pt" } ?: ""
        val banner = "${outcome.shortLabel} ($ratioSummary$ptSummary) · ${stableResult.targetBlockProfile.take(3)}/${stableResult.pawnStance.take(4)}"
        val record = JumpTelemetryRecord(
            id = AutomationBridge.nextRecordId(),
            timestamp = AutomationBridge.currentTimeStr(),
            pawnX = stableResult.pawnCenterX,
            pawnY = stableResult.pawnBaseY,
            pawnPixels = stableResult.pawnPixelCount,
            blockX = blk.center.x,
            blockY = blk.center.y,
            blockWidth = blk.widthPx,
            blockHeight = blk.heightPx,
            rawDistancePx = stableResult.rawDistancePx,
            normalizedDistancePx = stableResult.normalizedDistancePx,
            holdMs = holdMs,
            landingRatio = landingRatio,
            outcome = outcome,
            kBefore = activeCfg.kFactor,
            bBefore = activeCfg.offsetMs,
            kAfter = updatedConfig.kFactor,
            bAfter = updatedConfig.offsetMs,
            skyTopHex = stableDiag.skyTopHex,
            skyMidHex = stableDiag.skyMidHex,
            pawnSampleHex = stableDiag.pawnSampleHex,
            blockTopHex = stableDiag.blockTopHex,
            blockSideHex = stableDiag.blockSideHex,
            note = trainNote,
            pawnStance = stableResult.pawnStance,
            stanceOffsetFrac = stableResult.pawnStanceOffsetFrac,
            sourceWidthPx = stableResult.sourceWidthPx,
            targetProfile = stableResult.targetBlockProfile,
            targetColorGroup = stableResult.targetColorGroup,
            appliedBiasMs = stableResult.appliedContextBiasMs,
            scoreDelta = scoreDelta
        )

        AutomationBridge.prependTrainingRecord(record, banner)
        repository.saveTrainingHistory(AutomationBridge.trainingRecords.value)

        if (!userOverrodeMidJump && updatedConfig != activeCfg && activeCfg.autoTrainEnabled) {
            AutomationBridge.updateConfig(updatedConfig)
            repository.updateConfig { updatedConfig }
            AutomationBridge.appendLog(
                String.format(
                    Locale.US,
                    "Self-Train [%s %s]: k %.3f→%.3f, b %.0f→%.0f (%s)",
                    outcome.shortLabel,
                    ratioSummary,
                    activeCfg.kFactor,
                    updatedConfig.kFactor,
                    activeCfg.offsetMs,
                    updatedConfig.offsetMs,
                    trainNote
                )
            )
        } else {
            AutomationBridge.appendLog("Result [${outcome.shortLabel} $ratioSummary] ($trainNote)")
        }

        // Keep Auto-Loop ARMED across Game Over so user just taps Play Again and self-training continues!
        if (outcome == JumpOutcome.OUT_SHORT || outcome == JumpOutcome.OUT_OVERSHOOT || outcome == JumpOutcome.OUT_UNKNOWN) {
            lastTouchdownSnapshot = null
            AutomationBridge.updateTrainingBanner("OUT ($ratioSummary) · Armed for Replay")
            AutomationBridge.appendLog("Round ended ($outcome) — Auto stays armed; tap Replay in game to continue")
        }

        return 40L
    }

    private fun analyzeLatestFrameWithDiag(
        config: BotConfig,
        overlayRect: android.graphics.Rect?,
        allowCached: Boolean,
        touchdownSnapshot: BlockVisionAnalyzer.UpperRoiSnapshot? = null
    ): Pair<VisionAnalysisResult, BlockVisionAnalyzer.FrameDiagnostics>? {
        val bitmap = acquireLatestBitmapCopy(allowCached) ?: return null
        return try {
            var res = BlockVisionAnalyzer.analyze(bitmap, config, overlayRect)
            if (touchdownSnapshot != null && res.pawnFound) {
                val hint = BlockVisionAnalyzer.detectNewlyAppearedHint(
                    prevSnapshot = touchdownSnapshot,
                    currentBitmap = bitmap,
                    currPawnX = res.pawnCenterX,
                    currPawnY = res.pawnBaseY
                )
                if (hint != null) {
                    res = BlockVisionAnalyzer.analyze(bitmap, config, overlayRect, preferredTargetHint = hint)
                }
            }
            val diag = BlockVisionAnalyzer.sampleFrameDiagnostics(
                bitmap = bitmap,
                roiTopY = res.roiTopY,
                roiBottomY = res.roiBottomY,
                pawnX = res.pawnCenterX,
                pawnY = res.pawnBaseY,
                blockX = res.blockBounds?.center?.x ?: 0f,
                blockY = res.blockBounds?.center?.y ?: 0f,
                blockBottomY = res.blockBounds?.bottomY ?: 0f
            )
            res to diag
        } finally {
            bitmap.recycle()
        }
    }

    private fun analyzeLatestFrameWithSnapshot(
        config: BotConfig,
        overlayRect: android.graphics.Rect?,
        allowCached: Boolean
    ): Triple<VisionAnalysisResult, BlockVisionAnalyzer.FrameDiagnostics, BlockVisionAnalyzer.UpperRoiSnapshot?>? {
        val bitmap = acquireLatestBitmapCopy(allowCached) ?: return null
        return try {
            val res = BlockVisionAnalyzer.analyze(bitmap, config, overlayRect)
            val diag = BlockVisionAnalyzer.sampleFrameDiagnostics(
                bitmap = bitmap,
                roiTopY = res.roiTopY,
                roiBottomY = res.roiBottomY,
                pawnX = res.pawnCenterX,
                pawnY = res.pawnBaseY,
                blockX = res.blockBounds?.center?.x ?: 0f,
                blockY = res.blockBounds?.center?.y ?: 0f,
                blockBottomY = res.blockBounds?.bottomY ?: 0f
            )
            val snap = if (res.pawnFound) {
                BlockVisionAnalyzer.captureUpperRoiSnapshot(
                    bitmap = bitmap,
                    roiTopY = res.roiTopY,
                    roiBottomY = res.roiBottomY,
                    pawnX = res.pawnCenterX,
                    pawnY = res.pawnBaseY
                )
            } else null
            Triple(res, diag, snap)
        } finally {
            bitmap.recycle()
        }
    }

    private fun clearCachedFrame() {
        synchronized(bitmapLock) {
            cachedBitmap?.recycle()
            cachedBitmap = null
        }
        runCatching {
            var img = imageReader?.acquireLatestImage()
            while (img != null) {
                img.close()
                img = imageReader?.acquireLatestImage()
            }
        }
    }

    private fun acquireLatestBitmapCopy(allowCached: Boolean): Bitmap? {
        val reader = imageReader ?: return null
        val image: Image? = runCatching { reader.acquireLatestImage() }.getOrNull()
        if (image != null) {
            val fresh = try {
                val plane = image.planes[0]
                val buffer = plane.buffer
                val pixelStride = plane.pixelStride
                val rowStride = plane.rowStride
                val rowPadding = rowStride - pixelStride * screenWidth
                val paddedWidth = screenWidth + rowPadding / pixelStride

                val rawBitmap = Bitmap.createBitmap(paddedWidth, screenHeight, Bitmap.Config.ARGB_8888)
                rawBitmap.copyPixelsFromBuffer(buffer)

                if (paddedWidth == screenWidth) {
                    rawBitmap
                } else {
                    val cropped = Bitmap.createBitmap(rawBitmap, 0, 0, screenWidth, screenHeight)
                    rawBitmap.recycle()
                    cropped
                }
            } catch (_: Throwable) {
                null
            } finally {
                image.close()
            }

            if (fresh != null) {
                synchronized(bitmapLock) {
                    cachedBitmap?.recycle()
                    cachedBitmap = fresh.copy(Bitmap.Config.ARGB_8888, false)
                }
                return fresh
            }
        }

        if (!allowCached) return null
        return synchronized(bitmapLock) {
            cachedBitmap?.takeIf { !it.isRecycled }?.copy(Bitmap.Config.ARGB_8888, false)
        }
    }

    private fun stopCaptureResources() {
        lastTouchdownSnapshot = null
        loopJob?.cancel()
        stepJob?.cancel()
        feedbackJob?.cancel()
        clearCachedFrame()
        virtualDisplay?.release()
        virtualDisplay = null
        imageReader?.close()
        imageReader = null
        mediaProjection?.unregisterCallback(projectionCallback)
        mediaProjection?.stop()
        mediaProjection = null
        AutomationBridge.setScreenCaptureActive(false)
    }

    override fun onDestroy() {
        stopCaptureResources()
        serviceScope.cancel()
        super.onDestroy()
    }

    companion object {
        const val ACTION_START = "com.example.action.START_CAPTURE"
        const val ACTION_STOP = "com.example.action.STOP_CAPTURE"
        const val EXTRA_RESULT_CODE = "extra_result_code"
        const val EXTRA_DATA = "extra_data"
        private const val CHANNEL_ID = "jump_assist_capture_channel"
        private const val NOTIFICATION_ID = 4102

        /**
         * Natural human pre-jump reaction distribution:
         * ~38% quick confident hops (55..210ms),
         * ~44% standard human rhythm (240..590ms),
         * ~18% occasional brief aiming/thinking pauses (680..1280ms).
         */
        fun computeHumanPreJumpPauseMs(
            tierSample: Float = kotlin.random.Random.nextFloat(),
            withinTierSample: Float = kotlin.random.Random.nextFloat()
        ): Long {
            val u = withinTierSample.coerceIn(0f, 1f)
            return when {
                tierSample < 0.38f -> (55f + u * 155f).roundToLong()
                tierSample < 0.82f -> (240f + u * 350f).roundToLong()
                else -> (680f + u * 600f).roundToLong()
            }
        }

        fun startIntent(context: Context, resultCode: Int, data: Intent): Intent {
            return Intent(context, ScreenCaptureService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_RESULT_CODE, resultCode)
                putExtra(EXTRA_DATA, data)
            }
        }

        fun stopIntent(context: Context): Intent {
            return Intent(context, ScreenCaptureService::class.java).apply {
                action = ACTION_STOP
            }
        }
    }
}
