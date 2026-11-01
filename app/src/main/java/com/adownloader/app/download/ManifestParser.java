package com.adownloader.app.download;

import java.io.StringReader;
import java.net.URI;
import java.util.*;
import java.util.regex.*;
import javax.xml.parsers.DocumentBuilderFactory;
import org.w3c.dom.*;
import org.xml.sax.InputSource;

/** Pure-Java VOD playlist planning. Unsupported constructs fail before publication. */
public final class ManifestParser {
  public static final class Part {
    public final String url;
    public final long offset, length, sequence;
    public final String keyUrl, iv;

    Part(String url, long offset, long length, long sequence, String keyUrl, String iv) {
      this.url = url;
      this.offset = offset;
      this.length = length;
      this.sequence = sequence;
      this.keyUrl = keyUrl;
      this.iv = iv;
    }

    Part(String url) {
      this(url, 0, -1, 0, null, null);
    }
  }

  public static final class Track {
    public final List<Part> parts = new ArrayList<>();
    public double duration;
    public boolean audio;
    public int width, height;
    public long bitRate;
    public double frameRate;
    public String representation = "", codecs = "", language = "";
  }

  public static final class HlsSelection {
    public String video, audio;
  }

  private static final Pattern ATTR = Pattern.compile("([A-Z0-9-]+)=(\"[^\"]*\"|[^,]*)");

  public static Map<String, String> attributes(String line) {
    Map<String, String> values = new LinkedHashMap<>();
    Matcher matcher = ATTR.matcher(line.substring(line.indexOf(':') + 1));
    while (matcher.find()) {
      String value = matcher.group(2);
      if (value.startsWith("\"")) value = value.substring(1, value.length() - 1);
      values.put(matcher.group(1), value);
    }
    return values;
  }

  public static HlsSelection hlsSelection(String url, String text) {
    require(text.trim().startsWith("#EXTM3U"), "올바른 HLS 목록이 아닙니다");
    HlsSelection result = new HlsSelection();
    result.video = url;
    long bestPixels = -1, bestBandwidth = -1;
    double bestFps = -1;
    String audioGroup = null;
    String[] lines = text.split("\\r?\\n");
    for (int i = 0; i < lines.length; i++) {
      String line = lines[i].trim();
      if (!line.startsWith("#EXT-X-STREAM-INF:")) continue;
      Map<String, String> a = attributes(line);
      long pixels = 0;
      String[] size = a.getOrDefault("RESOLUTION", "0x0").split("x");
      if (size.length == 2) pixels = Long.parseLong(size[0]) * Long.parseLong(size[1]);
      double fps = Double.parseDouble(a.getOrDefault("FRAME-RATE", "0"));
      long bandwidth =
          Long.parseLong(a.getOrDefault("AVERAGE-BANDWIDTH", a.getOrDefault("BANDWIDTH", "0")));
      int next = i + 1;
      while (next < lines.length && lines[next].trim().isEmpty()) next++;
      require(next < lines.length && !lines[next].trim().startsWith("#"), "HLS 스트림 주소 누락");
      if (pixels > bestPixels
          || (pixels == bestPixels
              && (fps > bestFps || (fps == bestFps && bandwidth > bestBandwidth)))) {
        bestPixels = pixels;
        bestFps = fps;
        bestBandwidth = bandwidth;
        result.video = resolve(url, lines[next].trim());
        audioGroup = a.get("AUDIO");
      }
    }
    if (audioGroup != null) {
      int best = -1;
      for (String line : lines)
        if (line.trim().startsWith("#EXT-X-MEDIA:")) {
          Map<String, String> a = attributes(line.trim());
          if (!"AUDIO".equals(a.get("TYPE")) || !audioGroup.equals(a.get("GROUP-ID"))) continue;
          int score =
              "YES".equals(a.get("DEFAULT")) ? 2 : ("YES".equals(a.get("AUTOSELECT")) ? 1 : 0);
          if (score > best) {
            best = score;
            result.audio = a.containsKey("URI") ? resolve(url, a.get("URI")) : null;
          }
        }
      require(best >= 0, "HLS 오디오 그룹 누락");
    }
    return result;
  }

