package com.wirelessalien.android.moviedb.fragment

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.squareup.picasso.Picasso
import com.wirelessalien.android.moviedb.R
import com.wirelessalien.android.moviedb.activity.DetailActivity
import com.wirelessalien.android.moviedb.helper.CalendarDataHelper
import com.wirelessalien.android.moviedb.helper.CalendarItem
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

class SeriesSoonFragment : Fragment() {

    private lateinit var rvSoon: RecyclerView
    private lateinit var progressBar: View

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        val view = inflater.inflate(R.layout.fragment_series_soon, container, false)
        rvSoon = view.findViewById(R.id.rvSoon)
        progressBar = view.findViewById(R.id.progressBar)

        rvSoon.layoutManager = LinearLayoutManager(requireContext())

        loadData()

        return view
    }

    private fun loadData() {
        progressBar.visibility = View.VISIBLE
        lifecycleScope.launch {
            val helper = CalendarDataHelper(requireContext())
            val allItems = helper.fetchData(false)

            // Filter only TV shows (you can distinguish by 'type' if calendar data helper adds it,
            // or assume we want everything returning from calendar that isn't a movie).
            val tvItems = allItems.filter { it.type == "tv" || it.type == "episode" } // typically CalendarDataHelper uses "episode" or "movie"

            val sortedItems = tvItems.sortedBy { it.releaseDate }

            val adapter = SeriesSoonAdapter(sortedItems) { item ->
                val intent = Intent(requireContext(), DetailActivity::class.java).apply {
                    putExtra("movieObject", item.toJSONObject().toString())
                    putExtra("isMovie", false)
                }
                startActivity(intent)
            }
            rvSoon.adapter = adapter
            progressBar.visibility = View.GONE
        }
    }
}

class SeriesSoonAdapter(private val items: List<CalendarItem>, private val onItemClick: (CalendarItem) -> Unit) : RecyclerView.Adapter<SeriesSoonAdapter.ViewHolder>() {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_series_soon, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val item = items[position]
        val context = holder.itemView.context

        // Header Logic (group by day)
        val showHeader = if (position == 0) {
            true
        } else {
            items[position - 1].releaseDate != item.releaseDate
        }

        if (showHeader) {
            holder.tvDayHeader.visibility = View.VISIBLE
            holder.tvDayHeader.text = formatDayHeader(item.releaseDate)
        } else {
            holder.tvDayHeader.visibility = View.GONE
        }

        holder.tvTitle.text = item.title

        // "S03 | E1 · Episode 1" format if available, otherwise just use subtitle
        if (item.season > 0 && item.episode > 0) {
            holder.tvEpisodeInfo.text = String.format(Locale.getDefault(), "S%02d | E%d · %s", item.season, item.episode, item.subtitle)
        } else {
            holder.tvEpisodeInfo.text = item.subtitle
        }

        val posterUrl = if (item.posterPath.isNotEmpty()) "https://image.tmdb.org/t/p/w500${item.posterPath}" else null
        if (posterUrl != null) {
            Picasso.get().load(posterUrl).placeholder(R.drawable.ic_broken_image).into(holder.ivPoster)
        } else {
            holder.ivPoster.setImageResource(R.drawable.ic_broken_image)
        }

        // Time logic: CalendarItem currently doesn't store time, assume empty for now unless extracted from subtitle
        holder.tvTime.visibility = View.GONE

        holder.tvCountdown.text = getCountdownText(context, item.releaseDate)

        holder.itemView.setOnClickListener { onItemClick(item) }
    }

    override fun getItemCount() = items.size

    private fun formatDayHeader(dateStr: String): String {
        try {
            val sdf = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
            val date = sdf.parse(dateStr)
            if (date != null) {
                val outSdf = SimpleDateFormat("EEE, dd MMM", Locale.getDefault())
                return outSdf.format(date)
            }
        } catch (e: Exception) {}
        return dateStr
    }

    private fun getCountdownText(context: android.content.Context, dateStr: String): String {
        try {
            val sdf = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
            val targetDate = sdf.parse(dateStr)

            val today = Calendar.getInstance().apply {
                set(Calendar.HOUR_OF_DAY, 0)
                set(Calendar.MINUTE, 0)
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
            }.time

            val targetCal = Calendar.getInstance().apply { time = targetDate }
            targetCal.set(Calendar.HOUR_OF_DAY, 0)
            targetCal.set(Calendar.MINUTE, 0)
            targetCal.set(Calendar.SECOND, 0)
            targetCal.set(Calendar.MILLISECOND, 0)

            val diffMillis = targetCal.timeInMillis - today.time
            val diffDays = (diffMillis / (1000 * 60 * 60 * 24)).toInt()

            return when {
                diffDays == 0 -> context.getString(R.string.series_days_today)
                diffDays == 1 -> context.getString(R.string.series_days_tomorrow)
                diffDays > 1 -> context.resources.getQuantityString(R.plurals.series_days_count, diffDays, diffDays)
                else -> dateStr
            }
        } catch (e: Exception) {}
        return dateStr
    }

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val tvDayHeader: TextView = view.findViewById(R.id.tvDayHeader)
        val ivPoster: ImageView = view.findViewById(R.id.ivPoster)
        val tvTitle: TextView = view.findViewById(R.id.tvTitle)
        val tvEpisodeInfo: TextView = view.findViewById(R.id.tvEpisodeInfo)
        val tvTime: TextView = view.findViewById(R.id.tvTime)
        val tvCountdown: TextView = view.findViewById(R.id.tvCountdown)
    }
}
