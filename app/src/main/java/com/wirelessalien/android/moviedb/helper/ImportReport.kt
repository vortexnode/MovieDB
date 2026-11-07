package com.wirelessalien.android.moviedb.helper

import java.io.Serializable

enum class ImportReason {
    MISSING_TMDB_ID,
    MISSING_TITLE,
    TMDB_FETCH_FAILED,
    DB_ERROR,
    INVALID_VALUE
}

data class ImportIssue(
    val rowNumber: Int,
    val title: String?,
    val tmdbId: Long?,
    val reason: ImportReason,
    val detailMessage: String?
) : Serializable

class ImportReport(
    var total: Int = 0,
    var inserted: Int = 0,
    var updated: Int = 0,
    var skipped: Int = 0,
    var failed: Int = 0,
    val issues: MutableList<ImportIssue> = mutableListOf()
) : Serializable
