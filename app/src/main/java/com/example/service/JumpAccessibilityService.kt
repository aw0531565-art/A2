package com.example.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Intent
import android.graphics.Path
import android.os.Handler
import android.os.Looper
import android.view.accessibility.AccessibilityEvent
import java.util.concurrent.atomic.AtomicBoolean

class JumpAccessibilityService : AccessibilityService() {

    private val mainHandler = Handler(Looper.getMainLooper())

    override fun onServiceConnected() {
        super.onServiceConnected()
        AutomationBridge.setAccessibilityService(this)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // Frame vision uses MediaProjection; accessibility event stream is intentionally minimal.
    }

    override fun onInterrupt() {
        AutomationBridge.setAutoLoopActive(false)
    }

    override fun onUnbind(intent: Intent?): Boolean {
        AutomationBridge.setAccessibilityService(null)
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        AutomationBridge.setAccessibilityService(null)
        super.onDestroy()
    }

    fun performHoldGesture(
        x: Float,
        y: Float,
        durationMs: Long,
        onComplete: (Boolean) -> Unit
    ) {
        val clampedDuration = durationMs.coerceIn(50L, 4000L)
        val finished = AtomicBoolean(false)
        val notifyOnce: (Boolean) -> Unit = { ok ->
            if (finished.compareAndSet(false, true)) {
                onComplete(ok)
            }
        }

        mainHandler.post {
            val path = Path().apply {
                moveTo(x, y)
                lineTo(x + 0.5f, y + 0.5f)
            }
            val stroke = GestureDescription.StrokeDescription(path, 0L, clampedDuration)
            val gesture = GestureDescription.Builder()
                .addStroke(stroke)
                .build()

            val dispatched = runCatching {
                dispatchGesture(
                    gesture,
                    object : GestureResultCallback() {
                        override fun onCompleted(gestureDescription: GestureDescription?) {
                            notifyOnce(true)
                        }

                        override fun onCancelled(gestureDescription: GestureDescription?) {
                            notifyOnce(false)
                        }
                    },
                    mainHandler
                )
            }.getOrDefault(false)

            if (!dispatched) {
                notifyOnce(false)
            }
        }
    }
}
