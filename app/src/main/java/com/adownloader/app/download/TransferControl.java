package com.adownloader.app.download;

import java.io.InterruptedIOException;
import java.net.HttpURLConnection;

/** A cancellation signal owns at most one active HTTP exchange per worker. */
public final class TransferControl {
  public enum Stop {
    NONE,
    PAUSE,
    CANCEL
  }

  public static final class Stopped extends InterruptedIOException {
    public final Stop reason;

    Stopped(Stop reason) {
      super(reason.name());
      this.reason = reason;
    }
  }

  private static final java.util.concurrent.ExecutorService aborts =
      java.util.concurrent.Executors.newFixedThreadPool(
          2,
          r -> {
            Thread thread = new Thread(r, "download-abort");
            thread.setDaemon(true);
            return thread;
          });
  private volatile Stop stop = Stop.NONE;
  private volatile boolean userRequested;

  public boolean userRequested() {
    return userRequested;
  }

  public void userStop(Stop reason) {
    userRequested = true;
    stop(reason);
  }

  private volatile HttpURLConnection active;

  public void stop(Stop reason) {
    stop = reason;
    HttpURLConnection connection = active;
    if (connection != null) aborts.execute(connection::disconnect);
  }

  public void check() throws Stopped {
    if (stop != Stop.NONE) throw new Stopped(stop);
    if (Thread.currentThread().isInterrupted()) throw new Stopped(Stop.PAUSE);
  }

  public void attach(HttpURLConnection connection) throws Stopped {
    check();
    active = connection;
    if (stop != Stop.NONE) {
      connection.disconnect();
      check();
    }
  }

  public void detach(HttpURLConnection connection) {
    if (active == connection) active = null;
  }
}
