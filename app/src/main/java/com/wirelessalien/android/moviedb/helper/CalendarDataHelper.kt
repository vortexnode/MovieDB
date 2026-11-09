package com.wirelessalien.android.moviedb.helper

import android.content.Context
import android.util.Log
import com.wirelessalien.android.moviedb.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import okhttp3.OkHttpClient
import okhttp3.Request

// Data class to represent calendar items for easy testing and usage
data class CalendarItem(
    val type: String,
    val tmdbId: Int,
    val title: String,
    val releaseDate: String, // format yyyy-MM-dd
    val posterPath: String,
    val season: Int = 0,
    val episode: Int = 0,
    val subtitle: String = ""
) {
    fun toJSONObject(): JSONObject {
        return JSONObject().apply {
            put("type", type)
            put("tmdb_id", tmdbId)
            put("title", title)
            put("release_date", releaseDate)
            put("poster_path", posterPath)
            put("season", season)
            put("episode", episode)
            put("subtitle", subtitle)
        }
    }

    companion object {
        fun fromJSONObject(json: JSONObject): CalendarItem {
            return CalendarItem(
                type = json.optString("type"),
                tmdbId = json.optInt("tmdb_id"),
                title = json.optString("title"),
                releaseDate = json.optString("release_date"),
                posterPath = json.optString("poster_path"),
                season = json.optInt("season", 0),
                episode = json.optInt("episode", 0),
                subtitle = json.optString("subtitle", "")
            )
        }
    }
}

class CalendarDataHelper(private val context: Context) {

    private val dbHelper = MovieDatabaseHelper(context)
    private val traktDbHelper = TraktDatabaseHelper(context)
    private val tmdbDbHelper = TmdbDetailsDatabaseHelper(context)
    private val preferences = androidx.preference.PreferenceManager.getDefaultSharedPreferences(context)
    private val rateLimiter = RateLimiter(30, 10, java.util.concurrent.TimeUnit.SECONDS)

    // Share OkHttpClient instance
    private val httpClient = OkHttpClient()

    suspend fun fetchData(forceRefresh: Boolean): List<CalendarItem> = withContext(Dispatchers.IO) {
        val cacheKey = "calendar_data_cache"
        val cacheTimeKey = "calendar_data_cache_time"
        val lastFetchTime = preferences.getLong(cacheTimeKey, 0)
        val currentTime = System.currentTimeMillis()

        // 12 hours cache
        if (!forceRefresh && (currentTime - lastFetchTime) < 12 * 60 * 60 * 1000) {
            val cachedDataStr = preferences.getString(cacheKey, null)
            if (cachedDataStr != null) {
                try {
                    val jsonArray = JSONArray(cachedDataStr)
                    val result = mutableListOf<CalendarItem>()
                    for (i in 0 until jsonArray.length()) {
                        result.add(CalendarItem.fromJSONObject(jsonArray.getJSONObject(i)))
                    }
                    if (result.isNotEmpty()) {
                        return@withContext result
                    }
                } catch (e: Exception) {
                    Log.e("CalendarDataHelper", "Error parsing cached data", e)
                }
            }
        }

        val allItems = mutableListOf<CalendarItem>()

        // 1. Local Movies
        allItems.addAll(fetchLocalMovies())

        // 2. Local TV Shows (Watching, Plan to watch)
        allItems.addAll(fetchLocalTvShows())

        // 3. Trakt Calendar (if logged in)
        if (preferences.getString("sync_provider", "local") == "trakt") {
            allItems.addAll(fetchTraktCalendar())
        }

        // 4. Deduplicate & Sort
        val deduplicated = deduplicateData(allItems)
        val sorted = deduplicated.sortedBy { it.releaseDate.ifEmpty { "9999-12-31" } }

        // Cache the result
        val jsonArrayToCache = JSONArray()
        sorted.forEach { jsonArrayToCache.put(it.toJSONObject()) }
        preferences.edit()
            .putString(cacheKey, jsonArrayToCache.toString())
            .putLong(cacheTimeKey, currentTime)
            .apply()

        return@withContext sorted
    }

