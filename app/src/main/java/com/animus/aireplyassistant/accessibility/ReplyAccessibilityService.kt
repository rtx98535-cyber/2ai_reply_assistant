package com.animus.aireplyassistant.accessibility

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Intent
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.animus.aireplyassistant.automation.XGptAutomationCoordinator
import com.animus.aireplyassistant.context.ContextEngine
import com.animus.aireplyassistant.context.FeedReplyEngine
import com.animus.aireplyassistant.core.TextInserter
import com.animus.aireplyassistant.generation.ControlsBlock
import com.animus.aireplyassistant.generation.ReplyContextBlock
import com.animus.aireplyassistant.generation.ReplySuggestionsRepository
import com.animus.aireplyassistant.generation.ReplySuggestionsRequest
import com.animus.aireplyassistant.overlay.OverlayCapturePreview
import com.animus.aireplyassistant.overlay.OverlayController
import com.animus.aireplyassistant.overlay.OverlaySurfaceMode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class ReplyAccessibilityService : AccessibilityService() {
    private enum class TapDraftMode {
        ENHANCE,
        GRAMMAR,
    }

    private enum class ReplySurfaceMode {
        FEED_POINTER,
        UNSUPPORTED,
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val feedPackages = setOf(
        "com.twitter.android",
        "com.instagram.barcelona",
        "com.linkedin.android",
    )

    private lateinit var overlay: OverlayController
    private lateinit var xGptCoordinator: XGptAutomationCoordinator
    private lateinit var repo: ReplySuggestionsRepository
    private var lastActivePackage: String? = null
    private var pendingTapDraft: String? = null

    override fun onServiceConnected() {
        super.onServiceConnected()

        serviceInfo = serviceInfo.apply {
            flags = flags or AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS or
                AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
        }

        repo = ReplySuggestionsRepository(applicationContext)
        overlay = OverlayController(
            context = this,
            onGenerateTapped = { controls ->
                Log.d("ReplyEngine", "ENTER onGenerateTapped callback")
                onMainButtonTapped(controls)
            },
            onGptReplyTapped = {
                xGptCoordinator.startFromX()
            },
            onGenerateFromDrop = { controls, point ->
                Log.d("ReplyEngine", "ENTER onGenerateFromDrop callback x=${point.x} y=${point.y}")
                generateSuggestions(
                    controls = controls,
                    pointerHint = ContextEngine.PointerHint(
                        x = point.x,
                        y = point.y,
                        radiusPx = 150,
                    ),
                )
            },
            onGenerateChatManual = { _, _, _ ->
                Log.d("ReplyEngine", "IGNORE onGenerateChatManual callback")
            },
            onChatEnhanceAsIs = { generatePendingTapDraftEnhancement(mode = TapDraftMode.ENHANCE) },
            onChatCorrectGrammar = { generatePendingTapDraftEnhancement(mode = TapDraftMode.GRAMMAR) },
            onChatSendEdited = { context -> generatePendingTapDraftEnhancement(context) },
            onSuggestionPicked = { text -> insertSuggestion(text) },
            onClosePanel = {
                pendingTapDraft = null
                xGptCoordinator.onPanelClosed()
            },
        )
        xGptCoordinator = XGptAutomationCoordinator(
            service = this,
            overlay = overlay,
            scope = scope,
        )

        overlay.attach()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return

        when (event.eventType) {
            AccessibilityEvent.TYPE_VIEW_FOCUSED,
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED,
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED,
            AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED,
            -> updateFloatingButtonVisibility()
        }
    }

    override fun onInterrupt() {
        // No-op
    }

    override fun onDestroy() {
        if (::xGptCoordinator.isInitialized) {
            xGptCoordinator.stop()
        }
        overlay.detach()
        scope.cancel()
        super.onDestroy()
    }

    private fun updateFloatingButtonVisibility() {
        if (::overlay.isInitialized && overlay.isDragInProgress()) {
            Log.i("ReplyEngine", "VISIBILITY skip_update dragInProgress=true")
            return
        }

        val activeRoot = rootInActiveWindow
        val activePackage = activeRoot?.packageName?.toString()
        val packageChanged = activePackage != null && activePackage != lastActivePackage
        lastActivePackage = activePackage

        if (activePackage == packageName) {
            overlay.setButtonVisible(false)
            overlay.dismissPanel()
            return
        }

        if (::xGptCoordinator.isInitialized && xGptCoordinator.isChatGptPackage(activePackage)) {
            overlay.setButtonVisible(false)
            if (!xGptCoordinator.isRunning()) {
                overlay.dismissPanel()
            }
            return
        }

        if (!isSupportedPackage(activePackage)) {
            overlay.setSurfaceMode(OverlaySurfaceMode.UNSUPPORTED)
            overlay.setButtonVisible(false)
            val preserve = ::xGptCoordinator.isInitialized && xGptCoordinator.shouldPreservePanel(activePackage)
            if (!preserve) {
                overlay.dismissPanel()
            }
            return
        }
        overlay.setSurfaceMode(toOverlaySurfaceMode(resolveMode(activePackage)))

        val visible = if (::xGptCoordinator.isInitialized && xGptCoordinator.isXPackage(activePackage)) {
            true
        } else {
            val focused = activeRoot?.let(ContextEngine::findFocusedEditable)
            val sensitive = focused != null && ContextEngine.isSensitiveInput(focused)
            focused != null && !sensitive
        }

        val preserve = ::xGptCoordinator.isInitialized && xGptCoordinator.shouldPreservePanel(activePackage)
        if ((!visible || packageChanged) && !preserve) {
            overlay.dismissPanel()
        }
        overlay.setButtonVisible(visible)
    }

    private fun onMainButtonTapped(controls: ControlsBlock) {
        val root = rootInActiveWindow
        val activePackage = root?.packageName?.toString().orEmpty()
        if (::xGptCoordinator.isInitialized && xGptCoordinator.isXPackage(activePackage) && root != null) {
            val preview = xGptCoordinator.buildPreview(root)
            overlay.showGptEntry(
                postTextPreview = if (preview.isBlank()) {
                    "Post text will be extracted with screenshot when GPT Reply starts."
                } else {
                    preview
                },
            )
            return
        }

        generateSuggestions(controls)
    }

    private fun generateSuggestions(
        controls: ControlsBlock,
        pointerHint: ContextEngine.PointerHint? = null,
    ) {
        Log.d("ReplyEngine", "ENTER generateSuggestions")

        val root = findSupportedRoot()
        if (root == null) {
            overlay.showError("No active window")
            return
        }

        val activePackage = root.packageName?.toString().orEmpty()
        when (resolveMode(activePackage)) {
            ReplySurfaceMode.FEED_POINTER -> {
                if (pointerHint == null) {
                    showTapEnhancePanel(root)
                    return
                }
                generateFeedSuggestions(
                    root = root,
                    packageName = activePackage,
                    controls = controls,
                    pointerHint = pointerHint,
                )
            }

            ReplySurfaceMode.UNSUPPORTED -> {
                overlay.dismissPanel()
                overlay.setButtonVisible(false)
            }
        }
    }

    private fun showTapEnhancePanel(root: AccessibilityNodeInfo) {
        val focused = ContextEngine.findFocusedEditable(root) ?: findAnyVisibleEditable(root)
        if (focused == null) {
            overlay.showError("Focus an input box first, then tap AI+.")
            return
        }
        if (ContextEngine.isSensitiveInput(focused)) {
            overlay.showError("Sensitive input detected. Draft assist is blocked.")
            return
        }

        val draft = focused.text?.toString().orEmpty().trim()
        if (draft.length < 2) {
            overlay.showError("Type your draft first, then tap AI+.")
            return
        }
        pendingTapDraft = draft

        overlay.showChatReview(
            capturePreview = OverlayCapturePreview(
                captureModeLabel = "Tap mode: draft enhancement",
                payloadSummary = "Choose Enhance this or add more context",
                primaryText = draft,
                secondaryTexts = emptyList(),
                userDraft = draft,
            ),
            capturedDraft = draft,
        )
    }

    private fun generateFeedSuggestions(
        root: AccessibilityNodeInfo,
        packageName: String,
        controls: ControlsBlock,
        pointerHint: ContextEngine.PointerHint,
    ) {
        overlay.showLoading(
            capturePreview = OverlayCapturePreview(
                captureModeLabel = "Crosshair active: extracting text near your drop point",
                payloadSummary = "Preparing context for GPT-4.1",
                primaryText = "",
                secondaryTexts = emptyList(),
                userDraft = "",
            ),
        )

        scope.launch {
            val req = withContext(Dispatchers.Default) {
                FeedReplyEngine.buildRequest(
                    packageName = packageName,
                    root = root,
                    controls = controls,
                    pointerHint = pointerHint,
                )
            }
            Log.d("ReplyEngine", "EXIT feed buildContext -> confidence=${req.context.confidence}")

            if (req.context.primaryText.isBlank() || FeedReplyEngine.isLikelyMetadataLine(req.context.primaryText)) {
                overlay.showError("Nothing detected near pointer. Move crosshair onto post text and try again.")
                return@launch
            }

            overlay.showLoading(
                capturePreview = buildCapturePreview(
                    req = req,
                    mode = ReplySurfaceMode.FEED_POINTER,
                ),
            )

            val result = repo.getSuggestions(req)
            result.fold(
                onSuccess = { overlay.showSuggestions(it, req.context.confidence) },
                onFailure = { overlay.showError(it.message ?: "Request failed") },
            )
        }
    }

    private fun generatePendingTapDraftEnhancement(
        extraContext: String? = null,
        mode: TapDraftMode = TapDraftMode.ENHANCE,
    ) {
        val draft = pendingTapDraft?.trim().orEmpty()
        if (draft.length < 2) {
            overlay.showError("No draft captured. Type text and tap AI+ again.")
            return
        }

        val contextLines = extraContext
            .orEmpty()
            .replace("\r", "\n")
            .lines()
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .take(6)
        val contextJoined = contextLines.joinToString(" ").lowercase()
        val req = ReplySuggestionsRequest(
            context = ReplyContextBlock(
                replyType = "chat",
                primaryText = draft,
                secondaryTexts = contextLines,
                intent = detectDraftIntent(draft),
                conversationTone = detectDraftTone(draft, contextJoined),
                userStyle = detectDraftStyle(draft, contextJoined),
                confidence = 0.9,
            ),
            controls = if (mode == TapDraftMode.GRAMMAR) {
                overlay.currentControls().copy(
                    toneBias = "serious",
                    length = "medium",
                    emojiLevel = 0,
                    slangLevel = 0,
                )
            } else {
                overlay.currentControls()
            },
            userDraft = draft,
        )

        overlay.showLoading(
            capturePreview = OverlayCapturePreview(
                captureModeLabel = if (mode == TapDraftMode.GRAMMAR) {
                    "Tap mode: grammar correction"
                } else {
                    "Tap mode: draft enhancement"
                },
                payloadSummary = when {
                    mode == TapDraftMode.GRAMMAR -> "Correcting grammar and polishing your draft"
                    contextLines.isEmpty() -> "Enhancing your draft text"
                    else -> "Enhancing draft using extra context"
                },
                primaryText = draft,
                secondaryTexts = contextLines.take(2),
                userDraft = draft,
            ),
        )

        scope.launch {
            val result = repo.getSuggestions(req)
            result.fold(
                onSuccess = { overlay.showSuggestions(it, req.context.confidence) },
                onFailure = { overlay.showError(it.message ?: "Request failed") },
            )
        }
    }

    private fun detectDraftIntent(draft: String): String {
        val d = draft.lowercase()
        return when {
            d.contains("?") -> "asking"
            listOf("lol", "haha", "lmao").any(d::contains) -> "joking"
            listOf("great", "nice", "awesome", "love", "thanks").any(d::contains) -> "praising"
            listOf("disagree", "wrong", "not true", "bad").any(d::contains) -> "disagreeing"
            listOf("hate", "stupid", "idiot", "wtf").any(d::contains) -> "criticizing"
            else -> "neutral"
        }
    }

    private fun detectDraftTone(draft: String, extra: String): String {
        val sample = (draft + " " + extra).lowercase()
        return when {
            listOf("idiot", "stupid", "hate", "wtf").any(sample::contains) -> "heated"
            listOf("lol", "haha", "bro").any(sample::contains) -> "playful"
            listOf("thanks", "appreciate", "glad").any(sample::contains) -> "friendly"
            else -> "neutral"
        }
    }

    private fun detectDraftStyle(draft: String, extra: String): String {
        val markers = setOf(
            "bhai",
            "yaar",
            "kya",
            "kyu",
            "kyun",
            "mast",
            "bhot",
            "bahut",
            "nahi",
            "haan",
            "acha",
            "accha",
            "sahi",
        )
        val words = (draft + " " + extra).lowercase().split(Regex("\\s+"))
        val hits = words.count { markers.contains(it.trim()) }
        return if (hits >= 2) "Hinglish" else "English"
    }

    private fun insertSuggestion(text: String) {
        scope.launch {
            ensureXVisibleForInsert()
            delay(260)

            val focused = findEditableInCurrentOrSupportedWindow()
            if (focused == null) {
                overlay.showError("Open the X reply box, then tap the reply card again.")
                return@launch
            }
            if (ContextEngine.isSensitiveInput(focused)) {
                overlay.showError("Sensitive field detected. Paste blocked.")
                return@launch
            }

            val ok = TextInserter.setText(focused, text) || TextInserter.pasteText(applicationContext, focused, text)
            if (ok) {
                overlay.dismissPanel()
            } else {
                overlay.showError("Could not paste in X. Focus the reply box and try again.")
            }
        }
    }

    private fun ensureXVisibleForInsert() {
        val activePkg = rootInActiveWindow?.packageName?.toString().orEmpty()
        if (activePkg == XGptAutomationCoordinator.X_PACKAGE) return

        val launchIntent = packageManager.getLaunchIntentForPackage(XGptAutomationCoordinator.X_PACKAGE) ?: return
        launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        startActivity(launchIntent)
    }

    private fun findEditableInCurrentOrSupportedWindow(): AccessibilityNodeInfo? {
        rootInActiveWindow?.let { root ->
            if (isSupportedPackage(root.packageName?.toString())) {
                val node = ContextEngine.findFocusedEditable(root) ?: findAnyVisibleEditable(root)
                if (node != null) return node
            }
        }

        for (window in windows) {
            val root = window.root ?: continue
            if (!isSupportedPackage(root.packageName?.toString())) continue
            val node = ContextEngine.findFocusedEditable(root) ?: findAnyVisibleEditable(root)
            if (node != null) return node
        }
        return null
    }

    private fun findSupportedRoot(): AccessibilityNodeInfo? {
        rootInActiveWindow?.let { root ->
            if (isSupportedPackage(root.packageName?.toString())) return root
        }
        for (window in windows) {
            val root = window.root ?: continue
            if (!isSupportedPackage(root.packageName?.toString())) continue
            return root
        }
        return null
    }

    private fun findAnyVisibleEditable(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        val stack = ArrayDeque<AccessibilityNodeInfo>()
        stack.add(root)
        var visited = 0
        while (stack.isNotEmpty() && visited < 2500) {
            visited++
            val node = stack.removeLast()
            if (!node.isVisibleToUser) continue
            if (node.isEditable) return node
            for (i in 0 until node.childCount) {
                node.getChild(i)?.let(stack::add)
            }
        }
        return null
    }

    private fun isSupportedPackage(packageName: String?): Boolean {
        return resolveMode(packageName) != ReplySurfaceMode.UNSUPPORTED
    }

    private fun buildCapturePreview(
        req: ReplySuggestionsRequest,
        mode: ReplySurfaceMode,
    ): OverlayCapturePreview {
        val modeLabel = when (mode) {
            ReplySurfaceMode.FEED_POINTER -> "Capture mode: Crosshair target near drop point"
            ReplySurfaceMode.UNSUPPORTED -> "Capture mode: Unsupported app"
        }

        val payloadLabel = when (mode) {
            ReplySurfaceMode.FEED_POINTER -> "Sending this context to GPT-4.1"
            ReplySurfaceMode.UNSUPPORTED -> "Sending this context to GPT-4.1"
        }
        val secondaryLimit = 2

        return OverlayCapturePreview(
            captureModeLabel = modeLabel,
            payloadSummary = payloadLabel,
            primaryText = req.context.primaryText,
            secondaryTexts = req.context.secondaryTexts.take(secondaryLimit),
            userDraft = req.userDraft,
        )
    }

    private fun resolveMode(packageName: String?): ReplySurfaceMode {
        val pkg = packageName?.trim().orEmpty()
        if (pkg.isBlank()) return ReplySurfaceMode.UNSUPPORTED
        return when {
            feedPackages.contains(pkg) -> ReplySurfaceMode.FEED_POINTER
            else -> ReplySurfaceMode.UNSUPPORTED
        }
    }

    private fun toOverlaySurfaceMode(mode: ReplySurfaceMode): OverlaySurfaceMode {
        return when (mode) {
            ReplySurfaceMode.FEED_POINTER -> OverlaySurfaceMode.FEED_POINTER
            ReplySurfaceMode.UNSUPPORTED -> OverlaySurfaceMode.UNSUPPORTED
        }
    }
}
