package com.wirelessalien.android.moviedb.recommendations

import android.content.Context
import com.wirelessalien.android.moviedb.helper.MovieDatabaseHelper
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.Dispatchers
import com.wirelessalien.android.moviedb.NetworkClient
import com.wirelessalien.android.moviedb.helper.RateLimiter
import com.wirelessalien.android.moviedb.helper.ConfigHelper
import androidx.preference.PreferenceManager
import com.wirelessalien.android.moviedb.activity.BaseActivity
import okhttp3.Request

data class RecommendationSeed(
    val tmdbId: Int,
    val isMovie: Boolean,
    val title: String,
    val personalRating: Double
)

data class RecommendationResult(
    val tmdbId: Int,
    val isMovie: Boolean,
    val title: String,
    val posterPath: String?,
    val releaseYear: String,
    val tmdbScore: Double,
    val seedTitle: String,
    var finalScore: Double = 0.0
)

data class RecommendationCache(
    val timestamp: Long,
    val results: List<RecommendationResult>
)

class RecommendationEngine(private val context: Context, private val dbHelper: MovieDatabaseHelper) {

    companion object {
        private val cache = ConcurrentHashMap<Boolean, RecommendationCache>() // true for movies, false for series
        private val CACHE_EXPIRATION_MS = TimeUnit.HOURS.toMillis(12)
    }

    fun getCachedResults(isMovie: Boolean): List<RecommendationResult>? {
        val cached = cache[isMovie]
        if (cached != null && System.currentTimeMillis() - cached.timestamp < CACHE_EXPIRATION_MS) {
            return cached.results
        }
        return null
    }

    fun setCachedResults(isMovie: Boolean, results: List<RecommendationResult>) {
        cache[isMovie] = RecommendationCache(System.currentTimeMillis(), results)
    }

    fun invalidateCache() {
        cache.clear()
    }

    fun getSeeds(isMovie: Boolean): List<RecommendationSeed> {
        val db = dbHelper.readableDatabase
        val seeds = mutableListOf<RecommendationSeed>()
        val movieInt = if (isMovie) 1 else 0

        // 1. Items with personal_rating >= 8
        val queryHighlyRated = """
            SELECT ${MovieDatabaseHelper.COLUMN_MOVIES_ID}, ${MovieDatabaseHelper.COLUMN_TITLE}, ${MovieDatabaseHelper.COLUMN_PERSONAL_RATING}
            FROM ${MovieDatabaseHelper.TABLE_MOVIES}
            WHERE ${MovieDatabaseHelper.COLUMN_MOVIE} = ? AND ${MovieDatabaseHelper.COLUMN_PERSONAL_RATING} >= 8
            ORDER BY ${MovieDatabaseHelper.COLUMN_ID} DESC
            LIMIT 20
        """
        val cursorHighlyRated = db.rawQuery(queryHighlyRated, arrayOf(movieInt.toString()))
        cursorHighlyRated.use {
            while (it.moveToNext()) {
                val tmdbId = it.getInt(it.getColumnIndexOrThrow(MovieDatabaseHelper.COLUMN_MOVIES_ID))
                val title = it.getString(it.getColumnIndexOrThrow(MovieDatabaseHelper.COLUMN_TITLE)) ?: ""
                val rating = it.getDouble(it.getColumnIndexOrThrow(MovieDatabaseHelper.COLUMN_PERSONAL_RATING))
                if (seeds.none { seed -> seed.tmdbId == tmdbId }) {
                    seeds.add(RecommendationSeed(tmdbId, isMovie, title, rating))
                }
            }
        }

        // 2. Items tagged 'Favorites'
        if (seeds.size < 20) {
            val queryFavorites = """
                SELECT m.${MovieDatabaseHelper.COLUMN_MOVIES_ID}, m.${MovieDatabaseHelper.COLUMN_TITLE}, m.${MovieDatabaseHelper.COLUMN_PERSONAL_RATING}
                FROM ${MovieDatabaseHelper.TABLE_MOVIES} m
                INNER JOIN ${MovieDatabaseHelper.TABLE_MOVIE_TAGS} mt ON m.${MovieDatabaseHelper.COLUMN_MOVIES_ID} = mt.${MovieDatabaseHelper.COLUMN_MT_MOVIE_ID} AND m.${MovieDatabaseHelper.COLUMN_MOVIE} = mt.${MovieDatabaseHelper.COLUMN_MT_IS_MOVIE}
                INNER JOIN ${MovieDatabaseHelper.TABLE_TAGS} t ON mt.${MovieDatabaseHelper.COLUMN_MT_TAG_ID} = t.${MovieDatabaseHelper.COLUMN_TAG_ID}
                WHERE m.${MovieDatabaseHelper.COLUMN_MOVIE} = ? AND (t.${MovieDatabaseHelper.COLUMN_TAG_NAME} = 'Favorites' OR t.${MovieDatabaseHelper.COLUMN_TAG_NAME} = 'Favorite')
                ORDER BY m.${MovieDatabaseHelper.COLUMN_ID} DESC
                LIMIT 20
            """
            val cursorFavorites = db.rawQuery(queryFavorites, arrayOf(movieInt.toString()))
            cursorFavorites.use {
                while (it.moveToNext() && seeds.size < 20) {
                    val tmdbId = it.getInt(it.getColumnIndexOrThrow(MovieDatabaseHelper.COLUMN_MOVIES_ID))
                    val title = it.getString(it.getColumnIndexOrThrow(MovieDatabaseHelper.COLUMN_TITLE)) ?: ""
                    val rating = if (it.isNull(it.getColumnIndexOrThrow(MovieDatabaseHelper.COLUMN_PERSONAL_RATING))) 8.0 else it.getDouble(it.getColumnIndexOrThrow(MovieDatabaseHelper.COLUMN_PERSONAL_RATING))
                    if (seeds.none { seed -> seed.tmdbId == tmdbId }) {
                        seeds.add(RecommendationSeed(tmdbId, isMovie, title, rating))
                    }
                }
            }
        }

        // 3. Fallback: Watched items if seeds < 5
        if (seeds.size < 5) {
            val queryWatched = """
                SELECT ${MovieDatabaseHelper.COLUMN_MOVIES_ID}, ${MovieDatabaseHelper.COLUMN_TITLE}, ${MovieDatabaseHelper.COLUMN_PERSONAL_RATING}
                FROM ${MovieDatabaseHelper.TABLE_MOVIES}
                WHERE ${MovieDatabaseHelper.COLUMN_MOVIE} = ? AND ${MovieDatabaseHelper.COLUMN_CATEGORIES} = ?
                ORDER BY ${MovieDatabaseHelper.COLUMN_ID} DESC
                LIMIT 20
            """
            val cursorWatched = db.rawQuery(queryWatched, arrayOf(movieInt.toString(), MovieDatabaseHelper.CATEGORY_WATCHED.toString()))
            cursorWatched.use {
                while (it.moveToNext() && seeds.size < 20) {
                    val tmdbId = it.getInt(it.getColumnIndexOrThrow(MovieDatabaseHelper.COLUMN_MOVIES_ID))
                    val title = it.getString(it.getColumnIndexOrThrow(MovieDatabaseHelper.COLUMN_TITLE)) ?: ""
                    val rating = if (it.isNull(it.getColumnIndexOrThrow(MovieDatabaseHelper.COLUMN_PERSONAL_RATING))) 8.0 else it.getDouble(it.getColumnIndexOrThrow(MovieDatabaseHelper.COLUMN_PERSONAL_RATING))
                    if (seeds.none { seed -> seed.tmdbId == tmdbId }) {
                        seeds.add(RecommendationSeed(tmdbId, isMovie, title, rating))
                    }
                }
            }
        }
        return seeds.take(20)
    }