  public static Track hlsTrack(String url, String text) {
    require(
        text.trim().startsWith("#EXTM3U") && text.contains("#EXT-X-ENDLIST"),
        "완료된 VOD HLS만 저장할 수 있습니다");
    require(
        !text.contains("#EXT-X-GAP") && !text.contains("#EXT-X-DISCONTINUITY"),
        "누락/불연속 HLS는 안전하게 병합할 수 없습니다");
    Track track = new Track();
    long sequence = 0, offset = 0, length = -1;
    String previousUrl = null, key = null, iv = null;
    boolean explicitOffset = false;
    Part init = null;
    for (String raw : text.split("\\r?\\n")) {
      String line = raw.trim();
      if (line.startsWith("#EXT-X-MEDIA-SEQUENCE:"))
        sequence = Long.parseLong(line.substring(line.indexOf(':') + 1));
      else if (line.startsWith("#EXTINF:"))
        track.duration += Double.parseDouble(line.substring(8).split(",")[0]);
      else if (line.startsWith("#EXT-X-KEY:")) {
        Map<String, String> a = attributes(line);
        String method = a.get("METHOD");
        if ("NONE".equals(method)) {
          key = null;
          iv = null;
        } else {
          require(
              "AES-128".equals(method)
                  && "identity".equals(a.getOrDefault("KEYFORMAT", "identity")),
              "DRM/SAMPLE-AES는 지원하지 않습니다");
          require(a.containsKey("URI"), "HLS 키 주소 누락");
          key = resolve(url, a.get("URI"));
          iv = a.get("IV");
        }
      } else if (line.startsWith("#EXT-X-MAP:")) {
        require(init == null, "초기화 세그먼트 변경은 지원하지 않습니다");
        Map<String, String> a = attributes(line);
        require(a.containsKey("URI"), "초기화 주소 누락");
        long mapOffset = 0, mapLength = -1;
        if (a.containsKey("BYTERANGE")) {
          String[] r = a.get("BYTERANGE").split("@");
          require(r.length == 2, "초기화 범위 오프셋 누락");
          mapLength = Long.parseLong(r[0]);
          mapOffset = Long.parseLong(r[1]);
        }
        require(key == null || iv != null, "암호화된 초기화 세그먼트 IV 누락");
        init = new Part(resolve(url, a.get("URI")), mapOffset, mapLength, sequence, key, iv);
        track.parts.add(init);
      } else if (line.startsWith("#EXT-X-BYTERANGE:")) {
        String[] r = line.substring(line.indexOf(':') + 1).split("@");
        length = Long.parseLong(r[0]);
        require(length > 0, "잘못된 바이트 범위");
        explicitOffset = r.length == 2;
        if (explicitOffset) offset = Long.parseLong(r[1]);
        else require(previousUrl != null, "바이트 범위 오프셋 누락");
      } else if (!line.isEmpty() && !line.startsWith("#")) {
        String partUrl = resolve(url, line);
        if (length > 0 && !explicitOffset) require(partUrl.equals(previousUrl), "범위 파일이 변경되었습니다");
        track.parts.add(new Part(partUrl, length > 0 ? offset : 0, length, sequence++, key, iv));
        offset = length > 0 ? offset + length : 0;
        previousUrl = partUrl;
        length = -1;
        require(track.parts.size() <= 100_000, "세그먼트 수 제한 초과");
      }
    }
    require(track.duration > 0 && track.parts.size() > (init == null ? 0 : 1), "HLS 세그먼트 누락");
    return track;
  }

  public static List<Track> dash(String url, String xml) throws Exception {
    require(
        !xml.toUpperCase(Locale.ROOT).contains("<!DOCTYPE")
            && !xml.toUpperCase(Locale.ROOT).contains("<!ENTITY"),
        "외부 엔티티가 있는 MPD는 거부합니다");
    DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
    factory.setExpandEntityReferences(false);
    Element mpd =
        factory
            .newDocumentBuilder()
            .parse(new InputSource(new StringReader(xml)))
            .getDocumentElement();
    require("MPD".equals(mpd.getTagName()), "올바른 MPD가 아닙니다");
    require(!"dynamic".equals(mpd.getAttribute("type")), "라이브 DASH는 지원하지 않습니다");
    require(mpd.getElementsByTagName("ContentProtection").getLength() == 0, "DRM 스트림은 지원하지 않습니다");
    List<Element> periods = children(mpd, "Period");
    require(periods.size() == 1, "단일 Period VOD DASH만 지원합니다");
    Element period = periods.get(0);
    double duration = duration(period.getAttribute("duration"));
    if (duration <= 0) duration = duration(mpd.getAttribute("mediaPresentationDuration"));
    String base = base(base(url, mpd), period);
    Element video = null, audio = null;
    long bestPixels = -1, bestRate = -1;
    double bestFps = -1;
    long bestAudio = -1;
    for (Element adaptation : children(period, "AdaptationSet"))
      for (Element representation : children(adaptation, "Representation")) {
        String mime = inherit(representation, adaptation, "mimeType");
        String kind = inherit(representation, adaptation, "contentType");
        long bitrate = number(representation, "bandwidth", 0);
        if (mime.startsWith("video/") || kind.equals("video")) {
          long pixels =
              Long.parseLong(defaultValue(inherit(representation, adaptation, "width"), "0"))
                  * Long.parseLong(
                      defaultValue(inherit(representation, adaptation, "height"), "0"));
          String rate = defaultValue(inherit(representation, adaptation, "frameRate"), "0");
          String[] fraction = rate.split("/");
          double fps =
              Double.parseDouble(fraction[0])
                  / (fraction.length == 2 ? Double.parseDouble(fraction[1]) : 1);
          if (pixels > bestPixels
              || (pixels == bestPixels
                  && (fps > bestFps || (fps == bestFps && bitrate > bestRate)))) {
            video = representation;
            bestPixels = pixels;
            bestFps = fps;
            bestRate = bitrate;
          }
        } else if ((mime.startsWith("audio/") || kind.equals("audio")) && bitrate > bestAudio) {
          audio = representation;
          bestAudio = bitrate;
        }
      }
    require(video != null || audio != null, "DASH 트랙 없음");
    List<Track> result = new ArrayList<>();
    if (video != null) result.add(dashTrack(base, period, video, duration, false));
    if (audio != null) result.add(dashTrack(base, period, audio, duration, true));
    return result;
  }

