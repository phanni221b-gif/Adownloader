package com.adownloader.app.ui;

import android.app.Activity;
import android.net.Uri;
import android.view.*;
import android.widget.TextView;
import androidx.recyclerview.widget.*;
import com.adownloader.app.*;
import com.adownloader.app.download.*;
import com.google.android.material.bottomsheet.BottomSheetDialog;
import java.util.*;
import java.util.concurrent.*;
import org.w3c.dom.*;

/** Read-only metadata preview. Download selection remains exclusively in the frozen engine. */
public final class MediaSheetController {
  public interface Downloader {
    void start(MediaCandidate candidate);
  }

  private final Activity activity;
  private final Downloader downloader;
  private final ExecutorService io = Executors.newFixedThreadPool(2);
  private final Map<String, String> information = new ConcurrentHashMap<>();
  private final Map<String, String> informationVersions = new ConcurrentHashMap<>();
  private BottomSheetDialog dialog;
  private volatile boolean closed;
  private MediaAdapter activeAdapter;
  private View activeContent;

  public MediaSheetController(Activity activity, Downloader downloader) {
    this.activity = activity;
    this.downloader = downloader;
  }

  /** Downloadable candidates first, protected (undownloadable) last; stable otherwise. */
  private static List<MediaCandidate> ordered(List<MediaCandidate> candidates) {
    List<MediaCandidate> out = new ArrayList<>(candidates);
    out.sort(
        (a, b) ->
            Boolean.compare(
                a.getType() == MediaCandidate.MediaType.PROTECTED,
                b.getType() == MediaCandidate.MediaType.PROTECTED));
    return out;
  }

  private static boolean supported(MediaCandidate item) {
    return item.getType() != MediaCandidate.MediaType.PROTECTED;
  }

  /** The concrete, verified reason a transport is not downloadable — not a generic label. */
  static String protectedReason(MediaCandidate item) {
    String token = item.getMetadata().getOrDefault("protectionToken", "");
    boolean bodyVerified = "body".equals(item.getMetadata().get("protectionVerified"));
    if (bodyVerified)
      return "응답 본문이 실제로 암호화돼 있습니다"
          + (token.isEmpty() ? "" : " (" + token + ")")
          + ". 재생은 플레이어만 복호화하며, 암호화 해제·보호 우회는 지원하지 않습니다.";
    return "서버가 암호화 전송이라고 표시했지만 응답 본문은 확인하지 못했습니다"
        + (token.isEmpty() ? "" : " (" + token + ")")
        + ". 지원되는 인가 재생 세션이 아니며, 암호화 해제·보호 우회는 지원하지 않습니다.";
  }

  public static String type(MediaCandidate item) {
    if (item.getType() == MediaCandidate.MediaType.PROTECTED)
      return "body".equals(item.getMetadata().get("protectionVerified")) ? "암호화 전송" : "보호 방식 미확인";
    if (item.getType() == MediaCandidate.MediaType.RELAY_HLS) return "세션 HLS";
    if (item.getType() == MediaCandidate.MediaType.HLS) return "HLS";
    if (item.getType() == MediaCandidate.MediaType.DASH) return "DASH";
    String path = Uri.parse(item.getUrl()).getPath();
    if (path != null) {
      String low = path.toLowerCase(Locale.ROOT);
      if (low.endsWith(".mp4")) return "MP4";
      if (low.endsWith(".webm")) return "WEBM";
    }
    return item.getType() == MediaCandidate.MediaType.AUDIO ? "오디오" : "동영상";
  }

  public static String name(MediaCandidate item, int index) {
    for (String key : List.of("title", "mediaTitle")) {
      String title = DownloadFileName.sanitize(item.getMetadata().get(key));
      if (!title.isEmpty()) return title;
    }
    String label = DownloadFileName.label(Collections.emptyMap(), item.getUrl());
    return label.equals("동영상 다운로드") ? "영상 " + String.format(Locale.ROOT, "%02d", index + 1) : label;
  }

