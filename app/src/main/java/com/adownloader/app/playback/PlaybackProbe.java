package com.adownloader.app.playback;

import com.adownloader.app.MediaCandidate;
import com.adownloader.app.download.*;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.util.*;

/**
 * The download side of a diagnostic session: the same request stack a download uses (HttpSource
 * with the candidate's page context and captured request context), but strictly bounded so a
 * server's refusal is respected. At most one root playlist, one media playlist, two segment
 * requests (first, then middle only if the first succeeded) and one AES-128 key request; the first
 * refusal ends the probe. Each step is recorded with what was actually received, never with a URL
 * or body.
 */
public final class PlaybackProbe {
  public static final int SEGMENT_HEAD = 8192;

  public static final class Step {
    public String stage, status = "skipped", kind = ResponseKind.NOT_PROBED.name();
    public String group = "none", host = "", mime = "", signature = "", key = "", note = "";
    public int httpStatus, bytes, index = -1;
    public String category = "";

    Map<String, Object> toMap() {
      Map<String, Object> m = new LinkedHashMap<>();
      m.put("stage", stage);
      m.put("status", status);
      m.put("kind", kind);
      m.put("group", group);
      m.put("httpStatus", httpStatus);
      m.put("host", host);
      m.put("mime", mime);
      if (!signature.isEmpty()) m.put("signature", signature);
      if (index >= 0) m.put("segmentIndex", index);
      if (bytes > 0) m.put("bytes", bytes);
      if (!key.isEmpty()) m.put("key", key);
      if (!category.isEmpty()) m.put("category", category);
      if (!note.isEmpty()) m.put("note", note);
      return m;
    }
  }

  public final List<Step> steps = new ArrayList<>();
  public HlsInspection root, media;
  public final Set<String> segmentKeys = new HashSet<>();
  public String stoppedReason = "";
  public List<Object> transportEvents = List.of();
  public int requestsSkippedAfterBlock;

  public interface SourceFactory {
    HttpSource create(MediaCandidate candidate, TransferControl control);
  }

  /** The request stack a download uses for this candidate. */
  public static HttpSource defaultSource(MediaCandidate c, TransferControl control) {
    int tab = -1;
    long frame = -1;
    try {
      tab = Integer.parseInt(c.getMetadata().getOrDefault("browserTabId", "-1"));
      frame = Long.parseLong(c.getMetadata().getOrDefault("frameId", "-1"));
    } catch (NumberFormatException ignored) {
    }
    return new HttpSource(c.getUrl(), c.getHeaders(), control)
        .pageContext(c.getPageUrl())
        .browserScope(tab, frame);
  }

  public static PlaybackProbe run(
      MediaCandidate candidate, String salt, TransferControl control, SourceFactory factory) {
    PlaybackProbe probe = new PlaybackProbe();
    if (candidate == null) {
      probe.stoppedReason = "no-candidate";
      return probe;
    }
    if (candidate.getType() != MediaCandidate.MediaType.HLS) {
      probe.stoppedReason = "not-hls";
      return probe;
    }
    FailureDiagnostics.Session diagnostics =
        new FailureDiagnostics.Session(
            0, "HLS", candidate.getUrl(), candidate.getPageUrl(), candidate.getMetadata());
    probe.diagnostics = diagnostics;
    try (FailureDiagnostics.Scope scope = FailureDiagnostics.activate(diagnostics);
        HttpSource source = factory.create(candidate, control)) {
      probe.probe(source, candidate.getUrl(), salt);
      probe.requestsSkippedAfterBlock = source.blockedRequestsSkipped();
    } catch (Exception closing) {
      if (probe.stoppedReason.isEmpty()) probe.stoppedReason = "probe-error";
    } finally {
      Map<String, Object> report =
          diagnostics.report(null, DownloadError.Category.UNKNOWN, "PROBE_FAILURE");
      Object events = report.get("eventHistory");
      if (events instanceof List<?> list) probe.transportEvents = new ArrayList<>(list);
      diagnostics.complete();
    }
    return probe;
  }

