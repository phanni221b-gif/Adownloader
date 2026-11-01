package com.adownloader.app;

import static org.junit.Assert.*;

import android.app.*;
import android.content.*;
import android.graphics.Rect;
import android.net.Uri;
import android.os.StrictMode;
import android.provider.MediaStore;
import android.view.*;
import androidx.recyclerview.widget.*;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.uiautomator.UiDevice;
import androidx.window.layout.*;
import androidx.window.testing.layout.WindowLayoutInfoPublisherRule;
import com.adownloader.app.browser.BrowserSessionManager;
import com.adownloader.app.jobs.*;
import com.adownloader.app.ui.*;
import java.io.*;
import java.util.*;
import java.util.concurrent.*;
import org.json.JSONObject;
import org.junit.*;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
public class FoldStressIntegrationTest {
  @Rule public WindowLayoutInfoPublisherRule windows = new WindowLayoutInfoPublisherRule();
  private final Instrumentation instrumentation = InstrumentationRegistry.getInstrumentation();
  private final Context context = instrumentation.getTargetContext();
  private final UiDevice device = UiDevice.getInstance(instrumentation);
  private MainActivity activity;
  private DownloadIntegrationTest fixture;
  private final List<Uri> files = new ArrayList<>();
  private final List<Long> jobs = new ArrayList<>();
  private StrictMode.ThreadPolicy oldPolicy;

  private <T> T main(Callable<T> call) throws Exception {
    FutureTask<T> task = new FutureTask<>(call);
    instrumentation.runOnMainSync(task);
    return task.get();
  }

  private void await(Callable<Boolean> check) throws Exception {
    long deadline = System.currentTimeMillis() + 20000;
    while (System.currentTimeMillis() < deadline) {
      if (main(check)) return;
      Thread.sleep(100);
    }
    fail("native layout/data did not settle");
  }

  @After
  public void cleanup() throws Exception {
    if (activity != null)
      main(
          () -> {
            if (oldPolicy != null) StrictMode.setThreadPolicy(oldPolicy);
            activity.finish();
            return null;
          });
    instrumentation.waitForIdleSync();
    for (Uri uri : files) context.getContentResolver().delete(uri, null, null);
    DownloadJobStore store = DownloadJobs.get(context).store;
    for (long id : jobs) {
      store.state(id, DownloadJobStore.State.CANCELED, "");
      store.cleanup(id);
    }
    store.clearTerminal(DownloadJobStore.State.CANCELED);
    if (fixture != null) fixture.stop();
    device.executeShellCommand("wm size reset");
    device.executeShellCommand("wm density reset");
  }