  private static Track dashTrack(
      String periodBase, Element period, Element rep, double seconds, boolean audio) {
    Element adaptation = (Element) rep.getParentNode();
    String base = base(base(periodBase, adaptation), rep);
    Track track = new Track();
    track.audio = audio;
    track.duration = seconds;
    track.bitRate = number(rep, "bandwidth", 0);
    track.representation = rep.getAttribute("id");
    track.codecs = inherit(rep, adaptation, "codecs");
    track.language = inherit(rep, adaptation, "lang");
    track.width = Integer.parseInt(defaultValue(inherit(rep, adaptation, "width"), "0"));
    track.height = Integer.parseInt(defaultValue(inherit(rep, adaptation, "height"), "0"));
    String[] fps = defaultValue(inherit(rep, adaptation, "frameRate"), "0").split("/");
    track.frameRate =
        Double.parseDouble(fps[0]) / (fps.length == 2 ? Double.parseDouble(fps[1]) : 1);
    Element template = child(rep, "SegmentTemplate");
    if (template == null) template = child(adaptation, "SegmentTemplate");
    if (template == null) template = child(period, "SegmentTemplate");
    Element list = child(rep, "SegmentList");
    if (list == null) list = child(adaptation, "SegmentList");
    if (template != null) {
      // Merge inherited attributes and timeline rather than silently ignoring parent definitions.
      List<Element> levels =
          Arrays.asList(
              child(period, "SegmentTemplate"),
              child(adaptation, "SegmentTemplate"),
              child(rep, "SegmentTemplate"));
      Map<String, String> attrs = new HashMap<>();
      Element timeline = null;
      for (Element level : levels)
        if (level != null) {
          NamedNodeMap map = level.getAttributes();
          for (int i = 0; i < map.getLength(); i++)
            attrs.put(map.item(i).getNodeName(), map.item(i).getNodeValue());
          if (child(level, "SegmentTimeline") != null) timeline = child(level, "SegmentTimeline");
        }
      long timescale = Long.parseLong(attrs.getOrDefault("timescale", "1"));
      require(timescale > 0, "잘못된 DASH 시간 단위");
      long start = Long.parseLong(attrs.getOrDefault("startNumber", "1"));
      String media = attrs.get("media");
      require(media != null, "DASH 세그먼트 주소 누락");
      if (attrs.containsKey("initialization"))
        track.parts.add(
            new Part(resolve(base, substitute(attrs.get("initialization"), rep, start, 0))));
      if (timeline != null) {
        List<Element> entries = children(timeline, "S");
        long time = 0, number = start;
        double actual = 0;
        for (int i = 0; i < entries.size(); i++) {
          Element s = entries.get(i);
          time = number(s, "t", time);
          long d = number(s, "d", 0), repeat = number(s, "r", 0);
          require(d > 0, "잘못된 DASH 세그먼트 길이");
          if (repeat < 0) {
            long end =
                i + 1 < entries.size()
                    ? number(entries.get(i + 1), "t", -1)
                    : (long) Math.ceil(seconds * timescale)
                        + Long.parseLong(attrs.getOrDefault("presentationTimeOffset", "0"));
            require(end > time, "DASH 반복 범위를 결정할 수 없습니다");
            repeat = (end - time + d - 1) / d - 1;
          }
          require(repeat <= 100_000 && repeat >= 0, "DASH 반복 제한 초과");
          for (long n = 0; n <= repeat; n++) {
            track.parts.add(new Part(resolve(base, substitute(media, rep, number++, time))));
            time += d;
            actual += (double) d / timescale;
            require(track.parts.size() <= 100_000, "세그먼트 수 제한 초과");
          }
        }
        if (track.duration <= 0) track.duration = actual;
      } else {
        long d = Long.parseLong(attrs.getOrDefault("duration", "0"));
        require(d > 0 && seconds > 0, "DASH 세그먼트 길이 누락");
        long count = (long) Math.ceil(seconds * timescale / d);
        require(count <= 100_000, "세그먼트 수 제한 초과");
        for (long i = 0; i < count; i++)
          track.parts.add(new Part(resolve(base, substitute(media, rep, start + i, i * d))));
      }
    } else if (list != null) {
      Element init = child(list, "Initialization");
      if (init != null)
        track.parts.add(
            rangePart(resolve(base, init.getAttribute("sourceURL")), init.getAttribute("range")));
      for (Element segment : children(list, "SegmentURL"))
        track.parts.add(
            rangePart(
                resolve(base, segment.getAttribute("media")), segment.getAttribute("mediaRange")));
    } else {
      // A complete BaseURL file (including SegmentBase/indexRange) needs no sidx parsing.
      require(
          child(rep, "BaseURL") != null || child(adaptation, "BaseURL") != null,
          "지원하지 않는 DASH 세그먼트 구조");
      track.parts.add(new Part(base));
    }
    require(!track.parts.isEmpty(), "DASH 세그먼트 없음");
    return track;
  }

