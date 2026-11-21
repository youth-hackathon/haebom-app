package ai.toolus.mobileguide

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.WindowInsets
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.util.concurrent.Executors

/**
 * The chat screen: say what you want in natural language and the guide shows, step by step, where to tap.
 * The in-app prominent disclosure comes first; it must precede the accessibility settings hop.
 */
class MainActivity : Activity() {
    private lateinit var status: TextView
    private lateinit var disclosure: LinearLayout
    private lateinit var list: LinearLayout
    private lateinit var scroll: ScrollView
    private lateinit var input: EditText
    private val io = Executors.newSingleThreadExecutor()
    private var rendered = 0

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        GuideSettings.init(this)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#0B1220"))
        }
        root.addView(TextView(this).apply {
            text = "해봄 화면 안내"
            textSize = 20f
            setTextColor(Color.WHITE)
            setTypeface(typeface, Typeface.BOLD)
        })
        status = TextView(this).apply { textSize = 12f; setTextColor(Color.parseColor("#94A3B8")) }
        root.addView(status)

        disclosure = buildDisclosure()
        root.addView(disclosure)

        list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        scroll = ScrollView(this).apply { addView(list); isFillViewport = true }
        root.addView(scroll, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))

        val inputRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        input = EditText(this).apply {
            hint = "무엇을 도와드릴까요? 예: 블루투스 켜는 화면 열어줘"
            setHintTextColor(Color.parseColor("#64748B"))
            setTextColor(Color.WHITE)
            imeOptions = EditorInfo.IME_ACTION_SEND
            isSingleLine = true
            textSize = 15f
            setPadding(dp(14), dp(10), dp(14), dp(10))
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#1E293B")); cornerRadius = dp(18).toFloat()
                setStroke(dp(1), Color.parseColor("#334155"))
            }
            setOnEditorActionListener { _, id, _ -> if (id == EditorInfo.IME_ACTION_SEND) { send(); true } else false }
        }
        inputRow.addView(input, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        inputRow.addView(pill("보내기") { send() })
        inputRow.addView(pill("중단") { GuideHub.stop() })
        root.addView(inputRow)
        // targetSdk 36 is edge-to-edge: keep the input row above the navigation bar and the keyboard.
        root.setOnApplyWindowInsetsListener { v, insets ->
            val bars = insets.getInsets(
                WindowInsets.Type.systemBars() or WindowInsets.Type.ime() or WindowInsets.Type.displayCutout()
            )
            v.setPadding(dp(16) + bars.left, bars.top + dp(8), dp(16) + bars.right, bars.bottom + dp(8))
            WindowInsets.CONSUMED
        }
        setContentView(root)
    }

    // ------------------------------------------------------------ views

    private fun buildDisclosure(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(14), dp(12), dp(14), dp(12))
        background = GradientDrawable().apply { setColor(Color.parseColor("#1E293B")); cornerRadius = dp(12).toFloat() }
        addView(TextView(this@MainActivity).apply {
            setTextColor(Color.WHITE); textSize = 14f
            text = "화면 안내를 하려면 접근성 권한이 필요합니다.\n\n" +
                "• 읽는 것: 지금 화면에 보이는 버튼·글자와 그 위치\n" +
                "• 쓰는 곳: 다음에 누를 곳을 화면 위에 표시하고, 안내가 끝났는지 확인\n" +
                "• 보내는 것: 안내를 만들 때 화면 요소 목록과 화면 사진을 안내 서버로, 읽어 줄 안내 문장을 음성 서버로 보냅니다.\n" +
                "• 하지 않는 것: 대신 누르거나 입력하지 않습니다.\n\n" +
                "동의하면 접근성 설정 화면으로 이동합니다. 목록에서 '${getString(R.string.a11y_service_label)}'을 켜 주세요."
        })
        val buttons = LinearLayout(this@MainActivity).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.END }
        buttons.addView(pill("동의하고 설정 열기") { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) })
        addView(buttons)
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(10); bottomMargin = dp(10)
        }
    }

    private fun pill(label: String, onClick: () -> Unit) = Button(this).apply {
        text = label
        isAllCaps = false
        setTextColor(Color.WHITE)
        background = GradientDrawable().apply { setColor(Color.parseColor("#0EA5E9")); cornerRadius = dp(18).toFloat() }
        setPadding(dp(14), dp(6), dp(14), dp(6))
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            marginStart = dp(8); topMargin = dp(6)
        }
        setOnClickListener { onClick() }
    }

    private fun send() {
        val text = input.text.toString().trim()
        if (text.isEmpty()) return
        if (!GuideHub.isServiceOn()) {
            Toast.makeText(this, "접근성 서비스를 먼저 켜 주세요.", Toast.LENGTH_SHORT).show()
            return
        }
        input.setText("")
        if (GuideHub.awaitingUserAnswer()) GuideHub.userReply(text) else GuideHub.startTask(text)
        // Step out of the way so the guide can read the app underneath (our own windows are skipped).
        input.postDelayed({ moveTaskToBack(true) }, 350)
    }

    override fun onResume() {
        super.onResume()
        GuideHub.listener = { renderMessages(); refreshStatus() }
        renderMessages()
        refreshStatus()
    }

    override fun onPause() {
        super.onPause()
        GuideHub.listener = null
    }

    private fun refreshStatus() {
        val on = GuideHub.isServiceOn()
        disclosure.visibility = if (on) View.GONE else View.VISIBLE
        val service = "접근성 서비스: ${if (on) "켜짐" else "꺼짐"}"
        status.text = "$service · 안내 서버: 확인 중…"
        io.execute {
            val ok = PlannerClient.health()
            runOnUiThread { status.text = "$service · 안내 서버: ${if (ok) "연결됨" else "연결 안 됨 (${GuideSettings.guideBase})"}" }
        }
    }

    private fun renderMessages() {
        val msgs = GuideHub.messages
        while (rendered < msgs.size) {
            val m = msgs[rendered]
            rendered += 1
            val bubble = TextView(this).apply {
                text = m.text
                textSize = 14f
                setPadding(dp(12), dp(8), dp(12), dp(8))
                val (bg, fg) = when (m.role) {
                    "user" -> "#0EA5E9" to "#FFFFFF"
                    "guide" -> "#1E293B" to "#E2E8F0"
                    else -> "#00000000" to "#64748B"
                }
                setTextColor(Color.parseColor(fg))
                background = GradientDrawable().apply { setColor(Color.parseColor(bg)); cornerRadius = dp(12).toFloat() }
            }
            val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                topMargin = dp(6)
                gravity = if (m.role == "user") Gravity.END else Gravity.START
            }
            list.addView(bubble, lp)
        }
        scroll.post { scroll.fullScroll(View.FOCUS_DOWN) }
    }
}
