#!/usr/bin/env python3
from pathlib import Path
import re
import subprocess

BASE = "4aaa3ac34b5ca20251176958d9437fdcc67569d6"
FINAL_REF = "origin/fix/playback-source-errors"
OUT_BRANCH = "rebuild/playback-source-errors"

MOBILE = Path("app/src/main/java/com/streamflixreborn/streamflix/fragments/player/PlayerMobileFragment.kt")
TV = Path("app/src/main/java/com/streamflixreborn/streamflix/fragments/player/PlayerTvFragment.kt")
VM = Path("app/src/main/java/com/streamflixreborn/streamflix/fragments/player/PlayerViewModel.kt")
FILES = [MOBILE, TV, VM]


def run(*args):
    subprocess.run(args, check=True)


def output(*args):
    return subprocess.check_output(args, text=True).strip()


def read(path):
    return path.read_text()


def write(path, text):
    path.write_text(text)


def replace(path, old, new, expected=1):
    text = read(path)
    count = text.count(old)
    if count != expected:
        raise RuntimeError(f"{path}: expected {expected} occurrences, found {count}: {old[:100]!r}")
    write(path, text.replace(old, new, expected))


def sub(path, pattern, repl, expected=1, flags=re.MULTILINE | re.DOTALL):
    text = read(path)
    text, count = re.subn(pattern, repl, text, count=expected, flags=flags)
    if count != expected:
        raise RuntimeError(f"{path}: expected {expected} regex replacements, found {count}: {pattern[:120]!r}")
    write(path, text)


def commit(message):
    run("git", "add", *(str(p) for p in FILES))
    if subprocess.run(["git", "diff", "--cached", "--quiet"]).returncode == 0:
        raise RuntimeError(f"empty commit: {message}")
    run("git", "commit", "-m", message)


final_sha = output("git", "rev-parse", FINAL_REF)
run("git", "checkout", "-B", OUT_BRANCH, BASE)

# 1. Playback/source failures must never be diagnosed as TMDB language availability.
for path in (MOBILE, TV):
    replace(path, "import java.util.Locale\n", "")

    sub(
        path,
        r'^(?P<i>[ \t]*)val providerName = UserPreferences\.currentProvider\?\.name \?: ""\n(?P=i)val isTmdb = .*?\n(?P=i)val isAD = .*?\n\n(?P=i)if \(servers\.isEmpty\(\)\) \{.*?^(?P=i)\}\n\n(?=(?P=i)player\.playlistMetadata)',
        "",
    )

    sub(
        path,
        r'^(?P<i>[ \t]*)is PlayerViewModel\.State\.FailedLoadingServers -> \{\n.*?^(?P=i)    findNavController\(\)\.navigateUp\(\)\n(?P=i)\}',
        lambda m: f'{m.group("i")}is PlayerViewModel.State.FailedLoadingServers -> {{\n{m.group("i")}    showPlaybackUnavailable(state.error)\n{m.group("i")}}}',
    )

    sub(
        path,
        r'^(?P<i>[ \t]*)else \{\n(?P=i)    val providerName = UserPreferences\.currentProvider\?\.name \?: "".*?^(?P=i)    findNavController\(\)\.navigateUp\(\)\n(?P=i)\}',
        lambda m: f'{m.group("i")}else {{\n{m.group("i")}    showPlaybackUnavailable(state.error)\n{m.group("i")}}}',
    )

mobile_helper = '''    private fun showPlaybackUnavailable(error: Exception? = null) {\n        error?.let { Log.e("PlayerMobileFragment", "Playback unavailable", it) }\n        Toast.makeText(\n            requireContext(),\n            getString(R.string.player_retry_later_message),\n            Toast.LENGTH_LONG,\n        ).show()\n        findNavController().navigateUp()\n    }\n\n'''
replace(MOBILE, "    private fun initializeVideo() {\n", mobile_helper + "    private fun initializeVideo() {\n")

tv_helper = '''    private fun showPlaybackUnavailable(error: Exception? = null) {\n        error?.let { Log.e("PlayerTvFragment", "Playback unavailable", it) }\n        Toast.makeText(\n            requireContext(),\n            getString(R.string.player_retry_later_message),\n            Toast.LENGTH_LONG,\n        ).show()\n        findNavController().navigateUp()\n    }\n\n'''
replace(TV, "    private fun handleMediaPrevious(): Boolean {\n", tv_helper + "    private fun handleMediaPrevious(): Boolean {\n")
commit("fix: stop treating playback failures as language availability")

