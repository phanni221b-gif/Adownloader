package com.adownloader.app.download;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.Map;

/** Bounded manifest/segment requests, checked byte counts, explicit safe redirects. */
public final class HttpSource implements AutoCloseable {
  public interface CookieListener {
    void changed(String encryptedAdapterState) throws Exception;
  }

  private final JobCookieJar cookies;
  private final FailureDiagnostics.Session diagnosticSession = FailureDiagnostics.current();
  private String topPage;
  private int topTab = -1;
  private long topFrame = -1;

  public String browserScopeKey() {
    return topTab + ":" + topFrame;
  }

  public HttpSource browserScope(int tab, long frame) {
    topTab = tab;
    topFrame = frame;
    return this;
  }

  public HttpSource pageContext(String page) {
    try {
      URI uri = URI.create(page);
      if (uri.getHost() != null && java.util.Set.of("http", "https").contains(uri.getScheme())) {
        topPage = page;
        cookies.pageContext(page);
      }
    } catch (Exception ignored) {
    }
    return this;
  }

  public String pageScopeKey() {
    return topPage == null ? "" : topPage;
  }

  public String pageSiteKey() {
    return topPage == null ? "" : CookieSites.site(URI.create(topPage));
  }

  private CapturedRequestContexts captured =
      new CapturedRequestContexts(java.util.Collections.emptyMap());

  public HttpSource resourceContexts(Map<String, Map<String, String>> values) {
    captured.close();
    captured = new CapturedRequestContexts(values, true);
    return this;
  }

  private boolean browserBootstrapped;
  private boolean capturedOnly;

  public HttpSource capturedManifestsOnly() {
    capturedOnly = true;
    return this;
  }

  public String cookieState() throws IOException {
    return cookies.snapshot();
  }

  public String cookieStateFor(String finalUrl, Map<String, String> headers) throws IOException {
    return cookies.snapshotFor(finalUrl, headers);
  }

  private String playbackRequestType = "playback-response";
  private long manifestEvidenceExpiresAt = Long.MAX_VALUE;

  public long manifestEvidenceExpiresAt() {
    return manifestEvidenceExpiresAt;
  }

  private CookieListener cookieListener;

  public HttpSource onCookies(CookieListener listener) {
    cookieListener = listener;
    return this;
  }

  public void restoreCookies(String state) throws IOException {
    cookies.restore(state);
  }

  public boolean hasResponseCookies() {
    return cookies.hasResponseCookies() || browserBootstrapped;
  }

  @Override
  public void close() {
    if (relay != null) relay.close();
    cookies.close();
    captured.close();
    cookieListener = null;
  }

  public interface ContextListener {
    void changed(String url, Map<String, String> headers) throws Exception;
  }

  private ContextListener contextListener;

  public HttpSource onContext(ContextListener value) {
    contextListener = value;
    return this;
  }

  private final java.util.Set<String>
      contextRetried = java.util.Collections.synchronizedSet(new java.util.HashSet<>()),
      bootstrapped = java.util.Collections.synchronizedSet(new java.util.HashSet<>());
  private final Map<String, String> resourceStages = new java.util.concurrent.ConcurrentHashMap<>();
  private final Map<String, Map<String, String>> lastHeaders =
      new java.util.concurrent.ConcurrentHashMap<>();

  public void registerTracks(java.util.List<ManifestParser.Track> tracks) {
    for (var t : tracks)
      for (var p : t.parts) {
        resourceStages.put(
            p.url, p.initialization ? "INIT_SEGMENT" : t.audio ? "AUDIO_SEGMENT" : "VIDEO_SEGMENT");
        if (p.keyUrl != null) resourceStages.put(p.keyUrl, "AES_KEY");
      }
  }

  private String stage(String url) {
    if (!documentRole.isEmpty())
      return documentRole.equals("root")
          ? "ROOT_MANIFEST"
          : documentRole.equals("audio") ? "AUDIO_PLAYLIST" : "VARIANT_PLAYLIST";
    return resourceStages.getOrDefault(url, "DIRECT");
  }

  private static boolean access(Exception e) {
    return e instanceof DownloadError
        && (((DownloadError) e).category == DownloadError.Category.AUTH
            || ((DownloadError) e).category == DownloadError.Category.ACCESS_BLOCKED);
  }

  private void record(
      String url,
      int code,
      int hops,
      String mime,
      String body,
      String transport,
      String action,
      java.util.Collection<String> names) {
    var observation = MediaNetworkSession.latest(url, topPage, topTab, topFrame);
    try (var diagnosticScope = FailureDiagnostics.activate(diagnosticSession)) {
      MediaNetworkSession.trace(
          stage(url),
          url,
          transport,
          code,
          hops,
          mime,
          body,
          names,
          observation == null ? java.util.List.of() : observation.headerNames(),
          observation != null || action.equals("GECKO_RESPONSE_CACHE"),
          action,
          transport.equals("gecko-response-cache")
              ? playbackRequestType
              : transport.equals("gecko")
                      ? "browser-fetch"
                      : transport.equals("content-script") ? "content-script-fetch" : "native-http");
    }
  }

  private final Map<String, java.util.Collection<String>> lastSentNames =
      new java.util.concurrent.ConcurrentHashMap<>();

  private synchronized boolean refreshContext(String url) throws Exception {
    var latest = MediaNetworkSession.latest(url, topPage, topTab, topFrame);
    if (latest == null || contextRetried.size() >= 128 || !contextRetried.add(url)) return false;
    Map<String, String> values = MediaHeaders.select(latest.headers());
    if (values.equals(lastHeaders.get(url))) return false;
    adoptContext(url, values);
    persistContext(url, values);
    record(url, 0, 0, "", "UNKNOWN", "native", "NATIVE_CONTEXT_RETRY", values.keySet());
    return true;
  }

  private void persistContext(String url, Map<String, String> values) throws DownloadError {
    try {
      if (contextListener != null) contextListener.changed(url, values);
    } catch (Exception storage) {
      throw DownloadError.storage(storage);
    }
  }