  @Test
  public void nativeFoldHingesSheetsAndLargeListsRetainFiveSessionsWithoutMainThreadNetwork()
      throws Exception {
    device.executeShellCommand("wm size 1400x1800");
    device.executeShellCommand("wm density 160");
    fixture = new DownloadIntegrationTest();
    fixture.start();
    var field = DownloadIntegrationTest.class.getDeclaredField("base");
    field.setAccessible(true);
    String base = (String) field.get(fixture);
    DownloadJobStore store = DownloadJobs.get(context).store;
    CountDownLatch ready = new CountDownLatch(1);
    DownloadJobs.get(context).whenReady(ready::countDown);
    assertTrue(ready.await(15, TimeUnit.SECONDS));
    for (var state :
        new DownloadJobStore.State[] {
          DownloadJobStore.State.COMPLETED,
          DownloadJobStore.State.FAILED,
          DownloadJobStore.State.CANCELED
        }) store.clearTerminal(state);
    for (int i = 0; i < 100; i++) {
      long id =
          store.enqueue(
              "fold-stress-" + i,
              "VIDEO",
              "Stress history " + i,
              "localhost",
              new JSONObject().put("url", base + "direct.mp4").put("headers", new JSONObject()));
      jobs.add(id);
      store.state(id, DownloadJobStore.State.FAILED, "NETWORK");
    }
    byte[] media;
    try (InputStream in = instrumentation.getContext().getAssets().open("fixtures/direct.mp4")) {
      media = in.readAllBytes();
    }
    for (int i = 0; i < 500; i++) {
      ContentValues values = new ContentValues();
      values.put(MediaStore.MediaColumns.DISPLAY_NAME, "Fold-stress-" + UUID.randomUUID() + ".mp4");
      values.put(MediaStore.MediaColumns.MIME_TYPE, "video/mp4");
      values.put(MediaStore.MediaColumns.RELATIVE_PATH, "Movies/Adownloader");
      values.put(MediaStore.MediaColumns.IS_PENDING, 1);
      Uri uri =
          context.getContentResolver().insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values);
      assertNotNull(uri);
      files.add(uri);
      try (OutputStream out = context.getContentResolver().openOutputStream(uri)) {
        out.write(media);
      }
      values.clear();
      values.put(MediaStore.MediaColumns.IS_PENDING, 0);
      context.getContentResolver().update(uri, values, null, null);
    }
    activity =
        (MainActivity)
            instrumentation.startActivitySync(
                new Intent(context, MainActivity.class)
                    .setData(Uri.parse(base + "index.html"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
    var managerField = MainActivity.class.getDeclaredField("browser");
    managerField.setAccessible(true);
    BrowserSessionManager manager = (BrowserSessionManager) managerField.get(activity);
    main(
        () -> {
          oldPolicy = StrictMode.getThreadPolicy();
          StrictMode.setThreadPolicy(
              new StrictMode.ThreadPolicy.Builder(oldPolicy)
                  .detectNetwork()
                  .penaltyDeath()
                  .build());
          while (manager.tabs().size() < 5) assertTrue(manager.newTab(base + "index.html"));
          manager.getDetector().reset();
          for (int i = 0; i < 200; i++)
            manager
                .getDetector()
                .capture(
                    base + "clip-" + i + ".mp4",
                    "video/mp4",
                    "network-response",
                    base + "index.html",
                    Collections.emptyMap());
          assertEquals(200, manager.getDetector().snapshot().size());
          return null;
        });
    var sessions =
        main(
            () -> {
              var result = new ArrayList<org.mozilla.geckoview.GeckoSession>();
              for (var tab : manager.tabs()) result.add(tab.session);
              return result;
            });
    await(() -> activity.findViewById(R.id.app_root).getWidth() > 1300);
    windows.overrideWindowLayoutInfo(
        new WindowLayoutInfo(
            Collections.singletonList(new Feature(new Rect(580, 0, 620, 1800), true))));
    await(
        () ->
            FoldLayoutController.geometry(activity) != null
                && activity.findViewById(R.id.browser_page).getWidth() < 900);
    main(
        () -> {
          activity.findViewById(R.id.nav_downloads).performClick();
          return null;
        });
    RecyclerView downloads = main(() -> activity.findViewById(R.id.download_list));
    await(() -> downloads.getAdapter().getItemCount() == 100 && downloads.getChildCount() > 1);
    assertAvoidsHinge(downloads, new Rect(580, 0, 620, 1800));
    main(
        () -> {
          activity.findViewById(R.id.nav_library).performClick();
          return null;
        });
    RecyclerView library = main(() -> activity.findViewById(R.id.library_list));
    await(() -> library.getAdapter().getItemCount() >= 500 && library.getChildCount() > 1);
    assertAvoidsHinge(library, new Rect(580, 0, 620, 1800));
    long start = System.currentTimeMillis();
    for (int position : new int[] {100, 250, 499, 0}) {
      main(
          () -> {
            library.scrollToPosition(position);
            return null;
          });
      instrumentation.waitForIdleSync();
    }
    assertTrue("scrolling must stay responsive", System.currentTimeMillis() - start < 10000);
    com.google.android.material.bottomsheet.BottomSheetDialog sheet =
        main(
            () ->
                UiSheets.action(
                    activity,
                    R.drawable.ic_settings,
                    "Fold sheet",
                    "Hinge-safe content",
                    "닫기",
                    null,
                    null,
                    null,
                    null));
    Thread.sleep(500);
    main(
        () -> {
          View panel = sheet.findViewById(com.google.android.material.R.id.design_bottom_sheet);
          Rect rect = rect(panel);
          assertFalse(Rect.intersects(rect, new Rect(580, 0, 620, 1800)));
          sheet.dismiss();
          return null;
        });
    windows.overrideWindowLayoutInfo(
        new WindowLayoutInfo(
            Collections.singletonList(new Feature(new Rect(0, 850, 1400, 900), false))));
    await(() -> activity.findViewById(R.id.library_page).getHeight() < 950);
    assertFalse(
        main(
            () ->
                Rect.intersects(
                    rect(activity.findViewById(R.id.library_page)), new Rect(0, 850, 1400, 900))));
    main(
        () -> {
          for (int i = 0; i < 5; i++) assertSame(sessions.get(i), manager.tabs().get(i).session);
          context
              .getSharedPreferences("device_diagnostics", 0)
              .edit()
              .putString("download", "https://secret/?cookie=private")
              .putString("crash", "password-secret")
              .commit();
          String report = DeviceDiagnostics.collect(activity);
          assertFalse(report.contains("secret"));
          assertFalse(report.contains(base));
          assertTrue(report.contains("HALF_OPENED"));
          assertTrue(report.contains(com.adownloader.app.BuildConfig.GECKO_VERSION));
          return null;
        });
    windows.overrideWindowLayoutInfo(new WindowLayoutInfo(Collections.emptyList()));
    await(() -> activity.findViewById(R.id.library_page).getHeight() > 1000);
  }

  private void assertAvoidsHinge(RecyclerView list, Rect hinge) throws Exception {
    main(
        () -> {
          for (int i = 0; i < list.getChildCount(); i++)
            assertFalse(
                "item crosses hinge: " + rect(list.getChildAt(i)),
                Rect.intersects(rect(list.getChildAt(i)), hinge));
          return null;
        });
  }

  private static Rect rect(View view) {
    int[] xy = new int[2];
    view.getLocationInWindow(xy);
    return new Rect(xy[0], xy[1], xy[0] + view.getWidth(), xy[1] + view.getHeight());
  }

  private static final class Feature implements FoldingFeature {
    private final Rect bounds;
    private final boolean vertical;

    Feature(Rect bounds, boolean vertical) {
      this.bounds = bounds;
      this.vertical = vertical;
    }

    public Rect getBounds() {
      return new Rect(bounds);
    }

    public boolean isSeparating() {
      return true;
    }

    public Orientation getOrientation() {
      return vertical ? Orientation.VERTICAL : Orientation.HORIZONTAL;
    }

    public State getState() {
      return State.HALF_OPENED;
    }

    public OcclusionType getOcclusionType() {
      return OcclusionType.FULL;
    }
  }
}
