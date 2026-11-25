package com.streamflixreborn.streamflix.kids

import android.content.Context
import com.bumptech.glide.Glide
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.security.MessageDigest

internal object KidArtwork {
    suspend fun localCopy(context: Context, url: String): File = withContext(Dispatchers.IO) {
        val directory = File(context.noBackupFilesDir, "kidflix-artwork")
        val key = MessageDigest.getInstance("SHA-256").digest(url.toByteArray())
            .joinToString("") { "%02x".format(it) }
        val image = File(directory, key)
        if (image.isFile && image.length() > 0) return@withContext image
        directory.mkdirs()
        val glide = Glide.with(context.applicationContext)
        val download = glide.downloadOnly().load(url).submit()
        try {
            val source = download.get()
            val temp = File.createTempFile("art-", ".tmp", directory)
            try {
                source.copyTo(temp, overwrite = true)
                if (!temp.renameTo(image) && !image.isFile) throw java.io.IOException("Could not store artwork")
            } finally {
                temp.delete()
            }
        } finally {
            glide.clear(download)
        }
        image
    }
}