  private void persistCookies() throws DownloadError {
    FailureDiagnostics.event(
        diagnosticSession, Map.of("action", "COOKIE_UPDATE", "cookieRefreshed", true));
    try {
      if (cookieListener != null) cookieListener.changed(cookies.snapshot());
    } catch (Exception storage) {
      throw DownloadError.storage(storage);
    }
  }

  private void adoptContext(String url, Map<String, String> values) throws Exception {
    captured.update(url, values);
    if (url.equals(rootUrl)) {
      context = new RequestContext(rootUrl, values);
      if (cookies.refreshSeed(url, values)) persistCookies();
    }
  }

  private volatile RequestContext context;
  private volatile boolean preferDirect;
  // Hosts whose direct requests were access-blocked (e.g. an anti-bot challenge on a segment CDN)
  // but succeeded through the browser stack, which carries the cleared browsing session. Later
  // requests to such a host go straight to the browser stack instead of paying a blocked direct
  // attempt first. preferDirect, in contrast, is a single per-transfer latch, so on its own it
  // would force every host onto the direct stack once any one request fell back to it.
  private final java.util.Set<String> browserPreferredHosts =
      java.util.concurrent.ConcurrentHashMap.newKeySet();
  // Hosts that answered with access-blocked on every transport (direct and browser). Once a host is
  // confirmed blocked, its remaining resources fail fast instead of each repeating the full set of
  // blocked attempts — a CDN that blocks the first segment this way blocks all 1000+ of them.
  private final java.util.Set<String> blockedHosts =
      java.util.concurrent.ConcurrentHashMap.newKeySet();
  private final java.util.Set<String> blockedHostRecorded =
      java.util.concurrent.ConcurrentHashMap.newKeySet();
  // Hosts whose segments are served through the live playing tab's content-script context (route
  // #1): native and the browser stack were both access-blocked, but the page-context fetch — where
  // the site's anti-bot challenge is already solved for the tab that is playing the media —
  // returned the bytes. Later whole-segment requests on such a host go straight to that bridge
  // instead of each paying two blocked network attempts first. A host here is never added to
  // blockedHosts, so the bridge stays available for its remaining segments.
  private final java.util.Set<String> contentScriptHosts =
      java.util.concurrent.ConcurrentHashMap.newKeySet();
  // Hosts for which the content-script bridge (route #1) could not even be attempted because the
  // live tab never recorded a successful media observation of the segment to replay. Recorded once
  // per host so the report shows the player's own fetch of this host was never captured — the
  // signal that the download target was not confirmed as the stream actually playing (or that the
  // observation had expired), as opposed to a transport that was tried and blocked.
  private final java.util.Set<String> contentScriptUnobservedRecorded =
      java.util.concurrent.ConcurrentHashMap.newKeySet();
  // The page-context bridge returns the whole body in memory (base64 over the extension channel),
  // so an oversized segment is refused rather than risk exhausting memory. Matches the manifest
  // bootstrap's own 8 MiB ceiling.
  private static final int CONTENT_SCRIPT_BODY_LIMIT = 8 * 1024 * 1024;
  private final String rootUrl;

  private static String hostOf(String url) {
    try {
      return URI.create(url).getHost() == null ? "" : URI.create(url).getHost();
    } catch (RuntimeException invalid) {
      return "";
    }
  }
  private volatile String responseDisposition = "";
  private boolean acceptedResource;
  private int lastRedirects;
  // Per worker thread: segments are fetched concurrently and each refresh must target its own URL.
  private final ThreadLocal<String> lastRequestUrl = new ThreadLocal<>();
  private String documentRole = "";

  public HttpSource previouslyAccepted(boolean accepted) {
    acceptedResource = accepted;
    return this;
  }

  public String disposition() {
    return responseDisposition;
  }

  private final TransferControl control;
  private RelayTransport relay;

  public HttpSource relay(boolean enabled) {
    if (enabled && relay == null) {
      relay = new RelayTransport(rootUrl, context.forUrl(rootUrl), control);
      relay.page(topPage);
    }
    return this;
  }

  public HttpSource(String source, Map<String, String> headers) {
    this(source, headers, new TransferControl());
  }

  public HttpSource(String source, Map<String, String> headers, TransferControl control) {
    rootUrl = source;
    cookies = new JobCookieJar(source, headers);
    context = new RequestContext(source, headers);
    this.control = control;
  }

  public void release(HttpURLConnection connection) {
    control.detach(connection);
    connection.disconnect();
  }

  public HttpURLConnection resume(String url, long offset, String validator) throws Exception {
    if (diagnosticSession != null) diagnosticSession.phase(FailureDiagnostics.Stage.RANGE_RESUME);
    return request(url, offset, -1, validator, true);
  }

  public HttpURLConnection open(String url, long offset, long length) throws Exception {
    return request(url, offset, length, null, false);
  }

  private HttpURLConnection request(
      String url, long offset, long length, String validator, boolean resume) throws Exception {
    return request(url, offset, length, validator, resume, null);
  }

