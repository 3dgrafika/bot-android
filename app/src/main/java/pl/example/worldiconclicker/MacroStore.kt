package pl.example.worldiconclicker

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** Nazwane makro: lista gestów, które można włączać/wyłączać jako krok algorytmu bota. */
data class Macro(
    val id: Long,
    val name: String,
    val enabled: Boolean,
    val actions: List<MacroAction>
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("name", name)
        put("enabled", enabled)
        put("actions", JSONArray().apply { actions.forEach { put(it.toJson()) } })
    }

    companion object {
        fun fromJson(o: JSONObject): Macro {
            val arr = o.optJSONArray("actions") ?: JSONArray()
            return Macro(
                id = o.optLong("id"),
                name = o.optString("name", "Makro"),
                enabled = o.optBoolean("enabled", true),
                actions = (0 until arr.length()).map { MacroAction.fromJson(arr.getJSONObject(it)) }
            )
        }
    }
}

/** Lista makr trzymana w SharedPreferences ("macros"). Kolejność listy = kolejność wykonywania w bocie. */
object MacroStore {
    private const val PREFS = "clicker"
    private const val KEY = "macros"

    private fun prefs(c: Context) = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun rawJson(c: Context): String = prefs(c).getString(KEY, "[]") ?: "[]"

    @Synchronized
    fun load(c: Context): List<Macro> = try {
        val arr = JSONArray(rawJson(c))
        (0 until arr.length()).map { Macro.fromJson(arr.getJSONObject(it)) }
    } catch (_: Exception) {
        emptyList()
    }

    @Synchronized
    private fun save(c: Context, list: List<Macro>) {
        val arr = JSONArray()
        list.forEach { arr.put(it.toJson()) }
        prefs(c).edit().putString(KEY, arr.toString()).apply()
    }

    fun parseActions(json: String): List<MacroAction> = try {
        val arr = JSONArray(json)
        (0 until arr.length()).map { MacroAction.fromJson(arr.getJSONObject(it)) }
    } catch (_: Exception) {
        emptyList()
    }

    /**
     * Jednorazowa migracja z poprzedniej wersji (jedno nienazwane makro w "macro_actions").
     * Wywoływać tylko poza nagrywaniem — przy starcie aplikacji i połączeniu usługi.
     */
    @Synchronized
    fun migrateLegacy(c: Context) {
        val p = prefs(c)
        if (p.contains(KEY)) return
        val legacy = parseActions(p.getString("macro_actions", "[]") ?: "[]")
        if (legacy.isEmpty()) {
            save(c, emptyList())
        } else {
            save(c, listOf(Macro(System.currentTimeMillis(), "Makro (poprzednie)", true, legacy)))
            p.edit().putString("macro_actions", "[]").apply()
        }
    }

    @Synchronized
    fun add(c: Context, name: String, actions: List<MacroAction>): Macro {
        val macro = Macro(System.currentTimeMillis(), name, true, actions)
        save(c, load(c) + macro)
        return macro
    }

    @Synchronized
    fun setEnabled(c: Context, id: Long, enabled: Boolean) {
        save(c, load(c).map { if (it.id == id) it.copy(enabled = enabled) else it })
    }

    @Synchronized
    fun remove(c: Context, id: Long) {
        save(c, load(c).filter { it.id != id })
    }

    /** Przesuwa makro w liście o delta pozycji (−1 = wyżej, +1 = niżej). */
    @Synchronized
    fun move(c: Context, id: Long, delta: Int) {
        val list = load(c).toMutableList()
        val i = list.indexOfFirst { it.id == id }
        val j = i + delta
        if (i < 0 || j < 0 || j >= list.size) return
        val m = list.removeAt(i)
        list.add(j, m)
        save(c, list)
    }
}
