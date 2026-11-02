package com.adownloader.app.download;

import static org.junit.Assert.*;

import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.*;

public class ManifestResponseCacheTest {
  AtomicLong now = new AtomicLong(100000);
  ManifestResponseCache cache = new ManifestResponseCache(now::get);
  String page = "https://page.test/a", url = "https://cdn.test/movie/master.m3u8?token=PRIVATE";
  byte[] body = "#EXTM3U\n#EXTINF:1,\npart.ts\n#EXT-X-ENDLIST\n".getBytes(StandardCharsets.UTF_8);

  MediaNetworkSession.Observation observation(int tab, int frame, int status) {
    return new MediaNetworkSession.Observation(
        url,
        page,
        frame,
        status,
        now.get(),
        Map.of("Cookie", "sid=PRIVATE"),
        List.of("sec-fetch-dest"),
        tab);
  }

  void capture(String id, int tab, int frame) throws Exception {
    cache.page(tab, page);
    assertTrue(
        cache.begin(
            id,
            observation(tab, frame, 200),
            Map.of("content-type", "application/vnd.apple.mpegurl"),
            List.of()));
    assertTrue(cache.chunk(id, tab, 0, body));
    assertTrue(cache.end(id, tab, 1, body.length));
  }

  @Test
  public void exactBindingTtlAndNavigationPreventCrossVideoReuse() throws Exception {
    capture("response-1", 3, 2);
    assertArrayEquals(body, cache.get(url, page, 3, 2, new TransferControl()).body());
    assertNull(cache.get(url, page, 4, 2, new TransferControl()));
    assertNull(cache.get(url, page, 3, 1, new TransferControl()));
    assertNull(cache.get(url + "x", page, 3, 2, new TransferControl()));
    cache.page(3, "https://page.test/b");
    assertNull(cache.get(url, page, 3, 2, new TransferControl()));
    assertEquals(0, cache.size());
    capture("response-2", 3, 2);
    now.addAndGet(60001);
    assertNull(cache.get(url, page, 3, 2, new TransferControl()));
    assertEquals(0, cache.weight());
  }

  @Test
  public void unsuccessfulIncompleteOutOfOrderAndNonManifestBodiesAreRejected() throws Exception {
    cache.page(3, page);
    assertFalse(cache.begin("response-1", observation(3, 2, 403), Map.of(), List.of()));
    assertTrue(cache.begin("response-2", observation(3, 2, 200), Map.of(), List.of()));
    assertFalse(cache.chunk("response-2", 3, 1, body));
    assertEquals(0, cache.weight());
    assertTrue(cache.begin("response-3", observation(3, 2, 200), Map.of(), List.of()));
    assertTrue(cache.chunk("response-3", 3, 0, "SEGMENT".getBytes(StandardCharsets.UTF_8)));
    assertFalse(cache.end("response-3", 3, 1, 7));
    assertEquals(0, cache.size());
    assertTrue(cache.begin("response-4", observation(3, 2, 200), Map.of(), List.of()));
    assertTrue(cache.chunk("response-4", 3, 0, body));
    cache.abort("response-4", 3);
    assertNull(cache.get(url, page, 3, 2, new TransferControl()));
  }

  @Test
  public void boundedChunksEntriesAndClosedSessionReleaseAllEvidence() throws Exception {
    cache.page(3, page);
    assertTrue(cache.begin("response-1", observation(3, 2, 200), Map.of(), List.of()));
    assertFalse(cache.chunk("response-1", 3, 0, new byte[49153]));
    assertEquals(0, cache.weight());
    for (int i = 0; i < 25; i++) capture("response-" + (i + 2), 3, i);
    assertEquals(16, cache.size());
    assertTrue(cache.weight() <= ManifestResponseCache.MAX_BYTES);
    cache.close();
    assertEquals(0, cache.weight());
    assertNull(cache.get(url, page, 3, 24, new TransferControl()));
  }

  @Test
  public void anOlderRequestCannotReplaceNewerPlaybackEvidenceAfterCompletingLate()
      throws Exception {
    cache.page(3, page);
    assertTrue(
        cache.begin(
            "response-1",
            observation(3, 2, 200),
            Map.of("request-started-at", "99999"),
            List.of()));
    assertTrue(cache.chunk("response-1", 3, 0, body));
    assertTrue(cache.end("response-1", 3, 1, body.length));
    now.incrementAndGet();
    assertFalse(
        cache.begin(
            "response-2",
            observation(3, 2, 200),
            Map.of("request-started-at", "99998"),
            List.of()));
    assertArrayEquals(body, cache.get(url, page, 3, 2, new TransferControl()).body());
  }
}
