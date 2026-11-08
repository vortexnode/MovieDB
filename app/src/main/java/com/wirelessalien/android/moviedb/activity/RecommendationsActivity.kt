package com.wirelessalien.android.moviedb.activity

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.viewpager2.widget.ViewPager2
import com.google.android.material.tabs.TabLayout
import com.google.android.material.tabs.TabLayoutMediator
import com.wirelessalien.android.moviedb.R
import com.wirelessalien.android.moviedb.adapter.RecommendationsPagerAdapter
import com.wirelessalien.android.moviedb.helper.ThemeHelper

class RecommendationsActivity : BaseActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        ThemeHelper.applyAmoledTheme(this)
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_recommendations)

        val toolbar = findViewById<androidx.appcompat.widget.Toolbar>(R.id.toolbar)
        setSupportActionBar(toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = getString(R.string.title_recommendations)

        val viewPager = findViewById<ViewPager2>(R.id.view_pager)
        val tabs = findViewById<TabLayout>(R.id.tabs)

        viewPager.adapter = RecommendationsPagerAdapter(this)

        TabLayoutMediator(tabs, viewPager) { tab, position ->
            tab.text = when (position) {
                0 -> getString(R.string.recommendations_tab_movies)
                1 -> getString(R.string.recommendations_tab_series)
                else -> null
            }
        }.attach()
    }

    override fun onSupportNavigateUp(): Boolean {
        onBackPressed()
        return true
    }
}
