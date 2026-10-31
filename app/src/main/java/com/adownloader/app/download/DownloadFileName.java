package com.adownloader.app.download;

import java.net.*;
import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.regex.*;

/** Names are display metadata, never paths. The final extension comes from inspected media. */
public final class DownloadFileName {
  private DownloadFileName() {}

  public static String choose(
      String disposition,
      String title,
      String url,
      String mediaTitle,
      String extension,
      long time) {
    String base = base(disposition, title, url, mediaTitle);
    if (base.isEmpty())
      base =
          "Adownloader_"
              + DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss", Locale.ROOT)
                  .withZone(ZoneId.systemDefault())
                  .format(Instant.ofEpochMilli(time));
    return base + extension;
  }

  public static String label(Map<String, String> metadata, String url) {
    String name =
        base(metadata.get("disposition"), metadata.get("title"), url, metadata.get("mediaTitle"));
    return name.isEmpty() ? "동영상 다운로드" : name;
  }

  private static String base(String disposition, String title, String url, String mediaTitle) {
    for (String candidate :
        new String[] {fromDisposition(disposition), title, fromUrl(url), mediaTitle}) {
      String name = sanitize(candidate);
      if (!name.isEmpty()) return name;
    }
    return "";
  }

  static String fromDisposition(String value) {
    if (value == null) return "";
    String name = "";
    Matcher extended = Pattern.compile("(?i)(?:^|;)\\s*filename\\*\\s*=\\s*([^;]+)").matcher(value);
    if (extended.find()) {
      String raw = unquote(extended.group(1).trim());
      int first = raw.indexOf('\''), second = raw.indexOf('\'', first + 1);
      if (first > 0 && second > first) {
        String charset = raw.substring(0, first);
        if (charset.equalsIgnoreCase("UTF-8") || charset.equalsIgnoreCase("ISO-8859-1"))
          try {
            name = URLDecoder.decode(raw.substring(second + 1).replace("+", "%2B"), charset);
          } catch (Exception ignored) {
          }
      }
    }
    if (name.isEmpty()) {
      Matcher plain =
          Pattern.compile("(?i)(?:^|;)\\s*filename\\s*=\\s*(\"(?:[^\"\\\\]|\\\\.)*\"|[^;]+)")
              .matcher(value);
      if (plain.find()) name = unquote(plain.group(1).trim());
    }
    if (name.contains("/")
        || name.contains("\\")
        || name.toLowerCase(Locale.ROOT).matches(".*[.](m3u8|mpd)$")) return "";
    return name;
  }

  private static String unquote(String name) {
    if (name.length() > 1 && name.startsWith("\"") && name.endsWith("\""))
      return name.substring(1, name.length() - 1).replace("\\\"", "\"").replace("\\\\", "\\");
    return name;
  }

  private static String fromUrl(String value) {
    try {
      String path = URI.create(value).getRawPath();
      String name =
          URLDecoder.decode(path.substring(path.lastIndexOf('/') + 1).replace("+", "%2B"), "UTF-8");
      String stem = stripExtension(name).toLowerCase(Locale.ROOT);
      if (Set.of("play", "playback", "download", "stream", "index", "master", "playlist", "api")
          .contains(stem)) return "";
      return name;
    } catch (Exception invalid) {
      return "";
    }
  }

  private static String stripExtension(String value) {
    return value.replaceFirst("(?i)[.](mp4|webm|m4v|mov|mkv|m4a|mp3|aac|ogg|opus|m3u8|mpd)$", "");
  }

  public static String sanitize(String value) {
    if (value == null
        || value.isBlank()
        || value.matches("(?is).*(https?://|token=|signature=|cookie=|bearer ).*")) return "";
    String normalized = Normalizer.normalize(stripExtension(value), Normalizer.Form.NFC);
    StringBuilder out = new StringBuilder();
    int bytes = 0;
    for (int offset = 0; offset < normalized.length(); ) {
      int cp = normalized.codePointAt(offset);
      offset += Character.charCount(cp);
      if (Character.isISOControl(cp)
          || cp == 0x2028
          || cp == 0x2029
          || cp >= 0x202a && cp <= 0x202e
          || cp >= 0x2066 && cp <= 0x2069
          || "/\\:*?\"<>|".indexOf(cp) >= 0) continue;
      String character = new String(Character.toChars(cp));
      int length = character.getBytes(StandardCharsets.UTF_8).length;
      if (bytes + length > 180) break;
      out.append(character);
      bytes += length;
    }
    String name = out.toString().replaceAll("^[.\\s]+|[.\\s]+$", "");
    if (name.matches("(?i)(CON|PRN|AUX|NUL|COM[1-9]|LPT[1-9])")) name = "영상_" + name;
    return name;
  }
}
