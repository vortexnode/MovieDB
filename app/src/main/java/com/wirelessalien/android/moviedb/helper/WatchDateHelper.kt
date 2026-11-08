package com.wirelessalien.android.moviedb.helper

import android.content.Context
import android.icu.text.SimpleDateFormat
import android.icu.util.Calendar
import android.icu.util.TimeZone
import android.view.LayoutInflater
import android.view.View
import android.widget.TextView
import androidx.preference.PreferenceManager
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.datepicker.MaterialDatePicker
import com.google.android.material.switchmaterial.SwitchMaterial
import com.wirelessalien.android.moviedb.R
import java.util.Locale

class WatchDateHelper {
    sealed class WatchDateResult {
        data class DateSelected(val date: String) : WatchDateResult()
        object UnknownDate : WatchDateResult()
        object StartWatching : WatchDateResult()
        object Cancelled : WatchDateResult()
    }

    companion object {
        fun getDefaultDate(
            askForWatchDate: Boolean,
            defaultType: String?,
            releaseDate: String?,
            currentDate: String
        ): String? {
            if (!askForWatchDate) {
                if (defaultType == "release_date" && !releaseDate.isNullOrEmpty()) {
                    return releaseDate
                } else {
                    return currentDate
                }
            }
            return null
        }

        fun showWatchDateDialog(
            context: Context,
            titleSubtitle: String,
            releaseDate: String?,
            runtime: Int,
            callback: (WatchDateResult) -> Unit
        ) {
            val prefs = PreferenceManager.getDefaultSharedPreferences(context)
            val askForWatchDate = prefs.getBoolean("ask_for_watch_date", true)
            val defaultWatchDate = prefs.getString("default_watch_date", "current_date")
            val currentDate = getCurrentDate()

            val fastPathDate = getDefaultDate(askForWatchDate, defaultWatchDate, releaseDate, currentDate)
            if (fastPathDate != null) {
                callback(WatchDateResult.DateSelected(fastPathDate))
                return
            }

            val dialog = BottomSheetDialog(context)
            val view = LayoutInflater.from(context).inflate(R.layout.dialog_choose_watch_date, null)
            dialog.setContentView(view)

            val tvSubtitle = view.findViewById<TextView>(R.id.tv_subtitle)
            tvSubtitle.text = titleSubtitle

            val rowRightNow = view.findViewById<View>(R.id.row_right_now)
            val tvRightNowSubtitle = view.findViewById<TextView>(R.id.tv_right_now_subtitle)

            val currentDateTimeFull = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(java.util.Date())
            tvRightNowSubtitle.text = currentDateTimeFull

            rowRightNow.setOnClickListener {
                if (view.findViewById<SwitchMaterial>(R.id.switch_dont_ask_again).isChecked) {
                    saveDontAskAgain(context, "current_date")
                }
                callback(WatchDateResult.DateSelected(currentDate))
                dialog.dismiss()
            }

            val rowReleaseDate = view.findViewById<View>(R.id.row_release_date)
            val tvReleaseDateSubtitle = view.findViewById<TextView>(R.id.tv_release_date_subtitle)
            if (releaseDate.isNullOrEmpty()) {
                rowReleaseDate.visibility = View.GONE
            } else {
                tvReleaseDateSubtitle.text = releaseDate
                rowReleaseDate.setOnClickListener {
                    if (view.findViewById<SwitchMaterial>(R.id.switch_dont_ask_again).isChecked) {
                        saveDontAskAgain(context, "release_date")
                    }
                    callback(WatchDateResult.DateSelected(releaseDate))
                    dialog.dismiss()
                }
            }

            val rowAnotherDate = view.findViewById<View>(R.id.row_another_date)
            rowAnotherDate.setOnClickListener {
                if (view.findViewById<SwitchMaterial>(R.id.switch_dont_ask_again).isChecked) {
                    saveDontAskAgain(context, "current_date")
                }
                showDatePicker(context, dialog, callback)
            }

            val rowUnknownDate = view.findViewById<View>(R.id.row_unknown_date)
            rowUnknownDate.setOnClickListener {
                if (view.findViewById<SwitchMaterial>(R.id.switch_dont_ask_again).isChecked) {
                    saveDontAskAgain(context, "current_date")
                }
                callback(WatchDateResult.UnknownDate)
                dialog.dismiss()
            }

            val rowStartWatching = view.findViewById<View>(R.id.row_start_watching)
            val tvStartWatchingSubtitle = view.findViewById<TextView>(R.id.tv_start_watching_subtitle)
            if (runtime > 0) {
                tvStartWatchingSubtitle.text = context.getString(R.string.runtime_minutes, runtime)
            } else {
                tvStartWatchingSubtitle.visibility = View.GONE
            }

            rowStartWatching.setOnClickListener {
                if (view.findViewById<SwitchMaterial>(R.id.switch_dont_ask_again).isChecked) {
                    saveDontAskAgain(context, "current_date")
                }
                callback(WatchDateResult.StartWatching)
                dialog.dismiss()
            }

            val btnCancel = view.findViewById<View>(R.id.btn_cancel)
            btnCancel.setOnClickListener {
                callback(WatchDateResult.Cancelled)
                dialog.dismiss()
            }

            dialog.setOnCancelListener {
                callback(WatchDateResult.Cancelled)
            }

            dialog.show()
        }

        private fun showDatePicker(
            context: Context,
            bottomSheetDialog: BottomSheetDialog,
            callback: (WatchDateResult) -> Unit
        ) {
            val builder = MaterialDatePicker.Builder.datePicker()
            builder.setTitleText(context.getString(R.string.select_date))
            val datePicker = builder.build()

            datePicker.addOnPositiveButtonClickListener { selection ->
                val sdf = SimpleDateFormat("yyyy-MM-dd", Locale.ENGLISH)
                sdf.timeZone = TimeZone.getTimeZone("UTC")
                val formattedDate = sdf.format(java.util.Date(selection))
                callback(WatchDateResult.DateSelected(formattedDate))
                bottomSheetDialog.dismiss()
            }

            // Using context to show MaterialDatePicker if it's a FragmentActivity
            if (context is androidx.appcompat.app.AppCompatActivity) {
                datePicker.show(context.supportFragmentManager, "watch_date_picker")
            } else if (context is androidx.fragment.app.FragmentActivity) {
                datePicker.show(context.supportFragmentManager, "watch_date_picker")
            }
        }

        fun getCurrentDate(): String {
            return SimpleDateFormat("yyyy-MM-dd", Locale.ENGLISH).format(java.util.Date())
        }

        private fun saveDontAskAgain(context: Context, defaultType: String) {
            val prefs = PreferenceManager.getDefaultSharedPreferences(context)
            prefs.edit()
                .putBoolean("ask_for_watch_date", false)
                .putString("default_watch_date", defaultType)
                .apply()
        }
    }
}
