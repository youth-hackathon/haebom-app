package ai.toolus.mobileguide

import android.accessibilityservice.AccessibilityService
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.animation.PathInterpolator
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.math.PI
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * Three accessibility-overlay windows:
 *  - a full-screen, touch-transparent canvas: the ring on the target, the scroll chevron, and the callout bubble
 *    whose tail points at the ring. Ring and bubble are one drawing, so a new step glides both together;
 *  - a small touchable control with the two runtime buttons: done (✓, re-check this step now) and end (✕);
 *  - a touchable card for states that need a choice (a question, an error, the end of a lesson, the exit sheet).
 *
 * Visual language is the TGP DOM renderer's (toolus-runtime@6c61c40 packages/tgp/src/renderer-dom.ts): ring 3px #38bdf8
 * with a soft halo, slate card rgba(15,23,42,.97), double-pulse chevron (1.15s, 0.16s stagger), done #34d399, and
 * the glide: a new stage moves from the previous target on cubic-bezier(0.2, 0.8, 0.2, 1) (0.42s here) while
 * scroll-driven repositioning follows without a transition. Sizes are set for older readers.
 *
 * Hand-offs between steps: a finished
 * step leaves its bubble up as a mint afterglow (check pop, settle glow) and the next step's bubble glides on from
 * it; a bubble taken down for a moment (limbo, 400 ms) is still where the next one starts; a target that vanishes
 * for less than 800 ms keeps its ring and bubble; a waiting bubble carries the warm border trace (1.2 s).
 */
class GuideOverlay(private val service: AccessibilityService) {
    private val wm = service.getSystemService(WindowManager::class.java)
    private val density = service.resources.displayMetrics.density
    private fun dp(v: Float) = v * density
    private fun dpi(v: Float) = (v * density).toInt()

    /** What the bubble says: one instruction, plus the exact text to type when there is one. */
    data class Instruction(val headline: String, val typeText: String? = null, val kind: Kind = Kind.TAP, val sub: String? = null) {
        enum class Kind { TAP, TYPE, SCROLL, KEY, OPEN, WAIT, STATUS }
    }

    data class Chip(val label: String, val primary: Boolean = false, val onClick: () -> Unit) {
        constructor(label: String, onClick: () -> Unit) : this(label, false, onClick)
    }

    private val canvasView = GuideCanvasView(service)
    private var canvasAdded = false

    // ------------------------------------------------------------ touch probe

    /**
     * Touch-timing probe: a 1x1 window with FLAG_WATCH_OUTSIDE_TOUCH receives one ACTION_OUTSIDE per touch elsewhere.
     * The input dispatcher zeroes its coordinates when the touched window belongs to another UID, so this says
     * when a touch happened, never where (WindowManager.LayoutParams#FLAG_WATCH_OUTSIDE_TOUCH, InputDispatcher.cpp).
     */
    var onOutsideTouch: ((x: Float, y: Float) -> Unit)? = null
    private var probeAdded = false
    private val probe = View(service)

    fun ensureTouchProbe() {
        if (probeAdded) return
        val p = WindowManager.LayoutParams(1, 1, WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT).apply { gravity = Gravity.TOP or Gravity.START; title = "ToolusTouchProbe" }
        probe.setOnTouchListener { _, ev ->
            if (ev.actionMasked == MotionEvent.ACTION_OUTSIDE) onOutsideTouch?.invoke(ev.rawX, ev.rawY)
            false
        }
        wm.addView(probe, p)
        probeAdded = true
    }

    // ------------------------------------------------------------ windows

    private fun overlayParams(w: Int, h: Int, touchable: Boolean, name: String) = WindowManager.LayoutParams(
        w, h, WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            (if (touchable) WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL else WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE) or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
            WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
        PixelFormat.TRANSLUCENT,
    ).apply {
        gravity = Gravity.TOP or Gravity.START
        fitInsetsTypes = 0
        layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
        title = name
    }

    private fun ensureCanvas() {
        if (canvasAdded) return
        wm.addView(canvasView, overlayParams(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT, false, "ToolusGuideCanvas"))
        canvasAdded = true
    }

    private fun screen(): Pair<Int, Int> {
        val b = wm.currentWindowMetrics.bounds
        val dm = service.resources.displayMetrics
        return (b.width().takeIf { it > 0 } ?: dm.widthPixels) to (b.height().takeIf { it > 0 } ?: dm.heightPixels)
    }

    // ------------------------------------------------------------ runtime controls: done ✓ and end ✕

    private val controls = LinearLayout(service)
    private var controlsAdded = false
    private val controlParams = overlayParams(WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT, true, "ToolusGuideControls")
    private val doneButton = IconButton(service, IconButton.Icon.CHECK)
    private val endButton = IconButton(service, IconButton.Icon.CLOSE)
    /** The guide layout the controls were last placed for; a different one re-places them. */
    private var controlsOnTop = ""

    init {
        controls.orientation = LinearLayout.HORIZONTAL
        controls.gravity = Gravity.CENTER_VERTICAL
        controls.setPadding(dpi(8f), dpi(8f), dpi(8f), dpi(8f))
        controls.background = GradientDrawable().apply {
            setColor(Color.parseColor("#F20F172A"))
            cornerRadius = dp(40f)
            setStroke(dpi(1.5f), Color.parseColor("#5938BDF8"))
        }
        controls.elevation = dp(10f)
        val size = dpi(60f)
        controls.addView(doneButton, LinearLayout.LayoutParams(size, size))
        controls.addView(endButton, LinearLayout.LayoutParams(size, size).apply { marginStart = dpi(10f) })
        doneButton.contentDescription = "다 했어요. 지금 화면을 다시 확인합니다"
        endButton.contentDescription = "안내 끝내기"
    }

    /** Show the two runtime buttons; [onDone] null hides ✓ (nothing to confirm while the next step is being prepared). */
    fun showControls(onDone: (() -> Unit)?, onEnd: () -> Unit) {
        doneButton.visibility = if (onDone != null) View.VISIBLE else View.GONE
        doneButton.setOnClickListener { onDone?.invoke() }
        endButton.setOnClickListener { onEnd() }
        controls.visibility = View.VISIBLE
        placeControls()
    }

    fun hideControls() {
        if (controlsAdded) controls.visibility = View.INVISIBLE
    }

