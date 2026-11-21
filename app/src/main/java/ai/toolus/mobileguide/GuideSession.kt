package ai.toolus.mobileguide

import android.content.Intent
import android.graphics.Rect
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.Executors
import kotlin.math.max

/**
 * Observe → plan → route → arm → observe the human → settle → observe again.
 *
 * The loop is the artemis Flash loop (agents/flash/runner.py) with the executor replaced by a guide pair
 * from [GuideCatalog]: the guide server picks one action per turn, the phone shows it, the person does it.
 * How a step ended goes back to the server as the next request's event (docs/interface.md): `met` (the pair's
 * built-in condition was observed after arm), `already-satisfied`, `declared_done`, `declared_stuck`,
 * `unaimable`. Aim failure closes; observation failure holds: a tap elsewhere does not end the
 * step, the guide keeps waiting for the aimed node. The service never acts on the user's behalf.
 */
class GuideSession(private val service: GuideAccessibilityService, private val overlay: GuideOverlay) {
    private enum class Phase { IDLE, PLANNING, ARMED, SETTLING, ASKING, FINISHED }

    private val main = Handler(Looper.getMainLooper())
    /** Planner requests: one thread each, so a request whose result is already stale never delays the next one. */
    private val net = Executors.newCachedThreadPool()
    private val ownPackage = service.packageName

    private var phase = Phase.IDLE
    private var sessionId = ""
    private var task = ""
    private var step = 0
    private var startedAt = 0L
    private var generation = 0
    private var consecutiveUnaimable = 0

    private var reply: PlannerClient.PlannerReply? = null
    private var spec: GuideCatalog.GuideSpec? = null
    private var snapshot: ScreenSnapshot? = null
    private var armedAt = 0L
    /** type_guide: what the bubble asks for now (open, type, submit, submit-words); see [typeStage]. */
    private var typePhase = ""
    /** type_guide: the field holds the requested text; a submit step then asks for the keyboard's action key. */
    private var typedNow = false
    /**
     * Since when the step has asked for the keyboard's action key (0: it does not now). Only a touch after this ends a
     * submit step: typing changes the screen too, and so does the tap that brings the keyboard back (a cursor popup).
     */
    private var submitSince = 0L
    /** The action key last ringed, and since when the keyboard is up without one (a keyboard sliding away). */
    private var lastKey: Rect? = null
    private var keyMissingSince = 0L
    /** Since when the keyboard has been gone in a typing step that had it up; see [typeStage]. */
    private var keyboardGoneSince = 0L
    private var rebinds = 0
    /** Since when another window covers the target (0: it does not); the bubble then says so instead of the step. */
    private var coveredSince = 0L
    /** How long this cover is waited out: a notification goes by itself, the app's own popup (a menu) does not. */
    private var coverHoldMs = 0L
    /** The step's own words, put back when the cover goes away. */
    private var armedInstruction: GuideOverlay.Instruction? = null
    private var lastAimLog = ""
    private var lostSince = 0L
    /** Since when a target inside the window has read as scrolled out of its list (0: it has not); see [resolveAndRender]. */
    private var offViewSince = 0L
    private val pending = ArrayList<Runnable>()

    // Built-in observation shared by every pair: a touch after arm followed by a screen change.
    private var lastTouchAt = 0L
    /** After a touch the screen began to change with the step still open; the ring lets go until then (see [startChecking]). */
    private var checkingSince = 0L
    private var armSignature: ScreenSignature? = null
    private var changeQueued = false
    private var lastRenderAt = 0L
    /** When the list that holds the aimed target last scrolled: a touch followed by that is a scroll, not a tap. */
    private var targetScrolledAt = 0L

    fun awaitingUserAnswer() = phase == Phase.ASKING

    // ---------------------------------------------------------------- lifecycle

    fun start(newTask: String) {
        cancelPending()
        generation += 1
        sessionId = UUID.randomUUID().toString().take(8)
        task = newTask
        step = 0
        consecutiveUnaimable = 0
        startedAt = System.currentTimeMillis()
        GuideHub.post("user", newTask)
        GuideHub.post("guide", "안내를 시작합니다. 화면 위 표시를 따라와 주세요.")
        phase = Phase.SETTLING
        overlay.hideAim()
        overlay.showStatus("화면을 살펴보고 있어요", ::endGuide)
        later(900) { plan("start", "") }
    }

    fun stop(message: String) {
        cancelPending()
        generation += 1
        phase = Phase.IDLE
        spec = null
        Speaker.stop()
        overlay.hideAll()
        GuideHub.post("guide", message)
    }

    fun onUserMessage(text: String) {
        GuideHub.post("user", text)
        if (phase == Phase.IDLE || phase == Phase.FINISHED) { start(text); return }
        cancelPending()
        phase = Phase.SETTLING
        later(900) { plan("user_message", text) }
    }

    // ---------------------------------------------------------------- planning

    /** One turn: observe the screen as it is now and ask the guide server for the next action. */
    private fun plan(event: String, detail: String) {
        val snap = SnapshotTaker.take(service, ownPackage)
        snapshot = snap
        spec = null
        phase = Phase.PLANNING
        // artemis observes a screenshot every turn; our own overlay must not be in it.
        overlay.hideAim()
        overlay.hideCard()
        overlay.hideControls()
        val gen = generation
        later(150) {
            Screenshotter.capture(service) { jpeg, err ->
                if (gen != generation) return@capture
                overlay.showStatus("다음 안내를 준비하고 있어요", ::endGuide)
                request(event, detail, snap, jpeg, err)
            }
        }
    }

    private fun request(event: String, detail: String, snap: ScreenSnapshot, jpeg: String?, shotError: String?) {
        val req = JSONObject().apply {
            put("session_id", sessionId)
            put("task", task + TASK_NOTE)
            GuideSettings.guideMode.takeIf { it.isNotEmpty() }?.let { put("mode", it) }
            put("step", step + 1)
            put("elapsed_s", (System.currentTimeMillis() - startedAt) / 1000)
            put("event", event)
            put("event_detail", detail)
            // the phone's own settings: the server can tell "done" from them where the screen cannot show it
            put("facts", DeviceFacts.read(service))
            put("screen", JSONObject().apply {
                put("package", snap.packageName)
                put("width", snap.width)
                put("height", snap.height)
                put("element_count", snap.elements.size)
                put("xml", snap.xml) // artemis-helper dump; the server runs artemis parse/filter/index on it
                if (jpeg != null) put("screenshot_jpeg_b64", jpeg) else put("screenshot_error", shotError ?: "none")
            })
        }
        Log.i(TAG, "plan event=$event detail=$detail pkg=${snap.packageName} n=${snap.elements.size} shot=" + (jpeg?.let { "${it.length / 1024}KB" } ?: shotError))
        val gen = generation
        net.execute {
            val result = runCatching { PlannerClient.next(req) }
            main.post {
                if (gen != generation) return@post
                result.onSuccess { route(it, snap) }.onFailure {
                    Log.w(TAG, "planner failed", it)
                    overlay.showCard(stepLabel(step + 1), "안내를 준비하지 못했어요", "인터넷 연결을 확인하고 다시 시도해 주세요.", listOf(
                        OverlayChip("다시 시도", true) { plan(event, detail) }, endChip(),
                    ), null)
                    GuideHub.post("system", "안내 서버 오류: ${it.message}")
                }
            }
        }
    }

