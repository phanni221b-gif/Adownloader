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

  private static org.json.JSONArray transportSummary(JSONObject rows) {
    org.json.JSONArray result = new org.json.JSONArray();
    for (var keys = rows.keys(); keys.hasNext() && result.length() < 40; ) {
      JSONObject r = rows.optJSONObject(keys.next());
      if (r == null) continue;
      String stage = r.optString("stage"), action = r.optString("action");
      int status = r.optInt("status");
      String label =
          switch (stage) {
            case "ROOT_MANIFEST", "MASTER_MANIFEST" ->
                r.optString("body").equals("DASH") ? "DASH manifest" : "HLS manifest";
            case "VARIANT_PLAYLIST" -> "Variant manifest";
            case "AUDIO_PLAYLIST" -> "오디오 manifest";
            case "INIT_SEGMENT" -> "초기화 세그먼트";
            case "VIDEO_SEGMENT" -> "영상 세그먼트";
            case "AUDIO_SEGMENT" -> "오디오 세그먼트";
            case "AES_KEY" -> "암호화 키 요청";
            default -> "미디어 요청";
          };
      String detail =
          action.equals("GECKO_RESPONSE_CACHE")
              ? "Gecko 재생 응답 사용 · Native 재요청 생략"
              : action.equals("GECKO_CACHE_MISS")
                  ? "최근 재생 응답 없음/만료 · 현재 요청으로 확인"
                  : action.equals("NATIVE_CONTEXT_RETRY")
                      ? "최근 성공 요청 문맥으로 Native 재시도"
                      : r.optString("transport").equals("gecko")
                          ? "Gecko 재요청 " + (status == 0 ? "시도" : status)
                          : "Native " + status;
      if (action.equals("SEGMENT_NATIVE_BLOCKED") || action.equals("MANIFEST_NATIVE_BLOCKED"))
        detail += " · 요청 차단";
      result.put(label + ": " + detail);
    }
    return result;
  }

  public static void configureNetwork(Context raw) {
    Context context = raw.getApplicationContext();
    com.adownloader.app.download.MediaNetworkSession.diagnostics(
        event -> {
          synchronized (DeviceDiagnostics.class) {
            try {
              var prefs = context.getSharedPreferences("device_diagnostics", 0);
              JSONObject rows = new JSONObject(prefs.getString("network", "{}"));
              String key =
                  event.get("stage") + ":" + event.get("transport") + ":" + event.get("action");
              if (rows.length() >= 40 && !rows.has(key)) {
                var it = rows.keys();
                if (it.hasNext()) rows.remove(it.next());
              }
              rows.put(key, new JSONObject(event));
              prefs.edit().putString("network", rows.toString()).apply();
            } catch (Exception ignored) {
            }
          }
        });
  }

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
      try {
        JSONObject network = new JSONObject(preferences.getString("network", "{}"));
        out.put("mediaNetwork", network);
        out.put("mediaTransportSummary", transportSummary(network));
        var provider = com.adownloader.app.download.MediaNetworkSession.provider();
        if (provider != null)
          out.put("manifestEvidence", new JSONObject(provider.captureDiagnostics()));
      } catch (Exception ignored) {
      }
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
