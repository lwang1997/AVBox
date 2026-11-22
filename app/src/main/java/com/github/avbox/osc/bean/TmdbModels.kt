package com.github.avbox.osc.bean

const val TMDB_SOURCE_KEY = "tmdb"

data class TmdbSearchHit(
    val id: Int,
    val mediaType: String,
    val title: String,
    val originalTitle: String,
    val year: Int,
    val posterPath: String,
    val originalLanguage: String? = null,
)

data class TmdbCastMember(
    val id: Int,
    val name: String,
    val character: String,
    val profilePath: String,
)

data class TmdbDetail(
    val overview: String,
    val rating: Double,
    val genres: List<String>,
    val cast: List<TmdbCastMember>,
    val seasons: List<TmdbSeason>,
    /** TMDB status string, e.g. "Returning Series" / "Ended". Nullable for old cached JSON. */
    val status: String? = null,
    /** Total episode count reported by TMDB; 0 when unknown/movie. */
    val totalEpisodes: Int = 0,
    /** Most recently aired episode (TV only). Nullable for old cached JSON. */
    val lastEpisode: TmdbAirEpisode? = null,
    /** Next episode to air (TV only). Nullable for old cached JSON. */
    val nextEpisode: TmdbAirEpisode? = null,
)

/** Lightweight episode reference used for follow update info. */
data class TmdbAirEpisode(
    val season: Int = 0,
    val episode: Int = 0,
    val name: String = "",
    val airDate: String = "",
)

data class TmdbSeason(
    val seasonNumber: Int,
    val episodeCount: Int,
)

data class TmdbEpisode(
    val name: String,
    val stillPath: String,
)

data class TmdbEpisodeRef(
    val seasonNumber: Int,
    val episodeNumber: Int,
)

data class TmdbPerson(
    val name: String,
    val biography: String,
    val birthday: String,
    val placeOfBirth: String,
    val profilePath: String,
)
