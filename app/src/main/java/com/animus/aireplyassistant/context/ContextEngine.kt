package com.animus.aireplyassistant.context

import android.graphics.Rect
import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo
import com.animus.aireplyassistant.generation.ControlsBlock
import com.animus.aireplyassistant.generation.ReplyContextBlock
import com.animus.aireplyassistant.generation.ReplySuggestionsRequest
import java.util.Locale
import kotlin.math.abs
import kotlin.math.min

object ContextEngine {
    private val memory = SessionMemoryStore(ttlMs = 90_000L)

    data class PointerHint(
        val x: Int,
        val y: Int,
        val radiusPx: Int = 160,
    )

    fun buildContext(
        packageName: String,
        root: AccessibilityNodeInfo,
        controls: ControlsBlock,
        pointerHint: PointerHint? = null,
    ): ReplySuggestionsRequest {
        Log.d("ContextEngine", "ENTER buildContext")
        val focused = findFocusedEditable(root)
        val userDraft = focused?.text?.toString().orEmpty()

        val focusedBounds = focused?.let { Rect().also(it::getBoundsInScreen) }
        val visibleTextItems = ScreenTextExtractor.extractVisibleText(root, maxItems = 120)
        val appFlavor = AppFlavorResolver.resolve(packageName)

        val detectedReplyType = ReplyTypeDetector.detect(appFlavor, visibleTextItems)
        val pointerMode = pointerHint != null

        val selection = if (pointerMode) {
            null
        } else {
            TargetTextSelector.select(
                items = visibleTextItems,
                focusedBounds = focusedBounds,
                packageName = packageName,
                appFlavor = appFlavor,
                replyType = detectedReplyType,
                memory = memory,
            )
        }
        val pointerSelection = pointerHint?.let { hint ->
            PointerContextSelector.select(
                items = visibleTextItems,
                hint = hint,
            )
        }

        val usedPointerContext = pointerMode && pointerSelection?.primaryText?.isNotBlank() == true
        val primaryText = if (pointerMode) {
            pointerSelection?.primaryText.orEmpty()
        } else {
            selection?.primaryText.orEmpty()
        }
        val primarySource = if (pointerMode) {
            pointerSelection?.primarySource ?: "pointer_none"
        } else {
            selection?.decision?.primarySource.orEmpty()
        }
        val secondaryTexts = if (pointerMode) {
            pointerSelection?.secondaryTexts.orEmpty()
        } else {
            selection?.secondaryTexts.orEmpty()
        }
        val usedMemoryForScore = if (pointerMode) false else selection?.usedMemory ?: false
        val replyType = if (pointerMode) detectedReplyType else selection?.replyType ?: detectedReplyType
        val decision = if (pointerMode) {
            SelectionDecision(
                priorityTarget = ReplyPriorityTarget.UNKNOWN,
                commentScore = 0,
                postScore = 0,
                weakCaption = false,
                prioritizedCommentCount = 0,
                prioritizedPostCount = 0,
                primarySource = primarySource,
                secondarySource = if (secondaryTexts.isEmpty()) "pointer_none" else "pointer_region",
            )
        } else {
            selection?.decision ?: SelectionDecision(
                priorityTarget = ReplyPriorityTarget.UNKNOWN,
                commentScore = 0,
                postScore = 0,
                weakCaption = false,
                prioritizedCommentCount = 0,
                prioritizedPostCount = 0,
                primarySource = "unknown_none",
                secondarySource = "unknown_none",
            )
        }

        val style = LanguageStyleDetector.detect(userDraft, primaryText)
        val intent = IntentClassifier.detect(userDraft, primaryText)
        val tone = ToneTracker.detect(primaryText)

        var confidence = ContextConfidenceScorer.score(
            hasFocusedInput = focused != null,
            replyType = replyType,
            primaryText = primaryText,
            usedMemory = usedMemoryForScore,
            primarySource = primarySource,
            priorityTarget = decision.priorityTarget,
        )
        if (pointerMode && pointerSelection != null) {
            confidence = applyPointerConfidenceBoost(
                baseConfidence = confidence,
                nearbyCandidates = pointerSelection.candidateCount,
            )
        } else if (pointerMode && pointerSelection == null) {
            confidence = 0.0
        }

        val limitedSecondary = when {
            confidence < 0.35 -> emptyList()
            confidence < 0.65 -> secondaryTexts.take(1)
            else -> secondaryTexts.take(3)
        }

        val finalPrimary = when {
            confidence < 0.35 -> ""
            else -> primaryText
        }

        val ctx = ReplyContextBlock(
            replyType = replyType.apiValue,
            primaryText = finalPrimary,
            secondaryTexts = limitedSecondary,
            intent = intent,
            conversationTone = tone,
            userStyle = style,
            confidence = confidence,
        )

        Log.d(
            "ContextEngine",
            "pkg=${packageName.ifBlank { "unknown" }} " +
                "flavor=$appFlavor detected=$detectedReplyType resolved=$replyType " +
                "target=${decision.priorityTarget} cScore=${decision.commentScore} pScore=${decision.postScore} " +
                "weakCaption=${decision.weakCaption} cSignals=${decision.prioritizedCommentCount} pSignals=${decision.prioritizedPostCount} " +
                "primarySource=$primarySource secondarySource=${decision.secondarySource} " +
                "primaryLen=${primaryText.length} finalPrimaryLen=${finalPrimary.length} " +
                "secondaryCount=${limitedSecondary.size} usedMemory=$usedMemoryForScore " +
                "pointerMode=$pointerMode pointerItems=${pointerSelection?.candidateCount ?: 0} " +
                "conf=${formatConfidence(confidence)}"
        )

        return ReplySuggestionsRequest(
            context = ctx,
            controls = controls,
            userDraft = userDraft,
        )
    }

    fun buildRequest(
        packageName: String,
        root: AccessibilityNodeInfo,
        controls: ControlsBlock,
        pointerHint: PointerHint? = null,
    ): ReplySuggestionsRequest = buildContext(
        packageName = packageName,
        root = root,
        controls = controls,
        pointerHint = pointerHint,
    )

    fun findFocusedEditable(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        // Some apps don't set isFocused correctly on the input; this still works well enough for v1.
        val stack = ArrayDeque<AccessibilityNodeInfo>()
        stack.add(root)
        var visited = 0
        val maxNodesVisited = 2500

        while (stack.isNotEmpty()) {
            if (visited++ >= maxNodesVisited) return null
            val node = stack.removeLast()
            if (!node.isVisibleToUser) continue
            if (node.isEditable && node.isFocused) return node

            for (i in 0 until node.childCount) {
                node.getChild(i)?.let(stack::add)
            }
        }
        return null
    }

