package ai.toolus.mobileguide

import android.accessibilityservice.AccessibilityService
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.util.Log
import android.view.accessibility.AccessibilityEvent

/** Reads the screen and draws the guide over it. It has no gesture capability: it never acts for the person. */
class GuideAccessibilityService : AccessibilityService() {
    lateinit var overlay: GuideOverlay
        private set
    lateinit var session: GuideSession
        private set

    /** Shell-only debug hooks (protected by android.permission.DUMP, which only adb shell holds). */
    private val debugReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                ACTION_DEBUG_START -> intent.getStringExtra("task")?.let { session.start(it) }
                ACTION_DEBUG_REPLY -> intent.getStringExtra("text")?.let { session.onUserMessage(it) }
                ACTION_DEBUG_STOP -> session.stop("디버그 중단")
                ACTION_DEBUG_DUMP -> session.debugDump().lines().forEach { Log.i(GuideSession.TAG, "DUMP $it") }
                ACTION_DEBUG_BENCH -> intent.getIntExtra("stale", 0).let { k ->
                    if (k > 0) session.debugStale(k, intent.getIntExtra("gap", 150).toLong()) else Log.i(GuideSession.TAG, session.debugBench())
                }
                ACTION_DEBUG_DECLARE -> intent.getStringExtra("kind")?.let { session.debugDeclare(it) }
                ACTION_DEBUG_ACTION -> intent.getStringExtra("json")?.let { session.debugAction(it) }
                // Where the two servers are, and which mode the app asks the guide server for. Without extras it only reports.
                ACTION_DEBUG_CONFIG -> {
                    intent.getStringExtra("guide_base")?.let { GuideSettings.guideBase = it }
                    intent.getStringExtra("mode")?.let { GuideSettings.guideMode = it }
                    intent.getStringExtra("tts_base")?.let { GuideSettings.ttsBase = it }
                    intent.getStringExtra("stream")?.let { GuideSettings.ttsStream = it == "true" || it == "1" }
                    // the speech server's address can end in a secret path: the log names the host only
                    Log.i(GuideSession.TAG, "CONFIG guide=${GuideSettings.guideBase} mode=${GuideSettings.guideMode.ifEmpty { "-" }} " +
                        "tts=${GuideSettings.hostOf(GuideSettings.ttsBase).ifEmpty { "-" }} engine=${GuideSettings.VOICE} " +
                        "stream=${GuideSettings.ttsStream}")
                    GuideHub.post("system", "안내 서버: ${GuideSettings.guideBase}")
                }
            }
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        GuideSettings.init(this)
        overlay = GuideOverlay(this)
        session = GuideSession(this, overlay)
        overlay.onOutsideTouch = { x, y -> session.onTouch(x, y) }
        overlay.ensureTouchProbe()
        GuideHub.service = this
        val f = IntentFilter().apply {
            addAction(ACTION_DEBUG_START); addAction(ACTION_DEBUG_REPLY); addAction(ACTION_DEBUG_STOP)
            addAction(ACTION_DEBUG_DUMP); addAction(ACTION_DEBUG_ACTION); addAction(ACTION_DEBUG_BENCH)
            addAction(ACTION_DEBUG_DECLARE); addAction(ACTION_DEBUG_CONFIG)
        }
        registerReceiver(debugReceiver, f, "android.permission.DUMP", null, Context.RECEIVER_EXPORTED)
        Log.i(GuideSession.TAG, "service connected")
        GuideHub.post("system", "화면 안내 서비스가 켜졌습니다.")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        if (!::session.isInitialized) return
        session.onEvent(event)
    }

    override fun onInterrupt() {}

    override fun onUnbind(intent: Intent?): Boolean {
        cleanup()
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        cleanup()
        super.onDestroy()
    }

    private fun cleanup() {
        if (GuideHub.service === this) GuideHub.service = null
        try { unregisterReceiver(debugReceiver) } catch (_: Exception) {}
        if (::overlay.isInitialized) overlay.hideAll()
    }

    companion object {
        const val ACTION_DEBUG_START = "ai.toolus.mobileguide.DEBUG_START"
        const val ACTION_DEBUG_REPLY = "ai.toolus.mobileguide.DEBUG_REPLY"
        const val ACTION_DEBUG_STOP = "ai.toolus.mobileguide.DEBUG_STOP"
        const val ACTION_DEBUG_DUMP = "ai.toolus.mobileguide.DEBUG_DUMP"
        const val ACTION_DEBUG_ACTION = "ai.toolus.mobileguide.DEBUG_ACTION"
        const val ACTION_DEBUG_BENCH = "ai.toolus.mobileguide.DEBUG_BENCH"
        const val ACTION_DEBUG_DECLARE = "ai.toolus.mobileguide.DEBUG_DECLARE"
        const val ACTION_DEBUG_CONFIG = "ai.toolus.mobileguide.DEBUG_CONFIG"
    }
}