# 2. A stale/unknown settings selection must not crash on !!.
for path in (MOBILE, TV):
    sub(
        path,
        r'^(?P<i>[ \t]*)viewModel\.getVideo\(state\.servers\.find \{ server\.id == it\.id \}!!\)$',
        lambda m: f'{m.group("i")}state.servers.find {{ server.id == it.id }}\n{m.group("i")}    ?.let(viewModel::getVideo)',
        flags=re.MULTILINE,
    )
commit("fix: avoid unsafe player server selection")

# 3. indexOf(-1) + 1 used to restart failover at server 0.
for path in (MOBILE, TV):
    replace(path, "servers.getOrNull(servers.indexOf(state.server) + 1)", "nextServerAfter(state.server)")
    replace(path, "servers.getOrNull(servers.indexOf(currentServer) + 1)", "nextServerAfter(currentServer)")

    helper = '''    private fun nextServerAfter(server: Video.Server?): Video.Server? {\n        if (server == null) return null\n        val index = servers.indexOf(server)\n        return if (index >= 0) servers.getOrNull(index + 1) else null\n    }\n\n'''
    marker = "    private fun showPlaybackUnavailable(error: Exception? = null) {\n"
    replace(path, marker, helper + marker)
commit("fix: stop failed-server fallback from restarting at first source")

# 4. Structurally equal duplicate servers must advance from the exact occurrence.
for path in (MOBILE, TV):
    replace(
        path,
        "        val index = servers.indexOf(server)\n",
        "        val index = servers.indexOfFirst { it === server }\n            .takeIf { it >= 0 }\n            ?: servers.indexOf(server)\n",
    )
commit("fix: preserve duplicate server occurrence during failover")

# 5. Loading a replacement source blanks the MediaItem, so save position first.
for path in (MOBILE, TV):
    replace(
        path,
        "    private var currentServer: Video.Server? = null\n",
        "    private var currentServer: Video.Server? = null\n    private var pendingPlaybackPositionMs: Long? = null\n",
    )

    sub(
        path,
        r'^(?P<i>[ \t]*)is PlayerViewModel\.State\.LoadingVideo -> \{\n(?P=i)    player\.setMediaItem\(',
        lambda m: (
            f'{m.group("i")}is PlayerViewModel.State.LoadingVideo -> {{\n'
            f'{m.group("i")}    if (pendingPlaybackPositionMs == null) {{\n'
            f'{m.group("i")}        val currentUri = player.currentMediaItem?.localConfiguration?.uri?.toString().orEmpty()\n'
            f'{m.group("i")}        if (currentUri.isNotBlank()) {{\n'
            f'{m.group("i")}            pendingPlaybackPositionMs = player.currentPosition\n'
            f'{m.group("i")}        }}\n'
            f'{m.group("i")}    }}\n\n'
            f'{m.group("i")}    player.setMediaItem('
        ),
    )

replace(
    MOBILE,
    "                        displayVideo(state.video, state.server)\n",
    "                        val resumePosition = pendingPlaybackPositionMs\n                        pendingPlaybackPositionMs = null\n                        displayVideo(state.video, state.server, resumePosition)\n",
)
replace(
    TV,
    "                            displayVideo(state.video, state.server)\n",
    "                            val resumePosition = pendingPlaybackPositionMs\n                            pendingPlaybackPositionMs = null\n                            displayVideo(state.video, state.server, startPositionMs = resumePosition)\n",
)
replace(MOBILE, "    private fun displayVideo(video: Video, server: Video.Server) {\n", "    private fun displayVideo(video: Video, server: Video.Server, startPositionMs: Long? = null) {\n")
replace(MOBILE, "        val currentPosition = player.currentPosition\n", "        val currentPosition = startPositionMs ?: player.currentPosition\n")
replace(
    MOBILE,
    "        if (currentPosition == 0L) {\n",
    "        if (startPositionMs != null) {\n            player.seekTo(startPositionMs)\n        } else if (currentPosition == 0L) {\n",
)
for path, tag in ((MOBILE, "PlayerMobileFragment"), (TV, "PlayerTvFragment")):
    replace(
        path,
        f'        error?.let {{ Log.e("{tag}", "Playback unavailable", it) }}\n',
        f'        error?.let {{ Log.e("{tag}", "Playback unavailable", it) }}\n        pendingPlaybackPositionMs = null\n',
    )
commit("fix: preserve playback position during source recovery")

# 6. displayVideo can run repeatedly during failover; attach a listener once per player.
for path in (MOBILE, TV):
    replace(
        path,
        "    private var currentServer: Video.Server? = null\n    private var pendingPlaybackPositionMs: Long? = null\n",
        "    private var currentServer: Video.Server? = null\n    private var listenerPlayer: ExoPlayer? = null\n    private var pendingPlaybackPositionMs: Long? = null\n",
    )

