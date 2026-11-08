package com.wirelessalien.android.moviedb.helper

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import com.google.gson.Gson
import java.io.File
import java.io.FileWriter
import java.io.OutputStreamWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object ReportExportHelper {
    private const val TAG = "ReportExportHelper"

    fun saveReport(context: Context, report: ImportReport, prefix: String): Pair<String?, String?> {
        var reportJsonPath: String? = null
        var reportCsvPath: String? = null

        try {
            val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
            val baseFileName = "${prefix}_$timestamp"
            val jsonFileName = "$baseFileName.json"
            val csvFileName = "$baseFileName.csv"

            // 1. Save to app-specific directory for internal usage
            val appSpecificDir = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: context.filesDir
            val jsonFile = File(appSpecificDir, jsonFileName)
            val csvFile = File(appSpecificDir, csvFileName)

            val jsonContent = Gson().toJson(report)
            val csvContent = buildString {
                append("row,title,tmdb_id,status,reason,detailMessage\n")
                report.issues.forEach { issue ->
                    val titleEscaped = issue.title?.replace("\"", "\"\"") ?: ""
                    val detailEscaped = issue.detailMessage?.replace("\"", "\"\"") ?: ""
                    append("${issue.rowNumber},\"$titleEscaped\",${issue.tmdbId ?: ""},FAILED/SKIPPED,${issue.reason.name},\"$detailEscaped\"\n")
                }
            }

            FileWriter(jsonFile).use { it.write(jsonContent) }
            reportJsonPath = jsonFile.absolutePath

            FileWriter(csvFile).use { it.write(csvContent) }
            reportCsvPath = csvFile.absolutePath

            // 2. Export to public Downloads directory
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                saveToMediaStore(context, jsonFileName, "application/json", jsonContent)
                saveToMediaStore(context, csvFileName, "text/csv", csvContent)
            } else {
                val publicDownloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                if (publicDownloadsDir != null) {
                    val publicJsonFile = File(publicDownloadsDir, jsonFileName)
                    val publicCsvFile = File(publicDownloadsDir, csvFileName)
                    FileWriter(publicJsonFile).use { it.write(jsonContent) }
                    FileWriter(publicCsvFile).use { it.write(csvContent) }
                }
            }

        } catch (e: Exception) {
            Log.e(TAG, "Error saving import reports", e)
        }

        return Pair(reportJsonPath, reportCsvPath)
    }

    private fun saveToMediaStore(context: Context, fileName: String, mimeType: String, content: String) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val contentValues = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
                put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
                put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
            }

            val resolver = context.contentResolver
            val uri: Uri? = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, contentValues)

            uri?.let {
                resolver.openOutputStream(it)?.use { outputStream ->
                    OutputStreamWriter(outputStream).use { writer ->
                        writer.write(content)
                    }
                }
            }
        }
    }
}