    fun isSensitiveInput(node: AccessibilityNodeInfo): Boolean {
        if (node.isPassword) return true

        val hint = node.hintText?.toString()?.lowercase().orEmpty()
        val id = node.viewIdResourceName?.lowercase().orEmpty()
        val joined = "$hint $id"
        val keywords = listOf("password", "passcode", "otp", "one time", "verification", "pin")
        return keywords.any { joined.contains(it) }
    }

    private fun applyPointerConfidenceBoost(
        baseConfidence: Double,
        nearbyCandidates: Int,
    ): Double {
        val densityBoost = min(nearbyCandidates.coerceAtLeast(0) / 10.0, 1.0) * 0.08
        val floor = 0.72 + densityBoost
        return maxOf(baseConfidence, floor).coerceAtMost(0.95)
    }

    private fun formatConfidence(value: Double): String = String.format(Locale.US, "%.2f", value)
}

private enum class AppFlavor {
    WHATSAPP,
    INSTAGRAM,
    X,
    YOUTUBE,
    OTHER,
}

private data class TargetSelectionResult(
    val primaryText: String,
    val secondaryTexts: List<String>,
    val usedMemory: Boolean,
    val replyType: ReplyType,
    val decision: SelectionDecision,
)

private data class SurfaceSignals(
    val commentBoost: Int = 0,
    val postBoost: Int = 0,
    val weakCaption: Boolean = false,
    val prioritizedCommentCandidates: List<String> = emptyList(),
    val prioritizedPostCandidates: List<String> = emptyList(),
)

private data class SelectionDecision(
    val priorityTarget: ReplyPriorityTarget,
    val commentScore: Int,
    val postScore: Int,
    val weakCaption: Boolean,
    val prioritizedCommentCount: Int,
    val prioritizedPostCount: Int,
    val primarySource: String,
    val secondarySource: String,
)

private data class PriorityDecision(
    val target: ReplyPriorityTarget,
    val commentScore: Int,
    val postScore: Int,
)

private data class PrimarySelection(
    val text: String,
    val source: String,
)

private data class SecondarySelection(
    val texts: List<String>,
    val source: String,
)

private data class PointerSelection(
    val primaryText: String,
    val secondaryTexts: List<String>,
    val primarySource: String,
    val candidateCount: Int,
)

private object PointerContextSelector {
    private data class Candidate(
        val text: String,
        val containsAnchor: Boolean,
        val distanceSq: Long,
        val score: Int,
    )

    fun select(
        items: List<VisibleTextItem>,
        hint: ContextEngine.PointerHint,
    ): PointerSelection? {
        if (items.isEmpty()) return null

        val firstRadius = hint.radiusPx.coerceIn(96, 320)
        val firstPass = selectInRadius(items = items, hint = hint, radius = firstRadius)
        if (firstPass != null) return firstPass

        // Retry once with a wider search radius for long posts where accessibility bounds are sparse.
        val secondRadius = maxOf(260, firstRadius).coerceIn(96, 320)
        if (secondRadius == firstRadius) return null
        return selectInRadius(items = items, hint = hint, radius = secondRadius)
    }

    private fun selectInRadius(
        items: List<VisibleTextItem>,
        hint: ContextEngine.PointerHint,
        radius: Int,
    ): PointerSelection? {
        val scanRect = Rect(
            hint.x - radius,
            hint.y - radius,
            hint.x + radius,
            hint.y + radius,
        )

        val nearby = items.asSequence()
            .mapNotNull { item ->
                val text = item.text.trim()
                if (text.isBlank() || isNoiseCandidate(text)) return@mapNotNull null
                if (!Rect.intersects(scanRect, item.bounds) && !item.bounds.contains(hint.x, hint.y)) return@mapNotNull null
                val distanceSq = distanceToBoundsSq(item.bounds, hint.x, hint.y)
                val containsAnchor = item.bounds.contains(hint.x, hint.y)
                val qualityScore = scoreCandidateText(text)
                val proximityScore = (170_000L / (distanceSq + 600L)).toInt().coerceIn(0, 70)
                val anchorBonus = if (containsAnchor) 12 else 0
                val totalScore = qualityScore + proximityScore + anchorBonus
                if (totalScore < 30) return@mapNotNull null

                Candidate(
                    text = text,
                    containsAnchor = containsAnchor,
                    distanceSq = distanceSq,
                    score = totalScore,
                )
            }
            .sortedWith(
                compareByDescending<Candidate> { it.score }
                    .thenBy<Candidate> { !it.containsAnchor }
                    .thenBy { it.distanceSq }
                    .thenByDescending { it.text.length },
            )
            .take(8)
            .toList()

        if (nearby.isEmpty()) return null

        val primaryCandidate = nearby.first()
        val requiredPrimaryScore = if (primaryCandidate.containsAnchor) 54 else 64
        if (primaryCandidate.score < requiredPrimaryScore) return null

        val primary = primaryCandidate.text
        val secondary = nearby
            .drop(1)
            .filter { it.score >= (primaryCandidate.score - 18) && it.score >= 52 }
            .map { it.text.trim() }
            .filter { it.isNotBlank() && !it.equals(primary, ignoreCase = true) }
            .distinct()
            .take(2)

        return PointerSelection(
            primaryText = primary,
            secondaryTexts = secondary,
            primarySource = "pointer_targeted",
            candidateCount = nearby.size,
        )
    }

    private fun distanceToBoundsSq(bounds: Rect, x: Int, y: Int): Long {
        val dx = when {
            x < bounds.left -> bounds.left - x
            x > bounds.right -> x - bounds.right
            else -> 0
        }
        val dy = when {
            y < bounds.top -> bounds.top - y
            y > bounds.bottom -> y - bounds.bottom
            else -> 0
        }
        return dx.toLong() * dx.toLong() + dy.toLong() * dy.toLong()
    }

    private fun isNoiseCandidate(text: String): Boolean {
        val t = text.trim().lowercase()
        if (t.length < 4 || t.length > 1600) return true
        if (!t.any(Char::isLetterOrDigit)) return true
        if (isLikelyMetadataLine(t)) return true

        val exact = setOf(
            "ai+",
            "follow",
            "following",
            "like",
            "likes",
            "reply",
            "replies",
            "comment",
            "comments",
            "share",
            "send",
            "post",
            "home",
            "profile",
            "notifications",
            "search",
        )
        if (exact.contains(t)) return true
        if (t.startsWith("replying to")) return true
        if (t.startsWith("post your reply")) return true
        if (t.startsWith("most relevant repl")) return true
        if (t.startsWith("draft:")) return true
        if (t.startsWith("capture mode:")) return true
        if (t.startsWith("sending this context")) return true
        if (Regex("^@?[a-z0-9._]{2,32}$").matches(t)) return true
        if (Regex("^@([a-z0-9_]{1,15})(\\s+@([a-z0-9_]{1,15}))*$").matches(t)) return true
        if (Regex("^\\d+([.,]\\d+)?\\s*(k|m|b)?\\s*(likes?|repl(?:y|ies)|views?)$").matches(t)) return true
        if (Regex("^\\d+\\s*(s|m|h|d|w|mo|y)$").matches(t)) return true
        if (t.startsWith("view ") && t.contains(" repl")) return true
        if (t.startsWith("see ") && t.contains(" more")) return true
        return false
    }

