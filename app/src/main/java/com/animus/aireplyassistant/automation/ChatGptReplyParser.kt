package com.animus.aireplyassistant.automation

object ChatGptReplyParser {
    fun parseFromCandidates(candidates: List<String>): List<String>? {
        for (raw in candidates) {
            val parsed = parseFromText(raw)
            if (parsed != null) return parsed
        }
        return null
    }

    fun parseFromText(raw: String): List<String>? {
        val text = raw.trim()
        if (text.isBlank()) return null
        if (looksLikePromptInstruction(text)) return null

        val labeled = Regex(
            pattern = "(?is)reply\\s*1\\s*[:\\-]\\s*(.+?)\\s*reply\\s*2\\s*[:\\-]\\s*(.+?)\\s*reply\\s*3\\s*[:\\-]\\s*(.+)",
        ).find(text)
        if (labeled != null) {
            return listOf(
                clean(labeled.groupValues[1]),
                clean(labeled.groupValues[2]),
                clean(labeled.groupValues[3]),
            ).takeIf(::isValidTriple)
        }

        val lines = text
            .lines()
            .map { it.trim() }
            .filter { it.isNotBlank() }

        val numbered = lines
            .mapNotNull { line ->
                Regex("^\\s*(?:reply\\s*)?([1-3])\\s*[\\):\\-.]\\s*(.+)\\s*$", RegexOption.IGNORE_CASE)
                    .find(line)
                    ?.let { match ->
                        match.groupValues[1].toInt() to clean(match.groupValues[2])
                    }
            }
            .sortedBy { it.first }
            .map { it.second }

        if (numbered.size >= 3) {
            return numbered.take(3).takeIf(::isValidTriple)
        }

        val bulleted = lines
            .mapNotNull { line ->
                Regex("^\\s*[-*•]\\s+(.+)\\s*$")
                    .find(line)
                    ?.groupValues
                    ?.getOrNull(1)
                    ?.let(::clean)
            }
            .filter(::looksLikeReplyLine)
        if (bulleted.size >= 3) {
            return bulleted.take(3).takeIf(::isValidTriple)
        }

        val unlabeledLines = lines
            .map(::stripPrefix)
            .map(::clean)
            .filter(::looksLikeReplyLine)
            .distinctBy { it.lowercase() }
        if (unlabeledLines.size >= 3) {
            return unlabeledLines.take(3).takeIf(::isValidTriple)
        }

        val blocks = text
            .split(Regex("\\n\\s*\\n+"))
            .map(::stripPrefix)
            .map(::clean)
            .filter(::looksLikeReplyLine)
            .distinctBy { it.lowercase() }
        if (blocks.size >= 3) {
            return blocks.take(3).takeIf(::isValidTriple)
        }

        return null
    }

    private fun clean(input: String): String {
        val s = input
            .replace(Regex("\\s+"), " ")
            .trim()
            .trim('"', '\'', '`')
        return if (s.length > 320) s.take(320).trimEnd() else s
    }

    private fun isValidTriple(values: List<String>): Boolean {
        if (values.size != 3) return false
        if (values.any { it.length < 2 }) return false
        return values.distinctBy { it.lowercase() }.size == 3
    }

    private fun stripPrefix(input: String): String {
        return input
            .replace(Regex("^\\s*(?:reply\\s*)?[1-3]\\s*[\\):\\-.]\\s*", RegexOption.IGNORE_CASE), "")
            .replace(Regex("^\\s*[-*•]\\s+"), "")
            .trim()
    }

    private fun looksLikePromptInstruction(input: String): Boolean {
        if (input.contains(ChatGptPromptBuilder.USER_PROMPT_MARKER, ignoreCase = true)) return true
        val lowered = input.lowercase()
        return lowered.contains("generate exactly 3 options") ||
            lowered.contains("you are writing a reply for a post on x") ||
            lowered.contains("do not include explanations") ||
            lowered.contains("extracted post text")
    }

    private fun looksLikeReplyLine(input: String): Boolean {
        val t = input.trim()
        if (t.length !in 8..320) return false
        if (looksLikePromptInstruction(t)) return false
        val lowered = t.lowercase()
        val uiNoise = setOf(
            "send",
            "stop",
            "retry",
            "copy",
            "edit",
            "new chat",
            "search",
        )
        if (uiNoise.contains(lowered)) return false
        if (lowered.startsWith("message ")) return false
        return true
    }
}
