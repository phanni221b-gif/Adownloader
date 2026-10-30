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
import android.webkit.JavascriptInterface
import android.webkit.RenderProcessGoneDetail
import android.webkit.ServiceWorkerClient
import android.webkit.ServiceWorkerController
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.net.URI

/**
 * Browser shell intentionally uses only public android.webkit APIs.
 *
 * Media detection remains multi-path:
 * 1) WebViewClient network requests
 * 2) platform ServiceWorkerClient requests
 * 3) JS fetch/XHR/DOM/currentSrc hooks
 * 4) PerformanceResourceTiming back-scan for requests made before JS injection
 * 5) DownloadListener
 *
 * A renderer crash is never auto-restarted in a loop. The user explicitly restarts
 * the browser surface, preventing Android 16/17 Crash Recovery from seeing a tight
 * WebView crash/recreate loop.
 */
class StableBrowserActivity : Activity() {
    private lateinit var webView: WebView
    private lateinit var address: EditText
    private lateinit var mediaButton: TextView
    private lateinit var root: LinearLayout
    private lateinit var body: FrameLayout
    private lateinit var toolbar: LinearLayout
    private lateinit var bottomBar: LinearLayout
    private lateinit var mediaSniffer: MediaSniffer
    private var webViewAlive = false

    private val prefs by lazy { getSharedPreferences("adownloader", MODE_PRIVATE) }
    private var adBlockEnabled: Boolean
        get() = prefs.getBoolean("ad_block", false)
        set(value) = prefs.edit().putBoolean("ad_block", value).apply()
    private var popupConfirmEnabled: Boolean
        get() = prefs.getBoolean("popup_confirm", true)
        set(value) = prefs.edit().putBoolean("popup_confirm", value).apply()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= 30) window.setDecorFitsSystemWindows(false)
        window.statusBarColor = Color.WHITE
        window.navigationBarColor = Color.WHITE
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR

        mediaSniffer = MediaSniffer { updateMediaButton(it.size) }
        buildUi()
        createBrowserWebView()
        installServiceWorkerSniffer()
        applyInsets()
        requestNotificationPermissionIfNeeded()

        // Do not restore a possibly crashing WebView renderer/page state after a process failure.
        // Browser history is deliberately kept only while this Activity instance is alive.
        webView.loadUrl("https://www.google.com")
    }

    override fun onDestroy() {
        destroyBrowserWebView()
        super.onDestroy()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (webViewAlive && webView.canGoBack()) webView.goBack() else super.onBackPressed()
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

        toolbar.addView(navButton("‹") { if (webViewAlive && webView.canGoBack()) webView.goBack() })
        toolbar.addView(navButton("›") { if (webViewAlive && webView.canGoForward()) webView.goForward() })
        toolbar.addView(navButton("↻") { if (webViewAlive) webView.reload() })

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
        bottomBar.addView(bottomItem("◎", "브라우저") {
            if (webViewAlive) webView.visibility = View.VISIBLE else showBrowserCrashedPanel(null)
        })
        bottomBar.addView(bottomItem("⇩", "다운로드") { showDownloads(false) })
        bottomBar.addView(bottomItem("▷", "플레이어") { showDownloads(true) })
        bottomBar.addView(bottomItem("⚙", "설정") { showSettings() })

        root.addView(toolbar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(54)))
        root.addView(body, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        root.addView(bottomBar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(64)))
        setContentView(root)
    }

    private fun createBrowserWebView() {
        if (webViewAlive) return
        webView = WebView(this)
        webViewAlive = true
        body.addView(webView, 0, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        configureWebView(webView)
    }

    private fun configureWebView(target: WebView) {
        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(target, true)

        target.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            mediaPlaybackRequiresUserGesture = false
            javaScriptCanOpenWindowsAutomatically = true
            setSupportMultipleWindows(true)
            loadsImagesAutomatically = true
            allowContentAccess = true
            allowFileAccess = false
            mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
            builtInZoomControls = true
            displayZoomControls = false
        }
        applyUserAgent(target, prefs.getString("ua_mode", "default") ?: "default")
        target.addJavascriptInterface(StableJsBridge(), "AdownloaderBridge")

        target.webViewClient = object : WebViewClient() {
            override fun onPageStarted(view: WebView?, url: String?, favicon: android.graphics.Bitmap?) {
                if (!url.isNullOrBlank()) {
                    address.setText(url)
                    mediaSniffer.clear()
                }
                // Deliberately do not evaluate JavaScript while the document is only provisional.
                // shouldInterceptRequest already sees the early requests.
            }

            override fun onPageCommitVisible(view: WebView?, url: String?) {
                injectMediaHook(view)
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                if (!url.isNullOrBlank()) address.setText(url)
                injectMediaHook(view)
            }

            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest): Boolean {
                val uri = request.url
                val scheme = uri.scheme?.lowercase()
                if (scheme == "http" || scheme == "https") return false
                return openExternal(uri)
            }

            override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest): WebResourceResponse? {
                mediaSniffer.observeRequest(request, view?.url)
                if (adBlockEnabled && AdBlocker.shouldBlock(request.url.toString(), mediaSniffer)) return emptyResponse()
                return null
            }

            override fun onReceivedError(view: WebView?, request: WebResourceRequest, error: WebResourceError) {
                if (!request.isForMainFrame) return
                runOnUiThread { showLoadError(request.url.toString(), error.errorCode, error.description.toString()) }
            }

            override fun onRenderProcessGone(view: WebView?, detail: RenderProcessGoneDetail): Boolean {
                val lastUrl = view?.url
                runOnUiThread {
                    // Never immediately instantiate another WebView here. A bad provider/page would
                    // otherwise create a renderer crash loop and trigger Android Crash Recovery.
                    destroyBrowserWebView()
                    showBrowserCrashedPanel(lastUrl)
                }
                return true
            }
        }

        target.webChromeClient = object : WebChromeClient() {
            private var customView: View? = null
            private var customCallback: CustomViewCallback? = null

            override fun onCreateWindow(view: WebView?, isDialog: Boolean, isUserGesture: Boolean, resultMsg: android.os.Message): Boolean {
                val popup = WebView(this@StableBrowserActivity)
                popup.settings.javaScriptEnabled = true
                popup.webViewClient = object : WebViewClient() {
                    private var handled = false
                    private fun handle(url: String): Boolean {
                        if (handled) return true
                        handled = true
                        popup.stopLoading()
                        popup.post { runCatching { popup.destroy() } }
                        handlePopup(url)
                        return true
                    }
                    override fun shouldOverrideUrlLoading(v: WebView?, request: WebResourceRequest): Boolean = handle(request.url.toString())
                    override fun onPageStarted(v: WebView?, url: String?, favicon: android.graphics.Bitmap?) {
                        if (!url.isNullOrBlank() && url != "about:blank") handle(url)
                    }
                }
                val transport = resultMsg.obj as? WebView.WebViewTransport ?: return false
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
                target.visibility = View.GONE
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
                if (webViewAlive) target.visibility = View.VISIBLE
            }
        }

        target.setDownloadListener { url, _, _, mimetype, _ ->
            mediaSniffer.observeDownload(url, mimetype, target.url)
            showDetectedMedia()
        }
    }

    private fun installServiceWorkerSniffer() {
        runCatching {
            ServiceWorkerController.getInstance().setServiceWorkerClient(object : ServiceWorkerClient() {
                override fun shouldInterceptRequest(request: WebResourceRequest): WebResourceResponse? {
                    mediaSniffer.observeServiceWorker(request)
                    if (adBlockEnabled && AdBlocker.shouldBlock(request.url.toString(), mediaSniffer)) return emptyResponse()
                    return null
                }
            })
        }
    }

    private inner class StableJsBridge {
        @JavascriptInterface
        fun postMessage(message: String?) {
            if (message.isNullOrBlank()) return
            val current = if (webViewAlive) webView.url else null
            runOnUiThread { mediaSniffer.observeJs(message, current) }
        }

        @JavascriptInterface
        fun onPopup(url: String?) {
            val value = url?.trim().orEmpty()
            if (value.startsWith("http://") || value.startsWith("https://")) {
                runOnUiThread { handlePopup(value) }
            }
        }
    }

    private fun injectMediaHook(view: WebView?) {
        val target = view ?: return
        if (!webViewAlive) return
        target.post { runCatching { target.evaluateJavascript(MEDIA_HOOK_JS, null) } }
    }

    private fun destroyBrowserWebView() {
        if (!webViewAlive || !::webView.isInitialized) return
        webViewAlive = false
        runCatching {
            body.removeView(webView)
            webView.stopLoading()
            webView.removeJavascriptInterface("AdownloaderBridge")
            webView.webChromeClient = null
            webView.webViewClient = WebViewClient()
            webView.removeAllViews()
            webView.destroy()
        }
    }

    private fun showBrowserCrashedPanel(lastUrl: String?) {
        body.removeViews(0, body.childCount)
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(30), dp(30), dp(30), dp(30))
            addView(TextView(this@StableBrowserActivity).apply {
                text = "웹 화면이 종료되었습니다\n자동 재시작하지 않아 충돌 반복을 막았습니다."
                textSize = 17f
                gravity = Gravity.CENTER
                setTextColor(Color.DKGRAY)
            })
            addView(Button(this@StableBrowserActivity).apply {
                text = "브라우저 다시 시작"
                setOnClickListener {
                    body.removeAllViews()
                    body.addView(mediaButton, FrameLayout.LayoutParams(dp(58), dp(58), Gravity.END or Gravity.BOTTOM).apply {
                        marginEnd = dp(18); bottomMargin = dp(18)
                    })
                    createBrowserWebView()
                    webView.loadUrl(lastUrl?.takeIf { it.startsWith("http://") || it.startsWith("https://") } ?: "https://www.google.com")
                }
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(18) })
        }
        body.addView(panel, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
    }

    private fun handlePopup(url: String) {
        if (!webViewAlive) return
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
            addView(TextView(this@StableBrowserActivity).apply {
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
                if (webViewAlive) webView.loadUrl(url)
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
                .setMessage("MPD까지 감지했습니다. 현재 다운로드 엔진은 HLS와 직접 파일 저장을 우선 지원합니다.")
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
                items += name to Uri.withAppendedPath(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, id.toString())
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
                    startActivity(Intent.createChooser(Intent(Intent.ACTION_VIEW).apply {
                        setDataAndType(uri, "video/*")
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }, "영상 열기"))
                }
            }
            .setNegativeButton("닫기", null)
            .show()
    }

    private fun showSettings() {
        val labels = arrayOf(
            "내장 광고 차단 (유니콘/NordVPN 사용 시 끄기 권장)",
            "팝업 열기 전 확인",
            "기본 WebView User-Agent",
            "Chrome 모바일 User-Agent",
            "Samsung Internet User-Agent",
            "PC Chrome User-Agent"
        )
        val ua = prefs.getString("ua_mode", "default") ?: "default"
        val checked = booleanArrayOf(adBlockEnabled, popupConfirmEnabled, ua == "default", ua == "chrome", ua == "samsung", ua == "desktop")
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
                        if (webViewAlive) applyUserAgent(webView, mode)
                        for (i in 2..5) (dialog as AlertDialog).listView.setItemChecked(i, i == which)
                    }
                }
            }
            .setNeutralButton("WebView 정보") { _, _ -> showWebViewInfo() }
            .setPositiveButton("완료") { _, _ -> if (webViewAlive) webView.reload() }
            .show()
    }

    private fun showWebViewInfo() {
        val pkg = runCatching { WebView.getCurrentWebViewPackage() }.getOrNull()
        val text = buildString {
            append("Provider: ").append(pkg?.packageName ?: "확인 불가").append('\n')
            append("Version: ").append(pkg?.versionName ?: "확인 불가").append('\n')
            append("Android: ").append(Build.VERSION.RELEASE).append(" (API ").append(Build.VERSION.SDK_INT).append(")\n")
            append("Device: ").append(Build.MANUFACTURER).append(' ').append(Build.MODEL).append('\n')
            append("Hardware acceleration: ON\n")
            append("Detection: network + service worker + JS + performance + download listener")
        }
        AlertDialog.Builder(this).setTitle("WebView 진단").setMessage(text).setPositiveButton("확인", null).show()
    }

    private fun showBrowserMenu() {
        val items = arrayOf("새로고침", "감지 목록", "WebView 정보", "외부 브라우저로 열기", "팝업 허용 목록 초기화")
        AlertDialog.Builder(this).setItems(items) { _, which ->
            when (which) {
                0 -> if (webViewAlive) webView.reload()
                1 -> showDetectedMedia()
                2 -> showWebViewInfo()
                3 -> if (webViewAlive) webView.url?.let { openExternal(Uri.parse(it)) }
                4 -> prefs.edit().remove("popup_allow_hosts").apply()
            }
        }.show()
    }

    private fun applyUserAgent(target: WebView, mode: String) {
        val defaultUa = WebSettings.getDefaultUserAgent(this)
        target.settings.userAgentString = when (mode) {
            "chrome" -> defaultUa.replace("; wv", "").replace(Regex("Version/4\\.0\\s+"), "")
            "samsung" -> "Mozilla/5.0 (Linux; Android 17; SM-F971N) AppleWebKit/537.36 (KHTML, like Gecko) SamsungBrowser/29.0 Chrome/140.0 Mobile Safari/537.36"
            "desktop" -> "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/140.0 Safari/537.36"
            else -> defaultUa
        }
    }

    private fun loadTypedAddress() {
        if (!webViewAlive) {
            Toast.makeText(this, "먼저 브라우저를 다시 시작하세요", Toast.LENGTH_SHORT).show()
            return
        }
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
            .setPositiveButton("다시 시도") { _, _ -> if (webViewAlive) webView.loadUrl(url) }
            .show()
    }

    private fun openExternal(uri: Uri): Boolean = try {
        startActivity(Intent.createChooser(Intent(Intent.ACTION_VIEW, uri), "브라우저 선택")); true
    } catch (_: Throwable) { false }

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
        minWidth = 0; minimumWidth = 0; minHeight = 0; minimumHeight = 0
        setPadding(0, 0, 0, 0)
        setBackgroundColor(Color.TRANSPARENT)
        setOnClickListener { action() }
        layoutParams = LinearLayout.LayoutParams(dp(38), dp(42))
    }

    private fun bottomItem(icon: String, label: String, action: () -> Unit): View = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER
        setOnClickListener { action() }
        addView(TextView(this@StableBrowserActivity).apply {
            text = icon; textSize = 23f; gravity = Gravity.CENTER; setTextColor(Color.rgb(70, 70, 75))
        })
        addView(TextView(this@StableBrowserActivity).apply {
            text = label; textSize = 11f; gravity = Gravity.CENTER; setTextColor(Color.rgb(75, 75, 80))
        })
        layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f)
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
              if (window.__ADOWNLOADER_HOOKED__) {
                try {
                  performance.getEntriesByType('resource').forEach(e => {
                    if (e && e.name) AdownloaderBridge.postMessage(JSON.stringify({url:String(e.name),kind:'performance-rescan',page:location.href}));
                  });
                } catch (_) {}
                return;
              }
              window.__ADOWNLOADER_HOOKED__ = true;
              const send = (url, kind) => {
                try {
                  if (!url) return;
                  const s = String(url);
                  if (!(s.startsWith('http://') || s.startsWith('https://') || s.startsWith('blob:'))) return;
                  if (window.AdownloaderBridge && AdownloaderBridge.postMessage) {
                    AdownloaderBridge.postMessage(JSON.stringify({url:s,kind:kind,page:location.href}));
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
              try {
                const realWindowOpen = window.open;
                window.open = function(url) {
                  try {
                    const s = String(url || '');
                    if (s.startsWith('http://') || s.startsWith('https://')) {
                      AdownloaderBridge.onPopup(s);
                      return null;
                    }
                  } catch (_) {}
                  return realWindowOpen.apply(this, arguments);
                };
              } catch (_) {}
              const inspect = el => {
                try {
                  if (!el) return;
                  if (el.currentSrc) send(el.currentSrc, 'currentSrc');
                  if (el.src) send(el.src, 'src');
                  if (el.querySelectorAll) el.querySelectorAll('source[src],video[src],audio[src]').forEach(x => send(x.currentSrc || x.src, 'dom'));
                } catch (_) {}
              };
              document.addEventListener('play', e => inspect(e.target), true);
              document.addEventListener('loadedmetadata', e => inspect(e.target), true);
              const scan = () => {
                try { document.querySelectorAll('video,audio,source').forEach(inspect); } catch (_) {}
                try { performance.getEntriesByType('resource').forEach(e => send(e.name, 'performance')); } catch (_) {}
              };
              scan();
              try { new MutationObserver(scan).observe(document.documentElement, {subtree:true,childList:true,attributes:true,attributeFilter:['src']}); } catch (_) {}
            })();
        """.trimIndent()
    }
}
