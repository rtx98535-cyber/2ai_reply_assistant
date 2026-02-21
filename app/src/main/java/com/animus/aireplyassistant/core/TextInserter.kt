package com.animus.aireplyassistant.core

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo

object TextInserter {
    fun setText(node: AccessibilityNodeInfo, text: String): Boolean {
        val args = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        }
        return node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
    }

    fun pasteText(context: Context, node: AccessibilityNodeInfo, text: String): Boolean {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val previous = clipboard.primaryClip

        val token = "ai_reply_suggestion_" + SystemClock.uptimeMillis()
        clipboard.setPrimaryClip(ClipData.newPlainText(token, text))

        // Best-effort: some apps support paste, others don't.
        val pasted = node.performAction(AccessibilityNodeInfo.ACTION_PASTE)

        // Restore clipboard to avoid leaving private suggestions behind.
        // Only restore if the clipboard still contains our token, so we don't clobber user changes.
        val restore = Runnable {
            val desc = clipboard.primaryClipDescription
            val curLabel = desc?.label?.toString().orEmpty()
            if (curLabel != token) return@Runnable

            if (previous != null) {
                clipboard.setPrimaryClip(previous)
            } else {
                clipboard.setPrimaryClip(ClipData.newPlainText("", ""))
            }
        }

        if (!pasted) {
            restore.run()
            return false
        }

        Handler(Looper.getMainLooper()).postDelayed(restore, 1500)
        return true
    }
}
