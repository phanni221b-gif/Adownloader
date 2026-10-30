package com.adownloader.app;

import java.net.URI;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

public final class MediaCandidate {
    public enum MediaType { HLS, DASH, VIDEO, AUDIO, UNKNOWN }

    private final String url;
    private final MediaType type;
    private final String source;
    private final String pageUrl;
    private final Map<String, String> headers;
    private final long detectedAt;

    public MediaCandidate(String url,
                          MediaType type,
                          String source,
                          String pageUrl,
                          Map<String, String> headers,
                          long detectedAt) {
        this.url = url;
        this.type = type == null ? MediaType.UNKNOWN : type;
        this.source = source == null ? "unknown" : source;
        this.pageUrl = pageUrl;
        this.headers = headers == null
                ? Collections.emptyMap()
                : Collections.unmodifiableMap(new LinkedHashMap<>(headers));
        this.detectedAt = detectedAt;
    }

    public String getUrl() { return url; }
    public MediaType getType() { return type; }
    public String getSource() { return source; }
    public String getPageUrl() { return pageUrl; }
    public Map<String, String> getHeaders() { return headers; }
    public long getDetectedAt() { return detectedAt; }

    public String displayName() {
        String host = "media";
        try {
            String parsed = URI.create(url).getHost();
            if (parsed != null && !parsed.isBlank()) host = parsed;
        } catch (Throwable ignored) {
        }
        String label;
        switch (type) {
            case HLS: label = "HLS"; break;
            case DASH: label = "DASH"; break;
            case VIDEO: label = "VIDEO"; break;
            case AUDIO: label = "AUDIO"; break;
            default: label = "MEDIA";
        }
        return label + " · " + host;
    }
}
