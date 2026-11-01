package com.adownloader.app.download;

import static org.junit.Assert.*;

import java.util.*;
import org.junit.Test;

public class RefreshPlanMatcherTest {
  private ManifestParser.Track track(String token, int height, long sequence) {
    ManifestParser.Track t = new ManifestParser.Track();
    t.width = height * 16 / 9;
    t.height = height;
    t.frameRate = 60;
    t.bitRate = 10000000;
    t.duration = 3;
    t.codecs = "avc1.64002a";
    t.representation = "https://cdn.test/asset-123.m3u8?token=" + token;
    t.parts.add(
        new ManifestParser.Part(
            "https://cdn.test/part.ts?quality=" + height + "&token=" + token,
            0,
            -1,
            sequence,
            null,
            null));
    return t;
  }

  @Test
  public void authRotationCanReuseTheExactOriginalPlan() {
    assertTrue(
        RefreshPlanMatcher.compatible(
            List.of(track("old", 2160, 1)), List.of(track("new", 2160, 1))));
  }

  @Test
  public void changedRepresentationRangeOrSequenceCannotMix() {
    assertFalse(
        RefreshPlanMatcher.compatible(
            List.of(track("old", 2160, 1)), List.of(track("new", 1080, 1))));
    assertFalse(
        RefreshPlanMatcher.compatible(
            List.of(track("old", 2160, 1)), List.of(track("new", 2160, 2))));
  }

  @Test
  public void refreshedSourceNeverAutomaticallyLowersOriginalQuality() {
    assertFalse(
        RefreshPlanMatcher.qualityNotLower(
            List.of(track("old", 2160, 1)), List.of(track("new", 1080, 1))));
    assertTrue(
        RefreshPlanMatcher.qualityNotLower(
            List.of(track("old", 1080, 1)), List.of(track("new", 2160, 1))));
    ManifestParser.Track old = track("old", 2160, 1), fresh = track("new", 2160, 1);
    fresh.frameRate = 30;
    assertFalse(RefreshPlanMatcher.qualityNotLower(List.of(old), List.of(fresh)));
  }

  @Test
  public void cdnSignatureRotationRetainsPartsButResourceAndJwtClaimsDoNot() {
    ManifestParser.Track old = track("old", 2160, 1), fresh = track("new", 2160, 1);
    old.representation = "https://cdn.test/asset-123.m3u8?sig=one&exp=1800000000";
    fresh.representation = "https://cdn.test/asset-123.m3u8?sig=two&exp=1900000000";
    old.parts.clear();
    fresh.parts.clear();
    old.parts.add(
        new ManifestParser.Part(
            "https://cdn.test/part.ts?id=1&Signature=one&Key-Pair-Id=K1&Expires=1800000000",
            0,
            -1,
            1,
            null,
            null));
    fresh.parts.add(
        new ManifestParser.Part(
            "https://cdn.test/part.ts?id=1&Signature=two&Key-Pair-Id=K2&Expires=1900000000",
            0,
            -1,
            1,
            null,
            null));
    assertTrue(RefreshPlanMatcher.compatible(List.of(old), List.of(fresh)));
    fresh.representation = "https://cdn.test/asset-123.m3u8?id=2&sig=two&exp=1900000000";
    assertFalse(RefreshPlanMatcher.compatible(List.of(old), List.of(fresh)));
    old.representation = "https://cdn.test/asset-123.m3u8?token=a.video1.one";
    fresh.representation = "https://cdn.test/asset-123.m3u8?token=a.video2.two";
    assertFalse(RefreshPlanMatcher.compatible(List.of(old), List.of(fresh)));
  }
}
