package com.jaewook.d4timer

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import java.net.HttpURLConnection
import java.net.URL
import java.time.Instant
import java.time.OffsetDateTime

/**
 * Pulls upcoming event times from community trackers and stores them as anchors.
 * The local cycle math stays the fallback, so a dead or changed site only means "not synced".
 * The response format isn't documented, so parsing looks for any object under a
 * boss/legion/helltide key that carries a recognizable time field.
 */
object Sync {
    // JSON endpoints first; the HTML page is only used for boss names and zones
    private val SOURCES = listOf(
        "https://helltides.com/api/schedule",
        "https://helltides.com/api/worldboss",
        "https://helltides.com/worldboss",
    )
    private val TIME_KEYS = listOf(
        "startTime", "start_time", "start", "spawnTime", "spawn_time", "expected", "timestamp", "time", "date",
    )
    private const val SNIPPET = 1500
    private const val BROWSER_UA =
        "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0 Mobile Safari/537.36"

    data class Found(val key: String, val time: Long, val name: String?, val zone: String?)

    // Season info and the emergency schedule override; a daily GitHub job keeps it current
    private const val CONFIG_URL = "https://raw.githubusercontent.com/jasonyoo1985/d4-boss-alarm/main/season.json"

    /** Keeps the last good copy; any failure leaves it untouched and is never shown to the user. */
    private fun fetchConfig(context: Context) {
        try {
            val (code, body) = fetch(CONFIG_URL)
            if (code !in 200..299) return
            JSONObject(body).getJSONObject("season")
            val prefs = Prefs(context)
            val changed = prefs.remoteConfig() != body
            prefs.setRemoteConfig(body)
            if (changed) Alarms.scheduleAll(context)
        } catch (_: Exception) {
        }
    }

    /** Blocking; call off the main thread. Returns true when at least one event was updated. */
    fun run(context: Context): Boolean {
        fetchConfig(context)
        val prefs = Prefs(context)
        val log = StringBuilder()
        val notes = mutableListOf<String>()
        var anchored = false
        var named = false
        for (url in SOURCES) {
            val (code, body) = try {
                fetch(url)
            } catch (e: Exception) {
                log.append("[$url] 실패: ${e.javaClass.simpleName} ${e.message ?: ""}\n\n")
                notes += "${path(url)} 연결 실패"
                continue
            }
            val html = body.trimStart().startsWith("<")
            log.append("[$url] HTTP $code, ${body.length}자, ${if (html) "HTML" else "JSON?"}\n")
            log.append(if (html) htmlSnippet(body) else body.take(SNIPPET)).append("\n\n")
            if (code !in 200..299) {
                notes += "${path(url)} HTTP $code"
                continue
            }
            val found = try {
                if (html) parseHtml(body, prefs) else parse(body)
            } catch (e: Exception) {
                notes += "${path(url)} 형식 오류"
                continue
            }
            if (found.isEmpty()) {
                notes += "${path(url)} 일정 없음"
                continue
            }
            if (html) {
                // Page times have no timezone, so they only label starts already on our cycle
                if (!named && saveBossList(prefs, found)) named = true
            } else {
                val applied = apply(context, found)
                if (applied.isNotEmpty()) {
                    anchored = true
                    named = true
                    notes += "${path(url)}에서 일정을 맞췄어요 (" + applied.joinToString(", ") + ")"
                    break
                }
                notes += "${path(url)} 일정 없음"
            }
        }
        prefs.setSyncRaw(log.toString())
        val ok = anchored || named
        val msg = when {
            anchored -> notes.last()
            named -> "보스 이름·지역만 받아왔어요 (시간은 자체 계산)"
            else -> notes.joinToString(" / ")
        }
        prefs.setSyncResult(ok, msg)
        if (anchored) Alarms.scheduleAll(context)
        return ok
    }

    private fun path(url: String) = URL(url).let { it.host + it.path }

    private fun fetch(url: String): Pair<Int, String> {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 10_000
        conn.readTimeout = 10_000
        conn.instanceFollowRedirects = true
        conn.setRequestProperty("Accept", "application/json, text/html;q=0.9")
        conn.setRequestProperty("User-Agent", BROWSER_UA)
        try {
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val body = stream?.bufferedReader()?.use { it.readText() } ?: ""
            return code to body
        } finally {
            conn.disconnect()
        }
    }

    private fun stripTags(html: String) = html
        .replace(Regex("(?s)<script.*?</script>|<style.*?</style>"), " ")
        .replace(Regex("<[^>]+>"), " ")
        .replace("&amp;", "&").replace("&#x27;", "'").replace("&#39;", "'")
        .replace(Regex("\\s+"), " ")

