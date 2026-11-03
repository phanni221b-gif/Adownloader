package com.adownloader.app.ui;

import android.net.Uri;
import android.os.*;
import android.text.*;
import android.view.*;
import android.widget.*;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.PopupMenu;
import androidx.recyclerview.widget.*;
import com.adownloader.app.R;
import com.adownloader.app.browser.*;
import com.google.android.material.bottomsheet.BottomSheetDialog;
import com.google.android.material.snackbar.Snackbar;
import java.util.function.Supplier;
import org.json.*;
import org.mozilla.geckoview.*;

/** Small browser tools; Gecko owns searching, rendering and site storage. */
public final class BrowserToolsController implements AutoCloseable {
  private static final int DESKTOP = 6201,
      FIND = 6202,
      HISTORY = 6203,
      BOOKMARKS = 6204,
      SAVE = 6205,
      CLEAR_SITE = 6206,
      CLEAR_ALL = 6207,
      TABS = 6208,
      NEW_TAB = 6209;
  private final AppCompatActivity activity;
  private final BrowserSessionManager browser;
  private final BrowserDataStore data;
  private final Supplier<String> url;
  private BottomSheetDialog dialog, tabsDialog;
  private View tabsContent;
  private RecyclerView.Adapter<Holder> tabsAdapter;
  private final Handler handler = new Handler(Looper.getMainLooper());
  private boolean closed;

  public BrowserToolsController(
      AppCompatActivity activity,
      BrowserSessionManager browser,
      BrowserDataStore data,
      Supplier<String> url) {
    this.activity = activity;
    this.browser = browser;
    this.data = data;
    this.url = url;
  }

  public void addMenu(PopupMenu menu) {
    menu.getMenu()
        .add(0, DESKTOP, 10, "데스크톱 사이트")
        .setCheckable(true)
        .setChecked(browser.isDesktop());
    menu.getMenu().add(0, FIND, 11, "페이지 내 찾기");
    menu.getMenu().add(0, TABS, 8, "탭 · " + browser.tabs().size() + " / 5");
    menu.getMenu().add(0, NEW_TAB, 9, "새 탭");
    menu.getMenu().add(0, HISTORY, 12, "방문 기록");
    menu.getMenu().add(0, BOOKMARKS, 13, "북마크");
    menu.getMenu().add(0, SAVE, 14, "현재 페이지 북마크");
    menu.getMenu().add(0, CLEAR_SITE, 15, "현재 사이트 데이터 삭제");
    menu.getMenu().add(0, CLEAR_ALL, 16, "전체 브라우징 데이터 삭제");
  }

  public boolean handle(int id) {
    switch (id) {
      case TABS:
        showTabs();
        return true;
      case NEW_TAB:
        if (!browser.newTab(null)) message("탭은 최대 5개까지 열 수 있습니다.");
        return true;
      case DESKTOP:
        browser.setDesktop(!browser.isDesktop());
        return true;
      case FIND:
        find();
        return true;
      case HISTORY:
        records(false);
        return true;
      case BOOKMARKS:
        records(true);
        return true;
      case SAVE:
        if (SafeSessionState.safeUrl(url.get())) {
          java.lang.ref.WeakReference<BrowserToolsController> weak =
              new java.lang.ref.WeakReference<>(this);
          data.remember(
              url.get(),
              browser.getTitle(),
              true,
              success -> {
                var owner = weak.get();
                if (owner != null)
                  owner.message(success ? "북마크에 저장했습니다." : "북마크를 저장하지 못했습니다. 저장 공간을 확인해 주세요.");
              });
        }
        return true;
      case CLEAR_SITE:
        clear(false);
        return true;
      case CLEAR_ALL:
        clear(true);
        return true;
      default:
        return false;
    }
  }

  private void message(String text) {
    if (!closed)
      Snackbar.make(activity.findViewById(R.id.app_root), text, Snackbar.LENGTH_SHORT).show();
  }

  public void link(String target) {
    if (!SafeSessionState.safeUrl(target)) return;
    dialog =
        UiSheets.action(
            activity,
            R.drawable.ic_media,
            "링크 열기",
            Uri.parse(target).getHost(),
            "취소",
            "현재 탭에서 열기",
            () -> browser.load(target),
            "새 탭에서 열기",
            () -> {
              if (!browser.newTab(target)) message("탭은 최대 5개까지 열 수 있습니다.");
            });
  }

