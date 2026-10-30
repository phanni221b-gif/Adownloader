package com.adownloader.app;

import android.os.Handler;
import android.os.Looper;

import org.json.JSONObject;

import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class GeckoMediaSniffer {
    public interface Listener {
        void onChanged(List<MediaCandidate> candidates);
    }

    private final Object lock = new Object();
    private final LinkedHashMap<String, MediaCandidate> candidates = new LinkedHashMap<>();
    private final Set<String> probed = Collections.synchronizedSet(new java.util.HashSet<>());
    private final ExecutorService probeExecutor = Executors.newFixedThreadPool(3);
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Listener listener;

    public GeckoMediaSniffer(Listener listener) {
        this.listener = listener;
    }

    public void clear() {
        synchronized (lock) {
            candidates.clear();
        }
        notifyChanged();
    }

    public List<MediaCandidate> snapshot() {
        synchronized (lock) {
            return new ArrayList<>(candidates.values());
        }
    }

    public void observeMessage(JSONObject message, String fallbackPage) {
        if (message == null) return;
        String event = message.optString("event", "gecko");
        String url = message.optString("url", "").trim();
        if (url.isEmpty()) return;
        if ("popup".equals(event)) return;

        String page = message.optString("page", "").trim();
        if (page.isEmpty()) page = fallbackPage;
        String mime = message.optString("mime", "").trim();
        if (mime.isEmpty()) mime = null;

        Map<String, String> headers = new LinkedHashMap<>();
        JSONObject jsonHeaders = message.optJSONObject("headers");
        if (jsonHeaders != null) {
            for (java.util.Iterator<String> it = jsonHeaders.keys(); it.hasNext();) {
                String key = it.next();
                String lower = key.toLowerCase(Locale.ROOT);
                if ("referer".equals(lower) || "user-agent".equals(lower) || "origin".equals(lower)) {
                    String value = jsonHeaders.optString(key, "");
                    if (!value.isEmpty()) headers.put(key, value);
                }
            }
        }
        if (page != null && !page.isEmpty() && !headers.containsKey("Referer")) {
            headers.put("Referer", page);
        }
        observe(url, mime, "gecko:" + event, page, headers);
    }

    public void observe(String url, String mimeType, String source, String pageUrl, Map<String, String> headers) {
        if (url == null || !(url.startsWith("http://") || url.startsWith("https://"))) return;
        MediaCandidate.MediaType type = classify(url, mimeType);
        if (type != MediaCandidate.MediaType.UNKNOWN) {
            add(new MediaCandidate(url, type, source, pageUrl, headers, System.currentTimeMillis()));
            return;
        }
        if (shouldProbe(url, source)) probe(url, source, pageUrl, headers);
    }

    private void add(MediaCandidate candidate) {
        boolean changed = false;
        synchronized (lock) {
            MediaCandidate previous = candidates.get(candidate.getUrl());
            if (previous == null || previous.getType() == MediaCandidate.MediaType.UNKNOWN) {
                candidates.put(candidate.getUrl(), candidate);
                changed = true;
            }
        }
        if (changed) notifyChanged();
    }

    private void notifyChanged() {
        List<MediaCandidate> copy = snapshot();
        main.post(() -> listener.onChanged(copy));
    }

    private boolean shouldProbe(String url, String source) {
        if (!probed.add(url)) return false;
        String lower = url.toLowerCase(Locale.ROOT);
        String[] hints = {"manifest", "playlist", "master", "stream", "video", "media", "play", "source"};
        for (String hint : hints) if (lower.contains(hint)) return true;
        return source != null && source.startsWith("gecko:content");
    }

    private void probe(String url, String source, String pageUrl, Map<String, String> headers) {
        probeExecutor.execute(() -> {
            HttpURLConnection conn = null;
            try {
                conn = (HttpURLConnection) new URL(url).openConnection();
                conn.setInstanceFollowRedirects(true);
                conn.setConnectTimeout(5000);
                conn.setReadTimeout(5000);
                conn.setRequestMethod("GET");
                conn.setRequestProperty("Range", "bytes=0-4095");
                for (Map.Entry<String, String> entry : headers.entrySet()) {
                    if (entry.getValue() != null && !entry.getValue().isEmpty()) {
                        conn.setRequestProperty(entry.getKey(), entry.getValue());
                    }
                }
                conn.connect();
                MediaCandidate.MediaType mimeType = classify(conn.getURL().toString(), conn.getContentType());
                if (mimeType != MediaCandidate.MediaType.UNKNOWN) {
                    add(new MediaCandidate(conn.getURL().toString(), mimeType, source + ":probe", pageUrl, headers, System.currentTimeMillis()));
                    return;
                }
                byte[] buffer = new byte[4096];
                int count;
                try (InputStream input = conn.getInputStream()) {
                    count = input.read(buffer);
                }
                if (count > 0) {
                    MediaCandidate.MediaType signature = classifyBytes(buffer, count);
                    if (signature != MediaCandidate.MediaType.UNKNOWN) {
                        add(new MediaCandidate(conn.getURL().toString(), signature, source + ":signature", pageUrl, headers, System.currentTimeMillis()));
                    }
                }
            } catch (Throwable ignored) {
            } finally {
                if (conn != null) conn.disconnect();
            }
        });
    }

    private MediaCandidate.MediaType classifyBytes(byte[] bytes, int count) {
        String text = new String(bytes, 0, count, StandardCharsets.UTF_8).trim();
        if (text.startsWith("#EXTM3U")) return MediaCandidate.MediaType.HLS;
        if (text.contains("<MPD") || text.contains("urn:mpeg:dash")) return MediaCandidate.MediaType.DASH;
        if (count >= 12) {
            String marker = new String(bytes, 4, 8, StandardCharsets.ISO_8859_1);
            if (marker.contains("ftyp")) return MediaCandidate.MediaType.VIDEO;
        }
        if (count >= 4 && (bytes[0] & 0xff) == 0x1A && (bytes[1] & 0xff) == 0x45 && (bytes[2] & 0xff) == 0xDF && (bytes[3] & 0xff) == 0xA3) {
            return MediaCandidate.MediaType.VIDEO;
        }
        return MediaCandidate.MediaType.UNKNOWN;
    }

    private MediaCandidate.MediaType classify(String url, String mimeType) {
        String mime = mimeType == null ? "" : mimeType.toLowerCase(Locale.ROOT);
        if (mime.contains("mpegurl") || mime.contains("vnd.apple.mpegurl")) return MediaCandidate.MediaType.HLS;
        if (mime.contains("dash+xml")) return MediaCandidate.MediaType.DASH;
        if (mime.startsWith("video/")) return MediaCandidate.MediaType.VIDEO;
        if (mime.startsWith("audio/")) return MediaCandidate.MediaType.AUDIO;

        String path;
        try {
            path = new URI(url).getPath();
            path = path == null ? "" : path.toLowerCase(Locale.ROOT);
        } catch (Throwable e) {
            path = url.toLowerCase(Locale.ROOT);
        }
        if (path.endsWith(".m3u8") || path.endsWith(".m3u")) return MediaCandidate.MediaType.HLS;
        if (path.endsWith(".mpd")) return MediaCandidate.MediaType.DASH;
        String[] videos = {".mp4", ".m4v", ".webm", ".mov", ".mkv", ".avi", ".ts", ".m2ts"};
        for (String ext : videos) if (path.endsWith(ext)) return MediaCandidate.MediaType.VIDEO;
        String[] audios = {".mp3", ".m4a", ".aac", ".ogg", ".opus"};
        for (String ext : audios) if (path.endsWith(ext)) return MediaCandidate.MediaType.AUDIO;
        return MediaCandidate.MediaType.UNKNOWN;
    }
}
