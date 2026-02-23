package com.animus.aireplyassistant.automation

import android.accessibilityservice.AccessibilityService
import android.content.ClipData
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.ColorSpace
import android.graphics.Rect
import android.net.Uri
import android.os.Build
import android.os.SystemClock
import android.util.Log
import android.view.Display
import android.view.accessibility.AccessibilityNodeInfo
import androidx.core.content.FileProvider
import com.animus.aireplyassistant.context.FeedReplyEngine
import com.animus.aireplyassistant.context.ScreenTextExtractor
import com.animus.aireplyassistant.generation.ReplySuggestion
import com.animus.aireplyassistant.overlay.OverlayCapturePreview
import com.animus.aireplyassistant.overlay.OverlayController
import java.io.File
import java.io.FileOutputStream
import kotlin.coroutines.resume
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext

class XGptAutomationCoordinator(
    private val service: AccessibilityService,
    private val overlay: OverlayController,
    private val scope: CoroutineScope,
    private val logTag: String = "ReplyEngine",
) {
    companion object {
        const val X_PACKAGE = "com.twitter.android"
        const val CHATGPT_PACKAGE = "com.openai.chatgpt"
    }

    private enum class State {
        WAITING_FOR_CHATGPT,
        WAITING_FOR_SEND,
        WAITING_FOR_RESPONSE,
    }

    private data class Session(
        val prompt: String,
        val postText: String,
        val screenshotUri: Uri?,
        val startedAtMs: Long = SystemClock.uptimeMillis(),
        var state: State = State.WAITING_FOR_CHATGPT,
        var sendAttempts: Int = 0,
        var sendClickedAtMs: Long = 0L,
    )

    private data class NodeText(
        val text: String,
        val bounds: Rect,
    )

    private var session: Session? = null
    private var job: Job? = null
    private var keepPanelOnNextXReturn = false

    fun isRunning(): Boolean = session != null

    fun isChatGptPackage(pkg: String?): Boolean = pkg == CHATGPT_PACKAGE

    fun isXPackage(pkg: String?): Boolean = pkg == X_PACKAGE

    fun onPanelClosed() {
        keepPanelOnNextXReturn = false
    }

    fun shouldPreservePanel(activePackage: String?): Boolean {
        if (session != null) return true
        if (keepPanelOnNextXReturn && activePackage == X_PACKAGE) {
            keepPanelOnNextXReturn = false
            return true
        }
        return false
    }

    fun stop() {
        job?.cancel()
        job = null
        session = null
        keepPanelOnNextXReturn = false
    }

    fun buildPreview(root: AccessibilityNodeInfo): String = extractBestPostText(root)

    fun startFromX() {
        if (session != null) {
            overlay.showError("GPT Reply is already running.")
            return
        }

        val xRoot = findRootForPackage(X_PACKAGE)
        if (xRoot == null) {
            overlay.showError("Open X first, then tap GPT Reply.")
            return
        }

        scope.launch {
            val postText = withContext(Dispatchers.Default) { extractBestPostText(xRoot) }
            val prompt = ChatGptPromptBuilder.build(postText)

            overlay.showLoading(
                capturePreview = OverlayCapturePreview(
                    captureModeLabel = "GPT Reply: preparing ChatGPT handoff",
                    payloadSummary = "Reading post text and capturing screenshot",
                    primaryText = postText,
                    secondaryTexts = emptyList(),
                    userDraft = "",
                ),
            )

            val screenshotUri = captureScreenshotUri()
            val launched = launchChatGpt(prompt = prompt, screenshotUri = screenshotUri)
            if (!launched) {
                overlay.showError("ChatGPT app not found. Install/open ChatGPT and try again.")
                return@launch
            }

            session = Session(
                prompt = prompt,
                postText = postText,
                screenshotUri = screenshotUri,
            )

            overlay.showLoading(
                capturePreview = OverlayCapturePreview(
                    captureModeLabel = "GPT Reply: ChatGPT opened",
                    payloadSummary = "Auto-submitting prompt",
                    primaryText = postText,
                    secondaryTexts = if (screenshotUri == null) {
                        listOf("Screenshot capture unavailable on this device/version")
                    } else {
                        listOf("Screenshot attached")
                    },
                    userDraft = "",
                ),
            )

            runLoop()
        }
    }

    private fun runLoop() {
        job?.cancel()
        job = scope.launch {
            while (isActive) {
                val s = session ?: break
                if (SystemClock.uptimeMillis() - s.startedAtMs > 120_000L) {
                    fail("Timed out waiting for ChatGPT response.")
                    break
                }
                step(s)
                delay(900)
            }
        }
    }

    private suspend fun step(s: Session) {
        when (s.state) {
            State.WAITING_FOR_CHATGPT -> {
                val root = findRootForPackage(CHATGPT_PACKAGE) ?: return
                s.state = State.WAITING_FOR_SEND
                s.sendAttempts = 0
                overlay.showLoading(
                    capturePreview = OverlayCapturePreview(
                        captureModeLabel = "GPT Reply: ChatGPT ready",
                        payloadSummary = "Finding Send button",
                        primaryText = s.postText,
                        secondaryTexts = emptyList(),
                        userDraft = "",
                    ),
                )
                tryTapSend(root, s)
            }

            State.WAITING_FOR_SEND -> {
                val root = findRootForPackage(CHATGPT_PACKAGE) ?: return
                if (!tryTapSend(root, s) && s.sendAttempts > 18) {
                    fail("Could not auto-tap Send in ChatGPT.")
                }
            }

            State.WAITING_FOR_RESPONSE -> {
                val root = findRootForPackage(CHATGPT_PACKAGE) ?: return
                val sinceSend = SystemClock.uptimeMillis() - s.sendClickedAtMs
                if (sinceSend < 3_500L) return

                val replies = parseReplies(root)
                if (replies != null) {
                    complete(replies)
                    return
                }
                if (sinceSend > 80_000L) {
                    fail("ChatGPT response was not detected in time.")
                }
            }
        }
    }

    private fun tryTapSend(root: AccessibilityNodeInfo, s: Session): Boolean {
        val sendNode = findSendNode(root)
        if (sendNode == null) {
            s.sendAttempts += 1
            return false
        }
        val clicked = clickNodeOrAncestor(sendNode)
        if (!clicked) {
            s.sendAttempts += 1
            return false
        }

        s.state = State.WAITING_FOR_RESPONSE
        s.sendClickedAtMs = SystemClock.uptimeMillis()
        s.sendAttempts = 0
        overlay.showLoading(
            capturePreview = OverlayCapturePreview(
                captureModeLabel = "GPT Reply: prompt submitted",
                payloadSummary = "Waiting for ChatGPT to generate 3 replies",
                primaryText = s.postText,
                secondaryTexts = emptyList(),
                userDraft = "",
            ),
        )
        return true
    }

    private fun complete(replies: List<String>) {
        job?.cancel()
        job = null
        session = null
        keepPanelOnNextXReturn = true
        val launched = launchPackage(X_PACKAGE)
        if (!launched) {
            Log.w(logTag, "Could not launch X package after GPT parse. Showing cards in current app.")
        }

        scope.launch {
            delay(450)
            overlay.showSuggestions(
                suggestions = replies.map { ReplySuggestion(text = it, archetype = "gpt_auto", tone = "neutral") },
                confidence = 0.95,
            )
        }
    }

    private fun fail(message: String) {
        job?.cancel()
        job = null
        session = null
        overlay.showError(message)
    }

    private suspend fun captureScreenshotUri(): Uri? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return null

        val bitmap = suspendCancellableCoroutine<Bitmap?> { cont ->
            try {
                service.takeScreenshot(Display.DEFAULT_DISPLAY, service.mainExecutor, object : AccessibilityService.TakeScreenshotCallback {
                    override fun onSuccess(screenshot: AccessibilityService.ScreenshotResult) {
                        val hardwareBuffer = screenshot.hardwareBuffer ?: run {
                            cont.resume(null)
                            return
                        }
                        val colorSpace = screenshot.colorSpace ?: ColorSpace.get(ColorSpace.Named.SRGB)
                        val wrapped = Bitmap.wrapHardwareBuffer(hardwareBuffer, colorSpace)
                        hardwareBuffer.close()
                        if (wrapped == null) {
                            cont.resume(null)
                            return
                        }
                        cont.resume(wrapped.copy(Bitmap.Config.ARGB_8888, false))
                    }

                    override fun onFailure(errorCode: Int) {
                        Log.w(logTag, "screenshot failed code=$errorCode")
                        cont.resume(null)
                    }
                })
            } catch (t: Throwable) {
                Log.w(logTag, "screenshot exception", t)
                cont.resume(null)
            }
        } ?: return null

        return withContext(Dispatchers.IO) {
            try {
                val dir = File(service.cacheDir, "images").apply { mkdirs() }
                val file = File(dir, "gpt_capture_${System.currentTimeMillis()}.jpg")
                FileOutputStream(file).use { out ->
                    bitmap.compress(Bitmap.CompressFormat.JPEG, 92, out)
                }
                FileProvider.getUriForFile(
                    service,
                    "${service.packageName}.fileprovider",
                    file,
                )
            } catch (t: Throwable) {
                Log.w(logTag, "screenshot persist failed", t)
                null
            }
        }
    }

    private fun launchChatGpt(prompt: String, screenshotUri: Uri?): Boolean {
        return try {
            val intent = Intent(Intent.ACTION_SEND).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                setPackage(CHATGPT_PACKAGE)
                putExtra(Intent.EXTRA_TEXT, prompt)
                if (screenshotUri != null) {
                    type = "image/jpeg"
                    putExtra(Intent.EXTRA_STREAM, screenshotUri)
                    clipData = ClipData.newUri(service.contentResolver, "x_post_capture", screenshotUri)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    service.grantUriPermission(CHATGPT_PACKAGE, screenshotUri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                } else {
                    type = "text/plain"
                }
            }
            service.startActivity(intent)
            true
        } catch (t: Throwable) {
            Log.w(logTag, "launch ChatGPT failed", t)
            false
        }
    }

    private fun launchPackage(pkg: String): Boolean {
        return try {
            val launchIntent = service.packageManager.getLaunchIntentForPackage(pkg) ?: return false
            launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            service.startActivity(launchIntent)
            true
        } catch (_: Throwable) {
            false
        }
    }

    private fun findSendNode(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        val stack = ArrayDeque<AccessibilityNodeInfo>()
        val candidates = ArrayList<AccessibilityNodeInfo>()
        stack.add(root)
        var visited = 0

        while (stack.isNotEmpty() && visited < 3000) {
            visited++
            val node = stack.removeLast()
            if (!node.isVisibleToUser) continue

            val id = node.viewIdResourceName?.lowercase().orEmpty()
            val text = node.text?.toString()?.trim().orEmpty().lowercase()
            val desc = node.contentDescription?.toString()?.trim().orEmpty().lowercase()
            val looksLikeSend = text == "send" ||
                desc == "send" ||
                id.contains("send") ||
                (desc.contains("send") && !desc.contains("resend"))
            if (looksLikeSend) {
                candidates.add(node)
            }

            for (i in 0 until node.childCount) {
                node.getChild(i)?.let(stack::add)
            }
        }

        if (candidates.isEmpty()) return null
        return candidates.maxByOrNull { node ->
            val rect = Rect()
            node.getBoundsInScreen(rect)
            (if (node.isEnabled) 1000 else 0) + rect.bottom + rect.right
        }
    }

    private fun clickNodeOrAncestor(node: AccessibilityNodeInfo): Boolean {
        var current: AccessibilityNodeInfo? = node
        var depth = 0
        while (current != null && depth < 8) {
            if (current.isClickable && current.isEnabled) {
                if (current.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true
            }
            current = current.parent
            depth++
        }
        return false
    }

    private fun parseReplies(root: AccessibilityNodeInfo): List<String>? {
        val visible = collectVisibleTexts(root)
        val filtered = visible
            .filterNot(::isPromptEcho)
            .filterNot { isUiChrome(it.text) }

        val candidates = filtered
            .sortedByDescending { it.bounds.bottom }
            .map { it.text.trim() }
            .filter { it.length >= 8 }
            .distinct()
            .take(72)

        if (candidates.isEmpty()) return null

        val direct = ChatGptReplyParser.parseFromCandidates(candidates)
        if (direct != null) {
            Log.i(logTag, "GPT parse success mode=direct")
            return direct
        }

        for (i in candidates.indices) {
            val joined = candidates.drop(i).take(10).joinToString("\n")
            val parsed = ChatGptReplyParser.parseFromText(joined)
            if (parsed != null) {
                Log.i(logTag, "GPT parse success mode=window start=$i")
                return parsed
            }
        }

        val pageJoined = filtered
            .sortedWith(compareBy<NodeText>({ it.bounds.top }, { it.bounds.left }))
            .map { it.text.trim() }
            .distinct()
            .takeLast(220)
            .joinToString("\n")
        val fullParsed = ChatGptReplyParser.parseFromText(pageJoined)
        if (fullParsed != null) {
            Log.i(logTag, "GPT parse success mode=fullPage")
            return fullParsed
        }

        val heuristic = fallbackTriplet(candidates)
        if (heuristic != null) {
            Log.i(logTag, "GPT parse fallback mode=heuristic")
            return heuristic
        }

        Log.w(logTag, "GPT parse failed candidates=${candidates.take(12)}")
        return null
    }

    private fun collectVisibleTexts(root: AccessibilityNodeInfo): List<NodeText> {
        val out = ArrayList<NodeText>()
        val stack = ArrayDeque<AccessibilityNodeInfo>()
        stack.add(root)
        var visited = 0

        while (stack.isNotEmpty() && visited < 3200) {
            visited++
            val node = stack.removeLast()
            if (!node.isVisibleToUser) continue

            val text = node.text?.toString()?.trim().orEmpty()
            val desc = node.contentDescription?.toString()?.trim().orEmpty()
            if (text.isNotBlank() || desc.isNotBlank()) {
                val bounds = Rect()
                node.getBoundsInScreen(bounds)
                if (text.isNotBlank() && text.length <= 3000) {
                    out.add(NodeText(text = text, bounds = bounds))
                }
                if (desc.isNotBlank() && !desc.equals(text, ignoreCase = true) && desc.length <= 3000) {
                    out.add(NodeText(text = desc, bounds = bounds))
                }
            }

            for (i in 0 until node.childCount) {
                node.getChild(i)?.let(stack::add)
            }
        }
        return out
    }

    private fun isUiChrome(text: String): Boolean {
        val t = text.trim().lowercase()
        if (t.isBlank()) return true
        val exact = setOf(
            "send",
            "stop",
            "copy",
            "edit",
            "retry",
            "search",
            "new chat",
            "profile",
            "home",
            "reply",
            "replies",
            "post",
        )
        if (exact.contains(t)) return true
        if (t.startsWith("message ")) return true
        if (t.contains("chatgpt can make mistakes")) return true
        if (t.contains("temporary chat")) return true
        if (Regex("^\\d+[smhdwy]$").matches(t)) return true
        return false
    }

    private fun isPromptEcho(node: NodeText): Boolean {
        val t = node.text.trim()
        return t.contains(ChatGptPromptBuilder.USER_PROMPT_MARKER, ignoreCase = true) ||
            t.contains("Generate exactly 3 options", ignoreCase = true) ||
            t.contains("You are writing a reply for a post on X", ignoreCase = true) ||
            t.contains("Extracted post text:", ignoreCase = true)
    }

    private fun fallbackTriplet(candidates: List<String>): List<String>? {
        val filtered = candidates
            .map { it.trim().replace(Regex("\\s+"), " ") }
            .filter { it.length in 12..320 }
            .filterNot { isUiChrome(it) }
            .filterNot {
                it.contains(ChatGptPromptBuilder.USER_PROMPT_MARKER, ignoreCase = true) ||
                    it.contains("Generate exactly 3 options", ignoreCase = true) ||
                    it.contains("Extracted post text", ignoreCase = true)
            }
            .distinctBy { it.lowercase() }

        if (filtered.size < 3) return null
        for (i in 0..(filtered.size - 3)) {
            val triple = filtered.drop(i).take(3)
            if (triple.distinctBy { it.lowercase() }.size == 3) return triple
        }
        return null
    }

    private fun extractBestPostText(root: AccessibilityNodeInfo): String {
        val visible = ScreenTextExtractor.extractVisibleText(root = root, maxItems = 180)
        if (visible.isEmpty()) return ""

        val candidates = visible
            .map { it.text.trim() }
            .filter { it.length in 20..1200 }
            .filterNot { FeedReplyEngine.isLikelyMetadataLine(it) }
            .filterNot(::isUiChrome)
            .filterNot { it.startsWith("http", ignoreCase = true) }
            .filterNot { it.startsWith("@") && it.split(Regex("\\s+")).size <= 2 }
            .distinct()

        if (candidates.isEmpty()) return ""

        return candidates
            .sortedByDescending(::scorePostTextCandidate)
            .take(2)
            .joinToString("\n")
    }

    private fun scorePostTextCandidate(text: String): Int {
        val words = text.split(Regex("\\s+")).count { it.isNotBlank() }
        val punctuation = text.count { it == '.' || it == ',' || it == '!' || it == '?' }
        val hasSentence = text.any { it == '.' || it == '!' || it == '?' }
        return words * 3 + punctuation * 2 + if (hasSentence) 8 else 0
    }

    private fun findRootForPackage(pkg: String): AccessibilityNodeInfo? {
        service.rootInActiveWindow?.let { root ->
            if (root.packageName?.toString() == pkg) return root
        }
        for (window in service.windows) {
            val root = window.root ?: continue
            if (root.packageName?.toString() == pkg) return root
        }
        return null
    }
}
