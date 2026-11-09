package com.wirelessalien.android.moviedb.helper

import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.TimeZone
import android.content.Context
import org.mockito.Mockito.mock

class CalendarDataHelperTest {

    @Test
    fun testGetLocalDateString_withTimezone() {
        val mockContext = mock(Context::class.java)
        val dataHelper = CalendarDataHelper(mockContext)
        // Given UTC time 23:30, timezone +03:00 -> Next day 02:30
        TimeZone.setDefault(TimeZone.getTimeZone("GMT+03:00"))
        val isoStr = "2026-11-10T23:30:00Z"
        val localDate = dataHelper.getLocalDateString(isoStr)
        assertEquals("2026-11-11", localDate)
    }

    @Test
    fun testGetLocalDateString_dateOnly() {
        val mockContext = mock(Context::class.java)
        val dataHelper = CalendarDataHelper(mockContext)
        val dateOnly = "2026-11-10"
        val localDate = dataHelper.getLocalDateString(dateOnly)
        assertEquals("2026-11-10", localDate)
    }

    @Test
    fun testGroupItemsByDay_mixedList() {
        val mockContext = mock(Context::class.java)
        val dataHelper = CalendarDataHelper(mockContext)
        TimeZone.setDefault(TimeZone.getTimeZone("GMT+03:00"))
        val items = listOf(
            CalendarItem("movie", 1, "Movie 1", "2026-11-10T23:30:00Z", ""), // Maps to 2026-11-11
            CalendarItem("episode", 2, "Show 1", "2026-11-11", ""), // Maps to 2026-11-11
            CalendarItem("season", 3, "Show 2", "2026-11-15", "") // Maps to 2026-11-15
        )

        val grouped = dataHelper.groupItemsByDay(items)
        assertEquals(2, grouped.size)
        assertEquals(2, grouped["2026-11-11"]?.size)
        assertEquals(1, grouped["2026-11-15"]?.size)
    }
}