    private fun fetchLocalMovies(): List<CalendarItem> {
        val movies = mutableListOf<CalendarItem>()
        val db = dbHelper.readableDatabase

        val calendar = Calendar.getInstance()
        calendar.add(Calendar.DAY_OF_YEAR, -7)
        val sevenDaysAgoStr = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(calendar.time)

        val query = """
            SELECT ${MovieDatabaseHelper.COLUMN_MOVIES_ID}, ${MovieDatabaseHelper.COLUMN_TITLE}, ${MovieDatabaseHelper.COLUMN_RELEASE_DATE}, ${MovieDatabaseHelper.COLUMN_IMAGE}
            FROM ${MovieDatabaseHelper.TABLE_MOVIES}
            WHERE ${MovieDatabaseHelper.COLUMN_MOVIE} = 1
              AND ${MovieDatabaseHelper.COLUMN_RELEASE_DATE} >= ?
              AND ${MovieDatabaseHelper.COLUMN_RELEASE_DATE} != ''
        """.trimIndent()

        db.rawQuery(query, arrayOf(sevenDaysAgoStr)).use { cursor ->
            while (cursor.moveToNext()) {
                val tmdbId = cursor.getInt(cursor.getColumnIndexOrThrow(MovieDatabaseHelper.COLUMN_MOVIES_ID))
                val title = cursor.getString(cursor.getColumnIndexOrThrow(MovieDatabaseHelper.COLUMN_TITLE))
                val releaseDate = cursor.getString(cursor.getColumnIndexOrThrow(MovieDatabaseHelper.COLUMN_RELEASE_DATE))
                val posterPath = cursor.getString(cursor.getColumnIndexOrThrow(MovieDatabaseHelper.COLUMN_IMAGE))

                movies.add(CalendarItem(
                    type = "movie",
                    tmdbId = tmdbId,
                    title = title,
                    releaseDate = releaseDate,
                    posterPath = posterPath,
                    subtitle = context.getString(R.string.calendar_movie_release)
                ))
            }
        }
        return movies
    }