    private fun scoreCandidateText(text: String): Int {
        val t = text.trim()
        val compact = t.lowercase()
        val words = t.split(Regex("\\s+")).count { it.isNotBlank() }

        var score = 0
        score += when (t.length) {
            in 4..16 -> 10
            in 17..320 -> 16
            in 321..900 -> 13
            in 901..1600 -> 8
            else -> 3
        }

        score += when {
            words >= 4 -> 8
            words >= 2 -> 4
            else -> 1
        }

        if (t.any(Char::isLetter)) score += 2 else score -= 14
        if (t.any { it == '.' || it == '!' || it == '?' }) score += 2

        if (compact.startsWith("@") && words <= 3) score -= 10
        if (compact.startsWith("replying to")) score -= 20
        if (compact.startsWith("post your reply")) score -= 16
        if (compact.startsWith("most relevant repl")) score -= 16
        if (compact.startsWith("draft:")) score -= 10
        if (compact.contains("capture mode")) score -= 20
        if (compact.contains("sending this context")) score -= 20

        if (Regex("^@?[a-z0-9._]{2,32}$").matches(compact)) score -= 18
        if (Regex("^@([a-z0-9_]{1,15})(\\s+@([a-z0-9_]{1,15}))*$").matches(compact)) score -= 18
        if (Regex("^\\d+([.,]\\d+)?\\s*(k|m|b)?\\s*(likes?|repl(?:y|ies)|views?)$").matches(compact)) score -= 16
        if (Regex("^\\d+\\s*(s|m|h|d|w|mo|y)$").matches(compact)) score -= 16
        if (isLikelyMetadataLine(compact)) score -= 32

        return score
    }

    private fun isLikelyMetadataLine(text: String): Boolean {
        val t = text.trim().lowercase()
        if (t.isBlank()) return false

        val normalized = t
            .replace('·', ' ')
            .replace('|', ' ')
            .replace('•', ' ')
            .replace(Regex("\\s+"), " ")
            .trim()

        val hasTime = Regex("\\b\\d{1,2}:\\d{2}\\s?(am|pm)\\b").containsMatchIn(normalized)
        val hasDate = Regex("\\b\\d{1,2}\\s+[a-z]{3}\\s+\\d{2,4}\\b").containsMatchIn(normalized)
        val hasMetricWord = Regex("\\b(views?|reposts?|likes?|bookmarks?|quotes?)\\b").containsMatchIn(normalized)
        val hasMetricPair = Regex(
            "\\b\\d{1,3}(?:[.,]\\d{3})*(?:[.,]\\d+)?\\s*(k|m|b)?\\s*(views?|reposts?|likes?|bookmarks?|quotes?)\\b",
        ).containsMatchIn(normalized)

        val letterCount = normalized.count { it.isLetter() }
        val wordCount = normalized.split(Regex("\\s+")).count { it.isNotBlank() }
        val mostlyMetrics = hasMetricPair && wordCount <= 14 && letterCount <= 44

        if ((hasTime && (hasDate || hasMetricWord)) || mostlyMetrics) return true
        if (Regex("^\\d{1,2}:\\d{2}\\s?(am|pm)$").matches(normalized)) return true

        return false
    }
}

private object AppFlavorResolver {
    fun resolve(packageName: String): AppFlavor {
        val p = packageName.lowercase()
        return when {
            p.contains("whatsapp") -> AppFlavor.WHATSAPP
            p.contains("instagram") -> AppFlavor.INSTAGRAM
            p.contains("twitter") || p.contains("xcorp") -> AppFlavor.X
            p.contains("youtube") -> AppFlavor.YOUTUBE
            else -> AppFlavor.OTHER
        }
    }
}

private object TargetTextSelector {
    private object Thresholds {
        const val COMMENT_POST_MARGIN = 3
        const val MAX_COMMENT_TARGET_LEN = 260
        const val COMMENT_ROW_SHORT_TEXT_MAX = 180
        const val COMMENT_ROW_NEIGHBOR_WINDOW = 3
        const val COMMENT_ROW_MAX_VERTICAL_GAP_PX = 140
        const val COMMENT_SIGNALS_STRONG_COUNT = 4
        const val COMMENT_ROW_MIN_SCORE = 6
        const val COMMENT_ROW_MIN_SCORE_RELAXED = 5
        const val WEAK_CAPTION_MIN_COMMENT_CANDIDATES = 3
        const val WEAK_CAPTION_COMMENT_BONUS = 3
        const val INSTAGRAM_STRONG_POST_LENGTH = 55
        const val YOUTUBE_STRONG_POST_LENGTH = 70
        const val MIN_SURFACE_POST_SCORE = 7
    }

