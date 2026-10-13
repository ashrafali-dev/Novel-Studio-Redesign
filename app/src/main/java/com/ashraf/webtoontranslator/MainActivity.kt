package com.ashraf.webtoontranslator

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Rect
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.view.Gravity
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
    private lateinit var scriptSpinner: Spinner
    private lateinit var scanButton: Button
    private lateinit var progressBar: ProgressBar
    private lateinit var statusLabel: TextView
    private lateinit var imageCountLabel: TextView
    private lateinit var resultText: TextView

    private val worker: ExecutorService = Executors.newSingleThreadExecutor()
    private val scanSequence = AtomicInteger(0)

    @Volatile private var pageGeneration: Int = 0
    @Volatile private var activeScanToken: Int = 0
    @Volatile private var scanPageGeneration: Int = -1
    @Volatile private var scanInProgress: Boolean = false

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
                imageCountLabel.text = parsed.size.toString() + " chapter images"
                statusLabel.text = "Found " + parsed.size + " images · preparing native OCR"
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
        titleRow.addView(chip("PHASE 1", ACCENT, Color.WHITE), LinearLayout.LayoutParams(-2, dp(25)))
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

        webView = WebView(this).apply {
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
            addJavascriptInterface(bridge, "WebtoonBridge")
            webChromeClient = WebChromeClient()
            webViewClient = object : WebViewClient() {
                override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                    super.onPageStarted(view, url, favicon)
                    pageGeneration += 1
                    if (scanInProgress) cancelScan("Page changed; previous scan cancelled.")
                    view?.evaluateJavascript(
                        "window.__WebtoonTranslator&&window.__WebtoonTranslator.clearBoxes&&window.__WebtoonTranslator.clearBoxes();",
                        null
                    )
                    statusLabel.text = "Opening chapter page…"
                    progressBar.progress = 0
                    url?.let { addressBar.setText(it) }
                }

                override fun onPageFinished(view: WebView?, url: String?) {
                    super.onPageFinished(view, url)
                    url?.let { addressBar.setText(it) }
                    view?.evaluateJavascript(buildOverlayHelperScript(), null)
                    if (!scanInProgress) statusLabel.text = "Page loaded · choose script and scan chapter."
                }

                override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                    val target = request?.url ?: return false
                    return target.scheme !in listOf("http", "https")
                }
            }
        }
        val readerCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = rounded(Color.WHITE, 12, BORDER)
            clipToOutline = true
        }
        readerCard.addView(webView, LinearLayout.LayoutParams(-1, -1))
        root.addView(readerCard, LinearLayout.LayoutParams(-1, 0, 0.68f))

        val resultsHeader = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(2), dp(5), dp(2), dp(3))
        }
        resultsHeader.addView(TextView(this).apply {
            text = "OCR RESULTS"
            textSize = 10f
            typeface = Typeface.DEFAULT_BOLD
            letterSpacing = 0.08f
            setTextColor(SECONDARY)
        }, LinearLayout.LayoutParams(0, -2, 1f))
        imageCountLabel = TextView(this).apply {
            text = "No scan yet"
            textSize = 10f
            setTextColor(MUTED)
        }
        resultsHeader.addView(imageCountLabel)
        val copyButton = Button(this).apply {
            text = "Copy"
            isAllCaps = false
            textSize = 10f
            setTextColor(TEXT)
            background = rounded(SURFACE, 9, BORDER)
            setPadding(dp(10), 0, dp(10), 0)
            setOnClickListener { copyOcr() }
        }
        resultsHeader.addView(copyButton, LinearLayout.LayoutParams(-2, dp(31)).apply {
            leftMargin = dp(6)
        })
        root.addView(resultsHeader, LinearLayout.LayoutParams(-1, dp(37)))

        val resultScroll = ScrollView(this).apply {
            isFillViewport = true
            background = rounded(PANEL, 10, BORDER)
        }
        resultText = TextView(this).apply {
            text = "Recognized text will appear here, grouped by source image. Diagnostic boxes will be drawn over the chapter images."
            textSize = 12f
            setTextColor(TEXT)
            typeface = Typeface.MONOSPACE
            setTextIsSelectable(true)
            setPadding(dp(10), dp(8), dp(10), dp(10))
        }
        resultScroll.addView(resultText, ViewGroup.LayoutParams(-1, -2))
        root.addView(resultScroll, LinearLayout.LayoutParams(-1, 0, 0.32f))
        setContentView(root)
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
        webView.evaluateJavascript(buildCollectionScript(token), null)
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
  var maxSteps = 420;
  var collectLoadedCount = function() {
    return Array.prototype.filter.call(document.images, function(img) { return img.complete && img.naturalWidth > 0; }).length;
  };
  var walk = async function() {
    try {
      window.scrollTo(0, 0);
      await sleep(250);
      while (steps < maxSteps) {
        var height = pageHeight();
        var bottom = Math.max(0, height - viewport);
        y = Math.min(y + stepSize, bottom);
        window.scrollTo(0, y);
        await sleep(220);
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
        if (atBottom && stable >= 4) break;
      }
      window.scrollTo(0, pageHeight());
      await sleep(550);
      var imgs = Array.prototype.slice.call(document.images);
      var chapterImages = [];
      var seenSources = Object.create(null);
      imgs.forEach(function(img) {
        var src = img.currentSrc || img.src || '';
        var width = img.naturalWidth || 0;
        var height = img.naturalHeight || 0;
        var rect = img.getBoundingClientRect();
        var style = window.getComputedStyle(img);
        // Exclude unloaded assets, tiny thumbnails/icons, and hidden decorative images.
        if (!src || !img.complete || width < 300 || height < 300 || width * height < 100000) return;
        if (style.display === 'none' || style.visibility === 'hidden' || Number(style.opacity) === 0 ||
            rect.width < 80 || rect.height < 80) return;
        if (!/^(https?:|data:image\\/|blob:)/i.test(src)) return;
        var absoluteSrc;
        try { absoluteSrc = new URL(src, document.baseURI).href; } catch (e) { return; }
        if (seenSources[absoluteSrc]) return;
        seenSources[absoluteSrc] = true;
        var index = chapterImages.length;
        img.setAttribute('data-webtoon-ocr-index', String(index));
        chapterImages.push({
          index: index,
          url: absoluteSrc,
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
                            val textResult = Tasks.await(
                                recognizer.process(InputImage.fromBitmap(bitmap, 0)),
                                60,
                                TimeUnit.SECONDS
                            )
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
                            renderDiagnosticBoxes(token, snapshot)
                        }
                    }
                }
                runOnUiThread {
                    if (isCurrentScan(token)) {
                        val ok = outcomes.count { it.error == null }
                        val lineCount = outcomes.sumOf { it.lines.size }
                        val failures = outcomes.count { it.error != null }
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

    private fun createRecognizer(script: Int): TextRecognizer = when (script) {
        1 -> TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build())
        2 -> TextRecognition.getClient(JapaneseTextRecognizerOptions.Builder().build())
        3 -> TextRecognition.getClient(KoreanTextRecognizerOptions.Builder().build())
        else -> TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
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
        webView.evaluateJavascript(
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
        if (::webView.isInitialized) webView.evaluateJavascript(
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
            webView.removeJavascriptInterface("WebtoonBridge")
            webView.stopLoading()
            webView.destroy()
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