sub(
    MOBILE,
    r'^(?P<i>[ \t]*)player\.addListener\(object : Player\.Listener \{$',
    lambda m: (
        f'{m.group("i")}val shouldAttachListener = listenerPlayer !== player\n'
        f'{m.group("i")}if (shouldAttachListener) listenerPlayer = player\n'
        f'{m.group("i")}if (shouldAttachListener) player.addListener(object : Player.Listener {{'
    ),
    flags=re.MULTILINE,
)
sub(
    TV,
    r'^(?P<i>[ \t]*)player\.addListener\(object : Player\.Listener \{$',
    lambda m: (
        f'{m.group("i")}val shouldAttachListener = listenerPlayer !== player\n'
        f'{m.group("i")}if (shouldAttachListener) listenerPlayer = player\n'
        f'{m.group("i")}if (shouldAttachListener) player.addListener(object : Player.Listener {{'
    ),
    flags=re.MULTILINE,
)
for path in (MOBILE, TV):
    replace(path, "            stopProgressHandler()\n" if path == TV else "        stopProgressHandler()\n",
            ("            stopProgressHandler()\n            listenerPlayer = null\n" if path == TV else "        stopProgressHandler()\n        listenerPlayer = null\n"))
commit("fix: avoid duplicate player listeners after source changes")

# 7. Older async server/source results must not overwrite newer selections.
replace(VM, "import kotlinx.coroutines.Dispatchers\n", "import kotlinx.coroutines.CancellationException\nimport kotlinx.coroutines.CoroutineStart\nimport kotlinx.coroutines.Dispatchers\nimport kotlinx.coroutines.Job\nimport kotlinx.coroutines.ensureActive\n")
replace(VM, "import com.streamflixreborn.streamflix.utils.SubDL\n", "import com.streamflixreborn.streamflix.utils.SubDL\nimport java.util.concurrent.atomic.AtomicReference\n")
replace(
    VM,
    "    val playPreviousOrNextEpisode: SharedFlow<Video.Type.Episode> = _playPreviousOrNextEpisode\n",
    "    val playPreviousOrNextEpisode: SharedFlow<Video.Type.Episode> = _playPreviousOrNextEpisode\n\n    private val activeServerLoad = AtomicReference<Job?>(null)\n    private val activeVideoLoad = AtomicReference<Job?>(null)\n",
)

get_servers_intermediate = '''    private fun getServers(videoType: Video.Type, id: String): Job {\n        activeVideoLoad.getAndSet(null)?.cancel()\n\n        val job = viewModelScope.launch(Dispatchers.IO, start = CoroutineStart.LAZY) {\n            Log.d("PlayerViewModel", "Inizio ricerca server per ID: $id")\n            _state.emit(State.LoadingServers)\n            try {\n                val servers = UserPreferences.currentProvider!!.getServers(id, videoType)\n                ensureActive()\n                if (servers.isEmpty()) throw Exception("No servers found")\n\n                // LOG POTENZIATO: Mostra tutti i server disponibili per il player\n                Log.i("StreamFlixES", "[SERVERS LIST] -> Provider: ${UserPreferences.currentProvider!!.name}")\n                Log.i("StreamFlixES", "[SERVERS LIST] -> Found ${servers.size} servers: ${servers.joinToString { it.name }}")\n\n                Log.d("PlayerViewModel", "Ricerca server completata: ${servers.size} server trovati")\n                _state.emit(State.SuccessLoadingServers(servers))\n            } catch (e: CancellationException) {\n                throw e\n            } catch (e: Exception) {\n                Log.e("PlayerViewModel", "Errore ricerca server: ", e)\n                _state.emit(State.FailedLoadingServers(e))\n            }\n        }\n\n        activeServerLoad.getAndSet(job)?.cancel()\n        lastVideoType = videoType\n        lastId = id\n        job.invokeOnCompletion {\n            activeServerLoad.compareAndSet(job, null)\n        }\n        job.start()\n        return job\n    }\n'''
sub(
    VM,
    r'^    private fun getServers\(videoType: Video\.Type, id: String\) = viewModelScope\.launch\(Dispatchers\.IO\) \{.*?^    \}\n(?=\n    fun getVideo)',
    get_servers_intermediate.rstrip("\n"),
)