  private void probe(HttpSource source, String url, String salt) throws Exception {
    Step rootStep = document(source, url, "ROOT_MANIFEST", "root");
    if (!rootStep.status.equals("ok")) {
      stoppedReason = "manifest-" + rootStep.group;
      return;
    }
    String mediaUrl = url;
    if (root.playlistType.equals("master")) {
      if (root.protection == HlsInspection.Protection.DRM) {
        stoppedReason = "drm";
        return;
      }
      if (root.variantUrls.isEmpty()) {
        stoppedReason = "master-without-variants";
        return;
      }
      try {
        mediaUrl = ManifestParser.hlsSelection(url, lastText).video;
      } catch (RuntimeException unparsable) {
        mediaUrl = root.variantUrls.get(0);
      }
      Step variant = document(source, mediaUrl, "VARIANT_PLAYLIST", "variant");
      if (!variant.status.equals("ok")) {
        stoppedReason = "variant-" + variant.group;
        return;
      }
    } else media = root;
    if (media == null || media.segmentUrls.isEmpty()) {
      stoppedReason = "no-segments";
      return;
    }
    for (String s : media.segmentUrls) segmentKeys.add(DiagKeys.key(salt, s));
    if (media.protection == HlsInspection.Protection.DRM) {
      stoppedReason = "drm";
      return;
    }
    ManifestParser.Track track = null;
    try {
      track = ManifestParser.hlsTrack(mediaUrl, lastText);
      source.registerTracks(List.of(track));
    } catch (RuntimeException unsupported) {
      Step parse = new Step();
      parse.stage = "VARIANT_PLAYLIST";
      parse.status = "failed";
      parse.kind = ResponseKind.MEDIA_FORMAT_ERROR.name();
      parse.group = "format";
      parse.note = "parser-rejected";
      steps.add(parse);
      stoppedReason = "playlist-unsupported";
      return;
    }
    List<ManifestParser.Part> parts = new ArrayList<>();
    for (ManifestParser.Part p : track.parts) if (!p.initialization) parts.add(p);
    if (parts.isEmpty()) {
      stoppedReason = "no-segments";
      return;
    }
    Step first = segment(source, parts.get(0), 0, salt);
    if (!first.status.equals("ok")) {
      // Respect the refusal: no further segment, key or retry request is sent.
      stoppedReason = "segment-" + first.group;
      return;
    }
    if (parts.size() > 2) {
      Step middle = segment(source, parts.get(parts.size() / 2), parts.size() / 2, salt);
      if (!middle.status.equals("ok")) {
        stoppedReason = "segment-" + middle.group;
        return;
      }
    }
    if (!media.keyUrls.isEmpty()) key(source, media.keyUrls.get(0));
    stoppedReason = "completed";
  }

  private String lastText = "";
  private FailureDiagnostics.Session diagnostics;

  /** Whether the request stack classified a body received for this step as a challenge page. */
  private boolean challengeSeen(int fromEvent) {
    List<?> list = events();
    for (int i = Math.max(0, fromEvent); i < list.size(); i++)
      if (list.get(i) instanceof Map<?, ?> e && "HTML_CHALLENGE".equals(e.get("body"))) return true;
    return false;
  }

  private List<?> events() {
    if (diagnostics == null) return List.of();
    Object events = diagnostics.report(null, DownloadError.Category.UNKNOWN, "PROBE_FAILURE").get("eventHistory");
    return events instanceof List<?> list ? list : List.of();
  }

  private Step document(HttpSource source, String url, String stage, String role) {
    Step step = new Step();
    step.stage = stage;
    step.host = FailureDiagnostics.host(url);
    steps.add(step);
    int mark = events().size();
    try {
      HttpSource.Document doc = source.document(url, null, role);
      lastText = doc.text == null ? "" : doc.text;
      step.httpStatus = doc.status;
      step.mime = mime(doc.mime);
      byte[] head = lastText.substring(0, Math.min(lastText.length(), 4096)).getBytes(java.nio.charset.StandardCharsets.UTF_8);
      ResponseKind kind = ResponseKind.classify(doc.status == 0 ? 200 : doc.status, doc.mime, head, head.length, "playlist");
      step.kind = kind.name();
      step.group = kind.group;
      step.signature = ResponseKind.signature(head, head.length);
      step.status = kind.ok() ? "ok" : "failed";
      HlsInspection inspected = HlsInspection.inspect(url, lastText);
      if (role.equals("root")) root = inspected;
      else media = inspected;
      if (inspected.protection == HlsInspection.Protection.DRM) {
        step.kind = ResponseKind.DRM_PROTECTED.name();
        step.group = "protected";
      }
    } catch (Exception e) {
      failure(step, e, challengeSeen(mark));
    }
    return step;
  }

