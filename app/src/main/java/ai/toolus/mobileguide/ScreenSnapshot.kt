package ai.toolus.mobileguide

import android.accessibilityservice.AccessibilityService
import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityNodeInfo.AccessibilityAction
import android.view.accessibility.AccessibilityWindowInfo

/**
 * One visible node, kept as a handle for hit-testing and late-bound aiming (TGP ref anchor).
 * [bounds] is the artemis-helper clipped rect (screen ∩ window ∩ scrollable ancestors).
 */
class UiElement(
    val index: Int,
    val node: AccessibilityNodeInfo,
    val label: String,
    val className: String,
    val viewId: String,
    val packageName: String,
    val windowId: Int,
    val windowType: Int,
    val bounds: Rect,
    val rawBounds: Rect,
    val clickable: Boolean,
    val longClickable: Boolean,
    val editable: Boolean,
    val scrollDirs: Set<String>,
    val checkable: Boolean,
) {
    val scrollable: Boolean get() = scrollDirs.isNotEmpty()
    val shortClass: String get() = className.substringAfterLast('.')
}

/** [region] is the window's touchable region (AccessibilityWindowInfo.getRegionInScreen); an IME's bounds can span
 *  far more of the screen than the keys it draws. */
data class WindowFact(val id: Int, val type: Int, val layer: Int, val bounds: Rect, val title: String, val pkg: String,
                      val region: android.graphics.Region = android.graphics.Region(bounds))

/**
 * [xml] is the artemis accessibility-helper dump (UIAutomator-compatible) that the guide server feeds to
 * artemis's own parse → filter → index pipeline. [elements] holds every visible node for the guide catalog.
 */
class ScreenSnapshot(
    val packageName: String,
    val width: Int,
    val height: Int,
    val elements: List<UiElement>,
    val windows: List<WindowFact>,
    val xml: String,
    val takenAtMs: Long,
) {
    val onscreen: List<UiElement> get() = elements.filter { !it.bounds.isEmpty }

    /** Debug listing of interactive / labelled nodes (adb DEBUG_DUMP); the planner reads artemis's own list. */
    fun describe(): String = buildString {
        append("Screen ${width}x$height, foreground package: $packageName\n")
        windows.forEach { append("window ${it.id} ${windowTypeName(it.type)} layer=${it.layer} pkg=${it.pkg} title='${it.title}'\n") }
        onscreen.filter { it.clickable || it.longClickable || it.editable || it.scrollable || it.checkable || it.label.isNotEmpty() }.forEach {
            append("[${it.index}] ${it.shortClass} '${it.label}' ${it.viewId.substringAfter(":id/")} ")
            append(listOfNotNull("click".takeIf { _ -> it.clickable }, "edit".takeIf { _ -> it.editable },
                (if (it.node.isChecked) "checked" else "unchecked").takeIf { _ -> it.checkable },
                "swipe(${it.scrollDirs.joinToString("|")})".takeIf { _ -> it.scrollable }).joinToString(","))
            append(" ${it.bounds.toShortString()}\n")
        }
    }

    companion object {
        fun windowTypeName(t: Int) = when (t) {
            AccessibilityWindowInfo.TYPE_APPLICATION -> "application"
            AccessibilityWindowInfo.TYPE_INPUT_METHOD -> "input_method"
            AccessibilityWindowInfo.TYPE_SYSTEM -> "system"
            AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY -> "accessibility_overlay"
            AccessibilityWindowInfo.TYPE_SPLIT_SCREEN_DIVIDER -> "split_screen_divider"
            AccessibilityWindowInfo.TYPE_MAGNIFICATION_OVERLAY -> "magnification_overlay"
            else -> "unknown"
        }
    }
}

/**
 * Kotlin port of google/artemis `HierarchyDumper.snapshotNode` + `A11yNode.writeXml` (Apache-2.0):
 * windows by descending layer, invisible non-root nodes skipped, bounds clipped to the display ∩ window ∩
 * every scrollable ancestor, hint split from text, same XML attributes. Differences: this app's own windows
 * and accessibility overlays are left out (the guide must not observe itself), and each node handle is kept.
 */
object SnapshotTaker {
    private const val MAX_DEPTH = 75
    private const val MAX_NODES = 8000

