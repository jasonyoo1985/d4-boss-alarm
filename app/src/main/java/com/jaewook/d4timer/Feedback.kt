package com.jaewook.d4timer

import android.content.Context
import android.os.Build
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Anonymous feedback sent to the developer's Google Form, with replies read back from a published sheet.
 * Where to send and where to read replies comes from season.json "feedback", so it can change without an update:
 * {"form": ".../formResponse", "stars": "entry.1", "text": "entry.2", "info": "entry.3", "code": "entry.4", "answers": "...output=csv"}
 * Each message carries a random code instead of anything personal; the developer answers by writing that code
 * and a reply in the published sheet. Unsent messages stay on the phone and go out on a later sync.
 */
object Feedback {
    private const val CODE_CHARS = "ABCDEFGHJKMNPQRSTUVWXYZ23456789"

    fun items(context: Context): JSONArray = Prefs(context).feedback()

    fun add(context: Context, stars: Int, text: String, version: String) {
        val list = items(context)
        val code = (1..5).map { CODE_CHARS.random() }.joinToString("")
        list.put(
            JSONObject().put("code", code).put("stars", stars.coerceIn(0, 5)).put("text", text.take(2000))
                .put("time", System.currentTimeMillis()).put("sent", false)
                .put("info", "앱 $version · Android ${Build.VERSION.RELEASE} · ${Build.MANUFACTURER} ${Build.MODEL}"),
        )
        Prefs(context).setFeedback(list)
    }

    /** The user has opened the feedback tab, so every reply counts as read. */
    fun markSeen(context: Context) {
        val list = items(context)
        for (i in 0 until list.length()) list.getJSONObject(i).put("seen", true)
        Prefs(context).setFeedback(list)
    }

    private fun config(context: Context): JSONObject? =
        runCatching { JSONObject(Prefs(context).remoteConfig()).getJSONObject("feedback") }.getOrNull()

    /** Blocking; call off the main thread. Sends what's waiting and picks up replies; failures just wait for next time. */
    @Synchronized
    fun sync(context: Context) {
        val cfg = config(context) ?: return
        send(context, cfg)
        readAnswers(context, cfg)
    }

    private fun send(context: Context, cfg: JSONObject) {
        val form = cfg.optString("form")
        if (!form.startsWith("https://docs.google.com/forms/")) return
        val list = items(context)
        var changed = false
        for (i in 0 until list.length()) {
            val item = list.getJSONObject(i)
            if (item.optBoolean("sent")) continue
            val fields = listOf(
                cfg.optString("stars") to (item.optInt("stars").takeIf { it > 0 }?.toString() ?: ""),
                cfg.optString("text") to item.optString("text"),
                cfg.optString("info") to item.optString("info"),
                cfg.optString("code") to item.optString("code"),
            ).filter { it.first.startsWith("entry.") }
            val body = fields.joinToString("&") { (k, v) -> k + "=" + URLEncoder.encode(v, "UTF-8") }
            val ok = runCatching { post(form, body) in 200..299 }.getOrDefault(false)
            if (!ok) break
            item.put("sent", true)
            changed = true
        }
        if (changed) Prefs(context).setFeedback(list)
    }

    private fun readAnswers(context: Context, cfg: JSONObject) {
        val url = cfg.optString("answers")
        if (!url.startsWith("https://docs.google.com/")) return
        val csv = runCatching { get(url) }.getOrNull() ?: return
        val answers = parseCsv(csv).filter { it.size >= 2 }
            .associate { it[0].trim().uppercase() to it[1].trim() }
            .filterValues { it.isNotEmpty() }
        val list = items(context)
        var changed = false
        for (i in 0 until list.length()) {
            val item = list.getJSONObject(i)
            val answer = answers[item.optString("code")] ?: continue
            if (item.optString("answer") == answer) continue
            item.put("answer", answer).put("seen", false)
            changed = true
        }
        if (changed) Prefs(context).setFeedback(list)
    }

    private fun post(url: String, body: String): Int {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 10_000
        conn.readTimeout = 10_000
        conn.requestMethod = "POST"
        conn.doOutput = true
        conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8")
        try {
            conn.outputStream.use { it.write(body.toByteArray()) }
            return conn.responseCode
        } finally {
            conn.disconnect()
        }
    }

    private fun get(url: String): String? {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 10_000
        conn.readTimeout = 10_000
        conn.instanceFollowRedirects = true
        try {
            if (conn.responseCode !in 200..299) return null
            return conn.inputStream.bufferedReader().use { it.readText() }
        } finally {
            conn.disconnect()
        }
    }

    /** Google's CSV export: quoted fields may hold commas, quotes ("") and line breaks. */
    private fun parseCsv(s: String): List<List<String>> {
        val rows = mutableListOf<List<String>>()
        var row = mutableListOf<String>()
        val cell = StringBuilder()
        var quoted = false
        var i = 0
        while (i < s.length) {
            val c = s[i]
            when {
                quoted && c == '"' && s.getOrNull(i + 1) == '"' -> { cell.append('"'); i++ }
                c == '"' -> quoted = !quoted
                !quoted && c == ',' -> { row.add(cell.toString()); cell.clear() }
                !quoted && (c == '\n' || c == '\r') -> {
                    if (c == '\r' && s.getOrNull(i + 1) == '\n') i++
                    row.add(cell.toString()); cell.clear(); rows.add(row); row = mutableListOf()
                }
                else -> cell.append(c)
            }
            i++
        }
        if (cell.isNotEmpty() || row.isNotEmpty()) { row.add(cell.toString()); rows.add(row) }
        return rows
    }
}
