package eu.kanade.tachiyomi.animeextension.en.animeheaven

/**
 * Exact metadata matching only. Broad labels such as "Mature", "Ecchi",
 * "Adult Cast", "Age Transformation", and "School Life" are not explicit labels.
 */
internal object ContentFilter {
    private val blockedLabels = setOf(
        "hentai",
        "nsfw",
        "explicit",
        "erotica",
        "pornographic",
        "18+",
        "r18",
        "r18+",
        "r-18",
        "r-18+",
        "r 18",
        "r 18+",
        "adult only",
        "adults only",
    )

    fun isExplicit(tags: Collection<String>, ratings: Collection<String> = emptyList()): Boolean =
        (tags + ratings).any { normalize(it) in blockedLabels }

    private fun normalize(value: String): String = value.trim()
        .lowercase()
        .replace(Regex("[–—−]"), "-")
        .replace(Regex("\\s+"), " ")
        .replace(Regex("^r\\s*-\\s*18\\s*\\+$"), "r18+")
}
