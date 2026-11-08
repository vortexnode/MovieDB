package com.wirelessalien.android.moviedb.activity

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.view.MenuItem
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.preference.PreferenceManager
import androidx.documentfile.provider.DocumentFile
import com.wirelessalien.android.moviedb.R
import com.wirelessalien.android.moviedb.databinding.ActivityMoviebaseImportBinding
import com.wirelessalien.android.moviedb.helper.ThemeHelper
import com.wirelessalien.android.moviedb.service.MoviebaseImportService
import java.util.zip.ZipInputStream

class MoviebaseImportActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMoviebaseImportBinding
    private var selectedUri: Uri? = null
    private var isZip: Boolean = false

    private val folderPickerLauncher = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            selectedUri = uri
            isZip = false
            binding.textViewSelectedFile.text = uri.path
            scanSelectedSource()
        }
    }

    private val zipPickerLauncher = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            selectedUri = uri
            isZip = true
            binding.textViewSelectedFile.text = getFileName(uri)
            scanSelectedSource()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        ThemeHelper.applyAmoledTheme(this)
        super.onCreate(savedInstanceState)
        binding = ActivityMoviebaseImportBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        binding.buttonSelectFolder.setOnClickListener {
            folderPickerLauncher.launch(null)
        }

        binding.buttonSelectZip.setOnClickListener {
            zipPickerLauncher.launch(arrayOf("application/zip", "application/x-zip-compressed", "multipart/x-zip", "application/zip-compressed", "application/octet-stream"))
        }

        binding.buttonStartImport.setOnClickListener {
            startImport()
        }

        binding.buttonLastImportReport.setOnClickListener {
            val prefs = PreferenceManager.getDefaultSharedPreferences(this)
            val jsonPath = prefs.getString("moviebase_last_report_json_path", null)
            val csvPath = prefs.getString("moviebase_last_report_csv_path", null)
            if (jsonPath != null) {
                val intent = Intent(this, ImportReportActivity::class.java).apply {
                    putExtra("report_json_path", jsonPath as String)
                    if (csvPath != null) putExtra("report_csv_path", csvPath as String)
                }
                startActivity(intent)
            } else {
                Toast.makeText(this, getString(R.string.no_report_found), Toast.LENGTH_SHORT).show()
            }
        }
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == android.R.id.home) {
            finish()
            return true
        }
        return super.onOptionsItemSelected(item)
    }

    private fun getFileName(uri: Uri): String {
        var result: String? = null
        if (uri.scheme == "content") {
            val cursor = contentResolver.query(uri, null, null, null, null)
            try {
                if (cursor != null && cursor.moveToFirst()) {
                    val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (index >= 0) {
                        result = cursor.getString(index)
                    }
                }
            } finally {
                cursor?.close()
            }
        }
        if (result == null) {
            result = uri.path
            val cut = result?.lastIndexOf('/') ?: -1
            if (cut != -1) {
                result = result?.substring(cut + 1)
            }
        }
        return result ?: "Unknown file"
    }

    private fun scanSelectedSource() {
        val uri = selectedUri ?: return
        val detectedFiles = mutableListOf<String>()

        try {
            if (isZip) {
                contentResolver.openInputStream(uri)?.use { inputStream ->
                    ZipInputStream(inputStream).use { zis ->
                        var zipEntry = zis.nextEntry
                        while (zipEntry != null) {
                            if (!zipEntry.isDirectory && zipEntry.name.endsWith(".csv")) {
                                detectedFiles.add(zipEntry.name.substringAfterLast("/"))
                            }
                            zipEntry = zis.nextEntry
                        }
                    }
                }
            } else {
                val documentFile = DocumentFile.fromTreeUri(this, uri)
                documentFile?.listFiles()?.forEach { file ->
                    if (file.isFile && file.name?.endsWith(".csv") == true) {
                        detectedFiles.add(file.name!!)
                    }
                }
            }

            if (detectedFiles.isNotEmpty()) {
                val expectedFiles = setOf("watched.csv", "ratings.csv", "watchlist.csv", "favorites.csv", "collection.csv", "lists.csv", "people.csv")
                val foundExpected = detectedFiles.filter { it in expectedFiles }

                binding.textViewDetectedFiles.visibility = View.VISIBLE
                binding.textViewDetectedFiles.text = getString(R.string.detected_files, foundExpected.joinToString(", "))
                binding.buttonStartImport.isEnabled = foundExpected.isNotEmpty()
            } else {
                binding.textViewDetectedFiles.visibility = View.VISIBLE
                binding.textViewDetectedFiles.text = getString(R.string.no_csv_files_found)
                binding.buttonStartImport.isEnabled = false
            }

        } catch (e: Exception) {
            e.printStackTrace()
            Toast.makeText(this, "Error scanning files: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun startImport() {
        val uri = selectedUri
        if (uri == null) {
            Toast.makeText(this, getString(R.string.please_select_a_csv_file_first), Toast.LENGTH_SHORT).show()
            return
        }

        val intent = Intent(this, MoviebaseImportService::class.java).apply {
            action = MoviebaseImportService.ACTION_START_IMPORT
            putExtra(MoviebaseImportService.EXTRA_FILE_URI, uri)
            putExtra(MoviebaseImportService.EXTRA_IS_ZIP, isZip)
        }
        startService(intent)

        Toast.makeText(this, getString(R.string.import_process_started), Toast.LENGTH_LONG).show()
        finish()
    }
}
