package com.frameworkstudios.autotapper

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.os.VibrationEffect
import android.os.Vibrator
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONObject
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

class OverlayService : Service() {

    private lateinit var windowManager: WindowManager
    private lateinit var panelView: View
    private lateinit var panelParams: WindowManager.LayoutParams
    private lateinit var store: ProfileStore

    private lateinit var cardContainer: View
    private lateinit var bubbleView: TextView

    private var scrollStartCrosshair: CrosshairView? = null
    private var scrollEndCrosshair: CrosshairView? = null
    private var tapCrosshair: CrosshairView? = null
    private var tapRadiusRing: RadiusRingView? = null

    private var scrollStart: Pair<Float, Float>? = null
    private var scrollEnd: Pair<Float, Float>? = null
    private var tapPoint: Pair<Float, Float>? = null

    private var minimized = false

    private val overlayWindowType: Int
        get() = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else
            @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE

    private enum class PositioningMode { NONE, SCROLL, TAP }
    private var positioningMode = PositioningMode.NONE

    private val serviceScope = CoroutineScope(Dispatchers.Main)
    private var loopJob: Job? = null

    private var loopIntervalMs = 1500L
    private var slideSpeedMs = 300L
    private var clickDelayMs = 300L
    private var tapRadiusPx = 0
    private var tapCount = 1
    private var tapGapMs = 150L
    private var scrollJitterPx = 0
    private var cycles = 0

