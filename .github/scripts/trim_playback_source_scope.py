from pathlib import Path

MOBILE = Path("app/src/main/java/com/streamflixreborn/streamflix/fragments/player/PlayerMobileFragment.kt")
TV = Path("app/src/main/java/com/streamflixreborn/streamflix/fragments/player/PlayerTvFragment.kt")


def replace_once(text: str, old: str, new: str, label: str) -> str:
    count = text.count(old)
    if count != 1:
        raise RuntimeError(f"{label}: expected exactly one match, found {count}")
    return text.replace(old, new, 1)


def trim_mobile() -> None:
    text = MOBILE.read_text()
    text = replace_once(text, "    private var pendingPlaybackPositionMs: Long? = null\n", "", "mobile position field")
    text = replace_once(text, "    private var pendingPlaybackShouldPlay: Boolean? = null\n", "", "mobile play-state field")

    text = replace_once(
        text,
        '''                    is PlayerViewModel.State.LoadingVideo -> {\n                        if (pendingPlaybackPositionMs == null) {\n                            val currentUri = player.currentMediaItem?.localConfiguration?.uri?.toString().orEmpty()\n                            if (currentUri.isNotBlank()) {\n                                pendingPlaybackPositionMs = player.currentPosition\n                                pendingPlaybackShouldPlay = player.playWhenReady\n                            }\n                        }\n\n                        player.setMediaItem(\n''',
        '''                    is PlayerViewModel.State.LoadingVideo -> {\n                        player.setMediaItem(\n''',
        "mobile loading continuity",
    )

    text = replace_once(
        text,
        '''                    is PlayerViewModel.State.SuccessLoadingVideo -> {\n                        PlayerSettingsView.Settings.ExtraBuffering.init(state.video.extraBuffering)\n                        PlayerSettingsView.Settings.SoftwareDecoder.init(false)\n                        val resumePosition = pendingPlaybackPositionMs\n                        val shouldPlay = pendingPlaybackShouldPlay ?: true\n                        pendingPlaybackPositionMs = null\n                        pendingPlaybackShouldPlay = null\n                        displayVideo(state.video, state.server, resumePosition, shouldPlay)\n                        playbackSourceRecoveryInProgress = false\n                    }\n''',
        '''                    is PlayerViewModel.State.SuccessLoadingVideo -> {\n                        PlayerSettingsView.Settings.ExtraBuffering.init(state.video.extraBuffering)\n                        PlayerSettingsView.Settings.SoftwareDecoder.init(false)\n                        displayVideo(state.video, state.server)\n                        playbackSourceRecoveryInProgress = false\n                    }\n''',
        "mobile success continuity",
    )

    text = replace_once(
        text,
        '''        playbackSourceRecoveryInProgress = false\n        pendingPlaybackPositionMs = null\n        pendingPlaybackShouldPlay = null\n''',
        '''        playbackSourceRecoveryInProgress = false\n''',
        "mobile unavailable continuity",
    )

    text = replace_once(
        text,
        '''    private fun displayVideo(\n        video: Video,\n        server: Video.Server,\n        startPositionMs: Long? = null,\n        shouldPlay: Boolean = true,\n    ) {\n''',
        '''    private fun displayVideo(video: Video, server: Video.Server) {\n''',
        "mobile display signature",
    )
    text = replace_once(text, "        val currentPosition = startPositionMs ?: player.currentPosition\n", "        val currentPosition = player.currentPosition\n", "mobile position capture")
    text = replace_once(
        text,
        '''        if (startPositionMs != null) {\n            player.seekTo(startPositionMs)\n        } else if (currentPosition == 0L) {\n''',
        '''        if (currentPosition == 0L) {\n''',
        "mobile explicit resume seek",
    )
    text = replace_once(text, "        player.playWhenReady = shouldPlay\n", "        player.play()\n", "mobile play-state restore")

    forbidden = ["pendingPlaybackPositionMs", "pendingPlaybackShouldPlay", "startPositionMs", "shouldPlay"]
    for token in forbidden:
        if token in text:
            raise RuntimeError(f"mobile still contains out-of-scope token: {token}")
    MOBILE.write_text(text)


