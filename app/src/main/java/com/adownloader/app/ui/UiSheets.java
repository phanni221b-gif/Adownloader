package com.adownloader.app.ui;

import android.app.Activity;
import android.view.*;
import android.widget.TextView;
import com.adownloader.app.R;
import com.google.android.material.bottomsheet.*;
import com.google.android.material.button.MaterialButton;

/** Shared, inset-aware sheets. Width follows the window, capped on expanded Fold screens. */
public final class UiSheets {
  private UiSheets() {}

  private static final java.util.Map<
          Activity, java.util.List<java.lang.ref.WeakReference<BottomSheetDialog>>>
      openSheets = new java.util.WeakHashMap<>();

  public static void close(Activity activity) {
    var sheets = openSheets.remove(activity);
    if (sheets != null)
      for (var reference : sheets) {
        var dialog = reference.get();
        if (dialog != null && dialog.isShowing()) dialog.dismiss();
      }
  }

  public static BottomSheetDialog show(Activity activity, View content, boolean tall) {
    BottomSheetDialog dialog = new BottomSheetDialog(activity);
    var sheets = openSheets.computeIfAbsent(activity, key -> new java.util.ArrayList<>());
    sheets.removeIf(reference -> reference.get() == null || !reference.get().isShowing());
    sheets.add(new java.lang.ref.WeakReference<>(dialog));
    int left = content.getPaddingLeft(),
        top = content.getPaddingTop(),
        right = content.getPaddingRight(),
        bottom = content.getPaddingBottom();
    content.setOnApplyWindowInsetsListener(
        (v, insets) -> {
          android.graphics.Insets safe =
              insets.getInsets(
                  WindowInsets.Type.navigationBars()
                      | WindowInsets.Type.displayCutout()
                      | WindowInsets.Type.ime());
          v.setPadding(left + safe.left, top, right + safe.right, bottom + safe.bottom);
          return insets;
        });
    dialog.setContentView(content);
    String[] previousBounds = {""};
    Runnable resize =
        () -> {
          android.graphics.Rect bounds =
              activity.getWindowManager().getCurrentWindowMetrics().getBounds();
          View sheet = dialog.findViewById(com.google.android.material.R.id.design_bottom_sheet);
          if (sheet == null) return;
          android.graphics.Rect visible = new android.graphics.Rect();
          sheet.getWindowVisibleDisplayFrame(visible);
          int height =
              Math.min(bounds.height(), visible.bottom > 0 ? visible.bottom : bounds.height());
          android.graphics.Rect pane =
              FoldLayoutController.sheetPane(activity, bounds.width(), height);
          String key =
              pane.toString() + ":" + activity.getResources().getDisplayMetrics().densityDpi;
          BottomSheetBehavior<?> behavior = dialog.getBehavior();
          if (!key.equals(previousBounds[0])) {
            previousBounds[0] = key;
            behavior.setMaxWidth(Math.min(pane.width(), dp(activity, 640)));
            behavior.setMaxHeight((int) (pane.height() * 0.88f));
            if (tall) content.getLayoutParams().height = ViewGroup.LayoutParams.WRAP_CONTENT;
            content.requestLayout();
          }
          int[] location = new int[2];
          sheet.getLocationInWindow(location);
          if (FoldLayoutController.geometry(activity) != null) {
            sheet.setTranslationX(
                pane.exactCenterX()
                    - sheet.getWidth() / 2f
                    - (location[0] - sheet.getTranslationX()));
            sheet.setTranslationY(
                pane.bottom - sheet.getHeight() - (location[1] - sheet.getTranslationY()));
          } else {
            sheet.setTranslationX(0);
            sheet.setTranslationY(0);
          }
        };
    AutoCloseable watch = FoldLayoutController.watch(activity, resize);
    content.addOnAttachStateChangeListener(
        new View.OnAttachStateChangeListener() {
          public void onViewAttachedToWindow(View v) {}

          public void onViewDetachedFromWindow(View v) {
            try {
              watch.close();
            } catch (Exception ignored) {
            }
          }
        });
    content.addOnLayoutChangeListener((v, l, t, r, b, oldL, oldT, oldR, oldB) -> resize.run());
    dialog.setOnShowListener(
        d -> {
          resize.run();
          dialog.getBehavior().setState(BottomSheetBehavior.STATE_EXPANDED);
          dialog.getBehavior().setSkipCollapsed(true);
          content.requestApplyInsets();
        });
    dialog.show();
    return dialog;
  }

  public static BottomSheetDialog action(
      Activity activity,
      int icon,
      String title,
      String message,
      String cancel,
      String confirm,
      Runnable accepted,
      String extra,
      Runnable extraAction) {
    View content = activity.getLayoutInflater().inflate(R.layout.sheet_action, null);
    ((android.widget.ImageView) content.findViewById(R.id.action_icon)).setImageResource(icon);
    ((TextView) content.findViewById(R.id.action_title)).setText(title);
    ((TextView) content.findViewById(R.id.action_message)).setText(message);
    BottomSheetDialog dialog = show(activity, content, false);
    MaterialButton no = content.findViewById(R.id.action_cancel),
        yes = content.findViewById(R.id.action_confirm),
        other = content.findViewById(R.id.action_extra);
    no.setText(cancel);
    no.setOnClickListener(v -> dialog.dismiss());
    if (confirm == null) yes.setVisibility(View.GONE);
    else {
      yes.setText(confirm);
      yes.setOnClickListener(
          v -> {
            dialog.dismiss();
            if (accepted != null) accepted.run();
          });
    }
    if (extra != null) {
      other.setVisibility(View.VISIBLE);
      other.setText(extra);
      other.setOnClickListener(
          v -> {
            dialog.dismiss();
            if (extraAction != null) extraAction.run();
          });
    }
    return dialog;
  }

  public static int dp(android.content.Context context, int value) {
    return Math.round(value * context.getResources().getDisplayMetrics().density);
  }
}