    /** Scroll directions in artemis swipe vocabulary ('up' = swipe up = reveal content below). */
    fun scrollDirs(node: AccessibilityNodeInfo): Set<String> {
        if (!node.isScrollable) return emptySet()
        val ids = node.actionList.map { it.id }.toSet()
        val dirs = LinkedHashSet<String>()
        if (AccessibilityAction.ACTION_SCROLL_DOWN.id in ids) dirs.add("up")
        if (AccessibilityAction.ACTION_SCROLL_UP.id in ids) dirs.add("down")
        if (AccessibilityAction.ACTION_SCROLL_RIGHT.id in ids) dirs.add("left")
        if (AccessibilityAction.ACTION_SCROLL_LEFT.id in ids) dirs.add("right")
        if (dirs.isEmpty()) {
            if (AccessibilityAction.ACTION_SCROLL_FORWARD.id in ids) dirs.add("up")
            if (AccessibilityAction.ACTION_SCROLL_BACKWARD.id in ids) dirs.add("down")
        }
        if (dirs.isEmpty()) dirs.add("up")
        return dirs
    }

    /**
     * Drops this service's accessibility cache (API 34+), so the next reads come from the apps themselves. The cache
     * follows the events apps send; a change that sends none (ChatGPT's zoom-in when it reopens) stays in it with its
     * old bounds, and a dump that walks the tree gets those cached nodes back.
     */
    fun dropCache(service: AccessibilityService) {
        if (android.os.Build.VERSION.SDK_INT >= 34) try { service.clearCache() } catch (_: Exception) {}
    }

    /** A decision read ([fresh], the default) drops the cache first; the bench compares it with a cached read. */
    fun take(service: AccessibilityService, ownPackage: String, fresh: Boolean = true): ScreenSnapshot {
        if (fresh) dropCache(service)
        val screen = Rect()
        service.getSystemService(android.view.WindowManager::class.java)?.currentWindowMetrics?.bounds?.let { screen.set(it) }
        if (screen.isEmpty) service.resources.displayMetrics.let { screen.set(0, 0, it.widthPixels, it.heightPixels) }
        val rotation = service.getSystemService(android.hardware.display.DisplayManager::class.java)
            ?.getDisplay(android.view.Display.DEFAULT_DISPLAY)?.rotation ?: 0

        val out = ArrayList<UiElement>()
        val facts = ArrayList<WindowFact>()
        val xml = StringBuilder("<?xml version='1.0' encoding='UTF-8' standalone='yes' ?>\n<hierarchy rotation=\"$rotation\">")
        var foreground = ""
        val windows = try { service.windows } catch (e: Exception) { emptyList<AccessibilityWindowInfo>() }
        val stats = IntArray(1)
        for (w in windows.sortedByDescending { it.layer }) {
            val root = rootOf(w)
            val pkg = root?.packageName?.toString() ?: ""
            val wb = Rect().also { w.getBoundsInScreen(it) }
            val region = android.graphics.Region().also { w.getRegionInScreen(it) }.takeIf { !it.isEmpty } ?: android.graphics.Region(wb)
            facts.add(WindowFact(w.id, w.type, w.layer, wb, w.title?.toString() ?: "", pkg, region))
            if (w.type == AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY || root == null || pkg == ownPackage) continue
            if (foreground.isEmpty() && w.type == AccessibilityWindowInfo.TYPE_APPLICATION) foreground = pkg
            val clip = Rect(screen).apply { if (!intersect(wb)) setEmpty() }
            snapshotNode(root, 0, 0, clip, w, out, xml, stats)
            if (stats[0] >= MAX_NODES) break
        }
        xml.append("</hierarchy>\n")
        if (foreground.isEmpty()) foreground = service.rootInActiveWindow?.packageName?.toString() ?: ""
        return ScreenSnapshot(foreground, screen.width(), screen.height(), out, facts, xml.toString(), System.currentTimeMillis())
    }

