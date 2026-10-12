package com.ashraf.novelstudio

import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import org.json.JSONObject
import org.json.JSONTokener
import java.io.ByteArrayInputStream

class MainActivity : Activity() {
    private lateinit var addressBar: EditText
    private lateinit var browserPanel: LinearLayout
    private lateinit var chatPanel: LinearLayout
    private lateinit var browserHost: FrameHost
    private lateinit var chatbot: WebView
    private lateinit var splitBar: SeekBar
    private lateinit var tabBar: LinearLayout
    private lateinit var statusText: TextView
    private lateinit var splitDivider: LinearLayout

    private val tabs = mutableListOf<BrowserTab>()
    private var activeTab = 0
    private var splitEnabled = true
    private var splitRatio = 62
    private var adBlockEnabled = true
    private var activeProvider = "ChatGPT"
    private val core = NovelStudioCore()

    private var translationSendJobId: Long? = null
    private var translationPollJobId: Long? = null
    private var lastInsertedJobId: Long? = null
    private var translationBaselineJobId: Long? = null
    private var translationBaseline = ""
    private var translationBaselineCount = 0
    private var lastPolledCandidate = ""
    private var stableCandidatePolls = 0

    private var autoWorkflowEnabled = true
    private var autoTranslateOnNext = false
    private var autoExtractUrl = ""
    private var nextPending = false
    private var nextOriginUrl = ""
    private var nextPendingWebView: WebView? = null
    private var nextWatchToken = 0

    private val providerUrls = linkedMapOf(
        "ChatGPT" to "https://chatgpt.com/",
        "Gemini" to "https://gemini.google.com/app",
        "Claude" to "https://claude.ai/new",
        "DeepSeek" to "https://chat.deepseek.com/",
        "Grok" to "https://grok.com/"
    )

    private val quickSites = listOf(
        SiteShortcut("WTR-LAB", "https://wtr-lab.com/en"),
        SiteShortcut("Webnovel", "https://www.webnovel.com/"),
        SiteShortcut("Qidian", "https://m.qidian.com/"),
        SiteShortcut("NovelBin", "https://novelbin.com/"),
        SiteShortcut("BoxNovel", "https://boxnovel.com/"),
        SiteShortcut("AsianNovel", "https://www.asiannovel.net/")
    )

    private inner class NovelBridge(private val sourceView: WebView) {
        @JavascriptInterface
        fun chapterPayload(json: String) {
            runOnUiThread {
                if (sourceView !== activeWebView()) return@runOnUiThread
                runCatching {
                    val obj = JSONObject(json)
                    val url = obj.optString("url")
                    val title = obj.optString("title")
                    val text = obj.optString("text").trim()
                    if (text.length < 80) {
                        autoTranslateOnNext = false
                        setStatus("Chapter text পাওয়া যায়নি · অন্য selector দরকার হতে পারে")
                        toast("Chapter text পাওয়া যায়নি")
                        return@runOnUiThread
                    }
                    val previous = core.currentJob
                    if (previous != null && previous.chapterUrl == url &&
                        previous.extractedText == text && previous.status != NovelStudioCore.Status.INVALIDATED
                    ) {
                        setStatus("এই chapter আগে থেকেই extracted · " + text.length + " characters")
                        toast("এই chapter আগেই extract করা আছে")
                        return@runOnUiThread
                    }
                    val job = core.beginJob(url)
                    core.setExtracted(job.id, title, text)
                    setStatus("EXTRACTED · " + text.length + " characters · ready to translate")
                    toast("Chapter extracted")
                    val shouldAutoTranslate = autoWorkflowEnabled || autoTranslateOnNext
                    autoTranslateOnNext = false
                    if (shouldAutoTranslate) {
                        sourceView.postDelayed({
                            if (sourceView === activeWebView() && core.isCurrentJob(job.id)) {
                                setStatus("AUTO · sending chapter to " + activeProvider)
                                startTranslation()
                            }
                        }, 700)
                    }
                }
            }
        }

        @JavascriptInterface
        fun routeChanged(url: String) {
            runOnUiThread {
                val tab = tabs.firstOrNull { it.webView === sourceView }
                val previous = tab?.lastUrl.orEmpty()
                if (previous != url) {
                    tab?.lastUrl = url
                    installPageHooks(sourceView)
                    if (sourceView === activeWebView()) {
                        core.invalidateCurrentJob()
                        resetTranslationTracking()
                        addressBar.setText(url)
                        setStatus("Chapter route changed · refreshing page hooks")
                        if (nextPending && nextPendingWebView === sourceView && url != nextOriginUrl) {
                            scheduleNextChapterExtraction(sourceView)
                        } else if (!nextPending) {
                            autoTranslateOnNext = false
                        }
                    }
                }
            }
        }
    }

