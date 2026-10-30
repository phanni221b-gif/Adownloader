package com.adownloader.app

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.webkit.JavaScriptReplyProxy
import androidx.webkit.ServiceWorkerClientCompat
import androidx.webkit.ServiceWorkerControllerCompat
import androidx.webkit.WebMessageCompat
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.net.URI

class MainActivity : Activity() {
    private lateinit var webView: WebView
    private lateinit var address: EditText
    private lateinit var mediaButton: TextView
    private lateinit var root: LinearLayout
    private lateinit var body: FrameLayout
    private lateinit var toolbar: LinearLayout
    private lateinit var bottomBar: LinearLayout
    private lateinit var mediaSniffer: MediaSniffer

    private val prefs by lazy { getSharedPreferences("adownloader", MODE_PRIVATE) }
    private var adBlockEnabled: Boolean
        get() = prefs.getBoolean("ad_block", true)
        set(value) = prefs.edit().putBoolean("ad_block", value).apply()
    private var popupConfirmEnabled: Boolean
        get() = prefs.getBoolean("popup_confirm", true)
        set(value) = prefs.edit().putBoolean("popup_confirm", value).apply()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= 30) window.setDecorFitsSystemWindows(false)
        window.statusBarColor = Color.WHITE
        window.navigationBarColor = Color.WHITE
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR

        mediaSniffer = MediaSniffer { updateMediaButton(it.size) }
        installServiceWorkerSniffer()
        buildUi()
        configureWebView()
        applyInsets()
        requestNotificationPermissionIfNeeded()

        if (savedInstanceState == null) {
            webView.loadUrl("https://www.google.com")
        } else {
            webView.restoreState(savedInstanceState)
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        webView.saveState(outState)
        super.onSaveInstanceState(outState)
    }

    override fun onDestroy() {
        webView.destroy()
        super.onDestroy()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (webView.canGoBack()) webView.goBack() else super.onBackPressed()
    }

    private fun buildUi() {
        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.WHITE)
        }

        toolbar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(6), dp(5), dp(6), dp(5))
            setBackgroundColor(Color.WHITE)
        }

        toolbar.addView(navButton("‹") { if (webView.canGoBack()) webView.goBack() })
        toolbar.addView(navButton("›") { if (webView.canGoForward()) webView.goForward() })
        toolbar.addView(navButton("↻") { webView.reload() })

        address = EditText(this).apply {
            setSingleLine(true)
            textSize = 14f
            hint = "검색 또는 주소 입력"
            setPadding(dp(12), 0, dp(12), 0)
            background = rounded(Color.rgb(245, 245, 247), dp(18).toFloat())
            setOnEditorActionListener { _, _, event ->
                if (event == null || event.keyCode == KeyEvent.KEYCODE_ENTER) {
                    loadTypedAddress()
                    true
                } else false
            }
        }
        toolbar.addView(address, LinearLayout.LayoutParams(0, dp(42), 1f).apply {
            marginStart = dp(4)
            marginEnd = dp(4)
        })
        toolbar.addView(navButton("⋮") { showBrowserMenu() })

        body = FrameLayout(this)
        webView = WebView(this)
        body.addView(webView, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        mediaButton = TextView(this).apply {
            text = "0"
            textSize = 15f
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            background = circle(Color.rgb(225, 34, 71))
            elevation = dp(7).toFloat()
            visibility = View.GONE
            setOnClickListener { showDetectedMedia() }
        }
        body.addView(mediaButton, FrameLayout.LayoutParams(dp(58), dp(58), Gravity.END or Gravity.BOTTOM).apply {
            marginEnd = dp(18)
            bottomMargin = dp(18)
        })

        bottomBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(dp(4), dp(3), dp(4), dp(3))
            setBackgroundColor(Color.WHITE)
            elevation = dp(8).toFloat()
        }
        bottomBar.addView(bottomItem("◎", "브라우저") { webView.visibility = View.VISIBLE })
        bottomBar.addView(bottomItem("⇩", "다운로드") { showDownloads(false) })
        bottomBar.addView(bottomItem("▷", "플레이어") { showDownloads(true) })
        bottomBar.addView(bottomItem("⚙", "설정") { showSettings() })

        root.addView(toolbar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(54)))
        root.addView(body, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        root.addView(bottomBar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(64)))
        setContentView(root)
    }

    private fun configureWebView() {
        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(webView, true)

        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            mediaPlaybackRequiresUserGesture = false
            javaScriptCanOpenWindowsAutomatically = true
            setSupportMultipleWindows(true)
            loadsImagesAutomatically = true
            allowContentAccess = true
            allowFileAccess = false
            mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
            builtInZoomControls = true
            displayZoomControls = false
        }
        applyUserAgent(prefs.getString("ua_mode", "default") ?: "default")

        installDocumentStartHooks()

        webView.webViewClient = object : WebViewClient() {
            override fun onPageStarted(view: WebView?, url: String?, favicon: android.graphics.Bitmap?) {
                if (!url.isNullOrBlank()) {
                    address.setText(url)
                    mediaSniffer.clear()
                }
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                if (!url.isNullOrBlank()) address.setText(url)
                if (!WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
                    view?.evaluateJavascript(MEDIA_HOOK_JS, null)
                }
            }

            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest): Boolean {
                val uri = request.url
                val scheme = uri.scheme?.lowercase()
                if (scheme == "http" || scheme == "https") return false
                return openExternal(uri)
            }

            override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest): WebResourceResponse? {
                mediaSniffer.observeRequest(request, view?.url)
                if (adBlockEnabled && AdBlocker.shouldBlock(request.url.toString(), mediaSniffer)) {
                    return emptyResponse()
                }
                return null
            }

            override fun onReceivedError(view: WebView?, request: WebResourceRequest, error: WebResourceError) {
                if (!request.isForMainFrame) return
                runOnUiThread { showLoadError(request.url.toString(), error.errorCode, error.description.toString()) }
            }
        }

        webView.webChromeClient = object : WebChromeClient() {
            private var customView: View? = null
            private var customCallback: CustomViewCallback? = null

            override fun onCreateWindow(view: WebView?, isDialog: Boolean, isUserGesture: Boolean, resultMsg: android.os.Message): Boolean {
                val popup = WebView(this@MainActivity)
                popup.settings.javaScriptEnabled = true
                popup.webViewClient = object : WebViewClient() {
                    private var handled = false
                    private fun handle(url: String): Boolean {
                        if (handled) return true
                        handled = true
                        popup.stopLoading()
                        popup.destroy()
                        handlePopup(url)
                        return true
                    }
                    override fun shouldOverrideUrlLoading(v: WebView?, request: WebResourceRequest): Boolean = handle(request.url.toString())
                    override fun onPageStarted(v: WebView?, url: String?, favicon: android.graphics.Bitmap?) {
                        if (!url.isNullOrBlank() && url != "about:blank") handle(url)
                    }
                }
                val transport = resultMsg.obj as WebView.WebViewTransport
                transport.webView = popup
                resultMsg.sendToTarget()
                return true
            }

            override fun onShowCustomView(view: View?, callback: CustomViewCallback?) {
                if (view == null || customView != null) {
                    callback?.onCustomViewHidden()
                    return
                }
                customView = view
                customCallback = callback
                toolbar.visibility = View.GONE
                bottomBar.visibility = View.GONE
                webView.visibility = View.GONE
                body.addView(view, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            }

            override fun onHideCustomView() {
                val v = customView ?: return
                body.removeView(v)
                customView = null
                customCallback?.onCustomViewHidden()
                customCallback = null
                toolbar.visibility = View.VISIBLE
                bottomBar.visibility = View.VISIBLE
                webView.visibility = View.VISIBLE
            }
        }

        webView.setDownloadListener { url, _, _, mimetype, _ ->
            val page = webView.url
            mediaSniffer.observeDownload(url, mimetype, page)
            showDetectedMedia()
        }
    }

    private fun installDocumentStartHooks() {
        if (WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)) {
            WebViewCompat.addWebMessageListener(
                webView,
                "AdownloaderBridge",
                mutableSetOf("*"),
                object : WebViewCompat.WebMessageListener {
                    override fun onPostMessage(
                        view: WebView,
                        message: WebMessageCompat,
                        sourceOrigin: Uri,
                        isMainFrame: Boolean,
                        replyProxy: JavaScriptReplyProxy
                    ) {
                        message.data?.let { mediaSniffer.observeJs(it, sourceOrigin.toString()) }
                    }
                }
            )
        }
        if (WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
            WebViewCompat.addDocumentStartJavaScript(webView, MEDIA_HOOK_JS, mutableSetOf("*"))
        }
    }

    private fun installServiceWorkerSniffer() {
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.SERVICE_WORKER_BASIC_USAGE)) return
        ServiceWorkerControllerCompat.getInstance().setServiceWorkerClient(object : ServiceWorkerClientCompat() {
            override fun shouldInterceptRequest(request: WebResourceRequest): WebResourceResponse? {
                mediaSniffer.observeServiceWorker(request)
                if (adBlockEnabled && AdBlocker.shouldBlock(request.url.toString(), mediaSniffer)) return emptyResponse()
                return null
            }
        })
    }

    private fun handlePopup(url: String) {
        val host = runCatching { URI(url).host.orEmpty().lowercase() }.getOrDefault("")
        val alwaysAllowed = prefs.getStringSet("popup_allow_hosts", emptySet()) ?: emptySet()
        if (!popupConfirmEnabled || host in alwaysAllowed) {
            webView.loadUrl(url)
            return
        }
        val remember = CheckBox(this).apply { text = "이 사이트의 팝업은 항상 허용" }
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(22), 0, dp(22), 0)
            addView(TextView(this@MainActivity).apply {
                text = host.ifBlank { url }
                textSize = 15f
                setTextColor(Color.DKGRAY)
                setPadding(0, 0, 0, dp(10))
            })
            addView(remember)
        }
        AlertDialog.Builder(this)
            .setTitle("새 창을 열려고 합니다")
            .setView(box)
            .setNegativeButton("차단", null)
            .setPositiveButton("열기") { _, _ ->
                if (remember.isChecked && host.isNotBlank()) {
                    prefs.edit().putStringSet("popup_allow_hosts", alwaysAllowed.toMutableSet().apply { add(host) }).apply()
                }
                webView.loadUrl(url)
            }
            .show()
    }

    private fun showDetectedMedia() {
        val list = mediaSniffer.snapshot()
        if (list.isEmpty()) {
            Toast.makeText(this, "아직 감지된 동영상이 없습니다", Toast.LENGTH_SHORT).show()
            return
        }
        val labels = list.mapIndexed { index, c ->
            val tail = runCatching { URI(c.url).path.substringAfterLast('/').take(40) }.getOrDefault("")
            "${index + 1}. ${c.displayName()}${if (tail.isNotBlank()) "\n$tail" else ""}"
        }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("감지된 미디어 ${list.size}개")
            .setItems(labels) { _, which -> confirmDownload(list[which]) }
            .setNeutralButton("목록 초기화") { _, _ -> mediaSniffer.clear() }
            .setNegativeButton("닫기", null)
            .show()
    }

    private fun confirmDownload(candidate: MediaCandidate) {
        if (candidate.type == MediaCandidate.MediaType.DASH) {
            AlertDialog.Builder(this)
                .setTitle("DASH 감지됨")
                .setMessage("이 스트림은 MPD까지 감지했습니다. 현재 빌드는 HLS와 직접 파일 저장을 우선 지원합니다.")
                .setPositiveButton("확인", null)
                .show()
            return
        }
        val input = EditText(this).apply {
            setSingleLine(true)
            hint = "파일 이름 (비워두면 자동)"
            setPadding(dp(12), dp(6), dp(12), dp(6))
        }
        AlertDialog.Builder(this)
            .setTitle(candidate.displayName())
            .setMessage(candidate.url.take(500))
            .setView(input)
            .setNegativeButton("취소", null)
            .setPositiveButton("다운로드") { _, _ -> startDownload(candidate, input.text.toString()) }
            .show()
    }

    private fun startDownload(candidate: MediaCandidate, requestedName: String) {
        val json = JSONObject().apply { candidate.headers.forEach { (k, v) -> put(k, v) } }.toString()
        val intent = Intent(this, DownloadService::class.java).apply {
            action = DownloadService.ACTION_DOWNLOAD
            putExtra(DownloadService.EXTRA_URL, candidate.url)
            putExtra(DownloadService.EXTRA_TYPE, candidate.type.name)
            putExtra(DownloadService.EXTRA_HEADERS, json)
            putExtra(DownloadService.EXTRA_FILENAME, requestedName)
        }
        startForegroundService(intent)
        Toast.makeText(this, "다운로드를 시작했습니다", Toast.LENGTH_SHORT).show()
    }

    private fun showDownloads(playOnTap: Boolean) {
        val items = mutableListOf<Pair<String, Uri>>()
        val projection = arrayOf(MediaStore.Video.Media._ID, MediaStore.Video.Media.DISPLAY_NAME)
        val selection = "${MediaStore.Video.Media.RELATIVE_PATH} LIKE ?"
        contentResolver.query(
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
            projection,
            selection,
            arrayOf("${android.os.Environment.DIRECTORY_MOVIES}/Adownloader%"),
            "${MediaStore.Video.Media.DATE_ADDED} DESC"
        )?.use { cursor ->
            val idCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media._ID)
            val nameCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DISPLAY_NAME)
            while (cursor.moveToNext()) {
                val id = cursor.getLong(idCol)
                val name = cursor.getString(nameCol)
                val uri = Uri.withAppendedPath(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, id.toString())
                items += name to uri
            }
        }
        if (items.isEmpty()) {
            Toast.makeText(this, "저장된 영상이 없습니다", Toast.LENGTH_SHORT).show()
            return
        }
        AlertDialog.Builder(this)
            .setTitle(if (playOnTap) "플레이어" else "다운로드")
            .setItems(items.map { it.first }.toTypedArray()) { _, which ->
                val uri = items[which].second
                if (playOnTap) {
                    startActivity(Intent(this, PlayerActivity::class.java).setData(uri))
                } else {
                    val share = Intent(Intent.ACTION_VIEW).apply {
                        setDataAndType(uri, "video/*")
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                    startActivity(Intent.createChooser(share, "영상 열기"))
                }
            }
            .setNegativeButton("닫기", null)
            .show()
    }

    private fun showSettings() {
        val labels = arrayOf(
            "광고/트래커 차단",
            "팝업 열기 전 확인",
            "기본 WebView User-Agent",
            "Chrome 모바일 User-Agent",
            "Samsung Internet User-Agent",
            "PC Chrome User-Agent"
        )
        val ua = prefs.getString("ua_mode", "default") ?: "default"
        val checked = booleanArrayOf(
            adBlockEnabled,
            popupConfirmEnabled,
            ua == "default",
            ua == "chrome",
            ua == "samsung",
            ua == "desktop"
        )
        AlertDialog.Builder(this)
            .setTitle("설정")
            .setMultiChoiceItems(labels, checked) { dialog, which, isChecked ->
                when (which) {
                    0 -> adBlockEnabled = isChecked
                    1 -> popupConfirmEnabled = isChecked
                    in 2..5 -> {
                        if (!isChecked) return@setMultiChoiceItems
                        val mode = when (which) { 3 -> "chrome"; 4 -> "samsung"; 5 -> "desktop"; else -> "default" }
                        prefs.edit().putString("ua_mode", mode).apply()
                        applyUserAgent(mode)
                        for (i in 2..5) (dialog as AlertDialog).listView.setItemChecked(i, i == which)
                    }
                }
            }
            .setPositiveButton("완료") { _, _ -> webView.reload() }
            .show()
    }

    private fun showBrowserMenu() {
        val items = arrayOf("새로고침", "감지 목록", "외부 브라우저로 열기", "팝업 허용 목록 초기화")
        AlertDialog.Builder(this)
            .setItems(items) { _, which ->
                when (which) {
                    0 -> webView.reload()
                    1 -> showDetectedMedia()
                    2 -> webView.url?.let { openExternal(Uri.parse(it)) }
                    3 -> prefs.edit().remove("popup_allow_hosts").apply()
                }
            }
            .show()
    }

    private fun applyUserAgent(mode: String) {
        val defaultUa = android.webkit.WebSettings.getDefaultUserAgent(this)
        webView.settings.userAgentString = when (mode) {
            "chrome" -> defaultUa.replace("; wv", "").replace(Regex("Version/4\\.0\\s+"), "")
            "samsung" -> "Mozilla/5.0 (Linux; Android 17; SM-F971N) AppleWebKit/537.36 (KHTML, like Gecko) SamsungBrowser/29.0 Chrome/140.0 Mobile Safari/537.36"
            "desktop" -> "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/140.0 Safari/537.36"
            else -> defaultUa
        }
    }

    private fun loadTypedAddress() {
        val raw = address.text.toString().trim()
        if (raw.isBlank()) return
        val url = when {
            raw.startsWith("http://") || raw.startsWith("https://") -> raw
            raw.contains('.') && !raw.contains(' ') -> "https://$raw"
            else -> "https://www.google.com/search?q=" + Uri.encode(raw)
        }
        webView.loadUrl(url)
        address.clearFocus()
    }

    private fun showLoadError(url: String, errorCode: Int, detail: String) {
        val reason = when (errorCode) {
            WebViewClient.ERROR_HOST_LOOKUP -> "DNS 조회 실패"
            WebViewClient.ERROR_TIMEOUT -> "연결 시간 초과"
            WebViewClient.ERROR_CONNECT -> "서버 연결 실패"
            WebViewClient.ERROR_FAILED_SSL_HANDSHAKE -> "SSL 연결 실패"
            WebViewClient.ERROR_UNKNOWN -> "알 수 없는 네트워크 오류"
            else -> detail
        }
        AlertDialog.Builder(this)
            .setTitle("페이지를 열 수 없습니다")
            .setMessage("$reason\n\n${runCatching { URI(url).host ?: url }.getOrDefault(url)}")
            .setNegativeButton("닫기", null)
            .setNeutralButton("외부 브라우저") { _, _ -> openExternal(Uri.parse(url)) }
            .setPositiveButton("다시 시도") { _, _ -> webView.loadUrl(url) }
            .show()
    }

    private fun openExternal(uri: Uri): Boolean {
        return try {
            startActivity(Intent.createChooser(Intent(Intent.ACTION_VIEW, uri), "브라우저 선택"))
            true
        } catch (_: Throwable) {
            false
        }
    }

    private fun emptyResponse() = WebResourceResponse("text/plain", "utf-8", ByteArrayInputStream(ByteArray(0)))

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 701)
        }
    }

    private fun applyInsets() {
        if (Build.VERSION.SDK_INT < 30) return
        root.setOnApplyWindowInsetsListener { _, insets ->
            val status = insets.getInsets(WindowInsets.Type.statusBars())
            val nav = insets.getInsets(WindowInsets.Type.navigationBars())
            root.setPadding(0, status.top, 0, nav.bottom)
            insets
        }
        root.requestApplyInsets()
    }

    private fun navButton(textValue: String, action: () -> Unit) = Button(this).apply {
        text = textValue
        textSize = 22f
        minWidth = 0
        minimumWidth = 0
        minHeight = 0
        minimumHeight = 0
        setPadding(0, 0, 0, 0)
        setBackgroundColor(Color.TRANSPARENT)
        setOnClickListener { action() }
        layoutParams = LinearLayout.LayoutParams(dp(38), dp(42))
    }

    private fun bottomItem(icon: String, label: String, action: () -> Unit): View {
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setOnClickListener { action() }
        }
        container.addView(TextView(this).apply {
            text = icon
            textSize = 23f
            gravity = Gravity.CENTER
            setTextColor(Color.rgb(70, 70, 75))
        })
        container.addView(TextView(this).apply {
            text = label
            textSize = 11f
            gravity = Gravity.CENTER
            setTextColor(Color.rgb(75, 75, 80))
        })
        container.layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f)
        return container
    }

    private fun updateMediaButton(count: Int) {
        mediaButton.visibility = if (count > 0) View.VISIBLE else View.GONE
        mediaButton.text = if (count > 99) "99+" else count.toString()
    }

    private fun circle(color: Int) = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(color) }
    private fun rounded(color: Int, radius: Float) = GradientDrawable().apply { setColor(color); cornerRadius = radius }
    private fun dp(v: Int): Int = (v * resources.displayMetrics.density + 0.5f).toInt()

    companion object {
        private val MEDIA_HOOK_JS = """
            (() => {
              if (window.__ADOWNLOADER_HOOKED__) return;
              window.__ADOWNLOADER_HOOKED__ = true;
              const send = (url, kind) => {
                try {
                  if (!url) return;
                  const s = String(url);
                  if (!(s.startsWith('http://') || s.startsWith('https://') || s.startsWith('blob:'))) return;
                  if (window.AdownloaderBridge && AdownloaderBridge.postMessage) {
                    AdownloaderBridge.postMessage(JSON.stringify({url:s, kind:kind, page:location.href}));
                  }
                } catch (_) {}
              };
              try {
                const nativeFetch = window.fetch;
                window.fetch = function(input, init) {
                  try { send(input && input.url ? input.url : input, 'fetch'); } catch (_) {}
                  return nativeFetch.apply(this, arguments).then(r => { try { send(r.url, 'fetch-response'); } catch (_) {} return r; });
                };
              } catch (_) {}
              try {
                const nativeOpen = XMLHttpRequest.prototype.open;
                XMLHttpRequest.prototype.open = function(method, url) { send(url, 'xhr'); return nativeOpen.apply(this, arguments); };
              } catch (_) {}
              try {
                const nativeCreate = URL.createObjectURL;
                URL.createObjectURL = function(obj) { const u = nativeCreate.apply(this, arguments); send(u, 'blob'); return u; };
              } catch (_) {}
              const inspect = el => {
                try {
                  if (!el) return;
                  if (el.currentSrc) send(el.currentSrc, 'currentSrc');
                  if (el.src) send(el.src, 'src');
                  if (el.querySelectorAll) el.querySelectorAll('source[src],video[src],audio[src]').forEach(x => send(x.src, 'dom'));
                } catch (_) {}
              };
              document.addEventListener('play', e => inspect(e.target), true);
              document.addEventListener('loadedmetadata', e => inspect(e.target), true);
              const scan = () => { try { document.querySelectorAll('video,audio,source').forEach(inspect); } catch (_) {} };
              if (document.readyState === 'loading') document.addEventListener('DOMContentLoaded', scan, {once:true}); else scan();
              try { new MutationObserver(scan).observe(document.documentElement, {subtree:true, childList:true, attributes:true, attributeFilter:['src']}); } catch (_) {}
            })();
        """.trimIndent()
    }
}
