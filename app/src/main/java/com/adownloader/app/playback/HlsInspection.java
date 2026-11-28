package com.adownloader.app.playback;

import com.adownloader.app.download.FailureDiagnostics;
import com.adownloader.app.download.ManifestParser;
import java.util.*;

/**
 * Reads an HLS playlist's structure and protection tags without fetching anything: whether it is
 * a master or media playlist, which EXT-X-KEY / EXT-X-SESSION-KEY methods and KEYFORMATs it
 * declares, and therefore whether the stream is clear, standard AES-128 (a key the player fetches
 * over HTTP), SAMPLE-AES, or DRM (a license system: FairPlay, Widevine, PlayReady). Diagnosis
 * only: no key or license is requested and no DRM is touched.
 */
public final class HlsInspection {
  public enum Protection {
    CLEAR,
    AES_128,
    SAMPLE_AES,
    DRM,
    UNKNOWN
  }

  public String playlistType = "invalid";
  public Protection protection = Protection.UNKNOWN;
  public final SortedSet<String> keyMethods = new TreeSet<>(), keyFormats = new TreeSet<>();
  public final SortedSet<String> drmSystems = new TreeSet<>(), keyUriSchemes = new TreeSet<>();
  public final SortedSet<String> codecs = new TreeSet<>(), segmentHosts = new TreeSet<>();
  public final List<String> segmentUrls = new ArrayList<>(), variantUrls = new ArrayList<>();
  public final List<String> keyUrls = new ArrayList<>();
  public int segmentCount, encryptedSegments, sessionKeys, variantCount, discontinuities, maxHeight;
  public boolean endList, byteRanges, initSection, live;
  public double targetDuration, totalSeconds;

  private static final Map<String, String> FORMATS =
      Map.ofEntries(
          Map.entry("identity", "identity"),
          Map.entry("com.apple.streamingkeydelivery", "FairPlay"),
          Map.entry("com.apple.fps", "FairPlay"),
          Map.entry("com.apple.fps.1_0", "FairPlay"),
          Map.entry("com.widevine", "Widevine"),
          Map.entry("com.widevine.alpha", "Widevine"),
          Map.entry("urn:uuid:edef8ba9-79d6-4ace-a3c8-27dcd51d21ed", "Widevine"),
          Map.entry("com.microsoft.playready", "PlayReady"),
          Map.entry("urn:uuid:9a04f079-9840-4286-ab92-e65be0885f95", "PlayReady"),
          Map.entry("urn:uuid:79f0049a-4098-8642-ab92-e65be0885f95", "PlayReady"),
          Map.entry("org.w3.clearkey", "ClearKey"),
          Map.entry("urn:uuid:e2719d58-a985-b3c9-781a-b030af78d30e", "ClearKey"),
          Map.entry("urn:uuid:1077efec-c0b2-4d02-ace3-3c1e52e2fb4b", "ClearKey"),
          Map.entry("urn:uuid:94ce86fb-07ff-4f43-adb8-93d2fa968ca2", "FairPlay"));

