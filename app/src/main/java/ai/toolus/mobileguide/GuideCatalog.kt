package ai.toolus.mobileguide

import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import kotlin.math.max

/**
 * Auto action ↔ guide pair catalog for Android.
 *
 * The planner sees only the artemis automation surface. The guide server resolves every element target the way
 * artemis executes it — an index lands on the element's center (`_resolve_index`), a coordinate is used
 * as given — and sends `target_point_px`. Here each action becomes a guide pair whose aim and built-in
 * completion are derived from the accessibility tree at that point:
 * - aim: the node a tap at that point reaches (hit-test: smallest node containing it with the needed
 *   action); a TGP geometry anchor only where no node exists (canvas);
 * - completion: a built-in condition observed after arm; already true at arm → `already-satisfied`;
 * - no unique target → `unaimable`, never a guess.
 *
 * | artemis action          | pair             | aim (derived at the resolved point)                       | built-in completion |
 * |-------------------------|------------------|-----------------------------------------------------------|---------------------|
 * | click(target)           | click_guide      | smallest clickable node containing the point; else geometry | TYPE_VIEW_CLICKED from that node (or inside it); geometry: from a node whose bounds contain the point |
 * | long_press(target)      | long_press_guide | same with long-clickable                                   | TYPE_VIEW_LONG_CLICKED, same attribution |
 * | input_text(target, t)   | type_guide       | smallest editable node containing the point (or the focused editable) | text-changed edge on the node and node text == t; with a trailing newline the guide then rings the keyboard's action key and the screen change after it completes |
 * | swipe(direction, tgt?)  | scroll_guide     | scrollable node at the point advertising that direction; else the largest such container (artemis compute_smart_swipe) | scroll edge from the container in that direction |
 * | press_key(BACK/HOME/APP_SWITCH) | press_guide | SystemUI navigation-bar node (AOSP ids back/home/recent_apps); card only under gesture nav | click on that node, or a window transition after arm |
 * | manage_app(launch, n)   | launch_guide     | launcher node whose text/description equals the app label | foreground window becomes that package |
 */
object GuideCatalog {

    sealed class Completion {
        /** [clickNode] reports the click: the aimed node or its nearest actionable ancestor. */
        data class Click(
            val target: AccessibilityNodeInfo,
            val clickNode: AccessibilityNodeInfo,
            val long: Boolean,
            /** What [clickNode]'s own click event will carry (window, class, populated text) — see [eventSignature]. */
            val signature: EventSignature = eventSignature(clickNode),
        ) : Completion()
        data class ClickAt(val x: Int, val y: Int, val long: Boolean) : Completion()
        /** [submit]: the text is then sent with the keyboard's action key (artemis input_text with a trailing newline). */
        data class TextEquals(val node: AccessibilityNodeInfo, val text: String, val submit: Boolean = false) : Completion()
        data class Scroll(val container: AccessibilityNodeInfo?, val direction: String, val pkg: String) : Completion()
        data class Press(val key: String, val navNode: AccessibilityNodeInfo?) : Completion()
        data class Foreground(val pkg: String) : Completion()
        /** No built-in condition: a lesson step whose end only the lesson monitor observes (e.g. an install in progress). */
        object Hold : Completion()
    }

    /** Aim the overlay draws. [ref] is re-resolved on every frame; null without geometry = card only. */
    data class GuideSpec(
        val verb: String,
        val title: String,
        val ref: UiElement?,
        val chevronFor: String?, // scroll_guide: artemis swipe direction; the chevron points where the content is
        val container: UiElement?,
        val completion: Completion,
        val alreadySatisfied: Boolean = false,
        val geometry: Rect? = null,
    )

    sealed class Routing {
        data class Guide(val spec: GuideSpec) : Routing()
        data class Unaimable(val reason: String, val detail: String) : Routing()
        data class Wait(val ms: Long) : Routing()
        data class Ask(val question: String) : Routing()
        data class Terminal(val completed: Boolean, val explanation: String) : Routing()
    }

