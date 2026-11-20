package com.example.viewmodel

import android.Manifest
import android.app.Application
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.BotPreferencesRepository
import com.example.model.BotConfig
import com.example.model.FormulaMode
import com.example.model.VisionAnalysisResult
import com.example.service.AutomationBridge
import com.example.service.FloatingOverlayService
import com.example.service.JumpAccessibilityService
import com.example.service.ScreenCaptureService
import com.example.training.JumpTrainer
import com.example.vision.BlockVisionAnalyzer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

data class PermissionReadiness(
    val accessibilityEnabled: Boolean = false,
    val accessibilitySettingOn: Boolean = false,
    val overlayGranted: Boolean = false,
    val notificationsGranted: Boolean = true,
    val captureRunning: Boolean = false,
    val overlayRunning: Boolean = false,
    val autoLoopRunning: Boolean = false
)

class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val repository = BotPreferencesRepository(application)

    private val _config = MutableStateFlow(BotConfig())
    val config: StateFlow<BotConfig> = _config.asStateFlow()

    private val _permissions = MutableStateFlow(PermissionReadiness())
    val permissions: StateFlow<PermissionReadiness> = _permissions.asStateFlow()

    private val _sampleBitmap = MutableStateFlow<Bitmap?>(null)
    val sampleBitmap: StateFlow<Bitmap?> = _sampleBitmap.asStateFlow()

    private val _sampleAnalysis = MutableStateFlow<VisionAnalysisResult?>(null)
    val sampleAnalysis: StateFlow<VisionAnalysisResult?> = _sampleAnalysis.asStateFlow()

    private val _isEyedropperActive = MutableStateFlow(false)
    val isEyedropperActive: StateFlow<Boolean> = _isEyedropperActive.asStateFlow()

    private var syntheticVariantIndex = 0

    val logs = AutomationBridge.logs
    val liveAnalysis = AutomationBridge.lastAnalysis
    val trainingRecords = AutomationBridge.trainingRecords
    val isDebugVisionEnabled = AutomationBridge.isDebugVisionEnabled

    init {
        viewModelScope.launch {
            repository.configFlow.collect { loaded ->
                _config.value = loaded
                AutomationBridge.updateConfig(loaded)
                reanalyzeCurrentBitmap(loaded)
            }
        }

        viewModelScope.launch {
            repository.trainingHistoryFlow.collect { history ->
                if (AutomationBridge.trainingRecords.value.isEmpty() && history.isNotEmpty()) {
                    AutomationBridge.setTrainingRecords(history)
                }
            }
        }

        viewModelScope.launch {
            AutomationBridge.isAccessibilityConnected.collect { refreshPermissions() }
        }
        viewModelScope.launch {
            AutomationBridge.isScreenCaptureActive.collect { refreshPermissions() }
        }
        viewModelScope.launch {
            AutomationBridge.isOverlayActive.collect { refreshPermissions() }
        }
        viewModelScope.launch {
            AutomationBridge.isAutoLoopActive.collect { refreshPermissions() }
        }

        generateNextSyntheticFrame()
        refreshPermissions()
    }

    fun refreshPermissions() {
        val ctx = getApplication<Application>()
        val accessibilityBound = AutomationBridge.isAccessibilityConnected.value
        val accessibilityInSettings = isAccessibilityServiceEnabledInSettings(ctx)
        val overlay = Settings.canDrawOverlays(ctx)
        val notifications = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        } else {
            true
        }

        _permissions.value = PermissionReadiness(
            accessibilityEnabled = accessibilityBound,
            accessibilitySettingOn = accessibilityInSettings,
            overlayGranted = overlay,
            notificationsGranted = notifications,
            captureRunning = AutomationBridge.isScreenCaptureActive.value,
            overlayRunning = AutomationBridge.isOverlayActive.value,
            autoLoopRunning = AutomationBridge.isAutoLoopActive.value
        )
    }

    private fun isAccessibilityServiceEnabledInSettings(context: Context): Boolean {
        val expectedComponent = "${context.packageName}/${JumpAccessibilityService::class.java.name}"
        val enabledServices = Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false
        return enabledServices.split(':').any { it.equals(expectedComponent, ignoreCase = true) }
    }

    fun startScreenCaptureService(resultCode: Int, data: Intent) {
        val ctx = getApplication<Application>()
        val intent = ScreenCaptureService.startIntent(ctx, resultCode, data)
        ContextCompat.startForegroundService(ctx, intent)
    }

    fun stopScreenCaptureService() {
        val ctx = getApplication<Application>()
        ctx.startService(ScreenCaptureService.stopIntent(ctx))
    }

    fun toggleOverlayService() {
        val ctx = getApplication<Application>()
        if (AutomationBridge.isOverlayActive.value) {
            ctx.stopService(FloatingOverlayService.intent(ctx))
        } else if (Settings.canDrawOverlays(ctx)) {
            ctx.startService(FloatingOverlayService.intent(ctx))
        }
    }

    fun toggleDebugVisionEnabled() {
        AutomationBridge.toggleDebugVisionEnabled()
    }

    fun toggleAutoLoop() {
        AutomationBridge.toggleAutoLoop()
    }

    fun triggerSingleStep() {
        AutomationBridge.requestSingleStep()
    }

    fun setEyedropperActive(active: Boolean) {
        _isEyedropperActive.value = active
    }

    fun generateNextSyntheticFrame() {
        val widths = listOf(175f, 130f, 205f, 110f)
        val distances = listOf(0.68f, 0.92f, 0.48f, 0.82f)
        val idx = syntheticVariantIndex % widths.size
        syntheticVariantIndex++

        viewModelScope.launch(Dispatchers.Default) {
            val currentCfg = _config.value
            val bmp = BlockVisionAnalyzer.generateSyntheticTestFrame(
                width = 720,
                height = 1280,
                pawnColorRgb = currentCfg.pawnColorRgb,
                targetBlockWidthPx = widths[idx],
                distanceFactor = distances[idx]
            )
            val result = BlockVisionAnalyzer.analyze(bmp, currentCfg)
            withContext(Dispatchers.Main) {
                _sampleBitmap.value = bmp
                _sampleAnalysis.value = result
            }
        }
    }

    fun loadScreenshotFromUri(uri: Uri) {
        viewModelScope.launch(Dispatchers.IO) {
            val ctx = getApplication<Application>()
            val decoded = runCatching {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    val source = ImageDecoder.createSource(ctx.contentResolver, uri)
                    ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
                        decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                        val maxDim = 1440
                        val w = info.size.width
                        val h = info.size.height
                        if (h > maxDim) {
                            val ratio = maxDim.toFloat() / h.toFloat()
                            decoder.setTargetSize((w * ratio).roundToInt().coerceAtLeast(1), maxDim)
                        }
                    }
                } else {
                    ctx.contentResolver.openInputStream(uri)?.use { stream ->
                        BitmapFactory.decodeStream(stream)
                    }
                }
            }.getOrNull() ?: return@launch

            val argbBitmap = if (decoded.config != Bitmap.Config.ARGB_8888) {
                decoded.copy(Bitmap.Config.ARGB_8888, false).also { decoded.recycle() }
            } else {
                decoded
            }

            val result = BlockVisionAnalyzer.analyze(argbBitmap, _config.value)
            withContext(Dispatchers.Main) {
                _sampleBitmap.value = argbBitmap
                _sampleAnalysis.value = result
                AutomationBridge.appendLog("Loaded screenshot (${argbBitmap.width}x${argbBitmap.height}) for calibration")
            }
        }
    }

    fun samplePawnColorAtNormalized(normX: Float, normY: Float) {
        val bmp = _sampleBitmap.value ?: return
        val px = (normX.coerceIn(0f, 0.999f) * bmp.width).toInt()
        val py = (normY.coerceIn(0f, 0.999f) * bmp.height).toInt()
        val pixel = bmp.getPixel(px, py) and 0x00FFFFFF
        _isEyedropperActive.value = false
        updateConfig { it.copy(pawnColorRgb = pixel) }
        AutomationBridge.appendLog("Sampled pawn color #${Integer.toHexString(pixel).uppercase().padStart(6, '0')}")
    }

    fun updateFormulaMode(mode: FormulaMode) = updateConfig { it.copy(formulaMode = mode) }
    fun updateKFactor(value: Float) = updateConfig { it.copy(kFactor = value) }
    fun updateOffsetMs(value: Float) = updateConfig { it.copy(offsetMs = value) }
    fun updateQuadFactor(value: Float) = updateConfig { it.copy(quadFactor = value) }
    fun updatePawnTolerance(value: Int) = updateConfig { it.copy(pawnColorTolerance = value) }
    fun updateBgThreshold(value: Int) = updateConfig { it.copy(bgDiffThreshold = value) }
    fun updateRoiTop(value: Float) = updateConfig {
        val top = value.coerceIn(0.08f, (it.roiBottomFraction - 0.15f).coerceAtLeast(0.10f))
        it.copy(roiTopFraction = top)
    }

    fun updateRoiBottom(value: Float) = updateConfig {
        val bottom = value.coerceIn((it.roiTopFraction + 0.15f).coerceAtMost(0.92f), 0.94f)
        it.copy(roiBottomFraction = bottom)
    }

    fun updateJumpCooldown(valueMs: Long) = updateConfig { it.copy(jumpCooldownMs = valueMs) }
    fun updateTapX(value: Float) = updateConfig { it.copy(tapXFraction = value.coerceIn(0.1f, 0.9f)) }
    fun updateTapY(value: Float) = updateConfig { it.copy(tapYFraction = value.coerceIn(0.4f, 0.94f)) }
    fun updateAutoTrainEnabled(enabled: Boolean) {
        updateConfig { it.copy(autoTrainEnabled = enabled) }
        AutomationBridge.appendLog(if (enabled) "Self-Training enabled" else "Self-Training paused")
    }

    fun updateHumanizeTiming(enabled: Boolean) {
        updateConfig { it.copy(humanizeTiming = enabled) }
        AutomationBridge.appendLog(if (enabled) "Human-Like Jump Pacing enabled" else "Human-Like Jump Pacing disabled")
    }

    fun updateUltraPrecisionMode(enabled: Boolean) {
        updateConfig { it.copy(ultraPrecisionMode = enabled) }
        AutomationBridge.appendLog(
            if (enabled) "Ultra-Precision Mode (Slow) enabled — multi-frame + edge fitting"
            else "Ultra-Precision Mode disabled — fast cadence active"
        )
    }

    fun applyManualFeedback(isShort: Boolean) {
        if (AutomationBridge.isScreenCaptureActive.value) {
            AutomationBridge.requestManualFeedback(isShort)
            return
        }
        if (!AutomationBridge.isAccessibilityConnected.value && AutomationBridge.trainingRecords.value.isEmpty()) {
            AutomationBridge.appendLog("Ignored correction: bind Accessibility Service and run a jump first", isError = true)
            return
        }
        viewModelScope.launch {
            val cfg = _config.value
            val latest = AutomationBridge.trainingRecords.value.firstOrNull()
            val (updatedCfg, updatedRec, msg) = JumpTrainer.applyManualFeedback(cfg, latest, isShort)
            repository.updateConfig { updatedCfg }
            if (updatedRec != null) {
                AutomationBridge.replaceLatestTrainingRecord(updatedRec, msg)
                repository.saveTrainingHistory(AutomationBridge.trainingRecords.value)
            } else {
                AutomationBridge.updateTrainingBanner(msg)
            }
            AutomationBridge.appendLog(msg)
        }
    }

    fun buildTelemetryReport(): String {
        val (w, h) = AutomationBridge.screenDimensions.value
        return JumpTrainer.formatTelemetryReport(
            config = _config.value,
            records = AutomationBridge.trainingRecords.value,
            logs = AutomationBridge.logs.value,
            screenW = w,
            screenH = h
        )
    }

    fun clearTrainingHistory() {
        viewModelScope.launch {
            AutomationBridge.setTrainingRecords(emptyList())
            AutomationBridge.updateTrainingBanner(null)
            repository.clearTrainingHistory()
            AutomationBridge.appendLog("Cleared saved training history")
        }
    }

    fun logReportCopied() {
        val count = AutomationBridge.trainingRecords.value.size
        AutomationBridge.appendLog("Copied training report to clipboard ($count jumps)")
    }

    fun resetDefaults() {
        viewModelScope.launch {
            repository.resetDefaults()
            AutomationBridge.appendLog("Reset calibration parameters to defaults")
        }
    }

    private fun updateConfig(transform: (BotConfig) -> BotConfig) {
        viewModelScope.launch {
            repository.updateConfig(transform)
        }
    }

    private fun reanalyzeCurrentBitmap(cfg: BotConfig) {
        val bmp = _sampleBitmap.value ?: return
        viewModelScope.launch(Dispatchers.Default) {
            val result = BlockVisionAnalyzer.analyze(bmp, cfg)
            withContext(Dispatchers.Main) {
                _sampleAnalysis.value = result
            }
        }
    }
}
