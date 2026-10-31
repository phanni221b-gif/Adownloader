package com.adownloader.app.media;

import com.adownloader.app.MediaCandidate;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.util.*;

/** Conservative grouping changes identity only; the download URL is never rewritten. */
public final class MediaIdentity {
  private MediaIdentity() {}

  public static String exact(MediaCandidate.MediaType type, String url) {
    return hash(type + "|" + url);
  }

  public static String logical(
      MediaCandidate.MediaType type, String url, String page, String mime) {
    try {
      URI uri = URI.create(url);
      if (uri.getUserInfo() != null) return exact(type, url);
      String path = uri.getRawPath() == null ? "/" : uri.getRawPath();
      String query = uri.getRawQuery();
      List<String> parts = new ArrayList<>();
      boolean stable = specific(path), aws = false;
      if (query != null) {
        for (String part : query.split("&")) {
          String key = decode(part.split("=", 2)[0]).toLowerCase(Locale.ROOT);
          if (key.equals("id") || key.equals("videoid") || key.equals("representation"))
            stable = true;
          if (key.equals("x-amz-algorithm")) aws = true;
        }
      }
      if (!stable && !aws) return exact(type, url);
      if (query != null)
        for (String part : query.split("&")) {
          String key = decode(part.split("=", 2)[0]).toLowerCase(Locale.ROOT);
          boolean volatileKey =
              Set.of("token", "signature", "expires", "timestamp", "_").contains(key)
                  || (aws && key.startsWith("x-amz-"));
          if (!volatileKey) parts.add(part);
        }

      return hash(
          type
              + "|"
              + origin(page)
              + "|"
              + origin(url)
              + "|"
              + path
              + "?"
              + String.join("&", parts));
    } catch (Exception invalid) {
      return exact(type, url);
    }
  }

  private static boolean specific(String path) {
    String name = path.substring(path.lastIndexOf('/') + 1).toLowerCase(Locale.ROOT);
    String stem = name.replaceFirst("[.][a-z0-9]{2,5}$", "");
    if (!name.matches(".*[.](m3u8|mpd|mp4|webm|m4a|mp3|mov|mkv|aac|ogg|opus)$")) return false;
    return !Set.of(
                "video", "stream", "playback", "index", "master", "playlist", "download", "watch")
            .contains(stem)
        || path.matches(".*[/][^/]*[0-9]{3,}[^/]*/[^/]+$");
  }

  public static String origin(String value) {
    try {
      URI uri = URI.create(value);
      String scheme = uri.getScheme().toLowerCase(Locale.ROOT),
          host = uri.getHost().toLowerCase(Locale.ROOT);
      int port = uri.getPort();
      return scheme
          + "://"
          + host
          + ((port < 0
                  || scheme.equals("https") && port == 443
                  || scheme.equals("http") && port == 80)
              ? ""
              : ":" + port);
    } catch (Exception invalid) {
      return "";
    }
  }

  private static String decode(String s) {
    try {
      return URLDecoder.decode(s, "UTF-8");
    } catch (Exception bad) {
      return s;
    }
  }

  public static String hash(String value) {
    try {
      byte[] bytes =
          MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
      StringBuilder out = new StringBuilder();
      for (byte b : bytes) out.append(String.format(Locale.ROOT, "%02x", b & 255));
      return out.toString();
    } catch (NoSuchAlgorithmException impossible) {
      throw new AssertionError(impossible);
    }
  }
}