get_video_intermediate = '''    fun getVideo(server: Video.Server): Job {\n        val job = viewModelScope.launch(Dispatchers.IO, start = CoroutineStart.LAZY) {\n            Log.d("PlayerViewModel", "Inizio estrazione video dal server: ${server.name}")\n            _state.emit(State.LoadingVideo(server))\n            try {\n                val video = UserPreferences.currentProvider!!.getVideo(server)\n                ensureActive()\n                if (video.source.isEmpty()) throw Exception("No source found")\n\n                // LOGICA SOTTOTITOLI GLOBALE:\n                // Se il provider non ha già impostato un default (es. i "forced" in spagnolo),\n                // allora proviamo ad attivare l'ultimo sottotitolo usato dall'utente.\n                // MA: se siamo su un provider spagnolo e non ci sono forced, non dobbiamo attivare nulla.\n                val currentProviderLang = UserPreferences.currentProvider?.language ?: ""\n                val hasDefaultAlready = video.subtitles.any { it.default }\n\n                if (!hasDefaultAlready && currentProviderLang != "es") {\n                    if (!(video.useServerSubtitleSetting && UserPreferences.serverAutoSubtitlesDisabled)) {\n                        video.subtitles\n                            .firstOrNull { it.label.startsWith(UserPreferences.subtitleName ?: "") }\n                            ?.default = true\n                    }\n                }\n\n                Log.d("PlayerViewModel", "Estrazione video completata con successo")\n                _state.emit(State.SuccessLoadingVideo(video, server))\n            } catch (e: CancellationException) {\n                throw e\n            } catch (e: Exception) {\n                Log.e("PlayerViewModel", "Errore estrazione video: ", e)\n                _state.emit(State.FailedLoadingVideo(e, server))\n            }\n        }\n\n        activeVideoLoad.getAndSet(job)?.cancel()\n        job.invokeOnCompletion {\n            activeVideoLoad.compareAndSet(job, null)\n        }\n        job.start()\n        return job\n    }\n'''
sub(
    VM,
    r'^    fun getVideo\(server: Video\.Server\) = viewModelScope\.launch\(Dispatchers\.IO\) \{.*?^    \}\n(?=\n    fun getSubtitles)',
    get_video_intermediate.rstrip("\n"),
)
commit("fix: cancel stale server and source loads")

# 8. The fragment navigation recreates the player; the old ViewModel must not start a duplicate load first.
replace(VM, "        playEpisode(nextEpisode)\n\n", "")
sub(
    VM,
    r'^    fun playEpisode\(episode: Video\.Type\.Episode\) \{\n        getServers\(episode, episode\.id\)\n        getSubtitles\(episode\)\n    \}\n',
    "",
    flags=re.MULTILINE,
)
commit("fix: avoid duplicate episode source loads during navigation")

# 9. A player error should re-resolve the current server once, then advance, then terminate cleanly.
replace(VM, "import java.util.concurrent.atomic.AtomicReference\n", "import java.util.concurrent.ConcurrentHashMap\nimport java.util.concurrent.atomic.AtomicReference\n")
replace(
    VM,
    "    private val activeServerLoad = AtomicReference<Job?>(null)\n",
    "    private val playbackRetryAttempted = ConcurrentHashMap.newKeySet<Video.Server>()\n    private val activeServerLoad = AtomicReference<Job?>(null)\n",
)
replace(
    VM,
    '            Log.d("PlayerViewModel", "Inizio ricerca server per ID: $id")\n            _state.emit(State.LoadingServers)\n',
    '            Log.d("PlayerViewModel", "Inizio ricerca server per ID: $id")\n            playbackRetryAttempted.clear()\n            _state.emit(State.LoadingServers)\n',
)
retry_methods = '''\n    fun retryVideoAfterPlaybackError(server: Video.Server?): Boolean {\n        if (server == null || !playbackRetryAttempted.add(server)) return false\n        getVideo(server)\n        return true\n    }\n\n    fun selectVideo(server: Video.Server) {\n        playbackRetryAttempted.remove(server)\n        getVideo(server)\n    }\n'''
replace(VM, "\n    fun getSubtitles(videoType: Video.Type) = viewModelScope.launch(Dispatchers.IO) {\n", retry_methods + "\n    fun getSubtitles(videoType: Video.Type) = viewModelScope.launch(Dispatchers.IO) {\n")

for path in (MOBILE, TV):
    replace(path, "?.let(viewModel::getVideo)", "?.let(viewModel::selectVideo)")
    replace(path, "viewModel.getVideo(state.servers.first())", "viewModel.selectVideo(state.servers.first())")
    text = read(path)
    count = text.count("viewModel.getVideo(nextServer)")
    if count != 2:
        raise RuntimeError(f"{path}: expected 2 nextServer getVideo calls, found {count}")
    write(path, text.replace("viewModel.getVideo(nextServer)", "viewModel.selectVideo(nextServer)"))

