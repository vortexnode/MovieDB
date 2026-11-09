/*
 *     This file is part of "ShowCase" formerly Movie DB. <https://github.com/WirelessAlien/MovieDB>
 *     forked from <https://notabug.org/nvb/MovieDB>
 *
 *     Copyright (C) 2024  WirelessAlien <https://github.com/WirelessAlien>
 *
 *     ShowCase is free software: you can redistribute it and/or modify
 *     it under the terms of the GNU General Public License as published by
 *     the Free Software Foundation, either version 3 of the License, or
 *     (at your option) any later version.
 *
 *     ShowCase is distributed in the hope that it will be useful,
 *     but WITHOUT ANY WARRANTY; without even the implied warranty of
 *     MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *     GNU General Public License for more details.
 *
 *     You should have received a copy of the GNU General Public License
 *     along with "ShowCase".  If not, see <https://www.gnu.org/licenses/>.
 */

package com.wirelessalien.android.moviedb.helper

import android.app.Activity
import android.content.res.Configuration
import androidx.preference.PreferenceManager
import com.google.android.material.color.DynamicColors
import com.wirelessalien.android.moviedb.R

object ThemeHelper {

    const val AMOLED_THEME_PREFERENCE = "key_amoled_theme"
    const val APP_STYLE_PREFERENCE = "key_app_style"

    fun applyAppStyle(activity: Activity) {
        val preferences = PreferenceManager.getDefaultSharedPreferences(activity)
        var appStyle = preferences.getString(APP_STYLE_PREFERENCE, "moviebase_orange")

        if (appStyle == "showcase_orange") {
            appStyle = "moviebase_orange"
            preferences.edit().putString(APP_STYLE_PREFERENCE, appStyle).apply()
        }

        if (appStyle == "moviebase_orange") {
            activity.setTheme(R.style.ThemeOverlay_Moviebase_Orange)
        } else {
            val isDynamicColorsEnabled = preferences.getBoolean("dynamic_colors", false)
            if (isDynamicColorsEnabled) {
                DynamicColors.applyToActivityIfAvailable(activity)
            }
        }
    }

    fun applyAmoledTheme(activity: Activity) {
        val preferences = PreferenceManager.getDefaultSharedPreferences(activity)
        if (preferences.getBoolean(AMOLED_THEME_PREFERENCE, false) && (activity.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES)) {
            activity.setTheme(R.style.AppTheme_Amoled)
        }
    }
}
