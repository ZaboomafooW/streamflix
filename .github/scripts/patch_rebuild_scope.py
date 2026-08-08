from pathlib import Path

path = Path('.github/scripts/rebuild_playback_history.py')
text = path.read_text()

# The missing-server and duplicate-equal-server cases are one failover progression bug.
old = 'commit("fix: stop failed-server fallback from restarting at first source")'
if text.count(old) != 1:
    raise RuntimeError(f'expected one early failover commit, found {text.count(old)}')
text = text.replace(old, '', 1)
old = 'commit("fix: preserve duplicate server occurrence during failover")'
if text.count(old) != 1:
    raise RuntimeError(f'expected one duplicate-server commit, found {text.count(old)}')
text = text.replace(old, 'commit("fix: preserve server progression during failover")', 1)

# Episode navigation is adjacent behavior, not a proven playback-source defect. Keep upstream behavior.
start_marker = '# 8. The fragment navigation recreates the player; the old ViewModel must not start a duplicate load first.\n'
end_marker = 'commit("fix: avoid duplicate episode source loads during navigation")\n\n'
start = text.find(start_marker)
end = text.find(end_marker, start)
if start < 0 or end < 0:
    raise RuntimeError('episode-navigation history block not found exactly once')
end += len(end_marker)
text = text[:start] + text[end:]

# The expected final tree is the reviewed playback tree, except that upstream episode-loading behavior is restored.
old = '''# The rebuilt production tree must exactly match the reviewed branch.\nfor path in FILES:\n    expected = output("git", "rev-parse", f"{final_sha}:{path}")\n    actual = output("git", "hash-object", str(path))\n    if actual != expected:\n        subprocess.run(["git", "diff", final_sha, "--", str(path)], check=False)\n        raise RuntimeError(f"final blob mismatch for {path}: rebuilt={actual} reviewed={expected}")'''
new = '''# Mobile/TV must exactly match the reviewed tree. ViewModel must match it with upstream episode-loading behavior restored.\nfor path in (MOBILE, TV):\n    expected = output("git", "rev-parse", f"{final_sha}:{path}")\n    actual = output("git", "hash-object", str(path))\n    if actual != expected:\n        subprocess.run(["git", "diff", final_sha, "--", str(path)], check=False)\n        raise RuntimeError(f"final blob mismatch for {path}: rebuilt={actual} reviewed={expected}")\n\nreviewed_vm = subprocess.check_output(["git", "show", f"{final_sha}:{VM}"], text=True)\nreviewed_vm = reviewed_vm.replace(\n    "        viewModelScope.launch {\\n            _playPreviousOrNextEpisode.emit(nextEpisode)\\n",\n    "        playEpisode(nextEpisode)\\n\\n        viewModelScope.launch {\\n            _playPreviousOrNextEpisode.emit(nextEpisode)\\n",\n    1,\n)\nreviewed_vm = reviewed_vm.replace(\n    "    private fun getServers(videoType: Video.Type, id: String): Job {\\n",\n    "    fun playEpisode(episode: Video.Type.Episode) {\\n        getServers(episode, episode.id)\\n        getSubtitles(episode)\\n    }\\n\\n    private fun getServers(videoType: Video.Type, id: String): Job {\\n",\n    1,\n)\nactual_vm = read(VM)\nif actual_vm != reviewed_vm:\n    Path("/tmp/expected-PlayerViewModel.kt").write_text(reviewed_vm)\n    subprocess.run(["git", "diff", "--no-index", "/tmp/expected-PlayerViewModel.kt", str(VM)], check=False)\n    raise RuntimeError("final PlayerViewModel differs from reviewed playback code plus restored upstream episode behavior")'''
if text.count(old) != 1:
    raise RuntimeError(f'expected one final-tree validation block, found {text.count(old)}')
text = text.replace(old, new, 1)

path.write_text(text)
