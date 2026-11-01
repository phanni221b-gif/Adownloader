package com.adownloader.app.ui;

import android.app.Activity;
import android.graphics.Rect;
import android.view.*;
import android.widget.*;
import androidx.core.util.Consumer;
import androidx.recyclerview.widget.*;
import androidx.window.java.layout.WindowInfoTrackerCallbackAdapter;
import androidx.window.layout.*;
import com.adownloader.app.R;
import java.util.*;

/** Window-local fold geometry. Layout changes never replace a Gecko session. */
public final class FoldLayoutController implements AutoCloseable {
  public static final class Geometry {
    public final Rect bounds;
    public final boolean vertical, separating, occluding, halfOpen;

    Geometry(FoldingFeature feature) {
      bounds = new Rect(feature.getBounds());
      vertical = feature.getOrientation() == FoldingFeature.Orientation.VERTICAL;
      separating = feature.isSeparating();
      occluding = feature.getOcclusionType() == FoldingFeature.OcclusionType.FULL;
      halfOpen = feature.getState() == FoldingFeature.State.HALF_OPENED;
    }
  }

  private static final Map<Activity, Geometry> geometries =
      Collections.synchronizedMap(new WeakHashMap<>());
  private static final Map<Activity, List<java.lang.ref.WeakReference<Runnable>>> watchers =
      new WeakHashMap<>();

  public static Geometry geometry(Activity activity) {
    return geometries.get(activity);
  }

  private final Activity activity;
  private final Runnable changed;
  private final WindowInfoTrackerCallbackAdapter tracker;
  private final Consumer<WindowLayoutInfo> listener;
  private View observed;
  private String last = "";
  private boolean closed;
  private final View.OnLayoutChangeListener layout = (v, l, t, r, b, ol, ot, or, ob) -> refresh();

  public FoldLayoutController(Activity activity, Runnable changed) {
    this.activity = activity;
    this.changed = changed;
    tracker = new WindowInfoTrackerCallbackAdapter(WindowInfoTracker.getOrCreate(activity));
    listener =
        info -> {
          if (closed) return;
          Geometry selected = null;
          for (DisplayFeature feature : info.getDisplayFeatures())
            if (feature instanceof FoldingFeature) {
              selected = new Geometry((FoldingFeature) feature);
              if (selected.separating) break;
            }
          if (selected == null) geometries.remove(activity);
          else geometries.put(activity, selected);
          last = "";
          refresh();
          List<java.lang.ref.WeakReference<Runnable>> clients = watchers.get(activity);
          if (clients != null)
            for (var reference : new ArrayList<>(clients)) {
              Runnable callback = reference.get();
              if (callback != null) callback.run();
              else clients.remove(reference);
            }
        };
    tracker.addWindowLayoutInfoListener(activity, activity.getMainExecutor(), listener);
    refresh();
  }

  public static boolean verticalSplit(Activity activity) {
    Geometry g = geometry(activity);
    return g != null && g.vertical && (g.separating || g.occluding);
  }

  public static boolean spansVerticalHinge(Activity activity, View view) {
    Geometry g = geometry(activity);
    if (!verticalSplit(activity) || view.getWidth() == 0) return false;
    int[] location = new int[2];
    view.getLocationInWindow(location);
    return g.bounds.left > location[0] && g.bounds.right < location[0] + view.getWidth();
  }

  public static AutoCloseable watch(Activity activity, Runnable callback) {
    var reference = new java.lang.ref.WeakReference<>(callback);
    watchers.computeIfAbsent(activity, key -> new ArrayList<>()).add(reference);
    return () -> {
      var clients = watchers.get(activity);
      if (clients != null) {
        clients.remove(reference);
        if (clients.isEmpty()) watchers.remove(activity);
      }
    };
  }

