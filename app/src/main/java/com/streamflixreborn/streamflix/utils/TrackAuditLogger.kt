package com.streamflixreborn.streamflix.utils

import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.ui.DefaultTrackNameProvider
import com.streamflixreborn.streamflix.StreamFlixApp
import com.streamflixreborn.streamflix.models.Video
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStream
import java.net.ServerSocket
import java.net.Socket
import kotlin.concurrent.thread

/**
 * Temporary validation logger for the track-preference branch.
 *
 * It intentionally records the final Media3 view of tracks for every title/server that is opened,
 * plus source-specific raw HLS diagnostics when an extractor exposes them. Records are persisted
 * across provider switches and app restarts until explicitly cleared.
 */
object TrackAuditLogger {

    private const val FILE_NAME = "streamflix-track-audit.json"
    private const val SCHEMA_VERSION = 1

    private data class ContentContext(
        val provider: String,
        val providerLanguage: String?,
        val contentId: String,
        val contentTitle: String,
        val contentType: String,
        val originalLanguage: String?,
    )

    private val lock = Any()

    @Volatile
    private var currentContent: ContentContext? = null

    @Volatile
    private var currentServer: Video.Server? = null

    private val app
        get() = StreamFlixApp.instance

    private val auditFile
        get() = app.filesDir.resolve(FILE_NAME)

    fun beginContent(videoType: Video.Type, originalLanguage: String?) {
        val provider = UserPreferences.currentProvider
        currentContent = ContentContext(
            provider = provider?.name ?: "unknown",
            providerLanguage = provider?.language,
            contentId = when (videoType) {
                is Video.Type.Movie -> videoType.id
                is Video.Type.Episode -> videoType.id
            },
            contentTitle = when (videoType) {
                is Video.Type.Movie -> videoType.title
                is Video.Type.Episode -> buildString {
                    append(videoType.tvShow.title)
                    append(" • S${videoType.season.number} E${videoType.number}")
                    videoType.title?.takeIf { it.isNotBlank() }?.let { append(" • $it") }
                }
            },
            contentType = when (videoType) {
                is Video.Type.Movie -> "movie"
                is Video.Type.Episode -> "episode"
            },
            originalLanguage = originalLanguage,
        )
        currentServer = null
    }

    fun recordServers(servers: List<Video.Server>) {
        val content = currentContent ?: return
        val entry = baseEntry(content, "servers|${content.provider}|${content.contentId}", "servers_discovered")
            .put(
                "servers",
                JSONArray().apply {
                    servers.forEach { server ->
                        put(
                            JSONObject()
                                .put("id", server.id)
                                .put("name", server.name)
                                .putNullable("sourceHost", sourceHost(server.src))
                        )
                    }
                },
            )
        upsert(entry)
    }

    fun recordServerFailure(error: Throwable) {
        val content = currentContent ?: return
        val entry = baseEntry(content, "servers|${content.provider}|${content.contentId}", "server_lookup_failed")
            .put("error", errorSummary(error))
        upsert(entry)
    }

    fun beginServer(server: Video.Server) {
        currentServer = server
    }

    fun recordExtractionFailure(server: Video.Server, error: Throwable) {
        currentServer = server
        val content = currentContent ?: return
        val key = "extract|${content.provider}|${content.contentId}|${server.id}"
        val entry = baseEntry(content, key, "extraction_failed")
            .put("serverId", server.id)
            .put("serverName", server.name)
            .putNullable("sourceHost", sourceHost(server.src))
            .put("error", errorSummary(error))
        upsert(entry)
    }

