package com.wirelessalien.android.moviedb.helper

import android.content.Context
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.mockito.Mockito.mock

class CalendarDataHelperTest {

    private lateinit var helper: CalendarDataHelper

    @Before
    fun setup() {
        val context = mock(Context::class.java)
        helper = CalendarDataHelper(context)
    }

    @Test
    fun testDeduplicateItems() {
        val items = listOf(
            CalendarItem(type = "episode", tmdbId = 1, season = 1, episode = 1, title = "A", releaseDate = "2024-01-01", posterPath = ""),
            CalendarItem(type = "episode", tmdbId = 1, season = 1, episode = 1, title = "B", releaseDate = "2024-01-01", posterPath = "path/to/poster"), // Should override because it has poster
            CalendarItem(type = "movie", tmdbId = 2, title = "C", releaseDate = "2024-01-01", posterPath = ""),
            CalendarItem(type = "movie", tmdbId = 2, title = "D", releaseDate = "2024-01-01", posterPath = ""),
            CalendarItem(type = "season", tmdbId = 3, season = 2, title = "E", releaseDate = "2024-01-01", posterPath = ""),
            CalendarItem(type = "season", tmdbId = 3, season = 2, title = "F", releaseDate = "2024-01-01", posterPath = "")
        )

        val result = helper.deduplicateData(items)
        assertEquals(3, result.size)
        assertEquals(1, result.count { it.type == "episode" && it.tmdbId == 1 && it.posterPath == "path/to/poster" })
        assertEquals(1, result.count { it.type == "movie" && it.tmdbId == 2 })
        assertEquals(1, result.count { it.type == "season" && it.tmdbId == 3 })
    }

    @Test
    fun testGroupItemsByDay() {
        val items = listOf(
            CalendarItem(type = "movie", releaseDate = "2024-11-15", tmdbId = 1, title = "", posterPath = ""),
            CalendarItem(type = "episode", releaseDate = "2024-11-15T12:00:00Z", tmdbId = 2, title = "", posterPath = ""),
            CalendarItem(type = "season", releaseDate = "2024-11-16", tmdbId = 3, title = "", posterPath = ""),
            CalendarItem(type = "movie", releaseDate = "", tmdbId = 4, title = "", posterPath = "") // Should be ignored in grouping
        )

        val grouped = helper.groupItemsByDay(items)
        assertEquals(2, grouped.keys.size)
        assertEquals(2, grouped["2024-11-15"]?.size)
        assertEquals(1, grouped["2024-11-16"]?.size)
    }
}