    fun select(
        items: List<VisibleTextItem>,
        focusedBounds: Rect?,
        packageName: String,
        appFlavor: AppFlavor,
        replyType: ReplyType,
        memory: SessionMemoryStore,
    ): TargetSelectionResult {
        val above = if (focusedBounds == null) {
            items
        } else {
            items.filter { it.bounds.bottom <= focusedBounds.top + 8 }
        }

        val sortedAll = items.sortedWith(compareBy({ it.bounds.bottom }, { it.bounds.left }))
        val sortedAbove = above.sortedWith(compareBy({ it.bounds.bottom }, { it.bounds.left }))
        val primaryCandidates = if (sortedAbove.isNotEmpty()) sortedAbove else sortedAll

        val surfaceSignals = extractSurfaceSignals(appFlavor, primaryCandidates)
        val priorityDecision = detectPriorityTarget(
            appFlavor = appFlavor,
            primaryCandidates = primaryCandidates,
            defaultReplyType = replyType,
            surfaceSignals = surfaceSignals,
        )
        val priorityTarget = priorityDecision.target
        val resolvedReplyType = resolveReplyType(appFlavor, replyType, priorityTarget)

        val primarySelection = selectPrimary(
            primaryCandidates = primaryCandidates,
            appFlavor = appFlavor,
            replyType = resolvedReplyType,
            priorityTarget = priorityTarget,
            surfaceSignals = surfaceSignals,
        )
        val primary = primarySelection.text

        val allowMemoryFallback = shouldUseMemoryFallback(primarySelection.source, surfaceSignals)
        val usedMemory = if (allowMemoryFallback && primary.length < 40) {
            val mem = memory.get(packageName)
            if (!mem.isNullOrBlank()) {
                true
            } else {
                false
            }
        } else false

        val primaryFinal = if (usedMemory) memory.get(packageName).orEmpty() else primary

        if (primaryFinal.isNotBlank()) memory.put(packageName, primaryFinal)

        val secondarySelection = selectSecondary(
            primaryCandidates = primaryCandidates,
            appFlavor = appFlavor,
            replyType = resolvedReplyType,
            priorityTarget = priorityTarget,
            surfaceSignals = surfaceSignals,
        )
        val secondary = secondarySelection.texts
        return TargetSelectionResult(
            primaryText = primaryFinal,
            secondaryTexts = secondary,
            usedMemory = usedMemory,
            replyType = resolvedReplyType,
            decision = SelectionDecision(
                priorityTarget = priorityTarget,
                commentScore = priorityDecision.commentScore,
                postScore = priorityDecision.postScore,
                weakCaption = surfaceSignals.weakCaption,
                prioritizedCommentCount = surfaceSignals.prioritizedCommentCandidates.size,
                prioritizedPostCount = surfaceSignals.prioritizedPostCandidates.size,
                primarySource = primarySelection.source,
                secondarySource = secondarySelection.source,
            ),
        )
    }

    private fun resolveReplyType(
        appFlavor: AppFlavor,
        detectedReplyType: ReplyType,
        priorityTarget: ReplyPriorityTarget,
    ): ReplyType {
        if (detectedReplyType == ReplyType.CHAT) return ReplyType.CHAT
        if (appFlavor !in setOf(AppFlavor.INSTAGRAM, AppFlavor.X, AppFlavor.YOUTUBE)) return detectedReplyType
        return when (priorityTarget) {
            ReplyPriorityTarget.POST -> ReplyType.POST
            ReplyPriorityTarget.COMMENT,
            ReplyPriorityTarget.UNKNOWN,
            -> ReplyType.COMMENT
        }
    }

    private fun shouldUseMemoryFallback(primarySource: String, surfaceSignals: SurfaceSignals): Boolean {
        if (primarySource.startsWith("surface_comment_signal")) return false
        if (primarySource.startsWith("unknown_structured_comment_row")) return false
        if (primarySource.startsWith("weak_caption_comment_priority")) return false
        if (surfaceSignals.prioritizedCommentCandidates.size >= Thresholds.COMMENT_SIGNALS_STRONG_COUNT) return false
        return true
    }

    private fun detectPriorityTarget(
        appFlavor: AppFlavor,
        primaryCandidates: List<VisibleTextItem>,
        defaultReplyType: ReplyType,
        surfaceSignals: SurfaceSignals,
    ): PriorityDecision {
        if (defaultReplyType == ReplyType.CHAT) {
            return PriorityDecision(
                target = ReplyPriorityTarget.UNKNOWN,
                commentScore = 0,
                postScore = 0,
            )
        }
        if (appFlavor !in setOf(AppFlavor.INSTAGRAM, AppFlavor.X, AppFlavor.YOUTUBE)) {
            return PriorityDecision(
                target = ReplyPriorityTarget.UNKNOWN,
                commentScore = 0,
                postScore = 0,
            )
        }
        if (primaryCandidates.isEmpty()) {
            return PriorityDecision(
                target = ReplyPriorityTarget.UNKNOWN,
                commentScore = 0,
                postScore = 0,
            )
        }

        val sample = primaryCandidates.takeLast(40).map { it.text.trim().lowercase() }
        var commentScore = 0
        var postScore = 0
        for (line in sample) {
            when {
                line.contains("replying to") -> commentScore += 4
                line.contains("write a reply") || line.contains("add a reply") -> commentScore += 3
                line.contains("reply to") -> commentScore += 2
                line.contains("view replies") || line.contains("view more repl") -> commentScore += 2
                line == "reply" || line == "replies" -> commentScore += 1
            }

            when {
                line.contains("add a comment") || line.contains("write a comment") -> postScore += 4
                line.contains("leave a comment") -> postScore += 3
                line.contains("comment as") -> postScore += 2
                line.contains("what do you think") || line.contains("share your thoughts") -> postScore += 2
            }
        }

        val nearby = primaryCandidates.takeLast(12).map { it.text }
        commentScore += nearby.count { isLikelyCommentTarget(it) && it.length <= Thresholds.MAX_COMMENT_TARGET_LEN }.coerceAtMost(4)
        postScore += nearby.count(::isLikelyPostTarget).coerceAtMost(3)
        commentScore += surfaceSignals.commentBoost
        postScore += surfaceSignals.postBoost

        if (surfaceSignals.weakCaption && surfaceSignals.prioritizedCommentCandidates.isNotEmpty()) {
            commentScore += Thresholds.WEAK_CAPTION_COMMENT_BONUS
        }

        val target = when {
            commentScore >= postScore + Thresholds.COMMENT_POST_MARGIN -> ReplyPriorityTarget.COMMENT
            postScore >= commentScore + Thresholds.COMMENT_POST_MARGIN -> ReplyPriorityTarget.POST
            else -> ReplyPriorityTarget.UNKNOWN
        }
        return PriorityDecision(target = target, commentScore = commentScore, postScore = postScore)
    }

