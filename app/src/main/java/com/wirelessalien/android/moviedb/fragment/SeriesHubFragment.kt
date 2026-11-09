package com.wirelessalien.android.moviedb.fragment

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import com.google.android.material.bottomsheet.BottomSheetDialog
import androidx.appcompat.widget.PopupMenu
import android.widget.CheckBox
import androidx.preference.PreferenceManager
import com.google.android.material.button.MaterialButtonToggleGroup
import com.wirelessalien.android.moviedb.R
import com.wirelessalien.android.moviedb.activity.MainActivity

class SeriesHubFragment : BaseFragment() {

    private lateinit var toggleGroupSegments: MaterialButtonToggleGroup
    private lateinit var actionGroup: LinearLayout
    private lateinit var headerContainer: LinearLayout
    private lateinit var btnFilter: ImageButton
    private lateinit var btnToggleView: ImageButton
    private lateinit var tvCount: TextView
    private lateinit var btnSort: LinearLayout
    private lateinit var tvSort: TextView

    private val PREF_LAST_SEGMENT = "series_hub_last_segment"
    private val PREF_IS_GRID = "series_hub_is_grid"
    private val PREF_PROGRESS_SORT = "series_hub_progress_sort"

    private var progressFragment: SeriesProgressFragment? = null
    private var soonFragment: SeriesSoonFragment? = null
    private var discoverFragment: ShowFragment? = null

    private var isGrid = false
    private var currentSort = "recently_watched"
    private var onlyUnfinished = false
    private var onlyNewEpisodes = false

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        val view = inflater.inflate(R.layout.fragment_series_hub, container, false)

        toggleGroupSegments = view.findViewById(R.id.toggleGroupSegments)
        actionGroup = view.findViewById(R.id.actionGroup)
        headerContainer = view.findViewById(R.id.headerContainer)
        btnFilter = view.findViewById(R.id.btnFilter)
        btnToggleView = view.findViewById(R.id.btnToggleView)
        tvCount = view.findViewById(R.id.tvCount)
        btnSort = view.findViewById(R.id.btnSort)
        tvSort = view.findViewById(R.id.tvSort)

        val prefs = PreferenceManager.getDefaultSharedPreferences(requireContext())
        isGrid = prefs.getBoolean(PREF_IS_GRID, false)
        currentSort = prefs.getString(PREF_PROGRESS_SORT, "recently_watched") ?: "recently_watched"

        setupSegments(prefs)
        setupActions()