    data class Action(val name: String, val args: Map<String, Any?>) {
        fun int(k: String): Int? = (args[k] as? Number)?.toInt()
        fun str(k: String): String? = (args[k] as? String)?.takeIf { it.isNotBlank() }

        /** `target_point_px` from the guide server, else an artemis-normalized [x, y] (debug actions). */
        fun point(snap: ScreenSnapshot): Pair<Int, Int>? {
            (args["target_point_px"] as? List<*>)?.let { l ->
                val x = (l.getOrNull(0) as? Number)?.toInt() ?: return null
                val y = (l.getOrNull(1) as? Number)?.toInt() ?: return null
                return x to y
            }
            val l = args["target"] as? List<*> ?: return null
            val x = (l.getOrNull(0) as? Number)?.toInt() ?: return null
            val y = (l.getOrNull(1) as? Number)?.toInt() ?: return null
            return (x.coerceIn(0, 1000) * snap.width / 1000) to (y.coerceIn(0, 1000) * snap.height / 1000)
        }

        /** What the user sees named on the card: the artemis element text, else the model's description. */
        fun targetName(): String? = (str("target_text") ?: str("target_description"))?.replace('\n', ' ')?.take(30)
    }

    fun route(a: Action, snap: ScreenSnapshot, pm: PackageManager): Routing = when (a.name) {
        "click", "long_press" -> click(a, snap)
        "input_text" -> type(a, snap)
        "swipe" -> scroll(a, snap)
        "press_key" -> press(a, snap)
        "manage_app" -> launch(a, snap, pm)
        "wait_for_delay" -> Routing.Wait((a.int("time_in_ms") ?: 1500).coerceIn(300, 10_000).toLong())
        "ask_user" -> Routing.Ask(a.str("question") ?: "다음에 무엇을 할지 알려 주세요.")
        "report_task_status" -> Routing.Terminal(a.str("status") == "completed", a.str("explanation") ?: "")
        else -> Routing.Unaimable("unsupported-action", "action '${a.name}' has no guide pair")
    }

    private fun click(a: Action, snap: ScreenSnapshot): Routing {
        val (x, y) = a.point(snap) ?: return Routing.Unaimable("no-target", "${a.name} needs a target")
        val long = a.name == "long_press"
        val name = a.targetName()?.let { "'$it'" } ?: "표시된 곳"
        val verb = if (long) "long_press_guide" else "click_guide"
        val title = if (long) "$name 길게 누르기" else "$name 누르기"
        val hit = smallestContaining(snap, x, y) { if (long) it.longClickable else it.clickable }
        if (hit != null) {
            val clickNode = nearestActionable(hit.node, long) ?: hit.node
            return Routing.Guide(GuideSpec(verb, title, hit, null, null, Completion.Click(hit.node, clickNode, long)))
        }
        val half = max(snap.width, snap.height) * 3 / 100
        return Routing.Guide(GuideSpec(verb, title, null, null, null, Completion.ClickAt(x, y, long),
            geometry = Rect(x - half, y - half, x + half, y + half)))
    }

    private fun type(a: Action, snap: ScreenSnapshot): Routing {
        val raw = a.str("text") ?: return Routing.Unaimable("no-text", "input_text needs text")
        // A trailing newline in artemis input_text submits the field; the field itself never holds it.
        val submit = raw.endsWith("\n")
        val text = raw.trimEnd('\n')
        val e = a.point(snap)?.let { (x, y) -> smallestContaining(snap, x, y) { it.editable } }
            ?: snap.onscreen.firstOrNull { it.editable && it.node.isFocused }
            ?: return Routing.Unaimable("not-editable", "no input field at the target and none focused")
        e.node.refresh()
        val current = e.node.text?.toString() ?: ""
        val satisfied = !e.node.isShowingHintText && current == text
        val name = a.targetName()?.let { "'$it'" } ?: "입력란"
        // artemis input_text: clear_exist (default true) replaces the whole text; tell the human when there is text to clear.
        val replace = (a.args["clear_exist"] as? Boolean ?: true) && current.isNotEmpty() && !e.node.isShowingHintText
        val title = (if (replace) "${name}의 글자를 지우고 \"$text\" 입력" else "${name}에 \"$text\" 입력") + if (submit) " 후 엔터" else ""
        return Routing.Guide(GuideSpec("type_guide", title, e, null, null,
            Completion.TextEquals(e.node, text, submit), alreadySatisfied = satisfied && !submit))
    }

