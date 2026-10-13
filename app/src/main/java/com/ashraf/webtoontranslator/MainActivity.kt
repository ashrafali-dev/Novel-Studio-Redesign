package com.ashraf.webtoontranslator

import android.app.Activity
import android.app.DownloadManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Rect
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Environment
import android.provider.Settings
import android.os.Bundle
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import com.google.mlkit.vision.text.japanese.JapaneseTextRecognizerOptions
import com.google.mlkit.vision.text.korean.KoreanTextRecognizerOptions
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLDecoder
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.max

class MainActivity : Activity() {
    private lateinit var addressBar: EditText
    private lateinit var webView: WebView
    private lateinit var scannerWebView: WebView
    private lateinit var webFrame: FrameLayout
    private lateinit var splitButton: Button
    private lateinit var scriptSpinner: Spinner
    private lateinit var scanButton: Button
    private lateinit var progressBar: ProgressBar
    private lateinit var statusLabel: TextView
    private lateinit var imageCountLabel: TextView
    private lateinit var resultText: TextView
    private lateinit var chatWebView: WebView
    private lateinit var chatToggleButton: Button
    private lateinit var updateButton: TextView
    private var pendingApkUri: Uri? = null
    private lateinit var bottomPanel: FrameLayout
    private var activeBottomPanel: String = ""

    private val worker: ExecutorService = Executors.newSingleThreadExecutor()
    private val scanSequence = AtomicInteger(0)

    @Volatile private var pageGeneration: Int = 0
    @Volatile private var activeScanToken: Int = 0
    @Volatile private var scanPageGeneration: Int = -1
    @Volatile private var scanInProgress: Boolean = false
    @Volatile private var scannerLoadPending: Boolean = false
    private var splitViewEnabled: Boolean = false
    private val translationBatches = java.util.TreeMap<Int, String>()
    private val translatedBatches = java.util.TreeMap<Int, String>()
    private var nextBatch = 0
    private var sendingBatch = false
    private var chatBaselineCount = 0
    private val translationPrompt = "Translate the following OCR lines into natural, fluent Bengali. Preserve line IDs and order exactly. Do not omit or summarize."

    private var scanPageUrl: String = ""
    private var currentRecognizer: TextRecognizer? = null
    private var currentImages: List<ImageInfo> = emptyList()
    private var currentOutcomes: List<ImageOutcome> = emptyList()
    private val scriptNames = listOf("Latin / English", "Chinese (Simplified)", "Japanese", "Korean")

    private inner class WebtoonBridge {
        @JavascriptInterface
        fun onScrollProgress(token: Int, step: Int, imageCount: Int, height: Int) {
            runOnUiThread {
                if (!isCurrentScan(token)) return@runOnUiThread
                statusLabel.text = "Loading lazy images · pass " + step + " · " + imageCount +
                    " loaded · page " + height + " px"
            }
        }

        @JavascriptInterface
        fun onChapterImages(token: Int, payload: String) {
            runOnUiThread {
                if (!isCurrentScan(token)) return@runOnUiThread
                val root = runCatching { JSONObject(payload) }.getOrElse {
                    finishScan("Could not parse the image list: " + (it.message ?: "unknown error"))
                    return@runOnUiThread
                }
                val parsed = runCatching { parseImages(root) }.getOrElse {
                    finishScan("Could not parse images: " + (it.message ?: "unknown error"))
                    return@runOnUiThread
                }
                if (parsed.isEmpty()) {
                    val detail = root.optString("error")
                    finishScan(
                        if (detail.isNotBlank()) "Page scan error: " + detail
                        else "No chapter images found. The page may use CSS backgrounds or a protected image loader."
                    )
                    return@runOnUiThread
                }
                currentImages = parsed
                currentOutcomes = emptyList()
                renderResults()
                progressBar.max = parsed.size
                progressBar.progress = 0
                imageCountLabel.text = parsed.size.toString() + " chapter images" + if (root.optBoolean("truncated", false)) " · scan limit reached" else ""
                statusLabel.text = "Found " + parsed.size + " images from " + root.optInt("totalDomImages", parsed.size) + " page elements" + if (root.optBoolean("truncated", false)) " · collection limit reached" else "" + " · preparing native OCR"
                startNativeOcr(token, parsed)
            }
        }
    }

