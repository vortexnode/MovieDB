/*
 *     This file is part of "ShowCase" formerly Movie DB. <https://github.com/WirelessAlien/MovieDB>
 *     forked from <https://notabug.org/nvb/MovieDB>
 *
 *     Copyright (C) 2024  WirelessAlien <https://github.com/WirelessAlien>
 *
 *     ShowCase is free software: you can redistribute it and/or modify
 *     it under the terms of the GNU General Public License as published by
 *     the Free Software Foundation, either version 3 of the License, or
 *     (at your option) any later version.
 *
 *     ShowCase is distributed in the hope that it will be useful,
 *     but WITHOUT ANY WARRANTY; without even the implied warranty of
 *     MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *     GNU General Public License for more details.
 *
 *     You should have received a copy of the GNU General Public License
 *     along with "ShowCase".  If not, see <https://www.gnu.org/licenses/>.
 */

package com.wirelessalien.android.moviedb.helper

import android.content.ContentValues
import android.util.Log
import org.json.JSONObject

class TraktImporter(private val movieDbHelper: MovieDatabaseHelper) {

    private val TAG = "TraktImporter"

    fun processWatchedMovie(item: JSONObject, report: ImportReport, rowNumber: Int): Boolean {
        val tmdbId = getTmdbId(item, "movie")
        var title = getTitle(item, "movie")

        if (tmdbId == null) {
            report.skipped++
            report.issues.add(ImportIssue(rowNumber, title, null, ImportReason.MISSING_TMDB_ID, "Missing TMDB ID for Watched Movie"))
            return false
        }

        val plays = item.optInt("plays", 0)
        val lastWatchedAt = item.optString("last_watched_at", "")
        val finishDate = if (lastWatchedAt.isNotBlank() && lastWatchedAt.length >= 10) lastWatchedAt.substring(0, 10) else ""

        val cv = ContentValues()
        cv.put(MovieDatabaseHelper.COLUMN_TITLE, title ?: "Unknown")
        cv.put(MovieDatabaseHelper.COLUMN_MOVIE, 1)
        cv.put(MovieDatabaseHelper.COLUMN_CATEGORIES, MovieDatabaseHelper.CATEGORY_WATCHED)

        if (finishDate.isNotBlank()) {
            cv.put(MovieDatabaseHelper.COLUMN_PERSONAL_FINISH_DATE, finishDate)
        }

        if (plays > 1) {
            cv.put(MovieDatabaseHelper.COLUMN_PERSONAL_REWATCHED, plays - 1)
        }

        return updateOrInsertMovie(tmdbId, cv, true, report, rowNumber, title)
    }

    fun processWatchedShow(item: JSONObject, report: ImportReport, rowNumber: Int): Boolean {
        val showObj = item.optJSONObject("show") ?: return false
        val tmdbId = showObj.optJSONObject("ids")?.optLong("tmdb")
        val title = showObj.optString("title")

        if (tmdbId == null || tmdbId == 0L) {
            report.skipped++
            report.issues.add(ImportIssue(rowNumber, title, null, ImportReason.MISSING_TMDB_ID, "Missing TMDB ID for Watched Show"))
            return false
        }

        // Insert show if not exists
        val showCv = ContentValues()
        showCv.put(MovieDatabaseHelper.COLUMN_TITLE, title)
        showCv.put(MovieDatabaseHelper.COLUMN_MOVIE, 0)
        val existingShowId = movieDbHelper.getMovieRowId(tmdbId, false)
        if (existingShowId == null) {
            showCv.put(MovieDatabaseHelper.COLUMN_CATEGORIES, MovieDatabaseHelper.CATEGORY_WATCHING)
            updateOrInsertMovie(tmdbId, showCv, false, report, rowNumber, title) // Creates the show row
        }

        val dbExistingShowId = movieDbHelper.getMovieRowId(tmdbId, false) ?: return false

        val seasons = item.optJSONArray("seasons") ?: return true // Just the show mapped
        var insertedEpisodeCount = 0
        for (i in 0 until seasons.length()) {
            val season = seasons.optJSONObject(i) ?: continue
            val seasonNum = season.optInt("number")
            val episodes = season.optJSONArray("episodes") ?: continue
            for (j in 0 until episodes.length()) {
                val episode = episodes.optJSONObject(j) ?: continue
                val epNum = episode.optInt("number")
                val lastWatchedAt = episode.optString("last_watched_at", "")
                val watchDate = if (lastWatchedAt.isNotBlank() && lastWatchedAt.length >= 10) lastWatchedAt.substring(0, 10) else ""

                val existingEpId = movieDbHelper.getEpisodeRowId(dbExistingShowId, seasonNum, epNum)
                if (existingEpId == null || existingEpId == -1L) {
                    val epCv = ContentValues()
                    epCv.put(MovieDatabaseHelper.COLUMN_MOVIES_ID, dbExistingShowId)
                    epCv.put(MovieDatabaseHelper.COLUMN_SEASON_NUMBER, seasonNum)
                    epCv.put(MovieDatabaseHelper.COLUMN_EPISODE_NUMBER, epNum)
                    if (watchDate.isNotBlank()) epCv.put(MovieDatabaseHelper.COLUMN_EPISODE_WATCH_DATE, watchDate)

                    try {
                        val db = movieDbHelper.writableDatabase
                        db.insert(MovieDatabaseHelper.TABLE_EPISODES, null, epCv)
                        insertedEpisodeCount++
                    } catch (e: Exception) {
                        Log.e(TAG, "Error inserting episode", e)
                    }
                }
            }
        }

        report.inserted += if (existingShowId == null) 1 else 0
        return true
    }

