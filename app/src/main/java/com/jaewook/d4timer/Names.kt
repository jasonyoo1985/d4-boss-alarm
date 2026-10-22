package com.jaewook.d4timer

/** Korean names for bosses and zones; mirrors the KO map in index.html. */
object Names {
    private val KO = mapOf(
        "wandering death" to "방랑하는 죽음", "avarice" to "아바리스", "ashava" to "아샤바", "azmodan" to "아즈모단",
        "fractured peaks" to "조각난 봉우리", "scosglen" to "스코스글렌", "dry steppes" to "메마른 평원",
        "kehjistan" to "케지스탄", "hawezar" to "하웨자르", "nahantu" to "나한투", "skovos" to "스코보스",
    )

    fun ko(s: String) = s.split(Regex("\\s*,\\s*")).filter { it.isNotBlank() }
        .joinToString(", ") { KO[it.lowercase().removePrefix("the ")] ?: it }
}
