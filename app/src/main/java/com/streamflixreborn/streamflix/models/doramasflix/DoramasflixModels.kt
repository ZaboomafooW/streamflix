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

class ContentPage(
    @SerializedName("items")
    private val rawItems: List<Content> = emptyList(),
    val pageInfo: PageInfo? = null,
) {
    val items: List<Content>
        get() {
            val seen = mutableSetOf<String>()
            return rawItems.filter { content ->
                val providerIdentities = buildList {
                    content.slug
                        ?.trim()
                        ?.takeIf { it.isNotEmpty() }
                        ?.let { add("slug:$it") }
                    content.id
                        ?.trim()
                        ?.takeIf { it.isNotEmpty() }
                        ?.let { add("id:$it") }
                }
                if (providerIdentities.isEmpty()) {
                    true
                } else if (providerIdentities.any(seen::contains)) {
                    false
                } else {
                    seen.addAll(providerIdentities)
                    true
                }
            }
        }
}

data class EpisodePage(
    val items: List<Episode> = emptyList(),
    val pageInfo: PageInfo? = null,
)

data class PageInfo(
    val hasNextPage: Boolean? = null,
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
    @SerializedName("release_date")
    val releaseDate: String? = null,
    val runtime: Int? = null,
    @SerializedName("first_air_date")
    val firstAirDate: String? = null,
    @SerializedName("episode_time")
    val episodeTime: Int? = null,
    val rating: Double? = null,
    @SerializedName("rating_count")
    val ratingCount: Int? = null,
    val genres: List<Tag>? = null,
    val labels: List<Tag>? = null,
    val cast: List<CastMember>? = null,
    val seasons: List<Season>? = null,
    val langs: List<LanguageMetadata>? = null,
) {
    internal fun sourceSignature(): String = listOf(id, slug, nameEs, name)
        .joinToString("|") { it.orEmpty() }
}

data class Images(
    val backdrops: List<String>? = null,
)

data class CastMember(
    val name: String? = null,
    val slug: String? = null,
    @SerializedName("profile_path")
    val profilePath: String? = null,
)

data class Tag(
    val name: String? = null,
    val slug: String? = null,
)

data class LanguageMetadata(
    val name: String? = null,
    val code: String? = null,
    @SerializedName("code_flix")
    val codeFlix: String? = null,
)

data class Season(
    val name: String? = null,
    @SerializedName("name_es")
    val nameEs: String? = null,
    val poster: String? = null,
    @SerializedName("poster_path")
    val posterPath: String? = null,
    @SerializedName("serie_id")
    val serieId: String? = null,
    @SerializedName("season_number")
    val seasonNumber: Int? = null,
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
    @SerializedName("date_string")
    val dateString: String? = null,
    @SerializedName("still_path")
    val stillPath: String? = null,
    @SerializedName("still_image")
    val stillImage: String? = null,
    val backdrop: String? = null,
    val overview: String? = null,
    @SerializedName("air_date")
    val airDate: String? = null,
    val langs: List<LanguageMetadata>? = null,
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
    val subtitles: List<SubtitleDescriptor>? = null,
)

data class SubtitleDescriptor(
    @SerializedName("language_code")
    val languageCode: String? = null,
    val type: String? = null,
)
