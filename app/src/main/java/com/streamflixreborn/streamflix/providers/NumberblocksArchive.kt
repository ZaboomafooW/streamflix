package com.streamflixreborn.streamflix.providers

import android.net.Uri
import androidx.media3.common.MimeTypes
import com.streamflixreborn.streamflix.models.Video

internal object NumberblocksArchive {
    // The collections group broadcast seasons differently from TMDb. Match by episode title.
    private val episodes = buildMap {
        listOf(
            "01 - One.mp4",
            "02 - Another One.mp4",
            "03 - Two.mp4",
            "04 - Three.mp4",
            "05 - One, Two, Three.mp4",
            "06 - Four.mp4",
            "07 - Five.mp4",
            "08 - Three Little Pigs.mp4",
            "09 - Off We Go.mp4",
            "10 - How to Count.mp4",
            "11 - Stampolines.mp4",
            "12 - The Whole of Me.mp4",
            "13 - The Terrible Twos.mp4",
            "14 - Holes.mp4",
            "15 - Hide and Seek.mp4",
            "16 - Six.mp4",
            "17 - Seven.mp4",
            "18 - Eight.mp4",
            "19 - Nine.mp4",
            "20 - Ten.mp4",
            "21 - Just Add One.mp4",
            "22 - Blast Off.mp4",
            "23 - Counting Sheep.mp4",
            "24 - Double Trouble.mp4",
            "25 - The Three Threes.mp4",
            "26 - Odds and Evens.mp4",
            "27 - Fluffies.mp4",
            "28 - The Two Tree.mp4",
            "29 - Numberblock Castle.mp4",
            "30 - Ten Green Bottles.mp4",
        ).forEach { file -> put(titleKey(file.drop(2).removeSuffix(".mp4")), "09-off-we-go/$file") }
        listOf(
            "01 Once Upon a Time.mp4",
            "02 Blockzilla.mp4",
            "03 The Numberblocks Express.mp4",
            "04 Fruit Salad.mp4",
            "05 Zero.mp4",
            "06 Now We Are Six to Ten.mp4",
            "07 Numberblobs.mp4",
            "08 Building Blocks.mp4",
            "09 Peekaboo.mp4",
            "10 Hiccups.mp4",
            "11 Whats the Difference.mp4",
            "12 Numberblock Rally.mp4",
            "13 Five and Friends.mp4",
            "14 Octoblock to the Rescue.mp4",
            "15 Ten Again.mp4",
            "16 Flatland.mp4",
            "17 Pattern Palace.mp4",
            "18 The Legend of Big Tum.mp4",
            "19 Mirror Mirror.mp4",
            "20 The Wrong Number.mp4",
            "21 Eleven.mp4",
            "22 Twelve.mp4",
            "23 The Way of the Rectangle.mp4",
            "24 - Ride the Rays.mp4",
            "25 - Block Star.mp4",
            "26 - Thirteen.mp4",
            "27 - Fourteen.mp4",
            "28 - Fifteen.mp4",
            "29 - Tween Scenes.mp4",
            "30 - Step Squads.mp4",
        ).forEach { file -> put(titleKey(file.drop(2).removeSuffix(".mp4")), "25-block-star_202401/$file") }
    }

    private fun titleKey(title: String) = title.lowercase().filter(Char::isLetterOrDigit)

    fun server(title: String?): Video.Server? {
        val path = episodes[titleKey(title.orEmpty())] ?: return null
        val url = "https://archive.org/download/${Uri.encode(path, "/")}"
        return Video.Server(url, "Internet Archive", url).apply {
            video = Video(source = url, type = MimeTypes.VIDEO_MP4)
        }
    }
}
