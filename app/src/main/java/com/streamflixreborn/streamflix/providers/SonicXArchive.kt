package com.streamflixreborn.streamflix.providers

import android.net.Uri
import androidx.media3.common.MimeTypes
import com.streamflixreborn.streamflix.models.Video
import java.util.Locale

internal object SonicXArchive {
    private val episodes = listOf(
        "S01E01 - Chaos Control Freaks.ia.mp4",
        "S01E02 - Sonic to the Rescue.ia.mp4",
        "S01E03 - Missile Wrist Rampage.ia.mp4",
        "S01E04 - Chaos Emerald Chaos.ia.mp4",
        "S01E05 - Cracking Knuckles.ia.mp4",
        "S01E06 - Techno-Teacher.ia.mp4",
        "S01E07 - Party Hardly.ia.mp4",
        "S01E08 - Satellite Swindle.ia.mp4",
        "S01E09 - The Last Resort.ia.mp4",
        "S01E10 - Unfair Ball.ia.mp4",
        "S01E11 - Fly Spy.ia.mp4",
        "S01E12 - Beating Eggman - Part 1.ia.mp4",
        "S01E13 - Beating Eggman - Part 2.ia.mp4",
        "S01E14 - That's What Friends Are For.ia.mp4",
        "S01E15 - Skirmish in the Sky.ia.mp4",
        "S01E16 - Depths of Danger.ia.mp4",
        "S01E17 - The Adventures of Knuckles and Hawk.ia.mp4",
        "S01E18 - The Dam Scam.ia.mp4",
        "S01E19 - Sonic's Scream Test.ia.mp4",
        "S01E20 - Cruise Blues.ia.mp4",
        "S01E21 - Fast Friends.ia.mp4",
        "S01E22 - Little Chao Lost.ia.mp4",
        "S01E23 - Emerald Anniversary.ia.mp4",
        "S01E24 - How to Catch a Hedgehog.ia.mp4",
        "S01E25 - A Dastardly Deed.ia.mp4",
        "S01E26 - Countdown Chaos.ia.mp4",
        "S02E01 - Pure Chaos.ia.mp4",
        "S02E02 - A Chaotic Day.ia.mp4",
        "S02E03 - A Robot Revels.ia.mp4",
        "S02E04 - Heads Up, Tails.ia.mp4",
        "S02E05 - Revenge of the Robot.ia.mp4",
        "S02E06 - Flood Fight.ia.mp4",
        "S02E07 - Project - Shadow.ia.mp4",
        "S02E08 - Shadow Knows.ia.mp4",
        "S02E09 - Sonic's Big Break.ia.mp4",
        "S02E10 - Shadow World.ia.mp4",
        "S02E11 - Robotnik's Revenge.ia.mp4",
        "S02E12 - Showdown in Space.ia.mp4",
        "S02E13 - Defective Detective.ia.mp4",
        "S02E14 - Sunblock Solution.ia.mp4",
        "S02E15 - Eggman for President.ia.mp4",
        "S02E16 - A Date to Forget.ia.mp4",
        "S02E17 - Mean Machines.ia.mp4",
        "S02E18 - Sewer Search.ia.mp4",
        "S02E19 - Prize Fights.ia.mp4",
        "S02E20 - A Wild Win.ia.mp4",
        "S02E21 - Map of Mayhem.ia.mp4",
        "S02E22 - The Volcanic Venture.ia.mp4",
        "S02E23 - The Beginning of the End.ia.mp4",
        "S02E24 - Running Out of Time.ia.mp4",
        "S02E25 - Friends 'Til the End.ia.mp4",
        "S02E26 - A New Start.ia.mp4",
        "S03E01 - A Cosmic Call.ia.mp4",
        "S03E02 - Cosmic Crisis.ia.mp4",
        "S03E03 - H2 Whoa.ia.mp4",
        "S03E04 - An Enemy in Need.ia.mp4",
        "S03E05 - A Chilling Discovery.ia.mp4",
        "S03E06 - Desperately Seeking Sonic.ia.mp4",
        "S03E07 - Galactic Gumshoes.ia.mp4",
        "S03E08 - Trick Sand.ia.mp4",
        "S03E09 - Ship of Doom.ia.mp4",
        "S03E10 - An Underground Odyssey.ia.mp4",
        "S03E11 - Station Break-In.ia.mp4",
        "S03E12 - A Metarex Melee.ia.mp4",
        "S03E13 - Mission Match Up.ia.mp4",
        "S03E14 - Clash in the Cloister.ia.mp4",
        "S03E15 - Testing Time.ia.mp4",
        "S03E16 - A Revolutionary Tale.ia.mp4",
        "S03E17 - The Planet of Misfortune.ia.mp4",
        "S03E18 - Terror on the Typhoon.ia.mp4",
        "S03E19 - Hedgehog Hunt.ia.mp4",
        "S03E20 - Zelkova Strikes Back.ia.mp4",
        "S03E21 - The Cosmo Conspiracy.ia.mp4",
        "S03E22 - Eye Spy.ia.mp4",
        "S03E23 - Agent of Mischief.ia.mp4",
        "S03E24 - The Light in the Darkness.ia.mp4",
        "S03E25 - A Fearless Friend.ia.mp4",
        "S03E26 - So Long Sonic.ia.mp4",
    ).associateBy { it.substringBefore(' ') }

    fun server(episode: Video.Type.Episode): Video.Server? {
        val key = String.format(Locale.ROOT, "S%02dE%02d", episode.season.number, episode.number)
        val file = episodes[key] ?: return null
        val url = "https://archive.org/download/sonic-x-full-series-english-dub-1080p/${Uri.encode(file)}"
        return Video.Server(url, "Internet Archive (English)", url).apply {
            video = Video(source = url, type = MimeTypes.VIDEO_MP4)
        }
    }
}
