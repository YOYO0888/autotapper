package com.frameworkstudios.autotapper

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

class OverlayService : Service() {

    private lateinit var windowManager: WindowManager
    private lateinit var panelView: View
    private lateinit var panelParams: WindowManager.LayoutParams

    private var scrollStartCrosshair: CrosshairView? = null
    private var scrollEndCrosshair: CrosshairView? = null
    private var tapCrosshair: CrosshairView? = null
    private var tapRadiusRing: RadiusRingView? = null

    private var scrollStart: Pair<Float, Float>? = null
    private var scrollEnd: Pair<Float, Float>? = null
    private var tapPoint: Pair<Float, Float>? = null

    private val overlayWindowType: Int
        get() = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else
            @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE

    private enum class PositioningMode { NONE, SCROLL, TAP }
    private var positioningMode = PositioningMode.NONE

    private val serviceScope = CoroutineScope(Dispatchers.Main)
    private var loopJob: Job? = null

    // Tunable timing/radius state
    private var loopIntervalMs = 1500L
    private var slideSpeedMs = 300L
    private var clickDelayMs = 600L               // increased from 300
    private var tapRadiusPx = 0
    private var tapCount = 1
    private var tapGapMs = 150L
    private var tapDurationMs = 80L               // new: tap press duration

    // Visual feedback overlay for tap position
    private var tapIndicator: View? = null
    private val handler = Handler(Looper.getMainLooper())

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        startForegroundWithNotification()
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        showPanel()
    }

    override fun onDestroy() {
        super.onDestroy()
        serviceScope.cancel()                     // fix leak
        loopJob?.cancel()
        removeCrosshairs()
        removeRadiusRing()
        removeTapIndicator()
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

        panelParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            overlayWindowType,
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

        val btnIntervalMinus = panelView.findViewById<Button>(R.id.btnIntervalMinus)
        val btnIntervalPlus = panelView.findViewById<Button>(R.id.btnIntervalPlus)
        val tvIntervalValue = panelView.findViewById<TextView>(R.id.tvIntervalValue)

        val btnSlideMinus = panelView.findViewById<Button>(R.id.btnSlideMinus)
        val btnSlidePlus = panelView.findViewById<Button>(R.id.btnSlidePlus)
        val tvSlideValue = panelView.findViewById<TextView>(R.id.tvSlideValue)

        val btnClickDelayMinus = panelView.findViewById<Button>(R.id.btnClickDelayMinus)
        val btnClickDelayPlus = panelView.findViewById<Button>(R.id.btnClickDelayPlus)
        val tvClickDelayValue = panelView.findViewById<TextView>(R.id.tvClickDelayValue)

        val btnRadiusMinus = panelView.findViewById<Button>(R.id.btnRadiusMinus)
        val btnRadiusPlus = panelView.findViewById<Button>(R.id.btnRadiusPlus)
        val tvRadiusValue = panelView.findViewById<TextView>(R.id.tvRadiusValue)

        val btnTapCountMinus = panelView.findViewById<Button>(R.id.btnTapCountMinus)
        val btnTapCountPlus = panelView.findViewById<Button>(R.id.btnTapCountPlus)
        val tvTapCountValue = panelView.findViewById<TextView>(R.id.tvTapCountValue)

        val btnTapGapMinus = panelView.findViewById<Button>(R.id.btnTapGapMinus)
        val btnTapGapPlus = panelView.findViewById<Button>(R.id.btnTapGapPlus)
        val tvTapGapValue = panelView.findViewById<TextView>(R.id.tvTapGapValue)

        val btnTapDurationMinus = panelView.findViewById<Button>(R.id.btnTapDurationMinus)
        val btnTapDurationPlus = panelView.findViewById<Button>(R.id.btnTapDurationPlus)
        val tvTapDurationValue = panelView.findViewById<TextView>(R.id.tvTapDurationValue)

        val editScrollJitter = panelView.findViewById<EditText>(R.id.editScrollJitter)

        tvIntervalValue.text = loopIntervalMs.toString()
        tvSlideValue.text = slideSpeedMs.toString()
        tvClickDelayValue.text = clickDelayMs.toString()
        tvRadiusValue.text = tapRadiusPx.toString()
        tvTapCountValue.text = tapCount.toString()
        tvTapGapValue.text = tapGapMs.toString()
        tvTapDurationValue.text = tapDurationMs.toString()

        btnIntervalMinus.setOnClickListener {
            loopIntervalMs = (loopIntervalMs - 250).coerceAtLeast(100)
            tvIntervalValue.text = loopIntervalMs.toString()
        }
        btnIntervalPlus.setOnClickListener {
            loopIntervalMs = (loopIntervalMs + 250).coerceAtMost(60000)
            tvIntervalValue.text = loopIntervalMs.toString()
        }

        btnSlideMinus.setOnClickListener {
            slideSpeedMs = (slideSpeedMs - 50).coerceAtLeast(50)
            tvSlideValue.text = slideSpeedMs.toString()
        }
        btnSlidePlus.setOnClickListener {
            slideSpeedMs = (slideSpeedMs + 50).coerceAtMost(5000)
            tvSlideValue.text = slideSpeedMs.toString()
        }

        btnClickDelayMinus.setOnClickListener {
            clickDelayMs = (clickDelayMs - 50).coerceAtLeast(0)
            tvClickDelayValue.text = clickDelayMs.toString()
        }
        btnClickDelayPlus.setOnClickListener {
            clickDelayMs = (clickDelayMs + 50).coerceAtMost(5000)
            tvClickDelayValue.text = clickDelayMs.toString()
        }

        btnRadiusMinus.setOnClickListener {
            tapRadiusPx = (tapRadiusPx - 10).coerceAtLeast(0)
            tvRadiusValue.text = tapRadiusPx.toString()
            if (positioningMode == PositioningMode.NONE) updateRadiusRing()
        }
        btnRadiusPlus.setOnClickListener {
            tapRadiusPx = (tapRadiusPx + 10).coerceAtMost(500)
            tvRadiusValue.text = tapRadiusPx.toString()
            if (positioningMode == PositioningMode.NONE) updateRadiusRing()
        }

        btnTapCountMinus.setOnClickListener {
            tapCount = (tapCount - 1).coerceAtLeast(1)
            tvTapCountValue.text = tapCount.toString()
        }
        btnTapCountPlus.setOnClickListener {
            tapCount = (tapCount + 1).coerceAtMost(5)
            tvTapCountValue.text = tapCount.toString()
        }

        btnTapGapMinus.setOnClickListener {
            tapGapMs = (tapGapMs - 50).coerceAtLeast(50)
            tvTapGapValue.text = tapGapMs.toString()
        }
        btnTapGapPlus.setOnClickListener {
            tapGapMs = (tapGapMs + 50).coerceAtMost(2000)
            tvTapGapValue.text = tapGapMs.toString()
        }

        btnTapDurationMinus.setOnClickListener {
            tapDurationMs = (tapDurationMs - 10).coerceAtLeast(30)
            tvTapDurationValue.text = tapDurationMs.toString()
        }
        btnTapDurationPlus.setOnClickListener {
            tapDurationMs = (tapDurationMs + 10).coerceAtMost(500)
            tvTapDurationValue.text = tapDurationMs.toString()
        }

        makeEditable(editScrollJitter)

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
            removeRadiusRing()
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
            val wasTap = positioningMode == PositioningMode.TAP
            positioningMode = PositioningMode.NONE
            removeCrosshairs()
            btnConfirm.visibility = View.GONE
            if (wasTap) updateRadiusRing()
            refreshStatus()
        }

        btnStart.setOnClickListener {
            val scrollJitter = editScrollJitter.text.toString().toIntOrNull()?.coerceAtLeast(0) ?: 0
            val start = scrollStart
            val end = scrollEnd
            val tap = tapPoint
            if (start == null || end == null || tap == null) return@setOnClickListener

            btnStart.isEnabled = false
            btnStop.isEnabled = true
            statusText.text = "Running…"

            loopJob = serviceScope.launch {
                var missingCycles = 0
                while (isActive) {
                    val service = AutoTapAccessibilityService.instance
                    if (service == null) {
                        missingCycles++
                        if (missingCycles >= 6) {
                            statusText.text =
                                "Accessibility service lost. Re-enable it in Settings, then tap Start again."
                            break
                        }
                        statusText.text = "Accessibility service reconnecting…"
                        delay(500)
                        continue
                    }
                    missingCycles = 0

                    try {
                        // 1. Scroll
                        val (sx, sy) = randomPointInRadius(start, scrollJitter)
                        val (ex, ey) = randomPointInRadius(end, scrollJitter)
                        service.performSwipe(sx, sy, ex, ey, durationMs = slideSpeedMs)

                        // 2. Wait for UI to settle
                        delay(clickDelayMs)

                        // 3. Then tap (repeated tapCount times)
                        for (i in 1..tapCount) {
                            val (tapX, tapY) = randomPointInRadius(tap, tapRadiusPx)
                            service.performTap(tapX, tapY, durationMs = tapDurationMs)
                            showTapIndicator(tapX, tapY)   // flash where we tapped
                            if (i < tapCount) delay(tapGapMs)
                        }

                        statusText.text = "Running…"
                    } catch (e: Exception) {
                        statusText.text = "Gesture error, retrying…"
                    }
                    val randomOffset = Random.nextLong(-250, 250)
                    delay((loopIntervalMs + randomOffset).coerceAtLeast(100))
                }
                btnStart.isEnabled = scrollStart != null && scrollEnd != null && tapPoint != null
                btnStop.isEnabled = false
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

    // ---------- Tap indicator flash ----------
    private fun showTapIndicator(x: Float, y: Float) {
        handler.post {
            removeTapIndicator()
            val indicator = View(this).apply {
                setBackgroundColor(0x80FF4081.toInt())
            }
            val size = 20
            val params = WindowManager.LayoutParams(
                size, size,
                overlayWindowType,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.TOP or Gravity.START
                this.x = (x - size / 2).toInt()
                this.y = (y - size / 2).toInt()
            }
            windowManager.addView(indicator, params)
            tapIndicator = indicator
            // remove after 200ms
            handler.postDelayed({ removeTapIndicator() }, 200)
        }
    }

    private fun removeTapIndicator() {
        tapIndicator?.let { runCatching { windowManager.removeView(it) } }
        tapIndicator = null
    }

    // ---------- Keyboard helpers ----------
    private fun makeEditable(editText: EditText) {
        editText.setOnClickListener {
            setPanelFocusable(true)
            editText.post {
                editText.requestFocus()
                val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
                imm.showSoftInput(editText, InputMethodManager.SHOW_IMPLICIT)
            }
        }
        editText.setOnFocusChangeListener { _, hasFocus ->
            if (!hasFocus) {
                val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
                imm.hideSoftInputFromWindow(editText.windowToken, 0)
                setPanelFocusable(false)
            }
        }
    }

    private fun setPanelFocusable(focusable: Boolean) {
        if (!::panelParams.isInitialized) return
        panelParams.flags = if (focusable) {
            panelParams.flags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE.inv()
        } else {
            panelParams.flags or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
        }
        runCatching { windowManager.updateViewLayout(panelView, panelParams) }
    }

    private fun crosshairCenter(view: CrosshairView): Pair<Float, Float> {
        val params = view.layoutParams as WindowManager.LayoutParams
        return Pair(
            params.x + view.width / 2f,
            params.y + view.height / 2f
        )
    }

    private fun randomPointInRadius(center: Pair<Float, Float>, radiusPx: Int): Pair<Float, Float> {
        if (radiusPx <= 0) return center
        val angle = Random.nextDouble(0.0, 2 * Math.PI)
        val r = radiusPx * sqrt(Random.nextDouble())
        val dx = (r * cos(angle)).toFloat()
        val dy = (r * sin(angle)).toFloat()
        return Pair(center.first + dx, center.second + dy)
    }

    // ---------- Radius ring ----------
    private fun updateRadiusRing() {
        val tap = tapPoint ?: run { removeRadiusRing(); return }
        if (tapRadiusPx <= 0) { removeRadiusRing(); return }
        val size = tapRadiusPx * 2
        val existing = tapRadiusRing
        if (existing == null) {
            val ring = RadiusRingView(this)
            val params = WindowManager.LayoutParams(
                size, size,
                overlayWindowType,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.TOP or Gravity.START
                x = (tap.first - tapRadiusPx).toInt()
                y = (tap.second - tapRadiusPx).toInt()
            }
            windowManager.addView(ring, params)
            tapRadiusRing = ring
        } else {
            val params = existing.layoutParams as WindowManager.LayoutParams
            params.width = size
            params.height = size
            params.x = (tap.first - tapRadiusPx).toInt()
            params.y = (tap.second - tapRadiusPx).toInt()
            windowManager.updateViewLayout(existing, params)
        }
    }

    private fun removeRadiusRing() {
        tapRadiusRing?.let { runCatching { windowManager.removeView(it) } }
        tapRadiusRing = null
    }

    // ---------- Drag ----------
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
        scrollStartCrosshair = addCrosshair(android.graphics.Color.parseColor("#00E676"), "START", 200, 900)
        scrollEndCrosshair = addCrosshair(android.graphics.Color.parseColor("#FFD600"), "END", 200, 400)
    }

    private fun addTapCrosshair() {
        tapCrosshair = addCrosshair(android.graphics.Color.parseColor("#FF4081"), "TAP", 400, 1200)
    }

    private fun addCrosshair(color: Int, label: String, initialX: Int, initialY: Int): CrosshairView {
        val size = 140
        val crosshair = CrosshairView(this, color, label)
        val params = WindowManager.LayoutParams(
            size, size, overlayWindowType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE, PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = initialX; y = initialY
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
        scrollStartCrosshair = null; scrollEndCrosshair = null; tapCrosshair = null
    }
}
