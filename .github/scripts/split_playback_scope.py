#!/usr/bin/env python3
from pathlib import Path
import sys

MOBILE = Path("app/src/main/java/com/streamflixreborn/streamflix/fragments/player/PlayerMobileFragment.kt")
TV = Path("app/src/main/java/com/streamflixreborn/streamflix/fragments/player/PlayerTvFragment.kt")


def replace_once(text: str, old: str, new: str, label: str) -> str:
    count = text.count(old)
    if count != 1:
        raise RuntimeError(f"{label}: expected exactly one match, found {count}")
    return text.replace(old, new, 1)


def listener_fix(path: Path, indent: str) -> None:
    text = path.read_text()
    text = replace_once(
        text,
        f"{indent}private var currentServer: Video.Server? = null\n",
        f"{indent}private var currentServer: Video.Server? = null\n{indent}private var listenerPlayer: ExoPlayer? = null\n",
        f"{path.name} listener field",
    )
    text = replace_once(
        text,
        f"{indent}player.addListener(object : Player.Listener {{\n",
        f"{indent}val shouldAttachListener = listenerPlayer !== player\n{indent}if (shouldAttachListener) listenerPlayer = player\n{indent}if (shouldAttachListener) player.addListener(object : Player.Listener {{\n",
        f"{path.name} listener attachment",
    )
    release_indent = "        " if path == MOBILE else "            "
    text = replace_once(
        text,
        f"{release_indent}stopProgressHandler()\n{release_indent}binding.pvPlayer.player = null\n",
        f"{release_indent}stopProgressHandler()\n{release_indent}listenerPlayer = null\n{release_indent}binding.pvPlayer.player = null\n",
        f"{path.name} listener release",
    )
    path.write_text(text)


def position_mobile() -> None:
    text = MOBILE.read_text()
    text = replace_once(text, "    private var listenerPlayer: ExoPlayer? = null\n", "    private var listenerPlayer: ExoPlayer? = null\n    private var pendingPlaybackPositionMs: Long? = null\n", "mobile position field")
    text = replace_once(text, '''                    is PlayerViewModel.State.LoadingVideo -> {\n                        player.setMediaItem(\n''', '''                    is PlayerViewModel.State.LoadingVideo -> {\n                        if (pendingPlaybackPositionMs == null) {\n                            val currentUri = player.currentMediaItem?.localConfiguration?.uri?.toString().orEmpty()\n                            if (currentUri.isNotBlank()) {\n                                pendingPlaybackPositionMs = player.currentPosition\n                            }\n                        }\n\n                        player.setMediaItem(\n''', "mobile position capture")
    text = replace_once(text, '''                    is PlayerViewModel.State.SuccessLoadingVideo -> {\n                        PlayerSettingsView.Settings.ExtraBuffering.init(state.video.extraBuffering)\n                        PlayerSettingsView.Settings.SoftwareDecoder.init(false)\n                        displayVideo(state.video, state.server)\n                        playbackSourceRecoveryInProgress = false\n                    }\n''', '''                    is PlayerViewModel.State.SuccessLoadingVideo -> {\n                        PlayerSettingsView.Settings.ExtraBuffering.init(state.video.extraBuffering)\n                        PlayerSettingsView.Settings.SoftwareDecoder.init(false)\n                        val resumePosition = pendingPlaybackPositionMs\n                        pendingPlaybackPositionMs = null\n                        displayVideo(state.video, state.server, resumePosition)\n                        playbackSourceRecoveryInProgress = false\n                    }\n''', "mobile position success")
    text = replace_once(text, '''        playbackSourceRecoveryInProgress = false\n        Toast.makeText(\n''', '''        playbackSourceRecoveryInProgress = false\n        pendingPlaybackPositionMs = null\n        Toast.makeText(\n''', "mobile position clear")
    text = replace_once(text, "    private fun displayVideo(video: Video, server: Video.Server) {\n", "    private fun displayVideo(video: Video, server: Video.Server, startPositionMs: Long? = null) {\n", "mobile position signature")
    text = replace_once(text, "        val currentPosition = player.currentPosition\n", "        val currentPosition = startPositionMs ?: player.currentPosition\n", "mobile position current")
    text = replace_once(text, '''        if (currentPosition == 0L) {\n''', '''        if (startPositionMs != null) {\n            player.seekTo(startPositionMs)\n        } else if (currentPosition == 0L) {\n''', "mobile position seek")
    MOBILE.write_text(text)


