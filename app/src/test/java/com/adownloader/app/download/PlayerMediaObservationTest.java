package com.adownloader.app.download;

import static org.junit.Assert.*;

import java.util.*;
import org.junit.*;

/**
 * The live player's own media-request outcomes (host + status only) are recorded so a failed
 * download can separate a server block (the player also fails on the segment host) from a candidate
 * that is not the playing stream (the player succeeds on a different host). No URL, header or cookie
 * is ever retained.
 */
public class PlayerMediaObservationTest {
  @Before
  public void start() {
    MediaNetworkSession.clearPlayerOutcomes();
  }

  @After
  public void stop() {
    MediaNetworkSession.clearPlayerOutcomes();
  }

  @Test
  public void aSuccessOnAHostIsKeptEvenAfterALaterFailureToIt() {
    MediaNetworkSession.observePlayerRequest("cdn.good", 200);
    MediaNetworkSession.observePlayerRequest("cdn.good", 403);
    assertEquals(Integer.valueOf(200), MediaNetworkSession.playerMediaSnapshot().get("cdn.good"));
  }

  @Test
  public void aFailureIsUpgradedToSuccessWhenTheHostLaterSucceeds() {
    MediaNetworkSession.observePlayerRequest("cdn.late", 403);
    MediaNetworkSession.observePlayerRequest("cdn.late", 200);
    assertEquals(Integer.valueOf(200), MediaNetworkSession.playerMediaSnapshot().get("cdn.late"));
  }

  @Test
  public void invalidHostsAndStatusesAreRejected() {
    MediaNetworkSession.observePlayerRequest("", 200);
    MediaNetworkSession.observePlayerRequest("h", 0);
    MediaNetworkSession.observePlayerRequest("h", 700);
    assertTrue(MediaNetworkSession.playerMediaSnapshot().isEmpty());
  }

  @Test
  @SuppressWarnings("unchecked")
  public void theReportShowsEachObservedHostAsOkOrErr() {
    MediaNetworkSession.observePlayerRequest("yapsyas.com", 403);
    MediaNetworkSession.observePlayerRequest("realcdn.example", 200);
    FailureDiagnostics.Session session =
        new FailureDiagnostics.Session(
            7, "HLS", "https://realcdn.example/seg1.ts", "https://page.test/", Map.of());
    Map<String, Object> report =
        session.report(
            new DownloadError(DownloadError.Category.ACCESS_BLOCKED),
            DownloadError.Category.ACCESS_BLOCKED,
            "TEST");
    List<String> hosts = (List<String>) report.get("observedPlayerMediaHosts");
    assertNotNull("the report carries the player's observed media hosts", hosts);
    assertTrue(
        "the segment host the player also failed on is marked err",
        hosts.contains("yapsyas.com|err"));
    assertTrue(
        "a host the player actually streamed is marked ok", hosts.contains("realcdn.example|ok"));
  }

  @Test
  public void theReportOmitsTheFieldWhenNothingWasObserved() {
    FailureDiagnostics.Session session =
        new FailureDiagnostics.Session(
            7, "HLS", "https://cdn.test/seg1.ts", "https://page.test/", Map.of());
    Map<String, Object> report =
        session.report(
            new DownloadError(DownloadError.Category.ACCESS_BLOCKED),
            DownloadError.Category.ACCESS_BLOCKED,
            "TEST");
    assertFalse(report.containsKey("observedPlayerMediaHosts"));
  }
}
