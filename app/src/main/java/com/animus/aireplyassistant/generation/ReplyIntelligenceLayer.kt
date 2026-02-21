package com.animus.aireplyassistant.generation

private data class IntelligenceProfile(
    val replyType: String,
    val intent: String,
    val trackedTone: String,
    val preferredTones: List<String>,
    val userStyle: String,
    val primaryTokens: Set<String>,
    val secondaryOnlyTokens: Set<String>,
    val primaryLooksQuestion: Boolean,
)

object ReplyIntelligenceLayer {
    fun process(
        req: ReplySuggestionsRequest,
        seedSuggestions: List<ReplySuggestion>,
        fallbackPool: List<ReplySuggestion>,
    ): List<ReplySuggestion> {
        val lengthMode = req.controls.length.lowercase().ifBlank { "medium" }
        val profile = buildProfile(req)

        val normalizedSeedRaw = seedSuggestions.mapNotNull { normalizeSuggestion(it, lengthMode) }
        val normalizedSeed = if (profile.replyType == "chat") {
            normalizedSeedRaw
        } else {
            normalizedSeedRaw.filterNot { looksLikePrimaryRewrite(it.text, req.context.primaryText) }
        }
        val normalizedFallback = fallbackPool.mapNotNull { normalizeSuggestion(it, lengthMode) }
        if (profile.replyType == "chat" && req.context.primaryText.isNotBlank()) {
            return processChatPrimaryOnly(
                req = req,
                profile = profile,
                normalizedSeed = normalizedSeed,
                lengthMode = lengthMode,
            )
        }
        val templatePool = buildIntentTemplates(profile, lengthMode)

        val candidates = normalizedSeed + templatePool + normalizedFallback
        val selected = pickDiverse(candidates, profile)

        val provisional = if (selected.size >= 5) {
            selected.take(5)
        } else {
            val out = selected.toMutableList()
            val filler = normalizedFallback + templatePool
            for (candidate in filler) {
                if (out.size >= 5) break
                if (out.none { sameText(it.text, candidate.text) }) out.add(candidate)
            }
            out.take(5)
        }

        return finalizeSuggestions(
            primary = provisional,
            backups = normalizedFallback + templatePool,
            lengthMode = lengthMode,
        )
    }

    private fun processChatPrimaryOnly(
        req: ReplySuggestionsRequest,
        profile: IntelligenceProfile,
        normalizedSeed: List<ReplySuggestion>,
        lengthMode: String,
    ): List<ReplySuggestion> {
        val anchoredSeed = normalizedSeed
            .filter { primaryFocusScore(it.text, profile) >= 0 }
            .take(5)

        val rewritePool = buildPrimaryRewritePool(
            primaryText = req.context.primaryText,
            lengthMode = lengthMode,
            looksQuestion = profile.primaryLooksQuestion,
        )

        return finalizeChatSuggestions(
            primary = anchoredSeed,
            backups = rewritePool,
            lengthMode = lengthMode,
        )
    }

    private fun buildProfile(req: ReplySuggestionsRequest): IntelligenceProfile {
        val replyType = req.context.replyType.lowercase().trim()
        val secondaryForSignals = if (replyType == "chat") req.context.secondaryTexts.take(2) else req.context.secondaryTexts
        val intent = IntentSignalDetector.detect(
            contextIntent = req.context.intent,
            userDraft = req.userDraft,
            primaryText = req.context.primaryText,
            secondaryTexts = secondaryForSignals,
        )
        val trackedTone = ToneSignalTracker.track(
            contextTone = req.context.conversationTone,
            controlsToneBias = req.controls.toneBias,
            intent = intent,
            primaryText = req.context.primaryText,
            secondaryTexts = secondaryForSignals,
        )
        val preferredTones = preferredTones(trackedTone, req.controls.toneBias)
        val primaryTokens = importantTokens(req.context.primaryText)
        val secondaryTokens = importantTokens(req.context.secondaryTexts.joinToString(" "))

        return IntelligenceProfile(
            replyType = replyType,
            intent = intent,
            trackedTone = trackedTone,
            preferredTones = preferredTones,
            userStyle = req.context.userStyle,
            primaryTokens = primaryTokens,
            secondaryOnlyTokens = secondaryTokens - primaryTokens,
            primaryLooksQuestion = req.context.primaryText.contains("?"),
        )
    }

