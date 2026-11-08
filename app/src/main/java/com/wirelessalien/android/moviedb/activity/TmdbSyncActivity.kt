package com.wirelessalien.android.moviedb.activity

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.preference.PreferenceManager
import androidx.recyclerview.widget.LinearLayoutManager
import com.wirelessalien.android.moviedb.R
import com.wirelessalien.android.moviedb.adapter.TagSyncAdapter
import com.wirelessalien.android.moviedb.data.Tag
import com.wirelessalien.android.moviedb.databinding.ActivityTmdbSyncBinding
import com.wirelessalien.android.moviedb.helper.MovieDatabaseHelper
import com.wirelessalien.android.moviedb.helper.ThemeHelper
import com.wirelessalien.android.moviedb.service.TmdbSyncService

class TmdbSyncActivity : AppCompatActivity() {

    private lateinit var binding: ActivityTmdbSyncBinding
    private val selectedTags = mutableSetOf<Long>()
    private lateinit var dbHelper: MovieDatabaseHelper

    override fun onCreate(savedInstanceState: Bundle?) {
        ThemeHelper.applyAmoledTheme(this)
        super.onCreate(savedInstanceState)
        binding = ActivityTmdbSyncBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.toolbar.setNavigationIcon(androidx.appcompat.R.drawable.abc_ic_ab_back_material)
        binding.toolbar.setNavigationOnClickListener { onBackPressedDispatcher.onBackPressed() }

        dbHelper = MovieDatabaseHelper(this)

        val preferences = PreferenceManager.getDefaultSharedPreferences(this)
        val accessToken = preferences.getString("access_token", "")
        if (accessToken.isNullOrEmpty()) {
            Toast.makeText(this, R.string.tmdb_sync_not_logged_in, Toast.LENGTH_LONG).show()
            finish()
            return
        }

        binding.checkboxLists.setOnCheckedChangeListener { _, isChecked ->
            binding.textViewListSelection.visibility = if (isChecked) View.VISIBLE else View.GONE
            binding.recyclerViewTags.visibility = if (isChecked) View.VISIBLE else View.GONE
        }

        val tags = dbHelper.getAllTags()
        if (tags.isNotEmpty()) {
            tags.forEach { selectedTags.add(it.id) }
            val adapter = TagSyncAdapter(tags, selectedTags)
            binding.recyclerViewTags.layoutManager = LinearLayoutManager(this)
            binding.recyclerViewTags.adapter = adapter
            binding.textViewListSelection.visibility = View.VISIBLE
            binding.recyclerViewTags.visibility = View.VISIBLE
        } else {
            binding.checkboxLists.isChecked = false
            binding.checkboxLists.isEnabled = false
        }

        binding.buttonSync.setOnClickListener {
            val syncRatings = binding.checkboxRatings.isChecked
            val syncFavorites = binding.checkboxFavorites.isChecked
            val syncWatchlist = binding.checkboxWatchlist.isChecked
            val syncLists = binding.checkboxLists.isChecked
            val dryRun = binding.checkboxDryRun.isChecked

            if (syncLists && selectedTags.isEmpty()) {
                Toast.makeText(this, R.string.tmdb_sync_select_tags_error, Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            val intent = Intent(this, TmdbSyncService::class.java).apply {
                putExtra("syncRatings", syncRatings)
                putExtra("syncFavorites", syncFavorites)
                putExtra("syncWatchlist", syncWatchlist)
                putExtra("syncLists", syncLists)
                putExtra("dryRun", dryRun)
                putExtra("selectedTags", selectedTags.toLongArray())
            }
            startService(intent)

            if (dryRun) {
                binding.textViewSyncStatus.visibility = View.VISIBLE
                binding.textViewSyncStatus.text = getString(R.string.tmdb_sync_started)
            } else {
                Toast.makeText(this, R.string.tmdb_sync_started, Toast.LENGTH_SHORT).show()
                finish()
            }
        }
    }
}
