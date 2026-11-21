package ai.toolus.mobileguide

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.media.MediaPlayer
import android.media.PlaybackParams
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.util.Base64
import android.util.Log
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import java.util.concurrent.Executors

/**
 * Reads a guide sentence aloud.
 *
 * The sentence goes to the TTS server ([GuideSettings.ttsBase]). There are two ways to get the sound (haebom-tts
 * docs/interface.md, docs/streaming-api.md):
 *  - in pieces ([GuideSettings.ttsStream], the default): `POST /tts/stream` answers with lines of PCM, and each piece
 *    is played as it comes, so the voice starts before the whole sentence is made;
 *  - whole: `POST /tts` answers with one audio file, played when all of it is here.
 * The voice is always Gemini's ([GuideSettings.VOICE]). However long the server takes, the app waits for that voice:
 * it does not swap to another one because the sound is late. A server that does not offer the pieces is asked for
 * the whole sound in the same voice. Only when the server cannot be reached or answers with an error, twice in a
 * row, or no server is set, does the phone's own voice (android.speech.tts.TextToSpeech) read the sentence, so that
 * a broken network does not leave the person with no sound at all.
 * A sentence that was read in pieces is kept in memory, so reading it again ("다시 듣기") starts at once.
 * The guide server is not involved: this takes a sentence and nothing else, and showing the guide never waits for it.
 */
object Speaker {
    private const val TAG = "ToolusGuide"
    /** How long one connection may take, and how long the app waits for the next bytes of the voice. */
    private const val CONNECT_MS = 10_000
    private const val READ_MS = 60_000
    /** A failed request for the whole sound is made this many times before the phone's own voice reads. */
    private const val TRIES = 2
    /** The same sentence again this soon is the same step being shown again, not a new step. */
    private const val REPEAT_MS = 4000L
    private const val SPEED = 0.9
    /** What `/tts/stream` sends: PCM16 little-endian, mono, at this rate. */
    private const val STREAM_RATE = 24000
    /** Playing starts once this much sound is here (0.15 s), so the first pieces do not stutter. */
    private const val START_BYTES = STREAM_RATE * 2 * 15 / 100
    private const val KEEP = 40

    private val main = Handler(Looper.getMainLooper())
    private val net = Executors.newCachedThreadPool()

    /** One reading through an AudioTrack. The main thread makes and cancels it; a worker thread plays it. */
    private class Job {
        @Volatile var cancelled = false
        /** The first sound went to the phone's audio: from here on nothing else reads this sentence. */
        @Volatile var sounding = false
        @Volatile var conn: HttpURLConnection? = null
        @Volatile var track: AudioTrack? = null
    }

    /** The server answered, but not with the pieces (it has no such voice): ask for the whole sound instead. */
    private class Unavailable(why: String) : Exception(why)

