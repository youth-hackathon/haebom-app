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

    /** [quiet]: a plain text link under the buttons (e.g. "안내 그만하기"), not a full button. */
    data class Chip(val label: String, val primary: Boolean = false, val quiet: Boolean = false, val onClick: () -> Unit) {
        constructor(label: String, onClick: () -> Unit) : this(label, false, false, onClick)
        constructor(label: String, primary: Boolean, onClick: () -> Unit) : this(label, primary, false, onClick)
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
    /** Separate labelled pills, not one segmented bar: each is an action of its own (Figma "오버레이 개선"). */
    private fun pillButton(label: String, iconRes: Int, strong: Boolean) = TextView(service).apply {
        text = label
        val tint = Color.parseColor(if (strong) Palette.BRAND_TEXT else Palette.INK)
        setCompoundDrawablesRelativeWithIntrinsicBounds(service.getDrawable(iconRes)!!.mutate().apply {
            setTint(tint); setBounds(0, 0, dpi(22f), dpi(22f)) }, null, null, null)
        compoundDrawablePadding = dpi(6f)
        // capped so the two pills stay one line each even at the phone's largest text size
        setTextSize(android.util.TypedValue.COMPLEX_UNIT_DIP, 17f * min(service.resources.configuration.fontScale, 1.3f))
        maxLines = 1
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        setTextColor(Color.parseColor(if (strong) Palette.BRAND_TEXT else Palette.INK))
        gravity = Gravity.CENTER
        minHeight = dpi(48f)
        setPadding(dpi(16f), dpi(10f), dpi(18f), dpi(10f))
        background = GradientDrawable().apply {
            setColor(Color.WHITE)
            cornerRadius = dp(26f)
            setStroke(dpi(2f), Color.parseColor(if (strong) Palette.BRAND else Palette.EDGE))
        }
        elevation = dp(8f)
        isClickable = true
    }
    private val doneButton = pillButton("다 했어요", R.drawable.ic_check, true)
    /** The one button over other apps: "도움이 필요해요" opens the help card (see again / ask the instructor / later). */
    private val endButton = pillButton("도움이 필요해요", R.drawable.ic_help, true)
    /** Set by the session; when null the button just ends the guide. */
    var onHelp: (() -> Unit)? = null
    /** The guide layout the controls were last placed for; a different one re-places them. */
    private var controlsOnTop = ""

    init {
        controls.orientation = LinearLayout.HORIZONTAL
        controls.gravity = Gravity.CENTER_VERTICAL
        controls.setPadding(dpi(6f), dpi(6f), dpi(6f), dpi(10f)) // room for the pills' shadows
        controls.clipToPadding = false
        controls.addView(doneButton, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        controls.addView(endButton, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { marginStart = dpi(12f) })
        doneButton.contentDescription = "다 했어요. 지금 화면을 다시 확인합니다"
        endButton.contentDescription = "도움이 필요해요. 다시 보기, 강사님께 물어보기, 나중에 이어서 하기"
    }

    /** 해보미's face as a round 44 dp drawable for the card title (Figma "Choice Card"). */
    private fun roundFace(res: Int): android.graphics.drawable.Drawable {
        val px = dpi(44f)
        val src = android.graphics.BitmapFactory.decodeResource(service.resources, res)
        val bmp = android.graphics.Bitmap.createBitmap(px, px, android.graphics.Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        c.clipPath(Path().apply { addCircle(px / 2f, px / 2f, px / 2f, Path.Direction.CW) })
        c.drawBitmap(src, null, RectF(0f, 0f, px.toFloat(), px.toFloat()), Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG))
        return android.graphics.drawable.BitmapDrawable(service.resources, bmp).apply { setBounds(0, 0, px, px) }
    }

    /** Show the two runtime buttons; [onDone] null hides ✓ (nothing to confirm while the next step is being prepared). */
    fun showControls(onDone: (() -> Unit)?, onEnd: () -> Unit) {
        // "다 했어요" is gone: the guide notices the press itself, and "도움이 필요해요" covers being stuck
        doneButton.visibility = View.GONE
        endButton.setOnClickListener { onHelp?.invoke() ?: onEnd() }
        controls.visibility = View.VISIBLE
        placeControls()
    }

    fun hideControls() {
        if (controlsAdded) controls.visibility = View.INVISIBLE
    }

    /**
     * The first of top-right (below the status bar), bottom-right (above the navigation bar) and mid-right that
     * keeps clear of the ring, the bubble and the keyboard; top-right when none does.
     */
    private fun placeControls() {
        val (sw, sh) = screen()
        controls.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED)
        val cw = controls.measuredWidth
        val ch = controls.measuredHeight
        val x = sw - cw - dpi(10f)
        val spots = listOf(dpi(40f), sh - ch - dpi(64f), (sh - ch) / 2)
        val y = spots.firstOrNull { y -> !canvasView.overlaps(RectF(x.toFloat(), y.toFloat(), (x + cw).toFloat(), (y + ch).toFloat())) } ?: spots[0]
        controlsOnTop = canvasView.layoutKey()
        // anchored to the right edge, so the pills never run off screen even if their measured width is short
        controlParams.gravity = Gravity.TOP or Gravity.END
        controlParams.x = dpi(10f)
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
        canvasView.onReplayRect = { r -> placeReplay(r) }
    }

    /** Where this step sits in its lesson (1-based step to total), or null; the bubble shows "2 / 5 단계". */
    fun setProgress(p: Pair<Int, Int>?) {
        canvasView.progress = p
    }

    /**
     * Review mode (복습): the person has done this lesson once. Each step shows only the words at first; the ring
     * and the swipe arrow appear as a hint after [GuideCanvas.REVIEW_HINT_MS], or at once on "이 단계 다시 보기".
     */
    var review: Boolean
        get() = canvasView.review
        set(v) { canvasView.review = v; canvasView.invalidate() }

    /** Show the ring now (the help card's "이 단계 다시 보기"). */
    fun revealHint() { canvasView.hintNow = true; canvasView.invalidate() }

    // ------------------------------------------------------------ replay: read the bubble out loud

    private val replayTouch = View(service).apply {
        contentDescription = "안내 다시 듣기"
        setOnClickListener { replayNow() }
    }
    private var replayAdded = false
    private val replayParams = overlayParams(1, 1, true, "ToolusGuideReplay")
    /** Set by the session: reads the sentence out (the TTS server first, the phone's own voice when it does not answer). */
    var onReplay: ((String) -> Unit)? = null

    private fun placeReplay(r: RectF?) {
        if (r == null) {
            if (replayAdded) { try { wm.removeView(replayTouch) } catch (_: Exception) {}; replayAdded = false }
            return
        }
        replayParams.x = r.left.toInt()
        replayParams.y = r.top.toInt()
        replayParams.width = r.width().toInt()
        replayParams.height = r.height().toInt()
        if (replayAdded) wm.updateViewLayout(replayTouch, replayParams) else { wm.addView(replayTouch, replayParams); replayAdded = true }
    }

    /** Read the step on screen out again (the help card's "이 단계 다시 보기"). */
    fun replayStep() = replayNow()

    private fun replayNow() {
        val text = canvasView.speakable() ?: return
        canvasView.replayed()
        android.util.Log.i("ToolusGuide", "REPLAY chars=${text.length}")
        onReplay?.invoke(text)
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
        placeReplay(null)
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
            setColor(Color.WHITE)
            cornerRadius = dp(26f)
            setStroke(dpi(2f), Color.parseColor(Palette.BRAND))
        }
        card.elevation = dp(12f)
        titleView.setTextColor(Color.parseColor(Palette.INK))
        titleView.textSize = 22f
        titleView.typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        titleView.includeFontPadding = false; titleView.isFallbackLineSpacing = false; titleView.setLineHeight(android.util.TypedValue.COMPLEX_UNIT_SP, 22f * 1.4f)
        bodyView.setTextColor(Color.parseColor(Palette.INK_SUB))
        bodyView.textSize = 17f
        bodyView.includeFontPadding = false; bodyView.isFallbackLineSpacing = false; bodyView.setLineHeight(android.util.TypedValue.COMPLEX_UNIT_SP, 17f * 1.6f)
        bodyView.setPadding(0, dpi(8f), 0, 0)
        chipCol.orientation = LinearLayout.VERTICAL
        if (android.os.Build.VERSION.SDK_INT >= 33) {
            val phrase = android.graphics.text.LineBreakConfig.Builder()
                .setLineBreakWordStyle(android.graphics.text.LineBreakConfig.LINE_BREAK_WORD_STYLE_PHRASE).build()
            titleView.lineBreakWordStyle = phrase.lineBreakWordStyle
            bodyView.lineBreakWordStyle = phrase.lineBreakWordStyle
        }
        titleView.gravity = Gravity.CENTER_VERTICAL
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
        (card.background as GradientDrawable).setStroke(dpi(if (done) 3f else 2f), Color.parseColor(Palette.BRAND))
        val faceRes = when { done -> R.drawable.hb_face_happy; title.contains("못") -> R.drawable.hb_face_sad; title.contains("막히") -> R.drawable.hb_face_focus; else -> R.drawable.hb_face_neutral }
        titleView.setCompoundDrawablesRelativeWithIntrinsicBounds(roundFace(faceRes), null, null, null)
        titleView.compoundDrawablePadding = dpi(10f)
        titleView.text = title
        bodyView.text = why
        bodyView.visibility = if (why.isBlank()) View.GONE else View.VISIBLE
        chipCol.removeAllViews()
        chips.forEach { c ->
            if (c.quiet) {
                chipCol.addView(TextView(service).apply {
                    text = c.label; textSize = 18f; gravity = Gravity.CENTER
                    setTextColor(Color.parseColor(Palette.INK_SUB)); paintFlags = paintFlags or android.graphics.Paint.UNDERLINE_TEXT_FLAG
                    minHeight = dpi(48f); setOnClickListener { c.onClick() }
                }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dpi(48f)).apply { topMargin = dpi(8f) })
                return@forEach
            }
            val b = Button(service).apply {
                text = c.label
                isAllCaps = false
                textSize = 20f
                typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
                setTextColor(if (c.primary) Color.WHITE else Color.parseColor(Palette.BRAND_TEXT))
                minHeight = dpi(56f)
                stateListAnimator = null
                background = GradientDrawable().apply {
                    setColor(if (c.primary) Color.parseColor(Palette.BRAND) else Color.WHITE)
                    cornerRadius = dp(18f)
                    if (!c.primary) setStroke(dpi(2f), Color.parseColor(Palette.BRAND))
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
        placeReplay(null)
        if (cardAdded) { try { wm.removeView(card) } catch (_: Exception) {}; cardAdded = false }
        if (controlsAdded) { try { wm.removeView(controls) } catch (_: Exception) {}; controlsAdded = false }
        if (canvasAdded) { try { wm.removeView(canvasView) } catch (_: Exception) {}; canvasAdded = false }
    }
}

/** The filled orange check in front of a finished card's title. */
private class CheckBadge(context: Context) : android.graphics.drawable.Drawable() {
    private val d = context.resources.displayMetrics.density
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor(Palette.BRAND) }
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 3.2f * d; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
        color = Color.WHITE
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
    private val sp = context.resources.displayMetrics.scaledDensity
    // orange palette (Palette.kt; every pair is checked by PaletteContrastTest)
    private val accent = Color.parseColor(Palette.RING)
    private val mint = Color.parseColor(Palette.BRAND) // "done": a filled deep orange with a white check (shape + words, not hue alone)
    private val slate = Color.parseColor(Palette.SURFACE)
    private val ink = Color.parseColor(Palette.INK)
    private val peach = Color.parseColor(Palette.PEACH)
    /** The ring's outer layers: a white halo and a thin dark outline, so it stands out on any app's screen. */
    private val ringHalo = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; color = Color.parseColor(Palette.RING_HALO) }
    private val ringOutline = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; color = Color.parseColor(Palette.RING_OUTLINE) }
    private val ringStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = RING_CORE * d; color = accent }
    private val ringFill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL; color = Color.argb(22, 255, 107, 44) }
    // glows are shadow layers (hardware-drawn for shapes since API 28); a mask filter would force a software layer
    private val ringGlow = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 8f * d; color = Color.argb(117, 255, 107, 44) }
    private val ripple = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 3f * d; color = accent }
    private val chevronPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 5f * d; color = accent
        strokeCap = Paint.Cap.SQUARE; strokeJoin = Paint.Join.MITER
    }
    private val chevronGlow = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 7f * d; color = Color.argb(184, 255, 107, 44) }
    private val bubbleFill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL; color = slate }
    private val bubbleBorder = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 3f * d; color = accent }
    private val headPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ink; textSize = 24f * sp
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
    }
    private val chipText = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ink; textSize = 19f * sp
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
    }
    private val subText = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor(Palette.INK_SUB); textSize = 17f * sp
    }
    /** The progress chip ("2 / 5 단계") and the replay pill (speaker icon + "다시 듣기") in the bubble's top row. */
    private val pillText = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor(Palette.BRAND_TEXT); textSize = 16f * d * min(context.resources.configuration.fontScale, PILL_SCALE_MAX); typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
    }
    private val pillFill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL; color = peach }
    /** "4 / 7 단계": plain grey text beside the dots, not a pill (only the replay pill is tappable). */
    private val stepText = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor(Palette.INK_SUB); textSize = 16f * d * min(context.resources.configuration.fontScale, PILL_SCALE_MAX)
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
    }
    private val copyIcon = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 2f * d; strokeJoin = Paint.Join.ROUND; strokeCap = Paint.Cap.ROUND
    }
    private val chipFill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL; color = Color.parseColor("#FFF4EC") }
    private val chipBorder = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 1.5f * d; color = Color.parseColor("#FFC9AE") }
    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL; color = accent }
    private val started = System.currentTimeMillis()
    private val warm = Color.parseColor("#FFB020")
    private val halo = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; color = mint }
    private val tracePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 2.5f * d }
    private val checkFill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL; color = mint }
    private val checkStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 3.2f * d; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
        color = Color.WHITE
    }
    /** The waiting border trace: a warm arc running round the bubble (the IDE card's conic trace, 0..36%). */
    private val trace = android.graphics.SweepGradient(0f, 0f,
        intArrayOf(Color.argb(0, 255, 176, 32), Color.argb(66, 255, 176, 32), Color.argb(245, 255, 176, 32), Color.argb(148, 255, 176, 32),
            Color.argb(0, 255, 176, 32), Color.argb(0, 255, 176, 32)),
        floatArrayOf(0f, 0.09f, 0.18f, 0.27f, 0.36f, 1f))

    /** Where this step sits in its lesson (1-based step, total); null outside a lesson. Shown as "2 / 5 단계" + dots. */
    var progress: Pair<Int, Int>? = null
    var review = false
    var hintNow = false
    private var stageAt = 0L
    private var shownStageForHint = -1
    /** Where the replay pill is on screen once its bubble has settled (null: none or moving); see [GuideOverlay.placeReplay]. */
    var onReplayRect: ((RectF?) -> Unit)? = null
    private var replayRectSent: RectF? = null
    private var replayedAt = 0L

    /** The words a replay reads out: the bubble on screen now. */
    fun speakable(): String? = shown?.takeIf { !it.afterglow }?.let { b -> listOfNotNull(b.headline, b.typeText, b.sub).joinToString(". ") }

    fun replayed() { replayedAt = System.currentTimeMillis(); invalidate() }

    private fun publishReplay(r: RectF?) {
        val sent = replayRectSent
        if (r == null && sent == null) return
        if (r != null && sent != null && Math.abs(r.left - sent.left) < 2f && Math.abs(r.top - sent.top) < 2f) return
        replayRectSent = r?.let { RectF(it) }
        post { onReplayRect?.invoke(replayRectSent) }
    }
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
        if (stage != shownStageForHint) { stageAt = System.currentTimeMillis(); hintNow = false; shownStageForHint = stage }
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
        // review: the person tries on their own first; the ring (and swipe arrow) come back as a hint
        val hiding = review && !hintNow && !done && !checking && now - stageAt < REVIEW_HINT_MS &&
            instr?.kind.let { it != GuideOverlay.Instruction.Kind.STATUS && it != GuideOverlay.Instruction.Kind.WAIT && it != null }
        val r = if (checking || (done && !doneRing) || hiding) null else drawnRing(now)
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
        if (!hiding) chevronDirection?.let { dir -> drawChevron(canvas, dir, t) }
        val lost = !hiding && r == null && chevronDirection == null && stageHadRing && !checking && instr?.kind != GuideOverlay.Instruction.Kind.WAIT
        val i = instr
        val want: BubbleText? = when {
            done -> if (now - doneAt < AFTERGLOW_MS + FADE_MS) text("잘하셨어요", null, null, null, afterglow = true) else null
            checking && i != null -> if (now - checkingAt >= CHECK_DELAY_MS) text("확인하고 있어요", null, null, GuideOverlay.Instruction.Kind.STATUS)
                else text(i.headline, i.typeText, i.sub ?: pasteHint(i), i.kind)
            i == null || lost -> null
            hiding -> text(i.headline, i.typeText, "스스로 찾아 눌러 보세요. 조금 뒤에 힌트를 드려요", i.kind)
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
        if (hiding || (r != null && ringVis > 0f) || chevronDirection != null || instr != null || shown != null || ghost != null || checking) postInvalidateOnAnimation()
    }

    private fun drawRing(canvas: Canvas, r: RectF, t: Double, now: Long, vis: Float) {
        val pad = 1f * d  // hug the target
        // keep the whole ring (with its thin outer layers) on screen: a full-width row must not spill past the edges
        val edge = (RING_CORE / 2 + 6f) * d  // a little glow stays visible even on full-width rows
        val rr = RectF(
            max(r.left - pad, edge), max(r.top - pad, edge),
            min(r.right + pad, width - edge), min(r.bottom + pad, height - edge))
        val radius = 12f * d
        val color = if (done) mint else Color.parseColor(Palette.RING_LINE)
        val cr = Color.red(color); val cg = Color.green(color); val cb = Color.blue(color)
        ringStroke.color = color
        val pulse = if (motion) (0.6 + 0.4 * sin(t * 2 * PI / 1.6)).toFloat() else 1f
        val fadeIn = if ((lockOn || ringFadeIn) && motion && glideStart != 0L) (0.25f + 0.75f * ((now - glideStart) / GLIDE_MS).coerceIn(0f, 1f)) else 1f
        val a = fadeIn * vis
        // the target itself stays clean (nothing painted over the button); the light lives on the edge:
        // a white inner line, the orange line, and an orange glow that spreads outward and fades
        val path = Path().apply { addRoundRect(rr, radius, radius, Path.Direction.CW) }
        canvas.save()
        canvas.clipOutPath(path)
        // a smooth outward glow: thin rings 1 dp apart whose opacity eases to nothing over GLOW dp
        ringGlow.clearShadowLayer()
        ringGlow.strokeWidth = 1.4f * d
        val n = GLOW_DP.toInt()
        for (k in 0 until n) {
            val q = k / GLOW_DP
            val off = (RING_CORE / 2 + k + 0.5f) * d
            ringGlow.color = Color.argb((95 * (1f - q) * (1f - q) * (0.75f + 0.25f * pulse) * a).toInt().coerceIn(0, 255), cr, cg, cb)
            canvas.drawRoundRect(RectF(rr.left - off, rr.top - off, rr.right + off, rr.bottom + off), radius + off, radius + off, ringGlow)
        }
        canvas.restore()
        // breathing ripple: a thin outline leaves the ring and fades, once every 1.6 s
        if (motion && !done) {
            val phase = ((t % RIPPLE_S) / RIPPLE_S).toFloat()
            val grow = 14f * d * phase
            ripple.strokeWidth = 1.5f * d
            ripple.alpha = (110 * (1f - phase) * (1f - phase) * a).toInt()
            canvas.drawRoundRect(RectF(rr.left - grow, rr.top - grow, rr.right + grow, rr.bottom + grow), radius + grow, radius + grow, ripple)
        }
        ringStroke.alpha = (255 * a).toInt()
        canvas.drawRoundRect(rr, radius, radius, ringStroke)
        // white hairline just inside the orange line: keeps the edge crisp on orange or busy screens
        val inIn = (RING_CORE / 2 + RING_HALO / 2) * d
        ringHalo.strokeWidth = RING_HALO * d
        ringHalo.alpha = (235 * a).toInt()
        canvas.drawRoundRect(RectF(rr.left + inIn, rr.top + inIn, rr.right - inIn, rr.bottom - inIn), max(0f, radius - inIn), max(0f, radius - inIn), ringHalo)
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

    /** 해보미's profile faces (Figma "디자인 시스템 · 앱 UI", images 44-47): neutral while guiding, happy when a step is
     *  done, focused while checking the next screen. Clipped to a circle when drawn. */
    private fun face(res: Int) = android.graphics.BitmapFactory.decodeResource(resources, res)
    private val facePoint by lazy { face(R.drawable.hb_face_neutral) }
    private val faceThumb by lazy { face(R.drawable.hb_face_happy) }
    private val faceCurious by lazy { face(R.drawable.hb_face_focus) }
    /** Material "volume_up" (filled) for the replay pill, tinted like its words. */
    private val speaker by lazy { context.getDrawable(R.drawable.ic_volume_up)!!.mutate().apply { setTint(Color.parseColor(Palette.BRAND_TEXT)) } }
    private val faceBg = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL; color = Color.parseColor(Palette.TONAL) }
    private val facePaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)

    /** 해보미's round face at the bubble's top-left; the done face pops in like the old check did. */
    private fun drawFace(canvas: Canvas, bmp: android.graphics.Bitmap, a: Float, pop: Pair<Float, Float>) {
        val (scale, pa) = pop
        val size = AV_SIZE * d
        val r = size / 2 * scale
        val c = size / 2
        faceBg.alpha = (255 * a * pa).toInt(); facePaint.alpha = (255 * a * pa).toInt()
        canvas.drawCircle(c, c, r, faceBg)
        canvas.save()
        canvas.clipPath(Path().apply { addCircle(c, c, r, Path.Direction.CW) })
        canvas.drawBitmap(bmp, null, RectF(c - r, c - r, c + r, c + r), facePaint)
        canvas.restore()
    }

    /** What one bubble says, laid out once for the width it is drawn at. */
    private inner class BubbleText(val headline: String, val typeText: String?, val sub: String?,
                                   val kind: GuideOverlay.Instruction.Kind?, val afterglow: Boolean) {
        val waiting = kind == GuideOverlay.Instruction.Kind.STATUS || kind == GuideOverlay.Instruction.Kind.WAIT
        /** A guide bubble carries a top row: where we are ("2 / 5 단계" + dots) on the left, speaker + "다시 듣기" on the right. */
        val topRow = !afterglow && !waiting
        val prog = if (topRow) progress else null
        val key = listOf(headline, typeText, sub, kind, afterglow, prog).joinToString("\u0001")
        val border = when { afterglow -> mint; waiting -> Color.argb(220, 255, 176, 32); else -> accent }
        private val progLabel = prog?.let { (k, n) -> if (review) "복습 $k / $n" else "$k / $n 단계" }
        private val replayLabel = "다시 듣기"
        /** The step is plain text (no pill), so only "다시 듣기" looks like a button. */
        private val progW get() = progLabel?.let { stepText.measureText(it) } ?: 0f
        /** Up to [MAX_DOTS] steps show one dot each; a longer lesson shows a short bar instead. */
        private val dotsW get() = prog?.let { (_, n) -> (if (n <= MAX_DOTS) n * 13f * d else BAR_W * d) + 10f * d } ?: 0f
        private val replayW get() = pillText.measureText(replayLabel) + (22f + REPLAY_ICON + 4f) * d
        private val topH get() = if (topRow) TOP_H * d + TOP_GAP * d else 0f
        /** The replay pill's place inside the body (body-relative), set when drawn. */
        var replayLocal: RectF? = null
        /** 해보미's face sits in front of every bubble (pointing, thumbs-up when done, curious while checking). */
        val lead = (AV_SIZE + AV_GAP) * d
        val face get() = when { afterglow -> faceThumb; waiting -> faceCurious; else -> facePoint }
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
        /** Height of the words (top row + headline + chip + sub line), and how far they are pushed down to centre. */
        var textH = 0f
        var textY = 0f

        fun layout(maxW: Float) {
            if (maxW == builtFor) return
            builtFor = maxW
            val textW = (maxW - 2 * PAD_X * d - lead - (if (waiting) 44f * d else 0f)).toInt()
            head = StaticLayout.Builder.obtain(headline, 0, headline.length, headPaint, textW)
                .setAlignment(Layout.Alignment.ALIGN_NORMAL).setLineSpacing(0f, lineMult(headPaint, 1.4f)).setIncludePad(false).setUseLineSpacingFromFallbacks(false).phrases().build()
            chip = typeText?.let { s -> StaticLayout.Builder.obtain(s, 0, s.length, chipText, textW - (16f * d + copyW).toInt()).setLineSpacing(0f, lineMult(chipText, 1.4f)).setIncludePad(false).setUseLineSpacingFromFallbacks(false).phrases().build() }
            subLayout = sub?.let { s -> StaticLayout.Builder.obtain(s, 0, s.length, subText, textW).setLineSpacing(0f, lineMult(subText, 1.6f)).setIncludePad(false).setUseLineSpacingFromFallbacks(false).phrases().build() }
            headW = (0 until head.lineCount).maxOf { head.getLineWidth(it) }
            chipW = chip?.let { c -> (0 until c.lineCount).maxOf { c.getLineWidth(it) } + 16f * d + copyW } ?: 0f
            val subW = subLayout?.let { c -> (0 until c.lineCount).maxOf { c.getLineWidth(it) } } ?: 0f
            val topW = if (topRow) progW + 12f * d + replayW else 0f
            val contentW = lead + maxOf(headW + (if (waiting) 44f * d else 0f), chipW, subW, topW)
            w = min(maxW, max(contentW + 2 * PAD_X * d, 160f * d))
            rowH = chip?.let { max(it.height + 20f * d, 44f * d) } ?: 0f
            textH = topH + head.height.toFloat() + (if (chip != null) 12f * d + rowH else 0f) + (subLayout?.let { 8f * d + it.height } ?: 0f)
            h = max(PAD_Y * d * 2 + textH, PAD_Y * d * 2 + AV_SIZE * d)
        }

        fun draw(canvas: Canvas, body: RectF, a: Float, now: Long, t: Double) {
            if (a <= 0f) return
            canvas.save()
            canvas.translate(body.left + PAD_X * d, body.top + PAD_Y * d)
            // the face sits centred against the words beside it
            val faceY = max(0f, (body.height() - 2 * PAD_Y * d - AV_SIZE * d) / 2)
            canvas.save(); canvas.translate(0f, faceY)
            drawFace(canvas, face, a, if (afterglow) pop(now - doneAt) else 1f to 1f)
            canvas.restore()
            // the words sit centred in the box too (a one-line "잘하셨어요!" is not pinned to the top)
            textY = max(0f, (body.height() - 2 * PAD_Y * d - textH) / 2)
            canvas.translate(lead, textY)
            if (topRow) drawTopRow(canvas, body.width() - 2 * PAD_X * d - lead, a, now)
            canvas.translate(0f, topH)
            val headTop = 0f
            canvas.save()
            canvas.translate(0f, headTop)
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
                chipFill.alpha = (255 * a).toInt(); chipBorder.alpha = (255 * a).toInt()
                canvas.drawRoundRect(cr, 12f * d, 12f * d, chipFill)
                canvas.drawRoundRect(cr, 12f * d, 12f * d, chipBorder)
                val br = RectF(chipW - copyW, 0f, chipW, rowH)
                drawCopyIcon(canvas, br, a, now)
                copyLocal = RectF(br).apply { offset(PAD_X * d + lead, PAD_Y * d + textY + topH + top) }
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
            copyIcon.color = mint
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

        /** Top row: progress chip and dots on the left, the replay pill at the right end ([innerW] = body minus padding). */
        private fun drawTopRow(canvas: Canvas, innerW: Float, a: Float, now: Long) {
            val h = TOP_H * d
            val r = h / 2
            pillFill.alpha = (255 * a).toInt()
            pillText.alpha = (255 * a).toInt()
            val baseline = h / 2 - (pillText.descent() + pillText.ascent()) / 2
            var x = 0f
            if (progLabel != null && prog != null) {
                stepText.alpha = (255 * a).toInt()
                canvas.drawText(progLabel, 0f, baseline, stepText)
                x = progW + 10f * d
                val (k, n) = prog
                // with very large text the words win: drop the dots/bar rather than let the pills overlap
                if (progW + dotsW + 12f * d + replayW > innerW) Unit
                else if (n <= MAX_DOTS) for (i in 1..n) {
                    dotPaint.color = if (i <= k) accent else peach
                    dotPaint.alpha = (255 * a).toInt()
                    canvas.drawCircle(x + 4f * d + (i - 1) * 13f * d, h / 2, if (i == k) 5f * d else 4f * d, dotPaint)
                } else {
                    val bh = 8f * d
                    val track = RectF(x, h / 2 - bh / 2, x + BAR_W * d, h / 2 + bh / 2)
                    dotPaint.color = peach; dotPaint.alpha = (255 * a).toInt()
                    canvas.drawRoundRect(track, bh / 2, bh / 2, dotPaint)
                    dotPaint.color = accent; dotPaint.alpha = (255 * a).toInt()
                    canvas.drawRoundRect(RectF(track.left, track.top, track.left + track.width() * k / n, track.bottom), bh / 2, bh / 2, dotPaint)
                }
            }
            // replay: a pill of its own at the far end, apart from the progress (an action, not a toggle)
            val rl = innerW - replayW
            val pressed = now - replayedAt < 600L
            pillFill.color = if (pressed) Color.parseColor("#FFC9AE") else peach
            canvas.drawRoundRect(RectF(rl, 0f, innerW, h), r, r, pillFill)
            pillFill.color = peach
            val ic = REPLAY_ICON * d; val iy = (h - ic) / 2
            speaker.setBounds((rl + 10f * d).toInt(), iy.toInt(), (rl + 10f * d + ic).toInt(), (iy + ic).toInt())
            speaker.alpha = (255 * a).toInt(); speaker.draw(canvas)
            canvas.drawText(replayLabel, rl + 10f * d + ic + 4f * d, baseline, pillText)
            replayLocal = RectF(rl, 0f, innerW, h).apply { offset(PAD_X * d + lead, PAD_Y * d + textY) }
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
            publishReplay(null)
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
        bubbleFill.alpha = (250 * a).toInt()
        bubbleFill.setShadowLayer(16f * d, 0f, 6f * d, Color.argb((90 * a).toInt(), 40, 20, 10))
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
            tracePaint.setShadowLayer(8f * d, 0f, 0f, Color.argb((148 * a).toInt(), 255, 176, 32))
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
        // and so does the replay pill (taller touch area than it looks, for older fingers)
        publishReplay(want.replayLocal?.takeIf { want.topRow && bodyFrom == null && inA * a > 0.95f }
            ?.let { RectF(it).apply { offset(body.left, body.top); inset(-6f * d, -8f * d) } })
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
            chevronGlow.setShadowLayer(8f * d, 0f, 0f, Color.argb((184 * alpha).toInt(), 255, 107, 44))
            chevronPaint.alpha = (255 * alpha).toInt()
            canvas.drawPath(p, chevronGlow)
            val core = chevronPaint.strokeWidth
            ringOutline.strokeWidth = core + 2 * (RING_HALO + RING_OUTLINE) * d; ringOutline.alpha = chevronPaint.alpha
            ringHalo.strokeWidth = core + 2 * RING_HALO * d; ringHalo.alpha = chevronPaint.alpha
            ringOutline.strokeCap = Paint.Cap.SQUARE; ringHalo.strokeCap = Paint.Cap.SQUARE
            ringOutline.strokeJoin = Paint.Join.MITER; ringHalo.strokeJoin = Paint.Join.MITER
            canvas.drawPath(p, ringOutline)
            canvas.drawPath(p, ringHalo)
            canvas.drawPath(p, chevronPaint)
            ringOutline.strokeCap = Paint.Cap.BUTT; ringHalo.strokeCap = Paint.Cap.BUTT
            ringOutline.strokeJoin = Paint.Join.MITER; ringHalo.strokeJoin = Paint.Join.MITER
        }
    }

    /** The multiplier that makes a line [ratio] x the text size (the font's own line is taller than its size). */
    private fun lineMult(p: android.text.TextPaint, ratio: Float): Float {
        val fm = p.fontMetrics; return ratio * p.textSize / (fm.descent - fm.ascent)
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
        /** Ring in dp: orange line 3, white hairline 1.5 just inside it, orange glow spreading outside. */
        const val RING_CORE = 3f
        /** How far the ring's orange glow spreads outward (dp). */
        const val GLOW_DP = 14f
        /** 해보미's face in front of each bubble: size and the gap before the words (dp). */
        const val AV_SIZE = 46f
        /** The Material speaker icon in front of "다시 듣기" (dp). */
        const val REPLAY_ICON = 18f
        const val AV_GAP = 14f
        /** Small labels (step pill, replay, done/stop) grow with the phone's text size up to this much, then stop. */
        const val PILL_SCALE_MAX = 1.3f
        const val RING_HALO = 1.5f
        const val RING_OUTLINE = 1f
        const val RIPPLE_S = 1.6
        /** Review mode: how long the words stand alone before the ring comes back as a hint (ms). */
        const val REVIEW_HINT_MS = 8000L
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
        /** The bubble's top row (progress chip, replay pill) and the gap under it, dp. */
        const val TOP_H = 30f
        const val TOP_GAP = 10f
        /** More steps than this get a progress bar of [BAR_W] dp instead of dots. */
        const val MAX_DOTS = 6
        const val BAR_W = 56f
    }
}
