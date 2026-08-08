from pathlib import Path

path = Path('.github/scripts/rebuild_playback_history.py')
text = path.read_text()
old = "r'^(?P<i>[ \\t]*)else \\{\\n(?P=i)    val providerName = UserPreferences\\.currentProvider\\?\\.name \\?: \"\".*?^(?P=i)    findNavController\\(\\)\\.navigateUp\\(\\)\\n(?P=i)\\}'"
new = "r'^(?P<i>[ \\t]*)else \\{\\n[ \\t]*val providerName = UserPreferences\\.currentProvider\\?\\.name \\?: \"\".*?^[ \\t]*findNavController\\(\\)\\.navigateUp\\(\\)\\n(?P=i)\\}'"
if text.count(old) != 1:
    raise RuntimeError(f'expected one pattern to patch, found {text.count(old)}')
path.write_text(text.replace(old, new, 1))