    private fun route(r: PlannerClient.PlannerReply, snap: ScreenSnapshot) {
        reply = r
        step += 1
        val routing = GuideCatalog.route(r.action, snap, service.packageManager)
        Log.i(TAG, "step=$step action=${r.action.name} ${r.action.args} -> ${routing::class.simpleName} (${r.latencyMs}ms)")
        when (routing) {
            is GuideCatalog.Routing.Guide -> arm(routing.spec, r)
            is GuideCatalog.Routing.Unaimable -> {
                consecutiveUnaimable += 1
                GuideHub.post("system", "조준 실패(${routing.reason}): ${routing.detail}")
                if (consecutiveUnaimable >= 4) {
                    phase = Phase.ASKING
                    overlay.hideAim()
                    overlay.showCard(stepLabel(step), "누를 곳을 찾지 못하고 있어요", "지금 화면에서 무엇을 하려는지 채팅으로 알려 주세요.",
                        listOf(OverlayChip("채팅으로 알려주기", true) { openChat() }, endChip()), null)
                } else plan("unaimable", "${routing.reason}: ${routing.detail}")
            }
            is GuideCatalog.Routing.Wait -> {
                phase = Phase.SETTLING
                overlay.hideAim()
                overlay.showGuide(GuideOverlay.Instruction("잠시 기다려 주세요", kind = GuideOverlay.Instruction.Kind.WAIT), null, ::endGuide)
                later(routing.ms) { plan("wait_done", "") }
            }
            is GuideCatalog.Routing.Ask -> {
                phase = Phase.ASKING
                overlay.hideAim()
                overlay.showCard(stepLabel(step), routing.question, r.explain, listOf(OverlayChip("채팅으로 답하기", true) { openChat() }, endChip()), null)
                GuideHub.post("guide", "❓ ${routing.question}")
            }
            is GuideCatalog.Routing.Terminal -> {
                phase = Phase.FINISHED
                overlay.hideAim()
                val title = if (routing.completed) "다 했어요" else "여기서 멈출게요"
                Speaker.say(service, title)
                val why = r.explain.ifBlank { routing.explanation }
                overlay.showCard(if (routing.completed) "완료" else "중단", title, why, listOf(OverlayChip("닫기", true) { overlay.hideAll(); phase = Phase.IDLE }), null)
                GuideHub.post("guide", "${if (routing.completed) "✅" else "⚠️"} $title — $why")
            }
        }
    }

    private fun arm(s: GuideCatalog.GuideSpec, r: PlannerClient.PlannerReply) {
        consecutiveUnaimable = 0
        if (s.alreadySatisfied) {
            GuideHub.post("guide", "${stepLabel(step)} ${s.title} (이미 되어 있음)")
            plan("already-satisfied", "${s.verb}: the condition already held when the step was armed")
            return
        }
        spec = s
        checkingSince = 0L
        rebinds = 0
        lostSince = 0L
        armedAt = System.currentTimeMillis()
        typePhase = ""
        typedNow = false
        submitSince = 0L
        lastKey = null
        keyMissingSince = 0L
        keyboardGoneSince = 0L
        // baseline from the screen as it is now; the planning snapshot can be seconds old
        armSignature = ScreenSignature.of(SnapshotTaker.take(service, ownPackage))
        phase = Phase.ARMED
        val text = (s.completion as? GuideCatalog.Completion.TextEquals)?.text
        val key = (s.completion as? GuideCatalog.Completion.Press)?.key
        Log.i(TAG, "ARM step=$step verb=${s.verb} src=planner title=${s.title}" + (text?.let { " text=${it.replace("\n", "\\n")}" } ?: "") + (key?.let { " key=$it" } ?: ""))
        lastAimLog = ""
        coveredSince = 0L
        offViewSince = 0L
        armedInstruction = instructionFor(s, r)
        Speaker.say(service, armedInstruction!!.headline)
        overlay.showGuide(armedInstruction!!, { declared("done") }, ::endGuide)
        resolveAndRender()
        GuideHub.post("guide", "${stepLabel(step)} ${s.title} — ${r.explain}")
        heartbeat()
    }

    // ---------------------------------------------------------------- aim (re-resolved every frame)

