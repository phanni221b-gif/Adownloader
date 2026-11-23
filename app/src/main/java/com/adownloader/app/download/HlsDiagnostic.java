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
    MALFORMED,
    SEGMENT_BODY
  }

  /**
   * A segment, key or direct-media response whose body is an HTML document is not media, whatever
   * its HTTP status. Returns the classified failure, or null when {@code head} is not HTML. Binary
   * media (TS sync byte, ISO-BMFF boxes, ADTS, ID3, AES ciphertext) never starts with an HTML tag.
   */
  public static DownloadError htmlInsteadOfMedia(
      String url, int status, String mime, int redirects, byte[] head, int length) {
    int start = 0;
    if (length >= 3 && (head[0] & 0xff) == 0xef && (head[1] & 0xff) == 0xbb && (head[2] & 0xff) == 0xbf)
      start = 3;
    while (start < length && (head[start] == ' ' || head[start] == '\t' || head[start] == '\r' || head[start] == '\n'))
      start++;
    if (start >= length || head[start] != '<') return null;
    String prefix =
        new String(head, start, Math.min(length - start, 8192), java.nio.charset.StandardCharsets.ISO_8859_1)
            .toLowerCase(Locale.ROOT);
    if (!prefix.matches("(?s)<(?:!doctype\\s+html|html|head|body|form|script|meta|title)[\\s>/].*"))
      return null;
    String received =
        prefix.matches("(?s).*(?:challenge|captcha|access.denied|cloudflare|forbidden).*")
            ? "challenge"
            : prefix.matches("(?s).*(?:password|login|sign.in).*") ? "login" : "page";
    DownloadError.Category category =
        received.equals("login") ? DownloadError.Category.AUTH : DownloadError.Category.ACCESS_BLOCKED;
    return new DownloadError(category)
        .diagnostic(
            new HlsDiagnostic(
                url, status, mime, redirects, "segment", Stage.SEGMENT_BODY, "", category, received));
  }

  public final String host, mime, playlist, tag, category;
  // What came back instead of a playlist when the body is the problem: "page" (an ordinary HTML
  // page), "challenge" (an access-protection/challenge page), "login" (a sign-in page); "" when the
  // body is not the issue. Lets the UI say what was actually received instead of a generic block.
  public final String body;
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
    this(url, status, mime, redirects, playlist, stage, tag, category, "");
  }

  public HlsDiagnostic(
      String url,
      int status,
      String mime,
      int redirects,
      String playlist,
      Stage stage,
      String tag,
      DownloadError.Category category,
      String body) {
    this.body = Set.of("page", "challenge", "login").contains(body) ? body : "";
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
    String received = "";
    if (prefix.matches("(?s).*<(?:!doctype|html|head|body|form).*")) {
      received =
          prefix.matches("(?s).*(?:challenge|captcha|access.denied|cloudflare|forbidden).*")
              ? "challenge"
              : prefix.matches("(?s).*(?:password|login|sign.in|로그인).*") ? "login" : "page";
      // The category drives recovery (an access failure still tries the page-context bootstrap),
      // so it is unchanged; "received" only makes the reported cause precise.
      category = received.equals("login") ? DownloadError.Category.AUTH : DownloadError.Category.ACCESS_BLOCKED;
    }
    throw new DownloadError(category)
        .diagnostic(
            new HlsDiagnostic(
                doc.url,
                doc.status,
                doc.mime,
                doc.redirects,
                role,
                role.equals("root") ? Stage.ROOT_BODY : Stage.VARIANT_BODY,
                "",
                category,
                received));
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
    if (message.equals("HLS_INIT_CHANGE")) tag = "EXT-X-MAP";
    else if (message.equals("HLS_SESSION_KEY")) tag = "EXT-X-SESSION-KEY";
    else if (message.equals("HLS_DELTA_PLAYLIST")) tag = "EXT-X-SKIP";
    else if (c == DownloadError.Category.HLS_GAP) tag = "EXT-X-GAP";
    else if (c == DownloadError.Category.HLS_DISCONTINUITY) tag = "EXT-X-DISCONTINUITY";
    return new DownloadError(c)
        .diagnostic(
            new HlsDiagnostic(doc.url, doc.status, doc.mime, doc.redirects, role, stage, tag, c));
  }
}
