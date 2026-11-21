package ai.toolus.mobileguide

import android.app.Activity
import android.Manifest
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.provider.Settings
import android.speech.RecognizerIntent
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.util.concurrent.Executors

/**
 * 해봄 home, built for older eyes and hands (Figma "온보딩 · 메인화면" v4 plus the senior review):
 * light grey page (#F7F7F7), white cards with a hairline edge, "하던 것" on top when there is one, and four big
 * tiles: scan the class paper's QR, ask by voice or text, the things to learn ([Practice]), and help.
 * Secondary text never goes lighter than #4E5968; every touch target is 56 dp or more.
 *
 * Everything the person starts here is a request sentence for the guide server ([GuideHub.startTask]); a thing to
 * learn is a sentence the home screen already knows. What they say goes to the TTS server's live path
 * ([SpeechInput]) and comes back as text. 설정 shows where the servers are and how the voice is fetched.
 * The accessibility disclosure still comes before the settings hop (Play prominent-disclosure rule).
 */
class MainActivity : Activity() {
    private val io = Executors.newSingleThreadExecutor()
    private lateinit var page: FrameLayout
    private var screen = "home"
    private val prefs by lazy { getSharedPreferences("haebom", Context.MODE_PRIVATE) }

    // tokens (Palette.kt; every pair is checked by PaletteContrastTest)
    private val BG = Color.parseColor(Palette.BG)
    private val SURF = Color.WHITE
    private val LINE = Color.parseColor(Palette.HAIRLINE)
    private val EDGE = Color.parseColor(Palette.EDGE)
    private val T1 = Color.parseColor(Palette.T1)
    private val T2 = Color.parseColor(Palette.T2)
    private val PRI = Color.parseColor(Palette.BRAND)
    private val TONAL = Color.parseColor(Palette.TONAL)
    private val TONAL_TX = Color.parseColor(Palette.BRAND_TEXT)
    private val NEU = Color.parseColor(Palette.NEU)
    private val NEU_TX = Color.parseColor(Palette.NEU_TX)
    private val WARN_BG = Color.parseColor(Palette.WARN_BG)

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    private fun dpf(v: Float) = v * resources.displayMetrics.density

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        GuideSettings.init(this)
        window.statusBarColor = BG
        window.navigationBarColor = BG
        page = FrameLayout(this).apply { setBackgroundColor(BG) }
        page.setOnApplyWindowInsetsListener { v, insets ->
            val bars = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.ime() or WindowInsets.Type.displayCutout())
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            WindowInsets.CONSUMED
        }
        setContentView(page)
        // dark status/navigation icons on the light page (the decor view exists only after setContentView)
        val light = android.view.WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or android.view.WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS
        window.insetsController?.setSystemBarsAppearance(light, light)
        // targetSdk 36 delivers Back through the dispatcher (Android 13+), not onBackPressed: an inner page goes home first
        if (android.os.Build.VERSION.SDK_INT >= 33) {
            onBackInvokedDispatcher.registerOnBackInvokedCallback(android.window.OnBackInvokedDispatcher.PRIORITY_DEFAULT) { goBack() }
        }
        showHome()
    }

    private fun goBack() {
        if (screen != "home") showHome() else finish()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() = goBack()

    override fun onResume() {
        super.onResume()
        GuideHub.listener = { runOnUiThread { if (screen == "home") showHome() } }
        // the guide asked something ("해봄에서 답하기" brought the person here): the answer page, with the question on it
        if (GuideHub.awaitingUserAnswer()) showAsk() else if (screen == "home") showHome()
    }

    override fun onPause() {
        super.onPause()
        GuideHub.listener = null
        stopListening()
    }

    // ------------------------------------------------------------ home

    private fun showHome() {
        screen = "home"
        val col = column()
        // top: wordmark, settings (icon + word)
        val top = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        // the 해봄 logo (OK-hand wordmark, Figma 119:2819)
        val logoBox = FrameLayout(this)
        logoBox.addView(ImageView(this).apply {
            setImageResource(R.drawable.haebom_logo); scaleType = ImageView.ScaleType.FIT_START; adjustViewBounds = true
            contentDescription = "해봄"
        }, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(46)))
        top.addView(logoBox, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        top.addView(smallButton("설정", Icon.GEAR) { showSettings() })
        col.addView(top, lp(top = 8))

        val on = GuideHub.isServiceOn()
        if (!on) col.addView(warning(), lp(top = 16))

        val last = prefs.getString("last_task", null)?.takeIf { it.isNotBlank() }
        col.addView(greeting("오늘은 무엇을\n해 볼까요?"), lp(top = 16))

        // Figma "시안 A · 큰 타일 2×2": what the person was doing is the biggest block, the rest are four equal tiles.
        // The guide server looks at the screen as it is, so asking for the same thing again goes on from where the phone is.
        if (last != null) {
            val short = Practice.short(last)
            val done = prefs.getBoolean("done_${Practice.key(last)}", false) && prefs.getInt("last_step", 0) == 0
            col.addView(if (done) continueBlock("$short · 혼자서", 0, 0, label = "복습하기", icon = Icon.REPLAY) { startFlow(last, review = true) }
                else continueBlock(short, prefs.getInt("last_step", 0), prefs.getInt("last_total", 0)) { startFlow(last) }, lp(top = 16))
        }
        val grid = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        fun row(a: View, b: View) = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(a, LinearLayout.LayoutParams(0, dp(176), 1f).apply { marginEnd = dp(6) })
            addView(b, LinearLayout.LayoutParams(0, dp(176), 1f).apply { marginStart = dp(6) })
        }
        grid.addView(row(
            tile(Icon.QR, "수업 종이\n찍기", SURF, TONAL, PRI, edge = LINE) { showQrSoon() },
            tile(Icon.MIC, "말로\n물어보기", SURF, TONAL, PRI, edge = LINE) { showAsk() }), lp())
        grid.addView(row(
            tile(Icon.BOOK, "배울 것\n보기", SURF, TONAL, PRI, edge = LINE) { showLessons() },
            tile(Icon.HELP, "도움이\n필요해요", SURF, NEU, NEU_TX, edge = LINE) { askHelp() }), lp(top = 12))
        // second section: the menu, set apart from "today" by space and its own small title
        col.addView(text("메뉴", 20f, T1, bold = true), lp(top = 40))
        col.addView(grid, lp(top = 12, bottom = 8))
        setPage(col)
    }

    /** 해보미 waving next to the question of the day (Figma "Greeting Header"). */
    private fun greeting(question: String): View {
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        row.addView(ImageView(this).apply {
            setImageResource(R.drawable.hb_hello); scaleType = ImageView.ScaleType.FIT_CENTER
            contentDescription = "해보미가 인사해요"
        }, LinearLayout.LayoutParams(dp(140), dp(140)))
        val texts = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(24), 0, 0, 0) }
        texts.addView(text("안녕하세요!", 17f, TONAL_TX, bold = true))
        texts.addView(text(question, 28f, T1, bold = true).apply { letterSpacing = -0.025f; phrase() }, lp(top = 8))
        row.addView(texts, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        return row
    }

    /** A thin bar: how far the stopped lesson got (Figma "Continue Card"). */
    private fun progressBar(step: Int, total: Int): View = object : View(this) {
        private val track = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor(Palette.PEACH) }
        private val fill = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor(Palette.RING) }
        override fun onDraw(c: android.graphics.Canvas) {
            val h = height.toFloat(); val r = h / 2
            c.drawRoundRect(0f, 0f, width.toFloat(), h, r, r, track)
            c.drawRoundRect(0f, 0f, maxOf(h, width * step.toFloat() / total), h, r, r, fill)
        }
    }.apply { contentDescription = "${total}단계 중 ${step}단계" }

    /** The stopped lesson as one big orange block: play mark, "이어서 하기", where it stopped, a progress bar. */
    private fun continueBlock(title: String, step: Int, total: Int, label: String = "이어서 하기", icon: Icon = Icon.PLAY, onClick: () -> Unit): View {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(22), dp(22), dp(22), dp(22))
            background = round(PRI, 28f); isClickable = true; foreground = pressable(); setOnClickListener { onClick() }
            contentDescription = "$label. $title" + (if (step in 1..total) ", ${total}단계 중 ${step}단계" else "")
        }
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        row.addView(FrameLayout(this).apply {
            background = round(Color.argb(56, 255, 255, 255), 28f)
            addView(ImageView(this@MainActivity).apply { setImageDrawable(IconDrawable(icon, Color.WHITE)) }, FrameLayout.LayoutParams(dp(32), dp(32), Gravity.CENTER))
        }, LinearLayout.LayoutParams(dp(56), dp(56)))
        val texts = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(14), 0, 0, 0) }
        texts.addView(text(label, 28f, Color.WHITE, bold = true))
        texts.addView(text(title + (if (step in 1..total) " · $step/${total}단계" else ""), 19f, Color.WHITE, bold = true).apply { maxLines = 2; phrase() }, lp(top = 2))
        row.addView(texts, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        box.addView(row)
        if (step in 1..total) box.addView(object : View(this) {
            private val track = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(77, 255, 255, 255) }
            private val fill = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
            override fun onDraw(c: android.graphics.Canvas) {
                val h = height.toFloat(); val r = h / 2
                c.drawRoundRect(0f, 0f, width.toFloat(), h, r, r, track)
                c.drawRoundRect(0f, 0f, maxOf(h, width * step.toFloat() / total), h, r, r, fill)
            }
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(8)).apply { topMargin = dp(16) })
        return box
    }

    /** One of the four big home tiles: icon in a rounded box on top, a two-line label (22sp bold) below. */
    private fun tile(icon: Icon, label: String, bg: Int, iconBg: Int, iconColor: Int, edge: Int? = null, onClick: () -> Unit): View {
        val t = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(20), dp(18), dp(20))
            background = round(bg, 28f, edge); isClickable = true; foreground = pressable(); setOnClickListener { onClick() }
            elevation = dpf(2f); outlineAmbientShadowColor = Color.argb(40, 0, 0, 0); outlineSpotShadowColor = Color.argb(40, 0, 0, 0)
            contentDescription = label.replace("\n", " ")
        }
        t.addView(FrameLayout(this).apply {
            background = round(iconBg, 20f)
            addView(ImageView(this@MainActivity).apply { setImageDrawable(IconDrawable(icon, iconColor)) }, FrameLayout.LayoutParams(dp(34), dp(34), Gravity.CENTER))
        }, LinearLayout.LayoutParams(dp(60), dp(60)))
        t.addView(text(label, 22f, T1, bold = true).apply { gravity = Gravity.BOTTOM }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f).apply { topMargin = dp(10) })
        return t
    }

    /** "배울 것 보기": every thing to learn ([Practice]) as its own card. */
    private fun showLessons() {
        screen = "lessons"
        val col = column()
        col.addView(backButton(), lp(top = 4))
        col.addView(text("내가 배울 수 있는 것", 28f, T1, bold = true).apply { letterSpacing = -0.025f }, lp(top = 16))
        Practice.items.forEach { p ->
            val done = prefs.getBoolean("done_${p.id}", false)
            col.addView(lessonRow(p.title, if (done) "해 봤어요 · 복습으로 혼자 해 보기" else p.summary, if (done) "복습" else "시작") { startFlow(p.task, review = done) }, lp(top = 12))
        }
        setPage(col)
    }

    private fun warning(): View {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(18), dp(20), dp(18))
            background = round(WARN_BG, 20f, Color.parseColor(Palette.WARN_EDGE))
        }
        box.addView(text("화면 안내가 꺼져 있어요", 20f, T1, bold = true))
        box.addView(text("켜야 누를 곳을 표시해 드릴 수 있어요.", 18f, T2), lp(top = 4))
        box.addView(button("켜는 방법 보기", Kind.PRIMARY) { showPermission() }, lp(top = 14))
        return box
    }

    private fun lessonRow(title: String, summary: String, action: String = "시작", onClick: () -> Unit): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(96)
            setPadding(dp(16), dp(14), dp(16), dp(14))
            isClickable = true
            background = round(SURF, 24f, LINE)
            foreground = pressable()
            setOnClickListener { onClick() }
        }
        row.addView(iconTile(Icon.BOOK, TONAL, PRI, 52))
        val texts = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(14), 0, dp(8), 0) }
        texts.addView(text(title, 19f, T1, bold = true).apply { phrase() })
        if (summary.isNotBlank()) texts.addView(text(summary, 16f, T2).apply { phrase(); maxLines = 2; ellipsize = TextUtils.TruncateAt.END }, lp(top = 2))
        row.addView(texts, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        row.addView(text(action, 18f, TONAL_TX, bold = true).apply {
            setPadding(dp(16), dp(10), dp(16), dp(10)); background = round(TONAL, 14f)
        })
        return row
    }

    private fun action(icon: Icon, title: String, sub: String, bg: Int, iconColor: Int, titleColor: Int, filled: Boolean, onClick: () -> Unit): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(112)
            setPadding(dp(20), dp(20), dp(20), dp(20))
            background = if (filled) round(PRI, 24f) else round(SURF, 24f, LINE)
            isClickable = true
            foreground = pressable()
            setOnClickListener { onClick() }
            contentDescription = "$title. $sub"
        }
        row.addView(iconTile(icon, if (filled) Color.argb(46, 255, 255, 255) else bg, iconColor, 60))
        val texts = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16), 0, 0, 0) }
        texts.addView(text(title, 22f, titleColor, bold = true))
        texts.addView(text(sub, if (filled) 19f else 17f, if (filled) Color.WHITE else T2, bold = filled).apply { phrase() }, lp(top = 4))
        row.addView(texts, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        return row
    }

    // ------------------------------------------------------------ ask by voice or text

    private lateinit var askInput: EditText
    private var micLabel: TextView? = null
    private var speech: SpeechInput? = null

    private fun showAsk() {
        stopListening()
        screen = "ask"
        val col = column()
        col.addView(backButton(), lp(top = 4))
        col.addView(text("무엇을 하고\n싶으세요?", 28f, T1, bold = true).apply { letterSpacing = -0.025f }, lp(top = 16))
        col.addView(text("말하거나 글자로 적어 주세요.\n한 단계씩 안내해 드려요.", 19f, T2), lp(top = 8))
        if (GuideHub.awaitingUserAnswer()) {
            val q = GuideHub.messages.lastOrNull { it.role == "guide" }?.text
            if (q != null) col.addView(card().apply {
                addView(text("해봄이 묻고 있어요", 17f, TONAL_TX, bold = true))
                addView(text(q, 19f, T1).apply { phrase() }, lp(top = 6))
            }, lp(top = 16))
        }
        askInput = EditText(this).apply {
            hint = "예) 사진을 딸에게 보내고 싶어요"
            setHintTextColor(T2)
            setTextColor(T1)
            textSize = 21f
            minHeight = dp(120)
            gravity = Gravity.TOP or Gravity.START
            setPadding(dp(20), dp(18), dp(20), dp(18))
            background = round(SURF, 20f, LINE)
            imeOptions = EditorInfo.IME_ACTION_DONE
        }
        col.addView(askInput, lp(top = 20))
        val mic = button("말로 하기", Kind.TONAL, Icon.MIC) { listen() } as LinearLayout
        micLabel = mic.getChildAt(mic.childCount - 1) as TextView
        col.addView(mic, lp(top = 12))
        col.addView(button("이걸로 안내 받기", Kind.PRIMARY) { sendAsk() }, lp(top = 24, bottom = 8))
        setPage(col)
    }

    /**
     * "말로 하기": the microphone's sound goes to the speech server while the person talks and the words fill the
     * box as they come. The same button ends it. Without a speech server the phone's own recognizer is asked instead.
     */
    private fun listen() {
        speech?.let { it.finish(); micLabel?.text = "받아 적고 있어요…"; return }
        val base = GuideSettings.ttsBase
        if (base.isEmpty()) { listenWithPhone(); return }
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), REQ_MIC)
            return
        }
        micLabel?.text = "듣고 있어요 · 다 말했으면 누르세요"
        var mine: SpeechInput? = null
        mine = SpeechInput(base,
            onText = { t -> if (speech === mine && screen == "ask") { askInput.setText(t); askInput.setSelection(askInput.text.length) } },
            onEnd = { error ->
                if (speech === mine) {
                    speech = null
                    micLabel?.text = "말로 하기"
                    if (error != null) Toast.makeText(this, "말을 받아 적지 못했어요. 다시 누르거나 글자로 적어 주세요.", Toast.LENGTH_LONG).show()
                }
            })
        speech = mine
        mine.start()
    }

    private fun stopListening() {
        speech?.cancel()
        speech = null
        micLabel?.text = "말로 하기"
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != REQ_MIC) return
        if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) { if (screen == "ask") listen() }
        else Toast.makeText(this, "마이크를 허용해야 말로 할 수 있어요. 글자로 적어도 돼요.", Toast.LENGTH_LONG).show()
    }

    private fun listenWithPhone() {
        val i = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "ko-KR")
            putExtra(RecognizerIntent.EXTRA_PROMPT, "하고 싶은 일을 말해 주세요")
        }
        try {
            @Suppress("DEPRECATION") startActivityForResult(i, REQ_SPEECH)
        } catch (_: Exception) {
            Toast.makeText(this, "이 폰에서는 말로 입력할 수 없어요. 글자로 적어 주세요.", Toast.LENGTH_LONG).show()
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_SPEECH && resultCode == RESULT_OK) {
            val said = data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull() ?: return
            if (screen == "ask") askInput.setText(said)
        }
    }

    private fun sendAsk() {
        stopListening()
        val text = askInput.text.toString().trim()
        if (text.isEmpty()) { Toast.makeText(this, "하고 싶은 일을 먼저 적어 주세요.", Toast.LENGTH_SHORT).show(); return }
        if (!GuideHub.isServiceOn()) { showPermission(); return }
        if (GuideHub.awaitingUserAnswer()) GuideHub.userReply(text) else GuideHub.startTask(text)
        showHome()
        page.postDelayed({ moveTaskToBack(true) }, 350)
    }

    // ------------------------------------------------------------ permission (prominent disclosure)

    private fun showPermission() {
        screen = "permission"
        val col = column()
        col.addView(backButton(), lp(top = 4))
        col.addView(text("화면을 읽어도\n될까요?", 28f, T1, bold = true).apply { letterSpacing = -0.025f }, lp(top = 16))
        col.addView(text("누를 곳을 찾으려면 화면을 봐야 해요.", 19f, T2), lp(top = 8))
        val c = card(padV = 8)
        listOf(
            Triple(Icon.EYE, TONAL, "버튼 이름과 위치를 읽어요"),
            Triple(Icon.CLOCK, TONAL, "안내하는 동안에만 읽어요"),
            Triple(Icon.HAND, TONAL, "대신 누르거나 입력하지 않아요"),
            Triple(Icon.SEND, NEU, "안내를 만들 때 화면의 요소 목록과 화면 사진을 안내 서버로 보내요"),
            Triple(Icon.SPEAKER, NEU, "읽어 드릴 안내 문장을 음성 서버로 보내요"),
        ).forEach { (ic, bg, s) ->
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(0, dp(12), 0, dp(12)) }
            row.addView(iconTile(ic, bg, if (bg == NEU) T2 else PRI, 48))
            row.addView(text(s, 19f, T1).apply { phrase(); setPadding(dp(14), 0, 0, 0) }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            c.addView(row)
        }
        col.addView(c, lp(top = 20))
        col.addView(text("다음 화면에서 ‘설치된 앱’ → ‘해봄 (화면 안내)’를 눌러 켜 주세요. 강사님이 도와드려요.", 18f, T2).apply { phrase() }, lp(top = 16))
        col.addView(button("동의하고 설정 열기", Kind.PRIMARY) { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }, lp(top = 24, bottom = 8))
        setPage(col)
    }

    // ------------------------------------------------------------ settings (incl. the developer controls)

    private fun showSettings() {
        screen = "settings"
        val col = column()
        col.addView(backButton(), lp(top = 4))
        col.addView(text("설정", 28f, T1, bold = true), lp(top = 16))
        // the speech server's address can end in a secret path: the screen names the host only
        val guide = GuideSettings.hostOf(GuideSettings.guideBase)
        val voice = GuideSettings.hostOf(GuideSettings.ttsBase).ifEmpty { "폰 음성만" }
        fun lines(guideState: String) = "화면 안내: ${if (GuideHub.isServiceOn()) "켜짐" else "꺼짐"}\n안내 서버: $guide $guideState\n음성 서버: $voice"
        val status = text(lines("확인 중…"), 18f, T2)
        col.addView(card().apply { addView(status) }, lp(top = 16))
        io.execute {
            val ok = PlannerClient.health()
            runOnUiThread { status.text = lines(if (ok) "연결됨" else "연결 안 됨") }
        }
        if (GuideSettings.ttsBase.isNotEmpty()) {
            col.addView(text("읽어 주는 목소리", 19f, T1, bold = true), lp(top = 24))
            col.addView(choice("자연스러운 목소리", GuideSettings.ttsStream) { GuideSettings.ttsStream = true; showSettings() }, lp(top = 8))
            col.addView(choice("빠른 기계 목소리", !GuideSettings.ttsStream) { GuideSettings.ttsStream = false; showSettings() }, lp(top = 8))
        }
        col.addView(button("화면 안내 켜는 방법", Kind.NEUTRAL) { showPermission() }, lp(top = 28))
        col.addView(button("지금 안내 멈추기", Kind.NEUTRAL) { GuideHub.stop(); Toast.makeText(this, "안내를 멈췄어요.", Toast.LENGTH_SHORT).show() }, lp(top = 12, bottom = 8))
        setPage(col)
    }

    private fun choice(label: String, on: Boolean, onClick: () -> Unit): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(64); setPadding(dp(20), dp(12), dp(20), dp(12))
            background = round(if (on) TONAL else SURF, 18f, if (on) PRI else EDGE)
            isClickable = true; foreground = pressable(); setOnClickListener { onClick() }
        }
        row.addView(text(label, 19f, T1, bold = on), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        if (on) row.addView(text("선택됨", 17f, TONAL_TX, bold = true).apply { setPadding(0, 0, dp(10), 0) })
        row.addView(ImageView(this).apply { setImageDrawable(IconDrawable(if (on) Icon.CHECK_ON else Icon.RADIO, if (on) PRI else EDGE)) }, LinearLayout.LayoutParams(dp(28), dp(28)))
        return row
    }

    // ------------------------------------------------------------ actions

    /** Start a guide for [task] (a request sentence). [review]: done once before, so the ring waits a little. */
    private fun startFlow(task: String, review: Boolean = false) {
        if (!GuideHub.isServiceOn()) { showPermission(); return }
        GuideHub.startTask(task, review)
        page.postDelayed({ moveTaskToBack(true) }, 350)
    }

    private fun showQrSoon() {
        AlertDialog.Builder(this)
            .setTitle("수업 종이 찍기")
            .setMessage("QR 찍기는 곧 연결돼요.\n지금은 ‘배울 것 보기’에서 골라 주세요.")
            .setPositiveButton("알겠어요", null)
            .show()
    }

    private fun askHelp() {
        GuideHub.post("system", "강사에게 도움을 요청했어요.")
        AlertDialog.Builder(this)
            .setTitle("강사님께 알렸어요")
            .setMessage("곧 도와드릴 거예요.\n화면은 그대로 두세요.")
            .setPositiveButton("알겠어요", null)
            .show()
    }

    // ------------------------------------------------------------ building blocks

    private fun setPage(content: LinearLayout) {
        page.removeAllViews()
        val scroll = ScrollView(this).apply { isFillViewport = true; addView(content); clipToPadding = false }
        page.addView(scroll, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
    }

    private fun column() = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(24), dp(12), dp(24), dp(24))
    }

    private fun card(padV: Int = 20) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(20), dp(padV), dp(20), dp(padV))
        background = round(SURF, 24f, LINE)
    }

    private fun lp(top: Int = 0, bottom: Int = 0) = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
        topMargin = dp(top); bottomMargin = dp(bottom)
    }

    private fun text(s: String, size: Float, color: Int, bold: Boolean = false) = TextView(this).apply {
        text = s; textSize = size; setTextColor(color)
        // line height as a share of the font size: 140% for titles (22sp+), 160% for body text
        includeFontPadding = false; isFallbackLineSpacing = false
        lineHeight = (size * (if (size >= 22f) 1.4f else 1.6f) * resources.displayMetrics.scaledDensity).toInt()
        typeface = Typeface.create(Typeface.DEFAULT, if (bold) Typeface.BOLD else Typeface.NORMAL)
    }

    private fun TextView.phrase() {
        if (android.os.Build.VERSION.SDK_INT >= 33) lineBreakWordStyle = android.graphics.text.LineBreakConfig.LINE_BREAK_WORD_STYLE_PHRASE
    }

    private fun round(fill: Int, r: Float, stroke: Int? = null) = GradientDrawable().apply {
        setColor(fill); cornerRadius = dpf(r); if (stroke != null) setStroke(dp(1), stroke)
    }

    private fun pressable(): Drawable? {
        val a = obtainStyledAttributes(intArrayOf(android.R.attr.selectableItemBackground))
        return a.getDrawable(0).also { a.recycle() }
    }

    private enum class Kind { PRIMARY, TONAL, NEUTRAL }

    /** One look per job: go on (filled orange), do something on the side (light orange), help/back (grey). */
    private fun button(label: String, kind: Kind, icon: Icon? = null, onClick: () -> Unit): View {
        val (bg, fg) = when (kind) { Kind.PRIMARY -> PRI to Color.WHITE; Kind.TONAL -> TONAL to TONAL_TX; Kind.NEUTRAL -> NEU to NEU_TX }
        val b = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER
            minimumHeight = dp(64); setPadding(dp(24), dp(16), dp(24), dp(16))
            background = round(bg, 18f); isClickable = true; foreground = pressable()
            setOnClickListener { onClick() }; contentDescription = label
        }
        if (icon != null) b.addView(ImageView(this).apply { setImageDrawable(IconDrawable(icon, fg)) }, LinearLayout.LayoutParams(dp(24), dp(24)).apply { marginEnd = dp(8) })
        b.addView(text(label, 20f, fg, bold = true))
        return b
    }

    private fun smallButton(label: String, icon: Icon, onClick: () -> Unit): View {
        val b = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(52); setPadding(dp(14), dp(8), dp(18), dp(8))
            background = round(NEU, 26f); isClickable = true; foreground = pressable(); setOnClickListener { onClick() }
        }
        b.addView(ImageView(this).apply { setImageDrawable(IconDrawable(icon, NEU_TX)) }, LinearLayout.LayoutParams(dp(22), dp(22)).apply { marginEnd = dp(6) })
        b.addView(text(label, 18f, NEU_TX, bold = true))
        return b
    }

    private fun backButton(): View = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
        minimumHeight = dp(52); setPadding(dp(8), dp(8), dp(16), dp(8))
        isClickable = true; foreground = pressable(); setOnClickListener { showHome() }
        addView(ImageView(this@MainActivity).apply { setImageDrawable(IconDrawable(Icon.BACK, T1)) }, LinearLayout.LayoutParams(dp(26), dp(26)))
        addView(text("처음으로", 19f, T1, bold = true).apply { setPadding(dp(4), 0, 0, 0) })
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
    }

    private fun iconTile(icon: Icon, bg: Int, fg: Int, size: Int): View = FrameLayout(this).apply {
        background = round(bg, size * 0.32f)
        addView(ImageView(this@MainActivity).apply { setImageDrawable(IconDrawable(icon, fg)) },
            FrameLayout.LayoutParams(dp(size / 2 + 4), dp(size / 2 + 4), Gravity.CENTER))
        layoutParams = LinearLayout.LayoutParams(dp(size), dp(size))
    }

    private enum class Icon { PLAY, REPLAY, QR, MIC, HELP, GEAR, BOOK, BACK, EYE, CLOCK, HAND, SEND, SPEAKER, CHECK_ON, RADIO }

    /** Material Icons (filled) from res/drawable, tinted; one look for every icon in the app. */
    private fun IconDrawable(icon: Icon, color: Int): Drawable = getDrawable(when (icon) {
        Icon.PLAY -> R.drawable.ic_play_arrow
        Icon.REPLAY -> R.drawable.ic_replay
        Icon.QR -> R.drawable.ic_qr_code
        Icon.MIC -> R.drawable.ic_mic
        Icon.HELP -> R.drawable.ic_help
        Icon.GEAR -> R.drawable.ic_settings
        Icon.BOOK -> R.drawable.ic_book
        Icon.BACK -> R.drawable.ic_arrow_back
        Icon.EYE -> R.drawable.ic_visibility
        Icon.CLOCK -> R.drawable.ic_schedule
        Icon.HAND -> R.drawable.ic_pan_tool
        Icon.SEND -> R.drawable.ic_send
        Icon.SPEAKER -> R.drawable.ic_volume_up
        Icon.CHECK_ON -> R.drawable.ic_check_circle
        Icon.RADIO -> R.drawable.ic_radio_unchecked
    })!!.mutate().apply { setTint(color) }

    companion object {
        private const val REQ_SPEECH = 7
        private const val REQ_MIC = 8
    }
}
