from pathlib import Path

path = Path('.github/scripts/rebuild_playback_history.py')
text = path.read_text()
marker = 'commit("fix: stop treating playback failures as language availability")'
insertion = '''tv_text = read(TV)\nneedle = "                        }\\n\\n\\n\\n                        player.playlistMetadata"\nif tv_text.count(needle) > 1:\n    raise RuntimeError("unexpected repeated TV spacing pattern before playlist metadata")\nif needle in tv_text:\n    write(TV, tv_text.replace(needle, "                        }\\n\\n                        player.playlistMetadata", 1))\ncommit("fix: stop treating playback failures as language availability")'''
if text.count(marker) != 1:
    raise RuntimeError(f'expected one language-error commit marker, found {text.count(marker)}')
path.write_text(text.replace(marker, insertion, 1))
