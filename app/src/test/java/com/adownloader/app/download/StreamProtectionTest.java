package com.adownloader.app.download;

import static org.junit.Assert.*;

import org.junit.Test;

public class StreamProtectionTest {
  private static final String URL = "https://media.example/index";
  private static final String WIDEVINE = "urn:uuid:edef8ba9-79d6-4ace-a3c8-27dcd51d21ed";

  @Test
  public void hlsMasterSessionKeyDoesNotBlockSelectedClearVariant() {
    var selection =
        ManifestParser.hlsSelection(
            URL,
            """
#EXTM3U
#EXT-X-SESSION-KEY:METHOD=SAMPLE-AES,URI="skd://license",KEYFORMAT="com.apple.streamingkeydelivery"
#EXT-X-STREAM-INF:BANDWIDTH=1000000
clear.m3u8
""");
    assertEquals("https://media.example/clear.m3u8", selection.video);
    assertNull(ManifestParser.hlsTrack(selection.video, hls("")).parts.get(0).keyUrl);
  }

  @Test
  public void hlsOnlyRecognizedLicenseSignalsAreDrm() {
    assertEquals(
        DownloadError.Category.ENCRYPTION_UNSUPPORTED, hlsFailure("METHOD=SAMPLE-AES,URI=\"key\""));
    assertEquals(
        DownloadError.Category.ENCRYPTION_UNSUPPORTED,
        hlsFailure("METHOD=AES-128,URI=\"key\",KEYFORMAT=\"vendor.unknown\""));
    assertEquals(
        DownloadError.Category.DRM,
        hlsFailure(
            "METHOD=SAMPLE-AES,URI=\"skd://license\",KEYFORMAT=\"com.apple.streamingkeydelivery\""));
    assertEquals(
        DownloadError.Category.DRM,
        hlsFailure("METHOD=SAMPLE-AES-CTR,URI=\"data:fixture\",KEYFORMAT=\"" + WIDEVINE + "\""));
    var aes = ManifestParser.hlsTrack(URL, hls("#EXT-X-KEY:METHOD=AES-128,URI=\"key\",IV=0x2\n"));
    assertEquals("https://media.example/key", aes.parts.get(0).keyUrl);
    assertEquals("0x2", aes.parts.get(0).iv);
  }

  @Test
  public void dashChecksOnlySelectedTracksAndTheirParents() throws Exception {
    String unselected =
        "<d:Representation id='other' bandwidth='1' width='128' height='72'>"
            + protection(WIDEVINE)
            + "</d:Representation>";
    assertEquals("original", ManifestParser.dash(URL, mpd("", unselected)).get(0).representation);
    var cenc =
        assertThrows(
            DownloadError.ManifestFailure.class,
            () ->
                ManifestParser.dash(URL, mpd(protection("urn:mpeg:dash:mp4protection:2011"), "")));
    assertEquals(DownloadError.Category.ENCRYPTION_UNSUPPORTED, cenc.category);
    var drm =
        assertThrows(
            DownloadError.ManifestFailure.class,
            () -> ManifestParser.dash(URL, mpd(protection(WIDEVINE), "")));
    assertEquals(DownloadError.Category.DRM, drm.category);
  }

  @Test
  public void forbiddenDoesNotImplyDrmOrAuthenticationWithoutEvidence() {
    assertEquals(
        DownloadError.Category.ACCESS_BLOCKED,
        DownloadError.http(403, URL, true, false, 0).category);
    assertEquals(
        DownloadError.Category.AUTH, DownloadError.http(403, URL, false, true, 0).category);
    assertEquals(
        DownloadError.Category.AUTH, DownloadError.http(401, URL, false, false, 0).category);
  }

  private static DownloadError.Category hlsFailure(String key) {
    return assertThrows(
            DownloadError.ManifestFailure.class,
            () -> ManifestParser.hlsTrack(URL, hls("#EXT-X-KEY:" + key + "\n")))
        .category;
  }

  private static String hls(String tags) {
    return "#EXTM3U\n" + tags + "#EXTINF:1,\nsegment.ts\n#EXT-X-ENDLIST\n";
  }

  private static String protection(String scheme) {
    return "<d:ContentProtection schemeIdUri='" + scheme + "'/>";
  }

  private static String mpd(String inherited, String unselected) {
    return """
           <d:MPD xmlns:d="urn:mpeg:dash:schema:mpd:2011" mediaPresentationDuration="PT1S">
             <d:Period><d:AdaptationSet mimeType="video/mp4">
               %s%s
               <d:Representation id="original" bandwidth="1000" width="1920" height="1080">
                 <d:BaseURL>clear.mp4</d:BaseURL>
               </d:Representation>
             </d:AdaptationSet></d:Period>
           </d:MPD>
           """
        .formatted(inherited, unselected);
  }
}
