package com.adownloader.app.jobs;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * Budget for resuming a transfer without the user: after the OS stops the transfer job, after the
 * process dies, or after a transient network/server failure. A run that commits new data restores
 * the budget, so a long download survives many interruptions, while a job that fails without any
 * progress stops after a few attempts instead of looping.
 */
final class AutoResume {
  static final int LIMIT = 4;

  private AutoResume() {}

  private static SharedPreferences prefs(Context context) {
    return context.getSharedPreferences("auto_resume", 0);
  }

  /** Consumes one attempt; returns false when the budget is exhausted. */
  static synchronized boolean take(
      Context context, DownloadJobStore store, long id, boolean progressed) {
    SharedPreferences prefs = prefs(context);
    String key = key(store, id);
    int used = progressed ? 0 : prefs.getInt(key, 0);
    if (used >= LIMIT) return false;
    prefs.edit().putInt(key, used + 1).commit();
    return true;
  }

  static synchronized void reset(Context context, DownloadJobStore store, long id) {
    prefs(context).edit().remove(key(store, id)).commit();
  }

  private static String key(DownloadJobStore store, long id) {
    return store.getDatabaseName() + ":" + id;
  }
}
