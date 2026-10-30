package com.adownloader.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.provider.MediaStore;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.view.inputmethod.EditorInfo;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONObject;
import org.mozilla.geckoview.GeckoResult;
import org.mozilla.geckoview.GeckoRuntime;
import org.mozilla.geckoview.GeckoSession;
import org.mozilla.geckoview.GeckoSessionSettings;
import org.mozilla.geckoview.GeckoView;
import org.mozilla.geckoview.WebExtension;

import java.net.URI;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class GeckoBrowserActivity extends Activity {
    private static GeckoRuntime runtime;
    private static final String EXTENSION_URI = "resource://android/assets/media_sniffer/";
    private static final String EXTENSION_ID = "adownloader-media@local";

    private GeckoView geckoView;
    private GeckoSession session;
    private EditText address;
    private TextView mediaButton;
    private LinearLayout root;
    private LinearLayout toolbar;
    private LinearLayout bottomBar;
    private FrameLayout body;
    private GeckoMediaSniffer mediaSniffer;
    private boolean canGoBack;
    private boolean canGoForward;
    private String currentUrl = "https://www.google.com";

    private android.content.SharedPreferences prefs() {
        return getSharedPreferences("adownloader", MODE_PRIVATE);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (android.os.Build.VERSION.SDK_INT >= 30) getWindow().setDecorFitsSystemWindows(false);
        getWindow().setStatusBarColor(Color.WHITE);
        getWindow().setNavigationBarColor(Color.WHITE);
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR);

        mediaSniffer = new GeckoMediaSniffer(list -> updateMediaButton(list.size()));
        buildUi();
        initGecko();
        applyInsets();
    }

    private void buildUi() {
        root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.WHITE);

        toolbar = new LinearLayout(this);
        toolbar.setOrientation(LinearLayout.HORIZONTAL);
        toolbar.setGravity(Gravity.CENTER_VERTICAL);
        toolbar.setPadding(dp(6), dp(5), dp(6), dp(5));

        toolbar.addView(navButton("‹", v -> { if (canGoBack) session.goBack(); }));
        toolbar.addView(navButton("›", v -> { if (canGoForward) session.goForward(); }));
        toolbar.addView(navButton("↻", v -> session.reload()));

        address = new EditText(this);
        address.setSingleLine(true);
        address.setTextSize(14f);
        address.setHint("검색 또는 주소 입력");
        address.setImeOptions(EditorInfo.IME_ACTION_GO);
        address.setPadding(dp(12), 0, dp(12), 0);
        address.setBackgroundColor(Color.rgb(245,245,247));
        address.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_GO || (event != null && event.getKeyCode() == KeyEvent.KEYCODE_ENTER)) {
                loadTypedAddress();
                return true;
            }
            return false;
        });
        LinearLayout.LayoutParams addressParams = new LinearLayout.LayoutParams(0, dp(42), 1f);
        addressParams.setMargins(dp(4), 0, dp(4), 0);
        toolbar.addView(address, addressParams);
        toolbar.addView(navButton("⋮", v -> showBrowserMenu()));

        body = new FrameLayout(this);
        geckoView = new GeckoView(this);
        body.addView(geckoView, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        mediaButton = new TextView(this);
        mediaButton.setGravity(Gravity.CENTER);
        mediaButton.setTextColor(Color.WHITE);
        mediaButton.setTextSize(15f);
        mediaButton.setBackgroundColor(Color.rgb(225,34,71));
        mediaButton.setVisibility(View.GONE);
        mediaButton.setOnClickListener(v -> showDetectedMedia());
        FrameLayout.LayoutParams mediaParams = new FrameLayout.LayoutParams(dp(58), dp(58), Gravity.END | Gravity.BOTTOM);
        mediaParams.setMargins(0, 0, dp(18), dp(18));
        body.addView(mediaButton, mediaParams);

        bottomBar = new LinearLayout(this);
        bottomBar.setOrientation(LinearLayout.HORIZONTAL);
        bottomBar.setGravity(Gravity.CENTER);
        bottomBar.setBackgroundColor(Color.WHITE);
        bottomBar.addView(bottomItem("◎", "브라우저", v -> geckoView.setVisibility(View.VISIBLE)));
        bottomBar.addView(bottomItem("⇩", "다운로드", v -> showDownloads(false)));
        bottomBar.addView(bottomItem("▷", "플레이어", v -> showDownloads(true)));
        bottomBar.addView(bottomItem("⚙", "설정", v -> showSettings()));

        root.addView(toolbar, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(54)));
        root.addView(body, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        root.addView(bottomBar, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(64)));
        setContentView(root);
    }

    private void initGecko() {
        if (runtime == null) runtime = GeckoRuntime.create(this);

        GeckoSessionSettings settings = new GeckoSessionSettings.Builder()
                .allowJavascript(true)
                .useTrackingProtection(false)
                .suspendMediaWhenInactive(false)
                .build();
        session = new GeckoSession(settings);
        session.setContentDelegate(new GeckoSession.ContentDelegate() {
            @Override
            public void onFullScreen(GeckoSession session, boolean fullScreen) {
                if (fullScreen) {
                    getWindow().addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN);
                    toolbar.setVisibility(View.GONE);
                    bottomBar.setVisibility(View.GONE);
                } else {
                    getWindow().clearFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN);
                    toolbar.setVisibility(View.VISIBLE);
                    bottomBar.setVisibility(View.VISIBLE);
                }
            }

            @Override
            public void onCrash(GeckoSession session) {
                Toast.makeText(GeckoBrowserActivity.this, "브라우저 엔진이 종료되었습니다. 앱을 다시 열어주세요.", Toast.LENGTH_LONG).show();
            }
        });

        session.setNavigationDelegate(new GeckoSession.NavigationDelegate() {
            @Override
            public void onLocationChange(GeckoSession s, String url, List<GeckoSession.PermissionDelegate.ContentPermission> perms, Boolean hasUserGesture) {
                if (url != null) {
                    currentUrl = url;
                    address.setText(url);
                }
            }

            @Override
            public void onCanGoBack(GeckoSession s, boolean value) { canGoBack = value; }

            @Override
            public void onCanGoForward(GeckoSession s, boolean value) { canGoForward = value; }

            @Override
            public GeckoResult<GeckoSession> onNewSession(GeckoSession s, String uri) {
                if (uri != null) runOnUiThread(() -> showPopupDecision(uri));
                return null;
            }
        });

        session.setProgressDelegate(new GeckoSession.ProgressDelegate() {
            @Override
            public void onPageStart(GeckoSession s, String url) {
                mediaSniffer.clear();
                currentUrl = url;
                address.setText(url);
            }
        });

        session.open(runtime);
        geckoView.setSession(session);
        installMediaExtensionAndLoad();
    }

    private void installMediaExtensionAndLoad() {
        WebExtension.MessageDelegate delegate = new WebExtension.MessageDelegate() {
            @Override
            public GeckoResult<Object> onMessage(String nativeApp, Object message, WebExtension.MessageSender sender) {
                if (!"adownloader".equals(nativeApp) || !(message instanceof JSONObject)) return null;
                JSONObject obj = (JSONObject) message;
                String event = obj.optString("event", "");
                String url = obj.optString("url", "");
                if ("popup".equals(event)) {
                    if (url.startsWith("http://") || url.startsWith("https://")) runOnUiThread(() -> showPopupDecision(url));
                    return null;
                }
                mediaSniffer.observeMessage(obj, currentUrl);
                return null;
            }
        };

        runtime.getWebExtensionController()
                .ensureBuiltIn(EXTENSION_URI, EXTENSION_ID)
                .accept(extension -> {
                    extension.setMessageDelegate(delegate, "adownloader");
                    session.getWebExtensionController().setMessageDelegate(extension, delegate, "adownloader");
                    session.loadUri(currentUrl);
                }, error -> {
                    Toast.makeText(this, "미디어 감지 확장 초기화 실패: " + error.getClass().getSimpleName(), Toast.LENGTH_LONG).show();
                    session.loadUri(currentUrl);
                });
    }

    private void showPopupDecision(String url) {
        boolean confirm = prefs().getBoolean("popup_confirm", true);
        if (!confirm) {
            session.loadUri(url);
            return;
        }
        String host;
        try { host = new URI(url).getHost(); } catch (Throwable e) { host = url; }
        final String messageHost = host == null ? url : host;
        new AlertDialog.Builder(this)
                .setTitle("새 창을 차단했습니다")
                .setMessage(messageHost + "\n\n이 페이지를 열까요?")
                .setNegativeButton("차단", null)
                .setPositiveButton("한 번 열기", (d, w) -> session.loadUri(url))
                .show();
    }

    private void showDetectedMedia() {
        List<MediaCandidate> list = mediaSniffer.snapshot();
        if (list.isEmpty()) {
            Toast.makeText(this, "아직 감지된 동영상이 없습니다. 영상을 재생해 보세요.", Toast.LENGTH_SHORT).show();
            return;
        }
        String[] labels = new String[list.size()];
        for (int i = 0; i < list.size(); i++) labels[i] = (i + 1) + ". " + list.get(i).displayName();
        new AlertDialog.Builder(this)
                .setTitle("감지된 미디어 " + list.size() + "개")
                .setItems(labels, (dialog, which) -> confirmDownload(list.get(which)))
                .setNeutralButton("목록 초기화", (dialog, which) -> mediaSniffer.clear())
                .setNegativeButton("닫기", null)
                .show();
    }

    private void confirmDownload(MediaCandidate candidate) {
        if (candidate.getType() == MediaCandidate.MediaType.DASH) {
            new AlertDialog.Builder(this)
                    .setTitle("DASH 감지됨")
                    .setMessage("MPD 스트림까지 감지했습니다. DASH 다운로드 병합은 다음 단계에서 연결합니다.")
                    .setPositiveButton("확인", null)
                    .show();
            return;
        }
        EditText input = new EditText(this);
        input.setSingleLine(true);
        input.setHint("파일 이름 (비워두면 자동)");
        new AlertDialog.Builder(this)
                .setTitle(candidate.displayName())
                .setMessage(candidate.getUrl())
                .setView(input)
                .setNegativeButton("취소", null)
                .setPositiveButton("다운로드", (dialog, which) -> startDownload(candidate, input.getText().toString()))
                .show();
    }

    private void startDownload(MediaCandidate candidate, String requestedName) {
        JSONObject headers = new JSONObject(candidate.getHeaders());
        Intent intent = new Intent(this, DownloadService.class);
        intent.setAction(DownloadService.ACTION_DOWNLOAD);
        intent.putExtra(DownloadService.EXTRA_URL, candidate.getUrl());
        intent.putExtra(DownloadService.EXTRA_TYPE, candidate.getType().name());
        intent.putExtra(DownloadService.EXTRA_HEADERS, headers.toString());
        intent.putExtra(DownloadService.EXTRA_FILENAME, requestedName);
        startForegroundService(intent);
        Toast.makeText(this, "다운로드를 시작했습니다", Toast.LENGTH_SHORT).show();
    }

    private void showDownloads(boolean playOnTap) {
        List<String> names = new ArrayList<>();
        List<Uri> uris = new ArrayList<>();
        String[] projection = { MediaStore.Video.Media._ID, MediaStore.Video.Media.DISPLAY_NAME };
        try (android.database.Cursor cursor = getContentResolver().query(
                MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
                projection,
                MediaStore.Video.Media.RELATIVE_PATH + " LIKE ?",
                new String[]{ android.os.Environment.DIRECTORY_MOVIES + "/Adownloader%" },
                MediaStore.Video.Media.DATE_ADDED + " DESC")) {
            if (cursor != null) {
                int idCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media._ID);
                int nameCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DISPLAY_NAME);
                while (cursor.moveToNext()) {
                    long id = cursor.getLong(idCol);
                    names.add(cursor.getString(nameCol));
                    uris.add(Uri.withAppendedPath(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, Long.toString(id)));
                }
            }
        }
        if (names.isEmpty()) {
            Toast.makeText(this, "저장된 영상이 없습니다", Toast.LENGTH_SHORT).show();
            return;
        }
        new AlertDialog.Builder(this)
                .setTitle(playOnTap ? "플레이어" : "다운로드")
                .setItems(names.toArray(new String[0]), (dialog, which) -> {
                    Uri uri = uris.get(which);
                    if (playOnTap) startActivity(new Intent(this, PlayerActivity.class).setData(uri));
                    else startActivity(Intent.createChooser(new Intent(Intent.ACTION_VIEW).setDataAndType(uri, "video/*").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION), "영상 열기"));
                })
                .setNegativeButton("닫기", null)
                .show();
    }

    private void showSettings() {
        boolean popup = prefs().getBoolean("popup_confirm", true);
        boolean desktop = prefs().getBoolean("gecko_desktop", false);
        String[] items = { "팝업 열기 전 확인", "PC 화면 모드", "GeckoView 엔진 정보" };
        new AlertDialog.Builder(this)
                .setTitle("설정")
                .setMultiChoiceItems(items, new boolean[]{popup, desktop, false}, (dialog, which, checked) -> {
                    if (which == 0) prefs().edit().putBoolean("popup_confirm", checked).apply();
                    if (which == 1) {
                        prefs().edit().putBoolean("gecko_desktop", checked).apply();
                        session.getSettings().setUserAgentMode(checked ? GeckoSessionSettings.USER_AGENT_MODE_DESKTOP : GeckoSessionSettings.USER_AGENT_MODE_MOBILE);
                        session.getSettings().setViewportMode(checked ? GeckoSessionSettings.VIEWPORT_MODE_DESKTOP : GeckoSessionSettings.VIEWPORT_MODE_MOBILE);
                        session.reload();
                    }
                    if (which == 2) {
                        ((AlertDialog) dialog).getListView().setItemChecked(2, false);
                        showEngineInfo();
                    }
                })
                .setPositiveButton("완료", null)
                .show();
    }

    private void showEngineInfo() {
        new AlertDialog.Builder(this)
                .setTitle("브라우저 엔진")
                .setMessage("GeckoView\n\n" + GeckoSession.getDefaultUserAgent() + "\n\nAndroid System WebView를 사용하지 않습니다.")
                .setPositiveButton("확인", null)
                .show();
    }

    private void showBrowserMenu() {
        String[] items = { "새로고침", "감지 목록", "브라우저 엔진 정보", "외부 브라우저로 열기" };
        new AlertDialog.Builder(this).setItems(items, (dialog, which) -> {
            if (which == 0) session.reload();
            else if (which == 1) showDetectedMedia();
            else if (which == 2) showEngineInfo();
            else if (which == 3) startActivity(Intent.createChooser(new Intent(Intent.ACTION_VIEW, Uri.parse(currentUrl)), "브라우저 선택"));
        }).show();
    }

    private void loadTypedAddress() {
        String raw = address.getText().toString().trim();
        if (raw.isEmpty()) return;
        String url;
        if (raw.startsWith("http://") || raw.startsWith("https://")) url = raw;
        else if (raw.contains(".") && !raw.contains(" ")) url = "https://" + raw;
        else url = "https://www.google.com/search?q=" + Uri.encode(raw);
        session.loadUri(url);
        address.clearFocus();
    }

    @Override
    public void onBackPressed() {
        if (canGoBack) session.goBack();
        else super.onBackPressed();
    }

    @Override
    protected void onDestroy() {
        try { if (session != null) session.close(); } catch (Throwable ignored) {}
        super.onDestroy();
    }

    private Button navButton(String text, View.OnClickListener listener) {
        Button button = new Button(this);
        button.setText(text);
        button.setTextSize(22f);
        button.setMinWidth(0);
        button.setMinimumWidth(0);
        button.setPadding(0,0,0,0);
        button.setBackgroundColor(Color.TRANSPARENT);
        button.setOnClickListener(listener);
        button.setLayoutParams(new LinearLayout.LayoutParams(dp(38), dp(42)));
        return button;
    }

    private View bottomItem(String icon, String label, View.OnClickListener listener) {
        LinearLayout container = new LinearLayout(this);
        container.setOrientation(LinearLayout.VERTICAL);
        container.setGravity(Gravity.CENTER);
        container.setOnClickListener(listener);
        TextView iconView = new TextView(this);
        iconView.setText(icon);
        iconView.setTextSize(23f);
        iconView.setGravity(Gravity.CENTER);
        TextView labelView = new TextView(this);
        labelView.setText(label);
        labelView.setTextSize(11f);
        labelView.setGravity(Gravity.CENTER);
        container.addView(iconView);
        container.addView(labelView);
        container.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f));
        return container;
    }

    private void updateMediaButton(int count) {
        mediaButton.setVisibility(count > 0 ? View.VISIBLE : View.GONE);
        mediaButton.setText(count > 99 ? "99+" : Integer.toString(count));
    }

    private void applyInsets() {
        if (android.os.Build.VERSION.SDK_INT < 30) return;
        root.setOnApplyWindowInsetsListener((v, insets) -> {
            android.graphics.Insets status = insets.getInsets(WindowInsets.Type.statusBars());
            android.graphics.Insets nav = insets.getInsets(WindowInsets.Type.navigationBars());
            root.setPadding(0, status.top, 0, nav.bottom);
            return insets;
        });
        root.requestApplyInsets();
    }

    private int dp(int value) {
        return (int) (value * getResources().getDisplayMetrics().density + 0.5f);
    }
}
