package com.wirelessalien.android.moviedb.helper

import com.wirelessalien.android.moviedb.recommendations.RecommendationResult
import org.json.JSONObject

object RecommendationIntentHelper {
    fun createMovieObjectJson(result: RecommendationResult): JSONObject {
        return JSONObject().apply {
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
    }
}
