package com.mangalens.core.acquisition

/** Fixed extraction logic shared by both rendered chapter paths; no page code or arbitrary JS API. */
object ChapterImageExtractionScript {
    val extract: String = """
        (function(){
          var roots=document.querySelectorAll('${ChapterImageCandidates.READER_SELECTOR}');
          var nodes=document.querySelectorAll('img'), images=[], seen={}, budget=0, limit=${ChapterImageCandidates.MAX_IMAGES};
          var hasReader=roots.length>0, commercial=['girlfriendgpt.com','girlfriendgpt.ai','girlfriendgpt.co','veyragame.com'];
          function safe(v){
            if(!v || v.length>${ChapterImageCandidates.MAX_URL_CHARS}) return null;
            try{var u=new URL(v,location.href); if(!/^https?:${'$'}/.test(u.protocol)||u.username||u.password)return null;u.hash='';return u.href;}catch(_){return null;}
          }
          function largest(v){
            if(!v || v.length>32768)return null;
            var parts=v.split(','), best=null, score=-1;
            for(var j=0;j<parts.length && j<64;j++){
              var p=parts[j].trim().split(/\s+/), s=parseFloat(p[1])||1, u=safe(p[0]);
              if(u && s>score){best=u;score=s;}
            }return best;
          }
          function source(e){
            var keys=['data-original','data-src','data-lazy-src','data-url'];
            for(var j=0;j<keys.length;j++){var v=safe(e.getAttribute(keys[j]));if(v)return v;}
            var v=largest(e.getAttribute('data-srcset'))||largest(e.getAttribute('srcset'));if(v)return v;
            var picture=e.parentElement;
            if(picture && picture.tagName==='PICTURE'){
              var alternatives=picture.querySelectorAll('source');
              for(var j=0;j<alternatives.length && j<8;j++){v=largest(alternatives[j].getAttribute('data-srcset'))||largest(alternatives[j].getAttribute('srcset'));if(v)return v;}
            }
            return safe(e.currentSrc)||safe(e.getAttribute('src'));
          }
          function providerPage(e,u){
            if(!e.matches || !e.matches('img.imgholder'))return false;
            try{
              var selected=new URL(location.href), path=selected.pathname.split('/').filter(function(v){return v!=='';});
              if(!/^(?:www\.)?demonicscans\.org${'$'}/i.test(selected.hostname)||path.length<4||path.length>5||path[0]!=='title'||path[2]!=='chapter'||!/^\d+(?:\.\d+)?${'$'}/.test(path[3]))return false;
              var title=decodeURIComponent(path[1]).replace(/-/g,' '), label=(e.getAttribute('alt')||'').trim();
              var prefix=title+' Chapter '+path[3]+' ';
              if(label.toLowerCase().indexOf(prefix.toLowerCase())!==0)return false;
              var index=label.slice(prefix.length);if(!/^\d+${'$'}/.test(index)||Number(index)>=limit)return false;
              var image=new URL(u), parts=image.pathname.split('/').filter(function(v){return v!=='';});
              return /^(?:cdn\.demoniclibs\.com|cdn\.librarydm\.com)${'$'}/i.test(image.hostname)&&parts.length===3&&decodeURIComponent(parts[0]).toLowerCase()===title.toLowerCase()&&parts[1]===path[3]&&new RegExp('^'+Number(index)+'\\.(jpg|jpeg|png|webp|avif)${'$'}','i').test(parts[2]);
            }catch(_){return false;}
          }
          function standaloneScope(){
            return /^(?:www\.)?demonicscans\.org${'$'}/i.test(location.hostname)&&/^\/title\/[^/]+\/chapter\/\d+(?:\.\d+)?(?:\/[^/]*)?${'$'}/.test(location.pathname);
          }
          function promotionalLabel(label){
            var text=label.toLowerCase();
            var evidence=(/(?:girlfriend\s*gpt|veyragame)/.test(text)&&/(?:start chatting|chat now|play at|play now)/.test(text))||/(?:read ad free|upgrade to (?:our )?premium|join our discord|scanlation credits)/.test(text);
            if(!evidence)return false;
            var weak=['girlfriendgpt','girlfriend gpt','veyragame','start chatting','chat now','play at'];
            var markers=weak.concat(['read ad free','upgrade to our premium','upgrade to premium','join our discord','scanlation credits']);
            var parts=text.split(/[\r\n!?。！？]+|\.(?:\s+|${'$'})/), remaining=0;
            function letters(s){return (s.match(/[a-z\u0080-\uffff]/g)||[]).length;}
            for(var i=0;i<parts.length && i<128;i++){
              var part=parts[i], marker=null, index=part.length;
              for(var j=0;j<markers.length;j++){var at=part.indexOf(markers[j]);if(at>=0 && at<index){index=at;marker=markers[j];}}
              var story=marker===null?part:part.slice(0,index)+(weak.indexOf(marker)>=0?part.slice(index+marker.length):'');
              remaining+=letters(story.replace(/\b(?:chapter|episode|page)\s*[\d.:-]+\b/g,''));
            }
            return !(remaining>=48 && remaining>=letters(text)*0.30);
          }
          function reason(e){
            var p=e, label=((e.getAttribute('alt')||'')+' '+(e.getAttribute('title')||'')).slice(0,1000);
            for(var j=0;p && j<6;j++,p=p.parentElement){
              if(p.tagName==='BODY'||p.tagName==='HTML'||(p.matches && p.matches('${ChapterImageCandidates.READER_SELECTOR}')))break;
              var tokens=((p.id||'')+' '+(typeof p.className==='string'?p.className:'')).slice(0,2048);
              if(p.hasAttribute('data-ad-slot') || /(?:^|[\s_-])(?:ad|ads|advert|advertisement|advertising|adbanner|adslot|sponsored)(?:${'$'}|[\s_-])/i.test(tokens))return 'AD_CONTAINER';
              if(p.tagName==='A'){
                var href=safe(p.getAttribute('href'));
                if(href){var host=new URL(href).hostname.toLowerCase();for(var k=0;k<commercial.length;k++)
                  if(host!==location.hostname && (host===commercial[k]||host.slice(-(commercial[k].length+1))==='.'+commercial[k]))return 'COMMERCIAL_LINK';}
              }
            }
            if(/^(?:advertisement|sponsored|advert)\s*${'$'}/i.test(label.trim()))return 'AD_CONTAINER';
            // Only explicit metadata claims here; full-page OCR uses the stronger dialogue guard later.
            if(promotionalLabel(label))return 'PROMOTIONAL_LABEL';
            return null;
          }
          for(var i=0;i<nodes.length && i<6000 && images.length<limit;i++){
            var e=nodes[i], reader=e.closest && e.closest('${ChapterImageCandidates.READER_SELECTOR}');
            var u=source(e), promo=reason(e);
            var standalone=standaloneScope(), provider=standalone && e.matches && e.matches('img.imgholder') && (providerPage(e,u) || promo!==null);
            if(hasReader && !reader && !provider)continue;
            if(!hasReader && standalone && !provider)continue;
            if(!hasReader && e.closest && e.closest('nav,header,footer,aside,[role="navigation"],form,[role="dialog"],[aria-modal="true"]'))continue;
            if(e.closest && e.closest('form,[role="dialog"],[aria-modal="true"]'))continue;
            if(!u || seen[u])continue;
            if(/(?:google\.com\/recaptcha|gstatic\.com\/recaptcha|hcaptcha\.com\/|challenges\.cloudflare\.com\/|\/cdn-cgi\/challenge-platform\/)/i.test(u))continue;
            var row={url:u,promotion:promo};budget+=JSON.stringify(row).length;
            if(budget>1400000)return JSON.stringify({pageUrl:location.href,images:[],videos:[],error:'Image evidence exceeds the bounded limit'});
            seen[u]=true;images.push(row);
          }
          var videos=[], videoNodes=document.querySelectorAll('video,video source');
          for(var i=0;i<videoNodes.length && i<500;i++){
            var video=videoNodes[i], value=safe(video.currentSrc)||safe(video.getAttribute('src'));
            if(value && videos.indexOf(value)<0){budget+=JSON.stringify(value).length;if(budget>1450000)break;videos.push(value);}
          }
          return JSON.stringify({pageUrl:location.href,images:images,videos:videos});
        })();
    """.trimIndent()
}
