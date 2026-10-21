package pl.example.worldiconclicker

import android.accessibilityservice.AccessibilityService
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.Rect
import android.os.SystemClock
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout

/** Jeden nagrany gest. Współrzędne w pikselach ekranu, czasy z SystemClock.elapsedRealtime(). */
class RecordedGesture(
    val points: List<Pair<Float, Float>>,
    val startedAt: Long,
    val endedAt: Long
)

/** Aktualne granice ekranu w pikselach (spójne dla nagrywania i odtwarzania). */
fun screenBounds(context: Context): Rect =
    (context.getSystemService(Context.WINDOW_SERVICE) as WindowManager).currentWindowMetrics.bounds

enum class OverlayMode { RECORDING, ARMED, PLAYING }

enum class OverlayControl { STOP_RECORDING, START_BOT, CLOSE_BOT, STOP_BOT }

/**
 * Nakładki usługi dostępności (TYPE_ACCESSIBILITY_OVERLAY — nie wymagają osobnego uprawnienia):
 *  - przezroczysta warstwa na cały ekran, która PRZECHWYTUJE dotknięcia podczas nagrywania
 *    (Android nie przekazuje usłudze surowych dotknięć w cudzych aplikacjach, więc to jedyny pewny sposób),
 *  - pływające przyciski sterujące: ■ ZAKOŃCZ (nagrywanie), ▶ START BOTA / ✕ (gotowość w grze), ■ ZATRZYMAJ (odtwarzanie).
 */
