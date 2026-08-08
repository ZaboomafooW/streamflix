from pathlib import Path

VIEW_MODEL = Path("app/src/main/java/com/streamflixreborn/streamflix/fragments/player/PlayerViewModel.kt")
MOBILE = Path("app/src/main/java/com/streamflixreborn/streamflix/fragments/player/PlayerMobileFragment.kt")
TV = Path("app/src/main/java/com/streamflixreborn/streamflix/fragments/player/PlayerTvFragment.kt")
STRINGS = Path("app/src/main/res/values/strings.xml")


def replace_once(path: Path, old: str, new: str, label: str) -> None:
    text = path.read_text()
    count = text.count(old)
    if count != 1:
        raise RuntimeError(f"{label}: expected one match, found {count}")
    path.write_text(text.replace(old, new, 1))


replace_once(
    VIEW_MODEL,
    '''                if (servers.isEmpty()) {
                    val error = Exception("No streaming servers found for this title.")
                    Log.w("PlayerViewModel", error.message.orEmpty())
                    _state.emit(State.FailedLoadingServers(error))
                    return@launch
                }
''',
    '''                if (servers.isEmpty()) {
                    Log.w("PlayerViewModel", "No streaming servers found for this title.")
                    _state.emit(State.NoServers)
                    return@launch
                }
''',
    "empty server state",
)

replace_once(
    VIEW_MODEL,
    '''    sealed class State {
        data object LoadingServers : State()
        data class SuccessLoadingServers(val servers: List<Video.Server>) : State()
''',
    '''    sealed class State {
        data object LoadingServers : State()
        data object NoServers : State()
        data class SuccessLoadingServers(val servers: List<Video.Server>) : State()
''',
    "no servers state declaration",
)

for path in (MOBILE, TV):
    replace_once(
        path,
        '''is PlayerViewModel.State.FailedLoadingServers -> {
                        showPlaybackUnavailable(state.error)
                    }''',
        '''PlayerViewModel.State.NoServers -> {
                        showPlaybackUnavailable(messageRes = R.string.player_no_sources_message)
                    }
                    is PlayerViewModel.State.FailedLoadingServers -> {
                        showPlaybackUnavailable(
                            state.error,
                            R.string.player_sources_load_failed_message,
                        )
                    }''',
        f"{path.name} server failure states",
    )

    replace_once(
        path,
        '''private fun showPlaybackUnavailable(error: Exception? = null) {''',
        '''private fun showPlaybackUnavailable(
        error: Exception? = null,
        messageRes: Int = R.string.player_retry_later_message,
    ) {''',
        f"{path.name} playback unavailable signature",
    )

    replace_once(
        path,
        '''getString(R.string.player_retry_later_message),''',
        '''getString(messageRes),''',
        f"{path.name} playback unavailable message",
    )

replace_once(
    STRINGS,
    '''    <string name="player_retry_later_message">Video currently unavailable on this provider. Please try again later</string>
''',
    '''    <string name="player_retry_later_message">Video currently unavailable on this provider. Please try again later</string>
    <string name="player_no_sources_message">No playback sources are available for this title.</string>
    <string name="player_sources_load_failed_message">Unable to load playback sources. Please try again.</string>
''',
    "source discovery strings",
)
