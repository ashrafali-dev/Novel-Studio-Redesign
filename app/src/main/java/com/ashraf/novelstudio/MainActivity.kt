package com.ashraf.novelstudio

import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
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
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import org.json.JSONObject

class MainActivity : Activity() {
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
    private var translationSendJobId: Long? = null
    private var translationPollJobId: Long? = null
    private var lastInsertedJobId: Long? = null

    private val novelBridge = object {
        @JavascriptInterface
        fun chapterPayload(json: String) {
            runOnUiThread {
                runCatching {
                    val o = JSONObject(json)
                    val url = o.optString("url")
                    val title = o.optString("title")
                    val text = o.optString("text").trim()
                    if (text.length < 80) { toast("Chapter text পাওয়া যায়নি"); return@runOnUiThread }
                    val job = core.beginJob(url)
                    core.setExtracted(job.id, title, text)
                    statusText.text = "Extracted · " + text.length + " chars · job " + job.id
                    toast("Chapter extracted")
                }
            }
        }
    }

    private val aiBridge = object {
        @JavascriptInterface
        fun translationResult(text: String) {
            runOnUiThread {
                val job = core.currentJob ?: return@runOnUiThread
                if (!core.isCurrentJob(job.id)) return@runOnUiThread
                if (lastInsertedJobId == job.id) return@runOnUiThread
                val cleaned = text.trim()
                if (cleaned.length < 40) return@runOnUiThread
                core.saveTranslation(job.chapterUrl, cleaned)
                core.markCompleted(job.id, cleaned)
                lastInsertedJobId = job.id
                translationPollJobId = null
                injectTranslation(cleaned)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WebView.setWebContentsDebuggingEnabled(BuildConfig.DEBUG)
        buildUi()
        createTab("https://www.google.com")
        intent?.getStringExtra("android.intent.extra.TEXT")?.takeIf { it.isNotBlank() }?.let { navigate(it) }
    }

    private fun buildUi() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.rgb(17,19,25))
        }
        root.addView(TextView(this).apply {
            text = "NovelStudio · WebView"
            textSize = 17f
            setTextColor(Color.WHITE)
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14),dp(8),dp(8),dp(8))
        }, matchWrap())

        val addressRow = LinearLayout(this).apply { orientation=LinearLayout.HORIZONTAL; gravity=Gravity.CENTER_VERTICAL }
        addressBar = EditText(this).apply {
            hint="URL অথবা search"; setSingleLine(true); textSize=14f
            setTextColor(Color.WHITE); setHintTextColor(Color.LTGRAY)
            imeOptions=EditorInfo.IME_ACTION_GO; setPadding(dp(10),0,dp(10),0)
            setBackgroundColor(Color.rgb(37,40,49))
            setOnEditorActionListener { _,action,_ ->
                if(action==EditorInfo.IME_ACTION_GO || action==EditorInfo.IME_ACTION_DONE){navigate(text.toString());true}else false
            }
        }
        addressRow.addView(addressBar,LinearLayout.LayoutParams(0,dp(44),1f))
        addressRow.addView(button("Go"){navigate(addressBar.text.toString())},wrap())
        root.addView(addressRow,matchWrap())

        val nav=LinearLayout(this).apply{orientation=LinearLayout.HORIZONTAL}
        nav.addView(button("←"){activeWebView()?.goBack()},weight())
        nav.addView(button("→"){activeWebView()?.goForward()},weight())
        nav.addView(button("⟳"){activeWebView()?.reload()},weight())
        nav.addView(button("Tabs"){showTabs()},weight())
        nav.addView(button("Split"){toggleSplit()},weight())
        root.addView(nav,matchWrap())
        tabBar=LinearLayout(this).apply{orientation=LinearLayout.HORIZONTAL}
        root.addView(tabBar,matchWrap())

        val content=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL}
        browserPanel=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL}
        browserHost=FrameHost(this)
        browserPanel.addView(browserHost,LinearLayout.LayoutParams(-1,0,1f))
        content.addView(browserPanel,LinearLayout.LayoutParams(-1,0,splitRatio.toFloat()))
        content.addView(TextView(this).apply{text="━━━━ SPLIT SIZE ━━━━";gravity=Gravity.CENTER;textSize=10f;setTextColor(Color.LTGRAY)},matchWrap())
        splitBar=SeekBar(this).apply{
            max=80;progress=splitRatio
            setOnSeekBarChangeListener(object:SeekBar.OnSeekBarChangeListener{
                override fun onProgressChanged(s:SeekBar?,p:Int,f:Boolean){splitRatio=p.coerceIn(20,80);updateSplitWeights()}
                override fun onStartTrackingTouch(s:SeekBar?)=Unit
                override fun onStopTrackingTouch(s:SeekBar?)=Unit
            })
        }
        content.addView(splitBar,matchWrap())

        chatPanel=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL}
        val ch=LinearLayout(this).apply{orientation=LinearLayout.HORIZONTAL}
        ch.addView(label("AI · WebView"),LinearLayout.LayoutParams(0,dp(34),1f))
        ch.addView(button("ChatGPT"){loadChatbot("https://chatgpt.com")},wrap())
        ch.addView(button("Gemini"){loadChatbot("https://gemini.google.com")},wrap())
        chatPanel.addView(ch,matchWrap())
        chatbot=WebView(this).apply{
            configureWebView();addJavascriptInterface(aiBridge,"NovelStudioAI")
            webViewClient=object:WebViewClient(){
                override fun onPageFinished(v:WebView,url:String){statusText.text="AI ready · "+url;if(core.translationRequested)injectTranslationPrompt()}
            }
            loadUrl("https://chatgpt.com")
        }
        chatPanel.addView(chatbot,LinearLayout.LayoutParams(-1,0,1f))
        content.addView(chatPanel,LinearLayout.LayoutParams(-1,0,(100-splitRatio).toFloat()))
        root.addView(content,LinearLayout.LayoutParams(-1,0,1f))

        val actions=LinearLayout(this).apply{orientation=LinearLayout.HORIZONTAL}
        actions.addView(button("Extract"){extractChapter()},weight())
        actions.addView(button("Translate"){startTranslation()},weight())
        actions.addView(button("Next"){nextChapter()},weight())
        actions.addView(button("Copy"){copyChapter()},weight())
        root.addView(actions,matchWrap())
        statusText=TextView(this).apply{text="WebView browser · AI WebView";textSize=11f;setTextColor(Color.LTGRAY);setPadding(dp(10),dp(5),dp(10),dp(5))}
        root.addView(statusText,matchWrap())
        setContentView(root)
    }

    private fun WebView.configureWebView(){
        settings.javaScriptEnabled=true
        settings.domStorageEnabled=true
        settings.databaseEnabled=true
        settings.loadsImagesAutomatically=true
        settings.javaScriptCanOpenWindowsAutomatically=true
        settings.setSupportMultipleWindows(false)
        settings.cacheMode=WebSettings.LOAD_DEFAULT
        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(this,true)
        webChromeClient=WebChromeClient()
        setBackgroundColor(Color.WHITE)
    }

    private fun createTab(url:String){
        val view=WebView(this).apply{
            configureWebView()
            addJavascriptInterface(novelBridge,"NovelStudio")
            webViewClient=object:WebViewClient(){
                override fun shouldOverrideUrlLoading(v:WebView,r:WebResourceRequest)=false
                override fun onPageFinished(v:WebView,url:String){
                    tabs.getOrNull(activeTab)?.lastUrl=url
                    addressBar.setText(url);renderTabs();installPageHooks(v)
                }
            }
        }
        tabs.add(BrowserTab(view,url));activeTab=tabs.lastIndex
        browserHost.show(view);view.loadUrl(url);addressBar.setText(url);renderTabs()
    }

    private fun navigate(raw:String){
        val input=raw.trim();if(input.isEmpty())return
        val url=when{
            input.startsWith("https://",true)||input.startsWith("http://",true)->input
            input.contains(" ")||!input.contains(".")->"https://www.google.com/search?q="+Uri.encode(input)
            else->"https://"+input
        }
        if(tabs.isEmpty())createTab(url)else{tabs[activeTab].lastUrl=url;addressBar.setText(url);tabs[activeTab].webView.loadUrl(url)}
    }

    private fun installPageHooks(view:WebView){
        val selectors=SiteProfiles.selectorJson()
        val js="""(function(){window.__NovelStudio={extract:function(){var s=$selectors,b=null,score=-1;s.forEach(function(q){document.querySelectorAll(q).forEach(function(e){var t=(e.innerText||e.textContent||'').trim(),x=t.length+Math.min(e.querySelectorAll('p').length*80,2000);if(t.length>200&&x>score){b=e;score=x;}})});if(!b)b=document.querySelector('article,main,[role="main"]');var t=(b?(b.innerText||b.textContent):document.body.innerText||'').trim().replace(/\n{3,}/g,'\n\n');if(window.NovelStudio)NovelStudio.chapterPayload(JSON.stringify({title:document.title,url:location.href,text:t}));}}})();"""
        view.evaluateJavascript(js,null)
    }

    private fun extractChapter(){
        activeWebView()?.evaluateJavascript("window.__NovelStudio&&window.__NovelStudio.extract();",null)
        statusText.text="Extracting chapter…"
    }

    private fun startTranslation(){
        val job=core.currentJob
        if(job==null||job.extractedText.isBlank()){toast("আগে Extract চাপ");return}
        if (translationSendJobId == job.id || job.status == NovelStudioCore.Status.COMPLETED) {
            toast("এই chapter ইতিমধ্যে পাঠানো হয়েছে")
            return
        }
        if (!core.markTranslationRequested(job.id)) return
        translationSendJobId = job.id
        statusText.text="Sending chapter to AI once…"
        injectTranslationPrompt()
    }

    private fun injectTranslationPrompt(){
        val job=core.currentJob?:return
        if (translationSendJobId != job.id) return
        if(job.extractedText.isBlank())return
        val prompt="Translate the following novel chapter into natural, fluent literary Bengali. Preserve meaning, names, dialogue, paragraph structure, tone, emotions, humor and pacing. Do not summarize. Output only the Bengali translation.\n\n"+job.extractedText
        val escaped=JSONObject.quote(prompt)
        val js="""(function(){var p=$escaped,el=document.querySelector('textarea,[contenteditable="true"]');if(!el)return;el.focus();if(el.tagName==='TEXTAREA'){var s=Object.getOwnPropertyDescriptor(HTMLTextAreaElement.prototype,'value').set;s.call(el,p);el.dispatchEvent(new Event('input',{bubbles:true}));}else{el.textContent=p;el.dispatchEvent(new InputEvent('input',{bubbles:true,inputType:'insertText',data:p}));}var b=document.querySelector('button[type="submit"],button[aria-label*="Send"],button[data-testid*="send"]');if(b)b.click();})();"""
        chatbot.evaluateJavascript(js,null)
        scheduleAiResponsePolling(job.id)
    }

    private fun scheduleAiResponsePolling(id:Long){
        if (translationPollJobId == id) return
        translationPollJobId = id
        chatbot.postDelayed(object:Runnable{
            override fun run(){
                if(!core.isCurrentJob(id)||!core.translationRequested){ if (translationPollJobId == id) translationPollJobId = null; return }
                chatbot.evaluateJavascript("""(function(){var a=[];document.querySelectorAll('[data-message-author-role="assistant"],article,[class*="markdown"],[class*="response"]').forEach(function(e){var t=(e.innerText||e.textContent||'').trim();if(t.length>80)a.push(t);});if(a.length&&window.NovelStudioAI)NovelStudioAI.translationResult(a[a.length-1]);})();""",null)
                if(core.isCurrentJob(id)&&core.translationRequested)chatbot.postDelayed(this,2500) else if (translationPollJobId == id) translationPollJobId = null
            }
        },6000)
    }

    private fun injectTranslation(text:String){
        val escaped=JSONObject.quote(text)
        val selectors=SiteProfiles.selectorJson()
        activeWebView()?.evaluateJavascript("""(function(){var t=$escaped,s=$selectors,b=null,n=-1;s.forEach(function(q){document.querySelectorAll(q).forEach(function(e){var x=(e.innerText||e.textContent||'').trim();if(x.length>200&&x.length>n){b=e;n=x.length;}})});if(!b)b=document.querySelector('article,main,[role="main"]');if(b){var o=b.parentNode.querySelector('[data-novelstudio-translation]');if(o)o.remove();var x=document.createElement('section');x.setAttribute('data-novelstudio-translation','true');x.style.cssText='white-space:pre-wrap;margin:16px 0;padding:16px;border:1px solid #888;border-radius:10px;background:#fffbe8;color:#111;line-height:1.8;';x.textContent=t;b.parentNode.insertBefore(x,b);x.scrollIntoView();}})();""",null)
        statusText.text="Translation inserted into chapter page"
        toast("অনুবাদ পেজে বসানো হয়েছে")
    }

    private fun nextChapter(){
        core.invalidateCurrentJob()
        translationSendJobId = null
        translationPollJobId = null
        val selectors=SiteProfiles.nextSelectorJson()
        activeWebView()?.evaluateJavascript(ProviderHandlers.nextChapterScript(selectors)){r->statusText.text=if(r=="true")"Next chapter clicked" else "Next button not found"}
    }

    private fun copyChapter(){
        val t=core.currentJob?.extractedText.orEmpty()
        if(t.isBlank()){toast("আগে Extract কর");return}
        (getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("Novel chapter",t))
        toast("Chapter copied")
    }

    private fun showTabs(){
        val items=tabs.mapIndexed{i,t->(if(i==activeTab)"● " else "")+"Tab "+(i+1)+" · "+t.lastUrl}.toMutableList();items.add("＋ New tab")
        AlertDialog.Builder(this).setTitle("Browser tabs").setItems(items.toTypedArray()){_,w->if(w==tabs.size)createTab("https://www.google.com")else selectTab(w)}.setNegativeButton("Close active tab"){_,_->closeActiveTab()}.show()
    }
    private fun selectTab(i:Int){if(i !in tabs.indices)return;activeTab=i;browserHost.show(tabs[i].webView);addressBar.setText(tabs[i].webView.url?:tabs[i].lastUrl);renderTabs()}
    private fun closeActiveTab(){if(tabs.size<=1){toast("কমপক্ষে একটি ট্যাব খোলা থাকতে হবে");return};tabs.removeAt(activeTab).webView.destroy();activeTab=(activeTab-1).coerceAtLeast(0);browserHost.show(tabs[activeTab].webView);renderTabs()}
    private fun renderTabs() {
        tabBar.removeAllViews()
        tabs.forEachIndexed { i, _ ->
            val marker = if (i == activeTab) "● " else ""
            tabBar.addView(button(marker + (i + 1)) { selectTab(i) }, weight())
        }
        tabBar.addView(button("+") { createTab("https://www.google.com") }, wrap())
    }
    private fun toggleSplit(){splitEnabled=!splitEnabled;chatPanel.visibility=if(splitEnabled)View.VISIBLE else View.GONE;splitBar.visibility=if(splitEnabled)View.VISIBLE else View.GONE;updateSplitWeights()}
    private fun updateSplitWeights(){if(!::browserPanel.isInitialized)return;val b=browserPanel.layoutParams as LinearLayout.LayoutParams;b.height=0;b.weight=if(splitEnabled)splitRatio.toFloat()else 1f;browserPanel.layoutParams=b;val c=chatPanel.layoutParams as LinearLayout.LayoutParams;c.height=0;c.weight=(100-splitRatio).coerceAtLeast(20).toFloat();chatPanel.layoutParams=c}
    private fun loadChatbot(url:String){chatbot.loadUrl(url);statusText.text="Chatbot: "+url}
    private fun activeWebView():WebView?=tabs.getOrNull(activeTab)?.webView
    override fun onBackPressed(){val v=activeWebView();if(v?.canGoBack()==true)v.goBack()else super.onBackPressed()}
    override fun onDestroy(){tabs.forEach{it.webView.destroy()};if(::chatbot.isInitialized)chatbot.destroy();super.onDestroy()}
    private fun button(t:String,a:()->Unit)=Button(this).apply{this.text=t;textSize=11f;isAllCaps=false;setOnClickListener{a()}}
    private fun label(t:String)=TextView(this).apply{this.text=t;textSize=11f;setTextColor(Color.WHITE);gravity=Gravity.CENTER_VERTICAL}
    private fun dp(v:Int)=(v*resources.displayMetrics.density).toInt()
    private fun matchWrap()=LinearLayout.LayoutParams(-1,-2)
    private fun wrap()=LinearLayout.LayoutParams(-2,-2)
    private fun weight()=LinearLayout.LayoutParams(0,dp(42),1f)
    private fun toast(m:String)=Toast.makeText(this,m,Toast.LENGTH_SHORT).show()
    private data class BrowserTab(val webView:WebView,var lastUrl:String)
    private class FrameHost(a:Activity):LinearLayout(a){init{orientation=VERTICAL};fun show(v:View){(v.parent as?ViewGroup)?.removeView(v);removeAllViews();addView(v,LayoutParams(-1,-1))}}
}
