package com.wirelessalien.android.moviedb.helper

import android.content.Context
import com.wirelessalien.android.moviedb.helper.ListDatabaseHelper
import com.wirelessalien.android.moviedb.helper.MovieDatabaseHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap
import java.util.Collections
import android.database.Cursor

class PosterStatusCache private constructor(private val context: Context) {

    private val favorites = Collections.newSetFromMap(ConcurrentHashMap<Int, Boolean>())
    private val collections = Collections.newSetFromMap(ConcurrentHashMap<Int, Boolean>())
    private val watched = Collections.newSetFromMap(ConcurrentHashMap<Int, Boolean>())
    private val willWatch = Collections.newSetFromMap(ConcurrentHashMap<Int, Boolean>())
    private val lists = Collections.newSetFromMap(ConcurrentHashMap<Int, Boolean>())
    private val ratings = ConcurrentHashMap<Int, Int>()

    suspend fun refresh() = withContext(Dispatchers.IO) {
        val movieDb = MovieDatabaseHelper(context)
        val listDb = ListDatabaseHelper(context)

        try {
            val db = movieDb.readableDatabase

            val cursor = db.rawQuery("SELECT ${MovieDatabaseHelper.COLUMN_MOVIES_ID}, ${MovieDatabaseHelper.COLUMN_CATEGORIES}, ${MovieDatabaseHelper.COLUMN_PERSONAL_RATING} FROM ${MovieDatabaseHelper.TABLE_MOVIES}", null)
            val tagCursor = db.rawQuery("SELECT t.${MovieDatabaseHelper.COLUMN_TAG_NAME}, mt.${MovieDatabaseHelper.COLUMN_MT_MOVIE_ID} FROM ${MovieDatabaseHelper.TABLE_TAGS} t INNER JOIN ${MovieDatabaseHelper.TABLE_MOVIE_TAGS} mt ON t.${MovieDatabaseHelper.COLUMN_TAG_ID} = mt.${MovieDatabaseHelper.COLUMN_MT_TAG_ID} WHERE t.${MovieDatabaseHelper.COLUMN_TAG_NAME} IN ('Favorites', 'Collection')", null)
            val listCursor = listDb.readableDatabase.rawQuery("SELECT ${ListDatabaseHelper.COLUMN_MOVIE_ID} FROM ${ListDatabaseHelper.TABLE_LIST_DATA}", null)

            parseAndApply(cursor, tagCursor, listCursor)

            cursor?.close()
            tagCursor?.close()
            listCursor?.close()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    // Extracted for testing without needing the context DB helper inside
    fun parseAndApply(cursor: Cursor?, tagCursor: Cursor?, listCursor: Cursor?) {
        val newFavorites = mutableSetOf<Int>()
        val newCollections = mutableSetOf<Int>()
        val newWatched = mutableSetOf<Int>()
        val newWillWatch = mutableSetOf<Int>()
        val newLists = mutableSetOf<Int>()
        val newRatings = mutableMapOf<Int, Int>()

        if (cursor != null && cursor.moveToFirst()) {
            do {
                val id = cursor.getInt(0)
                val category = cursor.getInt(1)
                val rating = cursor.getDouble(2)

                if (category == MovieDatabaseHelper.CATEGORY_WATCHED) {
                    newWatched.add(id)
                } else if (category == MovieDatabaseHelper.CATEGORY_PLAN_TO_WATCH) {
                    newWillWatch.add(id)
                }

                if (rating > 0) {
                    newRatings[id] = Math.ceil(rating).toInt()
                }
            } while (cursor.moveToNext())
        }

        if (tagCursor != null && tagCursor.moveToFirst()) {
            do {
                val tagName = tagCursor.getString(0)
                val movieId = tagCursor.getInt(1)
                if (tagName == "Favorites") newFavorites.add(movieId)
                if (tagName == "Collection") newCollections.add(movieId)
            } while (tagCursor.moveToNext())
        }

        if (listCursor != null && listCursor.moveToFirst()) {
            do {
                newLists.add(listCursor.getInt(0))
            } while (listCursor.moveToNext())
        }

        // Apply
        favorites.clear()
        favorites.addAll(newFavorites)
        collections.clear()
        collections.addAll(newCollections)
        watched.clear()
        watched.addAll(newWatched)
        willWatch.clear()
        willWatch.addAll(newWillWatch)
        lists.clear()
        lists.addAll(newLists)
        ratings.clear()
        ratings.putAll(newRatings)
    }

    fun isWatched(id: Int): Boolean = watched.contains(id)
    fun isWillWatch(id: Int): Boolean = willWatch.contains(id)
    fun isFavorite(id: Int): Boolean = favorites.contains(id)
    fun isInCollection(id: Int): Boolean = collections.contains(id)
    fun isInList(id: Int): Boolean = lists.contains(id)
    fun getRating(id: Int): Int? = ratings[id]

    companion object {
        @Volatile
        private var instance: PosterStatusCache? = null

        fun getInstance(context: Context): PosterStatusCache {
            return instance ?: synchronized(this) {
                instance ?: PosterStatusCache(context.applicationContext).also { instance = it }
            }
        }

        // For testing purposes
        fun createTestInstance(context: Context): PosterStatusCache {
            return PosterStatusCache(context)
        }
    }
}