class MacroOverlay(
    private val service: AccessibilityService,
    private val onGesture: (RecordedGesture) -> Unit,
    private val onControl: (OverlayControl) -> Unit
) {
    private val wm = service.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private var captureView: CaptureView? = null
    private var captureParams: WindowManager.LayoutParams? = null
    private var controlView: View? = null
    private var controlParams: WindowManager.LayoutParams? = null
    private var recordButton: Button? = null
    private var mode: OverlayMode? = null

    val isShown: Boolean get() = mode != null
    val currentMode: OverlayMode? get() = mode

    fun show(newMode: OverlayMode) {
        if (mode == newMode && controlView != null) return
        hide()
        try {
            if (newMode == OverlayMode.RECORDING) {
                val view = CaptureView(service)
                val lp = WindowManager.LayoutParams(
                    WindowManager.LayoutParams.MATCH_PARENT,
                    WindowManager.LayoutParams.MATCH_PARENT,
                    WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                        WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                        WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                    PixelFormat.TRANSLUCENT
                ).apply {
                    gravity = Gravity.TOP or Gravity.START
                    layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
                }
                wm.addView(view, lp)
                captureView = view
                captureParams = lp
            }

            val container = LinearLayout(service).apply { orientation = LinearLayout.HORIZONTAL }
            when (newMode) {
                OverlayMode.RECORDING -> {
                    val b = makeButton("■ ZAKOŃCZ I ZAPISZ (0)", Color.argb(230, 211, 47, 47), OverlayControl.STOP_RECORDING)
                    recordButton = b
                    container.addView(b)
                }
                OverlayMode.ARMED -> {
                    container.addView(makeButton("▶ START BOTA", Color.argb(230, 46, 125, 50), OverlayControl.START_BOT))
                    container.addView(makeButton("✕", Color.argb(230, 97, 97, 97), OverlayControl.CLOSE_BOT))
                }
                OverlayMode.PLAYING -> {
                    container.addView(makeButton("■ ZATRZYMAJ BOTA", Color.argb(230, 211, 47, 47), OverlayControl.STOP_BOT))
                }
            }
            val density = service.resources.displayMetrics.density
            val blp = WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
                y = (56 * density).toInt()
            }
            // Przyciski dodane jako ostatnie, więc leżą NAD warstwą przechwytującą.
            wm.addView(container, blp)
            controlView = container
            controlParams = blp
            mode = newMode
        } catch (e: Exception) {
            hide()
            throw e
        }
    }

    private fun makeButton(label: String, color: Int, control: OverlayControl): Button =
        Button(service).apply {
            isAllCaps = false
            setTextColor(Color.WHITE)
            setBackgroundColor(color)
            text = label
            setOnClickListener { onControl(control) }
        }

    fun hide() {
        captureView?.let { try { wm.removeViewImmediate(it) } catch (_: Exception) { } }
        controlView?.let { try { wm.removeViewImmediate(it) } catch (_: Exception) { } }
        captureView = null
        captureParams = null
        controlView = null
        controlParams = null
        recordButton = null
        mode = null
    }

    fun setRecordedCount(count: Int) {
        recordButton?.text = "■ ZAKOŃCZ I ZAPISZ ($count)"
    }

    /**
     * Przed wstrzyknięciem gestu do gry nakładka musi przepuszczać dotyk, inaczej
     * wstrzyknięty gest trafiłby w nią samą. Przycisk STOP wyłączamy tylko wtedy,
     * gdy gest przechodziłby przez jego obszar.
     */
    fun beginInjection(pxPoints: List<Pair<Float, Float>>) {
        setNotTouchable(captureView, captureParams, true)
        if (overlapsControls(pxPoints)) setNotTouchable(controlView, controlParams, true)
    }

    fun endInjection() {
        setNotTouchable(captureView, captureParams, false)
        setNotTouchable(controlView, controlParams, false)
    }

    private fun overlapsControls(pxPoints: List<Pair<Float, Float>>): Boolean {
        val b = controlView ?: return false
        if (pxPoints.isEmpty()) return false
        val loc = IntArray(2)
        b.getLocationOnScreen(loc)
        val margin = 24
        val rect = Rect(loc[0] - margin, loc[1] - margin, loc[0] + b.width + margin, loc[1] + b.height + margin)
        val minX = pxPoints.minOf { it.first }.toInt()
        val maxX = pxPoints.maxOf { it.first }.toInt()
        val minY = pxPoints.minOf { it.second }.toInt()
        val maxY = pxPoints.maxOf { it.second }.toInt()
        return Rect.intersects(rect, Rect(minX, minY, maxX, maxY))
    }

    private fun setNotTouchable(view: View?, lp: WindowManager.LayoutParams?, notTouchable: Boolean) {
        if (view == null || lp == null) return
        lp.flags = if (notTouchable) {
            lp.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        } else {
            lp.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()
        }
        try { wm.updateViewLayout(view, lp) } catch (_: Exception) { }
    }

    @SuppressLint("ViewConstructor")
    private inner class CaptureView(context: Context) : View(context) {
        private val points = ArrayList<Pair<Float, Float>>()
        private val trail = Path()
        private var tracking = false
        private var startedAt = 0L
        private var lastSampleAt = 0L

        private val trailPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.YELLOW; style = Paint.Style.STROKE; strokeWidth = 8f; strokeCap = Paint.Cap.ROUND
        }
        private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.YELLOW; style = Paint.Style.FILL }
        private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE; textSize = 15f * resources.displayMetrics.scaledDensity; textAlign = Paint.Align.CENTER
        }

        override fun onDraw(canvas: Canvas) {
            canvas.drawColor(Color.argb(40, 255, 0, 0))
            canvas.drawText("● NAGRYWANIE — wykonuj gesty", width / 2f, height - 160f, textPaint)
            if (points.isNotEmpty()) {
                canvas.drawPath(trail, trailPaint)
                val last = points.last()
                // points są w pikselach ekranu; warstwa zaczyna się w (0,0), więc współrzędne się pokrywają
                canvas.drawCircle(last.first, last.second, 24f, dotPaint)
            }
        }

        override fun onTouchEvent(e: MotionEvent): Boolean {
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    points.clear()
                    trail.reset()
                    tracking = true
                    startedAt = SystemClock.elapsedRealtime()
                    lastSampleAt = startedAt
                    points.add(Pair(e.rawX, e.rawY))
                    trail.moveTo(e.rawX, e.rawY)
                }
                MotionEvent.ACTION_MOVE -> if (tracking) {
                    val now = SystemClock.elapsedRealtime()
                    if (now - lastSampleAt >= 16L) {
                        points.add(Pair(e.rawX, e.rawY))
                        trail.lineTo(e.rawX, e.rawY)
                        lastSampleAt = now
                    }
                }
                MotionEvent.ACTION_UP -> if (tracking) {
                    tracking = false
                    val now = SystemClock.elapsedRealtime()
                    points.add(Pair(e.rawX, e.rawY))
                    trail.lineTo(e.rawX, e.rawY)
                    val gesture = RecordedGesture(points.toList(), startedAt, now)
                    invalidate()
                    onGesture(gesture)
                }
                MotionEvent.ACTION_CANCEL -> {
                    tracking = false
                    points.clear()
                    trail.reset()
                }
            }
            invalidate()
            return true
        }
    }
}
