package com.adownloader.app.ui;

import android.content.Intent;
import android.graphics.Typeface;
import android.os.*;
import android.view.*;
import android.widget.*;
import androidx.appcompat.app.AppCompatActivity;
import com.adownloader.app.BuildConfig;
import com.adownloader.app.MediaCandidate;
import com.adownloader.app.R;
import com.adownloader.app.browser.BrowserSessionManager;
import com.adownloader.app.download.TransferControl;
import com.adownloader.app.playback.*;
import com.google.android.material.bottomsheet.BottomSheetDialog;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.snackbar.Snackbar;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;

/**
 * Start and stop a live playback diagnostic on the phone itself (no PC or adb), then show each
 * stage's result and export the privacy-reduced JSON report through the share sheet.
 */
public final class PlaybackDiagnosticsUi implements AutoCloseable {
  private static final ExecutorService worker =
      Executors.newSingleThreadExecutor(
          r -> {
            Thread t = new Thread(r, "playback-diagnostic");
            t.setDaemon(true);
            return t;
          });
  private static final long PROBE_TIMEOUT_MS = 45000;
  private final AppCompatActivity activity;
  private final BrowserSessionManager browser;
  private final Handler handler = new Handler(Looper.getMainLooper());
  private Snackbar running;
  private BottomSheetDialog dialog;
  private boolean closed;

  public PlaybackDiagnosticsUi(AppCompatActivity activity, BrowserSessionManager browser) {
    this.activity = activity;
    this.browser = browser;
  }

  public String menuTitle() {
    return PlaybackDiagnostics.active() ? "재생 진단 종료 · 결과 보기" : "재생 진단 시작";
  }

  public void toggle() {
    if (PlaybackDiagnostics.active()) finish();
    else offerStart();
  }

  private void offerStart() {
    dialog =
        UiSheets.action(
            activity,
            R.drawable.ic_media,
            "재생 진단",
            "영상 재생 상태(형식·버퍼·오류·디코더·EME)와 플레이어의 네트워크 응답을 기록하고, "
                + "다운로드 요청이 받은 응답과 비교합니다. 주소·쿠키·인증값은 기록하지 않습니다. "
                + "처음부터 기록하려면 새로고침 후 시작을 권장합니다.",
            "취소",
            "새로고침 후 시작",
            () -> begin(true),
            "지금 시작",
            () -> begin(false));
  }

  private void begin(boolean reload) {
    PlaybackDiagnostics.start();
    // The extension picks the session up on its next poll (at most ~3 s); reloading after that
    // lets the page's first manifest and segment requests be recorded.
    if (reload) handler.postDelayed(() -> { if (!closed) browser.reload(); }, 3200);
    showRunning(reload ? "진단 준비 중… 잠시 후 페이지를 새로고침합니다. 영상을 재생하세요." : "재생 진단 중 · 영상을 재생한 뒤 종료하세요.");
  }

  private void showRunning(String text) {
    if (closed) return;
    View root = activity.findViewById(R.id.app_root);
    running = Snackbar.make(root, text, Snackbar.LENGTH_INDEFINITE).setAction("종료", v -> finish());
    running.show();
  }

  /** Reattach the running indicator after the activity is recreated. */
  public void resume() {
    if (PlaybackDiagnostics.active() && (running == null || !running.isShown()))
      showRunning("재생 진단 중 · 영상을 재생한 뒤 종료하세요.");
  }

  private void finish() {
    if (running != null) running.dismiss();
    running = null;
    PlaybackDiagnostics.Session session = PlaybackDiagnostics.stop();
    if (session == null) return;
    List<MediaCandidate> candidates =
        browser.getDetector() == null ? List.of() : browser.getDetector().snapshot();
    session.candidates(candidates);
    MediaCandidate hls = null;
    for (MediaCandidate c : candidates)
      if (c.getType() == MediaCandidate.MediaType.HLS) {
        hls = c;
        break;
      }
    MediaCandidate target = hls;
    Snackbar analyzing =
        Snackbar.make(activity.findViewById(R.id.app_root), "다운로드 경로를 검증하는 중…", Snackbar.LENGTH_INDEFINITE);
    analyzing.show();
    TransferControl control = new TransferControl();
    handler.postDelayed(() -> control.stop(TransferControl.Stop.CANCEL), PROBE_TIMEOUT_MS);
    worker.execute(
        () -> {
          if (target != null)
            session.probe(PlaybackProbe.run(target, session.salt, control, PlaybackProbe::defaultSource));
          Map<String, Object> report = session.report(environment());
          handler.post(
              () -> {
                analyzing.dismiss();
                if (!closed && !activity.isDestroyed()) showResult(session, report);
              });
        });
  }

  private static Map<String, Object> environment() {
    Map<String, Object> m = new LinkedHashMap<>();
    m.put("appVersion", BuildConfig.VERSION_NAME);
    m.put("versionCode", BuildConfig.VERSION_CODE);
    m.put("geckoVersion", BuildConfig.GECKO_VERSION);
    m.put("androidApi", Build.VERSION.SDK_INT);
    m.put("abi", Build.SUPPORTED_ABIS.length > 0 ? Build.SUPPORTED_ABIS[0] : "");
    m.put("geckoHlsPlayer", "media.hls.enabled (GeckoView default; not readable from the app)");
    return m;
  }

