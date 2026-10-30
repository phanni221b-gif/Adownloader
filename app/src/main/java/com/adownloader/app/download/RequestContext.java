package com.adownloader.app.download;

import java.net.URI;
import java.util.LinkedHashMap;
import java.util.Map;

/** Only request-scoped context, never a browser cookie-jar export. */
public final class RequestContext {
    private final String sourceOrigin;
    private final Map<String, String> headers;
    public RequestContext(String source, Map<String, String> headers) {
        sourceOrigin = origin(source);
        this.headers = new LinkedHashMap<>(headers);
    }
    public Map<String, String> forUrl(String url) {
        Map<String, String> result = new LinkedHashMap<>();
        headers.forEach((name, value) -> {
            String n = name.toLowerCase(java.util.Locale.ROOT);
            if (value == null || value.contains("\r") || value.contains("\n")) return;
            if (n.equals("referer") || n.equals("origin") || n.equals("user-agent") ||
                    ((n.equals("cookie") || n.equals("authorization")) && sourceOrigin.equals(origin(url)))) {
                result.put(name, value);
            }
        });
        return result;
    }
    private static String origin(String url) {
        URI uri = URI.create(url);
        if (!("https".equalsIgnoreCase(uri.getScheme()) || "http".equalsIgnoreCase(uri.getScheme())) || uri.getHost() == null)
            throw new IllegalArgumentException("HTTP(S) 주소만 저장할 수 있습니다");
        int port = uri.getPort() < 0 ? ("https".equalsIgnoreCase(uri.getScheme()) ? 443 : 80) : uri.getPort();
        return uri.getScheme().toLowerCase(java.util.Locale.ROOT) + "://" + uri.getHost().toLowerCase(java.util.Locale.ROOT) + ":" + port;
    }
}
