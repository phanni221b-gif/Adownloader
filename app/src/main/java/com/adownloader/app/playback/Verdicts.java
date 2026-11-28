package com.adownloader.app.playback;

import java.util.*;

/**
 * Conclusions drawn only from what the session measured. Each carries a code, a level (ok / info
 * / warning / problem), a Korean explanation and the evidence fields it rests on; "inferred" marks
 * a conclusion that names a likely cause rather than an observed fact.
 */
final class Verdicts {
  private Verdicts() {}

  static List<Object> derive(PlaybackDiagnostics.Session s) {
    List<Object> out = new ArrayList<>();
    List<PlaybackDiagnostics.Element> elements = s.elementList();
    double best = 0;
    boolean errors = false, eme = false;
    Set<String> paths = new TreeSet<>();
    for (PlaybackDiagnostics.Element e : elements) {
      best = Math.max(best, PlaybackDiagnostics.Session.progress(e));
      if (!e.errorCodes.isEmpty()) errors = true;
      if (e.mediaKeys || !e.initDataTypes.isEmpty() || e.events.containsKey("encrypted")) eme = true;
      paths.add(PlaybackDiagnostics.Session.path(e));
    }
    paths.remove("none");

    // ---- playback ----
    if (elements.isEmpty())
      add(out, "NO_MEDIA_ELEMENT", "warning", "진단 중 페이지에서 video/audio 요소를 찾지 못했습니다. 진단을 켠 뒤 영상을 재생해 주세요.", false, "playback.elementCount");
    else if (best >= 2)
      add(out, "PLAYBACK_PROGRESSING", "ok", "브라우저 안에서 영상이 실제로 재생되었습니다 (재생 진행 " + Math.round(best) + "초).", false, "playback.elements[].progressSeconds");
    else
      add(out, "PLAYBACK_NOT_PROGRESSING", "warning", "미디어 요소는 있으나 재생 시간이 진행되지 않았습니다.", false, "playback.elements[].progressSeconds");
    for (PlaybackDiagnostics.Element e : elements)
      for (int code : e.errorCodes) {
        String text =
            switch (code) {
              case 2 -> "플레이어 자체도 네트워크 오류(MEDIA_ERR_NETWORK)를 보고했습니다.";
              case 3 -> "디코더 오류(MEDIA_ERR_DECODE): 받은 데이터를 해독·디코딩하지 못했습니다.";
              case 4 -> "미디어 형식 오류(MEDIA_ERR_SRC_NOT_SUPPORTED): 이 형식/코덱을 재생할 수 없습니다.";
              default -> "재생이 중단되었습니다(MEDIA_ERR_ABORTED).";
            };
        add(out, "PLAYER_ERROR_" + code, code == 1 ? "info" : "problem", text, false, "playback.elements[].errorCodes");
      }
    if (paths.contains("native-hls"))
      add(out, "PATH_NATIVE_HLS", "info", "페이지가 m3u8 주소를 video 요소에 직접 넣어 GeckoView 내장 HLS 플레이어로 재생합니다. 이 경로의 세그먼트 요청은 확장 프로그램 관측 범위 밖일 수 있습니다.", false, "playback.elements[].path");
    if (paths.contains("mse"))
      add(out, "PATH_MSE", "info", "페이지 스크립트가 MediaSource(blob:)로 재생합니다. 세그먼트는 페이지의 XHR/fetch로 받습니다.", false, "playback.elements[].path");
    if (eme)
      add(out, "EME_IN_USE", "problem", "재생에 EME(MediaKeys/encrypted 이벤트)가 사용됩니다. DRM 보호 콘텐츠는 다운로드할 수 없으며, 앱은 이를 해제하지 않습니다.", false, "playback.elements[].emeMediaKeys");

    // ---- player network ----
    long player = 0;
    for (Map<String, Object> r : s.netRecords()) if (s.playerRequest(r)) player++;
    if (best >= 2 && player == 0)
      add(out, "PLAYER_REQUESTS_NOT_VISIBLE", "warning", "영상은 재생되었지만 플레이어의 미디어 요청이 확장 프로그램에 하나도 보이지 않았습니다. 내장 HLS 플레이어나 서비스 워커처럼 webRequest 관측 범위 밖 경로로 받는 것으로 보입니다.", true, "playerNetwork.playerTypeRequests");

    // ---- download side ----
    PlaybackProbe probe = s.probe;
    if (s.candidates.isEmpty())
      add(out, "NO_CANDIDATE", "warning", "다운로드 후보가 탐지되지 않았습니다.", false, "detection.candidateCount");
    if (probe != null) {
      HlsInspection pl = probe.media != null ? probe.media : probe.root;
      if (pl != null)
        switch (pl.protection) {
          case DRM -> add(out, "MANIFEST_DRM", "problem", "재생목록이 DRM 키 시스템(" + String.join(", ", pl.drmSystems) + ")을 선언합니다. 보호된 콘텐츠이므로 세그먼트 검증과 다운로드를 하지 않습니다.", false, "download.*Playlist.drmSystems");
          case AES_128 -> add(out, "MANIFEST_AES128", "info", "표준 AES-128 암호화(EXT-X-KEY METHOD=AES-128, 키는 HTTP로 제공)입니다. DRM이 아니며 플레이어와 같은 방식으로 키를 받아 복호화합니다.", false, "download.mediaPlaylist.keyMethods");
          case SAMPLE_AES -> add(out, "MANIFEST_SAMPLE_AES", "warning", "SAMPLE-AES 암호화가 선언되어 있습니다. 일반 다운로드와 호환되지 않을 수 있습니다.", false, "download.mediaPlaylist.keyMethods");
          case CLEAR -> add(out, "MANIFEST_CLEAR", "info", "재생목록에 암호화 태그가 없습니다(평문 세그먼트).", false, "download.mediaPlaylist.keyMethods");
          default -> {}
        }
      PlaybackProbe.Step failed = null, segOk = null;
      for (PlaybackProbe.Step st : probe.steps) {
        if (failed == null && !st.status.equals("ok") && !st.status.equals("skipped")) failed = st;
        if (st.stage.equals("VIDEO_SEGMENT") && st.status.equals("ok") && segOk == null) segOk = st;
      }
      if (failed != null) {
        String where = stageName(failed.stage);
        String text =
            switch (failed.group) {
              case "challenge" -> where + " 요청에 서버가 HTML 보안 확인(챌린지) 페이지로 응답했습니다 (HTTP " + failed.httpStatus + ").";
              case "access" -> where + " 요청이 접근 권한 오류로 거부되었습니다 (HTTP " + failed.httpStatus + ", " + failed.kind + ").";
              case "http" -> where + " 요청이 일반 HTTP 오류로 실패했습니다 (HTTP " + failed.httpStatus + ", " + failed.kind + ").";
              case "format" -> where + " 응답이 미디어 형식이 아닙니다 (" + failed.kind + ", 본문 " + failed.signature + ").";
              case "protected" -> where + " 단계에서 DRM 보호가 확인되었습니다.";
              default -> where + " 요청이 연결 단계에서 실패했습니다 (" + failed.kind + ").";
            };
        add(out, "DOWNLOAD_" + failed.stage + "_" + failed.kind, "problem", text, false, "download.steps");
        if (failed.group.equals("challenge") || failed.group.equals("access") || failed.kind.equals("RATE_LIMITED"))
          add(out, "REFUSAL_RESPECTED", "info", "서버 거부를 확인한 즉시 진단 요청을 멈췄습니다. 같은 호스트로 추가 요청·재시도를 보내지 않았습니다.", false, "download.stoppedReason");
      } else if (segOk != null)
        add(out, "DOWNLOAD_SEGMENT_OK", "ok", "다운로드 요청 경로로도 세그먼트를 정상 수신했습니다 (본문 " + segOk.signature + ").", false, "download.steps");

      // ---- the difference ----
      Map<String, Object> cmp = s.comparison();
      long matched = ((Number) cmp.get("playerRequestsMatchingManifest")).longValue();
      long matchedOk = ((Number) cmp.get("matchingOk")).longValue();
      long segmentLike = ((Number) cmp.get("playerSegmentLikeRequests")).longValue();
      boolean blocked = failed != null && failed.stage.equals("VIDEO_SEGMENT");
      if (!probe.segmentKeys.isEmpty() && matched > 0 && matchedOk > 0 && blocked)
        add(out, "SAME_STREAM_CONTEXT_DIFFERS", "problem", "플레이어는 이 재생목록의 같은 세그먼트를 정상으로 받았지만 다운로드 요청은 거부되었습니다. 차이는 요청 맥락(쿠키 " + yes(cmp.get("playerSentCookieForManifestSegments")) + ", Referer " + yes(cmp.get("playerSentRefererForManifestSegments")) + " 전송)에 있습니다.", false, "comparison");
      else if (!probe.segmentKeys.isEmpty() && matched > 0)
        add(out, "SAME_STREAM", "info", "플레이어가 다운로드 대상 재생목록과 같은 세그먼트를 요청했습니다.", false, "comparison.playerRequestsMatchingManifest");
      else if (!probe.segmentKeys.isEmpty() && segmentLike > 0)
        add(out, "DIFFERENT_STREAM", "problem", "플레이어가 받은 세그먼트 중 다운로드 대상 재생목록의 세그먼트가 없습니다. 탐지된 후보가 실제 재생 중인 스트림이 아닙니다.", false, "comparison");
      else if (!probe.segmentKeys.isEmpty() && best >= 2)
        add(out, "STREAM_MATCH_UNKNOWN", "warning", "플레이어의 세그먼트 요청이 관측되지 않아 다운로드 대상이 재생 중인 스트림과 같은지 확인할 수 없습니다.", false, "comparison");
    } else
      add(out, "DOWNLOAD_NOT_PROBED", "info", "다운로드 경로 검증을 실행하지 않았습니다 (HLS 후보 없음).", false, "download.probed");
    return out;
  }

  private static String stageName(String stage) {
    return switch (stage) {
      case "ROOT_MANIFEST" -> "매니페스트";
      case "VARIANT_PLAYLIST" -> "미디어 재생목록";
      case "VIDEO_SEGMENT" -> "세그먼트";
      case "AES_KEY" -> "AES 키";
      default -> stage;
    };
  }

  private static String yes(Object v) {
    return Boolean.TRUE.equals(v) ? "있음" : "없음";
  }

  private static void add(List<Object> out, String code, String level, String text, boolean inferred, String evidence) {
    Map<String, Object> m = new LinkedHashMap<>();
    m.put("code", code);
    m.put("level", level);
    m.put("message", text);
    m.put("inferred", inferred);
    m.put("evidence", evidence);
    out.add(m);
  }
}
