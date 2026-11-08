package com.wirelessalien.android.moviedb.helper

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.preference.PreferenceManager
import com.google.gson.Gson
import java.io.File
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object ReportExportHelper {

    fun saveReport(context: Context, report: ImportReport, prefix: String): Pair<String?, String?> {
        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
        val keyPrefix = if (prefix.isBlank()) "csv" else prefix
        val jsonFileName = "${keyPrefix}_import_report_$timestamp.json"
        val csvFileName = "${keyPrefix}_import_report_$timestamp.csv"

        val reportJson = Gson().toJson(report)
        val csvContent = buildString {
            append("row,title,tmdb_id,status,reason,detailMessage\n")
            report.issues.forEach { issue ->
                append("${issue.rowNumber},\"${issue.title?.replace("\"", "\"\"") ?: ""}\",${issue.tmdbId ?: ""},FAILED/SKIPPED,${issue.reason.name},\"${issue.detailMessage?.replace("\"", "\"\"") ?: ""}\"\n")
            }
        }

        var jsonPath: String? = null
        var csvPath: String? = null

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            jsonPath = saveFileToMediaStore(context, jsonFileName, "application/json", reportJson)
            csvPath = saveFileToMediaStore(context, csvFileName, "text/csv", csvContent)
        } else {
            val downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            val showcaseDir = File(downloadsDir, "ShowCase")
            if (!showcaseDir.exists()) showcaseDir.mkdirs()

            val jsonFile = File(showcaseDir, jsonFileName)
            FileWriter(jsonFile).use { it.write(reportJson) }
            jsonPath = jsonFile.absolutePath

            val csvFile = File(showcaseDir, csvFileName)
            FileWriter(csvFile).use { it.write(csvContent) }
            csvPath = csvFile.absolutePath
        }

        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
        prefs.edit().apply {
            putString("${keyPrefix}_last_report_json_path", jsonPath)
            putString("${keyPrefix}_last_report_csv_path", csvPath)
            apply()
        }

        return Pair(jsonPath, csvPath)
    }

    private fun saveFileToMediaStore(context: Context, fileName: String, mimeType: String, content: String): String? {
        val resolver = context.contentResolver
        val contentValues = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
            put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/ShowCase")
            }
        }

        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, contentValues)
        if (uri != null) {
            resolver.openOutputStream(uri)?.use { outputStream ->
                outputStream.write(content.toByteArray())
            }
            return uri.toString()
        }
        return null
    }
}
