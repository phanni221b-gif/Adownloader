package com.adownloader.app.download;

import static org.junit.Assert.*;

import java.util.*;
import org.junit.Test;

/** Regression: real VOD playlists with ad/intro boundaries were rejected outright before v0.7. */
public class DiscontinuityGroupsTest {
  private static final String U = "https://cdn.example.invalid/v/list.m3u8";

  @Test
  public void discontinuityMarkersSplitTheTimelineInsteadOfFailing() {
    ManifestParser.Track track =
        ManifestParser.hlsTrack(
            U,
            "#EXTM3U\n#EXT-X-TARGETDURATION:4\n#EXT-X-DISCONTINUITY-SEQUENCE:3\n"
                + "#EXTINF:4,\nintro0.ts\n#EXTINF:4,\nintro1.ts\n"
                + "#EXT-X-DISCONTINUITY\n"
                + "#EXTINF:4,\nmain0.ts\n#EXTINF:2.5,\nmain1.ts\n"
                + "#EXT-X-DISCONTINUITY\n"
                + "#EXTINF:3,\nend0.ts\n#EXT-X-ENDLIST\n");
    assertEquals(5, track.parts.size());
    assertEquals(17.5, track.duration, 0.0001);
    int[] expected = {0, 0, 1, 1, 2};
    for (int i = 0; i < expected.length; i++)
      assertEquals(expected[i], track.parts.get(i).discontinuity);
    List<List<String>> groups = SegmentGroups.split(track.parts, names(track));
    assertEquals(
        List.of(List.of("intro0.ts", "intro1.ts"), List.of("main0.ts", "main1.ts"), List.of("end0.ts")),
        groups);
  }

  @Test
  public void leadingAndRepeatedMarkersDoNotCreateEmptyGroups() {
    ManifestParser.Track track =
        ManifestParser.hlsTrack(
            U,
            "#EXTM3U\n#EXT-X-DISCONTINUITY\n#EXTINF:1,\na.ts\n"
                + "#EXT-X-DISCONTINUITY\n#EXT-X-DISCONTINUITY\n#EXTINF:1,\nb.ts\n#EXT-X-ENDLIST");
    assertEquals(0, track.parts.get(0).discontinuity);
    assertEquals(1, track.parts.get(1).discontinuity);
    assertEquals(2, SegmentGroups.split(track.parts, names(track)).size());
  }

  @Test
  public void fragmentedMp4InitializationIsRepeatedForEveryGroup() {
    ManifestParser.Track track =
        ManifestParser.hlsTrack(
            U,
            "#EXTM3U\n#EXT-X-MAP:URI=\"init.mp4\"\n#EXTINF:2,\na.m4s\n"
                + "#EXT-X-DISCONTINUITY\n#EXTINF:2,\nb.m4s\n#EXT-X-ENDLIST");
    assertEquals(
        List.of(List.of("init.mp4", "a.m4s"), List.of("init.mp4", "b.m4s")),
        SegmentGroups.split(track.parts, names(track)));
  }

  @Test
  public void refreshedPlanMustKeepTheSameTimelineGroups() {
    String a = "#EXTM3U\n#EXTINF:1,\na.ts\n#EXT-X-DISCONTINUITY\n#EXTINF:1,\nb.ts\n#EXT-X-ENDLIST";
    String moved = "#EXTM3U\n#EXTINF:1,\na.ts\n#EXTINF:1,\nb.ts\n#EXT-X-ENDLIST";
    List<ManifestParser.Track> saved = List.of(ManifestParser.hlsTrack(U, a));
    assertTrue(RefreshPlanMatcher.compatible(saved, List.of(ManifestParser.hlsTrack(U, a))));
    assertFalse(RefreshPlanMatcher.compatible(saved, List.of(ManifestParser.hlsTrack(U, moved))));
  }

  @Test
  public void gapsLiveAndDrmAreStillRejected() {
    for (String bad :
        List.of(
            "#EXTM3U\n#EXTINF:1,\na.ts\n",
            "#EXTM3U\n#EXT-X-GAP\n#EXTINF:1,\na.ts\n#EXT-X-ENDLIST",
            "#EXTM3U\n#EXT-X-KEY:METHOD=SAMPLE-AES,URI=\"k\"\n#EXTINF:1,\na.ts\n#EXT-X-ENDLIST"))
      try {
        ManifestParser.hlsTrack(U, bad);
        fail(bad);
      } catch (DownloadError.ManifestFailure expected) {
      }
  }

  private static List<String> names(ManifestParser.Track track) {
    List<String> out = new ArrayList<>();
    for (ManifestParser.Part part : track.parts)
      out.add(part.url.substring(part.url.lastIndexOf('/') + 1));
    return out;
  }
}
