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

package com.wirelessalien.android.moviedb.activity

import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.CheckBox
import android.widget.Toast
import androidx.preference.PreferenceManager
import com.wirelessalien.android.moviedb.R
import com.wirelessalien.android.moviedb.fragment.LoginFragmentTkt
import com.wirelessalien.android.moviedb.service.TraktImportService

class TraktImportActivity : BaseActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_trakt_import)

        setSupportActionBar(findViewById(R.id.toolbar))
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = getString(R.string.trakt_import_title)

        val btnStartImport = findViewById<Button>(R.id.btn_start_import)

        btnStartImport.setOnClickListener {
            val prefs = PreferenceManager.getDefaultSharedPreferences(this)
            val token = prefs.getString("trakt_access_token", null)

            if (token.isNullOrEmpty()) {
                Toast.makeText(this, getString(R.string.trakt_login_required), Toast.LENGTH_LONG).show()
                val bottomSheetFragment = LoginFragmentTkt()
                bottomSheetFragment.show(supportFragmentManager, bottomSheetFragment.tag)
            } else {
                startImportService()
            }
        }
    }

    private fun startImportService() {
        val cbWatched = findViewById<CheckBox>(R.id.cb_watched)
        val cbRatings = findViewById<CheckBox>(R.id.cb_ratings)
        val cbWatchlist = findViewById<CheckBox>(R.id.cb_watchlist)
        val cbCollection = findViewById<CheckBox>(R.id.cb_collection)
        val cbFavorites = findViewById<CheckBox>(R.id.cb_favorites)
        val cbCustomLists = findViewById<CheckBox>(R.id.cb_custom_lists)

        val intent = Intent(this, TraktImportService::class.java).apply {
            action = TraktImportService.ACTION_START_TRAKT_IMPORT
            putExtra(TraktImportService.EXTRA_IMPORT_WATCHED, cbWatched.isChecked)
            putExtra(TraktImportService.EXTRA_IMPORT_RATINGS, cbRatings.isChecked)
            putExtra(TraktImportService.EXTRA_IMPORT_WATCHLIST, cbWatchlist.isChecked)
            putExtra(TraktImportService.EXTRA_IMPORT_COLLECTION, cbCollection.isChecked)
            putExtra(TraktImportService.EXTRA_IMPORT_FAVORITES, cbFavorites.isChecked)
            putExtra(TraktImportService.EXTRA_IMPORT_CUSTOM_LISTS, cbCustomLists.isChecked)
        }

        startService(intent)
        finish()
    }

    override fun onSupportNavigateUp(): Boolean {
        onBackPressedDispatcher.onBackPressed()
        return true
    }
}