    fun recordTracks(player: Player) {
        val content = currentContent ?: return
        val tracks = player.currentTracks
        if (tracks.isEmpty) return

        val hasRelevantTracks = tracks.groups.any {
            it.type == C.TRACK_TYPE_AUDIO || it.type == C.TRACK_TYPE_TEXT
        }
        if (!hasRelevantTracks) return

        val server = currentServer
        val serverId = server?.id ?: "unknown"
        val serverName = server?.name ?: "unknown"
        val key = "tracks|${content.provider}|${content.contentId}|$serverId"
        val trackNameProvider = DefaultTrackNameProvider(app.resources)
        val parameters = player.trackSelectionParameters

        fun tracksOfType(type: Int): JSONArray = JSONArray().apply {
            var ordinal = 0
            tracks.groups.forEachIndexed { groupIndex, group ->
                if (group.type != type) return@forEachIndexed
                for (trackIndex in 0 until group.length) {
                    ordinal++
                    val format = group.getTrackFormat(trackIndex)
                    put(
                        JSONObject()
                            .put("ordinal", ordinal)
                            .put("groupIndex", groupIndex)
                            .put("trackIndex", trackIndex)
                            .putNullable("media3Name", trackNameProvider.getTrackName(format))
                            .putNullable("id", format.id)
                            .putNullable("label", format.label)
                            .putNullable("language", format.language)
                            .putNullable("mime", format.sampleMimeType)
                            .putNullable("codecs", format.codecs)
                            .put("selectionFlags", format.selectionFlags)
                            .put("isDefault", format.selectionFlags and C.SELECTION_FLAG_DEFAULT != 0)
                            .put("isForced", format.selectionFlags and C.SELECTION_FLAG_FORCED != 0)
                            .put("roleFlags", format.roleFlags)
                            .put("selected", group.isTrackSelected(trackIndex))
                            .put("supported", group.isTrackSupported(trackIndex))
                    )
                }
            }
        }

        val entry = baseEntry(content, key, "tracks_loaded")
            .put("serverId", serverId)
            .put("serverName", serverName)
            .putNullable("sourceHost", sourceHost(server?.src))
            .put("preferredAudioLanguages", JSONArray(parameters.preferredAudioLanguages))
            .put("preferredTextLanguages", JSONArray(parameters.preferredTextLanguages))
            .put("textTrackDisabled", parameters.disabledTrackTypes.contains(C.TRACK_TYPE_TEXT))
            .put("ignoredTextSelectionFlags", parameters.ignoredTextSelectionFlags)
            .put("audioTracks", tracksOfType(C.TRACK_TYPE_AUDIO))
            .put("textTracks", tracksOfType(C.TRACK_TYPE_TEXT))

        SubtitleDebugState.snapshot()
            ?.takeIf { snapshot -> serverName.contains(snapshot.source, ignoreCase = true) }
            ?.let { snapshot ->
                entry.put(
                    "sourceDiagnostics",
                    JSONObject()
                        .put("source", snapshot.source)
                        .putNullable("requestedLanguage", snapshot.preferredLanguage)
                        .put("rawAudio", JSONArray(snapshot.rawAudioLines))
                        .put("rawSubtitles", JSONArray(snapshot.rawSubtitleLines))
                        .put("normalizedAudio", JSONArray(snapshot.patchedAudioLines))
                        .put("normalizedSubtitles", JSONArray(snapshot.patchedSubtitleLines)),
                )
            }

        upsert(entry)
    }

    fun entryCount(): Int = synchronized(lock) {
        loadRoot().optJSONArray("entries")?.length() ?: 0
    }

    fun clear() = synchronized(lock) {
        if (auditFile.exists()) auditFile.delete()
    }

    fun exportFile() = synchronized(lock) {
        if (!auditFile.exists()) {
            saveRoot(newRoot())
        }
        auditFile
    }

    fun startExportServer(): ExportSession? {
        val address = BypassWebSocketEndpointHelper.getLocalIpv4Address() ?: return null
        val socket = runCatching { ServerSocket(0) }.getOrNull() ?: return null
        val url = "http://$address:${socket.localPort}/$FILE_NAME"
        return ExportSession(socket, url)
    }

