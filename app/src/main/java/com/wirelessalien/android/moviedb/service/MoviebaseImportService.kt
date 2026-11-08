package com.wirelessalien.android.moviedb.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.documentfile.provider.DocumentFile
import com.google.gson.Gson
import com.opencsv.CSVParserBuilder
import com.opencsv.CSVReaderHeaderAwareBuilder
import com.wirelessalien.android.moviedb.R
import com.wirelessalien.android.moviedb.activity.ImportReportActivity
import com.wirelessalien.android.moviedb.activity.MainActivity
import com.wirelessalien.android.moviedb.helper.*
import kotlinx.coroutines.*
import org.json.JSONObject
import java.io.File
import java.io.FileWriter
import java.io.InputStream
import java.io.InputStreamReader
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit
import java.util.zip.ZipInputStream

class MoviebaseImportService : Service() {

    private val serviceJob = Job()
    private val serviceScope = CoroutineScope(Dispatchers.IO + serviceJob)
    private lateinit var notificationManager: NotificationManager

    private lateinit var movieDbHelper: MovieDatabaseHelper
    private lateinit var listDbHelper: ListDatabaseHelper
    private lateinit var peopleDbHelper: PeopleDatabaseHelper

    private val rateLimiter = RateLimiter(10, 1, TimeUnit.SECONDS)
    private val httpClient = com.wirelessalien.android.moviedb.NetworkClient.client
    private var tmdbApiKey: String? = null

    companion object {
        const val ACTION_START_IMPORT = "com.wirelessalien.android.moviedb.service.action.START_MOVIEBASE_IMPORT"
        const val EXTRA_FILE_URI = "com.wirelessalien.android.moviedb.service.extra.MOVIEBASE_FILE_URI"
        const val EXTRA_IS_ZIP = "com.wirelessalien.android.moviedb.service.extra.MOVIEBASE_IS_ZIP"
        private const val NOTIFICATION_CHANNEL_ID = "MoviebaseImportServiceChannel"
        private const val NOTIFICATION_ID = 102
        private const val TAG = "MoviebaseImportService"
    }

