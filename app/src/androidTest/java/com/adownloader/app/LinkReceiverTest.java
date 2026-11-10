package com.adownloader.app;

import static org.junit.Assert.*;

import android.content.Intent;
import android.net.Uri;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
public class LinkReceiverTest {
  @Test
  public void viewIntentsAcceptOnlyWebLinks() {
    assertEquals(
        "https://example.test/watch?v=1",
        LinkReceiverActivity.extract(
            new Intent(Intent.ACTION_VIEW, Uri.parse("https://example.test/watch?v=1"))));
    assertNull(
        LinkReceiverActivity.extract(new Intent(Intent.ACTION_VIEW, Uri.parse("file:///sdcard/a"))));
  }

  @Test
  public void sharedTextYieldsTheFirstLinkWithoutTrailingPunctuation() {
    Intent share =
        new Intent(Intent.ACTION_SEND)
            .setType("text/plain")
            .putExtra(Intent.EXTRA_TEXT, "이 영상 봐 (https://video.example.test/v/42?t=3). 재밌어");
    assertEquals("https://video.example.test/v/42?t=3", LinkReceiverActivity.extract(share));
    assertNull(
        LinkReceiverActivity.extract(
            new Intent(Intent.ACTION_SEND).putExtra(Intent.EXTRA_TEXT, "링크 없음")));
  }
}
