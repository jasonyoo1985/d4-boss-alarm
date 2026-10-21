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
    private val SOURCES = listOf(
        "https://helltides.com/api/schedule",
        "https://d4armory.io/api/events/recent",
    )
    private val TIME_KEYS = listOf(
        "startTime", "start_time", "start", "spawnTime", "spawn_time", "expected", "timestamp", "time", "date",
    )
    private const val RAW_LIMIT = 6000

    data class Found(val key: String, val time: Long, val name: String?, val zone: String?)

    /** Blocking; call off the main thread. Returns true when at least one event was updated. */
    fun run(context: Context): Boolean {
        val prefs = Prefs(context)
        val errors = mutableListOf<String>()
        for (url in SOURCES) {
            val text = try {
                fetch(url)
            } catch (e: Exception) {
                errors += "${host(url)}: ${e.javaClass.simpleName} ${e.message ?: ""}".trim()
                continue
            }
            prefs.setSyncRaw("[$url]\n" + text.take(RAW_LIMIT))
            val found = try {
                parse(text)
            } catch (e: Exception) {
                errors += "${host(url)}: 형식을 읽지 못함 (${e.javaClass.simpleName})"
                continue
            }
            val applied = apply(context, found)
            if (applied.isNotEmpty()) {
                prefs.setSyncResult(true, "${host(url)} · " + applied.joinToString(", "))
                Alarms.scheduleAll(context)
                return true
            }
            errors += "${host(url)}: 일정 정보를 찾지 못함"
        }
        prefs.setSyncResult(false, errors.joinToString(" / "))
        return false
    }

    private fun host(url: String) = URL(url).host

    private fun fetch(url: String): String {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 10_000
        conn.readTimeout = 10_000
        conn.setRequestProperty("Accept", "application/json")
        conn.setRequestProperty("User-Agent", "D4BossAlarm/2 (Android)")
        try {
            val code = conn.responseCode
            if (code !in 200..299) throw IllegalStateException("HTTP $code")
            return conn.inputStream.bufferedReader().use { it.readText() }
        } finally {
            conn.disconnect()
        }
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
