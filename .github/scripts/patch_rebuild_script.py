from pathlib import Path

path = Path('.github/scripts/rebuild_playback_history.py')
text = path.read_text()
old = "r'^(?P<i>[ \\t]*)else \\{\\n(?P=i)    val providerName = UserPreferences\\.currentProvider\\?\\.name \\?: \"\".*?^(?P=i)    findNavController\\(\\)\\.navigateUp\\(\\)\\n(?P=i)\\}'"
new = "r'^(?P<i>[ \\t]*)\\} else \\{\\n[ \\t]*val providerName = UserPreferences\\.currentProvider\\?\\.name \\?: \"\".*?^[ \\t]*findNavController\\(\\)\\.navigateUp\\(\\)\\n(?P=i)\\}'"
if text.count(old) != 1:
    raise RuntimeError(f'expected one pattern to patch, found {text.count(old)}')
text = text.replace(old, new, 1)
text = text.replace(
    "lambda m: f'{m.group(\"i\")}else {{\\n{m.group(\"i\")}    showPlaybackUnavailable(state.error)\\n{m.group(\"i\")}}}',",
    "lambda m: f'{m.group(\"i\")}}} else {{\\n{m.group(\"i\")}    showPlaybackUnavailable(state.error)\\n{m.group(\"i\")}}}',",
    1,
)
path.write_text(text)
