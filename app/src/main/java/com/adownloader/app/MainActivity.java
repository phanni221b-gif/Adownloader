package com.adownloader.app;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.view.inputmethod.EditorInfo;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import com.adownloader.app.browser.BrowserSessionManager;
import com.adownloader.app.browser.MediaExtensionManager;
import com.adownloader.app.browser.PopupController;
import com.adownloader.app.media.MediaDetector;

import org.json.JSONObject;
import org.mozilla.geckoview.GeckoView;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;

public final class MainActivity extends Activity implements BrowserSessionManager.Listener, MediaDetector.Listener {
    private static final int REQ_NOTIFICATIONS = 50;

    private BrowserSessionManager browser;
    private PopupController popupController;
    private MediaDetector mediaDetector;

    private LinearLayout root;
    private LinearLayout toolbar;
    private EditText addressBar;
    private Button backButton;
    private Button forwardButton;
    private Button mediaButton;
    private ProgressBar progressBar;
    private GeckoView geckoView;
    private String currentUrl = "https://www.google.com";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        configureWindow();
        buildUi();

        mediaDetector = new MediaDetector(this);
        browser = new BrowserSessionManager(this, geckoView, this);
        popupController = new PopupController(this, browser::load);

        new MediaExtensionManager(browser.getRuntime(), mediaDetector, new MediaExtensionManager.StatusListener() {
            @Override
            public void onExtensionReady() {
                runOnUiThread(() -> Toast.makeText(MainActivity.this, "미디어 감지 준비 완료", Toast.LENGTH_SHORT).show());
            }

            @Override
            public void onExtensionError(String message) {
                runOnUiThread(() -> Toast.makeText(MainActivity.this, "미디어 감지 확장 초기화 실패", Toast.LENGTH_LONG).show());
            }
        }).install();