    /** TGP late-bound ref: refresh the node, classify visible / scroll-needed / covered, draw. Returns the ring rect. */
    private fun resolveAndRender(): Rect? {
        val s = spec ?: return null
        val snap = snapshot ?: return null
        if (s.verb == "scroll_guide") {
            val c = s.container
            // Aim failure closes: the aimed scroll area vanished, e.g. the swipe opened
            // another surface (home → app list) that reports no scroll event. Re-observe instead of pointing at nothing.
            if (c != null && !c.node.refresh()) {
                close("unaimable", "target-lost: the scroll area [${c.index}] is gone; the screen changed after the swipe")
                return null
            }
            val box = c?.let { GuideCatalog.visibleRect(it.node, snap).first }
            // The chevron points where the content to reveal is (TGP semantics): swipe 'up' reveals content below.
            val chevron = when (s.chevronFor) { "up" -> "down"; "down" -> "up"; "left" -> "right"; else -> "left" }
            aim(null, chevron, box, null)
            return box
        }
        if (checkingSince != 0L) {
            if (checkingHoldLeft() > 0) return null
            // nothing came of it: point at the target again
            Log.i(TAG, "CHECKING step=$step over: still open after ${CHECKING_MAX_MS}ms")
            checkingSince = 0L
            overlay.checking(false)
        }
        s.geometry?.let { aim(it, null, null, null); return it }
        (s.completion as? GuideCatalog.Completion.TextEquals)?.let { c -> typeStage(c)?.let { return it.takeIf { r -> !r.isEmpty } } }
        val e = s.ref ?: run { aim(null, null, null, null); return null }
        if (!e.node.refresh()) {
            startChecking("target-gone")
            if (lastTouchAt > armedAt) { checkTouchChange("heartbeat"); return null }
            if (lostSince == 0L) lostSince = System.currentTimeMillis()
            if (System.currentTimeMillis() - lostSince >= SETTLE_MS) rebind()
            return null
        }
        lostSince = 0L
        // The target was just read from its app (refresh). Its ancestors and its window are read now as well: the cache
        // and the last snapshot (up to 1.5 s old) can hold a screen mid transition. ChatGPT's window, snapshotted mid
        // open animation at ~80 %, clipped the full-size send button away and read as "off to the right".
        SnapshotTaker.dropCache(service)
        val liveWindow = try { service.windows.firstOrNull { it.id == e.node.windowId }?.let { w -> Rect().also { w.getBoundsInScreen(it) } } } catch (_: Exception) { null }
        val (vis, viewport) = GuideCatalog.visibleRect(e.node, snap, liveWindow)
        val raw = Rect().also { e.node.getBoundsInScreen(it) }
        if (vis.isEmpty) {
            val vp = if (viewport.isEmpty) Rect(0, 0, snap.width, snap.height) else viewport
            val dir = chevronToward(raw, vp)
            // A target still inside the window gets a moment before a chevron: a list caught mid animation settles
            // within it, and a chevron sends the person swiping (in ChatGPT, a swipe right opens the menu).
            val now = System.currentTimeMillis()
            if (offViewSince == 0L) offViewSince = now
            val inWindow = Rect(0, 0, snap.width, snap.height).contains(raw)
            if (inWindow && now - offViewSince < OFF_VIEW_HOLD_MS) { aim(null, null, null, null); return null }
            if (lastAimLog.contains("chevron=$dir").not()) Log.i(TAG, "CHEVRON step=$step dir=$dir raw=${raw.toShortString()} by=${GuideCatalog.clipChain(e.node)}")
            aim(null, dir, vp, raw)
            return null
        }
        offViewSince = 0L
        val cover = try { GuideCatalog.coveringWindow(vis, e.node.windowId, service.windows) } catch (_: Exception) { null }
        // Right after a touch the screen is in transition (a keyboard closing over a moved button); the
        // touch-change check decides first, occlusion counts only once the screen has settled.
        val now = System.currentTimeMillis()
        val settling = lastTouchAt > armedAt && now - lastTouchAt < TOUCH_SETTLE_MS
        // A keyboard sliding in covers the field until the app makes room (ChatGPT opens with it); a typing step's
        // text goes to the focused field even while the keyboard covers it.
        val byKeyboard = cover?.type == android.view.accessibility.AccessibilityWindowInfo.TYPE_INPUT_METHOD &&
            (s.verb == "type_guide" || now - armedAt < KEYBOARD_SETTLE_MS)
        if (cover != null && !settling && !byKeyboard) {
            // Never ring what another window covers (a messenger's notification over the button): a tap there lands
            // on the cover. Say so, and give a notification its few seconds to go before the planner looks.
            if (coveredSince == 0L) {
                coveredSince = now
                val own = try { cover.root?.packageName?.toString() == e.packageName } catch (_: Exception) { false }
                coverHoldMs = if (own) COVER_OWN_HOLD_MS else COVER_HOLD_MS
                Log.i(TAG, "COVERED step=$step by=${GuideCatalog.windowName(cover)} own=$own")
                overlay.retitle(GuideOverlay.Instruction("다른 창이 가리고 있어요", null, GuideOverlay.Instruction.Kind.WAIT,
                    sub = "잠시 기다리거나 그 창을 닫아 주세요"))
            }
            aim(null, null, null, null)
            if (now - coveredSince >= coverHoldMs) close("unaimable", "occluded: the target is covered by ${GuideCatalog.windowName(cover)}")
            return null
        }
        if (coveredSince != 0L) {
            Log.i(TAG, "COVERED step=$step over after ${now - coveredSince}ms")
            coveredSince = 0L
            typePhase = "" // a typing step says its phase again
            armedInstruction?.let { overlay.retitle(it) }
        }
        aim(vis, null, null, null)
        return vis
    }

    /** Draw and, when the aim changed, log it (the emulator harness follows these lines like a human would). */
    private fun aim(ring: Rect?, chevron: String?, viewport: Rect?, axis: Rect?) {
        overlay.showAim(ring, chevron, viewport, axis, keyboardTop())
        val line = "AIM step=$step ring=${ring?.flattenToString() ?: "-"} chevron=${chevron ?: "-"} viewport=${viewport?.flattenToString() ?: "-"}"
        if (line != lastAimLog) { lastAimLog = line; Log.i(TAG, line) }
    }

    /** Top of the on-screen keyboard's touchable region, if one is showing (the bubble must not cover its keys). */
    private fun keyboardTop(): Int? = try {
        service.windows.firstOrNull { it.type == android.view.accessibility.AccessibilityWindowInfo.TYPE_INPUT_METHOD }?.let { w ->
            val region = android.graphics.Region().also { w.getRegionInScreen(it) }
            region.bounds.takeIf { !it.isEmpty }?.top
        }
    } catch (_: Exception) { null }

    /** The event comes from the on-screen keyboard: its window, or the package that window belongs to. */
    private fun isKeyboard(e: AccessibilityEvent, pkg: String?): Boolean = try {
        service.windows.any { w ->
            w.type == android.view.accessibility.AccessibilityWindowInfo.TYPE_INPUT_METHOD &&
                (w.id == e.windowId || (pkg != null && w.root?.packageName?.toString() == pkg))
        }
    } catch (_: Exception) {
        false
    }

    /** TGP edge chevron: direction from where the unclipped rect lies relative to its viewport. */
    private fun chevronToward(r: Rect, viewport: Rect): String = when {
        r.top >= viewport.bottom -> "down"
        r.bottom <= viewport.top -> "up"
        r.left >= viewport.right -> "right"
        r.right <= viewport.left -> "left"
        r.centerY() > viewport.centerY() -> "down"
        else -> "up"
    }