    private fun preferredTones(trackedTone: String, toneBias: String): List<String> {
        val byBias = when (toneBias.lowercase()) {
            "funny" -> listOf("playful", "friendly", "neutral")
            "polite" -> listOf("friendly", "neutral", "serious")
            "serious" -> listOf("serious", "neutral", "friendly")
            else -> emptyList()
        }
        if (byBias.isNotEmpty()) return byBias

        return when (trackedTone) {
            "heated" -> listOf("neutral", "friendly", "serious")
            "playful" -> listOf("playful", "friendly", "neutral")
            "friendly" -> listOf("friendly", "neutral", "playful")
            "serious" -> listOf("serious", "neutral", "friendly")
            else -> listOf("neutral", "friendly", "playful")
        }
    }

    private fun normalizeSuggestion(s: ReplySuggestion, lengthMode: String): ReplySuggestion? {
        val text = shortenText(s.text.replace(Regex("\\s+"), " ").trim(), lengthMode)
        if (text.isBlank()) return null

        val archetype = normalizeArchetype(s.archetype, text)
        val tone = normalizeTone(s.tone, text)
        return ReplySuggestion(text = text, archetype = archetype, tone = tone)
    }

    private fun normalizeArchetype(raw: String, text: String): String {
        val r = raw.lowercase().trim()
        return when (r) {
            "witty", "supportive", "short", "curious", "direct" -> r
            else -> inferArchetype(text)
        }
    }

    private fun inferArchetype(text: String): String {
        val t = text.lowercase()
        return when {
            t.contains("?") -> "curious"
            t.length <= 20 -> "short"
            listOf("agree", "makes sense", "appreciate", "fair point").any(t::contains) -> "supportive"
            listOf("lol", "wild", "haha", "not me").any(t::contains) -> "witty"
            else -> "direct"
        }
    }

    private fun normalizeTone(raw: String, text: String): String {
        val r = raw.lowercase().trim()
        if (r in setOf("playful", "friendly", "neutral", "serious", "heated")) return r
        val t = text.lowercase()
        return when {
            listOf("lol", "haha", "wild").any(t::contains) -> "playful"
            listOf("thanks", "appreciate", "fair point").any(t::contains) -> "friendly"
            listOf("disagree", "source", "evidence").any(t::contains) -> "serious"
            else -> "neutral"
        }
    }

    private fun pickDiverse(
        candidates: List<ReplySuggestion>,
        profile: IntelligenceProfile,
    ): List<ReplySuggestion> {
        val out = mutableListOf<ReplySuggestion>()
        val seenText = HashSet<String>()
        val seenArchetypes = HashSet<String>()
        val seenStarters = HashSet<String>()
        val seenTones = HashSet<String>()

        val ranked = candidates
            .distinctBy { canonicalText(it.text) }
            .sortedByDescending { candidate ->
                var score = 0
                if (candidate.tone == profile.preferredTones.firstOrNull()) score += 4
                if (candidate.tone in profile.preferredTones) score += 2
                if (candidate.archetype in setOf("curious", "supportive", "direct", "witty", "short")) score += 1
                score += primaryFocusScore(candidate.text, profile)
                score
            }

        for (candidate in ranked) {
            if (out.size >= 5) break
            val canon = canonicalText(candidate.text)
            if (!seenText.add(canon)) continue

            val starter = starterSignature(candidate.text)
            var value = 0
            if (!seenArchetypes.contains(candidate.archetype)) value += 3
            if (!seenStarters.contains(starter)) value += 2
            if (!seenTones.contains(candidate.tone)) value += 1
            if (primaryFocusScore(candidate.text, profile) > 0) value += 2

            if (out.size < 2 || value >= 2 || candidate.tone == profile.preferredTones.firstOrNull()) {
                out.add(candidate)
                seenArchetypes.add(candidate.archetype)
                seenStarters.add(starter)
                seenTones.add(candidate.tone)
            }
        }
        return out
    }

