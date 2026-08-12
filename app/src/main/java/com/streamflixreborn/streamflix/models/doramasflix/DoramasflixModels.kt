package com.streamflixreborn.streamflix.models.doramasflix

import com.google.gson.annotations.SerializedName

data class ApiResponse(
    val data: Data? = null,
    val errors: List<GraphQlError> = emptyList(),
)

data class GraphQlError(
    val message: String? = null,
)

data class Data(
    val paginationDorama: Pagination? = null,
    val paginationMovie: Pagination? = null,
    val searchDorama: List<Content>? = null,
    val searchMovie: List<Content>? = null,
    val detailDorama: Content? = null,
    val detailMovie: Content? = null,
    val listSeasons: List<Season>? = null,
    val listEpisodes: List<Episode>? = null,
    val listServers: List<ServerMetadata>? = null,
    val getMovieLinks: LinkContainer? = null,
    val getEpisodeLinks: LinkContainer? = null,
)

data class Pagination(
    val items: List<Content> = emptyList(),
)

data class Content(
    @SerializedName("_id")
    val id: String,
    val name: String,
    @SerializedName("name_es")
    val nameEs: String? = null,
    val slug: String,
    @SerializedName("poster_path")
    val posterPath: String? = null,
    val poster: String? = null,
    @SerializedName("backdrop_path")
    val backdropPath: String? = null,
    val backdrop: String? = null,
    val overview: String? = null,
    val rating: Double? = null,
    val trailer: String? = null,
    @SerializedName("release_date")
    val releaseDate: String? = null,
    val runtime: Int? = null,
    @SerializedName("first_air_date")
    val firstAirDate: String? = null,
    @SerializedName("episode_time")
    val episodeTime: Int? = null,
    val genres: List<Tag> = emptyList(),
)

data class Tag(
    val name: String? = null,
    val slug: String? = null,
)

data class Season(
    val name: String? = null,
    val poster: String? = null,
    @SerializedName("poster_path")
    val posterPath: String? = null,
    @SerializedName("season_number")
    val seasonNumber: Int,
)

data class Episode(
    @SerializedName("_id")
    val id: String,
    val name: String? = null,
    @SerializedName("name_es")
    val nameEs: String? = null,
    val slug: String,
    @SerializedName("episode_number")
    val episodeNumber: Int? = null,
    @SerializedName("still_path")
    val stillPath: String? = null,
    @SerializedName("still_image")
    val stillImage: String? = null,
    @SerializedName("serie_backdrop_path")
    val serieBackdropPath: String? = null,
    val backdrop: String? = null,
    val overview: String? = null,
    @SerializedName("air_date")
    val airDate: String? = null,
    @SerializedName("count_links")
    val countLinks: Int? = null,
)

data class ServerMetadata(
    val name: String? = null,
    @SerializedName("code_flix")
    val codeFlix: String? = null,
)

data class LinkContainer(
    @SerializedName("links_online")
    val linksOnline: List<OnlineLink> = emptyList(),
)

data class OnlineLink(
    val server: String? = null,
    val lang: String? = null,
    val link: String? = null,
    @SerializedName("is_recommended")
    val isRecommended: Boolean? = null,
)
