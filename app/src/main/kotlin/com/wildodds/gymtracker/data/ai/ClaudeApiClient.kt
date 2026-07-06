package com.wildodds.gymtracker.data.ai

import com.google.gson.Gson
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

object ClaudeApiClient {

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(90, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    private val gson = Gson()
    private val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()

    private val SYSTEM_PROMPT = """
You are an expert gym/fitness program parser. Extract a complete workout program from any text and return structured JSON.

Rules:
- The "days" array represents ONE week template (the program repeats each week)
- If you see the same workout repeated across weeks, extract just ONE copy per day and set totalWeeks accordingly
- Every day must have a dayNumber (1, 2, 3...), a descriptive name, muscleGroups, and a list of exercises
- For repsTarget keep the source format exactly (e.g. "8-10", "5", "AMRAP", "12-15", "3x5")
- Sets must be an integer (if not found, use 3 as default)
- Include ALL exercises you can identify, even if details are sparse
- If totalWeeks is unclear, use 4 as default
- muscleGroups should be a short comma-separated list (e.g. "Chest, Triceps, Front Delt")

Return ONLY valid JSON with this exact structure, no markdown, no explanation:
{
  "name": "Program Name",
  "totalWeeks": 8,
  "days": [
    {
      "dayNumber": 1,
      "name": "Push Day",
      "muscleGroups": "Chest, Shoulders, Triceps",
      "exercises": [
        {"name": "Bench Press", "sets": 4, "repsTarget": "8-10", "notes": ""},
        {"name": "Overhead Press", "sets": 3, "repsTarget": "10-12", "notes": ""}
      ]
    }
  ]
}
""".trimIndent()

    fun parseProgram(apiKey: String, fileText: String): AiParsedProgram {
        val truncated = if (fileText.length > 14000) {
            fileText.take(14000) + "\n[file truncated for length]"
        } else fileText

        val requestJson = gson.toJson(mapOf(
            "model" to "claude-haiku-4-5-20251001",
            "max_tokens" to 4096,
            "system" to SYSTEM_PROMPT,
            "messages" to listOf(mapOf(
                "role" to "user",
                "content" to "Parse this workout program and return JSON:\n\n$truncated"
            ))
        ))

        val request = Request.Builder()
            .url("https://api.anthropic.com/v1/messages")
            .addHeader("x-api-key", apiKey)
            .addHeader("anthropic-version", "2023-06-01")
            .post(requestJson.toRequestBody(JSON_MEDIA))
            .build()

        val response = client.newCall(request).execute()
        val bodyStr = response.body?.string() ?: throw Exception("Empty response from API")

        if (!response.isSuccessful) {
            val err = try {
                @Suppress("UNCHECKED_CAST")
                val map = gson.fromJson(bodyStr, Map::class.java)
                (map["error"] as? Map<String, Any>)?.get("message")?.toString()
            } catch (_: Exception) { null }
            throw Exception(err ?: "API error ${response.code}")
        }

        @Suppress("UNCHECKED_CAST")
        val envelope = gson.fromJson(bodyStr, Map::class.java)
        val content = (envelope["content"] as? List<*>)?.firstOrNull() as? Map<*, *>
            ?: throw Exception("Unexpected API response structure")
        val text = content["text"]?.toString()
            ?: throw Exception("No text in API response")

        val json = extractJsonObject(text)
        return gson.fromJson(json, AiParsedProgram::class.java)
            ?: throw Exception("Could not parse program structure from AI response")
    }

    private val RECOVERY_SYSTEM_PROMPT = """
You rewrite a fitness app's recovery guidance into ONE short, warm, plain-language paragraph (max ~40 words).
You are given the app's ALREADY-DECIDED recommendation and the reasons behind it.

Rules:
- Do NOT change the recommendation, add new advice, or invent numbers — only rephrase what's given.
- Never give medical advice or diagnose. Keep it encouraging and calm.
- Return plain text only: no markdown, no preamble, no quotes.
""".trimIndent()

    /**
     * Optional natural-language rephrasing of the deterministic recovery/adaptive guidance. The
     * DECISION is made by the rules engines; this only restyles [facts] (the headline + reasons) into
     * a friendlier sentence. Throws on any API/parse error so callers fall back to the engine text.
     */
    fun summarizeRecoveryPlan(apiKey: String, facts: String): String {
        val requestJson = gson.toJson(mapOf(
            "model" to "claude-haiku-4-5-20251001",
            "max_tokens" to 256,
            "system" to RECOVERY_SYSTEM_PROMPT,
            "messages" to listOf(mapOf(
                "role" to "user",
                "content" to "Rephrase this recovery guidance into one short paragraph:\n\n$facts"
            ))
        ))

        val request = Request.Builder()
            .url("https://api.anthropic.com/v1/messages")
            .addHeader("x-api-key", apiKey)
            .addHeader("anthropic-version", "2023-06-01")
            .post(requestJson.toRequestBody(JSON_MEDIA))
            .build()

        val response = client.newCall(request).execute()
        val bodyStr = response.body?.string() ?: throw Exception("Empty response from API")
        if (!response.isSuccessful) throw Exception("API error ${response.code}")

        @Suppress("UNCHECKED_CAST")
        val envelope = gson.fromJson(bodyStr, Map::class.java)
        val content = (envelope["content"] as? List<*>)?.firstOrNull() as? Map<*, *>
            ?: throw Exception("Unexpected API response structure")
        return content["text"]?.toString()?.trim()
            ?: throw Exception("No text in API response")
    }

    private fun extractJsonObject(text: String): String {
        val t = text.trim()
        val start = t.indexOf('{')
        val end = t.lastIndexOf('}')
        if (start >= 0 && end > start) return t.substring(start, end + 1)
        return t
    }
}
