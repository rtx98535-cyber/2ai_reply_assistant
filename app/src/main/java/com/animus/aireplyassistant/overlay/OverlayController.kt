package com.animus.aireplyassistant.overlay

import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.util.Log
import android.view.Gravity
import android.view.WindowManager
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.ComposeView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import androidx.lifecycle.setViewTreeLifecycleOwner
import com.animus.aireplyassistant.AIReplyAssistantTheme
import com.animus.aireplyassistant.generation.ControlsBlock
import com.animus.aireplyassistant.generation.ReplySuggestion
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class OverlayController(
    private val context: Context,
    private val onGenerateTapped: (ControlsBlock) -> Unit,
    private val onGenerateFromDrop: (ControlsBlock, OverlayDropPoint) -> Unit,
    private val onGenerateChatManual: (ControlsBlock, String, String) -> Unit,
    private val onChatEnhanceAsIs: () -> Unit,
    private val onChatCorrectGrammar: () -> Unit,
    private val onChatSendEdited: (String) -> Unit,
    private val onSuggestionPicked: (String) -> Unit,
    private val onClosePanel: () -> Unit,
) {
    private val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val lifecycleOwner = OverlayLifecycleOwner()
    private val uiScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private var buttonView: ComposeView? = null
    private var pointerView: ComposeView? = null
    private var panelView: ComposeView? = null

    private var buttonAdded = false
    private var pointerAdded = false
    private var panelAdded = false
    private var buttonX = 24f
    private var buttonY = 240f

    var state by mutableStateOf(OverlayUiState())
        private set

    fun attach() {
        lifecycleOwner.onCreate()
        positionButtonAboveKeyboardZone()
        ensureButtonView()
        ensurePanelView()
        setButtonVisible(false)
    }

    fun detach() {
        if (buttonAdded) {
            buttonView?.let(wm::removeView)
            buttonAdded = false
        }
        if (pointerAdded) {
            pointerView?.let(wm::removeView)
            pointerAdded = false
        }
        if (panelAdded) {
            panelView?.let(wm::removeView)
            panelAdded = false
        }
        uiScope.cancel()
        lifecycleOwner.onDestroy()
    }

    fun setButtonVisible(visible: Boolean) {
        state = state.copy(
            buttonVisible = visible,
            buttonDragActive = if (visible) state.buttonDragActive else false,
            dragPointerVisible = if (visible) state.dragPointerVisible else false,
        )
        ensureButtonAdded()
        if (!visible) {
            removePointerView()
        }
    }

    fun setSurfaceMode(mode: OverlaySurfaceMode) {
        state = state.copy(surfaceMode = mode)
        refreshPanelWindowFlags()
    }

    fun showLoading(capturePreview: OverlayCapturePreview? = state.capturePreview) {
        state = state.copy(
            panelVisible = true,
            loading = true,
            error = null,
            suggestions = emptyList(),
            capturePreview = capturePreview,
            chatReviewActive = false,
            chatReviewEditMode = false,
        )
        ensurePanelAdded()
        refreshPanelWindowFlags()
    }

    fun showSuggestions(suggestions: List<ReplySuggestion>, confidence: Double) {
        state = state.copy(
            panelVisible = true,
            loading = false,
            error = null,
            suggestions = suggestions,
            limitedContext = confidence < 0.35,
            chatReviewActive = false,
            chatReviewEditMode = false,
        )
        ensurePanelAdded()
        refreshPanelWindowFlags()
    }

    fun showError(message: String) {
        state = state.copy(
            panelVisible = true,
            loading = false,
            error = message,
            suggestions = emptyList(),
            chatReviewActive = false,
            chatReviewEditMode = false,
        )
        ensurePanelAdded()
        refreshPanelWindowFlags()
    }

    fun showChatComposer() {
        state = state.copy(
            panelVisible = true,
            loading = false,
            error = null,
            suggestions = emptyList(),
            capturePreview = OverlayCapturePreview(
                captureModeLabel = "Chat assistant mode: manual context",
                payloadSummary = "Only text you type below will be sent to GPT-4.1",
                primaryText = "",
                secondaryTexts = emptyList(),
                userDraft = state.manualDraft,
            ),
            chatReviewActive = false,
            chatReviewEditMode = false,
        )
        ensurePanelAdded()
        refreshPanelWindowFlags()
    }

    fun showChatReview(capturePreview: OverlayCapturePreview, capturedDraft: String) {
        state = state.copy(
            panelVisible = true,
            loading = false,
            error = null,
            suggestions = emptyList(),
            capturePreview = capturePreview,
            chatReviewActive = true,
            chatReviewEditMode = false,
            chatReviewDraft = "",
        )
        ensurePanelAdded()
        refreshPanelWindowFlags()
    }

    fun setChatReviewEditMode(enabled: Boolean) {
        state = state.copy(
            chatReviewEditMode = enabled,
            chatReviewDraft = if (enabled) state.chatReviewDraft else "",
        )
        refreshPanelWindowFlags()
    }

    fun dismissPanel() {
        state = state.copy(
            panelVisible = false,
            loading = false,
            error = null,
            suggestions = emptyList(),
            capturePreview = null,
            chatReviewActive = false,
            chatReviewEditMode = false,
            chatReviewDraft = "",
        )
        refreshPanelWindowFlags()
    }

    fun isDragInProgress(): Boolean = state.buttonDragActive

    fun isManualComposerActive(): Boolean {
        return state.panelVisible && state.surfaceMode == OverlaySurfaceMode.CHAT_MANUAL
    }

    fun currentControls(): ControlsBlock = state.controls

    private fun ensureButtonView() {
        if (buttonView != null) return
        val v = ComposeView(context).apply {
            setViewTreeLifecycleOwner(lifecycleOwner)
            setViewTreeSavedStateRegistryOwner(lifecycleOwner)
            setContent {
                AIReplyAssistantTheme(useSurface = false) {
                    FloatingButton(
                        visible = state.buttonVisible,
                        crosshairMode = state.buttonDragActive,
                        dragEnabled = state.surfaceMode == OverlaySurfaceMode.FEED_POINTER,
                        onClick = { onGenerateTapped(state.controls) },
                        onDragStart = {
                            Log.i("ContextEngine", "CROSSHAIR startButtonDrag(compose)")
                            startButtonDrag()
                        },
                        onDragBy = { dx, dy ->
                            Log.i("ContextEngine", "BUTTON_TOUCH action=MOVE dragBy(compose) dx=$dx dy=$dy")
                            moveDragPointerBy(dx, dy)
                        },
                        onDragEnd = {
                            Log.i("ContextEngine", "BUTTON_TOUCH action=UP endDrag(compose)")
                            endButtonDrag()
                        },
                        onDragCancel = {
                            Log.i("ContextEngine", "BUTTON_TOUCH action=CANCEL(compose)")
                            cancelButtonDrag()
                        },
                    )
                }
            }
        }
        buttonView = v
    }

    private fun ensurePointerView() {
        if (pointerView != null) return
        val v = ComposeView(context).apply {
            setBackgroundColor(Color.TRANSPARENT)
            setViewTreeLifecycleOwner(lifecycleOwner)
            setViewTreeSavedStateRegistryOwner(lifecycleOwner)
            setContent {
                AIReplyAssistantTheme(useSurface = false) {
                    DragPointerLayer(state = state)
                }
            }
        }
        pointerView = v
    }

    private fun ensurePanelView() {
        if (panelView != null) return
        val v = ComposeView(context).apply {
            setViewTreeLifecycleOwner(lifecycleOwner)
            setViewTreeSavedStateRegistryOwner(lifecycleOwner)
            setContent {
                AIReplyAssistantTheme(useSurface = false) {
                    SuggestionPanel(
                        state = state,
                        onControlsChanged = { state = state.copy(controls = it) },
                        onManualContextChanged = { state = state.copy(manualContext = it) },
                        onManualDraftChanged = { state = state.copy(manualDraft = it) },
                        onGenerateChatManual = {
                            onGenerateChatManual(state.controls, state.manualContext, state.manualDraft)
                        },
                        onChatReviewEditRequested = {
                            state = state.copy(chatReviewDraft = "")
                            setChatReviewEditMode(true)
                        },
                        onChatReviewCancelEdit = { setChatReviewEditMode(false) },
                        onChatReviewEnhanceAsIs = { onChatEnhanceAsIs() },
                        onChatReviewCorrectGrammar = { onChatCorrectGrammar() },
                        onChatReviewDraftChanged = { state = state.copy(chatReviewDraft = it) },
                        onChatReviewSendEdited = { onChatSendEdited(state.chatReviewDraft) },
                        onPick = { onSuggestionPicked(it) },
                        onClose = {
                            dismissPanel()
                            onClosePanel()
                        },
                    )
                }
            }
        }
        panelView = v
    }

    private fun ensureButtonAdded() {
        if (buttonAdded) return
        val v = buttonView ?: return
        wm.addView(v, buttonLayoutParams())
        buttonAdded = true
    }

    private fun ensurePanelAdded() {
        if (panelAdded) return
        val v = panelView ?: return
        wm.addView(v, panelLayoutParams())
        panelAdded = true
        refreshPanelWindowFlags()
    }

    private fun ensurePointerAdded() {
        if (pointerAdded) return
        val v = pointerView ?: return
        wm.addView(v, pointerLayoutParams())
        pointerAdded = true
    }

    private fun removePointerView() {
        if (!pointerAdded) return
        pointerView?.let(wm::removeView)
        pointerAdded = false
    }

    private fun buttonLayoutParams(): WindowManager.LayoutParams {
        return WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.END or Gravity.BOTTOM
            x = buttonX.toInt()
            y = buttonY.toInt()
        }
    }

    private fun panelLayoutParams(): WindowManager.LayoutParams {
        val focusable = state.surfaceMode == OverlaySurfaceMode.CHAT_MANUAL || state.chatReviewEditMode
        val flags = (if (focusable) 0 else WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE) or
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
        return WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            flags,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.BOTTOM
            softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
        }
    }

    private fun pointerLayoutParams(): WindowManager.LayoutParams {
        return WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
        }
    }

    private fun moveDragPointerBy(dx: Float, dy: Float) {
        if (!state.buttonDragActive) return
        val dm = context.resources.displayMetrics
        val nx = (state.dragPointerX + dx).coerceIn(0f, dm.widthPixels.toFloat())
        val ny = (state.dragPointerY + dy).coerceIn(0f, dm.heightPixels.toFloat())
        state = state.copy(
            dragPointerVisible = true,
            dragPointerX = nx,
            dragPointerY = ny,
        )
    }

    private fun startButtonDrag() {
        if (state.surfaceMode != OverlaySurfaceMode.FEED_POINTER) return
        Log.i("ContextEngine", "CROSSHAIR startButtonDrag")
        val origin = buttonCenterPoint()
        state = state.copy(
            buttonDragActive = true,
            dragPointerVisible = true,
            dragOriginX = origin.x.toFloat(),
            dragOriginY = origin.y.toFloat(),
            dragPointerX = origin.x.toFloat(),
            dragPointerY = origin.y.toFloat(),
            panelVisible = true,
            loading = false,
            error = null,
            suggestions = emptyList(),
            capturePreview = OverlayCapturePreview(
                captureModeLabel = "Crosshair active: move to target text, then release",
                payloadSummary = "Preparing target capture for GPT-4.1",
                primaryText = "",
                secondaryTexts = emptyList(),
                userDraft = "",
            ),
        )
        ensurePointerView()
        ensurePointerAdded()
        ensurePanelAdded()
        refreshPanelWindowFlags()
    }

    private fun endButtonDrag() {
        if (state.surfaceMode != OverlaySurfaceMode.FEED_POINTER) return
        Log.i("ContextEngine", "CROSSHAIR endButtonDrag visible=${state.buttonVisible}")
        val drop = OverlayDropPoint(
            x = state.dragPointerX.toInt(),
            y = state.dragPointerY.toInt(),
        )
        state = state.copy(
            buttonDragActive = false,
            dragPointerX = state.dragOriginX,
            dragPointerY = state.dragOriginY,
        )
        if (!state.buttonVisible) return
        onGenerateFromDrop(state.controls, drop)
        scopeHidePointerAfterReturn()
    }

    private fun cancelButtonDrag() {
        Log.i("ContextEngine", "CROSSHAIR cancelButtonDrag")
        state = state.copy(
            buttonDragActive = false,
            dragPointerX = state.dragOriginX,
            dragPointerY = state.dragOriginY,
            panelVisible = false,
            capturePreview = null,
            loading = false,
            error = null,
            suggestions = emptyList(),
        )
        scopeHidePointerAfterReturn()
    }

    private fun scopeHidePointerAfterReturn() {
        uiScope.launch {
            delay(180)
            state = state.copy(dragPointerVisible = false)
            removePointerView()
        }
    }

    private fun positionButtonAboveKeyboardZone() {
        val dm = context.resources.displayMetrics
        buttonX = 24f
        buttonY = (dm.heightPixels * 0.34f).coerceIn(260f, 980f)
    }

    private fun refreshPanelWindowFlags() {
        if (!panelAdded) return
        val view = panelView ?: return
        val lp = view.layoutParams as? WindowManager.LayoutParams ?: return
        val focusable = state.surfaceMode == OverlaySurfaceMode.CHAT_MANUAL || state.chatReviewEditMode
        val desiredFlags = (if (focusable) 0 else WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE) or
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
        if (lp.flags != desiredFlags) {
            lp.flags = desiredFlags
            lp.softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
            wm.updateViewLayout(view, lp)
        }
    }

    private fun buttonCenterPoint(): OverlayDropPoint {
        val dm = context.resources.displayMetrics
        val view = buttonView
        val width = if (view != null && view.width > 0) view.width else 56
        val height = if (view != null && view.height > 0) view.height else 56

        val left = (dm.widthPixels - buttonX.toInt() - width).coerceIn(0, (dm.widthPixels - width).coerceAtLeast(0))
        val top = (dm.heightPixels - buttonY.toInt() - height).coerceIn(0, (dm.heightPixels - height).coerceAtLeast(0))
        return OverlayDropPoint(
            x = (left + width / 2).coerceIn(0, dm.widthPixels),
            y = (top + height / 2).coerceIn(0, dm.heightPixels),
        )
    }
}