    private fun selectPrimary(
        primaryCandidates: List<VisibleTextItem>,
        appFlavor: AppFlavor,
        replyType: ReplyType,
        priorityTarget: ReplyPriorityTarget,
        surfaceSignals: SurfaceSignals,
    ): PrimarySelection {
        if (primaryCandidates.isEmpty()) return PrimarySelection(text = "", source = "empty")

        return when {
            appFlavor == AppFlavor.WHATSAPP -> {
                PrimarySelection(
                    text = primaryCandidates
                        .takeLast(8)
                        .map { it.text }
                        .filter(::isLikelyContentLine)
                        .takeLast(6)
                        .joinToString("\n")
                        .trim(),
                    source = "whatsapp_recent_chat",
                )
            }

            appFlavor == AppFlavor.INSTAGRAM && replyType == ReplyType.CHAT -> {
                PrimarySelection(
                    text = primaryCandidates
                        .takeLast(10)
                        .map { it.text }
                        .filter(::isLikelyContentLine)
                        .takeLast(6)
                        .joinToString("\n")
                        .trim(),
                    source = "instagram_dm_recent_chat",
                )
            }

            appFlavor == AppFlavor.INSTAGRAM || appFlavor == AppFlavor.X || appFlavor == AppFlavor.YOUTUBE -> {
                when (priorityTarget) {
                    ReplyPriorityTarget.COMMENT -> {
                        val surfaceComment = surfaceSignals.prioritizedCommentCandidates.firstOrNull().orEmpty().trim()
                        when {
                            surfaceComment.isNotBlank() -> PrimarySelection(surfaceComment, "surface_comment_signal")
                            else -> {
                                val nearestComment = pickNearestCommentTarget(primaryCandidates)
                                when {
                                    nearestComment.isNotBlank() -> PrimarySelection(nearestComment, "nearest_comment_candidate")
                                    else -> {
                                        val surfacePost = surfaceSignals.prioritizedPostCandidates.firstOrNull().orEmpty().trim()
                                        when {
                                            surfacePost.isNotBlank() -> PrimarySelection(surfacePost, "surface_post_fallback")
                                            else -> PrimarySelection(pickPostTarget(primaryCandidates), "scored_post_fallback")
                                        }
                                    }
                                }
                            }
                        }
                    }

                    ReplyPriorityTarget.POST -> {
                        val surfacePost = surfaceSignals.prioritizedPostCandidates.firstOrNull().orEmpty().trim()
                        when {
                            surfacePost.isNotBlank() -> PrimarySelection(surfacePost, "surface_post_signal")
                            else -> {
                                val scoredPost = pickPostTarget(primaryCandidates)
                                when {
                                    scoredPost.isNotBlank() -> PrimarySelection(scoredPost, "scored_post_candidate")
                                    else -> {
                                        val surfaceComment = surfaceSignals.prioritizedCommentCandidates.firstOrNull().orEmpty().trim()
                                        when {
                                            surfaceComment.isNotBlank() -> PrimarySelection(surfaceComment, "surface_comment_fallback")
                                            else -> PrimarySelection(pickNearestCommentTarget(primaryCandidates), "nearest_comment_fallback")
                                        }
                                    }
                                }
                            }
                        }
                    }

                    ReplyPriorityTarget.UNKNOWN -> {
                        if (replyType == ReplyType.COMMENT) {
                            val forceStructuredScan =
                                surfaceSignals.prioritizedCommentCandidates.size >= Thresholds.COMMENT_SIGNALS_STRONG_COUNT
                            val structuredComment = pickStructuredCommentRow(
                                primaryCandidates = primaryCandidates,
                                relaxed = forceStructuredScan,
                            )
                            if (structuredComment.isNotBlank()) {
                                return PrimarySelection(
                                    text = structuredComment,
                                    source = if (forceStructuredScan) {
                                        "unknown_structured_comment_row_forced"
                                    } else {
                                        "unknown_structured_comment_row"
                                    },
                                )
                            }
                        }

                        if (surfaceSignals.weakCaption && surfaceSignals.prioritizedCommentCandidates.isNotEmpty()) {
                            PrimarySelection(
                                text = surfaceSignals.prioritizedCommentCandidates.first().trim(),
                                source = "weak_caption_comment_priority",
                            )
                        } else {
                            val nearestComment = pickNearestCommentTarget(primaryCandidates)
                            when {
                                nearestComment.isNotBlank() -> PrimarySelection(nearestComment, "unknown_nearest_comment")
                                else -> {
                                    val surfacePost = surfaceSignals.prioritizedPostCandidates.firstOrNull().orEmpty().trim()
                                    when {
                                        surfacePost.isNotBlank() -> PrimarySelection(surfacePost, "unknown_surface_post")
                                        else -> PrimarySelection(pickPostTarget(primaryCandidates), "unknown_scored_post")
                                    }
                                }
                            }
                        }
                    }
                }
            }

            else -> {
                PrimarySelection(
                    text = primaryCandidates.takeLast(16).joinToString("\n") { it.text }.trim(),
                    source = "generic_recent_content",
                )
            }
        }
    }

    private fun selectSecondary(
        primaryCandidates: List<VisibleTextItem>,
        appFlavor: AppFlavor,
        replyType: ReplyType,
        priorityTarget: ReplyPriorityTarget,
        surfaceSignals: SurfaceSignals,
    ): SecondarySelection {
        if (primaryCandidates.isEmpty()) {
            return SecondarySelection(texts = emptyList(), source = "empty")
        }

        return when {
            replyType == ReplyType.CHAT -> {
                SecondarySelection(
                    texts = primaryCandidates
                        .takeLast(12)
                        .map { it.text }
                        .filter(::isLikelyContentLine)
                        .distinct()
                        .takeLast(5),
                    source = "chat_recent_context",
                )
            }

            appFlavor == AppFlavor.INSTAGRAM || appFlavor == AppFlavor.X || appFlavor == AppFlavor.YOUTUBE -> {
                val structuredRows = detectStructuredCommentRows(primaryCandidates)
                val blendedCommentCandidates = (
                    structuredRows +
                        surfaceSignals.prioritizedCommentCandidates +
                        primaryCandidates
                            .takeLast(30)
                            .map { it.text }
                            .filter(::isLikelyCommentTarget)
                    ).map(String::trim)
                    .filter(String::isNotBlank)
                    .distinct()
                    .take(8)

                when (priorityTarget) {
                    ReplyPriorityTarget.POST -> {
                        SecondarySelection(
                            texts = blendedCommentCandidates.take(5),
                            source = "social_post_comment_secondary",
                        )
                    }

                    ReplyPriorityTarget.COMMENT,
                    ReplyPriorityTarget.UNKNOWN,
                    -> {
                        SecondarySelection(
                            texts = blendedCommentCandidates.take(5),
                            source = "social_comment_secondary",
                        )
                    }
                }
            }

            else -> {
                SecondarySelection(
                    texts = primaryCandidates
                        .takeLast(40)
                        .map { it.text }
                        .distinct()
                        .takeLast(5),
                    source = "generic_recent_secondary",
                )
            }
        }
    }

    private fun extractSurfaceSignals(
        appFlavor: AppFlavor,
        primaryCandidates: List<VisibleTextItem>,
    ): SurfaceSignals {
        if (primaryCandidates.isEmpty()) return SurfaceSignals()
        return when (appFlavor) {
            AppFlavor.INSTAGRAM -> extractInstagramSurfaceSignals(primaryCandidates)
            AppFlavor.YOUTUBE -> extractYouTubeSurfaceSignals(primaryCandidates)
            else -> SurfaceSignals()
        }
    }

