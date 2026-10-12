package com.ashraf.novelstudio

import android.app.Activity
import android.app.AlertDialog
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import org.mozilla.geckoview.GeckoRuntime
import org.mozilla.geckoview.GeckoSession
import org.mozilla.geckoview.GeckoView
import org.mozilla.geckoview.WebExtension

class MainActivity : Activity() {
    private lateinit var runtime: GeckoRuntime
    private lateinit var addressBar: EditText
    private lateinit var browserPanel: LinearLayout
    private lateinit var chatPanel: LinearLayout
    private lateinit var browserHost: FrameHost
    private lateinit var chatbot: WebView
    private lateinit var splitBar: SeekBar
    private lateinit var tabBar: LinearLayout
    private lateinit var statusText: TextView
    private val tabs = mutableListOf<BrowserTab>()
    private var activeTab = 0
    private var splitEnabled = true
    private var splitRatio = 62
    private val core = NovelStudioCore()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        runtime = GeckoRuntime.create(this)
        buildUi()
        createTab("https://www.google.com")
        intent?.getStringExtra("android.intent.extra.TEXT")?.takeIf { it.isNotBlank() }?.let { navigate(it) }
    }

    private fun buildUi() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.rgb(17, 19, 25))
        }
        val title = TextView(this).apply {
            text = "NovelStudio Next"
            textSize = 17f
            setTextColor(Color.WHITE)
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14), dp(8), dp(8), dp(8))
        }
        root.addView(title, matchWrap())

        val addressRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(6), dp(3), dp(6), dp(3))
        }
        addressBar = EditText(this).apply {
            hint = "URL লিখুন অথবা সার্চ করুন"
            singleLine = true
            textSize = 14f
            setTextColor(Color.WHITE)
            setHintTextColor(Color.LTGRAY)
            imeOptions = EditorInfo.IME_ACTION_GO
            setPadding(dp(10), 0, dp(10), 0)
            setBackgroundColor(Color.rgb(37, 40, 49))
            setOnEditorActionListener { _, action, _ ->
                if (action == EditorInfo.IME_ACTION_GO || action == EditorInfo.IME_ACTION_DONE) {
                    navigate(text.toString()); true
                } else false
            }
        }
        addressRow.addView(addressBar, LinearLayout.LayoutParams(0, dp(44), 1f))
        addressRow.addView(button("Go") { navigate(addressBar.text.toString()) }, wrap())
        root.addView(addressRow, matchWrap())

        val navRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        navRow.addView(button("←") { activeSession()?.goBack() }, weight())
        navRow.addView(button("→") { activeSession()?.goForward() }, weight())
        navRow.addView(button("⟳") { activeSession()?.reload() }, weight())
        navRow.addView(button("Tabs") { showTabs() }, weight())
        navRow.addView(button("Extensions") { showExtensions() }, weight())
        navRow.addView(button("Split") { toggleSplit() }, weight())
        root.addView(navRow, matchWrap())

        tabBar = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(dp(4), dp(2), dp(4), dp(2)) }
        root.addView(tabBar, matchWrap())

        val content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        browserPanel = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        browserHost = FrameHost(this)
        browserPanel.addView(browserHost, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        content.addView(browserPanel, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, splitRatio.toFloat()))

        val divider = TextView(this).apply {
            text = "━━━━  SPLIT SIZE  ━━━━"
            gravity = Gravity.CENTER
            textSize = 10f
            setTextColor(Color.LTGRAY)
            setBackgroundColor(Color.rgb(34, 37, 45))
        }
        content.addView(divider, matchWrap())
        splitBar = SeekBar(this).apply {
            max = 80
            progress = splitRatio
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                    splitRatio = progress.coerceIn(20, 80)
                    updateSplitWeights()
                }
                override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
                override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
            })
        }
        content.addView(splitBar, matchWrap())

        chatPanel = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val chatHeader = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        chatHeader.addView(label("CHATBOT · WebView"), LinearLayout.LayoutParams(0, dp(34), 1f))
        chatHeader.addView(button("ChatGPT") { loadChatbot("https://chatgpt.com") }, wrap())
        chatHeader.addView(button("Gemini") { loadChatbot("https://gemini.google.com") }, wrap())
        chatPanel.addView(chatHeader, matchWrap())
        chatbot = WebView(this).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.databaseEnabled = true
            settings.loadsImagesAutomatically = true
            webViewClient = WebViewClient()
            webChromeClient = WebChromeClient()
            setBackgroundColor(Color.WHITE)
            loadUrl("https://chatgpt.com")
        }
        chatPanel.addView(chatbot, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        content.addView(chatPanel, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, (100 - splitRatio).toFloat()))
        root.addView(content, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        val actions = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER }
        actions.addView(button("Extract") { extractChapter() }, weight())
        actions.addView(button("Translate") { startTranslation() }, weight())
        actions.addView(button("Next Chapter") { nextChapter() }, weight())
        root.addView(actions, matchWrap())

        statusText = TextView(this).apply {
            text = "GeckoView browser · separate chatbot WebView"
            textSize = 11f
            setTextColor(Color.LTGRAY)
            setPadding(dp(10), dp(5), dp(10), dp(5))
        }
        root.addView(statusText, matchWrap())
        setContentView(root)
    }

    private fun createTab(url: String) {
        val session = GeckoSession()
        session.open(runtime)
        val view = GeckoView(this).apply { setBackgroundColor(Color.WHITE); setSession(session) }
        tabs.add(BrowserTab(session, view, url))
        activeTab = tabs.lastIndex
        browserHost.show(view)
        session.loadUri(url)
        addressBar.setText(url)
        renderTabs()
    }

    private fun navigate(raw: String) {
        val input = raw.trim()
        if (input.isEmpty()) return
        val url = when {
            input.startsWith("https://", true) || input.startsWith("http://", true) -> input
            input.contains(" ") || !input.contains(".") -> "https://www.google.com/search?q=" + Uri.encode(input)
            else -> "https://" + input
        }
        if (tabs.isEmpty()) { createTab(url); return }
        tabs[activeTab].lastRequestedUrl = url
        addressBar.setText(url)
        tabs[activeTab].session.loadUri(url)
        statusText.text = "Loading: " + url
        renderTabs()
    }

    private fun showTabs() {
        val items = tabs.mapIndexed { i, tab ->
            (if (i == activeTab) "● " else "") + "Tab " + (i + 1) + " · " + tab.lastRequestedUrl
        }.toMutableList()
        items.add("＋ New tab")
        AlertDialog.Builder(this).setTitle("Browser tabs").setItems(items.toTypedArray()) { _, which ->
            if (which == tabs.size) createTab("https://www.google.com") else selectTab(which)
        }.setNegativeButton("Close active tab") { _, _ -> closeActiveTab() }.show()
    }

    private fun selectTab(index: Int) {
        if (index !in tabs.indices) return
        activeTab = index
        browserHost.show(tabs[index].view)
        addressBar.setText(tabs[index].lastRequestedUrl)
        renderTabs()
    }

    private fun closeActiveTab() {
        if (tabs.size <= 1) { toast("কমপক্ষে একটি ট্যাব খোলা থাকতে হবে"); return }
        tabs.removeAt(activeTab).session.close()
        activeTab = (activeTab - 1).coerceAtLeast(0)
        browserHost.show(tabs[activeTab].view)
        addressBar.setText(tabs[activeTab].lastRequestedUrl)
        renderTabs()
    }

    private fun renderTabs() {
        tabBar.removeAllViews()
        tabs.forEachIndexed { i, _ -> tabBar.addView(button((if (i == activeTab) "● " else "") + (i + 1)) { selectTab(i) }, weight()) }
        tabBar.addView(button("+") { createTab("https://www.google.com") }, wrap())
    }

    private fun showExtensions() {
        val input = EditText(this).apply { hint = "Signed HTTPS .xpi URL"; setText("https://"); singleLine = true }
        AlertDialog.Builder(this)
            .setTitle("WebExtension Manager")
            .setMessage("GeckoView extension installation uses Mozilla-signed XPI packages.")
            .setView(input)
            .setPositiveButton("Install") { _, _ ->
                val url = input.text.toString().trim()
                if (!url.startsWith("https://") || !url.endsWith(".xpi")) {
                    toast("একটি HTTPS .xpi URL দে")
                } else {
                    statusText.text = "Installing extension…"
                    runtime.webExtensionController.install(url).accept(
                        { extension: WebExtension ->
                            runOnUiThread {
                                statusText.text = "Installed: " + extension.metaData.name
                                toast("Extension installed")
                            }
                        },
                        { error: Throwable ->
                            runOnUiThread {
                                statusText.text = "Extension install failed: " + error.message
                                toast("Install failed")
                            }
                        }
                    )
                }
            }
            .setNeutralButton("List installed") { _, _ -> listExtensions() }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun listExtensions() {
        runtime.webExtensionController.list().accept(
            { extensions ->
                val names = extensions.map { it.metaData.name + " (" + it.id + ")" }.toTypedArray()
                runOnUiThread {
                    AlertDialog.Builder(this).setTitle("Installed extensions (" + names.size + ")")
                        .setItems(names) { _, which -> toast(names[which]) }
                        .setPositiveButton("OK", null).show()
                }
            },
            { error -> runOnUiThread { toast("Could not list extensions: " + error.message) } }
        )
    }

    private fun toggleSplit() {
        splitEnabled = !splitEnabled
        chatPanel.visibility = if (splitEnabled) View.VISIBLE else View.GONE
        splitBar.visibility = if (splitEnabled) View.VISIBLE else View.GONE
        updateSplitWeights()
    }

    private fun updateSplitWeights() {
        if (!::browserPanel.isInitialized || !::chatPanel.isInitialized) return
        val bp = browserPanel.layoutParams as LinearLayout.LayoutParams
        bp.height = 0
        bp.weight = if (splitEnabled) splitRatio.toFloat() else 1f
        browserPanel.layoutParams = bp
        val cp = chatPanel.layoutParams as LinearLayout.LayoutParams
        cp.height = 0
        cp.weight = (100 - splitRatio).coerceAtLeast(20).toFloat()
        chatPanel.layoutParams = cp
    }

    private fun loadChatbot(url: String) { chatbot.loadUrl(url); statusText.text = "Chatbot: " + url }

    private fun extractChapter() {
        val url = tabs.getOrNull(activeTab)?.lastRequestedUrl.orEmpty()
        val job = core.beginJob(url)
        statusText.text = "Extract requested · job " + job.id + " · content bridge is next"
        toast("NovelStudio job created")
    }

    private fun startTranslation() {
        val job = core.currentJob
        if (job == null) { toast("আগে Extract চাপ"); return }
        core.markTranslationRequested(job.id)
        statusText.text = "Translation job " + job.id + " queued · provider bridge pending"
        toast("Translation job queued")
    }

    private fun nextChapter() {
        core.invalidateCurrentJob()
        statusText.text = "Old chapter job invalidated. Automatic site-specific Next adapter is pending."
        toast("এই প্রাথমিক build-এ Next সাইটের বাটন নিজে চাপবে না")
    }

    private fun activeSession(): GeckoSession? = tabs.getOrNull(activeTab)?.session
    override fun onBackPressed() { activeSession()?.goBack() ?: super.onBackPressed() }

    override fun onDestroy() {
        tabs.forEach { runCatching { it.session.close() } }
        if (::chatbot.isInitialized) chatbot.destroy()
        super.onDestroy()
    }

    private fun button(text: String, action: () -> Unit) = Button(this).apply {
        this.text = text; textSize = 11f; isAllCaps = false
        setPadding(dp(4), 0, dp(4), 0)
        setOnClickListener { action() }
    }
    private fun label(text: String) = TextView(this).apply {
        this.text = text; textSize = 11f; setTextColor(Color.WHITE)
        gravity = Gravity.CENTER_VERTICAL; setPadding(dp(8), 0, dp(4), 0)
    }
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    private fun matchWrap() = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
    private fun wrap() = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
    private fun weight() = LinearLayout.LayoutParams(0, dp(42), 1f)
    private fun toast(message: String) = Toast.makeText(this, message, Toast.LENGTH_SHORT).show()

    private data class BrowserTab(val session: GeckoSession, val view: GeckoView, var lastRequestedUrl: String)

    private class FrameHost(activity: Activity) : LinearLayout(activity) {
        init { orientation = VERTICAL; setBackgroundColor(Color.WHITE) }
        fun show(view: View) {
            (view.parent as? ViewGroup)?.removeView(view)
            removeAllViews()
            addView(view, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        }
    }
}
