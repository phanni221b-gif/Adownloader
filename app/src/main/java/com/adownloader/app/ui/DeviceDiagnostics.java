package com.adownloader.app.ui;

import android.app.Activity;
import android.content.*;
import android.os.Build;
import android.view.*;
import com.adownloader.app.BuildConfig;
import com.adownloader.app.R;
import com.adownloader.app.download.DownloadError;
import org.json.JSONObject;

/** Explicit allowlist: no URL, hostname, identifier, exception text or browser state. */
public final class DeviceDiagnostics {
  private DeviceDiagnostics() {}

  public static void failure(Context context, DownloadError.Category category) {
    context
        .getSharedPreferences("device_diagnostics", 0)
        .edit()
        .putString("download", category.name())
        .apply();
  }

  public static void browserCrash(Context context) {
    context
        .getSharedPreferences("device_diagnostics", 0)
        .edit()
        .putString("crash", "GECKO_CONTENT")
        .apply();
  }

  public static String collect(Activity activity) {
    try {
      JSONObject out = new JSONObject();
      out.put("app", BuildConfig.VERSION_NAME);
      out.put("android", Build.VERSION.RELEASE);
      out.put("api", Build.VERSION.SDK_INT);
      out.put("model", Build.MANUFACTURER + " " + Build.MODEL);
      out.put("gecko", BuildConfig.GECKO_VERSION);
      var metrics = activity.getResources().getDisplayMetrics();
      out.put("resolution", metrics.widthPixels + "x" + metrics.heightPixels);
      out.put("densityDpi", metrics.densityDpi);
      out.put(
          "window",
          activity.getWindowManager().getCurrentWindowMetrics().getBounds().toShortString());
      out.put("multiWindow", activity.isInMultiWindowMode());
      WindowInsets insets = activity.getWindow().getDecorView().getRootWindowInsets();
      if (insets != null) {
        var safe =
            insets.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
        out.put(
            "insets",
            new JSONObject()
                .put("left", safe.left)
                .put("top", safe.top)
                .put("right", safe.right)
                .put("bottom", safe.bottom));
        out.put("keyboard", insets.isVisible(WindowInsets.Type.ime()));
      }
      var fold = FoldLayoutController.geometry(activity);
      if (fold == null) out.put("fold", "NONE");
      else
        out.put(
            "fold",
            new JSONObject()
                .put("bounds", fold.bounds.toShortString())
                .put("orientation", fold.vertical ? "VERTICAL" : "HORIZONTAL")
                .put("separating", fold.separating)
                .put("occluding", fold.occluding)
                .put("posture", fold.halfOpen ? "HALF_OPENED" : "FLAT"));
      var preferences = activity.getSharedPreferences("device_diagnostics", 0);
      String failure = preferences.getString("download", "NONE");
      try {
        DownloadError.Category.valueOf(failure);
      } catch (Exception invalid) {
        failure = "NONE";
      }
      out.put("downloadFailure", failure);
      if (activity instanceof com.adownloader.app.MainActivity)
        out.put(
            "mediaDetection",
            ((com.adownloader.app.MainActivity) activity).mediaDetectionDiagnostics());
      try {
        JSONObject hls = new JSONObject(preferences.getString("hls", "{}"));
        JSONObject safe = new JSONObject();
        for (String k :
            java.util.List.of(
                "host", "status", "mime", "redirects", "playlist", "stage", "tag", "category"))
          if (hls.has(k)) safe.put(k, hls.get(k));
        out.put("hls", safe);
      } catch (Exception ignored) {
      }
      out.put(
          "crash",
          "GECKO_CONTENT".equals(preferences.getString("crash", "")) ? "GECKO_CONTENT" : "NONE");
      return out.toString(2);
    } catch (Exception failure) {
      return "{\"diagnostics\":\"UNAVAILABLE\"}";
    }
  }

  public static void show(Activity activity) {
    String report = collect(activity);
    UiSheets.action(
        activity,
        R.drawable.ic_settings,
        "기기 진단 정보",
        report,
        "닫기",
        "공유",
        () -> {
          Intent intent =
              new Intent(Intent.ACTION_SEND)
                  .setType("text/plain")
                  .putExtra(Intent.EXTRA_SUBJECT, "Adownloader 기기 진단")
                  .putExtra(Intent.EXTRA_TEXT, report);
          activity.startActivity(Intent.createChooser(intent, "진단 정보 공유"));
        },
        null,
        null);
  }
}
