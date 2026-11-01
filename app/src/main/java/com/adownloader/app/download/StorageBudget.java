package com.adownloader.app.download;

import java.util.List;

/** Additional free bytes: existing source bytes are already charged to the filesystem. */
public final class StorageBudget {
  public static final long SAFETY = 8L * 1024 * 1024;

  public static final class Estimate {
    public final long minimumSource, expectedSource, additional;
    public final boolean exact;

    Estimate(long minimum, long expected, long additional, boolean exact) {
      this.minimumSource = minimum;
      this.expectedSource = expected;
      this.additional = additional;
      this.exact = exact;
    }
  }

  public static Estimate estimate(List<ManifestParser.Track> tracks, long committed)
      throws DownloadError {
    try {
      long known = 0, expected = 0;
      boolean exact = true;
      for (var track : tracks) {
        long trackKnown = 0;
        boolean complete = true;
        for (var part : track.parts)
          if (part.length > 0) trackKnown = Math.addExact(trackKnown, part.length);
          else complete = false;
        long trackExpected = trackKnown;
        if (!complete) {
          exact = false;
          if (track.bitRate > 0 && Double.isFinite(track.duration) && track.duration > 0) {
            double bytes = track.duration * track.bitRate / 8.0;
            if (bytes > Long.MAX_VALUE) throw new ArithmeticException();
            trackExpected = Math.max(trackKnown, (long) Math.ceil(bytes));
          }
        }
        known = Math.addExact(known, trackKnown);
        expected = Math.addExact(expected, trackExpected);
      }
      expected = Math.max(expected, committed);
      long output =
          Math.addExact(
              expected, expected / 10); // Platform mux overhead, never an exact total claim.
      long additional =
          Math.addExact(Math.addExact(Math.max(0, expected - committed), output), SAFETY);
      return new Estimate(known, expected, additional, exact);
    } catch (ArithmeticException overflow) {
      throw new DownloadError(DownloadError.Category.STORAGE_FULL);
    }
  }

  public static void require(Estimate estimate, long available) throws DownloadError {
    if (available < estimate.additional)
      throw new DownloadError(DownloadError.Category.STORAGE_FULL);
  }
}