mobile_old = '''            override fun onPlayerError(error: PlaybackException) {\n                super.onPlayerError(error)\n                Log.e("PlayerMobileFragment", "onPlayerError: ", error)\n                \n                val nextServer = nextServerAfter(currentServer)\n                if (nextServer != null) {\n                    Log.i("PlayerMobileFragment", "Playback failed, trying next server: ${nextServer.name}")\n                    viewModel.selectVideo(nextServer)\n                }\n            }\n'''
mobile_new = '''            override fun onPlayerError(error: PlaybackException) {\n                super.onPlayerError(error)\n                Log.e("PlayerMobileFragment", "onPlayerError: ", error)\n\n                if (viewModel.retryVideoAfterPlaybackError(currentServer)) {\n                    Log.i("PlayerMobileFragment", "Playback failed, retrying current server once")\n                    return\n                }\n\n                val nextServer = nextServerAfter(currentServer)\n                if (nextServer != null) {\n                    Log.i("PlayerMobileFragment", "Playback failed, trying next server: ${nextServer.name}")\n                    viewModel.selectVideo(nextServer)\n                } else {\n                    showPlaybackUnavailable()\n                }\n            }\n'''
replace(MOBILE, mobile_old, mobile_new)

tv_old = '''                override fun onPlayerError(error: PlaybackException) {\n                    super.onPlayerError(error)\n                    Log.e("PlayerTvFragment", "onPlayerError: ", error)\n\n                    val nextServer = nextServerAfter(currentServer)\n                    if (nextServer != null) {\n                        Log.i("PlayerTvFragment", "Playback failed, trying next server: ${nextServer.name}")\n                        viewModel.selectVideo(nextServer)\n                    }\n                }\n'''
tv_new = '''                override fun onPlayerError(error: PlaybackException) {\n                    super.onPlayerError(error)\n                    Log.e("PlayerTvFragment", "onPlayerError: ", error)\n\n                    if (viewModel.retryVideoAfterPlaybackError(currentServer)) {\n                        Log.i("PlayerTvFragment", "Playback failed, retrying current server once")\n                        return\n                    }\n\n                    val nextServer = nextServerAfter(currentServer)\n                    if (nextServer != null) {\n                        Log.i("PlayerTvFragment", "Playback failed, trying next server: ${nextServer.name}")\n                        viewModel.selectVideo(nextServer)\n                    } else {\n                        showPlaybackUnavailable()\n                    }\n                }\n'''
replace(TV, tv_old, tv_new)
commit("fix: retry failed playback source before server failover")

# 10. Empty server lists should produce a deliberate server-load failure rather than an opaque throw.
replace(
    VM,
    '                if (servers.isEmpty()) throw Exception("No servers found")\n',
    '                if (servers.isEmpty()) {\n                    val error = Exception("No streaming servers found for this title.")\n                    Log.w("PlayerViewModel", error.message.orEmpty())\n                    _state.emit(State.FailedLoadingServers(error))\n                    return@launch\n                }\n',
)
replace(
    VM,
    '                _state.emit(State.FailedLoadingServers(e))\n',
    '                _state.emit(\n                    State.FailedLoadingServers(\n                        Exception("Unable to load streaming servers. Please try again later.", e)\n                    )\n                )\n',
)
commit("fix: normalize missing server results as playback failures")

# 11. Whitespace-only URLs are not playable sources and should fail before ExoPlayer setup.
replace(
    VM,
    '                if (video.source.isEmpty()) throw Exception("No source found")\n',
    '                if (video.source.isBlank()) throw Exception("No playable source returned by ${server.name}.")\n',
)
commit("fix: reject blank playback sources before player setup")

# Match the reviewed final file EOF exactly.
vm_text = read(VM)
if vm_text.endswith("\n"):
    write(VM, vm_text[:-1])
    run("git", "add", str(VM))
    run("git", "commit", "--amend", "--no-edit")

# The rebuilt production tree must exactly match the reviewed branch.
for path in FILES:
    expected = output("git", "rev-parse", f"{final_sha}:{path}")
    actual = output("git", "hash-object", str(path))
    if actual != expected:
        run("git", "diff", "--no-index", f"/tmp/does-not-exist-{path.name}", str(path)) if False else None
        raise RuntimeError(f"final blob mismatch for {path}: rebuilt={actual} reviewed={expected}")

print("Rebuilt commits:")
run("git", "log", "--oneline", "--reverse", f"{BASE}..HEAD")
print(f"FINAL_SHA={output('git', 'rev-parse', 'HEAD')}")
