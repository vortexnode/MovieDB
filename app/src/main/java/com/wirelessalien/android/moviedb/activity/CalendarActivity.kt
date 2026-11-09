package com.wirelessalien.android.moviedb.activity

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.squareup.picasso.Picasso
import com.wirelessalien.android.moviedb.R
import com.wirelessalien.android.moviedb.databinding.ActivityCalendarBinding
import com.wirelessalien.android.moviedb.helper.CalendarDataHelper
import com.wirelessalien.android.moviedb.helper.CalendarItem
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

class CalendarActivity : BaseActivity() {

    private lateinit var binding: ActivityCalendarBinding
    private lateinit var dataHelper: CalendarDataHelper
    private var allData = listOf<CalendarItem>()
    private var filteredData = listOf<CalendarItem>()
    private var groupedData = mapOf<String, List<CalendarItem>>()

    private val sdfDate = SimpleDateFormat("yyyy-MM-dd", Locale.US)
    private var currentMonthCalendar = Calendar.getInstance()
    private var selectedDateStr = sdfDate.format(Date())

    private lateinit var monthGridAdapter: MonthGridAdapter
    private lateinit var agendaAdapter: AgendaAdapter
    private lateinit var wholeMonthAgendaAdapter: AgendaAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityCalendarBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        binding.toolbar.setNavigationOnClickListener { onBackPressedDispatcher.onBackPressed() }

        dataHelper = CalendarDataHelper(this)

