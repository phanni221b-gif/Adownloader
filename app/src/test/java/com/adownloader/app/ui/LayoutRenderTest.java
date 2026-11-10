package com.adownloader.app.ui;

import static org.junit.Assert.*;

import android.content.Context;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.view.ContextThemeWrapper;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.TextView;
import androidx.test.core.app.ApplicationProvider;
import com.adownloader.app.R;
import java.io.File;
import java.io.FileOutputStream;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;

/**
 * Renders key layouts to app/build/ui-render/*.png (light and dark) for visual review without an
 * emulator. Also asserts basic layout invariants: nothing collapses and touch targets keep 48dp.
 */
@RunWith(RobolectricTestRunner.class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = 35, qualifiers = "w411dp-h891dp-xhdpi")
public class LayoutRenderTest {
  private Context themed(boolean dark) {
    Context base = ApplicationProvider.getApplicationContext();
    Configuration config = new Configuration(base.getResources().getConfiguration());
    config.uiMode =
        (config.uiMode & ~Configuration.UI_MODE_NIGHT_MASK)
            | (dark ? Configuration.UI_MODE_NIGHT_YES : Configuration.UI_MODE_NIGHT_NO);
    return new ContextThemeWrapper(base.createConfigurationContext(config), R.style.Theme_Adownloader);
  }

