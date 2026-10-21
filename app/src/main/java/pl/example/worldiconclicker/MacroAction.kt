package pl.example.worldiconclicker

import org.json.JSONArray
import org.json.JSONObject

data class MacroAction(
    val delayMs: Long,
    val durationMs: Long,
    val points: List<Pair<Float, Float>>
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("delayMs", delayMs)
        put("durationMs", durationMs)
        put("points", JSONArray().apply {
            points.forEach { (x, y) -> put(JSONArray().put(x.toDouble()).put(y.toDouble())) }
        })
    }

    companion object {
        fun fromJson(obj: JSONObject): MacroAction {
            val pointsJson = obj.getJSONArray("points")
            val points = (0 until pointsJson.length()).map { i ->
                val p = pointsJson.getJSONArray(i)
                Pair(p.getDouble(0).toFloat(), p.getDouble(1).toFloat())
            }
            return MacroAction(obj.optLong("delayMs", 0), obj.optLong("durationMs", 100), points)
        }
    }
}