    override fun onCreate() {
        super.onCreate()
        notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        movieDbHelper = MovieDatabaseHelper(applicationContext)
        listDbHelper = ListDatabaseHelper(applicationContext)
        peopleDbHelper = PeopleDatabaseHelper(applicationContext)
        tmdbApiKey = ConfigHelper.getConfigValue(applicationContext, "api_key")
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_START_IMPORT) {
            val fileUri = intent.getParcelableExtra<Uri>(EXTRA_FILE_URI)
            val isZip = intent.getBooleanExtra(EXTRA_IS_ZIP, false)

            if (fileUri != null) {
                startForeground(NOTIFICATION_ID, createNotification(getString(R.string.starting_import), 0, 0))
                serviceScope.launch {
                    processImport(fileUri, isZip)
                }
            } else {
                Log.e(TAG, "File URI missing. Stopping service.")
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    private suspend fun processImport(fileUri: Uri, isZip: Boolean) {
        var processedRows = 0
        val report = ImportReport()
        report.total = 0

        try {
            if (isZip) {
                processZip(fileUri, report) { processedRows++ }
            } else {
                processFolder(fileUri, report) { processedRows++ }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to process import", e)
        }

        finalizeReport(report, processedRows)
    }

    private suspend fun processZip(fileUri: Uri, report: ImportReport, incrementRow: () -> Unit) {
        val requiredFiles = listOf("watched.csv", "ratings.csv", "watchlist.csv", "favorites.csv", "collection.csv", "lists.csv", "people.csv")

        for (fileName in requiredFiles) {
            applicationContext.contentResolver.openInputStream(fileUri)?.use { inputStream ->
                ZipInputStream(inputStream).use { zis ->
                    var zipEntry = zis.nextEntry
                    while (zipEntry != null) {
                        if (!zipEntry.isDirectory && zipEntry.name.endsWith(fileName)) {
                            Log.d(TAG, "Processing zip entry: ${zipEntry.name}")
                            processCsvStream(fileName, zis, report, incrementRow)
                            break
                        }
                        zipEntry = zis.nextEntry
                    }
                }
            }
        }
    }

    private suspend fun processFolder(treeUri: Uri, report: ImportReport, incrementRow: () -> Unit) {
        val documentFile = DocumentFile.fromTreeUri(applicationContext, treeUri)
        val requiredFiles = listOf("watched.csv", "ratings.csv", "watchlist.csv", "favorites.csv", "collection.csv", "lists.csv", "people.csv")

        for (fileName in requiredFiles) {
            val fileDoc = documentFile?.listFiles()?.find { it.isFile && it.name == fileName }
            if (fileDoc != null) {
                Log.d(TAG, "Processing folder entry: ${fileDoc.name}")
                applicationContext.contentResolver.openInputStream(fileDoc.uri)?.use { inputStream ->
                    processCsvStream(fileName, inputStream, report, incrementRow)
                }
            }
        }
    }

    private suspend fun processCsvStream(fileName: String, inputStream: InputStream, report: ImportReport, incrementRow: () -> Unit) {
        // Read stream avoiding closing the underlying ZIP stream if it's a ZipInputStream
        val reader = InputStreamReader(inputStream, "UTF-8")
        val csvParser = CSVParserBuilder().withSeparator(',').withQuoteChar('"').build()
        val csvReader = CSVReaderHeaderAwareBuilder(reader).withCSVParser(csvParser).build()

        while (true) {
            val rowValues = csvReader.readMap() ?: break
            // Remove BOM from keys if present
            val cleanedRowValues = rowValues.mapKeys { it.key.replace("\uFEFF", "") }

            try {
                handleRow(fileName, cleanedRowValues, report)
                incrementRow()
            } catch (e: Exception) {
                Log.e(TAG, "Error handling row in $fileName", e)
                report.failed++
                report.issues.add(ImportIssue(report.total, cleanedRowValues["title"], cleanedRowValues["tmdb_id"]?.toLongOrNull(), ImportReason.INVALID_VALUE, e.message))
            }
        }
        // Don't close csvReader as it will close the underlying InputStream (bad for ZipInputStream)
    }


    private suspend fun handleRow(fileName: String, row: Map<String, String>, report: ImportReport) {
        val tmdbIdStr = row["tmdb_id"] ?: row["SHOW"]
        val tmdbId = tmdbIdStr?.toLongOrNull()

        if (tmdbId == null) {
            report.skipped++
            report.issues.add(ImportIssue(report.total, row["title"], null, ImportReason.MISSING_TMDB_ID, "No tmdb_id found"))
            return
        }

        when (fileName) {
            "watched.csv" -> handleWatchedRow(row, tmdbId, report)
            "ratings.csv" -> handleRatingsRow(row, tmdbId, report)
            "watchlist.csv" -> handleWatchlistRow(row, tmdbId, report)
            "favorites.csv", "collection.csv" -> handleTagsRow(fileName, row, tmdbId, report)
            "lists.csv" -> handleListsRow(row, tmdbId, report)
            "people.csv" -> handlePeopleRow(row, tmdbId, report)
        }
    }

    private suspend fun handleWatchedRow(row: Map<String, String>, tmdbId: Long, report: ImportReport) {
        val type = row["type"]
        val title = row["title"] ?: ""
        val lastWatched = convertDate(row["last_watched_at"])
        val plays = row["plays"]?.toIntOrNull() ?: 1

        val db = movieDbHelper.writableDatabase
        db.beginTransaction()
        try {
            if (type == "movie") {
                val existingId = movieDbHelper.getMovieRowId(tmdbId, true)
                if (existingId == null) {
                    // Create minimal movie
                    val cv = android.content.ContentValues().apply {
                        put(MovieDatabaseHelper.COLUMN_MOVIES_ID, tmdbId)
                        put(MovieDatabaseHelper.COLUMN_TITLE, title)
                        put(MovieDatabaseHelper.COLUMN_MOVIE, 1)
                        put(MovieDatabaseHelper.COLUMN_CATEGORIES, MovieDatabaseHelper.CATEGORY_WATCHED)
                        if (lastWatched != null) put(MovieDatabaseHelper.COLUMN_PERSONAL_FINISH_DATE, lastWatched)
                        if (plays > 1) put(MovieDatabaseHelper.COLUMN_PERSONAL_REWATCHED, plays - 1)
                        // Defaults
                        put(MovieDatabaseHelper.COLUMN_SUMMARY, "N/A")
                        put(MovieDatabaseHelper.COLUMN_IMAGE, "")
                        put(MovieDatabaseHelper.COLUMN_ICON, "")
                        put(MovieDatabaseHelper.COLUMN_RATING, 0.0)
                        put(MovieDatabaseHelper.COLUMN_GENRES, "N/A")
                        put(MovieDatabaseHelper.COLUMN_GENRES_IDS, "[]")
                    }
                    db.insert(MovieDatabaseHelper.TABLE_MOVIES, null, cv)
                    report.inserted++
                    fetchTmdbDataIfNeeded(tmdbId, type == "movie")
                } else {
                    // Update existing
                    val cv = android.content.ContentValues().apply {
                        put(MovieDatabaseHelper.COLUMN_CATEGORIES, MovieDatabaseHelper.CATEGORY_WATCHED)
                        if (lastWatched != null) put(MovieDatabaseHelper.COLUMN_PERSONAL_FINISH_DATE, lastWatched)
                        if (plays > 1) put(MovieDatabaseHelper.COLUMN_PERSONAL_REWATCHED, plays - 1)
                    }
                    db.update(MovieDatabaseHelper.TABLE_MOVIES, cv, "${MovieDatabaseHelper.COLUMN_ID}=?", arrayOf(existingId.toString()))
                    report.updated++
                }
            } else if (type == "episode") {
                val season = row["season_number"]?.toIntOrNull()
                val episode = row["episode_number"]?.toIntOrNull()
                if (season != null && episode != null) {
                    val existingShowId = movieDbHelper.getMovieRowId(tmdbId, false)
                    if (existingShowId == null) {
                        // Create minimal show
                        val cv = android.content.ContentValues().apply {
                            put(MovieDatabaseHelper.COLUMN_MOVIES_ID, tmdbId)
                            put(MovieDatabaseHelper.COLUMN_TITLE, title)
                            put(MovieDatabaseHelper.COLUMN_MOVIE, 0)
                            put(MovieDatabaseHelper.COLUMN_CATEGORIES, MovieDatabaseHelper.CATEGORY_WATCHING)
                            // Defaults
                            put(MovieDatabaseHelper.COLUMN_SUMMARY, "N/A")
                            put(MovieDatabaseHelper.COLUMN_IMAGE, "")
                            put(MovieDatabaseHelper.COLUMN_ICON, "")
                            put(MovieDatabaseHelper.COLUMN_RATING, 0.0)
                            put(MovieDatabaseHelper.COLUMN_GENRES, "N/A")
                            put(MovieDatabaseHelper.COLUMN_GENRES_IDS, "[]")
                        }
                        db.insert(MovieDatabaseHelper.TABLE_MOVIES, null, cv)

                    fetchTmdbDataIfNeeded(tmdbId, false)
                    } else {
                        // Ensure show is at least watching if it wasn't watched
                        // (Avoiding complex query here, rely on user to fix up if needed, or set to WATCHING)
                        val cv = android.content.ContentValues().apply {
                            put(MovieDatabaseHelper.COLUMN_CATEGORIES, MovieDatabaseHelper.CATEGORY_WATCHING)
                        }
                        db.update(MovieDatabaseHelper.TABLE_MOVIES, cv, "${MovieDatabaseHelper.COLUMN_ID}=? AND ${MovieDatabaseHelper.COLUMN_CATEGORIES}=?", arrayOf(existingShowId.toString(), MovieDatabaseHelper.CATEGORY_PLAN_TO_WATCH.toString()))
                    }

                    val existingEpId = movieDbHelper.getEpisodeRowId(tmdbId, season, episode)
                    val epCv = android.content.ContentValues().apply {
                        put(MovieDatabaseHelper.COLUMN_MOVIES_ID, tmdbId)
                        put(MovieDatabaseHelper.COLUMN_SEASON_NUMBER, season)
                        put(MovieDatabaseHelper.COLUMN_EPISODE_NUMBER, episode)
                        if (lastWatched != null) put(MovieDatabaseHelper.COLUMN_EPISODE_WATCH_DATE, lastWatched)
                    }
                    if (existingEpId == null) {
                        db.insert(MovieDatabaseHelper.TABLE_EPISODES, null, epCv)
                        report.inserted++
                    } else {
                        db.update(MovieDatabaseHelper.TABLE_EPISODES, epCv, "${MovieDatabaseHelper.COLUMN_ID}=?", arrayOf(existingEpId.toString()))
                        report.updated++
                    }
                }
            }
            db.setTransactionSuccessful()
        } catch (e: Exception) {
            report.failed++
            report.issues.add(ImportIssue(report.total, title, tmdbId, ImportReason.DB_ERROR, e.message))
        } finally {
            db.endTransaction()
        }
    }

    private suspend fun handleRatingsRow(row: Map<String, String>, tmdbId: Long, report: ImportReport) {
        val type = row["type"]
        val rating10 = row["rating_10"]?.toDoubleOrNull()
        if (rating10 == null) return

        if (type == "season") {
            report.skipped++
            report.issues.add(ImportIssue(report.total, row["title"], tmdbId, ImportReason.INVALID_VALUE, "Season ratings are unsupported"))
            return
        }

        val db = movieDbHelper.writableDatabase
        db.beginTransaction()
        try {
            if (type == "movie" || type == "show") {
                val isMovie = type == "movie"
                val existingId = movieDbHelper.getMovieRowId(tmdbId, isMovie)
                if (existingId != null) {
                    val cv = android.content.ContentValues().apply {
                        put(MovieDatabaseHelper.COLUMN_PERSONAL_RATING, rating10)
                    }
                    db.update(MovieDatabaseHelper.TABLE_MOVIES, cv, "${MovieDatabaseHelper.COLUMN_ID}=?", arrayOf(existingId.toString()))
                    report.updated++
                } else {
                    // Create minimal
                    val cv = android.content.ContentValues().apply {
                        put(MovieDatabaseHelper.COLUMN_MOVIES_ID, tmdbId)
                        put(MovieDatabaseHelper.COLUMN_TITLE, row["title"] ?: "")
                        put(MovieDatabaseHelper.COLUMN_MOVIE, if(isMovie) 1 else 0)
                        put(MovieDatabaseHelper.COLUMN_PERSONAL_RATING, rating10)
                        put(MovieDatabaseHelper.COLUMN_SUMMARY, "N/A")
                        put(MovieDatabaseHelper.COLUMN_IMAGE, "")
                        put(MovieDatabaseHelper.COLUMN_ICON, "")
                        put(MovieDatabaseHelper.COLUMN_RATING, 0.0)
                        put(MovieDatabaseHelper.COLUMN_GENRES, "N/A")
                        put(MovieDatabaseHelper.COLUMN_GENRES_IDS, "[]")
                    }
                    db.insert(MovieDatabaseHelper.TABLE_MOVIES, null, cv)
                    report.inserted++
                    fetchTmdbDataIfNeeded(tmdbId, type == "movie")
                }
            } else if (type == "episode") {
                val season = row["season_number"]?.toIntOrNull()
                val episode = row["episode_number"]?.toIntOrNull()
                if (season != null && episode != null) {
                    val existingEpId = movieDbHelper.getEpisodeRowId(tmdbId, season, episode)
                    val epCv = android.content.ContentValues().apply {
                        put(MovieDatabaseHelper.COLUMN_MOVIES_ID, tmdbId)
                        put(MovieDatabaseHelper.COLUMN_SEASON_NUMBER, season)
                        put(MovieDatabaseHelper.COLUMN_EPISODE_NUMBER, episode)
                        put(MovieDatabaseHelper.COLUMN_EPISODE_RATING, rating10)
                    }
                    if (existingEpId == null) {
                        db.insert(MovieDatabaseHelper.TABLE_EPISODES, null, epCv)
                        report.inserted++
                    } else {
                        db.update(MovieDatabaseHelper.TABLE_EPISODES, epCv, "${MovieDatabaseHelper.COLUMN_ID}=?", arrayOf(existingEpId.toString()))
                        report.updated++
                    }
                }
            }
            db.setTransactionSuccessful()
        } catch (e: Exception) {
            report.failed++
            report.issues.add(ImportIssue(report.total, row["title"], tmdbId, ImportReason.DB_ERROR, e.message))
        } finally {
            db.endTransaction()
        }
    }

    private suspend fun handleWatchlistRow(row: Map<String, String>, tmdbId: Long, report: ImportReport) {
        val type = row["type"]
        val isMovie = type == "movie"

        val db = movieDbHelper.writableDatabase
        val existingId = movieDbHelper.getMovieRowId(tmdbId, isMovie)

        if (existingId == null) {
            db.beginTransaction()
            try {
                val cv = android.content.ContentValues().apply {
                    put(MovieDatabaseHelper.COLUMN_MOVIES_ID, tmdbId)
                    put(MovieDatabaseHelper.COLUMN_TITLE, row["title"] ?: "")
                    put(MovieDatabaseHelper.COLUMN_MOVIE, if(isMovie) 1 else 0)
                    put(MovieDatabaseHelper.COLUMN_CATEGORIES, MovieDatabaseHelper.CATEGORY_PLAN_TO_WATCH)
                    put(MovieDatabaseHelper.COLUMN_SUMMARY, "N/A")
                    put(MovieDatabaseHelper.COLUMN_IMAGE, "")
                    put(MovieDatabaseHelper.COLUMN_ICON, "")
                    put(MovieDatabaseHelper.COLUMN_RATING, 0.0)
                    put(MovieDatabaseHelper.COLUMN_GENRES, "N/A")
                    put(MovieDatabaseHelper.COLUMN_GENRES_IDS, "[]")
                }
                db.insert(MovieDatabaseHelper.TABLE_MOVIES, null, cv)
                db.setTransactionSuccessful()
                report.inserted++
                fetchTmdbDataIfNeeded(tmdbId, isMovie)
            } catch (e: Exception) {
                report.failed++
                report.issues.add(ImportIssue(report.total, row["title"], tmdbId, ImportReason.DB_ERROR, e.message))
            } finally {
                db.endTransaction()
            }
        } else {
             // Never downgrade already watched/watching items
             report.skipped++
        }
    }


    private suspend fun handleTagsRow(fileName: String, row: Map<String, String>, tmdbId: Long, report: ImportReport) {
        val type = row["type"]
        val isMovie = type == "movie"
        val tagName = if (fileName == "favorites.csv") "Favorites" else "Collection"

        val tagId = movieDbHelper.addTag(tagName)
        if (tagId != -1L) {
            movieDbHelper.addMovieTag(tmdbId.toInt(), tagId, isMovie)
            report.updated++
        }
    }

    private suspend fun handleListsRow(row: Map<String, String>, tmdbId: Long, report: ImportReport) {
        val listName = row["list_name"]
        val type = row["type"]
        if (listName.isNullOrBlank()) return

        // Generate a deterministic integer ID for the list
        val listId = listName.hashCode()
        listDbHelper.addList(listId, listName)
        listDbHelper.addListDetails(listId, listName, tmdbId.toInt(), type)
        report.updated++
    }

    private suspend fun handlePeopleRow(row: Map<String, String>, tmdbId: Long, report: ImportReport) {
        val name = row["name"]
        if (name.isNullOrBlank()) return

        peopleDbHelper.insert(
            id = tmdbId.toInt(),
            name = name,
            birthday = null,
            deathday = null,
            biography = null,
            placeOfBirth = null,
            popularity = 0.0,
            profilePath = null,
            imdbId = null,
            homepage = null
        )
        report.inserted++
    }


    private suspend fun fetchTmdbDataIfNeeded(tmdbId: Long, isMovie: Boolean) {
        if (tmdbApiKey.isNullOrBlank() || tmdbId == 0L) return

        val db = movieDbHelper.readableDatabase
        var needsUpdate = false
        val cursor = db.query(MovieDatabaseHelper.TABLE_MOVIES, null, "${MovieDatabaseHelper.COLUMN_MOVIES_ID}=? AND ${MovieDatabaseHelper.COLUMN_MOVIE}=?", arrayOf(tmdbId.toString(), if(isMovie) "1" else "0"), null, null, null)
        if (cursor.moveToFirst()) {
            val titleIdx = cursor.getColumnIndex(MovieDatabaseHelper.COLUMN_TITLE)
            val summaryIdx = cursor.getColumnIndex(MovieDatabaseHelper.COLUMN_SUMMARY)
            val imageIdx = cursor.getColumnIndex(MovieDatabaseHelper.COLUMN_IMAGE)
            val genresIdx = cursor.getColumnIndex(MovieDatabaseHelper.COLUMN_GENRES_IDS)

            val title = if (titleIdx != -1) cursor.getString(titleIdx) else null
            val summary = if (summaryIdx != -1) cursor.getString(summaryIdx) else null
            val image = if (imageIdx != -1) cursor.getString(imageIdx) else null
            val genres = if (genresIdx != -1) cursor.getString(genresIdx) else null

            if (title.isNullOrBlank() || summary.isNullOrBlank() || summary == "N/A" || image.isNullOrBlank() || genres.isNullOrBlank() || genres == "[]") {
                needsUpdate = true
            }
        }
        cursor.close()

        if (needsUpdate) {
            val tmdbJson = fetchTmdbData(tmdbId.toInt(), isMovie)
            if (tmdbJson != null) {
                val cv = android.content.ContentValues()
                val fetchedTitle = if (isMovie) tmdbJson.optString("title") else tmdbJson.optString("name")
                val fetchedSummary = tmdbJson.optString("overview", "N/A")
                val fetchedPoster = tmdbJson.optString("poster_path", "")
                val fetchedBackdrop = tmdbJson.optString("backdrop_path", "")
                val fetchedReleaseDate = if (isMovie) tmdbJson.optString("release_date", "") else tmdbJson.optString("first_air_date", "")
                val fetchedRating = tmdbJson.optDouble("vote_average", 0.0)

                val genresArray = tmdbJson.optJSONArray("genres")
                val genresList = mutableListOf<String>()
                val genresIdsList = mutableListOf<Int>()
                if (genresArray != null) {
                    for (i in 0 until genresArray.length()) {
                        val genreObj = genresArray.optJSONObject(i)
                        if (genreObj != null) {
                            genresList.add(genreObj.optString("name"))
                            genresIdsList.add(genreObj.optInt("id"))
                        }
                    }
                }

                if (fetchedTitle.isNotBlank()) cv.put(MovieDatabaseHelper.COLUMN_TITLE, fetchedTitle)
                if (fetchedSummary.isNotBlank()) cv.put(MovieDatabaseHelper.COLUMN_SUMMARY, fetchedSummary)
                if (fetchedPoster.isNotBlank()) cv.put(MovieDatabaseHelper.COLUMN_IMAGE, fetchedPoster)
                if (fetchedBackdrop.isNotBlank()) cv.put(MovieDatabaseHelper.COLUMN_ICON, fetchedBackdrop)
                if (fetchedReleaseDate.isNotBlank()) cv.put(MovieDatabaseHelper.COLUMN_RELEASE_DATE, fetchedReleaseDate)
                cv.put(MovieDatabaseHelper.COLUMN_RATING, fetchedRating)
                if (genresList.isNotEmpty()) cv.put(MovieDatabaseHelper.COLUMN_GENRES, genresList.joinToString(", "))
                if (genresIdsList.isNotEmpty()) cv.put(MovieDatabaseHelper.COLUMN_GENRES_IDS, genresIdsList.toString())

                if (cv.size() > 0) {
                    val wDb = movieDbHelper.writableDatabase
                    wDb.update(MovieDatabaseHelper.TABLE_MOVIES, cv, "${MovieDatabaseHelper.COLUMN_MOVIES_ID}=? AND ${MovieDatabaseHelper.COLUMN_MOVIE}=?", arrayOf(tmdbId.toString(), if(isMovie) "1" else "0"))
                }
            }
        }
    }

    private suspend fun fetchTmdbData(tmdbId: Int, isMovie: Boolean): JSONObject? {
        rateLimiter.acquire()
        val mediaType = if (isMovie) "movie" else "tv"
        val url = "https://api.themoviedb.org/3/$mediaType/$tmdbId?api_key=$tmdbApiKey&language=${Locale.getDefault().language}"
        val request = okhttp3.Request.Builder().url(url).build()

        return try {
            val response = httpClient.newCall(request).execute()
            if (response.isSuccessful) {
                response.body?.string()?.let { JSONObject(it) }
            } else {
                null
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to fetch TMDB data for ID $tmdbId", e)
            null
        }
    }

    private fun convertDate(isoDate: String?): String? {
        if (isoDate.isNullOrBlank()) return null
        return try {
            isoDate.substring(0, 10) // Extract YYYY-MM-DD from ISO 8601
        } catch (e: Exception) {
            null
        }
    }


    private fun finalizeReport(report: ImportReport, processedRows: Int) {
        report.total = processedRows

        var reportJsonPath: String? = null
        var reportCsvPath: String? = null
        try {
            val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
            val downloadsDir = applicationContext.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
                ?: Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                ?: applicationContext.filesDir

            val jsonFile = File(downloadsDir, "moviebase_import_report_$timestamp.json")
            val csvFile = File(downloadsDir, "moviebase_import_report_$timestamp.csv")

            FileWriter(jsonFile).use { writer ->
                writer.write(Gson().toJson(report))
            }
            reportJsonPath = jsonFile.absolutePath

            FileWriter(csvFile).use { writer ->
                writer.append("row,title,tmdb_id,status,reason,detailMessage\n")
                report.issues.forEach { issue ->
                    writer.append("${issue.rowNumber},\"${issue.title?.replace("\"", "\"\"") ?: ""}\",${issue.tmdbId ?: ""},FAILED/SKIPPED,${issue.reason.name},\"${issue.detailMessage?.replace("\"", "\"\"") ?: ""}\"\n")
                }
            }
            reportCsvPath = csvFile.absolutePath
        } catch (e: Exception) {
            Log.e(TAG, "Error saving import reports", e)
        }

        updateNotification(getString(R.string.import_complete), processedRows, processedRows, isFinished = true, reportPath = reportJsonPath, reportCsvPath = reportCsvPath)

        serviceScope.launch {
            delay(5000)
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                NOTIFICATION_CHANNEL_ID,
                "Moviebase Import Service",
                NotificationManager.IMPORTANCE_LOW
            )
            notificationManager.createNotificationChannel(channel)
        }
    }

    private fun createNotification(contentText: String, progress: Int, maxProgress: Int, isFinished: Boolean = false, isError: Boolean = false, reportPath: String? = null, reportCsvPath: String? = null): Notification {
        val intent = if (isFinished && reportPath != null) {
            Intent(this, ImportReportActivity::class.java).apply {
                putExtra("report_json_path", reportPath)
                putExtra("report_csv_path", reportCsvPath)
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }
        } else {
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }
        }
        val pendingIntent = PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

        val title = if (isFinished) getString(R.string.import_complete) else getString(R.string.moviebase_import)

        val builder = NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(contentText)
            .setSmallIcon(R.drawable.ic_app_icon)
            .setContentIntent(pendingIntent)
            .setOngoing(!isFinished)
            .setOnlyAlertOnce(true)

        if (!isFinished && maxProgress > 0) {
            builder.setProgress(maxProgress, progress, false)
        } else if (!isFinished) {
            builder.setProgress(0, 0, true)
        }
        return builder.build()
    }

    private fun updateNotification(contentText: String, progress: Int, maxProgress: Int, isFinished: Boolean = false, isError: Boolean = false, reportPath: String? = null, reportCsvPath: String? = null) {
        notificationManager.notify(NOTIFICATION_ID, createNotification(contentText, progress, maxProgress, isFinished, isError, reportPath, reportCsvPath))
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        serviceJob.cancel()
        if (::movieDbHelper.isInitialized) movieDbHelper.close()
        if (::listDbHelper.isInitialized) listDbHelper.close()
        if (::peopleDbHelper.isInitialized) peopleDbHelper.close()
    }
}