  private Step segment(HttpSource source, ManifestParser.Part part, int index, String salt) {
    Step step = new Step();
    step.stage = "VIDEO_SEGMENT";
    step.index = index;
    step.host = FailureDiagnostics.host(part.url);
    step.key = DiagKeys.key(salt, part.url);
    steps.add(step);
    // Exactly the request a download sends for this part (whole segment or its byte range); only
    // the first bytes are read before the connection is released.
    long length = part.length > 0 ? part.length : -1;
    HttpURLConnection c = null;
    int mark = events().size();
    try {
      c = source.open(part.url, part.offset, length);
      step.httpStatus = c.getResponseCode();
      step.mime = mime(c.getContentType());
      byte[] head = new byte[SEGMENT_HEAD];
      int n = 0;
      try (InputStream in = c.getInputStream()) {
        for (int r; n < head.length && (r = in.read(head, n, head.length - n)) > 0; ) n += r;
      }
      step.bytes = n;
      step.signature = ResponseKind.signature(head, n);
      // An AES-128 segment is ciphertext: its first bytes cannot carry a container signature.
      boolean ciphertext = media != null && media.protection == HlsInspection.Protection.AES_128;
      ResponseKind kind = ResponseKind.classify(step.httpStatus, step.mime, head, n, "media");
      if (ciphertext && kind == ResponseKind.MEDIA_FORMAT_ERROR) {
        kind = ResponseKind.MEDIA_OK;
        step.note = "aes-128-ciphertext";
      }
      step.kind = kind.name();
      step.group = kind.group;
      step.status = kind.ok() ? "ok" : "failed";
    } catch (Exception e) {
      failure(step, e, challengeSeen(mark));
    } finally {
      if (c != null) source.release(c);
    }
    return step;
  }

  private void key(HttpSource source, String url) {
    Step step = new Step();
    step.stage = "AES_KEY";
    step.host = FailureDiagnostics.host(url);
    steps.add(step);
    HttpURLConnection c = null;
    int mark = events().size();
    try {
      c = source.open(url, 0, -1);
      step.httpStatus = c.getResponseCode();
      step.mime = mime(c.getContentType());
      byte[] head = new byte[64];
      int n = 0;
      try (InputStream in = c.getInputStream()) {
        for (int r; n < head.length && (r = in.read(head, n, head.length - n)) > 0; ) n += r;
      }
      step.bytes = n;
      ResponseKind kind = ResponseKind.classify(step.httpStatus, step.mime, head, n, "key");
      step.kind = kind.name();
      step.group = kind.group;
      step.status = kind.ok() ? "ok" : "failed";
    } catch (Exception e) {
      failure(step, e, challengeSeen(mark));
    } finally {
      if (c != null) source.release(c);
    }
  }

  static void failure(Step step, Exception e, boolean challengeBody) {
    step.status = "failed";
    ResponseKind kind = ResponseKind.NETWORK_ERROR;
    if (e instanceof DownloadError d) {
      step.category = d.category.name();
      HlsDiagnostic h = d.diagnostic;
      if (h != null) {
        step.httpStatus = h.status;
        step.mime = mime(h.mime);
        if (!h.host.isEmpty()) step.host = FailureDiagnostics.host("https://" + h.host + "/");
      }
      String body = h == null ? "" : h.body;
      kind =
          switch (d.category) {
            case ACCESS_BLOCKED ->
                body.equals("challenge") || challengeBody
                    ? ResponseKind.HTML_CHALLENGE
                    : body.equals("page") ? ResponseKind.HTML_INSTEAD_OF_MEDIA : ResponseKind.ACCESS_DENIED;
            case AUTH -> ResponseKind.LOGIN_REQUIRED;
            case EXPIRED_URL -> ResponseKind.ACCESS_DENIED;
            case DRM -> ResponseKind.DRM_PROTECTED;
            case NETWORK -> ResponseKind.NETWORK_ERROR;
            case SERVER ->
                h != null && h.status == 429 ? ResponseKind.RATE_LIMITED : ResponseKind.SERVER_ERROR;
            case MANIFEST_INVALID, UNSUPPORTED_FORMAT, UNSUPPORTED_CODEC ->
                body.equals("page") ? ResponseKind.HTML_INSTEAD_OF_MEDIA : ResponseKind.MEDIA_FORMAT_ERROR;
            default ->
                h == null
                    ? ResponseKind.HTTP_ERROR
                    : h.status == 404 || h.status == 410
                        ? ResponseKind.NOT_FOUND
                        : h.status == 429 ? ResponseKind.RATE_LIMITED : ResponseKind.HTTP_ERROR;
          };
      if (h != null && (h.status == 401 || h.status == 403) && kind == ResponseKind.HTTP_ERROR)
        kind = ResponseKind.ACCESS_DENIED;
    } else if (e instanceof TransferControl.Stopped) {
      step.note = "stopped";
    }
    step.kind = kind.name();
    step.group = kind.group;
  }

  private static String mime(String raw) {
    String m = raw == null ? "" : raw.split(";", 2)[0].trim().toLowerCase(Locale.ROOT);
    return m.matches("[a-z0-9.+-]{1,40}/[a-z0-9.+-]{1,60}") ? m : "";
  }

  public List<Object> stepMaps() {
    List<Object> out = new ArrayList<>();
    for (Step s : steps) out.add(s.toMap());
    return out;
  }
}
