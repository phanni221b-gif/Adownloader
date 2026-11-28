package com.adownloader.app.playback;

import java.nio.charset.StandardCharsets;
import java.util.Locale;

/**
 * What a media request actually received, from status, Content-Type and the first body bytes. The
 * groups answer the questions a failed download raises: an anti-bot/challenge page, an access
 * refusal (401/403/expired), an ordinary HTTP error (404, 429, 5xx), a 2xx body that is not media
 * (format error), or a transport failure.
 */
public enum ResponseKind {
  MEDIA_OK("ok"),
  PLAYLIST_OK("ok"),
  KEY_OK("ok"),
  HTML_CHALLENGE("challenge"),
  LOGIN_REQUIRED("access"),
  ACCESS_DENIED("access"),
  NOT_FOUND("http"),
  RATE_LIMITED("http"),
  SERVER_ERROR("http"),
  HTTP_ERROR("http"),
  HTML_INSTEAD_OF_MEDIA("format"),
  MEDIA_FORMAT_ERROR("format"),
  EMPTY_BODY("format"),
  DRM_PROTECTED("protected"),
  NETWORK_ERROR("network"),
  NOT_PROBED("none");

  public final String group;

  ResponseKind(String group) {
    this.group = group;
  }

  public boolean ok() {
    return group.equals("ok");
  }

  /** Body signature names the report uses; never body content. */
  public static String signature(byte[] head, int length) {
    if (head == null || length <= 0) return "empty";
    int n = Math.min(length, head.length);
    int at = 0;
    if (n >= 3 && (head[0] & 0xff) == 0xef && (head[1] & 0xff) == 0xbb && (head[2] & 0xff) == 0xbf)
      at = 3;
    while (at < n && Character.isWhitespace(head[at])) at++;
    if (startsWith(head, n, at, "#EXTM3U")) return "hls";
    if (n >= 1 && (head[0] & 0xff) == 0x47 && (n < 189 || (head[188] & 0xff) == 0x47)) return "mpeg-ts";
    if (n >= 3 && head[0] == 'I' && head[1] == 'D' && head[2] == '3') return "id3";
    if (n >= 8) {
      String box = new String(head, 4, 4, StandardCharsets.ISO_8859_1);
      if (box.matches("ftyp|styp|moof|moov|sidx|emsg|free|mdat")) return "mp4-box";
    }
    if (n >= 4 && (head[0] & 0xff) == 0x1a && (head[1] & 0xff) == 0x45 && (head[2] & 0xff) == 0xdf
        && (head[3] & 0xff) == 0xa3) return "webm";
    if (n >= 2 && (head[0] & 0xff) == 0xff && (head[1] & 0xf6) == 0xf0) return "adts";
    boolean image =
        n >= 4 && (head[0] & 0xff) == 0x89 && head[1] == 'P' && head[2] == 'N' && head[3] == 'G'
            || n >= 3 && (head[0] & 0xff) == 0xff && (head[1] & 0xff) == 0xd8
            || n >= 4 && head[0] == 'G' && head[1] == 'I' && head[2] == 'F' && head[3] == '8'
            || n >= 2 && head[0] == 'B' && head[1] == 'M';
    if (image) {
      // An image header in front of an MPEG-TS stream (a disguised segment) still plays: find
      // three consecutive TS sync bytes after the header.
      for (int i = 1; i + 376 < n; i++)
        if ((head[i] & 0xff) == 0x47 && (head[i + 188] & 0xff) == 0x47 && (head[i + 376] & 0xff) == 0x47)
          return "image-prefixed-ts";
      return "image";
    }
    if (at < n && head[at] == '<') {
      String text = lower(head, at, n);
      if (text.matches("(?s)^<(?:!doctype html|html|head|body|script|meta|title|div|form).*")) return "html";
      if (text.matches("(?s)^<(?:\\?xml[^>]*>\\s*)?(?:<!--.*?-->\\s*)*<(?:[\\w.-]+:)?mpd.*")
          || text.matches("(?s)^<(?:[\\w.-]+:)?mpd.*")) return "dash";
      return "xml";
    }
    if (at < n && (head[at] == '{' || head[at] == '[')) return "json";
    return "unknown";
  }

  public static ResponseKind classify(int status, String mime, byte[] head, int length, String expected) {
    String type = mime == null ? "" : mime.toLowerCase(Locale.ROOT);
    String sig = signature(head, length);
    boolean html = sig.equals("html") || type.startsWith("text/html") && !sig.equals("hls");
    if (status == 0) return NETWORK_ERROR;
    if (status >= 400 || status < 200) {
      if (html && challenge(head, length)) return HTML_CHALLENGE;
      if (html && login(head, length)) return LOGIN_REQUIRED;
      if (status == 401 || status == 403 || status == 407 || status == 451) return ACCESS_DENIED;
      if (status == 404 || status == 410) return NOT_FOUND;
      if (status == 429) return RATE_LIMITED;
      if (status >= 500) return SERVER_ERROR;
      return HTTP_ERROR;
    }
    if (html) {
      if (challenge(head, length)) return HTML_CHALLENGE;
      if (login(head, length)) return LOGIN_REQUIRED;
      return HTML_INSTEAD_OF_MEDIA;
    }
    if (sig.equals("empty")) return EMPTY_BODY;
    switch (expected) {
      case "playlist":
        return sig.equals("hls") || sig.equals("dash") ? PLAYLIST_OK : MEDIA_FORMAT_ERROR;
      case "key":
        return length == 16 ? KEY_OK : MEDIA_FORMAT_ERROR;
      default:
        return sig.matches("mpeg-ts|id3|mp4-box|webm|adts|image-prefixed-ts") ? MEDIA_OK : MEDIA_FORMAT_ERROR;
    }
  }

  static boolean challenge(byte[] head, int length) {
    return lower(head, 0, Math.min(length, head == null ? 0 : head.length))
        .matches(
            "(?s).*(?:challenge|captcha|cf-chl|cf_chl|just a moment|attention required|ddos|"
                + "access denied|are you a robot|verify you are human|bot protection|hcaptcha|"
                + "turnstile).*");
  }

  static boolean login(byte[] head, int length) {
    return lower(head, 0, Math.min(length, head == null ? 0 : head.length))
        .matches("(?s).*(?:type=\"password\"|log ?in|sign ?in|로그인).*");
  }

  private static boolean startsWith(byte[] b, int n, int at, String text) {
    if (at + text.length() > n) return false;
    for (int i = 0; i < text.length(); i++) if (b[at + i] != text.charAt(i)) return false;
    return true;
  }

  private static String lower(byte[] b, int from, int to) {
    if (b == null || to <= from) return "";
    return new String(b, from, Math.min(to, from + 8192) - from, StandardCharsets.UTF_8)
        .toLowerCase(Locale.ROOT);
  }
}
