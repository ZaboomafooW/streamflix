from pathlib import Path

MOBILE = Path("app/src/main/java/com/streamflixreborn/streamflix/fragments/player/PlayerMobileFragment.kt")
TV = Path("app/src/main/java/com/streamflixreborn/streamflix/fragments/player/PlayerTvFragment.kt")
STRINGS = Path("app/src/main/res/values/strings.xml")


def replace_once(path: Path, old: str, new: str, label: str) -> None:
    text = path.read_text()
    count = text.count(old)
    if count != 1:
        raise RuntimeError(f"{label}: expected one match, found {count}")
    path.write_text(text.replace(old, new, 1))


for path in (MOBILE, TV):
    replace_once(
        path,
        "    private var zoomToast: Toast? = null\n",
        "    private var zoomToast: Toast? = null\n    private var sourceStatusToast: Toast? = null\n",
        f"{path.name} source status toast",
    )

replace_once(
    MOBILE,
    """                                selectedServer?.let {\n                                    restoringLastWorkingServer = false\n                                    viewModel.selectVideo(it)\n                                }\n""",
    """                                selectedServer?.let {\n                                    restoringLastWorkingServer = false\n                                    showSourceStatus(getString(R.string.player_source_trying, it.name))\n                                    viewModel.selectVideo(it)\n                                }\n""",
    "mobile manual source status",
)
replace_once(
    TV,
    """                            selectedServer?.let {\n                                restoringLastWorkingServer = false\n                                viewModel.selectVideo(it)\n                            }\n""",
    """                            selectedServer?.let {\n                                restoringLastWorkingServer = false\n                                showSourceStatus(getString(R.string.player_source_trying, it.name))\n                                viewModel.selectVideo(it)\n                            }\n""",
    "tv manual source status",
)

replace_once(
    MOBILE,
    """                            if (nextServer != null) {\n                                viewModel.selectVideo(nextServer)\n                            } else if (!restoreLastWorkingSource(state.server)) {\n""",
    """                            if (nextServer != null) {\n                                showSourceStatus(\n                                    getString(\n                                        R.string.player_source_trying_next,\n                                        state.server.name,\n                                        nextServer.name,\n                                    )\n                                )\n                                viewModel.selectVideo(nextServer)\n                            } else if (!restoreLastWorkingSource(state.server)) {\n""",
    "mobile extraction fallback status",
)
replace_once(
    TV,
    """                                if (nextServer != null) {\n                                    viewModel.selectVideo(nextServer)\n                                } else if (!restoreLastWorkingSource(state.server)) {\n""",
    """                                if (nextServer != null) {\n                                    showSourceStatus(\n                                        getString(\n                                            R.string.player_source_trying_next,\n                                            state.server.name,\n                                            nextServer.name,\n                                        )\n                                    )\n                                    viewModel.selectVideo(nextServer)\n                                } else if (!restoreLastWorkingSource(state.server)) {\n""",
    "tv extraction fallback status",
)

for path in (MOBILE, TV):
    replace_once(
        path,
        """        restoringLastWorkingServer = true\n        viewModel.selectVideo(workingServer)\n""",
        """        restoringLastWorkingServer = true\n        showSourceStatus(getString(R.string.player_source_restoring, workingServer.name))\n        viewModel.selectVideo(workingServer)\n""",
        f"{path.name} restore status",
    )

mobile_helper = """    private fun nextServerAfter(server: Video.Server?): Video.Server? {\n        if (server == null) return null\n        val index = servers.indexOfFirst { it === server }\n            .takeIf { it >= 0 }\n            ?: servers.indexOf(server)\n        return if (index >= 0) servers.getOrNull(index + 1) else null\n    }\n\n"""
mobile_helper_new = """    private fun showSourceStatus(message: String) {\n        sourceStatusToast?.cancel()\n        sourceStatusToast = Toast.makeText(requireContext(), message, Toast.LENGTH_SHORT).also {\n            it.show()\n        }\n    }\n\n""" + mobile_helper
replace_once(MOBILE, mobile_helper, mobile_helper_new, "mobile source status helper")
replace_once(TV, mobile_helper, mobile_helper_new, "tv source status helper")

for path, tag in ((MOBILE, "PlayerMobileFragment"), (TV, "PlayerTvFragment")):
    replace_once(
        path,
        f'''        error?.let {{ Log.e("{tag}", "Playback unavailable", it) }}\n        restoringLastWorkingServer = false\n''',
        f'''        error?.let {{ Log.e("{tag}", "Playback unavailable", it) }}\n        sourceStatusToast?.cancel()\n        sourceStatusToast = null\n        restoringLastWorkingServer = false\n''',
        f"{path.name} terminal status cleanup",
    )

