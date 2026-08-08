from pathlib import Path

path = Path('.github/scripts/rebuild_playback_history.py')
text = path.read_text()

# Kotlin writes the terminal branch as `} else {`, not a standalone `else {` line.
old = "r'^(?P<i>[ \\t]*)else \\{\\n(?P=i)    val providerName = UserPreferences\\.currentProvider\\?\\.name \\?: \"\".*?^(?P=i)    findNavController\\(\\)\\.navigateUp\\(\\)\\n(?P=i)\\}'"
new = "r'^(?P<i>[ \\t]*)\\} else \\{\\n[ \\t]*val providerName = UserPreferences\\.currentProvider\\?\\.name \\?: \"\".*?^[ \\t]*findNavController\\(\\)\\.navigateUp\\(\\)\\n(?P=i)\\}'"
if text.count(old) != 1:
    raise RuntimeError(f'expected one terminal-error pattern to patch, found {text.count(old)}')
text = text.replace(old, new, 1)
text = text.replace(
    "lambda m: f'{m.group(\"i\")}else {{\\n{m.group(\"i\")}    showPlaybackUnavailable(state.error)\\n{m.group(\"i\")}}}',",
    "lambda m: f'{m.group(\"i\")}}} else {{\\n{m.group(\"i\")}    showPlaybackUnavailable(state.error)\\n{m.group(\"i\")}}}',",
    1,
)

# Only change the displayVideo position capture, not unrelated currentPosition locals.
old = 'replace(MOBILE, "        val currentPosition = player.currentPosition\\n", "        val currentPosition = startPositionMs ?: player.currentPosition\\n")'
new = '''sub(\n    MOBILE,\n    r'(^    private fun displayVideo\\(video: Video, server: Video\\.Server, startPositionMs: Long\\? = null\\) \\{.*?^        )val currentPosition = player\\.currentPosition$',\n    r'\\1val currentPosition = startPositionMs ?: player.currentPosition',\n)'''
if text.count(old) != 1:
    raise RuntimeError(f'expected one displayVideo currentPosition edit to patch, found {text.count(old)}')
text = text.replace(old, new, 1)

# Likewise, only change the resume branch immediately after the displayVideo listener.
old = '''replace(\n    MOBILE,\n    "        if (currentPosition == 0L) {\\n",\n    "        if (startPositionMs != null) {\\n            player.seekTo(startPositionMs)\\n        } else if (currentPosition == 0L) {\\n",\n)'''
new = '''replace(\n    MOBILE,\n    "        })\\n\\n        if (currentPosition == 0L) {\\n",\n    "        })\\n\\n        if (startPositionMs != null) {\\n            player.seekTo(startPositionMs)\\n        } else if (currentPosition == 0L) {\\n",\n)'''
if text.count(old) != 1:
    raise RuntimeError(f'expected one displayVideo seek edit to patch, found {text.count(old)}')
text = text.replace(old, new, 1)

# Reset listener tracking specifically inside releasePlayer, not other stopProgressHandler calls.
old = '''for path in (MOBILE, TV):\n    replace(path, "            stopProgressHandler()\\n" if path == TV else "        stopProgressHandler()\\n",\n            ("            stopProgressHandler()\\n            listenerPlayer = null\\n" if path == TV else "        stopProgressHandler()\\n        listenerPlayer = null\\n"))'''
new = '''for path in (MOBILE, TV):\n    sub(\n        path,\n        r'^(?P<i>[ \\t]*)private fun releasePlayer\\(\\) \\{\\n(?P=i)    stopProgressHandler\\(\\)\\n',\n        lambda m: f'{m.group("i")}private fun releasePlayer() {{\\n{m.group("i")}    stopProgressHandler()\\n{m.group("i")}    listenerPlayer = null\\n',\n        flags=re.MULTILINE,\n    )'''
if text.count(old) != 1:
    raise RuntimeError(f'expected one releasePlayer edit to patch, found {text.count(old)}')
text = text.replace(old, new, 1)

path.write_text(text)
