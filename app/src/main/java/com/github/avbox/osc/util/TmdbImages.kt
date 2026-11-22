package com.github.avbox.osc.util

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class TmdbImageQuality {
    Standard, DataSaver, High;

    companion object {
        fun of(value: Int): TmdbImageQuality = entries.getOrElse(value) { Standard }
    }
}

object TmdbImages {

    private const val POSTER_SMALL = "w342"
    private const val POSTER_LARGE = "w780"
    private const val ORIGINAL = "original"
    private const val BACKDROP_MEDIUM = "w780"
    private const val BACKDROP_WIDE = "w1280"
    private const val PROFILE_SMALL = "w185"
    private const val PROFILE_LARGE = "w500"
    private const val STILL_SMALL = "w300"
    private const val STILL_LARGE = "w780"
    private const val LOGO_MEDIUM = "w500"

    private val configEpochFlow = MutableStateFlow(0)

    val configEpoch: StateFlow<Int> = configEpochFlow.asStateFlow()

    fun notifyConfigChanged() {
        configEpochFlow.value = configEpochFlow.value + 1
    }

    fun isActive(): Boolean = try {
        KV.get(KVKeys.TMDB_ENABLE, false) && KV.get(KVKeys.TMDB_API_KEY, "").isNotBlank()
    } catch (e: Exception) {
        AppLog.e("TmdbImages", e)
        false
    }

    fun isLogoEnabled(): Boolean = try {
        KV.get(KVKeys.TMDB_LOGO_ENABLED, true)
    } catch (e: Exception) {
        AppLog.e("TmdbImages", e)
        true
    }

    fun imageQuality(): TmdbImageQuality = try {
        TmdbImageQuality.of(KV.get(KVKeys.TMDB_IMAGE_QUALITY, 0))
    } catch (e: Exception) {
        AppLog.e("TmdbImages", e)
        TmdbImageQuality.Standard
    }

    private fun posterSize(large: Boolean): String = when (imageQuality()) {
        TmdbImageQuality.DataSaver -> POSTER_SMALL
        TmdbImageQuality.Standard -> if (large) POSTER_LARGE else POSTER_SMALL
        TmdbImageQuality.High -> ORIGINAL
    }

    private fun backdropSize(): String = when (imageQuality()) {
        TmdbImageQuality.DataSaver -> BACKDROP_MEDIUM
        TmdbImageQuality.Standard -> BACKDROP_WIDE
        TmdbImageQuality.High -> ORIGINAL
    }

    private fun profileSize(): String = when (imageQuality()) {
        TmdbImageQuality.High -> PROFILE_LARGE
        else -> PROFILE_SMALL
    }

    private fun stillSize(): String = when (imageQuality()) {
        TmdbImageQuality.DataSaver -> STILL_SMALL
        TmdbImageQuality.Standard -> STILL_SMALL
        TmdbImageQuality.High -> STILL_LARGE
    }

    private fun logoSize(): String = when (imageQuality()) {
        TmdbImageQuality.DataSaver -> LOGO_MEDIUM
        else -> ORIGINAL
    }

    fun imageBaseUrl(configured: String): String =
        configured.trim().trimEnd('/').ifEmpty { KVKeys.TMDB_IMAGE_BASE_DEFAULT }

    fun imageUrl(configuredImageBase: String, posterPath: String, large: Boolean): String =
        imageBaseUrl(configuredImageBase) + "/" + posterSize(large) + posterPath

    fun fullPosterUrl(configuredImageBase: String, posterPath: String): String =
        imageBaseUrl(configuredImageBase) + "/" + ORIGINAL + posterPath

    fun backdropUrl(configuredImageBase: String, backdropPath: String): String =
        imageBaseUrl(configuredImageBase) + "/" + backdropSize() + backdropPath

    fun profileUrl(configuredImageBase: String, profilePath: String): String =
        imageBaseUrl(configuredImageBase) + "/" + profileSize() + profilePath

    fun stillUrl(configuredImageBase: String, stillPath: String): String =
        imageBaseUrl(configuredImageBase) + "/" + stillSize() + stillPath

    fun logoUrl(configuredImageBase: String, logoPath: String): String =
        imageBaseUrl(configuredImageBase) + "/" + logoSize() + logoPath

    fun imageUrl(posterPath: String, large: Boolean): String =
        imageUrl(readImageBase(), posterPath, large)

    fun fullPosterUrl(posterPath: String): String =
        fullPosterUrl(readImageBase(), posterPath)

    fun backdropUrl(backdropPath: String): String =
        backdropUrl(readImageBase(), backdropPath)

    fun profileUrl(profilePath: String): String =
        profileUrl(readImageBase(), profilePath)

    fun stillUrl(stillPath: String): String =
        stillUrl(readImageBase(), stillPath)

    fun logoUrl(logoPath: String): String =
        logoUrl(readImageBase(), logoPath)

    private fun readImageBase(): String = try {
        KV.get(KVKeys.TMDB_IMAGE_BASE, "")
    } catch (e: Exception) {
        AppLog.e("TmdbImages", e)
        ""
    }
}
