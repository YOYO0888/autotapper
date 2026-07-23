package com.frameworkstudios.autotapper

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.view.accessibility.AccessibilityEvent
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/**
 * Performs the actual on-screen gestures. Android requires an AccessibilityService
 * to inject gestures into apps other than your own, which is why this exists
 * instead of just simulating touches directly.
 */
class AutoTapAccessibilityService : AccessibilityService() {

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
    }

    override fun onDestroy() {
        super.onDestroy()
        instance = null
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // No event handling needed; this service is used purely to dispatch gestures.
    }

    override fun onInterrupt() {}

    /** Swipes from (x1,y1) to (x2,y2) over [durationMs]. Suspends until the gesture completes. */
    suspend fun performSwipe(x1: Float, y1: Float, x2: Float, y2: Float, durationMs: Long = 300) {
        val path = Path().apply {
            moveTo(x1, y1)
            lineTo(x2, y2)
        }
        dispatchAndAwait(path, durationMs)
    }

    /** Taps at (x,y). Suspends until the gesture completes. */
    suspend fun performTap(x: Float, y: Float, durationMs: Long = 60) {
        val path = Path().apply {
            moveTo(x, y)
        }
        dispatchAndAwait(path, durationMs)
    }

    private suspend fun dispatchAndAwait(path: Path, durationMs: Long) {
        suspendCancellableCoroutine<Unit> { cont ->
            val stroke = GestureDescription.StrokeDescription(path, 0, durationMs)
            val gesture = GestureDescription.Builder().addStroke(stroke).build()

            val callback = object : GestureResultCallback() {
                override fun onCompleted(gestureDescription: GestureDescription?) {
                    if (cont.isActive) cont.resume(Unit)
                }

                override fun onCancelled(gestureDescription: GestureDescription?) {
                    if (cont.isActive) cont.resume(Unit)
                }
            }

            val dispatched = dispatchGesture(gesture, callback, null)
            if (!dispatched && cont.isActive) {
                cont.resume(Unit)
            }
        }
    }

    companion object {
        var instance: AutoTapAccessibilityService? = null
            private set
    }
}
