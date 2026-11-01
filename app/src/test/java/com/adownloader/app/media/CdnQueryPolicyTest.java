package com.adownloader.app.media;

import static org.junit.Assert.*;

import com.adownloader.app.MediaCandidate;
import java.util.*;
import org.junit.Test;

public class CdnQueryPolicyTest {
  private String id(String query) {
    return MediaIdentity.logical(
        MediaCandidate.MediaType.VIDEO,
        "https://cdn.test/movie-123.mp4?" + query,
        "https://site.test/watch");
  }

  @Test
  public void cloudFrontAndAwsRotateOnlyAuthentication() {
    assertEquals(
        id("Expires=1800000000&Signature=one&Key-Pair-Id=K1&quality=2160"),
        id("Expires=1900000000&Signature=two&Key-Pair-Id=K2&quality=2160"));
    String aws =
        "X-Amz-Algorithm=AWS4-HMAC-SHA256&X-Amz-Credential=KEY&X-Amz-Date=20261101T000000Z&X-Amz-Expires=60&X-Amz-SignedHeaders=host&X-Amz-Signature=";
    assertEquals(id(aws + "one"), id(aws + "two"));
    assertNotEquals(id(aws + "one&x-amz-meta-video-id=1"), id(aws + "two&x-amz-meta-video-id=2"));
  }

  @Test
  public void signaturePairsGroupWithoutCollapsingQualityIdsOrOpaqueAuth() {
    assertEquals(
        id("sig=one&exp=1800000000&quality=2160"), id("sig=two&exp=1900000000&quality=2160"));
    assertEquals(id("hmac=one&e=1800000000"), id("hmac=two&e=1900000000"));
    assertNotEquals(
        id("sig=one&exp=1800000000&quality=1080"), id("sig=two&exp=1800000000&quality=2160"));
    assertNotEquals(id("sig=one&exp=1800000000&id=1"), id("sig=two&exp=1800000000&id=2"));
    assertNotEquals(
        id("auth=video1&sig=one&exp=1800000000"), id("auth=video2&sig=two&exp=1800000000"));
    assertNotEquals(id("e=episode1&sig=one"), id("e=episode2&sig=two"));
    assertNotEquals(id("access_token=one"), id("access_token=two"));
  }

  @Test
  public void akamaiAndJwtPreserveTheirSemanticPayload() {
    assertEquals(
        id("hdntl=exp=1800000000~acl=/movie123/*~hmac=one"),
        id("hdntl=exp=1900000000~acl=/movie123/*~hmac=two"));
    assertNotEquals(
        id("hdntl=exp=1800000000~acl=/id1/*~hmac=one"),
        id("hdntl=exp=1800000000~acl=/id2/*~hmac=two"));
    assertEquals(id("jwt=a.payload.one"), id("jwt=a.payload.two"));
    assertNotEquals(id("jwt=a.video1.one"), id("jwt=a.video2.two"));
  }

  @Test
  public void latestFullNetworkUrlAndCookieArePreserved() {
    MediaDetector detector = new MediaDetector(null);
    String url = "";
    for (int i = 0; i < 100; i++) {
      url = "https://cdn.test/movie-123.mp4?quality=2160&sig=" + i + "&exp=1800000000";
      detector.capture(
          url,
          "video/mp4",
          "network-response",
          "https://site.test/watch",
          Map.of("Cookie", "session=" + i));
    }
    assertEquals(1, detector.snapshot().size());
    assertEquals(url, detector.snapshot().get(0).getUrl());
    assertEquals("session=99", detector.snapshot().get(0).getHeaders().get("Cookie"));
  }

  @Test
  public void ambiguousGenericAndDuplicateQueriesStaySeparate() {
    assertNotEquals(
        MediaIdentity.logical(
            MediaCandidate.MediaType.VIDEO,
            "https://cdn.test/play?sig=one&exp=1800000000",
            "https://site.test"),
        MediaIdentity.logical(
            MediaCandidate.MediaType.VIDEO,
            "https://cdn.test/play?sig=two&exp=1900000000",
            "https://site.test"));
    assertNotEquals(id("sig=a&sig=b&exp=1800000000"), id("sig=a&sig=c&exp=1800000000"));
  }

  @Test
  public void cloudFrontCustomPolicyAndJwtTokensDoNotEraseResourceClaims() {
    String first = policy(1800000000L, "https://cdn.test/movie-123.mp4"),
        second = policy(1900000000L, "https://cdn.test/movie-123.mp4");
    assertEquals(
        id("Policy=" + first + "&Signature=one&Key-Pair-Id=K1"),
        id("Policy=" + second + "&Signature=two&Key-Pair-Id=K2"));
    assertNotEquals(
        id("Policy=" + first + "&Signature=one&Key-Pair-Id=K1"),
        id(
            "Policy="
                + policy(1800000000L, "https://cdn.test/other.mp4")
                + "&Signature=two&Key-Pair-Id=K2"));
    assertEquals(id("token=a.payload.one"), id("token=a.payload.two"));
    assertNotEquals(id("token=a.video1.one"), id("token=a.video2.two"));
    assertEquals(
        id("hdnts=exp=1800000000~acl=/id1/*~hmac=one"),
        id("hdnts=exp=1900000000~acl=/id1/*~hmac=two"));
  }

  private static String policy(long expiry, String resource) {
    return Base64.getEncoder()
        .encodeToString(
            ("{\"Statement\":[{\"Resource\":\""
                    + resource
                    + "\",\"Condition\":{\"DateLessThan\":{\"AWS:EpochTime\":"
                    + expiry
                    + "}}}]}")
                .getBytes(java.nio.charset.StandardCharsets.UTF_8))
        .replace('+', '-')
        .replace('=', '_')
        .replace('/', '~');
  }
}