    /**
     * The first of bottom-right (above the navigation bar), top-right (below the status bar) and mid-right that
     * keeps clear of the ring, the bubble and the keyboard; bottom-right when none does.
     */
    private fun placeControls() {
        val (sw, sh) = screen()
        controls.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED)
        val cw = controls.measuredWidth
        val ch = controls.measuredHeight
        val x = sw - cw - dpi(16f)
        val spots = listOf(sh - ch - dpi(64f), dpi(48f), (sh - ch) / 2)
        val y = spots.firstOrNull { y -> !canvasView.overlaps(RectF(x.toFloat(), y.toFloat(), (x + cw).toFloat(), (y + ch).toFloat())) } ?: spots[0]
        controlsOnTop = canvasView.layoutKey()
        controlParams.x = x
        controlParams.y = y
        if (controlsAdded) wm.updateViewLayout(controls, controlParams) else {
            wm.addView(controls, controlParams)
            controlsAdded = true
        }
    }

    // ------------------------------------------------------------ the guide: ring + bubble

    private var stage = 0

    /**
     * A new step: the bubble [instr] attaches to the ring that [showAim] keeps on the target. A new stage glides from
     * the previous target; the following [showAim] calls (scroll, layout changes) follow without a transition.
     */
    fun showGuide(instr: Instruction, onDone: (() -> Unit)?, onEnd: () -> Unit) {
        ensureCanvas()
        hideCard()
        stage += 1
        canvasView.setInstruction(instr, stage)
        showControls(onDone, onEnd)
    }

    /** A bubble without a ring (preparing the next step, looking at the screen): bottom of the screen. */
    fun showStatus(text: String, onEnd: () -> Unit) {
        ensureCanvas()
        hideCard()
        stage += 1
        canvasView.ring = null
        canvasView.setInstruction(Instruction(text, kind = Instruction.Kind.STATUS), stage)
        showControls(null, onEnd)
    }

    /**
     * Ring on [target] (screen coords) and/or a pulsing chevron at the [chevron] edge of [viewport]
     * (screen when null), its cross axis clamped toward [axisFrom] — the TGP edge-chevron placement.
     */
    fun showAim(target: Rect?, chevron: String?, viewport: Rect?, axisFrom: Rect? = null, keyboardTop: Int? = null) {
        ensureCanvas()
        canvasView.keyboardTop = keyboardTop?.toFloat()
        canvasView.aimAt(target?.let { RectF(it) })
        canvasView.chevronDirection = chevron
        canvasView.viewport = viewport?.takeIf { !it.isEmpty }?.let { RectF(it) }
        canvasView.axisFrom = axisFrom?.let { RectF(it) }
        canvasView.invalidate()
        if (controlsAdded && controls.visibility == View.VISIBLE && canvasView.layoutKey() != controlsOnTop) placeControls()
    }

    /** The step was done: the ring turns mint for a moment and the bubble steps aside. */
    fun flashDone() {
        canvasView.flashDone()
    }

    /** The same step now asks for something else (open the keyboard, type, press its action key): the words swap in place. */
    fun retitle(instr: Instruction) {
        canvasView.retitle(instr)
    }

    // ------------------------------------------------------------ copy button beside the text to type

    /**
     * The copy button is drawn in the bubble (it glides with it); this transparent window over it takes the tap once
     * the bubble has settled. The canvas itself stays untouchable.
     */
    private val copyTouch = View(service).apply {
        contentDescription = "입력할 글자 복사"
        setOnClickListener { copyNow() }
    }
    private var copyAdded = false
    private val copyParams = overlayParams(1, 1, true, "ToolusGuideCopy")

    init {
        canvasView.onCopyRect = { r -> placeCopy(r) }
    }

    private fun placeCopy(r: RectF?) {
        if (r == null) {
            if (copyAdded) { try { wm.removeView(copyTouch) } catch (_: Exception) {}; copyAdded = false }
            return
        }
        copyParams.x = r.left.toInt()
        copyParams.y = r.top.toInt()
        copyParams.width = r.width().toInt()
        copyParams.height = r.height().toInt()
        if (copyAdded) wm.updateViewLayout(copyTouch, copyParams) else { wm.addView(copyTouch, copyParams); copyAdded = true }
        android.util.Log.i("ToolusGuide", "COPYBTN ${copyParams.x} ${copyParams.y} ${copyParams.x + copyParams.width} ${copyParams.y + copyParams.height}")
    }

    private fun copyNow() {
        val text = canvasView.copyable() ?: return
        service.getSystemService(android.content.ClipboardManager::class.java)
            ?.setPrimaryClip(android.content.ClipData.newPlainText("Toolus", text))
        canvasView.copied(text)
        android.util.Log.i("ToolusGuide", "COPY chars=${text.length}")
    }

    /**
     * After a touch the screen is changing while the step is still open: the ring lets go of the target and, if this
     * lasts, the bubble says it is checking. Off when the target is pointed at again.
     */
    fun checking(on: Boolean) {
        canvasView.checking(on)
    }

    /** The armed step was done by moving on: a mint check fades where its ring was (call after [hideAim]). */
    fun passed() {
        canvasView.passed()
    }

    /** Hide ring, chevron and bubble (before a screenshot, between steps). */
    fun hideAim() {
        placeCopy(null)
        canvasView.ring = null
        canvasView.aimAt(null)
        canvasView.chevronDirection = null
        canvasView.clearInstruction()
        canvasView.invalidate()
    }

    // ------------------------------------------------------------ choice card

    private val card = LinearLayout(service)
    private val titleView = TextView(service)
    private val bodyView = TextView(service)
    private val chipCol = LinearLayout(service)
    private var cardAdded = false
    private val cardParams = overlayParams(WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT, true, "ToolusGuideCard")

    init {
        card.orientation = LinearLayout.VERTICAL
        card.setPadding(dpi(22f), dpi(20f), dpi(22f), dpi(18f))
        card.background = GradientDrawable().apply {
            setColor(Color.parseColor("#F70F172A"))
            cornerRadius = dp(22f)
            setStroke(dpi(1.5f), Color.parseColor("#6638BDF8"))
        }
        card.elevation = dp(12f)
        titleView.setTextColor(Color.parseColor("#F8FAFC"))
        titleView.textSize = 22f
        titleView.typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        titleView.setLineSpacing(0f, 1.15f)
        bodyView.setTextColor(Color.parseColor("#CBD5E1"))
        bodyView.textSize = 17f
        bodyView.setLineSpacing(0f, 1.2f)
        bodyView.setPadding(0, dpi(8f), 0, 0)
        chipCol.orientation = LinearLayout.VERTICAL
        if (android.os.Build.VERSION.SDK_INT >= 33) {
            val phrase = android.graphics.text.LineBreakConfig.Builder()
                .setLineBreakWordStyle(android.graphics.text.LineBreakConfig.LINE_BREAK_WORD_STYLE_PHRASE).build()
            titleView.lineBreakWordStyle = phrase.lineBreakWordStyle
            bodyView.lineBreakWordStyle = phrase.lineBreakWordStyle
        }
        card.addView(titleView)
        card.addView(bodyView)
        card.addView(chipCol)
    }

    /** A card that needs a choice. [step] is kept for the call sites; the card itself does not show it. */
    @Suppress("UNUSED_PARAMETER", "unused")
    fun showCard(step: String, title: String, why: String, chips: List<Chip>, avoid: Rect?) {
        hideAim()
        hideControls()
        val done = step == "완료"
        (card.background as GradientDrawable).setStroke(dpi(if (done) 2f else 1.5f), Color.parseColor(if (done) "#34D399" else "#6638BDF8"))
        titleView.setCompoundDrawablesRelativeWithIntrinsicBounds(if (done) CheckBadge(service) else null, null, null, null)
        titleView.compoundDrawablePadding = dpi(10f)
        titleView.text = title
        bodyView.text = why
        bodyView.visibility = if (why.isBlank()) View.GONE else View.VISIBLE
        chipCol.removeAllViews()
        chips.forEach { c ->
            val b = Button(service).apply {
                text = c.label
                isAllCaps = false
                textSize = 18f
                typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
                setTextColor(if (c.primary) Color.parseColor("#0F172A") else Color.parseColor("#F8FAFC"))
                minHeight = dpi(56f)
                stateListAnimator = null
                background = GradientDrawable().apply {
                    setColor(Color.parseColor(if (c.primary) "#38BDF8" else "#1E293B"))
                    cornerRadius = dp(16f)
                    if (!c.primary) setStroke(dpi(1f), Color.parseColor("#475569"))
                }
                setOnClickListener { c.onClick() }
            }
            chipCol.addView(b, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dpi(58f)).apply { topMargin = dpi(12f) })
        }
        card.visibility = View.VISIBLE
        val (sw, sh) = screen()
        val margin = dpi(16f)
        val width = sw - 2 * margin
        card.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(sh, View.MeasureSpec.AT_MOST))
        cardParams.width = width
        cardParams.x = margin
        cardParams.y = sh - card.measuredHeight - dpi(56f)
        if (cardAdded) wm.updateViewLayout(card, cardParams) else {
            wm.addView(card, cardParams)
            cardAdded = true
        }
    }

    /** Keep the card window but make it transparent, e.g. while a screenshot is taken. */
    fun hideCard() {
        if (cardAdded) card.visibility = View.INVISIBLE
    }

    fun hideAll() {
        hideAim()
        canvasView.resetMotion() // the next guide starts fresh: its first ring closes in instead of gliding from here
        placeCopy(null)
        if (cardAdded) { try { wm.removeView(card) } catch (_: Exception) {}; cardAdded = false }
        if (controlsAdded) { try { wm.removeView(controls) } catch (_: Exception) {}; controlsAdded = false }
        if (canvasAdded) { try { wm.removeView(canvasView) } catch (_: Exception) {}; canvasAdded = false }
    }
}

