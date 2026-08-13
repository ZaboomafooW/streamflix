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
    val paginationDorama: ContentPage? = null,
    val paginationMovie: ContentPage? = null,
    val searchFullDoramas: ContentPage? = null,
    val searchFullMovies: ContentPage? = null,
    val detailDorama: Content? = null,
    val detailMovie: Content? = null,
    val detailSeason: Season? = null,
    val detailEpisode: Episode? = null,
    val carrouselDoramas: List<Content>? = null,
    val carrouselMovies: List<Content>? = null,
    val similarsDoramas: List<Content>? = null,
    val similarsMovies: List<Content>? = null,
    val listSeasons: List<Season>? = null,
    val listServers: List<ServerMetadata>? = null,
    val paginationEpisode: EpisodePage? = null,
    val getMovieLinks: LinkContainer? = null,
    val getEpisodeLinks: LinkContainer? = null,
)

data class ContentPage(
    val items: List<Content> = emptyList(),
    val count: Int? = null,
    val pageInfo: PageInfo? = null,
)

data class EpisodePage(
    val items: List<Episode> = emptyList(),
    val count: Int? = null,
    val pageInfo: PageInfo? = null,
)

data class PageInfo(
    val currentPage: Int? = null,
    val perPage: Int? = null,
    val pageCount: Int? = null,
    val itemCount: Int? = null,
    val hasNextPage: Boolean? = null,
    val hasPreviousPage: Boolean? = null,
)

data class Content(
    @SerializedName("_id")
    val id: String? = null,
    val name: String? = null,
    @SerializedName("name_es")
    val nameEs: String? = null,
    @SerializedName("original_name")
    val originalName: String? = null,
    val slug: String? = null,
    @SerializedName("tmdb_id")
    val tmdbId: String? = null,
    @SerializedName("poster_path")
    val posterPath: String? = null,
    val poster: String? = null,
    @SerializedName("backdrop_path")
    val backdropPath: String? = null,
    val backdrop: String? = null,
    val images: Images? = null,
    val overview: String? = null,
    val trailer: String? = null,
    val release: String? = null,
    @SerializedName("release_date")
    val releaseDate: String? = null,
    val runtime: Int? = null,
    @SerializedName("first_air_date")
    val firstAirDate: String? = null,
    @SerializedName("last_air_date")
    val lastAirDate: String? = null,
    @SerializedName("episode_time")
    val episodeTime: Int? = null,
    @SerializedName("isTVShow")
    val isTvShow: Boolean? = null,
    @SerializedName("isFinish")
    val isFinish: Boolean? = null,
    val premiere: Boolean? = null,
    @SerializedName("commingSoon")
    val comingSoon: Boolean? = null,
    val status: String? = null,
    @SerializedName("status_source")
    val statusSource: String? = null,
    @SerializedName("status_changed_at")
    val statusChangedAt: String? = null,
    val country: String? = null,
    @SerializedName("number_of_seasons")
    val numberOfSeasons: Int? = null,
    @SerializedName("number_of_episodes")
    val numberOfEpisodes: Int? = null,
    @SerializedName("number_of_episodes_online")
    val numberOfEpisodesOnline: Int? = null,
    @SerializedName("subtitles_available")
    val subtitlesAvailable: List<String>? = null,
    val rating: Double? = null,
    @SerializedName("rating_count")
    val ratingCount: Int? = null,
    @SerializedName("rating_total")
    val ratingTotal: Double? = null,
    @SerializedName("views_count")
    val viewsCount: Int? = null,
    @SerializedName("favs_count")
    val favsCount: Int? = null,
    @SerializedName("age_limit")
    val ageLimit: Int? = null,
    val genres: List<Tag>? = null,
    val labels: List<Tag>? = null,
    val networks: List<Tag>? = null,
    val cast: List<CastMember>? = null,
    @SerializedName("subbers_ref")
    val subbersRef: List<Subber>? = null,
    val langs: List<LanguageMetadata>? = null,
    val seasons: List<Season>? = null,
)

data class Images(
    val backdrops: List<String>? = null,
)

data class CastMember(
    val name: String? = null,
    val slug: String? = null,
    val character: String? = null,
    @SerializedName("profile_path")
    val profilePath: String? = null,
    val ref: String? = null,
)

data class Tag(
    val name: String? = null,
    val slug: String? = null,
    val ref: String? = null,
)

data class Subber(
    val nickname: String? = null,
)

