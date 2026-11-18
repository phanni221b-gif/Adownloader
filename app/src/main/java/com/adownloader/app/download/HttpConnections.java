package com.adownloader.app.download;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * Chooses the network stack for media requests.
 *
 * <p>The browser transport sends requests through Gecko itself: the same cookie store (including
 * HttpOnly and cookies refreshed while the page played), TLS stack and HTTP/2 as playback. It does
 * not need a visible or active tab, so downloads keep their access while the app is in the
 * background or after the process restarts. The direct transport (HttpURLConnection with the
 * captured request context) remains the fallback.
 */
public final class HttpConnections {
  public interface Opener {
    HttpURLConnection open(URL url) throws IOException;
  }

  private static volatile Opener browser;
  // The browser engine's own User-Agent, published by the browser layer. Used as a fallback for
  // direct requests whose captured context carries no User-Agent (e.g. media found in the DOM
  // rather than from an observed network request), so the request still looks like the player's.
  private static volatile String userAgent = "";

  private HttpConnections() {}

  public static void installBrowser(Opener opener) {
    browser = opener;
  }

  public static boolean browserAvailable() {
    return browser != null;
  }

  public static void setUserAgent(String value) {
    if (value != null && !value.isBlank() && value.length() <= 512 && !value.matches(".*[\\r\\n].*"))
      userAgent = value;
  }

  public static String userAgent() {
    return userAgent;
  }

  public static HttpURLConnection open(String url, boolean viaBrowser) throws IOException {
    Opener opener = browser;
    if (viaBrowser && opener != null) return opener.open(new URL(url));
    return (HttpURLConnection) new URL(url).openConnection();
  }
}
