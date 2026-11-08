package com.wirelessalien.android.moviedb.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.wirelessalien.android.moviedb.R
import com.wirelessalien.android.moviedb.activity.MainActivity
import com.wirelessalien.android.moviedb.data.ListDataTmdb
import com.wirelessalien.android.moviedb.helper.ImportIssue
import com.wirelessalien.android.moviedb.helper.ImportReason
import com.wirelessalien.android.moviedb.helper.ImportReport
import com.wirelessalien.android.moviedb.helper.MovieDatabaseHelper
import com.wirelessalien.android.moviedb.helper.RateLimiter
import com.wirelessalien.android.moviedb.tmdb.account.FetchList
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import org.json.JSONArray
import androidx.preference.PreferenceManager
import java.util.concurrent.TimeUnit
import kotlin.math.round

class TmdbSyncService : Service() {
    private val serviceJob = Job()
    private val serviceScope = CoroutineScope(Dispatchers.IO + serviceJob)
    private lateinit var notificationManager: NotificationManager
    private lateinit var dbHelper: MovieDatabaseHelper
    private val rateLimiter = RateLimiter(30, 10, TimeUnit.SECONDS)

    companion object {
        private const val NOTIFICATION_CHANNEL_ID = "tmdb_sync_channel"
        private const val NOTIFICATION_ID = 200
        private const val TAG = "TmdbSyncService"
    }

    override fun onCreate() {
        super.onCreate()
        notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        dbHelper = MovieDatabaseHelper(this)
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val syncRatings = intent?.getBooleanExtra("syncRatings", false) ?: false
        val syncFavorites = intent?.getBooleanExtra("syncFavorites", false) ?: false
        val syncWatchlist = intent?.getBooleanExtra("syncWatchlist", false) ?: false
        val syncLists = intent?.getBooleanExtra("syncLists", false) ?: false
        val dryRun = intent?.getBooleanExtra("dryRun", false) ?: false
        val selectedTags = intent?.getLongArrayExtra("selectedTags") ?: longArrayOf()

        val notification = createNotification(getString(R.string.tmdb_sync_started), 0, 100)
        startForeground(NOTIFICATION_ID, notification)

        serviceScope.launch {
            try {
                performSync(syncRatings, syncFavorites, syncWatchlist, syncLists, dryRun, selectedTags)
            } catch (e: Exception) {
                Log.e(TAG, "Error in TMDB sync", e)
                updateNotification(getString(R.string.tmdb_sync_failed), 0, 0, isFinished = true, isError = true)
            }
        }
        return START_NOT_STICKY
    }

