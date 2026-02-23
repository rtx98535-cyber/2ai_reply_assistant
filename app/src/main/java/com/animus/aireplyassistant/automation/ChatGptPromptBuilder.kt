package com.animus.aireplyassistant.automation

object ChatGptPromptBuilder {
    const val USER_PROMPT_MARKER = "AIRA_PROMPT_V1_DO_NOT_REPEAT"

    fun build(postText: String): String {
        val normalized = postText
            .replace("\r", "\n")
            .lines()
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .take(16)
            .joinToString("\n")
            .ifBlank { "No post text extracted. Infer from screenshot only." }

        return """
            $USER_PROMPT_MARKER
            You are writing a reply for a post on X (Twitter).
            Use the attached screenshot as primary context, and the extracted text as additional context.
            Keep each reply under 260 characters.
            Avoid hashtags unless absolutely necessary.
            Do not include explanations, labels, markdown, or any extra text.
            Extracted post text:
            $normalized

            Generate exactly 3 options in this exact format:
            Reply 1: <text>
            Reply 2: <text>
            Reply 3: <text>
        """.trimIndent()
    }
}

