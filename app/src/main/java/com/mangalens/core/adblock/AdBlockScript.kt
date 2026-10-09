package com.mangalens.core.adblock

/** One idempotent document guard; scripts stay compatible with older supported WebView providers. */
internal object AdBlockScript {
    private fun literal(values: Set<String>): String = values.joinToString(",", "[", "]") {
        "'" + it.replace("\\", "\\\\").replace("'", "\\'") + "'"
    }

    fun disable(): String = "window.__mangalensAdGuardV2 && window.__mangalensAdGuardV2.stop();"

    fun build(): String = script

    private val script: String by lazy { """
        (function(){
          if(window.top!==window) return;
          var existing=window.__mangalensAdGuardV2;
          if(existing && existing.enabled){ existing.schedule(document.documentElement||document); return; }
          var state={enabled:true}, pending=[], timer=null;
          var originals=new WeakMap(), skipTimes=new WeakMap();
          var hosts=${literal(AdBlockRequestPolicy.blockedDomains)};
          var adSegments=${literal(AdBlockRequestPolicy.explicitAdSegments)};
          var navigationSegments=${literal(AdBlockRequestPolicy.adNavigationSegments)};
          var queryKeys=${literal(AdBlockRequestPolicy.adQueryKeys)};
          var trackerFilenames=${literal(AdBlockRequestPolicy.trackerFilenames)};
          var selectors=[
            '[id="ad" i]','[id^="ad-" i]','[class~="ad" i]','[class~="ads" i]','[class*="ad-banner" i]',
            '[class*="ad-container" i]','[class*="ad-wrapper" i]','[class*="ad-slot" i]',
            '[id*="ad-container" i]','[id*="ad-wrapper" i]','[id*="ad-slot" i]',
            '[class*="popup-ad" i]','[class*="ad-popup" i]','[class*="overlay-ad" i]',
            '[id*="popup-ad" i]','[id*="ad-popup" i]','[data-ad-slot]',
            'iframe[src*="doubleclick.net" i]','iframe[src*="googlesyndication.com" i]',
            'iframe[src*="exoclick.com" i]','iframe[src*="trafficjunky.net" i]',
            'iframe[src*="popads.net" i]','iframe[src*="clickadu.com" i]',
            'ytd-display-ad-renderer','ytd-ad-slot-renderer',
            'ytd-promoted-sparkles-web-renderer','ytm-promoted-sparkles-web-renderer',
            '.ytp-ad-overlay-container','.ytp-ad-message-container'
          ].join(',');
          var skipSelector='.ytp-ad-skip-button,.ytp-ad-skip-button-modern,button.ytp-skip-ad-button,.videoAdUiSkipButton';
          var provider=/^(ytd-display-ad-renderer|ytd-ad-slot-renderer|ytd-promoted-sparkles-web-renderer|ytm-promoted-sparkles-web-renderer)$/i;
          function site(host){ return location.hostname===host || location.hostname.slice(-(host.length+1))==='.'+host; }
          function matches(node,selector){ return node && node.nodeType===1 && node.matches && node.matches(selector); }
          function blocked(value,kind){
            try{
              var u=new URL(String(value||''),location.href), host=u.hostname.toLowerCase();
              if(u.protocol!=='http:' && u.protocol!=='https:') return false;
              if(host==='cloudflare.com' || /\.cloudflare\.com$/.test(host)) return false;
              for(var i=0;i<hosts.length;i++){
                if(host===hosts[i] || host.slice(-(hosts[i].length+1))==='.'+hosts[i]) return true;
              }
              var pathname=u.pathname;
              try{ pathname=decodeURIComponent(pathname); }catch(_){}
              var segments=pathname.toLowerCase().split('/'), allowed=kind==='navigation'?navigationSegments:adSegments;
              for(var j=0;j<segments.length;j++) if(allowed.indexOf(segments[j].split('.')[0])!==-1) return true;
              if(kind==='navigation') return false;
              if(trackerFilenames.indexOf(segments[segments.length-1])!==-1) return true;
              var parameters=u.search.slice(1).split('&');
              for(var k=0;k<parameters.length;k++){
                var key=parameters[k].split('=')[0];
                try{ key=decodeURIComponent(key.replace(/\+/g,' ')); }catch(_){}
                if(queryKeys.indexOf(key.toLowerCase())!==-1) return true;
              }
            }catch(_){}
            return false;
          }
          function protectedContent(node,explicit){
            if(/^(VIDEO|AUDIO|IMG)$/.test(node.tagName)) return true;
            if(node.tagName==='IFRAME' && !blocked(node.getAttribute('src'),'network')) return true;
            if(node.closest && node.closest('form,[role="dialog"],[aria-modal="true"]')) return true;
            if(node.querySelector && node.querySelector('form,input[type="password"],'+skipSelector)) return true;
            if(!explicit && node.querySelector && node.querySelector('video,audio')) return true;
            var frames=!explicit && node.querySelectorAll?node.querySelectorAll('iframe'):[];
            for(var i=0;i<frames.length;i++) if(!blocked(frames[i].getAttribute('src'),'network')) return true;
            return false;
          }
          function restore(node){
            var saved=originals.get(node); if(!saved) return;
            if(node.style.getPropertyValue('display')==='none' && node.style.getPropertyPriority('display')==='important')
              node.style.setProperty('display',saved.display,saved.displayPriority);
            if(node.style.getPropertyValue('visibility')==='hidden' && node.style.getPropertyPriority('visibility')==='important')
              node.style.setProperty('visibility',saved.visibility,saved.visibilityPriority);
            originals.delete(node); node.removeAttribute('data-mangalens-ad-hidden');
          }
          function hide(node,explicit,reason){
            if(!state.enabled || !node) return;
            if(protectedContent(node,explicit)){ restore(node); return; }
            if(!originals.has(node)){
              originals.set(node,{display:node.style.getPropertyValue('display'),displayPriority:node.style.getPropertyPriority('display'),
                visibility:node.style.getPropertyValue('visibility'),visibilityPriority:node.style.getPropertyPriority('visibility')});
              node.setAttribute('data-mangalens-ad-hidden','1');
            }
            originals.get(node).reason=reason||'selector';
            if(node.style.getPropertyValue('display')!=='none' || node.style.getPropertyPriority('display')!=='important')
              node.style.setProperty('display','none','important');
            if(node.style.getPropertyValue('visibility')!=='hidden' || node.style.getPropertyPriority('visibility')!=='important')
              node.style.setProperty('visibility','hidden','important');
          }
          function collect(root,selector){
            var items=[];
            if(matches(root,selector)) items.push(root);
            if(root && root.querySelectorAll){ var nodes=root.querySelectorAll(selector); for(var i=0;i<nodes.length;i++) items.push(nodes[i]); }
            return items;
          }
          function sponsored(root){
            var items,i,j,labels,advertised,saved;
            if(site('instagram.com')){
              items=collect(root,'article');
              for(i=0;i<items.length;i++){
                labels=items[i].querySelectorAll('header span,[data-testid="sponsored-label"],[aria-label="Sponsored"]');
                advertised=false;
                for(j=0;j<labels.length;j++){
                  if(labels[j].closest && labels[j].closest('a')) continue;
                  if(/^sponsored$/i.test((labels[j].textContent||'').trim())){ advertised=true; break; }
                }
                saved=originals.get(items[i]);
                if(advertised) hide(items[i],true,'sponsored');
                else if(saved && saved.reason==='sponsored') restore(items[i]);
              }
            }
            if(site('youtube.com')){
              items=collect(root,'ytd-rich-item-renderer,ytd-video-renderer,ytd-compact-video-renderer');
              for(i=0;i<items.length;i++){
                labels=items[i].querySelectorAll('ytd-ad-badge-renderer,.badge-style-type-ad,[data-testid="ad-label"]');
                saved=originals.get(items[i]);
                if(labels.length) hide(items[i],true,'sponsored');
                else if(saved && saved.reason==='sponsored') restore(items[i]);
              }
            }
          }
          function skipAllowed(){
            if(!site('youtube.com')) return;
            var button=document.querySelector(skipSelector);
            if(!button || button.disabled || button.getAttribute('aria-disabled')==='true' || !button.getClientRects().length) return;
            var style=window.getComputedStyle(button);
            if(style.display==='none' || style.visibility==='hidden' || style.opacity==='0') return;
            var now=Date.now(), previous=skipTimes.get(button);
            if(previous!==undefined && now-previous<1000) return;
            skipTimes.set(button,now); button.click();
          }
          function scan(root){
            if(!state.enabled || !root) return;
            skipAllowed();
            var saved=root.nodeType===1?originals.get(root):null;
            if(saved && saved.reason!=='sponsored' && !matches(root,selectors)) restore(root);
            var nodes=collect(root,selectors);
            for(var i=0;i<nodes.length;i++) hide(nodes[i],provider.test(nodes[i].tagName));
            sponsored(root);
          }
          function flush(){
            timer=null;
            var roots=pending; pending=[];
            for(var i=0;i<roots.length;i++) scan(roots[i]);
          }
          function schedule(root){
            if(!state.enabled || !root) return;
            if(root.nodeType!==1 && root.nodeType!==9) root=root.parentElement;
            if(!root) return;
            if(pending.indexOf(root)===-1) pending.push(root);
            if(pending.length>64) pending=[document.documentElement||document];
            if(timer===null) timer=setTimeout(flush,16);
          }
          var observer=new MutationObserver(function(records){
            if(!state.enabled) return;
            for(var i=0;i<records.length;i++){
              var record=records[i];
              if(record.type==='childList'){
                for(var j=0;j<record.addedNodes.length;j++) schedule(record.addedNodes[j]);
                if(record.target.nodeType===1 && originals.has(record.target)) schedule(record.target);
              }else schedule(record.target);
              var parent=record.target.nodeType===1?record.target:record.target.parentElement;
              var owned=parent;
              while(owned){
                if(originals.has(owned)) schedule(owned);
                owned=owned.parentElement;
              }
              var item=parent && parent.closest && parent.closest('article,ytd-rich-item-renderer,ytd-video-renderer,ytd-compact-video-renderer');
              if(item) schedule(item);
            }
          });
          var nativeOpen=window.open, nativeFetch=window.fetch, nativeBeacon=navigator.sendBeacon;
          var nativeXhr=XMLHttpRequest.prototype.open;
          function open(target,name,features){ if(state.enabled && blocked(target,'navigation')) return null; return nativeOpen.apply(window,arguments); }
          function fetch(input,init){
            var target=typeof input==='string'?input:(input && input.url)||'';
            if(state.enabled && blocked(target,'network')) return Promise.resolve(new Response(null,{status:204}));
            return nativeFetch.apply(window,arguments);
          }
          function beacon(target,data){ if(state.enabled && blocked(target,'network')) return true; return nativeBeacon.apply(navigator,arguments); }
          function xhr(method,target){
            if(state.enabled && blocked(target,'network')){
              var args=Array.prototype.slice.call(arguments); args[1]='data:text/plain,';
              return nativeXhr.apply(this,args);
            }
            return nativeXhr.apply(this,arguments);
          }
          function click(event){
            var anchor=event.target && event.target.closest && event.target.closest('a');
            if(state.enabled && anchor && blocked(anchor.href,'navigation')){ event.preventDefault(); event.stopImmediatePropagation(); }
          }
          state.schedule=schedule;
          state.stop=function(){
            state.enabled=false; observer.disconnect(); if(timer!==null) clearTimeout(timer); timer=null; pending=[];
            document.removeEventListener('click',click,true);
            if(window.open===open) window.open=nativeOpen;
            if(window.fetch===fetch) window.fetch=nativeFetch;
            if(navigator.sendBeacon===beacon) navigator.sendBeacon=nativeBeacon;
            if(XMLHttpRequest.prototype.open===xhr) XMLHttpRequest.prototype.open=nativeXhr;
            var nodes=document.querySelectorAll('[data-mangalens-ad-hidden]');
            for(var i=0;i<nodes.length;i++){
              restore(nodes[i]);
            }
            if(window.__mangalensAdGuardV2===state) delete window.__mangalensAdGuardV2;
          };
          window.__mangalensAdGuardV2=state;
          if(typeof nativeOpen==='function') window.open=open;
          if(typeof nativeFetch==='function') window.fetch=fetch;
          if(typeof nativeBeacon==='function') navigator.sendBeacon=beacon;
          XMLHttpRequest.prototype.open=xhr;
          document.addEventListener('click',click,true);
          observer.observe(document,{subtree:true,childList:true,attributes:true,characterData:true,
            attributeFilter:['class','id','style','src','data-ad-slot','disabled','aria-disabled','data-testid','aria-label','role','aria-modal']});
          schedule(document.documentElement||document);
        })();
    """.trimIndent() }
}