    /** Locator rebind (Toolus claw resolve_ref: role/name/nth): unique match by id + class + label, ≤ 20 times. */
    private fun rebind() {
        val s = spec ?: return
        val old = s.ref ?: return
        if (rebinds >= 20) { close("unaimable", "target-lost: the highlighted element is gone"); return }
        rebinds += 1
        val snap = SnapshotTaker.take(service, ownPackage)
        snapshot = snap
        // type_guide: tapping a field can replace its view (Chrome's omnibox); input focus then names the field.
        val focused = if (s.completion is GuideCatalog.Completion.TextEquals)
            snap.onscreen.filter { it.editable && it.node.isFocused && it.packageName == old.packageName } else emptyList()
        val same = focused.ifEmpty { snap.elements.filter { it.viewId == old.viewId && it.className == old.className && it.label == old.label } }
        if (same.size != 1) { close("unaimable", "target-lost: ${if (same.isEmpty()) "no" else "${same.size}"} matching element after the screen changed"); return }
        val e = same[0]
        val completion = when (val c = s.completion) {
            is GuideCatalog.Completion.Click -> (GuideCatalog.nearestActionable(e.node, c.long) ?: e.node).let { cn ->
                c.copy(target = e.node, clickNode = cn, signature = GuideCatalog.eventSignature(cn))
            }
            is GuideCatalog.Completion.TextEquals -> if (e.editable) c.copy(node = e.node).also { cn ->
                // the new field may already hold the text
                cn.node.refresh()
                if (!cn.submit && !cn.node.isShowingHintText && cn.node.text?.toString() == cn.text) main.post { close("met", "the field now holds exactly the requested text") }
            } else c
            else -> c
        }
        spec = s.copy(ref = e, completion = completion)
        Log.i(TAG, "rebind #$rebinds -> [${e.index}] ${e.label}")
    }

    private fun heartbeat() {
        if (phase != Phase.ARMED) return
        later(250) {
            if (phase != Phase.ARMED) return@later
            if (System.currentTimeMillis() - (snapshot?.takenAtMs ?: 0) > 1500) snapshot = SnapshotTaker.take(service, ownPackage)
            (spec?.completion as? GuideCatalog.Completion.TextEquals)?.let { c ->
                val holds = textHolds(c)
                if (holds && !c.submit) { close("met", "the field now holds the requested text"); return@later }
                setTyped(holds)
            }
            if (lastTouchAt > armedAt && System.currentTimeMillis() - lastTouchAt >= QUIET_MS) {
                checkTouchChange("heartbeat")
                if (phase != Phase.ARMED) return@later
            }
            resolveAndRender()
            heartbeat()
        }
    }

    // ---------------------------------------------------------------- built-in completion

    fun onEvent(e: AccessibilityEvent) {
        val pkg = e.packageName?.toString() ?: ""
        if (phase != Phase.ARMED) return
        val s = spec ?: return
        if (pkg == ownPackage) return
        if (e.eventType == AccessibilityEvent.TYPE_VIEW_SCROLLED) {
            val target = s.ref?.node
            val src = e.source
            if (target != null && src != null && GuideCatalog.isSelfOrDescendant(target, src)) targetScrolledAt = System.currentTimeMillis()
        }
        // the page moved under the ring (scroll, keyboard, relayout): follow it now instead of on the next heartbeat
        if (e.eventType == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED || e.eventType == AccessibilityEvent.TYPE_VIEW_SCROLLED ||
            e.eventType == AccessibilityEvent.TYPE_WINDOWS_CHANGED) {
            val now = android.os.SystemClock.uptimeMillis()
            if (now - lastRenderAt >= RENDER_MIN_MS) { lastRenderAt = now; resolveAndRender() }
            if (phase != Phase.ARMED) return
        }
        when (val c = s.completion) {
            is GuideCatalog.Completion.Click -> {
                val want = if (c.long) AccessibilityEvent.TYPE_VIEW_LONG_CLICKED else AccessibilityEvent.TYPE_VIEW_CLICKED
                if (e.eventType == AccessibilityEvent.TYPE_VIEW_CLICKED || e.eventType == AccessibilityEvent.TYPE_VIEW_LONG_CLICKED) {
                    val sb = Rect().also { r -> e.source?.getBoundsInScreen(r) }
                    val tb = Rect().also { r -> c.clickNode.getBoundsInScreen(r) }
                    Log.i(TAG, "EVT ${AccessibilityEvent.eventTypeToString(e.eventType)} src=${e.source?.className}@${sb.toShortString()} " +
                        "srcNull=${e.source == null} eqClick=${e.source == c.clickNode} inTarget=${GuideCatalog.isSelfOrDescendant(e.source, c.target)} target=${tb.toShortString()} text=${e.text}")
                }
                if (e.eventType == want) {
                    val src = e.source
                    val byNode = src != null && (src == c.clickNode || GuideCatalog.isSelfOrDescendant(src, c.target))
                    val sig = c.signature
                    val byRecord = src == null && e.windowId == sig.windowId && e.className?.toString() == sig.className &&
                        sig.text.isNotEmpty() && GuideCatalog.normText(e.text.joinToString(" ")) == sig.text
                    if (byNode || byRecord) {
                        close("met", "the human ${if (c.long) "long-pressed" else "tapped"} the highlighted element")
                    }
                }
            }
            is GuideCatalog.Completion.TextEquals -> if (e.eventType == AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED) {
                // The person types into the focused field; apps can swap the field's view on focus (Chrome's omnibox).
                val src = e.source
                val field = when {
                    GuideCatalog.isSelfOrDescendant(src, c.node) -> c.node
                    src != null && src.isEditable && src.isFocused && src.packageName == c.node.packageName -> src
                    else -> return
                }
                field.refresh()
                val now = field.text?.toString() ?: ""
                // inline autocomplete (Chrome's omnibox) appends a selected suggestion after what was typed
                val ok = if (field.isPassword) now.length >= c.text.length
                else (!field.isShowingHintText && squash(now).startsWith(squash(c.text)))
                // The typed value never leaves the phone; the planner only learns that it matched.
                if (ok && !c.submit) close("met", "the field now holds exactly the requested text")
                else if (c.submit && ok != typedNow) { setTyped(ok); resolveAndRender() }
            }
            is GuideCatalog.Completion.Scroll -> {
                val fromContainer = if (c.container != null) GuideCatalog.isSelfOrDescendant(e.source, c.container) else pkg == c.pkg
                if (e.eventType == AccessibilityEvent.TYPE_VIEW_SCROLLED && fromContainer && directionMatches(e, c.direction)) {
                    close("met", "the human scrolled ${c.direction} in the highlighted area")
                } else if (c.container == null && e.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED && pkg == c.pkg) {
                    close("met", "the human swiped and the ${c.pkg} window changed state")
                }
            }
            is GuideCatalog.Completion.Press -> {
                if (c.navNode != null && e.eventType == AccessibilityEvent.TYPE_VIEW_CLICKED && e.source == c.navNode) {
                    close("met", "the human pressed the navigation bar ${c.key} button")
                } else if (e.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED && pkg != "com.android.systemui" && !isKeyboard(e, pkg)) {
                    close("met", "after ${c.key}, the screen changed to $pkg")
                }
            }
            is GuideCatalog.Completion.Foreground -> if (e.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED && pkg == c.pkg) {
                close("met", "${c.pkg} is now in the foreground")
            }
            is GuideCatalog.Completion.Hold -> Unit
            is GuideCatalog.Completion.ClickAt -> {
                val want = if (c.long) AccessibilityEvent.TYPE_VIEW_LONG_CLICKED else AccessibilityEvent.TYPE_VIEW_CLICKED
                val b = Rect()
                val src = e.source
                if (e.eventType == want && src != null) {
                    src.getBoundsInScreen(b)
                    if (b.contains(c.x, c.y)) close("met", "a click was reported by the element under the marked point")
                }
            }
        }
        if (phase == Phase.ARMED && (e.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED || e.eventType == AccessibilityEvent.TYPE_WINDOWS_CHANGED)) {
            startChecking("window")
        }
    }

