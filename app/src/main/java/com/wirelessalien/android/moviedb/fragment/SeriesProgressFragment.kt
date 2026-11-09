package com.wirelessalien.android.moviedb.fragment

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.snackbar.Snackbar
import com.wirelessalien.android.moviedb.R
import com.wirelessalien.android.moviedb.activity.DetailActivity
import com.wirelessalien.android.moviedb.adapter.SeriesProgressRecyclerAdapter
import com.wirelessalien.android.moviedb.helper.ConfigHelper
import com.wirelessalien.android.moviedb.helper.MovieDatabaseHelper
import com.wirelessalien.android.moviedb.helper.WatchDateHelper
import com.wirelessalien.android.moviedb.repository.ShowProgressItem
import com.wirelessalien.android.moviedb.repository.ShowProgressRepository
import kotlinx.coroutines.launch
import org.json.JSONObject

class SeriesProgressFragment : Fragment() {

    private lateinit var rvProgress: RecyclerView
    private lateinit var progressBar: View
    private lateinit var tvEmpty: TextView
    private lateinit var btnEmptyDiscover: View

    private lateinit var adapter: SeriesProgressRecyclerAdapter
    private var allItems = listOf<ShowProgressItem>()

    // Shared state received from Hub
    private var currentSort = "recently_watched"
    private var isGrid = false
    private var onlyUnfinished = false
    private var onlyNewEpisodes = false

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        val view = inflater.inflate(R.layout.fragment_series_progress, container, false)
        rvProgress = view.findViewById(R.id.rvProgress)
        progressBar = view.findViewById(R.id.progressBar)
        tvEmpty = view.findViewById(R.id.tvEmpty)
        btnEmptyDiscover = view.findViewById(R.id.btnEmptyDiscover)

        adapter = SeriesProgressRecyclerAdapter(emptyList(), isGrid, { item -> markWatched(item) }, { item -> openDetails(item) })
        rvProgress.adapter = adapter
        updateLayoutManager()

        btnEmptyDiscover.setOnClickListener {
            // Tell parent hub to switch to discover
            (parentFragment as? SeriesHubFragment)?.switchToDiscover()
        }

        loadData()

        return view
    }

    private fun loadData() {
        progressBar.visibility = View.VISIBLE
        tvEmpty.visibility = View.GONE
        btnEmptyDiscover.visibility = View.GONE
        rvProgress.visibility = View.GONE

        lifecycleScope.launch {
            val apiKey = ConfigHelper.getConfigValue(requireContext(), "api_key") ?: ""
            val repo = ShowProgressRepository(requireContext())
            allItems = repo.getWatchingShowsProgress(apiKey)
            applyFiltersAndSort()

            progressBar.visibility = View.GONE
        }
    }

    fun updateGridMode(grid: Boolean) {
        isGrid = grid
        if (::adapter.isInitialized) {
            adapter.setGridMode(grid)
            updateLayoutManager()
        }
    }

    private fun updateLayoutManager() {
        if (isGrid) {
            rvProgress.layoutManager = GridLayoutManager(requireContext(), 3)
        } else {
            rvProgress.layoutManager = LinearLayoutManager(requireContext())
        }
    }

    fun updateSort(sortBy: String) {
        currentSort = sortBy
        if (::adapter.isInitialized) {
            applyFiltersAndSort()
        }
    }

    fun updateFilters(unfinished: Boolean, newEpisodes: Boolean) {
        onlyUnfinished = unfinished
        onlyNewEpisodes = newEpisodes
        if (::adapter.isInitialized) {
            applyFiltersAndSort()
        }
    }

    private fun applyFiltersAndSort() {
        var filtered = allItems
        if (onlyUnfinished) {
            filtered = filtered.filter { it.watchedEpisodes < it.totalEpisodes }
        }
        if (onlyNewEpisodes) {
            // Basic approximation: if it has an unwatched episode that aired
            filtered = filtered.filter { it.nextEpisodeAirDate != null && isPastOrToday(it.nextEpisodeAirDate) }
        }

        val repo = ShowProgressRepository(requireContext())
        val sorted = repo.sortItems(filtered, currentSort)

        adapter.updateItems(sorted)

        if (sorted.isEmpty()) {
            rvProgress.visibility = View.GONE
            tvEmpty.visibility = View.VISIBLE
            btnEmptyDiscover.visibility = View.VISIBLE
        } else {
            rvProgress.visibility = View.VISIBLE
            tvEmpty.visibility = View.GONE
            btnEmptyDiscover.visibility = View.GONE
        }

        // Update count in parent
        (parentFragment as? SeriesHubFragment)?.updateCount(sorted.size, "progress")
    }

    private fun isPastOrToday(dateStr: String): Boolean {
        // Simple string comparison works for YYYY-MM-DD
        val today = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.getDefault()).format(java.util.Date())
        return dateStr <= today
    }

    private fun markWatched(item: ShowProgressItem) {
        if (item.nextEpisodeSeason == null || item.nextEpisodeNumber == null) return

        WatchDateHelper.showWatchDateDialog(requireContext(), "", null, 0) { result ->
            val watchDate = when (result) {
                is WatchDateHelper.WatchDateResult.DateSelected -> result.date
                is WatchDateHelper.WatchDateResult.UnknownDate -> null
                is WatchDateHelper.WatchDateResult.StartWatching -> WatchDateHelper.getCurrentDate()
                is WatchDateHelper.WatchDateResult.Cancelled -> return@showWatchDateDialog
            }

            val db = MovieDatabaseHelper(requireContext())
            db.addEpisodeNumber(item.id, item.nextEpisodeSeason, listOf(item.nextEpisodeNumber), watchDate)

            Snackbar.make(requireView(), R.string.series_mark_watched_undo, Snackbar.LENGTH_LONG)
                .setAction(R.string.series_undo) {
                    db.removeEpisodeNumber(item.id, item.nextEpisodeSeason, listOf(item.nextEpisodeNumber))
                    loadData()
                }.show()

            loadData()
        }
    }

    private fun openDetails(item: ShowProgressItem) {
        val jsonObj = JSONObject().apply {
            put(MovieDatabaseHelper.COLUMN_MOVIES_ID, item.id)
            put(MovieDatabaseHelper.COLUMN_TITLE, item.title)
            put(MovieDatabaseHelper.COLUMN_IMAGE, item.posterPath)
        }
        val intent = Intent(requireContext(), DetailActivity::class.java).apply {
            putExtra("movieObject", jsonObj.toString())
            putExtra("isMovie", false)
        }
        startActivity(intent)
    }
}
