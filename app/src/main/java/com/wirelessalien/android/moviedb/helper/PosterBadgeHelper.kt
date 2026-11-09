package com.wirelessalien.android.moviedb.helper

import android.content.Context
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import androidx.preference.PreferenceManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

object PosterBadgeHelper {

    private var isCacheLoaded = false
    private val callbacks = mutableListOf<() -> Unit>()

    fun checkAndLoadCache(context: Context) {
        if (!isCacheLoaded) {
            CoroutineScope(Dispatchers.IO).launch {
                PosterStatusCache.getInstance(context).refresh()
                isCacheLoaded = true
                val list = callbacks.toList()
                CoroutineScope(Dispatchers.Main).launch {
                    list.forEach { it.invoke() }
                    callbacks.clear()
                }
            }
        }
    }

    fun invalidateCache(context: Context) {
        CoroutineScope(Dispatchers.IO).launch {
            PosterStatusCache.getInstance(context).refresh()
        }
    }

    fun applyBadges(
        context: Context,
        movieId: Int,
        isEpisode: Boolean = false,
        badgesContainer: View?,
        badgeWatched: ImageView?,
        badgeWillWatch: ImageView?,
        badgeFavorites: ImageView?,
        badgeCollection: ImageView?,
        badgeLists: ImageView?,
        badgeYourRating: TextView?
    ) {
        if (badgesContainer == null) return

        if (!isCacheLoaded) {
            badgesContainer.visibility = View.GONE
            callbacks.add {
                applyBadges(context, movieId, isEpisode, badgesContainer, badgeWatched, badgeWillWatch, badgeFavorites, badgeCollection, badgeLists, badgeYourRating)
            }
            checkAndLoadCache(context)
            return
        }

        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
        val cache = PosterStatusCache.getInstance(context)

        val showWatched = prefs.getBoolean("poster_show_watched", true)
        val showWillWatch = prefs.getBoolean("poster_show_will_watch", true)
        val showFavorites = prefs.getBoolean("poster_show_favorites", true)
        val showCollection = prefs.getBoolean("poster_show_collection", true)
        val showLists = prefs.getBoolean("poster_show_lists", true)
        val showYourRating = prefs.getBoolean("poster_show_your_rating", true)

        var hasVisibleBadge = false

        if (showWatched && !isEpisode && cache.isWatched(movieId)) {
            badgeWatched?.visibility = View.VISIBLE
            hasVisibleBadge = true
        } else {
            badgeWatched?.visibility = View.GONE
        }

        if (showWillWatch && cache.isWillWatch(movieId)) {
            badgeWillWatch?.visibility = View.VISIBLE
            hasVisibleBadge = true
        } else {
            badgeWillWatch?.visibility = View.GONE
        }

        if (showFavorites && cache.isFavorite(movieId)) {
            badgeFavorites?.visibility = View.VISIBLE
            hasVisibleBadge = true
        } else {
            badgeFavorites?.visibility = View.GONE
        }

        if (showCollection && cache.isInCollection(movieId)) {
            badgeCollection?.visibility = View.VISIBLE
            hasVisibleBadge = true
        } else {
            badgeCollection?.visibility = View.GONE
        }

        if (showLists && cache.isInList(movieId)) {
            badgeLists?.visibility = View.VISIBLE
            hasVisibleBadge = true
        } else {
            badgeLists?.visibility = View.GONE
        }

        val rating = cache.getRating(movieId)
        if (showYourRating && rating != null) {
            badgeYourRating?.text = rating.toString()
            badgeYourRating?.visibility = View.VISIBLE
            hasVisibleBadge = true
        } else {
            badgeYourRating?.visibility = View.GONE
        }

        badgesContainer.visibility = if (hasVisibleBadge) View.VISIBLE else View.GONE
    }
}
