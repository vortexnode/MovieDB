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

package com.wirelessalien.android.moviedb.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.os.Environment
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.preference.PreferenceManager
import com.google.gson.Gson
import com.wirelessalien.android.moviedb.R
import com.wirelessalien.android.moviedb.activity.MainActivity
import com.wirelessalien.android.moviedb.activity.ImportReportActivity
import com.wirelessalien.android.moviedb.helper.ConfigHelper
import com.wirelessalien.android.moviedb.helper.ImportIssue
import com.wirelessalien.android.moviedb.helper.ImportReason
import com.wirelessalien.android.moviedb.helper.ImportReport
import com.wirelessalien.android.moviedb.helper.MovieDatabaseHelper
import com.wirelessalien.android.moviedb.helper.RateLimiter
import com.wirelessalien.android.moviedb.helper.TraktImporter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

class TraktImportService : Service() {

    private val serviceJob = Job()
    private val serviceScope = CoroutineScope(Dispatchers.IO + serviceJob)

    private lateinit var notificationManager: NotificationManager
    private lateinit var movieDbHelper: MovieDatabaseHelper
    private lateinit var traktImporter: TraktImporter
    private val rateLimiter = RateLimiter(10, 1, TimeUnit.SECONDS)
    private val httpClient = com.wirelessalien.android.moviedb.NetworkClient.client
    private var accessToken: String? = null
    private var tmdbApiKey: String? = null

    companion object {
        const val ACTION_START_TRAKT_IMPORT = "com.wirelessalien.android.moviedb.service.action.START_TRAKT_IMPORT"
        const val EXTRA_IMPORT_WATCHED = "extra_import_watched"
        const val EXTRA_IMPORT_RATINGS = "extra_import_ratings"
        const val EXTRA_IMPORT_WATCHLIST = "extra_import_watchlist"
        const val EXTRA_IMPORT_COLLECTION = "extra_import_collection"
        const val EXTRA_IMPORT_FAVORITES = "extra_import_favorites"
        const val EXTRA_IMPORT_CUSTOM_LISTS = "extra_import_custom_lists"

        private const val NOTIFICATION_CHANNEL_ID = "TraktImportServiceChannel"
        private const val NOTIFICATION_ID = 102
        private const val TAG = "TraktImportService"

        private const val TRAKT_API_URL = "https://api.trakt.tv"
    }

