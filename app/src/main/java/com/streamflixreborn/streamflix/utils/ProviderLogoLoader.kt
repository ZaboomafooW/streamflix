package com.streamflixreborn.streamflix.utils

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Request
import okhttp3.Response
import java.io.IOException

object ProviderLogoLoader {

    fun load(
        url: String,
        referer: String,
        callback: (Result<Bitmap>) -> Unit,
    ): Call {
        val request = Request.Builder()
            .url(url)
            .header("Referer", referer)
            .header("User-Agent", NetworkClient.USER_AGENT)
            .header("Accept", "image/avif,image/webp,image/apng,image/*,*/*;q=0.8")
            .build()
        val call = NetworkClient.default.newCall(request)
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, error: IOException) {
                if (!call.isCanceled()) callback(Result.failure(error))
            }

            override fun onResponse(call: Call, response: Response) {
                response.use {
                    if (!response.isSuccessful) {
                        callback(Result.failure(IOException("Provider logo request failed: HTTP ${response.code}")))
                        return
                    }
                    val body = response.body
                    if (body == null) {
                        callback(Result.failure(IOException("Provider logo response had no body")))
                        return
                    }
                    val bitmap = BitmapFactory.decodeStream(body.byteStream())
                    if (bitmap == null) {
                        callback(Result.failure(IOException("Provider logo response was not a decodable raster image")))
                        return
                    }
                    callback(Result.success(bitmap))
                }
            }
        })
        return call
    }
}
