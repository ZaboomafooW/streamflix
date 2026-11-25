package com.streamflixreborn.streamflix.utils

import androidx.media3.datasource.okhttp.OkHttpDataSource
import com.streamflixreborn.streamflix.models.Video
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Interceptor

internal object PlaybackHttp {
    fun factory(video: () -> Video?): OkHttpDataSource.Factory {
        val client = NetworkClient.default.newBuilder().addInterceptor(Interceptor { chain ->
            val request = chain.request()
            val current = video()
            val token = current?.segmentToken?.encodedQuery
            val master = if (token == null) null else current.source.toHttpUrlOrNull()
            val url = request.url
            if (master != null && master.host == url.host && master.scheme == url.scheme && master.port == url.port) {
                chain.proceed(request.newBuilder().url(url.newBuilder().encodedQuery(token).build()).build())
            } else chain.proceed(request)
        }).build()
        return OkHttpDataSource.Factory(client)
    }
}
