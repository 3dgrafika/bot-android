package pl.example.worldiconclicker

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityService.TakeScreenshotCallback
import android.accessibilityservice.AccessibilityService.ScreenshotResult
import android.accessibilityservice.GestureDescription
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Path
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.view.Display
import android.view.accessibility.AccessibilityEvent
import org.json.JSONArray
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.math.max
import kotlin.math.min
import kotlin.random.Random

class IconClickService : AccessibilityService() {
    companion object {
        /** Ustawiane, gdy usługa jest połączona; MainActivity używa tego do sprawdzenia, czy usługa działa. */
        @Volatile
        var instance: IconClickService? = null
            private set

        private const val MAX_RECORDING_MS = 10 * 60 * 1000L
        private const val BOT_START_DELAY_MS = 800L
    }

    /** Pojedynczy krok algorytmu bota: jeden gest z konkretnego makra. */
    private class Step(val macroName: String, val number: Int, val total: Int, val action: MacroAction)

    private val handler = Handler(Looper.getMainLooper())
    private val worker: ExecutorService = Executors.newSingleThreadExecutor()
    private var scanInProgress = false
    private var scanCount = 0
    private var lastClickAt = 0L
    private var template: Bitmap? = null

    private var overlay: MacroOverlay? = null
    private var recordingActive = false
    private var recordedCount = 0
    private var lastRecordedEndAt = 0L

    private var playbackGeneration = 0
    private var playbackIndex = 0
    private var playbackRepeat = 0
    private var playbackActive = false
    private var playbackActions: List<Step> = emptyList()
    private var pendingGesture: Runnable? = null

    private val recordingTimeout = Runnable {
        AppLog.write(this, "Nagrywanie przerwane automatycznie po 10 minutach")
        prefs().edit().putBoolean("macro_recording", false).apply()
    }

