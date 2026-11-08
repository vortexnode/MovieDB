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

package com.wirelessalien.android.moviedb.work

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.database.Cursor
import android.os.Build
import androidx.preference.PreferenceManager
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.PeriodicWorkRequest
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import androidx.work.OneTimeWorkRequest
import com.wirelessalien.android.moviedb.NotificationReceiver
import com.wirelessalien.android.moviedb.R
import com.wirelessalien.android.moviedb.data.NotificationItem
import com.wirelessalien.android.moviedb.data.ScheduledNotification
import com.wirelessalien.android.moviedb.helper.EpisodeReminderDatabaseHelper
import com.wirelessalien.android.moviedb.helper.MovieDatabaseHelper
import com.wirelessalien.android.moviedb.helper.NotificationDatabaseHelper
import com.wirelessalien.android.moviedb.helper.ScheduledNotificationDatabaseHelper
import com.wirelessalien.android.moviedb.helper.TraktDatabaseHelper
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.TimeUnit
import com.wirelessalien.android.moviedb.NetworkClient
import com.wirelessalien.android.moviedb.helper.ConfigHelper
import org.json.JSONObject
import okhttp3.Request

class ReleaseReminderWorker(context: Context, workerParams: WorkerParameters) : Worker(context, workerParams) {

    private val scheduledNotificationDbHelper = ScheduledNotificationDatabaseHelper(applicationContext)

    override fun doWork(): Result {
        return try {
            fetchUpcomingTmdbData()
            scheduleNotifications()
            Result.success()
        } catch (e: Exception) {
            e.printStackTrace()
            Result.failure()
        }
    }

