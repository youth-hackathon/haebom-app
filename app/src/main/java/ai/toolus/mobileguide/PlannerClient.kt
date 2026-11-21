package ai.toolus.mobileguide

import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * The guide server (haebom-artemis, [GuideSettings.guideBase]): the screen goes out, one action comes back
 * (docs/interface.md). The reply carries one ordinary artemis action plus `explain`; the phone routes the action
 * to a guide pair ([GuideCatalog]) and never performs it.
 */
object PlannerClient {
    data class PlannerReply(
        val explain: String,
        val action: GuideCatalog.Action,
        val latencyMs: Long,
        /** Who answered, as the server says in `meta.source` ("manual", "check", "wait", "model"); "" when it does not say. */
        val source: String = "",
    )

    /** The server answers `GET /health` with 200 within 1.5 s. */
    fun health(): Boolean = try {
        val c = URL("${GuideSettings.guideBase}/health").openConnection() as HttpURLConnection
        c.connectTimeout = 1500
        c.readTimeout = 1500
        val ok = c.responseCode == 200
        c.disconnect()
        ok
    } catch (e: Exception) {
        false
    }

    fun next(request: JSONObject): PlannerReply {
        val started = System.currentTimeMillis()
        val j = post(request)
        val a = j.optJSONObject("action") ?: JSONObject().put("name", "ask_user")
        val args = HashMap<String, Any?>()
        a.keys().forEach { k -> if (k != "name") args[k] = unwrap(a.get(k)) }
        return PlannerReply(
            explain = j.optString("explain"),
            action = GuideCatalog.Action(a.optString("name", "ask_user"), args),
            latencyMs = System.currentTimeMillis() - started,
            source = j.optJSONObject("meta")?.optString("source").orEmpty(),
        )
    }

    private fun post(request: JSONObject): JSONObject {
        val c = URL("${GuideSettings.guideBase}/v1/guide/next").openConnection() as HttpURLConnection
        c.requestMethod = "POST"
        c.connectTimeout = 3000
        c.readTimeout = 120_000
        c.doOutput = true
        c.setRequestProperty("Content-Type", "application/json; charset=utf-8")
        c.outputStream.use { it.write(request.toString().toByteArray(Charsets.UTF_8)) }
        val code = c.responseCode
        val body = (if (code in 200..299) c.inputStream else c.errorStream)?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: ""
        c.disconnect()
        if (code !in 200..299) throw IllegalStateException("guide server $code: ${body.take(200)}")
        return JSONObject(body)
    }

    private fun unwrap(v: Any?): Any? = when (v) {
        JSONObject.NULL -> null
        is JSONArray -> (0 until v.length()).map { unwrap(v.get(it)) }
        else -> v
    }
}