  private HttpURLConnection request(
      String url, long offset, long length, String validator, boolean resume, Document previous)
      throws Exception {
    if (relay != null) return relay.open(url, offset, length);
    // A host already proven to serve its segments through the page-context bridge (route #1) skips
    // the two blocked network attempts: go straight to the content-script fetch. Only for a whole
    // segment — a byte-range or resume request cannot be served from the in-memory body. A bridge
    // failure here (e.g. a segment not yet observed in the tab) falls through to the network path
    // rather than failing outright.
    if (documentRole.isEmpty()
        && !resume
        && offset == 0
        && length <= 0
        && contentScriptHosts.contains(hostOf(url)))
      try {
        HttpURLConnection viaContentScript = contentScriptSegment(url, true);
        if (viaContentScript != null) return viaContentScript;
      } catch (TransferControl.Stopped stopped) {
        throw stopped;
      } catch (Exception bridgeRetry) {
        if (bridgeRetry instanceof DownloadError e
            && (e.category == DownloadError.Category.STORAGE
                || e.category == DownloadError.Category.STORAGE_FULL)) throw e;
      }
    // A segment host already confirmed blocked on every transport fails fast: no further request is
    // sent, so a download does not hammer a hotlink/anti-bot CDN with all 1000+ remaining segments.
    // Scoped to segments (documentRole empty); a manifest can still recover through the content-
    // script bridge, so it is never fast-failed here.
    if (documentRole.isEmpty() && blockedHosts.contains(hostOf(url)))
      throw new DownloadError(DownloadError.Category.ACCESS_BLOCKED)
          .diagnostic(
              new HlsDiagnostic(
                  url, 403, "", 0, "segment",
                  HlsDiagnostic.Stage.VARIANT_HTTP, "", DownloadError.Category.ACCESS_BLOCKED));
    try {
      // A host already shown to need the browser stack is served through it directly, so later
      // segments on that anti-bot CDN do not each repeat a blocked direct attempt.
      if (HttpConnections.browserAvailable() && browserPreferredHosts.contains(hostOf(url)))
        try {
          return nativeRequest(url, offset, length, validator, resume, previous, true);
        } catch (Exception browserOnly) {
          if (!access(browserOnly) && !transport(browserOnly)) throw browserOnly;
        }
      if (HttpConnections.browserAvailable() && !preferDirect) {
        if (!transportDecided)
          // The first request decides which stack serves this transfer; concurrent segment
          // workers wait for it instead of each sending a request the server may reject.
          synchronized (transportLock) {
            if (!transportDecided && !preferDirect) {
              HttpURLConnection decided =
                  browserThenDirect(url, offset, length, validator, resume, previous);
              transportDecided = true;
              return decided;
            }
          }
        if (!preferDirect)
          return browserThenDirect(url, offset, length, validator, resume, previous);
      }
      return nativeRequest(url, offset, length, validator, resume, previous, false);
    } catch (Exception first) {
      if (!access(first)) throw first;
      // The direct stack pinned by preferDirect was blocked for this request. A segment CDN
      // different from the manifest host may run an anti-bot challenge the direct stack cannot pass
      // while the browser stack — carrying the cleared browsing session's cookies — can. Try it, and
      // remember the host so its remaining segments skip the blocked direct attempt.
      if (preferDirect && HttpConnections.browserAvailable())
        try {
          HttpURLConnection viaBrowser =
              nativeRequest(url, offset, length, validator, resume, previous, true);
          browserPreferredHosts.add(hostOf(url));
          return viaBrowser;
        } catch (Exception browserRetry) {
          if (access(browserRetry)) first = browserRetry;
          else if (!transport(browserRetry)) throw browserRetry;
        }
      if (refreshContext(lastRequestUrl.get() == null ? url : lastRequestUrl.get()))
        try {
          return nativeRequest(url, offset, length, validator, resume, previous, false);
        } catch (Exception retry) {
          if (!access(retry)) throw retry;
          first = retry;
        }
      if (documentRole.isEmpty() && resourceStages.containsKey(url)) {
        record(
            url,
            first instanceof DownloadError && ((DownloadError) first).diagnostic != null
                ? ((DownloadError) first).diagnostic.status
                : 403,
            lastRedirects,
            "",
            "UNKNOWN",
            "native",
            "SEGMENT_NATIVE_BLOCKED",
            lastSentNames.getOrDefault(url, java.util.List.of()));
        // Route #1, last resort: native and the browser stack were both access-blocked for this
        // segment. Try the live playing tab's content-script context, where the site's anti-bot
        // challenge is already solved — the same page-context bridge the manifest bootstrap uses.
        // Only a whole-segment request is eligible; a byte-range or resume request cannot be served
        // from the bridge's in-memory body. Not a protection bypass: it uses the page's own fetch,
        // and only reaches segments the tab itself has fetched.
        boolean eligible = !resume && offset == 0 && length <= 0;
        try {
          HttpURLConnection viaContentScript = contentScriptSegment(url, eligible);
          if (viaContentScript != null) {
            String host = hostOf(url);
            if (!host.isEmpty()) contentScriptHosts.add(host);
            return viaContentScript;
          }
        } catch (TransferControl.Stopped stopped) {
          throw stopped;
        } catch (Exception bridgeFailure) {
          if (bridgeFailure instanceof DownloadError e
              && (e.category == DownloadError.Category.STORAGE
                  || e.category == DownloadError.Category.STORAGE_FULL)) throw e;
          record(
              url,
              bridgeFailure instanceof DownloadError
                      && ((DownloadError) bridgeFailure).diagnostic != null
                  ? ((DownloadError) bridgeFailure).diagnostic.status
                  : 0,
              0,
              "",
              "UNKNOWN",
              "content-script",
              "SEGMENT_CONTENT_SCRIPT_BLOCKED",
              java.util.List.of());
        }
        // Every transport — including the content-script bridge — was blocked for this segment
        // host: mark it so its remaining segments fail fast, and record the decision once (not once
        // per skipped segment) so the retry storm is a single HOST_ACCESS_BLOCKED event instead of
        // hundreds of repeats. A host the bridge can serve is left usable (never marked here).
        String blockedHost = hostOf(url);
        if (!blockedHost.isEmpty() && !contentScriptHosts.contains(blockedHost)) {
          blockedHosts.add(blockedHost);
          if (blockedHostRecorded.add(blockedHost))
            FailureDiagnostics.event(
                diagnosticSession, Map.of("action", "HOST_ACCESS_BLOCKED", "stage", stage(url)));
        }
      }
      throw first;
    }
  }

  private final Object transportLock = new Object();
  private volatile boolean transportDecided;