    private fun fetchUpcomingTmdbData() {
        val apiKey = ConfigHelper.getConfigValue(applicationContext, "api_key")
        val client = NetworkClient.client
        val prefs = PreferenceManager.getDefaultSharedPreferences(applicationContext)
        val apiLanguage = prefs.getString("key_api_language", "en-US")
        val apiRegion = prefs.getString("key_api_region", "US")
        val episodeDb = EpisodeReminderDatabaseHelper(applicationContext)

        // Find distinct tv shows that need updating
        val query = "SELECT DISTINCT ${EpisodeReminderDatabaseHelper.COLUMN_MOVIE_ID}, ${EpisodeReminderDatabaseHelper.COL_TYPE} FROM ${EpisodeReminderDatabaseHelper.TABLE_EPISODE_REMINDERS} WHERE ${EpisodeReminderDatabaseHelper.COL_TYPE} = 'tv'"

        val tvShowIds = mutableListOf<Int>()
        episodeDb.readableDatabase.rawQuery(query, null).use { cursor ->
            while (cursor.moveToNext()) {
                val mediaId = cursor.getInt(0)
                tvShowIds.add(mediaId)
            }
        }

        for (tvShowId in tvShowIds) {
            val url = "https://api.themoviedb.org/3/tv/$tvShowId?api_key=$apiKey&language=$apiLanguage"
            val request = Request.Builder().url(url).build()
            try {
                val response = client.newCall(request).execute()
                if (response.isSuccessful) {
                    val responseStr = response.body?.string()
                    if (responseStr != null) {
                        val jsonObject = JSONObject(responseStr)
                        if (jsonObject.has("next_episode_to_air") && !jsonObject.isNull("next_episode_to_air")) {
                            val nextEpisode = jsonObject.getJSONObject("next_episode_to_air")
                            val airDate = nextEpisode.optString("air_date")
                            val seasonNumber = nextEpisode.optInt("season_number")
                            val episodeNumber = nextEpisode.optInt("episode_number")
                            val name = nextEpisode.optString("name")
                            val showName = jsonObject.optString("name")

                            if (airDate.isNotEmpty()) {
                                // Insert/Update into EpisodeReminderDatabaseHelper
                                val db = episodeDb.writableDatabase
                                val updateQuery = """
                                    UPDATE ${EpisodeReminderDatabaseHelper.TABLE_EPISODE_REMINDERS}
                                    SET ${EpisodeReminderDatabaseHelper.COLUMN_DATE} = ?,
                                        ${EpisodeReminderDatabaseHelper.COL_SEASON} = ?,
                                        ${EpisodeReminderDatabaseHelper.COLUMN_EPISODE_NUMBER} = ?,
                                        ${EpisodeReminderDatabaseHelper.COLUMN_NAME} = ?,
                                        ${EpisodeReminderDatabaseHelper.COLUMN_TV_SHOW_NAME} = ?
                                    WHERE ${EpisodeReminderDatabaseHelper.COLUMN_MOVIE_ID} = ? AND ${EpisodeReminderDatabaseHelper.COL_TYPE} = 'tv'
                                """.trimIndent()
                                db.execSQL(updateQuery, arrayOf(airDate, seasonNumber, episodeNumber, name, showName, tvShowId))
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }

        // Find distinct movies that need updating
        val movieQuery = "SELECT DISTINCT ${EpisodeReminderDatabaseHelper.COLUMN_MOVIE_ID} FROM ${EpisodeReminderDatabaseHelper.TABLE_EPISODE_REMINDERS} WHERE ${EpisodeReminderDatabaseHelper.COL_TYPE} = 'movie'"
        val movieIds = mutableListOf<Int>()
        episodeDb.readableDatabase.rawQuery(movieQuery, null).use { cursor ->
            while (cursor.moveToNext()) {
                movieIds.add(cursor.getInt(0))
            }
        }

        for (movieId in movieIds) {
            val url = "https://api.themoviedb.org/3/movie/$movieId?api_key=$apiKey&language=$apiLanguage"
            val request = Request.Builder().url(url).build()
            try {
                val response = client.newCall(request).execute()
                if (response.isSuccessful) {
                    val responseStr = response.body?.string()
                    if (responseStr != null) {
                        val jsonObject = JSONObject(responseStr)
                        val releaseDate = jsonObject.optString("release_date")
                        if (releaseDate.isNotEmpty()) {
                            val db = episodeDb.writableDatabase
                            val updateQuery = """
                                UPDATE ${EpisodeReminderDatabaseHelper.TABLE_EPISODE_REMINDERS}
                                SET ${EpisodeReminderDatabaseHelper.COLUMN_DATE} = ?
                                WHERE ${EpisodeReminderDatabaseHelper.COLUMN_MOVIE_ID} = ? AND ${EpisodeReminderDatabaseHelper.COL_TYPE} = 'movie'
                            """.trimIndent()
                            db.execSQL(updateQuery, arrayOf(releaseDate, movieId))
                        }
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    private fun scheduleNotifications() {
        val prefs = PreferenceManager.getDefaultSharedPreferences(applicationContext)
        val remindersMovie = prefs.getBoolean("key_reminders_movie_premieres", true)
        val remindersEpisodes = prefs.getBoolean("key_reminders_new_episodes", true)
        val remindersSeasons = prefs.getBoolean("key_reminders_new_seasons", true)

        val episodeDb = EpisodeReminderDatabaseHelper(applicationContext)
        val movieDb = MovieDatabaseHelper(applicationContext)
        val tktDb = TraktDatabaseHelper(applicationContext)

        val episodeQuery = """
        SELECT * FROM ${EpisodeReminderDatabaseHelper.TABLE_EPISODE_REMINDERS}
        WHERE ${EpisodeReminderDatabaseHelper.COLUMN_DATE} IS NOT NULL
    """.trimIndent()

        episodeDb.readableDatabase.rawQuery(episodeQuery, null).use { cursor ->
            while (cursor.moveToNext()) {
                val movieId = cursor.getIntOrNull(cursor.getColumnIndexOrThrow(EpisodeReminderDatabaseHelper.COLUMN_MOVIE_ID))
                val showTraktId = cursor.getIntOrNull(cursor.getColumnIndexOrThrow(EpisodeReminderDatabaseHelper.COL_SHOW_TRAKT_ID))
                val seasonNumber = cursor.getIntOrNull(cursor.getColumnIndexOrThrow(EpisodeReminderDatabaseHelper.COL_SEASON))
                val episodeNumber = cursor.getIntOrNull(cursor.getColumnIndexOrThrow(EpisodeReminderDatabaseHelper.COLUMN_EPISODE_NUMBER))
                val dateStr = cursor.getString(cursor.getColumnIndexOrThrow(EpisodeReminderDatabaseHelper.COLUMN_DATE))
                val type = cursor.getString(cursor.getColumnIndexOrThrow(EpisodeReminderDatabaseHelper.COL_TYPE))

                if (dateStr == null) continue

                val isNewSeason = seasonNumber != null && episodeNumber == 1
                val calendarExists = showTraktId?.let { checkTraktCalendarExists(tktDb, it) } ?: false
                val movieTypeMatches = if (movieId != null) {
                    val dbMediaType = movieDb.getMediaType(movieId)
                    dbMediaType == type
                } else {
                    false
                }

                if (calendarExists || movieTypeMatches || true) {
                    val isMovie = type == "movie"
                    if ((isMovie && remindersMovie) || (!isMovie && ((isNewSeason && remindersSeasons) || (!isNewSeason && remindersEpisodes)))) {
                        val notificationKey = buildNotificationKey(type, movieId ?: 0, seasonNumber, episodeNumber, dateStr)
                        if (!scheduledNotificationDbHelper.hasNotificationBeenScheduled(notificationKey)) {
                            scheduleNotification(cursor, dateStr, notificationKey, "episode_reminder")
                        }
                    }
                }
            }
        }

        val calendarQuery = """
        SELECT * FROM ${TraktDatabaseHelper.TABLE_CALENDER}
        WHERE ${TraktDatabaseHelper.COL_AIR_DATE} IS NOT NULL
    """.trimIndent()

        tktDb.readableDatabase.rawQuery(calendarQuery, null).use { cursor ->
            while (cursor.moveToNext()) {
                val tmdbId = cursor.getIntOrNull(cursor.getColumnIndexOrThrow(TraktDatabaseHelper.COL_SHOW_TMDB))
                val dateStr = cursor.getString(cursor.getColumnIndexOrThrow(TraktDatabaseHelper.COL_AIR_DATE))
                val season = cursor.getIntOrNull(cursor.getColumnIndexOrThrow(TraktDatabaseHelper.COL_SEASON))
                val episode = cursor.getIntOrNull(cursor.getColumnIndexOrThrow(TraktDatabaseHelper.COL_NUMBER))
                val type = cursor.getString(cursor.getColumnIndexOrThrow(TraktDatabaseHelper.COL_TYPE))

                if (dateStr == null || tmdbId == null) continue

                val isNewSeason = season != null && episode == 1
                val isMovie = type == "movie"
                if ((isMovie && remindersMovie) || (!isMovie && ((isNewSeason && remindersSeasons) || (!isNewSeason && remindersEpisodes)))) {
                    val notificationKey = buildNotificationKey(type, tmdbId, season, episode, dateStr)
                    if (!scheduledNotificationDbHelper.hasNotificationBeenScheduled(notificationKey)) {
                        scheduleNotification(cursor, dateStr, notificationKey, "trakt_calendar")
                    }
                }
            }
        }
    }

    private fun buildNotificationKey(type: String, id: Int, season: Int?, episode: Int?, dateStr: String): String {
        return if (type == "movie") {
            "notification_movie_${id}_$dateStr"
        } else {
            "notification_episode_${id}_${season ?: 0}_${episode ?: 0}_$dateStr"
        }
    }

    private fun checkTraktCalendarExists(tktDb: TraktDatabaseHelper, traktId: Int): Boolean {
        return tktDb.readableDatabase.rawQuery(
            "SELECT 1 FROM ${TraktDatabaseHelper.TABLE_CALENDER} WHERE ${TraktDatabaseHelper.COL_SHOW_TRAKT_ID} = ?",
            arrayOf(traktId.toString())
        ).use { it.count > 0 }
    }

    private fun scheduleNotification(cursor: Cursor, dateStr: String, notificationKey: String, source: String) {
        try {
            val alarmTime = parseDate(dateStr) ?: return

            val prefs = PreferenceManager.getDefaultSharedPreferences(applicationContext)
            val hour = prefs.getInt("key_reminders_hour", 9)
            val minute = prefs.getInt("key_reminders_minute", 0)
            val daysBeforeStr = prefs.getString("key_reminders_days_before", "0")
            val daysBefore = daysBeforeStr?.toIntOrNull() ?: 0

            val calendar = Calendar.getInstance().apply {
                time = alarmTime
                set(Calendar.HOUR_OF_DAY, hour)
                set(Calendar.MINUTE, minute)
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
                add(Calendar.DAY_OF_YEAR, -daysBefore)
            }
            val finalAlarmTime = calendar.timeInMillis

            // Skip scheduling if the alarm time is in the past
            if (finalAlarmTime <= System.currentTimeMillis()) {
                return
            }

            val title: String
            val message: String
            val episodeName: String
            val episodeNumber: String
            val type: String
            val mediaId: Int

            if (source == "episode_reminder") {
                title = cursor.getString(cursor.getColumnIndexOrThrow(EpisodeReminderDatabaseHelper.COLUMN_TV_SHOW_NAME)) ?: ""
                episodeName = cursor.getString(cursor.getColumnIndexOrThrow(EpisodeReminderDatabaseHelper.COLUMN_NAME)) ?: ""
                episodeNumber = cursor.getString(cursor.getColumnIndexOrThrow(EpisodeReminderDatabaseHelper.COLUMN_EPISODE_NUMBER)) ?: ""
                type = cursor.getString(cursor.getColumnIndexOrThrow(EpisodeReminderDatabaseHelper.COL_TYPE)) ?: ""
                mediaId = cursor.getIntOrNull(cursor.getColumnIndexOrThrow(EpisodeReminderDatabaseHelper.COLUMN_MOVIE_ID)) ?: 0
                message = if (type == "movie") {
                    applicationContext.getString(R.string.movie_released_today, title)
                } else {
                    applicationContext.getString(R.string.episode_airing_today, episodeNumber, episodeName)
                }
            } else {
                title = cursor.getString(cursor.getColumnIndexOrThrow(TraktDatabaseHelper.COL_SHOW_TITLE)) ?: ""
                episodeName = cursor.getString(cursor.getColumnIndexOrThrow(TraktDatabaseHelper.COL_EPISODE_TITLE)) ?: ""
                episodeNumber = cursor.getString(cursor.getColumnIndexOrThrow(TraktDatabaseHelper.COL_NUMBER)) ?: ""
                type = cursor.getString(cursor.getColumnIndexOrThrow(TraktDatabaseHelper.COL_TYPE)) ?: ""
                mediaId = cursor.getIntOrNull(cursor.getColumnIndexOrThrow(TraktDatabaseHelper.COL_SHOW_TMDB)) ?: 0
                message = if (type == "movie") {
                    applicationContext.getString(R.string.movie_released_today, title)
                } else {
                    applicationContext.getString(R.string.episode_airing_today, episodeNumber, episodeName)
                }
            }

            val scheduledNotification = ScheduledNotification(
                id = 0,
                notificationKey = notificationKey,
                title = title,
                episodeName = episodeName,
                episodeNumber = mediaId.toString(), // Store mediaId here for Intent to DetailActivity
                type = type,
                alarmTime = finalAlarmTime
            )
            val notificationId = scheduledNotificationDbHelper.addScheduledNotification(scheduledNotification)

            val dbHelper = NotificationDatabaseHelper(applicationContext)
            val notificationItem = NotificationItem(
                id = 0,
                uniqueId = notificationKey,
                title = title,
                message = message,
                date = dateStr
            )
            dbHelper.addNotification(notificationItem)

            val alarmManager = applicationContext.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            val intent = Intent(applicationContext, NotificationReceiver::class.java).apply {
                putExtra("title", title)
                putExtra("episodeName", episodeName)
                putExtra("episodeNumber", mediaId.toString())
                putExtra("notificationKey", notificationKey)
                putExtra("type", type)
                putExtra("notificationId", notificationId)
            }

            val pendingIntent = PendingIntent.getBroadcast(
                applicationContext,
                notificationId.toInt(),
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                if (alarmManager.canScheduleExactAlarms()) {
                    alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, finalAlarmTime, pendingIntent)
                } else {
                    alarmManager.set(AlarmManager.RTC_WAKEUP, finalAlarmTime, pendingIntent)
                }
            } else {
                alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, finalAlarmTime, pendingIntent)
            }

        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun parseDate(dateString: String): Date? {
        val SUPPORTED_FORMATS = arrayOf(
            "yyyy-MM-dd'T'HH:mm:ss.SSS'Z'",
            "yyyy-MM-dd"
        )
        for (format in SUPPORTED_FORMATS) {
            try {
                val sdf = SimpleDateFormat(format, Locale.getDefault())
                if (format.endsWith("'Z'")) {
                    sdf.timeZone = TimeZone.getTimeZone("UTC")
                }
                return sdf.parse(dateString)
            } catch (e: Exception) {
                // Ignore and try next format
            }
        }
        return null
    }

    private fun Cursor.getIntOrNull(columnIndex: Int): Int? {
        return if (isNull(columnIndex)) null else getInt(columnIndex)
    }

    companion object {
        fun schedulePeriodicWork(context: Context) {
            val reminderWorkRequest = PeriodicWorkRequest.Builder(
                ReleaseReminderWorker::class.java,
                12, TimeUnit.HOURS
            ).build()

            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                "ReleaseReminderPeriodic",
                ExistingPeriodicWorkPolicy.UPDATE,
                reminderWorkRequest
            )
        }

        fun scheduleWork(context: Context) {
            val calendarWorkRequest = OneTimeWorkRequest.Builder(
                CalenderAllWorkerTkt::class.java
            ).build()

            val reminderWorkRequest = OneTimeWorkRequest.Builder(
                ReleaseReminderWorker::class.java
            ).build()

            WorkManager.getInstance(context)
                .beginUniqueWork(
                    "CalendarAndReminderWork",
                    ExistingWorkPolicy.REPLACE,
                    calendarWorkRequest
                )
                .then(reminderWorkRequest)
                .enqueue()

            schedulePeriodicWork(context)
        }
    }
}