    // profile carousel selection
    private var profileNames: List<String> = emptyList()
    private var profileIndex = 0

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        store = ProfileStore(this)
        startForegroundWithNotification()
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        showPanel()
    }

    override fun onDestroy() {
        super.onDestroy()
        persistLast()
        loopJob?.cancel()
        removeCrosshairs()
        removeRadiusRing()
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

    // ---- UI wiring -------------------------------------------------------

    private lateinit var statusText: TextView
    private lateinit var btnStart: Button
    private lateinit var btnStop: Button
    private lateinit var tvIntervalValue: TextView
    private lateinit var tvSlideValue: TextView
    private lateinit var tvClickDelayValue: TextView
    private lateinit var tvRadiusValue: TextView
    private lateinit var tvTapCountValue: TextView
    private lateinit var tvTapGapValue: TextView
    private lateinit var tvJitterValue: TextView
    private lateinit var tvCyclesValue: TextView
    private lateinit var tvProfileName: TextView
    private lateinit var editProfileName: EditText

    private fun showPanel() {
        panelView = LayoutInflater.from(this).inflate(R.layout.overlay_panel, null)

        panelParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            overlayWindowType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 40
            y = 200
        }

        windowManager.addView(panelView, panelParams)
        setupDrag()

        cardContainer = panelView.findViewById(R.id.cardContainer)
        bubbleView = panelView.findViewById(R.id.bubbleView)
        statusText = panelView.findViewById(R.id.statusText)
        btnStart = panelView.findViewById(R.id.btnStart)
        btnStop = panelView.findViewById(R.id.btnStop)

        val btnSetScroll = panelView.findViewById<Button>(R.id.btnSetScroll)
        val btnSetTap = panelView.findViewById<Button>(R.id.btnSetTap)
        val btnConfirm = panelView.findViewById<Button>(R.id.btnConfirmPositions)
        val btnClearPoints = panelView.findViewById<TextView>(R.id.btnClearPoints)
        val btnClose = panelView.findViewById<TextView>(R.id.btnClose)
        val btnMinimize = panelView.findViewById<TextView>(R.id.btnMinimize)
        val btnToggleSettings = panelView.findViewById<TextView>(R.id.btnToggleSettings)
        val tuningContainer = panelView.findViewById<View>(R.id.tuningContainer)

        tvIntervalValue = panelView.findViewById(R.id.tvIntervalValue)
        tvSlideValue = panelView.findViewById(R.id.tvSlideValue)
        tvClickDelayValue = panelView.findViewById(R.id.tvClickDelayValue)
        tvRadiusValue = panelView.findViewById(R.id.tvRadiusValue)
        tvTapCountValue = panelView.findViewById(R.id.tvTapCountValue)
        tvTapGapValue = panelView.findViewById(R.id.tvTapGapValue)
        tvJitterValue = panelView.findViewById(R.id.tvJitterValue)
        tvCyclesValue = panelView.findViewById(R.id.tvCyclesValue)
        tvProfileName = panelView.findViewById(R.id.tvProfileName)
        editProfileName = panelView.findViewById(R.id.editProfileName)

        // restore auto-saved state before painting values
        store.loadLast()?.let { applyState(it) }

        renderValues()
        refreshProfileCarousel()
        makeEditable(editProfileName)

        // ---- steppers ----
        val step: (View, () -> Unit) -> Unit = { v, f -> v.setOnClickListener { f(); renderValues() } }

        step(panelView.findViewById(R.id.btnIntervalMinus)) { loopIntervalMs = (loopIntervalMs - 250).coerceAtLeast(100) }
        step(panelView.findViewById(R.id.btnIntervalPlus)) { loopIntervalMs = (loopIntervalMs + 250).coerceAtMost(60000) }
        step(panelView.findViewById(R.id.btnSlideMinus)) { slideSpeedMs = (slideSpeedMs - 50).coerceAtLeast(50) }
        step(panelView.findViewById(R.id.btnSlidePlus)) { slideSpeedMs = (slideSpeedMs + 50).coerceAtMost(5000) }
        step(panelView.findViewById(R.id.btnClickDelayMinus)) { clickDelayMs = (clickDelayMs - 50).coerceAtLeast(0) }
        step(panelView.findViewById(R.id.btnClickDelayPlus)) { clickDelayMs = (clickDelayMs + 50).coerceAtMost(5000) }
        panelView.findViewById<Button>(R.id.btnRadiusMinus).setOnClickListener {
            tapRadiusPx = (tapRadiusPx - 10).coerceAtLeast(0); renderValues()
            if (positioningMode == PositioningMode.NONE) updateRadiusRing()
        }
        panelView.findViewById<Button>(R.id.btnRadiusPlus).setOnClickListener {
            tapRadiusPx = (tapRadiusPx + 10).coerceAtMost(500); renderValues()
            if (positioningMode == PositioningMode.NONE) updateRadiusRing()
        }
        step(panelView.findViewById(R.id.btnTapCountMinus)) { tapCount = (tapCount - 1).coerceAtLeast(1) }
        step(panelView.findViewById(R.id.btnTapCountPlus)) { tapCount = (tapCount + 1).coerceAtMost(10) }
        step(panelView.findViewById(R.id.btnTapGapMinus)) { tapGapMs = (tapGapMs - 50).coerceAtLeast(50) }
        step(panelView.findViewById(R.id.btnTapGapPlus)) { tapGapMs = (tapGapMs + 50).coerceAtMost(2000) }
        step(panelView.findViewById(R.id.btnJitterMinus)) { scrollJitterPx = (scrollJitterPx - 5).coerceAtLeast(0) }
        step(panelView.findViewById(R.id.btnJitterPlus)) { scrollJitterPx = (scrollJitterPx + 5).coerceAtMost(200) }
        step(panelView.findViewById(R.id.btnCyclesMinus)) { cycles = (cycles - 5).coerceAtLeast(0) }
        step(panelView.findViewById(R.id.btnCyclesPlus)) { cycles = (cycles + 5).coerceAtMost(100000) }

        // ---- points ----
        btnSetScroll.setOnClickListener {
            positioningMode = PositioningMode.SCROLL
            removeCrosshairs()
            addScrollCrosshairs()
            btnConfirm.visibility = View.VISIBLE
            statusText.text = "Drag green (start) and yellow (end), then Confirm."
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
                PositioningMode.TAP -> tapPoint = tapCrosshair?.let { crosshairCenter(it) }
                PositioningMode.NONE -> {}
            }
            val wasTap = positioningMode == PositioningMode.TAP
            positioningMode = PositioningMode.NONE
            removeCrosshairs()
            btnConfirm.visibility = View.GONE
            if (wasTap) updateRadiusRing()
            persistLast()
            refreshStatus()
        }
        btnClearPoints.setOnClickListener {
            scrollStart = null; scrollEnd = null; tapPoint = null
            removeRadiusRing()
            persistLast()
            refreshStatus()
        }

        // ---- profiles ----
        panelView.findViewById<Button>(R.id.btnSaveProfile).setOnClickListener {
            val name = editProfileName.text.toString().trim()
            if (name.isEmpty()) { toast("Enter a profile name"); return@setOnClickListener }
            store.saveProfile(name, currentState())
            editProfileName.setText("")
            refreshProfileCarousel(selectName = name)
            toast("Saved \"$name\"")
        }
        panelView.findViewById<Button>(R.id.btnProfilePrev).setOnClickListener { moveProfile(-1) }
        panelView.findViewById<Button>(R.id.btnProfileNext).setOnClickListener { moveProfile(1) }
        panelView.findViewById<Button>(R.id.btnLoadProfile).setOnClickListener {
            val name = selectedProfile() ?: return@setOnClickListener
            store.loadProfile(name)?.let {
                applyState(it); renderValues(); updateRadiusRing(); persistLast(); refreshStatus()
                toast("Loaded \"$name\"")
            }
        }
        panelView.findViewById<Button>(R.id.btnDeleteProfile).setOnClickListener {
            val name = selectedProfile() ?: return@setOnClickListener
            store.deleteProfile(name)
            refreshProfileCarousel()
            toast("Deleted \"$name\"")
        }

        // ---- header ----
        btnClose.setOnClickListener { stopSelf() }
        btnMinimize.setOnClickListener { setMinimized(true) }
        bubbleView.setOnClickListener { setMinimized(false) }
        btnToggleSettings.setOnClickListener {
            tuningContainer.visibility =
                if (tuningContainer.visibility == View.VISIBLE) View.GONE else View.VISIBLE
        }

        // ---- run control ----
        btnStart.setOnClickListener { startLoop() }
        btnStop.setOnClickListener { stopLoop("Stopped.") }

        refreshStatus()
    }

    private fun renderValues() {
        tvIntervalValue.text = loopIntervalMs.toString()
        tvSlideValue.text = slideSpeedMs.toString()
        tvClickDelayValue.text = clickDelayMs.toString()
        tvRadiusValue.text = tapRadiusPx.toString()
        tvTapCountValue.text = tapCount.toString()
        tvTapGapValue.text = tapGapMs.toString()
        tvJitterValue.text = scrollJitterPx.toString()
        tvCyclesValue.text = cycles.toString()
    }

    // ---- state (de)serialization ----------------------------------------

    private fun currentState(): JSONObject = JSONObject().apply {
        put("loopIntervalMs", loopIntervalMs)
        put("slideSpeedMs", slideSpeedMs)
        put("clickDelayMs", clickDelayMs)
        put("tapRadiusPx", tapRadiusPx)
        put("tapCount", tapCount)
        put("tapGapMs", tapGapMs)
        put("scrollJitterPx", scrollJitterPx)
        put("cycles", cycles)
        scrollStart?.let { put("ssx", it.first); put("ssy", it.second) }
        scrollEnd?.let { put("sex", it.first); put("sey", it.second) }
        tapPoint?.let { put("tpx", it.first); put("tpy", it.second) }
    }

    private fun applyState(s: JSONObject) {
        loopIntervalMs = s.optLong("loopIntervalMs", loopIntervalMs)
        slideSpeedMs = s.optLong("slideSpeedMs", slideSpeedMs)
        clickDelayMs = s.optLong("clickDelayMs", clickDelayMs)
        tapRadiusPx = s.optInt("tapRadiusPx", tapRadiusPx)
        tapCount = s.optInt("tapCount", tapCount)
        tapGapMs = s.optLong("tapGapMs", tapGapMs)
        scrollJitterPx = s.optInt("scrollJitterPx", scrollJitterPx)
        cycles = s.optInt("cycles", cycles)
        scrollStart = if (s.has("ssx")) Pair(s.getDouble("ssx").toFloat(), s.getDouble("ssy").toFloat()) else null
        scrollEnd = if (s.has("sex")) Pair(s.getDouble("sex").toFloat(), s.getDouble("sey").toFloat()) else null
        tapPoint = if (s.has("tpx")) Pair(s.getDouble("tpx").toFloat(), s.getDouble("tpy").toFloat()) else null
    }

    private fun persistLast() {
        if (::store.isInitialized) store.saveLast(currentState())
    }

    // ---- profile carousel ------------------------------------------------

    private fun refreshProfileCarousel(selectName: String? = null) {
        profileNames = store.profileNames()
        profileIndex = when {
            profileNames.isEmpty() -> 0
            selectName != null -> profileNames.indexOf(selectName).coerceAtLeast(0)
            else -> profileIndex.coerceIn(0, profileNames.size - 1)
        }
        tvProfileName.text = selectedProfile() ?: "No saved profiles"
    }

    private fun selectedProfile(): String? = profileNames.getOrNull(profileIndex)

    private fun moveProfile(dir: Int) {
        if (profileNames.isEmpty()) return
        profileIndex = (profileIndex + dir + profileNames.size) % profileNames.size
        tvProfileName.text = selectedProfile()
    }

    // ---- run loop --------------------------------------------------------

    private fun hasSwipe() = scrollStart != null && scrollEnd != null
    private fun hasTap() = tapPoint != null
    private fun canRun() = hasSwipe() || hasTap()

    private fun refreshStatus() {
        val parts = mutableListOf<String>()
        parts.add(if (hasTap()) "Tap set" else "Tap –")
        parts.add(if (hasSwipe()) "Swipe set" else "Swipe –")
        setChip(statusText, "chip_idle")
        statusText.text = if (canRun()) parts.joinToString("  |  ")
        else "Set a tap point or swipe points to begin."
        btnStart.isEnabled = canRun() && loopJob?.isActive != true
        btnStop.isEnabled = loopJob?.isActive == true
    }

    private fun startLoop() {
        if (!canRun()) return
        val start = scrollStart
        val end = scrollEnd
        val tap = tapPoint
        val swipe = hasSwipe()

        btnStart.isEnabled = false
        btnStop.isEnabled = true
        vibrate(40)
        persistLast()

        loopJob = serviceScope.launch {
            var missingCycles = 0
            var done = 0
            while (isActive) {
                val service = AutoTapAccessibilityService.instance
                if (service == null) {
                    missingCycles++
                    if (missingCycles >= 6) {
                        setChip(statusText, "chip_error")
                        statusText.text = "Accessibility service lost. Re-enable it, then Start again."
                        break
                    }
                    setChip(statusText, "chip_error")
                    statusText.text = "Service reconnecting..."
                    delay(500)
                    continue
                }
                missingCycles = 0

                try {
                    if (swipe && start != null && end != null) {
                        val (sx, sy) = randomPointInRadius(start, scrollJitterPx)
                        val (ex, ey) = randomPointInRadius(end, scrollJitterPx)
                        service.performSwipe(sx, sy, ex, ey, durationMs = slideSpeedMs)
                        delay(clickDelayMs)
                    }
                    if (tap != null) {
                        for (i in 1..tapCount) {
                            val (tx, ty) = randomPointInRadius(tap, tapRadiusPx)
                            service.performTap(tx, ty)
                            if (i < tapCount) delay(tapGapMs)
                        }
                    }
                    done++
                    setChip(statusText, "chip_running")
                    statusText.text = if (cycles > 0) "Running…  $done / $cycles" else "Running…  $done"
                } catch (e: Exception) {
                    setChip(statusText, "chip_error")
                    statusText.text = "Gesture error, retrying…"
                }

                if (cycles > 0 && done >= cycles) {
                    stopLoop("Done — $done cycles.")
                    return@launch
                }

                val randomOffset = Random.nextLong(-250, 250)
                delay((loopIntervalMs + randomOffset).coerceAtLeast(100))
            }
            btnStart.isEnabled = canRun()
            btnStop.isEnabled = false
        }
    }

    private fun stopLoop(message: String) {
        loopJob?.cancel()
        loopJob = null
        vibrate(40)
        btnStart.isEnabled = canRun()
        btnStop.isEnabled = false
        setChip(statusText, "chip_idle")
        statusText.text = message
        if (minimized) setMinimized(false)
    }

    // ---- minimize --------------------------------------------------------

    private fun setMinimized(min: Boolean) {
        minimized = min
        cardContainer.visibility = if (min) View.GONE else View.VISIBLE
        bubbleView.visibility = if (min) View.VISIBLE else View.GONE
        bubbleView.text = if (loopJob?.isActive == true) "▶" else "⚙"
    }

    // ---- helpers ---------------------------------------------------------

    private fun setChip(view: TextView, colorName: String) {
        val id = resources.getIdentifier(colorName, "color", packageName)
        val bg = view.background
        bg?.setTint(getColor(id))
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

    private fun vibrate(ms: Long) {
        val v = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            (getSystemService(Context.VIBRATOR_MANAGER_SERVICE)
                as android.os.VibratorManager).defaultVibrator
        } else {
            @Suppress("DEPRECATION") getSystemService(VIBRATOR_SERVICE) as Vibrator
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            v.vibrate(VibrationEffect.createOneShot(ms, VibrationEffect.DEFAULT_AMPLITUDE))
        } else {
            @Suppress("DEPRECATION") v.vibrate(ms)
        }
    }

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
        return Pair(params.x + view.width / 2f, params.y + view.height / 2f)
    }

    private fun randomPointInRadius(center: Pair<Float, Float>, radiusPx: Int): Pair<Float, Float> {
        if (radiusPx <= 0) return center
        val angle = Random.nextDouble(0.0, 2 * Math.PI)
        val r = radiusPx * sqrt(Random.nextDouble())
        val dx = (r * cos(angle)).toFloat()
        val dy = (r * sin(angle)).toFloat()
        return Pair(center.first + dx, center.second + dy)
    }

    private fun updateRadiusRing() {
        val tap = tapPoint
        if (tap == null || tapRadiusPx <= 0) { removeRadiusRing(); return }
        val size = tapRadiusPx * 2
        val existing = tapRadiusRing
        if (existing == null) {
            val ring = RadiusRingView(this)
            val params = WindowManager.LayoutParams(
                size, size,
                overlayWindowType,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
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

    private fun setupDrag() {
        val dragHandle = panelView.findViewById<TextView>(R.id.dragHandle)
        var initialX = 0
        var initialY = 0
        var initialTouchX = 0f
        var initialTouchY = 0f

        val listener = View.OnTouchListener { _, event ->
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
        dragHandle.setOnTouchListener(listener)
        // let the bubble be dragged too
        panelView.findViewById<TextView>(R.id.bubbleView).setOnTouchListener { v, event ->
            if (event.actionMasked == MotionEvent.ACTION_MOVE) listener.onTouch(v, event)
            else if (event.actionMasked == MotionEvent.ACTION_DOWN) { listener.onTouch(v, event); false }
            else false
        }
    }

    private fun screenSize(): Pair<Int, Int> {
        val dm = resources.displayMetrics
        return Pair(dm.widthPixels, dm.heightPixels)
    }

    private fun addScrollCrosshairs() {
        val (w, h) = screenSize()
        scrollStartCrosshair = addCrosshair(
            android.graphics.Color.parseColor("#00E676"), "START",
            initialX = w / 4, initialY = (h * 0.7f).toInt()
        )
        scrollEndCrosshair = addCrosshair(
            android.graphics.Color.parseColor("#FFD600"), "END",
            initialX = w / 4, initialY = (h * 0.3f).toInt()
        )
    }

    private fun addTapCrosshair() {
        val (w, h) = screenSize()
        tapCrosshair = addCrosshair(
            android.graphics.Color.parseColor("#FF4081"), "TAP",
            initialX = w / 2, initialY = h / 2
        )
    }

    private fun addCrosshair(color: Int, label: String, initialX: Int, initialY: Int): CrosshairView {
        val size = 140
        val crosshair = CrosshairView(this, color, label)
        val params = WindowManager.LayoutParams(
            size, size,
            overlayWindowType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = initialX - size / 2
            y = initialY - size / 2
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
