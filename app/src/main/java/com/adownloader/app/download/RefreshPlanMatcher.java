package com.adownloader.app.download;

import java.net.URI;
import java.util.*;

/**
 * Reuse is allowed only for the same selected original representation and complete segment plan.
 */
public final class RefreshPlanMatcher {
  private RefreshPlanMatcher() {}

  public static boolean qualityNotLower(
      List<ManifestParser.Track> old, List<ManifestParser.Track> fresh) {
    for (ManifestParser.Track previous : old) {
      ManifestParser.Track next = null;
      for (ManifestParser.Track candidate : fresh)
        if (candidate.audio == previous.audio) {
          next = candidate;
          break;
        }
      if (next == null) return false;
      if (previous.audio) {
        if (next.bitRate < previous.bitRate) return false;
      } else {
        long oldPixels = (long) previous.width * previous.height,
            newPixels = (long) next.width * next.height;
        if (newPixels < oldPixels
            || newPixels == oldPixels
                && (next.frameRate < previous.frameRate
                    || next.frameRate == previous.frameRate && next.bitRate < previous.bitRate))
          return false;
      }
    }
    return true;
  }

  public static boolean knownQuality(List<ManifestParser.Track> tracks) {
    for (ManifestParser.Track track : tracks)
      if (track.audio ? track.bitRate > 0 : track.width > 0 && track.height > 0) return true;
    return false;
  }

  public static boolean compatible(
      List<ManifestParser.Track> old, List<ManifestParser.Track> fresh) {
    if (old == null || fresh == null || old.size() != fresh.size()) return false;
    for (int t = 0; t < old.size(); t++) {
      ManifestParser.Track a = old.get(t), b = fresh.get(t);
      if (a.bitRate != b.bitRate
          || a.audio != b.audio
          || a.width != b.width
          || a.height != b.height
          || Math.abs(a.frameRate - b.frameRate) > .01
          || Math.abs(a.duration - b.duration) > .05
          || !codecs(a.codecs).equals(codecs(b.codecs))
          || !a.language.equals(b.language)
          || !canonical(a.representation).equals(canonical(b.representation))
          || a.parts.size() != b.parts.size()) return false;
      for (int p = 0; p < a.parts.size(); p++) {
        ManifestParser.Part x = a.parts.get(p), y = b.parts.get(p);
        if (x.offset != y.offset
            || x.length != y.length
            || x.sequence != y.sequence
            || !Objects.equals(x.iv, y.iv)
            || !canonical(x.url).equals(canonical(y.url))
            || !canonical(x.keyUrl).equals(canonical(y.keyUrl))) return false;
      }
    }
    return true;
  }

  private static String codecs(String s) {
    List<String> values = new ArrayList<>();
    for (String value : s.toLowerCase(Locale.ROOT).split(",")) values.add(value.trim());
    Collections.sort(values);
    return String.join(",", values);
  }

  private static String canonical(String value) {
    if (value == null) return "";
    if (!value.startsWith("http://") && !value.startsWith("https://")) return value;
    try {
      URI uri = URI.create(value);
      String query = uri.getRawQuery();
      List<String> keep = new ArrayList<>();
      if (query != null)
        for (String part : query.split("&")) {
          String key =
              java.net.URLDecoder.decode(part.split("=", 2)[0], "UTF-8").toLowerCase(Locale.ROOT);
          if (!Set.of("token", "signature", "expires", "timestamp", "_").contains(key))
            keep.add(part);
        }
      return com.adownloader.app.media.MediaIdentity.origin(value)
          + uri.getRawPath()
          + "?"
          + String.join("&", keep);
    } catch (Exception bad) {
      return value;
    }
  }
}
