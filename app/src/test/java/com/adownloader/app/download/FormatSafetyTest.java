package com.adownloader.app.download;

import static org.junit.Assert.*;

import org.junit.Test;

public class FormatSafetyTest {
  @Test
  public void unsupportedStructuresHaveExplicitCategoriesRatherThanStringMatching()
      throws Exception {
    String root = "#EXTM3U\n#EXTINF:1,\none.ts\n";
    failure(root, DownloadError.Category.LIVE_UNSUPPORTED);
    failure(root + "#EXT-X-GAP\n#EXT-X-ENDLIST", DownloadError.Category.HLS_GAP);
    failure(
        root + "#EXT-X-DISCONTINUITY\n#EXT-X-ENDLIST", DownloadError.Category.HLS_DISCONTINUITY);
    failure(
        "#EXTM3U\n#EXT-X-KEY:METHOD=SAMPLE-AES,URI=\"key\"\n#EXTINF:1,\none.ts\n#EXT-X-ENDLIST",
        DownloadError.Category.DRM);
    try {
      ManifestParser.dash("https://example.invalid/list.mpd", "<MPD><Period/><Period/></MPD>");
      fail();
    } catch (DownloadError.ManifestFailure error) {
      assertEquals(DownloadError.Category.DASH_MULTIPERIOD, error.category);
    }
  }

  private void failure(String playlist, DownloadError.Category category) {
    try {
      ManifestParser.hlsTrack("https://example.invalid/list.m3u8", playlist);
      fail();
    } catch (DownloadError.ManifestFailure error) {
      assertEquals(category, error.category);
    }
  }

  @Test
  public void alternateAudioGroupsAndSubtitleMetadataPreserveHighestVideoAndDefaultLanguage() {
    String manifest =
        "#EXTM3U\n"
            + "#EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID=\"a\",NAME=\"Korean\",LANGUAGE=\"ko\",DEFAULT=YES,URI=\"ko.m3u8\"\n"
            + "#EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID=\"a\",NAME=\"English\",LANGUAGE=\"en\",URI=\"en.m3u8\"\n"
            + "#EXT-X-MEDIA:TYPE=SUBTITLES,GROUP-ID=\"s\",NAME=\"Korean\",LANGUAGE=\"ko\",URI=\"sub.m3u8\"\n"
            + "#EXT-X-STREAM-INF:BANDWIDTH=9000000,RESOLUTION=3840x2160,AUDIO=\"a\",SUBTITLES=\"s\"\n"
            + "high.m3u8\n";
    var selected = ManifestParser.hlsSelection("https://example.invalid/master.m3u8", manifest);
    assertEquals("https://example.invalid/high.m3u8", selected.video);
    assertEquals("https://example.invalid/ko.m3u8", selected.audio);
    assertEquals(2, selected.audioRenditions.size());
    assertEquals("en", selected.audioRenditions.get(1).get("LANGUAGE"));
    assertEquals("https://example.invalid/sub.m3u8", selected.subtitleRenditions.get(0).get("URI"));
  }
}
