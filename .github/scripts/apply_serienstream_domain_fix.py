from pathlib import Path

FILES = [
    Path("app/src/main/java/com/streamflixreborn/streamflix/fragments/player/PlayerMobileFragment.kt"),
    Path("app/src/main/java/com/streamflixreborn/streamflix/fragments/player/PlayerTvFragment.kt"),
]

old = '''    private fun isSerienStreamBypassUrl(url: String): Boolean {
        return runCatching {
            Uri.parse(url).host.equals("serienstream.to", ignoreCase = true)
        }.getOrDefault(false)
    }
'''

new = '''    private fun isSerienStreamBypassUrl(url: String): Boolean {
        if (UserPreferences.currentProvider != SerienStreamProvider) return false
        val configuredHost = runCatching {
            Uri.parse(SerienStreamProvider.baseUrl).host
        }.getOrNull()
        if (configuredHost.isNullOrBlank()) return false

        return runCatching {
            Uri.parse(url).host.equals(configuredHost, ignoreCase = true)
        }.getOrDefault(false)
    }
'''

for path in FILES:
    text = path.read_text()
    count = text.count(old)
    if count != 1:
        raise RuntimeError(f"Expected one SerienStream bypass detector in {path}, found {count}")
    path.write_text(text.replace(old, new, 1))
