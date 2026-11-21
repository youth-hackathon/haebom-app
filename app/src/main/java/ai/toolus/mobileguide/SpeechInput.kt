package ai.toolus.mobileguide

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.util.Log
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URI
import java.security.SecureRandom
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

/**
 * What the person says, as text, through the TTS server's live path (`GET /stt/live`, a WebSocket; haebom-tts
 * docs/streaming-api.md). The microphone's sound goes out while it is recorded (PCM16 little-endian, mono, 16 kHz,
 * 0.1 s pieces) and the words come back while the person is still talking.
 *
 *     connect → status ready → {"type":"start"} → started → binary PCM … → {"type":"stop"} → final … → done
 *
 * One object is one utterance. [start] opens the connection and the microphone, [finish] ends the recording and
 * waits for the words, [cancel] drops everything. [onText] gets the words so far (confirmed parts plus the part
 * still changing); [onEnd] comes once, with null when it ended well. Both come on the main thread.
 * The caller holds the RECORD_AUDIO permission. The app uses the Android SDK only, so the WebSocket framing
 * (RFC 6455) is done here: masked frames out, text frames in, ping answered.
 */
class SpeechInput(
    private val base: String,
    private val onText: (text: String) -> Unit,
    private val onEnd: (error: String?) -> Unit,
) {
    private val main = Handler(Looper.getMainLooper())
    @Volatile private var socket: Socket? = null
    @Volatile private var out: OutputStream? = null
    @Volatile private var finishing = false
    @Volatile private var closed = false
    /** Some words have come back already. */
    @Volatile private var heard = false
    private var ended = false // main thread
    private val random = SecureRandom()

    fun start() {
        Thread({ run() }, "haebom-stt").start()
        // nobody talks this long to ask for one thing; the server's own limit is 120 s
        main.postDelayed({ finish() }, MAX_MS)
    }

    /** The person is done talking: send what is left, then wait for the last words, but not for long. */
    fun finish() {
        if (finishing) return
        finishing = true
        // the server waits up to 20 s for words that may never come (nothing was said); the person does not
        main.postDelayed({ if (!ended) { Log.i(TAG, "STT no last words in ${LAST_WORDS_MS}ms"); end(if (heard) null else "nothing was heard") } }, LAST_WORDS_MS)
    }

    fun cancel() {
        end(null, silent = true)
    }

    private fun end(error: String?, silent: Boolean = false) {
        closed = true
        finishing = true
        val s = socket
        if (s != null) Thread { runCatching { s.close() } }.start()
        main.post {
            if (ended) return@post
            ended = true
            if (!silent) onEnd(error)
        }
    }

    private fun run() {
        try {
            val u = URI(base)
            val tls = u.scheme == "https"
            val port = if (u.port > 0) u.port else if (tls) 443 else 80
            val s = if (tls) SSLSocketFactory.getDefault().createSocket() else Socket()
            socket = s
            s.connect(InetSocketAddress(u.host, port), 4000)
            if (s is SSLSocket) {
                s.startHandshake()
                if (!HttpsURLConnection.getDefaultHostnameVerifier().verify(u.host, s.session)) throw IllegalStateException("the server's certificate is for another host")
            }
            s.soTimeout = 25_000
            val o = s.getOutputStream()
            val i = BufferedInputStream(s.getInputStream())
            val key = Base64.encodeToString(ByteArray(16).also { random.nextBytes(it) }, Base64.NO_WRAP)
            o.write(("GET ${u.rawPath.orEmpty().trimEnd('/')}/stt/live HTTP/1.1\r\nHost: ${u.host}:$port\r\nUpgrade: websocket\r\n" +
                "Connection: Upgrade\r\nSec-WebSocket-Key: $key\r\nSec-WebSocket-Version: 13\r\n\r\n").toByteArray(Charsets.US_ASCII))
            o.flush()
            val head = readHead(i)
            if (!head.startsWith("HTTP/1.1 101")) throw IllegalStateException("no WebSocket: ${head.lineSequence().firstOrNull()}")
            out = o

            val confirmed = StringBuilder()
            var recorder: Thread? = null
            while (!closed) {
                val (op, payload) = readMessage(i) ?: break
                if (op != OP_TEXT) continue
                val ev = JSONObject(String(payload, Charsets.UTF_8))
                when (ev.optString("type")) {
                    "status" -> if (ev.optString("status") == "ready" && recorder == null) send(OP_TEXT, """{"type":"start"}""".toByteArray())
                        else if (ev.optString("status") == "disconnected") throw IllegalStateException("the server closed the session")
                    "started" -> recorder = Thread({ record() }, "haebom-mic").also { it.start() }
                    "interim" -> { val t = confirmed.toString() + ev.optString("text"); if (t.isNotBlank()) heard = true; main.post { if (!ended) onText(t) } }
                    "final" -> { confirmed.append(ev.optString("text")); val t = confirmed.toString(); if (t.isNotBlank()) heard = true; main.post { if (!ended) onText(t) } }
                    "done" -> {
                        val t = confirmed.toString().trim()
                        Log.i(TAG, "STT done chars=${t.length}")
                        main.post { if (!ended) onText(t) }
                        runCatching { send(OP_CLOSE, ByteArray(0)) }
                        end(null)
                        return
                    }
                    "error" -> throw IllegalStateException(ev.optString("message", "error"))
                }
            }
            if (!closed) throw EOFException("the connection ended before the words came")
        } catch (e: Exception) {
            if (!closed) { Log.w(TAG, "STT failed: $e"); end(e.message ?: e.toString()) }
        } finally {
            runCatching { socket?.close() }
        }
    }

    /** The microphone to the socket until [finish]; then the rest of the sound, then `stop`. */
    @SuppressLint("MissingPermission")
    private fun record() {
        var rec: AudioRecord? = null
        try {
            val min = AudioRecord.getMinBufferSize(RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
            rec = AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, RATE, AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT, maxOf(min, PIECE * 4))
            if (rec.state != AudioRecord.STATE_INITIALIZED) throw IllegalStateException("the microphone did not open")
            rec.startRecording()
            val buf = ByteArray(PIECE)
            var sent = 0
            while (!finishing && !closed) {
                val n = rec.read(buf, 0, buf.size)
                if (n < 0) throw IllegalStateException("AudioRecord read $n")
                val even = n - n % 2
                if (even > 0) { send(OP_BINARY, if (even == buf.size) buf else buf.copyOf(even)); sent += even }
            }
            rec.stop()
            Log.i(TAG, "STT sent ${sent * 1000L / (RATE * 2)}ms of sound")
            if (!closed) send(OP_TEXT, """{"type":"stop"}""".toByteArray())
        } catch (e: Exception) {
            if (!closed) { Log.w(TAG, "STT microphone failed: $e"); end(e.message ?: e.toString()) }
        } finally {
            rec?.let { runCatching { it.release() } }
        }
    }

    // ---------------------------------------------------------------- WebSocket frames

    private fun readHead(i: BufferedInputStream): String {
        val b = ByteArrayOutputStream()
        var tail = 0
        while (tail != 0x0D0A0D0A) {
            val c = i.read()
            if (c < 0) throw EOFException("no answer to the WebSocket request")
            b.write(c)
            tail = (tail shl 8) or c
            if (b.size() > 16_384) throw IllegalStateException("the answer's head is too long")
        }
        return b.toString("ISO-8859-1")
    }

    /** One whole message (continuation frames joined), pings answered on the way. Null: the server closed. */
    private fun readMessage(i: BufferedInputStream): Pair<Int, ByteArray>? {
        var kind = -1
        val whole = ByteArrayOutputStream()
        while (true) {
            val b0 = i.read()
            val b1 = i.read()
            if (b0 < 0 || b1 < 0) return null
            val op = b0 and 0x0F
            var len = (b1 and 0x7F).toLong()
            if (len == 126L) { val e = need(i, 2); len = ((e[0].toLong() and 0xFF) shl 8) or (e[1].toLong() and 0xFF) }
            else if (len == 127L) { val e = need(i, 8); len = 0; for (k in 0 until 8) len = (len shl 8) or (e[k].toLong() and 0xFF) }
            if (len > MAX_MESSAGE) throw IllegalStateException("a message of $len bytes")
            val mask = if (b1 and 0x80 != 0) need(i, 4) else null
            val payload = need(i, len.toInt())
            if (mask != null) for (k in payload.indices) payload[k] = (payload[k].toInt() xor mask[k % 4].toInt()).toByte()
            when (op) {
                OP_PING -> { send(OP_PONG, payload); continue }
                OP_PONG -> continue
                OP_CLOSE -> return null
            }
            if (op != 0) kind = op
            whole.write(payload)
            if (b0 and 0x80 != 0) return kind to whole.toByteArray()
        }
    }

    private fun need(i: BufferedInputStream, n: Int): ByteArray {
        val b = ByteArray(n)
        var off = 0
        while (off < n) {
            val r = i.read(b, off, n - off)
            if (r < 0) throw EOFException("the connection ended inside a message")
            off += r
        }
        return b
    }

    /** A client's frames are masked. Called from the reading thread (start, pong) and the microphone thread. */
    private fun send(op: Int, payload: ByteArray) {
        val o = out ?: return
        val mask = ByteArray(4).also { random.nextBytes(it) }
        val n = payload.size
        val frame = ByteArrayOutputStream(n + 14)
        frame.write(0x80 or op)
        when {
            n < 126 -> frame.write(0x80 or n)
            n < 65536 -> { frame.write(0x80 or 126); frame.write(n shr 8); frame.write(n and 0xFF) }
            else -> { frame.write(0x80 or 127); for (k in 7 downTo 0) frame.write(((n.toLong() shr (8 * k)) and 0xFF).toInt()) }
        }
        frame.write(mask)
        for (k in 0 until n) frame.write(payload[k].toInt() xor mask[k % 4].toInt())
        synchronized(o) { o.write(frame.toByteArray()); o.flush() }
    }

    companion object {
        private const val TAG = "ToolusGuide"
        private const val RATE = 16000
        /** 0.1 s of sound. */
        private const val PIECE = 3200
        private const val MAX_MS = 30_000L
        private const val LAST_WORDS_MS = 8_000L
        private const val MAX_MESSAGE = 1_000_000L
        private const val OP_TEXT = 1
        private const val OP_BINARY = 2
        private const val OP_CLOSE = 8
        private const val OP_PING = 9
        private const val OP_PONG = 10
    }
}
