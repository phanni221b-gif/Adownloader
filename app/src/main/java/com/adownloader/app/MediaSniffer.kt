package com.adownloader.app

import android.os.Handler
import android.os.Looper
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.util.Collections
import java.util.LinkedHashMap
import java.util.concurrent.Executors

class MediaSniffer(private val onChanged: (List<MediaCandidate>) -> Unit) {
    private val lock = Any()
    private val candidates = LinkedHashMap<String, MediaCandidate>()
    private val probed = Collections.synchronizedSet(mutableSetOf<String>())
    private val probeExecutor = Executors.newFixedThreadPool(3)
    private val main = Handler(Looper.getMainLooper())

    fun clear() {
        synchronized(lock) { candidates.clear() }
        notifyChanged()
    }

    fun snapshot(): List<MediaCandidate> = synchronized(lock) { candidates.values.toList() }

    fun looksLikeMedia(url: String): Boolean = classify(url, null) != MediaCandidate.MediaType.UNKNOWN

    fun observeRequest(request: WebResourceRequest, pageUrl: String?) {
        val url = request.url.toString()
        val headers = request.requestHeaders.toMutableMap()
        pageUrl?.let { headers.putIfAbsent("Referer", it) }
        CookieManager.getInstance().getCookie(url)?.let { headers.putIfAbsent("Cookie", it) }
        observe(url, null, "network", pageUrl, headers)
    }

    fun observeServiceWorker(request: WebResourceRequest) {
        val url = request.url.toString()
        val headers = request.requestHeaders.toMutableMap()
        CookieManager.getInstance().getCookie(url)?.let { headers.putIfAbsent("Cookie", it) }
        observe(url, null, "service-worker", null, headers)
    }

    fun observeJs(raw: String, sourceOrigin: String?) {
        val obj = runCatching { JSONObject(raw) }.getOrNull() ?: return
        val url = obj.optString("url").trim()
        if (url.isBlank()) return
        val kind = obj.optString("kind", "js")
        val page = obj.optString("page").ifBlank { sourceOrigin }
        val headers = mutableMapOf<String, String>()
        page?.let { headers["Referer"] = it }
        CookieManager.getInstance().getCookie(url)?.let { headers["Cookie"] = it }
        observe(url, null, "js:$kind", page, headers)
    }

    fun observeDownload(url: String, mimeType: String?, pageUrl: String?) {
        val headers = mutableMapOf<String, String>()
        pageUrl?.let { headers["Referer"] = it }
        CookieManager.getInstance().getCookie(url)?.let { headers["Cookie"] = it }
        observe(url, mimeType, "download-listener", pageUrl, headers)
    }

    private fun observe(
        url: String,
        mimeType: String?,
        source: String,
        pageUrl: String?,
        headers: Map<String, String>
    ) {
        if (!url.startsWith("http://") && !url.startsWith("https://")) return
        val type = classify(url, mimeType)
        if (type != MediaCandidate.MediaType.UNKNOWN) {
            add(MediaCandidate(url, type, source, pageUrl, headers))
            return
        }
        if (shouldProbe(url, source)) probe(url, source, pageUrl, headers)
    }

    private fun add(candidate: MediaCandidate) {
        var changed = false
        synchronized(lock) {
            val previous = candidates[candidate.url]
            if (previous == null || previous.type == MediaCandidate.MediaType.UNKNOWN) {
                candidates[candidate.url] = candidate
                changed = true
            }
        }
        if (changed) notifyChanged()
    }

    private fun notifyChanged() {
        val copy = snapshot()
        main.post { onChanged(copy) }
    }

    private fun shouldProbe(url: String, source: String): Boolean {
        if (!probed.add(url)) return false
        val lower = url.lowercase()
        val hint = listOf("manifest", "playlist", "master", "stream", "video", "media", "play", "source")
            .any { lower.contains(it) }
        return source.startsWith("js:") || hint
    }