    private fun extractInstagramSurfaceSignals(
        primaryCandidates: List<VisibleTextItem>,
    ): SurfaceSignals {
        val sample = primaryCandidates.takeLast(42).map { it.text.trim() }.filter(String::isNotBlank)
        if (sample.isEmpty()) return SurfaceSignals()

        var commentBoost = 0
        var postBoost = 0
        val comments = mutableListOf<String>()
        val posts = mutableListOf<String>()

        for ((index, text) in sample.withIndex()) {
            val line = text.lowercase()
            when {
                line.contains("add a comment") || line.contains("view all") && line.contains("comment") -> commentBoost += 4
                line.contains("replying to") -> commentBoost += 3
                line.contains("view more repl") || line.contains("view repl") -> commentBoost += 2
                line.contains("replies") || line.contains("reply") -> commentBoost += 1
            }

            when {
                line.contains("original audio") -> postBoost += 3
                line.contains("reel") && line.contains("audio") -> postBoost += 2
                line.contains("caption") -> postBoost += 2
            }

            if (isLikelyCommentTarget(text) && text.length <= Thresholds.MAX_COMMENT_TARGET_LEN) {
                val hasMetaNeighbor =
                    (index > 0 && isLikelyCommentMetaSignal(sample[index - 1])) ||
                        (index + 1 < sample.size && isLikelyCommentMetaSignal(sample[index + 1]))
                if (hasMetaNeighbor || looksConversationalLine(text)) {
                    comments.add(text)
                }
            }

            if (isLikelyPostTarget(text) && scorePostCandidate(text, index, sample.size) >= Thresholds.MIN_SURFACE_POST_SCORE) {
                posts.add(text)
            }
        }

        val weakCaption =
            posts.none { it.length >= Thresholds.INSTAGRAM_STRONG_POST_LENGTH } &&
                comments.size >= Thresholds.WEAK_CAPTION_MIN_COMMENT_CANDIDATES
        if (weakCaption) commentBoost += Thresholds.WEAK_CAPTION_COMMENT_BONUS

        return SurfaceSignals(
            commentBoost = commentBoost,
            postBoost = postBoost,
            weakCaption = weakCaption,
            prioritizedCommentCandidates = comments.distinct().take(6),
            prioritizedPostCandidates = posts.distinct().take(3),
        )
    }

    private fun extractYouTubeSurfaceSignals(
        primaryCandidates: List<VisibleTextItem>,
    ): SurfaceSignals {
        val sample = primaryCandidates.takeLast(44).map { it.text.trim() }.filter(String::isNotBlank)
        if (sample.isEmpty()) return SurfaceSignals()

        var commentBoost = 0
        var postBoost = 0
        val comments = mutableListOf<String>()
        val posts = mutableListOf<String>()

        for ((index, text) in sample.withIndex()) {
            val line = text.lowercase()
            when {
                line.contains("top comments") || line.contains("all comments") -> commentBoost += 4
                line.contains("add a comment") || line.contains("write a comment") -> commentBoost += 4
                line.contains("pinned by") -> commentBoost += 3
                line.contains("reply") || line.contains("replies") -> commentBoost += 2
            }

            when {
                line.contains("description") || line.contains("show more") -> postBoost += 3
                line.contains("views") && Regex(".*\\d.*").matches(line) -> postBoost += 2
                line.contains("watch on") || line.contains("chapters") -> postBoost += 1
            }

            if (isLikelyCommentTarget(text) && text.length <= Thresholds.MAX_COMMENT_TARGET_LEN) {
                val hasMetaNeighbor =
                    (index > 0 && isLikelyCommentMetaSignal(sample[index - 1])) ||
                        (index + 1 < sample.size && isLikelyCommentMetaSignal(sample[index + 1]))
                if (hasMetaNeighbor || looksConversationalLine(text)) {
                    comments.add(text)
                }
            }

            if (isLikelyPostTarget(text) && scorePostCandidate(text, index, sample.size) >= Thresholds.MIN_SURFACE_POST_SCORE) {
                posts.add(text)
            }
        }

        val weakCaption =
            posts.none { it.length >= Thresholds.YOUTUBE_STRONG_POST_LENGTH } &&
                comments.size >= Thresholds.WEAK_CAPTION_MIN_COMMENT_CANDIDATES
        if (weakCaption) commentBoost += Thresholds.WEAK_CAPTION_COMMENT_BONUS

        return SurfaceSignals(
            commentBoost = commentBoost,
            postBoost = postBoost,
            weakCaption = weakCaption,
            prioritizedCommentCandidates = comments.distinct().take(6),
            prioritizedPostCandidates = posts.distinct().take(3),
        )
    }

    private fun looksConversationalLine(text: String): Boolean {
        val t = text.trim()
        if (t.length < 8 || t.length > 280) return false
        if (!t.any(Char::isLetter)) return false
        val words = t.split(Regex("\\s+")).count { it.isNotBlank() }
        if (words < 2) return false
        if (t.startsWith("@") && words < 3) return false
        return true
    }

    private fun isLikelyCommentMetaSignal(text: String): Boolean {
        val t = text.trim().lowercase()
        if (t.isBlank()) return false
        if (Regex("^\\d+\\s*(s|m|h|d|w|mo|y)$").matches(t)) return true
        if (Regex("^\\d+\\s*(sec|secs|second|seconds|min|mins|minute|minutes|hour|hours|day|days|week|weeks|month|months|year|years)\\s+ago$").matches(t)) return true
        if (Regex("^\\d+([.,]\\d+)?\\s*(k|m)?\\s+likes?$").matches(t)) return true
        if (Regex("^@?[a-z0-9._]{2,32}$").matches(t)) return true
        if (t.contains("likes") || t.contains("repl")) return true
        return false
    }

    private fun isLikelyCommentTarget(text: String): Boolean {
        val t = text.trim()
        if (t.length < 8 || t.length > 500) return false
        if (isUiChrome(t)) return false
        val compact = t.lowercase()
        if (compact.startsWith("replying to")) return false
        if (Regex("^\\d+([.,]\\d+)?\\s*(k|m|b)?$").matches(compact)) return false
        return t.any(Char::isLetter)
    }