  public static HlsInspection inspect(String url, String raw) {
    HlsInspection r = new HlsInspection();
    String text = ManifestParser.normalizeHls(raw == null ? "" : raw);
    if (!text.trim().startsWith("#EXTM3U")) return r;
    String[] lines = text.split("\\r?\\n");
    boolean master = false, encrypted = false, pendingVariant = false;
    for (String l : lines) if (l.trim().startsWith("#EXT-X-STREAM-INF:")) master = true;
    r.playlistType = master ? "master" : "media";
    for (String rawLine : lines) {
      String line = rawLine.trim();
      if (line.isEmpty()) continue;
      if (line.startsWith("#EXT-X-KEY:") || line.startsWith("#EXT-X-SESSION-KEY:")) {
        Map<String, String> a = attributes(line);
        String method = a.getOrDefault("METHOD", "").toUpperCase(Locale.ROOT);
        r.keyMethods.add(method.matches("[A-Z0-9-]{1,24}") ? method : "OTHER");
        String format = a.getOrDefault("KEYFORMAT", "identity").toLowerCase(Locale.ROOT);
        String name = FORMATS.getOrDefault(format, "other");
        r.keyFormats.add(name);
        if (!name.equals("identity") && !method.equals("NONE")) r.drmSystems.add(name);
        String uri = a.getOrDefault("URI", "");
        int colon = uri.indexOf(':');
        String scheme = colon > 0 ? uri.substring(0, colon).toLowerCase(Locale.ROOT) : "relative";
        if (!uri.isEmpty()) r.keyUriSchemes.add(scheme.matches("[a-z][a-z0-9+.-]{0,15}") ? scheme : "other");
        if (scheme.equals("skd")) r.drmSystems.add("FairPlay");
        if (line.startsWith("#EXT-X-SESSION-KEY:")) r.sessionKeys++;
        else {
          encrypted = !method.equals("NONE") && !method.isEmpty();
          if (method.equals("AES-128") && !uri.isEmpty() && name.equals("identity") && !scheme.equals("skd")
              && !scheme.equals("data") && r.keyUrls.size() < 4)
            r.keyUrls.add(ManifestParser.resolve(url, uri));
        }
      } else if (line.startsWith("#EXT-X-STREAM-INF:")) {
        Map<String, String> a = attributes(line);
        r.variantCount++;
        for (String c : a.getOrDefault("CODECS", "").split(","))
          if (c.trim().matches("[A-Za-z0-9.]{2,32}") && r.codecs.size() < 12) r.codecs.add(c.trim());
        String[] size = a.getOrDefault("RESOLUTION", "").split("x");
        if (size.length == 2 && size[1].matches("\\d{1,5}"))
          r.maxHeight = Math.max(r.maxHeight, Integer.parseInt(size[1]));
        pendingVariant = true;
      } else if (line.startsWith("#EXT-X-TARGETDURATION:")) {
        r.targetDuration = number(line.substring(22));
      } else if (line.startsWith("#EXTINF:")) {
        String d = line.substring(8);
        int comma = d.indexOf(',');
        r.totalSeconds += number(comma >= 0 ? d.substring(0, comma) : d);
      } else if (line.equals("#EXT-X-ENDLIST")) r.endList = true;
      else if (line.startsWith("#EXT-X-BYTERANGE")) r.byteRanges = true;
      else if (line.startsWith("#EXT-X-MAP:")) r.initSection = true;
      else if (line.equals("#EXT-X-DISCONTINUITY")) r.discontinuities++;
      else if (!line.startsWith("#")) {
        String resolved = ManifestParser.resolve(url, line);
        if (master && pendingVariant) {
          if (r.variantUrls.size() < 16) r.variantUrls.add(resolved);
          pendingVariant = false;
        } else if (!master) {
          r.segmentCount++;
          if (encrypted) r.encryptedSegments++;
          if (r.segmentUrls.size() < 20000) r.segmentUrls.add(resolved);
          String host = FailureDiagnostics.host(resolved);
          if (!host.isEmpty() && r.segmentHosts.size() < 8) r.segmentHosts.add(host);
        }
      }
    }
    r.live = !master && !r.endList;
    Set<String> active = new TreeSet<>(r.keyMethods);
    active.remove("NONE");
    if (!r.drmSystems.isEmpty()) r.protection = Protection.DRM;
    else if (active.isEmpty()) r.protection = Protection.CLEAR;
    else if (active.equals(Set.of("AES-128"))) r.protection = Protection.AES_128;
    else if (active.stream().allMatch(m -> m.startsWith("SAMPLE-AES"))) r.protection = Protection.SAMPLE_AES;
    else r.protection = Protection.UNKNOWN;
    return r;
  }

  private static double number(String value) {
    try {
      double d = Double.parseDouble(value.trim());
      return Double.isFinite(d) && d >= 0 ? d : 0;
    } catch (RuntimeException e) {
      return 0;
    }
  }

  private static Map<String, String> attributes(String line) {
    try {
      return ManifestParser.attributes(line);
    } catch (RuntimeException malformed) {
      return Map.of();
    }
  }

  /** Report form: tag-derived facts only, never a URL. */
  public Map<String, Object> toMap() {
    Map<String, Object> m = new LinkedHashMap<>();
    m.put("playlistType", playlistType);
    m.put("protection", protection.name());
    m.put("keyMethods", new ArrayList<>(keyMethods));
    m.put("keyFormats", new ArrayList<>(keyFormats));
    m.put("drmSystems", new ArrayList<>(drmSystems));
    m.put("keyUriSchemes", new ArrayList<>(keyUriSchemes));
    m.put("sessionKeys", sessionKeys);
    if (playlistType.equals("master")) {
      m.put("variantCount", variantCount);
      m.put("codecs", new ArrayList<>(codecs));
      m.put("maxHeight", maxHeight);
    } else {
      m.put("segmentCount", segmentCount);
      m.put("encryptedSegments", encryptedSegments);
      m.put("segmentHosts", new ArrayList<>(segmentHosts));
      m.put("targetDuration", targetDuration);
      m.put("totalSeconds", Math.round(totalSeconds));
      m.put("endList", endList);
      m.put("live", live);
      m.put("byteRanges", byteRanges);
      m.put("initSection", initSection);
      m.put("discontinuities", discontinuities);
    }
    return m;
  }
}