    private fun scroll(a: Action, snap: ScreenSnapshot): Routing {
        val dir = a.str("direction") ?: return Routing.Unaimable("no-direction", "only directional swipes are guided")
        val at = a.point(snap)
        val container = at?.let { (x, y) ->
            smallestContaining(snap, x, y) { dir in it.scrollDirs } ?: smallestContaining(snap, x, y) { it.scrollable }
        } ?: snap.onscreen.filter { dir in it.scrollDirs && it.windowType == AccessibilityWindowInfo.TYPE_APPLICATION }
            .maxByOrNull { it.bounds.width().toLong() * it.bounds.height() }
        val title = when (dir) {
            "up" -> "화면을 위로 밀어 아래 내용 보기"
            "down" -> "화면을 아래로 내려 위 내용 보기"
            "left" -> "화면을 왼쪽으로 밀기"
            else -> "화면을 오른쪽으로 밀기"
        }
        return Routing.Guide(GuideSpec("scroll_guide", title, null, dir, container,
            Completion.Scroll(container?.node, dir, snap.packageName)))
    }

    /** AOSP SystemUI navigation-bar button ids (NavigationBarInflaterView: back / home / recent_apps). */
    private val NAV_IDS = mapOf(
        "BACK" to "com.android.systemui:id/back",
        "HOME" to "com.android.systemui:id/home",
        "APP_SWITCH" to "com.android.systemui:id/recent_apps",
    )

    private fun press(a: Action, snap: ScreenSnapshot): Routing {
        val key = (a.str("key") ?: return Routing.Unaimable("no-key", "press_key needs a key")).uppercase()
        val nav = NAV_IDS[key]?.let { id -> snap.onscreen.firstOrNull { it.viewId == id } }
        val title = when (key) {
            "BACK" -> "뒤로 가기"
            "HOME" -> "홈으로 가기"
            "APP_SWITCH" -> "최근 앱 열기"
            "ENTER" -> "키보드의 완료(엔터) 누르기"
            else -> "$key 키 누르기"
        }
        // Gesture navigation has no button node: card-only press guide (Toolus press_guide has no anchor either).
        return Routing.Guide(GuideSpec("press_guide", title, nav, null, null, Completion.Press(key, nav?.node)))
    }

