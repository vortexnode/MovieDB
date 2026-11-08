package com.wirelessalien.android.moviedb.recommendations

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import com.wirelessalien.android.moviedb.helper.MovieDatabaseHelper
import android.content.ContentValues

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [32])
class RecommendationEngineTest {

    @Test
    fun testDbExclusions() {
        val context = RuntimeEnvironment.getApplication()
        val dbHelper = MovieDatabaseHelper(context)
        val engine = RecommendationEngine(context, dbHelper)

        val db = dbHelper.writableDatabase

        // Add a watched movie
        val cvWatched = ContentValues().apply {
            put(MovieDatabaseHelper.COLUMN_MOVIES_ID, 200)
            put(MovieDatabaseHelper.COLUMN_RATING, 0.0)
            put(MovieDatabaseHelper.COLUMN_IMAGE, "")
            put(MovieDatabaseHelper.COLUMN_ICON, "")
            put(MovieDatabaseHelper.COLUMN_TITLE, "Watched Movie")
            put(MovieDatabaseHelper.COLUMN_SUMMARY, "")
            put(MovieDatabaseHelper.COLUMN_GENRES, "")
            put(MovieDatabaseHelper.COLUMN_GENRES_IDS, "")
            put(MovieDatabaseHelper.COLUMN_CATEGORIES, MovieDatabaseHelper.CATEGORY_WATCHED)
            put(MovieDatabaseHelper.COLUMN_MOVIE, 1)
        }
        db.insert(MovieDatabaseHelper.TABLE_MOVIES, null, cvWatched)

        // Add a Plan to Watch movie
        val cvPlanToWatch = ContentValues().apply {
            put(MovieDatabaseHelper.COLUMN_MOVIES_ID, 202)
            put(MovieDatabaseHelper.COLUMN_RATING, 0.0)
            put(MovieDatabaseHelper.COLUMN_IMAGE, "")
            put(MovieDatabaseHelper.COLUMN_ICON, "")
            put(MovieDatabaseHelper.COLUMN_TITLE, "Plan to Watch Movie")
            put(MovieDatabaseHelper.COLUMN_SUMMARY, "")
            put(MovieDatabaseHelper.COLUMN_GENRES, "")
            put(MovieDatabaseHelper.COLUMN_GENRES_IDS, "")
            put(MovieDatabaseHelper.COLUMN_CATEGORIES, MovieDatabaseHelper.CATEGORY_PLAN_TO_WATCH)
            put(MovieDatabaseHelper.COLUMN_MOVIE, 1)
        }
        db.insert(MovieDatabaseHelper.TABLE_MOVIES, null, cvPlanToWatch)

        // Add a Not interested item
        dbHelper.insertNotInterested(201, "movie")

        val exclusions = engine.getExclusions(true)
        assertTrue(exclusions.contains(200))
        assertTrue(exclusions.contains(201))
        assertTrue(exclusions.contains(202))
    }
}