/** The mint check in front of a finished card's title. */
private class CheckBadge(context: Context) : android.graphics.drawable.Drawable() {
    private val d = context.resources.displayMetrics.density
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#34D399") }
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 3.2f * d; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
        color = Color.parseColor("#06281D")
    }
    override fun getIntrinsicWidth() = (30 * d).toInt()
    override fun getIntrinsicHeight() = (30 * d).toInt()
    override fun draw(canvas: Canvas) {
        val b = bounds
        val r = min(b.width(), b.height()) / 2f
        val cx = b.exactCenterX(); val cy = b.exactCenterY()
        canvas.drawCircle(cx, cy, r, fill)
        val s = r * 0.45f
        canvas.drawPath(Path().apply { moveTo(cx - s, cy + s * 0.05f); lineTo(cx - s * 0.25f, cy + s * 0.75f); lineTo(cx + s, cy - s * 0.6f) }, stroke)
    }
    override fun setAlpha(alpha: Int) {}
    override fun setColorFilter(colorFilter: android.graphics.ColorFilter?) {}
    @Deprecated("Deprecated in Java")
    override fun getOpacity() = PixelFormat.TRANSLUCENT
}

/** A round icon button drawn with paths (no icon font): ✓ in mint, ✕ in slate. */
private class IconButton(context: Context, private val icon: Icon) : View(context) {
    enum class Icon { CHECK, CLOSE }

    private val d = context.resources.displayMetrics.density
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 4.5f * d; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
    }
    private var pressed = false

    init {
        isClickable = true
        isFocusable = true
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> { pressed = true; invalidate() }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> { pressed = false; invalidate() }
        }
        return super.onTouchEvent(event)
    }

    override fun onDraw(canvas: Canvas) {
        val cx = width / 2f
        val cy = height / 2f
        val r = min(width, height) / 2f
        when (icon) {
            Icon.CHECK -> {
                fill.color = if (pressed) Color.parseColor("#10B981") else Color.parseColor("#34D399")
                stroke.color = Color.parseColor("#06281D")
            }
            Icon.CLOSE -> {
                fill.color = if (pressed) Color.parseColor("#475569") else Color.parseColor("#334155")
                stroke.color = Color.parseColor("#F8FAFC")
            }
        }
        canvas.drawCircle(cx, cy, r, fill)
        val p = Path()
        val s = r * 0.42f
        if (icon == Icon.CHECK) {
            p.moveTo(cx - s, cy + s * 0.05f); p.lineTo(cx - s * 0.25f, cy + s * 0.75f); p.lineTo(cx + s, cy - s * 0.6f)
        } else {
            val k = s * 0.8f
            p.moveTo(cx - k, cy - k); p.lineTo(cx + k, cy + k); p.moveTo(cx + k, cy - k); p.lineTo(cx - k, cy + k)
        }
        canvas.drawPath(p, stroke)
    }
}

private class GuideCanvasView(context: Context) : View(context) {
    var ring: RectF? = null
    var chevronDirection: String? = null
    var viewport: RectF? = null
    var axisFrom: RectF? = null
    /** Top of the on-screen keyboard (screen y) while one is showing: the bubble stays above it. */
    var keyboardTop: Float? = null
    /** Where the copy button is on screen once its bubble has settled (null: none or moving); see [GuideOverlay.placeCopy]. */
    var onCopyRect: ((RectF?) -> Unit)? = null
    private var copyRectSent: RectF? = null
    /** The text the person copied with the button this stage; the bubble then says how to paste it. */
    private var copiedText: String? = null
    private var copiedAt = 0L