    private fun snapshotNode(
        node: AccessibilityNodeInfo, depth: Int, childIndex: Int, clip: Rect, w: AccessibilityWindowInfo,
        out: MutableList<UiElement>, xml: StringBuilder, stats: IntArray,
    ) {
        if (depth > MAX_DEPTH || stats[0] >= MAX_NODES) return
        val visible = node.isVisibleToUser
        if (!visible && depth > 0) return
        stats[0] += 1

        val raw = Rect().also { node.getBoundsInScreen(it) }
        val bounds = Rect(raw).apply { if (!intersect(clip)) setEmpty() }
        var text = node.text?.toString() ?: ""
        val desc = node.contentDescription?.toString() ?: ""
        val hint = node.hintText?.toString() ?: ""
        if (node.isShowingHintText) text = ""
        val cls = node.className?.toString() ?: ""
        val pkg = node.packageName?.toString() ?: ""
        val resId = node.viewIdResourceName ?: ""
        val dirs = scrollDirs(node)

        out.add(UiElement(
            index = out.size + 1, node = node, label = text.ifEmpty { desc.ifEmpty { hint } }.replace('\n', ' ').trim(),
            className = cls, viewId = resId, packageName = pkg, windowId = w.id, windowType = w.type,
            bounds = bounds, rawBounds = raw, clickable = node.isClickable, longClickable = node.isLongClickable,
            editable = node.isEditable, scrollDirs = dirs, checkable = node.isCheckable,
        ))

        xml.append("<node")
        attr(xml, "index", childIndex.toString())
        attr(xml, "text", text)
        attr(xml, "resource-id", resId)
        attr(xml, "class", cls)
        attr(xml, "package", pkg)
        attr(xml, "content-desc", desc)
        attr(xml, "checkable", node.isCheckable.toString())
        attr(xml, "checked", node.isChecked.toString())
        attr(xml, "clickable", node.isClickable.toString())
        attr(xml, "enabled", node.isEnabled.toString())
        attr(xml, "focusable", node.isFocusable.toString())
        attr(xml, "focused", node.isFocused.toString())
        attr(xml, "scrollable", node.isScrollable.toString())
        attr(xml, "long-clickable", node.isLongClickable.toString())
        attr(xml, "password", node.isPassword.toString())
        attr(xml, "selected", node.isSelected.toString())
        attr(xml, "visible-to-user", visible.toString())
        attr(xml, "bounds", "[${bounds.left},${bounds.top}][${bounds.right},${bounds.bottom}]")
        attr(xml, "drawing-order", node.drawingOrder.toString())
        if (hint.isNotEmpty()) attr(xml, "hint", hint)
        if (depth == 0) {
            attr(xml, "window-id", w.id.toString())
            attr(xml, "window-type", ScreenSnapshot.windowTypeName(w.type))
            attr(xml, "window-layer", w.layer.toString())
            if (w.isActive) attr(xml, "window-active", "true")
            if (w.isFocused) attr(xml, "window-focused", "true")
        }
        if (node.isEditable) attr(xml, "editable", "true")
        if (node.isHeading) attr(xml, "heading", "true")
        if (node.isScreenReaderFocusable) attr(xml, "screen-reader-focusable", "true")
        node.stateDescription?.toString()?.takeIf { it.isNotEmpty() }?.let { attr(xml, "state-description", it) }
        node.error?.toString()?.takeIf { it.isNotEmpty() }?.let { attr(xml, "error", it) }
        node.paneTitle?.toString()?.takeIf { it.isNotEmpty() }?.let { attr(xml, "pane-title", it) }
        node.tooltipText?.toString()?.takeIf { it.isNotEmpty() }?.let { attr(xml, "tooltip", it) }

        // Children of a scrollable container are clipped by it (UIAutomator trimScrollableParent).
        val childClip = if (node.isScrollable && !bounds.isEmpty) Rect(bounds) else clip
        val n = node.childCount
        if (n == 0) { xml.append(" />"); return }
        xml.append('>')
        for (i in 0 until n) {
            val child = try { childOf(node, i) } catch (e: Exception) { null } ?: continue
            snapshotNode(child, depth + 1, i, childClip, w, out, xml, stats)
        }
        xml.append("</node>")
    }

    // API 33+: one IPC brings a batch of descendants into the cache (artemis HierarchyDumper does the same), so a read
    // right after the cache is dropped is not one round trip per node
    private fun rootOf(w: AccessibilityWindowInfo): AccessibilityNodeInfo? =
        if (android.os.Build.VERSION.SDK_INT >= 33) w.getRoot(AccessibilityNodeInfo.FLAG_PREFETCH_DESCENDANTS_HYBRID) else w.root

    private fun childOf(node: AccessibilityNodeInfo, i: Int): AccessibilityNodeInfo? =
        if (android.os.Build.VERSION.SDK_INT >= 33) node.getChild(i, AccessibilityNodeInfo.FLAG_PREFETCH_DESCENDANTS_HYBRID) else node.getChild(i)

    private fun attr(sb: StringBuilder, name: String, value: String) {
        sb.append(' ').append(name).append("=\"")
        for (c in value) when (c) {
            '&' -> sb.append("&amp;"); '<' -> sb.append("&lt;"); '>' -> sb.append("&gt;")
            '"' -> sb.append("&quot;"); '\'' -> sb.append("&apos;")
            '\n' -> sb.append("&#10;"); '\r' -> sb.append("&#13;"); '\t' -> sb.append("&#9;")
            else -> if (c.code < 0x20) sb.append(' ') else sb.append(c)
        }
        sb.append('"')
    }
}
