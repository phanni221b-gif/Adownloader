package com.adownloader.app.download;

/** Recovery wording follows the typed failure rather than the persisted recovery state. */
public final class AccessPresentation {
  private AccessPresentation() {}

  public static String label(String category) {
    return switch (category == null ? "" : category) {
      case "EXPIRED_URL" -> "주소 만료";
      case "ACCESS_BLOCKED" -> "사이트 요청 차단";
      default -> "접근 확인 필요";
    };
  }

  public static String action(String category) {
    return "EXPIRED_URL".equals(category) ? "원래 페이지 열기" : "페이지 열기";
  }

  public static String preview(String category) {
    return label(category) + " · 페이지에서 재생 상태를 확인해 주세요";
  }
}