    private val aiBridge = object {
        @JavascriptInterface
        fun translationResult(text: String) {
            runOnUiThread {
                val job = core.currentJob ?: return@runOnUiThread
                if (!core.isCurrentJob(job.id) || lastInsertedJobId == job.id) return@runOnUiThread
                val cleaned = text.trim()
                if (cleaned.length < 40) return@runOnUiThread
                if (!core.markCompleted(job.id, cleaned)) return@runOnUiThread
                core.saveTranslation(job.chapterUrl, cleaned)
                lastInsertedJobId = job.id
                translationPollJobId = null
                translationBaselineJobId = null
                translationBaseline = ""
                val target = webViewForUrl(job.chapterUrl)
                if (target == null) {
                    setStatus("TRANSLATION READY · chapter tab বদলে গেছে; translation copy করা যাবে")
                    toast("অনুবাদ তৈরি হয়েছে, কিন্তু chapter tab খুঁজে পাওয়া যায়নি")
                } else {
                    injectTranslation(target, job.chapterUrl, cleaned)
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = Color.rgb(13, 17, 26)
        window.navigationBarColor = Color.rgb(13, 17, 26)
        WebView.setWebContentsDebuggingEnabled(BuildConfig.DEBUG)
        buildUi()
        createTab("https://www.google.com")
        intent?.getStringExtra("android.intent.extra.TEXT")?.takeIf { it.isNotBlank() }?.let { navigate(it) }
    }

    private fun buildUi() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(5), dp(12), dp(7))
            setBackgroundColor(CANVAS)
        }

        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(2), 0, dp(7))
        }
        val logo = TextView(this).apply {
            text = "N"
            textSize = 21f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            background = rounded(ACCENT, 13)
            layoutParams = LinearLayout.LayoutParams(dp(40), dp(40))
        }
        header.addView(logo)
        val titleBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(10), 0, 0, 0)
        }
        titleBox.addView(TextView(this).apply {
            text = "NovelStudio"
            textSize = 19f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(TEXT)
        })
        titleBox.addView(TextView(this).apply {
            text = "READ  ·  TRANSLATE  ·  CONTINUE"
            textSize = 9f
            letterSpacing = 0.08f
            setTextColor(MUTED)
        })
        header.addView(titleBox, LinearLayout.LayoutParams(0, -2, 1f))
        header.addView(pill("⋮", minWidth = 42) { showMoreMenu() })
        root.addView(header, matchWrap())

        val addressRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        addressBar = EditText(this).apply {
            hint = "Novel URL অথবা search"
            setSingleLine(true)
            textSize = 13f
            setTextColor(TEXT)
            setHintTextColor(MUTED)
            imeOptions = EditorInfo.IME_ACTION_GO
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_URI
            setPadding(dp(13), 0, dp(10), 0)
            background = rounded(SURFACE, 14, BORDER)
            setOnEditorActionListener { _, action, _ ->
                if (action == EditorInfo.IME_ACTION_GO || action == EditorInfo.IME_ACTION_DONE) {
                    navigate(text.toString())
                    true
                } else false
            }
        }
        addressRow.addView(addressBar, LinearLayout.LayoutParams(0, dp(44), 1f))
        addressRow.addView(pill("Open ↗", primary = true) { navigate(addressBar.text.toString()) }.apply {
            setPadding(dp(14), 0, dp(14), 0)
        }, LinearLayout.LayoutParams(-2, dp(44)).apply { leftMargin = dp(7) })
        root.addView(addressRow, matchWrap())

        // Site shortcuts are intentionally hidden from the reader workspace; use More → Site profiles.
        val navRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        navRow.addView(pill("‹", minWidth = 42) { activeWebView()?.let { if (it.canGoBack()) it.goBack() } }, weightButton())
        navRow.addView(pill("›", minWidth = 42) { activeWebView()?.let { if (it.canGoForward()) it.goForward() } }, weightButton())
        navRow.addView(pill("↻", minWidth = 42) { activeWebView()?.reload() }, weightButton())
        navRow.addView(pill("Tabs", selected = true) { showTabs() }, weightButton())
        navRow.addView(pill(if        val compactTools = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        compactTools.addView(pill("‹", minWidth = 38) { activeWebView()?.goBack() }, weightButton())
        compactTools.addView(pill("›", minWidth = 38) { activeWebView()?.goForward() }, weightButton())
        compactTools.addView(pill("↻", minWidth = 38) { activeWebView()?.reload() }, weightButton())
        compactTools.addView(pill("Tabs", minWidth = 58) { showTabs() }, weightButton())
        compactTools.addView(pill("Split", selected = splitEnabled, minWidth = 58) { toggleSplit() }, weightButton())
        root.addView(compactTools, LinearLayout.LayoutParams(-1, dp(32)).apply { bottomMargin = dp(2) })
        val workspace = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            clipChildren = false
        }

        browserPanel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = rounded(PANEL, 18, BORDER)
            clipToOutline = true
        }
        val readerHeader = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), 0, dp(8), 0)
        }
        readerHeader.addView(dot(ACCENT))
        readerHeader.addView(TextView(this).apply {
            text = "  NOVEL READER"
            textSize = 10f
            typeface = Typeface.DEFAULT_BOLD
            letterSpacing = 0.07f
            setTextColor(TEXT)
        }, LinearLayout.LayoutParams(0, dp(37), 1f))
        readerHeader.addView(pill("⛶", minWidth = 36) { toggleReaderFocus() })
        browserPanel.addView(readerHeader, matchWrap())
        browserHost = FrameHost(this)
        browserPanel.addView(browserHost, LinearLayout.LayoutParams(-1, 0, 1f))
        workspace.addView(browserPanel, LinearLayout.LayoutParams(-1, 0, splitRatio.toFloat()))

        splitDivider = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(4), 0, dp(4), 0)
        }
        splitDivider.addView(TextView(this).apply {
            text = "READER / AI"
            textSize = 8f
            letterSpacing = 0.08f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(MUTED)
        })
        splitBar = SeekBar(this).apply {
            max = 60
            progress = splitRatio - 20
            progressTintList = android.content.res.ColorStateList.valueOf(ACCENT)
            thumbTintList = android.content.res.ColorStateList.valueOf(ACCENT)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seek: SeekBar?, progress: Int, fromUser: Boolean) {
                    splitRatio = (progress + 20).coerceIn(20, 80)
                    updateSplitWeights()
                }
                override fun onStartTrackingTouch(seek: SeekBar?) = Unit
                override fun onStopTrackingTouch(seek: SeekBar?) = Unit
            })
        }
        splitDivider.addView(splitBar, LinearLayout.LayoutParams(0, dp(26), 1f))
        splitDivider.addView(TextView(this).apply {
            text = splitRatio.toString() + "%"
            textSize = 9f
            setTextColor(MUTED)
            gravity = Gravity.CENTER
        }.also { ratioLabel = it }, LinearLayout.LayoutParams(dp(34), -1))
        workspace.addView(splitDivider, LinearLayout.LayoutParams(-1, dp(28)))

        chatPanel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = rounded(PANEL, 18, BORDER)
            clipToOutline = true
        }
        val aiHeader = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), 0, dp(8), 0)
        }
        aiHeader.addView(dot(GREEN))
        aiHeader.addView(TextView(this).apply {
            text = "  AI TRANSLATOR"
            textSize = 10f
            typeface = Typeface.DEFAULT_BOLD
            letterSpacing = 0.07f
            setTextColor(TEXT)
        }, LinearLayout.LayoutParams(0, dp(35), 1f))
        aiHeader.addView(pill("Reload", minWidth = 54) { chatbot.reload() })
        chatPanel.addView(aiHeader, matchWrap())

        val providerScroll = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            setPadding(dp(9), dp(1), dp(9), dp(6))
        }
        providerRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        providerScroll.addView(providerRow)
        chatPanel.addView(providerScroll, matchWrap())

        chatbot = WebView(this).apply {
            configureWebView()
            addJavascriptInterface(aiBridge, "NovelStudioAI")
            webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView, url: String) {
                    setStatus("AI · " + activeProvider + " · ready")
                }
            }
        }
        chatPanel.addView(chatbot, LinearLayout.LayoutParams(-1, 0, 1f))
        workspace.addView(chatPanel, LinearLayout.LayoutParams(-1, 0, (100 - splitRatio).toFloat()))
        root.addView(workspace, LinearLayout.LayoutParams(-1, 0, 1f))

        buildProviderChips()
        chatbot.loadUrl(providerUrls[activeProvider]!!)
        updateSplitWeights()

        val actionsCard = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            background = rounded(SURFACE, 14, BORDER)
            setPadding(dp(3), dp(3), dp(3), dp(3))
        }
        actionsCard.addView(pill(if (autoWorkflowEnabled) "AUTO ON" else "AUTO OFF", primary = autoWorkflowEnabled, minHeight = 39) {
            autoWorkflowEnabled = !autoWorkflowEnabled
            toast(if (autoWorkflowEnabled) "AUTO: Extract → Send → Wait → Insert → Next" else "AUTO বন্ধ")
        }, LinearLayout.LayoutParams(0, dp(39), 1.1f).apply { rightMargin = dp(3) })
        actionsCard.addView(pill("Next ›", minHeight = 39) { nextChapter() }, LinearLayout.LayoutParams(0, dp(39), .9f).apply { rightMargin = dp(3) })
        actionsCard.addView(pill("Copy", minHeight = 39) { copyContent() }, LinearLayout.LayoutParams(0, dp(39), .75f))
        root.addView(actionsCard, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(7) })

        statusText = TextView(this).apply {
            text = "AUTO ON · Open a chapter → extract → send → translate → insert"
            textSize = 10f
            setTextColor(MUTED)
            gravity = Gravity.CENTER_VERTICAL
            maxLines = 2
            setPadding(dp(5), dp(6), dp(5), 0)
        }
        root.addView(statusText, matchWrap())
        setContentView(root)
    }

    private lateinit var providerRow: LinearLayout
    private lateinit var ratioLabel: TextView

    private fun buildProviderChips() {
        providerRow.removeAllViews()
        providerUrls.keys.forEach { provider ->
            val selected = activeProvider == provider
            providerRow.addView(pill(provider, selected = selected) {
                activeProvider = provider
                buildProviderChips()
                loadChatbot(providerUrls[provider]!!)
            }, LinearLayout.LayoutParams(-2, dp(31)).apply { rightMargin = dp(5) })
        }
    }

    private fun WebView.configureWebView() {
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        settings.databaseEnabled = true
        settings.loadsImagesAutomatically = true
        settings.javaScriptCanOpenWindowsAutomatically = true
        settings.setSupportMultipleWindows(false)
        settings.cacheMode = WebSettings.LOAD_DEFAULT
        settings.builtInZoomControls = true
        settings.displayZoomControls = false
        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
        webChromeClient = WebChromeClient()
        setBackgroundColor(Color.WHITE)
    }

    private fun createTab(url: String) {
        if (tabs.size >= 12) {
            toast("সর্বোচ্চ ১২টি tab রাখা যাবে")
            return
        }
        val view = WebView(this).apply {
            configureWebView()
            addJavascriptInterface(NovelBridge(this), "NovelStudio")
            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest) = false

                override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
                    if (adBlockEnabled && isAdRequest(request.url)) {
                        return WebResourceResponse("text/plain", "utf-8", ByteArrayInputStream(ByteArray(0)))
                    }
                    return super.shouldInterceptRequest(view, request)
                }

                override fun onPageStarted(view: WebView, url: String, favicon: android.graphics.Bitmap?) {
                    if (view === activeWebView()) setStatus("LOADING · " + Uri.parse(url).host.orEmpty())
                }

                override fun onPageFinished(view: WebView, url: String) {
                    val tab = tabs.firstOrNull { it.webView === view }
                    tab?.lastUrl = url
                    if (view === activeWebView()) addressBar.setText(url)
                    renderTabs()
                    installPageHooks(view)
                    if (nextPending && nextPendingWebView === view && url != nextOriginUrl) {
                        scheduleNextChapterExtraction(view)
                    } else if (autoWorkflowEnabled && view === activeWebView() && isLikelyChapterUrl(url) && autoExtractUrl != url) {
                        autoExtractUrl = url
                        view.postDelayed({
                            if (view === activeWebView() && view.url == url && core.currentJob?.chapterUrl != url) {
                                setStatus("AUTO · extracting chapter")
                                extractFrom(view)
                            }
                        }, 1200)
                    }
                }
            }
        }
        tabs.add(BrowserTab(view, url))
        activeTab = tabs.lastIndex
        browserHost.show(view)
        addressBar.setText(url)
        renderTabs()
        view.loadUrl(url)
    }

    private fun isAdRequest(uri: Uri): Boolean {
        val host = uri.host?.lowercase().orEmpty()
        val blocked = listOf(
            "doubleclick.net", "googlesyndication.com", "googleadservices.com",
            "adservice.google.com", "adnxs.com", "adform.net", "taboola.com",
            "outbrain.com", "popads.net", "popcash.net", "exoclick.com",
            "adsterra.com", "trafficjunky.net"
        )
        return blocked.any { host == it || host.endsWith("." + it) }
    }

    private fun navigate(raw: String) {
        val input = raw.trim()
        if (input.isEmpty()) return
        val url = when {
            input.startsWith("https://", true) || input.startsWith("http://", true) -> input
            input.contains(" ") || !input.contains(".") -> "https://www.google.com/search?q=" + Uri.encode(input)
            else -> "https://" + input
        }
        if (tabs.isEmpty()) createTab(url) else {
            core.invalidateCurrentJob()
            resetTranslationTracking()
            tabs[activeTab].lastUrl = url
            addressBar.setText(url)
            activeWebView()?.loadUrl(url)
        }
    }

    private fun installPageHooks(view: WebView) {
        val profile = SiteProfiles.forUrl(view.url.orEmpty())
        view.evaluateJavascript(ProviderHandlers.pageHookScript(profile.chapterSelectorsJson()), null)
    }

    private fun extractChapter() {
        val view = activeWebView() ?: return
        setStatus("EXTRACTING · detecting chapter content")
        view.evaluateJavascript("window.__NovelStudio&&window.__NovelStudio.extract();", null)
    }

    private fun startTranslation(force: Boolean = false) {
        val job = core.currentJob
        if (job == null || job.extractedText.isBlank()) {
            toast("আগে Extract চাপ")
            return
        }
        if (!force && job.status == NovelStudioCore.Status.COMPLETED) {
            toast("এই chapter অনুবাদ হয়েছে · আবার করতে Translate চেপে ধরে রাখ")
            return
        }
        if (!force && (translationSendJobId == job.id || core.translationRequested)) {
            toast("এই chapter ইতিমধ্যে AI-তে পাঠানো হয়েছে")
            return
        }
        if (force) {
            translationSendJobId = null
            translationPollJobId = null
            translationBaselineJobId = null
            translationBaseline = ""
            translationBaselineCount = 0
            lastPolledCandidate = ""
            stableCandidatePolls = 0
            lastInsertedJobId = null
        }
        if (!core.markTranslationRequested(job.id)) return
        translationSendJobId = job.id
        setStatus("SENDING · chapter পাঠানো হচ্ছে " + activeProvider + "-এ")
        injectTranslationPrompt()
    }

    private fun injectTranslationPrompt() {
        val job = core.currentJob ?: return
        if (translationSendJobId != job.id || job.extractedText.isBlank()) return
        val prompt = "Translate the following novel chapter into natural, fluent literary Bengali. Preserve meaning, names, titles, dialogue, paragraph structure, tone, emotions, humor and pacing. Do not summarize or omit anything. Output only the Bengali translation, with no introduction or explanation.\n\n" + job.extractedText
        chatbot.evaluateJavascript(ProviderHandlers.responseSnapshotScript(chatbot.url.orEmpty())) { raw ->
            if (!core.isCurrentJob(job.id) || !core.translationRequested) return@evaluateJavascript
            val baseline = parseSnapshot(raw)
            translationBaseline = baseline.text
            translationBaselineCount = baseline.count
            translationBaselineJobId = job.id
            lastPolledCandidate = ""
            stableCandidatePolls = 0
            chatbot.evaluateJavascript(ProviderHandlers.inputScript(chatbot.url.orEmpty(), prompt)) { sentRaw ->
                val sent = runCatching { JSONTokener(sentRaw).nextValue() as? Boolean }.getOrNull() ?: false
                if (!core.isCurrentJob(job.id) || !core.translationRequested) return@evaluateJavascript
                if (!sent) {
                    core.cancelTranslationRequest(job.id)
                    translationSendJobId = null
                    setStatus("AI INPUT NOT FOUND · " + activeProvider + " composer selector check কর")
                    toast("AI input box পাওয়া যায়নি")
                    return@evaluateJavascript
                }
                setStatus("TRANSLATING · AI response-এর জন্য অপেক্ষা")
                scheduleAiResponsePolling(job.id)
            }
        }
    }

    private fun scheduleAiResponsePolling(id: Long) {
        if (translationPollJobId == id) return
        translationPollJobId = id
        chatbot.postDelayed(object : Runnable {
            override fun run() {
                if (!core.isCurrentJob(id) || !core.translationRequested) {
                    if (translationPollJobId == id) translationPollJobId = null
                    return
                }
                chatbot.evaluateJavascript(ProviderHandlers.responseSnapshotScript(chatbot.url.orEmpty())) { raw ->
                    if (!core.isCurrentJob(id) || !core.translationRequested) return@evaluateJavascript
                    val snapshot = parseSnapshot(raw)
                    val hasNewResponse = translationBaselineJobId == id &&
                        snapshot.text.length >= 40 &&
                        snapshot.text != translationBaseline &&
                        snapshot.count > translationBaselineCount
                    if (hasNewResponse) {
                        if (snapshot.text == lastPolledCandidate && !snapshot.streaming) {
                            stableCandidatePolls += 1
                        } else {
                            lastPolledCandidate = snapshot.text
                            stableCandidatePolls = if (snapshot.streaming) 0 else 1
                        }
                        if (stableCandidatePolls >= 2 && !snapshot.streaming) {
                            aiBridge.translationResult(snapshot.text)
                            return@evaluateJavascript
                        }
                    } else {
                        lastPolledCandidate = ""
                        stableCandidatePolls = 0
                    }
                }
                if (core.isCurrentJob(id) && core.translationRequested) {
                    chatbot.postDelayed(this, 1800)
                } else if (translationPollJobId == id) {
                    translationPollJobId = null
                }
            }
        }, 2200)
    }

    private fun parseSnapshot(raw: String): ResponseSnapshot {
        return runCatching {
            val first = JSONTokener(raw).nextValue()
            val obj = if (first is JSONObject) first else JSONObject(first as? String ?: "{}")
            ResponseSnapshot(
                count = obj.optInt("count", 0),
                text = obj.optString("text", "").trim(),
                streaming = obj.optBoolean("streaming", false)
            )
        }.getOrElse { ResponseSnapshot(0, "", false) }
    }

    private fun injectTranslation(view: WebView, chapterUrl: String, text: String) {
        val escaped = JSONObject.quote(text)
        val selectors = SiteProfiles.forUrl(chapterUrl).chapterSelectorsJson()
        val script = """(function(){var t=$escaped,s=$selectors,b=null;for(var i=0;i<s.length&&!b;i++){try{var best=-1;document.querySelectorAll(s[i]).forEach(function(e){var x=(e.innerText||e.textContent||'').trim();if(x.length>200&&x.length<120000&&x.length>best){b=e;best=x.length;}})}catch(e){}}if(!b)b=document.querySelector('article,main,[role="main"]');if(!b)return false;var old=document.querySelector('[data-novelstudio-translation]');if(old)old.remove();var box=document.createElement('section');box.setAttribute('data-novelstudio-translation','true');box.style.cssText='display:block;white-space:pre-wrap;box-sizing:border-box;width:100%;margin:22px 0;padding:18px 16px;border:1px solid #8bb9ff;border-radius:14px;background:#eff6ff;color:#172033;font-size:1em;line-height:1.95;overflow-wrap:anywhere;';var h=document.createElement('div');h.textContent='বাংলা অনুবাদ · NovelStudio';h.style.cssText='font-size:.82em;font-weight:700;letter-spacing:.03em;color:#2458a6;padding-bottom:12px;margin-bottom:12px;border-bottom:1px solid #cbdcf7;';var body=document.createElement('div');body.textContent=t;body.style.whiteSpace='pre-wrap';box.appendChild(h);box.appendChild(body);b.insertAdjacentElement('afterend',box);box.scrollIntoView({behavior:'smooth',block:'start'});return true;})()"""
        view.evaluateJavascript(script) { result ->
            val inserted = result == "true"
            if (inserted) {
                setStatus("COMPLETED · translation chapter-এর নিচে বসানো হয়েছে")
                toast("বাংলা অনুবাদ chapter-এর নিচে বসানো হয়েছে")
            } else {
                setStatus("TRANSLATION READY · page-এ insertion target পাওয়া যায়নি")
                toast("অনুবাদ তৈরি হয়েছে, কিন্তু page-এ বসানোর জায়গা পাওয়া যায়নি")
            }
        }
    }

    private fun webViewForUrl(url: String): WebView? {
        return tabs.firstOrNull { it.webView.url == url || it.lastUrl == url }?.webView
            ?: activeWebView()?.takeIf { it.url == url }
    }

    private fun nextChapter() {
        val view = activeWebView() ?: return
        val profile = SiteProfiles.forUrl(view.url.orEmpty())
        autoTranslateOnNext = core.currentJob?.let {
            it.status == NovelStudioCore.Status.COMPLETED && it.translation.isNotBlank()
        } == true
        core.invalidateCurrentJob()
        resetTranslationTracking()
        nextPending = true
        nextOriginUrl = view.url.orEmpty()
        nextPendingWebView = view
        nextWatchToken += 1
        val token = nextWatchToken
        setStatus("NEXT CHAPTER · next link খোঁজা হচ্ছে")
        view.evaluateJavascript(ProviderHandlers.nextChapterScript(profile.nextSelectorsJson())) { raw ->
            if (raw == "true") {
                setStatus("NEXT CHAPTER · page change detect করছি")
                view.postDelayed({
                    if (nextPending && nextPendingWebView === view && nextWatchToken == token) {
                        nextPending = false
                        nextPendingWebView = null
                        extractFrom(view)
                    }
                }, 2800)
            } else {
                nextPending = false
                nextPendingWebView = null
                autoTranslateOnNext = false
                setStatus("NEXT NOT FOUND · এই site-এর selector/profile উন্নত করতে হবে")
                toast("Next chapter link পাওয়া যায়নি")
            }
        }
    }

    private fun scheduleNextChapterExtraction(view: WebView) {
        nextPending = false
        nextPendingWebView = null
        nextWatchToken += 1
        setStatus("NEW CHAPTER · content load হওয়ার অপেক্ষা")
        view.postDelayed({
            if (view === activeWebView()) extractFrom(view)
        }, 1100)
    }

    private fun isLikelyChapterUrl(url: String): Boolean {
        val u = url.lowercase()
        if (u.isBlank()) return false
        return u.contains("/chapter/") ||
            u.contains("/chapters/") ||
            u.contains("/chapter-") ||
            u.contains("/read/") ||
            u.contains("/novel/") && (u.contains("chapter") || u.count { it == '/' } >= 5) ||
            u.contains("qidian.com") && u.matches(Regex(".*\\d{6,}.*"))
    }

    private fun extractFrom(view: WebView) {
        if (view !== activeWebView()) return
        setStatus("NEW CHAPTER · automatic extraction চলছে")
        view.evaluateJavascript("window.__NovelStudio&&window.__NovelStudio.extract();", null)
    }

    private fun copyContent() {
        val job = core.currentJob
        val text = job?.translation?.takeIf { it.isNotBlank() } ?: job?.extractedText.orEmpty()
        if (text.isBlank()) {
            toast("আগে Extract কর")
            return
        }
        (getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
            .setPrimaryClip(ClipData.newPlainText("NovelStudio chapter", text))
        toast(if (job?.translation?.isNotBlank() == true) "বাংলা translation copied" else "Original chapter copied")
    }

    private fun confirmRetranslation() {
        val job = core.currentJob
        if (job == null || job.extractedText.isBlank()) {
            toast("আগে Extract চাপ")
            return
        }
        AlertDialog.Builder(this)
            .setTitle("আবার অনুবাদ করবে?")
            .setMessage("একই chapter আবার " + activeProvider + "-এ পাঠানো হবে।")
            .setPositiveButton("Translate again") { _, _ -> startTranslation(force = true) }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun resetTranslationTracking() {
        translationSendJobId = null
        translationPollJobId = null
        translationBaselineJobId = null
        translationBaseline = ""
        translationBaselineCount = 0
        lastPolledCandidate = ""
        stableCandidatePolls = 0
    }

    private fun showTabs() {
        val items = tabs.mapIndexed { i, tab ->
            (if (i == activeTab) "● " else "") + "Tab " + (i + 1) + " · " + (tab.webView.url ?: tab.lastUrl)
        }.toMutableList()
        items.add("＋ New tab")
        AlertDialog.Builder(this).setTitle("Open tabs").setItems(items.toTypedArray()) { _, which ->
            if (which == tabs.size) createTab("https://www.google.com") else selectTab(which)
        }.setNegativeButton("Close active tab") { _, _ -> closeActiveTab() }.show()
    }

    private fun selectTab(index: Int) {
        if (index !in tabs.indices) return
        activeTab = index
        browserHost.show(tabs[index].webView)
        addressBar.setText(tabs[index].webView.url ?: tabs[index].lastUrl)
        renderTabs()
        setStatus("TAB " + (index + 1) + " · " + Uri.parse(tabs[index].webView.url ?: tabs[index].lastUrl).host.orEmpty())
    }

    private fun closeActiveTab() = closeTabAt(activeTab)

    private fun closeTabAt(index: Int) {
        if (tabs.size <= 1) {
            toast("কমপক্ষে একটি tab খোলা থাকতে হবে")
            return
        }
        if (index !in tabs.indices) return
        val previousActive = activeTab
        tabs.removeAt(index).webView.destroy()
        activeTab = when {
            index < previousActive -> previousActive - 1
            index == previousActive -> (index - 1).coerceAtLeast(0)
            else -> previousActive
        }.coerceIn(0, tabs.lastIndex)
        browserHost.show(tabs[activeTab].webView)
        addressBar.setText(tabs[activeTab].webView.url ?: tabs[activeTab].lastUrl)
        renderTabs()
    }

    private fun renderTabs() {
        if (!::tabBar.isInitialized) return
        tabBar.removeAllViews()
        tabs.forEachIndexed { i, tab ->
            val host = Uri.parse(tab.webView.url ?: tab.lastUrl).host.orEmpty().removePrefix("www.")
            val chip = pill((if (i == activeTab) "● " else "") + (host.ifBlank { "Tab " + (i + 1) }),
                selected = i == activeTab) { selectTab(i) }
            chip.setOnLongClickListener {
                if (tabs.size > 1) AlertDialog.Builder(this)
                    .setMessage("এই tab বন্ধ করবে?")
                    .setPositiveButton("Close") { _, _ -> closeTabAt(i) }
                    .setNegativeButton("Cancel", null).show()
                true
            }
            tabBar.addView(chip, LinearLayout.LayoutParams(-2, dp(30)).apply { rightMargin = dp(5) })
        }
        tabBar.addView(pill("+") { createTab("https://www.google.com") }, LinearLayout.LayoutParams(dp(34), dp(30)))
    }

    private fun toggleSplit() {
        splitEnabled = !splitEnabled
        updateSplitWeights()
        setStatus(if (splitEnabled) "SPLIT VIEW · reader + AI" else "READER VIEW · AI panel hidden")
    }

    private fun toggleReaderFocus() {
        splitEnabled = !splitEnabled
        updateSplitWeights()
        setStatus(if (splitEnabled) "Split reader restored" else "Reader focus mode")
    }

    private fun updateSplitWeights() {
        if (!::browserPanel.isInitialized || !::chatPanel.isInitialized) return
        val browserParams = browserPanel.layoutParams as LinearLayout.LayoutParams
        browserParams.height = 0
        browserParams.weight = if (splitEnabled) splitRatio.toFloat() else 1f
        browserPanel.layoutParams = browserParams
        val chatParams = chatPanel.layoutParams as LinearLayout.LayoutParams
        chatParams.height = 0
        chatParams.weight = (100 - splitRatio).toFloat()
        chatPanel.layoutParams = chatParams
        if (::splitDivider.isInitialized) splitDivider.visibility = if (splitEnabled) View.VISIBLE else View.GONE
        chatPanel.visibility = if (splitEnabled) View.VISIBLE else View.GONE
        if (::ratioLabel.isInitialized) ratioLabel.text = splitRatio.toString() + "%"
    }

    private fun loadChatbot(url: String) {
        core.cancelTranslationRequest(core.currentJob?.id ?: -1L)
        resetTranslationTracking()
        chatbot.loadUrl(url)
        setStatus("AI PROVIDER · " + activeProvider)
    }

    private fun showMoreMenu() {
        val items = arrayOf(
            "Novel site profiles",
            "Ad blocker: " + if (adBlockEnabled) "ON" else "OFF",
            "Reload current AI",
            "About NovelStudio"
        )
        AlertDialog.Builder(this).setTitle("NovelStudio tools").setItems(items) { _, which ->
            when (which) {
                0 -> showSiteProfiles()
                1 -> {
                    adBlockEnabled = !adBlockEnabled
                    toast("Ad blocker " + (if (adBlockEnabled) "ON" else "OFF") + " · page reload কর")
                }
                2 -> chatbot.reload()
                3 -> AlertDialog.Builder(this).setTitle("NovelStudio Redesign")
                    .setMessage("Novel reader + AI translation workspace. Extract → Translate → insert under the original chapter → Next auto-extracts the next chapter. Site selectors vary by website.")
                    .setPositiveButton("OK", null).show()
            }
        }.show()
    }

    private fun showSiteProfiles() {
        val items = quickSites.map { it.label + "  ·  " + Uri.parse(it.url).host }.toTypedArray()
        AlertDialog.Builder(this).setTitle("Novel site profiles").setItems(items) { _, which ->
            navigate(quickSites[which].url)
        }.setPositiveButton("Custom URL") { _, _ -> showCustomSiteDialog() }.show()
    }

    private fun showCustomSiteDialog() {
        val input = EditText(this).apply {
            hint = "https://example.com"
            setTextColor(TEXT)
            setHintTextColor(MUTED)
            setPadding(dp(12), dp(9), dp(12), dp(9))
        }
        AlertDialog.Builder(this).setTitle("Open a novel site").setView(input)
            .setPositiveButton("Open") { _, _ -> navigate(input.text.toString()) }
            .setNegativeButton("Cancel", null).show()
    }

    private fun setStatus(message: String) {
        if (::statusText.isInitialized) statusText.text = message.uppercase()
    }

    private fun activeWebView(): WebView? = tabs.getOrNull(activeTab)?.webView

    override fun onBackPressed() {
        val view = activeWebView()
        if (view?.canGoBack() == true) view.goBack() else super.onBackPressed()
    }

    override fun onDestroy() {
        tabs.forEach { it.webView.destroy() }
        if (::chatbot.isInitialized) chatbot.destroy()
        super.onDestroy()
    }

    private fun pill(
        text: String,
        primary: Boolean = false,
        selected: Boolean = false,
        minWidth: Int = 0,
        minHeight: Int = 34,
        action: () -> Unit
    ): TextView = TextView(this).apply {
        this.text = text
        textSize = 11f
        typeface = if (primary || selected) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
        gravity = Gravity.CENTER
        setTextColor(if (primary) Color.WHITE else if (selected) TEXT else SECONDARY)
        background = rounded(if (primary) ACCENT else if (selected) SELECTED else SURFACE, 12,
            if (selected && !primary) ACCENT_DARK else BORDER)
        setPadding(dp(11), 0, dp(11), 0)
        minimumWidth = dp(minWidth)
        minimumHeight = dp(minHeight)
        isClickable = true
        isFocusable = true
        setOnClickListener { action() }
    }

    private fun dot(color: Int): TextView = TextView(this).apply {
        text = "●"
        textSize = 10f
        setTextColor(color)
        gravity = Gravity.CENTER
        layoutParams = LinearLayout.LayoutParams(dp(10), dp(35))
    }

    private fun rounded(color: Int, radius: Int, stroke: Int? = null): GradientDrawable =
        GradientDrawable().apply {
            setColor(color)
            cornerRadius = dp(radius).toFloat()
            if (stroke != null) setStroke(dp(1), stroke)
        }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
    private fun matchWrap() = LinearLayout.LayoutParams(-1, -2)
    private fun weightButton() = LinearLayout.LayoutParams(0, dp(36), 1f).apply { rightMargin = dp(4) }
    private fun toast(message: String) = Toast.makeText(this, message, Toast.LENGTH_SHORT).show()

    private data class BrowserTab(val webView: WebView, var lastUrl: String)
    private data class SiteShortcut(val label: String, val url: String)
    private data class ResponseSnapshot(val count: Int, val text: String, val streaming: Boolean)

    private class FrameHost(activity: Activity) : LinearLayout(activity) {
        init {
            orientation = VERTICAL
            setBackgroundColor(Color.WHITE)
        }
        fun show(view: View) {
            (view.parent as? ViewGroup)?.removeView(view)
            removeAllViews()
            addView(view, LayoutParams(-1, -1))
        }
    }

    companion object {
        private val CANVAS = Color.rgb(13, 17, 26)
        private val PANEL = Color.rgb(20, 26, 38)
        private val SURFACE = Color.rgb(29, 37, 52)
        private val SELECTED = Color.rgb(32, 49, 76)
        private val BORDER = Color.rgb(46, 58, 78)
        private val ACCENT = Color.rgb(53, 119, 242)
        private val ACCENT_DARK = Color.rgb(68, 106, 170)
        private val GREEN = Color.rgb(64, 204, 146)
        private val TEXT = Color.rgb(241, 245, 251)
        private val SECONDARY = Color.rgb(188, 199, 216)
        private val MUTED = Color.rgb(136, 151, 173)
    }
}
