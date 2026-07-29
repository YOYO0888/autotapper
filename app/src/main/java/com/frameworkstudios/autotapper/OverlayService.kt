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
import android.view.ViewGroup
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
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

    // The editable sequence of steps
    private val steps: MutableList<Step> = mutableListOf()

    // Point picker state (transient while user is placing a crosshair)
    private var pickerA: CrosshairView? = null
    private var pickerB: CrosshairView? = null
    private var pickerMode: PickerMode = PickerMode.NONE

    // Loop settings
    private var loopIntervalMs = 1200L
    private var cycles = 0

    // Defaults applied to new steps (surfaced in Settings)
    private var defaultTapRadius = 0
    private var defaultTapCount = 1
    private var defaultTapGap = 150L
    private var defaultSwipeDuration = 300L
    private var defaultSwipeJitter = 0
    private var defaultWait = 500L

    // Profile carousel
    private var profileNames: List<String> = emptyList()
    private var profileIndex = 0

    private val overlayWindowType: Int
        get() = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else
            @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE

    private enum class PickerMode { NONE, TAP, SWIPE }

    private val serviceScope = CoroutineScope(Dispatchers.Main)
    private var loopJob: Job? = null
    private var minimized = false
    private var recordMode = false
    private var currentCycle = 0

    // ---- view refs ----
    private lateinit var statusText: TextView
    private lateinit var stepsContainer: LinearLayout
    private lateinit var btnStart: Button
    private lateinit var btnStop: Button
    private lateinit var tvProfileName: TextView
    private lateinit var tvIntervalValue: TextView
    private lateinit var tvCyclesValue: TextView
    private lateinit var tvTapRadiusValue: TextView
    private lateinit var tvTapCountValue: TextView
    private lateinit var tvTapGapValue: TextView
    private lateinit var tvSwipeDurValue: TextView
    private lateinit var tvSwipeJitterValue: TextView
    private lateinit var tvWaitValue: TextView
    private lateinit var editProfileName: EditText

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        instance = this
        store = ProfileStore(this)
        startForegroundWithNotification()
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        showPanel()
    }

    override fun onDestroy() {
        super.onDestroy()
        if (instance === this) instance = null
        persistLast()
        loopJob?.cancel()
        removePickers()
        if (::panelView.isInitialized) {
            runCatching { windowManager.removeView(panelView) }
        }
    }

    private fun startForegroundWithNotification() {
        val channelId = "autotapper_channel"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(
                NotificationChannel(channelId, "AutoTapper", NotificationManager.IMPORTANCE_LOW)
            )
        }
        val notif = NotificationCompat.Builder(this, channelId)
            .setContentTitle("AutoTapper running")
            .setContentText("Floating controls are active.")
            .setSmallIcon(android.R.drawable.ic_menu_myplaces)
            .setOngoing(true)
            .build()
        startForeground(1, notif)
    }

    // ---- panel setup -----------------------------------------------------

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
            x = 40; y = 200
        }

        windowManager.addView(panelView, panelParams)
        setupDrag()

        cardContainer = panelView.findViewById(R.id.cardContainer)
        bubbleView = panelView.findViewById(R.id.bubbleView)
        statusText = panelView.findViewById(R.id.statusText)
        stepsContainer = panelView.findViewById(R.id.stepsContainer)
        btnStart = panelView.findViewById(R.id.btnStart)
        btnStop = panelView.findViewById(R.id.btnStop)
        tvProfileName = panelView.findViewById(R.id.tvProfileName)
        tvIntervalValue = panelView.findViewById(R.id.tvIntervalValue)
        tvCyclesValue = panelView.findViewById(R.id.tvCyclesValue)
        tvTapRadiusValue = panelView.findViewById(R.id.tvTapRadiusValue)
        tvTapCountValue = panelView.findViewById(R.id.tvTapCountValue)
        tvTapGapValue = panelView.findViewById(R.id.tvTapGapValue)
        tvSwipeDurValue = panelView.findViewById(R.id.tvSwipeDurValue)
        tvSwipeJitterValue = panelView.findViewById(R.id.tvSwipeJitterValue)
        tvWaitValue = panelView.findViewById(R.id.tvWaitValue)
        editProfileName = panelView.findViewById(R.id.editProfileName)

        // Restore auto-saved state
        store.loadLast()?.let { applyState(it) }

        renderValues(); renderSteps(); refreshProfileCarousel()
        makeEditable(editProfileName)

        // ---- steppers -----------------------------------------------------
        val step: (View, () -> Unit) -> Unit = { v, f -> v.setOnClickListener { f(); renderValues(); persistLast() } }
        step(panelView.findViewById(R.id.btnIntervalMinus)) { loopIntervalMs = (loopIntervalMs - 250).coerceAtLeast(100) }
        step(panelView.findViewById(R.id.btnIntervalPlus))  { loopIntervalMs = (loopIntervalMs + 250).coerceAtMost(60000) }
        step(panelView.findViewById(R.id.btnCyclesMinus))   { cycles = (cycles - 5).coerceAtLeast(0) }
        step(panelView.findViewById(R.id.btnCyclesPlus))    { cycles = (cycles + 5).coerceAtMost(100000) }
        step(panelView.findViewById(R.id.btnTapRadiusMinus)){ defaultTapRadius = (defaultTapRadius - 10).coerceAtLeast(0) }
        step(panelView.findViewById(R.id.btnTapRadiusPlus)) { defaultTapRadius = (defaultTapRadius + 10).coerceAtMost(500) }
        step(panelView.findViewById(R.id.btnTapCountMinus)) { defaultTapCount = (defaultTapCount - 1).coerceAtLeast(1) }
        step(panelView.findViewById(R.id.btnTapCountPlus))  { defaultTapCount = (defaultTapCount + 1).coerceAtMost(10) }
        step(panelView.findViewById(R.id.btnTapGapMinus))   { defaultTapGap = (defaultTapGap - 50).coerceAtLeast(50) }
        step(panelView.findViewById(R.id.btnTapGapPlus))    { defaultTapGap = (defaultTapGap + 50).coerceAtMost(2000) }
        step(panelView.findViewById(R.id.btnSwipeDurMinus)) { defaultSwipeDuration = (defaultSwipeDuration - 50).coerceAtLeast(50) }
        step(panelView.findViewById(R.id.btnSwipeDurPlus))  { defaultSwipeDuration = (defaultSwipeDuration + 50).coerceAtMost(5000) }
        step(panelView.findViewById(R.id.btnSwipeJitterMinus)){ defaultSwipeJitter = (defaultSwipeJitter - 5).coerceAtLeast(0) }
        step(panelView.findViewById(R.id.btnSwipeJitterPlus)) { defaultSwipeJitter = (defaultSwipeJitter + 5).coerceAtMost(200) }
        step(panelView.findViewById(R.id.btnWaitMinus)) { defaultWait = (defaultWait - 100).coerceAtLeast(50) }
        step(panelView.findViewById(R.id.btnWaitPlus))  { defaultWait = (defaultWait + 100).coerceAtMost(60000) }

        // ---- step-add buttons --------------------------------------------
        panelView.findViewById<Button>(R.id.btnAddTap).setOnClickListener {
            startPicker(PickerMode.TAP)
        }
        panelView.findViewById<Button>(R.id.btnAddSwipe).setOnClickListener {
            startPicker(PickerMode.SWIPE)
        }
        panelView.findViewById<Button>(R.id.btnAddWait).setOnClickListener {
            steps.add(Step.Wait(defaultWait))
            renderSteps(); persistLast(); vibrate(20)
        }
        panelView.findViewById<Button>(R.id.btnConfirmPicker).setOnClickListener { confirmPicker() }
        panelView.findViewById<Button>(R.id.btnCancelPicker).setOnClickListener { cancelPicker() }
        panelView.findViewById<TextView>(R.id.btnClearSteps).setOnClickListener {
            steps.clear(); renderSteps(); persistLast(); refreshStatus()
        }

        // Record mode: user does the workflow with a finger, capture ends via Stop Recording.
        val btnRecord = panelView.findViewById<Button>(R.id.btnRecord)
        btnRecord.setOnClickListener { toggleRecord(btnRecord) }

        // ---- profiles + export/import ------------------------------------
        panelView.findViewById<Button>(R.id.btnSaveProfile).setOnClickListener {
            val name = editProfileName.text.toString().trim()
            if (name.isEmpty()) { toast("Enter a profile name"); return@setOnClickListener }
            store.saveProfile(name, currentState())
            editProfileName.setText("")
            refreshProfileCarousel(selectName = name); toast("Saved \"$name\"")
        }
        panelView.findViewById<Button>(R.id.btnProfilePrev).setOnClickListener { moveProfile(-1) }
        panelView.findViewById<Button>(R.id.btnProfileNext).setOnClickListener { moveProfile(1) }
        panelView.findViewById<Button>(R.id.btnLoadProfile).setOnClickListener {
            val name = selectedProfile() ?: return@setOnClickListener
            store.loadProfile(name)?.let {
                applyState(it); renderValues(); renderSteps(); persistLast(); refreshStatus()
                toast("Loaded \"$name\"")
            }
        }
        panelView.findViewById<Button>(R.id.btnDeleteProfile).setOnClickListener {
            val name = selectedProfile() ?: return@setOnClickListener
            store.deleteProfile(name); refreshProfileCarousel(); toast("Deleted \"$name\"")
        }
        panelView.findViewById<Button>(R.id.btnExportProfile).setOnClickListener {
            val name = selectedProfile() ?: run { toast("No profile selected"); return@setOnClickListener }
            val json = store.loadProfile(name) ?: return@setOnClickListener
            val cb = getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager
            cb.setPrimaryClip(android.content.ClipData.newPlainText("AutoTapper profile", json.toString()))
            toast("Copied \"$name\" to clipboard")
        }
        panelView.findViewById<Button>(R.id.btnImportProfile).setOnClickListener {
            val i = Intent(this, ImportActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            startActivity(i)
        }

        // ---- header ------------------------------------------------------
        panelView.findViewById<TextView>(R.id.btnClose).setOnClickListener { stopSelf() }
        panelView.findViewById<TextView>(R.id.btnMinimize).setOnClickListener { setMinimized(true) }
        bubbleView.setOnClickListener {
            when {
                recordMode -> stopRecording(panelView.findViewById(R.id.btnRecord))
                loopJob?.isActive == true -> stopLoop("Paused.")
                else -> setMinimized(false)
            }
        }
        panelView.findViewById<TextView>(R.id.btnToggleSettings).setOnClickListener {
            val c = panelView.findViewById<View>(R.id.tuningContainer)
            c.visibility = if (c.visibility == View.VISIBLE) View.GONE else View.VISIBLE
        }

        btnStart.setOnClickListener { startLoop() }
        btnStop.setOnClickListener { stopLoop("Stopped.") }

        refreshStatus()
    }

    // ---- render helpers --------------------------------------------------

    private fun renderValues() {
        tvIntervalValue.text = loopIntervalMs.toString()
        tvCyclesValue.text = cycles.toString()
        tvTapRadiusValue.text = defaultTapRadius.toString()
        tvTapCountValue.text = defaultTapCount.toString()
        tvTapGapValue.text = defaultTapGap.toString()
        tvSwipeDurValue.text = defaultSwipeDuration.toString()
        tvSwipeJitterValue.text = defaultSwipeJitter.toString()
        tvWaitValue.text = defaultWait.toString()
    }

    private fun renderSteps() {
        stepsContainer.removeAllViews()
        if (steps.isEmpty()) {
            val tv = TextView(this).apply {
                text = "No steps yet. Add Tap / Swipe / Wait, or Record."
                setTextColor(getColor(R.color.text_dim))
                textSize = 11f
                setPadding(0, dp(4), 0, dp(4))
            }
            stepsContainer.addView(tv)
            refreshStatus()
            return
        }
        steps.forEachIndexed { i, s ->
            stepsContainer.addView(makeStepRow(i, s))
        }
        refreshStatus()
    }

    private fun makeStepRow(index: Int, step: Step): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(3) }
            setPadding(dp(6), dp(4), dp(6), dp(4))
            background = getDrawable(R.drawable.card_bg)
        }
        val label = TextView(this).apply {
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            text = step.label(index)
            setTextColor(getColor(R.color.text_light))
            textSize = 11f
        }
        val up = mkSmallBtn("▲") {
            if (index > 0) {
                val s = steps.removeAt(index); steps.add(index - 1, s); renderSteps(); persistLast()
            }
        }
        val down = mkSmallBtn("▼") {
            if (index < steps.size - 1) {
                val s = steps.removeAt(index); steps.add(index + 1, s); renderSteps(); persistLast()
            }
        }
        val del = mkSmallBtn("✕") {
            steps.removeAt(index); renderSteps(); persistLast()
        }
        row.addView(label); row.addView(up); row.addView(down); row.addView(del)
        return row
    }

    private fun mkSmallBtn(text: String, onClick: () -> Unit): TextView =
        TextView(this).apply {
            this.text = text
            setTextColor(getColor(R.color.text_dim))
            textSize = 12f
            setPadding(dp(8), dp(2), dp(8), dp(2))
            setOnClickListener { onClick() }
        }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    // ---- point picker ----------------------------------------------------

    private fun startPicker(mode: PickerMode) {
        pickerMode = mode
        removePickers()
        val (w, h) = screenSize()
        when (mode) {
            PickerMode.TAP -> {
                pickerA = addCrosshair(0xFFFF4081.toInt(), "TAP", w / 2, h / 2)
                statusText.text = "Drag the pink point onto the target, then Confirm."
            }
            PickerMode.SWIPE -> {
                pickerA = addCrosshair(0xFF00E676.toInt(), "START", w / 4, (h * 0.7f).toInt())
                pickerB = addCrosshair(0xFFFFD600.toInt(), "END", w / 4, (h * 0.3f).toInt())
                statusText.text = "Drag green (start) and yellow (end), then Confirm."
            }
            PickerMode.NONE -> {}
        }
        panelView.findViewById<View>(R.id.pickerBar).visibility =
            if (mode == PickerMode.NONE) View.GONE else View.VISIBLE
    }

    private fun confirmPicker() {
        when (pickerMode) {
            PickerMode.TAP -> pickerA?.let {
                val (x, y) = crosshairCenter(it)
                steps.add(Step.Tap(x, y, defaultTapRadius, defaultTapCount, defaultTapGap))
            }
            PickerMode.SWIPE -> {
                val a = pickerA; val b = pickerB
                if (a != null && b != null) {
                    val (x1, y1) = crosshairCenter(a); val (x2, y2) = crosshairCenter(b)
                    steps.add(Step.Swipe(x1, y1, x2, y2, defaultSwipeDuration, defaultSwipeJitter))
                }
            }
            PickerMode.NONE -> {}
        }
        cancelPicker()
        renderSteps(); persistLast(); vibrate(30)
    }

    private fun cancelPicker() {
        pickerMode = PickerMode.NONE
        removePickers()
        panelView.findViewById<View>(R.id.pickerBar).visibility = View.GONE
        refreshStatus()
    }

    // ---- record mode -----------------------------------------------------

    private var recordCatcher: View? = null
    private var lastRecordTime = 0L

    private fun toggleRecord(btn: Button) {
        if (recordMode) stopRecording(btn) else startRecording(btn)
    }

    private fun startRecording(btn: Button) {
        recordMode = true
        btn.text = "■ Stop Recording"
        setChip(statusText, R.color.chip_error)
        lastRecordTime = System.currentTimeMillis()

        // Minimize panel to the bubble so it stays visible + tappable over the catcher.
        setMinimized(true)
        bubbleView.text = "●"

        val catcher = View(this).apply { setBackgroundColor(0x33FF4081) }
        val (w, h) = screenSize()
        val params = WindowManager.LayoutParams(
            w, h, overlayWindowType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.TOP or Gravity.START; x = 0; y = 0 }
        windowManager.addView(catcher, params)
        recordCatcher = catcher

        // Bring the panel window (bubble) back on top of the catcher so
        // the user can tap it to stop.
        runCatching {
            windowManager.removeView(panelView)
            windowManager.addView(panelView, panelParams)
        }
        toast("Recording. Tap the pink bubble to stop.")

        var downX = 0f; var downY = 0f; var downT = 0L
        catcher.setOnTouchListener { _, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    val now = System.currentTimeMillis()
                    val gap = now - lastRecordTime
                    if (steps.isNotEmpty() && gap in 150..60000) {
                        steps.add(Step.Wait(gap)); renderSteps()
                    }
                    downX = e.rawX; downY = e.rawY; downT = now
                    true
                }
                MotionEvent.ACTION_UP -> {
                    val dx = e.rawX - downX; val dy = e.rawY - downY
                    val dist = sqrt(dx * dx + dy * dy)
                    val dur = (System.currentTimeMillis() - downT).coerceAtLeast(60)
                    if (dist < 30f) {
                        steps.add(Step.Tap(downX, downY, defaultTapRadius, 1, defaultTapGap))
                    } else {
                        steps.add(Step.Swipe(downX, downY, e.rawX, e.rawY, dur, defaultSwipeJitter))
                    }
                    lastRecordTime = System.currentTimeMillis()
                    renderSteps(); persistLast()
                    true
                }
                else -> true
            }
        }
    }

    private fun stopRecording(btn: Button?) {
        if (!recordMode) return
        recordMode = false
        btn?.text = "● Record"
        panelView.findViewById<Button>(R.id.btnRecord).text = "● Record"
        recordCatcher?.let { runCatching { windowManager.removeView(it) } }
        recordCatcher = null
        setMinimized(false)
        refreshStatus()
    }

    // ---- state <-> JSON --------------------------------------------------

    private fun currentState(): JSONObject = JSONObject().apply {
        put("steps", Step.listToJson(steps))
        put("loopIntervalMs", loopIntervalMs)
        put("cycles", cycles)
        put("defaultTapRadius", defaultTapRadius)
        put("defaultTapCount", defaultTapCount)
        put("defaultTapGap", defaultTapGap)
        put("defaultSwipeDuration", defaultSwipeDuration)
        put("defaultSwipeJitter", defaultSwipeJitter)
        put("defaultWait", defaultWait)
    }

    private fun applyState(s: JSONObject) {
        loopIntervalMs = s.optLong("loopIntervalMs", loopIntervalMs)
        cycles = s.optInt("cycles", cycles)
        defaultTapRadius = s.optInt("defaultTapRadius", defaultTapRadius)
        defaultTapCount = s.optInt("defaultTapCount", defaultTapCount)
        defaultTapGap = s.optLong("defaultTapGap", defaultTapGap)
        defaultSwipeDuration = s.optLong("defaultSwipeDuration", defaultSwipeDuration)
        defaultSwipeJitter = s.optInt("defaultSwipeJitter", defaultSwipeJitter)
        defaultWait = s.optLong("defaultWait", defaultWait)
        steps.clear()
        steps.addAll(Step.listFromJson(s.optJSONArray("steps")))
    }

    private fun persistLast() {
        if (::store.isInitialized) store.saveLast(currentState())
    }

    // ---- profile carousel -----------------------------------------------

    fun onProfilesChanged(selectName: String? = null) {
        panelView.post { refreshProfileCarousel(selectName) }
    }

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

    private fun refreshStatus() {
        setChip(statusText, R.color.chip_idle)
        statusText.text = when {
            recordMode -> statusText.text
            steps.isEmpty() -> "No steps yet. Add Tap / Swipe / Wait, or Record."
            else -> "${steps.size} step${if (steps.size == 1) "" else "s"} ready."
        }
        btnStart.isEnabled = steps.isNotEmpty() && loopJob?.isActive != true && !recordMode
        btnStop.isEnabled = loopJob?.isActive == true
    }

    private fun startLoop() {
        if (steps.isEmpty() || recordMode) return
        btnStart.isEnabled = false; btnStop.isEnabled = true
        vibrate(40); persistLast()
        currentCycle = 0

        loopJob = serviceScope.launch {
            var missing = 0
            while (isActive) {
                val service = AutoTapAccessibilityService.instance
                if (service == null) {
                    missing++
                    if (missing >= 6) {
                        setChip(statusText, R.color.chip_error)
                        statusText.text = "Accessibility lost. Re-enable then Start again."
                        break
                    }
                    setChip(statusText, R.color.chip_error)
                    statusText.text = "Service reconnecting…"
                    delay(500); continue
                }
                missing = 0
                try {
                    for (s in steps) {
                        if (!isActive) return@launch
                        runStep(service, s)
                    }
                    currentCycle++
                    setChip(statusText, R.color.chip_running)
                    val label = if (cycles > 0) "Running  $currentCycle / $cycles" else "Running  $currentCycle"
                    statusText.text = label
                    if (minimized) bubbleView.text = currentCycle.toString()
                } catch (e: Exception) {
                    setChip(statusText, R.color.chip_error)
                    statusText.text = "Gesture error, retrying…"
                }
                if (cycles > 0 && currentCycle >= cycles) {
                    stopLoop("Done — $currentCycle cycles."); return@launch
                }
                val jitter = Random.nextLong(-250, 250)
                delay((loopIntervalMs + jitter).coerceAtLeast(50))
            }
            btnStart.isEnabled = steps.isNotEmpty()
            btnStop.isEnabled = false
        }
    }

    private suspend fun runStep(service: AutoTapAccessibilityService, step: Step) {
        when (step) {
            is Step.Tap -> {
                for (i in 1..step.count) {
                    val (x, y) = randomInRadius(step.x, step.y, step.radius)
                    service.performTap(x, y)
                    if (i < step.count) delay(step.gapMs)
                }
            }
            is Step.Swipe -> {
                val (sx, sy) = randomInRadius(step.x1, step.y1, step.jitterPx)
                val (ex, ey) = randomInRadius(step.x2, step.y2, step.jitterPx)
                service.performSwipe(sx, sy, ex, ey, step.durationMs)
            }
            is Step.Wait -> delay(step.ms)
        }
    }

    private fun stopLoop(message: String) {
        loopJob?.cancel(); loopJob = null
        vibrate(40)
        btnStart.isEnabled = steps.isNotEmpty()
        btnStop.isEnabled = false
        setChip(statusText, R.color.chip_idle)
        statusText.text = message
        if (minimized) bubbleView.text = "▶"
    }

    // ---- minimize --------------------------------------------------------

    private fun setMinimized(min: Boolean) {
        minimized = min
        cardContainer.visibility = if (min) View.GONE else View.VISIBLE
        bubbleView.visibility = if (min) View.VISIBLE else View.GONE
        bubbleView.text = if (loopJob?.isActive == true) currentCycle.toString() else "▶"
    }

    // ---- helpers ---------------------------------------------------------

    private fun setChip(view: TextView, colorRes: Int) {
        view.background?.setTint(getColor(colorRes))
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
        val p = view.layoutParams as WindowManager.LayoutParams
        return Pair(p.x + view.width / 2f, p.y + view.height / 2f)
    }

    private fun randomInRadius(cx: Float, cy: Float, radius: Int): Pair<Float, Float> {
        if (radius <= 0) return Pair(cx, cy)
        val a = Random.nextDouble(0.0, 2 * Math.PI)
        val r = radius * sqrt(Random.nextDouble())
        return Pair(cx + (r * cos(a)).toFloat(), cy + (r * sin(a)).toFloat())
    }

    private fun setupDrag() {
        var initialX = 0; var initialY = 0
        var touchX = 0f; var touchY = 0f
        val listener = View.OnTouchListener { _, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = panelParams.x; initialY = panelParams.y
                    touchX = e.rawX; touchY = e.rawY; true
                }
                MotionEvent.ACTION_MOVE -> {
                    panelParams.x = initialX + (e.rawX - touchX).toInt()
                    panelParams.y = initialY + (e.rawY - touchY).toInt()
                    windowManager.updateViewLayout(panelView, panelParams); true
                }
                else -> false
            }
        }
        panelView.findViewById<TextView>(R.id.dragHandle).setOnTouchListener(listener)
        panelView.findViewById<TextView>(R.id.bubbleView).setOnTouchListener(listener)
    }

    private fun screenSize(): Pair<Int, Int> {
        val dm = resources.displayMetrics
        return Pair(dm.widthPixels, dm.heightPixels)
    }

    private fun addCrosshair(color: Int, label: String, cx: Int, cy: Int): CrosshairView {
        val size = 140
        val ch = CrosshairView(this, color, label)
        val params = WindowManager.LayoutParams(
            size, size, overlayWindowType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = cx - size / 2; y = cy - size / 2
        }
        ch.onMoved = { rx, ry ->
            params.x = (rx - size / 2f).toInt()
            params.y = (ry - size / 2f).toInt()
            windowManager.updateViewLayout(ch, params)
        }
        windowManager.addView(ch, params)
        return ch
    }

    private fun removePickers() {
        listOf(pickerA, pickerB).forEach { it?.let { v -> runCatching { windowManager.removeView(v) } } }
        pickerA = null; pickerB = null
    }

    companion object {
        var instance: OverlayService? = null
            private set
    }
}