    /** How much longer checking lasts: an unreadable screen (an app drawing its first frame) is waited for meanwhile. */
    private fun checkingHoldLeft(): Long =
        if (checkingSince == 0L) 0L else (checkingSince + CHECKING_MAX_MS - System.currentTimeMillis()).coerceAtLeast(0L)

    /**
     * The person touched the screen and it started to change (a window came or went, the target was replaced) while
     * the step is still open: an app takes seconds to open, an answer to start. The ring lets go of the old target and
     * the bubble says it is checking until the step closes, or [CHECKING_MAX_MS] passes with nothing coming of it.
     * Typing and scrolling steps keep their ring: their touches do not finish them.
     */
    private fun startChecking(why: String) {
        if (checkingSince != 0L || phase != Phase.ARMED) return
        val now = System.currentTimeMillis()
        if (lastTouchAt <= armedAt || now - lastTouchAt > CHECKING_TOUCH_MS) return
        when (val c = spec?.completion) {
            // typing changes the screen as it goes; only the action key of a submit step can start a new screen
            is GuideCatalog.Completion.TextEquals -> if (!(c.submit && submitSince != 0L && lastTouchAt > submitSince)) return
            is GuideCatalog.Completion.Scroll, is GuideCatalog.Completion.Hold, null -> return
            else -> Unit
        }
        if (targetScrolledAt > armedAt && spec?.ref?.node?.refresh() == true) return // the touch moved the page
        checkingSince = now
        Log.i(TAG, "CHECKING step=$step via=$why")
        aim(null, null, null, null)
        overlay.checking(true)
    }

    /**
     * A typing step follows the keyboard. No keyboard: the ring stays on the field and asks for the tap that opens it.
     * Keyboard up: type (the text in a box with a copy button). Text in and the step sends it: the ring moves to the
     * keyboard's action key, or, on a keyboard that does not expose its keys, the words say where it is. Returns the
     * ring when it is not the field (an empty rect: no ring), null to ring the field.
     */
    private fun typeStage(c: GuideCatalog.Completion.TextEquals): Rect? {
        val kb = keyboardTop() != null
        val key = if (c.submit && typedNow && kb) GuideCatalog.imeActionKey(service.windows) else null
        var now = typePhaseNow(c, kb, key)
        if (now == "submit-words") {
            // a keyboard sliding away keeps its window a moment after its keys are gone: hold what was shown
            val t = System.currentTimeMillis()
            if (keyMissingSince == 0L) keyMissingSince = t
            if (t - keyMissingSince < KEY_MISSING_MS && typePhase.isNotEmpty()) now = typePhase
        } else keyMissingSince = 0L
        // A keyboard that just went away may be the person sending their own words (the next screen is coming), not
        // a keyboard to bring back: hold what was shown a moment before asking for the field again.
        if (now == "open" && typePhase.isNotEmpty() && typePhase != "open") {
            val t = System.currentTimeMillis()
            if (keyboardGoneSince == 0L) keyboardGoneSince = t
            if (t - keyboardGoneSince < KEYBOARD_GONE_HOLD_MS) now = typePhase
        } else if (now != "open") keyboardGoneSince = 0L
        // The action key was just pressed: the keyboard goes and the field may clear while the next screen comes (search
        // results). That is the step taking effect, not a reason to ask for the keyboard again.
        if (submitSince != 0L && lastTouchAt > submitSince && (now == "open" || now == "type") &&
            System.currentTimeMillis() - lastTouchAt < CHECKING_TOUCH_MS) {
            startChecking("submitted")
            if (checkingSince != 0L) return Rect()
        }
        if (now != typePhase) {
            typePhase = now
            Log.i(TAG, "TYPEPHASE step=$step phase=$now")
            spec?.let { overlay.retitle(typeInstruction(it, c, now)) }
            val submitting = now == "submit" || now == "submit-words"
            if (submitting && submitSince == 0L) {
                submitSince = System.currentTimeMillis()
                armSignature = ScreenSignature.of(SnapshotTaker.take(service, ownPackage)) // the screen as typing left it
            } else if (!submitting) submitSince = 0L
        }
        if (key != null) lastKey = key
        return when {
            now == "submit" -> (key ?: lastKey.takeIf { kb })?.let { aim(it, null, null, null); it } ?: Rect().also { aim(null, null, null, null) }
            now == "submit-words" -> { aim(null, null, null, null); Rect() }
            else -> null
        }
    }

    private fun typePhaseNow(c: GuideCatalog.Completion.TextEquals, keyboard: Boolean, key: Rect?) = when {
        key != null -> "submit"
        !keyboard -> "open"
        c.submit && typedNow -> "submit-words"
        else -> "type"
    }

    private fun setTyped(holds: Boolean) {
        typedNow = holds
    }

    /** Typed text compared without spaces or case: Korean spacing varies ("추천해 줘" / "추천해줘"). */
    private fun squash(t: String) = t.replace(Regex("\\s+"), "").lowercase()

    /** Typed-text state: the aimed field, or the focused editable of the same app (a view swapped in on focus). */
    private fun textHolds(c: GuideCatalog.Completion.TextEquals): Boolean {
        val pkg = c.node.packageName?.toString()
        val field = c.node.takeIf { it.refresh() && it.isFocused }
            ?: SnapshotTaker.take(service, ownPackage).onscreen.firstOrNull { it.editable && it.node.isFocused && it.packageName == pkg }?.node
            ?: c.node.takeIf { it.refresh() }
            ?: return false
        val now = field.text?.toString() ?: ""
        return if (field.isPassword) now.length >= c.text.length
        else !field.isShowingHintText && squash(now).startsWith(squash(c.text))
    }

