package com.adownloader.app.browser;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import java.io.IOException;
import java.util.concurrent.*;
import org.mozilla.geckoview.GeckoRuntime;

/**
 * The single process-wide GeckoRuntime. The browser UI and background downloads share it, so a
 * download started from a JobService after the Activity is gone still uses the browser's cookies.
 */
public final class GeckoRuntimes {
  private static volatile GeckoRuntime runtime;

  private GeckoRuntimes() {}

  /** Must be called on the main thread. */
  public static synchronized GeckoRuntime get(Context context) {
    if (runtime == null) runtime = GeckoRuntime.create(context.getApplicationContext());
    return runtime;
  }

  /** Callable from worker threads; creates the runtime on the main thread when needed. */
  public static GeckoRuntime obtain(Context context, long timeoutMillis) throws IOException {
    GeckoRuntime current = runtime;
    if (current != null) return current;
    if (Looper.myLooper() == Looper.getMainLooper()) return get(context);
    FutureTask<GeckoRuntime> task = new FutureTask<>(() -> get(context));
    new Handler(Looper.getMainLooper()).post(task);
    try {
      return task.get(timeoutMillis, TimeUnit.MILLISECONDS);
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      throw new java.io.InterruptedIOException("Gecko startup interrupted");
    } catch (ExecutionException | TimeoutException unavailable) {
      throw new IOException("Gecko runtime unavailable", unavailable);
    }
  }
}