  private void records(boolean bookmarks) {
    data.load(
        saved -> {
          if (closed || activity.isDestroyed()) return;
          JSONArray entries = saved.optJSONArray(bookmarks ? "bookmarks" : "history");
          if (entries == null || entries.length() == 0) {
            message(bookmarks ? "북마크가 없습니다." : "방문 기록이 없습니다.");
            return;
          }
          View content = activity.getLayoutInflater().inflate(R.layout.sheet_list, null);
          ((TextView) content.findViewById(R.id.sheet_title)).setText(bookmarks ? "북마크" : "방문 기록");
          ((TextView) content.findViewById(R.id.sheet_subtitle)).setText("길게 누르면 기록을 삭제합니다.");
          RecyclerView list = content.findViewById(R.id.sheet_list);
          list.setLayoutManager(new LinearLayoutManager(activity));
          dialog = UiSheets.show(activity, content, true);
          content.findViewById(R.id.sheet_close).setOnClickListener(v -> dialog.dismiss());
          list.setAdapter(
              new RecyclerView.Adapter<Holder>() {
                public Holder onCreateViewHolder(ViewGroup parent, int type) {
                  return new Holder(
                      activity
                          .getLayoutInflater()
                          .inflate(android.R.layout.simple_list_item_2, parent, false));
                }

                public int getItemCount() {
                  return entries.length();
                }

                public void onBindViewHolder(Holder holder, int position) {
                  JSONObject item = entries.optJSONObject(position);
                  String target = item.optString("url");
                  ((TextView) holder.itemView.findViewById(android.R.id.text1))
                      .setText(item.optString("title", Uri.parse(target).getHost()));
                  ((TextView) holder.itemView.findViewById(android.R.id.text2))
                      .setText(Uri.parse(target).getHost());
                  holder.itemView.setOnClickListener(
                      v -> {
                        dialog.dismiss();
                        browser.load(target);
                      });
                  holder.itemView.setOnLongClickListener(
                      v -> {
                        data.remove(target, bookmarks);
                        entries.remove(holder.getBindingAdapterPosition());
                        notifyDataSetChanged();
                        return true;
                      });
                }
              });
        });
  }

  private static class Holder extends RecyclerView.ViewHolder {
    Holder(View view) {
      super(view);
    }
  }

  public void showTabs() {
    if (closed) return;
    if (dialog != null) dialog.dismiss();
    View content = activity.getLayoutInflater().inflate(R.layout.sheet_browser_tabs, null);
    tabsContent = content;
    RecyclerView list = content.findViewById(R.id.tabs_list);
    list.setLayoutManager(new LinearLayoutManager(activity));
    dialog = UiSheets.show(activity, content, true);
    tabsDialog = dialog;
    BottomSheetDialog owner = dialog;
    content.findViewById(R.id.tabs_dismiss).setOnClickListener(v -> owner.dismiss());
    content
        .findViewById(R.id.tab_new)
        .setOnClickListener(
            v -> {
              if (browser.newTab(null)) owner.dismiss();
              else message("탭은 최대 5개까지 열 수 있습니다.");
            });
    tabsAdapter =
        new RecyclerView.Adapter<Holder>() {
          public Holder onCreateViewHolder(ViewGroup parent, int type) {
            return new Holder(
                activity.getLayoutInflater().inflate(R.layout.item_browser_tab, parent, false));
          }

          public int getItemCount() {
            return browser.tabs().size();
          }

          public void onBindViewHolder(Holder holder, int index) {
            var tab = browser.tabs().get(index);
            boolean selected = tab.session == browser.getSession();
            String name = tab.title.isBlank() ? "새 탭" : tab.title;
            ((TextView) holder.itemView.findViewById(R.id.tab_title)).setText(name);
            ((TextView) holder.itemView.findViewById(R.id.tab_url))
                .setText(tab.url.equals("about:blank") ? "주소를 입력해 페이지를 여세요" : tab.url);
            ImageView thumbnail = holder.itemView.findViewById(R.id.tab_preview);
            if (tab.preview == null) thumbnail.setImageResource(R.drawable.ic_browser);
            else thumbnail.setImageBitmap(tab.preview);
            var card = (com.google.android.material.card.MaterialCardView) holder.itemView;
            card.setSelected(selected);
            card.setStrokeWidth(dp(selected ? 2 : 1));
            card.setStrokeColor(
                androidx.core.content.ContextCompat.getColor(
                    activity, selected ? R.color.brand : R.color.outline));
            card.setCardBackgroundColor(
                androidx.core.content.ContextCompat.getColor(
                    activity, selected ? R.color.brand_soft : R.color.surface));
            card.setContentDescription((selected ? "현재 탭 · " : "탭 · ") + name);
            card.setOnClickListener(
                v -> {
                  int current = browser.tabs().indexOf(tab);
                  if (current >= 0) {
                    owner.dismiss();
                    browser.selectTab(current);
                  }
                });
            View close = holder.itemView.findViewById(R.id.tab_close);
            close.setContentDescription(name + " 탭 닫기");
            close.setOnClickListener(
                v -> {
                  int current = browser.tabs().indexOf(tab);
                  if (current >= 0) browser.closeTab(current);
                });
          }
        };
    list.setAdapter(tabsAdapter);
    onTabsChanged();
    browser.capturePreview(this::onTabsChanged);
  }

