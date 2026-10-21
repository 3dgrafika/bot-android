package pl.example.worldiconclicker

import android.app.Activity
import android.content.Intent
import android.content.ClipData
import android.content.ClipboardManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.widget.Button
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.EditText
import android.text.InputType
import android.widget.ScrollView
import android.widget.TextView
import android.view.Gravity
import android.app.AlertDialog

class MainActivity : Activity() {
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var logView: TextView
    private lateinit var macroListContainer: LinearLayout
    private var lastMacrosRaw: String? = null
    private val refreshLogs = object : Runnable {
        override fun run() {
            if (::logView.isInitialized) {
                val prefs = getSharedPreferences("clicker", MODE_PRIVATE)
                logView.text = prefs.getString("logs", "Brak logów. Uruchom aplikację ponownie.") ?: "Brak logów."
            }
            // Usługa dodaje makra po zakończeniu nagrywania — odśwież listę, gdy się zmieniła.
            if (::macroListContainer.isInitialized && MacroStore.rawJson(this@MainActivity) != lastMacrosRaw) renderMacroList()
            handler.postDelayed(this, 700)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val prefs = getSharedPreferences("clicker", MODE_PRIVATE)
        MacroStore.migrateLegacy(this)
        if (prefs.getString("logs", null) == null) AppLog.write(this, "Aplikacja uruchomiona")

        val info = TextView(this).apply {
            text = "Klikacz ikony Świat\n\n" +
                "1. Włącz usługę dostępności.\n" +
                "2. Naciśnij START i przejdź do ekranu z ikoną.\n" +
                "3. Obserwuj logi poniżej.\n"
            textSize = 16f
            setPadding(24, 24, 24, 12)
        }
        val enable = Button(this).apply {
            text = "Ustawienia dostępności"
            setOnClickListener {
                AppLog.write(this@MainActivity, "Otwieram ustawienia dostępności")
                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            }
        }
        val scanCheck = CheckBox(this).apply {
            text = "Wyszukiwanie ikony na ekranie"
            isChecked = prefs.getBoolean("scan_enabled", true)
            setOnCheckedChangeListener { _, checked ->
                prefs.edit().putBoolean("scan_enabled", checked).apply()
                AppLog.write(this@MainActivity, if (checked) "Wyszukiwanie ikony włączone" else "Wyszukiwanie ikony wyłączone")
            }
        }
        val clickCheck = CheckBox(this).apply {
            text = "Automatyczne klikanie znalezionej ikony"
            isChecked = prefs.getBoolean("auto_click_enabled", true)
            setOnCheckedChangeListener { _, checked ->
                prefs.edit().putBoolean("auto_click_enabled", checked).apply()
                AppLog.write(this@MainActivity, if (checked) "Automatyczne klikanie włączone" else "Automatyczne klikanie wyłączone")
            }
        }
        val passCheck = CheckBox(this).apply {
            text = "Podczas nagrywania przekazuj gesty do gry"
            isChecked = prefs.getBoolean("macro_passthrough", true)
            setOnCheckedChangeListener { _, checked ->
                prefs.edit().putBoolean("macro_passthrough", checked).apply()
                AppLog.write(this@MainActivity, if (checked) "Nagrywanie: gesty będą przekazywane do gry" else "Nagrywanie: gesty tylko zapisywane (gra ich nie dostanie)")
            }
        }
        val start = Button(this).apply {
            text = "START — uruchom usługę"
            setOnClickListener {
                prefs.edit().putBoolean("running", true).putBoolean("emergency_stop", false).apply()
                AppLog.write(this@MainActivity, "START: usługa uruchomiona; funkcje są sterowane checkboxami")
            }
        }
        val stop = Button(this).apply {
            text = "STOP"
            setOnClickListener {
                prefs.edit().putBoolean("running", false).apply()
                AppLog.write(this@MainActivity, "STOP: wyszukiwanie wyłączone")
            }
        }
        val macroTitle = TextView(this).apply {
            text = "MAKRA DOTYKOWE"
            textSize = 14f
            setPadding(24, 12, 24, 4)
        }
        val macroInfo = TextView(this).apply {
            text = "1) Wpisz nazwę i naciśnij NAGRYWAJ — dotykaj ekranu gry, zakończ czerwonym przyciskiem ■ u góry (jeden palec, bez pinch-zoom).\n2) Nagrane makra pojawią się na liście poniżej. Zaznaczone makra tworzą algorytm bota i wykonują się po kolei, od góry do dołu.\n3) Naciśnij URUCHOM BOTA, przejdź do gry i dotknij zielonego przycisku ▶ START BOTA na ekranie."
            textSize = 12f
            setPadding(24, 0, 24, 8)
        }
        val repeatInput = EditText(this).apply {
            hint = "Liczba powtórzeń całego algorytmu (1–999)"
            inputType = InputType.TYPE_CLASS_NUMBER
            setText(prefs.getInt("macro_repetitions", 1).toString())
        }
        val delayMinInput = EditText(this).apply {
            hint = "Minimalne losowe opóźnienie (ms)"
            inputType = InputType.TYPE_CLASS_NUMBER
            setText(prefs.getInt("macro_delay_min", 100).toString())
        }
        val delayMaxInput = EditText(this).apply {
            hint = "Maksymalne losowe opóźnienie (ms)"
            inputType = InputType.TYPE_CLASS_NUMBER
            setText(prefs.getInt("macro_delay_max", 500).toString())
        }
        val saveMacroSettings = Button(this).apply {
            text = "Zapisz ustawienia makra"
            setOnClickListener {
                val repetitions = repeatInput.text.toString().toIntOrNull()?.coerceIn(1, 999) ?: 1
                val minDelay = delayMinInput.text.toString().toIntOrNull()?.coerceIn(0, 60000) ?: 100
                val maxDelay = delayMaxInput.text.toString().toIntOrNull()?.coerceIn(minDelay, 60000) ?: maxOf(minDelay, 500)
                prefs.edit().putInt("macro_repetitions", repetitions)
                    .putInt("macro_delay_min", minDelay).putInt("macro_delay_max", maxDelay).apply()
                repeatInput.setText(repetitions.toString())
                delayMinInput.setText(minDelay.toString())
                delayMaxInput.setText(maxDelay.toString())
                AppLog.write(this@MainActivity, "Ustawienia makra zapisane: $repetitions powtórzeń, opóźnienie $minDelay–$maxDelay ms")
            }
        }
        val macroNameInput = EditText(this).apply {
            hint = "Nazwa nowego makra (np. Zbieranie)"
            inputType = InputType.TYPE_CLASS_TEXT
            setSingleLine(true)
        }
        val recordMacro = Button(this).apply {
            text = "NAGRYWAJ MAKRO"
            setOnClickListener {
                if (!serviceReady()) return@setOnClickListener
                prefs.edit().putBoolean("macro_recording", true).putBoolean("macro_playing", false)
                    .putBoolean("bot_armed", false).putBoolean("emergency_stop", false).putBoolean("running", false)
                    .putString("macro_actions", "[]")
                    .putString("macro_recording_name", macroNameInput.text.toString().trim()).apply()
                macroNameInput.setText("")
                AppLog.write(this@MainActivity, "Żądanie nagrywania makra. Przechodzę do poprzedniej aplikacji — wykonaj gesty, potem naciśnij czerwony przycisk ■ u góry ekranu.")
                moveTaskToBack(true)
            }
        }
        val stopRecording = Button(this).apply {
            text = "ZAKOŃCZ NAGRYWANIE / ZAPISZ"
            setOnClickListener {
                prefs.edit().putBoolean("macro_recording", false).apply()
                // Komunikat o zakończeniu i liczbie akcji zapisuje usługa; tu tylko, gdy usługi nie ma.
                if (IconClickService.instance == null) AppLog.write(this@MainActivity, "Usługa dostępności nie działa — nic nie było nagrywane.")
            }
        }
        val replayMacro = Button(this).apply {
            text = "URUCHOM BOTA (pokaż przycisk START w grze)"
            setOnClickListener {
                if (!serviceReady()) return@setOnClickListener
                val ready = MacroStore.load(this@MainActivity).filter { it.enabled && it.actions.isNotEmpty() }
                if (ready.isEmpty()) {
                    AppLog.write(this@MainActivity, "Brak włączonych makr. Nagraj makro i zaznacz je na liście algorytmu.")
                    return@setOnClickListener
                }
                prefs.edit().putBoolean("emergency_stop", false).putBoolean("macro_recording", false)
                    .putBoolean("macro_playing", false).putBoolean("running", false).putBoolean("bot_armed", true).apply()
                AppLog.write(this@MainActivity, "Bot gotowy: ${ready.joinToString(" → ") { it.name }}. Przejdź do gry i dotknij zielonego przycisku ▶ START BOTA.")
                moveTaskToBack(true)
            }
        }
        val emergencyStop = Button(this).apply {
            text = "AWARYJNE ZATRZYMANIE"
            setOnClickListener {
                prefs.edit().putBoolean("emergency_stop", true).putBoolean("macro_recording", false)
                    .putBoolean("macro_playing", false).putBoolean("bot_armed", false).putBoolean("running", false).apply()
                AppLog.write(this@MainActivity, "AWARYJNE ZATRZYMANIE: zatrzymano dalsze makro i automatyczne skanowanie/klikanie.")
            }
        }
        val algoTitle = TextView(this).apply {
            text = "ALGORYTM BOTA — makra wykonywane po kolei"
            textSize = 14f
            setPadding(24, 16, 24, 4)
        }
        macroListContainer = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val copy = Button(this).apply {
            text = "Kopiuj logi"
            setOnClickListener {
                val logs = prefs.getString("logs", "") ?: ""
                val clipboard = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
                clipboard.setPrimaryClip(ClipData.newPlainText("Logi Klikacza Świat", logs))
                AppLog.write(this@MainActivity, "Logi skopiowane do schowka")
            }
        }
        val clear = Button(this).apply {
            text = "Wyczyść logi"
            setOnClickListener { prefs.edit().putString("logs", "").apply(); AppLog.write(this@MainActivity, "Logi wyczyszczone") }
        }
        val logTitle = TextView(this).apply {
            text = "LOGI DZIAŁANIA"
            textSize = 14f
            setPadding(24, 12, 24, 8)
        }
        logView = TextView(this).apply {
            textSize = 12f
            setTextIsSelectable(true)
            setPadding(24, 12, 24, 24)
        }
        val logScroll = ScrollView(this).apply { addView(logView) }
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(info)
            addView(enable)
            addView(scanCheck)
            addView(clickCheck)
            addView(passCheck)
            addView(start)
            addView(stop)
            addView(macroTitle)
            addView(macroInfo)
            addView(repeatInput)
            addView(delayMinInput)
            addView(delayMaxInput)
            addView(saveMacroSettings)
            addView(macroNameInput)
            addView(recordMacro)
            addView(stopRecording)
            addView(algoTitle)
            addView(macroListContainer)
            addView(replayMacro)
            addView(emergencyStop)
            addView(copy)
            addView(clear)
            addView(logTitle)
            addView(logScroll, LinearLayout.LayoutParams(-1, 320))
            setPadding(12, 12, 12, 12)
        }
        // Cały panel jest przewijalny, żeby przyciski makr nie znikały poza ekranem.
        val rootScroll = ScrollView(this).apply {
            isFillViewport = true
            addView(layout)
        }
        setContentView(rootScroll)
        renderMacroList()
        handler.post(refreshLogs)
    }

