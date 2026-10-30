package com.adownloader.app

data class MediaCandidate(
    val url: String,
    val type: MediaType,
    val source: String,
    val pageUrl: String?,
    val headers: Map<String, String>,
    val detectedAt: Long = System.currentTimeMillis()
) {
    enum class MediaType { HLS, DASH, VIDEO, AUDIO, UNKNOWN }

    fun displayName(): String {
        val host = runCatching { java.net.URI(url).host ?: "media" }.getOrDefault("media")
        val label = when (type) {
            MediaType.HLS -> "HLS"
            MediaType.DASH -> "DASH"
            MediaType.VIDEO -> "VIDEO"
            MediaType.AUDIO -> "AUDIO"
            MediaType.UNKNOWN -> "MEDIA"
        }
        return "$label · $host"
    }
}