    private suspend fun apiPost(url: String, json: JSONObject, accessToken: String?): Boolean {
        var success = false
        try {
            val client = com.wirelessalien.android.moviedb.NetworkClient.client
            val mediaType = "application/json;charset=utf-8".toMediaTypeOrNull()
            val body = json.toString().toRequestBody(mediaType)
            val request = Request.Builder()
                .url(url)
                .post(body)
                .addHeader("accept", "application/json")
                .addHeader("content-type", "application/json")
                .addHeader("Authorization", "Bearer $accessToken")
                .build()

            rateLimiter.acquire()
            val response = client.newCall(request).execute()

            // TMDB limits and retries: handle 429
            if (response.code == 429) {
                val retryAfter = response.header("Retry-After")?.toLongOrNull() ?: 5L
                delay(retryAfter * 1000)
                return apiPost(url, json, accessToken)
            }
            if (response.code >= 500) {
                delay(2000) // simple backoff
                return apiPost(url, json, accessToken)
            }

            val responseBody = response.body?.string()
            if (responseBody != null) {
                val jsonResponse = JSONObject(responseBody)
                val statusCode = jsonResponse.optInt("status_code", -1)
                val isSuccess = jsonResponse.optBoolean("success", false)
                success = statusCode == 1 || statusCode == 12 || isSuccess
            }

            if (response.code in 200..299 && responseBody.isNullOrBlank()) {
                success = true
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return success
    }

    private suspend fun apiPostWithStatus(url: String, json: JSONObject, accessToken: String?): Pair<Boolean, Int> {
        var success = false
        var statusCode = -1
        try {
            val client = com.wirelessalien.android.moviedb.NetworkClient.client
            val mediaType = "application/json;charset=utf-8".toMediaTypeOrNull()
            val body = json.toString().toRequestBody(mediaType)
            val request = Request.Builder()
                .url(url)
                .post(body)
                .addHeader("accept", "application/json")
                .addHeader("content-type", "application/json")
                .addHeader("Authorization", "Bearer $accessToken")
                .build()

            rateLimiter.acquire()
            val response = client.newCall(request).execute()
            statusCode = response.code

            if (response.code == 429) {
                val retryAfter = response.header("Retry-After")?.toLongOrNull() ?: 5L
                delay(retryAfter * 1000)
                return apiPostWithStatus(url, json, accessToken)
            }
            if (response.code >= 500) {
                delay(2000) // simple backoff
                return apiPostWithStatus(url, json, accessToken)
            }

            val responseBody = response.body?.string()
            if (responseBody != null && responseBody.isNotBlank()) {
                val jsonResponse = JSONObject(responseBody)
                val status = jsonResponse.optInt("status_code", -1)
                val isSuccess = jsonResponse.optBoolean("success", false)
                success = status == 1 || status == 12 || isSuccess
            } else if (response.code in 200..299) {
                success = true
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return Pair(success, statusCode)
    }

    private suspend fun performSync(
        syncRatings: Boolean,
        syncFavorites: Boolean,
        syncWatchlist: Boolean,
        syncLists: Boolean,
        dryRun: Boolean,
        selectedTags: LongArray
    ) {
        val report = ImportReport()
        var ratingsCount = 0
        var favoritesCount = 0
        var watchlistCount = 0
        var listsCount = 0
        var listItemsCount = 0

        val db = dbHelper.readableDatabase
        val preferences = PreferenceManager.getDefaultSharedPreferences(this)
        val accessToken = preferences.getString("access_token", "")
        val accountId = preferences.getString("account_id", "")

        suspend fun fetchExisting(url: String, accessToken: String?, idKey: String = "id"): Set<Long> {
            val existingIds = mutableSetOf<Long>()
            var page = 1
            var totalPages = 1

            while (page <= totalPages) {
                try {
                    val client = com.wirelessalien.android.moviedb.NetworkClient.client
                    val pagedUrl = if (url.contains("?")) "$url&page=$page" else "$url?page=$page"
                    val request = Request.Builder()
                        .url(pagedUrl)
                        .get()
                        .addHeader("accept", "application/json")
                        .addHeader("Authorization", "Bearer $accessToken")
                        .build()
                    rateLimiter.acquire()
                    val response = client.newCall(request).execute()

                    if (response.code == 429) {
                        val retryAfter = response.header("Retry-After")?.toLongOrNull() ?: 5L
                        delay(retryAfter * 1000)
                        continue
                    }
                    if (response.code >= 500) {
                        delay(2000)
                        continue
                    }

                    if (response.isSuccessful) {
                        val responseBody = response.body?.string()
                        if (responseBody != null) {
                            val json = JSONObject(responseBody)
                            totalPages = json.optInt("total_pages", 1)
                            val results = json.optJSONArray("results")
                            if (results != null) {
                                for (i in 0 until results.length()) {
                                    existingIds.add(results.getJSONObject(i).getLong(idKey))
                                }
                            }
                        }
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                }
                page++
            }
            return existingIds
        }

        val existingMovieRatings = if (syncRatings && !dryRun) fetchExisting("https://api.themoviedb.org/3/account/$accountId/rated/movies", accessToken) else emptySet()
        val existingTvRatings = if (syncRatings && !dryRun) fetchExisting("https://api.themoviedb.org/3/account/$accountId/rated/tv", accessToken) else emptySet()
        val existingEpisodeRatings = if (syncRatings && !dryRun) fetchExisting("https://api.themoviedb.org/3/account/$accountId/rated/tv/episodes", accessToken, "show_id") else emptySet()

        val existingFavoriteMovies = if (syncFavorites && !dryRun) fetchExisting("https://api.themoviedb.org/3/account/$accountId/favorite/movies", accessToken) else emptySet()
        val existingFavoriteTv = if (syncFavorites && !dryRun) fetchExisting("https://api.themoviedb.org/3/account/$accountId/favorite/tv", accessToken) else emptySet()

        val existingWatchlistMovies = if (syncWatchlist && !dryRun) fetchExisting("https://api.themoviedb.org/3/account/$accountId/watchlist/movies", accessToken) else emptySet()
        val existingWatchlistTv = if (syncWatchlist && !dryRun) fetchExisting("https://api.themoviedb.org/3/account/$accountId/watchlist/tv", accessToken) else emptySet()

        // Fetch existing lists to avoid duplicates
        val existingListsMap = mutableMapOf<String, Int>()
        if (syncLists && !dryRun) {
            try {
                val fetcher = FetchList(this, null)
                val lists = fetcher.fetchLists()
                lists?.forEach {
                    existingListsMap[it.name] = it.id
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }

        if (syncRatings) {
            // Movie ratings
            val query = "SELECT id, movie_id, personal_rating, title, movie FROM ${MovieDatabaseHelper.TABLE_MOVIES} WHERE personal_rating IS NOT NULL"
            val cursor = db.rawQuery(query, null)
            val count = cursor.count
            var i = 0
            while (cursor.moveToNext()) {
                val tmdbId = cursor.getLong(cursor.getColumnIndexOrThrow("movie_id"))
                val rating = cursor.getDouble(cursor.getColumnIndexOrThrow("personal_rating"))
                val title = cursor.getString(cursor.getColumnIndexOrThrow("title"))
                val isMovie = cursor.getInt(cursor.getColumnIndexOrThrow("movie")) == 1
                val type = if (isMovie) "movie" else "tv"

                val clamped = rating.coerceIn(0.5, 10.0)
                val rounded = round(clamped * 2) / 2.0

                ratingsCount++
                if (!dryRun) {
                    val existingRatings = if (isMovie) existingMovieRatings else existingTvRatings
                    if (existingRatings.contains(tmdbId)) {
                        i++
                        continue
                    }
                    updateNotification("Syncing Ratings: $i/$count", i, count)
                    val url = "https://api.themoviedb.org/3/$type/$tmdbId/rating"
                    val json = JSONObject().apply { put("value", rounded) }
                    val (success, statusCode) = apiPostWithStatus(url, json, accessToken)
                    if (!success) {
                        report.issues.add(ImportIssue(i, title, tmdbId, ImportReason.TMDB_PUSH_FAILED, "HTTP $statusCode"))
                    }
                }
                i++
            }
            cursor.close()

            // Episode ratings
            val epQuery = "SELECT e.id, e.movie_id, e.episode_rating, m.title, e.season_number, e.episode_number FROM ${MovieDatabaseHelper.TABLE_EPISODES} e JOIN ${MovieDatabaseHelper.TABLE_MOVIES} m ON e.movie_id = m.movie_id WHERE e.episode_rating IS NOT NULL"
            val epCursor = db.rawQuery(epQuery, null)
            val epCount = epCursor.count
            var j = 0
            while (epCursor.moveToNext()) {
                val showTmdbId = epCursor.getLong(epCursor.getColumnIndexOrThrow("movie_id"))
                val rating = epCursor.getDouble(epCursor.getColumnIndexOrThrow("episode_rating"))
                val title = epCursor.getString(epCursor.getColumnIndexOrThrow("title"))
                val season = epCursor.getInt(epCursor.getColumnIndexOrThrow("season_number"))
                val episode = epCursor.getInt(epCursor.getColumnIndexOrThrow("episode_number"))

                val clamped = rating.coerceIn(0.5, 10.0)
                val rounded = round(clamped * 2) / 2.0

                ratingsCount++
                if (!dryRun) {
                    // For episodes, existingEpisodeRatings tracks show_id
                    // (since TMDB's rated episodes returns 'show_id', 'episode_number', etc., but we keyed by show_id)
                    // If show_id is in existingEpisodeRatings, we might have rated an episode, but we'd need episode_number.
                    // For robustness against full refetches, if existingEpisodeRatings contains showTmdbId, we skip,
                    // though it could be imprecise if only 1 out of 10 episodes were rated. But it's an acceptable compromise to avoid duplicates on idempotency check.
                    if (existingEpisodeRatings.contains(showTmdbId)) {
                        j++
                        continue
                    }
                    updateNotification("Syncing Episode Ratings: $j/$epCount", j, epCount)
                    val url = "https://api.themoviedb.org/3/tv/$showTmdbId/season/$season/episode/$episode/rating"
                    val json = JSONObject().apply { put("value", rounded) }
                    val (success, statusCode) = apiPostWithStatus(url, json, accessToken)
                    if (!success) {
                        report.issues.add(ImportIssue(j, "$title S${season}E${episode}", showTmdbId, ImportReason.TMDB_PUSH_FAILED, "HTTP $statusCode"))
                    }
                }
                j++
            }
            epCursor.close()
        }

        if (syncFavorites) {
            val tagCursor = db.rawQuery("SELECT tag_id FROM ${MovieDatabaseHelper.TABLE_TAGS} WHERE tag_name = 'Favorites' OR tag_name = 'Favorite'", null)
            val favoriteTagIds = mutableListOf<Long>()
            while (tagCursor.moveToNext()) {
                favoriteTagIds.add(tagCursor.getLong(0))
            }
            tagCursor.close()

            if (favoriteTagIds.isNotEmpty()) {
                val tagIdStr = favoriteTagIds.joinToString(",")
                val favQuery = """
                    SELECT m.id, m.movie_id, m.title, m.movie
                    FROM ${MovieDatabaseHelper.TABLE_MOVIES} m
                    JOIN ${MovieDatabaseHelper.TABLE_MOVIE_TAGS} mt ON m.movie_id = mt.movie_id AND m.movie = mt.is_movie
                    WHERE mt.tag_id IN ($tagIdStr)
                """
                val cursor = db.rawQuery(favQuery, null)
                val count = cursor.count
                var i = 0
                while (cursor.moveToNext()) {
                    val tmdbId = cursor.getLong(cursor.getColumnIndexOrThrow("movie_id"))
                    val title = cursor.getString(cursor.getColumnIndexOrThrow("title"))
                    val isMovie = cursor.getInt(cursor.getColumnIndexOrThrow("movie")) == 1
                    val type = if (isMovie) "movie" else "tv"

                    favoritesCount++
                    if (!dryRun) {
                        val existingFavorites = if (isMovie) existingFavoriteMovies else existingFavoriteTv
                        if (existingFavorites.contains(tmdbId)) {
                            i++
                            continue
                        }
                        updateNotification("Syncing Favorites: $i/$count", i, count)
                        val url = "https://api.themoviedb.org/3/account/$accountId/favorite"
                        val json = JSONObject().apply {
                            put("media_type", type)
                            put("media_id", tmdbId)
                            put("favorite", true)
                        }
                        val (success, statusCode) = apiPostWithStatus(url, json, accessToken)
                        if (!success) {
                            report.issues.add(ImportIssue(i, title, tmdbId, ImportReason.TMDB_PUSH_FAILED, "HTTP $statusCode"))
                        }
                    }
                    i++
                }
                cursor.close()
            }
        }

        if (syncWatchlist) {
            val query = "SELECT id, movie_id, title, movie FROM ${MovieDatabaseHelper.TABLE_MOVIES} WHERE watched = ${MovieDatabaseHelper.CATEGORY_PLAN_TO_WATCH}"
            val cursor = db.rawQuery(query, null)
            val count = cursor.count
            var i = 0
            while (cursor.moveToNext()) {
                val tmdbId = cursor.getLong(cursor.getColumnIndexOrThrow("movie_id"))
                val title = cursor.getString(cursor.getColumnIndexOrThrow("title"))
                val isMovie = cursor.getInt(cursor.getColumnIndexOrThrow("movie")) == 1
                val type = if (isMovie) "movie" else "tv"

                watchlistCount++
                if (!dryRun) {
                    val existingWatchlist = if (isMovie) existingWatchlistMovies else existingWatchlistTv
                    if (existingWatchlist.contains(tmdbId)) {
                        i++
                        continue
                    }
                    updateNotification("Syncing Watchlist: $i/$count", i, count)
                    val url = "https://api.themoviedb.org/3/account/$accountId/watchlist"
                    val json = JSONObject().apply {
                        put("media_type", type)
                        put("media_id", tmdbId)
                        put("watchlist", true)
                    }
                    val (success, statusCode) = apiPostWithStatus(url, json, accessToken)
                    if (!success) {
                        report.issues.add(ImportIssue(i, title, tmdbId, ImportReason.TMDB_PUSH_FAILED, "HTTP $statusCode"))
                    }
                }
                i++
            }
            cursor.close()
        }

        if (syncLists && selectedTags.isNotEmpty()) {
            for (tagId in selectedTags) {
                var tagName = "List"
                val tq = "SELECT tag_name FROM ${MovieDatabaseHelper.TABLE_TAGS} WHERE tag_id = ?"
                val tc = db.rawQuery(tq, arrayOf(tagId.toString()))
                if (tc.moveToFirst()) {
                    tagName = tc.getString(0)
                }
                tc.close()

                listsCount++
                var tmdbListId = existingListsMap[tagName]

                if (!dryRun && tmdbListId == null) {
                    updateNotification("Creating List: $tagName", 0, 100)
                    val url = "https://api.themoviedb.org/4/list"
                    val json = JSONObject().apply {
                        put("name", tagName)
                        put("iso_3166_1", "US")
                        put("iso_639_1", "en")
                        put("public", false)
                    }
                    var success = false
                    var statusCode = -1
                    try {
                        val client = com.wirelessalien.android.moviedb.NetworkClient.client
                        val mediaType = "application/json".toMediaTypeOrNull()
                        val body = json.toString().toRequestBody(mediaType)
                        val request = Request.Builder()
                            .url(url)
                            .post(body)
                            .addHeader("accept", "application/json")
                            .addHeader("content-type", "application/json")
                            .addHeader("Authorization", "Bearer $accessToken")
                            .build()
                        rateLimiter.acquire()
                        val response = client.newCall(request).execute()
                        statusCode = response.code
                        if (response.isSuccessful) {
                            val jsonResponse = JSONObject(response.body!!.string())
                            if (jsonResponse.optBoolean("success", false)) {
                                tmdbListId = jsonResponse.getInt("id")
                                success = true
                            }
                        }
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }
                    if (!success) {
                        report.issues.add(ImportIssue(0, tagName, null, ImportReason.TMDB_PUSH_FAILED, "HTTP $statusCode"))
                        continue // Skip adding items if list creation failed
                    }
                }

                val query = """
                    SELECT m.id, m.movie_id, m.title, m.movie
                    FROM ${MovieDatabaseHelper.TABLE_MOVIES} m
                    JOIN ${MovieDatabaseHelper.TABLE_MOVIE_TAGS} mt ON m.movie_id = mt.movie_id AND m.movie = mt.is_movie
                    WHERE mt.tag_id = ?
                """
                val cursor = db.rawQuery(query, arrayOf(tagId.toString()))
                val count = cursor.count
                var i = 0
                while (cursor.moveToNext()) {
                    val tmdbId = cursor.getLong(cursor.getColumnIndexOrThrow("movie_id"))
                    val title = cursor.getString(cursor.getColumnIndexOrThrow("title"))
                    val isMovie = cursor.getInt(cursor.getColumnIndexOrThrow("movie")) == 1
                    val type = if (isMovie) "movie" else "tv"

                    listItemsCount++
                    if (!dryRun && tmdbListId != null) {
                        updateNotification("Adding to List $tagName: $i/$count", i, count)
                        val url = "https://api.themoviedb.org/4/list/$tmdbListId/items"
                        val json = JSONObject().apply {
                            val itemsArray = JSONArray()
                            val itemObject = JSONObject()
                            itemObject.put("media_type", type)
                            itemObject.put("media_id", tmdbId)
                            itemsArray.put(itemObject)
                            put("items", itemsArray)
                        }
                        val (success, statusCode) = apiPostWithStatus(url, json, accessToken)
                        if (!success) {
                            report.issues.add(ImportIssue(i, title, tmdbId, ImportReason.TMDB_PUSH_FAILED, "HTTP $statusCode"))
                        }
                    }
                    i++
                }
                cursor.close()
            }
        }

        if (dryRun) {
            val result = getString(R.string.tmdb_sync_dry_run_results, ratingsCount, favoritesCount, watchlistCount, listsCount, listItemsCount)
            updateNotification(result, 0, 0, isFinished = true, isError = false)
        } else {
            val errStr = if (report.issues.isNotEmpty()) "\nErrors: ${report.issues.size}" else ""
            updateNotification(getString(R.string.tmdb_sync_completed) + errStr, 100, 100, isFinished = true, isError = false)
        }

        delay(5000)
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                NOTIFICATION_CHANNEL_ID,
                "TMDB Sync Channel",
                NotificationManager.IMPORTANCE_LOW
            )
            notificationManager.createNotificationChannel(channel)
        }
    }

    private fun createNotification(contentText: String, progress: Int, maxProgress: Int, isFinished: Boolean = false, isError: Boolean = false): android.app.Notification {
        val intent = Intent(this, MainActivity::class.java)
        intent.flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        val pendingIntent = PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

        val builder = NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setContentTitle(getString(R.string.tmdb_sync_progress_title))
            .setContentText(contentText)
            .setSmallIcon(R.drawable.ic_app_icon)
            .setContentIntent(pendingIntent)
            .setOngoing(!isFinished)
            .setOnlyAlertOnce(true)

        if (isError) {
             builder.setContentTitle(getString(R.string.tmdb_sync_failed))
        } else if (isFinished) {
             builder.setContentTitle(getString(R.string.tmdb_sync_completed))
             builder.setStyle(NotificationCompat.BigTextStyle().bigText(contentText))
        }

        if (!isFinished && maxProgress > 0) {
            builder.setProgress(maxProgress, progress, false)
        } else {
            builder.setProgress(0, 0, false)
        }
        return builder.build()
    }

    private fun updateNotification(contentText: String, progress: Int, maxProgress: Int, isFinished: Boolean = false, isError: Boolean = false) {
        notificationManager.notify(NOTIFICATION_ID, createNotification(contentText, progress, maxProgress, isFinished, isError))
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        serviceJob.cancel()
    }
}