data class OverlayUiState(
    val buttonVisible: Boolean = false,
    val surfaceMode: OverlaySurfaceMode = OverlaySurfaceMode.UNSUPPORTED,
    val buttonDragActive: Boolean = false,
    val dragPointerVisible: Boolean = false,
    val dragOriginX: Float = 0f,
    val dragOriginY: Float = 0f,
    val dragPointerX: Float = 0f,
    val dragPointerY: Float = 0f,
    val panelVisible: Boolean = false,
    val loading: Boolean = false,
    val limitedContext: Boolean = false,
    val error: String? = null,
    val capturePreview: OverlayCapturePreview? = null,
    val manualContext: String = "",
    val manualDraft: String = "",
    val chatReviewActive: Boolean = false,
    val chatReviewEditMode: Boolean = false,
    val chatReviewDraft: String = "",
    val controls: ControlsBlock = ControlsBlock(),
    val suggestions: List<ReplySuggestion> = emptyList(),
)

enum class OverlaySurfaceMode {
    FEED_POINTER,
    CHAT_AUTO,
    CHAT_MANUAL,
    UNSUPPORTED,
}

data class OverlayDropPoint(
    val x: Int,
    val y: Int,
)

data class OverlayCapturePreview(
    val captureModeLabel: String,
    val payloadSummary: String,
    val primaryText: String,
    val secondaryTexts: List<String> = emptyList(),
    val userDraft: String = "",
)

private class OverlayLifecycleOwner : LifecycleOwner, SavedStateRegistryOwner {
    private val lifecycleRegistry = LifecycleRegistry(this)
    private val savedStateController = SavedStateRegistryController.create(this)

    fun onCreate() {
        savedStateController.performRestore(null)
        lifecycleRegistry.currentState = Lifecycle.State.CREATED
        lifecycleRegistry.currentState = Lifecycle.State.STARTED
        lifecycleRegistry.currentState = Lifecycle.State.RESUMED
    }

    fun onDestroy() {
        lifecycleRegistry.currentState = Lifecycle.State.DESTROYED
    }

    override val lifecycle: Lifecycle
        get() = lifecycleRegistry

    override val savedStateRegistry: SavedStateRegistry
        get() = savedStateController.savedStateRegistry
}