    class ExportSession internal constructor(
        private val serverSocket: ServerSocket,
        val url: String,
    ) {
        @Volatile
        private var running = true

        private val worker = thread(
            name = "StreamFlixTrackAuditExport",
            isDaemon = true,
        ) {
            while (running && !serverSocket.isClosed) {
                val client = runCatching { serverSocket.accept() }.getOrNull() ?: break
                runCatching { serve(client) }
            }
        }

        private fun serve(client: Socket) {
            client.use { socket ->
                val reader = BufferedReader(InputStreamReader(socket.getInputStream(), Charsets.UTF_8))
                reader.readLine() ?: return
                while (true) {
                    val line = reader.readLine() ?: break
                    if (line.isBlank()) break
                }

                val bytes = TrackAuditLogger.exportFile().readBytes()
                val output = socket.getOutputStream()
                writeHeaders(output, bytes.size)
                output.write(bytes)
                output.flush()
            }
        }

        private fun writeHeaders(output: OutputStream, length: Int) {
            val headers = buildString {
                append("HTTP/1.1 200 OK\r\n")
                append("Content-Type: application/json; charset=utf-8\r\n")
                append("Content-Disposition: attachment; filename=\"$FILE_NAME\"\r\n")
                append("Content-Length: $length\r\n")
                append("Cache-Control: no-store\r\n")
                append("Connection: close\r\n")
                append("\r\n")
            }
            output.write(headers.toByteArray(Charsets.UTF_8))
        }

        fun stop() {
            running = false
            runCatching { serverSocket.close() }
            runCatching { worker.interrupt() }
        }
    }

    private fun baseEntry(
        content: ContentContext,
        key: String,
        status: String,
    ) = JSONObject()
        .put("key", key)
        .put("status", status)
        .put("recordedAt", System.currentTimeMillis())
        .put("provider", content.provider)
        .putNullable("providerLanguage", content.providerLanguage)
        .put("contentId", content.contentId)
        .put("contentTitle", content.contentTitle)
        .put("contentType", content.contentType)
        .putNullable("originalLanguage", content.originalLanguage)

    private fun upsert(entry: JSONObject) = synchronized(lock) {
        val root = loadRoot()
        val entries = root.optJSONArray("entries") ?: JSONArray().also { root.put("entries", it) }
        val key = entry.getString("key")
        var existingIndex = -1
        for (index in 0 until entries.length()) {
            if (entries.optJSONObject(index)?.optString("key") == key) {
                existingIndex = index
                break
            }
        }

        if (existingIndex >= 0) entries.put(existingIndex, entry) else entries.put(entry)
        root.put("schemaVersion", SCHEMA_VERSION)
        root.put("updatedAt", System.currentTimeMillis())
        saveRoot(root)
    }

    private fun loadRoot(): JSONObject {
        if (!auditFile.exists()) return newRoot()
        return runCatching { JSONObject(auditFile.readText()) }.getOrElse { newRoot() }
    }

    private fun newRoot() = JSONObject()
        .put("schemaVersion", SCHEMA_VERSION)
        .put("createdAt", System.currentTimeMillis())
        .put("updatedAt", System.currentTimeMillis())
        .put("entries", JSONArray())

    private fun saveRoot(root: JSONObject) {
        auditFile.parentFile?.mkdirs()
        auditFile.writeText(root.toString(2))
    }

    private fun sourceHost(value: String?): String? {
        val source = value?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        if (source.startsWith("data:", ignoreCase = true)) return "inline-data"
        return runCatching { java.net.URI(source).host }.getOrNull()
            ?: source.substringBefore('?').take(120)
    }

    private fun errorSummary(error: Throwable): String = buildString {
        append(error::class.java.simpleName)
        error.message?.takeIf { it.isNotBlank() }?.let { append(": $it") }
    }

    private fun JSONObject.putNullable(key: String, value: String?): JSONObject =
        put(key, value ?: JSONObject.NULL)
}
