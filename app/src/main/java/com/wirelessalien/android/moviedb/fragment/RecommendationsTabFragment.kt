package com.wirelessalien.android.moviedb.fragment

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import com.facebook.shimmer.ShimmerFrameLayout
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
    private lateinit var shimmerContainer: ShimmerFrameLayout
    private lateinit var emptyStateContainer: LinearLayout
    private lateinit var emptyView: TextView
    private lateinit var btnRetry: Button
    private lateinit var recyclerView: RecyclerView

    private var currentGridMode = false

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
        shimmerContainer = view.findViewById(R.id.shimmer_view_container)
        emptyStateContainer = view.findViewById(R.id.empty_state_container)
        emptyView = view.findViewById(R.id.empty_view)
        btnRetry = view.findViewById(R.id.btn_retry)
        recyclerView = view.findViewById(R.id.recycler_view)

        adapter = RecommendationsAdapter(isMovie, requireContext(), MovieDatabaseHelper(requireContext()))
        setGridMode(currentGridMode)
        recyclerView.adapter = adapter

        swipeRefresh.setOnRefreshListener {
            loadRecommendations(forceRefresh = true)
        }

        btnRetry.setOnClickListener {
            loadRecommendations(forceRefresh = true)
        }

        loadRecommendations()

        return view
    }

    fun setGridMode(isGrid: Boolean) {
        currentGridMode = isGrid
        if (::recyclerView.isInitialized) {
            if (isGrid) {
                recyclerView.layoutManager = GridLayoutManager(requireContext(), 2)
            } else {
                recyclerView.layoutManager = LinearLayoutManager(requireContext())
            }
            adapter.isGrid = isGrid
            adapter.notifyDataSetChanged()
        }
    }

    private fun loadRecommendations(forceRefresh: Boolean = false) {
        if (!forceRefresh && engine.getCachedResults(isMovie) != null) {
            updateUI(engine.getCachedResults(isMovie)!!)
            return
        }

        if (!swipeRefresh.isRefreshing) {
            shimmerContainer.visibility = View.VISIBLE
            shimmerContainer.startShimmer()
        }
        emptyStateContainer.visibility = View.GONE
        recyclerView.visibility = View.GONE

        lifecycleScope.launch {
            if (forceRefresh) {
                engine.invalidateCache()
            }
            try {
                val results = withContext(Dispatchers.IO) {
                    engine.getRecommendations(isMovie)
                }
                updateUI(results)
            } catch (e: Exception) {
                updateUI(emptyList(), isError = true)
            }
        }
    }

    private fun updateUI(results: List<com.wirelessalien.android.moviedb.recommendations.RecommendationResult>, isError: Boolean = false) {
        swipeRefresh.isRefreshing = false
        shimmerContainer.stopShimmer()
        shimmerContainer.visibility = View.GONE

        if (isError) {
            emptyStateContainer.visibility = View.VISIBLE
            emptyView.text = getString(R.string.error_state)
            recyclerView.visibility = View.GONE
        } else if (results.isEmpty()) {
            emptyStateContainer.visibility = View.VISIBLE
            emptyView.text = getString(R.string.recommendations_empty)
            recyclerView.visibility = View.GONE
        } else {
            emptyStateContainer.visibility = View.GONE
            recyclerView.visibility = View.VISIBLE
            adapter.submitList(results)
        }
    }
}