    private fun htmlSnippet(html: String): String {
        val scripts = Regex("<script([^>]*)>").findAll(html).map { it.groupValues[1].trim() }
            .filter { it.isNotEmpty() }.take(12).joinToString(" | ")
        val text = stripTags(html)
        // Include the part above the list too; the next boss is shown there
        val i = (text.indexOf("Upcoming").takeIf { it >= 0 } ?: 0) - 600
        val data = Regex("(?s)<script[^>]*__NUXT_DATA__[^>]*>(.*?)</script>").find(html)?.groupValues?.get(1)
        val peek = data?.let { d ->
            val j = Regex("Wandering Death|Avarice|Ashava|Azmodan").find(d)?.range?.first ?: 0
            "\nnuxt(${d.length}자): " + d.substring((j - 700).coerceAtLeast(0)).take(1400)
        } ?: ""
        return "scripts: $scripts\ntext: " + text.substring(i.coerceAtLeast(0)).take(SNIPPET + 600) + peek
    }

    private val PAGE_ENTRY = Regex(
        "(\\d{1,2})/(\\d{1,2})/(\\d{4}),? (\\d{1,2}):(\\d{2}) ?([AP]M)\\s+([A-Za-z' ]+?)\\s+in\\s+(Fractured Peaks|Dry Steppes|[A-Z][A-Za-z]+)",
    )

    private val NEXT_NAME = Regex("([A-Z][A-Za-z']+(?: [A-Z][A-Za-z']+)?) [Ss]pawns [Ii]n")

    /** Reads embedded JSON if present, else the "MM/DD/YYYY h:mm AM Boss in Zone" list. */
    private fun parseHtml(html: String, prefs: Prefs): List<Found> {
        val out = mutableListOf<Found>()
        val boss = prefs.effective(Schedule.byKey("boss"))
        Regex("(?s)<script[^>]*(?:__NEXT_DATA__|__NUXT_DATA__|application/json)[^>]*>(.*?)</script>").findAll(html).forEach {
            val raw = it.groupValues[1]
            runCatching { out += parse(raw) }
            // Nuxt stores its state flattened (values point at each other by index); rebuild it first
            runCatching { out += walkAll(Devalue.decode(raw)) }
        }
        // Keep only named world bosses that land on the cycle, so stray dates in page state are ignored
        val onCycle = out.mapNotNull { f ->
            val snapped = boss.anchor + Math.round((f.time - boss.anchor).toDouble() / boss.period) * boss.period
            f.copy(time = snapped).takeIf {
                f.key == "boss" && !f.name.isNullOrBlank() && Math.abs(f.time - snapped) <= 5 * MIN
            }
        }
        out.clear()
        out += onCycle
        for (m in PAGE_ENTRY.findAll(stripTags(html))) {
            val (mo, d, y, h, mi, ap, name, zone) = m.destructured
            var hour = h.toInt() % 12
            if (ap == "PM") hour += 12
            val utc = java.util.Calendar.getInstance(java.util.TimeZone.getTimeZone("UTC")).apply {
                clear()
                set(y.toInt(), mo.toInt() - 1, d.toInt(), hour, mi.toInt())
            }.timeInMillis
            // Offsets 7 hours apart both land on a 3.5h cycle, so try UTC and the phone's zone first
            val local = java.util.TimeZone.getDefault().getOffset(utc) / (60 * MIN).toInt()
            val offsets = (listOf(0, local) + (-12..14).sortedBy { Math.abs(it) }).distinct()
            val t = offsets.map { utc - it * 60 * MIN }.firstOrNull { Math.floorMod(it - boss.anchor, boss.period) == 0L }
                ?: continue
            out += Found("boss", t, name.trim(), zone.trim())
        }
        // The very next boss is shown separately above the list, so the list starts one spawn later
        val next = Schedule.nextStart(boss, System.currentTimeMillis())
        if (out.none { it.time == next }) nextBoss(stripTags(html), out)?.let { (name, zone) ->
            out += Found("boss", next, name, zone)
        }
        return out
    }

    private val KNOWN_BOSSES = listOf("Wandering Death", "Avarice", "Ashava", "Azmodan")
    private val ZONES = "Fractured Peaks|Dry Steppes|Scosglen|Kehjistan|Hawezar|Nahantu|Skovos"

    /** Finds the first boss name in the text before the upcoming list, plus a zone right after it. */
    private fun nextBoss(text: String, listed: List<Found>): Pair<String, String?>? {
        NEXT_NAME.find(text)?.let { return it.groupValues[1].trim() to null }
        val names = (KNOWN_BOSSES + listed.mapNotNull { it.name }).distinct()
        // Only look just above the list, and take the closest name, to skip menus and intro text
        val end = PAGE_ENTRY.find(text)?.range?.first ?: return null
        val head = text.substring((end - 600).coerceAtLeast(0), end)
        val hit = names.mapNotNull { n -> Regex("\\b" + Regex.escape(n) + "\\b").findAll(head).lastOrNull()?.let { it.range.first to n } }
            .maxByOrNull { it.first } ?: return null
        val zone = Regex("^.{0,80}?($ZONES)").find(head.substring(hit.first + hit.second.length))?.groupValues?.get(1)
        return hit.second to zone
    }