    override fun onCreate() {
        super.onCreate()
        notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        movieDbHelper = MovieDatabaseHelper(applicationContext)
        traktImporter = TraktImporter(movieDbHelper)

        val prefs = PreferenceManager.getDefaultSharedPreferences(this)
        accessToken = prefs.getString("trakt_access_token", null)
        tmdbApiKey = ConfigHelper.getConfigValue(applicationContext, "api_key")

        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_START_TRAKT_IMPORT) {
            val doWatched = intent.getBooleanExtra(EXTRA_IMPORT_WATCHED, false)
            val doRatings = intent.getBooleanExtra(EXTRA_IMPORT_RATINGS, false)
            val doWatchlist = intent.getBooleanExtra(EXTRA_IMPORT_WATCHLIST, false)
            val doCollection = intent.getBooleanExtra(EXTRA_IMPORT_COLLECTION, false)
            val doFavorites = intent.getBooleanExtra(EXTRA_IMPORT_FAVORITES, false)
            val doCustomLists = intent.getBooleanExtra(EXTRA_IMPORT_CUSTOM_LISTS, false)

            startForeground(NOTIFICATION_ID, createNotification("Starting Trakt Import...", 0, 0))

            serviceScope.launch {
                processImport(doWatched, doRatings, doWatchlist, doCollection, doFavorites, doCustomLists)
            }
        }
        return START_NOT_STICKY
    }

    private suspend fun processImport(
        doWatched: Boolean, doRatings: Boolean, doWatchlist: Boolean,
        doCollection: Boolean, doFavorites: Boolean, doCustomLists: Boolean
    ) {
        val report = ImportReport()
        var currentProgress = 0
        val totalStepsEstimate = 1

        if (accessToken.isNullOrEmpty()) {
            updateNotification("Error: Trakt access token missing", 0, 0, true, true)
            stopSelf()
            return
        }

        try {
            if (doWatched) {
                updateNotification("Fetching Watched Movies", currentProgress, totalStepsEstimate)
                fetchPaginatedData("/sync/watched/movies", report) { item, rep, row ->
                    traktImporter.processWatchedMovie(item, rep, row)
                }

                updateNotification("Fetching Watched Shows", currentProgress, totalStepsEstimate)
                fetchPaginatedData("/sync/watched/shows", report) { item, rep, row ->
                    traktImporter.processWatchedShow(item, rep, row)
                }
            }

            if (doRatings) {
                updateNotification("Fetching Ratings", currentProgress, totalStepsEstimate)
                fetchPaginatedData("/sync/ratings", report) { item, rep, row ->
                    traktImporter.processRating(item, rep, row)
                }
            }

            if (doWatchlist) {
                updateNotification("Fetching Watchlist", currentProgress, totalStepsEstimate)
                fetchPaginatedData("/sync/watchlist", report) { item, rep, row ->
                    traktImporter.processWatchlist(item, rep, row)
                }
            }

            if (doCollection) {
                updateNotification("Fetching Collection Movies", currentProgress, totalStepsEstimate)
                fetchPaginatedData("/sync/collection/movies", report) { item, rep, row ->
                    traktImporter.processTagItem(item, "Collection", rep, row)
                }
                updateNotification("Fetching Collection Shows", currentProgress, totalStepsEstimate)
                fetchPaginatedData("/sync/collection/shows", report) { item, rep, row ->
                    traktImporter.processTagItem(item, "Collection", rep, row)
                }
            }

            if (doFavorites) {
                updateNotification("Fetching Favorites", currentProgress, totalStepsEstimate)
                fetchPaginatedData("/sync/favorites", report) { item, rep, row ->
                    traktImporter.processTagItem(item, "Favorites", rep, row)
                }
            }

            if (doCustomLists) {
                updateNotification("Fetching Custom Lists", currentProgress, totalStepsEstimate)
                fetchCustomLists(report)
            }

            updateNotification("Fetching Missing TMDB Metadata", currentProgress, totalStepsEstimate)
            fetchMissingTmdbMetadata(report)

            // Save report
            val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
            val downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            val showcaseDir = File(downloadsDir, "ShowCase")
            if (!showcaseDir.exists()) showcaseDir.mkdirs()

            val jsonFile = File(showcaseDir, "trakt_import_report_$timestamp.json")
            FileWriter(jsonFile).use { writer ->
                writer.write(Gson().toJson(report))
            }

            updateNotification("Import Complete", 100, 100, true, false, jsonFile.absolutePath)
        } catch (e: Exception) {
            Log.e(TAG, "Error during Trakt Import", e)
            updateNotification("Import Failed: ${e.message}", 0, 0, true, true)
        }

        serviceScope.launch {
            delay(5000)
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    private suspend fun fetchCustomLists(report: ImportReport) {
        val request = Request.Builder()
            .url("$TRAKT_API_URL/users/me/lists")
            .addHeader("Content-Type", "application/json")
            .addHeader("Authorization", "Bearer $accessToken")
            .addHeader("trakt-api-version", "2")
            .addHeader("trakt-api-key", ConfigHelper.getConfigValue(applicationContext, "client_id") ?: "")
            .get()
            .build()

        try {
            val response = executeWithTraktRetry(request)
            if (response.isSuccessful) {
                val responseBody = response.body?.string() ?: return
                val listsArray = JSONArray(responseBody)
                for (i in 0 until listsArray.length()) {
                    val listObj = listsArray.getJSONObject(i)
                    val listId = listObj.optString("ids").let { JSONObject(it).optString("trakt") }
                    val listName = listObj.optString("name")
                    if (listId.isNotBlank() && listName.isNotBlank()) {
                        updateNotification("Fetching List: $listName", 0, 100)
                        fetchPaginatedData("/users/me/lists/$listId/items", report) { item, rep, row ->
                            traktImporter.processTagItem(item, listName, rep, row)
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed fetching lists", e)
        }
    }

    private suspend fun fetchPaginatedData(
        endpoint: String,
        report: ImportReport,
        processItem: (JSONObject, ImportReport, Int) -> Unit
    ) {
        var page = 1
        val limit = 100
        var totalPages = 1
        var rowNumber = 1

        while (page <= totalPages) {
            val url = "$TRAKT_API_URL$endpoint?page=$page&limit=$limit&extended=metadata"
            val request = Request.Builder()
                .url(url)
                .addHeader("Content-Type", "application/json")
                .addHeader("Authorization", "Bearer $accessToken")
                .addHeader("trakt-api-version", "2")
                .addHeader("trakt-api-key", ConfigHelper.getConfigValue(applicationContext, "client_id") ?: "")
                .get()
                .build()

            try {
                val response = executeWithTraktRetry(request)
                if (!response.isSuccessful) {
                    Log.e(TAG, "Request failed for $url: ${response.code}")
                    break
                }

                val pageCountHeader = response.header("X-Pagination-Page-Count")
                if (pageCountHeader != null) {
                    totalPages = pageCountHeader.toIntOrNull() ?: 1
                }

                val body = response.body?.string()
                if (!body.isNullOrBlank()) {
                    val array = JSONArray(body)
                    report.total += array.length()
                    for (i in 0 until array.length()) {
                        val item = array.getJSONObject(i)
                        processItem(item, report, rowNumber++)
                    }
                }
                page++
            } catch (e: Exception) {
                Log.e(TAG, "Error fetching page $page for $endpoint", e)
                break
            }
        }
    }

    private suspend fun executeWithTraktRetry(request: Request): Response {
        var response = httpClient.newCall(request).execute()
        var retryCount = 0
        while (response.code == 429 && retryCount < 3) {
            val retryAfterStr = response.header("Retry-After")
            val retryAfterMs = (retryAfterStr?.toLongOrNull() ?: 1L) * 1000L + 500L
            Log.w(TAG, "Rate limited 429. Retrying after $retryAfterMs ms")
            response.close()
            delay(retryAfterMs)
            response = httpClient.newCall(request).execute()
            retryCount++
        }
        if (response.code == 401) {
            val refreshed = refreshTraktToken()
            if (refreshed) {
                val newRequest = request.newBuilder().header("Authorization", "Bearer $accessToken").build()
                response.close()
                return httpClient.newCall(newRequest).execute()
            }
        }
        return response
    }

    private suspend fun refreshTraktToken(): Boolean {
        val prefs = PreferenceManager.getDefaultSharedPreferences(this)
        val refreshToken = prefs.getString("trakt_refresh_token", null) ?: return false
        val clientId = ConfigHelper.getConfigValue(this, "client_id")
        val clientSecret = ConfigHelper.getConfigValue(this, "client_secret")

        if (clientId.isNullOrEmpty() || clientSecret.isNullOrEmpty()) return false

        val json = JSONObject().apply {
            put("refresh_token", refreshToken)
            put("client_id", clientId)
            put("client_secret", clientSecret)
            put("grant_type", "refresh_token")
        }

        val request = Request.Builder()
            .url("https://api.trakt.tv/oauth/token")
            .post(json.toString().toRequestBody("application/json".toMediaTypeOrNull()))
            .build()

        try {
            val response = httpClient.newCall(request).execute()
            if (response.isSuccessful) {
                val body = response.body?.string() ?: return false
                val resJson = JSONObject(body)
                val newToken = resJson.getString("access_token")
                val newRefresh = resJson.getString("refresh_token")

                prefs.edit().putString("trakt_access_token", newToken)
                    .putString("trakt_refresh_token", newRefresh).apply()

                this.accessToken = newToken
                return true
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to refresh token", e)
        }
        return false
    }

    private suspend fun fetchMissingTmdbMetadata(report: ImportReport) {
        val db = movieDbHelper.readableDatabase
        val cursor = db.query(MovieDatabaseHelper.TABLE_MOVIES, arrayOf(MovieDatabaseHelper.COLUMN_MOVIES_ID, MovieDatabaseHelper.COLUMN_MOVIES_ID, MovieDatabaseHelper.COLUMN_MOVIE, MovieDatabaseHelper.COLUMN_TITLE, MovieDatabaseHelper.COLUMN_SUMMARY, MovieDatabaseHelper.COLUMN_RATING, MovieDatabaseHelper.COLUMN_ICON, MovieDatabaseHelper.COLUMN_IMAGE, MovieDatabaseHelper.COLUMN_GENRES_IDS), null, null, null, null, null)

        val rowsToUpdate = mutableListOf<android.content.ContentValues>()

        while (cursor.moveToNext()) {
            val internalId = cursor.getLong(cursor.getColumnIndexOrThrow(MovieDatabaseHelper.COLUMN_MOVIES_ID))
            val tmdbId = cursor.getLong(cursor.getColumnIndexOrThrow(MovieDatabaseHelper.COLUMN_MOVIES_ID))
            val isMovie = cursor.getInt(cursor.getColumnIndexOrThrow(MovieDatabaseHelper.COLUMN_MOVIE)) == 1
            val title = cursor.getString(cursor.getColumnIndexOrThrow(MovieDatabaseHelper.COLUMN_TITLE))
            val summary = cursor.getString(cursor.getColumnIndexOrThrow(MovieDatabaseHelper.COLUMN_SUMMARY))
            val rating = cursor.getDouble(cursor.getColumnIndexOrThrow(MovieDatabaseHelper.COLUMN_RATING))
            val poster = cursor.getString(cursor.getColumnIndexOrThrow(MovieDatabaseHelper.COLUMN_ICON))
            val backdrop = cursor.getString(cursor.getColumnIndexOrThrow(MovieDatabaseHelper.COLUMN_IMAGE))
            val genres = cursor.getString(cursor.getColumnIndexOrThrow(MovieDatabaseHelper.COLUMN_GENRES_IDS))

            // Only update TMDB fields if a TMDB fetch is truly needed for this entry based on imported Trakt data
            val needsTitle = title.isNullOrBlank() || title == "Unknown" || title == "N/A"
            val needsSummary = summary == "N/A" || summary.isNullOrBlank()
            val needsRating = rating == 0.0
            val needsPoster = poster.isNullOrBlank()
            val needsBackdrop = backdrop.isNullOrBlank()
            val needsGenres = genres == "[]" || genres.isNullOrBlank()

            if (tmdbId > 0 && (needsTitle || needsSummary || needsRating || needsPoster || needsBackdrop || needsGenres)) {
                val tmdbJson = fetchTmdbData(tmdbId, isMovie)
                if (tmdbJson != null) {
                    val cv = android.content.ContentValues()
                    cv.put(MovieDatabaseHelper.COLUMN_MOVIES_ID, internalId)

                    if (needsTitle) {
                        val fetchedTitle = if (isMovie) tmdbJson.optString("title") else tmdbJson.optString("name")
                        if (!fetchedTitle.isNullOrBlank()) cv.put(MovieDatabaseHelper.COLUMN_TITLE, fetchedTitle)
                    }
                    if (needsSummary) {
                        val fetchedOverview = tmdbJson.optString("overview")
                        if (!fetchedOverview.isNullOrBlank()) cv.put(MovieDatabaseHelper.COLUMN_SUMMARY, fetchedOverview)
                    }
                    if (needsRating) {
                        val fetchedRating = tmdbJson.optDouble("vote_average", 0.0)
                        if (fetchedRating > 0.0) cv.put(MovieDatabaseHelper.COLUMN_RATING, fetchedRating)
                    }
                    if (needsPoster) {
                        val path = tmdbJson.optString("poster_path")
                        if (!path.isNullOrBlank()) cv.put(MovieDatabaseHelper.COLUMN_ICON, path)
                    }
                    if (needsBackdrop) {
                        val path = tmdbJson.optString("backdrop_path")
                        if (!path.isNullOrBlank()) cv.put(MovieDatabaseHelper.COLUMN_IMAGE, path)
                    }
                    if (needsGenres) {
                        val genreArray = tmdbJson.optJSONArray("genres")
                        if (genreArray != null && genreArray.length() > 0) {
                            val gIds = JSONArray()
                            val gNames = StringBuilder()
                            for (i in 0 until genreArray.length()) {
                                val g = genreArray.getJSONObject(i)
                                gIds.put(g.optInt("id"))
                                if (i > 0) gNames.append(", ")
                                gNames.append(g.optString("name"))
                            }
                            cv.put(MovieDatabaseHelper.COLUMN_GENRES_IDS, gIds.toString())
                            cv.put(MovieDatabaseHelper.COLUMN_GENRES, gNames.toString())
                        }
                    }
                    rowsToUpdate.add(cv)
                } else if (needsTitle) {
                    // TMDB fetch failed, and we don't have a title even from Trakt
                    report.issues.add(ImportIssue(-1, title, tmdbId, ImportReason.MISSING_TITLE, "Missing title from both Trakt and TMDB"))
                    // Delete the row if we couldn't resolve the title at all.
                    val dbWritable = movieDbHelper.writableDatabase
                    dbWritable.delete(MovieDatabaseHelper.TABLE_MOVIES, "${MovieDatabaseHelper.COLUMN_MOVIES_ID} = ?", arrayOf(internalId.toString()))
                }
            } else if (needsTitle) {
                // If it needs title, but has no TMDB id, delete it as we can't show it properly
                report.issues.add(ImportIssue(-1, title, tmdbId, ImportReason.MISSING_TITLE, "Missing title and no TMDB ID to fallback on"))
                val dbWritable = movieDbHelper.writableDatabase
                dbWritable.delete(MovieDatabaseHelper.TABLE_MOVIES, "${MovieDatabaseHelper.COLUMN_MOVIES_ID} = ?", arrayOf(internalId.toString()))
            }
        }
        cursor.close()

        val writeDb = movieDbHelper.writableDatabase
        writeDb.beginTransaction()
        try {
            for (cv in rowsToUpdate) {
                val id = cv.getAsLong(MovieDatabaseHelper.COLUMN_MOVIES_ID)
                cv.remove(MovieDatabaseHelper.COLUMN_MOVIES_ID)
                if (cv.size() > 0) {
                    val args = arrayOf(id.toString())
                    writeDb.update(MovieDatabaseHelper.TABLE_MOVIES, cv, "${MovieDatabaseHelper.COLUMN_MOVIES_ID} = ?", args)
                }
            }
            writeDb.setTransactionSuccessful()
        } finally {
            writeDb.endTransaction()
        }
    }

    private suspend fun fetchTmdbData(tmdbId: Long, isMovie: Boolean): JSONObject? {
        if (tmdbApiKey.isNullOrBlank()) return null

        val type = if (isMovie) "movie" else "tv"
        val url = "https://api.themoviedb.org/3/$type/$tmdbId?api_key=$tmdbApiKey&append_to_response=genres"
        val request = Request.Builder().url(url).build()

        return try {
            rateLimiter.acquire()
            var response = httpClient.newCall(request).execute()
            var retryCount = 0
            while (response.code == 429 && retryCount < 3) {
                val retryAfterStr = response.header("Retry-After")
                val retryAfterMs = (retryAfterStr?.toLongOrNull() ?: 1L) * 1000L + 500L
                response.close()
                delay(retryAfterMs)
                response = httpClient.newCall(request).execute()
                retryCount++
            }

            if (!response.isSuccessful) {
                response.close()
                return null
            }
            val body = response.body?.string()
            if (body.isNullOrEmpty()) return null
            JSONObject(body)
        } catch (e: Exception) {
            null
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                NOTIFICATION_CHANNEL_ID,
                "Trakt Import Service Channel",
                NotificationManager.IMPORTANCE_LOW
            )
            channel.description = "Trakt Import Service notifications"
            notificationManager.createNotificationChannel(channel)
        }
    }

    private fun createNotification(contentText: String, progress: Int, maxProgress: Int, isFinished: Boolean = false, isError: Boolean = false, reportPath: String? = null): Notification {
        val intent = if (isFinished && reportPath != null) {
            Intent(this, ImportReportActivity::class.java).apply {
                putExtra("report_json_path", reportPath)
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }
        } else {
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }
        }

        val pendingIntent = PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

        val title = when {
            isError -> "Trakt Import Error"
            isFinished -> "Trakt Import Complete"
            else -> "Importing from Trakt..."
        }

        val builder = NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(contentText)
            .setSmallIcon(R.drawable.ic_app_icon)
            .setContentIntent(pendingIntent)
            .setOngoing(!isFinished)
            .setOnlyAlertOnce(true)

        if (!isFinished && maxProgress > 0) {
            builder.setProgress(maxProgress, progress, false)
        } else {
            builder.setProgress(0, 0, true) // indeterminate if max is 0
        }
        return builder.build()
    }

    private fun updateNotification(contentText: String, progress: Int, maxProgress: Int, isFinished: Boolean = false, isError: Boolean = false, reportPath: String? = null) {
        notificationManager.notify(NOTIFICATION_ID, createNotification(contentText, progress, maxProgress, isFinished, isError, reportPath))
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        serviceJob.cancel()
        if (::movieDbHelper.isInitialized) {
            movieDbHelper.close()
        }
    }
}