    private fun buildIntentTemplates(profile: IntelligenceProfile, lengthMode: String): List<ReplySuggestion> {
        val tones = profile.preferredTones.ifEmpty { listOf("neutral", "friendly", "playful") }
        val styleHinglish = profile.userStyle.equals("Hinglish", ignoreCase = true)

        fun tone(i: Int) = tones[i % tones.size]
        fun text(en: String, hi: String) = if (styleHinglish) hi else en

        val base = when (profile.intent) {
            "asking" -> listOf(
                ReplySuggestion(text("Good question. I was wondering the same.", "Achha question hai, main bhi yahi soch raha tha."), "supportive", tone(0)),
                ReplySuggestion(text("Can you explain that part a bit more?", "Thoda aur explain kar sakte ho?"), "curious", tone(1)),
                ReplySuggestion(text("Interesting angle, what made you think that?", "Interesting angle hai, aisa kyun laga tumhe?"), "curious", tone(2)),
            )
            "praising" -> listOf(
                ReplySuggestion(text("Great point, this is solid.", "Sahi point, ye kaafi solid hai."), "supportive", tone(0)),
                ReplySuggestion(text("Love this perspective.", "Ye perspective mast laga."), "short", tone(1)),
                ReplySuggestion(text("Well said, this lands well.", "Badiya bola, bilkul sahi laga."), "direct", tone(2)),
            )
            "criticizing", "disagreeing" -> listOf(
                ReplySuggestion(text("I see it differently, but fair take.", "Mera take thoda alag hai, but fair point."), "direct", tone(0)),
                ReplySuggestion(text("Not sure I agree. What’s your source?", "Mujhe pura agree nahi ho raha, source kya hai?"), "curious", tone(1)),
                ReplySuggestion(text("Valid concern, but context matters here.", "Concern valid hai, but context bhi matter karta hai."), "supportive", tone(2)),
            )
            "joking" -> listOf(
                ReplySuggestion(text("That caught me off guard 😂", "Ye toh unexpected tha 😂"), "witty", tone(0)),
                ReplySuggestion(text("Okay that was actually funny.", "Haha ye genuinely funny tha."), "short", tone(1)),
                ReplySuggestion(text("Now I can’t unsee this 😂", "Ab ye unsee hi nahi hoga 😂"), "witty", tone(2)),
            )
            else -> listOf(
                ReplySuggestion(text("Interesting take.", "Interesting take hai."), "short", tone(0)),
                ReplySuggestion(text("Fair point, that makes sense.", "Fair point, makes sense."), "supportive", tone(1)),
                ReplySuggestion(text("Could you share more context?", "Thoda aur context share karoge?"), "curious", tone(2)),
            )
        }

        val toneBridge = listOf(
            ReplySuggestion(text("Good one.", "Sahi hai."), "short", tone(0)),
            ReplySuggestion(text("That’s a fair perspective.", "Ye fair perspective hai."), "supportive", tone(1)),
        )
        return (base + toneBridge).mapNotNull { normalizeSuggestion(it, lengthMode) }
    }

    private fun finalizeSuggestions(
        primary: List<ReplySuggestion>,
        backups: List<ReplySuggestion>,
        lengthMode: String,
    ): List<ReplySuggestion> {
        val out = mutableListOf<ReplySuggestion>()
        val seen = HashSet<String>()

        fun tryAdd(candidate: ReplySuggestion) {
            if (out.size >= 5) return
            val shortText = shortenText(candidate.text, lengthMode)
            if (shortText.isBlank()) return
            val key = canonicalText(shortText)
            if (!seen.add(key)) return
            out.add(candidate.copy(text = shortText))
        }

        for (candidate in primary) tryAdd(candidate)
        for (candidate in backups) tryAdd(candidate)

        val fallbackTexts = if (lengthMode == "short") {
            listOf("Nice.", "Good point.", "Makes sense.", "Interesting.", "Fair point.")
        } else {
            listOf("Nice!", "Good point.", "That makes sense.", "Interesting take.", "Fair point.")
        }
        for (text in fallbackTexts) {
            if (out.size >= 5) break
            tryAdd(ReplySuggestion(text, "short", "neutral"))
        }

        while (out.size < 5) {
            out.add(ReplySuggestion("Nice!", "short", "neutral"))
        }
        return out.take(5)
    }

