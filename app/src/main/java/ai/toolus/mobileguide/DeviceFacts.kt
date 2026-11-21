package ai.toolus.mobileguide

import android.app.AlarmManager
import android.app.UiModeManager
import android.content.Context
import android.media.AudioManager
import android.provider.Settings
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Settings of the phone, read from the system itself. They go to the guide server with every request (`facts`), so
 * "is the text bigger now?" or "is there an alarm at 8?" is answered by the setting, not by judging a picture of the
 * screen. Every value here is readable without a permission prompt; one that cannot be read is left out.
 */
object DeviceFacts {
    fun read(c: Context): JSONObject {
        val cr = c.contentResolver
        val o = JSONObject()
        fun put(key: String, value: () -> Any?) {
            runCatching { value() }.getOrNull()?.let { o.put(key, it) }
        }
        put("font_scale") { Math.round(Settings.System.getFloat(cr, Settings.System.FONT_SCALE, 1f) * 100) / 100.0 }
        put("brightness") { Settings.System.getInt(cr, Settings.System.SCREEN_BRIGHTNESS) }
        put("media_volume") { c.getSystemService(AudioManager::class.java)?.getStreamVolume(AudioManager.STREAM_MUSIC) }
        put("ring_volume") { c.getSystemService(AudioManager::class.java)?.getStreamVolume(AudioManager.STREAM_RING) }
        put("dark_mode") { c.getSystemService(UiModeManager::class.java)?.nightMode == UiModeManager.MODE_NIGHT_YES }
        put("airplane_mode") { Settings.Global.getInt(cr, Settings.Global.AIRPLANE_MODE_ON, 0) == 1 }
        // The next alarm any clock app has set, as the time of day it rings ("08:00"); "" when there is none.
        put("next_alarm") {
            c.getSystemService(AlarmManager::class.java)?.nextAlarmClock?.triggerTime
                ?.let { SimpleDateFormat("HH:mm", Locale.US).format(Date(it)) } ?: ""
        }
        return o
    }
}
