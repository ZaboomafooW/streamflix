package com.streamflixreborn.streamflix.providers

import com.streamflixreborn.streamflix.models.Movie
import com.streamflixreborn.streamflix.models.Show
import com.streamflixreborn.streamflix.models.TvShow

internal val Show.id: String
    get() = when (this) {
        is Movie -> this.id
        is TvShow -> this.id
    }