    // main thread only
    private var turn = 0
    private var last = ""
    private var lastAt = 0L
    private var job: Job? = null
    private var player: MediaPlayer? = null
    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var pendingLocal: String? = null
    private val heard = object : LinkedHashMap<String, ByteArray>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, ByteArray>?) = size > KEEP
    }

    /** [again]: the person asked to hear it again, so the same sentence is read even right after itself. */
    fun say(context: Context, text: String, again: Boolean = false) {
        val sentence = text.replace('\n', ' ').trim()
        val now = System.currentTimeMillis()
        if (sentence.isEmpty() || (!again && sentence == last && now - lastAt < REPEAT_MS)) return
        last = sentence
        lastAt = now
        val my = ++turn
        silence()
        val app = context.applicationContext
        val base = GuideSettings.ttsBase
        if (base.isEmpty()) {
            Log.i(TAG, "TTS phone voice (no TTS server set): $sentence")
            speakLocally(app, sentence)
            return
        }
        val kept = heard[sentence]
        when {
            kept != null && GuideSettings.ttsStream -> {
                Log.i(TAG, "TTS from memory ${kept.size}B: $sentence")
                val j = Job().also { job = it }
                net.execute { playKept(j, kept) }
            }
            GuideSettings.ttsStream -> pieces(app, base, sentence, my)
            else -> whole(app, base, sentence, my)
        }
    }

    fun stop() {
        turn += 1
        silence()
    }

    private fun silence() {
        job?.let { cancel(it) }
        job = null
        player?.let { runCatching { it.stop() }; it.release() }
        player = null
        tts?.stop()
    }

    // ---------------------------------------------------------------- in pieces: POST /tts/stream

    private fun pieces(app: Context, base: String, sentence: String, my: Int) {
        val j = Job().also { job = it }
        val started = System.currentTimeMillis()
        var settled = false
        /** The pieces did not come, before any sound: ask for the whole sound, in the same voice. No clock is involved. */
        fun askWhole(reason: String) {
            if (settled || my != turn) return
            synchronized(j) { if (j.sounding) return; j.cancelled = true }
            settled = true
            cancel(j)
            Log.i(TAG, "TTS no pieces ($reason), asking for the whole sound")
            whole(app, base, sentence, my)
        }
        net.execute {
            val result = runCatching { readPieces(j, base, sentence, started) }
            main.post {
                result.onSuccess { pcm -> if (pcm != null) heard[sentence] = pcm }.onFailure { e ->
                    if (j.sounding) { if (!j.cancelled) Log.w(TAG, "TTS pieces broke off after the sound began: ${e.message}") }
                    else askWhole(if (e is Unavailable) e.message ?: "" else "server failed: ${e.message}")
                }
            }
        }
    }

    /** Reads the lines and plays each piece as it comes. The whole sound when it ended well, null when called off. */
    private fun readPieces(j: Job, base: String, sentence: String, started: Long): ByteArray? {
        val c = URL("$base/tts/stream").openConnection() as HttpURLConnection
        j.conn = c
        var track: AudioTrack? = null
        var played = 0
        try {
            c.requestMethod = "POST"
            c.connectTimeout = CONNECT_MS
            c.readTimeout = READ_MS
            c.doOutput = true
            c.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            // no speed here: this path takes none; the slower reading is done by the player (see [open])
            c.outputStream.use { it.write(JSONObject().put("text", sentence).toString().toByteArray(Charsets.UTF_8)) }
            val code = c.responseCode
            if (code != 200) throw Unavailable("tts/stream $code")
            val all = ByteArrayOutputStream()
            var done = false
            var firstMs = 0L
            c.inputStream.bufferedReader(Charsets.UTF_8).use { lines ->
                read@ while (!j.cancelled) {
                    val line = lines.readLine() ?: break
                    if (line.isBlank()) continue
                    val ev = JSONObject(line)
                    when (ev.optString("type")) {
                        "audio" -> {
                            if (ev.optInt("sample_rate", STREAM_RATE) != STREAM_RATE || ev.optInt("channels", 1) != 1 ||
                                ev.optString("encoding", "pcm_s16le") != "pcm_s16le") throw IllegalStateException("unexpected audio format")
                            val piece = Base64.decode(ev.getString("data"), Base64.DEFAULT)
                            all.write(piece)
                            val t = track
                            if (t != null) played += write(j, t, piece)
                            else if (all.size() >= START_BYTES) {
                                track = open(j) ?: return null
                                firstMs = System.currentTimeMillis() - started
                                played += write(j, track!!, all.toByteArray())
                            }
                        }
                        "done" -> { done = true; break@read }
                        "error" -> throw IllegalStateException(ev.optString("message", "error"))
                    }
                }
            }
            if (j.cancelled) return null
            if (!done) throw IllegalStateException("the stream ended without done")
            if (track == null) { // a sound shorter than START_BYTES
                track = open(j) ?: return null
                firstMs = System.currentTimeMillis() - started
                played += write(j, track!!, all.toByteArray())
            }
            Log.i(TAG, "TTS pieces first=${firstMs}ms all=${System.currentTimeMillis() - started}ms ${all.size()}B: $sentence")
            return all.toByteArray()
        } finally {
            runCatching { c.disconnect() }
            track?.let { t -> if (!j.cancelled) playOut(j, t, played); runCatching { t.release() } }
        }
    }

    private fun playKept(j: Job, pcm: ByteArray) {
        var track: AudioTrack? = null
        try {
            track = open(j) ?: return
            val n = write(j, track, pcm)
            if (!j.cancelled) playOut(j, track, n)
        } catch (e: Exception) {
            Log.w(TAG, "TTS playback failed ($e)")
        } finally {
            track?.let { runCatching { it.release() } }
        }
    }

    /** The first sound is about to go out. Null: this reading was called off before that. */
    private fun open(j: Job): AudioTrack? {
        val t = try {
            val min = AudioTrack.getMinBufferSize(STREAM_RATE, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
            AudioTrack.Builder()
                .setAudioAttributes(attributes())
                .setAudioFormat(AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(STREAM_RATE).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
                .setTransferMode(AudioTrack.MODE_STREAM)
                .setBufferSizeInBytes(maxOf(min, STREAM_RATE)) // half a second
                .build()
        } catch (e: Exception) {
            throw Unavailable("no AudioTrack: $e")
        }
        synchronized(j) {
            if (j.cancelled) { t.release(); return null }
            j.sounding = true
            j.track = t
        }
        // a little slower for older ears, at the same pitch (the whole-sound path asks the server for this speed)
        runCatching { t.playbackParams = PlaybackParams().setSpeed(SPEED.toFloat()).setPitch(1f) }
        t.play()
        return t
    }

    /** Blocks while the track's buffer is full, so the network is read no faster than the sound plays. */
    private fun write(j: Job, t: AudioTrack, b: ByteArray): Int {
        var off = 0
        while (off < b.size && !j.cancelled) {
            val n = t.write(b, off, b.size - off)
            if (n < 0) throw IllegalStateException("AudioTrack write $n")
            if (n == 0) Thread.sleep(10)
            off += n
        }
        return off
    }

    /** Everything is written: let the last piece play to its end before the track is let go. */
    private fun playOut(j: Job, t: AudioTrack, bytes: Int) {
        val frames = bytes / 2
        val left = (frames - t.playbackHeadPosition).coerceAtLeast(0)
        val until = System.currentTimeMillis() + (left * 1000.0 / STREAM_RATE / SPEED).toLong() + 400
        while (!j.cancelled && System.currentTimeMillis() < until && t.playbackHeadPosition < frames) Thread.sleep(30)
        if (!j.cancelled) Thread.sleep(150) // what the phone's audio still holds
        runCatching { t.stop() }
        if (!j.cancelled) Log.i(TAG, "TTS played ${frames * 1000L / STREAM_RATE}ms")
    }

    private fun cancel(j: Job) {
        synchronized(j) { j.cancelled = true }
        j.track?.let { t -> runCatching { t.pause(); t.flush() } }
        j.conn?.let { c -> net.execute { runCatching { c.disconnect() } } }
    }

    private fun attributes() = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_ASSISTANCE_ACCESSIBILITY)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build()

    // ---------------------------------------------------------------- whole: POST /tts

    /** Waits for the server's voice for as long as it takes. A failed request is made again ([TRIES] in all). */
    private fun whole(app: Context, base: String, sentence: String, my: Int, attempt: Int = 1) {
        net.execute {
            val started = System.currentTimeMillis()
            val audio = runCatching { fetch(app, base, sentence, my) }
            main.post {
                if (my != turn) return@post
                audio.onSuccess {
                    Log.i(TAG, "TTS server ${System.currentTimeMillis() - started}ms ${it.length()}B: $sentence")
                    play(it) { why -> Log.w(TAG, "TTS playback failed ($why)"); speakLocally(app, sentence) }
                }.onFailure { e ->
                    if (attempt < TRIES) {
                        Log.i(TAG, "TTS server failed (${e.message}), asking again")
                        whole(app, base, sentence, my, attempt + 1)
                    } else {
                        Log.i(TAG, "TTS phone voice (server failed $TRIES times: ${e.message}): $sentence")
                        speakLocally(app, sentence)
                    }
                }
            }
        }
    }

    private fun fetch(context: Context, base: String, sentence: String, n: Int): File {
        val c = URL("$base/tts").openConnection() as HttpURLConnection
        try {
            c.requestMethod = "POST"
            c.connectTimeout = CONNECT_MS
            c.readTimeout = READ_MS
            c.doOutput = true
            c.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            val body = JSONObject().put("text", sentence).put("speed", SPEED).put("engine", GuideSettings.VOICE)
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
            p.setAudioAttributes(attributes())
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

    // ---------------------------------------------------------------- the phone's own voice

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
