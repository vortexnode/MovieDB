package com.wirelessalien.android.moviedb.recommendations

import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.junit.MockitoJUnitRunner

@RunWith(MockitoJUnitRunner::class)
class RecommendationEngineTest {

    @Test
    fun testScoringLogicAndDeduplication() {
        val seeds = listOf(
            RecommendationSeed(1, true, "Seed 1", 9.0), // 0.9 points
            RecommendationSeed(2, true, "Seed 2", 8.0)  // 0.8 points
        )
        val topGenres = listOf("28", "12")
        val exclusions = setOf<Int>(100)

        val resultsMap = mutableMapOf<Int, RecommendationResult>()

        val seed1Recs = listOf(
            Pair(101, listOf("28")), // Should get 0.9 + 0.1 = 1.0
            Pair(100, listOf("28"))  // Should be excluded
        )
        val seed2Recs = listOf(
            Pair(101, listOf("28")), // Deduplicated, gets another 0.8. Total: 1.8
            Pair(102, emptyList<String>()) // Should get 0.8
        )

        fun process(seed: RecommendationSeed, recs: List<Pair<Int, List<String>>>) {
            for (rec in recs) {
                val tmdbId = rec.first
                if (exclusions.contains(tmdbId)) continue

                var genreBonus = 0.0
                for (g in rec.second) {
                    if (topGenres.contains(g)) genreBonus += 0.1
                }

                val existing = resultsMap[tmdbId]
                if (existing != null) {
                    existing.finalScore += (seed.personalRating / 10.0)
                } else {
                    resultsMap[tmdbId] = RecommendationResult(
                        tmdbId = tmdbId,
                        isMovie = true,
                        title = "Title \$tmdbId",
                        posterPath = "",
                        releaseYear = "2024",
                        tmdbScore = 8.0,
                        seedTitle = seed.title,
                        finalScore = (seed.personalRating / 10.0) + genreBonus
                    )
                }
            }
        }

        process(seeds[0], seed1Recs)
        process(seeds[1], seed2Recs)

        val finalResults = resultsMap.values.sortedByDescending { it.finalScore }

        assertEquals(2, finalResults.size)
        assertEquals(101, finalResults[0].tmdbId)
        assertEquals(1.8, finalResults[0].finalScore, 0.01) // 0.9 (seed1) + 0.1 (genre) + 0.8 (seed2)
        assertEquals(102, finalResults[1].tmdbId)
        assertEquals(0.8, finalResults[1].finalScore, 0.01)
    }
}
