package com.animus.aireplyassistant.overlay

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.runtime.getValue
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.changedToUpIgnoreConsumed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.animus.aireplyassistant.generation.ControlsBlock

@Composable
fun FloatingButton(
    visible: Boolean,
    crosshairMode: Boolean,
    dragEnabled: Boolean,
    onClick: () -> Unit = {},
    onDragStart: () -> Unit = {},
    onDragBy: (Float, Float) -> Unit = { _, _ -> },
    onDragEnd: () -> Unit = {},
    onDragCancel: () -> Unit = {},
) {
    if (!visible) return

    val primary = MaterialTheme.colorScheme.primary
    val bg by animateColorAsState(
        targetValue = if (crosshairMode) MaterialTheme.colorScheme.surface else primary,
        animationSpec = spring(),
        label = "bubbleBg",
    )
    val fg by animateColorAsState(
        targetValue = if (crosshairMode) primary else MaterialTheme.colorScheme.onPrimary,
        animationSpec = spring(),
        label = "bubbleFg",
    )
    val borderWidth by animateDpAsState(
        targetValue = if (crosshairMode) 2.4.dp else 1.6.dp,
        animationSpec = spring(),
        label = "bubbleBorder",
    )
    val scale by animateFloatAsState(
        targetValue = if (crosshairMode) 1.06f else 1f,
        animationSpec = spring(),
        label = "bubbleScale",
    )
    val interactionModifier = if (dragEnabled) {
        Modifier.pointerInput(Unit) {
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false)
                down.consume()
                var pointerId = down.id
                var dragging = false
                var endedWithUp = false
                while (true) {
                    val event = awaitPointerEvent()
                    val change = event.changes.firstOrNull { it.id == pointerId } ?: event.changes.firstOrNull()
                    if (change == null) break
                    pointerId = change.id

                    if (change.changedToUpIgnoreConsumed()) {
                        change.consume()
                        endedWithUp = true
                        if (dragging) {
                            onDragEnd()
                        } else {
                            onClick()
                        }
                        break
                    }

                    val delta = change.positionChange()
                    if (delta != Offset.Zero) {
                        if (!dragging) {
                            dragging = true
                            onDragStart()
                        }
                        onDragBy(delta.x, delta.y)
                        change.consume()
                    }
                }
                if (!endedWithUp && dragging) {
                    onDragCancel()
                }
            }
        }
    } else {
        Modifier
    }

    Box(
        modifier = Modifier
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .clip(CircleShape)
            .background(bg)
            .border(width = borderWidth, color = primary, shape = CircleShape)
            .then(interactionModifier)
            .padding(horizontal = 12.dp, vertical = 9.dp),
        contentAlignment = Alignment.Center,
    ) {
        if (crosshairMode) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                CrosshairGlyph(color = fg)
                Text("Drop", color = fg, fontWeight = FontWeight.SemiBold)
            }
        } else {
            Text("AI+", color = fg, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
fun DragPointerLayer(state: OverlayUiState) {
    if (!state.dragPointerVisible) return

    val primary = MaterialTheme.colorScheme.primary
    val lineColor = primary.copy(alpha = 0.55f)
    val haloColor = primary.copy(alpha = 0.16f)
    val animSpec = spring<Float>(stiffness = if (state.buttonDragActive) 900f else 260f)
    val x by animateFloatAsState(
        targetValue = state.dragPointerX,
        animationSpec = animSpec,
        label = "dragPointerX",
    )
    val y by animateFloatAsState(
        targetValue = state.dragPointerY,
        animationSpec = animSpec,
        label = "dragPointerY",
    )

    Canvas(modifier = Modifier.fillMaxSize()) {
        val origin = Offset(state.dragOriginX, state.dragOriginY)
        val pointer = Offset(x, y)

        if ((pointer - origin).getDistance() > 1f) {
            drawLine(
                color = lineColor,
                start = origin,
                end = pointer,
                strokeWidth = 3.2f,
                cap = StrokeCap.Round,
            )
        }

        val r = 18f
        drawCircle(
            color = haloColor,
            radius = r,
            center = pointer,
        )
        drawCircle(
            color = primary,
            radius = r,
            center = pointer,
            style = Stroke(width = 2.8f),
        )
        drawLine(
            color = primary,
            start = Offset(pointer.x - r, pointer.y),
            end = Offset(pointer.x + r, pointer.y),
            strokeWidth = 2.8f,
            cap = StrokeCap.Round,
        )
        drawLine(
            color = primary,
            start = Offset(pointer.x, pointer.y - r),
            end = Offset(pointer.x, pointer.y + r),
            strokeWidth = 2.8f,
            cap = StrokeCap.Round,
        )
    }
}

@Composable
private fun CrosshairGlyph(color: Color) {
    Canvas(modifier = Modifier.size(20.dp)) {
        val stroke = 2.2.dp.toPx()
        val center = Offset(size.width / 2f, size.height / 2f)

        drawCircle(color = color, radius = size.minDimension * 0.45f, style = Stroke(width = stroke))
        drawLine(
            color = color,
            start = Offset(center.x, 0f),
            end = Offset(center.x, size.height),
            strokeWidth = stroke,
            cap = StrokeCap.Round,
        )
        drawLine(
            color = color,
            start = Offset(0f, center.y),
            end = Offset(size.width, center.y),
            strokeWidth = stroke,
            cap = StrokeCap.Round,
        )
    }
}

@Composable
fun SuggestionPanel(
    state: OverlayUiState,
    onControlsChanged: (ControlsBlock) -> Unit,
    onManualContextChanged: (String) -> Unit,
    onManualDraftChanged: (String) -> Unit,
    onGenerateChatManual: () -> Unit,
    onGptReply: () -> Unit,
    onChatReviewEditRequested: () -> Unit,
    onChatReviewCancelEdit: () -> Unit,
    onChatReviewEnhanceAsIs: () -> Unit,
    onChatReviewCorrectGrammar: () -> Unit,
    onChatReviewDraftChanged: (String) -> Unit,
    onChatReviewSendEdited: () -> Unit,
    onPick: (String) -> Unit,
    onClose: () -> Unit,
) {
    if (!state.panelVisible) return

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(12.dp)
            .imePadding(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 8.dp),
        shape = RoundedCornerShape(18.dp),
    ) {
        Column(
            modifier = Modifier
                .padding(14.dp)
                .animateContentSize(),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "Reply suggestions",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f),
                )
                if (state.limitedContext) {
                    Text(
                        "Limited context",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.tertiary,
                    )
                    Spacer(Modifier.width(8.dp))
                }
                TextButton(onClick = onClose) { Text("Close") }
            }

            state.capturePreview?.let {
                CapturePreviewCard(preview = it)
            }

            if (state.surfaceMode == OverlaySurfaceMode.CHAT_MANUAL) {
                ChatManualComposer(
                    contextText = state.manualContext,
                    draftText = state.manualDraft,
                    onContextChanged = onManualContextChanged,
                    onDraftChanged = onManualDraftChanged,
                    onGenerate = onGenerateChatManual,
                )
            }

            if (state.chatReviewActive) {
                ChatReviewComposer(
                    editMode = state.chatReviewEditMode,
                    draftText = state.chatReviewDraft,
                    onEditRequested = onChatReviewEditRequested,
                    onCancelEdit = onChatReviewCancelEdit,
                    onEnhanceAsIs = onChatReviewEnhanceAsIs,
                    onCorrectGrammar = onChatReviewCorrectGrammar,
                    onDraftChanged = onChatReviewDraftChanged,
                    onSendEdited = onChatReviewSendEdited,
                )
            }

            if (state.gptEntryActive) {
                GptEntryComposer(onGptReply = onGptReply)
            }

            if (!state.chatReviewEditMode && !state.gptEntryActive) {
                ControlsRow(
                    controls = state.controls,
                    onChanged = onControlsChanged,
                )
            }

            when {
                state.loading -> Text("Thinking...", style = MaterialTheme.typography.bodyMedium)
                state.error != null -> Text("Error: ${state.error}", color = MaterialTheme.colorScheme.error)
                else -> SuggestionsList(
                    suggestions = state.suggestions.map { it.text },
                    onPick = onPick,
                )
            }
        }
    }
}

