package com.jaewook.d4timer

import org.json.JSONArray
import org.json.JSONObject

/**
 * Decodes Nuxt's payload format (devalue): a flat JSON array where objects and arrays hold
 * indices of other entries, and arrays starting with a tag string mark special types.
 * Rebuilds it into plain JSONObject/JSONArray so the generic walker can read it.
 */
object Devalue {
    private const val MAX_DEPTH = 40

    @Synchronized
    fun decode(text: String): Any? {
        val flat = JSONArray(text)
        memo.clear()
        return resolve(flat, 0, 0, HashSet()).also { memo.clear() }
    }

    // Shared entries are rebuilt once; without this a large payload can blow up
    private val memo = HashMap<Int, Any?>()

    private fun resolve(flat: JSONArray, index: Int, depth: Int, seen: MutableSet<Int>): Any? {
        if (index < 0 || index >= flat.length() || depth > MAX_DEPTH) return null
        if (memo.containsKey(index)) return memo[index]
        if (!seen.add(index)) return null
        try {
            val out = when (val v = flat.opt(index)) {
                is JSONObject -> JSONObject().also { o ->
                    for (k in v.keys()) o.put(k, ref(flat, v.opt(k), depth, seen) ?: JSONObject.NULL)
                }
                is JSONArray -> {
                    val tag = v.opt(0) as? String
                    when (tag) {
                        null -> JSONArray().also { a ->
                            for (i in 0 until v.length()) a.put(ref(flat, v.opt(i), depth, seen) ?: JSONObject.NULL)
                        }
                        "Date" -> v.optString(1)
                        "Reactive", "ShallowReactive", "Ref", "ShallowRef", "NuxtError", "Island" ->
                            ref(flat, v.opt(1), depth, seen)
                        "Set" -> JSONArray().also { a ->
                            for (i in 1 until v.length()) a.put(ref(flat, v.opt(i), depth, seen) ?: JSONObject.NULL)
                        }
                        "Map" -> JSONObject().also { o ->
                            var i = 1
                            while (i + 1 < v.length()) {
                                val k = ref(flat, v.opt(i), depth, seen)
                                o.put(k.toString(), ref(flat, v.opt(i + 1), depth, seen) ?: JSONObject.NULL)
                                i += 2
                            }
                        }
                        else -> null
                    }
                }
                else -> v
            }
            memo[index] = out
            return out
        } finally {
            seen.remove(index)
        }
    }

    private fun ref(flat: JSONArray, v: Any?, depth: Int, seen: MutableSet<Int>): Any? =
        if (v is Int) resolve(flat, v, depth + 1, seen) else null
}
