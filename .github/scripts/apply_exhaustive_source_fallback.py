from pathlib import Path
import re

MOBILE = Path("app/src/main/java/com/streamflixreborn/streamflix/fragments/player/PlayerMobileFragment.kt")
TV = Path("app/src/main/java/com/streamflixreborn/streamflix/fragments/player/PlayerTvFragment.kt")


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
    replace_once(
        path,
        '''    private var lastWorkingServer: Video.Server? = null
    private var restoringLastWorkingServer = false
    private var playbackSourceRecoveryInProgress = false
''',
        '''    private var lastWorkingServer: Video.Server? = null
    private var restoringLastWorkingServer = false
    private val failedServers = mutableSetOf<Video.Server>()
    private var playbackSourceRecoveryInProgress = false
''',
        f"{path.name} failed server state",
    )

    replace_once(
        path,
        '''PlayerViewModel.State.LoadingServers -> {
                        lastWorkingServer = null
                        restoringLastWorkingServer = false
                    }''',
        '''PlayerViewModel.State.LoadingServers -> {
                        lastWorkingServer = null
                        restoringLastWorkingServer = false
                        failedServers.clear()
                    }''',
        f"{path.name} reset failed servers on discovery",
    )

    regex_replace_once(
        path,
        r'(?m)^(?P<indent>\s*)selectedServer\?\.let \{\n'
        r'(?P=indent)\s+restoringLastWorkingServer = false\n'
        r'(?P=indent)\s+showSourceStatus\(getString\(R\.string\.player_source_trying, it\.name\)\)\n'
        r'(?P=indent)\s+viewModel\.selectVideo\(it\)\n'
        r'(?P=indent)\}',
        lambda m: (
            f"{m.group('indent')}selectedServer?.let {{\n"
            f"{m.group('indent')}    failedServers.clear()\n"
            f"{m.group('indent')}    restoringLastWorkingServer = false\n"
            f"{m.group('indent')}    showSourceStatus(getString(R.string.player_source_trying, it.name))\n"
            f"{m.group('indent')}    viewModel.selectVideo(it)\n"
            f"{m.group('indent')}}}"
        ),
        f"{path.name} reset failed servers on manual selection",
    )

    regex_replace_once(
        path,
        r'(?m)^(?P<indent>\s*)val nextServer = nextServerAfter\(state\.server\)$',
        lambda m: (
            f"{m.group('indent')}failedServers.add(state.server)\n"
            f"{m.group('indent')}val nextServer = nextUnfailedServerAfter(state.server)"
        ),
        f"{path.name} mark extraction failure",
    )

    old_helper = '''    private fun nextServerAfter(server: Video.Server?): Video.Server? {
        if (server == null) return null
        val index = servers.indexOfFirst { it === server }
            .takeIf { it >= 0 }
            ?: servers.indexOf(server)
        return if (index >= 0) servers.getOrNull(index + 1) else null
    }
'''
    new_helper = '''    private fun nextUnfailedServerAfter(server: Video.Server?): Video.Server? {
        if (servers.isEmpty()) return null

        val currentIndex = if (server == null) {
            -1
        } else {
            servers.indexOfFirst { it === server }
                .takeIf { it >= 0 }
                ?: servers.indexOf(server)
        }

        for (offset in 1..servers.size) {
            val index = if (currentIndex >= 0) {
                (currentIndex + offset) % servers.size
            } else {
                offset - 1
            }
            val candidate = servers[index]
            val isCurrent = server != null && (candidate === server || candidate == server)
            val isLastWorking = lastWorkingServer?.let {
                candidate === it || candidate == it
            } ?: false

            if (!isCurrent && !isLastWorking && candidate !in failedServers) {
                return candidate
            }
        }

        return null
    }
'''
    replace_once(path, old_helper, new_helper, f"{path.name} exhaustive server helper")

    replace_once(
        path,
        '''        if (restoringLastWorkingServer || isFailedServer) return false
''',
        '''        if (restoringLastWorkingServer || isFailedServer || workingServer in failedServers) return false
''',
        f"{path.name} do not restore failed known-good server",
    )

    replace_once(
        path,
        '''        restoringLastWorkingServer = false
        playbackSourceRecoveryInProgress = false
''',
        '''        restoringLastWorkingServer = false
        failedServers.clear()
        playbackSourceRecoveryInProgress = false
''',
        f"{path.name} clear failed servers on terminal failure",
    )

    regex_replace_once(
        path,
        r'(?m)^(?P<indent>\s*)currentServer\?\.let \{ lastWorkingServer = it \}\n'
        r'(?P=indent)restoringLastWorkingServer = false$',
        lambda m: (
            f"{m.group('indent')}currentServer?.let {{ lastWorkingServer = it }}\n"
            f"{m.group('indent')}restoringLastWorkingServer = false\n"
            f"{m.group('indent')}failedServers.clear()"
        ),
        f"{path.name} clear failed servers after playback starts",
    )

    regex_replace_once(
        path,
        r'(?m)^(?P<indent>\s*)val nextServer = nextServerAfter\(currentServer\)$',
        lambda m: (
            f"{m.group('indent')}currentServer?.let(failedServers::add)\n"
            f"{m.group('indent')}val nextServer = nextUnfailedServerAfter(currentServer)"
        ),
        f"{path.name} runtime fallback tracks failed server",
    )