@Composable
private fun GptEntryComposer(
    onGptReply: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            "Generate an auto reply in ChatGPT from this post and screenshot.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Button(
            onClick = onGptReply,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("GPT Reply")
        }
    }
}

@Composable
private fun ChatReviewComposer(
    editMode: Boolean,
    draftText: String,
    onEditRequested: () -> Unit,
    onCancelEdit: () -> Unit,
    onEnhanceAsIs: () -> Unit,
    onCorrectGrammar: () -> Unit,
    onDraftChanged: (String) -> Unit,
    onSendEdited: () -> Unit,
) {
    val focusManager = LocalFocusManager.current
    LaunchedEffect(editMode) {
        if (!editMode) {
            focusManager.clearFocus(force = true)
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            "Draft actions",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.primary,
        )

        if (editMode) {
            OutlinedTextField(
                value = draftText,
                onValueChange = onDraftChanged,
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Put more context (optional)") },
                placeholder = { Text("Add context to guide the rewrite...") },
                minLines = 2,
                maxLines = 5,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = onSendEdited,
                    modifier = Modifier.weight(1f),
                ) {
                    Text("Enhance with context")
                }
                TextButton(
                    onClick = onCancelEdit,
                    modifier = Modifier.weight(1f),
                ) {
                    Text("Back")
                }
            }
        } else {
            Text(
                "Use your current input as base text, or add extra context first.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = onEnhanceAsIs,
                    modifier = Modifier.weight(1f),
                ) {
                    Text("Enhance this")
                }
                TextButton(
                    onClick = onEditRequested,
                    modifier = Modifier.weight(1f),
                ) {
                    Text("Put more context")
                }
            }
            Button(
                onClick = onCorrectGrammar,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Correct grammar")
            }
        }
    }
}