    private fun directionMatches(e: AccessibilityEvent, dir: String): Boolean {
        if (Build.VERSION.SDK_INT < 28) return true
        val dx = e.scrollDeltaX
        val dy = e.scrollDeltaY
        if (dx == 0 && dy == 0) return true // delta not reported by this view
        return when (dir) {
            "up" -> dy > 0      // swipe up: content moves up, reveals below
            "down" -> dy < 0
            "left" -> dx > 0
            else -> dx < 0
        }
    }

    private fun declared(kind: String) {
        if (phase != Phase.ARMED) return
        if (kind == "stop") { stop("안내를 멈췄습니다."); return }
        close("declared_$kind", if (kind == "done") "the human pressed '했어요' (says this step is done)"
        else "the human pressed '막혔어요' (says they are stuck on this step)")
    }

    private fun close(outcome: String, detail: String) {
        if (phase != Phase.ARMED) return
        checkingSince = 0L
        cancelPending()
        phase = Phase.SETTLING
        if (outcome == "met" || outcome == "declared_done") overlay.flashDone() else overlay.hideAim()
        Log.i(TAG, "outcome $outcome :: $detail")
        if (outcome == "met") GuideHub.post("system", "✓ ${stepLabel(step)} 확인됨")
        later(if (outcome.startsWith("declared")) 300 else SETTLE_MS) { plan(outcome, detail) }
    }

    /** A touch landed somewhere (coordinates are zero for other apps' windows): look at the screen right after it. */
    fun onTouch(x: Float, y: Float) {
        Log.i(TAG, "TOUCH outside x=$x y=$y")
        lastTouchAt = System.currentTimeMillis()
        if (phase == Phase.ARMED && !changeQueued) {
            changeQueued = true
            main.postDelayed({ changeQueued = false; checkTouchChange("touch") }, QUIET_MS)
        }
    }

    /**
     * Built-in completion added to every pair (`met` means the pair's built-in observation rule held, not that the
     * person touched that element; the server reads the outcome and the diff in the next request's `event_detail`).
     * Many screens never send TYPE_VIEW_CLICKED / TYPE_VIEW_SCROLLED (Compose, SystemUI panels, the launcher), so a
     * touch after arm followed by a different screen also closes the step, with the diff as the detail.
     */
    private fun checkTouchChange(why: String) {
        if (phase != Phase.ARMED || lastTouchAt <= armedAt) return
        // typing: tapping the field brings up the keyboard before the text is in; the text-changed edge is exact.
        // A submit step, once the text is in, ends with the screen the action key brings (search results).
        (spec?.completion as? GuideCatalog.Completion.TextEquals)?.let { c -> if (!(c.submit && submitSince != 0L && lastTouchAt > submitSince)) return }
        // the target's list scrolled during this step and the target is still there: the person moved the page, a tap on
        // a list item would have taken them away from it; the ring follows the target (or the chevron points to it)
        if (targetScrolledAt > armedAt && spec?.ref?.node?.refresh() == true) {
            Log.i(TAG, "touch-change via=$why ignored: the target's list scrolled")
            armSignature = ScreenSignature.of(SnapshotTaker.take(service, ownPackage))
            lastTouchAt = armedAt
            return
        }
        val base = armSignature ?: return
        val snap = SnapshotTaker.take(service, ownPackage)
        if (snap.packageName.isEmpty()) return // mid-transition: look again on the next heartbeat
        val now = ScreenSignature.of(snap)
        val diff = base.diff(now) ?: return
        snapshot = snap
        close("met", "after a touch the screen changed ($diff); no click event says which element was touched")
        Log.i(TAG, "touch-change via=$why $diff")
    }

    // ---------------------------------------------------------------- helpers

