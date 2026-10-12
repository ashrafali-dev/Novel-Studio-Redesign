package com.ashraf.novelstudio

object ProviderHandlers {
    fun inputScript(url: String, prompt: String): String {
        val p = org.json.JSONObject.quote(prompt)
        val host = url.lowercase()
        val selectors = when {
            "gemini.google.com" in host ->
                "['rich-textarea[contenteditable=true]','[contenteditable=true]','textarea']"
            "claude.ai" in host ->
                "['div[contenteditable=true]','textarea']"
            "chat.deepseek.com" in host || "deepseek.com" in host ->
                "['textarea','[contenteditable=true]']"
            "grok.com" in host || "x.com/i/grok" in host ->
                "['textarea','[contenteditable=true]']"
            else ->
                "['textarea','[contenteditable=true]']"
        }
        return """(function(){var p=$p,s=$selectors,el=null;for(var i=0;i<s.length&&!el;i++){try{el=document.querySelector(s[i])}catch(e){}}if(!el)return false;el.focus();if(el.tagName==='TEXTAREA'){var d=Object.getOwnPropertyDescriptor(HTMLTextAreaElement.prototype,'value');if(d&&d.set)d.set.call(el,p);else el.value=p;}else{el.innerHTML='';el.textContent=p;}el.dispatchEvent(new Event('input',{bubbles:true}));el.dispatchEvent(new Event('change',{bubbles:true}));setTimeout(function(){var b=document.querySelector('button[type="submit"],button[aria-label*="Send"],button[data-testid*="send"],button[aria-label*="send"],button[title*="Send"]');if(b&&!b.disabled)b.click();else{var e=new KeyboardEvent('keydown',{key:'Enter',code:'Enter',which:13,keyCode:13,bubbles:true});el.dispatchEvent(e);}},120);return true;})()"""
            .replace("$p", p).replace("$selectors", selectors)
    }

    fun responseScript(url: String): String {
        val host = url.lowercase()
        return when {
            "gemini.google.com" in host ->
                """(function(){var a=[];document.querySelectorAll('[data-message-author-role="model"],.model-response-container,[class*="model-response"],message-content').forEach(function(e){var t=(e.innerText||e.textContent||'').trim();if(t.length>80)a.push(t)});return a.length?a[a.length-1]:''})()"""
            "claude.ai" in host ->
                """(function(){var a=[];document.querySelectorAll('[data-is-streaming="false"],[class*="font-claude-message"],div[class*="prose"]').forEach(function(e){var t=(e.innerText||e.textContent||'').trim();if(t.length>80)a.push(t)});return a.length?a[a.length-1]:''})()"""
            "deepseek.com" in host ->
                """(function(){var a=[];document.querySelectorAll('[class*="ds-markdown"],[class*="markdown"],[class*="message"]').forEach(function(e){var t=(e.innerText||e.textContent||'').trim();if(t.length>80)a.push(t)});return a.length?a[a.length-1]:''})()"""
            "grok.com" in host || "x.com/i/grok" in host ->
                """(function(){var a=[];document.querySelectorAll('[data-testid*="message"],[class*="message"],[class*="markdown"]').forEach(function(e){var t=(e.innerText||e.textContent||'').trim();if(t.length>80)a.push(t)});return a.length?a[a.length-1]:''})()"""
            else ->
                """(function(){var a=[];document.querySelectorAll('[data-message-author-role="assistant"],[class*="markdown"],article').forEach(function(e){var t=(e.innerText||e.textContent||'').trim();if(t.length>80)a.push(t)});return a.length?a[a.length-1]:''})()"""
        }
    }

    fun nextChapterScript(selectorsJson: String): String =
        """(function(){var s=$selectors;for(var i=0;i<s.length;i++){var e=document.querySelector(s[i]);if(e){e.scrollIntoView({block:'center'});e.click();return true;}}return false;})()"""
            .replace("$selectors", selectorsJson)

    fun pageHookScript(selectorsJson: String): String =
        """(function(){if(window.__NovelStudioInstalled)return;window.__NovelStudioInstalled=true;var s=$selectors;function extract(){var b=null,score=-1;s.forEach(function(q){document.querySelectorAll(q).forEach(function(e){var t=(e.innerText||e.textContent||'').trim(),x=t.length+Math.min(e.querySelectorAll('p').length*80,2000);if(t.length>200&&x>score){b=e;score=x;}})});if(!b)b=document.querySelector('article,main,[role="main"]');var t=(b?(b.innerText||b.textContent):document.body.innerText||'').trim().replace(/\n{3,}/g,'\n\n');if(window.NovelStudio)NovelStudio.chapterPayload(JSON.stringify({title:document.title,url:location.href,text:t}));}window.__NovelStudio={extract:extract};function route(){if(window.NovelStudio&&NovelStudio.routeChanged)NovelStudio.routeChanged(location.href);}var ps=history.pushState,rs=history.replaceState;history.pushState=function(){var r=ps.apply(this,arguments);setTimeout(route,50);return r};history.replaceState=function(){var r=rs.apply(this,arguments);setTimeout(route,50);return r};window.addEventListener('popstate',function(){setTimeout(route,50)});})()"""
            .replace("$selectors", selectorsJson)
}