  private static File render(View view, int widthDp, int heightDp, String name) throws Exception {
    float density = view.getResources().getDisplayMetrics().density;
    int width = Math.round(widthDp * density), height = Math.round(heightDp * density);
    view.measure(
        View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
        heightDp > 0
            ? View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY)
            : View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
    view.layout(0, 0, view.getMeasuredWidth(), view.getMeasuredHeight());
    Bitmap bitmap =
        Bitmap.createBitmap(view.getMeasuredWidth(), view.getMeasuredHeight(), Bitmap.Config.ARGB_8888);
    Canvas canvas = new Canvas(bitmap);
    canvas.drawColor(view.getContext().getColor(R.color.canvas));
    view.draw(canvas);
    File out = new File("build/ui-render/" + name + ".png");
    out.getParentFile().mkdirs();
    try (FileOutputStream stream = new FileOutputStream(out)) {
      bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream);
    }
    return out;
  }

  private View downloadRow(Context context, String title, String subtitle, String detail,
      int percent, String primary, boolean diagnostic, boolean open) {
    View row = LayoutInflater.from(context).inflate(R.layout.item_download, null);
    ((TextView) row.findViewById(R.id.item_title)).setText(title);
    ((TextView) row.findViewById(R.id.item_subtitle)).setText(subtitle);
    ((TextView) row.findViewById(R.id.item_detail)).setText(detail);
    var progress =
        (com.google.android.material.progressindicator.LinearProgressIndicator)
            row.findViewById(R.id.item_progress);
    progress.setVisibility(percent >= 0 ? View.VISIBLE : View.GONE);
    if (percent >= 0) progress.setProgressCompat(percent, false);
    row.findViewById(R.id.item_open).setVisibility(open ? View.VISIBLE : View.GONE);
    TextView primaryButton = row.findViewById(R.id.item_primary);
    primaryButton.setVisibility(primary == null ? View.GONE : View.VISIBLE);
    if (primary != null) primaryButton.setText(primary);
    row.findViewById(R.id.item_secondary).setVisibility(open ? View.GONE : View.VISIBLE);
    row.findViewById(R.id.item_diagnostic).setVisibility(diagnostic ? View.VISIBLE : View.GONE);
    return row;
  }

  @Test
  public void downloadRows() throws Exception {
    for (boolean dark : new boolean[] {false, true}) {
      Context context = themed(dark);
      android.widget.LinearLayout list = new android.widget.LinearLayout(context);
      list.setOrientation(android.widget.LinearLayout.VERTICAL);
      list.addView(
          downloadRow(context, "주말 캠핑 브이로그 1080p 원본", "다운로드 중 · HLS · video.example.test",
              "영상 조각 412/980\n42% · 512MB / 1.2GB · 4.1MB/s · 약 3분 남음", 42, "일시정지", false, false));
      list.addView(
          downloadRow(context, "강의 3강 - 신호와 시스템", "일시정지 · MP4 · lecture.example.test",
              "일시정지됨 · 이어받을 수 있습니다.\n230MB / 640MB", 36, "재개", false, false));
      list.addView(
          downloadRow(context, "라이브 클립", "접근 확인 필요 · HLS · clip.example.test",
              "영상에 접근할 수 없습니다. 로그인 상태 또는 사이트 재생 여부를 확인해 주세요.", -1, "페이지 열기", true, false));
      list.addView(
          downloadRow(context, "여행 하이라이트", "완료 · MP4 · travel.example.test",
              "1080p · 845MB · 11월 10일", -1, null, false, true));
      File png = render(list, 411, 0, "downloads-" + (dark ? "dark" : "light"));
      assertTrue(png.length() > 0);
      View firstPrimary = list.getChildAt(0).findViewById(R.id.item_primary);
      assertTrue("touch target", firstPrimary.getHeight() >= 48 * context.getResources().getDisplayMetrics().density - 1);
    }
  }

  @Test
  public void settingsScreen() throws Exception {
    for (boolean dark : new boolean[] {false, true}) {
      Context context = themed(dark);
      View screen = LayoutInflater.from(context).inflate(R.layout.screen_settings, null);
      String[][] rows = {
        {"setting_popups", "팝업 허용 사이트", "새 창을 열 수 있도록 허용한 사이트를 관리합니다"},
        {"setting_parallel_downloads", "동시 다운로드", "2개까지 함께 받기"},
        {"setting_background", "알림", "켜짐 · 앱을 닫아도 다운로드는 계속되고 진행 상황을 알려 줍니다"},
        {"setting_theme", "테마", "시스템 설정 따르기"},
      };
      for (String[] row : rows) {
        TextView button =
            screen.findViewById(context.getResources().getIdentifier(row[0], "id", context.getPackageName()));
        android.text.SpannableStringBuilder text = new android.text.SpannableStringBuilder(row[1]);
        int start = text.length();
        text.append('\n').append(row[2]);
        text.setSpan(new android.text.style.RelativeSizeSpan(0.86f), start, text.length(), 0);
        text.setSpan(new android.text.style.ForegroundColorSpan(context.getColor(R.color.ink_muted)), start, text.length(), 0);
        button.setText(text);
      }
      screen.findViewById(R.id.background_state).setVisibility(View.GONE);
      ((TextView) screen.findViewById(R.id.app_version)).setText("버전 0.7.1 · Gecko 159");
      View content = screen.findViewById(R.id.settings_content);
      render(content, 411, 0, "settings-" + (dark ? "dark" : "light"));
    }
  }

  @Test
  public void browserToolbarCompactAndWide() throws Exception {
    for (int width : new int[] {360, 411, 720}) {
      Context context = themed(false);
      View shell = LayoutInflater.from(context).inflate(R.layout.activity_main, null);
      boolean compact = width < 600;
      shell.findViewById(R.id.browser_back).setVisibility(compact ? View.GONE : View.VISIBLE);
      shell.findViewById(R.id.browser_forward).setVisibility(compact ? View.GONE : View.VISIBLE);
      ((TextView) shell.findViewById(R.id.address_bar)).setText("video.example.test/watch/42");
      View toolbar = shell.findViewById(R.id.browser_toolbar);
      render(shell, width, 300, "browser-" + width + "dp");
      View address = shell.findViewById(R.id.address_bar);
      float density = context.getResources().getDisplayMetrics().density;
      assertTrue("address field width at " + width + "dp: " + address.getWidth() / density,
          address.getWidth() / density >= (compact ? 150 : 300));
      assertTrue(toolbar.getHeight() > 0);
    }
  }
}
