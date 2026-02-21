package com.animus.aireplyassistant.generation

data class ReplySuggestionsRequest(
    val context: ReplyContextBlock,
    val controls: ControlsBlock,
    val userDraft: String,
)

data class ReplyContextBlock(
    val replyType: String,
    val primaryText: String,
    val secondaryTexts: List<String>,
    val intent: String,
    val conversationTone: String,
    val userStyle: String,
    val confidence: Double,
)

data class ControlsBlock(
    val toneBias: String = "neutral",
    val length: String = "medium",
    val emojiLevel: Int = 1,
    val slangLevel: Int = 1,
)

data class ReplySuggestionsResponse(
    val suggestions: List<ReplySuggestion>,
)

data class ReplySuggestion(
    val text: String,
    val archetype: String,
    val tone: String,
)
