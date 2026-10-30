package com.adownloader.app.media;

import com.adownloader.app.MediaCandidate;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/** Central, engine-independent media candidate collector. */
public final class MediaDetector {
    public interface Listener {
        void onCandidateAdded(MediaCandidate candidate, int totalCount);
    }

    private final CopyOnWriteArrayList<MediaCandidate> candidates = new CopyOnWriteArrayList<>();
    private final ConcurrentHashMap<String, Boolean> seen = new ConcurrentHashMap<>();
    private final Listener listener;

    public MediaDetector(Listener listener) {
        this.listener = listener;
    }

    public void reset() {
        candidates.clear();
        seen.clear();
        if (listener != null) listener.onCandidateAdded(null, 0);
    }

    public void capture(String url, String contentType, String source, String pageUrl, Map<String, String> headers) {
        if (url == null) return;
        String clean = url.trim();
        if (clean.isEmpty() || clean.startsWith("data:") || clean.startsWith("blob:")) return;

        MediaCandidate.MediaType type = classify(clean, contentType);
        if (type == MediaCandidate.MediaType.UNKNOWN) return;

        String key = type.name() + "|" + clean;
        if (seen.putIfAbsent(key, Boolean.TRUE) != null) return;

        Map<String, String> safeHeaders = headers == null
                ? Collections.emptyMap()
                : Collections.unmodifiableMap(new LinkedHashMap<>(headers));

        MediaCandidate candidate = new MediaCandidate(
                clean,
                type,
                source == null ? "gecko" : source,
                pageUrl,
                safeHeaders,
                System.currentTimeMillis()
        );
        candidates.add(candidate);
        if (listener != null) listener.onCandidateAdded(candidate, candidates.size());
    }

    public List<MediaCandidate> snapshot() {
        return new ArrayList<>(candidates);
    }

    private MediaCandidate.MediaType classify(String url, String contentType) {
        String lowerUrl = url.toLowerCase(Locale.ROOT);
        String mime = contentType == null ? "" : contentType.toLowerCase(Locale.ROOT);

        if (lowerUrl.contains(".m3u8") || mime.contains("mpegurl") || mime.contains("vnd.apple.mpegurl")) {
            return MediaCandidate.MediaType.HLS;
        }
        if (lowerUrl.contains(".mpd") || mime.contains("dash+xml")) {
            return MediaCandidate.MediaType.DASH;
        }
        if (mime.startsWith("video/") || hasAny(lowerUrl, ".mp4", ".webm", ".m4v", ".mov", ".mkv")) {
            return MediaCandidate.MediaType.VIDEO;
        }
        if (mime.startsWith("audio/") || hasAny(lowerUrl, ".m4a", ".mp3", ".aac", ".ogg", ".opus")) {
            return MediaCandidate.MediaType.AUDIO;
        }
        return MediaCandidate.MediaType.UNKNOWN;
    }

    private boolean hasAny(String value, String... needles) {
        for (String needle : needles) {
            if (value.contains(needle)) return true;
        }
        return false;
    }
}
