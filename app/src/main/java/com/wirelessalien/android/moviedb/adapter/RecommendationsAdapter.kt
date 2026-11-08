package com.wirelessalien.android.moviedb.adapter

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.database.sqlite.SQLiteDatabase
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.recyclerview.widget.RecyclerView
import com.squareup.picasso.Picasso
import com.google.android.material.button.MaterialButton
import com.wirelessalien.android.moviedb.R
import com.wirelessalien.android.moviedb.activity.DetailActivity
import com.wirelessalien.android.moviedb.helper.MovieDatabaseHelper
import com.wirelessalien.android.moviedb.recommendations.RecommendationResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class RecommendationsAdapter(
    private val isMovie: Boolean,
    private val context: Context,
    private val dbHelper: MovieDatabaseHelper
) : RecyclerView.Adapter<RecommendationsAdapter.ViewHolder>() {

    private var items: MutableList<RecommendationResult> = mutableListOf()

    fun submitList(newItems: List<RecommendationResult>) {
        items.clear()
        items.addAll(newItems)
        notifyDataSetChanged()
    }

    inner class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val posterImage: ImageView = view.findViewById(R.id.poster_image)
        val titleText: TextView = view.findViewById(R.id.title_text)
        val yearScoreText: TextView = view.findViewById(R.id.year_score_text)
        val seedText: TextView = view.findViewById(R.id.seed_text)
        val btnWatchlist: MaterialButton = view.findViewById(R.id.btn_add_watchlist)
        val btnNotInterested: MaterialButton = view.findViewById(R.id.btn_not_interested)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_recommendation, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val item = items[position]

        holder.titleText.text = item.title
        val scoreStr = if (item.tmdbScore > 0) String.format("%.1f", item.tmdbScore) else "N/A"
        holder.yearScoreText.text = "${item.releaseYear} • \u2605 $scoreStr"
        holder.seedText.text = context.getString(R.string.recommendations_because_you_liked, item.seedTitle)

        if (!item.posterPath.isNullOrEmpty()) {
            Picasso.get()
                .load("https://image.tmdb.org/t/p/w342${item.posterPath}")
                .into(holder.posterImage)
        } else {
            holder.posterImage.setImageResource(R.drawable.ic_broken_image)
        }

        holder.itemView.setOnClickListener {
            val intent = Intent(context, DetailActivity::class.java).apply {
                putExtra("id", item.tmdbId.toString())
                putExtra("movie", if (isMovie) 1 else 0)
                putExtra("isUpdate", false)
            }
            context.startActivity(intent)
        }

        holder.btnWatchlist.setOnClickListener {
            CoroutineScope(Dispatchers.IO).launch {
                val db = dbHelper.writableDatabase

                // First check if it already exists
                val cursor = db.query(MovieDatabaseHelper.TABLE_MOVIES, arrayOf(MovieDatabaseHelper.COLUMN_ID),
                    "${MovieDatabaseHelper.COLUMN_MOVIES_ID} = ? AND ${MovieDatabaseHelper.COLUMN_MOVIE} = ?",
                    arrayOf(item.tmdbId.toString(), if(isMovie) "1" else "0"), null, null, null)
                val exists = cursor.moveToFirst()
                cursor.close()

                var success = false
                if (!exists) {
                    val values = ContentValues().apply {
                        put(MovieDatabaseHelper.COLUMN_MOVIES_ID, item.tmdbId)
                        put(MovieDatabaseHelper.COLUMN_RATING, 0.0)
                        put(MovieDatabaseHelper.COLUMN_IMAGE, "")
                        put(MovieDatabaseHelper.COLUMN_ICON, "")
                        put(MovieDatabaseHelper.COLUMN_TITLE, item.title)
                        put(MovieDatabaseHelper.COLUMN_SUMMARY, "")
                        put(MovieDatabaseHelper.COLUMN_GENRES, "")
                        put(MovieDatabaseHelper.COLUMN_GENRES_IDS, "")
                        put(MovieDatabaseHelper.COLUMN_CATEGORIES, MovieDatabaseHelper.CATEGORY_PLAN_TO_WATCH)
                        put(MovieDatabaseHelper.COLUMN_MOVIE, if (isMovie) 1 else 0)
                    }
                    val result = db.insertWithOnConflict(MovieDatabaseHelper.TABLE_MOVIES, null, values, SQLiteDatabase.CONFLICT_IGNORE)
                    success = result != -1L
                }

                CoroutineScope(Dispatchers.Main).launch {
                    if (success) {
                        Toast.makeText(context, "Added to watchlist", Toast.LENGTH_SHORT).show()
                        removeItem(holder.adapterPosition)
                    } else if (exists) {
                        Toast.makeText(context, "Already in database", Toast.LENGTH_SHORT).show()
                        removeItem(holder.adapterPosition)
                    } else {
                        Toast.makeText(context, "Failed to add", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }

        holder.btnNotInterested.setOnClickListener {
            CoroutineScope(Dispatchers.IO).launch {
                val typeStr = if (isMovie) "movie" else "tv"
                dbHelper.insertNotInterested(item.tmdbId, typeStr)
                CoroutineScope(Dispatchers.Main).launch {
                    removeItem(holder.adapterPosition)
                }
            }
        }
    }

    private fun removeItem(position: Int) {
        if (position >= 0 && position < items.size) {
            items.removeAt(position)
            notifyItemRemoved(position)
            notifyItemRangeChanged(position, items.size)
        }
    }

    override fun getItemCount(): Int = items.size
}
