package ai.toolus.mobileguide

import android.os.Handler
import android.os.Looper
import java.util.concurrent.CopyOnWriteArrayList

/** Process-wide link between the chat activity and the accessibility service. */
object GuideHub {
    data class ChatMessage(val role: String, val text: String, val ts: Long = System.currentTimeMillis())

    val messages = CopyOnWriteArrayList<ChatMessage>()
    private val main = Handler(Looper.getMainLooper())
    @Volatile var listener: (() -> Unit)? = null
    @Volatile var service: GuideAccessibilityService? = null

    fun post(role: String, text: String) {
        messages.add(ChatMessage(role, text))
        main.post { listener?.invoke() }
    }

    fun isServiceOn(): Boolean = service != null

    fun awaitingUserAnswer(): Boolean = service?.session?.awaitingUserAnswer() == true

    fun startTask(task: String): Boolean {
        val s = service ?: return false
        main.post { s.session.start(task) }
        return true
    }

    fun userReply(text: String) {
        val s = service ?: return
        main.post { s.session.onUserMessage(text) }
    }

    fun stop() {
        val s = service ?: return
        main.post { s.session.stop("채팅에서 중단했습니다.") }
    }
}