    private fun probe(url: String, source: String, pageUrl: String?, headers: Map<String, String>) {
        probeExecutor.execute {
            var conn: HttpURLConnection? = null
            try {
                conn = URL(url).openConnection() as HttpURLConnection
                conn.instanceFollowRedirects = true
                conn.connectTimeout = 5000
                conn.readTimeout = 5000
                conn.requestMethod = "GET"
                conn.setRequestProperty("Range", "bytes=0-4095")
                headers.forEach { (k, v) -> if (v.isNotBlank()) conn.setRequestProperty(k, v) }
                conn.connect()
                val mime = conn.contentType
                val typeFromMime = classify(url, mime)
                if (typeFromMime != MediaCandidate.MediaType.UNKNOWN) {
                    add(MediaCandidate(conn.url.toString(), typeFromMime, "$source:probe", pageUrl, headers))
                    return@execute
                }
                val bytes = conn.inputStream.use { input ->
                    val buffer = ByteArray(4096)
                    val n = input.read(buffer)
                    if (n > 0) buffer.copyOf(n) else ByteArray(0)
                }
                val signature = classifyBytes(bytes)
                if (signature != MediaCandidate.MediaType.UNKNOWN) {
                    add(MediaCandidate(conn.url.toString(), signature, "$source:signature", pageUrl, headers))
                }
            } catch (_: Throwable) {
            } finally {
                conn?.disconnect()
            }
        }
    }

    private fun classifyBytes(bytes: ByteArray): MediaCandidate.MediaType {
        if (bytes.isEmpty()) return MediaCandidate.MediaType.UNKNOWN
        val text = bytes.toString(Charsets.UTF_8).trimStart()
        if (text.startsWith("#EXTM3U")) return MediaCandidate.MediaType.HLS
        if (text.contains("<MPD") || text.contains("urn:mpeg:dash")) return MediaCandidate.MediaType.DASH
        if (bytes.size >= 12) {
            val ascii = bytes.copyOfRange(4, 12).toString(Charsets.ISO_8859_1)
            if (ascii.contains("ftyp")) return MediaCandidate.MediaType.VIDEO
        }
        if (bytes.size >= 4 && bytes[0] == 0x1A.toByte() && bytes[1] == 0x45.toByte() && bytes[2] == 0xDF.toByte() && bytes[3] == 0xA3.toByte()) {
            return MediaCandidate.MediaType.VIDEO
        }
        return MediaCandidate.MediaType.UNKNOWN
    }

    private fun classify(url: String, mimeType: String?): MediaCandidate.MediaType {
        val lowerMime = mimeType?.lowercase().orEmpty()
        if (lowerMime.contains("mpegurl") || lowerMime.contains("vnd.apple.mpegurl")) return MediaCandidate.MediaType.HLS
        if (lowerMime.contains("dash+xml")) return MediaCandidate.MediaType.DASH
        if (lowerMime.startsWith("video/")) return MediaCandidate.MediaType.VIDEO
        if (lowerMime.startsWith("audio/")) return MediaCandidate.MediaType.AUDIO

        val cleanPath = runCatching { URI(url).path?.lowercase().orEmpty() }.getOrDefault(url.lowercase())
        return when {
            cleanPath.endsWith(".m3u8") || cleanPath.endsWith(".m3u") -> MediaCandidate.MediaType.HLS
            cleanPath.endsWith(".mpd") -> MediaCandidate.MediaType.DASH
            listOf(".mp4", ".m4v", ".webm", ".mov", ".mkv", ".avi", ".ts", ".m2ts").any { cleanPath.endsWith(it) } -> MediaCandidate.MediaType.VIDEO
            listOf(".mp3", ".m4a", ".aac", ".ogg", ".opus").any { cleanPath.endsWith(it) } -> MediaCandidate.MediaType.AUDIO
            else -> MediaCandidate.MediaType.UNKNOWN
        }
    }
}
