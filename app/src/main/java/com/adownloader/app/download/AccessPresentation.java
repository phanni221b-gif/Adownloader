package com.adownloader.app.download;

/** Recovery wording follows the typed failure rather than the persisted recovery state. */
public final class AccessPresentation {
  private AccessPresentation() {}

  public static String label(String category) {
    return switch (category == null ? "" : category) {
      case "EXPIRED_URL" -> "주소 만료";
      case "AUTH" -> "로그인 또는 세션 확인 필요";
      case "ACCESS_BLOCKED" -> "사이트 요청 차단";
      default -> "접근 확인 필요";
    };
  }

  public static String action(String category) {
    return "EXPIRED_URL".equals(category) ? "원래 페이지 열기" : "페이지 열기";
  }

  public static String preview(DownloadError error) {
    HlsDiagnostic d = error.diagnostic;
    if (d == null || d.status == 0) return preview(error.category.name());
    String stage =
        switch (d.stage) {
          case VARIANT_HTTP, VARIANT_BODY -> "화질 정보 요청";
          case CHILD_AUTH, SEGMENT_URI -> "영상 조각 요청";
          default -> "영상 정보 요청";
        };
    return stage
        + " · HTTP "
        + d.status
        + (d.redirects > 0 ? " · 서버 이동 후" : "")
        + " · "
        + label(error.category.name());
  }

  public static String preview(String category) {
    return label(category) + " · 페이지에서 재생 상태를 확인해 주세요";
  }
}
