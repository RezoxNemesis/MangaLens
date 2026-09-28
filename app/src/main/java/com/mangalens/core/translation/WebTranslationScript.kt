package com.mangalens.core.translation

import java.util.Base64

object WebTranslationScript {
    fun build(targetLanguage: String): String {
        val language = targetLanguage.replace("'", "")
        return """
            (function() {
                const target = '$language';
                const walker = document.createTreeWalker(document.body, NodeFilter.SHOW_TEXT);
                const nodes = [];
                while (walker.nextNode()) {
                    const node = walker.currentNode;
                    const text = (node.nodeValue || '').trim();
                    if (text.length > 1 && node.parentElement &&
                        !['SCRIPT','STYLE','NOSCRIPT','TEXTAREA','INPUT'].includes(node.parentElement.tagName)) {
                        nodes.push(node);
                    }
                }
                window.__mangalensTranslationNodes = nodes;
                window.__mangalensTargetLanguage = target;
                return nodes.length;
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
