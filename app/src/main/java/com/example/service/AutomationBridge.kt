package com.example.service

import android.graphics.Rect
import com.example.model.BotConfig
import com.example.model.BotLogEntry
import com.example.model.JumpTelemetryRecord
import com.example.model.VisionAnalysisResult
import com.example.model.VisionDebugStatus
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.lang.ref.WeakReference
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicLong

object AutomationBridge {

    private var accessibilityServiceRef: WeakReference<JumpAccessibilityService>? = null
    private val logIdGen = AtomicLong(1L)
    private val recordIdGen = AtomicLong(1L)
    private val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.US)

    private val _isAccessibilityConnected = MutableStateFlow(false)
    val isAccessibilityConnected: StateFlow<Boolean> = _isAccessibilityConnected.asStateFlow()

    private val _isScreenCaptureActive = MutableStateFlow(false)
    val isScreenCaptureActive: StateFlow<Boolean> = _isScreenCaptureActive.asStateFlow()

    private val _isOverlayActive = MutableStateFlow(false)
    val isOverlayActive: StateFlow<Boolean> = _isOverlayActive.asStateFlow()

    private val _overlayBounds = MutableStateFlow<Rect?>(null)
    val overlayBounds: StateFlow<Rect?> = _overlayBounds.asStateFlow()

    private val _isAutoLoopActive = MutableStateFlow(false)
    val isAutoLoopActive: StateFlow<Boolean> = _isAutoLoopActive.asStateFlow()

    private val _currentConfig = MutableStateFlow(BotConfig())
    val currentConfig: StateFlow<BotConfig> = _currentConfig.asStateFlow()

    private val _lastAnalysis = MutableStateFlow<VisionAnalysisResult?>(null)
    val lastAnalysis: StateFlow<VisionAnalysisResult?> = _lastAnalysis.asStateFlow()

    private val _visionDebugStatus = MutableStateFlow(VisionDebugStatus())
    val visionDebugStatus: StateFlow<VisionDebugStatus> = _visionDebugStatus.asStateFlow()

    private val _isDebugVisionEnabled = MutableStateFlow(true)
    val isDebugVisionEnabled: StateFlow<Boolean> = _isDebugVisionEnabled.asStateFlow()

    private val _logs = MutableStateFlow<List<BotLogEntry>>(emptyList())
    val logs: StateFlow<List<BotLogEntry>> = _logs.asStateFlow()

    private val _trainingRecords = MutableStateFlow<List<JumpTelemetryRecord>>(emptyList())
    val trainingRecords: StateFlow<List<JumpTelemetryRecord>> = _trainingRecords.asStateFlow()

    private val _lastTrainingBanner = MutableStateFlow<String?>(null)
    val lastTrainingBanner: StateFlow<String?> = _lastTrainingBanner.asStateFlow()

    private val _screenDimensions = MutableStateFlow(720 to 1600)
    val screenDimensions: StateFlow<Pair<Int, Int>> = _screenDimensions.asStateFlow()

    private val _singleStepRequests = MutableSharedFlow<Unit>(extraBufferCapacity = 4)
    val singleStepRequests: SharedFlow<Unit> = _singleStepRequests.asSharedFlow()

    // true = user says last jump was too SHORT (+k); false = user says last jump was too LONG (-k)
    private val _manualFeedbackRequests = MutableSharedFlow<Boolean>(extraBufferCapacity = 4)
    val manualFeedbackRequests: SharedFlow<Boolean> = _manualFeedbackRequests.asSharedFlow()

    fun setAccessibilityService(service: JumpAccessibilityService?) {
        accessibilityServiceRef = service?.let { WeakReference(it) }
        _isAccessibilityConnected.value = service != null
        if (service != null) {
            appendLog("Accessibility gesture service connected")
        } else {
            _isAutoLoopActive.value = false
            appendLog("Accessibility gesture service disconnected", isError = true)
        }
    }

    fun setScreenCaptureActive(active: Boolean) {
        _isScreenCaptureActive.value = active
        if (!active) {
            _isAutoLoopActive.value = false
        }
    }

    fun setScreenDimensions(width: Int, height: Int) {
        _screenDimensions.value = width to height
    }

    fun setOverlayActive(active: Boolean) {
        _isOverlayActive.value = active
        if (!active) {
            _overlayBounds.value = null
        }
    }

    fun setOverlayBounds(rect: Rect?) {
        _overlayBounds.value = rect
    }

    fun setAutoLoopActive(active: Boolean) {
        _isAutoLoopActive.value = active
        appendLog(if (active) "Auto-jump loop started" else "Auto-jump loop paused")
    }

    fun toggleAutoLoop() {
        setAutoLoopActive(!_isAutoLoopActive.value)
    }

    fun requestSingleStep() {
        _singleStepRequests.tryEmit(Unit)
    }

    fun requestManualFeedback(isShort: Boolean) {
        _manualFeedbackRequests.tryEmit(isShort)
    }

    fun updateConfig(config: BotConfig) {
        _currentConfig.value = config
    }

    fun updateAnalysis(result: VisionAnalysisResult) {
        _lastAnalysis.value = result
    }

    fun updateVisionDebugStatus(status: VisionDebugStatus) {
        _visionDebugStatus.value = status
    }

    fun setDebugVisionEnabled(enabled: Boolean) {
        _isDebugVisionEnabled.value = enabled
        appendLog(if (enabled) "On-screen bot vision overlay ON" else "On-screen bot vision overlay OFF")
    }

    fun toggleDebugVisionEnabled() {
        setDebugVisionEnabled(!_isDebugVisionEnabled.value)
    }

    fun nextRecordId(): Long = recordIdGen.getAndIncrement()
    fun currentTimeStr(): String = timeFormat.format(Date())

    fun setTrainingRecords(records: List<JumpTelemetryRecord>) {
        val maxId = records.maxOfOrNull { it.id } ?: 0L
        if (maxId >= recordIdGen.get()) {
            recordIdGen.set(maxId + 1L)
        }
        _trainingRecords.value = records
    }

    fun prependTrainingRecord(record: JumpTelemetryRecord, banner: String? = null) {
        _trainingRecords.update { current ->
            (listOf(record) + current).take(60)
        }
        if (banner != null) {
            _lastTrainingBanner.value = banner
        }
    }

    fun replaceLatestTrainingRecord(updated: JumpTelemetryRecord, banner: String? = null) {
        _trainingRecords.update { current ->
            if (current.isEmpty()) listOf(updated)
            else listOf(updated) + current.drop(1)
        }
        if (banner != null) {
            _lastTrainingBanner.value = banner
        }
    }

    fun updateTrainingBanner(banner: String?) {
        _lastTrainingBanner.value = banner
    }

    fun performHoldGesture(
        x: Float,
        y: Float,
        durationMs: Long,
        onComplete: (Boolean) -> Unit
    ) {
        val service = accessibilityServiceRef?.get()
        if (service == null) {
            appendLog("Cannot dispatch touch: Accessibility Service disabled", isError = true)
            onComplete(false)
            return
        }
        service.performHoldGesture(x, y, durationMs, onComplete)
    }

    fun appendLog(message: String, isError: Boolean = false) {
        val entry = BotLogEntry(
            id = logIdGen.getAndIncrement(),
            timestamp = timeFormat.format(Date()),
            message = message,
            isError = isError
        )
        _logs.update { current ->
            (listOf(entry) + current).take(40)
        }
    }
}
