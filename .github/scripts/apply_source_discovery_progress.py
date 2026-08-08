from pathlib import Path
import re

MOBILE = Path("app/src/main/java/com/streamflixreborn/streamflix/fragments/player/PlayerMobileFragment.kt")
TV = Path("app/src/main/java/com/streamflixreborn/streamflix/fragments/player/PlayerTvFragment.kt")
STRINGS = Path("app/src/main/res/values/strings.xml")


def replace_once(path: Path, old: str, new: str, label: str) -> None:
    text = path.read_text()
    count = text.count(old)
    if count != 1:
        raise RuntimeError(f"{label}: expected one match, found {count}")
    path.write_text(text.replace(old, new, 1))


def regex_replace_once(path: Path, pattern: str, replacement, label: str) -> None:
    text = path.read_text()
    compiled = re.compile(pattern, re.M)
    matches = list(compiled.finditer(text))
    if len(matches) != 1:
        raise RuntimeError(f"{label}: expected one match, found {len(matches)}")
    match = matches[0]
    new = replacement(match) if callable(replacement) else replacement
    path.write_text(text[:match.start()] + new + text[match.end():])


for path in (MOBILE, TV):
    regex_replace_once(
        path,
        r'(?m)^(?P<indent>\s*)PlayerViewModel\.State\.LoadingServers -> \{\n'
        r'(?P=indent)\s+lastWorkingServer = null\n'
        r'(?P=indent)\s+restoringLastWorkingServer = false\n'
        r'(?P=indent)\s+failedServers\.clear\(\)\n'
        r'(?P=indent)\}',
        lambda m: (
            f"{m.group('indent')}PlayerViewModel.State.LoadingServers -> {{\n"
            f"{m.group('indent')}    lastWorkingServer = null\n"
            f"{m.group('indent')}    restoringLastWorkingServer = false\n"
            f"{m.group('indent')}    failedServers.clear()\n"
            f"{m.group('indent')}    showSourceStatus(getString(R.string.player_sources_loading_message))\n"
            f"{m.group('indent')}}}"
        ),
        f"{path.name} source discovery status",
    )

    regex_replace_once(
        path,
        r'(?m)^(?P<indent>\s*)is PlayerViewModel\.State\.SuccessLoadingServers -> \{\n'
        r'(?P=indent)\s+servers = state\.servers$',
        lambda m: (
            f"{m.group('indent')}is PlayerViewModel.State.SuccessLoadingServers -> {{\n"
            f"{m.group('indent')}    sourceStatusToast?.cancel()\n"
            f"{m.group('indent')}    sourceStatusToast = null\n"
            f"{m.group('indent')}    servers = state.servers"
        ),
        f"{path.name} clear discovery status",
    )

    regex_replace_once(
        path,
        r'(?m)^(?P<indent>\s*)viewModel\.selectVideo\(state\.servers\.first\(\)\)$',
        lambda m: (
            f"{m.group('indent')}state.servers.first().let {{ firstServer ->\n"
            f"{m.group('indent')}    showSourceStatus(getString(R.string.player_source_trying, firstServer.name))\n"
            f"{m.group('indent')}    viewModel.selectVideo(firstServer)\n"
            f"{m.group('indent')}}}"
        ),
        f"{path.name} first source status",
    )

replace_once(
    STRINGS,
    '''    <string name="player_no_working_source_message">No working playback source is currently available.</string>\n''',
    '''    <string name="player_no_working_source_message">No working playback source is currently available.</string>\n    <string name="player_sources_loading_message">Finding playback sources...</string>\n''',
    "source discovery progress string",
)
