from pathlib import Path

MOBILE = Path("app/src/main/java/com/streamflixreborn/streamflix/fragments/player/PlayerMobileFragment.kt")
TV = Path("app/src/main/java/com/streamflixreborn/streamflix/fragments/player/PlayerTvFragment.kt")


def replace_once(path: Path, old: str, new: str, label: str) -> None:
    text = path.read_text()
    count = text.count(old)
    if count != 1:
        raise RuntimeError(f"{label}: expected one match, found {count}")
    path.write_text(text.replace(old, new, 1))


for path in (MOBILE, TV):
    replace_once(
        path,
        "    private var listenerPlayer: ExoPlayer? = null\n    private var playbackSourceRecoveryInProgress = false\n",
        "    private var listenerPlayer: ExoPlayer? = null\n    private var lastWorkingServer: Video.Server? = null\n    private var restoringLastWorkingServer = false\n    private var playbackSourceRecoveryInProgress = false\n",
        f"{path.name} working-source fields",
    )
    replace_once(
        path,
        "                    PlayerViewModel.State.LoadingServers -> {}\n",
        "                    PlayerViewModel.State.LoadingServers -> {\n                        lastWorkingServer = null\n                        restoringLastWorkingServer = false\n                    }\n",
        f"{path.name} server-load reset",
    )

replace_once(
    MOBILE,
    """                                selectedServer?.let(viewModel::selectVideo)\n""",
    """                                selectedServer?.let {\n                                    restoringLastWorkingServer = false\n                                    viewModel.selectVideo(it)\n                                }\n""",
    "mobile manual server selection",
)
replace_once(
    TV,
    """                            selectedServer?.let(viewModel::selectVideo)\n""",
    """                            selectedServer?.let {\n                                restoringLastWorkingServer = false\n                                viewModel.selectVideo(it)\n                            }\n""",
    "tv manual server selection",
)

replace_once(
    MOBILE,
    """                    is PlayerViewModel.State.FailedLoadingVideo -> {\n                        val nextServer = nextServerAfter(state.server)\n                        if (nextServer != null) {\n                            viewModel.selectVideo(nextServer)\n                        } else {\n                            showPlaybackUnavailable(state.error)\n                        }\n                    }\n""",
    """                    is PlayerViewModel.State.FailedLoadingVideo -> {\n                        if (restoringLastWorkingServer) {\n                            restoringLastWorkingServer = false\n                            showPlaybackUnavailable(state.error)\n                        } else {\n                            val nextServer = nextServerAfter(state.server)\n                            if (nextServer != null) {\n                                viewModel.selectVideo(nextServer)\n                            } else if (!restoreLastWorkingSource(state.server)) {\n                                showPlaybackUnavailable(state.error)\n                            }\n                        }\n                    }\n""",
    "mobile failed video restore",
)
replace_once(
    TV,
    """                        is PlayerViewModel.State.FailedLoadingVideo -> {\n                            val nextServer = nextServerAfter(state.server)\n                            if (nextServer != null) {\n                                viewModel.selectVideo(nextServer)\n                            } else {\n                                showPlaybackUnavailable(state.error)\n                            }\n                        }\n""",
    """                        is PlayerViewModel.State.FailedLoadingVideo -> {\n                            if (restoringLastWorkingServer) {\n                                restoringLastWorkingServer = false\n                                showPlaybackUnavailable(state.error)\n                            } else {\n                                val nextServer = nextServerAfter(state.server)\n                                if (nextServer != null) {\n                                    viewModel.selectVideo(nextServer)\n                                } else if (!restoreLastWorkingSource(state.server)) {\n                                    showPlaybackUnavailable(state.error)\n                                }\n                            }\n                        }\n""",
    "tv failed video restore",
)

