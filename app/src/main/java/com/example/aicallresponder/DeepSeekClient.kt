package com.example.aicallresponder

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * Minimal client for the DeepSeek chat-completions API (OpenAI-compatible) using java.net.
 * Docs: https://api-docs.deepseek.com/  •  base URL https://api.deepseek.com
 */
class DeepSeekClient(
    private val apiKey: String,
    private val model: String
) {
    data class Turn(val role: String, val text: String) // role = "user" | "assistant"

    /** Sends the running transcript and returns the assistant's reply. Runs off the main thread. */
    suspend fun reply(systemPrompt: String, history: List<Turn>): String =
        withContext(Dispatchers.IO) {
            val messages = JSONArray()
            if (systemPrompt.isNotBlank()) {
                messages.put(JSONObject().apply {
                    put("role", "system")
                    put("content", systemPrompt)
                })
            }
            history.forEach { turn ->
                messages.put(JSONObject().apply {
                    put("role", turn.role)
                    put("content", turn.text)
                })
            }

            val payload = JSONObject().apply {
                put("model", model)
                put("messages", messages)
                put("max_tokens", 300)
                put("stream", false)
            }

            val conn = (URL(ENDPOINT).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 15_000
                readTimeout = 30_000
                doOutput = true
                setRequestProperty("Authorization", "Bearer $apiKey")
                setRequestProperty("Content-Type", "application/json")
                setRequestProperty("Accept", "application/json")
            }

            try {
                conn.outputStream.use { it.write(payload.toString().toByteArray(Charsets.UTF_8)) }
                val code = conn.responseCode
                val stream = if (code in 200..299) conn.inputStream else conn.errorStream
                val body = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
                if (code !in 200..299) {
                    throw IOException("DeepSeek API error $code: $body")
                }
                parseText(body)
            } finally {
                conn.disconnect()
            }
        }

    private fun parseText(body: String): String {
        val choices = JSONObject(body).optJSONArray("choices") ?: return ""
        val first = choices.optJSONObject(0) ?: return ""
        val message = first.optJSONObject("message") ?: return ""
        return message.optString("content").trim()
    }

    companion object {
        private const val ENDPOINT = "https://api.deepseek.com/chat/completions"
    }
}
