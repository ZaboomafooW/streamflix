from pathlib import Path

ROOT = Path('.')


def read(path: str) -> str:
    return (ROOT / path).read_text()


def write(path: str, text: str) -> None:
    (ROOT / path).write_text(text)


def replace_once(path: str, old: str, new: str) -> None:
    text = read(path)
    count = text.count(old)
    if count != 1:
        raise SystemExit(f"{path}: expected exactly one match, found {count}: {old[:100]!r}")
    write(path, text.replace(old, new, 1))


prefs = 'app/src/main/java/com/streamflixreborn/streamflix/utils/PlaybackTrackPreferences.kt'

# A normal language choice supersedes any older source-specific special/anonymous
# Audio track saved for this title. Otherwise returning to an old source can resurrect
# a stale commentary/Track 1 choice after the user has explicitly returned to Original.
replace_once(
    prefs,
    'savedAudio?.takeIf(::audioExactApplies)?.let { saved ->',
    'savedAudio?.let { saved ->',
)
replace_once(
    prefs,
    '''        clearAudioExact()\n\n        if (\n            originalAudioLanguage != null &&''',
    '''        clearAllAudioExactForContent()\n\n        if (\n            originalAudioLanguage != null &&''',
)
replace_once(
    prefs,
    '''    private fun audioExactApplies(saved: SavedTrack): Boolean {\n        val titleLanguage = titleAudioLanguage ?: return true\n        val savedLanguage = canonicalLanguage(saved.language) ?: languageFromLabel(saved.label)\n        return savedLanguage != null && languageMatches(savedLanguage, titleLanguage)\n    }\n\n''',
    '',
)
replace_once(
    prefs,
    '''    private fun clearAudioExact() {\n        val scope = scopeKey ?: return\n        savedAudio = null\n        prefs.edit().remove(audioKey(scope)).apply()\n    }\n''',
    '''    private fun clearAllAudioExactForContent() {\n        val contentKey = contentPreferenceKey ?: return\n        val prefix = audioKey(contentKey)\n        val editor = prefs.edit()\n        prefs.all.keys\n            .filter { key -> key.startsWith(prefix) }\n            .forEach(editor::remove)\n        editor.apply()\n        savedAudio = null\n    }\n''',
)

# Subtitle Off is a native Media3 disabled text-track state. Retain recognition of the
# previous ignored-selection-flags representation only as an upgrade/runtime bridge.
text = read(prefs)
count = text.count('.setIgnoredTextSelectionFlags(SUBTITLE_OFF_FLAGS)')
if count != 2:
    raise SystemExit(f'{prefs}: expected 2 legacy subtitle Off applications, found {count}')
text = text.replace(
    '.setIgnoredTextSelectionFlags(SUBTITLE_OFF_FLAGS)',
    '.setIgnoredTextSelectionFlags(DEFAULT_TEXT_FLAGS)',
)
write(prefs, text)
replace_once(
    prefs,
    '''    private fun isSubtitleOff(parameters: TrackSelectionParameters) =\n        parameters.ignoredTextSelectionFlags == SUBTITLE_OFF_FLAGS\n''',
    '''    private fun isSubtitleOff(parameters: TrackSelectionParameters) =\n        parameters.disabledTrackTypes.contains(C.TRACK_TYPE_TEXT) ||\n            parameters.ignoredTextSelectionFlags == LEGACY_SUBTITLE_OFF_FLAGS\n''',
)
replace_once(
    prefs,
    '    private const val SUBTITLE_OFF_FLAGS = C.SELECTION_FLAG_FORCED.inv()\n',
    '    private const val LEGACY_SUBTITLE_OFF_FLAGS = C.SELECTION_FLAG_FORCED.inv()\n',
)

# Make the player's None action use the same native Media3 state, and remove writes to
# the retired subtitleName preference. Downloaded/local subtitle choices remain playback-only.
settings_view = 'app/src/main/java/com/streamflixreborn/streamflix/fragments/player/settings/PlayerSettingsView.kt'
replace_once(
    settings_view,
    '''                    player.trackSelectionParameters = player.trackSelectionParameters\n                        .buildUpon()\n                        .clearOverridesOfType(C.TRACK_TYPE_TEXT)\n                        .setIgnoredTextSelectionFlags(C.SELECTION_FLAG_FORCED.inv())\n                        .build()\n                    UserPreferences.subtitleName = null\n''',
    '''                    player.trackSelectionParameters = player.trackSelectionParameters\n                        .buildUpon()\n                        .clearOverridesOfType(C.TRACK_TYPE_TEXT)\n                        .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)\n                        .setIgnoredTextSelectionFlags(0)\n                        .build()\n''',
)
replace_once(
    settings_view,
    '                    UserPreferences.subtitleName = (subtitle.language ?: subtitle.label).substringBefore(" ")\n',
    '',
)