  public void refresh() {
    if (closed) return;
    View root = activity.findViewById(R.id.app_root);
    if (root == null) return;
    if (observed != root) {
      if (observed != null) observed.removeOnLayoutChangeListener(layout);
      observed = root;
      root.addOnLayoutChangeListener(layout);
      last = "";
    }
    Geometry g = geometry(activity);
    String next =
        root.getWidth()
            + ":"
            + root.getHeight()
            + ":"
            + root.getPaddingBottom()
            + ":"
            + (g == null
                ? "none"
                : g.bounds
                    + ":"
                    + g.vertical
                    + ":"
                    + g.separating
                    + ":"
                    + g.occluding
                    + ":"
                    + g.halfOpen);
    if (next.equals(last) || root.getWidth() == 0) return;
    last = next;
    View browser = activity.findViewById(R.id.browser_page);
    View parent = (View) browser.getParent();
    int[] origin = new int[2];
    parent.getLocationInWindow(origin);
    Rect local = g == null ? null : new Rect(g.bounds);
    if (local != null) local.offset(-origin[0], -origin[1]);
    for (int id :
        new int[] {R.id.browser_page, R.id.downloads_page, R.id.library_page, R.id.settings_page}) {
      View page = activity.findViewById(id);
      FrameLayout.LayoutParams params = (FrameLayout.LayoutParams) page.getLayoutParams();
      int width = -1, height = -1, left = 0, top = 0;
      if (g != null
          && (g.separating || g.occluding)
          && !g.vertical
          && local.top > 0
          && local.bottom < parent.getHeight()) {
        int above = local.top, below = parent.getHeight() - local.bottom;
        if (above >= dp(180) || above >= below) height = above;
        else {
          height = below;
          top = local.bottom;
        }
      }
      if (g != null
          && g.vertical
          && (g.separating || g.occluding)
          && local.left > 0
          && local.right < parent.getWidth()
          && (id == R.id.browser_page && g.occluding
              || Math.min(local.left, parent.getWidth() - local.right) < dp(220))) {
        if (local.left > parent.getWidth() - local.right) width = local.left;
        else {
          left = local.right;
          width = parent.getWidth() - local.right;
        }
      }
      if (params.width != width
          || params.height != height
          || params.leftMargin != left
          || params.topMargin != top) {
        params.width = width;
        params.height = height;
        params.leftMargin = left;
        params.topMargin = top;
        params.gravity = Gravity.TOP | Gravity.LEFT;
        page.setLayoutParams(params);
      }
    }
    View toolbar = activity.findViewById(R.id.browser_toolbar);
    LinearLayout.LayoutParams bar = (LinearLayout.LayoutParams) toolbar.getLayoutParams();
    int available =
        browser.getLayoutParams().width > 0 ? browser.getLayoutParams().width : parent.getWidth();
    int width = Math.min(available, dp(720));
    if (bar.width != width) {
      bar.width = width;
      bar.gravity = Gravity.CENTER_HORIZONTAL;
      toolbar.setLayoutParams(bar);
    }
    changed.run();
    for (int id : new int[] {R.id.download_list, R.id.library_list}) {
      RecyclerView list = activity.findViewById(id);
      if (list != null) list.invalidateItemDecorations();
    }
  }

  public static Rect sheetPane(Activity activity, int width, int height) {
    Rect full = new Rect(0, 0, width, height);
    Geometry g = geometry(activity);
    if (g == null || !(g.separating || g.occluding)) return full;
    if (g.vertical && g.bounds.left > 0 && g.bounds.right < width) {
      return g.bounds.left > width - g.bounds.right
          ? new Rect(0, 0, g.bounds.left, height)
          : new Rect(g.bounds.right, 0, width, height);
    }
    if (!g.vertical && g.bounds.top > 0 && g.bounds.bottom < height) {
      return g.bounds.top > height - g.bounds.bottom
          ? new Rect(0, 0, width, g.bounds.top)
          : new Rect(0, g.bounds.bottom, width, height);
    }
    return full;
  }

  public static RecyclerView.ItemDecoration gridDecoration(Activity activity) {
    return new RecyclerView.ItemDecoration() {
      @Override
      public void getItemOffsets(
          Rect out, View child, RecyclerView parent, RecyclerView.State state) {
        Geometry g = geometry(activity);
        if (g == null
            || !g.vertical
            || !(g.separating || g.occluding)
            || !(parent.getLayoutManager() instanceof GridLayoutManager)) return;
        int spans = ((GridLayoutManager) parent.getLayoutManager()).getSpanCount();
        if (spans < 2 || spans % 2 != 0) return;
        int position = parent.getChildAdapterPosition(child);
        if (position < 0) return;
        int[] location = new int[2];
        parent.getLocationInWindow(location);
        int hingeLeft = g.bounds.left - location[0], hingeRight = g.bounds.right - location[0];
        int start = parent.getPaddingLeft(), end = parent.getWidth() - parent.getPaddingRight();
        if (hingeLeft <= start || hingeRight >= end) return;
        int column = position % spans;
        if (parent.getLayoutDirection() == View.LAYOUT_DIRECTION_RTL) column = spans - 1 - column;
        int half = spans / 2,
            gap = Math.round(8 * parent.getResources().getDisplayMetrics().density);
        int paneStart = column < half ? start : hingeRight + gap,
            paneEnd = column < half ? hingeLeft - gap : end,
            within = column % half;
        int desiredStart = paneStart + (paneEnd - paneStart) * within / half,
            desiredEnd = paneStart + (paneEnd - paneStart) * (within + 1) / half;
        int baseStart = start + (end - start) * column / spans,
            baseEnd = start + (end - start) * (column + 1) / spans;
        out.left = desiredStart - baseStart;
        out.right = baseEnd - desiredEnd;
      }
    };
  }

  private int dp(int value) {
    return Math.round(value * activity.getResources().getDisplayMetrics().density);
  }

  @Override
  public void close() {
    closed = true;
    tracker.removeWindowLayoutInfoListener(listener);
    if (observed != null) observed.removeOnLayoutChangeListener(layout);
    observed = null;
    geometries.remove(activity);
  }
}
