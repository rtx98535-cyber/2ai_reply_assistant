package com.animus.aireplyassistant.context

import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo

data class VisibleTextItem(
    val text: String,
    val bounds: Rect,
)

object ScreenTextExtractor {
    fun extractVisibleText(
        root: AccessibilityNodeInfo,
        maxItems: Int,
        maxNodesVisited: Int = 2000,
    ): List<VisibleTextItem> {
        val out = ArrayList<VisibleTextItem>(minOf(64, maxItems))
        val stack = ArrayDeque<AccessibilityNodeInfo>()
        stack.add(root)
        var visited = 0

        while (stack.isNotEmpty() && out.size < maxItems && visited < maxNodesVisited) {
            val node = stack.removeLast()
            visited++

            // Skip traversing invisible subtrees; this keeps traversal bounded on complex screens.
            if (!node.isVisibleToUser) continue

            run {
                val t = node.text?.toString()?.trim().orEmpty()
                if (t.isNotBlank() && t.length <= 2000 && !isNoiseText(t)) {
                    val r = Rect()
                    node.getBoundsInScreen(r)
                    out.add(VisibleTextItem(t, r))
                }
            }

            for (i in 0 until node.childCount) {
                node.getChild(i)?.let(stack::add)
            }
        }

        // Keep entries from different screen positions; pointer mode needs positional duplicates.
        val seen = HashSet<String>()
        return out.filter { item ->
            val normalized = item.text.lowercase().replace(Regex("\\s+"), " ").trim()
            val key = "$normalized|${item.bounds.left},${item.bounds.top},${item.bounds.right},${item.bounds.bottom}"
            seen.add(key)
        }
    }

    private fun isNoiseText(text: String): Boolean {
        val t = text.trim().lowercase()
        if (t.isBlank()) return true

        // Common feed/UI chrome labels we don't want in context.
        val exact = setOf(
            "share",
            "following",
            "follow",
            "subscribe",
            "subscribed",
            "like",
            "likes",
            "comment",
            "comments",
            "reply",
            "replies",
            "view replies",
            "view reply",
            "see translation",
            "translate",
            "more",
            "show more",
            "show less",
            "send",
            "post",
        )
        if (exact.contains(t)) return true

        // “View 12 replies”, “See more”, etc.
        if (t.startsWith("view ") && t.contains(" repl")) return true
        if (t.startsWith("see ") && t.contains(" more")) return true

        // Very short + UI-ish single tokens.
        if (t.length <= 2 && (t == "ok" || t == "kk")) return false
        if (t.length <= 3 && exact.contains(t)) return true

        return false
    }
}