  private HttpURLConnection browserThenDirect(
      String url, long offset, long length, String validator, boolean resume, Document previous)
      throws Exception {
    try {
      return nativeRequest(url, offset, length, validator, resume, previous, true);
    } catch (Exception browserFailure) {
      // Only access and transport failures may differ between the stacks; anything else
      // (404, range mismatch, storage) is a property of the resource itself.
      if (!access(browserFailure) && !transport(browserFailure)) throw browserFailure;
      FailureDiagnostics.event(
          diagnosticSession, Map.of("action", "BROWSER_TRANSPORT_FALLBACK", "stage", stage(url)));
    }
    HttpURLConnection direct =
        nativeRequest(url, offset, length, validator, resume, previous, false);
    // The captured context works where the browser's did not (for example a partitioned
    // third-party cookie or a path-exact Referer): keep using it for this transfer.
    preferDirect = true;
    return direct;
  }

  private static boolean transport(Exception e) {
    if (e instanceof TransferControl.Stopped) return false;
    if (e instanceof DownloadError)
      return ((DownloadError) e).category == DownloadError.Category.NETWORK;
    return e instanceof java.io.IOException;
  }

  private HttpURLConnection nativeRequest(
      String url,
      long offset,
      long length,
      String validator,
      boolean resume,
      Document previous,
      boolean viaBrowser)
      throws Exception {
    control.check();
    // The browser stack is served through GeckoHttpConnection (GeckoWebExecutor). Label both its
    // transport and its action so a browser-stack attempt is never reported with a direct "native"
    // event name — this is the GeckoWebExecutor fetch, distinct from the page content-script fetch
    // used for manifest bootstrap.
    String transportLabel = viaBrowser ? "gecko" : "native";
    String actionLabel = viaBrowser ? "BROWSER_INITIAL" : "NATIVE_INITIAL";
    String current = url;
    for (int hop = 0; hop < 8; hop++) {
      Map<String, String> headers = context.forUrl(current);
      headers.keySet().removeIf(k -> k.equalsIgnoreCase("cookie"));
      Map<String, String> observed = captured.forUrl(current);
      String observedCookie = null;
      for (var e : observed.entrySet())
        if (e.getKey().equalsIgnoreCase("cookie")) observedCookie = e.getValue();
        else {
          headers.keySet().removeIf(k -> k.equalsIgnoreCase(e.getKey()));
          headers.put(e.getKey(), e.getValue());
        }
      // The browser transport sends Gecko's own live cookies; a replayed snapshot would be stale.
      String cookie = viaBrowser ? "" : cookies.header(URI.create(current), observedCookie);
      if (!cookie.isEmpty()) headers.put("Cookie", cookie);
      lastRequestUrl.set(current);
      HttpURLConnection connection = HttpConnections.open(current, viaBrowser);
      control.attach(connection);
      connection.setInstanceFollowRedirects(false);
      connection.setConnectTimeout(20_000);
      connection.setReadTimeout(45_000);
      connection.setRequestProperty("Accept-Encoding", "identity");
      connection.setRequestProperty("Accept", "*/*");
      headers.forEach(connection::setRequestProperty);
      // Media discovered in the DOM (rather than from an observed network request) carries no
      // captured request context, so without this the request would reach the CDN with no Referer
      // and no User-Agent and a hotlink/anti-bot origin answers 403. Supply the page as Referer and
      // the engine's own User-Agent — what the in-page player sends — only when they are absent, so
      // a captured value is never overridden.
      boolean hasReferer = headers.keySet().stream().anyMatch(k -> k.equalsIgnoreCase("referer"));
      boolean hasUserAgent =
          headers.keySet().stream().anyMatch(k -> k.equalsIgnoreCase("user-agent"));
      if (!hasReferer && topPage != null && !topPage.isEmpty())
        try {
          URI p = URI.create(topPage);
          if (p.getHost() != null && java.util.Set.of("http", "https").contains(p.getScheme()))
            connection.setRequestProperty("Referer", p.resolve("/").toString());
        } catch (RuntimeException ignored) {
        }
      if (!hasUserAgent && !HttpConnections.userAgent().isEmpty())
        connection.setRequestProperty("User-Agent", HttpConnections.userAgent());
      lastHeaders.put(current, new java.util.LinkedHashMap<>(headers));
      if (previous != null && current.equals(previous.url)) {
        if (!previous.etag.isEmpty()) connection.setRequestProperty("If-None-Match", previous.etag);
        else if (!previous.modified.isEmpty())
          connection.setRequestProperty("If-Modified-Since", previous.modified);
      }
      if (resume && offset > 0) {
        connection.setRequestProperty("Range", "bytes=" + offset + "-");
        if (validator != null && !validator.isEmpty())
          connection.setRequestProperty("If-Range", validator);
      }
      if (length > 0)
        connection.setRequestProperty("Range", "bytes=" + offset + "-" + (offset + length - 1));
      java.util.Set<String> sentNames =
          new java.util.HashSet<>(connection.getRequestProperties().keySet());
      int code;
      try {
        code = connection.getResponseCode();
      } catch (Exception error) {
        release(connection);
        control.check();
        throw new DownloadError(DownloadError.Category.NETWORK)
            .diagnostic(
                new HlsDiagnostic(
                    current,
                    0,
                    "",
                    hop,
                    documentRole,
                    documentRole.equals("root")
                        ? HlsDiagnostic.Stage.ROOT_HTTP
                        : HlsDiagnostic.Stage.VARIANT_HTTP,
                    "",
                    DownloadError.Category.NETWORK));
      }
      FailureDiagnostics.event(
          diagnosticSession,
          Map.of(
              "action",
              "PHASE",
              "stage",
              stage(current),
              "contentLengthPresent",
              connection.getHeaderField("Content-Length") != null,
              "etagPresent",
              connection.getHeaderField("ETag") != null,
              "rangeSupported",
              code == 206 || "bytes".equalsIgnoreCase(connection.getHeaderField("Accept-Ranges"))));
      lastRedirects = hop;
      lastSentNames.put(current, sentNames);
      String errorBody = "UNKNOWN";
      if (code >= 400) {
        try (InputStream denied = connection.getErrorStream()) {
          if (denied != null) {
            connection.setReadTimeout(1500);
            byte[] sample = new byte[4096];
            int used = 0, n;
            while (used < sample.length
                && (n = denied.read(sample, used, sample.length - used)) > 0) used += n;
            errorBody = bodyKind(new String(sample, 0, used, StandardCharsets.UTF_8));
          }
        } catch (Exception ignored) {
        }
      }
      record(
          current,
          code,
          hop,
          connection.getContentType(),
          errorBody,
          transportLabel,
          actionLabel,
          sentNames);
      boolean cookieChanged = cookies.accept(URI.create(current), connection.getHeaderFields());
      if (cookieChanged)
        FailureDiagnostics.event(
            diagnosticSession,
            Map.of(
                "action",
                "COOKIE_UPDATE",
                "setCookieObserved",
                true,
                "cookieRefreshed",
                true,
                "cookieEntryCount",
                cookies.entryCount()));
      if (cookieChanged && cookieListener != null)
        try {
          cookieListener.changed(cookies.snapshot());
        } catch (Exception storage) {
          release(connection);
          throw DownloadError.storage(storage);
        }
      if (previous != null && code == 304 && current.equals(previous.url)) return connection;
      if (code == 301 || code == 302 || code == 303 || code == 307 || code == 308) {
        String location = connection.getHeaderField("Location");
        String responseMime = connection.getContentType();
        release(connection);
        if (location == null) throw redirectFailure(current, code, responseMime, hop);
        String next;
        try {
          URI redirect = URI.create(current).resolve(location);
          if (!java.util.Set.of("http", "https").contains(redirect.getScheme())
              || redirect.getHost() == null
              || redirect.getUserInfo() != null) throw new IllegalArgumentException();
          next = redirect.toString();
        } catch (IllegalArgumentException invalid) {
          throw redirectFailure(current, code, responseMime, hop);
        }
        if (current.startsWith("https:") && !next.startsWith("https:"))
          throw redirectFailure(current, code, connection.getContentType(), hop);
        current = next;
        continue;
      }
      if (code == 401 || code == 403) {
        String kind = "UNKNOWN";
        try (InputStream error = connection.getErrorStream()) {
          if (error != null) {
            byte[] prefix = new byte[4096];
            int n = error.read(prefix);
            if (n > 0) kind = bodyKind(new String(prefix, 0, n, StandardCharsets.UTF_8));
          }
        } catch (IOException ignored) {
        }
        record(
            current,
            code,
            hop,
            connection.getContentType(),
            kind,
            transportLabel,
            actionLabel,
            sentNames);
      }
      if (code < 200
          || code > 299
          || (length > 0 && code != 206)
          || (!resume && length <= 0 && code == 206)) {
        boolean challenge = connection.getHeaderField("WWW-Authenticate") != null;
        DownloadError failure =
            DownloadError.http(
                code, current, acceptedResource, challenge, System.currentTimeMillis());
        failure.diagnostic(
            new HlsDiagnostic(
                current,
                code,
                connection.getContentType(),
                hop,
                documentRole.isEmpty() ? "segment" : documentRole,
                (code == 401 || code == 403) && headers.isEmpty()
                    ? HlsDiagnostic.Stage.REQUEST_CONTEXT_UNAVAILABLE
                    : !documentRole.equals("root") && (code == 401 || code == 403)
                        ? HlsDiagnostic.Stage.CHILD_AUTH
                        : documentRole.equals("root")
                            ? HlsDiagnostic.Stage.ROOT_HTTP
                            : HlsDiagnostic.Stage.VARIANT_HTTP,
                "",
                failure.category));
        release(connection);
        throw failure;
      }
      if (url.equals(rootUrl)) {
        acceptedResource = true;
        String disposition = connection.getHeaderField("Content-Disposition");
        if (disposition != null && disposition.length() <= 4096) responseDisposition = disposition;
      }
      if (length > 0) {
        String range = connection.getHeaderField("Content-Range");
        if (range == null
            || !range.startsWith("bytes " + offset + "-" + (offset + length - 1) + "/")) {
          connection.disconnect();
          throw new IOException("세그먼트 범위 불일치");
        }
      }
      return connection;
    }
    throw redirectFailure(current, 0, "", 8);
  }

