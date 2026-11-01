package com.adownloader.app.download;

import static org.junit.Assert.*;

import java.util.*;
import org.junit.Test;

public class StorageBudgetTest {
  @Test
  public void twentyGiBPeakAndResumeAlreadyAllocatedBytesAreNotHidden() throws Exception {
    var track = new ManifestParser.Track();
    long source = 20L * 1024 * 1024 * 1024;
    track.parts.add(new ManifestParser.Part("https://x.test/a", 0, source, 0, null, null));
    var fresh = StorageBudget.estimate(List.of(track), 0);
    assertTrue(fresh.exact);
    assertEquals(source, fresh.minimumSource);
    assertEquals(source * 2 + source / 10 + StorageBudget.SAFETY, fresh.additional);
    var resumed = StorageBudget.estimate(List.of(track), source / 2);
    assertEquals(fresh.additional - source / 2, resumed.additional);
    try {
      StorageBudget.require(fresh, source * 2);
      fail();
    } catch (DownloadError error) {
      assertEquals(DownloadError.Category.STORAGE_FULL, error.category);
    }
    StorageBudget.require(fresh, fresh.additional);
  }

  @Test
  public void unknownAndBitrateEstimatesRemainExplicit() throws Exception {
    var track = new ManifestParser.Track();
    track.parts.add(new ManifestParser.Part("https://x.test/a"));
    assertFalse(StorageBudget.estimate(List.of(track), 0).exact);
    assertEquals(StorageBudget.SAFETY, StorageBudget.estimate(List.of(track), 0).additional);
    track.duration = 3600;
    track.bitRate = 80000000;
    assertEquals(36000000000L, StorageBudget.estimate(List.of(track), 0).expectedSource);
  }

  @Test
  public void overflowFailsAsStorageFull() throws Exception {
    var track = new ManifestParser.Track();
    track.parts.add(new ManifestParser.Part("https://x.test/a", 0, Long.MAX_VALUE, 0, null, null));
    try {
      StorageBudget.estimate(List.of(track), 0);
      fail();
    } catch (DownloadError error) {
      assertEquals(DownloadError.Category.STORAGE_FULL, error.category);
    }
  }
}
