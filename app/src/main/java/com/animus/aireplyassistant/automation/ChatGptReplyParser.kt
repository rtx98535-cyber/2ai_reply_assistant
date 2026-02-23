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
        if (text.contains(ChatGptPromptBuilder.USER_PROMPT_MARKER, ignoreCase = true)) return null
        if (text.contains("Generate exactly 3 options", ignoreCase = true)) return null

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
}

