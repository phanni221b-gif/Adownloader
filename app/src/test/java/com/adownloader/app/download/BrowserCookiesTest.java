package com.adownloader.app.download;

import static org.junit.Assert.*;

import java.util.*;
import org.junit.*;

public class BrowserCookiesTest {
  @After
  public void reset() {
    BrowserCookies.install(null);
  }

  private static BrowserCookies.Cookie c(String n, String v, String sameSite, boolean partitioned) {
    return new BrowserCookies.Cookie(n, v, sameSite, partitioned);
  }

  @Test
  public void sameSiteRequestCarriesEveryCookieTheBrowserHoldsForTheUrl() {
    String header =
        BrowserCookies.select(
            List.of(c("a", "1", "lax", false), c("b", "2", "strict", false), c("p", "3", "no_restriction", true)),
            "https://media.example.com/v/a.m3u8",
            "https://www.example.com/watch");
    assertEquals("a=1; b=2; p=3", header);
  }

  @Test
  public void crossSiteRequestCarriesOnlyPartitionedAndSameSiteNoneCookies() {
    String header =
        BrowserCookies.select(
            List.of(
                c("lax", "x", "lax", false),
                c("none", "1", "no_restriction", false),
                c("tok", "old", "no_restriction", false),
                c("tok", "partitioned", "no_restriction", true)),
            "https://cdn.other-site.net/seg.ts",
            "https://www.example.com/watch");
    assertEquals("none=1; tok=partitioned", header);
  }

  @Test
  public void headerValuesWithControlCharactersAreDropped() {
    assertEquals("ok=1", BrowserCookies.sanitize("ok=1; bad=\r\nInjected: x; =novalue"));
  }

  @Test
  public void lookupFailureOrAbsenceIsAnEmptyHeaderNeverAnError() {
    assertEquals("", BrowserCookies.forUrl("https://a.test/x", "https://a.test/"));
    BrowserCookies.install(
        (url, page) -> {
          throw new IllegalStateException("bridge gone");
        });
    assertEquals("", BrowserCookies.forUrl("https://a.test/x", "https://a.test/"));
  }

  @Test
  public void lookupsAreCachedPerHostDirectoryAndPage() {
    int[] calls = {0};
    BrowserCookies.install(
        (url, page) -> {
          calls[0]++;
          return "s=" + calls[0];
        });
    assertEquals("s=1", BrowserCookies.forUrl("https://a.test/v/1.ts", "https://p.test/w"));
    assertEquals("s=1", BrowserCookies.forUrl("https://a.test/v/2.ts", "https://p.test/w"));
    assertEquals("another path directory is asked again", "s=2", BrowserCookies.forUrl("https://a.test/w/1.ts", "https://p.test/w"));
    assertEquals(2, calls[0]);
  }

  @Test
  public void jobCookiesOverrideTheLiveJarWhichOverridesTheObservedSnapshot() {
    try (JobCookieJar jar = new JobCookieJar("https://a.test/m.m3u8", Map.of("Cookie", "s=seed; k=keep"))) {
      jar.accept(
          java.net.URI.create("https://a.test/m.m3u8"),
          Map.of("Set-Cookie", List.of("j=job; Path=/")));
      String header = jar.header(java.net.URI.create("https://a.test/seg.ts"), null, "s=live; j=stale");
      assertEquals("k=keep; s=live; j=job", header);
    }
  }

  @Test
  public void retryAfterIsParsedAndCapped() {
    assertEquals(2000, RetryPolicy.parseRetryAfter("2", 0));
    assertEquals(30_000, RetryPolicy.parseRetryAfter("86400", 0));
    assertEquals(0, RetryPolicy.parseRetryAfter("soon", 0));
    assertFalse(
        RetryPolicy.retryable(
            new DownloadError(DownloadError.Category.ACCESS_BLOCKED)));
    assertTrue(RetryPolicy.retryable(new DownloadError(DownloadError.Category.SERVER)));
  }

  @Test
  public void defaultRefererFollowsTheBrowserPolicy() {
    String page = "https://www.example.com/watch/1?x=2#t";
    assertEquals("https://www.example.com/watch/1?x=2", HttpSource.defaultReferer(page, "https://www.example.com/a.m3u8"));
    assertEquals("https://www.example.com/", HttpSource.defaultReferer(page, "https://cdn.example.net/a.m3u8"));
    assertEquals("no downgrade leak", "", HttpSource.defaultReferer(page, "http://cdn.example.net/a.m3u8"));
  }
}