    private fun finalizeChatSuggestions(
        primary: List<ReplySuggestion>,
        backups: List<ReplySuggestion>,
        lengthMode: String,
    ): List<ReplySuggestion> {
        val out = mutableListOf<ReplySuggestion>()
        val seen = HashSet<String>()

        fun tryAdd(candidate: ReplySuggestion) {
            if (out.size >= 5) return
            val shortText = shortenText(candidate.text, lengthMode)
            if (shortText.isBlank()) return
            val key = shortText.lowercase().trim()
            if (!seen.add(key)) return
            out.add(candidate.copy(text = shortText))
        }

        for (candidate in primary) tryAdd(candidate)
        for (candidate in backups) tryAdd(candidate)

        var attempt = 0
        while (out.size < 5 && attempt < 12) {
            attempt++
            val filler = backups.firstOrNull()?.text ?: primary.firstOrNull()?.text ?: break
            val suffix = when (out.size % 3) {
                0 -> "."
                1 -> " please."
                else -> "!"
            }
            tryAdd(ReplySuggestion("$filler$suffix", "direct", "neutral"))
        }

        return out.take(5)
    }

    private fun buildPrimaryRewritePool(
        primaryText: String,
        lengthMode: String,
        looksQuestion: Boolean,
    ): List<ReplySuggestion> {
        val base = primaryText.replace(Regex("\\s+"), " ").trim()
        if (base.isBlank()) return emptyList()

        val coreRaw = base.trimEnd().trimEnd('.', '!', '?')
        val core = if (coreRaw.isBlank()) base else coreRaw
        val head = sentenceCase(core)
        val lower = lowercaseStart(core)
        val question = looksQuestion || base.contains("?")

        val variants = if (question) {
            listOf(
                "$head?",
                "$head please?",
                "Please, $lower?",
                "Hey, $lower?",
                "Just checking, $lower?",
                "Kindly, $lower?",
            )
        } else {
            listOf(
                "$head.",
                head,
                "$head please.",
                "Please, $lower.",
                "Hey, $lower.",
                "Kindly, $lower.",
            )
        }

        val archetype = if (question) "curious" else "direct"
        return variants.mapNotNull { normalizeSuggestion(ReplySuggestion(it, archetype, "neutral"), lengthMode) }
    }

    private fun shortenText(text: String, lengthMode: String): String {
        return text.replace(Regex("\\s+"), " ").trim()
    }

    private fun canonicalText(text: String): String = text.lowercase().replace(Regex("[^a-z0-9\\s]"), "").trim()

    private fun sameText(a: String, b: String): Boolean = canonicalText(a) == canonicalText(b)

    private fun looksLikePrimaryRewrite(candidate: String, primary: String): Boolean {
        val c = canonicalText(candidate)
        val p = canonicalText(primary)
        if (c.isBlank() || p.isBlank()) return false
        if (c == p) return true

        val cTokens = c.split(Regex("\\s+")).filter { it.length >= 3 }.toSet()
        val pTokens = p.split(Regex("\\s+")).filter { it.length >= 3 }.toSet()
        if (cTokens.isEmpty() || pTokens.isEmpty()) return false

        val overlap = cTokens.intersect(pTokens).size.toDouble() / minOf(cTokens.size, pTokens.size).toDouble()
        return overlap >= 0.75
    }

    private fun starterSignature(text: String): String {
        val t = text.lowercase().trim()
        return t.split(Regex("\\s+")).take(2).joinToString(" ")
    }

    private fun sentenceCase(text: String): String {
        val cleaned = text.trim()
        if (cleaned.isBlank()) return cleaned
        return cleaned.replaceFirstChar { ch ->
            if (ch.isLowerCase()) ch.titlecase() else ch.toString()
        }
    }