    private fun detectStructuredCommentRows(
        primaryCandidates: List<VisibleTextItem>,
        minScore: Int = Thresholds.COMMENT_ROW_MIN_SCORE,
    ): List<String> {
        if (primaryCandidates.isEmpty()) return emptyList()

        val scored = mutableListOf<Pair<String, Int>>()
        for ((index, item) in primaryCandidates.withIndex()) {
            val text = item.text.trim()
            if (!isLikelyCommentTarget(text)) continue

            val inlineUser = hasInlineUsernamePrefix(text)
            val bodyText = if (inlineUser) stripInlineUsernamePrefix(text) else text
            if (bodyText.length !in 8..Thresholds.COMMENT_ROW_SHORT_TEXT_MAX) continue

            val usernameAdjacent = inlineUser || hasAdjacentUsername(index, primaryCandidates)
            if (!usernameAdjacent) continue

            val replyUiAdjacent = hasAdjacentReplyUiSignal(index, primaryCandidates)
            if (!replyUiAdjacent) continue

            var score = 0
            if (inlineUser) score += 2
            if (usernameAdjacent) score += 3
            if (replyUiAdjacent) score += 3
            if (looksConversationalLine(bodyText)) score += 1
            if (bodyText.length <= 120) score += 1

            val distanceFromBottom = (primaryCandidates.size - index - 1).coerceAtLeast(0)
            score += (distanceFromBottom / 8).coerceAtMost(2)

            if (score >= minScore) {
                scored.add(bodyText to score)
            }
        }

        return scored
            .sortedByDescending { it.second }
            .map { it.first }
            .distinct()
            .take(6)
    }

    private fun pickStructuredCommentRow(
        primaryCandidates: List<VisibleTextItem>,
        relaxed: Boolean = false,
    ): String {
        val minScore = if (relaxed) {
            Thresholds.COMMENT_ROW_MIN_SCORE_RELAXED
        } else {
            Thresholds.COMMENT_ROW_MIN_SCORE
        }
        return detectStructuredCommentRows(primaryCandidates, minScore = minScore)
            .firstOrNull()
            .orEmpty()
            .trim()
    }

    private fun hasAdjacentUsername(index: Int, primaryCandidates: List<VisibleTextItem>): Boolean {
        val center = primaryCandidates[index]
        val start = (index - Thresholds.COMMENT_ROW_NEIGHBOR_WINDOW).coerceAtLeast(0)
        val end = (index + Thresholds.COMMENT_ROW_NEIGHBOR_WINDOW).coerceAtMost(primaryCandidates.lastIndex)

        for (neighborIndex in start..end) {
            if (neighborIndex == index) continue
            val neighbor = primaryCandidates[neighborIndex]
            val neighborText = neighbor.text.trim()
            if (!isLikelyUsernameToken(neighborText)) continue
            if (!isVerticallyAdjacent(center.bounds, neighbor.bounds)) continue
            if (!isRoughlySameRowColumn(center.bounds, neighbor.bounds)) continue
            return true
        }
        return false
    }

    private fun hasAdjacentReplyUiSignal(index: Int, primaryCandidates: List<VisibleTextItem>): Boolean {
        val center = primaryCandidates[index]
        val start = (index - Thresholds.COMMENT_ROW_NEIGHBOR_WINDOW).coerceAtLeast(0)
        val end = (index + Thresholds.COMMENT_ROW_NEIGHBOR_WINDOW).coerceAtMost(primaryCandidates.lastIndex)

        for (neighborIndex in start..end) {
            if (neighborIndex == index) continue
            val neighbor = primaryCandidates[neighborIndex]
            if (!isVerticallyAdjacent(center.bounds, neighbor.bounds)) continue
            val t = neighbor.text.trim().lowercase()
            if (isReplyUiAdjacencySignal(t)) return true
        }
        return false
    }

    private fun isReplyUiAdjacencySignal(text: String): Boolean {
        if (text.isBlank()) return false
        if (text.contains("reply") || text.contains("repl")) return true
        if (text.contains("add a comment") || text.contains("write a comment")) return true
        if (Regex("^\\d+\\s*(s|m|h|d|w|mo|y)$").matches(text)) return true
        if (Regex("^\\d+\\s*(sec|secs|second|seconds|min|mins|minute|minutes|hour|hours|day|days|week|weeks|month|months|year|years)\\s+ago$").matches(text)) return true
        if (Regex("^\\d+([.,]\\d+)?\\s*(k|m)?\\s+likes?$").matches(text)) return true
        if (text.contains("likes")) return true
        return false
    }

    private fun isLikelyUsernameToken(text: String): Boolean {
        val t = text.trim().trimEnd(':').lowercase()
        if (t.length !in 2..32) return false
        if (t.contains(' ')) return false
        if (isUiChrome(t)) return false
        if (Regex("^@?[a-z0-9._]{2,32}$").matches(t)) return true
        return false
    }

    private fun hasInlineUsernamePrefix(text: String): Boolean {
        val trimmed = text.trim()
        return Regex("^@?[a-z0-9._]{2,32}[:,]?\\s+.+", RegexOption.IGNORE_CASE).matches(trimmed)
    }

    private fun stripInlineUsernamePrefix(text: String): String {
        return text
            .trim()
            .replaceFirst(Regex("^@?[a-z0-9._]{2,32}[:,]?\\s+", RegexOption.IGNORE_CASE), "")
            .trim()
    }

    private fun isVerticallyAdjacent(a: Rect, b: Rect): Boolean {
        val gap = when {
            b.top > a.bottom -> b.top - a.bottom
            a.top > b.bottom -> a.top - b.bottom
            else -> 0
        }
        return gap <= Thresholds.COMMENT_ROW_MAX_VERTICAL_GAP_PX
    }

    private fun isRoughlySameRowColumn(a: Rect, b: Rect): Boolean {
        val dx = abs(a.left - b.left)
        return dx <= 220
    }

    private fun isLikelyPostTarget(text: String): Boolean {
        val t = text.trim()
        if (t.length < 20 || t.length > 500) return false
        if (!isLikelyContentLine(t)) return false
        val compact = t.lowercase()
        if (compact.startsWith("replying to")) return false
        if (compact.startsWith("@") && t.length < 40) return false
        if (compact.startsWith("#") && t.length < 20) return false
        if (Regex("^\\d+[smhdw]$").matches(compact)) return false
        return true
    }

    private fun pickNearestCommentTarget(primaryCandidates: List<VisibleTextItem>): String {
        return primaryCandidates
            .asReversed()
            .map { it.text }
            .firstOrNull { isLikelyCommentTarget(it) && it.length <= Thresholds.MAX_COMMENT_TARGET_LEN }
            .orEmpty()
            .trim()
    }

    private fun pickPostTarget(primaryCandidates: List<VisibleTextItem>): String {
        if (primaryCandidates.isEmpty()) return ""

        var best: String = ""
        var bestScore = Int.MIN_VALUE
        val total = primaryCandidates.size

        for ((index, item) in primaryCandidates.withIndex()) {
            val candidate = item.text.trim()
            val score = scorePostCandidate(candidate, index, total)
            if (score > bestScore) {
                bestScore = score
                best = candidate
            }
        }

        return best
    }

