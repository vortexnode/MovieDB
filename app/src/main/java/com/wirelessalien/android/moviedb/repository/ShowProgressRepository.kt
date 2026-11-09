package com.wirelessalien.android.moviedb.repository

import android.content.Context
import com.wirelessalien.android.moviedb.helper.MovieDatabaseHelper
import com.wirelessalien.android.moviedb.helper.TmdbDetailsDatabaseHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONException

data class ShowProgressItem(
    val id: Int,
    val tmdbId: Int,
    val title: String,
    val posterPath: String,
    val watchedEpisodes: Int,
    val totalEpisodes: Int,
    val seasonsCount: Int,
    val nextEpisodeSeason: Int?,
    val nextEpisodeNumber: Int?,
    val nextEpisodeTitle: String?,
    val nextEpisodeAirDate: String?,
    val lastWatchedDate: String?
)

class ShowProgressRepository(private val context: Context) {
    private val dbHelper = MovieDatabaseHelper(context)
    private val tmdbDbHelper = TmdbDetailsDatabaseHelper(context)
    private val httpClient = OkHttpClient()
    private val CACHE_DURATION_MS = 12 * 60 * 60 * 1000L // 12 hours

    suspend fun getWatchingShowsProgress(apiKey: String): List<ShowProgressItem> = withContext(Dispatchers.IO) {
        val items = mutableListOf<ShowProgressItem>()
        val db = dbHelper.readableDatabase

        // category 2 = Watching, movie 0 = TV show
        val cursor = db.query(
            MovieDatabaseHelper.TABLE_MOVIES,
            arrayOf(
                MovieDatabaseHelper.COLUMN_MOVIES_ID,
                MovieDatabaseHelper.COLUMN_TITLE,
                MovieDatabaseHelper.COLUMN_IMAGE
            ),
            "${MovieDatabaseHelper.COLUMN_CATEGORIES} = ? AND ${MovieDatabaseHelper.COLUMN_MOVIE} = ?",
            arrayOf(MovieDatabaseHelper.CATEGORY_WATCHING.toString(), "0"),
            null, null, null
        )

        cursor.use {
            while (it.moveToNext()) {
                val movieId = it.getInt(it.getColumnIndexOrThrow(MovieDatabaseHelper.COLUMN_MOVIES_ID))
                val title = it.getString(it.getColumnIndexOrThrow(MovieDatabaseHelper.COLUMN_TITLE)) ?: ""
                val posterPath = it.getString(it.getColumnIndexOrThrow(MovieDatabaseHelper.COLUMN_IMAGE)) ?: ""

                val showDetails = getShowDetails(movieId, apiKey) ?: continue
                val totalEpisodes = showDetails.optInt("number_of_episodes", 0)
                val seasonsCount = showDetails.optInt("number_of_seasons", 0)

                val watchedData = getWatchedData(movieId)
                val watchedEpisodes = watchedData.watchedCount
                val lastWatchedDate = watchedData.lastWatchedDate

                var nextEpisodeSeason: Int? = null
                var nextEpisodeNumber: Int? = null
                var nextEpisodeTitle: String? = null
                var nextEpisodeAirDate: String? = null

                val nextEpisodeToAir = showDetails.optJSONObject("next_episode_to_air")

                // If there's an unaired next episode and we've watched everything before it
                if (nextEpisodeToAir != null && watchedEpisodes >= totalEpisodes) {
                     nextEpisodeSeason = nextEpisodeToAir.optInt("season_number")
                     nextEpisodeNumber = nextEpisodeToAir.optInt("episode_number")
                     nextEpisodeTitle = nextEpisodeToAir.optString("name")
                     nextEpisodeAirDate = nextEpisodeToAir.optString("air_date")
                } else if (watchedEpisodes < totalEpisodes) {
                     // We have unwatched episodes, find the next one
                     val next = calculateNextEpisode(movieId, apiKey, showDetails, watchedData.watchedEpisodesSet)
                     if (next != null) {
                         nextEpisodeSeason = next.season
                         nextEpisodeNumber = next.number
                         nextEpisodeTitle = next.title
                         nextEpisodeAirDate = next.airDate
                     }
                }

                items.add(
                    ShowProgressItem(
                        id = movieId,
                        tmdbId = movieId, // assuming tmdbId == movieId for simple local entries
                        title = title,
                        posterPath = posterPath,
                        watchedEpisodes = watchedEpisodes,
                        totalEpisodes = totalEpisodes,
                        seasonsCount = seasonsCount,
                        nextEpisodeSeason = nextEpisodeSeason,
                        nextEpisodeNumber = nextEpisodeNumber,
                        nextEpisodeTitle = nextEpisodeTitle,
                        nextEpisodeAirDate = nextEpisodeAirDate,
                        lastWatchedDate = lastWatchedDate
                    )
                )
            }
        }
        items
    }

    private data class WatchedData(val watchedCount: Int, val lastWatchedDate: String?, val watchedEpisodesSet: Set<Pair<Int, Int>>)

