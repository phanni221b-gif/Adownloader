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
}