    fun processRating(item: JSONObject, report: ImportReport, rowNumber: Int): Boolean {
        val type = item.optString("type")
        val rating = item.optInt("rating")
        if (rating < 1 || rating > 10) return false

        when (type) {
            "movie", "show" -> {
                val tmdbId = getTmdbId(item, type)
                val title = getTitle(item, type)
                if (tmdbId == null) {
                    report.skipped++
                    report.issues.add(ImportIssue(rowNumber, title, null, ImportReason.MISSING_TMDB_ID, "Missing TMDB ID for Rating"))
                    return false
                }
                val cv = ContentValues()
                cv.put(MovieDatabaseHelper.COLUMN_TITLE, title ?: "Unknown")
                cv.put(MovieDatabaseHelper.COLUMN_MOVIE, if (type == "movie") 1 else 0)
                cv.put(MovieDatabaseHelper.COLUMN_PERSONAL_RATING, rating)
                return updateOrInsertMovie(tmdbId, cv, type == "movie", report, rowNumber, title)
            }
            "episode" -> {
                val showObj = item.optJSONObject("show") ?: return false
                val tmdbId = showObj.optJSONObject("ids")?.optLong("tmdb")
                val showTitle = showObj.optString("title")

                if (tmdbId == null || tmdbId == 0L) {
                    report.skipped++
                    report.issues.add(ImportIssue(rowNumber, showTitle, null, ImportReason.MISSING_TMDB_ID, "Missing TMDB ID for Episode Rating Show"))
                    return false
                }

                val dbExistingShowId = movieDbHelper.getMovieRowId(tmdbId, false)
                if (dbExistingShowId == null) {
                    // Create minimal show row to hold the episode
                    val showCv = ContentValues()
                    showCv.put(MovieDatabaseHelper.COLUMN_TITLE, showTitle)
                    showCv.put(MovieDatabaseHelper.COLUMN_MOVIE, 0)
                    showCv.put(MovieDatabaseHelper.COLUMN_CATEGORIES, MovieDatabaseHelper.CATEGORY_PLAN_TO_WATCH)
                    updateOrInsertMovie(tmdbId, showCv, false, report, rowNumber, showTitle)
                }

                val finalShowId = movieDbHelper.getMovieRowId(tmdbId, false) ?: return false

                val episodeObj = item.optJSONObject("episode") ?: return false
                val seasonNum = episodeObj.optInt("season")
                val epNum = episodeObj.optInt("number")

                val existingEpId = movieDbHelper.getEpisodeRowId(finalShowId, seasonNum, epNum)
                val epCv = ContentValues()
                epCv.put(MovieDatabaseHelper.COLUMN_EPISODE_RATING, rating)

                try {
                    val db = movieDbHelper.writableDatabase
                    if (existingEpId != null && existingEpId != -1L) {
                        db.update(MovieDatabaseHelper.TABLE_EPISODES, epCv, "${MovieDatabaseHelper.COLUMN_ID} = ?", arrayOf(existingEpId.toString()))
                    } else {
                        epCv.put(MovieDatabaseHelper.COLUMN_MOVIES_ID, finalShowId)
                        epCv.put(MovieDatabaseHelper.COLUMN_SEASON_NUMBER, seasonNum)
                        epCv.put(MovieDatabaseHelper.COLUMN_EPISODE_NUMBER, epNum)
                        db.insert(MovieDatabaseHelper.TABLE_EPISODES, null, epCv)
                    }
                    report.updated++
                    return true
                } catch (e: Exception) {
                    Log.e(TAG, "Error saving episode rating", e)
                    report.failed++
                    report.issues.add(ImportIssue(rowNumber, showTitle, tmdbId, ImportReason.DB_ERROR, e.message))
                    return false
                }
            }
            "season" -> {
                val showTitle = item.optJSONObject("show")?.optString("title")
                report.skipped++
                report.issues.add(ImportIssue(rowNumber, showTitle, null, ImportReason.INVALID_VALUE, "Season ratings are not supported and were skipped"))
                return false
            }
            else -> return false
        }
    }

