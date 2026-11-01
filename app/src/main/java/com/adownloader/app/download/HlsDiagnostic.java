package com.adownloader.app.download;

import java.net.*;
import java.util.*;

/** Strict allowlist. Never stores path, query, response body, headers, or exception text. */
public final class HlsDiagnostic {
  public enum Stage {
    ROOT_HTTP,
    ROOT_BODY,
    MASTER_PARSE,
    VARIANT_HTTP,
    VARIANT_BODY,
    VARIANT_PARSE,
    SEGMENT_URI,
    ATTRIBUTE,
    CHILD_AUTH,
    REDIRECT,
    REQUEST_CONTEXT_UNAVAILABLE,
    UNSUPPORTED,
    MALFORMED
  }

  public final String host, mime, playlist, tag, category;
  public final Stage stage;
  public final int status, redirects;

  public HlsDiagnostic(
      String url,
      int status,
      String mime,
      int redirects,
      String playlist,
      Stage stage,
      String tag,
      DownloadError.Category category) {
    String h;
    try {
      h = URI.create(url).getHost();
    } catch (Exception e) {
      h = "";
    }
    host = h == null ? "" : h.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9.:-]", "");
    this.status = Math.max(0, Math.min(599, status));
    this.redirects = Math.max(0, Math.min(8, redirects));
    String m = mime == null ? "" : mime.split(";", 2)[0].toLowerCase(Locale.ROOT);
    this.mime = m.matches("[a-z0-9.+-]+/[a-z0-9.+-]+") ? m : "";
    this.playlist =
        Set.of("master", "media", "root", "variant", "segment").contains(playlist)
            ? playlist
            : "root";
    this.stage = stage;
    this.tag = tag != null && tag.matches("EXT[A-Z0-9-]*") ? tag : "";
    this.category = category.name();
  }

  public Map<String, Object> safeFields() {
    Map<String, Object> v = new LinkedHashMap<>();
    v.put("host", host);
    v.put("status", status);
    v.put("mime", mime);
    v.put("redirects", redirects);
    v.put("playlist", playlist);
    v.put("stage", stage.name());
    v.put("tag", tag);
    v.put("category", category);
    return Collections.unmodifiableMap(v);
  }

  public static String body(HttpSource.Document doc, String role) throws DownloadError {
    String text = ManifestParser.normalizeHls(doc.text);
    if (text.trim().startsWith("#EXTM3U")) return text;
    String prefix = text.substring(0, Math.min(8192, text.length())).toLowerCase(Locale.ROOT);
    DownloadError.Category category = DownloadError.Category.MANIFEST_INVALID;
    if (prefix.matches("(?s).*<(?:!doctype|html|head|body|form).*"))
      category =
          prefix.matches("(?s).*(?:challenge|captcha|access.denied|cloudflare|forbidden).*")
              ? DownloadError.Category.ACCESS_BLOCKED
              : prefix.matches("(?s).*(?:password|login|sign.in|로그인).*")
                  ? DownloadError.Category.AUTH
                  : DownloadError.Category.ACCESS_BLOCKED;
    throw new DownloadError(category)
        .diagnostic(
            new HlsDiagnostic(
                doc.url,
                200,
                doc.mime,
                doc.redirects,
                role,
                role.equals("root") ? Stage.ROOT_BODY : Stage.VARIANT_BODY,
                "",
                category));
  }

  public static DownloadError parse(
      HttpSource.Document doc, String text, String role, RuntimeException error) {
    DownloadError.Category c =
        error instanceof DownloadError.ManifestFailure
            ? ((DownloadError.ManifestFailure) error).category
            : DownloadError.Category.MANIFEST_INVALID;
    String message = error.getMessage() == null ? "" : error.getMessage();
    Stage stage = role.equals("master") ? Stage.MASTER_PARSE : Stage.VARIANT_PARSE;
    if (error instanceof NumberFormatException || message.equals("HLS_ATTRIBUTE"))
      stage = Stage.ATTRIBUTE;
    else if (message.contains("주소") || message.equals("HLS_SEGMENT_URI")) stage = Stage.SEGMENT_URI;
    else if (c != DownloadError.Category.MANIFEST_INVALID) stage = Stage.UNSUPPORTED;
    String tag =
        stage == Stage.ATTRIBUTE && role.equals("master")
            ? "EXT-X-STREAM-INF"
            : stage == Stage.SEGMENT_URI ? "EXTINF" : "";
    return new DownloadError(c)
        .diagnostic(new HlsDiagnostic(doc.url, 200, doc.mime, doc.redirects, role, stage, tag, c));
  }
}