  @SuppressWarnings("unchecked")
  private void showResult(PlaybackDiagnostics.Session session, Map<String, Object> report) {
    int pad = UiSheets.dp(activity, 20);
    LinearLayout content = new LinearLayout(activity);
    content.setOrientation(LinearLayout.VERTICAL);
    content.setPadding(pad, pad, pad, pad);
    TextView title = new TextView(activity);
    title.setText("재생 진단 결과");
    title.setTextSize(20);
    title.setTypeface(Typeface.DEFAULT_BOLD);
    content.addView(title);
    ScrollView scroll = new ScrollView(activity);
    LinearLayout body = new LinearLayout(activity);
    body.setOrientation(LinearLayout.VERTICAL);
    scroll.addView(body);
    Map<String, Object> stages = (Map<String, Object>) report.get("stages");
    section(body, "단계별 상태");
    row(body, "후보 탐지", label(String.valueOf(stages.get("candidateDetection"))));
    row(body, "매니페스트 검증", label(String.valueOf(stages.get("manifestValidation"))));
    row(body, "세그먼트 검증", label(String.valueOf(stages.get("segmentValidation"))));
    row(body, "브라우저 재생", label(String.valueOf(stages.get("playback"))));
    Map<String, Object> net = (Map<String, Object>) report.get("playerNetwork");
    row(body, "플레이어 요청 관측", net.get("playerTypeRequests") + "건");
    section(body, "결론");
    for (Object o : (List<Object>) report.get("conclusions")) {
      Map<String, Object> c = (Map<String, Object>) o;
      String mark =
          switch (String.valueOf(c.get("level"))) {
            case "ok" -> "✔ ";
            case "problem" -> "✖ ";
            case "warning" -> "⚠ ";
            default -> "ℹ ";
          };
      TextView t = new TextView(activity);
      t.setText(mark + c.get("message") + (Boolean.TRUE.equals(c.get("inferred")) ? " (추정)" : ""));
      t.setPadding(0, UiSheets.dp(activity, 4), 0, UiSheets.dp(activity, 4));
      body.addView(t);
    }
    content.addView(scroll, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));
    LinearLayout actions = new LinearLayout(activity);
    actions.setGravity(Gravity.END);
    MaterialButton close = new MaterialButton(activity);
    close.setText("닫기");
    MaterialButton export = new MaterialButton(activity);
    export.setText("JSON 내보내기");
    actions.addView(close);
    actions.addView(export);
    content.addView(actions);
    dialog = UiSheets.show(activity, content, true);
    BottomSheetDialog owner = dialog;
    close.setOnClickListener(v -> owner.dismiss());
    export.setOnClickListener(v -> export(session, report));
  }

  private void section(LinearLayout body, String text) {
    TextView t = new TextView(activity);
    t.setText(text);
    t.setTypeface(Typeface.DEFAULT_BOLD);
    t.setPadding(0, UiSheets.dp(activity, 14), 0, UiSheets.dp(activity, 4));
    body.addView(t);
  }

  private void row(LinearLayout body, String name, String value) {
    TextView t = new TextView(activity);
    t.setText(name + " · " + value);
    t.setPadding(0, UiSheets.dp(activity, 2), 0, UiSheets.dp(activity, 2));
    body.addView(t);
  }

  static String label(String state) {
    if (state.startsWith("failed:")) return "실패 (" + state.substring(7) + ")";
    if (state.startsWith("skipped:")) return "생략 (" + state.substring(8) + ")";
    return switch (state) {
      case "detected" -> "탐지됨";
      case "none" -> "후보 없음";
      case "verified" -> "정상";
      case "not-probed" -> "검증 안 함";
      case "progressing" -> "재생 진행";
      case "not-progressing" -> "진행 없음";
      case "no-media-element" -> "미디어 요소 없음";
      default -> state;
    };
  }

  private void export(PlaybackDiagnostics.Session session, Map<String, Object> report) {
    worker.execute(
        () -> {
          try {
            // Same private cache directory the failure-report FileProvider already exposes.
            File dir = new File(activity.getCacheDir(), "failure-exports");
            if (!dir.isDirectory() && !dir.mkdirs()) throw new java.io.IOException("DIAGNOSTIC_EXPORT_STORAGE");
            if (!session.id.matches("P-[a-f0-9]{8}")) throw new java.io.IOException("DIAGNOSTIC_EXPORT_INVALID");
            File file = new File(dir, "Adownloader-Playback-" + session.id + ".json");
            java.nio.file.Files.write(file.toPath(), Json.write(report).getBytes(StandardCharsets.UTF_8));
            Intent share = FailureJournalUi.intent(activity, file);
            handler.post(
                () -> {
                  if (closed || activity.isDestroyed()) return;
                  try {
                    activity.startActivity(Intent.createChooser(share, "재생 진단 보고서 내보내기"));
                  } catch (Exception unavailable) {
                    Toast.makeText(activity, "보고서를 공유할 앱을 찾지 못했습니다.", Toast.LENGTH_LONG).show();
                  }
                });
          } catch (Exception failed) {
            handler.post(
                () -> Toast.makeText(activity, "진단 보고서를 저장하지 못했습니다. 저장 공간을 확인해 주세요.", Toast.LENGTH_LONG).show());
          }
        });
  }

  @Override
  public void close() {
    closed = true;
    handler.removeCallbacksAndMessages(null);
    if (running != null) running.dismiss();
    if (dialog != null) dialog.dismiss();
  }
}