  private static Part rangePart(String url, String range) {
    if (range.isEmpty()) return new Part(url);
    String[] r = range.split("-");
    require(r.length == 2, "잘못된 DASH 범위");
    long start = Long.parseLong(r[0]), end = Long.parseLong(r[1]);
    require(end >= start, "잘못된 DASH 범위");
    return new Part(url, start, end - start + 1, 0, null, null);
  }

  static String substitute(String pattern, Element rep, long number, long time) {
    String escaped = pattern.replace("$$", "\u0001");
    Matcher m =
        Pattern.compile("\\$(RepresentationID|Bandwidth|Number|Time)(%0(\\d+)d)?\\$")
            .matcher(escaped);
    StringBuffer out = new StringBuffer();
    while (m.find()) {
      String value;
      switch (m.group(1)) {
        case "RepresentationID":
          value = rep.getAttribute("id");
          break;
        case "Bandwidth":
          value = rep.getAttribute("bandwidth");
          break;
        case "Number":
          value = Long.toString(number);
          break;
        default:
          value = Long.toString(time);
      }
      if (m.group(3) != null) {
        int width = Integer.parseInt(m.group(3));
        require(width <= 20, "잘못된 DASH 서식");
        value = String.format(Locale.ROOT, "%0" + width + "d", Long.parseLong(value));
      }
      m.appendReplacement(out, Matcher.quoteReplacement(value));
    }
    m.appendTail(out);
    require(out.indexOf("$") < 0, "지원하지 않는 DASH 주소 서식");
    return out.toString().replace('\u0001', '$');
  }

  private static double duration(String iso) {
    if (iso.isEmpty()) return 0;
    return java.time.Duration.parse(iso).toMillis() / 1000.0;
  }

  private static long number(Element element, String key, long fallback) {
    return element.hasAttribute(key) ? Long.parseLong(element.getAttribute(key)) : fallback;
  }

  private static String defaultValue(String value, String fallback) {
    return value.isEmpty() ? fallback : value;
  }

  private static String inherit(Element rep, Element parent, String key) {
    return defaultValue(rep.getAttribute(key), parent.getAttribute(key));
  }

  private static String base(String url, Element element) {
    Element b = child(element, "BaseURL");
    return b == null ? url : resolve(url, b.getTextContent().trim());
  }

  private static Element child(Element element, String name) {
    List<Element> list = children(element, name);
    return list.isEmpty() ? null : list.get(0);
  }

  private static List<Element> children(Element element, String name) {
    List<Element> result = new ArrayList<>();
    for (Node n = element.getFirstChild(); n != null; n = n.getNextSibling())
      if (n instanceof Element && name.equals(n.getNodeName())) result.add((Element) n);
    return result;
  }

  public static String resolve(String base, String child) {
    return URI.create(base).resolve(child).toString();
  }

  private static void require(boolean condition, String message) {
    if (!condition) throw new IllegalArgumentException(message);
  }
}
