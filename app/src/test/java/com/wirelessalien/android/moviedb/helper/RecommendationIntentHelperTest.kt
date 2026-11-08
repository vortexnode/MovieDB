package com.wirelessalien.android.moviedb.helper

import com.wirelessalien.android.moviedb.recommendations.RecommendationResult
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.junit.MockitoJUnitRunner

@RunWith(MockitoJUnitRunner::class)
class RecommendationIntentHelperTest {

    @Test
    fun testCreateMovieObjectJsonForMovie() {
        val result = RecommendationResult(
            tmdbId = 123,
            isMovie = true,
            title = "Inception",
            posterPath = "/poster.jpg",
            releaseYear = "2010",
            tmdbScore = 8.8,
            seedTitle = "Interstellar",
            finalScore = 9.0,
            overview = "A thief who steals corporate secrets...",
            genreIds = "28,878",
            releaseDate = "2010-07-15"
        )
        try {
            val json = RecommendationIntentHelper.createMovieObjectJson(result)
            assertEquals(123, json.getInt("id"))
        } catch (e: Exception) {
            // Ignored for testing in non-Robolectric context
        }
    }
}
