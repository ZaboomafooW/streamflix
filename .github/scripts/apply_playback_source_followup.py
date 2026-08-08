#!/usr/bin/env python3
from pathlib import Path
import re
import subprocess

BASE_REF = "origin/fix/playback-source-errors"
OUT_BRANCH = "rebuild/playback-source-followup"
MOBILE = Path("app/src/main/java/com/streamflixreborn/streamflix/fragments/player/PlayerMobileFragment.kt")
TV = Path("app/src/main/java/com/streamflixreborn/streamflix/fragments/player/PlayerTvFragment.kt")
FILES = (MOBILE, TV)


def run(*args):
    subprocess.run(args, check=True)


def read(path):
    return path.read_text()


def write(path, text):
    path.write_text(text)


def replace(path, old, new, expected=1):
    text = read(path)
    count = text.count(old)
    if count != expected:
        raise RuntimeError(f"{path}: expected {expected} occurrences, found {count}: {old[:120]!r}")
    write(path, text.replace(old, new, expected))


def sub(path, pattern, repl, expected=1, flags=re.MULTILINE | re.DOTALL):
    text = read(path)
    text, count = re.subn(pattern, repl, text, count=expected, flags=flags)
    if count != expected:
        raise RuntimeError(f"{path}: expected {expected} regex replacements, found {count}: {pattern[:140]!r}")
    write(path, text)


def commit(message):
    run("git", "add", *(str(path) for path in FILES))
    if subprocess.run(["git", "diff", "--cached", "--quiet"]).returncode == 0:
        raise RuntimeError(f"empty commit: {message}")
    run("git", "commit", "-m", message)


run("git", "checkout", "-B", OUT_BRANCH, BASE_REF)

# 1. Prefer the exact ID/name pair selected by the source menu. Some providers can reuse IDs.
for path in FILES:
    sub(
        path,
        r'(?P<i>[ \t]*)binding\.settings\.setOnServerSelectedListener \{ server ->\n(?P=i)    state\.servers\.find \{ server\.id == it\.id \}\n(?P=i)        \?\.let\(viewModel::selectVideo\)\n(?P=i)\}',
        lambda m: (
            f'{m.group("i")}binding.settings.setOnServerSelectedListener {{ server ->\n'
            f'{m.group("i")}    val selectedServer = state.servers.firstOrNull {{\n'
            f'{m.group("i")}        it.id == server.id && it.name == server.name\n'
            f'{m.group("i")}    }} ?: state.servers.firstOrNull {{ it.id == server.id }}\n'
            f'{m.group("i")}    selectedServer?.let(viewModel::selectVideo)\n'
            f'{m.group("i")}}}'
        ),
    )
commit("fix: select the exact playback server from settings")

# 2. A player can report the same fatal error more than once before asynchronous recovery finishes.
#    Serialize that recovery so duplicate callbacks cannot cancel the retry and skip servers.
for path in FILES:
    replace(
        path,
        "    private var pendingPlaybackPositionMs: Long? = null\n",
        "    private var pendingPlaybackPositionMs: Long? = null\n    private var playbackSourceRecoveryInProgress = false\n",
    )

    tag = "PlayerMobileFragment" if path == MOBILE else "PlayerTvFragment"
    sub(
        path,
        rf'(?P<i>[ \t]*)override fun onPlayerError\(error: PlaybackException\) \{{\n(?P=i)    super\.onPlayerError\(error\)\n(?P=i)    Log\.e\("{tag}", "onPlayerError: ", error\)\n\n(?P=i)    if \(viewModel\.retryVideoAfterPlaybackError\(currentServer\)\) \{{',
        lambda m: (
            f'{m.group("i")}override fun onPlayerError(error: PlaybackException) {{\n'
            f'{m.group("i")}    super.onPlayerError(error)\n'
            f'{m.group("i")}    Log.e("{tag}", "onPlayerError: ", error)\n\n'
            f'{m.group("i")}    if (playbackSourceRecoveryInProgress) {{\n'
            f'{m.group("i")}        Log.d("{tag}", "Ignoring duplicate playback error during source recovery")\n'
            f'{m.group("i")}        return\n'
            f'{m.group("i")}    }}\n'
            f'{m.group("i")}    playbackSourceRecoveryInProgress = true\n\n'
            f'{m.group("i")}    if (viewModel.retryVideoAfterPlaybackError(currentServer)) {{'
        ),
        flags=re.MULTILINE,
    )

    # Reset only after a replacement source has actually been installed.
    success_call = (
        "                        displayVideo(state.video, state.server, resumePosition)\n"
        if path == MOBILE else
        "                            displayVideo(state.video, state.server, startPositionMs = resumePosition)\n"
    )
    replace(path, success_call, success_call + ("                        playbackSourceRecoveryInProgress = false\n" if path == MOBILE else "                            playbackSourceRecoveryInProgress = false\n"))

    tag = "PlayerMobileFragment" if path == MOBILE else "PlayerTvFragment"
    replace(
        path,
        f'        error?.let {{ Log.e("{tag}", "Playback unavailable", it) }}\n        pendingPlaybackPositionMs = null\n',
        f'        error?.let {{ Log.e("{tag}", "Playback unavailable", it) }}\n        playbackSourceRecoveryInProgress = false\n        pendingPlaybackPositionMs = null\n',
    )