    private fun saveBossList(prefs: Prefs, found: List<Found>): Boolean {
        val now = System.currentTimeMillis()
        val fresh = found.filter { it.key == "boss" && it.time > now - 15 * MIN && !it.name.isNullOrBlank() }
        if (fresh.isEmpty()) return false
        // The page drops the next boss from its list, so keep names learned by earlier syncs
        val old = runCatching { JSONArray(prefs.bossList()) }.getOrNull()
        val kept = (0 until (old?.length() ?: 0)).mapNotNull { i ->
            old!!.optJSONObject(i)?.let { Found("boss", it.optLong("t"), it.optString("name"), it.optString("zone")) }
        }.filter { it.time > now - 15 * MIN && !it.name.isNullOrBlank() }
        val list = JSONArray()
        (fresh + kept).sortedBy { it.time }.distinctBy { it.time }.take(12)
            .forEach { list.put(JSONObject().put("t", it.time).put("name", it.name ?: "").put("zone", it.zone ?: "")) }
        prefs.setBossList(list.toString())
        return true
    }

    private fun walkAll(node: Any?): List<Found> {
        val out = mutableListOf<Found>()
        walk(node, "", out)
        return out
    }

    fun parse(text: String): List<Found> {
        val out = mutableListOf<Found>()
        walk(JSONTokener(text).nextValue(), "", out)
        return out
    }

    private fun walk(node: Any?, path: String, out: MutableList<Found>) {
        when (node) {
            is JSONObject -> {
                val key = classify(path + " " + node.optString("type") + " " + node.optString("event"))
                val time = timeOf(node)
                if (key != null && time != null) {
                    out += Found(key, time, nameOf(node), zoneOf(node))
                }
                for (k in node.keys()) walk(node.opt(k), "$path/$k", out)
            }
            is JSONArray -> for (i in 0 until node.length()) walk(node.opt(i), path, out)
        }
    }

    private fun classify(s: String): String? {
        val l = s.lowercase()
        return when {
            "helltide" in l -> "tide"
            "legion" in l -> "legion"
            "boss" in l -> "boss"
            else -> null
        }
    }

    private fun timeOf(o: JSONObject): Long? {
        for (k in TIME_KEYS) {
            if (!o.has(k)) continue
            parseTime(o.opt(k))?.let { return it }
        }
        return null
    }

    private fun parseTime(v: Any?): Long? = when (v) {
        is Number -> v.toLong().let { if (it > 100_000_000_000L) it else it * 1000 }.takeIf { plausible(it) }
        is String -> v.trim().toLongOrNull()?.let { parseTime(it) }
            ?: runCatching { Instant.parse(v).toEpochMilli() }.getOrNull()
            ?: runCatching { OffsetDateTime.parse(v).toInstant().toEpochMilli() }.getOrNull()
        else -> null
    }?.takeIf { plausible(it) }

    // Ignore numbers that can't be event times (ids, counters)
    private fun plausible(t: Long) = t in 1_700_000_000_000L..4_000_000_000_000L

    private fun nameOf(o: JSONObject): String? =
        listOf("boss", "name", "bossName").firstNotNullOfOrNull { k -> o.optString(k).takeIf { it.isNotBlank() && !it.startsWith("{") } }

    private fun zoneOf(o: JSONObject): String? {
        val z = o.opt("zone") ?: o.opt("territory") ?: o.opt("location") ?: return null
        return when (z) {
            is JSONArray -> (0 until z.length()).mapNotNull { i ->
                when (val x = z.opt(i)) {
                    is JSONObject -> x.optString("name").takeIf { it.isNotBlank() }
                    is String -> x
                    else -> null
                }
            }.joinToString(", ").takeIf { it.isNotBlank() }
            is JSONObject -> z.optString("name").takeIf { it.isNotBlank() }
            is String -> z.takeIf { it.isNotBlank() }
            else -> null
        }
    }

    /** Stores anchors and the world boss list; returns the names of events that were updated. */
    private fun apply(context: Context, found: List<Found>): List<String> {
        val prefs = Prefs(context)
        val now = System.currentTimeMillis()
        val applied = mutableListOf<String>()
        for (e in Schedule.events) {
            val mine = found.filter { it.key == e.key }.sortedBy { it.time }
            if (mine.isEmpty()) continue
            // Any start on the cycle works as an anchor; prefer the next upcoming one
            val anchor = mine.firstOrNull { it.time > now - e.duration } ?: mine.last()
            prefs.setSyncAnchor(e, anchor.time)
            applied += e.name
            if (e.key == "boss") {
                val list = JSONArray()
                mine.filter { it.time > now - e.duration }.distinctBy { it.time }.take(12).forEach {
                    list.put(JSONObject().put("t", it.time).put("name", it.name ?: "").put("zone", it.zone ?: ""))
                }
                prefs.setBossList(list.toString())
            }
        }
        return applied
    }
}
