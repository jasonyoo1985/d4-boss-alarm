package com.jaewook.d4timer

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Clan promotion: a small list of clans the developer has approved, shown on the home screen and
 * when the app is opened from an alarm. Where to read and write it comes from season.json "clans":
 * {"url": "https://script.google.com/macros/s/.../exec"}
 * so the whole feature can be turned on, off or moved without an app update. With no "clans" entry,
 * or with nothing cached, every clan line stays hidden - the app must never look broken (see [Sync]).
 *
 * The server (a Google Apps Script on the developer's sheet) keeps the applications, the approved
 * clans, the first-come queue and the reports; the app only reads its answer and posts forms to it.
 * Nothing here identifies the user: an application carries the clan's own details, a report carries
 * only the clan id.
 */
object Clans {
    /** Links a clan may offer; anything else is dropped so a bad link can't be pushed to users. */
    private val ALLOWED = listOf("https://open.kakao.com/", "https://discord.gg/", "https://discord.com/invite/")

    fun cached(context: Context): JSONObject? =
        runCatching { JSONObject(Prefs(context).clans()) }.getOrNull()?.takeIf { it.optJSONArray("featured") != null }

    private fun url(context: Context): String? =
        runCatching { JSONObject(Prefs(context).remoteConfig()).getJSONObject("clans").getString("url") }
            .getOrNull()?.takeIf { it.startsWith("https://script.google.com/") }

    fun okLink(link: String) = ALLOWED.any { link.startsWith(it) }

    /**
     * Blocking; call off the main thread. Reads the clan list and keeps the last good answer.
     * A failure leaves what the phone already has, so a clan stays visible to the end of its run
     * even while the sheet is unreachable.
     */
    @Synchronized
    fun sync(context: Context) {
        val base = url(context) ?: return
        val body = runCatching { get("$base?a=list") }.getOrNull() ?: return
        val parsed = runCatching { JSONObject(body) }.getOrNull() ?: return
        if (parsed.optJSONArray("featured") == null) return
        Prefs(context).setClans(clean(parsed).toString())
        // A clan leader sees where their own application stands, including why it was turned down
        val token = Prefs(context).clanToken()
        if (token.isNotEmpty()) {
            runCatching { JSONObject(get("$base?a=mine&token=$token")!!) }.getOrNull()
                ?.takeIf { it.has("state") }?.let { Prefs(context).setClanMineStatus(it.toString()) }
        }
    }

    fun mineStatus(context: Context): JSONObject? = runCatching { JSONObject(Prefs(context).clanMineStatus()) }.getOrNull()

    /** Keeps only the fields the page uses and drops links that aren't an open chat or Discord invite. */
    private fun clean(o: JSONObject): JSONObject {
        for (key in listOf("featured", "list", "queue")) {
            val arr = o.optJSONArray(key) ?: continue
            for (i in 0 until arr.length()) {
                val c = arr.optJSONObject(i) ?: continue
                for (link in listOf("chat", "discord")) {
                    val v = c.optString(link)
                    if (v.isNotEmpty() && !okLink(v)) c.remove(link)
                }
            }
        }
        return o
    }

    /** A clan applies from inside the app. Returns the server's message, or null when it couldn't be sent. */
    fun apply(context: Context, form: JSONObject): String? {
        val base = url(context) ?: return null
        val token = Prefs(context).clanToken().ifEmpty {
            (1..12).map { TOKEN_CHARS.random() }.joinToString("").also { Prefs(context).setClanToken(it) }
        }
        val answer = send(base, "a=apply&token=$token&data=" + enc(form.toString())) ?: return null
        sync(context)
        return answer
    }

    /** Anyone can report a clan's text; the server counts them and hides a free clan at three. */
    fun report(context: Context, id: String, reason: String): String? {
        val base = url(context) ?: return null
        return send(base, "a=report&id=" + enc(id) + "&reason=" + enc(reason))
    }

    /** The developer's own screen: password-checked by the server, never stored in the app. */
    fun admin(context: Context, pw: String, action: String, id: String, extra: String): String? {
        val base = url(context) ?: return null
        val answer = send(base, "a=admin&pw=" + enc(pw) + "&do=" + enc(action) + "&id=" + enc(id) + "&v=" + enc(extra))
        if (answer != null && action != "list") sync(context)
        return answer
    }

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    private fun send(url: String, body: String): String? = runCatching {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 15_000
        conn.readTimeout = 15_000
        conn.requestMethod = "POST"
        conn.doOutput = true
        conn.instanceFollowRedirects = true
        conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8")
        try {
            conn.outputStream.use { it.write(body.toByteArray()) }
            if (conn.responseCode !in 200..299) return null
            conn.inputStream.bufferedReader().use { it.readText() }
        } finally {
            conn.disconnect()
        }
    }.getOrNull()

    private fun get(url: String): String? {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 15_000
        conn.readTimeout = 15_000
        conn.instanceFollowRedirects = true
        try {
            if (conn.responseCode !in 200..299) return null
            return conn.inputStream.bufferedReader().use { it.readText() }
        } finally {
            conn.disconnect()
        }
    }

    /**
     * The clan shown this time. The approved clans take turns so each gets a fair share of the
     * exposure, and the turn only moves on when the app is opened afresh.
     */
    fun featured(context: Context): JSONObject? {
        val list = cached(context)?.optJSONArray("featured") ?: return null
        val live = (0 until list.length()).mapNotNull { list.optJSONObject(it) }
            .filter { it.optLong("ends", Long.MAX_VALUE) > System.currentTimeMillis() }
        if (live.isEmpty()) return null
        val prefs = Prefs(context)
        return live[(prefs.clanTurn() % live.size + live.size) % live.size]
    }

    fun nextTurn(context: Context) = Prefs(context).setClanTurn(Prefs(context).clanTurn() + 1)

    private const val TOKEN_CHARS = "abcdefghijkmnpqrstuvwxyz23456789"

    /** Events the clans the user follows have posted, soonest first, for the schedule tab. */
    fun events(context: Context): JSONArray {
        val out = JSONArray()
        val cache = cached(context) ?: return out
        val following = Prefs(context).clanFollowing()
        val featuredId = featured(context)?.optString("id") ?: ""
        for (key in listOf("featured", "list")) {
            val arr = cache.optJSONArray(key) ?: continue
            for (i in 0 until arr.length()) {
                val c = arr.optJSONObject(i) ?: continue
                val id = c.optString("id")
                val mine = following.contains(id)
                val evs = c.optJSONArray("events") ?: continue
                for (j in 0 until evs.length()) {
                    val e = evs.optJSONObject(j) ?: continue
                    // Everyone sees the featured clan's next event; the rest only for clans the user follows
                    if (!mine && !(id == featuredId && j == 0)) continue
                    out.put(JSONObject(e.toString()).put("clan", c.optString("name")).put("id", id))
                }
            }
        }
        return out
    }
}
