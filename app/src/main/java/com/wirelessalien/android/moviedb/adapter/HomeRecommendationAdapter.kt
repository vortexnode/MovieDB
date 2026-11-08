package com.wirelessalien.android.moviedb.adapter

import android.content.Intent
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.content.res.ResourcesCompat
import androidx.preference.PreferenceManager
import androidx.recyclerview.widget.RecyclerView
import com.squareup.picasso.Picasso
import com.wirelessalien.android.moviedb.R
import com.wirelessalien.android.moviedb.activity.DetailActivity
import com.wirelessalien.android.moviedb.databinding.HomeCardRecommendationBinding
import com.wirelessalien.android.moviedb.recommendations.RecommendationResult
import org.json.JSONObject
import java.util.Locale

class HomeRecommendationAdapter(private val results: List<RecommendationResult>) :
    RecyclerView.Adapter<HomeRecommendationAdapter.ViewHolder>() {

    override fun getItemCount(): Int {
        return Math.min(results.size, 15)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = HomeCardRecommendationBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val result = results[position]
        val context = holder.binding.root.context

        val defaultSharedPreferences = PreferenceManager.getDefaultSharedPreferences(context)
        val loadHDImage = defaultSharedPreferences.getBoolean("key_hq_images", false)
        val imageSize = if (loadHDImage) "w780" else "w500"

        if (result.posterPath.isNullOrEmpty() || result.posterPath == "null") {
            holder.binding.image.setBackgroundColor(ResourcesCompat.getColor(context.resources, R.color.md_theme_outline, null))
        } else {
            Picasso.get().load("https://image.tmdb.org/t/p/$imageSize" + result.posterPath).into(holder.binding.image)
        }

        holder.binding.title.text = result.title
        holder.binding.scoreBadge.text = String.format(Locale.getDefault(), "%.1f", result.tmdbScore)

        holder.binding.root.setOnClickListener { view ->
            val json = JSONObject().apply {
                put("id", result.tmdbId)
                if (result.isMovie) {
                    put("title", result.title)
                    put("release_date", result.releaseDate)
                } else {
                    put("name", result.title)
                    put("first_air_date", result.releaseDate)
                }
                put("poster_path", result.posterPath)
                put("overview", result.overview)
                put("vote_average", result.tmdbScore)
            }
            val intent = Intent(view.context, DetailActivity::class.java)
            intent.putExtra("movieObject", json.toString())
            if (!result.isMovie) {
                intent.putExtra("isMovie", false)
            }
            view.context.startActivity(intent)
        }
    }

    class ViewHolder(val binding: HomeCardRecommendationBinding) : RecyclerView.ViewHolder(binding.root)
}