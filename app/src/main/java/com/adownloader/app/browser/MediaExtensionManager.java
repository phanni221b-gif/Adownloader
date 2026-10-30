package com.adownloader.app.browser;

import android.util.Log;

import com.adownloader.app.media.MediaDetector;

import org.json.JSONObject;
import org.mozilla.geckoview.GeckoResult;
import org.mozilla.geckoview.GeckoRuntime;
import org.mozilla.geckoview.WebExtension;

import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;

/** Installs the bundled Gecko WebExtension that observes network/media events. */
public final class MediaExtensionManager {
    private static final String TAG = "MediaExtension";
    private static final String EXTENSION_URI = "resource://android/assets/media_sniffer/";
    private static final String EXTENSION_ID = "media-sniffer@adownloader.local";
    private static final String NATIVE_APP = "adownloader";

    public interface StatusListener {
        void onExtensionReady();
        void onExtensionError(String message);
    }

    private final GeckoRuntime runtime;
    private final MediaDetector detector;
    private final StatusListener statusListener;

    public MediaExtensionManager(GeckoRuntime runtime, MediaDetector detector, StatusListener statusListener) {
        this.runtime = runtime;
        this.detector = detector;
        this.statusListener = statusListener;
    }

    public void install() {
        runtime.getWebExtensionController()
                .ensureBuiltIn(EXTENSION_URI, EXTENSION_ID)
                .accept(extension -> {
                    extension.setMessageDelegate(new WebExtension.MessageDelegate() {
                        @Override
                        public GeckoResult<Object> onMessage(String nativeApp, Object message, WebExtension.MessageSender sender) {
                            if (!NATIVE_APP.equals(nativeApp) || !(message instanceof JSONObject)) return null;
                            handleMessage((JSONObject) message);
                            return null;
                        }
                    }, NATIVE_APP);
                    if (statusListener != null) statusListener.onExtensionReady();
                }, error -> {
                    Log.e(TAG, "Unable to install media extension", error);
                    if (statusListener != null) statusListener.onExtensionError(error.getMessage());
                });
    }

    private void handleMessage(JSONObject json) {
        String url = json.optString("url", "").trim();
        if (url.isEmpty()) return;

        String contentType = json.optString("contentType", "");
        String source = json.optString("source", "extension");
        String pageUrl = json.optString("pageUrl", null);
        Map<String, String> headers = new LinkedHashMap<>();
        JSONObject headerJson = json.optJSONObject("headers");
        if (headerJson != null) {
            Iterator<String> keys = headerJson.keys();
            while (keys.hasNext()) {
                String key = keys.next();
                String value = headerJson.optString(key, "");
                if (!value.isBlank()) headers.put(key, value);
            }
        }
        detector.capture(url, contentType, source, pageUrl, headers);
    }
}
