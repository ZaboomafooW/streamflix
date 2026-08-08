from pathlib import Path

MOBILE = Path("app/src/main/java/com/streamflixreborn/streamflix/fragments/player/PlayerMobileFragment.kt")
TV = Path("app/src/main/java/com/streamflixreborn/streamflix/fragments/player/PlayerTvFragment.kt")


def replace_once(path: Path, old: str, new: str, label: str) -> None:
    text = path.read_text()
    count = text.count(old)
    if count != 1:
        raise RuntimeError(f"{label}: expected one match, found {count}")
    path.write_text(text.replace(old, new, 1))


replace_once(
    MOBILE,
    "    private var listenerPlayer: ExoPlayer? = null\n",
    "    private var listenerPlayer: ExoPlayer? = null\n    private var playbackSourceRecoveryInProgress = false\n",
    "mobile recovery field",
)
replace_once(
    MOBILE,
    '''                    is PlayerViewModel.State.SuccessLoadingVideo -> {\n                        PlayerSettingsView.Settings.ExtraBuffering.init(state.video.extraBuffering)\n                        PlayerSettingsView.Settings.SoftwareDecoder.init(false)\n                        displayVideo(state.video, state.server)\n                    }\n''',
    '''                    is PlayerViewModel.State.SuccessLoadingVideo -> {\n                        PlayerSettingsView.Settings.ExtraBuffering.init(state.video.extraBuffering)\n                        PlayerSettingsView.Settings.SoftwareDecoder.init(false)\n                        displayVideo(state.video, state.server)\n                        playbackSourceRecoveryInProgress = false\n                    }\n''',
    "mobile recovery success",
)
replace_once(
    MOBILE,
    '''    private fun showPlaybackUnavailable(error: Exception? = null) {\n        error?.let { Log.e("PlayerMobileFragment", "Playback unavailable", it) }\n''',
    '''    private fun showPlaybackUnavailable(error: Exception? = null) {\n        error?.let { Log.e("PlayerMobileFragment", "Playback unavailable", it) }\n        playbackSourceRecoveryInProgress = false\n''',
    "mobile recovery terminal reset",
)
replace_once(
    MOBILE,
    '''                Log.e("PlayerMobileFragment", "onPlayerError: ", error)\n\n                if (viewModel.retryVideoAfterPlaybackError(currentServer)) {\n''',
    '''                Log.e("PlayerMobileFragment", "onPlayerError: ", error)\n\n                if (playbackSourceRecoveryInProgress) {\n                    Log.d("PlayerMobileFragment", "Ignoring duplicate playback error during source recovery")\n                    return\n                }\n                playbackSourceRecoveryInProgress = true\n\n                if (viewModel.retryVideoAfterPlaybackError(currentServer)) {\n''',
    "mobile recovery guard",
)

replace_once(
    TV,
    "    private var listenerPlayer: ExoPlayer? = null\n",
    "    private var listenerPlayer: ExoPlayer? = null\n    private var playbackSourceRecoveryInProgress = false\n",
    "tv recovery field",
)
replace_once(
    TV,
    '''                        is PlayerViewModel.State.SuccessLoadingVideo -> {\n                            PlayerSettingsView.Settings.ExtraBuffering.init(state.video.extraBuffering)\n                            PlayerSettingsView.Settings.SoftwareDecoder.init(false)\n                            displayVideo(state.video, state.server)\n                        }\n''',
    '''                        is PlayerViewModel.State.SuccessLoadingVideo -> {\n                            PlayerSettingsView.Settings.ExtraBuffering.init(state.video.extraBuffering)\n                            PlayerSettingsView.Settings.SoftwareDecoder.init(false)\n                            displayVideo(state.video, state.server)\n                            playbackSourceRecoveryInProgress = false\n                        }\n''',
    "tv recovery success",
)
replace_once(
    TV,
    '''    private fun showPlaybackUnavailable(error: Exception? = null) {\n        error?.let { Log.e("PlayerTvFragment", "Playback unavailable", it) }\n''',
    '''    private fun showPlaybackUnavailable(error: Exception? = null) {\n        error?.let { Log.e("PlayerTvFragment", "Playback unavailable", it) }\n        playbackSourceRecoveryInProgress = false\n''',
    "tv recovery terminal reset",
)
replace_once(
    TV,
    '''                    Log.e("PlayerTvFragment", "onPlayerError: ", error)\n\n                    if (viewModel.retryVideoAfterPlaybackError(currentServer)) {\n''',
    '''                    Log.e("PlayerTvFragment", "onPlayerError: ", error)\n\n                    if (playbackSourceRecoveryInProgress) {\n                        Log.d("PlayerTvFragment", "Ignoring duplicate playback error during source recovery")\n                        return\n                    }\n                    playbackSourceRecoveryInProgress = true\n\n                    if (viewModel.retryVideoAfterPlaybackError(currentServer)) {\n''',
    "tv recovery guard",
)
