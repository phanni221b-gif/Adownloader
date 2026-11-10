package com.adownloader.app.download;

import static org.junit.Assert.*;

import org.junit.Test;

public class HlsVodMatrixTest {
  private static final String U = "https://cdn.test/v/list.m3u8?private=root";

  private static ManifestParser.Track track(String text) {
    return ManifestParser.hlsTrack(U, "#EXTM3U\n" + text + "\n#EXT-X-ENDLIST\n");
  }

  private static void rejects(String text, DownloadError.Category category) {
    try {
      track(text);
      fail("Must reject incomplete or protected input");
    } catch (DownloadError.ManifestFailure e) {
      assertEquals(category, e.category);
    }
  }

  @Test
  public void mediaByteRangesExplicitThenImplicitStayOnSameResource() {
    var t =
        track(
            "#EXT-X-VERSION:4\n"
                + "#EXT-X-TARGETDURATION:2\n"
                + "#EXTINF:2,\n"
                + "#EXT-X-BYTERANGE:100@0\n"
                + "file.ts\n"
                + "#EXTINF:2,\n"
                + "#EXT-X-BYTERANGE:80\n"
                + "file.ts");
    assertEquals(100, t.parts.get(1).offset);
    assertEquals(80, t.parts.get(1).length);
    assertEquals(4, t.duration, .001);
    rejects(
        "#EXTINF:1,\na.ts\n#EXTINF:1,\n#EXT-X-BYTERANGE:10\na.ts",
        DownloadError.Category.MANIFEST_INVALID);
  }

  @Test
  public void relativeAbsoluteAndQueryOnlyUrisFollowRfcWithoutInventedTokens() {
    var t =
        track(
            "#EXTINF:1,\n"
                + "?seg=1\n"
                + "#EXTINF:1,\n"
                + "../a.ts?seg=2\n"
                + "#EXTINF:1,\n"
                + "https://other.test/c.ts?seg=3");
    assertEquals("https://cdn.test/v/list.m3u8?seg=1", t.parts.get(0).url);
    assertEquals("https://cdn.test/a.ts?seg=2", t.parts.get(1).url);
    assertEquals("https://other.test/c.ts?seg=3", t.parts.get(2).url);
  }

  @Test
  public void identityAes128KeepsMediaSequenceForImplicitIv() {
    var t =
        track(
            "#EXT-X-MEDIA-SEQUENCE:41\n"
                + "#EXT-X-KEY:METHOD=AES-128,KEYFORMAT=\"identity\",URI=\"../key.bin\"\n"
                + "#EXTINF:1,\n"
                + "a.ts\n"
                + "#EXTINF:1,\n"
                + "b.ts");
    assertEquals(41, t.parts.get(0).sequence);
    assertEquals(42, t.parts.get(1).sequence);
    assertNull(t.parts.get(0).iv);
    assertEquals("https://cdn.test/key.bin", t.parts.get(1).keyUrl);
  }

  @Test
  public void audioGroupSupportsMuxedAndSeparateRenditionsWithQuotedComma() {
    String head =
        "#EXTM3U\n#EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID=\"a\",NAME=\"Main, stereo\",DEFAULT=YES";
    String variant =
        "\n"
            + "#EXT-X-STREAM-INF:BANDWIDTH=200,RESOLUTION=1920x1080,FRAME-RATE=60,AUDIO=\"a\",CODECS=\"avc1.640028,mp4a.40.2\"\n"
            + "# comment\n"
            + "v.m3u8\n";
    assertNull(ManifestParser.hlsSelection(U, head + variant).audio);
    var s = ManifestParser.hlsSelection(U, head + ",URI=\"audio.m3u8\"" + variant);
    assertEquals("https://cdn.test/v/audio.m3u8", s.audio);
    assertEquals("60", s.selectedAttributes.get("FRAME-RATE"));
  }

  @Test
  public void repeatedEquivalentInitializationAllowedButChangedInitIsExplicitUnsupported() {
    track(
        "#EXT-X-MAP:URI=\"i.mp4\",BYTERANGE=\"20@0\"\n"
            + "#EXTINF:1,\n"
            + "a.m4s\n"
            + "#EXT-X-MAP:URI=\"i.mp4\",BYTERANGE=\"20@0\"\n"
            + "#EXTINF:1,\n"
            + "b.m4s");
    rejects(
        "#EXT-X-MAP:URI=\"i.mp4\"\n"
            + "#EXTINF:1,\n"
            + "a.m4s\n"
            + "#EXT-X-MAP:URI=\"different.mp4\"\n"
            + "#EXTINF:1,\n"
            + "b.m4s",
        DownloadError.Category.HLS_UNSUPPORTED);
  }

  @Test
  public void protectedSessionKeySampleAesGapAndDeltaNeverComplete() {
    rejects(
        "#EXT-X-SESSION-KEY:METHOD=SAMPLE-AES,URI=\"key\"\n#EXTINF:1,\na.ts",
        DownloadError.Category.DRM);
    rejects(
        "#EXT-X-KEY:METHOD=SAMPLE-AES,URI=\"key\"\n#EXTINF:1,\na.ts", DownloadError.Category.DRM);
    rejects("#EXT-X-GAP\n#EXTINF:1,\na.ts", DownloadError.Category.HLS_GAP);
    rejects(
        "#EXT-X-SKIP:SKIPPED-SEGMENTS=2\n#EXTINF:1,\na.ts", DownloadError.Category.HLS_UNSUPPORTED);
  }

  @Test
  public void malformedDurationsAttributesAndMissingUriAreRejected() {
    rejects("#EXTINF:NaN,\na.ts", DownloadError.Category.MANIFEST_INVALID);
    rejects("#EXTINF:2,", DownloadError.Category.MANIFEST_INVALID);
    rejects("#EXT-X-TARGETDURATION:0\n#EXTINF:1,\na.ts", DownloadError.Category.MANIFEST_INVALID);
    try {
      ManifestParser.hlsSelection(U, "#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=1,BROKEN\nv.m3u8");
      fail();
    } catch (DownloadError.ManifestFailure expected) {
      assertEquals(DownloadError.Category.MANIFEST_INVALID, expected.category);
    }
  }
}