data class LanguageMetadata(
    @SerializedName("_id")
    val id: String? = null,
    val name: String? = null,
    val slug: String? = null,
    val code: String? = null,
    @SerializedName("code_flix")
    val codeFlix: String? = null,
    val flag: String? = null,
    val images: List<LanguageImage>? = null,
)

data class LanguageImage(
    @SerializedName("image_tmdb")
    val imageTmdb: String? = null,
    @SerializedName("image_url")
    val imageUrl: String? = null,
    val page: String? = null,
)

data class Season(
    @SerializedName("_id")
    val id: String? = null,
    val ref: String? = null,
    val name: String? = null,
    @SerializedName("name_es")
    val nameEs: String? = null,
    val slug: String? = null,
    val poster: String? = null,
    @SerializedName("poster_path")
    val posterPath: String? = null,
    @SerializedName("serie_id")
    val serieId: String? = null,
    @SerializedName("season_number")
    val seasonNumber: Int? = null,
    @SerializedName("serie_backdrop_path")
    val serieBackdropPath: String? = null,
    val backdrop: String? = null,
    @SerializedName("number_of_episodes")
    val numberOfEpisodes: Int? = null,
    @SerializedName("number_of_episodes_online")
    val numberOfEpisodesOnline: Int? = null,
    val emision: Boolean? = null,
    @SerializedName("emision_days")
    val emisionDays: List<String>? = null,
    val uploading: Boolean? = null,
    val pause: Boolean? = null,
    @SerializedName("commingSoon")
    val comingSoon: Boolean? = null,
    val status: String? = null,
    @SerializedName("status_source")
    val statusSource: String? = null,
    @SerializedName("status_changed_at")
    val statusChangedAt: String? = null,
    @SerializedName("notShowDate")
    val notShowDate: Boolean? = null,
    @SerializedName("air_date")
    val airDate: String? = null,
    @SerializedName("date_string")
    val dateString: String? = null,
    val trailer: String? = null,
    val overview: String? = null,
    @SerializedName("serie_poster")
    val seriePoster: String? = null,
    @SerializedName("serie_poster_path")
    val seriePosterPath: String? = null,
    @SerializedName("subtitles_available")
    val subtitlesAvailable: List<String>? = null,
    val langs: List<LanguageMetadata>? = null,
    val subbers: List<Subber>? = null,
)

data class Episode(
    @SerializedName("_id")
    val id: String? = null,
    val name: String? = null,
    @SerializedName("name_es")
    val nameEs: String? = null,
    val slug: String? = null,
    @SerializedName("episode_number")
    val episodeNumber: Int? = null,
    @SerializedName("season_number")
    val seasonNumber: Int? = null,
    @SerializedName("date_string")
    val dateString: String? = null,
    @SerializedName("serie_id")
    val serieId: String? = null,
    @SerializedName("season_id")
    val seasonId: String? = null,
    @SerializedName("still_path")
    val stillPath: String? = null,
    @SerializedName("still_image")
    val stillImage: String? = null,
    @SerializedName("serie_backdrop_path")
    val serieBackdropPath: String? = null,
    val backdrop: String? = null,
    @SerializedName("notShowDate")
    val notShowDate: Boolean? = null,
    val note: String? = null,
    val overview: String? = null,
    val pause: Boolean? = null,
    val emision: Boolean? = null,
    @SerializedName("emision_days")
    val emisionDays: List<String>? = null,
    @SerializedName("commingSoon")
    val comingSoon: Boolean? = null,
    val status: String? = null,
    @SerializedName("status_source")
    val statusSource: String? = null,
    @SerializedName("count_links")
    val countLinks: Int? = null,
    @SerializedName("serie_name")
    val serieName: String? = null,
    @SerializedName("serie_slug")
    val serieSlug: String? = null,
    @SerializedName("serie_name_es")
    val serieNameEs: String? = null,
    val subbers: List<Subber>? = null,
    val langs: List<LanguageMetadata>? = null,
    val uploading: Boolean? = null,
    @SerializedName("isTVShow")
    val isTvShow: Boolean? = null,
    @SerializedName("air_date")
    val airDate: String? = null,
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
    @SerializedName("_id")
    val id: String? = null,
    val server: String? = null,
    val lang: String? = null,
    val link: String? = null,
    val page: String? = null,
    @SerializedName("is_recommended")
    val isRecommended: Boolean? = null,
    val subtitles: List<SubtitleDescriptor>? = null,
)

data class SubtitleDescriptor(
    @SerializedName("language_code")
    val languageCode: String? = null,
    val type: String? = null,
)