  // Fetches a whole segment through the live playing tab's content-script context (route #1) and
  // wraps the returned bytes in an in-memory connection the segment consumers read like any other.
  // Returns null when the attempt is not applicable (ineligible request, no provider, or the
  // segment was never observed in the tab, so the page-context fetch has nothing to replay). A
  // reachable-but-refused fetch throws DownloadError instead, so the caller marks the host blocked.
  private HttpURLConnection contentScriptSegment(String url, boolean eligible) throws Exception {
    if (!eligible || topPage == null || topTab < 0 || topFrame < 0) return null;
    var provider = MediaNetworkSession.provider();
    if (provider == null) return null;
    var observed = MediaNetworkSession.latest(url, topPage, topTab, topFrame);
    if (observed == null) {
      // The bridge replays a request the live tab was seen making successfully; without that
      // observation there is nothing to replay. Record once per host that the player's own
      // successful fetch of this segment host was never captured, so the report can tell this apart
      // from a transport that was actually attempted and blocked.
      String host = hostOf(url);
      if (!host.isEmpty() && contentScriptUnobservedRecorded.add(host))
        FailureDiagnostics.event(
            diagnosticSession,
            Map.of("action", "SEGMENT_CONTENT_SCRIPT_UNOBSERVED", "stage", stage(url)));
      return null;
    }
    control.check();
    record(
        url, 0, 0, "", "UNKNOWN", "content-script", "SEGMENT_CONTENT_SCRIPT", observed.headerNames());
    var response = provider.manifest(url, topPage, topTab, topFrame, observed.headers(), control);
    if (response.status() < 200
        || response.status() >= 300
        || response.body().length == 0
        || response.body().length > CONTENT_SCRIPT_BODY_LIMIT
        || (url.startsWith("https:") && !response.url().startsWith("https:")))
      throw new DownloadError(DownloadError.Category.ACCESS_BLOCKED)
          .diagnostic(
              new HlsDiagnostic(
                  url,
                  response.status(),
                  response.metadata().getOrDefault("content-type", ""),
                  response.redirects(),
                  "segment",
                  HlsDiagnostic.Stage.VARIANT_HTTP,
                  "",
                  DownloadError.Category.ACCESS_BLOCKED));
    // Segment responses can carry rotating hotlink cookies; adopt them so a later native request
    // to the same host benefits, exactly as the manifest bootstrap does.
    for (var entry : response.responseCookies().entrySet())
      if (cookies.accept(URI.create(entry.getKey()), Map.of("Set-Cookie", entry.getValue())))
        persistCookies();
    record(
        url,
        response.status(),
        response.redirects(),
        response.metadata().getOrDefault("content-type", ""),
        "MEDIA",
        "content-script",
        "SEGMENT_CONTENT_SCRIPT",
        response.requestHeaders().keySet());
    return inMemoryConnection(url, response);
  }