  public void update(List<MediaCandidate> candidates) {
    if (closed || dialog == null || !dialog.isShowing() || activeAdapter == null) return;
    List<MediaCandidate> sorted = ordered(candidates);
    activeAdapter.items.clear();
    activeAdapter.items.addAll(sorted);
    activeAdapter.notifyDataSetChanged();
    if (activeContent != null) {
      ((TextView) activeContent.findViewById(R.id.sheet_title))
          .setText("감지된 미디어" + (candidates.isEmpty() ? "" : " " + candidates.size()));
      activeContent
          .findViewById(R.id.sheet_empty)
          .setVisibility(candidates.isEmpty() ? View.VISIBLE : View.GONE);
    }
    loadPreviews(sorted, activeAdapter);
    subtitle(sorted);
  }

  public void show(List<MediaCandidate> input) {
    List<MediaCandidate> candidates = ordered(input);
    if (dialog != null) dialog.dismiss();
    View content = activity.getLayoutInflater().inflate(R.layout.sheet_list, null);
    ((TextView) content.findViewById(R.id.sheet_title))
        .setText("감지된 미디어" + (candidates.isEmpty() ? "" : " " + candidates.size()));
    RecyclerView list = content.findViewById(R.id.sheet_list);
    list.setLayoutManager(new LinearLayoutManager(activity));
    MediaAdapter adapter = new MediaAdapter(candidates);
    activeAdapter = adapter;
    activeContent = content;
    list.setAdapter(adapter);
    if (candidates.isEmpty()) {
      View empty = content.findViewById(R.id.sheet_empty);
      empty.setVisibility(View.VISIBLE);
      ((TextView) empty.findViewById(R.id.empty_title)).setText("아직 감지된 미디어가 없습니다.");
      ((TextView) empty.findViewById(R.id.empty_message)).setText("페이지에서 영상을 재생하면 자동으로 감지합니다.");
      ((android.widget.ImageView) empty.findViewById(R.id.empty_icon))
          .setImageResource(R.drawable.ic_media);
    }
    dialog = UiSheets.show(activity, content, true);
    content.findViewById(R.id.sheet_close).setOnClickListener(v -> dialog.dismiss());
    loadPreviews(candidates, adapter);
    subtitle(candidates);
  }

  private void subtitle(List<MediaCandidate> candidates) {
    if (activeContent == null) return;
    long downloadable = candidates.stream().filter(MediaSheetController::supported).count();
    long protectedCount = candidates.size() - downloadable;
    String text;
    if (candidates.isEmpty()) text = "최고 원본 자동 선택";
    else if (downloadable == 0) {
      boolean anyVerified =
          candidates.stream().anyMatch(c -> "body".equals(c.getMetadata().get("protectionVerified")));
      text = (anyVerified ? "암호화 전송만 감지됨" : "보호 방식 미확인 · 받을 수 있는 원본 없음") + " · 다운로드 미지원";
    }
    else if (protectedCount == 0) text = "최고 원본 자동 선택";
    else text = "다운로드 가능 " + downloadable + "개 · 암호화 전송 " + protectedCount + "개";
    ((TextView) activeContent.findViewById(R.id.sheet_subtitle)).setText(text);
  }

  private void loadPreviews(List<MediaCandidate> candidates, MediaAdapter adapter) {
    for (MediaCandidate item : candidates.subList(0, Math.min(6, candidates.size()))) {
      if (item.getType() == MediaCandidate.MediaType.PROTECTED) {
        information.put(item.getUrl(), protectedReason(item));
        continue;
      }
      String version =
          com.adownloader.app.media.MediaIdentity.hash(
              item.getPageUrl()
                  + "|"
                  + item.getMetadata().getOrDefault("browserTabId", "")
                  + ":"
                  + item.getMetadata().getOrDefault("frameId", "")
                  + "|"
                  + item.getMetadata().getOrDefault("mediaObservedAt", "")
                  + "|"
                  + new TreeMap<>(item.getHeaders()));
      if (version.equals(informationVersions.put(item.getUrl(), version))) continue;
      information.put(
          item.getUrl(),
          MediaNetworkSession.latest(item.getUrl(), item.getPageUrl()) != null
              ? "영상 확인 중 · 브라우저 인증 사용 가능"
              : "정보 분석 중");
      io.execute(
          () -> {
            String info = preview(item);
            if (closed || !version.equals(informationVersions.get(item.getUrl()))) return;
            information.put(item.getUrl(), info);
            activity.runOnUiThread(
                () -> {
                  if (!closed) adapter.notifyDataSetChanged();
                });
          });
    }
  }