    private fun scorePostCandidate(text: String, index: Int, total: Int): Int {
        if (!isLikelyContentLine(text)) return -1000

        var score = 0
        val compact = text.lowercase()
        val length = text.length
        score += when {
            length in 45..280 -> 9
            length in 30..44 -> 6
            length in 261..420 -> 4
            else -> 1
        }

        val distanceFromBottom = (total - index - 1).coerceAtLeast(0)
        score += (distanceFromBottom / 4).coerceAtMost(4)

        if (compact.startsWith("@") && length < 40) score -= 4
        if (compact.startsWith("replying to")) score -= 6
        if (compact.contains("view replies")) score -= 5
        if (compact == "reply" || compact == "replies") score -= 5
        if (Regex("^\\d+[smhdw]$").matches(compact)) score -= 6

        if (text.any { it == '.' || it == '!' || it == '?' }) score += 1
        if (text.count { it == '#' } in 1..4) score += 1

        return score
    }

    private fun isLikelyContentLine(text: String): Boolean {
        val t = text.trim()
        if (t.length < 2 || t.length > 500) return false
        if (isUiChrome(t)) return false
        return t.any(Char::isLetterOrDigit)
    }

    private fun isUiChrome(text: String): Boolean {
        val t = text.trim().lowercase()
        val exact = setOf(
            "follow",
            "following",
            "like",
            "likes",
            "reply",
            "replies",
            "comment",
            "comments",
            "share",
            "send",
            "post",
            "repost",
            "retweet",
            "quote",
            "bookmarks",
            "views",
            "view",
            "message",
            "new message",
            "search",
            "home",
            "notifications",
            "profile",
            "settings",
        )
        if (exact.contains(t)) return true
        if (t.startsWith("view ") && t.contains(" repl")) return true
        if (t.startsWith("see ") && t.contains(" more")) return true
        return false
    }
}

private enum class ReplyPriorityTarget {
    COMMENT,
    POST,
    UNKNOWN,
}

private object ReplyTypeDetector {
    fun detect(appFlavor: AppFlavor, items: List<VisibleTextItem>): ReplyType {
        return when (appFlavor) {
            AppFlavor.WHATSAPP -> ReplyType.CHAT
            AppFlavor.INSTAGRAM -> if (looksLikeInstagramDm(items)) ReplyType.CHAT else ReplyType.COMMENT
            AppFlavor.X,
            AppFlavor.YOUTUBE,
            AppFlavor.OTHER,
            -> ReplyType.COMMENT
        }
    }

    private fun looksLikeInstagramDm(items: List<VisibleTextItem>): Boolean {
        val sample = items.takeLast(60).map { it.text.trim().lowercase() }
        if (sample.isEmpty()) return false

        val commentSignals = listOf("add a comment", "view replies", "view all comments")
        if (sample.any { line -> commentSignals.any(line::contains) }) return false

        val dmSignals = listOf("active now", "typing", "seen", "new message")
        if (sample.any { line -> dmSignals.any(line::contains) }) return true

        val shortLines = sample.count { it.length in 1..35 && !isUiToken(it) }
        return shortLines >= 20
    }

    private fun isUiToken(text: String): Boolean {
        val t = text.trim().lowercase()
        return t in setOf(
            "like",
            "reply",
            "replies",
            "share",
            "send",
            "post",
            "follow",
            "following",
            "comment",
            "comments",
            "home",
            "search",
            "profile",
        )
    }
}

private enum class ReplyType(val apiValue: String) {
    POST("post"),
    COMMENT("comment"),
    CHAT("chat"),
}

private object LanguageStyleDetector {
    private val hinglishMarkers = setOf(
        "bhai", "yaar", "kya", "kyu", "kyun", "mast", "bhot", "bahut", "nahi", "haan", "ha", "acha", "accha",
        "sahi", "bekar", "bakwas", "waah", "lol", "arre", "arey",
    )

    fun detect(userDraft: String, primary: String): String {
        val sample = (userDraft + " " + primary).lowercase()
        val words = sample.split(Regex("\\s+")).filter { it.isNotBlank() }.take(80)
        val hits = words.count {
            val cleaned = it.trim { ch -> !ch.isLetter() }
            hinglishMarkers.contains(cleaned)
        }
        return if (hits >= 2) "Hinglish" else "English"
    }
}

private object IntentClassifier {
    fun detect(userDraft: String, target: String): String {
        val d = userDraft.lowercase().trim()
        if (d.isBlank()) return "neutral"

        if (d.contains("?") || d.startsWith("why") || d.startsWith("how") || d.startsWith("kya") || d.startsWith("kyu")) {
            return "asking"
        }
        if (listOf("lol", "lmao").any { d.contains(it) }) return "joking"
        if (listOf("nice", "great", "awesome", "love", "mast", "sahi", "waah").any { d.contains(it) }) return "praising"
        if (listOf("bakwas", "bekar", "hate", "wtf", "stupid").any { d.contains(it) }) return "criticizing"
        if (listOf("no", "nah", "not really", "disagree").any { d.contains(it) }) return "disagreeing"

        return "neutral"
    }
}

private object ToneTracker {
    fun detect(text: String): String {
        val t = text.lowercase()
        val heated = listOf("idiot", "stupid", "bakwas", "hate").any { t.contains(it) }
        if (heated) return "heated"
        val playful = listOf("lol", "lmao", "bro").any { t.contains(it) }
        if (playful) return "playful"
        return "neutral"
    }
}

private object ContextConfidenceScorer {
    fun score(
        hasFocusedInput: Boolean,
        replyType: ReplyType,
        primaryText: String,
        usedMemory: Boolean,
        primarySource: String,
        priorityTarget: ReplyPriorityTarget,
    ): Double {
        var score = 0.0
        if (hasFocusedInput) score += 0.45
        score += when (replyType) {
            ReplyType.CHAT, ReplyType.COMMENT, ReplyType.POST -> 0.25
        }
        val unknownPrimary = primarySource.startsWith("unknown_")
        val lengthBoostCap = if (unknownPrimary) 0.18 else 0.30
        val lengthBoostNormalizer = if (unknownPrimary) 300.0 else 200.0
        score += min(primaryText.length / lengthBoostNormalizer, lengthBoostCap)
        if (unknownPrimary && primaryText.length < 24) {
            score -= 0.10
        }
        if (usedMemory) score -= 0.18
        var finalScore = score.coerceIn(0.0, 1.0)
        val fallbackPrimary = primarySource.contains("fallback", ignoreCase = true)
        if (priorityTarget == ReplyPriorityTarget.UNKNOWN || fallbackPrimary) {
            finalScore = min(finalScore, 0.85)
        }
        return finalScore
    }
}