    private fun launch(a: Action, snap: ScreenSnapshot, pm: PackageManager): Routing {
        if ((a.str("action") ?: "launch") != "launch") return Routing.Unaimable("not-guided", "only launching an app is guided")
        val name = a.str("app_name") ?: return Routing.Unaimable("no-app", "manage_app needs app_name")
        val launchers = pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0)
        val matches = launchers.filter { norm(it.loadLabel(pm).toString()) == norm(name) || it.activityInfo.packageName == name }
            .distinctBy { it.activityInfo.packageName }
        if (matches.isEmpty()) return Routing.Unaimable("app-not-found", "no installed app is labelled '$name'")
        if (matches.size > 1) return Routing.Unaimable("ambiguous", "${matches.size} apps are labelled '$name'")
        val pkg = matches[0].activityInfo.packageName
        val label = matches[0].loadLabel(pm).toString()
        if (snap.packageName == pkg) {
            return Routing.Guide(GuideSpec("launch_guide", "'$label' 열기", null, null, null, Completion.Foreground(pkg), alreadySatisfied = true))
        }
        val home = pm.resolveActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), 0)?.activityInfo?.packageName
        // A launcher may append a badge to the icon's description ("ChatGPT, 알림 1개" on One UI).
        val icons = snap.onscreen.filter { e ->
            e.packageName == home && e.clickable &&
                listOf(e.node.text, e.node.contentDescription).any { it != null && norm(it.toString()).let { v -> v == norm(label) || v.startsWith(norm(label) + ",") } }
        }
        // Every match is a shortcut to the one package resolved above, so they are equivalent targets: aim the first.
        return when (icons.size) {
            0 -> Routing.Unaimable("target-lost", "the '$label' icon is not on the current screen")
            else -> Routing.Guide(GuideSpec("launch_guide", "'$label' 열기", icons[0], null, null, Completion.Foreground(pkg)))
        }
    }

    /**
     * When a click navigates away, the event arrives after its view detached and `getSource()` is null.
     * The event record itself still says which view sent it: window id, class name, and the text the view
     * populated (View/ViewGroup.dispatchPopulateAccessibilityEvent: its own text or description, then each
     * visible child's, in order). Capturing the same three at arm time identifies the source without geometry.
     */
    data class EventSignature(val windowId: Int, val className: String, val text: String)

    fun eventSignature(node: AccessibilityNodeInfo): EventSignature {
        val parts = ArrayList<String>()
        fun walk(n: AccessibilityNodeInfo, depth: Int) {
            if (depth > 12 || !n.isVisibleToUser) return
            val own = n.text?.toString()?.takeIf { it.isNotEmpty() } ?: n.contentDescription?.toString()?.takeIf { it.isNotEmpty() }
            if (own != null) parts.add(own)
            for (i in 0 until n.childCount) n.getChild(i)?.let { walk(it, depth + 1) }
        }
        walk(node, 0)
        return EventSignature(node.windowId, node.className?.toString() ?: "", normText(parts.joinToString(" ")))
    }

    fun normText(s: String) = s.replace(Regex("\\s+"), " ").trim()

    // ------------------------------------------------------------------ tree helpers

    /** Hit-test: the smallest visible node containing (x, y) that [accept]s. */
    fun smallestContaining(snap: ScreenSnapshot, x: Int, y: Int, accept: (UiElement) -> Boolean): UiElement? =
        snap.onscreen.filter { accept(it) && it.bounds.contains(x, y) }
            .minByOrNull { it.bounds.width().toLong() * it.bounds.height() }

    fun nearestActionable(node: AccessibilityNodeInfo, long: Boolean): AccessibilityNodeInfo? {
        var n: AccessibilityNodeInfo? = node
        var depth = 0
        while (n != null && depth < 30) {
            if (if (long) n.isLongClickable else n.isClickable) return n
            n = n.parent
            depth += 1
        }
        return null
    }

    /** True when [node] is [ancestor] or lies under it. Identity is AccessibilityNodeInfo.equals (node id + window id). */
    fun isSelfOrDescendant(node: AccessibilityNodeInfo?, ancestor: AccessibilityNodeInfo): Boolean {
        var n = node
        var depth = 0
        while (n != null && depth < 40) {
            if (n == ancestor) return true
            n = n.parent
            depth += 1
        }
        return false
    }

    private fun norm(s: String) = s.trim().replace(Regex("\\s+"), " ").lowercase()

    /**
     * Visible part of [node]: its rect clipped by every scrollable ancestor, its window and the screen. [window] is the
     * window's bounds read now; without it the snapshot's are used, which can be 1.5 s old: ChatGPT's window, caught mid
     * open animation at ~80 % of the screen, clipped its full-size send button away ("off to the right").
     */
    fun visibleRect(node: AccessibilityNodeInfo, snap: ScreenSnapshot, window: Rect? = null): Pair<Rect, Rect> {
        val raw = Rect().also { node.getBoundsInScreen(it) }
        val clip = Rect(0, 0, snap.width, snap.height)
        (window ?: snap.windows.firstOrNull { it.id == node.windowId }?.bounds)?.let { if (!clip.intersect(it)) clip.setEmpty() }
        var p = node.parent
        var depth = 0
        while (p != null && depth < 40) {
            if (p.isScrollable) {
                // A scroll area hides what it scrolled away only along the way it scrolls. Across that axis it can be a
                // parent whose bounds do not hold the node: ChatGPT's message list, caught mid app-open zoom at ~80 % of
                // its size while its send button already reported its final place, read as "off to the right".
                val pr = Rect().also { p!!.getBoundsInScreen(it) }
                val dirs = SnapshotTaker.scrollDirs(p)
                val c = Rect(clip)
                if ("up" in dirs || "down" in dirs) { c.top = max(c.top, pr.top); c.bottom = minOf(c.bottom, pr.bottom) }
                if ("left" in dirs || "right" in dirs) { c.left = max(c.left, pr.left); c.right = minOf(c.right, pr.right) }
                if (c.isEmpty) clip.setEmpty() else clip.set(c)
            }
            p = p.parent
            depth += 1
        }
        val vis = Rect(raw)
        if (!vis.intersect(clip)) vis.setEmpty()
        return vis to clip
    }

    /** The scroll areas [visibleRect] clipped [node] by, for the log: class, bounds, scroll directions. */
    fun clipChain(node: AccessibilityNodeInfo): String {
        val out = ArrayList<String>()
        var p = node.parent
        var depth = 0
        while (p != null && depth < 40) {
            if (p.isScrollable) out.add("${p.className?.toString()?.substringAfterLast('.')}${Rect().also { p!!.getBoundsInScreen(it) }.toShortString()}" +
                "${SnapshotTaker.scrollDirs(p)}/${p.actionList.joinToString(",") { it.id.toString() }}")
            p = p.parent
            depth += 1
        }
        return out.joinToString(" < ").ifEmpty { "window" }
    }

    /** What the keyboard's action key (enter, search, go, done, send, next) is called on keyboards that expose their keys. */
    private val ACTION_KEY_LABELS = setOf("엔터", "enter", "검색", "search", "이동", "go", "완료", "done", "보내기", "send", "다음", "next", "return")

    /**
     * The on-screen keyboard's action key, or null when no keyboard is up or it does not expose its keys. Gboard names it
     * by view id (`key_pos_ime_action`, "Search"); Samsung Keyboard only by description ("엔터"). Of several label matches
     * the bottom-right one is the action key.
     */
    fun imeActionKey(windows: List<AccessibilityWindowInfo>): Rect? {
        val root = windows.firstOrNull { it.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD }?.root ?: return null
        var byLabel: Rect? = null
        val queue = ArrayDeque<AccessibilityNodeInfo>().apply { add(root) }
        var seen = 0
        while (queue.isNotEmpty() && seen < 600) {
            val n = queue.removeFirst()
            seen += 1
            val r = Rect().also { n.getBoundsInScreen(it) }
            if (!r.isEmpty && n.isVisibleToUser) {
                if (n.viewIdResourceName?.endsWith("key_pos_ime_action") == true) return r
                val label = norm((n.contentDescription ?: n.text)?.toString() ?: "")
                val best = byLabel
                if (label in ACTION_KEY_LABELS && (best == null || r.bottom > best.bottom || (r.bottom == best.bottom && r.right > best.right))) byLabel = r
            }
            for (i in 0 until n.childCount) n.getChild(i)?.let { queue.add(it) }
        }
        return byLabel
    }

    /**
     * TGP occlusion: a higher, non-overlay window covering the target's centre. Read from the live window list: a
     * snapshot's can be 1.5 s old (a keyboard already gone, a notification popped up since).
     */
    fun coveringWindow(rect: Rect, windowId: Int, windows: List<AccessibilityWindowInfo>): AccessibilityWindowInfo? {
        val own = windows.firstOrNull { it.id == windowId } ?: return null
        return windows.firstOrNull { w ->
            w.id != windowId && w.layer > own.layer && w.type != AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY && run {
                val region = android.graphics.Region().also { w.getRegionInScreen(it) }
                if (region.isEmpty) Rect().also { w.getBoundsInScreen(it) }.contains(rect.centerX(), rect.centerY())
                else region.contains(rect.centerX(), rect.centerY())
            }
        }
    }

    fun windowName(w: AccessibilityWindowInfo): String = w.title?.toString()?.takeIf { it.isNotEmpty() } ?: ScreenSnapshot.windowTypeName(w.type)
}