def position_tv() -> None:
    text = TV.read_text()
    text = replace_once(text, "    private var listenerPlayer: ExoPlayer? = null\n", "    private var listenerPlayer: ExoPlayer? = null\n    private var pendingPlaybackPositionMs: Long? = null\n", "tv position field")
    text = replace_once(text, '''                        is PlayerViewModel.State.LoadingVideo -> {\n                            player.setMediaItem(\n''', '''                        is PlayerViewModel.State.LoadingVideo -> {\n                            if (pendingPlaybackPositionMs == null) {\n                                val currentUri = player.currentMediaItem?.localConfiguration?.uri?.toString().orEmpty()\n                                if (currentUri.isNotBlank()) {\n                                    pendingPlaybackPositionMs = player.currentPosition\n                                }\n                            }\n\n                            player.setMediaItem(\n''', "tv position capture")
    text = replace_once(text, '''                        is PlayerViewModel.State.SuccessLoadingVideo -> {\n                            PlayerSettingsView.Settings.ExtraBuffering.init(state.video.extraBuffering)\n                            PlayerSettingsView.Settings.SoftwareDecoder.init(false)\n                            displayVideo(state.video, state.server)\n                            playbackSourceRecoveryInProgress = false\n                        }\n''', '''                        is PlayerViewModel.State.SuccessLoadingVideo -> {\n                            PlayerSettingsView.Settings.ExtraBuffering.init(state.video.extraBuffering)\n                            PlayerSettingsView.Settings.SoftwareDecoder.init(false)\n                            val resumePosition = pendingPlaybackPositionMs\n                            pendingPlaybackPositionMs = null\n                            displayVideo(state.video, state.server, startPositionMs = resumePosition)\n                            playbackSourceRecoveryInProgress = false\n                        }\n''', "tv position success")
    text = replace_once(text, '''        playbackSourceRecoveryInProgress = false\n        Toast.makeText(\n''', '''        playbackSourceRecoveryInProgress = false\n        pendingPlaybackPositionMs = null\n        Toast.makeText(\n''', "tv position clear")
    text = replace_once(text, "        private fun displayVideo(video: Video, server: Video.Server) {\n", "        private fun displayVideo(video: Video, server: Video.Server, startPositionMs: Long? = null) {\n", "tv position signature")
    text = replace_once(text, "            val currentPosition = player.currentPosition\n", "            val currentPosition = startPositionMs ?: player.currentPosition\n", "tv position current")
    text = replace_once(text, '''            if (currentPosition == 0L) {\n''', '''            if (startPositionMs != null) {\n                player.seekTo(startPositionMs)\n            } else if (currentPosition == 0L) {\n''', "tv position seek")
    TV.write_text(text)