  private int dp(int value) {
    return Math.round(value * activity.getResources().getDisplayMetrics().density);
  }

  public void onTabsChanged() {
    if (closed
        || tabsDialog == null
        || tabsDialog != dialog
        || !tabsDialog.isShowing()
        || tabsAdapter == null) return;
    ((TextView) tabsContent.findViewById(R.id.tabs_title)).setText("열린 탭 " + browser.tabs().size());
    tabsContent.findViewById(R.id.tab_new).setEnabled(browser.tabs().size() < 5);
    tabsAdapter.notifyDataSetChanged();
  }

  private void find() {
    GeckoSession session = browser.getSession();
    LinearLayout content = new LinearLayout(activity);
    content.setOrientation(LinearLayout.VERTICAL);
    int pad = (int) (20 * activity.getResources().getDisplayMetrics().density);
    content.setPadding(pad, pad, pad, pad);
    EditText input = new EditText(activity);
    input.setSingleLine(true);
    input.setHint("페이지 내 찾기");
    content.addView(input);
    TextView result = new TextView(activity);
    content.addView(result);
    LinearLayout actions = new LinearLayout(activity);
    content.addView(actions);
    for (boolean previous : new boolean[] {true, false}) {
      Button button = new Button(activity);
      button.setText(previous ? "이전" : "다음");
      actions.addView(button);
      button.setOnClickListener(v -> search(session, input.getText().toString(), previous, result));
    }
    dialog = UiSheets.show(activity, content, false);
    dialog.setOnDismissListener(
        d -> {
          handler.removeCallbacksAndMessages(null);
          session.getFinder().clear();
        });
    input.addTextChangedListener(
        new TextWatcher() {
          public void beforeTextChanged(CharSequence s, int start, int count, int after) {}

          public void onTextChanged(CharSequence s, int start, int before, int count) {
            handler.removeCallbacksAndMessages(null);
            handler.postDelayed(
                () -> search(session, input.getText().toString(), false, result), 180);
          }

          public void afterTextChanged(Editable value) {}
        });
  }

  private void search(GeckoSession session, String value, boolean previous, TextView result) {
    if (value.isEmpty()) {
      session.getFinder().clear();
      result.setText("");
      return;
    }
    session.getFinder().setDisplayFlags(GeckoSession.FINDER_DISPLAY_HIGHLIGHT_ALL);
    session
        .getFinder()
        .find(
            value, previous ? GeckoSession.FINDER_FIND_BACKWARDS : GeckoSession.FINDER_FIND_FORWARD)
        .accept(
            found -> {
              if (!closed && dialog != null && dialog.isShowing())
                result.setText(
                    found.found
                        ? found.current + " / " + (found.total < 0 ? "…" : found.total)
                        : "일치하는 내용이 없습니다.");
            },
            error -> {});
  }

  private void clear(boolean all) {
    String host = Uri.parse(url.get()).getHost();
    if (!all && host == null) return;
    dialog =
        UiSheets.action(
            activity,
            R.drawable.ic_block,
            all ? "전체 브라우징 데이터 삭제" : "현재 사이트 데이터 삭제",
            all
                ? "열린 탭을 닫고 쿠키·캐시·사이트 저장소·방문 기록을 지웁니다."
                : "열린 탭을 닫고 " + host + "의 쿠키·캐시·사이트 저장소를 지웁니다.",
            "취소",
            "삭제",
            () -> {
              // Gecko explicitly requires sessions to close before clearing persistent site
              // storage.
              browser.close();
              GeckoResult<Void> operation =
                  all
                      ? browser
                          .getRuntime()
                          .getStorageController()
                          .clearData(StorageController.ClearFlags.ALL)
                      : browser
                          .getRuntime()
                          .getStorageController()
                          .clearDataFromHost(
                              host,
                              StorageController.ClearFlags.SITE_DATA
                                  | StorageController.ClearFlags.AUTH_SESSIONS);
              operation.accept(
                  done -> {
                    if (all) data.clearHistory();
                    if (!activity.isDestroyed()) {
                      browser.recover(null);
                      message("브라우징 데이터를 삭제했습니다.");
                    }
                  },
                  error -> {
                    if (!activity.isDestroyed()) {
                      browser.recover(null);
                      message("데이터를 삭제하지 못했습니다. 다시 시도해 주세요.");
                    }
                  });
            },
            null,
            null);
  }

  @Override
  public void close() {
    closed = true;
    handler.removeCallbacksAndMessages(null);
    if (dialog != null) dialog.dismiss();
    dialog = null;
  }
}