replace_once(
    MOBILE,
    """                if (isPlaying) {\n                    currentServer?.let { lastWorkingServer = it }\n                    restoringLastWorkingServer = false\n                    startProgressHandler()\n""",
    """                if (isPlaying) {\n                    currentServer?.let { lastWorkingServer = it }\n                    restoringLastWorkingServer = false\n                    sourceStatusToast?.cancel()\n                    sourceStatusToast = null\n                    startProgressHandler()\n""",
    "mobile clear status when playing",
)
replace_once(
    TV,
    """                    if (isPlaying) {\n                        currentServer?.let { lastWorkingServer = it }\n                        restoringLastWorkingServer = false\n                        startProgressHandler()\n""",
    """                    if (isPlaying) {\n                        currentServer?.let { lastWorkingServer = it }\n                        restoringLastWorkingServer = false\n                        sourceStatusToast?.cancel()\n                        sourceStatusToast = null\n                        startProgressHandler()\n""",
    "tv clear status when playing",
)

replace_once(
    MOBILE,
    """                if (viewModel.retryVideoAfterPlaybackError(currentServer)) {\n                    Log.i("PlayerMobileFragment", "Playback failed, retrying current server once")\n                    return\n                }\n""",
    """                if (viewModel.retryVideoAfterPlaybackError(currentServer)) {\n                    Log.i("PlayerMobileFragment", "Playback failed, retrying current server once")\n                    currentServer?.let {\n                        showSourceStatus(getString(R.string.player_source_retrying, it.name))\n                    }\n                    return\n                }\n""",
    "mobile retry status",
)
replace_once(
    TV,
    """                    if (viewModel.retryVideoAfterPlaybackError(currentServer)) {\n                        Log.i("PlayerTvFragment", "Playback failed, retrying current server once")\n                        return\n                    }\n""",
    """                    if (viewModel.retryVideoAfterPlaybackError(currentServer)) {\n                        Log.i("PlayerTvFragment", "Playback failed, retrying current server once")\n                        currentServer?.let {\n                            showSourceStatus(getString(R.string.player_source_retrying, it.name))\n                        }\n                        return\n                    }\n""",
    "tv retry status",
)

replace_once(
    MOBILE,
    """                if (nextServer != null) {\n                    Log.i("PlayerMobileFragment", "Playback failed, trying next server: ${nextServer.name}")\n                    viewModel.selectVideo(nextServer)\n""",
    """                if (nextServer != null) {\n                    Log.i("PlayerMobileFragment", "Playback failed, trying next server: ${nextServer.name}")\n                    showSourceStatus(\n                        getString(\n                            R.string.player_source_trying_next,\n                            currentServer?.name ?: getString(R.string.player_source_unknown),\n                            nextServer.name,\n                        )\n                    )\n                    viewModel.selectVideo(nextServer)\n""",
    "mobile runtime fallback status",
)
replace_once(
    TV,
    """                    if (nextServer != null) {\n                        Log.i("PlayerTvFragment", "Playback failed, trying next server: ${nextServer.name}")\n                        viewModel.selectVideo(nextServer)\n""",
    """                    if (nextServer != null) {\n                        Log.i("PlayerTvFragment", "Playback failed, trying next server: ${nextServer.name}")\n                        showSourceStatus(\n                            getString(\n                                R.string.player_source_trying_next,\n                                currentServer?.name ?: getString(R.string.player_source_unknown),\n                                nextServer.name,\n                            )\n                        )\n                        viewModel.selectVideo(nextServer)\n""",
    "tv runtime fallback status",
)

replace_once(
    STRINGS,
    """    <string name="player_retry_later_message">Video currently unavailable on this provider. Please try again later</string>\n""",
    """    <string name="player_retry_later_message">Video currently unavailable on this provider. Please try again later</string>\n    <string name="player_source_trying">Trying %1$s...</string>\n    <string name="player_source_retrying">%1$s didn\'t work. Retrying...</string>\n    <string name="player_source_trying_next">%1$s didn\'t work. Trying %2$s...</string>\n    <string name="player_source_restoring">Other sources didn\'t work. Returning to %1$s...</string>\n    <string name="player_source_unknown">Current source</string>\n""",
    "source status strings",
)
