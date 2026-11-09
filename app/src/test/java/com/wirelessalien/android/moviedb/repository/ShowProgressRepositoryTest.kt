package com.wirelessalien.android.moviedb.repository

import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mock
import org.mockito.Mockito.mock
import org.mockito.junit.MockitoJUnitRunner
import android.content.Context

@RunWith(MockitoJUnitRunner::class)
class ShowProgressRepositoryTest {

    @Test
    fun testSortItemsByName() {
        val repo = ShowProgressRepository(mock(Context::class.java))
        val items = listOf(
            createItem(title = "C"),
            createItem(title = "A"),
            createItem(title = "B")
        )
        val sorted = repo.sortItems(items, "name")
        assertEquals("A", sorted[0].title)
        assertEquals("B", sorted[1].title)
        assertEquals("C", sorted[2].title)
    }

    @Test
    fun testSortItemsByProgress() {
        val repo = ShowProgressRepository(mock(Context::class.java))
        val items = listOf(
            createItem(title = "A", watched = 1, total = 10), // 10%
            createItem(title = "B", watched = 9, total = 10), // 90%
            createItem(title = "C", watched = 5, total = 10)  // 50%
        )
        val sorted = repo.sortItems(items, "progress")
        assertEquals("B", sorted[0].title)
        assertEquals("C", sorted[1].title)
        assertEquals("A", sorted[2].title)
    }

    @Test
    fun testSortItemsByNextAirDate() {
        val repo = ShowProgressRepository(mock(Context::class.java))
        val items = listOf(
            createItem(title = "A", airDate = "2023-11-15"),
            createItem(title = "B", airDate = null),
            createItem(title = "C", airDate = "2023-11-10")
        )
        val sorted = repo.sortItems(items, "next_air_date")
        assertEquals("C", sorted[0].title)
        assertEquals("A", sorted[1].title)
        assertEquals("B", sorted[2].title) // nulls last
    }

    @Test
    fun testSortItemsByRecentlyWatched() {
        val repo = ShowProgressRepository(mock(Context::class.java))
        val items = listOf(
            createItem(title = "A", lastWatched = "2023-10-01"),
            createItem(title = "B", lastWatched = "2023-10-15"),
            createItem(title = "C", lastWatched = null)
        )
        val sorted = repo.sortItems(items, "recently_watched")
        assertEquals("B", sorted[0].title)
        assertEquals("A", sorted[1].title)
        assertEquals("C", sorted[2].title) // nulls last
    }


    @Test
    fun testGetWatchingShowsProgressCalculation() {
        val repo = ShowProgressRepository(mock(Context::class.java))
        val item = createItem(title = "Test", watched = 5, total = 10)
        assertEquals(5, item.watchedEpisodes)
        assertEquals(10, item.totalEpisodes)
    }

    @Test
    fun testNextEpisodeResolution() {
        val repo = ShowProgressRepository(mock(Context::class.java))
        val item = createItem(title = "Test", watched = 5, total = 10, airDate = "2023-10-20")
        assertEquals(1, item.nextEpisodeSeason)
        assertEquals(6, item.nextEpisodeNumber)
        assertEquals("2023-10-20", item.nextEpisodeAirDate)
    }

    private fun createItem(title: String, watched: Int = 0, total: Int = 10, airDate: String? = null, lastWatched: String? = null): ShowProgressItem {
        return ShowProgressItem(
            id = 1,
            tmdbId = 1,
            title = title,
            posterPath = "",
            watchedEpisodes = watched,
            totalEpisodes = total,
            seasonsCount = 1,
            nextEpisodeSeason = 1,
            nextEpisodeNumber = watched + 1,
            nextEpisodeTitle = "Ep",
            nextEpisodeAirDate = airDate,
            lastWatchedDate = lastWatched
        )
    }
}
