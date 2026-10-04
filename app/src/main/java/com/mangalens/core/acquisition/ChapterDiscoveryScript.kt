package com.mangalens.core.acquisition

/** Does not click verification controls or navigate next-chapter links. */
object ChapterDiscoveryScript {
    fun script(): String = """
        (function() {
          const out=[], seen=new Set();
          const bad=/(?:^|[\/_.\s-])(captcha|recaptcha|hcaptcha|turnstile|spinner|placeholder|loading|logo|avatar|banner|advertisement)(?:$|[\/_.\s-])/i;
          const roots=document.querySelectorAll('${GenericMangaSourceAdapter.READER_SELECTORS}');
          function add(value, desc) {
            if(!value || bad.test(desc||'')) return;
            try {
              const u=new URL(value,location.href);
              if(!/^https?:$/.test(u.protocol)||u.username||u.password||bad.test(u.pathname)) return;
              u.hash=''; if(!seen.has(u.href)&&out.length<3000){seen.add(u.href);out.push(u.href);}
            }catch(e){}
          }
          roots.forEach(root=>{
            root.querySelectorAll('img').forEach(img=>{
              if(img.closest('.ads,.advertisement,.recommendations,[data-ad]')) return;
              const desc=[img.alt,img.className,img.id].join(' ');
              const candidates=['data-src','data-original','data-lazy-src','src'].map(k=>img.getAttribute(k)).filter(Boolean);
              const source=candidates.find(v=>!v.startsWith('data:')&&!bad.test(v));
              add(source||img.currentSrc,desc);
            });
            // Sources that publish all slides as data can be imported without advancing the carousel.
            ['data-pages','data-images'].forEach(key=>{
              try { const pages=JSON.parse(root.getAttribute(key)||'null');
                if(Array.isArray(pages)) pages.slice(0,3000).forEach(p=>add(typeof p==='string'?p:(p.url||p.src),''));
              }catch(e){}
            });
          });
          // Trigger ordinary lazy image loading with bounded scrolling, never CAPTCHA interaction.
          const target=roots[0];
          if(target&&target.scrollHeight>target.clientHeight) target.scrollTop=Math.min(target.scrollHeight,target.scrollTop+Math.max(600,target.clientHeight));
          window.scrollBy(0,Math.max(600,window.innerHeight));
          document.querySelectorAll('video,video source').forEach(el=>add(el.currentSrc||el.src,''));
          return encodeURIComponent(JSON.stringify(out));
        })();
    """.trimIndent()
}
