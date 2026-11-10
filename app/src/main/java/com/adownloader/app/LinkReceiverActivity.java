package com.adownloader.app;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Receives links opened or shared from other apps and hands them to the single browser window
 * (MainActivity keeps its standard launch mode; this avoids a second Gecko window in another
 * app's task).
 */
public final class LinkReceiverActivity extends Activity {
  static final String ACTION_OPEN_URL = "com.adownloader.app.OPEN_URL";
  private static final Pattern URL = Pattern.compile("https?://[^\\s<>\"]+", Pattern.CASE_INSENSITIVE);

  @Override
  protected void onCreate(Bundle state) {
    super.onCreate(state);
    String url = extract(getIntent());
    if (url != null)
      startActivity(
          new Intent(this, MainActivity.class)
              .setAction(ACTION_OPEN_URL)
              .setData(Uri.parse(url))
              .addFlags(
                  Intent.FLAG_ACTIVITY_NEW_TASK
                      | Intent.FLAG_ACTIVITY_CLEAR_TOP
                      | Intent.FLAG_ACTIVITY_SINGLE_TOP));
    finish();
  }

  /** An http(s) link from a VIEW intent, or the first one inside shared text. */
  static String extract(Intent intent) {
    if (intent == null) return null;
    if (Intent.ACTION_VIEW.equals(intent.getAction()) && intent.getData() != null) {
      String scheme = intent.getData().getScheme();
      return "http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme)
          ? intent.getData().toString()
          : null;
    }
    if (Intent.ACTION_SEND.equals(intent.getAction())) {
      CharSequence text = intent.getCharSequenceExtra(Intent.EXTRA_TEXT);
      if (text == null) return null;
      Matcher match = URL.matcher(text);
      if (match.find()) return match.group().replaceAll("[).,;!?]+$", "");
    }
    return null;
  }
}