    private fun openChat() {
        service.startActivity(Intent(service, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    private fun endChip() = OverlayChip("안내 끝내기") { endGuide() }

    /** ✕: end the guide, then ask whether to call the instructor or just close. */
    private fun endGuide() {
        val where = task
        val at = step
        stop("안내를 끝냈어요.")
        overlay.showCard("", "안내를 끝냈어요", "잘 안 된 부분이 있었다면 강사에게 물어볼 수 있어요.", listOf(
            OverlayChip("강사에게 물어보기", true) { askInstructor(where, at) },
            OverlayChip("그냥 끄기") { overlay.hideAll() },
        ), null)
    }

    /**
     * The help request leaves the phone as an app broadcast plus a log line (no instructor service is wired yet);
     * the person sees that it was sent.
     */
    private fun askInstructor(where: String, at: Int) {
        Log.i(TAG, "HELP_REQUEST task=$where step=$at session=$sessionId")
        service.sendBroadcast(Intent(ACTION_HELP_REQUEST).setPackage(service.packageName)
            .putExtra("task", where).putExtra("step", at).putExtra("session", sessionId))
        GuideHub.post("system", "강사에게 도움을 요청했어요.")
        overlay.showCard("", "강사에게 알렸어요", "강사가 곧 도와드릴 거예요. 화면은 그대로 두세요.",
            listOf(OverlayChip("닫기", true) { overlay.hideAll() }), null)
    }

    /** The bubble's instruction: what to do, in the polite imperative, named by what the person sees. */
    private fun instructionFor(s: GuideCatalog.GuideSpec, r: PlannerClient.PlannerReply): GuideOverlay.Instruction {
        // the ring may sit on a text-less container (a button whose label is a child); the action names the target
        val label = listOf(s.ref?.label, r.action.args["target_text"] as? String)
            .map { it?.trim()?.replace(Regex("\\s+"), " ") }
            .firstOrNull { !it.isNullOrEmpty() && it.length <= 16 }
        return when (s.verb) {
            "click_guide" -> GuideOverlay.Instruction(if (label != null) "‘$label’ 누르세요" else "여기를 누르세요")
            "long_press_guide" -> GuideOverlay.Instruction(if (label != null) "‘$label’ 길게 누르세요" else "여기를 길게 누르세요")
            "type_guide" -> {
                val c = s.completion as GuideCatalog.Completion.TextEquals
                setTyped(textHolds(c))
                val kb = keyboardTop() != null
                typePhase = typePhaseNow(c, kb, if (c.submit && typedNow && kb) GuideCatalog.imeActionKey(service.windows) else null)
                if (typePhase == "submit" || typePhase == "submit-words") submitSince = armedAt
                Log.i(TAG, "TYPEPHASE step=$step phase=$typePhase")
                typeInstruction(s, c, typePhase)
            }
            "scroll_guide" -> GuideOverlay.Instruction(when (s.chevronFor) {
                "up" -> "화면을 위로 밀어 올리세요"
                "down" -> "화면을 아래로 끌어내리세요"
                "left" -> "화면을 왼쪽으로 넘기세요"
                else -> "화면을 오른쪽으로 넘기세요"
            }, kind = GuideOverlay.Instruction.Kind.SCROLL)
            "press_guide" -> {
                val key = (s.completion as? GuideCatalog.Completion.Press)?.key
                // no navigation-bar button on screen: gesture navigation
                GuideOverlay.Instruction(if (s.ref != null) when (key) {
                    "BACK" -> "뒤로 버튼을 누르세요"
                    "HOME" -> "홈 버튼을 누르세요"
                    "APP_SWITCH" -> "최근 앱 버튼을 누르세요"
                    else -> "버튼을 누르세요"
                } else when (key) {
                    "BACK" -> "화면 왼쪽 끝에서 안쪽으로 쓸어 주세요"
                    "HOME" -> "화면 맨 아래에서 위로 쓸어 올리세요"
                    "APP_SWITCH" -> "맨 아래에서 위로 쓸어 올린 뒤 잠시 멈추세요"
                    else -> "버튼을 누르세요"
                }, kind = GuideOverlay.Instruction.Kind.KEY)
            }
            "launch_guide" -> {
                val app = label ?: Regex("'(.+)'").find(s.title)?.groupValues?.get(1) ?: "앱"
                GuideOverlay.Instruction("‘$app’ 아이콘을 누르세요", kind = GuideOverlay.Instruction.Kind.OPEN)
            }
            "wait_guide" -> GuideOverlay.Instruction(
                if (s.title.endsWith("기다리기")) s.title.removeSuffix("기다리기") + "기다려 주세요" else "잠시 기다려 주세요", kind = GuideOverlay.Instruction.Kind.WAIT)
            else -> GuideOverlay.Instruction(s.title)
        }
    }

    private fun typeInstruction(s: GuideCatalog.GuideSpec, c: GuideCatalog.Completion.TextEquals, phase: String): GuideOverlay.Instruction =
        when (phase) {
            "open" -> GuideOverlay.Instruction("입력란을 누르세요", null, GuideOverlay.Instruction.Kind.TAP, sub = "키보드가 나와요")
            "submit" -> GuideOverlay.Instruction("키보드의 이 버튼을 누르세요", null, GuideOverlay.Instruction.Kind.KEY)
            "submit-words" -> GuideOverlay.Instruction("키보드 오른쪽 아래 버튼을 누르세요", null, GuideOverlay.Instruction.Kind.KEY)
            else -> GuideOverlay.Instruction(if (s.title.contains("글자를 지우고")) "있던 글자를 지우고 입력하세요" else "여기에 입력하세요",
                c.text, GuideOverlay.Instruction.Kind.TYPE)
        }

    private fun stepLabel(n: Int) = "단계 ${max(n, 1)}"

    private fun later(ms: Long, block: () -> Unit) {
        val gen = generation
        val r = Runnable { if (gen == generation) block() }
        pending.add(r)
        main.postDelayed(r, ms)
    }

    private fun cancelPending() {
        pending.forEach { main.removeCallbacks(it) }
        pending.clear()
    }

    /** Shell-only: the person presses a card chip (done / stuck / stop), as the emulator harness does. */
    fun debugDeclare(kind: String) = declared(kind)

    /** Shell-only: what one observation costs on this screen (polling budget). Runs on the main thread. */
    fun debugBench(n: Int = 20): String {
        fun ms(block: () -> Unit): Double { val t = System.nanoTime(); block(); return (System.nanoTime() - t) / 1e6 }
        fun stats(xs: List<Double>) = if (xs.isEmpty()) "-" else xs.sorted().let {
            "med=%.1f p90=%.1f max=%.1f".format(it[it.size / 2], it[minOf(it.size - 1, it.size * 9 / 10)], it.last())
        }
        var snap = SnapshotTaker.take(service, ownPackage)
        val cached = (1..n).map { ms { snap = SnapshotTaker.take(service, ownPackage, fresh = false) } }
        val fresh = (1..n).map { ms { snap = SnapshotTaker.take(service, ownPackage, fresh = true) } }
        val node = snap.onscreen.lastOrNull { it.clickable && it.packageName == snap.packageName }?.node
        val refresh = node?.let { nd -> (1..n * 3).map { ms { nd.refresh() } } } ?: emptyList()
        // the ring's per-frame read: the target from its app, its scroll ancestors from the cache / from the app
        val frameCached = node?.let { nd -> (1..n * 3).map { ms { nd.refresh(); GuideCatalog.visibleRect(nd, snap) } } } ?: emptyList()
        val frameFresh = node?.let { nd -> (1..n * 3).map { ms { nd.refresh(); SnapshotTaker.dropCache(service); GuideCatalog.visibleRect(nd, snap) } } } ?: emptyList()
        return "BENCH pkg=${snap.packageName} nodes=${snap.elements.size} | snapshot cached ${stats(cached)} | snapshot fresh ${stats(fresh)} | " +
            "one node refresh ${stats(refresh)} | ring frame cached ${stats(frameCached)} | ring frame fresh ${stats(frameFresh)} (ms) | " +
            staleness()
    }

    /** What the cache says against what the apps say, read back to back: nodes whose bounds or presence differ. */
    fun staleness(): String {
        val a = SnapshotTaker.take(service, ownPackage, fresh = false)
        val b = SnapshotTaker.take(service, ownPackage, fresh = true)
        fun keyed(s: ScreenSnapshot) = s.elements.groupBy({ "${it.windowId}|${it.viewId}|${it.className}|${it.label}" }, { it.rawBounds.flattenToString() })
        val ka = keyed(a)
        val kb = keyed(b)
        var moved = 0
        var gone = 0
        val examples = ArrayList<String>()
        for ((k, ra) in ka) {
            val rb = kb[k]
            if (rb == null) { gone += ra.size; continue }
            val diff = ra.filter { it !in rb }
            moved += diff.size
            if (diff.isNotEmpty() && examples.size < 3) examples.add("${k.substringAfterLast('|').take(20)}:${diff.first()}->${rb.first()}")
        }
        val added = kb.filterKeys { it !in ka }.values.sumOf { it.size }
        return "STALE pkg=${b.packageName} cached=${a.elements.size} fresh=${b.elements.size} moved=$moved gone=$gone new=$added ${examples.joinToString(" ")}"
    }

    /** Shell-only: [staleness] [k] times, [gapMs] apart, off the main thread (to catch an app mid transition). */
    fun debugStale(k: Int, gapMs: Long) {
        Thread {
            repeat(k) { i ->
                val t = System.currentTimeMillis()
                Log.i(TAG, "STALE#$i " + try { staleness() } catch (e: Exception) { "failed ${e.message}" } + " (${System.currentTimeMillis() - t}ms)")
                Thread.sleep(gapMs)
            }
        }.start()
    }

    fun debugDump(): String = SnapshotTaker.take(service, ownPackage).describe()

    /** Shell-only: route one action through the catalog without the server (catalog conformance checks). */
    fun debugAction(json: String) {
        cancelPending()
        generation += 1
        if (sessionId.isEmpty()) sessionId = "debug"
        if (task.isEmpty()) task = "(debug)"
        if (startedAt == 0L) startedAt = System.currentTimeMillis()
        val o = JSONObject(json)
        val args = HashMap<String, Any?>()
        o.keys().forEach { k ->
            if (k != "name") args[k] = o.get(k).let { v ->
                if (v is org.json.JSONArray) (0 until v.length()).map { v.get(it) } else v
            }
        }
        val snap = SnapshotTaker.take(service, ownPackage)
        snapshot = snap
        route(PlannerClient.PlannerReply("디버그 동작", GuideCatalog.Action(o.optString("name"), args), 0), snap)
    }

    companion object {
        const val TAG = "ToolusGuide"
        /** A typing step whose keyboard went away asks for the field again only after this long (the person may have sent). */
        const val KEYBOARD_GONE_HOLD_MS = 700L
        /** A keyboard up without its action key for this long is one that does not expose its keys. */
        const val KEY_MISSING_MS = 700L
        /**
         * A target inside the window that reads as scrolled out of its list gets this long before a chevron (a list caught
         * mid transition; with the cache dropped before each read, such a read lasted 0.1-0.2 s on a Galaxy S26).
         */
        const val OFF_VIEW_HOLD_MS = 500L
        /** A window covering the target this long (more than a notification stays) is handed to the planner. */
        const val COVER_HOLD_MS = 6000L
        /** The app's own window over the target (a popup menu it opened) is not waited out as long. */
        const val COVER_OWN_HOLD_MS = 2000L
        /** For this long after a step is shown, a keyboard covering its target is the keyboard still sliding in. */
        const val KEYBOARD_SETTLE_MS = 1500L
        /**
         * Added to every task: the planner judges completion from the screen, and an app reopens where it was left
         * (ChatGPT shows the last chat). A result already there is not the person doing the task now.
         */
        const val TASK_NOTE = "\n\nThe person does every action of this task themselves now, following your guide. " +
            "Report the task completed only after you have guided the action that produces its result in this session " +
            "(for a question to a chat app: you guided typing it and sending it, and the answer then appeared). Apps open " +
            "where they were left: a chat app shows its last conversation, which may already hold the same question and " +
            "answer from before. That earlier result does not count; guide the person to do it again now, in a new chat."
        /** artemis mcp/observation.py settle_ms. */
        const val SETTLE_MS = 400L
        /** After a touch, occlusion is not judged for this long (the touch-change check runs first). */
        const val TOUCH_SETTLE_MS = 1500L
        /** Event-driven re-aim at most this often. */
        const val RENDER_MIN_MS = 40L
        /** A screen change this soon after a touch is taken as the touch taking effect (see startChecking). */
        const val CHECKING_TOUCH_MS = 2500L
        /** Checking gives up and points again after this long without the step closing. */
        const val CHECKING_MAX_MS = 5000L
        /** A touch is judged once the screen has been quiet this long (artemis waits for the UI to settle before it observes). */
        const val QUIET_MS = 350L
        const val ACTION_HELP_REQUEST = "ai.toolus.mobileguide.HELP_REQUEST"
    }
}

typealias OverlayChip = GuideOverlay.Chip

/**
 * What a screen is, for change detection: its windows, its structure (class + view id per node) and its content
 * (labels). Status-bar text (clock, battery) is left out. The split follows davi's structure/content signatures.
 */
class ScreenSignature(
    private val pkg: String,
    private val windows: Set<String>,
    private val structure: Map<String, Int>,
    private val content: Map<String, Int>,
) {
    /** A short description of what changed, or null when the screen is the same for this purpose. */
    fun diff(o: ScreenSignature): String? = when {
        pkg != o.pkg -> "foreground $pkg → ${o.pkg}"
        windows != o.windows -> "windows ${(o.windows - windows).joinToString(",").ifEmpty { "-" }} added, " +
            "${(windows - o.windows).joinToString(",").ifEmpty { "-" }} removed"
        structure != o.structure -> "layout changed (structure similarity %.2f)".format(jaccard(structure, o.structure))
        content != o.content -> "text changed: +${(o.content.keys - content.keys).take(3)} -${(content.keys - o.content.keys).take(3)}"
        else -> null
    }

    companion object {
        fun of(snap: ScreenSnapshot): ScreenSignature {
            val statusBar = (snap.height * 0.06).toInt()
            val els = snap.onscreen.filter { !(it.packageName == "com.android.systemui" && it.bounds.bottom <= statusBar) }
            return ScreenSignature(
                snap.packageName,
                // a window whose root could not be read (pkg unknown) says nothing about a change
                snap.windows.filter { it.type != android.view.accessibility.AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY && it.pkg.isNotEmpty() }
                    .map { "${ScreenSnapshot.windowTypeName(it.type)}:${it.pkg}:${it.title}" }.toSet(),
                els.groupingBy { "${it.packageName}|${it.className}|${it.viewId}" }.eachCount(),
                els.filter { it.label.isNotEmpty() }.groupingBy { it.label }.eachCount(),
            )
        }

        private fun jaccard(a: Map<String, Int>, b: Map<String, Int>): Double {
            val keys = a.keys + b.keys
            if (keys.isEmpty()) return 1.0
            val inter = keys.sumOf { minOf(a[it] ?: 0, b[it] ?: 0) }
            val union = keys.sumOf { maxOf(a[it] ?: 0, b[it] ?: 0) }
            return inter.toDouble() / union
        }
    }
}
