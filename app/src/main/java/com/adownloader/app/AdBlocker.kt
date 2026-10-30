package com.adownloader.app

import android.net.Uri

object AdBlocker {
    private val blockedHosts = setOf(
        "doubleclick.net",
        "googlesyndication.com",
        "adservice.google.com",
        "app-measurement.com",
        "t.nettrck.store",
        "popads.net",
        "popcash.net",
        "propellerads.com",
        "onclicka.com"
    )

    private val obviousAdPathTokens = listOf(
        "/popunder/", "/popup/", "/ads/", "/adserver/", "clickunder", "popunder"
    )

    fun shouldBlock(url: String, mediaSniffer: MediaSniffer): Boolean {
        if (mediaSniffer.looksLikeMedia(url)) return false
        val uri = runCatching { Uri.parse(url) }.getOrNull() ?: return false
        val host = uri.host?.lowercase() ?: return false
        if (blockedHosts.any { host == it || host.endsWith(".$it") }) return true
        val lower = url.lowercase()
        return obviousAdPathTokens.any { lower.contains(it) }
    }
}
