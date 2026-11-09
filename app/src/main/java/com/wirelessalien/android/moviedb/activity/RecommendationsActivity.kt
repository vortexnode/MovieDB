package com.wirelessalien.android.moviedb.activity

import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import androidx.appcompat.app.AppCompatActivity
import androidx.viewpager2.widget.ViewPager2
import com.google.android.material.tabs.TabLayout
import com.google.android.material.tabs.TabLayoutMediator
import com.wirelessalien.android.moviedb.R
import com.wirelessalien.android.moviedb.adapter.RecommendationsPagerAdapter
import com.wirelessalien.android.moviedb.fragment.RecommendationsTabFragment
import com.wirelessalien.android.moviedb.helper.ThemeHelper

class RecommendationsActivity : BaseActivity() {

    private var isGridMode = false
    private lateinit var viewPager: ViewPager2
    private lateinit var pagerAdapter: RecommendationsPagerAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        ThemeHelper.applyAmoledTheme(this)
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_recommendations)

        val toolbar = findViewById<androidx.appcompat.widget.Toolbar>(R.id.toolbar)
        setSupportActionBar(toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = getString(R.string.title_recommendations)

        viewPager = findViewById(R.id.view_pager)
        val tabs = findViewById<TabLayout>(R.id.tabs)

        pagerAdapter = RecommendationsPagerAdapter(this)
        viewPager.adapter = pagerAdapter

        TabLayoutMediator(tabs, viewPager) { tab, position ->
            tab.text = when (position) {
                0 -> getString(R.string.recommendations_tab_movies)
                1 -> getString(R.string.recommendations_tab_series)
                else -> null
            }
        }.attach()
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.menu_recommendations, menu)
        val toggleItem = menu.findItem(R.id.action_toggle_view)
        toggleItem.title = if (isGridMode) getString(R.string.show_as_list) else getString(R.string.show_as_grid)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.action_toggle_view -> {
                isGridMode = !isGridMode
                invalidateOptionsMenu()
                updateFragmentsGridMode()
                true
            }
            android.R.id.home -> {
                onBackPressed()
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    private fun updateFragmentsGridMode() {
        pagerAdapter.currentGridMode = isGridMode
        supportFragmentManager.fragments.forEach { fragment ->
            if (fragment is RecommendationsTabFragment) {
                fragment.setGridMode(isGridMode)
            }
        }
    }
}
