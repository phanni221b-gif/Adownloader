package com.adownloader.app.download;

import java.util.*;

/**
 * One read-only, original-quality selection shared by preview and transfer. URLs remain in memory.
 */
public final class MediaProbeResult {
  public final long fetchedAt, expiresAt;
  public final String finalManifestUrl, etag, modified, disposition, description;
  public final Map<String, HttpSource.Document> documents;
  private final List<ManifestParser.Track> plan;

  MediaProbeResult(
      HttpSource.Document root,
      Map<String, HttpSource.Document> documents,
      List<ManifestParser.Track> tracks,
      long expiresAt) {
    this.fetchedAt = System.currentTimeMillis();
    this.expiresAt = expiresAt;
    finalManifestUrl = root.url;
    disposition = root.disposition;
    etag = root.etag;
    modified = root.modified;
    this.documents = Collections.unmodifiableMap(new LinkedHashMap<>(documents));
    plan = copy(tracks);
    List<String> fields = new ArrayList<>();
    for (ManifestParser.Track track : tracks)
      if (!track.audio) {
        if (track.height > 0) fields.add(track.height + "p");
        if (track.frameRate > 0)
          fields.add(
              String.format(Locale.ROOT, "%.2f", track.frameRate).replaceAll("\\.?0+$", "")
                  + "fps");
        if (!track.codecs.isEmpty()) fields.add(track.codecs);
        break;
      }
    if (tracks.stream().anyMatch(t -> t.audio)) fields.add("별도 오디오 포함");
    description = fields.isEmpty() ? "최고 원본 자동 선택" : String.join(" · ", fields);
  }

  public List<ManifestParser.Track> tracks() {
    return copy(plan);
  }

  private static List<ManifestParser.Track> copy(List<ManifestParser.Track> source) {
    List<ManifestParser.Track> out = new ArrayList<>();
    for (ManifestParser.Track track : source) {
      ManifestParser.Track t = new ManifestParser.Track();
      t.audio = track.audio;
      t.duration = track.duration;
      t.width = track.width;
      t.height = track.height;
      t.frameRate = track.frameRate;
      t.representation = track.representation;
      t.codecs = track.codecs;
      t.language = track.language;
      t.parts.addAll(track.parts);
      out.add(t);
    }
    return out;
  }

  long weight() {
    long bytes = 0;
    for (HttpSource.Document doc : documents.values()) bytes += doc.text.length() * 2L;
    for (ManifestParser.Track t : plan)
      for (ManifestParser.Part p : t.parts)
        bytes += (p.url.length() + (p.keyUrl == null ? 0 : p.keyUrl.length())) * 2L;
    return bytes;
  }
}
