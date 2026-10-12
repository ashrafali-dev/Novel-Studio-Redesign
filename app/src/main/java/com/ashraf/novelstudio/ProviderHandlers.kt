package com.ashraf.novelstudio

import org.json.JSONObject

object ProviderHandlers {
    fun inputScript(url: String, prompt: String): String {
        val host = url.lowercase()
        val promptJson = JSONObject.quote(prompt)
        val selectors = when {
            "gemini.google.com" in host ->
                listOf("rich-textarea [contenteditable=true]", "[contenteditable=true]", "textarea")
            "claude.ai" in host ->
                listOf("div[contenteditable=true]", "[contenteditable=true]", "textarea")
            "chat.deepseek.com" in host || "deepseek.com" in host ->
                listOf("textarea", "div[contenteditable=true]", "[contenteditable=true]")
            "grok.com" in host || "x.com/i/grok" in host ->
                listOf("textarea", "[contenteditable=true]")
            else ->
                listOf("#prompt-textarea", "[contenteditable=true]", "textarea")
        }.joinToString(prefix = "[", postfix = "]") { JSONObject.quote(it) }

        val sendSelectors = when {
            "gemini.google.com" in host ->
                listOf("button[aria-label*='Send']", "button[aria-label*='send']", "button[data-test-id*='send']")
            "claude.ai" in host ->
                listOf("button[aria-label*='Send']", "button[aria-label*='send']", "button[type='submit']")
            "deepseek.com" in host ->
                listOf("button[aria-label*='Send']", "button[type='submit']", "button[class*='send']")
            "grok.com" in host || "x.com/i/grok" in host ->
                listOf("button[aria-label*='Send']", "button[data-testid*='send']", "button[type='submit']")
            else ->
                listOf("button[data-testid='send-button']", "button[aria-label*='Send prompt']", "button[aria-label*='Send message']", "button[aria-label*='send']", "button[type='submit']")
        }.joinToString(prefix = "[", postfix = "]") { JSONObject.quote(it) }

        return """(function(){var p=__PROMPT__,s=__INPUT_SELECTORS__,el=null;for(var i=0;i<s.length&&!el;i++){try{var found=document.querySelector(s[i]);if(found&&found.offsetParent!==null)el=found}catch(e){}}if(!el)return false;el.focus();if(el.tagName==='TEXTAREA'||el.tagName==='INPUT'){var proto=el.tagName==='TEXTAREA'?HTMLTextAreaElement.prototype:HTMLInputElement.prototype;var d=Object.getOwnPropertyDescriptor(proto,'value');if(d&&d.set)d.set.call(el,p);else el.value=p;}else{try{document.execCommand('selectAll',false,null);document.execCommand('insertText',false,p)}catch(e){}if((el.innerText||el.textContent||'').trim()!==p.trim()){el.replaceChildren(document.createTextNode(p));}}try{el.dispatchEvent(new InputEvent('input',{bubbles:true,inputType:'insertText',data:p}))}catch(e){el.dispatchEvent(new Event('input',{bubbles:true}))}el.dispatchEvent(new Event('change',{bubbles:true}));setTimeout(function(){var bs=__SEND_SELECTORS__,b=null;for(var i=0;i<bs.length&&!b;i++){try{var x=document.querySelector(bs[i]);if(x&&x.offsetParent!==null&&!x.disabled)b=x}catch(e){}}if(b){b.click();return;}var e=new KeyboardEvent('keydown',{key:'Enter',code:'Enter',which:13,keyCode:13,bubbles:true});el.dispatchEvent(e);},180);return true;})()"""
            .replace("__PROMPT__", promptJson)
            .replace("__INPUT_SELECTORS__", selectors)
            .replace("__SEND_SELECTORS__", sendSelectors)
    }

