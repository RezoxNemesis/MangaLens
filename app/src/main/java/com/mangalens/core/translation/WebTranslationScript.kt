package com.mangalens.core.translation

import java.util.Base64

object WebTranslationScript {
    fun build(targetLanguage: String): String {
        val language = targetLanguage.filter { it.isLetterOrDigit() || it == '-' || it == '_' }.take(12)
        return """
            (function() {
                const target = '$language';
                if (window.__mangalensTranslationOff) window.__mangalensTranslationOff();
                const walker = document.createTreeWalker(document.body, NodeFilter.SHOW_TEXT);
                const nodes = [];
                while (nodes.length < 160 && walker.nextNode()) {
                    const node = walker.currentNode;
                    const text = (node.nodeValue || '').trim();
                    const parent = node.parentElement;
                    if (text.length > 1 && parent &&
                        !['SCRIPT','STYLE','NOSCRIPT','TEXTAREA','INPUT','CODE','PRE'].includes(parent.tagName) &&
                        !parent.closest('[data-mangalens-translation-ignore]') &&
                        parent.getClientRects().length > 0 &&
                        getComputedStyle(parent).visibility !== 'hidden') {
                        nodes.push(node);
                    }
                }
                window.__mangalensTranslationNodes = nodes;
                window.__mangalensTranslationOriginals = nodes.map(node => node.nodeValue);
                window.__mangalensTranslationApplied = [];
                window.__mangalensTargetLanguage = target;
                window.__mangalensTranslationOff = function() {
                    (window.__mangalensTranslationNodes || []).forEach(function(node, index) {
                        if (node && node.isConnected && node.ownerDocument === document &&
                            window.__mangalensTranslationApplied[index] !== undefined &&
                            node.nodeValue === window.__mangalensTranslationApplied[index] &&
                            window.__mangalensTranslationOriginals[index] !== undefined) {
                            node.nodeValue = window.__mangalensTranslationOriginals[index];
                        }
                    });
                    window.__mangalensTranslationApplied = [];
                };
                return JSON.stringify(nodes.map(node => node.nodeValue));
            })();
        """.trimIndent()
    }

    /** Acknowledges only an exact current connected node, never a stale original after async translation. */
    fun apply(nodeId: Int, translatedText: String, expectedOriginal: String? = null): String {
        require(nodeId in 0..159)
        val encoded = Base64.getEncoder().encodeToString(translatedText.toByteArray(Charsets.UTF_8))
        val expected = expectedOriginal?.let { Base64.getEncoder().encodeToString(it.toByteArray(Charsets.UTF_8)) }
        return """
            (function(){try{
                function decode(value){return new TextDecoder().decode(Uint8Array.from(atob(value),function(c){return c.charCodeAt(0);}));}
                var node=(window.__mangalensTranslationNodes||[])[$nodeId];
                var original=(window.__mangalensTranslationOriginals||[])[$nodeId];
                var expected=${expected?.let { "decode('$it')" } ?: "null"};
                if(!node||!node.isConnected||node.ownerDocument!==document||typeof original!=='string'||
                    expected!==null&&original!==expected||node.nodeValue!==original)return JSON.stringify({status:'changed'});
                var text=decode('$encoded');
                if(text===original)return JSON.stringify({status:'unchanged'});
                node.nodeValue=text;
                if(node.nodeValue!==text)return JSON.stringify({status:'unavailable'});
                (window.__mangalensTranslationApplied||(window.__mangalensTranslationApplied=[]))[$nodeId]=text;
                return JSON.stringify({status:'applied'});
            }catch(e){return JSON.stringify({status:'unavailable'});}})()
        """.trimIndent()
    }

    /** Android wraps a returned JavaScript string; other/unacknowledged shapes are not success. */
    fun applicationStatus(raw: String?): String? = runCatching {
        val value = org.json.JSONTokener(raw ?: return null).nextValue() as? String ?: return null
        org.json.JSONObject(value).getString("status").takeIf { it in setOf("applied", "unchanged", "changed", "unavailable") }
    }.getOrNull()
}