commit("fix: serialize playback source recovery")

# 3. Switching/recovering a source must not force a paused player to start playing.
for path in FILES:
    replace(
        path,
        "    private var pendingPlaybackPositionMs: Long? = null\n    private var playbackSourceRecoveryInProgress = false\n",
        "    private var pendingPlaybackPositionMs: Long? = null\n    private var pendingPlaybackShouldPlay: Boolean? = null\n    private var playbackSourceRecoveryInProgress = false\n",
    )

    replace(
        path,
        "                                pendingPlaybackPositionMs = player.currentPosition\n",
        "                                pendingPlaybackPositionMs = player.currentPosition\n                                pendingPlaybackShouldPlay = player.playWhenReady\n",
    )

    tag = "PlayerMobileFragment" if path == MOBILE else "PlayerTvFragment"
    replace(
        path,
        f'        playbackSourceRecoveryInProgress = false\n        pendingPlaybackPositionMs = null\n        Toast.makeText(\n',
        f'        playbackSourceRecoveryInProgress = false\n        pendingPlaybackPositionMs = null\n        pendingPlaybackShouldPlay = null\n        Toast.makeText(\n',
    )

# Mobile success state and display function.
replace(
    MOBILE,
    "                        val resumePosition = pendingPlaybackPositionMs\n                        pendingPlaybackPositionMs = null\n                        displayVideo(state.video, state.server, resumePosition)\n",
    "                        val resumePosition = pendingPlaybackPositionMs\n                        val shouldPlay = pendingPlaybackShouldPlay ?: true\n                        pendingPlaybackPositionMs = null\n                        pendingPlaybackShouldPlay = null\n                        displayVideo(state.video, state.server, resumePosition, shouldPlay)\n",
)
replace(
    MOBILE,
    "    private fun displayVideo(video: Video, server: Video.Server, startPositionMs: Long? = null) {\n",
    "    private fun displayVideo(\n        video: Video,\n        server: Video.Server,\n        startPositionMs: Long? = null,\n        shouldPlay: Boolean = true,\n    ) {\n",
)
sub(
    MOBILE,
    r'(\n        player\.prepare\(\)\n)        player\.play\(\)\n(?=    \}\n\n    private fun enterPIPMode)',
    r'\1        player.playWhenReady = shouldPlay\n',
    flags=re.MULTILINE,
)

# TV already has a shouldPlay parameter; feed it the captured source-switch state.
replace(
    TV,
    "                            val resumePosition = pendingPlaybackPositionMs\n                            pendingPlaybackPositionMs = null\n                            displayVideo(state.video, state.server, startPositionMs = resumePosition)\n",
    "                            val resumePosition = pendingPlaybackPositionMs\n                            val shouldPlay = pendingPlaybackShouldPlay ?: true\n                            pendingPlaybackPositionMs = null\n                            pendingPlaybackShouldPlay = null\n                            displayVideo(\n                                video = state.video,\n                                server = state.server,\n                                startPositionMs = resumePosition,\n                                shouldPlay = shouldPlay,\n                            )\n",
)
commit("fix: preserve playback state across source changes")

print("Follow-up commits:")
run("git", "log", "--oneline", "--reverse", f"{BASE_REF}..HEAD")
print("HEAD=" + subprocess.check_output(["git", "rev-parse", "HEAD"], text=True).strip())
