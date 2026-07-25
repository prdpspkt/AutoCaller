package com.example.aicallresponder

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * Minimal client for the Anthropic Messages API using java.net (no extra dependencies).
 * Docs: https://docs.claude.com/en/api/messages
 */
class ClaudeClient(
    private val apiKey: String,
    private val model: String
) {
    data class Turn(val role: String, val text: String) // role = "user" | "assistant"

    /** Sends the running transcript and returns Claude's text reply. Runs off the main thread. */
    suspend fun reply(systemPrompt: String, history: List<Turn>): String =
        withContext(Dispatchers.IO) {
            val messages = JSONArray()
            history.forEach { turn ->
                messages.put(JSONObject().apply {
                    put("role", turn.role)
                    put("content", turn.text)
                })
            }

            val payload = JSONObject().apply {
                put("model", model)
                put("max_tokens", 300)
                if (systemPrompt.isNotBlank()) put("system", systemPrompt)
                put("messages", messages)
            }

            val conn = (URL(ENDPOINT).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 15_000
                readTimeout = 30_000
                doOutput = true
                setRequestProperty("x-api-key", apiKey)
                setRequestProperty("anthropic-version", "2023-06-01")
                setRequestProperty("content-type", "application/json")
            }

            try {
                conn.outputStream.use { it.write(payload.toString().toByteArray(Charsets.UTF_8)) }
                val code = conn.responseCode
                val stream = if (code in 200..299) conn.inputStream else conn.errorStream
                val body = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
                if (code !in 200..299) {
                    throw IOException("Claude API error $code: $body")
                }
                parseText(body)
            } finally {
                conn.disconnect()
            }
        }

    private fun parseText(body: String): String {
        val content = JSONObject(body).optJSONArray("content") ?: return ""
        val sb = StringBuilder()
        for (i in 0 until content.length()) {
            val block = content.optJSONObject(i) ?: continue
            if (block.optString("type") == "text") sb.append(block.optString("text"))
        }
        return sb.toString().trim()
    }

    companion object {
        private const val ENDPOINT = "https://api.anthropic.com/v1/messages"
    }
}
