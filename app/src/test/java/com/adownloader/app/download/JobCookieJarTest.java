package com.adownloader.app.download;

import static org.junit.Assert.*;

import java.net.*;
import java.util.*;
import org.junit.Test;

public class JobCookieJarTest {
  private boolean set(JobCookieJar j, String url, String cookie) {
    return j.accept(URI.create(url), Map.of("Set-Cookie", List.of(cookie)));
  }

  @Test
  public void sameJobDomainPathSecureAndSameSiteFollowServerScope() {
    try (JobCookieJar j =
        new JobCookieJar(
            "https://www.example.test/root", Map.of("Referer", "https://www.example.test/page"))) {
      assertTrue(
          set(
              j,
              "https://www.example.test/root",
              "shared=private; Domain=example.test; Path=/stream/; Secure; SameSite=Lax"));
      assertTrue(
          j.header(URI.create("https://cdn.example.test/stream/part")).contains("shared=private"));
      assertEquals("", j.header(URI.create("https://cdn.example.test/streaming/part")));
      assertEquals("", j.header(URI.create("http://cdn.example.test/stream/part")));
      assertEquals("", j.header(URI.create("https://other.test/stream/part")));
    }
  }

  @Test
  public void crossSiteNoneRequiresSecureAndStrictIsNotSent() {
    try (JobCookieJar j =
        new JobCookieJar("https://cdn.test/root", Map.of("Referer", "https://page.test/post"))) {
      assertTrue(set(j, "https://cdn.test/root", "a=private; Secure; SameSite=Strict; Path=/"));
      assertEquals("", j.header(URI.create("https://cdn.test/part")));
      assertFalse(set(j, "https://cdn.test/root", "b=private; SameSite=None; Path=/"));
      assertTrue(set(j, "https://cdn.test/root", "b=private; SameSite=None; Secure; Path=/"));
      assertEquals("b=private", j.header(URI.create("https://cdn.test/part")));
    }
  }

  @Test
  public void publicSuffixForeignDomainAndPrefixesCannotLeakCookies() {
    try (JobCookieJar j = new JobCookieJar("https://foo.github.io/root", Map.of())) {
      assertFalse(set(j, "https://foo.github.io/root", "a=private; Domain=github.io"));
      assertFalse(set(j, "https://foo.github.io/root", "a=private; Domain=other.test"));
      assertFalse(
          set(
              j,
              "https://foo.github.io/root",
              "__Host-a=private; Secure; Domain=foo.github.io; Path=/"));
      assertFalse(set(j, "https://foo.github.io/root", "__Secure-a=private; Path=/"));
      assertFalse(set(j, "https://foo.github.io/root", "__Host-a=private; Secure"));
      assertFalse(set(j, "https://foo.example.co.uk/root", "a=private; Domain=co.uk"));
      assertEquals("", j.header(URI.create("https://bar.github.io/root")));
    }
  }

  @Test
  public void seedStaysOnItsOriginAndResponseCookieOverridesOnlyMatchingPath() {
    try (JobCookieJar j =
        new JobCookieJar("https://media.test/root", Map.of("Cookie", "sid=old"))) {
      assertEquals("sid=old", j.header(URI.create("https://media.test/child")));
      assertEquals("", j.header(URI.create("https://sub.media.test/child")));
      assertEquals("", j.header(URI.create("https://media.test:444/child")));
      set(j, "https://media.test/root", "sid=new; Path=/child");
      assertEquals("sid=new", j.header(URI.create("https://media.test/child")));
      assertEquals("sid=old", j.header(URI.create("https://media.test/childish")));
    }
  }

  @Test
  public void isolatedJobsAndEncryptedAdapterSnapshotsDoNotUseGlobalCookieHandler()
      throws Exception {
    CookieHandler before = CookieHandler.getDefault();
    try (JobCookieJar a = new JobCookieJar("https://a.test/root", Map.of());
        JobCookieJar b = new JobCookieJar("https://a.test/root", Map.of())) {
      set(a, "https://a.test/root", "x=PRIVATE; Path=/");
      assertEquals("", b.header(URI.create("https://a.test/part")));
      b.restore(a.snapshot());
      assertEquals("x=PRIVATE", b.header(URI.create("https://a.test/part")));
      set(a, "https://a.test/root", "x=deleted; Path=/; Max-Age=0");
      assertEquals("", a.header(URI.create("https://a.test/part")));
      a.close();
      assertEquals("", a.header(URI.create("https://a.test/part")));
    }
    assertSame(before, CookieHandler.getDefault());
  }

