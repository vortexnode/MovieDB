package com.wirelessalien.android.moviedb.fragment

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ProgressBar
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import com.wirelessalien.android.moviedb.R
import com.wirelessalien.android.moviedb.adapter.RecommendationsAdapter
import com.wirelessalien.android.moviedb.helper.MovieDatabaseHelper
import com.wirelessalien.android.moviedb.recommendations.RecommendationEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class RecommendationsTabFragment : Fragment() {

    private var isMovie: Boolean = true
    private lateinit var adapter: RecommendationsAdapter
    private lateinit var engine: RecommendationEngine
    private lateinit var swipeRefresh: SwipeRefreshLayout
    private lateinit var progressBar: ProgressBar
    private lateinit var emptyView: TextView
    private lateinit var recyclerView: RecyclerView

    companion object {
        private const val ARG_IS_MOVIE = "is_movie"
        fun newInstance(isMovie: Boolean) = RecommendationsTabFragment().apply {
            arguments = Bundle().apply {
                putBoolean(ARG_IS_MOVIE, isMovie)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        isMovie = arguments?.getBoolean(ARG_IS_MOVIE) ?: true
        engine = RecommendationEngine(requireContext(), MovieDatabaseHelper(requireContext()))
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        val view = inflater.inflate(R.layout.fragment_recommendations_tab, container, false)

        swipeRefresh = view.findViewById(R.id.swipe_refresh)
        progressBar = view.findViewById(R.id.progress_bar)
        emptyView = view.findViewById(R.id.empty_view)
        recyclerView = view.findViewById(R.id.recycler_view)

        adapter = RecommendationsAdapter(isMovie, requireContext(), MovieDatabaseHelper(requireContext()))
        recyclerView.layoutManager = LinearLayoutManager(requireContext())
        recyclerView.adapter = adapter

        swipeRefresh.setOnRefreshListener {
            loadRecommendations(forceRefresh = true)
        }

        loadRecommendations()

        return view
    }

    private fun loadRecommendations(forceRefresh: Boolean = false) {
        if (!forceRefresh && engine.getCachedResults(isMovie) != null) {
            updateUI(engine.getCachedResults(isMovie))
            return
        }

        if (!swipeRefresh.isRefreshing) {
            progressBar.visibility = View.VISIBLE
        }
        emptyView.visibility = View.GONE
        recyclerView.visibility = View.GONE

        lifecycleScope.launch {
            if (forceRefresh) {
                engine.invalidateCache()
            }
            val results = try {
                withContext(Dispatchers.IO) {
                    engine.getRecommendations(isMovie)
                }
            } catch (e: Exception) {
                e.printStackTrace()
                null
            }
            updateUI(results)
        }
    }

    private fun updateUI(results: List<com.wirelessalien.android.moviedb.recommendations.RecommendationResult>?) {
        swipeRefresh.isRefreshing = false
        progressBar.visibility = View.GONE
        if (results == null) {
            emptyView.text = "Failed to load recommendations. Please try again later."
            emptyView.visibility = View.VISIBLE
            recyclerView.visibility = View.GONE
        } else if (results.isEmpty()) {
            emptyView.text = getString(R.string.recommendations_empty)
            emptyView.visibility = View.VISIBLE
            recyclerView.visibility = View.GONE
        } else {
            emptyView.visibility = View.GONE
            recyclerView.visibility = View.VISIBLE
            adapter.submitList(results)
        }
    }
}
