package com.adownloader.app.ui;

import static org.junit.Assert.*;

import org.junit.Test;

public class TransferRateTest {
  @Test
  public void rateIsSmoothedAndResetsWhenBytesGoBackwards() {
    TransferRate rate = new TransferRate();
    assertEquals(0, rate.update(1, 0, 0), 0.0);
    assertEquals(1_000_000, rate.update(1, 1_000_000, 1000), 1);
    // 3 MB/s instant reading: 0.6 * 1 + 0.4 * 3 = 1.8 MB/s
    assertEquals(1_800_000, rate.update(1, 4_000_000, 2000), 1);
    // A restarted transfer (smaller count) starts a fresh measurement.
    assertEquals(0, rate.update(1, 10, 2500), 0.0);
  }

  @Test
  public void staleProgressHidesTheSpeed() {
    TransferRate rate = new TransferRate();
    rate.update(2, 0, 0);
    assertTrue(rate.update(2, 500_000, 1000) > 0);
    assertEquals(0, rate.update(2, 500_000, 20_000), 0.0);
  }

  @Test
  public void remainingUsesTotalOrSegmentExtrapolation() {
    assertEquals(700, TransferRate.remaining(300, 1000, 0, 0));
    assertEquals(300, TransferRate.remaining(100, -1, 1, 4));
    assertEquals(-1, TransferRate.remaining(100, -1, 0, 0));
  }

  @Test
  public void etaLabels() {
    assertEquals("", TransferRate.eta(-1, 100));
    assertEquals("", TransferRate.eta(1000, 0));
    assertEquals("1분 이내 남음", TransferRate.eta(1000, 100));
    assertEquals("약 2분 남음", TransferRate.eta(9_000, 100));
    assertEquals("약 1시간 30분 남음", TransferRate.eta(540_000, 100));
    assertEquals("약 2시간 남음", TransferRate.eta(720_000, 100));
  }
}
