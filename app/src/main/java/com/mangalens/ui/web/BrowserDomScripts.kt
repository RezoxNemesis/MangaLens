package com.mangalens.ui.web

import org.json.JSONObject

/** Fixed main-frame operations only. Neither tool arguments nor page text become JavaScript. */
internal object BrowserDomScripts {
    private val helpers = """
        function clean(s,n){return String(s||'').replace(/[\u0000-\u001f\s]+/g,' ').trim().slice(0,n);}
        function visible(e){if(!e||!e.isConnected||!e.getClientRects().length)return false;
          var r=e.getBoundingClientRect(),w=window.innerWidth||document.documentElement.clientWidth,h=window.innerHeight||document.documentElement.clientHeight;
          if(!(r.width>0&&r.height>0&&r.bottom>0&&r.right>0&&r.top<h&&r.left<w))return false;
          var n=e,depth=0;for(;n&&depth<25;n=n.parentElement,depth++){
            if(n.hidden||n.hasAttribute('inert')||String(n.getAttribute('aria-hidden')).toLowerCase()==='true')return false;
            var s=getComputedStyle(n);if(s.display==='none'||s.visibility==='hidden'||s.visibility==='collapse'||!(Number(s.opacity)>0))return false;
          }return n===null;}
        function readable(e){if(!e||!e.isConnected||!e.getClientRects().length)return false;
          var n=e,depth=0;for(;n&&depth<25;n=n.parentElement,depth++){
            if(n.hidden||n.hasAttribute('inert')||String(n.getAttribute('aria-hidden')).toLowerCase()==='true')return false;
            var s=getComputedStyle(n);if(s.display==='none'||s.visibility==='hidden'||s.visibility==='collapse'||!(Number(s.opacity)>0))return false;
          }return n===null;}
        function actionable(e){if(!visible(e))return false;
          var r=e.getBoundingClientRect(),w=window.innerWidth||document.documentElement.clientWidth,h=window.innerHeight||document.documentElement.clientHeight;
          var left=Math.max(0,r.left),right=Math.min(w,r.right),top=Math.max(0,r.top),bottom=Math.min(h,r.bottom);
          if(!(right>left&&bottom>top))return false;
          var hit=document.elementFromPoint((left+right)/2,(top+bottom)/2);
          return !!hit&&(hit===e||e.contains(hit));}
        function label(e){var field=e.tagName==='INPUT'||e.tagName==='TEXTAREA';
          return clean(e.getAttribute('aria-label')||e.getAttribute('placeholder')||e.getAttribute('title')||(!field?e.innerText:''),120);}
        function forbidden(e){var form=e.closest('form');
          var s=clean([label(e),e.getAttribute('name'),e.id,e.getAttribute('autocomplete')].join(' '),400);
          return /\b(password|passwd|captcha|verification|verify|sign.?in|log.?in|one.?time|otp|2fa|mfa|drm|decrypt)\b/i.test(s)||
            !!(form&&form.querySelector('input[type="password"],input[autocomplete="one-time-code"],iframe'));}
        function fillable(e){var t=(e.getAttribute('type')||'text').toLowerCase();
          if(!(e.tagName==='TEXTAREA'||e.tagName==='INPUT'&&(t==='text'||t==='search'))||e.disabled||e.readOnly||forbidden(e))return false;
          var a=(e.getAttribute('autocomplete')||'').toLowerCase();
          var s=[label(e),e.getAttribute('name'),e.id,a].join(' ');
          return (!a||a==='off'||a==='on')&&!/\b(user.?name|email|e-mail|phone|tel|address|card|cc-|cvc|cvv|ssn|passport|account|secret|token|api.?key)\b/i.test(s);}
        function target(e){if(e.tagName==='A')return e.href||'';
          if(e.tagName==='VIDEO'||e.tagName==='AUDIO')return e.currentSrc||e.getAttribute('src')||'';return '';}
        function kind(e){if(e.tagName==='A')return /\bchapter\b|\/chapter[\/-]|[?&]chapter=/i.test(label(e)+' '+target(e))?'chapter_link':'link';
          if(e.tagName==='VIDEO'||e.tagName==='AUDIO')return 'media';if(fillable(e))return 'field';
          if(e.tagName==='BUTTON'&&(!e.disabled))return 'button';return '';}
        function signature(e){return JSON.stringify({tag:e.tagName,kind:kind(e),label:label(e),target:target(e),
          type:e.getAttribute('type')||'',disabled:!!e.disabled,readonly:!!e.readOnly,forbidden:forbidden(e),fillable:fillable(e)});}
        function locator(e){var parts=[];for(var depth=0;e&&e.nodeType===1&&depth<25;depth++,e=e.parentElement){
          var n=1;for(var p=e.previousElementSibling;p;p=p.previousElementSibling)if(p.tagName===e.tagName)n++;
          var tag=e.tagName.toLowerCase();if(!/^[a-z][a-z0-9-]{0,40}$/.test(tag))return '';
          parts.unshift(tag+':nth-of-type('+n+')');if(e===document.documentElement)return parts.join('>');}return '';}
    """.trimIndent()

