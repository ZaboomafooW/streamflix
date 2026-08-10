package com.streamflixreborn.streamflix.models.doramasflix

import com.google.gson.annotations.SerializedName

data class ApiResponse(
    val data: Data? = null,
)

data class Data(
    val paginationDorama: Pagination? = null,
    val paginationMovie: Pagination? = null,
    val searchDorama: List<Show>? = null,
    val searchMovie: List<Show>? = null,
    val listSeasons: List<Season>? = null,
    val listEpisodes: List<Episode>? = null,
    val getMovieLinks: LinkContainer? = null,
    val getEpisodeLinks: LinkContainer? = null,
)

data class Pagination(
    val items: List<Show> = emptyList(),
    val pageInfo: PageInfo? = null,
    val hasNextPage: Boolean? = null,
)

data class Show(
    @SerializedName("_id")
    val id: String,
    val name: String,
    @SerializedName("name_es")
    val nameEs: String? = null,
    val slug: String,
    val overview: String? = null,
    @SerializedName("poster_path")
    val posterPath: String? = null,
    val poster: String? = null,
    @SerializedName("backdrop_path")
    val backdropPath: String? = null,
    val backdrop: String? = null,
    @SerializedName("isTVShow")
    val isTvShow: Boolean? = null,
    val genres: List<Genre> = emptyList(),
    @SerializedName("__typename")
    val typename: String,
)

data class Genre(
    val name: String? = null,
    val slug: String? = null,
)

data class PageInfo(
    val hasNextPage: Boolean? = false,
)

data class Season(
    @SerializedName("_id")
    val id: String? = null,
    val slug: String,
    @SerializedName("season_number")
    val seasonNumber: Int,
    @SerializedName("poster_path")
    val posterPath: String? = null,
    @SerializedName("serie_backdrop_path")
    val serieBackdropPath: String? = null,
    @SerializedName("serie_name")
    val serieName: String? = null,
    val trailer: String? = null,
    val backdrop: String? = null,
    val overview: String? = null,
    val name: String? = null,
)

data class Episode(
    @SerializedName("_id")
    val id: String,
    val name: String?,
    val slug: String,
    @SerializedName("serie_name")
    val serieName: String? = null,
    @SerializedName("serie_id")
    val serieId: String? = null,
    @SerializedName("episode_number")
    val episodeNumber: Int?,
    @SerializedName("season_number")
    val seasonNumber: Int?,
    @SerializedName("still_path")
    val stillPath: String? = null,
    val languages: List<String> = emptyList(),
    val backdrop: String? = null,
)

data class LinkContainer(
    @SerializedName("links_online")
    val linksOnline: List<OnlineLink> = emptyList(),
)

data class OnlineLink(
    @SerializedName("_id")
    val id: String? = null,
    val lang: String? = null,
    val link: String? = null,
    val page: String? = null,
    val server: String? = null,
    @SerializedName("is_recommended")
    val isRecommended: Boolean? = null,
    val subtitles: List<OnlineSubtitle> = emptyList(),
)

data class OnlineSubtitle(
    @SerializedName("language_code")
    val languageCode: String? = null,
    val type: String? = null,
)

// --- MODELOS PARA EL TOKEN DE FKPLAYER ---
data class TokenModel(
    val props: PropsToken? = null,
)

data class PropsToken(
    val pageProps: PagePropsToken? = null,
)

data class PagePropsToken(
    val token: String? = null,
)

data class VideoToken(
    val link: String? = null,
)
