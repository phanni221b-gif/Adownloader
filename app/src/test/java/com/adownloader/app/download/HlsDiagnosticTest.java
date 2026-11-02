package com.adownloader.app.download;

import static org.junit.Assert.*;

import java.net.*;
import java.util.*;
import org.junit.Test;

public class HlsDiagnosticTest {
  private static HttpSource.Document doc(String text, String mime) throws Exception {
    var response =
        new HttpURLConnection(new URL("https://cdn.test/private/path?token=DO_NOT_REPORT")) {
          public void connect() {}

          public void disconnect() {}

          public boolean usingProxy() {
            return false;
          }

          public int getResponseCode() {
            return 200;
          }

          public String getHeaderField(String name) {
            return name.equalsIgnoreCase("Content-Type") ? mime : null;
          }
        };
    return new HttpSource.Document(
        "https://cdn.test/private/path?token=DO_NOT_REPORT", text, response, null);
  }

  @Test
  public void correctHlsSignatureOverridesWrongMimeAndBom() throws Exception {
    assertTrue(
        HlsDiagnostic.body(
                doc("\ufeff#EXTM3U\n#EXTINF:1,\na.ts\n#EXT-X-ENDLIST", "text/plain"), "root")
            .startsWith("#EXTM3U"));
  }

  @Test
  public void htmlChallengeAndLoginAreNotMalformedPlaylist() throws Exception {
    for (String text :
        List.of(
            "<html>cloudflare challenge token=PRIVATE</html>",
            "<html><form><input type=password></form></html>"))
      try {
        HlsDiagnostic.body(doc(text, "text/html; secret=PRIVATE"), "root");
        fail();
      } catch (DownloadError e) {
        assertEquals(
            text.contains("password")
                ? DownloadError.Category.AUTH
                : DownloadError.Category.ACCESS_BLOCKED,
            e.category);
        assertEquals(HlsDiagnostic.Stage.ROOT_BODY, e.diagnostic.stage);
        assertEquals("text/html", e.diagnostic.mime);
        assertFalse(e.diagnostic.safeFields().toString().contains("PRIVATE"));
        assertFalse(e.diagnostic.safeFields().toString().contains("DO_NOT_REPORT"));
        assertFalse(e.diagnostic.safeFields().toString().contains("/private/path"));
      }
  }

  @Test
  public void ordinaryNonHtmlGarbageIsManifestInvalidAtVariantBody() throws Exception {
    try {
      HlsDiagnostic.body(doc("not a playlist", "text/plain"), "variant");
      fail();
    } catch (DownloadError e) {
      assertEquals(DownloadError.Category.MANIFEST_INVALID, e.category);
      assertEquals(HlsDiagnostic.Stage.VARIANT_BODY, e.diagnostic.stage);
    }
  }

  @Test
  public void parserStagesPreserveUnsupportedAndNeverExposeAttributeValues() throws Exception {
    var d = doc("#EXTM3U", "application/octet-stream");
    var attr =
        HlsDiagnostic.parse(d, d.text, "master", new NumberFormatException("secret=PRIVATE"));
    assertEquals(HlsDiagnostic.Stage.ATTRIBUTE, attr.diagnostic.stage);
    assertEquals("EXT-X-STREAM-INF", attr.diagnostic.tag);
    assertFalse(attr.diagnostic.safeFields().toString().contains("PRIVATE"));
    var unsupported =
        HlsDiagnostic.parse(
            d,
            d.text,
            "media",
            new DownloadError.ManifestFailure(
                DownloadError.Category.HLS_UNSUPPORTED, "HLS_INIT_CHANGE"));
    assertEquals(HlsDiagnostic.Stage.UNSUPPORTED, unsupported.diagnostic.stage);
    assertEquals("EXT-X-MAP", unsupported.diagnostic.tag);
  }

  @Test
  public void blockedPreviewShowsSafeStageStatusAndRedirectInsteadOfInventedMediaDetails() {
    var e =
        new DownloadError(DownloadError.Category.ACCESS_BLOCKED)
            .diagnostic(
                new HlsDiagnostic(
                    "https://cdn.test/private?token=SECRET",
                    403,
                    "text/html",
                    1,
                    "root",
                    HlsDiagnostic.Stage.ROOT_HTTP,
                    "",
                    DownloadError.Category.ACCESS_BLOCKED));
    String label = AccessPresentation.preview(e);
    assertTrue(label.contains("HTTP 403"));
    assertTrue(label.contains("서버 이동 후"));
    assertFalse(label.contains("SECRET"));
    assertFalse(label.contains("private"));
  }
}
