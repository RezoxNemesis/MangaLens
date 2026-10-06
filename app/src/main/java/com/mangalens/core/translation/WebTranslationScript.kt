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
                window.__mangalensTargetLanguage = target;
                window.__mangalensTranslationOff = function() {
                    (window.__mangalensTranslationNodes || []).forEach(function(node, index) {
                        if (node && node.isConnected && window.__mangalensTranslationOriginals[index] !== undefined) {
                            node.nodeValue = window.__mangalensTranslationOriginals[index];
                        }
                    });
                };
                return JSON.stringify(nodes.map(node => node.nodeValue));
            })();
        """.trimIndent()
    }

    fun apply(nodeId: Int, translatedText: String): String {
        val encoded = Base64.getEncoder().encodeToString(translatedText.toByteArray(Charsets.UTF_8))
        return "window.__mangalensTranslationNodes && window.__mangalensTranslationNodes[" + nodeId + "] ? " +
            "window.__mangalensTranslationNodes[" + nodeId + "].nodeValue = new TextDecoder().decode(" +
            "Uint8Array.from(atob('" + encoded + "'), function(c) { return c.charCodeAt(0); })) : null;"
    }
}
