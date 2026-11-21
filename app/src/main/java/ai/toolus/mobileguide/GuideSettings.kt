package ai.toolus.mobileguide

import android.content.Context
import android.content.SharedPreferences

/**
 * Where the two servers are and which of their ways of working the app asks for (docs/interface.md).
 * The default addresses are given when the app is built (`haebom.guideBase`, `haebom.ttsBase` in local.properties,
 * see app/build.gradle.kts); a build that names none uses this computer through `adb reverse`.
 * Set from adb (DEBUG_CONFIG, shell-only) and kept in app-private storage. No model key is on the phone:
 * the servers hold them.
 */
object GuideSettings {
    val DEFAULT_GUIDE_BASE: String = BuildConfig.GUIDE_BASE
    val DEFAULT_TTS_BASE: String = BuildConfig.TTS_BASE
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
        set(v) = if (v.trim() == "default") p().edit().remove("guide_base").apply() else set("guide_base", v.trim().trimEnd('/'))

    /** The request's `mode`; empty leaves the choice to the server's own default. */
    var guideMode: String
        get() = get("guide_mode", BASELINE)
        set(v) = set("guide_mode", v)

    /**
     * The TTS server (haebom-tts): `POST /tts/stream`, `POST /tts`, and `GET /stt/live` for what the person says.
     * Empty means the phone's own voice only. `default` goes back to the address the app was built with.
     */
    var ttsBase: String
        get() = get("tts_base", DEFAULT_TTS_BASE)
        set(v) = if (v.trim() == "default") p().edit().remove("tts_base").apply() else set("tts_base", v.trim().trimEnd('/'))

    /**
     * How the sound is fetched. True (the default): in pieces (`POST /tts/stream`), played as they come.
     * False: one whole file (`POST /tts`, with [ttsEngine]).
     */
    var ttsStream: Boolean
        get() = get("tts_stream", "true") == "true"
        set(v) = set("tts_stream", v.toString())

    /** An address without its path (`http://host:port`), for the screen and the log. */
    fun hostOf(base: String): String = Regex("^(https?://[^/]+)").find(base)?.groupValues?.get(1) ?: base

    /** The `engine` of a `POST /tts` request; empty leaves the choice to the server's own default. */
    var ttsEngine: String
        get() = get("tts_engine", BASELINE)
        set(v) = set("tts_engine", v)
}
