package ai.toolus.mobileguide

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.util.Log
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import java.util.concurrent.Executors

/**
 * Reads a guide sentence aloud.
 *
 * The sentence goes to the TTS server ([GuideSettings.ttsBase], `POST /tts`) and the audio that comes back is played.
 * When the server has not answered within [WAIT_MS], fails, or is not set, the phone's own voice
 * (android.speech.tts.TextToSpeech) reads the sentence instead. The guide server is not involved: this takes a
 * sentence and nothing else, and showing the guide never waits for it.
 */
object Speaker {
    private const val TAG = "ToolusGuide"
    private const val WAIT_MS = 1500L
    /** The same sentence again this soon is the same step being shown again, not a new step. */
    private const val REPEAT_MS = 4000L
    private const val SPEED = 0.9

    private val main = Handler(Looper.getMainLooper())
    private val net = Executors.newCachedThreadPool()

    // main thread only
    private var turn = 0
    private var last = ""
    private var lastAt = 0L
    private var player: MediaPlayer? = null
    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var pendingLocal: String? = null

    fun say(context: Context, text: String) {
        val sentence = text.replace('\n', ' ').trim()
        val now = System.currentTimeMillis()
        if (sentence.isEmpty() || (sentence == last && now - lastAt < REPEAT_MS)) return
        last = sentence
        lastAt = now
        val my = ++turn
        silence()
        val app = context.applicationContext
        var settled = false
        fun local(reason: String) {
            if (settled || my != turn) return
            settled = true
            Log.i(TAG, "TTS phone voice ($reason): $sentence")
            speakLocally(app, sentence)
        }
        val base = GuideSettings.ttsBase
        if (base.isEmpty()) { local("no TTS server set"); return }
        main.postDelayed({ local("no answer in ${WAIT_MS}ms") }, WAIT_MS)
        net.execute {
            val started = System.currentTimeMillis()
            val audio = runCatching { fetch(app, base, sentence, my) }
            main.post {
                if (settled || my != turn) return@post
                audio.onSuccess {
                    settled = true
                    Log.i(TAG, "TTS server ${System.currentTimeMillis() - started}ms ${it.length()}B: $sentence")
                    play(it) { why -> Log.w(TAG, "TTS playback failed ($why)"); speakLocally(app, sentence) }
                }.onFailure { local("server failed: ${it.message}") }
            }
        }
    }

    fun stop() {
        turn += 1
        silence()
    }

    private fun silence() {
        player?.let { runCatching { it.stop() }; it.release() }
        player = null
        tts?.stop()
    }

    private fun fetch(context: Context, base: String, sentence: String, n: Int): File {
        val c = URL("$base/tts").openConnection() as HttpURLConnection
        try {
            c.requestMethod = "POST"
            c.connectTimeout = 1000
            c.readTimeout = 5000
            c.doOutput = true
            c.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            val body = JSONObject().put("text", sentence).put("speed", SPEED)
            GuideSettings.ttsEngine.takeIf { it.isNotEmpty() }?.let { body.put("engine", it) }
            c.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            val code = c.responseCode
            if (code != 200) throw IllegalStateException("tts $code")
            val file = File(context.cacheDir, "tts-${n % 4}.audio")
            c.inputStream.use { input -> file.outputStream().use { input.copyTo(it) } }
            return file
        } finally {
            c.disconnect()
        }
    }

    private fun play(file: File, onError: (String) -> Unit) {
        val p = MediaPlayer()
        player = p
        try {
            p.setAudioAttributes(AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ASSISTANCE_ACCESSIBILITY)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
            p.setDataSource(file.path)
            p.setOnPreparedListener { it.start() }
            p.setOnCompletionListener {
                Log.i(TAG, "TTS played ${it.duration}ms")
                it.release()
                if (player === it) player = null
            }
            p.setOnErrorListener { mp, what, extra ->
                mp.release()
                if (player === mp) player = null
                onError("MediaPlayer $what/$extra")
                true
            }
            p.prepareAsync()
        } catch (e: Exception) {
            p.release()
            if (player === p) player = null
            onError(e.toString())
        }
    }

    private fun speakLocally(context: Context, sentence: String) {
        val engine = tts
        if (engine != null && ttsReady) {
            engine.speak(sentence, TextToSpeech.QUEUE_FLUSH, null, "guide")
            return
        }
        pendingLocal = sentence
        if (engine != null) return
        tts = TextToSpeech(context) { status ->
            main.post {
                ttsReady = status == TextToSpeech.SUCCESS
                if (!ttsReady) { Log.w(TAG, "TTS phone voice unavailable (status $status)"); return@post }
                val lang = tts?.setLanguage(Locale.KOREAN)
                if (lang == TextToSpeech.LANG_MISSING_DATA || lang == TextToSpeech.LANG_NOT_SUPPORTED) Log.w(TAG, "TTS phone voice has no Korean data ($lang)")
                pendingLocal?.let { tts?.speak(it, TextToSpeech.QUEUE_FLUSH, null, "guide") }
                pendingLocal = null
            }
        }
    }
}