    private fun renderMacroList() {
        lastMacrosRaw = MacroStore.rawJson(this)
        macroListContainer.removeAllViews()
        val macros = MacroStore.load(this)
        if (macros.isEmpty()) {
            macroListContainer.addView(TextView(this).apply {
                text = "Brak zapisanych makr. Wpisz nazwę i naciśnij NAGRYWAJ MAKRO."
                textSize = 12f
                setPadding(24, 8, 24, 8)
            })
            return
        }
        macros.forEachIndexed { index, m ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            val check = CheckBox(this).apply {
                text = "${index + 1}. ${m.name} (${m.actions.size} gestów)"
                isChecked = m.enabled
                setOnCheckedChangeListener { _, checked ->
                    MacroStore.setEnabled(this@MainActivity, m.id, checked)
                    lastMacrosRaw = MacroStore.rawJson(this@MainActivity)
                    AppLog.write(this@MainActivity, "Makro „${m.name}”: ${if (checked) "włączone" else "wyłączone"} w algorytmie")
                }
            }
            row.addView(check, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            fun small(label: String, onClick: () -> Unit) = Button(this).apply {
                text = label
                minWidth = 0
                minimumWidth = 0
                setPadding(20, 0, 20, 0)
                setOnClickListener { onClick() }
            }
            row.addView(small("▲") { MacroStore.move(this@MainActivity, m.id, -1); renderMacroList() })
            row.addView(small("▼") { MacroStore.move(this@MainActivity, m.id, 1); renderMacroList() })
            row.addView(small("Usuń") {
                AlertDialog.Builder(this@MainActivity)
                    .setMessage("Usunąć makro „${m.name}”?")
                    .setPositiveButton("Usuń") { _, _ ->
                        MacroStore.remove(this@MainActivity, m.id)
                        AppLog.write(this@MainActivity, "Makro „${m.name}” usunięte")
                        renderMacroList()
                    }
                    .setNegativeButton("Anuluj", null)
                    .show()
            })
            macroListContainer.addView(row)
        }
    }

    /** Zwraca true, jeśli usługa dostępności jest połączona; w przeciwnym razie loguje i otwiera ustawienia. */
    private fun serviceReady(): Boolean {
        if (IconClickService.instance != null) return true
        AppLog.write(this, "Usługa dostępności NIE jest włączona. Włącz „Klikacz ikony Świat” w ustawieniach dostępności (na Androidzie 13+ przy instalacji z APK może być potrzebne: Informacje o aplikacji → ⋮ → Zezwól na ustawienia z ograniczeniami).")
        startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        return false
    }

    override fun onDestroy() {
        handler.removeCallbacks(refreshLogs)
        super.onDestroy()
    }
}
