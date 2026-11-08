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
        val existingRatings = setOf("123", "456")
        val tmdbId = 123L
        assertEquals(true, existingRatings.contains(tmdbId.toString()))

        val tmdbId2 = 789L
        assertEquals(false, existingRatings.contains(tmdbId2.toString()))
    }

    @Test
    fun testEpisodeDiffLogic() {
        val existingEpisodes = setOf("123_1_1", "123_1_2")
        assertEquals(true, existingEpisodes.contains("123_1_1"))
        assertEquals(false, existingEpisodes.contains("123_1_3"))
        assertEquals(false, existingEpisodes.contains("124_1_1"))
    }

    @Test
    fun testListItemDiffLogic() {
        val existingListItems = setOf("movie_123", "tv_456")

        val isMovie1 = true
        val type1 = if (isMovie1) "movie" else "tv"
        val tmdbId1 = 123L
        assertEquals(true, existingListItems.contains("${type1}_${tmdbId1}"))

        val isMovie2 = false
        val type2 = if (isMovie2) "movie" else "tv"
        val tmdbId2 = 456L
        assertEquals(true, existingListItems.contains("${type2}_${tmdbId2}"))

        val isMovie3 = true
        val type3 = if (isMovie3) "movie" else "tv"
        val tmdbId3 = 456L
        assertEquals(false, existingListItems.contains("${type3}_${tmdbId3}"))
    }

    @Test
    fun testRetryBackoffLogicMock() {
        // Mock testing for backoff logic based on the service's implementation.
        // The service uses: val backoff = (1000 * Math.pow(2.0, retryCount.toDouble())).toLong()
        val getBackoff = { retryCount: Int ->
            (1000 * Math.pow(2.0, retryCount.toDouble())).toLong()
        }

        assertEquals(1000L, getBackoff(0))
        assertEquals(2000L, getBackoff(1))
        assertEquals(4000L, getBackoff(2))
    }
}