    fun observe(query: String): String = """
        (function(){try{
          $helpers
          var q=${JSONObject.quote(query.lowercase())},rows=[],all=document.getElementsByTagName('*'),count=Math.min(all.length,2000);
          for(var i=0;i<count&&rows.length<40;i++){var e=all[i];if(!visible(e)||forbidden(e))continue;
            var k=kind(e);if(!k||k!=='media'&&!actionable(e)||q&&label(e).toLowerCase().indexOf(q)<0)continue;
            var loc=locator(e);if(!loc)continue;rows.push({locator:loc,signature:signature(e),kind:k,label:label(e),target:target(e)});}
          return JSON.stringify({url:location.href,title:clean(document.title,160),text:'',elements:rows,
            truncated:all.length>count||rows.length===40});
        }catch(e){return JSON.stringify({error:'DOM observation unavailable'});}})()
    """.trimIndent()

    fun extract(query: String): String = """
        (function(){try{
          $helpers
          var q=${JSONObject.quote(query.lowercase())},parts=[],length=0,count=0,truncated=false;
          var root=document.querySelector('article,main,[role="main"]')||document.body;
          var walker=document.createTreeWalker(root,NodeFilter.SHOW_TEXT,null,false),node;
          while((node=walker.nextNode())&&count++<4000){var p=node.parentElement;
            if(!p||!readable(p)||p.closest('script,style,noscript,nav,header,footer,aside,form,input,textarea,select,[contenteditable],iframe'))continue;
            var text=clean(node.textContent,6000);if(!text||q&&text.toLowerCase().indexOf(q)<0)continue;
            if(length+text.length+1>6000){parts.push(text.slice(0,Math.max(0,6000-length-1)));truncated=true;break;}
            parts.push(text);length+=text.length+1;}
          return JSON.stringify({url:location.href,title:clean(document.title,160),text:parts.join(' ').slice(0,6000),elements:[],truncated:truncated||count>=4000});
        }catch(e){return JSON.stringify({error:'Visible page extraction unavailable'});}})()
    """.trimIndent()

    fun element(action: String, locator: String, signature: String, text: String = ""): String {
        require(action in setOf("browser_click", "browser_fill") && BrowserDomPolicy.validLocator(locator))
        return """
            (function(){try{
              $helpers
              var e=document.querySelector(${JSONObject.quote(locator)});
              if(!actionable(e)||forbidden(e)||signature(e)!==${JSONObject.quote(signature)})return JSON.stringify({error:'Observed element changed'});
              var action=${JSONObject.quote(action)};
              if(action==='browser_fill'){
                if(!fillable(e))return JSON.stringify({error:'Sensitive or unavailable field'});
                var value=${JSONObject.quote(text)};
                var proto=e.tagName==='TEXTAREA'?HTMLTextAreaElement.prototype:HTMLInputElement.prototype;
                var setter=Object.getOwnPropertyDescriptor(proto,'value').set;setter.call(e,value);
                e.dispatchEvent(new Event('input',{bubbles:true}));e.dispatchEvent(new Event('change',{bubbles:true}));
              }else{if(['link','chapter_link','button'].indexOf(kind(e))<0)return JSON.stringify({error:'Element does not support a click'});e.click();}
              return JSON.stringify({url:location.href,status:'dispatched',action:action});
            }catch(e){return JSON.stringify({error:'Page action unavailable'});}})()
        """.trimIndent()
    }

    /** Native Reader selection only. Returns the exact observed href after current signature/hit testing. */
    fun chapterTarget(locator: String, signature: String): String {
        require(BrowserDomPolicy.validLocator(locator))
        return """
            (function(){try{
              $helpers
              var e=document.querySelector(${JSONObject.quote(locator)});
              if(!actionable(e)||forbidden(e)||kind(e)!=='chapter_link'||signature(e)!==${JSONObject.quote(signature)})return JSON.stringify({error:'Chapter link changed'});
              return JSON.stringify({url:location.href,target:target(e)});
            }catch(e){return JSON.stringify({error:'Chapter selection unavailable'});}})()
        """.trimIndent()
    }

    fun scroll(delta: Int): String {
        require(delta in -2_048..2_048 && delta != 0)
        return "(function(){try{window.scrollBy(0,$delta);return JSON.stringify({url:location.href,status:'dispatched',action:'browser_scroll'});}catch(e){return JSON.stringify({error:'Page scrolling unavailable'});}})()"
    }
}