    private val d = context.resources.displayMetrics.density
    private val accent = Color.parseColor("#38BDF8")
    private val mint = Color.parseColor("#34D399")
    private val slate = Color.parseColor("#F50F172A")
    private val ringStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 3.5f * d; color = accent }
    private val ringFill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL; color = Color.argb(22, 56, 189, 248) }
    // glows are shadow layers (hardware-drawn for shapes since API 28); a mask filter would force a software layer
    private val ringGlow = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 6f * d; color = Color.argb(117, 56, 189, 248) }
    private val ripple = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 3f * d; color = accent }
    private val chevronPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 5f * d; color = accent
        strokeCap = Paint.Cap.SQUARE; strokeJoin = Paint.Join.MITER
    }
    private val chevronGlow = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 7f * d; color = Color.argb(184, 56, 189, 248) }
    private val bubbleFill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL; color = slate }
    private val bubbleBorder = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 2f * d; color = accent }
    private val headPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#F8FAFC"); textSize = 24f * context.resources.displayMetrics.scaledDensity
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
    }
    private val chipText = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#E0F2FE"); textSize = 19f * context.resources.displayMetrics.scaledDensity
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
    }
    private val subText = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#CBD5E1"); textSize = 17f * context.resources.displayMetrics.scaledDensity
    }
    private val copyIcon = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 2f * d; strokeJoin = Paint.Join.ROUND; strokeCap = Paint.Cap.ROUND
    }
    private val chipFill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL; color = Color.argb(46, 56, 189, 248) }
    private val chipBorder = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 1.5f * d; color = Color.argb(160, 56, 189, 248) }
    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL; color = accent }
    private val started = System.currentTimeMillis()
    private val warm = Color.parseColor("#EAB308")
    private val halo = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; color = mint }
    private val tracePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 2.5f * d }
    private val checkFill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL; color = mint }
    private val checkStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 3.2f * d; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
        color = Color.parseColor("#06281D")
    }
    /** The waiting border trace: a warm arc running round the bubble (the IDE card's conic trace, 0..36%). */
    private val trace = android.graphics.SweepGradient(0f, 0f,
        intArrayOf(Color.argb(0, 234, 179, 8), Color.argb(66, 234, 179, 8), Color.argb(245, 234, 179, 8), Color.argb(148, 234, 179, 8),
            Color.argb(0, 234, 179, 8), Color.argb(0, 234, 179, 8)),
        floatArrayOf(0f, 0.09f, 0.18f, 0.27f, 0.36f, 1f))
    private val traceMatrix = android.graphics.Matrix()

    // ring glide: the drawn ring moves from [fromRect] to [target] over GLIDE_MS once per stage
    private var target: RectF? = null
    private var fromRect: RectF? = null
    private var glideStart = 0L
    private var glideStage = -1
    private var lastDrawn: RectF? = null
    private var instr: GuideOverlay.Instruction? = null
    private var instrStage = 0
    /** This stage has shown a ring; losing it means the screen moved on, not that there was nothing to point at. */
    private var stageHadRing = false
    /** When the ringed target went missing. Ring and bubble hold TARGET_LOSS_GRACE_MS: a redraw can swap the node. */
    private var lostAt = 0L
    private var done = false
    private var doneAt = 0L
    /** First ring of a guide (nothing to glide from): it closes in on the target instead. */
    private var lockOn = false
    /** The previous ring had already faded (after a done flash): the new one fades in while it glides. */
    private var ringFadeIn = false
    /** A finished step's ring, checked off in mint and fading while the next ring glides away from it. */
    private var ghost: RectF? = null
    private var ghostAt = 0L
    /** The ring let go of its target this stage (checking); kept past [clearInstruction] for [passed] and the next glide. */
    private var ringLetGo = false
    /** Since when the screen has been changing after a touch (0 = not checking); see [checking]. */
    private var checkingAt = 0L
    /** Where the bubble stays while it checks or glows: the ring it was attached to. */
    private var heldAnchor: RectF? = null
    /** The done flash has a ring to draw (not when the step closed while checking, the target long gone). */
    private var doneRing = false

    // the bubble: ONE body that glides to each new placement and swaps its content in place, so the eye is carried
    // from the finished step to the next (the IDE stage card's afterglow -> glide hand-off)
    private var shown: BubbleText? = null
    private var outgoing: BubbleText? = null
    private var bodyFrom: RectF? = null
    private var bodyGlideAt = 0L
    private var bodyGlideMs = GLIDE_MS
    private var borderFrom = accent
    private var drawnBody: RectF? = null
    private var drawnBodyAt = 0L
    private var drawnBorder = accent
    private var bodyStage = -1
    private var enteredAt = 0L
    private var bubbleAbove = false
    private var placedStage = -1
    private var placedKey = ""
    private var bubbleRect = RectF()
    private val glideCurve = PathInterpolator(0.2f, 0.8f, 0.2f, 1f)
    private val easeOut = PathInterpolator(0f, 0f, 0.58f, 1f)
    private val motion get() = ValueAnimator.areAnimatorsEnabled()

    init {
        setLayerType(LAYER_TYPE_NONE, null) // hardware: a full-screen software layer re-uploads 10 MB per frame
    }

    fun setInstruction(i: GuideOverlay.Instruction, stage: Int) {
        instr = i
        instrStage = stage
        // a new stage glides from wherever the ring was last drawn
        fromRect = lastDrawn
        lockOn = lastDrawn == null
        val now = System.currentTimeMillis()
        if (done && target != null && ringVisibility(now) > 0f) {
            ghost = lastDrawn?.let { RectF(it) }
            ghostAt = now - ((1f - ringVisibility(now)) * GHOST_MS).toLong()
        }
        ringFadeIn = done || checkingAt != 0L || ringLetGo
        ringLetGo = false
        copiedText = null
        checkingAt = 0L
        heldAnchor = null
        glideStage = stage
        stageHadRing = false
        lostAt = 0L
        glideStart = 0L
        done = false
        invalidate()
    }

    /** Same stage, new words; the ring glides from where it was to the next thing it points at (field -> keyboard key). */
    fun retitle(i: GuideOverlay.Instruction) {
        if (instr == null || done) return
        instr = i
        fromRect = lastDrawn
        lockOn = lastDrawn == null
        glideStart = 0L
        invalidate()
    }

    /** The text the copy button on screen would copy. */
    fun copyable(): String? = shown?.typeText

    fun copied(text: String) {
        copiedText = text
        copiedAt = System.currentTimeMillis()
        invalidate()
    }

    /** After copying, the bubble says how to paste: Android shows Paste when an input field is pressed and held. */
    private fun pasteHint(i: GuideOverlay.Instruction): String? =
        if (i.typeText != null && i.typeText == copiedText) "입력란을 길게 누르면 ‘붙여넣기’가 나와요" else null

    private fun publishCopy(r: RectF?) {
        val sent = copyRectSent
        if (r == null && sent == null) return
        if (r != null && sent != null && Math.abs(r.left - sent.left) < 2f && Math.abs(r.top - sent.top) < 2f &&
            Math.abs(r.width() - sent.width()) < 2f) return
        copyRectSent = r?.let { RectF(it) }
        post { onCopyRect?.invoke(copyRectSent) }
    }

    /** Off at once (a screenshot is about to be taken); the last body is kept only as the place a next bubble glides from. */
    fun clearInstruction() {
        instr = null
        done = false
        lostAt = 0L
        stageHadRing = false
        ghost = null
        checkingAt = 0L
    }

    fun checking(on: Boolean) {
        if (on) {
            if (instr == null || done || checkingAt != 0L) return
            checkingAt = System.currentTimeMillis()
            ringLetGo = true
            heldAnchor = lastDrawn?.let { RectF(it) }
        } else { checkingAt = 0L; ringLetGo = false }
        invalidate()
    }

    fun passed() {
        if (!motion || ringLetGo) return
        ghost = lastDrawn?.let { RectF(it) } ?: return
        ghostAt = System.currentTimeMillis()
        ringFadeIn = true
        invalidate()
    }

    fun aimAt(r: RectF?) {
        val now = System.currentTimeMillis()
        if (r == null && target != null && lostAt == 0L) lostAt = now
        if (r != null) lostAt = 0L
        target = r
        ring = r
        if (r != null && glideStart == 0L && glideStage == instrStage) glideStart = now
    }

    fun resetMotion() {
        lastDrawn = null
        fromRect = null
        drawnBody = null
    }

    fun flashDone() {
        val now = System.currentTimeMillis()
        // the tap that finished the step often removed its target a moment earlier: flash where the ring was
        if (target == null && lostAt != 0L && now - lostAt < TARGET_LOSS_GRACE_MS && checkingAt == 0L) target = lastDrawn?.let { RectF(it) }
        if (checkingAt != 0L) target = null // the screen already moved on: the afterglow stays where the bubble was
        doneRing = target != null
        heldAnchor = target?.let { RectF(it) } ?: heldAnchor
        checkingAt = 0L
        lostAt = 0L
        done = true
        doneAt = now
        instr = null
        invalidate()
    }

    /** Whether [r] (screen coords) would cover the ring, the bubble or the keyboard. */
    fun overlaps(r: RectF): Boolean {
        val pad = 12f * d
        val ring = target?.let { RectF(it.left - pad, it.top - pad, it.right + pad, it.bottom + pad) }
        if (ring != null && RectF.intersects(ring, r)) return true
        if (!bubbleRect.isEmpty && RectF.intersects(bubbleRect, r)) return true
        val k = keyboardTop
        return k != null && r.bottom > k
    }

    /** A coarse key of what the controls must avoid; when it changes they are placed again. */
    fun layoutKey(): String {
        val q = 120f * d
        val r = target
        return listOf(r?.let { "${(it.top / q).toInt()}:${(it.bottom / q).toInt()}:${(it.right / q).toInt()}" } ?: "-",
            if (!bubbleRect.isEmpty) "${(bubbleRect.top / q).toInt()}:${(bubbleRect.bottom / q).toInt()}" else "-",
            keyboardTop?.let { (it / q).toInt().toString() } ?: "-").joinToString("|")
    }

    private fun lerp(a: Float, b: Float, t: Float) = a + (b - a) * t
    private fun lerp(a: RectF, b: RectF, t: Float) = RectF(lerp(a.left, b.left, t), lerp(a.top, b.top, t), lerp(a.right, b.right, t), lerp(a.bottom, b.bottom, t))
    private fun blend(a: Int, b: Int, t: Float) = Color.argb(lerp(Color.alpha(a).toFloat(), Color.alpha(b).toFloat(), t).toInt(),
        lerp(Color.red(a).toFloat(), Color.red(b).toFloat(), t).toInt(), lerp(Color.green(a).toFloat(), Color.green(b).toFloat(), t).toInt(),
        lerp(Color.blue(a).toFloat(), Color.blue(b).toFloat(), t).toInt())

    /** The ring as drawn this frame: gliding from the previous target at the start of a stage, then on the target. */
    private fun drawnRing(now: Long): RectF? {
        val t = target ?: return lastDrawn?.takeIf {
            chevronDirection == null && stageHadRing && lostAt != 0L && now - lostAt < TARGET_LOSS_GRACE_MS
        }
        val from = fromRect ?: if (lockOn && motion && glideStart != 0L) {
            val k = 0.35f
            RectF(t.left - t.width() * k / 2, t.top - t.height() * k / 2, t.right + t.width() * k / 2, t.bottom + t.height() * k / 2)
        } else null
        if (!motion || from == null || glideStart == 0L) return t
        val p = ((now - glideStart).toFloat() / GLIDE_MS).coerceIn(0f, 1f)
        if (p >= 1f) { fromRect = null; lockOn = false; ringFadeIn = false; return t }
        return lerp(from, t, glideCurve.getInterpolation(p))
    }

    /** 1 while the ring shows; a done ring holds its check for a moment, then fades and leaves the afterglow bubble. */
    private fun ringVisibility(now: Long): Float {
        if (!done) return 1f
        return 1f - ((now - doneAt - DONE_RING_HOLD_MS) / FADE_MS).coerceIn(0f, 1f)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val loc = IntArray(2)
        getLocationOnScreen(loc) // window origin should be (0,0); subtract it just in case
        canvas.save()
        canvas.translate(-loc[0].toFloat(), -loc[1].toFloat())
        val now = System.currentTimeMillis()
        val t = (now - started) / 1000.0
        ghost?.let { g -> if (now - ghostAt < GHOST_MS) drawGhost(canvas, g, now) else ghost = null }
        val checking = checkingAt != 0L
        val r = if (checking || (done && !doneRing)) null else drawnRing(now)
        val ringVis = ringVisibility(now)
        if (checking) heldAnchor?.let { h ->
            val fade = 1f - ((now - checkingAt) / FADE_MS).coerceIn(0f, 1f)
            if (fade > 0f && motion) drawRing(canvas, h, t, now, fade)
        }
        if (r != null) {
            if (ringVis > 0f) drawRing(canvas, r, t, now, ringVis)
            lastDrawn = RectF(r)
            stageHadRing = true
        }
        chevronDirection?.let { dir -> drawChevron(canvas, dir, t) }
        val lost = r == null && chevronDirection == null && stageHadRing && !checking && instr?.kind != GuideOverlay.Instruction.Kind.WAIT
        val i = instr
        val want: BubbleText? = when {
            done -> if (now - doneAt < AFTERGLOW_MS + FADE_MS) text("잘하셨어요", null, null, null, afterglow = true) else null
            checking && i != null -> if (now - checkingAt >= CHECK_DELAY_MS) text("확인하고 있어요", null, null, GuideOverlay.Instruction.Kind.STATUS)
                else text(i.headline, i.typeText, i.sub ?: pasteHint(i), i.kind)
            i == null || lost -> null
            r == null && chevronDirection != null && i.kind != GuideOverlay.Instruction.Kind.SCROLL &&
                i.kind != GuideOverlay.Instruction.Kind.STATUS && i.kind != GuideOverlay.Instruction.Kind.WAIT -> {
                // the chevron points where the target is; the finger moves the other way
                val head = when (chevronDirection) {
                    "down" -> "화면을 위로 밀어 올리세요"
                    "up" -> "화면을 아래로 끌어내리세요"
                    "right" -> "화면을 왼쪽으로 넘기세요"
                    else -> "화면을 오른쪽으로 넘기세요"
                }
                text(head, null, i.headline, GuideOverlay.Instruction.Kind.SCROLL)
            }
            else -> text(i.headline, i.typeText, i.sub ?: pasteHint(i), i.kind)
        }
        val ringShown = r != null && ringVis > 0.5f
        drawBubble(canvas, want, if (ringShown) r else if (done || checking) heldAnchor else null, ringShown, now, t)
        canvas.restore()
        if ((r != null && ringVis > 0f) || chevronDirection != null || instr != null || shown != null || ghost != null || checking) postInvalidateOnAnimation()
    }

    private fun drawRing(canvas: Canvas, r: RectF, t: Double, now: Long, vis: Float) {
        val pad = 5f * d
        val rr = RectF(r.left - pad, r.top - pad, r.right + pad, r.bottom + pad)
        val radius = 12f * d
        val color = if (done) mint else accent
        ringStroke.color = color
        ringGlow.color = Color.argb(117, Color.red(color), Color.green(color), Color.blue(color))
        ringFill.color = Color.argb(if (done) 40 else 22, Color.red(color), Color.green(color), Color.blue(color))
        val pulse = if (motion) (0.6 + 0.4 * sin(t * 2 * PI / 1.6)).toFloat() else 1f
        val fadeIn = if ((lockOn || ringFadeIn) && motion && glideStart != 0L) (0.25f + 0.75f * ((now - glideStart) / GLIDE_MS).coerceIn(0f, 1f)) else 1f
        val a = fadeIn * vis
        ringStroke.alpha = (255 * a).toInt()
        ringGlow.alpha = (60 * pulse * a).toInt()
        ringGlow.setShadowLayer(12f * d, 0f, 0f, Color.argb((117 * pulse * a).toInt(), Color.red(color), Color.green(color), Color.blue(color)))
        ringFill.alpha = (ringFill.alpha * a).toInt()
        canvas.drawRoundRect(rr, radius, radius, ringGlow)
        canvas.drawRoundRect(rr, radius, radius, ringFill)
        // breathing ripple: an outline leaves the ring and fades, once every 1.6 s, to pull the eye there
        if (motion && !done) {
            val phase = ((t % RIPPLE_S) / RIPPLE_S).toFloat()
            val grow = 18f * d * phase
            ripple.alpha = (150 * (1f - phase) * (1f - phase) * a).toInt()
            canvas.drawRoundRect(RectF(rr.left - grow, rr.top - grow, rr.right + grow, rr.bottom + grow), radius + grow, radius + grow, ripple)
        }
        canvas.drawRoundRect(rr, radius, radius, ringStroke)
        if (done && motion) {
            val q = ((now - doneAt) / DONE_WAVE_MS).coerceIn(0f, 1f)
            if (q < 1f) {
                val grow = 26f * d * glideCurve.getInterpolation(q)
                ripple.color = mint
                ripple.alpha = (210 * (1f - q)).toInt()
                canvas.drawRoundRect(RectF(rr.left - grow, rr.top - grow, rr.right + grow, rr.bottom + grow), radius + grow, radius + grow, ripple)
                ripple.color = accent
            }
        }
        if (done) {
            val (scale, pa) = pop(now - doneAt)
            drawCheckBadge(canvas, rr, scale, pa * vis)
        }
    }

    /** A mint check badge on the top-right corner of [rr] (kept on screen). */
    private fun drawCheckBadge(canvas: Canvas, rr: RectF, scale: Float, a: Float) {
        val rad = 14f * d
        val cx = rr.right.coerceIn(rad + 4f * d, width - rad - 4f * d)
        val cy = rr.top.coerceIn(rad + 4f * d, height - rad - 4f * d)
        val r = rad * scale
        checkFill.alpha = (255 * a).toInt()
        checkStroke.alpha = (255 * a).toInt()
        canvas.drawCircle(cx, cy, r + 2.5f * d, Paint(bubbleFill).apply { alpha = (235 * a).toInt() })
        canvas.drawCircle(cx, cy, r, checkFill)
        val s = r * 0.45f
        canvas.drawPath(Path().apply { moveTo(cx - s, cy + s * 0.05f); lineTo(cx - s * 0.25f, cy + s * 0.75f); lineTo(cx + s, cy - s * 0.6f) }, checkStroke)
    }

    /** The checked-off ring: mint outline and check, swelling a little as it fades over GHOST_MS. */
    private fun drawGhost(canvas: Canvas, g: RectF, now: Long) {
        val q = ((now - ghostAt) / GHOST_MS).coerceIn(0f, 1f)
        val a = 1f - easeOut.getInterpolation(q)
        val pad = 5f * d + 8f * d * glideCurve.getInterpolation(q)
        val rr = RectF(g.left - pad, g.top - pad, g.right + pad, g.bottom + pad)
        val radius = 12f * d + pad
        val stroke = Paint(ringStroke).apply { color = mint; alpha = (255 * a).toInt() }
        canvas.drawRoundRect(rr, radius, radius, Paint(ringFill).apply { color = mint; alpha = (40 * a).toInt() })
        canvas.drawRoundRect(rr, radius, radius, stroke)
        drawCheckBadge(canvas, rr, 1f, a)
    }

    /** The IDE's check pop: scale 0.6 -> 1.15 (60%) -> 1 and opacity 0 -> 1 over 500 ms, ease-out. Returns scale to opacity. */
    private fun pop(age: Long): Pair<Float, Float> {
        if (!motion) return 1f to 1f
        val q = easeOut.getInterpolation((age / POP_MS).coerceIn(0f, 1f))
        val scale = if (q < 0.6f) lerp(0.6f, 1.15f, q / 0.6f) else lerp(1.15f, 1f, (q - 0.6f) / 0.4f)
        return scale to q
    }

    // ------------------------------------------------------------ bubble

    /** What one bubble says, laid out once for the width it is drawn at. */
    private inner class BubbleText(val headline: String, val typeText: String?, val sub: String?,
                                   val kind: GuideOverlay.Instruction.Kind?, val afterglow: Boolean) {
        val key = listOf(headline, typeText, sub, kind, afterglow).joinToString("\u0001")
        val waiting = kind == GuideOverlay.Instruction.Kind.STATUS || kind == GuideOverlay.Instruction.Kind.WAIT
        val border = when { afterglow -> mint; waiting -> Color.argb(184, 234, 179, 8); else -> accent }
        /** Room for the check in front of an afterglow headline. */
        val lead = if (afterglow) 40f * d else 0f
        private var builtFor = -1f
        lateinit var head: StaticLayout
        var chip: StaticLayout? = null
        var subLayout: StaticLayout? = null
        var headW = 0f
        var chipW = 0f
        /** The text box's height (at least a 44 dp touch target for its copy icon). */
        var rowH = 0f
        /** The copy icon's place at the right end inside the text box. */
        val copyW = if (typeText != null) COPY_AREA * d else 0f
        /** The copy icon's place inside the body (body-relative), set when drawn. */
        var copyLocal: RectF? = null
        var w = 0f
        var h = 0f

        fun layout(maxW: Float) {
            if (maxW == builtFor) return
            builtFor = maxW
            val textW = (maxW - 2 * PAD_X * d - lead - (if (waiting) 44f * d else 0f)).toInt()
            head = StaticLayout.Builder.obtain(headline, 0, headline.length, headPaint, textW)
                .setAlignment(Layout.Alignment.ALIGN_NORMAL).setLineSpacing(0f, 1.12f).phrases().build()
            chip = typeText?.let { s -> StaticLayout.Builder.obtain(s, 0, s.length, chipText, textW - (16f * d + copyW).toInt()).setLineSpacing(0f, 1.1f).phrases().build() }
            subLayout = sub?.let { s -> StaticLayout.Builder.obtain(s, 0, s.length, subText, textW).setLineSpacing(0f, 1.1f).phrases().build() }
            headW = (0 until head.lineCount).maxOf { head.getLineWidth(it) }
            chipW = chip?.let { c -> (0 until c.lineCount).maxOf { c.getLineWidth(it) } + 16f * d + copyW } ?: 0f
            val subW = subLayout?.let { c -> (0 until c.lineCount).maxOf { c.getLineWidth(it) } } ?: 0f
            val contentW = maxOf(lead + headW + (if (waiting) 44f * d else 0f), chipW, subW)
            w = min(maxW, max(contentW + 2 * PAD_X * d, 160f * d))
            rowH = chip?.let { max(it.height + 20f * d, 44f * d) } ?: 0f
            h = PAD_Y * d * 2 + max(head.height.toFloat(), if (afterglow) 30f * d else 0f) +
                (if (chip != null) 12f * d + rowH else 0f) + (subLayout?.let { 8f * d + it.height } ?: 0f)
        }

        fun draw(canvas: Canvas, body: RectF, a: Float, now: Long, t: Double) {
            if (a <= 0f) return
            canvas.save()
            canvas.translate(body.left + PAD_X * d, body.top + PAD_Y * d)
            val headTop = if (afterglow) max(0f, (30f * d - head.height) / 2) else 0f
            if (afterglow) {
                // mint check badge that pops in, then the words
                val (scale, pa) = pop(now - doneAt)
                val rad = 15f * d * scale
                val cy = headTop + head.height / 2f
                checkFill.alpha = (255 * a * pa).toInt(); checkStroke.alpha = (255 * a * pa).toInt()
                canvas.drawCircle(15f * d, cy, rad, checkFill)
                val s = rad * 0.45f
                canvas.drawPath(Path().apply { moveTo(15f * d - s, cy + s * 0.05f); lineTo(15f * d - s * 0.25f, cy + s * 0.75f); lineTo(15f * d + s, cy - s * 0.6f) }, checkStroke)
            }
            canvas.save()
            canvas.translate(lead, headTop)
            headPaint.alpha = (255 * a).toInt()
            head.draw(canvas)
            canvas.restore()
            if (waiting) {
                // three dots breathing in turn after the text: the guide is working
                val baseX = headW + 14f * d
                val cy = head.getLineBaseline(head.lineCount - 1) - 8f * d
                for (k in 0..2) {
                    val ph = if (motion) ((t * 1.4 - k * 0.18) % 1.0 + 1.0) % 1.0 else 0.5
                    dotPaint.color = warm
                    dotPaint.alpha = ((0.3 + 0.7 * sin(ph * PI)) * 255 * a).toInt().coerceIn(0, 255)
                    canvas.drawCircle(baseX + k * 11f * d, cy, 3.6f * d, dotPaint)
                }
            }
            var y = head.height.toFloat()
            subLayout?.let { s ->
                canvas.save()
                canvas.translate(0f, y + 8f * d)
                subText.alpha = (215 * a).toInt()
                s.draw(canvas)
                canvas.restore()
                y += 8f * d + s.height
            }
            chip?.let { c ->
                val top = y + 12f * d
                canvas.translate(0f, top)
                val cr = RectF(0f, 0f, chipW, rowH)
                chipFill.alpha = (46 * a).toInt(); chipBorder.alpha = (160 * a).toInt()
                canvas.drawRoundRect(cr, 12f * d, 12f * d, chipFill)
                canvas.drawRoundRect(cr, 12f * d, 12f * d, chipBorder)
                val br = RectF(chipW - copyW, 0f, chipW, rowH)
                drawCopyIcon(canvas, br, a, now)
                copyLocal = RectF(br).apply { offset(PAD_X * d, PAD_Y * d + top) }
                canvas.translate(12f * d, (rowH - c.height) / 2)
                chipText.alpha = (255 * a).toInt()
                c.draw(canvas)
            }
            canvas.restore()
        }

        /**
         * The copy icon at the right end inside the text box, past a hairline: two stacked sheets; for a moment after a
         * tap, a mint check that pops (the system says "복사했어요" too).
         */
        private fun drawCopyIcon(canvas: Canvas, br: RectF, a: Float, now: Long) {
            val ok = copiedText != null && copiedText == typeText && now - copiedAt < COPIED_MS
            chipBorder.alpha = (70 * a).toInt()
            canvas.drawLine(br.left, br.top + 10f * d, br.left, br.bottom - 10f * d, chipBorder)
            val cx = br.centerX()
            val cy = br.centerY()
            copyIcon.color = if (ok) mint else Color.parseColor("#BAE6FD")
            copyIcon.alpha = (255 * a).toInt()
            if (ok) {
                val (scale, pa) = pop(now - copiedAt)
                val k = 7f * d * scale
                copyIcon.alpha = (255 * a * pa).toInt()
                canvas.drawPath(Path().apply { moveTo(cx - k, cy); lineTo(cx - k * 0.2f, cy + k * 0.75f); lineTo(cx + k, cy - k * 0.7f) }, copyIcon)
            } else {
                val sz = 11f * d
                canvas.drawRoundRect(RectF(cx - sz / 2 + 2.5f * d, cy - sz / 2 - 2.5f * d, cx + sz / 2 + 2.5f * d, cy + sz / 2 - 2.5f * d), 2.5f * d, 2.5f * d, copyIcon)
                canvas.drawRoundRect(RectF(cx - sz / 2 - 2.5f * d, cy - sz / 2 + 2.5f * d, cx + sz / 2 - 2.5f * d, cy + sz / 2 + 2.5f * d), 2.5f * d, 2.5f * d, copyIcon)
            }
        }
    }

    /** The content for this frame: the one already shown when it says the same, so layouts are built once. */
    private fun text(headline: String, typeText: String?, sub: String?, kind: GuideOverlay.Instruction.Kind?, afterglow: Boolean = false): BubbleText {
        val b = BubbleText(headline, typeText, sub, kind, afterglow)
        return shown?.takeIf { it.key == b.key } ?: b
    }

    /** Where [b] wants to sit: below the ring when it fits, else above; without a ring, at the bottom above the controls. */
    private fun place(b: BubbleText, ringRect: RectF?, sw: Float, sh: Float): RectF {
        val margin = 16f * d
        val w = b.w
        val h = b.h
        if (ringRect == null) {
            placedKey = "free"
            val left = (sw - w) / 2
            val top = min(sh - 150f * d, (keyboardTop ?: sh) - 24f * d) - h
            return RectF(left, top, left + w, top + h)
        }
        val k = keyboardTop
        if (k != null && ringRect.top >= k - 1f) {
            // a key on the keyboard: the bubble stands just above the keyboard, toward the key, its keys left clear
            val l = (ringRect.centerX() - w / 2).coerceIn(margin, sw - margin - w)
            val y = (k - GAP * d - TAIL_H * d - h).coerceAtLeast(40f * d)
            bubbleAbove = true
            placedStage = instrStage
            placedKey = "keyboard"
            return RectF(l, y, l + w, y + h)
        }
        val pad = 5f * d
        val below = ringRect.bottom + pad + GAP * d + TAIL_H * d
        val above = ringRect.top - pad - GAP * d - TAIL_H * d - h
        val floor = min(sh - 72f * d, (keyboardTop ?: sh) - 8f * d)
        val fitsBelow = below + h < floor
        val fitsAbove = above > 40f * d
        // typing brings the keyboard up under the field: say it above the field when there is room
        val preferAbove = b.kind == GuideOverlay.Instruction.Kind.TYPE && fitsAbove
        if (placedStage != instrStage || (bubbleAbove && !fitsAbove) || (!bubbleAbove && !fitsBelow)) {
            bubbleAbove = preferAbove || (!fitsBelow && fitsAbove)
            placedStage = instrStage
        }
        val l = (ringRect.centerX() - w / 2).coerceIn(margin, sw - margin - w)
        val y = when {
            bubbleAbove -> above
            fitsBelow -> below
            else -> (floor - h - 12f * d).coerceAtLeast(40f * d)
        }
        placedKey = when { bubbleAbove -> "above"; fitsBelow -> "below"; else -> "floor" }
        return RectF(l, y, l + w, y + h)
    }

    private fun glideBody(from: RectF, now: Long, ms: Float) {
        bodyFrom = RectF(from)
        bodyGlideAt = now
        bodyGlideMs = ms
        borderFrom = drawnBorder
    }

    private fun drawBubble(canvas: Canvas, want: BubbleText?, anchor: RectF?, ringShown: Boolean, now: Long, t: Double) {
        val sw = width.toFloat()
        val sh = height.toFloat()
        if (want == null) {
            // gone at once (a screenshot may follow); the last body stays the place a next bubble glides from
            if (shown != null) { shown = null; outgoing = null; bodyFrom = null }
            bubbleRect = RectF()
            publishCopy(null)
            return
        }
        want.layout(sw - 32f * d)
        val prevKey = placedKey
        val dest = place(want, anchor, sw, sh)
        if (want.key != shown?.key) {
            val from = drawnBody
            val ms = if (bodyStage != instrStage) GLIDE_MS else SHIFT_MS
            when {
                // the bubble is up: the same body travels to the new place and swaps its words on the way
                shown != null && from != null -> { outgoing = shown; glideBody(from, now, ms) }
                // just taken down for a hand-off (limbo): the next bubble starts from there
                from != null && now - drawnBodyAt < LIMBO_MS -> { outgoing = null; glideBody(from, now, GLIDE_MS) }
                else -> { outgoing = null; bodyFrom = null; enteredAt = now }
            }
            shown = want
            android.util.Log.i("ToolusGuide", "BUBBLE '${want.headline}' from=${if (outgoing != null) "swap" else if (bodyFrom != null) "limbo" else "fresh"}")
            bodyStage = instrStage
        } else if (prevKey != placedKey && bodyFrom == null && drawnBody != null && motion) {
            // same words, new side of the ring (or the ring just appeared): move there instead of jumping
            glideBody(drawnBody!!, now, SHIFT_MS)
        }
        // body: gliding from its last place, or where it wants to be
        var p = 1f
        val from = bodyFrom
        val body = if (motion && from != null) {
            p = ((now - bodyGlideAt) / bodyGlideMs).coerceIn(0f, 1f)
            if (p >= 1f) { bodyFrom = null; outgoing = null; dest } else lerp(from, dest, glideCurve.getInterpolation(p))
        } else { bodyFrom = null; outgoing = null; dest }
        val e = glideCurve.getInterpolation(p)
        // a fresh bubble fades in and settles 6 dp up over 180 ms; an afterglow fades out at its ceiling
        val enter = if (motion) glideCurve.getInterpolation(((now - enteredAt) / FADE_MS).coerceIn(0f, 1f)) else 1f
        val leave = if (want.afterglow) 1f - ((now - doneAt - AFTERGLOW_MS) / FADE_MS).coerceIn(0f, 1f) else 1f
        val a = enter * leave
        body.offset(0f, (1f - enter) * 6f * d)
        bubbleRect = body
        drawnBody = RectF(body)
        drawnBodyAt = now
        val border = if (bodyFrom != null) blend(borderFrom, want.border, e) else want.border
        drawnBorder = border

        val radius = 20f * d
        val path = Path().apply { addRoundRect(body, radius, radius, Path.Direction.CW) }
        if (anchor != null && ringShown) {
            // the tail points at the ring from whichever side the body is on (none while it crosses over)
            val tw = TAIL_W * d
            val th = TAIL_H * d
            val tx = anchor.centerX().coerceIn(body.left + radius + tw / 2, body.right - radius - tw / 2)
            val tail = Path()
            if (body.bottom <= anchor.top - 4f * d) {
                tail.moveTo(tx - tw / 2, body.bottom - 1f); tail.lineTo(tx, body.bottom + th); tail.lineTo(tx + tw / 2, body.bottom - 1f)
            } else if (body.top >= anchor.bottom + 4f * d) {
                tail.moveTo(tx - tw / 2, body.top + 1f); tail.lineTo(tx, body.top - th); tail.lineTo(tx + tw / 2, body.top + 1f)
            }
            tail.close()
            path.op(tail, Path.Op.UNION)
        }
        if (want.afterglow) {
            // the IDE afterglow settle: a mint halo swells to 10 dp and settles at 3 dp over 900 ms
            val q = if (motion) ((now - doneAt) / SETTLE_GLOW_MS).coerceIn(0f, 1f) else 1f
            val (spread, ha) = if (q < 0.55f) {
                val k = easeOut.getInterpolation(q / 0.55f); lerp(0f, 10f, k) to lerp(0.55f, 0.09f, k)
            } else {
                val k = easeOut.getInterpolation((q - 0.55f) / 0.45f); lerp(10f, 3f, k) to lerp(0.09f, 0.16f, k)
            }
            halo.strokeWidth = 2 * spread * d
            halo.alpha = (255 * ha * a).toInt()
            if (spread > 0f) canvas.drawPath(path, halo)
        }
        bubbleFill.alpha = (245 * a).toInt()
        bubbleFill.setShadowLayer(14f * d, 0f, 4f * d, Color.argb((110 * a).toInt(), 2, 6, 23))
        bubbleBorder.color = border
        bubbleBorder.alpha = (Color.alpha(border) * a).toInt()
        canvas.drawPath(path, bubbleFill)
        bubbleFill.clearShadowLayer()
        canvas.drawPath(path, bubbleBorder)
        if (want.waiting && motion && bodyFrom == null) {
            // waiting: a warm arc runs round the border once every 1.2 s
            traceMatrix.setRotate(((now - started) % TRACE_MS) * 360f / TRACE_MS)
            traceMatrix.postTranslate(body.centerX(), body.centerY())
            trace.setLocalMatrix(traceMatrix)
            tracePaint.shader = trace
            tracePaint.alpha = (255 * a).toInt()
            tracePaint.setShadowLayer(8f * d, 0f, 0f, Color.argb((148 * a).toInt(), 234, 179, 8))
            canvas.drawPath(path, tracePaint)
        }
        // words: the old ones leave in the first third of a glide, the new ones arrive over the rest
        canvas.save()
        canvas.clipRect(body)
        val out = outgoing
        if (out != null && bodyFrom != null) out.draw(canvas, body, (1f - p / 0.25f).coerceIn(0f, 1f) * a, now, t)
        val inA = if (bodyFrom != null && out != null) ((p - 0.3f) / 0.5f).coerceIn(0f, 1f) else if (bodyFrom != null) (p / 0.5f).coerceIn(0f, 1f) else 1f
        want.draw(canvas, body, inA * a, now, t)
        canvas.restore()
        // the copy button takes taps only where it rests
        publishCopy(want.copyLocal?.takeIf { want.typeText != null && bodyFrom == null && inA * a > 0.95f }
            ?.let { RectF(it).apply { offset(body.left, body.top); inset(-4f * d, 0f) } })
    }

    /** TGP edge placement: 44 box, 12 margin, axis clamped toward the anchor's centre. */
    private fun drawChevron(canvas: Canvas, dir: String, t: Double) {
        val size = 44f * d
        val margin = 12f * d
        // Viewport = the scroll container (or the screen minus the system bars).
        val vp = viewport ?: RectF(0f, 48f * d, width.toFloat(), height.toFloat() - 56f * d)
        val axis = axisFrom ?: vp
        fun clamp(v: Float, lo: Float, hi: Float) = if (hi < lo) lo else v.coerceIn(lo, hi)
        val (left, top) = when (dir) {
            "up" -> clamp(axis.centerX() - size / 2, vp.left + margin, vp.right - size - margin) to vp.top + margin
            "left" -> vp.left + margin to clamp(axis.centerY() - size / 2, vp.top + margin, vp.bottom - size - margin)
            "right" -> (vp.right - size - margin) to clamp(axis.centerY() - size / 2, vp.top + margin, vp.bottom - size - margin)
            else -> clamp(axis.centerX() - size / 2, vp.left + margin, vp.right - size - margin) to (vp.bottom - size - margin)
        }
        val cx = left + size / 2
        val cy = top + size / 2
        val arm = 24f * d * 0.62f
        // Two stacked V shapes, second one delayed by 0.16s (double pulse).
        for ((k, delay) in listOf(0 to 0.0, 1 to 0.16)) {
            val phase = ((t - delay) % 1.15 + 1.15) % 1.15 / 1.15
            val alpha = if (motion) (0.42 + 0.58 * sin(phase * PI)).toFloat() else 1f
            val offset = (if (k == 0) -0.30f else 0.22f) * 24f * d
            val p = Path()
            when (dir) {
                "up" -> { val y = cy - offset; p.moveTo(cx - arm, y + arm / 2); p.lineTo(cx, y - arm / 2); p.lineTo(cx + arm, y + arm / 2) }
                "left" -> { val x = cx - offset; p.moveTo(x + arm / 2, cy - arm); p.lineTo(x - arm / 2, cy); p.lineTo(x + arm / 2, cy + arm) }
                "right" -> { val x = cx + offset; p.moveTo(x - arm / 2, cy - arm); p.lineTo(x + arm / 2, cy); p.lineTo(x - arm / 2, cy + arm) }
                else -> { val y = cy + offset; p.moveTo(cx - arm, y - arm / 2); p.lineTo(cx, y + arm / 2); p.lineTo(cx + arm, y - arm / 2) }
            }
            chevronGlow.alpha = (90 * alpha).toInt()
            chevronGlow.setShadowLayer(8f * d, 0f, 0f, Color.argb((184 * alpha).toInt(), 56, 189, 248))
            chevronPaint.alpha = (255 * alpha).toInt()
            canvas.drawPath(p, chevronGlow)
            canvas.drawPath(p, chevronPaint)
        }
    }

    /** Korean breaks between phrases, not inside a word (API 33+). */
    private fun StaticLayout.Builder.phrases(): StaticLayout.Builder = apply {
        if (android.os.Build.VERSION.SDK_INT >= 33) {
            setLineBreakConfig(android.graphics.text.LineBreakConfig.Builder()
                .setLineBreakWordStyle(android.graphics.text.LineBreakConfig.LINE_BREAK_WORD_STYLE_PHRASE).build())
        }
    }

    companion object {
        /** TGP glide curve, cubic-bezier(0.2, 0.8, 0.2, 1), lengthened from the web's 0.26s so older eyes can follow it. */
        const val GLIDE_MS = 420f
        const val RIPPLE_S = 1.6
        const val DONE_WAVE_MS = 450f
        /** In-stage moves of the bubble (a new side of the ring, the afterglow swap): the IDE card's 240 ms. */
        const val SHIFT_MS = 240f
        /** A bubble taken down this recently is where the next one glides from (the IDE hand-off limbo). */
        const val LIMBO_MS = 400L
        /** A ringed target missing for less than this is a redraw, not a change of screen (IDE target-loss grace). */
        const val TARGET_LOSS_GRACE_MS = 800L
        /** Ceiling of the afterglow bubble when no next step comes (IDE afterglow). */
        const val AFTERGLOW_MS = 8000L
        const val FADE_MS = 180f
        const val DONE_RING_HOLD_MS = 900L
        const val SETTLE_GLOW_MS = 900f
        const val POP_MS = 500f
        const val TRACE_MS = 1200L
        const val GHOST_MS = 600f
        /** A screen change after a touch shorter than this does not swap the bubble to "checking". */
        const val CHECK_DELAY_MS = 300L
        // bubble geometry, dp
        const val PAD_X = 20f
        const val PAD_Y = 16f
        const val GAP = 10f
        const val TAIL_H = 12f
        const val TAIL_W = 26f
        /** Width of the copy icon's place inside the text box, dp (its touch area reaches 48 dp). */
        const val COPY_AREA = 40f
        /** How long the copy button says "복사됨". */
        const val COPIED_MS = 2000L
    }
}