    private val bridge = WebtoonBridge()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = CANVAS
        window.navigationBarColor = CANVAS
        WebView.setWebContentsDebuggingEnabled(BuildConfig.DEBUG)
        CookieManager.getInstance().setAcceptCookie(true)
        buildUi()
        openUrl("https://www.webtoons.com/en/")
        intent?.getStringExtra(android.content.Intent.EXTRA_TEXT)?.takeIf { it.isNotBlank() }?.let { openUrl(it) }
    }

    private fun buildUi() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(9), dp(6), dp(9), dp(7))
            setBackgroundColor(CANVAS)
        }

        val titleRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        titleRow.addView(TextView(this).apply {
            text = "WEBTOON TRANSLATOR"
            textSize = 14f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(TEXT)
            letterSpacing = 0.035f
        }, LinearLayout.LayoutParams(0, dp(30), 1f))
        updateButton = chip("UPDATE APK", ACCENT, Color.WHITE).apply { setOnClickListener { checkForAppUpdate() } }
        titleRow.addView(updateButton, LinearLayout.LayoutParams(-2, dp(30)))
        root.addView(titleRow, LinearLayout.LayoutParams(-1, dp(34)))

        val addressRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        addressRow.addView(actionChip("‹", minWidth = 36) {
            if (webView.canGoBack()) webView.goBack()
        })
        addressBar = EditText(this).apply {
            hint = "Paste a Webtoon chapter URL"
            setSingleLine(true)
            textSize = 12.5f
            setTextColor(TEXT)
            setHintTextColor(MUTED)
            imeOptions = EditorInfo.IME_ACTION_GO
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_URI
            setPadding(dp(10), 0, dp(7), 0)
            background = rounded(SURFACE, 12, BORDER)
            setOnEditorActionListener { _, action, _ ->
                if (action == EditorInfo.IME_ACTION_GO || action == EditorInfo.IME_ACTION_DONE) {
                    openUrl(text.toString())
                    true
                } else false
            }
        }
        addressRow.addView(addressBar, LinearLayout.LayoutParams(0, dp(41), 1f).apply {
            leftMargin = dp(5)
        })
        addressRow.addView(actionChip("GO", primary = true) {
            openUrl(addressBar.text.toString())
        }, LinearLayout.LayoutParams(-2, dp(41)).apply { leftMargin = dp(5) })
        root.addView(addressRow, LinearLayout.LayoutParams(-1, dp(44)))

        val actionRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        scriptSpinner = Spinner(this).apply {
            adapter = ArrayAdapter(
                this@MainActivity,
                android.R.layout.simple_spinner_item,
                scriptNames
            ).also { it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }
            setSelection(0)
        }
        actionRow.addView(scriptSpinner, LinearLayout.LayoutParams(0, dp(42), 1f).apply {
            rightMargin = dp(5)
        })
        scanButton = Button(this).apply {
            text = "Scan chapter"
            isAllCaps = false
            textSize = 12f
            setTextColor(Color.WHITE)
            background = rounded(ACCENT, 12)
            setPadding(dp(8), 0, dp(8), 0)
            setOnClickListener { scanChapter() }
        }
        actionRow.addView(scanButton, LinearLayout.LayoutParams(-2, dp(42)))
        splitButton = Button(this).apply {
            text = "Split view"
            isAllCaps = false
            textSize = 10f
            setTextColor(TEXT)
            background = rounded(SURFACE, 10, BORDER)
            setPadding(dp(5), 0, dp(5), 0)
            setOnClickListener { toggleSplitView() }
        }
        actionRow.addView(splitButton, LinearLayout.LayoutParams(-2, dp(42)).apply { leftMargin = dp(5) })
        root.addView(actionRow, LinearLayout.LayoutParams(-1, dp(46)))

        progressBar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            progress = 0
            progressTintList = android.content.res.ColorStateList.valueOf(ACCENT)
            progressBackgroundTintList = android.content.res.ColorStateList.valueOf(BORDER)
        }
        root.addView(progressBar, LinearLayout.LayoutParams(-1, dp(3)))

        statusLabel = TextView(this).apply {
            text = "Open a chapter, choose its language, then scan."
            textSize = 10.5f
            setTextColor(SECONDARY)
            maxLines = 2
            setPadding(dp(2), dp(4), dp(2), dp(3))
        }
        root.addView(statusLabel, LinearLayout.LayoutParams(-1, -2))

        webView = createBrowserWebView(false)
        scannerWebView = createBrowserWebView(true)
        webFrame = FrameLayout(this).apply {
            background = rounded(Color.WHITE, 12, BORDER)
            clipToOutline = true
        }
        // The reader is the only WebView covering the reading area.
        // The scanner is hosted in the lower panel, never layered over the reader.
        webFrame.addView(webView, FrameLayout.LayoutParams(-1, -1))
        val readerCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = rounded(Color.WHITE, 12, BORDER)
            clipToOutline = true
        }
        readerCard.addView(webFrame, LinearLayout.LayoutParams(-1, -1))
        root.addView(readerCard, LinearLayout.LayoutParams(-1, 0, 1f))

        val panelButtons = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(5), 0, dp(3))
        }
        fun panelButton(label: String, action: () -> Unit): Button =
            Button(this).apply {
                text = label
                isAllCaps = false
                textSize = 10f
                setTextColor(TEXT)
                background = rounded(SURFACE, 9, BORDER)
                setPadding(dp(5), 0, dp(5), 0)
                setOnClickListener { action() }
            }
        val ocrPanelButton = panelButton("OCR result") { showBottomPanel("ocr") }
        panelButtons.addView(ocrPanelButton, LinearLayout.LayoutParams(0, dp(34), 1f).apply { rightMargin = dp(4) })
        chatToggleButton = panelButton("Chatbot") { showBottomPanel(if (activeBottomPanel == "chat") "" else "chat") }
        panelButtons.addView(chatToggleButton, LinearLayout.LayoutParams(0, dp(34), 1f).apply { rightMargin = dp(4) })
        val scanSiteButton = panelButton("Scanning site") { showBottomPanel(if (activeBottomPanel == "scanner") "" else "scanner") }
        panelButtons.addView(scanSiteButton, LinearLayout.LayoutParams(0, dp(34), 1f))
        root.addView(panelButtons, LinearLayout.LayoutParams(-1, dp(40)))
        val resultInfoRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        imageCountLabel = TextView(this).apply {
            text = "No scan yet"
            textSize = 10f
            setTextColor(MUTED)
        }
        resultInfoRow.addView(imageCountLabel, LinearLayout.LayoutParams(0, dp(27), 1f))
        resultInfoRow.addView(panelButton("Copy OCR") { copyOcr() }, LinearLayout.LayoutParams(-2, dp(27)))
        root.addView(resultInfoRow, LinearLayout.LayoutParams(-1, dp(29)))

        val resultScroll = ScrollView(this).apply {
            isFillViewport = true
            background = rounded(PANEL, 10, BORDER)
        }
        resultText = TextView(this).apply {
            text = "OCR lines will appear here when scanning starts."
            textSize = 12f
            setTextColor(TEXT)
            typeface = Typeface.MONOSPACE
            setTextIsSelectable(true)
            setPadding(dp(10), dp(8), dp(10), dp(10))
        }
        resultScroll.addView(resultText, ViewGroup.LayoutParams(-1, -2))
        chatWebView = WebView(this).apply {
            setBackgroundColor(Color.WHITE)
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.loadsImagesAutomatically = true
            webChromeClient = WebChromeClient()
            webViewClient = WebViewClient()
            visibility = View.GONE
            loadUrl("https://chatgpt.com/")
        }
        scannerWebView.alpha = 1f
        scannerWebView.isClickable = true
        bottomPanel = FrameLayout(this).apply {
            background = rounded(PANEL, 10, BORDER)
            clipToOutline = true
        }
        bottomPanel.addView(resultScroll, FrameLayout.LayoutParams(-1, -1))
        bottomPanel.addView(chatWebView, FrameLayout.LayoutParams(-1, -1))
        bottomPanel.addView(scannerWebView, FrameLayout.LayoutParams(-1, -1))
        root.addView(bottomPanel, LinearLayout.LayoutParams(-1, dp(1)))
        setContentView(root)
    }

    private fun showBottomPanel(panel: String) {
        activeBottomPanel = panel
        val height = if (panel.isBlank()) dp(1) else dp(245)
        bottomPanel.layoutParams = bottomPanel.layoutParams.apply { this.height = height }
        resultText.parent?.let { (it as? View)?.visibility = if (panel == "ocr") View.VISIBLE else View.GONE }
        // ScrollView is the direct parent of resultText.
        val resultScroll = resultText.parent as? ScrollView
        resultScroll?.visibility = if (panel == "ocr") View.VISIBLE else View.GONE
        chatWebView.visibility = if (panel == "chat") View.VISIBLE else View.GONE
        scannerWebView.visibility = if (panel == "scanner") View.VISIBLE else View.INVISIBLE
        if (panel == "chat") chatToggleButton.text = "Hide chatbot" else chatToggleButton.text = "Chatbot"
    }

    private fun scanChapter() {
        val url = webView.url.orEmpty()
        if (!url.startsWith("https://") && !url.startsWith("http://")) {
            toast("Open a chapter page first.")
            return
        }
        if (scanInProgress) {
            toast("A scan is already running.")
            return
        }
        val token = scanSequence.incrementAndGet()
        activeScanToken = token
        scanPageGeneration = pageGeneration
        scanPageUrl = url
        scanInProgress = true
        currentImages = emptyList()
        currentOutcomes = emptyList()
        scanButton.isEnabled = false
        scriptSpinner.isEnabled = false
        progressBar.max = 100
        progressBar.progress = 1
        imageCountLabel.text = "Collecting images…"
        resultText.text = "Scrolling the chapter to trigger lazy-loaded panels…"
        statusLabel.text = "Scanning chapter from top to bottom · keep this page open."
        showBottomPanel("")
        if (scannerWebView.url != scanPageUrl) {
            scannerLoadPending = true
            statusLabel.text = "Opening a separate chapter copy for background scanning…"
            scannerWebView.loadUrl(scanPageUrl)
        } else {
            startCollection(token)
        }
    }

    private fun startCollection(token: Int) {
        if (!isCurrentScan(token)) return
        scannerWebView.evaluateJavascript(buildCollectionScript(token), null)
    }

    private fun toggleSplitView() {
        splitViewEnabled = !splitViewEnabled
        splitButton.text = if (splitViewEnabled) "Hide split" else "Split view"
        showBottomPanel(if (splitViewEnabled) "scanner" else "")
        statusLabel.text = if (splitViewEnabled) "Scanning site panel opened below the reader." else if (scanInProgress) "Background scan running…" else "Full-page reader view."
    }

    private fun createBrowserWebView(isScanner: Boolean): WebView = WebView(this).apply {
        setBackgroundColor(Color.WHITE)
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        settings.loadsImagesAutomatically = true
        settings.blockNetworkImage = false
        settings.javaScriptCanOpenWindowsAutomatically = false
        settings.allowFileAccess = false
        settings.allowContentAccess = false
        settings.setSupportMultipleWindows(false)
        CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
        if (isScanner) addJavascriptInterface(bridge, "WebtoonBridge")
        webChromeClient = WebChromeClient()
        webViewClient = object : WebViewClient() {
            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                super.onPageStarted(view, url, favicon)
                if (!isScanner) {
                    pageGeneration += 1
                    if (scanInProgress) cancelScan("Reader page changed; previous scan cancelled.")
                    statusLabel.text = "Opening chapter page…"
                    progressBar.progress = 0
                    url?.let { addressBar.setText(it) }
                }
            }
            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                if (!isScanner) {
                    url?.let { addressBar.setText(it) }
                    if (!scanInProgress) statusLabel.text = "Page loaded · choose script and scan chapter."
                } else {
                    view?.evaluateJavascript(buildOverlayHelperScript(), null)
                    if (scannerLoadPending) {
                        scannerLoadPending = false
                        startCollection(activeScanToken)
                    }
                }
            }
            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                val target = request?.url ?: return false
                return target.scheme !in listOf("http", "https")
            }
        }
    }

    private fun buildCollectionScript(token: Int): String = """
(function() {
  var token = __TOKEN__;
  var sleep = function(ms) { return new Promise(function(resolve) { setTimeout(resolve, ms); }); };
  var pageHeight = function() {
    return Math.max(document.documentElement ? document.documentElement.scrollHeight : 0,
      document.body ? document.body.scrollHeight : 0, document.documentElement ? document.documentElement.clientHeight : 0);
  };
  var viewport = Math.max(window.innerHeight || 600, 400);
  var stepSize = Math.max(320, Math.floor(viewport * 0.78));
  var y = 0, stable = 0, steps = 0, previousHeight = -1, previousLoaded = -1;
  var maxSteps = 180;
  var collectLoadedCount = function() {
    return Array.prototype.filter.call(document.images, function(img) { return img.complete && img.naturalWidth > 0; }).length;
  };
  var walk = async function() {
    try {
      window.scrollTo(0, 0);
      await sleep(120);
      while (steps < maxSteps) {
        var height = pageHeight();
        var bottom = Math.max(0, height - viewport);
        y = Math.min(y + stepSize, bottom);
        window.scrollTo(0, y);
        await sleep(100);
        var nextHeight = pageHeight();
        var loaded = collectLoadedCount();
        var atBottom = y >= Math.max(0, nextHeight - viewport - 12);
        if (atBottom && nextHeight === previousHeight && loaded === previousLoaded) stable++;
        else stable = 0;
        previousHeight = nextHeight;
        previousLoaded = loaded;
        steps++;
        if (steps % 4 === 0 && window.WebtoonBridge) {
          try { window.WebtoonBridge.onScrollProgress(token, steps, loaded, nextHeight); } catch (e) {}
        }
        if (atBottom && stable >= 3) break;
      }
      window.scrollTo(0, pageHeight());
      await sleep(350);
      var imgs = Array.prototype.slice.call(document.images);
      imgs.forEach(function(img) { img.removeAttribute('data-webtoon-ocr-index'); });
      var chapterImages = [];
      var seenSources = Object.create(null);
      imgs.forEach(function(img) {
        var src = img.currentSrc || img.src || '';
        var width = img.naturalWidth || 0;
        var height = img.naturalHeight || 0;
        var rect = img.getBoundingClientRect();
        var style = window.getComputedStyle(img);
        if (!src || width < 80 || height < 80 || width * height < 12000) return;
        if (style.display === 'none' || style.visibility === 'hidden' || rect.width < 10 || rect.height < 10) return;
        if (!/^(https?:|data:image\/|blob:)/i.test(src)) return;
        var normalizedSrc = src.split('#')[0];
        if (seenSources[normalizedSrc]) return;
        seenSources[normalizedSrc] = true;
        var index = chapterImages.length;
        img.setAttribute('data-webtoon-ocr-index', String(index));
        chapterImages.push({
          index: index,
          url: new URL((img.getAttribute('data-src') || img.getAttribute('data-original') || img.currentSrc || img.src || src), document.baseURI).href,
          width: width,
          height: height,
          alt: img.alt || ''
        });
      });
      window.scrollTo(0, 0);
      await sleep(350);
      if (window.WebtoonBridge) {
        window.WebtoonBridge.onChapterImages(token, JSON.stringify({
          pageUrl: location.href,
          pageTitle: document.title || '',
          steps: steps,
          truncated: steps >= maxSteps,
          totalDomImages: document.images.length,
          loadedImages: collectLoadedCount(),
          images: chapterImages
        }));
      }
    } catch (error) {
      if (window.WebtoonBridge) {
        window.WebtoonBridge.onChapterImages(token, JSON.stringify({
          pageUrl: location.href, images: [], error: String(error)
        }));
      }
    }
  };
  walk();
})();
""".trimIndent().replace("__TOKEN__", token.toString())

    private fun parseImages(root: JSONObject): List<ImageInfo> {
        val array = root.optJSONArray("images") ?: JSONArray()
        val list = mutableListOf<ImageInfo>()
        for (i in 0 until array.length()) {
            val item = array.optJSONObject(i) ?: continue
            val url = item.optString("url").trim()
            if (url.isBlank()) continue
            list.add(ImageInfo(item.optInt("index", i), url, item.optInt("width", 0),
                item.optInt("height", 0), item.optString("alt", "")))
        }
        return list
    }

    private fun startNativeOcr(token: Int, images: List<ImageInfo>) {
        val recognizer = runCatching { createRecognizer(scriptSpinner.selectedItemPosition) }.getOrElse {
            finishScan("Could not start the selected OCR model: " + (it.message ?: "unknown error"))
            return
        }
        currentRecognizer = recognizer
        val userAgent = webView.settings.userAgentString.ifBlank { "Mozilla/5.0 Android WebtoonTranslator" }
        val pageUrl = scanPageUrl
        worker.execute {
            val outcomes = mutableListOf<ImageOutcome>()
            try {
                for ((position, info) in images.withIndex()) {
                    if (!isCurrentScan(token)) return@execute
                    val outcome = try {
                        val bitmap = downloadBitmap(info.url, pageUrl, userAgent)
                        try {
                            // Run OCR on the original first, then retry a cleaned/upscaled copy
                            // when the source is a long webtoon panel and ML Kit finds no text.
                            var textResult = Tasks.await(
                                recognizer.process(InputImage.fromBitmap(bitmap, 0)),
                                60,
                                TimeUnit.SECONDS
                            )
                            if (textResult.text.isBlank()) {
                                val retryBitmap = prepareOcrRetryBitmap(bitmap)
                                try {
                                    textResult = Tasks.await(
                                        recognizer.process(InputImage.fromBitmap(retryBitmap, 0)),
                                        60,
                                        TimeUnit.SECONDS
                                    )
                                } finally {
                                    if (retryBitmap !== bitmap) retryBitmap.recycle()
                                }
                            }
                            ImageOutcome(info, extractLines(info.index, textResult, bitmap.width, bitmap.height), null)
                        } finally {
                            bitmap.recycle()
                        }
                    } catch (error: Exception) {
                        ImageOutcome(info, emptyList(), readableError(error))
                    }
                    outcomes.add(outcome)
                    val snapshot = outcomes.toList()
                    runOnUiThread {
                        if (isCurrentScan(token)) {
                            currentOutcomes = snapshot
                            progressBar.max = images.size
                            progressBar.progress = position + 1
                            val lineCount = snapshot.sumOf { it.lines.size }
                            statusLabel.text = "OCR image " + (position + 1) + "/" + images.size +
                                " · " + lineCount + " text lines"
                            imageCountLabel.text = (position + 1).toString() + "/" + images.size +
                                " images · " + lineCount + " lines"
                            renderResults()
                            queueTranslationBatches(token, snapshot, false)
                            renderDiagnosticBoxes(token, snapshot)
                        }
                    }
                }
                runOnUiThread {
                    if (isCurrentScan(token)) {
                        val ok = outcomes.count { it.error == null }
                        val lineCount = outcomes.sumOf { it.lines.size }
                        val failures = outcomes.count { it.error != null }
                        queueTranslationBatches(token, outcomes, true)
                        finishScan("OCR finished · " + ok + "/" + images.size + " images processed · " +
                            lineCount + " lines recognized" + if (failures > 0) " · " + failures + " image errors" else "")
                    }
                }
            } catch (error: Exception) {
                runOnUiThread {
                    if (isCurrentScan(token)) finishScan("OCR stopped: " + readableError(error))
                }
            } finally {
                runCatching { recognizer.close() }
                runOnUiThread {
                    if (currentRecognizer === recognizer) currentRecognizer = null
                }
            }
        }
    }

    private fun queueTranslationBatches(token: Int, outcomes: List<ImageOutcome>, final: Boolean) {
        if (token != activeScanToken) return
        val lines = outcomes.sortedBy { it.info.index }.flatMap { it.lines }
        synchronized(translationBatches) {
            while ((nextBatch + 1) * 50 <= lines.size) {
                val start = nextBatch * 50
                translationBatches[nextBatch] = lines.subList(start, start + 50).mapIndexed { i, line ->
                    "[L" + (start + i + 1).toString().padStart(3, '0') + "] " + line.text
                }.joinToString("\n")
                nextBatch++
            }
            if (final && nextBatch * 50 < lines.size) {
                val start = nextBatch * 50
                translationBatches[nextBatch] = lines.subList(start, lines.size).mapIndexed { i, line ->
                    "[L" + (start + i + 1).toString().padStart(3, '0') + "] " + line.text
                }.joinToString("\n")
                nextBatch++
            }
        }
        runOnUiThread { imageCountLabel.text = lines.size.toString() + " lines · " + nextBatch + " translation batches" }
        sendNextTranslationBatch(token)
    }

    private fun sendNextTranslationBatch(token: Int) {
        val index: Int
        val batch: String
        synchronized(translationBatches) {
            if (token != activeScanToken || sendingBatch) return
            index = translatedBatches.size
            batch = translationBatches[index] ?: return
            sendingBatch = true
        }
        val payload = translationPrompt + "\n\nTranslate batch " + (index + 1) +
            ". Preserve every line ID exactly once.\n\n" + batch
        runOnUiThread {
            statusLabel.text = "Sending translation batch " + (index + 1) + " to chatbot…"
        }
        chatWebView.evaluateJavascript(
            "(function(){return document.querySelectorAll('[data-message-author-role=assistant],.model-response-text,[class*=response-content]').length;})()"
        ) { baselineRaw ->
            if (token != activeScanToken) return@evaluateJavascript
            chatBaselineCount = baselineRaw?.trim()?.toIntOrNull() ?: 0
            chatWebView.evaluateJavascript(chatSendScript(payload)) { raw ->
                if (token != activeScanToken) return@evaluateJavascript
                if (decodeJsResult(raw) != "SENT") {
                    synchronized(translationBatches) { sendingBatch = false }
                    runOnUiThread { toast("Chat input not found. Open the Chatbot panel and sign in first.") }
                } else {
                    pollChatBatch(token, index, System.currentTimeMillis(), "", 0, chatBaselineCount)
                }
            }
        }
    }

    private fun chatSendScript(message: String): String {
        val value = JSONObject.quote(message)
        return "(function(){var t=" + value + ";var b=document.querySelector('#prompt-textarea')||document.querySelector('div[contenteditable=true]')||document.querySelector('textarea');if(!b)return 'NOBOX';b.focus();if(b.tagName==='TEXTAREA'){b.value=t;b.dispatchEvent(new Event('input',{bubbles:true}));}else{b.innerHTML='';var p=document.createElement('p');p.textContent=t;b.appendChild(p);b.dispatchEvent(new InputEvent('input',{bubbles:true,inputType:'insertText',data:t}));}setTimeout(function(){var x=document.querySelector('button[data-testid=send-button]')||document.querySelector('button[aria-label*=Send]');if(x&&!x.disabled)x.click();else b.dispatchEvent(new KeyboardEvent('keydown',{key:'Enter',bubbles:true}));},250);return 'SENT';})()"
    }

    private fun pollChatBatch(token: Int, index: Int, started: Long, previous: String, stable: Int, baselineCount: Int) {
        android.os.Handler(mainLooper).postDelayed({
            if (token != activeScanToken) return@postDelayed
            chatWebView.evaluateJavascript("(function(){var a=document.querySelectorAll('[data-message-author-role=assistant],.model-response-text,.response-container message-content,[class*=response-content],model-response');var n=a.length>a.length?null:(a.length?a[a.length-1]:null);var busy=!!document.querySelector('button[data-testid=stop-button],button[aria-label*=Stop],button[aria-label*=stop]');return JSON.stringify({text:n?(n.innerText||''):'',busy:busy,count:a.length});})()") { raw ->
                if (token != activeScanToken) return@evaluateJavascript
                val obj = runCatching { JSONObject(decodeJsResult(raw)) }.getOrNull()
                val reply = obj?.optString("text").orEmpty().trim()
                val busy = obj?.optBoolean("busy", false) ?: false
                val replyCount = obj?.optInt("count", 0) ?: 0
                val isNewReply = replyCount > baselineCount
                val stableNow = if (isNewReply && reply.isNotBlank() && reply == previous && !busy) stable + 1 else 0
                if (isNewReply && reply.isNotBlank() && !busy && stableNow >= 3 && System.currentTimeMillis() - started > 3000) {
                    synchronized(translationBatches) {
                        translatedBatches[index] = reply
                        sendingBatch = false
                    }
                    runOnUiThread {
                        resultText.text = synchronized(translationBatches) {
                            translatedBatches.entries.joinToString("\\n\\n") { entry ->
                                "=== BATCH " + (entry.key + 1) + " ===\\n" + entry.value
                            }
                        }
                        statusLabel.text = "Translated " + translatedBatches.size + " batches · waiting for next OCR batch"
                        showTranslationOnReader()
                    }
                    sendNextBatchIfAny(token)
                } else if (System.currentTimeMillis() - started > 180000) {
                    synchronized(translationBatches) { sendingBatch = false }
                    runOnUiThread { toast("ChatGPT reply timed out. Check the chat panel.") }
                } else pollChatBatch(token, index, started, reply, stableNow, baselineCount)
            }
        }, 1200)
    }

    private fun showTranslationOnReader() {
        val translated = synchronized(translationBatches) {
            translatedBatches.entries.joinToString("\n\n") { entry ->
                "— Batch " + (entry.key + 1) + " —\n" + entry.value
            }
        }
        val quoted = JSONObject.quote(translated)
        val script = """
(function(){
  var id='__webtoon_translator_translation__';
  var old=document.getElementById(id);
  if(old)old.remove();
  var panel=document.createElement('section');panel.id=id;
  panel.style.cssText='position:fixed;left:8px;right:8px;bottom:8px;max-height:45vh;overflow:auto;z-index:2147483647;background:#101827;color:#f8fafc;border:1px solid #64748b;border-radius:12px;box-shadow:0 4px 24px #0008;padding:12px;font:16px/1.65 sans-serif;white-space:pre-wrap;overflow-wrap:anywhere;';
  var head=document.createElement('div');head.style.cssText='display:flex;align-items:center;justify-content:space-between;gap:12px;margin-bottom:8px;font-weight:bold;';
  var title=document.createElement('span');title.textContent='বাংলা অনুবাদ';
  var close=document.createElement('button');close.textContent='×';close.style.cssText='font-size:22px;border:0;border-radius:8px;padding:2px 12px;background:#334155;color:white;';
  close.onclick=function(){panel.remove();};
  head.appendChild(title);head.appendChild(close);
  var body=document.createElement('div');body.textContent=$quoted;
  panel.appendChild(head);panel.appendChild(body);document.body.appendChild(panel);
})();""".trimIndent()
        webView.evaluateJavascript(script, null)
    }

    private fun sendNextBatchIfAny(token: Int) {
        sendNextTranslationBatch(token)
    }

    private fun decodeJsResult(raw: String?): String =
        if (raw.isNullOrBlank() || raw == "null") "" else
            runCatching { org.json.JSONTokener(raw).nextValue().toString() }.getOrDefault("")

    private fun createRecognizer(script: Int): TextRecognizer = when (script) {
        1 -> TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build())
        2 -> TextRecognition.getClient(JapaneseTextRecognizerOptions.Builder().build())
        3 -> TextRecognition.getClient(KoreanTextRecognizerOptions.Builder().build())
        else -> TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    }

    private fun prepareOcrRetryBitmap(source: Bitmap): Bitmap {
        val maxSide = max(source.width, source.height)
        val scale = if (maxSide < 2200) minOf(2f, 2200f / maxSide) else 1f
        val width = (source.width * scale).toInt().coerceAtLeast(1)
        val height = (source.height * scale).toInt().coerceAtLeast(1)
        val output = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = android.graphics.Canvas(output)
        val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG or android.graphics.Paint.FILTER_BITMAP_FLAG)
        // Slight contrast lift makes thin speech-bubble lettering easier to recognize.
        val matrix = android.graphics.ColorMatrix().apply { set(floatArrayOf(
            1.18f, 0f, 0f, 0f, -12f,
            0f, 1.18f, 0f, 0f, -12f,
            0f, 0f, 1.18f, 0f, -12f,
            0f, 0f, 0f, 1f, 0f
        )) }
        paint.colorFilter = android.graphics.ColorMatrixColorFilter(matrix)
        canvas.drawBitmap(source, null, android.graphics.Rect(0, 0, width, height), paint)
        return output
    }

    private fun extractLines(imageIndex: Int, result: Text, bitmapWidth: Int, bitmapHeight: Int): List<OcrLine> {
        val all = mutableListOf<OcrLine>()
        result.textBlocks.forEach { block ->
            block.lines.forEach { line ->
                val box = line.boundingBox ?: Rect(0, 0, bitmapWidth, bitmapHeight)
                val left = (box.left.toFloat() / max(1, bitmapWidth)).coerceIn(0f, 1f)
                val top = (box.top.toFloat() / max(1, bitmapHeight)).coerceIn(0f, 1f)
                val right = (box.right.toFloat() / max(1, bitmapWidth)).coerceIn(left, 1f)
                val bottom = (box.bottom.toFloat() / max(1, bitmapHeight)).coerceIn(top, 1f)
                val lineText = line.text.trim()
                if (lineText.isNotBlank()) all.add(OcrLine(imageIndex, lineText, left, top, right, bottom))
            }
        }
        return all.sortedWith(compareBy<OcrLine> { it.top }.thenBy { it.left })
    }

    private fun downloadBitmap(imageUrl: String, pageUrl: String, userAgent: String): Bitmap {
        val bytes = if (imageUrl.startsWith("data:image/", ignoreCase = true)) {
            decodeDataUrl(imageUrl)
        } else {
            if (imageUrl.startsWith("blob:", ignoreCase = true)) {
                throw IOException("This page uses a blob image URL; native download is not supported in Phase 1.")
            }
            val connection = URL(imageUrl).openConnection() as? HttpURLConnection
                ?: throw IOException("Unsupported image URL.")
            try {
                connection.instanceFollowRedirects = true
                connection.connectTimeout = 15000
                connection.readTimeout = 25000
                connection.useCaches = true
                connection.setRequestProperty("User-Agent", userAgent)
                connection.setRequestProperty("Referer", pageUrl)
                connection.setRequestProperty("Accept", "image/avif,image/webp,image/apng,image/svg+xml,image/*,*/*;q=0.8")
                CookieManager.getInstance().getCookie(imageUrl)?.takeIf { it.isNotBlank() }?.let {
                    connection.setRequestProperty("Cookie", it)
                }
                val code = connection.responseCode
                if (code !in 200..299) throw IOException("Image request returned HTTP " + code)
                if (connection.contentLengthLong > MAX_IMAGE_BYTES) {
                    throw IOException("Image exceeds the 24 MB safety limit.")
                }
                connection.inputStream.use { readLimited(it, MAX_IMAGE_BYTES) }
            } finally {
                connection.disconnect()
            }
        }

        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
            throw IOException("The response is not a supported bitmap image.")
        }
        var sample = 1
        while (max(bounds.outWidth / sample, bounds.outHeight / sample) > MAX_BITMAP_DIMENSION) sample *= 2
        val options = BitmapFactory.Options().apply {
            inJustDecodeBounds = false
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
            ?: throw IOException("Android could not decode this image format.")
    }

    private fun decodeDataUrl(dataUrl: String): ByteArray {
        val comma = dataUrl.indexOf(',')
        if (comma < 0) throw IOException("Malformed data URL.")
        val metadata = dataUrl.substring(0, comma)
        val data = dataUrl.substring(comma + 1)
        return if (metadata.contains(";base64", ignoreCase = true)) {
            android.util.Base64.decode(data, android.util.Base64.DEFAULT)
        } else {
            URLDecoder.decode(data, "UTF-8").toByteArray(Charsets.UTF_8)
        }
    }

    private fun readLimited(input: java.io.InputStream, maximum: Int): ByteArray {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(16 * 1024)
        var total = 0
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            total += count
            if (total > maximum) throw IOException("Image exceeds the 24 MB safety limit.")
            output.write(buffer, 0, count)
        }
        return output.toByteArray()
    }

    private fun readableError(error: Throwable): String {
        val detail = error.cause?.message?.takeIf { it.isNotBlank() }
            ?: error.message?.takeIf { it.isNotBlank() }
            ?: error.javaClass.simpleName
        return detail.replace('\n', ' ').take(180)
    }

    private fun renderDiagnosticBoxes(token: Int, outcomes: List<ImageOutcome>) {
        if (!isCurrentScan(token)) return
        val boxes = JSONArray()
        var count = 0
        for (outcome in outcomes) {
            for (line in outcome.lines) {
                if (count >= MAX_DIAGNOSTIC_BOXES) break
                boxes.put(JSONObject()
                    .put("imageIndex", line.imageIndex)
                    .put("left", line.left.toDouble())
                    .put("top", line.top.toDouble())
                    .put("right", line.right.toDouble())
                    .put("bottom", line.bottom.toDouble())
                    .put("label", (count + 1).toString()))
                count++
            }
            if (count >= MAX_DIAGNOSTIC_BOXES) break
        }
        val payload = JSONObject.quote(boxes.toString())
        scannerWebView.evaluateJavascript(
            "(function(){if(window.__WebtoonTranslator&&window.__WebtoonTranslator.drawBoxes){" +
                "window.__WebtoonTranslator.drawBoxes(JSON.parse(" + payload + "));}})();",
            null
        )
    }

    private fun renderResults() {
        val buffer = StringBuilder()
        if (currentOutcomes.isEmpty()) {
            buffer.append("OCR lines will appear here once image processing starts.")
        } else {
            currentOutcomes.forEachIndexed { outcomeIndex, outcome ->
                buffer.append("IMAGE ").append(outcome.info.index + 1)
                    .append("  ·  ").append(outcome.info.width).append("×").append(outcome.info.height).append('\n')
                if (!outcome.error.isNullOrBlank()) {
                    buffer.append("  ERROR: ").append(outcome.error).append('\n')
                } else if (outcome.lines.isEmpty()) {
                    buffer.append("  No text detected for the selected script.\n")
                } else {
                    outcome.lines.forEachIndexed { lineIndex, line ->
                        buffer.append("  ").append(lineIndex + 1).append(". ").append(line.text).append('\n')
                        buffer.append("     box ")
                            .append(percent(line.left)).append(", ").append(percent(line.top))
                            .append(" → ").append(percent(line.right)).append(", ").append(percent(line.bottom))
                            .append('\n')
                    }
                }
                if (outcomeIndex != currentOutcomes.lastIndex) buffer.append('\n')
            }
        }
        resultText.text = buffer.toString()
    }

    private fun percent(value: Float): String = String.format(Locale.US, "%.1f%%", value * 100f)

    private fun copyOcr() {
        val text = currentOutcomes.joinToString("\n\n") { outcome ->
            val heading = "IMAGE " + (outcome.info.index + 1)
            if (!outcome.error.isNullOrBlank()) heading + "\n[ERROR] " + outcome.error
            else heading + "\n" + outcome.lines.joinToString("\n") { it.text }
        }
        if (text.isBlank()) {
            toast("No OCR result to copy.")
            return
        }
        (getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
            .setPrimaryClip(ClipData.newPlainText("Webtoon OCR", text))
        toast("OCR text copied.")
    }

    private fun isCurrentScan(token: Int): Boolean =
        scanInProgress && token == activeScanToken && scanPageGeneration == pageGeneration

    private fun finishScan(message: String) {
        scanInProgress = false
        scanButton.isEnabled = true
        scriptSpinner.isEnabled = true
        statusLabel.text = message
        if (currentImages.isEmpty()) imageCountLabel.text = "No images"
        if (::scannerWebView.isInitialized) scannerWebView.evaluateJavascript(
            "window.__WebtoonTranslator&&window.__WebtoonTranslator.scrollToTop&&window.__WebtoonTranslator.scrollToTop();",
            null
        )
    }

    private fun cancelScan(message: String) {
        activeScanToken = scanSequence.incrementAndGet()
        scanInProgress = false
        scanButton.isEnabled = true
        scriptSpinner.isEnabled = true
        statusLabel.text = message
    }

    private fun checkForAppUpdate() {
        updateButton.text = "CHECKING…"
        worker.execute {
            try {
                val connection = URL("https://api.github.com/repos/ashrafali-dev/Novel-Studio-Redesign/releases?per_page=1").openConnection() as HttpURLConnection
                val releaseJson = try {
                    connection.connectTimeout = 12000
                    connection.readTimeout = 12000
                    connection.setRequestProperty("Accept", "application/vnd.github+json")
                    connection.setRequestProperty("User-Agent", "WebtoonTranslator-Android")
                    if (connection.responseCode !in 200..299) throw IOException("GitHub returned HTTP " + connection.responseCode)
                    connection.inputStream.bufferedReader().use { it.readText() }
                } finally { connection.disconnect() }
                val releases = JSONArray(releaseJson)
                if (releases.length() == 0) throw IOException("No APK release published yet.")
                val release = releases.getJSONObject(0)
                val assets = release.optJSONArray("assets") ?: JSONArray()
                var apkUrl = ""
                for (i in 0 until assets.length()) {
                    val asset = assets.optJSONObject(i) ?: continue
                    if (asset.optString("name").endsWith(".apk", true)) {
                        apkUrl = asset.optString("browser_download_url")
                        if (apkUrl.isNotBlank()) break
                    }
                }
                if (apkUrl.isBlank()) throw IOException("Latest release has no APK asset.")
                runOnUiThread {
                    updateButton.text = "UPDATE APK"
                    android.app.AlertDialog.Builder(this)
                        .setTitle("Webtoon Translator update")
                        .setMessage("Available build: " + release.optString("name", release.optString("tag_name")) + "\nPublished: " + release.optString("published_at") + "\n\nDownload APK inside the app?")
                        .setNegativeButton("Cancel", null)
                        .setPositiveButton("Download") { _, _ -> downloadAndInstallApk(apkUrl) }
                        .show()
                }
            } catch (error: Exception) {
                runOnUiThread {
                    updateButton.text = "UPDATE APK"
                    Toast.makeText(this, "Update check failed: " + (error.message ?: "unknown error"), Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun downloadAndInstallApk(apkUrl: String) {
        try {
            val request = DownloadManager.Request(Uri.parse(apkUrl))
                .setTitle("Webtoon Translator update")
                .setDescription("Downloading latest APK…")
                .setMimeType("application/vnd.android.package-archive")
                .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                .setAllowedOverMetered(true)
                .setAllowedOverRoaming(false)
                .setDestinationInExternalFilesDir(this, Environment.DIRECTORY_DOWNLOADS, "webtoon-translator-update.apk")
            val manager = getSystemService(DOWNLOAD_SERVICE) as DownloadManager
            val downloadId = manager.enqueue(request)
            Toast.makeText(this, "APK download started. Android will notify when it finishes.", Toast.LENGTH_LONG).show()
            val receiver = object : android.content.BroadcastReceiver() {
                override fun onReceive(context: Context?, intent: Intent?) {
                    if (intent?.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1L) != downloadId) return
                    try { unregisterReceiver(this) } catch (_: Exception) {}
                    manager.query(DownloadManager.Query().setFilterById(downloadId)).use { cursor ->
                        if (!cursor.moveToFirst()) {
                            Toast.makeText(this@MainActivity, "Could not find downloaded APK.", Toast.LENGTH_LONG).show()
                            return
                        }
                        val statusIndex = cursor.getColumnIndex(DownloadManager.COLUMN_STATUS)
                        if (statusIndex < 0 || cursor.getInt(statusIndex) != DownloadManager.STATUS_SUCCESSFUL) {
                            val reasonIndex = cursor.getColumnIndex(DownloadManager.COLUMN_REASON)
                            val reason = if (reasonIndex >= 0) cursor.getInt(reasonIndex) else -1
                            Toast.makeText(this@MainActivity, "APK download failed (code $reason).", Toast.LENGTH_LONG).show()
                            return
                        }
                    }
                    val uri = manager.getUriForDownloadedFile(downloadId)
                    if (uri == null) {
                        Toast.makeText(this@MainActivity, "Downloaded APK could not be opened.", Toast.LENGTH_LONG).show()
                        return
                    }
                    pendingApkUri = uri
                    runOnUiThread { requestInstallDownloadedApk(uri) }
                }
            }
            if (android.os.Build.VERSION.SDK_INT >= 33) {
                registerReceiver(receiver, android.content.IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE), Context.RECEIVER_NOT_EXPORTED)
            } else {
                @Suppress("DEPRECATION")
                registerReceiver(receiver, android.content.IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE))
            }
        } catch (error: Exception) {
            Toast.makeText(this, "Could not start APK download: " + (error.message ?: "unknown error"), Toast.LENGTH_LONG).show()
        }
    }

    private fun requestInstallDownloadedApk(uri: Uri) {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O && !packageManager.canRequestPackageInstalls()) {
            android.app.AlertDialog.Builder(this)
                .setTitle("Allow APK installation")
                .setMessage("Android needs permission to install updates downloaded inside this app. Enable Allow from this source, then return and tap UPDATE APK again.")
                .setPositiveButton("Settings") { _, _ ->
                    startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:" + packageName)))
                }
                .setNegativeButton("Later", null)
                .show()
            return
        }
        val install = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        try { startActivity(install) }
        catch (error: Exception) {
            Toast.makeText(this, "Android could not open installer: " + (error.message ?: "unknown error"), Toast.LENGTH_LONG).show()
        }
    }

    private fun openUrl(input: String) {
        var target = input.trim()
        if (target.isBlank()) return
        if (!target.contains("://")) {
            target = if (target.contains('.') && !target.contains(' ')) "https://" + target
            else "https://www.google.com/search?q=" + Uri.encode(target)
        }
        val parsed = runCatching { Uri.parse(target) }.getOrNull()
        if (parsed == null || parsed.scheme !in listOf("http", "https") || parsed.host.isNullOrBlank()) {
            toast("Enter a valid http or https URL.")
            return
        }
        addressBar.setText(target)
        webView.loadUrl(target)
    }

    private fun actionChip(
        label: String,
        primary: Boolean = false,
        minWidth: Int = 0,
        action: () -> Unit
    ): TextView = TextView(this).apply {
        text = label
        textSize = 11f
        typeface = if (primary) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
        gravity = Gravity.CENTER
        setTextColor(if (primary) Color.WHITE else SECONDARY)
        background = rounded(if (primary) ACCENT else SURFACE, 11, if (primary) null else BORDER)
        setPadding(dp(10), 0, dp(10), 0)
        minimumWidth = dp(minWidth)
        isClickable = true
        isFocusable = true
        setOnClickListener { action() }
    }

    private fun chip(label: String, backgroundColor: Int, textColor: Int): TextView =
        TextView(this).apply {
            text = label
            textSize = 9f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setTextColor(textColor)
            background = rounded(backgroundColor, 9)
            setPadding(dp(8), 0, dp(8), 0)
        }

    private fun rounded(color: Int, radius: Int, stroke: Int? = null): GradientDrawable =
        GradientDrawable().apply {
            setColor(color)
            cornerRadius = dp(radius).toFloat()
            if (stroke != null) setStroke(dp(1), stroke)
        }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
    private fun toast(message: String) = Toast.makeText(this, message, Toast.LENGTH_SHORT).show()

    override fun onBackPressed() {
        if (::webView.isInitialized && webView.canGoBack()) webView.goBack()
        else super.onBackPressed()
    }

    override fun onDestroy() {
        activeScanToken = scanSequence.incrementAndGet()
        scanInProgress = false
        runCatching { currentRecognizer?.close() }
        currentRecognizer = null
        worker.shutdownNow()
        if (::webView.isInitialized) {
            webView.stopLoading()
            webView.destroy()
        }
        if (::scannerWebView.isInitialized) {
            scannerWebView.removeJavascriptInterface("WebtoonBridge")
            scannerWebView.stopLoading()
            scannerWebView.destroy()
        }
        super.onDestroy()
    }

    private data class ImageInfo(
        val index: Int,
        val url: String,
        val width: Int,
        val height: Int,
        val alt: String
    )

    private data class OcrLine(
        val imageIndex: Int,
        val text: String,
        val left: Float,
        val top: Float,
        val right: Float,
        val bottom: Float
    )

    private data class ImageOutcome(
        val info: ImageInfo,
        val lines: List<OcrLine>,
        val error: String?
    )

    private fun buildOverlayHelperScript(): String = """
(function() {
  if (!window.__WebtoonTranslator) window.__WebtoonTranslator = {};
  window.__WebtoonTranslator.clearBoxes = function() {
    var old = document.getElementById('__webtoon_ocr_overlay__');
    if (old) old.remove();
  };
  window.__WebtoonTranslator.scrollToTop = function() { window.scrollTo(0, 0); };
  window.__WebtoonTranslator.drawBoxes = function(items) {
    window.__WebtoonTranslator.clearBoxes();
    var layer = document.createElement('div');
    layer.id = '__webtoon_ocr_overlay__';
    layer.style.cssText = 'position:absolute;left:0;top:0;width:100%;height:' +
      Math.max(document.documentElement.scrollHeight, document.body ? document.body.scrollHeight : 0) +
      'px;z-index:2147483646;pointer-events:none;overflow:visible;';
    document.body.appendChild(layer);
    items.forEach(function(item) {
      var img = document.querySelector('img[data-webtoon-ocr-index="' + item.imageIndex + '"]');
      if (!img) return;
      var rect = img.getBoundingClientRect();
      if (rect.width < 1 || rect.height < 1) return;
      var box = document.createElement('div');
      box.style.cssText = 'position:absolute;box-sizing:border-box;border:2px solid #ef4444;' +
        'background:rgba(239,68,68,0.09);border-radius:2px;';
      box.style.left = (rect.left + window.scrollX + item.left * rect.width) + 'px';
      box.style.top = (rect.top + window.scrollY + item.top * rect.height) + 'px';
      box.style.width = Math.max(3, (item.right - item.left) * rect.width) + 'px';
      box.style.height = Math.max(3, (item.bottom - item.top) * rect.height) + 'px';
      var label = document.createElement('span');
      label.textContent = item.label || '';
      label.style.cssText = 'position:absolute;left:0;top:0;max-width:48px;overflow:hidden;' +
        'font:9px sans-serif;line-height:11px;color:#fff;background:#dc2626;padding:0 2px;';
      box.appendChild(label);
      layer.appendChild(box);
    });
  };
})();
""".trimIndent()

    private companion object {
        const val MAX_IMAGE_BYTES = 24 * 1024 * 1024
        const val MAX_BITMAP_DIMENSION = 4096
        const val MAX_DIAGNOSTIC_BOXES = 2500

        val CANVAS = Color.rgb(13, 17, 26)
        val PANEL = Color.rgb(20, 26, 38)
        val SURFACE = Color.rgb(29, 37, 52)
        val BORDER = Color.rgb(46, 58, 78)
        val ACCENT = Color.rgb(53, 119, 242)
        val TEXT = Color.rgb(241, 245, 251)
        val SECONDARY = Color.rgb(188, 199, 216)
        val MUTED = Color.rgb(136, 151, 173)
    }
}
