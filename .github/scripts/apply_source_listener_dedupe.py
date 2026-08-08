from pathlib import Path

MOBILE = Path("app/src/main/java/com/streamflixreborn/streamflix/fragments/player/PlayerMobileFragment.kt")
TV = Path("app/src/main/java/com/streamflixreborn/streamflix/fragments/player/PlayerTvFragment.kt")


def r(path: Path, old: str, new: str, label: str) -> None:
    text = path.read_text()
    count = text.count(old)
    if count != 1:
        raise RuntimeError(f"{label}: expected one match, found {count}")
    path.write_text(text.replace(old, new, 1))


r(
    MOBILE,
    "    private var currentServer: Video.Server? = null\n",
    "    private var currentServer: Video.Server? = null\n    private var listenerPlayer: ExoPlayer? = null\n",
    "mobile listener field",
)
r(
    MOBILE,
    "        player.addListener(object : Player.Listener {\n",
    "        val shouldAttachListener = listenerPlayer !== player\n        if (shouldAttachListener) listenerPlayer = player\n        if (shouldAttachListener) player.addListener(object : Player.Listener {\n",
    "mobile listener attachment",
)
r(
    MOBILE,
    "        stopProgressHandler()\n        binding.pvPlayer.player = null\n",
    "        stopProgressHandler()\n        listenerPlayer = null\n        binding.pvPlayer.player = null\n",
    "mobile listener release",
)

r(
    TV,
    "    private var currentServer: Video.Server? = null\n",
    "    private var currentServer: Video.Server? = null\n    private var listenerPlayer: ExoPlayer? = null\n",
    "tv listener field",
)
r(
    TV,
    "            player.addListener(object : Player.Listener {\n",
    "            val shouldAttachListener = listenerPlayer !== player\n            if (shouldAttachListener) listenerPlayer = player\n            if (shouldAttachListener) player.addListener(object : Player.Listener {\n",
    "tv listener attachment",
)
r(
    TV,
    "            stopProgressHandler()\n            binding.pvPlayer.player = null\n",
    "            stopProgressHandler()\n            listenerPlayer = null\n            binding.pvPlayer.player = null\n",
    "tv listener release",
)
