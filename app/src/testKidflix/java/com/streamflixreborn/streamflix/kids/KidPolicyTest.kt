package com.streamflixreborn.streamflix.kids

import com.streamflixreborn.streamflix.utils.TMDb3
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class KidPolicyTest {
    @Test fun upperLimitsApplyToEveryKnownRating() {
        assertTrue(KidPolicy.allowedRatings(listOf("PG-13"), movie = true))
        assertTrue(KidPolicy.allowedRatings(listOf("TV-14"), movie = false))
        assertFalse(KidPolicy.allowedRatings(listOf("PG", "R"), movie = true))
        assertFalse(KidPolicy.allowedRatings(listOf("TV-MA"), movie = false, pbs = true))
        assertFalse(KidPolicy.allowedRatings(listOf("TV-14"), movie = true))
    }

    @Test fun unratedRequiresExplicitApproval() {
        assertFalse(KidPolicy.allowedRatings(listOf("NR"), movie = false))
        assertTrue(KidPolicy.allowedRatings(listOf("NR"), movie = false, pbs = true))
        assertTrue(KidPolicy.allowedRatings(emptyList(), movie = false, favorite = true))
    }

    @Test fun producerAndFormatExclusionsApplyRegardlessOfDistribution() {
        assertTrue(KidPolicy.excluded("CoComelon Lane", "", emptyList()))
        assertTrue(KidPolicy.excluded("New show", "", listOf(TMDb3.Company(142470))))
        assertTrue(KidPolicy.excluded("Mystery box", "Kids unboxing toys", emptyList()))
        assertFalse(KidPolicy.excluded("Sonic Prime", "An animated adventure based on a video game.", emptyList()))
        assertFalse(KidPolicy.excluded("Toy Story", "", emptyList()))
    }
}