    fun processWatchlist(item: JSONObject, report: ImportReport, rowNumber: Int): Boolean {
        val type = item.optString("type")
        if (type != "movie" && type != "show") return false // only movie/show allowed

        val tmdbId = getTmdbId(item, type)
        val title = getTitle(item, type)
        if (tmdbId == null) {
            report.skipped++
            report.issues.add(ImportIssue(rowNumber, title, null, ImportReason.MISSING_TMDB_ID, "Missing TMDB ID for Watchlist"))
            return false
        }

        val isMovie = type == "movie"
        val existingRowId = movieDbHelper.getMovieRowId(tmdbId, isMovie)

        val cv = ContentValues()
        cv.put(MovieDatabaseHelper.COLUMN_TITLE, title ?: "Unknown")
        cv.put(MovieDatabaseHelper.COLUMN_MOVIE, if (isMovie) 1 else 0)

        if (existingRowId == null) {
            cv.put(MovieDatabaseHelper.COLUMN_CATEGORIES, MovieDatabaseHelper.CATEGORY_PLAN_TO_WATCH)
        }

        return updateOrInsertMovie(tmdbId, cv, isMovie, report, rowNumber, title)
    }

    fun processTagItem(item: JSONObject, tagName: String, report: ImportReport, rowNumber: Int): Boolean {
        val type = item.optString("type")
        if (type != "movie" && type != "show") return false // skip seasons/episodes for tags for now or map to show

        val tmdbId = getTmdbId(item, type)
        val title = getTitle(item, type)
        if (tmdbId == null) {
            report.skipped++
            report.issues.add(ImportIssue(rowNumber, title, null, ImportReason.MISSING_TMDB_ID, "Missing TMDB ID for $tagName"))
            return false
        }

        val isMovie = type == "movie"

        // Ensure item exists in DB minimally
        val cv = ContentValues()
        cv.put(MovieDatabaseHelper.COLUMN_TITLE, title ?: "Unknown")
        cv.put(MovieDatabaseHelper.COLUMN_MOVIE, if (isMovie) 1 else 0)
        val existingRowId = movieDbHelper.getMovieRowId(tmdbId, isMovie)
        if (existingRowId == null) {
            cv.put(MovieDatabaseHelper.COLUMN_CATEGORIES, MovieDatabaseHelper.CATEGORY_PLAN_TO_WATCH)
            updateOrInsertMovie(tmdbId, cv, isMovie, report, rowNumber, title)
        }

        val dbExistingRowId = movieDbHelper.getMovieRowId(tmdbId, isMovie) ?: return false

        // Add Tag
        val tagId = movieDbHelper.addTag(tagName)
        if (tagId != -1L) {
            movieDbHelper.addMovieTag(dbExistingRowId.toInt(), tagId, isMovie)
            report.updated++
            return true
        }
        return false
    }

