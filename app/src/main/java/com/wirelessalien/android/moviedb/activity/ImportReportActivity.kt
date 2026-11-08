package com.wirelessalien.android.moviedb.activity

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.gson.Gson
import com.wirelessalien.android.moviedb.R
import com.wirelessalien.android.moviedb.databinding.ActivityImportReportBinding
import com.wirelessalien.android.moviedb.helper.ImportIssue
import com.wirelessalien.android.moviedb.helper.ImportReport
import com.wirelessalien.android.moviedb.helper.ThemeHelper
import java.io.File

class ImportReportActivity : AppCompatActivity() {

    private lateinit var binding: ActivityImportReportBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        ThemeHelper.applyAmoledTheme(this)
        super.onCreate(savedInstanceState)

        binding = ActivityImportReportBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = getString(R.string.import_report_title)

        val reportJsonPath = intent.getStringExtra("report_json_path")
        val reportCsvPath = intent.getStringExtra("report_csv_path")

        if (reportJsonPath != null) {
            try {
                val reportJson = if (reportJsonPath.startsWith("content://")) {
                    contentResolver.openInputStream(Uri.parse(reportJsonPath))?.bufferedReader()?.use { it.readText() }
                        ?: throw Exception("Could not open content URI")
                } else {
                    File(reportJsonPath).readText()
                }
                val report = Gson().fromJson(reportJson, ImportReport::class.java)

                binding.textViewSummary.text = getString(
                    R.string.import_report_summary,
                    report.total,
                    report.inserted,
                    report.updated,
                    report.skipped,
                    report.failed
                )

                binding.recyclerViewIssues.layoutManager = LinearLayoutManager(this)
                binding.recyclerViewIssues.adapter = IssuesAdapter(report.issues)
            } catch (e: Exception) {
                binding.textViewSummary.text = getString(R.string.error_loading_report)
                e.printStackTrace()
            }
        } else {
            binding.textViewSummary.text = getString(R.string.error_loading_report)
        }

        binding.buttonExportCsv.setOnClickListener {
            if (reportCsvPath != null) {
                shareCsvFile(reportCsvPath)
            } else {
                Toast.makeText(this, getString(R.string.csv_file_not_found), Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun shareCsvFile(csvPath: String) {
        val uri: Uri
        if (csvPath.startsWith("content://")) {
            uri = Uri.parse(csvPath)
        } else {
            val file = File(csvPath)
            if (file.exists()) {
                uri = FileProvider.getUriForFile(
                    this,
                    "${applicationContext.packageName}.provider",
                    file
                )
            } else {
                Toast.makeText(this, getString(R.string.csv_file_not_found), Toast.LENGTH_SHORT).show()
                return
            }
        }

        val shareIntent = Intent(Intent.ACTION_SEND).apply {
            type = "text/csv"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, "Import Report")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(Intent.createChooser(shareIntent, getString(R.string.share_import_report)))
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == android.R.id.home) {
            finish()
            return true
        }
        return super.onOptionsItemSelected(item)
    }

    private class IssuesAdapter(private val issues: List<ImportIssue>) : RecyclerView.Adapter<IssuesAdapter.ViewHolder>() {

        class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            val rowInfo: TextView = view.findViewById(R.id.textViewRowInfo)
            val reasonInfo: TextView = view.findViewById(R.id.textViewReason)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val view = LayoutInflater.from(parent.context).inflate(R.layout.item_import_issue, parent, false)
            return ViewHolder(view)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val issue = issues[position]
            val context = holder.itemView.context

            holder.rowInfo.text = "Row ${issue.rowNumber}: ${issue.title ?: "Unknown Title"} (ID: ${issue.tmdbId ?: "Unknown"})"
            holder.reasonInfo.text = "Reason: ${issue.reason.name} - ${issue.detailMessage ?: ""}"
        }

        override fun getItemCount() = issues.size
    }
}