    fun getTopGenres(): List<String> {
        val db = dbHelper.readableDatabase
        val genreCounts = mutableMapOf<String, Int>()
        val query = """
            SELECT ${MovieDatabaseHelper.COLUMN_GENRES_IDS}
            FROM ${MovieDatabaseHelper.TABLE_MOVIES}
            WHERE ${MovieDatabaseHelper.COLUMN_PERSONAL_RATING} >= 7
        """
        val cursor = db.rawQuery(query, null)
        cursor.use {
            while (it.moveToNext()) {
                val genreIdsStr = it.getString(it.getColumnIndexOrThrow(MovieDatabaseHelper.COLUMN_GENRES_IDS))
                if (!genreIdsStr.isNullOrEmpty()) {
                    val ids = genreIdsStr.split(",").map { id -> id.trim() }
                    for (id in ids) {
                        if (id.isNotEmpty()) {
                            genreCounts[id] = genreCounts.getOrDefault(id, 0) + 1
                        }
                    }
                }
            }
        }
        return genreCounts.entries.sortedByDescending { it.value }.map { it.key }.take(3)
    }

    fun getExclusions(isMovie: Boolean): Set<Int> {
        val db = dbHelper.readableDatabase
        val exclusions = mutableSetOf<Int>()
        val movieInt = if (isMovie) 1 else 0

        // Exclude Watched, Watching, Dropped
        val queryCategories = """
            SELECT ${MovieDatabaseHelper.COLUMN_MOVIES_ID}
            FROM ${MovieDatabaseHelper.TABLE_MOVIES}
            WHERE ${MovieDatabaseHelper.COLUMN_MOVIE} = ? AND ${MovieDatabaseHelper.COLUMN_CATEGORIES} IN (?, ?, ?, ?)
        """
        val cursorCategories = db.rawQuery(queryCategories, arrayOf(
            movieInt.toString(),
            MovieDatabaseHelper.CATEGORY_WATCHED.toString(),
            MovieDatabaseHelper.CATEGORY_WATCHING.toString(),
            MovieDatabaseHelper.CATEGORY_DROPPED.toString()
            , MovieDatabaseHelper.CATEGORY_PLAN_TO_WATCH.toString()
        ))
        cursorCategories.use {
            while (it.moveToNext()) {
                exclusions.add(it.getInt(it.getColumnIndexOrThrow(MovieDatabaseHelper.COLUMN_MOVIES_ID)))
            }
        }

        // Exclude Not interested
        val typeStr = if (isMovie) "movie" else "tv"
        val queryNotInterested = """
            SELECT ${MovieDatabaseHelper.COLUMN_NI_TMDB_ID}
            FROM ${MovieDatabaseHelper.TABLE_NOT_INTERESTED}
            WHERE ${MovieDatabaseHelper.COLUMN_NI_TYPE} = ?
        """
        val cursorNotInterested = db.rawQuery(queryNotInterested, arrayOf(typeStr))
        cursorNotInterested.use {
            while (it.moveToNext()) {
                exclusions.add(it.getInt(it.getColumnIndexOrThrow(MovieDatabaseHelper.COLUMN_NI_TMDB_ID)))
            }
        }

        return exclusions
    }
    suspend fun getRecommendations(isMovie: Boolean): List<RecommendationResult> {
        val cached = getCachedResults(isMovie)
        if (cached != null) return cached

        val seeds = getSeeds(isMovie)
        if (seeds.isEmpty()) return emptyList()

        val topGenres = getTopGenres()
        val exclusions = getExclusions(isMovie)
        val rateLimiter = RateLimiter(10, 1, TimeUnit.SECONDS)
        val client = NetworkClient.client
        val apiKey = ConfigHelper.getConfigValue(context, "api_key")
        val apiReadAccessToken = PreferenceManager.getDefaultSharedPreferences(context).getString("tmdb_access_token", null)

        val resultsMap = mutableMapOf<Int, RecommendationResult>()
        val language = BaseActivity.getLanguageParameter2(context)

        coroutineScope {
            val deferreds = seeds.map { seed ->
                async(Dispatchers.IO) {
                    rateLimiter.acquire()
                    val mediaTypeStr = if (isMovie) "movie" else "tv"
                    val url = "https://api.themoviedb.org/3/$mediaTypeStr/${seed.tmdbId}/recommendations$language&page=1"
                    val requestBuilder = Request.Builder().url(url).get().addHeader("Content-Type", "application/json;charset=utf-8")
                    if (apiReadAccessToken != null) {
                        requestBuilder.addHeader("Authorization", "Bearer $apiReadAccessToken")
                    } else if (apiKey != null) {
                        val urlWithKey = "https://api.themoviedb.org/3/$mediaTypeStr/${seed.tmdbId}/recommendations$language&page=1&api_key=$apiKey"
                        requestBuilder.url(urlWithKey)
                    }

                    try {
                        client.newCall(requestBuilder.build()).execute().use { response ->
                            if (response.isSuccessful) {
                                val responseBody = response.body?.string()
                                if (responseBody != null) {
                                    val jsonObject = JSONObject(responseBody)
                                    val resultsArray = jsonObject.optJSONArray("results")
                                    if (resultsArray != null) {
                                        for (i in 0 until resultsArray.length()) {
                                            val item = resultsArray.optJSONObject(i)
                                            if (item != null) {
                                                val tmdbId = item.optInt("id")
                                                if (exclusions.contains(tmdbId)) continue

                                                val title = if (isMovie) item.optString("title") else item.optString("name")
                                                val posterPath = item.optString("poster_path")
                                                val releaseDate = if (isMovie) item.optString("release_date") else item.optString("first_air_date")
                                                val releaseYear = if (releaseDate.isNotEmpty()) releaseDate.substring(0, 4) else ""
                                                val tmdbScore = item.optDouble("vote_average", 0.0)

                                                var genreBonus = 0.0
                                                val genreIds = item.optJSONArray("genre_ids")
                                                if (genreIds != null) {
                                                    for (j in 0 until genreIds.length()) {
                                                        val genreId = genreIds.optInt(j).toString()
                                                        if (topGenres.contains(genreId)) {
                                                            genreBonus += 0.1
                                                        }
                                                    }
                                                }

                                                synchronized(resultsMap) {
                                                    val existing = resultsMap[tmdbId]
                                                    if (existing != null) {
                                                        existing.finalScore += (seed.personalRating / 10.0)
                                                    } else {
                                                        val result = RecommendationResult(
                                                            tmdbId = tmdbId,
                                                            isMovie = isMovie,
                                                            title = title,
                                                            posterPath = posterPath,
                                                            releaseYear = releaseYear,
                                                            tmdbScore = tmdbScore,
                                                            seedTitle = seed.title,
                                                            finalScore = (seed.personalRating / 10.0) + genreBonus
                                                        )
                                                        resultsMap[tmdbId] = result
                                                    }
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }
                }
            }
            deferreds.forEach { it.await() }
        }

        val finalResults = resultsMap.values.sortedByDescending { it.finalScore }.take(60)
        setCachedResults(isMovie, finalResults)
        return finalResults
    }
}