for player_file in [
    'app/src/main/java/com/streamflixreborn/streamflix/fragments/player/PlayerTvFragment.kt',
    'app/src/main/java/com/streamflixreborn/streamflix/fragments/player/PlayerMobileFragment.kt',
]:
    lines = read(player_file).splitlines(keepends=True)
    output = []
    i = 0
    removed = 0
    while i < len(lines):
        if 'UserPreferences.subtitleName =' not in lines[i]:
            output.append(lines[i])
            i += 1
            continue

        removed += 1
        if '.substringBefore(' in lines[i]:
            i += 1
            continue

        # Multi-line assignment: discard through the line containing substringBefore(...).
        i += 1
        while i < len(lines):
            end = '.substringBefore(' in lines[i]
            i += 1
            if end:
                break

    if removed != 2:
        raise SystemExit(f'{player_file}: expected 2 subtitleName writes, removed {removed}')
    write(player_file, ''.join(output))

# Remove the obsolete negative VOE/Vidnest/Vidzy subtitle-default preference and its
# dead model plumbing. Server default metadata is now preserved for Media3 to consider.
user_prefs = 'app/src/main/java/com/streamflixreborn/streamflix/utils/UserPreferences.kt'
replace_once(
    user_prefs,
    '''    var serverAutoSubtitlesDisabled: Boolean\n        get() = Key.SERVER_AUTO_SUBTITLES_DISABLED.getBoolean() ?: true\n        set(value) {\n            Key.SERVER_AUTO_SUBTITLES_DISABLED.setBoolean(value)\n        }\n\n''',
    '',
)
replace_once(
    user_prefs,
    '''    /**\n     * Compatibility no-op for old call sites. Subtitle memory is now entirely\n     * handled by PlaybackTrackPreferences and is never stored globally.\n     */\n    var subtitleName: String?\n        get() = null\n        set(value) {\n            // Deliberately ignored.\n        }\n\n''',
    '',
)
replace_once(user_prefs, '        SERVER_AUTO_SUBTITLES_DISABLED,\n', '')

settings_block = '''        findPreference<SwitchPreference>("SERVER_AUTO_SUBTITLES_DISABLED")?.apply {\n            isChecked = UserPreferences.serverAutoSubtitlesDisabled\n            setOnPreferenceChangeListener { _, newValue ->\n                UserPreferences.serverAutoSubtitlesDisabled = newValue as Boolean\n                true\n            }\n        }\n\n'''
settings_refresh = '        findPreference<SwitchPreference>("SERVER_AUTO_SUBTITLES_DISABLED")?.isChecked = UserPreferences.serverAutoSubtitlesDisabled\n'
settings_tv = 'app/src/main/java/com/streamflixreborn/streamflix/fragments/settings/SettingsTvFragment.kt'
settings_mobile = 'app/src/main/java/com/streamflixreborn/streamflix/fragments/settings/SettingsMobileFragment.kt'
replace_once(settings_tv, settings_block, '')
replace_once(settings_tv, settings_refresh, '')
replace_once(settings_mobile, settings_block, '')

video = 'app/src/main/java/com/streamflixreborn/streamflix/models/Video.kt'
replace_once(video, '    val useServerSubtitleSetting: Boolean = false,\n', '')

# originalLanguage is intentionally not a Room column, so preserve it explicitly through
# TV-show -> season -> episode navigation and EpisodeManager reconstruction.
season_holder = 'app/src/main/java/com/streamflixreborn/streamflix/adapters/viewholders/SeasonViewHolder.kt'
text = read(season_holder)
needle = '                        tvShowBanner = season.tvShow?.banner,\n'
if text.count(needle) != 2:
    raise SystemExit(f'{season_holder}: expected two season navigation calls, found {text.count(needle)}')
write(
    season_holder,
    text.replace(
        needle,
        needle + '                        tvShowOriginalLanguage = season.tvShow?.originalLanguage,\n',
    ),
)

for nav in [
    'app/src/main/res/navigation/nav_main_graph_tv.xml',
    'app/src/main/res/navigation/nav_main_graph_mobile.xml',
]:
    replace_once(
        nav,
        '''        <argument\n            android:name="tvShowBanner"\n            app:argType="string"\n            app:nullable="true" />\n        <argument\n            android:name="seasonId"''',
        '''        <argument\n            android:name="tvShowBanner"\n            app:argType="string"\n            app:nullable="true" />\n        <argument\n            android:name="tvShowOriginalLanguage"\n            app:argType="string"\n            app:nullable="true" />\n        <argument\n            android:name="seasonId"''',
    )

