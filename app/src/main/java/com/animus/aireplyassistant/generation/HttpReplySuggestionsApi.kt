package com.animus.aireplyassistant.generation

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

class HttpReplySuggestionsApi(
    private val baseUrl: String,
    private val installId: String,
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(4, TimeUnit.SECONDS)
        .writeTimeout(4, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .callTimeout(25, TimeUnit.SECONDS)
        .build(),
) : ReplySuggestionsApi {
    override suspend fun fetchSuggestions(req: ReplySuggestionsRequest): ReplySuggestionsResponse {
        val url = baseUrl.trimEnd('/') + "/v1/reply-suggestions"

        val json = JSONObject()
            .put(
                "context",
                JSONObject()
                    .put("reply_type", req.context.replyType)
                    .put("primary_text", req.context.primaryText)
                    .put("secondary_texts", JSONArray(req.context.secondaryTexts))
                    .put("intent", req.context.intent)
                    .put("conversation_tone", req.context.conversationTone)
                    .put("user_style", req.context.userStyle)
                    .put("confidence", req.context.confidence),
            )
            .put(
                "controls",
                JSONObject()
                    .put("tone_bias", req.controls.toneBias)
                    .put("length", req.controls.length)
                    .put("emoji_level", req.controls.emojiLevel)
                    .put("slang_level", req.controls.slangLevel),
            )
            .put("surface", req.surface)
            .put("desired_count", req.desiredCount)
            .put("user_draft", req.userDraft)

        val mediaType = "application/json; charset=utf-8".toMediaType()
        val body = json.toString().toRequestBody(mediaType)

        val httpReq = Request.Builder()
            .url(url)
            .addHeader("Content-Type", "application/json")
            .addHeader("X-Install-Id", installId)
            .post(body)
            .build()

        client.newCall(httpReq).execute().use { resp ->
            val raw = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) {
                throw IllegalStateException("HTTP ${resp.code}: $raw")
            }
            return parseResponse(raw)
        }
    }

    private fun parseResponse(raw: String): ReplySuggestionsResponse {
        val root = JSONObject(raw)
        val source = root.optString("source", "unknown")
        val arr = root.getJSONArray("suggestions")
        val out = ArrayList<ReplySuggestion>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            out.add(
                ReplySuggestion(
                    text = o.getString("text"),
                    archetype = o.optString("archetype", "direct"),
                    tone = o.optString("tone", "neutral"),
                ),
            )
        }
        return ReplySuggestionsResponse(
            source = source,
            suggestions = out,
        )
    }
}