    private suspend fun fetchLocalTvShows(): List<CalendarItem> {
        val shows = mutableListOf<CalendarItem>()
        val db = dbHelper.readableDatabase

        val query = """
            SELECT ${MovieDatabaseHelper.COLUMN_MOVIES_ID}, ${MovieDatabaseHelper.COLUMN_TITLE}, ${MovieDatabaseHelper.COLUMN_IMAGE}
            FROM ${MovieDatabaseHelper.TABLE_MOVIES}
            WHERE ${MovieDatabaseHelper.COLUMN_MOVIE} = 0
              AND (${MovieDatabaseHelper.COLUMN_CATEGORIES} = ${MovieDatabaseHelper.CATEGORY_WATCHING} OR ${MovieDatabaseHelper.COLUMN_CATEGORIES} = ${MovieDatabaseHelper.CATEGORY_PLAN_TO_WATCH})
        """.trimIndent()

        val showList = mutableListOf<Triple<Int, String, String>>()
        db.rawQuery(query, null).use { cursor ->
            while (cursor.moveToNext()) {
                val tmdbId = cursor.getInt(cursor.getColumnIndexOrThrow(MovieDatabaseHelper.COLUMN_MOVIES_ID))
                val title = cursor.getString(cursor.getColumnIndexOrThrow(MovieDatabaseHelper.COLUMN_TITLE))
                val posterPath = cursor.getString(cursor.getColumnIndexOrThrow(MovieDatabaseHelper.COLUMN_IMAGE))
                showList.add(Triple(tmdbId, title, posterPath))
            }
        }

        val apiKey = ConfigHelper.getConfigValue(context, "api_key") ?: return shows

        for ((tmdbId, title, posterPath) in showList) {
            rateLimiter.acquire()
            try {
                val url = "https://api.themoviedb.org/3/tv/${tmdbId}?api_key=${apiKey}"
                val request = Request.Builder().url(url).build()
                val response = httpClient.newCall(request).execute()

                if (response.isSuccessful) {
                    val responseBody = response.body?.string()
                    if (responseBody != null) {
                        val json = JSONObject(responseBody)

                        // Check last episode
                        if (json.has("last_episode_to_air") && !json.isNull("last_episode_to_air")) {
                            val ep = json.getJSONObject("last_episode_to_air")
                            addEpisodeItem(shows, tmdbId, title, posterPath, ep)
                        }

                        // Check next episode
                        if (json.has("next_episode_to_air") && !json.isNull("next_episode_to_air")) {
                            val ep = json.getJSONObject("next_episode_to_air")
                            addEpisodeItem(shows, tmdbId, title, posterPath, ep)
                        }

                        // Check new seasons
                        if (json.has("seasons") && !json.isNull("seasons")) {
                            val seasons = json.getJSONArray("seasons")
                            for (i in 0 until seasons.length()) {
                                val season = seasons.getJSONObject(i)
                                val seasonNumber = season.optInt("season_number", 0)
                                val airDate = season.optString("air_date", "")
                                if (seasonNumber > 0 && airDate.isNotEmpty()) {
                                    shows.add(CalendarItem(
                                        type = "season",
                                        tmdbId = tmdbId,
                                        title = title,
                                        releaseDate = airDate,
                                        posterPath = season.optString("poster_path", posterPath),
                                        season = seasonNumber,
                                        subtitle = context.getString(R.string.calendar_season_premiere, seasonNumber)
                                    ))
                                }
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e("CalendarDataHelper", "Error fetching TV show details for $tmdbId", e)
            }
        }
        return shows
    }

    private fun addEpisodeItem(shows: MutableList<CalendarItem>, tmdbId: Int, showTitle: String, showPoster: String, ep: JSONObject) {
        val airDate = ep.optString("air_date", "")
        if (airDate.isNotEmpty()) {
            val season = ep.optInt("season_number", 0)
            val episode = ep.optInt("episode_number", 0)
            val epName = ep.optString("name", "")

            val subtitle = String.format("S%02dE%02d", season, episode) + if (epName.isNotEmpty()) " - $epName" else ""

            shows.add(CalendarItem(
                type = "episode",
                tmdbId = tmdbId,
                title = showTitle,
                releaseDate = airDate,
                posterPath = showPoster,
                season = season,
                episode = episode,
                subtitle = subtitle
            ))
        }
    }

    private fun fetchTraktCalendar(): List<CalendarItem> {
        val items = mutableListOf<CalendarItem>()
        val db = traktDbHelper.readableDatabase
        val tmdbDb = tmdbDbHelper.readableDatabase

        val cursor = db.query(TraktDatabaseHelper.TABLE_CALENDER, null, null, null, null, null, null)

        if (cursor.moveToFirst()) {
            do {
                val airDate = cursor.getString(cursor.getColumnIndexOrThrow(TraktDatabaseHelper.COL_AIR_DATE))
                val title = cursor.getString(cursor.getColumnIndexOrThrow(TraktDatabaseHelper.COL_TITLE))
                val type = cursor.getString(cursor.getColumnIndexOrThrow(TraktDatabaseHelper.COL_TYPE))

                var tmdbId = -1
                var posterPath = ""

                if (type == "movie") {
                    tmdbId = cursor.getInt(cursor.getColumnIndexOrThrow(TraktDatabaseHelper.COL_TMDB))
                    val tmdbCursor = tmdbDb.query(
                        TmdbDetailsDatabaseHelper.TABLE_TMDB_DETAILS, arrayOf(TmdbDetailsDatabaseHelper.COL_POSTER_PATH),
                        "${TmdbDetailsDatabaseHelper.COL_TMDB_ID} = ? AND ${TmdbDetailsDatabaseHelper.COL_TYPE} = ?",
                        arrayOf(tmdbId.toString(), "movie"), null, null, null)
                    if (tmdbCursor.moveToFirst()) {
                        posterPath = tmdbCursor.getString(tmdbCursor.getColumnIndexOrThrow(TmdbDetailsDatabaseHelper.COL_POSTER_PATH)) ?: ""
                    }
                    tmdbCursor.close()

                    if (airDate != null && airDate.isNotEmpty() && tmdbId != -1) {
                        items.add(CalendarItem(
                            type = "movie",
                            tmdbId = tmdbId,
                            title = title,
                            releaseDate = airDate.substringBefore("T"),
                            posterPath = posterPath,
                            subtitle = context.getString(R.string.calendar_movie_release)
                        ))
                    }
                } else if (type == "episode" || type == "season") {
                    tmdbId = cursor.getInt(cursor.getColumnIndexOrThrow(TraktDatabaseHelper.COL_SHOW_TMDB))
                    val showTitle = cursor.getString(cursor.getColumnIndexOrThrow(TraktDatabaseHelper.COL_SHOW_TITLE)) ?: title
                    val season = cursor.getInt(cursor.getColumnIndexOrThrow(TraktDatabaseHelper.COL_SEASON))
                    val episode = cursor.getInt(cursor.getColumnIndexOrThrow(TraktDatabaseHelper.COL_NUMBER))
                    val epTitle = cursor.getString(cursor.getColumnIndexOrThrow(TraktDatabaseHelper.COL_EPISODE_TITLE)) ?: ""

                    val tmdbCursor = tmdbDb.query(
                        TmdbDetailsDatabaseHelper.TABLE_TMDB_DETAILS, arrayOf(TmdbDetailsDatabaseHelper.COL_POSTER_PATH),
                        "${TmdbDetailsDatabaseHelper.COL_TMDB_ID} = ? AND ${TmdbDetailsDatabaseHelper.COL_TYPE} = ?",
                        arrayOf(tmdbId.toString(), "show"), null, null, null)
                    if (tmdbCursor.moveToFirst()) {
                        posterPath = tmdbCursor.getString(tmdbCursor.getColumnIndexOrThrow(TmdbDetailsDatabaseHelper.COL_POSTER_PATH)) ?: ""
                    }
                    tmdbCursor.close()

                    if (airDate != null && airDate.isNotEmpty() && tmdbId != -1) {
                        if (type == "episode") {
                            val subtitle = String.format("S%02dE%02d", season, episode) + if (epTitle.isNotEmpty()) " - $epTitle" else ""
                            items.add(CalendarItem(
                                type = "episode",
                                tmdbId = tmdbId,
                                title = showTitle,
                                releaseDate = airDate.substringBefore("T"),
                                posterPath = posterPath,
                                season = season,
                                episode = episode,
                                subtitle = subtitle
                            ))
                        } else {
                            items.add(CalendarItem(
                                type = "season",
                                tmdbId = tmdbId,
                                title = showTitle,
                                releaseDate = airDate.substringBefore("T"),
                                posterPath = posterPath,
                                season = season,
                                subtitle = context.getString(R.string.calendar_season_premiere, season)
                            ))
                        }
                    }
                }
            } while (cursor.moveToNext())
        }
        cursor.close()
        return items
    }

    fun deduplicateData(items: List<CalendarItem>): List<CalendarItem> {
        val map = mutableMapOf<String, CalendarItem>()
        for (item in items) {
            val key = when (item.type) {
                "movie" -> "movie_${item.tmdbId}"
                "season" -> "season_${item.tmdbId}_${item.season}"
                "episode" -> "episode_${item.tmdbId}_${item.season}_${item.episode}"
                else -> "unknown_${item.hashCode()}"
            }
            if (!map.containsKey(key) || (map[key]?.posterPath.isNullOrEmpty() && item.posterPath.isNotEmpty())) {
                map[key] = item
            }
        }
        return map.values.toList()
    }

    fun getLocalDateString(isoString: String): String {
        if (!isoString.contains("T")) {
            return isoString
        }
        try {
            val isoFormat = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", java.util.Locale.US)
            isoFormat.timeZone = java.util.TimeZone.getTimeZone("UTC")
            val date = isoFormat.parse(isoString)
            if (date != null) {
                val localFormat = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.getDefault())
                return localFormat.format(date)
            }
        } catch (e: Exception) {
            // ignore
        }
        return isoString.substringBefore("T")
    }

    fun groupItemsByDay(items: List<CalendarItem>): Map<String, List<CalendarItem>> {
        val grouped = mutableMapOf<String, MutableList<CalendarItem>>()
        for (item in items) {
            val date = getLocalDateString(item.releaseDate)
            if (date.isNotEmpty()) {
                if (!grouped.containsKey(date)) {
                    grouped[date] = mutableListOf()
                }
                grouped[date]?.add(item)
            }
        }
        return grouped
    }
}