    private fun lowercaseStart(text: String): String {
        val cleaned = text.trim()
        if (cleaned.isBlank()) return cleaned
        return cleaned.replaceFirstChar { ch ->
            if (ch.isUpperCase()) ch.lowercase() else ch.toString()
        }
    }

    private fun primaryFocusScore(text: String, profile: IntelligenceProfile): Int {
        if (profile.replyType != "chat") return 0
        if (profile.primaryTokens.isEmpty()) return 0

        val tokens = importantTokens(text)
        if (tokens.isEmpty()) return -2

        val overlapCount = tokens.intersect(profile.primaryTokens).size
        val driftCount = tokens.intersect(profile.secondaryOnlyTokens).size

        var score = 0
        score += overlapCount * 3
        score -= driftCount * 3
        if (overlapCount == 0) score -= 5
        if (profile.primaryLooksQuestion && text.contains("?")) score += 1
        return score
    }

    private fun importantTokens(text: String): Set<String> {
        return text
            .lowercase()
            .replace(Regex("[^a-z0-9\\s]"), " ")
            .split(Regex("\\s+"))
            .asSequence()
            .map { it.trim() }
            .filter { it.length >= 3 }
            .filterNot(::isStopToken)
            .toSet()
    }

    private fun isStopToken(token: String): Boolean {
        return token in setOf(
            "the", "and", "for", "with", "you", "your", "are", "was", "were", "this", "that",
            "have", "has", "from", "about", "what", "why", "how", "can", "could", "will", "would",
            "just", "not", "too", "very", "then", "than", "when", "where", "into", "onto", "near",
            "reply", "draft", "message", "messages", "chat", "text", "context", "mode", "capture",
            "kya", "kyu", "kyun", "hai", "haan", "nahi", "acha", "accha", "bhai", "yaar", "bhot",
            "bahut", "tum", "mera", "main", "bhi", "aur"
        )
    }
}

private object IntentSignalDetector {
    fun detect(
        contextIntent: String,
        userDraft: String,
        primaryText: String,
        secondaryTexts: List<String>,
    ): String {
        val ctx = contextIntent.lowercase().trim()
        if (ctx in setOf("asking", "joking", "praising", "criticizing", "disagreeing")) return ctx

        val sample = buildString {
            append(userDraft).append(' ')
            append(primaryText).append(' ')
            append(secondaryTexts.joinToString(" "))
        }.lowercase()

        return when {
            sample.contains("?") || listOf("why", "how", "what", "kya", "kyu", "source").any { sample.contains(it) } -> "asking"
            listOf("lol", "lmao", "haha", "😂", "🤣").any { sample.contains(it) } -> "joking"
            listOf("great", "nice", "awesome", "love", "sahi", "mast").any { sample.contains(it) } -> "praising"
            listOf("disagree", "not true", "doesn't make sense", "nah").any { sample.contains(it) } -> "disagreeing"
            listOf("stupid", "bakwas", "hate", "worst", "bad take").any { sample.contains(it) } -> "criticizing"
            else -> "neutral"
        }
    }
}

private object ToneSignalTracker {
    fun track(
        contextTone: String,
        controlsToneBias: String,
        intent: String,
        primaryText: String,
        secondaryTexts: List<String>,
    ): String {
        val ctx = contextTone.lowercase().trim()
        if (ctx in setOf("heated", "playful", "friendly", "serious")) return ctx

        return when (controlsToneBias.lowercase()) {
            "funny" -> "playful"
            "polite" -> "friendly"
            "serious" -> "serious"
            else -> inferFromText(intent, primaryText, secondaryTexts)
        }
    }

    private fun inferFromText(intent: String, primaryText: String, secondaryTexts: List<String>): String {
        val sample = (primaryText + " " + secondaryTexts.joinToString(" ")).lowercase()
        if (listOf("idiot", "stupid", "hate", "bakwas", "wtf").any { sample.contains(it) }) return "heated"
        if (listOf("lol", "haha", "😂", "🤣").any { sample.contains(it) }) return "playful"
        if (intent == "praising") return "friendly"
        if (intent == "criticizing" || intent == "disagreeing") return "serious"
        return "neutral"
    }
}
