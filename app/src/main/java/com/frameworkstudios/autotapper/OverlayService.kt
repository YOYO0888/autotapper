package com.frameworkstudios.autotapper

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class OverlayService : Service() {

    private lateinit var windowManager: WindowManager
    private lateinit var panelView: View
    private lateinit var panelParams: WindowManager.LayoutParams

    private var scrollStartCrosshair: CrosshairView? = null
    private var scrollEndCrosshair: CrosshairView? = null
    private var tapCrosshair: CrosshairView? = null

    // Saved gesture coordinates, in raw screen pixels.
    private var scrollStart: Pair<Float, Float>? = null
    private var scrollEnd: Pair<Float, Float>? = null
    private var tapPoint: Pair<Float, Float>? = null

    private enum class PositioningMode { NONE, SCROLL, TAP }
    private var positioningMode = PositioningMode.NONE

    private val serviceScope = CoroutineScope(Dispatchers.Main)
    private var loopJob: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        startForegroundWithNotification()
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        showPanel()
    }

    override fun onDestroy() {
        super.onDestroy()
        loopJob?.cancel()
        removeCrosshairs()
        if (::panelView.isInitialized) {
            runCatching { windowManager.removeView(panelView) }
        }
    }

    private fun startForegroundWithNotification() {
        val channelId = "autotapper_channel"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                channelId, "AutoTapper", NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
        val notification = NotificationCompat.Builder(this, channelId)
            .setContentTitle("AutoTapper running")
            .setContentText("Floating controls are active.")
            .setSmallIcon(android.R.drawable.ic_menu_myplaces)
            .setOngoing(true)
            .build()
        startForeground(1, notification)
    }

    // ---------- Control panel ----------

    private fun showPanel() {
        panelView = LayoutInflater.from(this).inflate(R.layout.overlay_panel, null)

        val overlayType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else
            @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE

        panelParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            overlayType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 40
            y = 200
        }

        windowManager.addView(panelView, panelParams)

        setupDrag()

        val statusText = panelView.findViewById<TextView>(R.id.statusText)
        val btnSetScroll = panelView.findViewById<Button>(R.id.btnSetScroll)
        val btnSetTap = panelView.findViewById<Button>(R.id.btnSetTap)
        val btnConfirm = panelView.findViewById<Button>(R.id.btnConfirmPositions)
        val btnStart = panelView.findViewById<Button>(R.id.btnStart)
        val btnStop = panelView.findViewById<Button>(R.id.btnStop)
        val btnClose = panelView.findViewById<TextView>(R.id.btnClose)
        val editInterval = panelView.findViewById<EditText>(R.id.editInterval)

        fun refreshStatus() {
            val parts = mutableListOf<String>()
            parts.add(if (scrollStart != null && scrollEnd != null) "Scroll ✓" else "Scroll not set")
            parts.add(if (tapPoint != null) "Tap ✓" else "Tap not set")
            statusText.text = parts.joinToString("  •  ")
            btnStart.isEnabled = scrollStart != null && scrollEnd != null && tapPoint != null &&
                loopJob?.isActive != true
        }

        btnSetScroll.setOnClickListener {
            positioningMode = PositioningMode.SCROLL
            removeCrosshairs()
            addScrollCrosshairs()
            btnConfirm.visibility = View.VISIBLE
            statusText.text = "Drag green (start) & yellow (end) points, then Confirm."
        }

        btnSetTap.setOnClickListener {
            positioningMode = PositioningMode.TAP
            removeCrosshairs()
            addTapCrosshair()
            btnConfirm.visibility = View.VISIBLE
            statusText.text = "Drag the pink point onto the button, then Confirm."
        }

        btnConfirm.setOnClickListener {
            when (positioningMode) {
                PositioningMode.SCROLL -> {
                    scrollStart = scrollStartCrosshair?.let { crosshairCenter(it) }
                    scrollEnd = scrollEndCrosshair?.let { crosshairCenter(it) }
                }
                PositioningMode.TAP -> {
                    tapPoint = tapCrosshair?.let { crosshairCenter(it) }
                }
                PositioningMode.NONE -> {}
            }
            positioningMode = PositioningMode.NONE
            removeCrosshairs()
            btnConfirm.visibility = View.GONE
            refreshStatus()
        }

        btnStart.setOnClickListener {
            val interval = editInterval.text.toString().toLongOrNull() ?: 1500L
            val start = scrollStart
            val end = scrollEnd
            val tap = tapPoint
            if (start == null || end == null || tap == null) return@setOnClickListener

            btnStart.isEnabled = false
            btnStop.isEnabled = true
            statusText.text = "Running…"

            loopJob = serviceScope.launch {
                val service = AutoTapAccessibilityService.instance
                if (service == null) {
                    statusText.text = "Accessibility service not connected."
                    btnStart.isEnabled = true
                    btnStop.isEnabled = false
                    return@launch
                }
                while (isActive) {
                    service.performSwipe(start.first, start.second, end.first, end.second)
                    delay(250)
                    service.performTap(tap.first, tap.second)
                    delay(interval)
                }
            }
        }

        btnStop.setOnClickListener {
            loopJob?.cancel()
            loopJob = null
            btnStart.isEnabled = scrollStart != null && scrollEnd != null && tapPoint != null
            btnStop.isEnabled = false
            statusText.text = "Stopped."
        }

        btnClose.setOnClickListener {
            stopSelf()
        }

        refreshStatus()
    }

    private fun crosshairCenter(view: CrosshairView): Pair<Float, Float> {
        val params = view.layoutParams as WindowManager.LayoutParams
        return Pair(
            params.x + view.width / 2f,
            params.y + view.height / 2f
        )
    }

    private fun setupDrag() {
        val dragHandle = panelView.findViewById<TextView>(R.id.dragHandle)
        var initialX = 0
        var initialY = 0
        var initialTouchX = 0f
        var initialTouchY = 0f

        dragHandle.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = panelParams.x
                    initialY = panelParams.y
                    initialTouchX = event.rawX
                    initialTouchY = event.rawY
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    panelParams.x = initialX + (event.rawX - initialTouchX).toInt()
                    panelParams.y = initialY + (event.rawY - initialTouchY).toInt()
                    windowManager.updateViewLayout(panelView, panelParams)
                    true
                }
                else -> false
            }
        }
    }

    // ---------- Crosshairs ----------

    private fun addScrollCrosshairs() {
        scrollStartCrosshair = addCrosshair(
            android.graphics.Color.parseColor("#00E676"), "START",
            initialX = 200, initialY = 900
        )
        scrollEndCrosshair = addCrosshair(
            android.graphics.Color.parseColor("#FFD600"), "END",
            initialX = 200, initialY = 400
        )
    }

    private fun addTapCrosshair() {
        tapCrosshair = addCrosshair(
            android.graphics.Color.parseColor("#FF4081"), "TAP",
            initialX = 400, initialY = 1200
        )
    }

    private fun addCrosshair(color: Int, label: String, initialX: Int, initialY: Int): CrosshairView {
        val size = 140
        val crosshair = CrosshairView(this, color, label)
        val params = WindowManager.LayoutParams(
            size, size,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else
                @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = initialX
            y = initialY
        }

        crosshair.onMoved = { rawX, rawY ->
            params.x = (rawX - size / 2f).toInt()
            params.y = (rawY - size / 2f).toInt()
            windowManager.updateViewLayout(crosshair, params)
        }

        windowManager.addView(crosshair, params)
        return crosshair
    }

    private fun removeCrosshairs() {
        scrollStartCrosshair?.let { runCatching { windowManager.removeView(it) } }
        scrollEndCrosshair?.let { runCatching { windowManager.removeView(it) } }
        tapCrosshair?.let { runCatching { windowManager.removeView(it) } }
        scrollStartCrosshair = null
        scrollEndCrosshair = null
        tapCrosshair = null
    }
}