for fragment in [
    'app/src/main/java/com/streamflixreborn/streamflix/fragments/season/SeasonTvFragment.kt',
    'app/src/main/java/com/streamflixreborn/streamflix/fragments/season/SeasonMobileFragment.kt',
]:
    replace_once(
        fragment,
        '            args.tvShowId,\n',
        '            args.tvShowId,\n            args.tvShowOriginalLanguage,\n',
    )

season_vm = 'app/src/main/java/com/streamflixreborn/streamflix/fragments/season/SeasonViewModel.kt'
replace_once(
    season_vm,
    '''class SeasonViewModel(\n    seasonId: String,\n    private val tvShowId: String,\n    private val database: AppDatabase,\n)''',
    '''class SeasonViewModel(\n    seasonId: String,\n    private val tvShowId: String,\n    private val tvShowOriginalLanguage: String?,\n    private val database: AppDatabase,\n)''',
)
replace_once(
    season_vm,
    '''                    }.sortedBy { it.number }.onEach {\n                        it.tvShow = tvShow\n                        it.season = season\n                    }''',
    '''                    }.sortedBy { it.number }.onEach { episode ->\n                        episode.tvShow = tvShow?.copy(\n                            originalLanguage = episode.tvShow?.originalLanguage\n                                ?: tvShowOriginalLanguage,\n                        ) ?: episode.tvShow\n                        episode.season = season\n                    }''',
)
replace_once(
    season_vm,
    '            val tvShow = TvShow(tvShowId)\n',
    '            val tvShow = TvShow(tvShowId, originalLanguage = tvShowOriginalLanguage)\n',
)
replace_once(
    season_vm,
    '            EpisodeManager.addEpisodes(EpisodeManager.convertToVideoTypeEpisodes(episodes, database, seasonNumber))\n',
    '''            EpisodeManager.addEpisodes(\n                EpisodeManager.convertToVideoTypeEpisodes(\n                    episodes,\n                    database,\n                    seasonNumber,\n                    tvShowOriginalLanguage,\n                )\n            )\n''',
)

episode_manager = 'app/src/main/java/com/streamflixreborn/streamflix/utils/EpisodeManager.kt'
replace_once(
    episode_manager,
    '''                imdbId = storedTvShow.imdbId\n            )''',
    '''                imdbId = storedTvShow.imdbId,\n                originalLanguage = type.tvShow.originalLanguage,\n            )''',
)
replace_once(
    episode_manager,
    '''            imdbId = type.tvShow.imdbId\n        )''',
    '''            imdbId = type.tvShow.imdbId,\n            originalLanguage = type.tvShow.originalLanguage,\n        )''',
)
replace_once(
    episode_manager,
    '            addEpisodes(convertToVideoTypeEpisodes(episodesFromDb, database, seasonNumber))\n',
    '''            addEpisodes(\n                convertToVideoTypeEpisodes(\n                    episodesFromDb,\n                    database,\n                    seasonNumber,\n                    type.tvShow.originalLanguage,\n                )\n            )\n''',
)
replace_once(
    episode_manager,
    '        mergeEpisodes(convertToVideoTypeEpisodes(nextSeasonEpisodes, database, seasonToLoad.number))\n',
    '''        mergeEpisodes(\n            convertToVideoTypeEpisodes(\n                nextSeasonEpisodes,\n                database,\n                seasonToLoad.number,\n                currentEpisode.tvShow.originalLanguage,\n            )\n        )\n''',
)
replace_once(
    episode_manager,
    '    fun convertToVideoTypeEpisodes(episodes: List<com.streamflixreborn.streamflix.models.Episode>, database: AppDatabase, seasonNumber: Int): List<Episode> {',
    '''    fun convertToVideoTypeEpisodes(\n        episodes: List<com.streamflixreborn.streamflix.models.Episode>,\n        database: AppDatabase,\n        seasonNumber: Int,\n        originalLanguage: String? = null,\n    ): List<Episode> {''',
)
replace_once(
    episode_manager,
    '''                    imdbId = tvShowFromDb?.imdbId ?: ep.tvShow?.imdbId\n                ),''',
    '''                    imdbId = tvShowFromDb?.imdbId ?: ep.tvShow?.imdbId,\n                    originalLanguage = ep.tvShow?.originalLanguage ?: originalLanguage,\n                ),''',
)

# Final source-level sanity checks for systems that should now be completely retired.
for token in [
    'UserPreferences.subtitleName',
    'serverAutoSubtitlesDisabled',
    'SERVER_AUTO_SUBTITLES_DISABLED',
    'useServerSubtitleSetting',
    'audioExactApplies',
]:
    hits = []
    for file in Path('app/src').rglob('*'):
        if not file.is_file() or file.suffix not in {'.kt', '.xml'}:
            continue
        try:
            if token in file.read_text():
                hits.append(str(file))
        except UnicodeDecodeError:
            pass
    if hits:
        raise SystemExit(f'retired token {token!r} still present in: {hits}')
