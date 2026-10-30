package com.adownloader.app

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ContentValues
import android.content.Intent
import android.media.MediaExtractor
import android.media.MediaMuxer
import android.net.Uri
import android.os.Environment
import android.os.IBinder
import android.provider.MediaStore
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.nio.ByteBuffer
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

class DownloadService : Service() {
    companion object {
        const val ACTION_DOWNLOAD = "com.adownloader.app.DOWNLOAD"
        const val EXTRA_URL = "url"
        const val EXTRA_TYPE = "type"
        const val EXTRA_HEADERS = "headers"
        const val EXTRA_FILENAME = "filename"
        private const val CHANNEL_ID = "adownloader_downloads"
        private val nextId = AtomicInteger(4000)
    }

    private val executor = Executors.newFixedThreadPool(2)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action != ACTION_DOWNLOAD) return START_NOT_STICKY
        val url = intent.getStringExtra(EXTRA_URL) ?: return START_NOT_STICKY
        val type = intent.getStringExtra(EXTRA_TYPE) ?: MediaCandidate.MediaType.UNKNOWN.name
        val headersJson = intent.getStringExtra(EXTRA_HEADERS).orEmpty()
        val requestedName = intent.getStringExtra(EXTRA_FILENAME)
        val notificationId = nextId.getAndIncrement()

        startForeground(notificationId, buildNotification("다운로드 준비 중", 0, true))
        executor.execute {
            try {
                val headers = jsonToMap(headersJson)
                val resultUri = if (type == MediaCandidate.MediaType.HLS.name) {
                    downloadHls(url, headers, requestedName, notificationId)
                } else {
                    downloadDirect(url, headers, requestedName, notificationId)
                }
                notifyDone(notificationId, resultUri)
            } catch (t: Throwable) {
                notifyFailed(notificationId, t.message ?: t.javaClass.simpleName)
            } finally {
                stopSelf(startId)
            }
        }
        return START_NOT_STICKY
    }

    private fun createChannel() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Adownloader 다운로드", NotificationManager.IMPORTANCE_LOW)
        )
    }

    private fun buildNotification(text: String, progress: Int, indeterminate: Boolean) =
        android.app.Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle("Adownloader")
            .setContentText(text)
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .setProgress(100, progress.coerceIn(0, 100), indeterminate)
            .build()

    private fun updateProgress(id: Int, text: String, progress: Int, indeterminate: Boolean = false) {
        getSystemService(NotificationManager::class.java).notify(id, buildNotification(text, progress, indeterminate))
    }

    private fun notifyDone(id: Int, uri: Uri) {
        val openIntent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "video/*")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        val pending = PendingIntent.getActivity(
            this,
            id,
            Intent.createChooser(openIntent, "영상 열기"),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification = android.app.Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle("다운로드 완료")
            .setContentText("탭해서 영상 열기")
            .setContentIntent(pending)
            .setAutoCancel(true)
            .build()
        getSystemService(NotificationManager::class.java).notify(id, notification)
    }

    private fun notifyFailed(id: Int, reason: String) {
        val notification = android.app.Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_notify_error)
            .setContentTitle("다운로드 실패")
            .setContentText(reason.take(120))
            .setAutoCancel(true)
            .build()
        getSystemService(NotificationManager::class.java).notify(id, notification)
    }

    private fun downloadDirect(
        url: String,
        headers: Map<String, String>,
        requestedName: String?,
        notificationId: Int
    ): Uri {
        val temp = File.createTempFile("adownloader_", ".part", cacheDir)
        var conn: HttpURLConnection? = null
        try {
            conn = openConnection(url, headers)
            val total = conn.contentLengthLong
            val mime = conn.contentType?.substringBefore(';') ?: "video/mp4"
            val name = requestedName?.takeIf { it.isNotBlank() }
                ?: fileNameFromConnection(conn, url, mime)
            BufferedInputStream(conn.inputStream, 128 * 1024).use { input ->
                BufferedOutputStream(FileOutputStream(temp), 128 * 1024).use { output ->
                    val buffer = ByteArray(128 * 1024)
                    var done = 0L
                    while (true) {
                        val n = input.read(buffer)
                        if (n < 0) break
                        output.write(buffer, 0, n)
                        done += n
                        if (total > 0) {
                            val p = ((done * 100) / total).toInt()
                            updateProgress(notificationId, "다운로드 중 · $p%", p)
                        }
                    }
                }
            }
            return publishVideo(temp, ensureExtension(name, mime), mime)
        } finally {
            conn?.disconnect()
            temp.delete()
        }
    }

    private fun downloadHls(
        entryUrl: String,
        headers: Map<String, String>,
        requestedName: String?,
        notificationId: Int
    ): Uri {
        updateProgress(notificationId, "HLS 목록 확인 중", 0, true)
        val masterText = fetchText(entryUrl, headers)
        val mediaUrl = chooseBestVariant(entryUrl, masterText)
        val playlistText = if (mediaUrl == entryUrl) masterText else fetchText(mediaUrl, headers)
        val playlist = parseMediaPlaylist(mediaUrl, playlistText)
        if (playlist.segments.isEmpty()) error("HLS 세그먼트를 찾지 못했습니다")
        if (playlist.unsupportedEncryption) error("DRM/SAMPLE-AES 스트림은 저장할 수 없습니다")

        val ext = if (playlist.initSegment != null || playlist.segments.any { it.url.substringBefore('?').endsWith(".m4s", true) }) ".mp4" else ".ts"
        val joined = File.createTempFile("adownloader_hls_", ext, cacheDir)
        try {
            BufferedOutputStream(FileOutputStream(joined), 256 * 1024).use { output ->
                playlist.initSegment?.let { init ->
                    fetchBytes(init, headers).also { output.write(it) }
                }
                playlist.segments.forEachIndexed { index, segment ->
                    val raw = fetchBytes(segment.url, headers)
                    val bytes = segment.key?.let { key -> decryptAes128(raw, key, segment.sequence, headers) } ?: raw
                    output.write(bytes)
                    val p = (((index + 1) * 100L) / playlist.segments.size).toInt()
                    updateProgress(notificationId, "HLS 조각 ${index + 1}/${playlist.segments.size}", p)
                }
            }

            val baseName = sanitizeName(requestedName?.takeIf { it.isNotBlank() } ?: "Adownloader_${System.currentTimeMillis()}")
            if (ext == ".mp4") {
                return publishVideo(joined, ensureExtension(baseName, "video/mp4"), "video/mp4")
            }

            updateProgress(notificationId, "MP4로 안정화 중", 100, true)
            val remuxed = File.createTempFile("adownloader_remux_", ".mp4", cacheDir)
            return try {
                remuxToMp4(joined, remuxed)
                publishVideo(remuxed, ensureExtension(baseName, "video/mp4"), "video/mp4")
            } catch (_: Throwable) {
                publishVideo(joined, ensureExtension(baseName, "video/mp2t"), "video/mp2t")
            } finally {
                remuxed.delete()
            }
        } finally {
            joined.delete()
        }
    }

    private data class HlsKey(val uri: String, val ivHex: String?)
    private data class HlsSegment(val url: String, val key: HlsKey?, val sequence: Long)
    private data class HlsPlaylist(
        val initSegment: String?,
        val segments: List<HlsSegment>,
        val unsupportedEncryption: Boolean
    )

    private fun chooseBestVariant(baseUrl: String, text: String): String {
        if (!text.contains("#EXT-X-STREAM-INF")) return baseUrl
        val lines = text.lines()
        var bestUrl: String? = null
        var bestBandwidth = -1L
        for (i in lines.indices) {
            val line = lines[i].trim()
            if (!line.startsWith("#EXT-X-STREAM-INF:")) continue
            val bw = Regex("BANDWIDTH=(\\d+)").find(line)?.groupValues?.getOrNull(1)?.toLongOrNull() ?: 0L
            val next = lines.drop(i + 1).firstOrNull { it.trim().isNotBlank() && !it.trim().startsWith("#") }?.trim() ?: continue
            if (bw >= bestBandwidth) {
                bestBandwidth = bw
                bestUrl = resolve(baseUrl, next)
            }
        }
        return bestUrl ?: baseUrl
    }

    private fun parseMediaPlaylist(baseUrl: String, text: String): HlsPlaylist {
        var mediaSequence = Regex("#EXT-X-MEDIA-SEQUENCE:(\\d+)").find(text)?.groupValues?.getOrNull(1)?.toLongOrNull() ?: 0L
        var currentKey: HlsKey? = null
        var unsupported = false
        var init: String? = null
        val segments = mutableListOf<HlsSegment>()

        for (raw in text.lines()) {
            val line = raw.trim()
            when {
                line.startsWith("#EXT-X-MAP:") -> {
                    val uri = Regex("URI=\"([^\"]+)\"").find(line)?.groupValues?.getOrNull(1)
                    if (uri != null) init = resolve(baseUrl, uri)
                }
                line.startsWith("#EXT-X-KEY:") -> {
                    val method = Regex("METHOD=([^,]+)").find(line)?.groupValues?.getOrNull(1)?.uppercase() ?: "NONE"
                    when (method) {
                        "NONE" -> currentKey = null
                        "AES-128" -> {
                            val uri = Regex("URI=\"([^\"]+)\"").find(line)?.groupValues?.getOrNull(1)
                            val iv = Regex("IV=([^,]+)").find(line)?.groupValues?.getOrNull(1)
                            if (uri != null) currentKey = HlsKey(resolve(baseUrl, uri), iv)
                        }
                        else -> unsupported = true
                    }
                }
                line.isNotBlank() && !line.startsWith("#") -> {
                    segments += HlsSegment(resolve(baseUrl, line), currentKey, mediaSequence)
                    mediaSequence++
                }
            }
        }
        return HlsPlaylist(init, segments, unsupported)
    }

    private fun decryptAes128(data: ByteArray, key: HlsKey, sequence: Long, headers: Map<String, String>): ByteArray {
        val keyBytes = fetchBytes(key.uri, headers)
        if (keyBytes.size != 16) error("AES-128 키 길이가 올바르지 않습니다")
        val iv = if (!key.ivHex.isNullOrBlank()) {
            hexTo16Bytes(key.ivHex)
        } else {
            ByteArray(16).also { bytes ->
                var value = sequence
                for (i in 15 downTo 0) {
                    bytes[i] = (value and 0xFF).toByte()
                    value = value ushr 8
                }
            }
        }
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(keyBytes, "AES"), IvParameterSpec(iv))
        return cipher.doFinal(data)
    }

    private fun hexTo16Bytes(value: String): ByteArray {
        val clean = value.removePrefix("0x").removePrefix("0X").padStart(32, '0').takeLast(32)
        return ByteArray(16) { i -> clean.substring(i * 2, i * 2 + 2).toInt(16).toByte() }
    }

    private fun fetchText(url: String, headers: Map<String, String>): String = fetchBytes(url, headers).toString(Charsets.UTF_8)

    private fun fetchBytes(url: String, headers: Map<String, String>): ByteArray {
        var conn: HttpURLConnection? = null
        return try {
            conn = openConnection(url, headers)
            conn.inputStream.use { it.readBytes() }
        } finally {
            conn?.disconnect()
        }
    }

    private fun openConnection(url: String, headers: Map<String, String>): HttpURLConnection {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.instanceFollowRedirects = true
        conn.connectTimeout = 15000
        conn.readTimeout = 30000
        conn.setRequestProperty("Accept", "*/*")
        conn.setRequestProperty("Accept-Encoding", "identity")
        headers.forEach { (k, v) -> if (v.isNotBlank()) conn.setRequestProperty(k, v) }
        val code = conn.responseCode
        if (code !in 200..299) error("HTTP $code")
        return conn
    }

    private fun resolve(base: String, child: String): String = URI(base).resolve(child).toString()

    private fun fileNameFromConnection(conn: HttpURLConnection, url: String, mime: String): String {
        val disposition = conn.getHeaderField("Content-Disposition").orEmpty()
        val fromHeader = Regex("filename\\*?=(?:UTF-8''|\")?([^\";]+)", RegexOption.IGNORE_CASE)
            .find(disposition)?.groupValues?.getOrNull(1)?.let { java.net.URLDecoder.decode(it.trim(), "UTF-8") }
        if (!fromHeader.isNullOrBlank()) return sanitizeName(fromHeader)
        val path = runCatching { URI(url).path }.getOrNull().orEmpty()
        val tail = path.substringAfterLast('/').takeIf { it.contains('.') }
        return sanitizeName(tail ?: "Adownloader_${System.currentTimeMillis()}${extensionForMime(mime)}")
    }

    private fun sanitizeName(name: String): String = name.replace(Regex("[\\\\/:*?\"<>|]"), "_").take(180)

    private fun ensureExtension(name: String, mime: String): String {
        val clean = sanitizeName(name)
        val ext = extensionForMime(mime)
        return if (clean.lowercase().endsWith(ext)) clean else clean.substringBeforeLast('.', clean) + ext
    }

    private fun extensionForMime(mime: String): String = when {
        mime.contains("webm", true) -> ".webm"
        mime.contains("mp2t", true) -> ".ts"
        mime.contains("quicktime", true) -> ".mov"
        else -> ".mp4"
    }

    private fun publishVideo(source: File, fileName: String, mime: String): Uri {
        val values = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, fileName)
            put(MediaStore.Video.Media.MIME_TYPE, mime)
            put(MediaStore.Video.Media.RELATIVE_PATH, Environment.DIRECTORY_MOVIES + "/Adownloader")
            put(MediaStore.Video.Media.IS_PENDING, 1)
        }
        val uri = contentResolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)
            ?: error("저장 위치를 만들 수 없습니다")
        try {
            contentResolver.openOutputStream(uri, "w")!!.use { out ->
                FileInputStream(source).use { input -> input.copyTo(out, 256 * 1024) }
            }
            values.clear()
            values.put(MediaStore.Video.Media.IS_PENDING, 0)
            contentResolver.update(uri, values, null, null)
            return uri
        } catch (t: Throwable) {
            contentResolver.delete(uri, null, null)
            throw t
        }
    }

    private fun remuxToMp4(input: File, output: File) {
        val extractor = MediaExtractor()
        extractor.setDataSource(input.absolutePath)
        val muxer = MediaMuxer(output.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        try {
            val trackMap = mutableMapOf<Int, Int>()
            for (i in 0 until extractor.trackCount) {
                val format = extractor.getTrackFormat(i)
                val mime = format.getString(android.media.MediaFormat.KEY_MIME).orEmpty()
                if (mime.startsWith("video/") || mime.startsWith("audio/")) {
                    extractor.selectTrack(i)
                    trackMap[i] = muxer.addTrack(format)
                }
            }
            if (trackMap.isEmpty()) error("미디어 트랙을 찾지 못했습니다")
            muxer.start()
            val buffer = ByteBuffer.allocate(2 * 1024 * 1024)
            val info = android.media.MediaCodec.BufferInfo()
            while (true) {
                val track = extractor.sampleTrackIndex
                if (track < 0) break
                val muxTrack = trackMap[track]
                if (muxTrack == null) {
                    extractor.advance()
                    continue
                }
                buffer.clear()
                val size = extractor.readSampleData(buffer, 0)
                if (size < 0) break
                info.offset = 0
                info.size = size
                info.presentationTimeUs = extractor.sampleTime
                info.flags = extractor.sampleFlags
                muxer.writeSampleData(muxTrack, buffer, info)
                extractor.advance()
            }
        } finally {
            runCatching { muxer.stop() }
            muxer.release()
            extractor.release()
        }
    }

    private fun jsonToMap(json: String): Map<String, String> {
        if (json.isBlank()) return emptyMap()
        val obj = JSONObject(json)
        return obj.keys().asSequence().associateWith { obj.optString(it) }
    }
}