def state_mobile() -> None:
    text = MOBILE.read_text()
    text = replace_once(text, "    private var pendingPlaybackPositionMs: Long? = null\n", "    private var pendingPlaybackPositionMs: Long? = null\n    private var pendingPlaybackShouldPlay: Boolean? = null\n", "mobile play-state field")
    text = replace_once(text, '''                            if (currentUri.isNotBlank()) {\n                                pendingPlaybackPositionMs = player.currentPosition\n                            }\n''', '''                            if (currentUri.isNotBlank()) {\n                                pendingPlaybackPositionMs = player.currentPosition\n                                pendingPlaybackShouldPlay = player.playWhenReady\n                            }\n''', "mobile play-state capture")
    text = replace_once(text, '''                        val resumePosition = pendingPlaybackPositionMs\n                        pendingPlaybackPositionMs = null\n                        displayVideo(state.video, state.server, resumePosition)\n''', '''                        val resumePosition = pendingPlaybackPositionMs\n                        val shouldPlay = pendingPlaybackShouldPlay ?: true\n                        pendingPlaybackPositionMs = null\n                        pendingPlaybackShouldPlay = null\n                        displayVideo(state.video, state.server, resumePosition, shouldPlay)\n''', "mobile play-state success")
    text = replace_once(text, '''        pendingPlaybackPositionMs = null\n        Toast.makeText(\n''', '''        pendingPlaybackPositionMs = null\n        pendingPlaybackShouldPlay = null\n        Toast.makeText(\n''', "mobile play-state clear")
    text = replace_once(text, "    private fun displayVideo(video: Video, server: Video.Server, startPositionMs: Long? = null) {\n", '''    private fun displayVideo(\n        video: Video,\n        server: Video.Server,\n        startPositionMs: Long? = null,\n        shouldPlay: Boolean = true,\n    ) {\n''', "mobile play-state signature")
    text = replace_once(text, "        player.play()\n", "        player.playWhenReady = shouldPlay\n", "mobile play-state restore")
    MOBILE.write_text(text)


def state_tv() -> None:
    text = TV.read_text()
    text = replace_once(text, "    private var pendingPlaybackPositionMs: Long? = null\n", "    private var pendingPlaybackPositionMs: Long? = null\n    private var pendingPlaybackShouldPlay: Boolean? = null\n", "tv play-state field")
    text = replace_once(text, '''                                if (currentUri.isNotBlank()) {\n                                    pendingPlaybackPositionMs = player.currentPosition\n                                }\n''', '''                                if (currentUri.isNotBlank()) {\n                                    pendingPlaybackPositionMs = player.currentPosition\n                                    pendingPlaybackShouldPlay = player.playWhenReady\n                                }\n''', "tv play-state capture")
    text = replace_once(text, '''                            val resumePosition = pendingPlaybackPositionMs\n                            pendingPlaybackPositionMs = null\n                            displayVideo(state.video, state.server, startPositionMs = resumePosition)\n''', '''                            val resumePosition = pendingPlaybackPositionMs\n                            val shouldPlay = pendingPlaybackShouldPlay ?: true\n                            pendingPlaybackPositionMs = null\n                            pendingPlaybackShouldPlay = null\n                            displayVideo(\n                                video = state.video,\n                                server = state.server,\n                                startPositionMs = resumePosition,\n                                shouldPlay = shouldPlay,\n                            )\n''', "tv play-state success")
    text = replace_once(text, '''        pendingPlaybackPositionMs = null\n        Toast.makeText(\n''', '''        pendingPlaybackPositionMs = null\n        pendingPlaybackShouldPlay = null\n        Toast.makeText(\n''', "tv play-state clear")
    text = replace_once(text, "        private fun displayVideo(video: Video, server: Video.Server, startPositionMs: Long? = null) {\n", '''        private fun displayVideo(\n            video: Video,\n            server: Video.Server,\n            startPositionMs: Long? = null,\n            shouldPlay: Boolean = true,\n        ) {\n''', "tv play-state signature")
    text = replace_once(text, "            player.play()\n", "            player.playWhenReady = shouldPlay\n", "tv play-state restore")
    TV.write_text(text)


mode = sys.argv[1] if len(sys.argv) > 1 else ""
if mode == "listener":
    listener_fix(MOBILE, "    ")
    listener_fix(TV, "    ")
elif mode == "position":
    position_mobile()
    position_tv()
elif mode == "state":
    state_mobile()
    state_tv()
else:
    raise SystemExit("usage: split_playback_scope.py listener|position|state")
