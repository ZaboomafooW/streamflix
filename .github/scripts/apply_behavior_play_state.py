from pathlib import Path

MOBILE = Path("app/src/main/java/com/streamflixreborn/streamflix/fragments/player/PlayerMobileFragment.kt")
TV = Path("app/src/main/java/com/streamflixreborn/streamflix/fragments/player/PlayerTvFragment.kt")


def r(path: Path, old: str, new: str, label: str) -> None:
    text = path.read_text()
    count = text.count(old)
    if count != 1:
        raise RuntimeError(f"{label}: expected one match, found {count}")
    path.write_text(text.replace(old, new, 1))


r(MOBILE, "    private var pendingPlaybackPositionMs: Long? = null\n", "    private var pendingPlaybackPositionMs: Long? = null\n    private var pendingPlaybackShouldPlay: Boolean? = null\n", "mobile play-state field")
r(MOBILE, '''                            if (currentUri.isNotBlank()) {\n                                pendingPlaybackPositionMs = player.currentPosition\n                            }\n''', '''                            if (currentUri.isNotBlank()) {\n                                pendingPlaybackPositionMs = player.currentPosition\n                                pendingPlaybackShouldPlay = player.playWhenReady\n                            }\n''', "mobile play-state capture")
r(MOBILE, '''                        val resumePosition = pendingPlaybackPositionMs\n                        pendingPlaybackPositionMs = null\n                        displayVideo(state.video, state.server, resumePosition)\n''', '''                        val resumePosition = pendingPlaybackPositionMs\n                        val shouldPlay = pendingPlaybackShouldPlay ?: true\n                        pendingPlaybackPositionMs = null\n                        pendingPlaybackShouldPlay = null\n                        displayVideo(state.video, state.server, resumePosition, shouldPlay)\n''', "mobile play-state success")
r(MOBILE, '''        pendingPlaybackPositionMs = null\n        Toast.makeText(\n''', '''        pendingPlaybackPositionMs = null\n        pendingPlaybackShouldPlay = null\n        Toast.makeText(\n''', "mobile play-state clear")
r(MOBILE, "    private fun displayVideo(video: Video, server: Video.Server, startPositionMs: Long? = null) {\n", '''    private fun displayVideo(\n        video: Video,\n        server: Video.Server,\n        startPositionMs: Long? = null,\n        shouldPlay: Boolean = true,\n    ) {\n''', "mobile play-state signature")
r(MOBILE, '''        player.prepare()\n        player.play()\n    }\n\n    private fun enterPIPMode() {\n''', '''        player.prepare()\n        player.playWhenReady = shouldPlay\n    }\n\n    private fun enterPIPMode() {\n''', "mobile play-state restore")

# TV already supports shouldPlay upstream; only carry the previous play/pause state through the source reload.
r(TV, "    private var pendingPlaybackPositionMs: Long? = null\n", "    private var pendingPlaybackPositionMs: Long? = null\n    private var pendingPlaybackShouldPlay: Boolean? = null\n", "tv play-state field")
r(TV, '''                                if (currentUri.isNotBlank()) {\n                                    pendingPlaybackPositionMs = player.currentPosition\n                                }\n''', '''                                if (currentUri.isNotBlank()) {\n                                    pendingPlaybackPositionMs = player.currentPosition\n                                    pendingPlaybackShouldPlay = player.playWhenReady\n                                }\n''', "tv play-state capture")
r(TV, '''                            val resumePosition = pendingPlaybackPositionMs\n                            pendingPlaybackPositionMs = null\n                            displayVideo(state.video, state.server, startPositionMs = resumePosition)\n''', '''                            val resumePosition = pendingPlaybackPositionMs\n                            val shouldPlay = pendingPlaybackShouldPlay ?: true\n                            pendingPlaybackPositionMs = null\n                            pendingPlaybackShouldPlay = null\n                            displayVideo(\n                                video = state.video,\n                                server = state.server,\n                                startPositionMs = resumePosition,\n                                shouldPlay = shouldPlay,\n                            )\n''', "tv play-state success")
r(TV, '''        pendingPlaybackPositionMs = null\n        Toast.makeText(\n''', '''        pendingPlaybackPositionMs = null\n        pendingPlaybackShouldPlay = null\n        Toast.makeText(\n''', "tv play-state clear")
