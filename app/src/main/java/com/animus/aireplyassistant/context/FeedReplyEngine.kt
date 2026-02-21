package com.animus.aireplyassistant.context

import android.view.accessibility.AccessibilityNodeInfo
import com.animus.aireplyassistant.generation.ControlsBlock
import com.animus.aireplyassistant.generation.ReplySuggestionsRequest

object FeedReplyEngine {
    fun buildRequest(
        packageName: String,
        root: AccessibilityNodeInfo,
        controls: ControlsBlock,
        pointerHint: ContextEngine.PointerHint,
    ): ReplySuggestionsRequest {
        val base = ContextEngine.buildContext(
            packageName = packageName,
            root = root,
            controls = controls,
            pointerHint = pointerHint,
        ).copy(
            surface = "pointer",
            desiredCount = 3,
        )

        val normalizedReplyType = when (base.context.replyType.trim().lowercase()) {
            "comment",
            "post",
            -> base.context.replyType
            else -> "comment"
        }

        if (normalizedReplyType == base.context.replyType) return base
        return base.copy(
            context = base.context.copy(replyType = normalizedReplyType),
        )
    }

    fun isLikelyMetadataLine(text: String): Boolean {
        val normalized = text
            .trim()
            .lowercase()
            .replace('·', ' ')
            .replace('|', ' ')
            .replace('•', ' ')
            .replace(Regex("\\s+"), " ")
            .trim()

        if (normalized.isBlank()) return true

        val hasTime = Regex("\\b\\d{1,2}:\\d{2}\\s?(am|pm)\\b").containsMatchIn(normalized)
        val hasDate = Regex("\\b\\d{1,2}\\s+[a-z]{3}\\s+\\d{2,4}\\b").containsMatchIn(normalized)
        val hasMetricWord = Regex("\\b(views?|reposts?|likes?|bookmarks?|quotes?)\\b").containsMatchIn(normalized)
        val hasMetricPair = Regex(
            "\\b\\d{1,3}(?:[.,]\\d{3})*(?:[.,]\\d+)?\\s*(k|m|b)?\\s*(views?|reposts?|likes?|bookmarks?|quotes?)\\b",
        ).containsMatchIn(normalized)

        val letterCount = normalized.count { it.isLetter() }
        val wordCount = normalized.split(Regex("\\s+")).count { it.isNotBlank() }
        val mostlyMetrics = hasMetricPair && wordCount <= 14 && letterCount <= 44

        return (hasTime && (hasDate || hasMetricWord)) || mostlyMetrics
    }
}
