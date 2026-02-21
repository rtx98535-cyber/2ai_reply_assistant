package com.animus.aireplyassistant.generation

import android.content.Context
import com.animus.aireplyassistant.BuildConfig
import com.animus.aireplyassistant.core.InstallIdStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class ReplySuggestionsRepository(private val context: Context) {
    private val api: ReplySuggestionsApi by lazy {
        val baseUrl = BuildConfig.REPLY_API_BASE_URL
        if (baseUrl.isBlank()) {
            MockReplySuggestionsApi()
        } else {
            HttpReplySuggestionsApi(
                baseUrl = baseUrl,
                installId = InstallIdStore.getOrCreateInstallId(context),
            )
        }
    }

    suspend fun getSuggestions(req: ReplySuggestionsRequest): Result<List<ReplySuggestion>> = withContext(Dispatchers.IO) {
        val fallbackPool = FallbackSuggestions.forStyle(req.context.userStyle)
        if (isPointerFlow(req)) {
            return@withContext Result.success(getPointerSuggestions(req, fallbackPool))
        }

        val out = if (req.context.confidence < 0.35) {
            ReplyIntelligenceLayer.process(
                req = req,
                seedSuggestions = fallbackPool,
                fallbackPool = fallbackPool,
            )
        } else {
            try {
                val response = api.fetchSuggestions(req)
                val suggestions = response.suggestions.take(5).toMutableList()
                if (suggestions.size != 5) {
                    throw IllegalStateException("Backend must return exactly 5 suggestions")
                }

                val deduped = LinkedHashMap<String, ReplySuggestion>()
                for (s in suggestions) {
                    val t = s.text.trim()
                    if (t.isNotBlank()) deduped.putIfAbsent(t, s.copy(text = t))
                }

                val seed = deduped.values.toMutableList()
                if (seed.size < 5) {
                    for (f in fallbackPool) {
                        if (seed.size >= 5) break
                        if (seed.none { it.text.equals(f.text, ignoreCase = true) }) seed.add(f)
                    }
                }

                ReplyIntelligenceLayer.process(
                    req = req,
                    seedSuggestions = seed.take(5),
                    fallbackPool = fallbackPool,
                )
            } catch (_: Throwable) {
                ReplyIntelligenceLayer.process(
                    req = req,
                    seedSuggestions = fallbackPool,
                    fallbackPool = fallbackPool,
                )
            }
        }

        Result.success(out)
    }

    private suspend fun getPointerSuggestions(
        req: ReplySuggestionsRequest,
        fallbackPool: List<ReplySuggestion>,
    ): List<ReplySuggestion> {
        val targetCount = req.desiredCount.coerceIn(1, 5)
        val defaults = pointerDefaults(fallbackPool, targetCount)

        if (req.context.confidence < 0.35) return defaults

        return try {
            val response = api.fetchSuggestions(req)
            if (!response.source.equals("openai", ignoreCase = true)) {
                return defaults
            }

            val aiOnly = response.suggestions
                .asSequence()
                .map { it.copy(text = it.text.trim()) }
                .filter { it.text.isNotBlank() }
                .distinctBy { canonicalSuggestionKey(it.text) }
                .take(targetCount)
                .toList()

            if (aiOnly.size == targetCount) aiOnly else defaults
        } catch (_: Throwable) {
            defaults
        }
    }

    private fun pointerDefaults(
        fallbackPool: List<ReplySuggestion>,
        targetCount: Int,
    ): List<ReplySuggestion> {
        val trimmed = fallbackPool
            .asSequence()
            .map { it.copy(text = it.text.trim()) }
            .filter { it.text.isNotBlank() }
            .distinctBy { canonicalSuggestionKey(it.text) }
            .toMutableList()

        while (trimmed.size < targetCount) {
            trimmed.add(ReplySuggestion("Nice!", "short", "neutral"))
        }
        return trimmed.take(targetCount)
    }

    private fun canonicalSuggestionKey(text: String): String {
        return text.lowercase().replace(Regex("\\s+"), " ").trim()
    }

    private fun isPointerFlow(req: ReplySuggestionsRequest): Boolean {
        return req.surface.equals("pointer", ignoreCase = true)
    }
}

private object FallbackSuggestions {
    fun forStyle(style: String): List<ReplySuggestion> {
        return if (style.equals("Hinglish", ignoreCase = true)) {
            listOf(
                ReplySuggestion("Nice!", "short", "neutral"),
                ReplySuggestion("Sahi point.", "direct", "neutral"),
                ReplySuggestion("Interesting.", "short", "neutral"),
                ReplySuggestion("Good question.", "curious", "neutral"),
                ReplySuggestion("Haan, makes sense.", "supportive", "friendly"),
            )
        } else {
            listOf(
                ReplySuggestion("Nice!", "short", "neutral"),
                ReplySuggestion("Good point.", "direct", "neutral"),
                ReplySuggestion("Interesting.", "short", "neutral"),
                ReplySuggestion("Good question.", "curious", "neutral"),
                ReplySuggestion("That makes sense.", "supportive", "friendly"),
            )
        }
    }
}