  private HttpURLConnection inMemoryConnection(String url, MediaNetworkSession.Bootstrap response)
      throws Exception {
    byte[] body = response.body();
    String contentType = response.metadata().getOrDefault("content-type", "");
    InputStream stream = new ByteArrayInputStream(body);
    HttpURLConnection connection =
        new HttpURLConnection(URI.create(url).toURL()) {
          @Override
          public void connect() {}

          @Override
          public boolean usingProxy() {
            return false;
          }

          @Override
          public void disconnect() {
            try {
              stream.close();
            } catch (IOException ignored) {
            }
            control.detach(this);
          }

          @Override
          public int getResponseCode() {
            return 200;
          }

          @Override
          public InputStream getInputStream() {
            return stream;
          }

          @Override
          public long getContentLengthLong() {
            return body.length;
          }

          @Override
          public String getContentType() {
            return contentType.isEmpty() ? null : contentType;
          }

          @Override
          public String getHeaderField(String name) {
            if (name == null) return null;
            if (name.equalsIgnoreCase("Content-Length")) return String.valueOf(body.length);
            if (name.equalsIgnoreCase("Content-Type")) return contentType.isEmpty() ? null : contentType;
            return null;
          }
        };
    control.attach(connection);
    return connection;
  }

  public byte[] bytes(String url, long offset, long length, int limit) throws Exception {
    Exception last = null;
    for (int attempt = 0; attempt < 3; attempt++) {
      HttpURLConnection connection = null;
      try {
        connection = open(url, offset, length);
        long expected = connection.getContentLengthLong();
        if (expected > limit || length > limit) throw new IOException("세그먼트 크기 제한 초과");
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (InputStream in = connection.getInputStream()) {
          byte[] buffer = new byte[64 * 1024];
          int count;
          while ((count = in.read(buffer)) != -1) {
            control.check();
            if (out.size() + (long) count > limit) throw new IOException("응답 크기 제한 초과");
            out.write(buffer, 0, count);
          }
        }
        byte[] data = out.toByteArray();
        if (data.length == 0
            || (expected >= 0 && data.length != expected)
            || (length > 0 && data.length != length)) throw new IOException("누락되거나 잘린 세그먼트");
        return data;
      } catch (Exception error) {
        control.check();
        DownloadError storageFailure = DownloadError.storage(error);
        if (storageFailure.category == DownloadError.Category.STORAGE_FULL) throw storageFailure;
        if (error instanceof DownloadError) throw error;
        last = error;
        if (attempt < 2) Thread.sleep(300L * (attempt + 1));
      } finally {
        if (connection != null) connection.disconnect();
      }
    }
    throw last;
  }

  public interface Decoder {
    InputStream wrap(InputStream input) throws Exception;
  }

  /** Segments stream to a staging file; retries always replace an incomplete attempt. */
  public void stream(String url, long offset, long length, File file, Decoder decoder)
      throws Exception {
    Exception last = null;
    for (int attempt = 0; attempt < 3; attempt++) {
      HttpURLConnection connection = null;
      try {
        connection = open(url, offset, length);
        long expected = connection.getContentLengthLong();
        if (expected > 0 && expected > file.getParentFile().getUsableSpace() - 8L * 1024 * 1024)
          throw new DownloadError(DownloadError.Category.STORAGE_FULL);
        CountingInput raw = new CountingInput(connection.getInputStream());
        try (InputStream input = decoder == null ? raw : decoder.wrap(raw);
            FileOutputStream output = new FileOutputStream(file)) {
          byte[] buffer = new byte[128 * 1024];
          int n;
          long written = 0;
          while ((n = input.read(buffer)) != -1) {
            control.check();
            try {
              output.write(buffer, 0, n);
            } catch (IOException storage) {
              throw DownloadError.storage(storage);
            }
            written += n;
          }
          control.check();
          if (written <= 0
              || expected >= 0 && raw.count != expected
              || length > 0 && raw.count != length)
            throw new DownloadError(DownloadError.Category.NETWORK);
          try {
            output.getFD().sync();
          } catch (IOException storage) {
            throw DownloadError.storage(storage);
          }
        }
        return;
      } catch (Exception error) {
        control.check();
        DownloadError storageFailure = DownloadError.storage(error);
        if (storageFailure.category == DownloadError.Category.STORAGE_FULL) throw storageFailure;
        if (error instanceof DownloadError
            && ((DownloadError) error).category != DownloadError.Category.NETWORK
            && ((DownloadError) error).category != DownloadError.Category.SERVER) throw error;
        last = error;
        if (attempt < 2) Thread.sleep(300L * (attempt + 1));
      } finally {
        if (connection != null) release(connection);
      }
    }
    if (last instanceof DownloadError) throw last;
    throw new DownloadError(DownloadError.Category.NETWORK);
  }

  private static final class CountingInput extends FilterInputStream {
    long count;

    CountingInput(InputStream input) {
      super(input);
    }

