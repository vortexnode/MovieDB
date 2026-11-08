package com.wirelessalien.android.moviedb.service

import org.junit.Test
import org.junit.Assert.assertEquals
import kotlin.math.round

class TmdbSyncServiceTest {

    @Test
    fun testRatingClampingAndRounding() {
        val clampAndRound = { rating: Double ->
            val clamped = rating.coerceIn(0.5, 10.0)
            round(clamped * 2) / 2.0
        }

        assertEquals(0.5, clampAndRound(0.0), 0.01)
        assertEquals(0.5, clampAndRound(0.4), 0.01)
        assertEquals(0.5, clampAndRound(0.5), 0.01)
        assertEquals(0.5, clampAndRound(0.7), 0.01)
        assertEquals(1.0, clampAndRound(0.8), 0.01)
        assertEquals(1.0, clampAndRound(1.0), 0.01)
        assertEquals(1.5, clampAndRound(1.3), 0.01)
        assertEquals(2.0, clampAndRound(1.8), 0.01)
        assertEquals(5.5, clampAndRound(5.6), 0.01)
        assertEquals(9.5, clampAndRound(9.4), 0.01)
        assertEquals(10.0, clampAndRound(9.8), 0.01)
        assertEquals(10.0, clampAndRound(10.0), 0.01)
        assertEquals(10.0, clampAndRound(15.0), 0.01)
    }

    @Test
    fun testDiffLogic() {
        val existingRatings = setOf(123L, 456L)
        val tmdbId = 123L
        assertEquals(true, existingRatings.contains(tmdbId))

        val tmdbId2 = 789L
        assertEquals(false, existingRatings.contains(tmdbId2))
    }
}
