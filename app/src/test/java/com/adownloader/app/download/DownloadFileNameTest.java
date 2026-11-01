package com.adownloader.app.download;

import static org.junit.Assert.*;

import java.nio.charset.StandardCharsets;
import org.junit.Test;

public class DownloadFileNameTest {
  @Test
  public void dispositionUtf8WinsAndFinalContainerIsInspected() {
    assertEquals(
        "원본 영상.webm",
        DownloadFileName.choose(
            "attachment; filename*=UTF-8''%EC%9B%90%EB%B3%B8%20%EC%98%81%EC%83%81.mp4",
            "다른 제목", "https://cdn.test/url.mp4", "metadata", ".webm", 0));
    assertEquals(
        "a+b.mp4",
        DownloadFileName.choose(
            "attachment; filename*=UTF-8''a+b.mp4", "", "https://cdn.test/x.mp4", "", ".mp4", 0));
  }

  @Test
  public void titleThenUrlThenMetadataThenFallback() {
    assertEquals(
        "페이지 제목.mp4",
        DownloadFileName.choose("", "페이지 제목", "https://cdn.test/file.mp4", "metadata", ".mp4", 0));
    assertEquals(
        "file.mp4",
        DownloadFileName.choose(
            "", "", "https://cdn.test/file.mp4?token=PRIVATE", "metadata", ".mp4", 0));
    assertEquals(
        "metadata.mp4",
        DownloadFileName.choose("", "", "https://cdn.test/play", "metadata", ".mp4", 0));
    assertTrue(
        DownloadFileName.choose("", "", "https://cdn.test/play", "", ".mp4", 0)
            .startsWith("Adownloader_"));
  }

  @Test
  public void traversalAndSignedUrlTitlesCannotLeakIntoNames() {
    assertEquals(
        "안전한 제목.mp4",
        DownloadFileName.choose(
            "attachment; filename=../../private.mp4",
            "안전한 제목",
            "https://cdn.test/a.mp4",
            "",
            ".mp4",
            0));
    assertEquals(
        "file.mp4",
        DownloadFileName.choose(
            "",
            "https://cdn.test/file.mp4?token=PRIVATE",
            "https://cdn.test/file.mp4?token=PRIVATE",
            "",
            ".mp4",
            0));
    assertEquals("abc", DownloadFileName.sanitize(" ..a/b:c*?\"<>|\\\n. "));
  }

  @Test
  public void unicodeLengthIsBoundedWithoutSplittingCharacters() {
    String filename =
        DownloadFileName.choose("", "😀영상".repeat(100), "https://cdn.test/play", "", ".mp4", 0);
    assertTrue(filename.getBytes(StandardCharsets.UTF_8).length <= 184);
    assertFalse(filename.contains("�"));
    assertFalse(filename.endsWith(" .mp4"));
  }
}