    private fun getTmdbId(item: JSONObject, type: String): Long? {
        val obj = item.optJSONObject(type) ?: return null
        val tmdbId = obj.optJSONObject("ids")?.optLong("tmdb", 0L)
        return if (tmdbId != null && tmdbId > 0L) tmdbId else null
    }

    private fun getTitle(item: JSONObject, type: String): String? {
        val obj = item.optJSONObject(type) ?: return null
        return obj.optString("title").takeIf { it.isNotBlank() }
    }

    private fun updateOrInsertMovie(tmdbId: Long, cv: ContentValues, isMovie: Boolean, report: ImportReport, rowNumber: Int, title: String?): Boolean {
        try {
            // Add a hook to tell the service if this needs a TMDB fetch later or handle it via caller.
            // We will just put a placeholder here that the service checks.

            // Apply defaults
            if (cv.getAsString(MovieDatabaseHelper.COLUMN_SUMMARY).isNullOrEmpty()) cv.put(MovieDatabaseHelper.COLUMN_SUMMARY, "N/A")
            if (cv.getAsString(MovieDatabaseHelper.COLUMN_IMAGE).isNullOrEmpty()) cv.put(MovieDatabaseHelper.COLUMN_IMAGE, "")
            if (cv.getAsString(MovieDatabaseHelper.COLUMN_ICON).isNullOrEmpty()) cv.put(MovieDatabaseHelper.COLUMN_ICON, "")
            if (cv.getAsDouble(MovieDatabaseHelper.COLUMN_RATING) == null) cv.put(MovieDatabaseHelper.COLUMN_RATING, 0.0)
            if (cv.getAsString(MovieDatabaseHelper.COLUMN_GENRES).isNullOrEmpty()) cv.put(MovieDatabaseHelper.COLUMN_GENRES, "N/A")
            if (cv.getAsString(MovieDatabaseHelper.COLUMN_GENRES_IDS).isNullOrEmpty()) cv.put(MovieDatabaseHelper.COLUMN_GENRES_IDS, "[]")

            val db = movieDbHelper.writableDatabase
            val existingRowId = movieDbHelper.getMovieRowId(tmdbId, isMovie)
            if (existingRowId != null) {
                // If it already exists, be careful not to overwrite watched state if it's already watched
                val cursor = db.query(MovieDatabaseHelper.TABLE_MOVIES, arrayOf(MovieDatabaseHelper.COLUMN_CATEGORIES), "${MovieDatabaseHelper.COLUMN_MOVIES_ID} = ?", arrayOf(existingRowId.toString()), null, null, null)
                var currentCategory = MovieDatabaseHelper.CATEGORY_PLAN_TO_WATCH
                if (cursor.moveToFirst()) {
                    currentCategory = cursor.getInt(0)
                }
                cursor.close()

                val newCategory = cv.getAsInteger(MovieDatabaseHelper.COLUMN_CATEGORIES)
                if (newCategory != null) {
                     // Never downgrade watched/watching
                     if ((currentCategory == MovieDatabaseHelper.CATEGORY_WATCHED || currentCategory == MovieDatabaseHelper.CATEGORY_WATCHING) && newCategory == MovieDatabaseHelper.CATEGORY_PLAN_TO_WATCH) {
                         cv.remove(MovieDatabaseHelper.COLUMN_CATEGORIES)
                     }
                }

                db.update(MovieDatabaseHelper.TABLE_MOVIES, cv, "${MovieDatabaseHelper.COLUMN_MOVIES_ID} = ?", arrayOf(existingRowId.toString()))
                report.updated++
            } else {
                cv.put(MovieDatabaseHelper.COLUMN_MOVIES_ID, tmdbId)
                db.insert(MovieDatabaseHelper.TABLE_MOVIES, null, cv)
                report.inserted++
            }
            return true
        } catch (e: Exception) {
            Log.e(TAG, "Error updating/inserting movie", e)
            report.failed++
            report.issues.add(ImportIssue(rowNumber, title, tmdbId, ImportReason.DB_ERROR, e.message))
            return false
        }
    }
}
