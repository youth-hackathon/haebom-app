package ai.toolus.mobileguide

import android.content.Context
import android.content.SharedPreferences

/**
 * Where the two servers are and which of their ways of working the app asks for (docs/interface.md).
 * The defaults are this computer through `adb reverse`, and `baseline` on both servers.
 * Set from adb (DEBUG_CONFIG, shell-only) and kept in app-private storage. No credential is on the phone:
 * the guide server holds the model keys.
 */
object GuideSettings {
    const val DEFAULT_GUIDE_BASE = "http://127.0.0.1:8765"
    const val DEFAULT_TTS_BASE = "http://127.0.0.1:8766"
    const val BASELINE = "baseline"

    @Volatile private var prefs: SharedPreferences? = null

    fun init(context: Context) {
        if (prefs != null) return
        prefs = context.applicationContext.getSharedPreferences("guide", Context.MODE_PRIVATE)
    }

    private fun p() = prefs ?: throw IllegalStateException("GuideSettings.init was not called")

    private fun get(key: String, default: String) = p().getString(key, null) ?: default

    private fun set(key: String, value: String) = p().edit().putString(key, value.trim()).apply()

    /** The guide server (haebom-artemis): `POST /v1/guide/next`, `GET /health`. */
    var guideBase: String
        get() = get("guide_base", DEFAULT_GUIDE_BASE)
        set(v) = set("guide_base", v.trim().trimEnd('/'))

    /** The request's `mode`; empty leaves the choice to the server's own default. */
    var guideMode: String
        get() = get("guide_mode", BASELINE)
        set(v) = set("guide_mode", v)

    /** The TTS server (haebom-tts): `POST /tts`; empty means the phone's own voice only. */
    var ttsBase: String
        get() = get("tts_base", DEFAULT_TTS_BASE)
        set(v) = set("tts_base", v.trim().trimEnd('/'))

    /** The request's `engine`; empty leaves the choice to the server's own default. */
    var ttsEngine: String
        get() = get("tts_engine", BASELINE)
        set(v) = set("tts_engine", v)
}