@Composable
private fun ChatManualComposer(
    contextText: String,
    draftText: String,
    onContextChanged: (String) -> Unit,
    onDraftChanged: (String) -> Unit,
    onGenerate: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(
            value = contextText,
            onValueChange = onContextChanged,
            modifier = Modifier.fillMaxWidth(),
            label = { Text("Context (required)") },
            placeholder = { Text("Type or paste what is happening in chat...") },
            minLines = 4,
            maxLines = 7,
        )
        OutlinedTextField(
            value = draftText,
            onValueChange = onDraftChanged,
            modifier = Modifier.fillMaxWidth(),
            label = { Text("Your draft (optional)") },
            placeholder = { Text("Optional draft tone to guide replies") },
            minLines = 1,
            maxLines = 3,
        )
        Button(
            onClick = onGenerate,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("Generate from typed context")
        }
    }
}

@Composable
private fun CapturePreviewCard(preview: OverlayCapturePreview) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        shape = RoundedCornerShape(12.dp),
    ) {
        Column(
            modifier = Modifier.padding(10.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                preview.payloadSummary,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
            )
            Text(
                preview.captureModeLabel,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            val primary = preview.primaryText.trim()
            if (primary.isBlank()) {
                Text(
                    "No text detected near pointer yet. Nothing will be sent until text is detected.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                Text(
                    ellipsize(primary, 260),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }

            if (preview.secondaryTexts.isNotEmpty()) {
                Text(
                    "Extra: ${ellipsize(preview.secondaryTexts.joinToString(" | "), 200)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            if (preview.userDraft.isNotBlank()) {
                Text(
                    "Draft: ${ellipsize(preview.userDraft, 120)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun ControlsRow(
    controls: ControlsBlock,
    onChanged: (ControlsBlock) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ToneChip("Neutral", controls.toneBias == "neutral") { onChanged(controls.copy(toneBias = "neutral")) }
            ToneChip("Funny", controls.toneBias == "funny") { onChanged(controls.copy(toneBias = "funny")) }
            ToneChip("Polite", controls.toneBias == "polite") { onChanged(controls.copy(toneBias = "polite")) }
            ToneChip("Serious", controls.toneBias == "serious") { onChanged(controls.copy(toneBias = "serious")) }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ToneChip("Short", controls.length == "short") { onChanged(controls.copy(length = "short")) }
            ToneChip("Medium", controls.length == "medium") { onChanged(controls.copy(length = "medium")) }
            ToneChip("Emoji ${controls.emojiLevel}", false) {
                onChanged(controls.copy(emojiLevel = (controls.emojiLevel + 1) % 3))
            }
            ToneChip("Slang ${controls.slangLevel}", false) {
                onChanged(controls.copy(slangLevel = (controls.slangLevel + 1) % 3))
            }
        }
    }
}

@Composable
private fun ToneChip(
    text: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val colors = if (selected) {
        ButtonDefaults.buttonColors(
            containerColor = MaterialTheme.colorScheme.primary,
            contentColor = MaterialTheme.colorScheme.onPrimary,
        )
    } else {
        ButtonDefaults.buttonColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
            contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }

    Button(
        onClick = onClick,
        colors = colors,
        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
    ) {
        Text(text, style = MaterialTheme.typography.labelMedium)
    }
}

@Composable
private fun SuggestionsList(
    suggestions: List<String>,
    onPick: (String) -> Unit,
) {
    if (suggestions.isEmpty()) return

    val pagerState = rememberPagerState(
        initialPage = 0,
        pageCount = { suggestions.size },
    )

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        HorizontalPager(
            state = pagerState,
            modifier = Modifier.fillMaxWidth(),
        ) { page ->
            val s = suggestions[page]
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onPick(s) },
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                shape = RoundedCornerShape(12.dp),
            ) {
                Text(
                    s,
                    modifier = Modifier.padding(12.dp),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            for (i in suggestions.indices) {
                val selected = i == pagerState.currentPage
                Box(
                    modifier = Modifier
                        .padding(horizontal = 3.dp)
                        .size(if (selected) 8.dp else 6.dp)
                        .clip(CircleShape)
                        .background(
                            if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline.copy(alpha = 0.45f),
                        ),
                )
            }
        }
    }
}

private fun ellipsize(text: String, maxChars: Int): String {
    val clean = text.trim().replace(Regex("\\s+"), " ")
    if (clean.length <= maxChars) return clean
    return clean.take((maxChars - 3).coerceAtLeast(1)).trimEnd() + "..."
}