    @Override
    public int read() throws IOException {
      int value = in.read();
      if (value >= 0) count++;
      return value;
    }

    @Override
    public int read(byte[] buffer, int offset, int length) throws IOException {
      int n = in.read(buffer, offset, length);
      if (n > 0) count += n;
      return n;
    }
  }

  public static final class Document {
    public final String url, text, etag, modified, mime, disposition;
    public final long fetchedAt, length;
    public int redirects;
    public final int status;

    Document(String url, String text, HttpURLConnection response, Document previous)
        throws IOException {
      status = response.getResponseCode();
      this.url = url;
      this.text = text;
      etag = header(response, "ETag", previous == null ? "" : previous.etag);
      modified = header(response, "Last-Modified", previous == null ? "" : previous.modified);
      mime = header(response, "Content-Type", previous == null ? "" : previous.mime);
      disposition =
          header(response, "Content-Disposition", previous == null ? "" : previous.disposition);
      fetchedAt = System.currentTimeMillis();
      length = response.getContentLengthLong();
    }

    Document(MediaNetworkSession.Bootstrap b) {
      url = b.url();
      status = b.status();
      redirects = b.redirects();
      text = new String(b.body(), StandardCharsets.UTF_8);
      mime = b.metadata().getOrDefault("content-type", "");
      etag = b.metadata().getOrDefault("etag", "");
      modified = b.metadata().getOrDefault("last-modified", "");
      disposition = b.metadata().getOrDefault("content-disposition", "");
      fetchedAt = System.currentTimeMillis();
      length = b.body().length;
    }

    private static String header(HttpURLConnection response, String name, String fallback) {
      String value = response.getHeaderField(name);
      return value == null ? fallback : value;
    }
  }

  public Document document(String url) throws Exception {
    return document(url, null);
  }

  private DownloadError redirectFailure(String url, int status, String mime, int redirects) {
    return new DownloadError(DownloadError.Category.NETWORK)
        .diagnostic(
            new HlsDiagnostic(
                url,
                status,
                mime,
                redirects,
                documentRole,
                HlsDiagnostic.Stage.REDIRECT,
                "",
                DownloadError.Category.NETWORK));
  }

  public Document document(String url, Document previous, String role) throws Exception {
    documentRole = role;
    try {
      return document(url, previous);
    } finally {
      documentRole = "";
    }
  }

  public Document document(String url, Document previous) throws Exception {
    if (relay != null) return nativeDocument(url, null);
    var playback = MediaNetworkSession.provider();
    if (playback != null && topPage != null && topTab >= 0 && topFrame >= 0) {
      var captured = playback.captured(url, topPage, topTab, topFrame, control);
      if (captured != null) {
        boolean verifiedRedirect =
            url.equals(captured.metadata().get("captured-request-url"))
                && captured.redirects() > 0
                && captured.redirects() <= 8
                && !(url.startsWith("https:") && !captured.url().startsWith("https:"));
        if ((!url.equals(captured.url()) && !verifiedRedirect)
            || captured.status() < 200
            || captured.status() >= 300
            || captured.body().length == 0
            || captured.body().length > 8388608)
          throw new DownloadError(DownloadError.Category.MANIFEST_INVALID);
        playbackRequestType = captured.metadata().getOrDefault("request-type", "playback-response");
        Document d = adoptManifest(captured, url);
        try {
          manifestEvidenceExpiresAt =
              Math.min(
                  manifestEvidenceExpiresAt,
                  Long.parseLong(captured.metadata().getOrDefault("evidence-expires-at", "0")));
        } catch (NumberFormatException invalid) {
          throw new DownloadError(DownloadError.Category.MANIFEST_INVALID);
        }
        if (!java.util.Set.of("HLS", "DASH").contains(bodyKind(d.text)))
          throw new DownloadError(DownloadError.Category.MANIFEST_INVALID);
        record(
            url,
            d.status,
            d.redirects,
            d.mime,
            bodyKind(d.text),
            "gecko-response-cache",
            "GECKO_RESPONSE_CACHE",
            captured.requestHeaders().keySet());
        return d;
      }
      record(
          url,
          0,
          0,
          "",
          "UNKNOWN",
          "gecko-response-cache",
          "GECKO_CACHE_MISS",
          java.util.List.of());
    }
    if (capturedOnly) throw new DownloadError(DownloadError.Category.ACCESS_BLOCKED);
    try {
      Document d = nativeDocument(url, previous);
      record(
          url,
          d.status,
          d.redirects,
          d.mime,
          bodyKind(d.text),
          "native",
          "NATIVE_INITIAL",
          lastSentNames.getOrDefault(url, java.util.List.of()));
      if (d.text
          .stripLeading()
          .toLowerCase(java.util.Locale.ROOT)
          .matches("(?s)<(?:!doctype|html|head|body|form).*"))
        HlsDiagnostic.body(d, documentRole.isEmpty() ? "root" : documentRole);

      if (d.text.contains("#EXT-X-STREAM-INF:")) {
        var proof = MediaNetworkSession.latest(url, topPage, topTab, topFrame);
        MediaNetworkSession.trace(
            "MASTER_MANIFEST",
            d.url,
            "native",
            d.status,
            d.redirects,
            d.mime,
            "HLS",
            lastSentNames.getOrDefault(d.url, java.util.List.of()),
            proof == null ? java.util.List.of() : proof.headerNames(),
            proof != null,
            "NATIVE_INITIAL");
      }
      return d;
    } catch (Exception first) {
      if (!access(first)) throw first;
      if (refreshContext(url))
        try {
          Document d = nativeDocument(url, null);
          if (bodyKind(d.text).startsWith("HTML")) HlsDiagnostic.body(d, documentRole);
          return d;
        } catch (Exception retry) {
          if (!access(retry)) throw retry;
          first = retry;
        }
      var observed = MediaNetworkSession.latest(url, topPage, topTab, topFrame);
      var provider = MediaNetworkSession.provider();
      record(
          url,
          first instanceof DownloadError && ((DownloadError) first).diagnostic != null
              ? ((DownloadError) first).diagnostic.status
              : 403,
          lastRedirects,
          "",
          "UNKNOWN",
          "native",
          "MANIFEST_NATIVE_BLOCKED",
          lastSentNames.getOrDefault(url, java.util.List.of()));
      if (observed == null
          || provider == null
          || bootstrapped.size() >= 8
          || !bootstrapped.add(url)) throw first;
      try {
        record(url, 0, 0, "", "UNKNOWN", "content-script", "GECKO_BOOTSTRAP", observed.headerNames());
        var response =
            provider.manifest(url, topPage, topTab, topFrame, observed.headers(), control);
        if (response.status() < 200
            || response.status() >= 300
            || response.body().length > 8388608
            || response.body().length == 0
            || url.startsWith("https:") && !response.url().startsWith("https:"))
          throw new DownloadError(DownloadError.Category.ACCESS_BLOCKED);
        Document d = adoptManifest(response, url);
        var fresh = MediaNetworkSession.latest(response.url(), topPage, topTab, topFrame);
        record(
            url,
            d.status,
            d.redirects,
            d.mime,
            bodyKind(d.text),
            "content-script",
            "GECKO_BOOTSTRAP",
            fresh == null ? observed.headerNames() : fresh.headerNames());
        if (bodyKind(d.text).startsWith("HTML")) HlsDiagnostic.body(d, documentRole);
        return d;
      } catch (TransferControl.Stopped stopped) {
        throw stopped;
      } catch (Exception blocked) {
        if (blocked instanceof DownloadError e
            && (e.category == DownloadError.Category.STORAGE
                || e.category == DownloadError.Category.STORAGE_FULL)) throw e;
        record(
            url,
            blocked instanceof DownloadError && ((DownloadError) blocked).diagnostic != null
                ? ((DownloadError) blocked).diagnostic.status
                : 0,
            0,
            "",
            "UNKNOWN",
            "content-script",
            "CONTENT_SCRIPT_BLOCKED",
            observed.headerNames());
        throw first;
      }
    }
  }

