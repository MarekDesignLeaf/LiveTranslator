package com.example.livetranslator

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

class AiClient {
    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()
    private val jsonMedia = "application/json; charset=utf-8".toMediaType()

    fun generateReply(
        apiKey: String,
        userText: String,
        targetLanguageLabel: String,
        onResult: (Result<String>) -> Unit
    ) {
        val trimmedKey = apiKey.trim()
        if (trimmedKey.isEmpty()) {
            onResult(Result.failure(IllegalArgumentException("Missing API key")))
            return
        }

        val systemPrompt = "You are a helpful assistant. Reply in $targetLanguageLabel. Keep your answer short and clear unless the user explicitly asks for more detail."

        // Standard Chat Completions API (works with gpt-4o-mini and compatible models)
        val bodyJson = JSONObject().apply {
            put("model", "gpt-4o-mini")
            put("max_tokens", 300)
            put("messages", JSONArray().apply {
                put(JSONObject().apply {
                    put("role", "system")
                    put("content", systemPrompt)
                })
                put(JSONObject().apply {
                    put("role", "user")
                    put("content", userText)
                })
            })
        }

        val request = Request.Builder()
            .url("https://api.openai.com/v1/chat/completions")
            .addHeader("Authorization", "Bearer $trimmedKey")
            .addHeader("Content-Type", "application/json")
            .post(bodyJson.toString().toRequestBody(jsonMedia))
            .build()

        Thread {
            try {
                http.newCall(request).execute().use { resp ->
                    val body = resp.body?.string().orEmpty()
                    if (!resp.isSuccessful) {
                        // Try to extract OpenAI error message
                        val errMsg = try {
                            JSONObject(body).optJSONObject("error")?.optString("message") ?: body
                        } catch (_: Exception) { body }
                        onResult(Result.failure(RuntimeException("HTTP ${resp.code}: $errMsg")))
                        return@use
                    }
                    val text = extractChatText(body)
                    if (text.isBlank()) {
                        onResult(Result.failure(RuntimeException("Empty AI response")))
                    } else {
                        onResult(Result.success(text.trim()))
                    }
                }
            } catch (e: Exception) {
                onResult(Result.failure(e))
            }
        }.start()
    }

    private fun extractChatText(rawJson: String): String = try {
        JSONObject(rawJson)
            .optJSONArray("choices")
            ?.optJSONObject(0)
            ?.optJSONObject("message")
            ?.optString("content", "")
            ?: ""
    } catch (_: Exception) { "" }
}