        return view
    }

    private fun setupSegments(prefs: android.content.SharedPreferences) {
        val lastSegment = prefs.getInt(PREF_LAST_SEGMENT, R.id.btnProgress)
        toggleGroupSegments.check(lastSegment)
        showSegment(lastSegment)

        toggleGroupSegments.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (isChecked) {
                prefs.edit().putInt(PREF_LAST_SEGMENT, checkedId).apply()
                showSegment(checkedId)
            }
        }
    }

    private fun showSegment(id: Int) {
        val fm = childFragmentManager
        val ft = fm.beginTransaction()

        // Hide all
        fm.fragments.forEach { ft.hide(it) }

        when (id) {
            R.id.btnProgress -> {
                actionGroup.visibility = View.VISIBLE
                headerContainer.visibility = View.VISIBLE
                btnSort.visibility = View.VISIBLE
                btnFilter.visibility = View.VISIBLE
                tvSort.text = getSortName(currentSort)
                updateGridIcon()

                if (progressFragment == null) {
                    progressFragment = childFragmentManager.findFragmentByTag("progress") as? SeriesProgressFragment ?: SeriesProgressFragment()
                    if (!progressFragment!!.isAdded) ft.add(R.id.fragmentContainer, progressFragment!!, "progress")
                }
                ft.show(progressFragment!!)

                progressFragment?.updateGridMode(isGrid)
                progressFragment?.updateSort(currentSort)
                progressFragment?.updateFilters(onlyUnfinished, onlyNewEpisodes)
            }
            R.id.btnSoon -> {
                actionGroup.visibility = View.INVISIBLE // maintain space or GONE
                headerContainer.visibility = View.VISIBLE
                btnSort.visibility = View.VISIBLE
                btnFilter.visibility = View.GONE
                tvSort.text = getString(R.string.series_sort_release_date)

                if (soonFragment == null) {
                    soonFragment = childFragmentManager.findFragmentByTag("soon") as? SeriesSoonFragment ?: SeriesSoonFragment()
                    if (!soonFragment!!.isAdded) ft.add(R.id.fragmentContainer, soonFragment!!, "soon")
                }
                ft.show(soonFragment!!)
            }
            R.id.btnDiscover -> {
                actionGroup.visibility = View.GONE
                headerContainer.visibility = View.GONE

                if (discoverFragment == null) {
                    discoverFragment = childFragmentManager.findFragmentByTag("discover") as? ShowFragment ?: ShowFragment.newInstance(MainActivity.TV)
                    if (!discoverFragment!!.isAdded) ft.add(R.id.fragmentContainer, discoverFragment!!, "discover")
                }
                ft.show(discoverFragment!!)
            }
        }
        ft.commit()
    }

    private fun setupActions() {
        btnToggleView.setOnClickListener {
            isGrid = !isGrid
            PreferenceManager.getDefaultSharedPreferences(requireContext()).edit().putBoolean(PREF_IS_GRID, isGrid).apply()
            updateGridIcon()
            progressFragment?.updateGridMode(isGrid)
        }

        btnSort.setOnClickListener {
            val popup = PopupMenu(requireContext(), btnSort)
            popup.menu.add(0, 1, 0, R.string.series_sort_recently_watched)
            popup.menu.add(0, 2, 0, R.string.series_sort_name)
            popup.menu.add(0, 3, 0, R.string.series_sort_progress)
            popup.menu.add(0, 4, 0, R.string.series_sort_next_air_date)

            popup.setOnMenuItemClickListener { item ->
                currentSort = when (item.itemId) {
                    1 -> "recently_watched"
                    2 -> "name"
                    3 -> "progress"
                    4 -> "next_air_date"
                    else -> "recently_watched"
                }
                PreferenceManager.getDefaultSharedPreferences(requireContext()).edit().putString(PREF_PROGRESS_SORT, currentSort).apply()
                tvSort.text = getSortName(currentSort)
                progressFragment?.updateSort(currentSort)
                true
            }
            popup.show()
        }

        btnFilter.setOnClickListener {
            val bottomSheet = BottomSheetDialog(requireContext())
            val view = layoutInflater.inflate(R.layout.bottom_sheet_series_filter, null)
            val cbUnfinished = view.findViewById<CheckBox>(R.id.cbUnfinished)
            val cbNewEpisodes = view.findViewById<CheckBox>(R.id.cbNewEpisodes)

            cbUnfinished.isChecked = onlyUnfinished
            cbNewEpisodes.isChecked = onlyNewEpisodes

            cbUnfinished.setOnCheckedChangeListener { _, isChecked ->
                onlyUnfinished = isChecked
                btnFilter.alpha = if (onlyUnfinished || onlyNewEpisodes) 1.0f else 0.6f
                progressFragment?.updateFilters(onlyUnfinished, onlyNewEpisodes)
            }
            cbNewEpisodes.setOnCheckedChangeListener { _, isChecked ->
                onlyNewEpisodes = isChecked
                btnFilter.alpha = if (onlyUnfinished || onlyNewEpisodes) 1.0f else 0.6f
                progressFragment?.updateFilters(onlyUnfinished, onlyNewEpisodes)
            }
            bottomSheet.setContentView(view)
            bottomSheet.show()
        }
    }

    private fun updateGridIcon() {
        btnToggleView.setImageResource(if (isGrid) R.drawable.ic_view_list else R.drawable.ic_grid_view)
    }

    private fun getSortName(sort: String): String {
        return when (sort) {
            "name" -> getString(R.string.series_sort_name)
            "progress" -> getString(R.string.series_sort_progress)
            "next_air_date" -> getString(R.string.series_sort_next_air_date)
            "recently_watched" -> getString(R.string.series_sort_recently_watched)
            else -> getString(R.string.series_sort_recently_watched)
        }
    }

    fun updateCount(count: Int, type: String) {
        if (type == "progress" && toggleGroupSegments.checkedButtonId == R.id.btnProgress) {
            tvCount.text = resources.getQuantityString(R.plurals.series_shows_count, count, count)
        } else if (type == "soon" && toggleGroupSegments.checkedButtonId == R.id.btnSoon) {
            tvCount.text = resources.getQuantityString(R.plurals.series_episodes_count, count, count)
        }
    }

    fun switchToDiscover() {
        toggleGroupSegments.check(R.id.btnDiscover)
    }

    override fun doNetworkWork() {
        discoverFragment?.doNetworkWork()
    }
}
