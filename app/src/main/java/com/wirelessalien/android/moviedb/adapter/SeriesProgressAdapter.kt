package com.wirelessalien.android.moviedb.adapter

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.progressindicator.LinearProgressIndicator
import com.squareup.picasso.Picasso
import com.wirelessalien.android.moviedb.R
import com.wirelessalien.android.moviedb.repository.ShowProgressItem
import java.text.SimpleDateFormat
import java.util.Locale

class SeriesProgressAdapter(
    private var items: List<ShowProgressItem>,
    private val isGrid: Boolean,
    private val onMarkWatchedClick: (ShowProgressItem) -> Unit,
    private val onItemClick: (ShowProgressItem) -> Unit
) : RecyclerView.ViewHolder(View(null)) { // Hack to allow class to be used as base but we don't use it directly

    // The real class is below
}

class SeriesProgressRecyclerAdapter(
    private var items: List<ShowProgressItem>,
    private var isGrid: Boolean,
    private val onMarkWatchedClick: (ShowProgressItem) -> Unit,
    private val onItemClick: (ShowProgressItem) -> Unit
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    fun updateItems(newItems: List<ShowProgressItem>) {
        items = newItems
        notifyDataSetChanged()
    }

    fun setGridMode(grid: Boolean) {
        if (isGrid != grid) {
            isGrid = grid
            notifyDataSetChanged()
        }
    }

    override fun getItemViewType(position: Int): Int {
        return if (isGrid) 1 else 0
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val layout = if (viewType == 1) R.layout.item_series_progress_grid else R.layout.item_series_progress_list
        val view = LayoutInflater.from(parent.context).inflate(layout, parent, false)
        return if (viewType == 1) GridViewHolder(view) else ListViewHolder(view)
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        val item = items[position]
        val context = holder.itemView.context

        if (holder is ListViewHolder) {
            holder.tvTitle.text = item.title

            val posterUrl = if (item.posterPath.isNotEmpty()) "https://image.tmdb.org/t/p/w500${item.posterPath}" else null
            if (posterUrl != null) {
                Picasso.get().load(posterUrl).placeholder(R.drawable.ic_broken_image).into(holder.ivPoster)
            } else {
                holder.ivPoster.setImageResource(R.drawable.ic_broken_image)
            }

            if (item.nextEpisodeSeason != null && item.nextEpisodeNumber != null) {
                if (item.nextEpisodeTitle != null) {
                    holder.tvEpisodeInfo.text = context.getString(R.string.series_episode_info_format, item.nextEpisodeSeason, item.nextEpisodeNumber, item.nextEpisodeTitle)
                } else {
                    holder.tvEpisodeInfo.text = context.getString(R.string.series_episode_info_format_no_title, item.nextEpisodeSeason, item.nextEpisodeNumber)
                }
                holder.tvEpisodeInfo.visibility = View.VISIBLE
            } else {
                holder.tvEpisodeInfo.visibility = View.GONE
            }

            if (item.nextEpisodeAirDate != null) {
                try {
                    val inputFormat = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
                    val outputFormat = SimpleDateFormat("EEE, dd MMM", Locale.getDefault())
                    val date = inputFormat.parse(item.nextEpisodeAirDate)
                    if (date != null) {
                        holder.tvCaption.text = context.getString(R.string.series_episode_info_date_format, outputFormat.format(date), item.nextEpisodeSeason ?: 0, item.nextEpisodeNumber ?: 0)
                        holder.tvCaption.visibility = View.VISIBLE
                    } else {
                        holder.tvCaption.visibility = View.GONE
                    }
                } catch (e: Exception) {
                    holder.tvCaption.visibility = View.GONE
                }
            } else {
                holder.tvCaption.visibility = View.GONE
            }

            holder.tvProgressFraction.text = context.getString(R.string.series_progress_format, item.watchedEpisodes, item.totalEpisodes)
            val percent = if (item.totalEpisodes > 0) (item.watchedEpisodes * 100) / item.totalEpisodes else 0
            holder.tvProgressPercent.text = context.getString(R.string.series_progress_percent, percent)
            holder.progressIndicator.max = item.totalEpisodes
            holder.progressIndicator.progress = item.watchedEpisodes

            holder.btnMarkWatched.setOnClickListener { onMarkWatchedClick(item) }
            holder.itemView.setOnClickListener { onItemClick(item) }

        } else if (holder is GridViewHolder) {
            val posterUrl = if (item.posterPath.isNotEmpty()) "https://image.tmdb.org/t/p/w500${item.posterPath}" else null
            if (posterUrl != null) {
                Picasso.get().load(posterUrl).placeholder(R.drawable.ic_broken_image).into(holder.ivPoster)
            } else {
                holder.ivPoster.setImageResource(R.drawable.ic_broken_image)
            }

            holder.progressIndicator.max = item.totalEpisodes
            holder.progressIndicator.progress = item.watchedEpisodes
            holder.itemView.setOnClickListener { onItemClick(item) }
        }
    }

    override fun getItemCount() = items.size

    class ListViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val ivPoster: ImageView = view.findViewById(R.id.ivPoster)
        val tvCaption: TextView = view.findViewById(R.id.tvCaption)
        val tvTitle: TextView = view.findViewById(R.id.tvTitle)
        val tvEpisodeInfo: TextView = view.findViewById(R.id.tvEpisodeInfo)
        val tvProgressFraction: TextView = view.findViewById(R.id.tvProgressFraction)
        val tvProgressPercent: TextView = view.findViewById(R.id.tvProgressPercent)
        val progressIndicator: LinearProgressIndicator = view.findViewById(R.id.progressIndicator)
        val btnMarkWatched: ImageButton = view.findViewById(R.id.btnMarkWatched)
    }

    class GridViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val ivPoster: ImageView = view.findViewById(R.id.ivPoster)
        val progressIndicator: LinearProgressIndicator = view.findViewById(R.id.progressIndicator)
    }
}
