package com.streamflixreborn.streamflix.providers

import android.net.Uri
import androidx.media3.common.MimeTypes
import com.streamflixreborn.streamflix.models.Video

internal object SpiderManArchive {
    private val files = listOf(
        "01x01 Night of the Lizard.mp4",
        "01x02 The Spider Slayer.mp4",
        "01x03 Return of the Spider Slayers.mp4",
        "01x04 Doctor Octopus - Armed and Dangerous.mp4",
        "01x05 The Menace of Mysterio.mp4",
        "01x06 The Sting of the Scorpion.mp4",
        "01x07 Kraven The Hunter.mp4",
        "01x08 The Alien Costume (Part 1).mp4",
        "01x09 The Alien Costume (Part 2).mp4",
        "01x10 The Alien Costume (Part 3).mp4",
        "01x11 The Hobgoblin (Part 1).mp4",
        "01x12 The Hobgoblin (Part 2).mp4",
        "01x13 Day of the Chameleon.mp4",
        "02x01 The Insidious Six.mp4",
        "02x02 Battle of the Insidious Six.mp4",
        "02x03 Hydro-Man.mp4",
        "02x04 The Mutant Agenda.mp4",
        "02x05 Mutants' Revenge.mp4",
        "02x06 Morbius.mp4",
        "02x07 Enter the Punisher.mp4",
        "02x08 Duel of the Hunters.mp4",
        "02x09 Blade, The Vampire Hunter.mp4",
        "02x10 The Immortal Vampire.mp4",
        "02x11 Tablet of Time.mp4",
        "02x12 Ravages of Time.mp4",
        "02x13 Shriek of the Vulture.mp4",
        "02x14 The Final Nightmare.mp4",
        "03x01 Doctor Strange.mp4",
        "03x02 Make A Wish.mp4",
        "03x03 Attack of the Octobot.mp4",
        "03x04 Enter the Green Goblin.mp4",
        "03x05 The Rocket Racer.mp4",
        "03x06 Framed.mp4",
        "03x07 The Man Without Fear.mp4",
        "03x08 The Ultimate Slayer.mp4",
        "03x09 Tombstone.mp4",
        "03x10 Venom Returns.mp4",
        "03x11 Carnage.mp4",
        "03x12 The Spot.mp4",
        "03x13 Goblin War.mp4",
        "03x14 Turning Point.mp4",
        "04x01 Guilty.mp4",
        "04x02 The Cat.mp4",
        "04x03 The Black Cat.mp4",
        "04x04 The Return of Kraven.mp4",
        "04x05 Partners.mp4",
        "04x06 The Awakening.mp4",
        "04x07 The Vampire Queen.mp4",
        "04x08 The Return of the Green Goblin.mp4",
        "04x09 The Haunting of Mary Jane Watson.mp4",
        "04x10 The Lizard King.mp4",
        "04x11 The Prowler.mp4",
        "05x01 The Wedding.mp4",
        "05x02 Six Forgotten Warriors.mp4",
        "05x03 Unclaimed Legacy.mp4",
        "05x04 Secrets of the Six.mp4",
        "05x05 The Six Fight Again.mp4",
        "05x06 The Price of Heroism.mp4",
        "05x07 The Return of Hydro-Man (Part 1).mp4",
        "05x08 The Return of Hydro-Man (Part 2).mp4",
        "05x09 Arrival.mp4",
        "05x10 The Gauntlet of the Red Skull.mp4",
        "05x11 Doom.mp4",
        "05x12 I Really, Really Hate Clones.mp4",
        "05x13 Farewell, Spider-Man.mp4",
    ).associateBy { it.substringBefore(' ') }

    fun server(episode: Video.Type.Episode): Video.Server? {
        val season = episode.season.number.toString().padStart(2, '0')
        val number = episode.number.toString().padStart(2, '0')
        val file = files["${season}x$number"] ?: return null
        val url = "https://archive.org/download/spider-mantheanimatedseries/${Uri.encode(file)}"
        return Video.Server(url, "Internet Archive (English)", url).apply {
            video = Video(source = url, type = MimeTypes.VIDEO_MP4)
        }
    }
}