    // Referencja musi być trzymana w polu — SharedPreferences trzyma listenery tylko słabo.
    private val prefListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == "macro_recording" || key == "macro_playing" || key == "bot_armed" || key == "emergency_stop") onMacroStateChanged()
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        try {
            template = assets.open("icon_template.png").use { BitmapFactory.decodeStream(it) }
            AppLog.write(this, "Usługa dostępności połączona. Wzór: ${template?.width}x${template?.height}px")
        } catch (e: Exception) {
            AppLog.write(this, "BŁĄD ładowania wzoru: ${e.message}")
        }
        MacroStore.migrateLegacy(this)
        overlay = MacroOverlay(this, ::onRecordedGesture, ::onOverlayControl)
        // Po ponownym połączeniu usługi nie wznawiamy starych stanów (nagrywania/odtwarzania).
        prefs().edit().putBoolean("macro_recording", false).putBoolean("macro_playing", false).putBoolean("bot_armed", false).apply()
        prefs().registerOnSharedPreferenceChangeListener(prefListener)
        scheduleScan(500)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) { }

    // ---------------------------------------------------------------------------------------
    // NAGRYWANIE
    // Android nie dostarcza usłudze dostępności surowych dotknięć w cudzych aplikacjach
    // (onMotionEvent nie działa dla ekranu dotykowego). Dlatego na czas nagrywania
    // pokazujemy przezroczystą nakładkę, która łapie gesty, zapisuje je, a następnie
    // wstrzykuje je do gry przez dispatchGesture (żeby stan gry zgadzał się z makrem).
    // ---------------------------------------------------------------------------------------

    private fun onMacroStateChanged() {
        val p = prefs()
        val emergency = p.getBoolean("emergency_stop", false)
        val recording = !emergency && p.getBoolean("macro_recording", false)
        val playing = !emergency && p.getBoolean("macro_playing", false)
        val armed = !emergency && p.getBoolean("bot_armed", false)

        if (!playing && playbackActive) stopPlaybackInternal()
        handler.removeCallbacks(recordingTimeout)

        try {
            if (recording && !recordingActive) {
                recordingActive = true
                recordedCount = 0
                lastRecordedEndAt = 0L
                AppLog.write(this, "Nagrywanie: nakładka aktywna. Dotykaj ekranu gry — każdy gest zostanie zapisany i przekazany do gry. Przycisk ■ u góry kończy nagrywanie.")
            } else if (!recording && recordingActive) {
                recordingActive = false
                finishRecording()
            }

            when {
                recording -> {
                    overlay?.show(OverlayMode.RECORDING)
                    handler.postDelayed(recordingTimeout, MAX_RECORDING_MS)
                }
                playing -> {
                    overlay?.show(OverlayMode.PLAYING)
                    if (!playbackActive) startPlayback()
                }
                armed -> overlay?.show(OverlayMode.ARMED)
                else -> overlay?.hide()
            }
        } catch (e: Exception) {
            AppLog.write(this, "BŁĄD nakładki: ${e.javaClass.simpleName}: ${e.message}")
            recordingActive = false
            overlay?.hide()
            prefs().edit().putBoolean("macro_recording", false).putBoolean("macro_playing", false)
                .putBoolean("bot_armed", false).apply()
        }
    }

    /** Przenosi świeżo nagrane gesty z bufora do listy nazwanych makr. */
    private fun finishRecording() {
        val p = prefs()
        val actions = MacroStore.parseActions(p.getString("macro_actions", "[]") ?: "[]")
        p.edit().putString("macro_actions", "[]").apply()
        if (actions.isEmpty()) {
            AppLog.write(this, "Nagrywanie zakończone. Nie nagrano żadnego gestu — makro nie zostało zapisane.")
            return
        }
        val name = (p.getString("macro_recording_name", "") ?: "").trim()
            .ifEmpty { "Makro ${MacroStore.load(this).size + 1}" }
        MacroStore.add(this, name, actions)
        AppLog.write(this, "Nagrywanie zakończone. Zapisano makro „$name” (${actions.size} gestów). Zaznacz je na liście w aplikacji.")
    }

    private fun onOverlayControl(control: OverlayControl) {
        when (control) {
            OverlayControl.STOP_RECORDING -> prefs().edit().putBoolean("macro_recording", false).apply()
            OverlayControl.START_BOT -> {
                AppLog.write(this, "Bot: START z przycisku na ekranie")
                prefs().edit().putBoolean("macro_playing", true).apply()
            }
            OverlayControl.STOP_BOT -> {
                AppLog.write(this, "Bot zatrzymany przyciskiem na ekranie")
                prefs().edit().putBoolean("macro_playing", false).apply()
            }
            OverlayControl.CLOSE_BOT -> {
                AppLog.write(this, "Przycisk START bota zamknięty")
                prefs().edit().putBoolean("bot_armed", false).apply()
            }
        }
    }

    private fun onRecordedGesture(g: RecordedGesture) {
        if (g.points.isEmpty()) return
        val bounds = screenBounds(this)
        val w = bounds.width().coerceAtLeast(1).toFloat()
        val h = bounds.height().coerceAtLeast(1).toFloat()
        val duration = (g.endedAt - g.startedAt).coerceIn(40L, 10000L)
        val normalized = thin(g.points).map { (x, y) -> Pair((x / w).coerceIn(0f, 1f), (y / h).coerceIn(0f, 1f)) }
        val action = MacroAction(
            delayMs = if (lastRecordedEndAt == 0L) 0L else (g.startedAt - lastRecordedEndAt).coerceAtLeast(0L),
            durationMs = duration,
            points = normalized
        )
        val prefs = prefs()
        val array = try { JSONArray(prefs.getString("macro_actions", "[]") ?: "[]") } catch (_: Exception) { JSONArray() }
        array.put(action.toJson())
        prefs.edit().putString("macro_actions", array.toString()).apply()
        lastRecordedEndAt = g.endedAt
        recordedCount = array.length()
        overlay?.setRecordedCount(recordedCount)
        AppLog.write(this, "Makro: zapisano gest #$recordedCount (${normalized.size} próbek, ${duration} ms, opóźnienie ${action.delayMs} ms)")

        if (prefs.getBoolean("macro_passthrough", true)) forwardToGame(g.points, duration)
    }

    /** Odtwarza właśnie nagrany gest w grze, żeby użytkownik widział jego efekt. */
    private fun forwardToGame(px: List<Pair<Float, Float>>, duration: Long) {
        val o = overlay ?: return
        o.beginInjection(px)
        val restore = Runnable { o.endInjection() }
        handler.postDelayed(restore, duration + 1500L) // zabezpieczenie, gdyby callback nie wrócił
        val accepted = try {
            dispatchGesture(buildGesture(clampToScreen(px), duration), object : GestureResultCallback() {
                override fun onCompleted(gestureDescription: GestureDescription?) {
                    handler.removeCallbacks(restore); o.endInjection()
                }
                override fun onCancelled(gestureDescription: GestureDescription?) {
                    handler.removeCallbacks(restore); o.endInjection()
                    AppLog.write(this@IconClickService, "Gest przekazany do gry został anulowany przez Androida")
                }
            }, handler)
        } catch (e: Exception) {
            AppLog.write(this, "BŁĄD przekazania gestu do gry: ${e.message}")
            false
        }
        if (!accepted) {
            handler.removeCallbacks(restore); o.endInjection()
            AppLog.write(this, "Android nie przyjął gestu przekazywanego do gry (gest i tak jest zapisany w makrze)")
        }
    }

    private fun thin(p: List<Pair<Float, Float>>, maxPoints: Int = 300): List<Pair<Float, Float>> {
        if (p.size <= maxPoints) return p
        val step = p.size.toDouble() / maxPoints
        val out = (0 until maxPoints).map { p[(it * step).toInt()] }.toMutableList()
        out[out.size - 1] = p.last()
        return out
    }

    private fun clampToScreen(px: List<Pair<Float, Float>>): List<Pair<Float, Float>> {
        val b = screenBounds(this)
        val w = b.width().coerceAtLeast(2).toFloat()
        val h = b.height().coerceAtLeast(2).toFloat()
        return px.map { (x, y) -> Pair(x.coerceIn(0f, w - 1f), y.coerceIn(0f, h - 1f)) }
    }

    private fun buildGesture(px: List<Pair<Float, Float>>, duration: Long): GestureDescription {
        val path = Path()
        px.forEachIndexed { i, (x, y) -> if (i == 0) path.moveTo(x, y) else path.lineTo(x, y) }
        if (px.size == 1) path.lineTo(px[0].first, px[0].second)
        return GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0L, duration.coerceIn(1L, 60000L)))
            .build()
    }

    override fun onInterrupt() { AppLog.write(this, "Usługa przerwana przez Androida") }

    override fun onDestroy() {
        AppLog.write(this, "Usługa dostępności zatrzymana")
        instance = null
        try { prefs().unregisterOnSharedPreferenceChangeListener(prefListener) } catch (_: Exception) { }
        overlay?.hide()
        overlay = null
        worker.shutdownNow()
        handler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    private fun prefs() = getSharedPreferences("clicker", MODE_PRIVATE)

    private fun isRunning(): Boolean = prefs().getBoolean("running", false) && !prefs().getBoolean("emergency_stop", false)
    private fun isScanEnabled(): Boolean = prefs().getBoolean("scan_enabled", true)
    private fun isAutoClickEnabled(): Boolean = prefs().getBoolean("auto_click_enabled", true)

    // Schedule the next scan only after the previous scan finishes. This avoids
    // queuing repeated screenshots while image matching is still running.
    private fun scheduleScan(delay: Long = 700) {
        handler.postDelayed({
            if (prefs().getBoolean("emergency_stop", false)) {
                if (playbackActive) stopPlaybackInternal()
            } else if (prefs().getBoolean("macro_playing", false) && !playbackActive) {
                startPlayback()
            }
            if (isRunning() && isScanEnabled()) takeAndFind() else scheduleScan(700)
        }, delay)
    }

    private fun finishScan() {
        scanInProgress = false
        scheduleScan(900)
    }

    private fun takeAndFind() {
        if (!isRunning() || !isScanEnabled()) {
            scheduleScan(700)
            return
        }
        if (scanInProgress) return
        val tpl = template
        if (tpl == null) {
            AppLog.write(this, "Brak wzoru ikony — pomijam skan")
            scheduleScan(2000)
            return
        }
        scanInProgress = true
        scanCount++
        AppLog.write(this, "Skan #$scanCount: proszę o zrzut ekranu…")
        try {
            takeScreenshot(Display.DEFAULT_DISPLAY, worker, object : TakeScreenshotCallback {
                override fun onSuccess(result: ScreenshotResult) {
                    var bmp: Bitmap? = null
                    try {
                        val buffer = result.hardwareBuffer
                        bmp = Bitmap.wrapHardwareBuffer(buffer, result.colorSpace)?.copy(Bitmap.Config.ARGB_8888, false)
                        buffer.close()
                        if (bmp == null) {
                            AppLog.write(this@IconClickService, "BŁĄD: nie udało się utworzyć bitmapy zrzutu")
                            return
                        }
                        AppLog.write(this@IconClickService, "Zrzut OK: ${bmp.width}x${bmp.height}px")

                        // If STOP was pressed while the screenshot was being captured,
                        // do not spend time analyzing it or dispatch a late tap.
                        if (!isRunning() || !isScanEnabled()) {
                            AppLog.write(this@IconClickService, "STOP lub wyłączenie wyszukiwania — pomijam analizę zrzutu")
                            return
                        }

                        val found = findTemplate(bmp, tpl)
                        if (!isRunning() || !isScanEnabled()) {
                            AppLog.write(this@IconClickService, "STOP lub wyłączenie wyszukiwania po analizie — kliknięcie pominięte")
                            return
                        }

                        if (found != null) {
                            val x = found.centerX()
                            val y = found.centerY()
                            AppLog.write(this@IconClickService, "ZNALEZIONO ikonę w ${found.left},${found.top}; środek $x,$y")
                            if (!isAutoClickEnabled()) {
                                AppLog.write(this@IconClickService, "Ikona znaleziona, ale automatyczne klikanie jest wyłączone")
                            } else if (System.currentTimeMillis() - lastClickAt > 2500) {
                                lastClickAt = System.currentTimeMillis()
                                val path = android.graphics.Path().apply { moveTo(x.toFloat(), y.toFloat()) }
                                val gesture = android.accessibilityservice.GestureDescription.Builder()
                                    .addStroke(android.accessibilityservice.GestureDescription.StrokeDescription(path, 0, 100))
                                    .build()
                                val accepted = dispatchGesture(gesture, object : GestureResultCallback() {
                                    override fun onCompleted(gestureDescription: android.accessibilityservice.GestureDescription?) {
                                        AppLog.write(this@IconClickService, "Gest kliknięcia zakończony przez Androida (nie gwarantuje reakcji gry)")
                                    }
                                    override fun onCancelled(gestureDescription: android.accessibilityservice.GestureDescription?) {
                                        AppLog.write(this@IconClickService, "Android anulował kliknięcie")
                                    }
                                }, handler)
                                if (!accepted) AppLog.write(this@IconClickService, "BŁĄD: Android nie przyjął gestu kliknięcia")
                            } else {
                                AppLog.write(this@IconClickService, "Kliknięcie pominięte — aktywny limit anty-powtórzeniowy")
                            }
                        } else {
                            AppLog.write(this@IconClickService, "Ikona nieznaleziona w tym skanie")
                        }
                    } catch (e: Exception) {
                        AppLog.write(this@IconClickService, "BŁĄD analizy zrzutu: ${e.javaClass.simpleName}: ${e.message}")
                    } finally {
                        bmp?.recycle()
                        finishScan()
                    }
                }

                override fun onFailure(errorCode: Int) {
                    AppLog.write(this@IconClickService, "BŁĄD zrzutu ekranu, kod: $errorCode")
                    finishScan()
                }
            })
        } catch (e: Exception) {
            AppLog.write(this, "BŁĄD żądania zrzutu: ${e.javaClass.simpleName}: ${e.message}")
            finishScan()
        }
    }

    // ---------------------------------------------------------------------------------------
    // ODTWARZANIE
    // ---------------------------------------------------------------------------------------

    private fun stopPlaybackInternal() {
        playbackActive = false
        playbackGeneration++ // unieważnia spóźnione callbacki poprzedniego odtwarzania
        pendingGesture?.let { handler.removeCallbacks(it) }
        pendingGesture = null
        overlay?.endInjection()
    }

    private fun startPlayback() {
        if (prefs().getBoolean("emergency_stop", false)) return
        val enabled = MacroStore.load(this).filter { it.enabled && it.actions.isNotEmpty() }
        playbackActions = enabled.flatMap { m ->
            m.actions.mapIndexed { i, act -> Step(m.name, i + 1, m.actions.size, act) }
        }
        if (playbackActions.isEmpty()) {
            AppLog.write(this, "Brak włączonych makr z gestami. Zaznacz makra na liście algorytmu.")
            prefs().edit().putBoolean("macro_playing", false).apply()
            return
        }
        playbackGeneration++
        playbackActive = true
        playbackIndex = 0
        playbackRepeat = 0
        AppLog.write(this, "Bot: ${enabled.size} makr (${enabled.joinToString(" → ") { it.name }}), ${playbackActions.size} gestów, ${prefs().getInt("macro_repetitions", 1).coerceIn(1, 999)} powtórzeń algorytmu")
        playNextAction(playbackGeneration)
    }

    private fun playNextAction(gen: Int) {
        if (gen != playbackGeneration || !playbackActive) return
        if (!prefs().getBoolean("macro_playing", false) || prefs().getBoolean("emergency_stop", false)) {
            stopPlaybackInternal()
            return
        }
        if (playbackIndex >= playbackActions.size) {
            playbackRepeat++
            val total = prefs().getInt("macro_repetitions", 1).coerceIn(1, 999)
            if (playbackRepeat >= total) {
                AppLog.write(this, "Bot: algorytm zakończony")
                stopPlaybackInternal()
                prefs().edit().putBoolean("macro_playing", false).apply()
                return
            }
            playbackIndex = 0
        }
        val step = playbackActions[playbackIndex]
        val action = step.action
        val minDelay = prefs().getInt("macro_delay_min", 100).coerceIn(0, 60000)
        val maxDelay = prefs().getInt("macro_delay_max", 500).coerceIn(minDelay, 60000)
        val randomDelay = if (maxDelay == minDelay) minDelay.toLong() else Random.nextLong(minDelay.toLong(), maxDelay.toLong() + 1)
        val startExtra = if (playbackIndex == 0 && playbackRepeat == 0) BOT_START_DELAY_MS else 0L // czas na puszczenie przycisku START
        val gap = (action.delayMs + randomDelay + startExtra).coerceAtMost(120000L)
        playbackIndex++
        val runnable = Runnable {
            pendingGesture = null
            if (gen != playbackGeneration || !playbackActive) return@Runnable
            if (!prefs().getBoolean("macro_playing", false) || prefs().getBoolean("emergency_stop", false)) return@Runnable
            dispatchMacroGesture(step, gen)
        }
        pendingGesture = runnable
        handler.postDelayed(runnable, gap)
    }

    private fun dispatchMacroGesture(step: Step, gen: Int) {
        val action = step.action
        if (action.points.isEmpty()) {
            playNextAction(gen)
            return
        }
        val b = screenBounds(this)
        val width = b.width().coerceAtLeast(2).toFloat()
        val height = b.height().coerceAtLeast(2).toFloat()
        val px = action.points.map { (fx, fy) ->
            Pair((fx * width).coerceIn(0f, width - 1f), (fy * height).coerceIn(0f, height - 1f))
        }
        overlay?.beginInjection(px) // przycisk STOP przepuszcza dotyk tylko, gdy gest przechodzi przez jego obszar
        val accepted = try {
            dispatchGesture(buildGesture(px, action.durationMs.coerceIn(40L, 10000L)), object : GestureResultCallback() {
                override fun onCompleted(gestureDescription: GestureDescription?) {
                    overlay?.endInjection()
                    if (gen != playbackGeneration || !playbackActive) return
                    AppLog.write(this@IconClickService, "Bot: „${step.macroName}” — gest ${step.number}/${step.total}")
                    playNextAction(gen)
                }
                override fun onCancelled(gestureDescription: GestureDescription?) {
                    overlay?.endInjection()
                    if (gen != playbackGeneration || !playbackActive) return
                    AppLog.write(this@IconClickService, "Makro: Android anulował gest; zatrzymuję odtwarzanie")
                    prefs().edit().putBoolean("macro_playing", false).apply()
                }
            }, handler)
        } catch (e: Exception) {
            AppLog.write(this, "BŁĄD wysyłania gestu: ${e.javaClass.simpleName}: ${e.message}")
            false
        }
        if (!accepted) {
            overlay?.endInjection()
            AppLog.write(this, "Makro: Android odrzucił gest; zatrzymuję odtwarzanie")
            prefs().edit().putBoolean("macro_playing", false).apply()
        }
    }

    private fun findTemplate(screen: Bitmap, original: Bitmap): Rect? {
        val scales = floatArrayOf(0.85f, 1.0f, 1.15f)
        var bestScore = 0.0
        var bestRect: Rect? = null
        for (scale in scales) {
            val tw = (original.width * scale).toInt()
            val th = (original.height * scale).toInt()
            if (tw < 20 || th < 20 || tw >= screen.width || th >= screen.height) continue
            val temp = Bitmap.createScaledBitmap(original, tw, th, true)
            val step = 8
            var localScore = 0.0
            var bx = -1
            var by = -1
            val maxY = screen.height - th
            val maxX = screen.width - tw
            var y = 0
            while (y <= maxY) {
                var x = 0
                while (x <= maxX) {
                    val score = similarity(screen, temp, x, y)
                    if (score > localScore) { localScore = score; bx = x; by = y }
                    x += step
                }
                y += step
            }
            if (bx >= 0) {
                val x0 = max(0, bx - step); val x1 = min(maxX, bx + step)
                val y0 = max(0, by - step); val y1 = min(maxY, by + step)
                for (yy in y0..y1) for (xx in x0..x1) {
                    val score = similarity(screen, temp, xx, yy)
                    if (score > localScore) { localScore = score; bx = xx; by = yy }
                }
                if (localScore > bestScore) {
                    bestScore = localScore
                    bestRect = Rect(bx, by, bx + tw, by + th)
                }
            }
            if (temp !== original) temp.recycle()
        }
        AppLog.write(this, "Najlepsze podobieństwo: ${"%.3f".format(java.util.Locale.US, bestScore)}")
        return if (bestScore >= 0.88) bestRect else null
    }

    private fun similarity(screen: Bitmap, temp: Bitmap, left: Int, top: Int): Double {
        var error = 0.0
        var count = 0
        var y = 1
        while (y < temp.height - 1) {
            var x = 1
            while (x < temp.width - 1) {
                val a = screen.getPixel(left + x, top + y)
                val b = temp.getPixel(x, y)
                val dr = kotlin.math.abs(Color.red(a) - Color.red(b))
                val dg = kotlin.math.abs(Color.green(a) - Color.green(b))
                val db = kotlin.math.abs(Color.blue(a) - Color.blue(b))
                error += (dr + dg + db) / (3.0 * 255.0)
                count++
                x += 8
            }
            y += 8
        }
        return if (count == 0) 0.0 else 1.0 - error / count
    }
}
