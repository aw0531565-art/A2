package com.example.service

import android.annotation.SuppressLint
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Outline
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import android.util.DisplayMetrics
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewOutlineProvider
import android.view.WindowManager
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import com.example.R
import com.example.model.BotConfig
import com.example.model.VisionAnalysisResult
import com.example.model.VisionDebugStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt

class FloatingOverlayService : Service() {

    private var windowManager: WindowManager? = null
    private var debugOverlayView: VisionDebugCanvasView? = null
    private var rootView: FrameLayout? = null
    private lateinit var iconBubbleView: FrameLayout
    private lateinit var expandedHudView: LinearLayout
    private lateinit var bubbleRingDrawable: GradientDrawable
    private lateinit var bubbleDotDrawable: GradientDrawable
    private lateinit var telemetryText: TextView
    private lateinit var trainStatusText: TextView
    private lateinit var autoButton: Button
    private lateinit var eyeButton: TextView
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        createDebugVisionOverlay()
        createOverlayHud()
        observeBridgeState()
        AutomationBridge.setOverlayActive(true)
        AutomationBridge.appendLog("Floating icon badge launched (tap icon to open HUD)")
    }

    private fun overlayWindowType(): Int {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }
    }

    private fun createDebugVisionOverlay() {
        val view = VisionDebugCanvasView(this).apply {
            visibility = if (AutomationBridge.isDebugVisionEnabled.value) View.VISIBLE else View.GONE
        }

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            overlayWindowType(),
            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }

        debugOverlayView = view
        windowManager?.addView(view, params)
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun createOverlayHud() {
        val density = resources.displayMetrics.density
        fun dp(value: Int): Int = (value * density).roundToInt()

        val rootFrame = FrameLayout(this)

        // 1. Collapsed Floating Icon Bubble (52dp circular badge)
        bubbleRingDrawable = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(Color.argb(242, 11, 19, 32))
            setStroke(dp(3), Color.rgb(34, 211, 238))
        }

        val bubbleSize = dp(52)
        iconBubbleView = FrameLayout(this).apply {
            layoutParams = FrameLayout.LayoutParams(bubbleSize, bubbleSize)
            background = bubbleRingDrawable
            elevation = dp(6).toFloat()
            visibility = View.VISIBLE
        }

        val iconImage = ImageView(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            ).apply {
                val inset = dp(5)
                setMargins(inset, inset, inset, inset)
            }
            setImageResource(R.drawable.ic_launcher_art)
            scaleType = ImageView.ScaleType.CENTER_CROP
            contentDescription = "Open JumpAssist HUD"
            outlineProvider = object : ViewOutlineProvider() {
                override fun getOutline(view: View, outline: Outline) {
                    outline.setOval(0, 0, view.width, view.height)
                }
            }
            clipToOutline = true
        }

        bubbleDotDrawable = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(Color.rgb(16, 185, 129))
            setStroke(dp(2), Color.rgb(11, 19, 32))
        }

        val statusDot = View(this).apply {
            val dotSize = dp(13)
            layoutParams = FrameLayout.LayoutParams(dotSize, dotSize, Gravity.BOTTOM or Gravity.END).apply {
                setMargins(0, 0, dp(1), dp(1))
            }
            background = bubbleDotDrawable
        }

        iconBubbleView.addView(iconImage)
        iconBubbleView.addView(statusDot)

        // 2. Expanded JumpAssist HUD Popup
        val containerBg = GradientDrawable().apply {
            setColor(Color.argb(238, 11, 19, 32))
            cornerRadius = dp(18).toFloat()
            setStroke(dp(1), Color.rgb(34, 211, 238))
        }

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = containerBg
            setPadding(dp(12), dp(10), dp(12), dp(10))
            visibility = View.GONE
        }

        val headerRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        val titleView = TextView(this).apply {
            text = "JumpAssist HUD"
            setTextColor(Color.rgb(34, 211, 238))
            textSize = 12f
            typeface = Typeface.DEFAULT_BOLD
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }

        lateinit var publishBounds: () -> Unit

        eyeButton = TextView(this).apply {
            text = "👁"
            setTextColor(Color.rgb(34, 211, 238))
            textSize = 14f
            contentDescription = "Toggle on-screen bot vision overlay"
            setPadding(dp(8), dp(2), dp(8), dp(2))
            setOnClickListener {
                AutomationBridge.toggleDebugVisionEnabled()
            }
        }

        val minimizeBtn = TextView(this).apply {
            text = "—"
            setTextColor(Color.rgb(148, 163, 184))
            textSize = 15f
            typeface = Typeface.DEFAULT_BOLD
            contentDescription = "Minimize to floating icon"
            setPadding(dp(10), dp(2), dp(10), dp(2))
            setOnClickListener {
                container.visibility = View.GONE
                iconBubbleView.visibility = View.VISIBLE
                rootFrame.post { publishBounds() }
            }
        }

        val closeBtn = TextView(this).apply {
            text = "✕"
            setTextColor(Color.rgb(244, 63, 94))
            textSize = 14f
            typeface = Typeface.DEFAULT_BOLD
            contentDescription = "Close JumpAssist HUD"
            setPadding(dp(8), dp(2), dp(4), dp(2))
            setOnClickListener {
                stopSelf()
            }
        }

        headerRow.addView(titleView)
        headerRow.addView(eyeButton)
        headerRow.addView(minimizeBtn)
        headerRow.addView(closeBtn)

        telemetryText = TextView(this).apply {
            text = "D: -- px  |  Hold: -- ms"
            setTextColor(Color.rgb(241, 245, 249))
            textSize = 11f
            typeface = Typeface.MONOSPACE
            setPadding(0, dp(4), 0, dp(2))
        }

        trainStatusText = TextView(this).apply {
            text = "Train: READY"
            setTextColor(Color.rgb(16, 185, 129))
            textSize = 10f
            typeface = Typeface.MONOSPACE
            setPadding(0, 0, 0, dp(6))
        }

        val buttonRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        val stepBtn = createPillButton("Step", Color.rgb(29, 45, 70), Color.rgb(241, 245, 249), dp(8)).apply {
            setOnClickListener {
                if (ensureServicesReadyForTouch()) {
                    AutomationBridge.requestSingleStep()
                }
            }
        }

        autoButton = createPillButton("Auto", Color.rgb(16, 185, 129), Color.rgb(2, 38, 25), dp(8)).apply {
            setOnClickListener {
                if (AutomationBridge.isAutoLoopActive.value) {
                    AutomationBridge.setAutoLoopActive(false)
                } else if (ensureServicesReadyForTouch()) {
                    AutomationBridge.setAutoLoopActive(true)
                }
            }
        }

        val spacer = View(this).apply {
            layoutParams = LinearLayout.LayoutParams(dp(8), dp(1))
        }

        buttonRow.addView(stepBtn)
        buttonRow.addView(spacer)
        buttonRow.addView(autoButton)

        // Manual Self-Train nudge pills for immediate correction if user spots short/long jump
        val trainRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(6), 0, 0)
        }

        val shortBtn = createSmallFeedbackButton("◀ Too Short", Color.rgb(51, 35, 12), Color.rgb(251, 191, 36), dp(6)).apply {
            setOnClickListener {
                if (ensureServicesReadyForTouch()) {
                    AutomationBridge.requestManualFeedback(isShort = true)
                }
            }
        }

        val trainSpacer = View(this).apply {
            layoutParams = LinearLayout.LayoutParams(dp(6), dp(1))
        }

        val longBtn = createSmallFeedbackButton("Too Far ▶", Color.rgb(66, 20, 32), Color.rgb(251, 113, 133), dp(6)).apply {
            setOnClickListener {
                if (ensureServicesReadyForTouch()) {
                    AutomationBridge.requestManualFeedback(isShort = false)
                }
            }
        }

        trainRow.addView(shortBtn)
        trainRow.addView(trainSpacer)
        trainRow.addView(longBtn)

        container.addView(headerRow)
        container.addView(telemetryText)
        container.addView(trainStatusText)
        container.addView(buttonRow)
        container.addView(trainRow)

        expandedHudView = container
        rootFrame.addView(iconBubbleView)
        rootFrame.addView(container)

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            overlayWindowType(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = dp(14)
            y = dp(48)
        }

        publishBounds = {
            val activeView: View = if (iconBubbleView.visibility == View.VISIBLE) iconBubbleView else container
            val loc = IntArray(2)
            activeView.getLocationOnScreen(loc)
            if (activeView.width > 0 && activeView.height > 0) {
                AutomationBridge.setOverlayBounds(
                    Rect(loc[0], loc[1], loc[0] + activeView.width, loc[1] + activeView.height)
                )
            }
        }

        rootFrame.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
            publishBounds()
        }

        val touchSlop = ViewConfiguration.get(this).scaledTouchSlop

        var bubbleInitialX = 0
        var bubbleInitialY = 0
        var bubbleTouchX = 0f
        var bubbleTouchY = 0f
        var bubbleDragged = false

        iconBubbleView.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    bubbleInitialX = params.x
                    bubbleInitialY = params.y
                    bubbleTouchX = event.rawX
                    bubbleTouchY = event.rawY
                    bubbleDragged = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (event.rawX - bubbleTouchX).roundToInt()
                    val dy = (event.rawY - bubbleTouchY).roundToInt()
                    if (!bubbleDragged && (abs(dx) > touchSlop || abs(dy) > touchSlop)) {
                        bubbleDragged = true
                    }
                    if (bubbleDragged) {
                        params.x = bubbleInitialX + dx
                        params.y = bubbleInitialY + dy
                        windowManager?.updateViewLayout(rootFrame, params)
                        publishBounds()
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (!bubbleDragged) {
                        iconBubbleView.visibility = View.GONE
                        container.visibility = View.VISIBLE
                        rootFrame.post { publishBounds() }
                    }
                    true
                }
                else -> false
            }
        }

        var initialX = 0
        var initialY = 0
        var initialTouchX = 0f
        var initialTouchY = 0f

        headerRow.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = params.x
                    initialY = params.y
                    initialTouchX = event.rawX
                    initialTouchY = event.rawY
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    params.x = initialX + (event.rawX - initialTouchX).roundToInt()
                    params.y = initialY + (event.rawY - initialTouchY).roundToInt()
                    windowManager?.updateViewLayout(rootFrame, params)
                    publishBounds()
                    true
                }
                else -> false
            }
        }

        rootView = rootFrame
        windowManager?.addView(rootFrame, params)
    }

    private fun createPillButton(label: String, bgColor: Int, textColor: Int, horizontalPad: Int): Button {
        val drawable = GradientDrawable().apply {
            setColor(bgColor)
            cornerRadius = horizontalPad * 1.5f
        }
        return Button(this).apply {
            text = label
            setTextColor(textColor)
            textSize = 11f
            isAllCaps = false
            background = drawable
            setPadding(horizontalPad * 2, horizontalPad / 2, horizontalPad * 2, horizontalPad / 2)
            minHeight = (34 * resources.displayMetrics.density).roundToInt()
            minimumHeight = minHeight
            minWidth = (64 * resources.displayMetrics.density).roundToInt()
        }
    }

    private fun createSmallFeedbackButton(label: String, bgColor: Int, textColor: Int, horizontalPad: Int): Button {
        val drawable = GradientDrawable().apply {
            setColor(bgColor)
            cornerRadius = horizontalPad * 1.2f
        }
        return Button(this).apply {
            text = label
            setTextColor(textColor)
            textSize = 10f
            isAllCaps = false
            background = drawable
            setPadding(horizontalPad + 4, horizontalPad / 3, horizontalPad + 4, horizontalPad / 3)
            minHeight = (28 * resources.displayMetrics.density).roundToInt()
            minimumHeight = minHeight
            minWidth = (56 * resources.displayMetrics.density).roundToInt()
        }
    }

    private fun observeBridgeState() {
        val strokePx = (3 * resources.displayMetrics.density).roundToInt()

        serviceScope.launch {
            AutomationBridge.lastAnalysis.collect { analysis ->
                if (analysis != null) {
                    val d = analysis.rawDistancePx.roundToInt()
                    val t = analysis.computedDurationMs
                    val prof = analysis.targetBlockProfile.take(3)
                    val st = when (analysis.pawnStance) {
                        "SIDE_FRONT" -> "S+"
                        "SIDE_BACK" -> "S-"
                        "SIDE_EDGE" -> "SE"
                        "CENTER" -> "C"
                        else -> "?"
                    }
                    val bTag = if (analysis.appliedContextBiasMs != 0L) {
                        String.format(Locale.US, "%+d", analysis.appliedContextBiasMs)
                    } else ""
                    telemetryText.text = if (analysis.blockFound) {
                        "D:${d} [$prof|$st] → ${t}ms$bTag"
                    } else {
                        analysis.statusMessage.take(32)
                    }
                }
                debugOverlayView?.updateState(
                    analysis = analysis,
                    status = AutomationBridge.visionDebugStatus.value,
                    config = AutomationBridge.currentConfig.value
                )
            }
        }

        serviceScope.launch {
            combine(
                AutomationBridge.visionDebugStatus,
                AutomationBridge.currentConfig,
                AutomationBridge.isDebugVisionEnabled
            ) { status, config, enabled ->
                Triple(status, config, enabled)
            }.collect { (status, config, enabled) ->
                eyeButton.setTextColor(
                    if (enabled) Color.rgb(34, 211, 238) else Color.rgb(100, 116, 139)
                )
                eyeButton.alpha = if (enabled) 1f else 0.55f
                debugOverlayView?.visibility = if (enabled) View.VISIBLE else View.GONE
                if (enabled) {
                    debugOverlayView?.updateState(
                        analysis = AutomationBridge.lastAnalysis.value,
                        status = status,
                        config = config
                    )
                }
            }
        }

        serviceScope.launch {
            combine(
                AutomationBridge.lastTrainingBanner,
                AutomationBridge.isAccessibilityConnected,
                AutomationBridge.isScreenCaptureActive,
                AutomationBridge.isAutoLoopActive
            ) { banner, accessibilityConnected, captureActive, autoActive ->
                Quad(banner, accessibilityConnected, captureActive, autoActive)
            }.collect { (banner, accessibilityConnected, captureActive, autoActive) ->
                if (!accessibilityConnected) {
                    trainStatusText.text = "⚠ Touch OFF — Tap Auto to Re-enable"
                    trainStatusText.setTextColor(Color.rgb(251, 191, 36))
                    bubbleRingDrawable.setStroke(strokePx, Color.rgb(251, 191, 36))
                    bubbleDotDrawable.setColor(Color.rgb(251, 191, 36))
                } else if (!captureActive) {
                    trainStatusText.text = "⚠ Capture OFF — Tap Auto to Grant"
                    trainStatusText.setTextColor(Color.rgb(251, 191, 36))
                    bubbleRingDrawable.setStroke(strokePx, Color.rgb(251, 191, 36))
                    bubbleDotDrawable.setColor(Color.rgb(251, 191, 36))
                } else {
                    val cfg = AutomationBridge.currentConfig.value
                    trainStatusText.text = banner ?: String.format(
                        Locale.US,
                        "Train:%s · k=%.2f b=%.0f",
                        if (cfg.autoTrainEnabled) "ON" else "OFF",
                        cfg.kFactor,
                        cfg.offsetMs
                    )
                    trainStatusText.setTextColor(Color.rgb(16, 185, 129))
                    if (autoActive) {
                        bubbleRingDrawable.setStroke(strokePx, Color.rgb(245, 158, 11))
                        bubbleDotDrawable.setColor(Color.rgb(245, 158, 11))
                    } else {
                        bubbleRingDrawable.setStroke(strokePx, Color.rgb(34, 211, 238))
                        bubbleDotDrawable.setColor(Color.rgb(16, 185, 129))
                    }
                }
                debugOverlayView?.invalidate()
            }
        }

        serviceScope.launch {
            AutomationBridge.isAutoLoopActive.collect { active ->
                val bg = autoButton.background as? GradientDrawable
                if (active) {
                    autoButton.text = "Pause"
                    autoButton.setTextColor(Color.rgb(43, 24, 0))
                    bg?.setColor(Color.rgb(245, 158, 11))
                } else {
                    autoButton.text = "Auto"
                    autoButton.setTextColor(Color.rgb(2, 38, 25))
                    bg?.setColor(Color.rgb(16, 185, 129))
                }
            }
        }
    }

    private data class Quad<A, B, C, D>(val first: A, val second: B, val third: C, val fourth: D)

    private fun ensureServicesReadyForTouch(): Boolean {
        if (!AutomationBridge.isAccessibilityConnected.value) {
            Toast.makeText(
                this,
                "Turn JumpAssist Accessibility OFF → ON to enable touch!",
                Toast.LENGTH_LONG
            ).show()
            AutomationBridge.appendLog(
                "HUD: Accessibility unbound — opening Settings (toggle OFF → ON)",
                isError = true
            )
            runCatching {
                startActivity(
                    Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                )
            }
            return false
        }
        if (!AutomationBridge.isScreenCaptureActive.value) {
            Toast.makeText(
                this,
                "Grant Screen Capture in JumpAssist app first!",
                Toast.LENGTH_LONG
            ).show()
            AutomationBridge.appendLog(
                "HUD: Screen Capture inactive — opening JumpAssist",
                isError = true
            )
            runCatching {
                packageManager.getLaunchIntentForPackage(packageName)?.let { intent ->
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                    startActivity(intent)
                }
            }
            return false
        }
        return true
    }

    override fun onDestroy() {
        serviceScope.cancel()
        debugOverlayView?.let { view ->
            runCatching { windowManager?.removeView(view) }
        }
        debugOverlayView = null
        rootView?.let { view ->
            runCatching { windowManager?.removeView(view) }
        }
        rootView = null
        AutomationBridge.setOverlayBounds(null)
        AutomationBridge.setOverlayActive(false)
        AutomationBridge.appendLog("Floating HUD overlay closed")
        super.onDestroy()
    }

    /**
     * Pass-through full-screen transparent vision canvas.
     * Everything drawn inside the ROI uses thin hollow strokes / L-brackets with open interiors
     * so MediaProjection screen captures continue detecting game blocks cleanly without interference.
     * Diagnostic banner & ROI labels sit outside [roiTop..roiBottom].
     */
    private inner class VisionDebugCanvasView(context: Context) : View(context) {

        private val d = resources.displayMetrics.density
        private val screenLoc = IntArray(2)

        private var analysis: VisionAnalysisResult? = null
        private var debugStatus: VisionDebugStatus = VisionDebugStatus()
        private var config: BotConfig = BotConfig()

        private val roiLinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 1.4f * d
            color = Color.argb(145, 34, 211, 238)
            pathEffect = DashPathEffect(floatArrayOf(10f * d, 8f * d), 0f)
        }

        private val roiLabelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(190, 34, 211, 238)
            textSize = 9.5f * d
            typeface = Typeface.MONOSPACE
        }

        private val bannerBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
        }

        private val bannerStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 1.5f * d
        }

        private val bannerDotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
        }

        private val bannerTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(241, 245, 249)
            textSize = 11f * d
            typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
        }

        private val pawnRingPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 2.4f * d
            color = Color.rgb(16, 185, 129)
        }

        private val pawnCrossPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 1.5f * d
            color = Color.rgb(16, 185, 129)
        }

        private val sourceLinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 2.0f * d
            color = Color.argb(210, 34, 211, 238)
        }

        private val targetBracketPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 2.6f * d
            strokeCap = Paint.Cap.ROUND
            color = Color.rgb(245, 158, 11)
        }

        private val targetCenterDotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            color = Color.rgb(249, 115, 22)
        }

        private val trajectoryPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 2.0f * d
            color = Color.argb(215, 34, 211, 238)
            pathEffect = DashPathEffect(floatArrayOf(9f * d, 7f * d), 0f)
        }

        private val tagBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            color = Color.argb(205, 9, 16, 27)
        }

        private val tagTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = 10f * d
            typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
        }

        private val holdOuterPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 2.5f * d
            color = Color.argb(220, 34, 211, 238)
        }

        private val holdInnerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 3.0f * d
            color = Color.rgb(16, 185, 129)
        }

        private val dashedPath = Path()
        private val bannerRect = RectF()
        private val tagRect = RectF()

        fun updateState(
            analysis: VisionAnalysisResult?,
            status: VisionDebugStatus,
            config: BotConfig
        ) {
            this.analysis = analysis
            this.debugStatus = status
            this.config = config
            invalidate()
        }

        private fun resolveRealScreenSize(): Pair<Int, Int> {
            val wm = windowManager
            if (wm != null) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    val b = wm.currentWindowMetrics.bounds
                    if (b.width() > 0 && b.height() > 0) return b.width() to b.height()
                } else {
                    @Suppress("DEPRECATION")
                    val m = DisplayMetrics()
                    @Suppress("DEPRECATION")
                    wm.defaultDisplay.getRealMetrics(m)
                    if (m.widthPixels > 0 && m.heightPixels > 0) return m.widthPixels to m.heightPixels
                }
            }
            return AutomationBridge.screenDimensions.value
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            if (width <= 0 || height <= 0) return

            getLocationOnScreen(screenLoc)
            val (realW, realH) = resolveRealScreenSize()
            val curAnalysis = analysis
            val imgW = (curAnalysis?.imageWidth ?: realW).coerceAtLeast(1).toFloat()
            val imgH = (curAnalysis?.imageHeight ?: realH).coerceAtLeast(1).toFloat()
            val sx = realW.toFloat() / imgW
            val sy = realH.toFloat() / imgH

            fun mx(x: Float): Float = x * sx - screenLoc[0]
            fun my(y: Float): Float = y * sy - screenLoc[1]

            // 1. ROI boundary dashed lines drawn 3px OUTSIDE [roiTopY..roiBottomY]
            val roiTopRaw = curAnalysis?.roiTopY ?: (imgH * config.roiTopFraction)
            val roiBotRaw = curAnalysis?.roiBottomY ?: (imgH * config.roiBottomFraction)
            val roiTopViewY = (my(roiTopRaw) - 3f).coerceIn(4f, height - 40f)
            val roiBotViewY = (my(roiBotRaw) + 3f).coerceIn(roiTopViewY + 20f, height - 10f)

            dashedPath.reset()
            dashedPath.moveTo(0f, roiTopViewY)
            dashedPath.lineTo(width.toFloat(), roiTopViewY)
            canvas.drawPath(dashedPath, roiLinePaint)

            dashedPath.reset()
            dashedPath.moveTo(0f, roiBotViewY)
            dashedPath.lineTo(width.toFloat(), roiBotViewY)
            canvas.drawPath(dashedPath, roiLinePaint)

            canvas.drawText("ROI TOP", 10f * d, roiTopViewY - 5f * d, roiLabelPaint)
            canvas.drawText("ROI BOT", 10f * d, roiBotViewY + 13f * d, roiLabelPaint)

            // 2. Top live diagnostic / stop-reason banner strictly ABOVE ROI
            drawStatusBanner(canvas, roiTopViewY)

            // 3. Pawn circle + stance + Source Platform center line
            if (curAnalysis != null && curAnalysis.pawnFound) {
                val px = mx(curAnalysis.pawnCenterX)
                val py = my(curAnalysis.pawnBaseY)

                if (curAnalysis.sourceWidthPx > 10f) {
                    val sl = mx(curAnalysis.sourceLeftX)
                    val sr = mx(curAnalysis.sourceRightX)
                    val scx = mx(curAnalysis.sourceCenterX)
                    val scy = my(curAnalysis.sourceCenterY)
                    canvas.drawLine(sl, scy, sr, scy, sourceLinePaint)
                    canvas.drawLine(sl, scy - 5f * d, sl, scy + 5f * d, sourceLinePaint)
                    canvas.drawLine(sr, scy - 5f * d, sr, scy + 5f * d, sourceLinePaint)
                    canvas.drawCircle(scx, scy, 4f * d, sourceLinePaint)
                }

                val rPawn = 10f * d
                canvas.drawCircle(px, py, rPawn, pawnRingPaint)
                canvas.drawLine(px - 5f * d, py, px + 5f * d, py, pawnCrossPaint)
                canvas.drawLine(px, py - 5f * d, px, py + 5f * d, pawnCrossPaint)

                val stanceShort = when (curAnalysis.pawnStance) {
                    "SIDE_FRONT" -> "S+"
                    "SIDE_BACK" -> "S-"
                    "SIDE_EDGE" -> "SE"
                    "CENTER" -> "C"
                    else -> "?"
                }
                drawCompactTag(
                    canvas = canvas,
                    text = "P:$stanceShort",
                    anchorX = px - 18f * d,
                    anchorY = py + rPawn + 16f * d,
                    textColor = Color.rgb(52, 211, 153)
                )

                // 4. Target block hollow L-corner brackets + dashed jump trajectory
                val blk = curAnalysis.blockBounds
                if (debugStatus.showTargetHighlight && curAnalysis.blockFound && blk != null) {
                    val l = mx(blk.leftEdgeX) - 3f * d
                    val r = mx(blk.rightEdgeX) + 3f * d
                    val t = my(blk.topY) - 3f * d
                    val b = my(blk.bottomY) + 3f * d
                    val cx = mx(blk.center.x)
                    val cy = my(blk.center.y)
                    val leg = 13f * d

                    // Top-left L
                    canvas.drawLine(l, t, l + leg, t, targetBracketPaint)
                    canvas.drawLine(l, t, l, t + leg, targetBracketPaint)
                    // Top-right L
                    canvas.drawLine(r - leg, t, r, t, targetBracketPaint)
                    canvas.drawLine(r, t, r, t + leg, targetBracketPaint)
                    // Bottom-left L
                    canvas.drawLine(l, b - leg, l, b, targetBracketPaint)
                    canvas.drawLine(l, b, l + leg, b, targetBracketPaint)
                    // Bottom-right L
                    canvas.drawLine(r - leg, b, r, b, targetBracketPaint)
                    canvas.drawLine(r, b - leg, r, b, targetBracketPaint)

                    // Center bullseye
                    canvas.drawCircle(cx, cy, 8f * d, targetBracketPaint)
                    canvas.drawCircle(cx, cy, 3f * d, targetCenterDotPaint)

                    // Dashed vector line
                    dashedPath.reset()
                    dashedPath.moveTo(px, py)
                    dashedPath.lineTo(cx, cy)
                    canvas.drawPath(dashedPath, trajectoryPaint)

                    val prof = curAnalysis.targetBlockProfile.take(3)
                    val grp = curAnalysis.targetColorGroup.ifBlank { "STD" }.take(4)
                    val dVal = curAnalysis.rawDistancePx.roundToInt()
                    val biasStr = if (curAnalysis.appliedContextBiasMs != 0L) {
                        String.format(Locale.US, "%+d", curAnalysis.appliedContextBiasMs)
                    } else ""
                    val labelY = max(roiTopViewY + 14f * d, t - 6f * d)
                    drawCompactTag(
                        canvas = canvas,
                        text = "$prof/$grp W=${blk.widthPx.roundToInt()} D=$dVal ${curAnalysis.computedDurationMs}ms$biasStr",
                        anchorX = l.coerceIn(8f * d, width - 170f * d),
                        anchorY = labelY,
                        textColor = Color.rgb(251, 191, 36)
                    )
                }
            }

            // 5. Touch hold pulse ring (at tap target near bottom of screen, well below ROI)
            if (debugStatus.isHolding && debugStatus.holdingTapX > 0f && debugStatus.holdingTapY > 0f) {
                val tx = mx(debugStatus.holdingTapX)
                val ty = my(debugStatus.holdingTapY)
                canvas.drawCircle(tx, ty, 32f * d, holdOuterPaint)
                canvas.drawCircle(tx, ty, 19f * d, holdInnerPaint)
                drawCompactTag(
                    canvas = canvas,
                    text = "HOLDING",
                    anchorX = tx - 26f * d,
                    anchorY = ty - 38f * d,
                    textColor = Color.rgb(34, 211, 238)
                )
            }
        }

        private fun drawStatusBanner(canvas: Canvas, roiTopViewY: Float) {
            val accReady = AutomationBridge.isAccessibilityConnected.value
            val capReady = AutomationBridge.isScreenCaptureActive.value
            val autoOn = AutomationBridge.isAutoLoopActive.value

            val (phase, rawText) = when {
                !accReady -> "TOUCH_OFF" to "Touch OFF — Open Settings & toggle Accessibility ON"
                !capReady -> "NO_FRAME" to "Capture OFF — Grant Screen Capture in JumpAssist"
                debugStatus.phase == "IDLE" && !autoOn -> "IDLE" to "Vision Ready — Press Step or Auto on HUD"
                else -> debugStatus.phase to debugStatus.message
            }

            val (strokeCol, bgCol, dotCol) = when (phase) {
                "LOCKED" -> Triple(Color.rgb(16, 185, 129), Color.argb(215, 7, 29, 23), Color.rgb(52, 211, 153))
                "WAITING_STABILITY" -> Triple(Color.rgb(245, 158, 11), Color.argb(222, 42, 26, 6), Color.rgb(251, 191, 36))
                "HOLDING" -> Triple(Color.rgb(34, 211, 238), Color.argb(225, 7, 32, 46), Color.rgb(34, 211, 238))
                "SETTLING" -> Triple(Color.rgb(96, 165, 250), Color.argb(215, 11, 21, 40), Color.rgb(96, 165, 250))
                "NO_BOARD", "NO_FRAME", "TOUCH_OFF", "ERROR" ->
                    Triple(Color.rgb(244, 63, 94), Color.argb(232, 48, 12, 22), Color.rgb(251, 113, 133))
                else -> Triple(Color.rgb(56, 189, 248), Color.argb(210, 11, 19, 32), Color.rgb(56, 189, 248))
            }

            val maxBannerW = width - 24f * d
            val prefix = if (autoOn) "AUTO · " else ""
            var msg = prefix + rawText
            while (msg.length > 18 && bannerTextPaint.measureText(msg) > maxBannerW - 36f * d) {
                msg = msg.dropLast(4) + "..."
            }

            val textW = bannerTextPaint.measureText(msg)
            val pillW = (textW + 34f * d).coerceAtMost(maxBannerW)
            val pillH = 26f * d
            val pillLeft = ((width - pillW) / 2f).coerceAtLeast(12f * d)
            // Always position above ROI TOP so it stays in empty sky and outside analysis band
            val pillBottom = (roiTopViewY - 20f * d).coerceAtLeast(36f * d + pillH)
            val pillTop = pillBottom - pillH

            bannerRect.set(pillLeft, pillTop, pillLeft + pillW, pillBottom)
            bannerBgPaint.color = bgCol
            bannerStrokePaint.color = strokeCol
            bannerDotPaint.color = dotCol

            canvas.drawRoundRect(bannerRect, 13f * d, 13f * d, bannerBgPaint)
            canvas.drawRoundRect(bannerRect, 13f * d, 13f * d, bannerStrokePaint)
            canvas.drawCircle(pillLeft + 14f * d, pillTop + pillH / 2f, 4f * d, bannerDotPaint)
            canvas.drawText(msg, pillLeft + 24f * d, pillTop + pillH / 2f + 4f * d, bannerTextPaint)
        }

        private fun drawCompactTag(
            canvas: Canvas,
            text: String,
            anchorX: Float,
            anchorY: Float,
            textColor: Int
        ) {
            val tw = tagTextPaint.measureText(text)
            val padH = 5f * d
            val boxH = 15f * d
            val left = anchorX.coerceIn(6f * d, (width - tw - padH * 2 - 6f * d).coerceAtLeast(6f * d))
            val bottom = anchorY.coerceIn(boxH + 4f * d, height - 4f * d)
            val top = bottom - boxH
            tagRect.set(left, top, left + tw + padH * 2, bottom)
            canvas.drawRoundRect(tagRect, 4f * d, 4f * d, tagBgPaint)
            tagTextPaint.color = textColor
            canvas.drawText(text, left + padH, bottom - 3.5f * d, tagTextPaint)
        }
    }

    companion object {
        fun intent(context: Context): Intent = Intent(context, FloatingOverlayService::class.java)
    }
}
