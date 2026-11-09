package com.wirelessalien.android.moviedb

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.wirelessalien.android.moviedb.activity.MainActivity
import com.wirelessalien.android.moviedb.activity.DetailActivity
import com.wirelessalien.android.moviedb.helper.ScheduledNotificationDatabaseHelper
import org.json.JSONObject

class NotificationReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val title = intent.getStringExtra("title") ?: return
        val episodeName = intent.getStringExtra("episodeName") ?: return
        val episodeNumberStr = intent.getStringExtra("episodeNumber") ?: ""
        val mediaId = intent.getIntExtra("mediaId", 0)
        val season = intent.getIntExtra("season", 0)
        val notificationKey = intent.getStringExtra("notificationKey") ?: return
        val type = intent.getStringExtra("type") ?: return
        val notificationId = intent.getLongExtra("notificationId", 0).toInt()

        val notificationIntent: Intent
        if (type == "person_movie" || type == "person_tv") {
            notificationIntent = Intent(context, com.wirelessalien.android.moviedb.activity.DeepLinkActivity::class.java).apply {
                action = Intent.ACTION_VIEW
                val pathPrefix = if (type == "person_movie") "movie" else "tv"
                data = android.net.Uri.parse("https://www.themoviedb.org/$pathPrefix/$mediaId")
            }
        } else if (mediaId != 0 && (type == "movie" || type == "episode")) {
            val isMovie = type == "movie"
            val movieObject = JSONObject().apply {
                put("id", mediaId)
                put(if (isMovie) "title" else "name", title)
            }
            notificationIntent = Intent(context, DetailActivity::class.java).apply {
                putExtra("movieObject", movieObject.toString())
                putExtra("isMovie", isMovie)
            }
        } else {
            notificationIntent = Intent(context, MainActivity::class.java)
        }

        val pendingIntent = PendingIntent.getActivity(
            context, notificationId, notificationIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val contentText = if (type == "episode") {
            val epNum = episodeNumberStr.toIntOrNull() ?: 0
            val formattedEpisode = String.format("S%02dE%02d", season, epNum)
            context.getString(R.string.episode_airing_today, formattedEpisode, episodeName)
        } else if (type == "person_movie" || type == "person_tv") {
            context.getString(R.string.notification_for_person_credit, title, episodeName)
        } else {
            context.getString(R.string.movie_released_today, title)
        }

        val notificationManager = NotificationManagerCompat.from(context)

        val groupKey = "episode_reminders_group"

        val notificationBuilder = NotificationCompat.Builder(context, "episode_reminders")
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(contentText)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .setGroup(groupKey)
            .setPriority(NotificationCompat.PRIORITY_HIGH)

        if (ActivityCompat.checkSelfPermission(context, android.Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) {
            notificationManager.notify(notificationId, notificationBuilder.build())

            // Handle grouping if multiple notifications active
            val activeNotifications = notificationManager.activeNotifications
            val groupNotifications = activeNotifications.filter { it.notification.group == groupKey && it.id != 99999 }

            if (groupNotifications.size >= 3) {
                val summaryNotification = NotificationCompat.Builder(context, "episode_reminders")
                    .setSmallIcon(R.drawable.ic_notification)
                    .setStyle(NotificationCompat.InboxStyle()
                        .setSummaryText(context.getString(R.string.reminders_group_summary, groupNotifications.size))
                    )
                    .setGroup(groupKey)
                    .setGroupSummary(true)
                    .setAutoCancel(true)
                    .build()

                notificationManager.notify(99999, summaryNotification)
            }
        }

        val dbHelper = ScheduledNotificationDatabaseHelper(context)
        dbHelper.deleteScheduledNotification(notificationKey)
    }
}