    fun responseSnapshotScript(url: String): String {
        val host = url.lowercase()
        val selectors = when {
            "gemini.google.com" in host ->
                listOf("[data-message-author-role='model']", ".model-response-container", "model-response")
            "claude.ai" in host ->
                listOf("[data-testid*='chat-message-content']", "[class*='font-claude-message']", "[data-is-streaming]")
            "deepseek.com" in host ->
                listOf("[data-testid*='assistant']", "[class*='ds-markdown']")
            "grok.com" in host || "x.com/i/grok" in host ->
                listOf("[data-testid*='message'] [class*='markdown']", "[data-testid*='response']", "[class*='response-content']")
            else ->
                listOf("[data-message-author-role='assistant']")
        }.joinToString(prefix = "[", postfix = "]") { JSONObject.quote(it) }

        return """(function(){var s=__SELECTORS__,nodes=[];s.forEach(function(q){try{document.querySelectorAll(q).forEach(function(e){if(nodes.indexOf(e)<0)nodes.push(e)})}catch(e){}});nodes=nodes.filter(function(e){var text=(e.innerText||e.textContent||'').trim();if(text.length<40)return false;return !nodes.some(function(p){return p!==e&&p.contains(e)&&(p.innerText||p.textContent||'').trim().length>=text.length&&((p.innerText||p.textContent||'').trim().length-text.length)<Math.max(30,text.length*.08)})});nodes.sort(function(a,b){var p=a.compareDocumentPosition(b);return p&Node.DOCUMENT_POSITION_FOLLOWING?-1:p&Node.DOCUMENT_POSITION_PRECEDING?1:0});var last=nodes.length?nodes[nodes.length-1]:null;var text=last?(last.innerText||last.textContent||'').trim():'';var stream=false;if(last){var marker=last.closest('[data-is-streaming="true"],[aria-busy="true"]')||last.querySelector('[data-is-streaming="true"],[aria-busy="true"]');var state=[last.getAttribute('data-state')||'',last.getAttribute('aria-label')||'',String(last.className||'')].join(' ');stream=!!marker||/streaming|generating|thinking/i.test(state)}return JSON.stringify({count:nodes.length,text:text,streaming:stream})})()"""
            .replace("__SELECTORS__", selectors)
    }

    fun responseScript(url: String): String {
        val snapshot = responseSnapshotScript(url)
        return snapshot
    }

    fun nextChapterScript(selectorsJson: String): String =
        """(function(){var s=__SELECTORS__;for(var i=0;i<s.length;i++){try{var e=document.querySelector(s[i]);if(e&&e.offsetParent!==null&&!e.disabled){e.scrollIntoView({block:'center'});e.click();return true}}catch(e){}}var re=/(next chapter|next|下一章|下一页|下章|다음 화|次の話)/i;var all=[].slice.call(document.querySelectorAll('a,button,[role="button"]'));for(var i=0;i<all.length;i++){var e=all[i],label=[e.innerText||'',e.getAttribute('aria-label')||'',e.getAttribute('title')||''].join(' ').trim();if(re.test(label)&&e.offsetParent!==null&&!e.disabled){e.scrollIntoView({block:'center'});e.click();return true}}return false})()"""
            .replace("__SELECTORS__", selectorsJson)

    fun pageHookScript(selectorsJson: String): String =
        """(function(){var selectors=__SELECTORS__;window.__NovelStudio=window.__NovelStudio||{};window.__NovelStudio.extract=function(){var b=null;for(var i=0;i<selectors.length&&!b;i++){try{var best=-1;document.querySelectorAll(selectors[i]).forEach(function(e){var t=(e.innerText||e.textContent||'').trim();if(t.length>200&&t.length<120000){var paragraphs=e.querySelectorAll('p').length;var score=t.length+Math.min(paragraphs*65,1800);if(score>best){b=e;best=score}}})}catch(e){}}if(!b)b=document.querySelector('article,main,[role="main"]');var text=(b?(b.innerText||b.textContent):document.body.innerText||'').trim().replace(/\n{3,}/g,'\n\n');if(window.NovelStudio)NovelStudio.chapterPayload(JSON.stringify({title:document.title,url:location.href,text:text}));return !!b};if(!window.__NovelStudioListenersInstalled){window.__NovelStudioListenersInstalled=true;var notify=function(){setTimeout(function(){if(window.NovelStudio&&NovelStudio.routeChanged)NovelStudio.routeChanged(location.href)},120)};var push=history.pushState,replace=history.replaceState;history.pushState=function(){var result=push.apply(this,arguments);notify();return result};history.replaceState=function(){var result=replace.apply(this,arguments);notify();return result};window.addEventListener('popstate',notify);window.addEventListener('hashchange',notify)}})()"""
            .replace("__SELECTORS__", selectorsJson)
}