  private String preview(MediaCandidate item) {
    if (item.getType() != MediaCandidate.MediaType.HLS
        && item.getType() != MediaCandidate.MediaType.RELAY_HLS
        && item.getType() != MediaCandidate.MediaType.DASH) return "원본 파일 그대로 저장";
    try {
      return MediaProbeService.shared().preview(item).description;
    } catch (Exception unavailable) {
      if (unavailable instanceof DownloadError error
          && (error.category == DownloadError.Category.AUTH
              || error.category == DownloadError.Category.ACCESS_BLOCKED
              || error.category == DownloadError.Category.EXPIRED_URL))
        return AccessPresentation.preview(error);
      return "세부 정보 확인 불가 · 원본 자동 선택";
    }
  }

  private String attribute(Element rep, Element parent, String name) {
    String value = rep.getAttribute(name);
    return value.isEmpty() ? parent.getAttribute(name) : value;
  }

  private long number(String value) {
    try {
      return Long.parseLong(value);
    } catch (Exception ignored) {
      return 0;
    }
  }

  private double frameRate(String value) {
    try {
      String[] parts = value.split("/");
      return Double.parseDouble(parts[0]) / (parts.length == 2 ? Double.parseDouble(parts[1]) : 1);
    } catch (Exception ignored) {
      return 0;
    }
  }

  private void confirm(MediaCandidate item, int index) {
    if (item.getType() == MediaCandidate.MediaType.PROTECTED) return;
    if (dialog != null) dialog.dismiss();
    String info = information.getOrDefault(item.getUrl(), "");
    if (info.equals("정보 분석 중")) info = "";
    UiSheets.action(
        activity,
        R.drawable.ic_download,
        name(item, index),
        "최고 원본 화질 · "
            + type(item)
            + (info.isEmpty() ? "" : "\n" + info)
            + "\n"
            + Uri.parse(item.getUrl()).getHost(),
        "취소",
        "다운로드",
        () -> downloader.start(item),
        null,
        null);
  }

  private final class MediaAdapter extends RecyclerView.Adapter<MediaHolder> {
    private final List<MediaCandidate> items;
    private final Set<String> expanded = new HashSet<>();

    MediaAdapter(List<MediaCandidate> items) {
      this.items = new ArrayList<>(items);
    }

    @Override
    public int getItemCount() {
      return items.size();
    }

    @Override
    public MediaHolder onCreateViewHolder(ViewGroup parent, int type) {
      return new MediaHolder(
          activity.getLayoutInflater().inflate(R.layout.item_media, parent, false));
    }

    @Override
    public void onBindViewHolder(MediaHolder holder, int position) {
      MediaCandidate item = items.get(position);
      holder.title.setText(name(item, position));
      holder.type.setText(type(item));
      holder.host.setText(Uri.parse(item.getUrl()).getHost());
      holder.info.setText(information.getOrDefault(item.getUrl(), "최고 원본 자동 선택"));
      holder.url.setText(item.getUrl());
      holder.url.setVisibility(expanded.contains(item.getUrl()) ? View.VISIBLE : View.GONE);
      holder.details.setOnClickListener(
          v -> {
            if (!expanded.add(item.getUrl())) expanded.remove(item.getUrl());
            notifyItemChanged(position);
          });
      boolean supported = item.getType() != MediaCandidate.MediaType.PROTECTED;
      holder.download.setEnabled(supported);
      ((TextView) holder.download).setText(supported ? "다운로드" : "미지원");
      holder.download.setOnClickListener(supported ? v -> confirm(item, position) : null);
    }
  }

  private static final class MediaHolder extends RecyclerView.ViewHolder {
    final TextView title, type, host, info, url;
    final View details, download;

    MediaHolder(View view) {
      super(view);
      title = view.findViewById(R.id.media_title);
      type = view.findViewById(R.id.media_type);
      host = view.findViewById(R.id.media_host);
      info = view.findViewById(R.id.media_metadata);
      url = view.findViewById(R.id.media_url);
      details = view.findViewById(R.id.media_details);
      download = view.findViewById(R.id.media_download);
    }
  }

  public void close() {
    closed = true;
    if (dialog != null) dialog.dismiss();
    dialog = null;
    activeAdapter = null;
    activeContent = null;
    io.shutdownNow();
  }
}
