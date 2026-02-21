package com.animus.aireplyassistant.generation

import kotlinx.coroutines.delay

class MockReplySuggestionsApi : ReplySuggestionsApi {
    override suspend fun fetchSuggestions(req: ReplySuggestionsRequest): ReplySuggestionsResponse {
        delay(450) // simulate network/model latency

        val style = req.context.userStyle
        val funny = req.controls.toneBias == "funny"
        val polite = req.controls.toneBias == "polite"
        val serious = req.controls.toneBias == "serious"

        fun maybeEmoji(s: String): String {
            return when (req.controls.emojiLevel.coerceIn(0, 2)) {
                0 -> s
                1 -> "$s :)"
                else -> "$s :D"
            }
        }

        fun maybeSlang(s: String): String {
            val level = req.controls.slangLevel.coerceIn(0, 2)
            if (level == 0) return s
            val prefix = if (style == "Hinglish") "bhai, " else "man, "
            return if (level == 1) prefix + s else prefix + s.replace("I ", "I kinda ").replace("I'm ", "I'm kinda ")
        }

        fun stylize(s: String): String {
            val base = if (style == "Hinglish") s else s.replace("bhai", "man")
            return maybeEmoji(maybeSlang(base))
        }

        val base = when {
            funny -> listOf(
                ReplySuggestion(stylize("That ending was wild"), "witty", "playful"),
                ReplySuggestion(stylize("Not me replaying this twice"), "witty", "playful"),
            )
            polite -> listOf(
                ReplySuggestion(stylize("That's a great point, thanks for sharing."), "direct", "friendly"),
                ReplySuggestion(stylize("Appreciate the perspective. I see it differently though."), "direct", "friendly"),
            )
            serious -> listOf(
                ReplySuggestion(stylize("I disagree. Here's why:"), "direct", "serious"),
                ReplySuggestion(stylize("Can you share a source for this?"), "curious", "serious"),
            )
            else -> listOf(
                ReplySuggestion(stylize("Didn't expect that"), "witty", "playful"),
                ReplySuggestion(stylize("Interesting take."), "short", "neutral"),
            )
        }

        val divers = listOf(
            ReplySuggestion(stylize("Wait what happened exactly?"), "curious", "playful"),
            ReplySuggestion(stylize("Fair point, I agree."), "supportive", "friendly"),
            ReplySuggestion(stylize("Nice one!"), "short", "neutral"),
        )

        val desiredCount = req.desiredCount.coerceIn(1, 5)
        val picked = (base + divers).distinctBy { it.text }.take(desiredCount).let {
            if (it.size < desiredCount) it + List(desiredCount - it.size) { ReplySuggestion("Nice!", "short", "neutral") } else it
        }

        return ReplySuggestionsResponse(
            source = "openai",
            suggestions = picked,
        )
    }
}
