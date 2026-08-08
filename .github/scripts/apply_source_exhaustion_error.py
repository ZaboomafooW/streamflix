from pathlib import Path

MOBILE = Path("app/src/main/java/com/streamflixreborn/streamflix/fragments/player/PlayerMobileFragment.kt")
TV = Path("app/src/main/java/com/streamflixreborn/streamflix/fragments/player/PlayerTvFragment.kt")
STRINGS = Path("app/src/main/res/values/strings.xml")


def replace_once(path: Path, old: str, new: str, label: str) -> None:
    text = path.read_text()
    count = text.count(old)
    if count != 1:
        raise RuntimeError(f"{label}: expected one match, found {count}")
    path.write_text(text.replace(old, new, 1))


for path in (MOBILE, TV):
    replace_once(
        path,
        "messageRes: Int = R.string.player_retry_later_message,",
        "messageRes: Int = R.string.player_no_working_source_message,",
        f"{path.name} exhausted source default",
    )

replace_once(
    STRINGS,
    '''    <string name="player_sources_load_failed_message">Unable to load playback sources. Please try again.</string>
''',
    '''    <string name="player_sources_load_failed_message">Unable to load playback sources. Please try again.</string>
    <string name="player_no_working_source_message">No working playback source is currently available.</string>
''',
    "exhausted source string",
)
