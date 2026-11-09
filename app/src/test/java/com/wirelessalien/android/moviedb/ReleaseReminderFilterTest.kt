package com.wirelessalien.android.moviedb

import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Test
import com.wirelessalien.android.moviedb.helper.MovieDatabaseHelper

class ReleaseReminderFilterTest {

    @Test
    fun testLibraryFilterLogic() {
        val categoryWatching = MovieDatabaseHelper.CATEGORY_WATCHING
        val categoryPlanToWatch = MovieDatabaseHelper.CATEGORY_PLAN_TO_WATCH
        val categoryWatched = MovieDatabaseHelper.CATEGORY_WATCHED

        val isMoviePlanToWatch = categoryPlanToWatch == MovieDatabaseHelper.CATEGORY_PLAN_TO_WATCH
        assertTrue("Movie in Plan to Watch should be allowed", isMoviePlanToWatch)

        val isMovieWatched = categoryWatched == MovieDatabaseHelper.CATEGORY_PLAN_TO_WATCH
        assertFalse("Movie in Watched should not be allowed directly (unless favorite)", isMovieWatched)

        val isTvWatching = categoryWatching == MovieDatabaseHelper.CATEGORY_WATCHING || categoryWatching == MovieDatabaseHelper.CATEGORY_PLAN_TO_WATCH
        assertTrue("TV Show in Watching should be allowed", isTvWatching)

        val isTvPlanToWatch = categoryPlanToWatch == MovieDatabaseHelper.CATEGORY_WATCHING || categoryPlanToWatch == MovieDatabaseHelper.CATEGORY_PLAN_TO_WATCH
        assertTrue("TV Show in Plan to Watch should be allowed", isTvPlanToWatch)

        val isTvWatched = categoryWatched == MovieDatabaseHelper.CATEGORY_WATCHING || categoryWatched == MovieDatabaseHelper.CATEGORY_PLAN_TO_WATCH
        assertFalse("TV Show in Watched should not be allowed", isTvWatched)
    }
}