def trim_tv() -> None:
    text = TV.read_text()
    text = replace_once(text, "    private var pendingPlaybackPositionMs: Long? = null\n", "", "tv position field")
    text = replace_once(text, "    private var pendingPlaybackShouldPlay: Boolean? = null\n", "", "tv play-state field")

    text = replace_once(
        text,
        '''                        is PlayerViewModel.State.LoadingVideo -> {\n                            if (pendingPlaybackPositionMs == null) {\n                                val currentUri = player.currentMediaItem?.localConfiguration?.uri?.toString().orEmpty()\n                                if (currentUri.isNotBlank()) {\n                                    pendingPlaybackPositionMs = player.currentPosition\n                                    pendingPlaybackShouldPlay = player.playWhenReady\n                                }\n                            }\n\n                            player.setMediaItem(\n''',
        '''                        is PlayerViewModel.State.LoadingVideo -> {\n                            player.setMediaItem(\n''',
        "tv loading continuity",
    )

    text = replace_once(
        text,
        '''                        is PlayerViewModel.State.SuccessLoadingVideo -> {\n                            PlayerSettingsView.Settings.ExtraBuffering.init(state.video.extraBuffering)\n                            PlayerSettingsView.Settings.SoftwareDecoder.init(false)\n                            val resumePosition = pendingPlaybackPositionMs\n                            val shouldPlay = pendingPlaybackShouldPlay ?: true\n                            pendingPlaybackPositionMs = null\n                            pendingPlaybackShouldPlay = null\n                            displayVideo(\n                                video = state.video,\n                                server = state.server,\n                                startPositionMs = resumePosition,\n                                shouldPlay = shouldPlay,\n                            )\n                            playbackSourceRecoveryInProgress = false\n                        }\n''',
        '''                        is PlayerViewModel.State.SuccessLoadingVideo -> {\n                            PlayerSettingsView.Settings.ExtraBuffering.init(state.video.extraBuffering)\n                            PlayerSettingsView.Settings.SoftwareDecoder.init(false)\n                            displayVideo(state.video, state.server)\n                            playbackSourceRecoveryInProgress = false\n                        }\n''',
        "tv success continuity",
    )

    text = replace_once(
        text,
        '''        playbackSourceRecoveryInProgress = false\n        pendingPlaybackPositionMs = null\n        pendingPlaybackShouldPlay = null\n''',
        '''        playbackSourceRecoveryInProgress = false\n''',
        "tv unavailable continuity",
    )

    text = replace_once(
        text,
        '''        private fun displayVideo(\n            video: Video,\n            server: Video.Server,\n            startPositionMs: Long? = null,\n            shouldPlay: Boolean = true,\n        ) {\n''',
        '''        private fun displayVideo(video: Video, server: Video.Server) {\n''',
        "tv display signature",
    )
    text = replace_once(text, "            val currentPosition = startPositionMs ?: player.currentPosition\n", "            val currentPosition = player.currentPosition\n", "tv position capture")
    text = replace_once(
        text,
        '''            if (startPositionMs != null) {\n                player.seekTo(startPositionMs)\n            } else if (currentPosition == 0L) {\n''',
        '''            if (currentPosition == 0L) {\n''',
        "tv explicit resume seek",
    )
    text = replace_once(text, "            player.playWhenReady = shouldPlay\n", "            player.play()\n", "tv play-state restore")

    forbidden = ["pendingPlaybackPositionMs", "pendingPlaybackShouldPlay", "startPositionMs", "shouldPlay"]
    for token in forbidden:
        if token in text:
            raise RuntimeError(f"tv still contains out-of-scope token: {token}")
    TV.write_text(text)


trim_mobile()
trim_tv()
