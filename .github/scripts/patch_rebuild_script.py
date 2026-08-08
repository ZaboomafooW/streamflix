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

# Match the reviewed spacing for the mobile playback-unavailable helper.
old = 'findNavController().navigateUp()\\n    }\\n\\n\'\'\''
new = 'findNavController().navigateUp()\\n    }\\n\\n\\n\'\'\''
if text.count(old) < 1:
    raise RuntimeError('mobile helper terminator not found')
text = text.replace(old, new, 1)

# FailedLoadingServers and LoadingVideo are adjacent in the reviewed branch.
old = 'commit("fix: stop treating playback failures as language availability")'
new = '''for path in (MOBILE, TV):\n    sub(\n        path,\n        r'(^[ \\t]*is PlayerViewModel\\.State\\.FailedLoadingServers -> \\{\\n[ \\t]*showPlaybackUnavailable\\(state\\.error\\)\\n[ \\t]*\\})\\n\\n(?=[ \\t]*is PlayerViewModel\\.State\\.LoadingVideo ->)',\n        r'\\1\\n',\n        flags=re.MULTILINE,\n    )\ncommit("fix: stop treating playback failures as language availability")'''
if text.count(old) != 1:
    raise RuntimeError('language-error commit marker not found')
text = text.replace(old, new, 1)

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

# Keep one blank line before nextServerAfter, matching the reviewed files.
old = 'commit("fix: stop failed-server fallback from restarting at first source")'
new = '''for path in (MOBILE, TV):\n    replace(path, "\\n\\n\\n    private fun nextServerAfter", "\\n\\n    private fun nextServerAfter")\ncommit("fix: stop failed-server fallback from restarting at first source")'''
if text.count(old) != 1:
    raise RuntimeError('failover commit marker not found')
text = text.replace(old, new, 1)

# Reset listener tracking specifically inside releasePlayer, not other stopProgressHandler calls.
old = '''for path in (MOBILE, TV):\n    replace(path, "            stopProgressHandler()\\n" if path == TV else "        stopProgressHandler()\\n",\n            ("            stopProgressHandler()\\n            listenerPlayer = null\\n" if path == TV else "        stopProgressHandler()\\n        listenerPlayer = null\\n"))'''
new = '''for path in (MOBILE, TV):\n    sub(\n        path,\n        r'^(?P<i>[ \\t]*)private fun releasePlayer\\(\\) \\{\\n(?P=i)    stopProgressHandler\\(\\)\\n',\n        lambda m: f'{m.group("i")}private fun releasePlayer() {{\\n{m.group("i")}    stopProgressHandler()\\n{m.group("i")}    listenerPlayer = null\\n',\n        flags=re.MULTILINE,\n    )'''
if text.count(old) != 1:
    raise RuntimeError(f'expected one releasePlayer edit to patch, found {text.count(old)}')
text = text.replace(old, new, 1)

# Mobile intentionally keeps a separating blank line before listener setup.
old = 'commit("fix: avoid duplicate player listeners after source changes")'
new = '''replace(MOBILE, "        }\\n        val shouldAttachListener = listenerPlayer !== player\\n", "        }\\n\\n        val shouldAttachListener = listenerPlayer !== player\\n")\ncommit("fix: avoid duplicate player listeners after source changes")'''
if text.count(old) != 1:
    raise RuntimeError('listener commit marker not found')
text = text.replace(old, new, 1)

# On a final blob mismatch, print the real diff before failing the safety gate.
old = '''        run("git", "diff", "--no-index", f"/tmp/does-not-exist-{path.name}", str(path)) if False else None\n        raise RuntimeError(f"final blob mismatch for {path}: rebuilt={actual} reviewed={expected}")'''
new = '''        subprocess.run(["git", "diff", final_sha, "--", str(path)], check=False)\n        raise RuntimeError(f"final blob mismatch for {path}: rebuilt={actual} reviewed={expected}")'''
if text.count(old) != 1:
    raise RuntimeError(f'expected one mismatch diagnostic to patch, found {text.count(old)}')
text = text.replace(old, new, 1)

path.write_text(text)
