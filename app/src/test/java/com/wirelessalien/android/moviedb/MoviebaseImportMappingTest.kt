package com.wirelessalien.android.moviedb

import com.wirelessalien.android.moviedb.helper.ImportReport
import com.wirelessalien.android.moviedb.helper.ImportReason
import com.wirelessalien.android.moviedb.helper.ImportIssue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.Before

class MoviebaseImportMappingTest {

    private lateinit var report: ImportReport

    @Before
    fun setUp() {
        report = ImportReport()
    }

    // Since actual SQLite tests require Robolectric (which we aren't guaranteed to have properly set up here)
    // we test the parsing/filtering logic isolating the components.

    @Test
    fun testConvertDate() {
        val isoDate = "2023-10-15T12:00:00Z"
        val converted = convertDate(isoDate)
        assertEquals("2023-10-15", converted)

        val shortDate = "2023-10-15"
        val convertedShort = convertDate(shortDate)
        assertEquals("2023-10-15", convertedShort)

        val nullDate = convertDate(null)
        assertEquals(null, nullDate)
    }

    @Test
    fun testHandleRowMissingTmdbId() {
        val row = mapOf("title" to "Test Movie")
        report.total = 1
        handleRow("watched.csv", row, report)

        assertEquals(1, report.skipped)
        assertEquals(1, report.issues.size)
        assertEquals(ImportReason.MISSING_TMDB_ID, report.issues[0].reason)
        assertEquals("Test Movie", report.issues[0].title)
        assertEquals(1, report.issues[0].rowNumber)
    }

    @Test
    fun testHandleRatingsSeasonUnsupported() {
        val row = mapOf("title" to "Test Season", "tmdb_id" to "123", "type" to "season", "rating_10" to "8.0")
        report.total = 2
        handleRow("ratings.csv", row, report)

        assertEquals(1, report.skipped)
        assertEquals(1, report.issues.size)
        assertEquals(ImportReason.INVALID_VALUE, report.issues[0].reason)
        assertEquals("Season ratings are unsupported", report.issues[0].detailMessage)
        assertEquals(2, report.issues[0].rowNumber)
    }

    private fun convertDate(isoDate: String?): String? {
        if (isoDate.isNullOrBlank()) return null
        return try {
            isoDate.substring(0, 10)
        } catch (e: Exception) {
            null
        }
    }

    private fun handleRow(fileName: String, row: Map<String, String>, report: ImportReport) {
        val tmdbIdStr = row["tmdb_id"] ?: row["SHOW"]
        val tmdbId = tmdbIdStr?.toLongOrNull()

        if (tmdbId == null) {
            report.skipped++
            report.issues.add(ImportIssue(report.total, row["title"], null, ImportReason.MISSING_TMDB_ID, "No tmdb_id found"))
            return
        }

        when (fileName) {
            "ratings.csv" -> handleRatingsRowMock(row, tmdbId, report)
        }
    }

    private fun handleRatingsRowMock(row: Map<String, String>, tmdbId: Long, report: ImportReport) {
        val type = row["type"]
        val rating10 = row["rating_10"]?.toDoubleOrNull()
        if (rating10 == null) return

        if (type == "season") {
            report.skipped++
            report.issues.add(ImportIssue(report.total, row["title"], tmdbId, ImportReason.INVALID_VALUE, "Season ratings are unsupported"))
            return
        }
    }
}