    private fun getWatchedData(movieId: Int): WatchedData {
        val db = dbHelper.readableDatabase
        val watchedSet = mutableSetOf<Pair<Int, Int>>()
        var lastDate: String? = null
        val cursor = db.query(
            MovieDatabaseHelper.TABLE_EPISODES,
            arrayOf(MovieDatabaseHelper.COLUMN_SEASON_NUMBER, MovieDatabaseHelper.COLUMN_EPISODE_NUMBER, MovieDatabaseHelper.COLUMN_EPISODE_WATCH_DATE),
            "${MovieDatabaseHelper.COLUMN_MOVIES_ID} = ?",
            arrayOf(movieId.toString()),
            null, null, "${MovieDatabaseHelper.COLUMN_EPISODE_WATCH_DATE} DESC"
        )
        cursor.use {
            while (it.moveToNext()) {
                val season = it.getInt(0)
                val episode = it.getInt(1)
                val watchDate = it.getString(2)
                watchedSet.add(Pair(season, episode))
                if (lastDate == null) {
                    lastDate = watchDate
                }
            }
        }
        return WatchedData(watchedSet.size, lastDate, watchedSet)
    }

    private data class NextEpisodeInfo(val season: Int, val number: Int, val title: String?, val airDate: String?)

    private suspend fun calculateNextEpisode(movieId: Int, apiKey: String, showDetails: JSONObject, watchedSet: Set<Pair<Int, Int>>): NextEpisodeInfo? {
        val seasonsArray = showDetails.optJSONArray("seasons") ?: return null

        for (i in 0 until seasonsArray.length()) {
            val seasonObj = seasonsArray.optJSONObject(i) ?: continue
            val seasonNum = seasonObj.optInt("season_number")
            if (seasonNum == 0) continue // Skip specials

            val episodeCount = seasonObj.optInt("episode_count")
            for (epNum in 1..episodeCount) {
                if (!watchedSet.contains(Pair(seasonNum, epNum))) {
                    // Fetch season details for title/airDate
                    val seasonDetails = getSeasonDetails(movieId, seasonNum, apiKey)
                    if (seasonDetails != null) {
                        val episodesArray = seasonDetails.optJSONArray("episodes")
                        if (episodesArray != null) {
                            for (j in 0 until episodesArray.length()) {
                                val epObj = episodesArray.optJSONObject(j) ?: continue
                                if (epObj.optInt("episode_number") == epNum) {
                                    return NextEpisodeInfo(
                                        seasonNum,
                                        epNum,
                                        epObj.optString("name").takeIf { it.isNotEmpty() },
                                        epObj.optString("air_date").takeIf { it.isNotEmpty() }
                                    )
                                }
                            }
                        }
                    }
                    return NextEpisodeInfo(seasonNum, epNum, null, null)
                }
            }
        }
        return null
    }

    private suspend fun getShowDetails(movieId: Int, apiKey: String): JSONObject? {
        val url = "https://api.themoviedb.org/3/tv/$movieId?api_key=$apiKey"
        return fetchJsonCached(url, "show_$movieId")
    }

    private suspend fun getSeasonDetails(movieId: Int, season: Int, apiKey: String): JSONObject? {
        val url = "https://api.themoviedb.org/3/tv/$movieId/season/$season?api_key=$apiKey"
        return fetchJsonCached(url, "show_${movieId}_season_$season")
    }

    private suspend fun fetchJsonCached(url: String, cacheKey: String): JSONObject? {
        val cacheFile = File(context.cacheDir, "$cacheKey.json")
        if (cacheFile.exists() && System.currentTimeMillis() - cacheFile.lastModified() < CACHE_DURATION_MS) {
            try {
                return JSONObject(cacheFile.readText())
            } catch (e: Exception) {
                // Ignore, fetch again
            }
        }

        try {
            val request = Request.Builder().url(url).build()
            val response = httpClient.newCall(request).execute()
            if (response.isSuccessful) {
                val jsonStr = response.body?.string()
                if (jsonStr != null) {
                    cacheFile.writeText(jsonStr)
                    return JSONObject(jsonStr)
                }
            }
        } catch (e: Exception) {
            // Error handling
        }
        return null
    }

    fun sortItems(items: List<ShowProgressItem>, sortBy: String): List<ShowProgressItem> {
        return when (sortBy) {
            "name" -> items.sortedBy { it.title }
            "progress" -> items.sortedByDescending { if (it.totalEpisodes > 0) it.watchedEpisodes.toFloat() / it.totalEpisodes else 0f }
            "next_air_date" -> items.sortedWith(compareBy<ShowProgressItem> { it.nextEpisodeAirDate == null }.thenBy { it.nextEpisodeAirDate })
            "recently_watched" -> items.sortedWith(compareBy<ShowProgressItem> { it.lastWatchedDate == null }.thenByDescending { it.lastWatchedDate })
            else -> items // default recently_watched handled above, or if unknown
        }
    }
}