        requestNotificationPermissionIfNeeded();
        browser.load(currentUrl);
    }

    private void configureWindow() {
        getWindow().setStatusBarColor(Color.WHITE);
        getWindow().setNavigationBarColor(Color.WHITE);
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR);
    }

    private void buildUi() {
        root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.WHITE);
        root.setFitsSystemWindows(false);
        root.setOnApplyWindowInsetsListener((view, insets) -> {
            int top = insets.getInsets(WindowInsets.Type.statusBars()).top;
            int bottom = insets.getInsets(WindowInsets.Type.navigationBars()).bottom;
            view.setPadding(0, top, 0, bottom);
            return insets;
        });

        toolbar = new LinearLayout(this);
        toolbar.setOrientation(LinearLayout.HORIZONTAL);
        toolbar.setGravity(Gravity.CENTER_VERTICAL);
        toolbar.setPadding(dp(4), dp(4), dp(4), dp(4));

        backButton = smallButton("‹");
        backButton.setEnabled(false);
        backButton.setOnClickListener(v -> browser.goBack());
        toolbar.addView(backButton, new LinearLayout.LayoutParams(dp(44), dp(48)));

        forwardButton = smallButton("›");
        forwardButton.setEnabled(false);
        forwardButton.setOnClickListener(v -> browser.goForward());
        toolbar.addView(forwardButton, new LinearLayout.LayoutParams(dp(44), dp(48)));

        addressBar = new EditText(this);
        addressBar.setSingleLine(true);
        addressBar.setTextSize(15);
        addressBar.setHint("주소 또는 검색어");
        addressBar.setSelectAllOnFocus(true);
        addressBar.setPadding(dp(14), 0, dp(14), 0);
        addressBar.setBackgroundColor(0xFFF2F2F4);
        addressBar.setImeOptions(EditorInfo.IME_ACTION_GO);
        addressBar.setOnEditorActionListener((v, actionId, event) -> {
            boolean go = actionId == EditorInfo.IME_ACTION_GO ||
                    (event != null && event.getKeyCode() == KeyEvent.KEYCODE_ENTER && event.getAction() == KeyEvent.ACTION_DOWN);
            if (go) {
                browser.load(normalizeAddress(addressBar.getText().toString()));
                return true;
            }
            return false;
        });
        toolbar.addView(addressBar, new LinearLayout.LayoutParams(0, dp(44), 1f));

        Button refresh = smallButton("↻");
        refresh.setOnClickListener(v -> browser.reload());
        toolbar.addView(refresh, new LinearLayout.LayoutParams(dp(44), dp(48)));

        Button menu = smallButton("⋮");
        menu.setOnClickListener(v -> showMenu());
        toolbar.addView(menu, new LinearLayout.LayoutParams(dp(44), dp(48)));
        root.addView(toolbar, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(56)));

        progressBar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        progressBar.setMax(100);
        progressBar.setProgress(0);
        root.addView(progressBar, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(2)));

        FrameLayout browserFrame = new FrameLayout(this);
        geckoView = new GeckoView(this);
        geckoView.setBackgroundColor(Color.WHITE);
        browserFrame.addView(geckoView, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        mediaButton = new Button(this);
        mediaButton.setText("▶ 0");
        mediaButton.setTextColor(Color.WHITE);
        mediaButton.setTextSize(13);
        mediaButton.setAllCaps(false);
        mediaButton.setBackgroundColor(0xFFD60072);
        mediaButton.setOnClickListener(v -> showMediaCandidates());
        FrameLayout.LayoutParams mediaLp = new FrameLayout.LayoutParams(dp(72), dp(52), Gravity.END | Gravity.BOTTOM);
        mediaLp.setMargins(0, 0, dp(16), dp(16));
        browserFrame.addView(mediaButton, mediaLp);
        root.addView(browserFrame, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        LinearLayout bottom = new LinearLayout(this);
        bottom.setOrientation(LinearLayout.HORIZONTAL);
        bottom.setGravity(Gravity.CENTER);
        bottom.setBackgroundColor(Color.WHITE);
        bottom.addView(bottomButton("🌐\n브라우저", v -> {}), weighted());
        bottom.addView(bottomButton("⇩\n다운로드", v -> showDownloadsInfo()), weighted());
        bottom.addView(bottomButton("▣\n플레이어", v -> openMoviesFolder()), weighted());
        bottom.addView(bottomButton("⚙\n설정", v -> showSettings()), weighted());
        root.addView(bottom, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(66)));

        setContentView(root);
        root.requestApplyInsets();
    }

    private Button smallButton(String text) {
        Button b = new Button(this);
        b.setText(text);
        b.setTextSize(23);
        b.setMinWidth(0);
        b.setMinHeight(0);
        b.setPadding(0, 0, 0, 0);
        b.setBackgroundColor(Color.TRANSPARENT);
        b.setAllCaps(false);
        return b;
    }

    private Button bottomButton(String text, View.OnClickListener listener) {
        Button b = new Button(this);
        b.setText(text);
        b.setTextSize(11);
        b.setTextColor(0xFF55555F);
        b.setAllCaps(false);
        b.setGravity(Gravity.CENTER);
        b.setBackgroundColor(Color.TRANSPARENT);
        b.setOnClickListener(listener);
        return b;
    }

    private LinearLayout.LayoutParams weighted() {
        return new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f);
    }

    private void showMenu() {
        String[] items = {"홈", "새로고침", "감지 목록 초기화", "앱 정보"};
        new AlertDialog.Builder(this)
                .setItems(items, (dialog, which) -> {
                    if (which == 0) browser.load("https://www.google.com");
                    else if (which == 1) browser.reload();
                    else if (which == 2) mediaDetector.reset();
                    else showAbout();
                })
                .show();
    }

    private void showAbout() {
        new AlertDialog.Builder(this)
                .setTitle("Adownloader 0.3.0")
                .setMessage("GeckoView 자체 엔진 사용\nAndroid System WebView 비의존\n외부 광고차단 앱과 독립 동작")
                .setPositiveButton("확인", null)
                .show();
    }

    private void showSettings() {
        new AlertDialog.Builder(this)
                .setTitle("설정")
                .setMessage("광고/추적 필터는 유니콘 Pro, NordVPN 같은 시스템 차단기에 맡깁니다.\n\nAdownloader는 팝업 차단과 미디어 감지에 집중합니다.")
                .setPositiveButton("확인", null)
                .show();
    }

    private void showDownloadsInfo() {
        new AlertDialog.Builder(this)
                .setTitle("다운로드")
                .setMessage("다운로드는 백그라운드 Foreground Service에서 계속 진행되며 완료 시 알림이 표시됩니다.")
                .setPositiveButton("확인", null)
                .show();
    }

    private void openMoviesFolder() {
        Intent intent = new Intent(Intent.ACTION_VIEW);
        intent.setDataAndType(android.provider.MediaStore.Video.Media.EXTERNAL_CONTENT_URI, "video/*");
        try {
            startActivity(intent);
        } catch (Throwable e) {
            Toast.makeText(this, "영상 앱을 열 수 없습니다", Toast.LENGTH_SHORT).show();
        }
    }

    private void showMediaCandidates() {
        List<MediaCandidate> items = mediaDetector.snapshot();
        if (items.isEmpty()) {
            new AlertDialog.Builder(this)
                    .setTitle("감지된 동영상 없음")
                    .setMessage("페이지에서 영상을 재생해 보세요. Gecko 네트워크 요청과 DOM/Performance 리소스를 동시에 감시합니다.")
                    .setPositiveButton("확인", null)
                    .show();
            return;
        }

        String[] labels = new String[items.size()];
        for (int i = 0; i < items.size(); i++) {
            MediaCandidate item = items.get(i);
            String url = item.getUrl();
            labels[i] = item.displayName() + "\n" + (url.length() > 120 ? url.substring(0, 120) + "…" : url);
        }

        new AlertDialog.Builder(this)
                .setTitle("감지된 미디어 " + items.size() + "개")
                .setItems(labels, (dialog, which) -> confirmDownload(items.get(which)))
                .setNegativeButton("닫기", null)
                .show();
    }

    private void confirmDownload(MediaCandidate item) {
        String type = item.getType().name();
        String warning = item.getType() == MediaCandidate.MediaType.DASH
                ? "DASH/MPD 다운로드 엔진은 다음 단계에서 별도 오디오·비디오 트랙 병합까지 연결합니다. 현재는 감지만 확인할 수 있습니다."
                : "원본 스트림을 그대로 저장합니다. 재인코딩하지 않습니다.";

        AlertDialog.Builder builder = new AlertDialog.Builder(this)
                .setTitle(item.displayName())
                .setMessage(warning + "\n\n" + item.getUrl())
                .setNegativeButton("취소", null);

        if (item.getType() != MediaCandidate.MediaType.DASH) {
            builder.setPositiveButton("다운로드", (dialog, which) -> startDownload(item));
        }
        builder.show();
    }

    private void startDownload(MediaCandidate item) {
        Intent intent = new Intent(this, DownloadService.class);
        intent.setAction(DownloadService.ACTION_DOWNLOAD);
        intent.putExtra(DownloadService.EXTRA_URL, item.getUrl());
        intent.putExtra(DownloadService.EXTRA_TYPE, item.getType().name());
        intent.putExtra(DownloadService.EXTRA_HEADERS, new JSONObject(item.getHeaders()).toString());
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(intent);
        else startService(intent);
        Toast.makeText(this, "다운로드를 시작했습니다", Toast.LENGTH_SHORT).show();
    }

    private String normalizeAddress(String raw) {
        String input = raw == null ? "" : raw.trim();
        if (input.isEmpty()) return currentUrl;
        if (input.startsWith("http://") || input.startsWith("https://")) return input;
        if (!input.contains(" ") && input.contains(".")) return "https://" + input;
        return "https://www.google.com/search?q=" + URLEncoder.encode(input, StandardCharsets.UTF_8);
    }

    private void requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, REQ_NOTIFICATIONS);
        }
    }

    @Override
    public void onLocationChanged(String url) {
        runOnUiThread(() -> {
            currentUrl = url;
            if (!addressBar.hasFocus()) addressBar.setText(url);
        });
    }

    @Override
    public void onNavigationStateChanged(boolean canGoBack, boolean canGoForward) {
        runOnUiThread(() -> {
            backButton.setEnabled(canGoBack);
            forwardButton.setEnabled(canGoForward);
        });
    }

    @Override
    public void onPageProgress(int progress) {
        runOnUiThread(() -> {
            progressBar.setProgress(progress);
            progressBar.setVisibility(progress >= 100 ? View.INVISIBLE : View.VISIBLE);
        });
    }

    @Override
    public void onPopupRequested(String url) {
        runOnUiThread(() -> popupController.onPopupRequested(url));
    }

    @Override
    public void onBrowserCrashed() {
        runOnUiThread(() -> new AlertDialog.Builder(this)
                .setTitle("브라우저 탭이 종료되었습니다")
                .setMessage("Android System WebView와는 무관한 Gecko 콘텐츠 프로세스가 종료되었습니다. 현재 페이지를 새 세션으로 다시 열 수 있습니다.")
                .setNegativeButton("닫기", null)
                .setPositiveButton("다시 열기", (dialog, which) -> browser.recover(currentUrl))
                .show());
    }

    @Override
    public void onCandidateAdded(MediaCandidate candidate, int totalCount) {
        runOnUiThread(() -> mediaButton.setText("▶ " + totalCount));
    }

    @Override
    public void onBackPressed() {
        if (browser != null && browser.canGoBack()) browser.goBack();
        else super.onBackPressed();
    }

    @Override
    protected void onDestroy() {
        if (browser != null) browser.close();
        super.onDestroy();
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