        setupUI()
        loadData(false)
    }

    private fun setupUI() {
        // Toggle view
        binding.toggleGroup.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (isChecked) {
                if (checkedId == R.id.btnMonthView) {
                    binding.monthContainer.visibility = View.VISIBLE
                    binding.rvWholeMonthAgenda.visibility = View.GONE
                } else {
                    binding.monthContainer.visibility = View.GONE
                    binding.rvWholeMonthAgenda.visibility = View.VISIBLE
                    updateWholeMonthAgenda()
                }
            }
        }

        // Filters
        binding.chipGroupFilters.setOnCheckedStateChangeListener { group, checkedIds ->
            if (checkedIds.isNotEmpty()) {
                applyFilters(checkedIds[0])
            }
        }

        // Month controls
        binding.btnPrevMonth.setOnClickListener {
            currentMonthCalendar.add(Calendar.MONTH, -1)
            updateMonthView()
        }
        binding.btnNextMonth.setOnClickListener {
            currentMonthCalendar.add(Calendar.MONTH, 1)
            updateMonthView()
        }

        // RecyclerViews
        binding.rvMonthGrid.layoutManager = GridLayoutManager(this, 7)
        monthGridAdapter = MonthGridAdapter()
        binding.rvMonthGrid.adapter = monthGridAdapter

        binding.rvDayAgenda.layoutManager = LinearLayoutManager(this)
        agendaAdapter = AgendaAdapter()
        binding.rvDayAgenda.adapter = agendaAdapter

        binding.rvWholeMonthAgenda.layoutManager = LinearLayoutManager(this)
        wholeMonthAgendaAdapter = AgendaAdapter()
        binding.rvWholeMonthAgenda.adapter = wholeMonthAgendaAdapter
    }

    private fun loadData(forceRefresh: Boolean) {
        binding.progressBar.visibility = View.VISIBLE
        binding.monthContainer.visibility = View.GONE
        binding.rvWholeMonthAgenda.visibility = View.GONE
        binding.emptyView.visibility = View.GONE

        lifecycleScope.launch {
            allData = dataHelper.fetchData(forceRefresh)

            // Initial filter is ALL
            val checkedChipId = binding.chipGroupFilters.checkedChipId
            applyFilters(if (checkedChipId != View.NO_ID) checkedChipId else R.id.chipAll)

            binding.progressBar.visibility = View.GONE

            if (binding.toggleGroup.checkedButtonId == R.id.btnMonthView) {
                binding.monthContainer.visibility = View.VISIBLE
            } else {
                binding.rvWholeMonthAgenda.visibility = View.VISIBLE
            }
        }
    }

    private fun applyFilters(checkedId: Int) {
        filteredData = when (checkedId) {
            R.id.chipMovies -> allData.filter { it.type == "movie" }
            R.id.chipEpisodes -> allData.filter { it.type == "episode" }
            R.id.chipNewSeasons -> allData.filter { it.type == "season" }
            else -> allData
        }
        groupedData = dataHelper.groupItemsByDay(filteredData)
        updateMonthView()
        updateDayAgenda()
        if (binding.toggleGroup.checkedButtonId == R.id.btnAgendaView) {
            updateWholeMonthAgenda()
        }
    }

    private fun updateMonthView() {
        val monthFormat = SimpleDateFormat("MMMM yyyy", Locale.getDefault())
        binding.tvMonthYear.text = monthFormat.format(currentMonthCalendar.time)

        val daysInMonth = currentMonthCalendar.getActualMaximum(Calendar.DAY_OF_MONTH)
        val firstDayOfMonth = currentMonthCalendar.clone() as Calendar
        firstDayOfMonth.set(Calendar.DAY_OF_MONTH, 1)

        // In Android Calendar, Sunday is 1, Saturday is 7
        val dayOfWeek = firstDayOfMonth.get(Calendar.DAY_OF_WEEK)
        val emptyDaysBefore = dayOfWeek - 1

        val monthYearStr = SimpleDateFormat("yyyy-MM", Locale.US).format(currentMonthCalendar.time)

        val items = mutableListOf<MonthDayItem>()
        for (i in 0 until emptyDaysBefore) {
            items.add(MonthDayItem("", "", false, false, false))
        }

        val todayStr = sdfDate.format(Date())

        for (i in 1..daysInMonth) {
            val dateStr = String.format(Locale.US, "%s-%02d", monthYearStr, i)
            val hasReleases = groupedData.containsKey(dateStr)
            val isToday = dateStr == todayStr
            val isSelected = dateStr == selectedDateStr
            items.add(MonthDayItem(i.toString(), dateStr, hasReleases, isToday, isSelected))
        }

        // Fill the rest to complete the grid (optional, but good for consistent height)
        val remaining = 42 - items.size
        for (i in 0 until remaining) {
            items.add(MonthDayItem("", "", false, false, false))
        }

        monthGridAdapter.submitList(items)
    }

    private fun updateDayAgenda() {
        val dayItems = groupedData[selectedDateStr] ?: emptyList()
        agendaAdapter.submitList(dayItems)

        if (binding.toggleGroup.checkedButtonId == R.id.btnMonthView) {
            binding.emptyView.visibility = if (dayItems.isEmpty()) View.VISIBLE else View.GONE
            if (dayItems.isEmpty()) {
                binding.emptyView.text = getString(R.string.calendar_no_releases)
            }
        }
    }

    private fun updateWholeMonthAgenda() {
        val monthYearStr = SimpleDateFormat("yyyy-MM", Locale.US).format(currentMonthCalendar.time)
        val monthItems = filteredData.filter { dataHelper.getLocalDateString(it.releaseDate).startsWith(monthYearStr) }
        wholeMonthAgendaAdapter.submitList(monthItems)

        if (binding.toggleGroup.checkedButtonId == R.id.btnAgendaView) {
            binding.emptyView.visibility = if (monthItems.isEmpty()) View.VISIBLE else View.GONE
            if (monthItems.isEmpty()) {
                binding.emptyView.text = getString(R.string.calendar_no_releases)
            }
        }
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.menu_refresh, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == R.id.action_refresh) {
            loadData(true)
            return true
        }
        return super.onOptionsItemSelected(item)
    }

    // --- Adapters ---

    data class MonthDayItem(val day: String, val dateStr: String, val hasReleases: Boolean, val isToday: Boolean, val isSelected: Boolean)

    inner class MonthGridAdapter : RecyclerView.Adapter<MonthGridAdapter.ViewHolder>() {
        private var items = listOf<MonthDayItem>()

        fun submitList(newItems: List<MonthDayItem>) {
            items = newItems
            notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val view = LayoutInflater.from(parent.context).inflate(R.layout.item_calendar_day, parent, false)
            return ViewHolder(view)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val item = items[position]
            holder.tvDayNumber.text = item.day

            if (item.day.isEmpty()) {
                holder.itemView.setOnClickListener(null)
                holder.ivHasReleases.visibility = View.INVISIBLE
                holder.tvDayNumber.setBackgroundResource(0)
                holder.tvDayNumber.setTextColor(getColor(R.color.md_theme_onSurface))
                return
            }

            holder.ivHasReleases.visibility = if (item.hasReleases) View.VISIBLE else View.INVISIBLE

            if (item.isSelected) {
                holder.tvDayNumber.setBackgroundResource(R.drawable.circle_shape) // Make sure this drawable is a filled circle with primary color
                holder.tvDayNumber.setTextColor(getColor(R.color.md_theme_onPrimary))
            } else if (item.isToday) {
                holder.tvDayNumber.setBackgroundResource(R.drawable.circle_shape_outline) // Needed if we want today outlined. Let's just use text color
                holder.tvDayNumber.setTextColor(getColor(R.color.md_theme_primary))
            } else {
                holder.tvDayNumber.setBackgroundResource(0)
                holder.tvDayNumber.setTextColor(getColor(R.color.md_theme_onSurface))
            }

            holder.itemView.setOnClickListener {
                selectedDateStr = item.dateStr
                updateMonthView()
                updateDayAgenda()
            }
        }

        override fun getItemCount() = items.size

        inner class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            val tvDayNumber: TextView = view.findViewById(R.id.tvDayNumber)
            val ivHasReleases: ImageView = view.findViewById(R.id.ivHasReleases)
        }
    }

    inner class AgendaAdapter : RecyclerView.Adapter<AgendaAdapter.ViewHolder>() {
        private var items = listOf<CalendarItem>()

        fun submitList(newItems: List<CalendarItem>) {
            items = newItems
            notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val view = LayoutInflater.from(parent.context).inflate(R.layout.item_calendar_agenda, parent, false)
            return ViewHolder(view)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val item = items[position]
            holder.tvTitle.text = item.title
            holder.tvSubtitle.text = item.subtitle
            holder.tvDate.text = item.releaseDate

            val posterPath = item.posterPath
            if (posterPath.isNotEmpty()) {
                val imageUrl = if (posterPath.startsWith("http")) posterPath else "https://image.tmdb.org/t/p/w200$posterPath"
                Picasso.get().load(imageUrl).placeholder(R.drawable.ic_image).error(R.drawable.ic_broken_image).into(holder.ivPoster)
            } else {
                holder.ivPoster.setImageResource(R.drawable.ic_image)
            }

            holder.itemView.setOnClickListener {
                val intent = Intent(this@CalendarActivity, DetailActivity::class.java)
                val movieObject = JSONObject().apply {
                    put("id", item.tmdbId)
                    put("name", item.title)
                    put("poster_path", posterPath)
                }
                intent.putExtra("movieObject", movieObject.toString())
                intent.putExtra("isMovie", item.type == "movie")
                startActivity(intent)
            }
        }

        override fun getItemCount() = items.size

        inner class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            val ivPoster: ImageView = view.findViewById(R.id.ivPoster)
            val tvTitle: TextView = view.findViewById(R.id.tvTitle)
            val tvSubtitle: TextView = view.findViewById(R.id.tvSubtitle)
            val tvDate: TextView = view.findViewById(R.id.tvDate)
        }
    }
}