  @Test
  public void capturedSegmentRefererIsScopedButCookieNeverPropagatesToOtherUrls() {
    CapturedRequestContexts contexts =
        new CapturedRequestContexts(
            Map.of(
                "https://cdn.test/stream/one.ts",
                Map.of(
                    "Referer",
                    "https://player.test/player",
                    "Cookie",
                    "private=one",
                    "Authorization",
                    "do-not-forward")));
    assertEquals("private=one", contexts.forUrl("https://cdn.test/stream/one.ts").get("Cookie"));
    assertEquals(
        "https://player.test/player",
        contexts.forUrl("https://cdn.test/stream/two.ts").get("Referer"));
    assertNull(contexts.forUrl("https://cdn.test/stream/two.ts").get("Cookie"));
    assertTrue(contexts.forUrl("https://other.test/stream/two.ts").isEmpty());
    assertNull(contexts.forUrl("https://cdn.test/stream/one.ts").get("Authorization"));
    contexts.close();
  }

  @Test
  public void responseDeletionDoesNotReviveCapturedSeedAfterEncryptedStateRestore()
      throws Exception {
    var uri = URI.create("https://cdn.test/root");
    var jar = new JobCookieJar(uri.toString(), Map.of("Cookie", "login=old; keep=yes"));
    jar.accept(uri, Map.of("Set-Cookie", List.of("login=; Max-Age=0; Path=/")));
    assertFalse(jar.header(uri).contains("login="));
    var restored = new JobCookieJar(uri.toString(), Map.of("Cookie", "login=old; keep=yes"));
    restored.restore(jar.snapshot());
    assertFalse(restored.header(uri).contains("login="));
    assertTrue(restored.header(uri).contains("keep=yes"));
  }

  @Test
  public void topPageSiteOverridesSameSiteIframeReferer() {
    var uri = URI.create("https://cdn.test/root");
    var jar = new JobCookieJar(uri.toString(), Map.of("Referer", "https://cdn.test/frame"));
    jar.pageContext("https://unrelated.test/post");
    jar.accept(
        uri,
        Map.of(
            "Set-Cookie",
            List.of(
                "strict=x; Secure; SameSite=Strict; Path=/",
                "lax=x; Secure; SameSite=Lax; Path=/",
                "none=yes; Secure; SameSite=None; Path=/")));
    assertEquals("none=yes", jar.header(uri));
  }

  @Test
  public void serverDeletionOverridesStaleCapturedSegmentCookieAfterRestore() throws Exception {
    var root = URI.create("https://cdn.test/master");
    var part = URI.create("https://cdn.test/parts/a.ts");
    var jar = new JobCookieJar(root.toString(), Map.of());
    jar.accept(root, Map.of("Set-Cookie", List.of("sid=; Max-Age=0; Path=/parts/")));
    assertEquals("keep=yes", jar.header(part, "sid=stale; keep=yes"));
    var restored = new JobCookieJar(root.toString(), Map.of());
    restored.restore(jar.snapshot());
    assertEquals("keep=yes", restored.header(part, "sid=stale; keep=yes"));
  }

  @Test
  public void redirectedJobRebasesOnlyItsActualFinalRequestSeed() throws Exception {
    String original = "https://page.test/hls/entry.m3u8",
        cdn = "https://cdn.test/video/master.m3u8";
    var old = new JobCookieJar(original, Map.of("Cookie", "login=PRIVATE_ORIGIN"));
    old.accept(
        URI.create(cdn),
        Map.of("Set-Cookie", List.of("next=CDN_NEXT; Secure; SameSite=None; Path=/video/")));
    var fresh = new JobCookieJar(cdn, Map.of());
    fresh.restore(old.snapshotFor(cdn, Map.of("Cookie", "clearance=CDN_ONLY")));
    String h = fresh.header(URI.create("https://cdn.test/video/part.ts"));
    assertTrue(h.contains("clearance=CDN_ONLY"));
    assertTrue(h.contains("next=CDN_NEXT"));
    assertFalse(h.contains("PRIVATE_ORIGIN"));
    assertEquals("", fresh.header(URI.create("https://foreign.test/video/part.ts")));
  }
}
