from pathlib import Path

path = Path('.github/scripts/rebuild_playback_history.py')
text = path.read_text()

# Remove the two blank lines left in TV when the old provider-language block is deleted.
marker = 'commit("fix: stop treating playback failures as language availability")'
insertion = '''tv_text = read(TV)\nneedle = "                        }\\n\\n\\n\\n                        player.playlistMetadata"\nif tv_text.count(needle) > 1:\n    raise RuntimeError("unexpected repeated TV spacing pattern before playlist metadata")\nif needle in tv_text:\n    write(TV, tv_text.replace(needle, "                        }\\n\\n                        player.playlistMetadata", 1))\ncommit("fix: stop treating playback failures as language availability")'''
if text.count(marker) != 1:
    raise RuntimeError(f'expected one language-error commit marker, found {text.count(marker)}')
text = text.replace(marker, insertion, 1)

# Preserve the reviewed method/field separators inside the stale-load commit.
marker = 'commit("fix: cancel stale server and source loads")'
insertion = '''vm_text = read(VM)\nfor needle, replacement in (\n    ("    private val activeVideoLoad = AtomicReference<Job?>(null)\\n    init {", "    private val activeVideoLoad = AtomicReference<Job?>(null)\\n\\n    init {"),\n    ("        return job\\n    }\\n    fun getVideo(server: Video.Server): Job {", "        return job\\n    }\\n\\n    fun getVideo(server: Video.Server): Job {"),\n):\n    count = vm_text.count(needle)\n    if count != 1:\n        raise RuntimeError(f"expected one ViewModel separator pattern, found {count}: {needle!r}")\n    vm_text = vm_text.replace(needle, replacement, 1)\nwrite(VM, vm_text)\ncommit("fix: cancel stale server and source loads")'''
if text.count(marker) != 1:
    raise RuntimeError(f'expected one stale-load commit marker, found {text.count(marker)}')
text = text.replace(marker, insertion, 1)

# Keep a separator between getVideo() and the retry API introduced by the retry commit.
marker = 'commit("fix: retry failed playback source before server failover")'
insertion = '''vm_text = read(VM)\nneedle = "        return job\\n    }\\n    fun retryVideoAfterPlaybackError(server: Video.Server?): Boolean {"\ncount = vm_text.count(needle)\nif count != 1:\n    raise RuntimeError(f"expected one retry separator pattern, found {count}")\nwrite(VM, vm_text.replace(needle, "        return job\\n    }\\n\\n    fun retryVideoAfterPlaybackError(server: Video.Server?): Boolean {", 1))\ncommit("fix: retry failed playback source before server failover")'''
if text.count(marker) != 1:
    raise RuntimeError(f'expected one retry commit marker, found {text.count(marker)}')
text = text.replace(marker, insertion, 1)

path.write_text(text)