mobile_helper = """    private fun nextServerAfter(server: Video.Server?): Video.Server? {\n        if (server == null) return null\n        val index = servers.indexOfFirst { it === server }\n            .takeIf { it >= 0 }\n            ?: servers.indexOf(server)\n        return if (index >= 0) servers.getOrNull(index + 1) else null\n    }\n\n"""
mobile_helper_new = mobile_helper + """    private fun restoreLastWorkingSource(failedServer: Video.Server?): Boolean {\n        val workingServer = lastWorkingServer ?: return false\n        val isFailedServer = failedServer != null &&\n            (workingServer === failedServer || workingServer == failedServer)\n        if (restoringLastWorkingServer || isFailedServer) return false\n\n        restoringLastWorkingServer = true\n        viewModel.selectVideo(workingServer)\n        return true\n    }\n\n"""
replace_once(MOBILE, mobile_helper, mobile_helper_new, "mobile restore helper")

# TV helper has the same source indentation as the mobile fragment.
replace_once(TV, mobile_helper, mobile_helper_new, "tv restore helper")

for path, tag in ((MOBILE, "PlayerMobileFragment"), (TV, "PlayerTvFragment")):
    replace_once(
        path,
        f'''        error?.let {{ Log.e("{tag}", "Playback unavailable", it) }}\n        playbackSourceRecoveryInProgress = false\n''',
        f'''        error?.let {{ Log.e("{tag}", "Playback unavailable", it) }}\n        restoringLastWorkingServer = false\n        playbackSourceRecoveryInProgress = false\n''',
        f"{path.name} terminal restore reset",
    )

replace_once(
    MOBILE,
    """                if (isPlaying) {\n                    startProgressHandler()\n""",
    """                if (isPlaying) {\n                    currentServer?.let { lastWorkingServer = it }\n                    restoringLastWorkingServer = false\n                    startProgressHandler()\n""",
    "mobile mark working server",
)
replace_once(
    TV,
    """                    if (isPlaying) {\n                        startProgressHandler()\n""",
    """                    if (isPlaying) {\n                        currentServer?.let { lastWorkingServer = it }\n                        restoringLastWorkingServer = false\n                        startProgressHandler()\n""",
    "tv mark working server",
)

replace_once(
    MOBILE,
    """                Log.e("PlayerMobileFragment", "onPlayerError: ", error)\n\n                if (playbackSourceRecoveryInProgress) {\n""",
    """                Log.e("PlayerMobileFragment", "onPlayerError: ", error)\n\n                if (restoringLastWorkingServer) {\n                    restoringLastWorkingServer = false\n                    showPlaybackUnavailable(error)\n                    return\n                }\n\n                if (playbackSourceRecoveryInProgress) {\n""",
    "mobile restored source playback failure",
)
replace_once(
    TV,
    """                    Log.e("PlayerTvFragment", "onPlayerError: ", error)\n\n                    if (playbackSourceRecoveryInProgress) {\n""",
    """                    Log.e("PlayerTvFragment", "onPlayerError: ", error)\n\n                    if (restoringLastWorkingServer) {\n                        restoringLastWorkingServer = false\n                        showPlaybackUnavailable(error)\n                        return\n                    }\n\n                    if (playbackSourceRecoveryInProgress) {\n""",
    "tv restored source playback failure",
)

replace_once(
    MOBILE,
    """                if (nextServer != null) {\n                    Log.i("PlayerMobileFragment", "Playback failed, trying next server: ${nextServer.name}")\n                    viewModel.selectVideo(nextServer)\n                } else {\n                    showPlaybackUnavailable()\n                }\n""",
    """                if (nextServer != null) {\n                    Log.i("PlayerMobileFragment", "Playback failed, trying next server: ${nextServer.name}")\n                    viewModel.selectVideo(nextServer)\n                } else if (!restoreLastWorkingSource(currentServer)) {\n                    showPlaybackUnavailable()\n                }\n""",
    "mobile runtime restore",
)
replace_once(
    TV,
    """                    if (nextServer != null) {\n                        Log.i("PlayerTvFragment", "Playback failed, trying next server: ${nextServer.name}")\n                        viewModel.selectVideo(nextServer)\n                    } else {\n                        showPlaybackUnavailable()\n                    }\n""",
    """                    if (nextServer != null) {\n                        Log.i("PlayerTvFragment", "Playback failed, trying next server: ${nextServer.name}")\n                        viewModel.selectVideo(nextServer)\n                    } else if (!restoreLastWorkingSource(currentServer)) {\n                        showPlaybackUnavailable()\n                    }\n""",
    "tv runtime restore",
)
