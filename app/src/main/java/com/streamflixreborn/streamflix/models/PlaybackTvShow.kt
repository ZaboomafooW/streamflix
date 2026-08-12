package com.streamflixreborn.streamflix.models

import com.streamflixreborn.streamflix.utils.format

internal fun TvShow.toPlaybackTvShow(): Video.Type.Episode.TvShow =
    Video.Type.Episode.TvShow(
        id = id,
        title = title,
        poster = poster,
        banner = banner,
        releaseDate = released?.format("yyyy-MM-dd"),
        imdbId = imdbId,
        originalLanguage = originalLanguage,
    )