  private Document adoptManifest(MediaNetworkSession.Bootstrap response, String requested)
      throws Exception {
    if (!response.requestHeaders().isEmpty()) {
      adoptContext(response.url(), response.requestHeaders());
      persistContext(response.url(), response.requestHeaders());
    } else {
      var latest = MediaNetworkSession.latest(response.url(), topPage, topTab, topFrame);
      if (latest != null) {
        adoptContext(response.url(), latest.headers());
        persistContext(response.url(), latest.headers());
      }
    }
    browserBootstrapped = true;
    for (var entry : response.responseCookies().entrySet())
      if (cookies.accept(
          java.net.URI.create(entry.getKey()), Map.of("Set-Cookie", entry.getValue())))
        persistCookies();
    return new Document(response);
  }

  private static String bodyKind(String text) {
    String p = text.replaceFirst("^\\uFEFF", "").stripLeading();
    if (p.startsWith("#EXTM3U")) return "HLS";
    if (p.matches(
        "(?s)(?:<\\?xml[^>]*>\\s*)?(?:<!--[\\s\\S]*?-->\\s*)*<(?:[\\w.-]+:)?MPD(?:\\s|>).*"))
      return "DASH";
    if (p.toLowerCase(java.util.Locale.ROOT).matches("(?s)<(?:!doctype|html|head|body|form).*"))
      return p.toLowerCase(java.util.Locale.ROOT)
              .matches("(?s).*(challenge|captcha|cloudflare|access.denied|forbidden|ddos|차단).*")
          ? "HTML_CHALLENGE"
          : "HTML";
    return "UNKNOWN";
  }

  private Document nativeDocument(String url, Document previous) throws Exception {
    HttpURLConnection connection = request(url, 0, -1, null, false, previous);
    try {
      String effective = connection.getURL().toString();
      if (connection.getResponseCode() == 304 && previous != null)
        return new Document(effective, previous.text, connection, previous);
      long expected = connection.getContentLengthLong();
      if (expected > 8 * 1024 * 1024)
        throw new DownloadError(DownloadError.Category.MANIFEST_INVALID);
      ByteArrayOutputStream out = new ByteArrayOutputStream();
      try (InputStream in = connection.getInputStream()) {
        byte[] buffer = new byte[64 * 1024];
        int n;
        while ((n = in.read(buffer)) != -1) {
          control.check();
          if (out.size() + (long) n > 8 * 1024 * 1024)
            throw new DownloadError(DownloadError.Category.MANIFEST_INVALID);
          out.write(buffer, 0, n);
        }
      }
      if (out.size() == 0 || expected >= 0 && out.size() != expected)
        throw new DownloadError(DownloadError.Category.NETWORK);
      Document result =
          new Document(
              effective, new String(out.toByteArray(), StandardCharsets.UTF_8), connection, null);
      result.redirects = lastRedirects;
      return result;
    } finally {
      release(connection);
    }
  }

  public void copy(String url, File file, DownloadEngine.Progress progress) throws Exception {
    HttpURLConnection connection = open(url, 0, -1);
    try (InputStream in = connection.getInputStream();
        OutputStream out = new BufferedOutputStream(new FileOutputStream(file))) {
      long total = connection.getContentLengthLong(), done = 0;
      byte[] buffer = new byte[128 * 1024];
      int n, previous = -1;
      while ((n = in.read(buffer)) != -1) {
        if (Thread.currentThread().isInterrupted()) throw new InterruptedIOException("다운로드 중단");
        out.write(buffer, 0, n);
        done += n;
        int percent = total > 0 ? (int) (done * 100 / total) : 0;
        if (percent != previous) {
          progress.update("원본 저장 중", percent);
          previous = percent;
        }
      }
      if (done == 0 || (total >= 0 && done != total)) throw new IOException("파일이 완전히 내려받아지지 않았습니다");
    } finally {
      connection.disconnect();
    }
  }
}
