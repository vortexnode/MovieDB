package com.wirelessalien.android.moviedb.adapter

import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import androidx.viewpager2.adapter.FragmentStateAdapter
import com.wirelessalien.android.moviedb.fragment.RecommendationsTabFragment

class RecommendationsPagerAdapter(activity: FragmentActivity) : FragmentStateAdapter(activity) {
    override fun getItemCount(): Int = 2

    var currentGridMode: Boolean = false

    override fun createFragment(position: Int): Fragment {
        val fragment = RecommendationsTabFragment.newInstance(position == 0)
        if (currentGridMode) {
            fragment.setGridMode(true)
        }
        return fragment
    }
}
