package pl.example.worldiconclicker

import android.content.Context
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object AppLog {
    @Synchronized
    fun write(context: Context, message: String) {
        val prefs = context.getSharedPreferences("clicker", Context.MODE_PRIVATE)
        val time = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
        val old = prefs.getString("logs", "") ?: ""
        val updated = (old + "[$time] $message\n").takeLast(12000)
        prefs.edit().putString("logs", updated).apply()
    }
}
