package com.streamflixreborn.streamflix.kids

import com.streamflixreborn.streamflix.utils.TMDb3
import java.util.Locale

internal object KidPolicy {
    private val movieRatings = setOf("G", "PG", "PG-13")
    private val tvRatings = setOf("TV-Y", "TV-Y7", "TV-Y7-FV", "TV-G", "TV-PG", "TV-14")
    private val missingRatings = setOf("", "NR", "NOT RATED", "UNRATED")
    private val excludedProducers = setOf(142470, 150378)
    private val brands = Regex(
        """moonbug|cocomelon|blippi|meekah|little baby bum|morphle|gecko.s garage|go buster|oddbods|little angel|ryan.s (world|toys|mystery)|pocket.?watch|kids diana|like nastya|vlad (and|&) niki|a for adley|vlad and nikita|diana and roma|ninja kidz|hobbykids""",
        RegexOption.IGNORE_CASE
    )
    private val formats = Regex(
        """\bunbox(?:ing|es)?\b|\btoy[ -]?(?:play|reviews?)\b|\b(?:child|family) influencers?\b|\b(?:family|kids?) vlog(?:s|gers?|ging)?\b|\bkidfluencers?\b|\bkid(?:s)? influencers?\b|\blet.s play videos?\b|\bgameplay (?:videos?|channel)\b""",
        RegexOption.IGNORE_CASE
    )

    fun excluded(title: String, overview: String?, companies: List<TMDb3.Company>): Boolean {
        return brands.containsMatchIn(title) || formats.containsMatchIn(title) ||
            formats.containsMatchIn(overview.orEmpty()) ||
            companies.any { it.id in excludedProducers || brands.containsMatchIn(it.name.orEmpty()) }
    }

    fun allowedRatings(ratings: List<String>, movie: Boolean, pbs: Boolean = false, favorite: Boolean = false): Boolean {
        val known = ratings.map { it.trim().uppercase(Locale.US) }.filterNot { it in missingRatings }
        if (known.isEmpty()) return pbs || favorite
        return known.all { rating ->
            if (movie) rating in movieRatings || (pbs && rating in tvRatings) else rating in tvRatings
        }
    }
}
