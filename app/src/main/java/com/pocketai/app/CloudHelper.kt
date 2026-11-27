package com.pocketai.app

import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

enum class CloudProvider(val label: String, val defaultModel: String) {
    GEMINI("Gemini", "gemini-3.5-flash-lite"),
    OPENAI("ChatGPT", "gpt-4o-mini"),
    CLAUDE("Claude", "claude-sonnet-4-5")
}

object CloudHelper {

    fun fetchWebText(query: String): String {
        return try {
            val cleanQ = query
                .replace(Regex("""(?i)\b(?:please|tell me|can you|what is|what are|who is|who was|where is|when is|summari[sz]e|batao|dass|kya hai|ki hai|search|google|look up)\b"""), " ")
                .replace(Regex("""\s+"""), " ").trim().ifBlank { query }
            fun httpGet(u: String): String = (URL(u).openConnection() as HttpURLConnection).run {
                requestMethod = "GET"; connectTimeout = 7000; readTimeout = 8000
                setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 15)")
                inputStream.bufferedReader().use { it.readText() }
            }
            val ddgHtml = runCatching { httpGet("https://html.duckduckgo.com/html/?q=" + Uri.encode(cleanQ)) }.getOrDefault("")
            val ddg = Regex("""class="result__snippet"[^>]*>(.*?)</a>""", RegexOption.DOT_MATCHES_ALL)
                .findAll(ddgHtml)
                .map { it.groupValues[1].replace(Regex("<[^>]+>"), "").replace("&amp;", "&").replace("&#x27;", "'").trim() }
                .filter { it.length > 20 }.take(4).toList()
            if (ddg.isNotEmpty()) return ddg.joinToString("\n- ", prefix = "- ")

            val rssXml = runCatching { httpGet("https://news.google.com/rss/search?q=" + Uri.encode(cleanQ) + "&hl=en-IN&gl=IN&ceid=IN:en") }.getOrDefault("")
            val news = Regex("""<title>(.*?)</title>""", RegexOption.DOT_MATCHES_ALL)
                .findAll(rssXml).drop(1)
                .map { it.groupValues[1].replace("<![CDATA[", "").replace("]]>", "").trim() }
                .filter { it.length > 15 }.take(4).toList()
            if (news.isNotEmpty()) return news.joinToString("\n- ", prefix = "- ")

            val wikiJson = runCatching { httpGet("https://en.wikipedia.org/api/rest_v1/page/summary/" + Uri.encode(cleanQ.replace(" ", "_"))) }.getOrDefault("")
            val extract = runCatching { JSONObject(wikiJson).optString("extract", "") }.getOrDefault("")
            if (extract.isNotBlank()) "- ${extract.take(500)}" else ""
        } catch (_: Exception) {
            ""
        }
    }

    fun ask(provider: CloudProvider, apiKey: String, model: String, prompt: String): String {
        if (apiKey.isBlank()) return "Cloud Help: No API key set. Tap API to add one."
        val chosenModel = model.ifBlank { provider.defaultModel }
        return try {
            when (provider) {
                CloudProvider.GEMINI -> callGemini(apiKey.trim(), chosenModel, prompt)
                CloudProvider.OPENAI -> callOpenAI(apiKey.trim(), chosenModel, prompt)
                CloudProvider.CLAUDE -> callClaude(apiKey.trim(), chosenModel, prompt)
            }
        } catch (e: Exception) {
            "Cloud error (${provider.label}):${e.message}"
        }
    }

    private fun callGemini(key: String, model: String, prompt: String): String {
        val url = URL("https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent?key=$key")
        val body = JSONObject().put("contents", JSONArray().put(JSONObject().put("parts", JSONArray().put(JSONObject().put("text", prompt)))))
        val resp = postJson(url, body.toString(), emptyMap())
        return JSONObject(resp).getJSONArray("candidates").getJSONObject(0)
            .getJSONObject("content").getJSONArray("parts").getJSONObject(0).getString("text").trim()
    }

    private fun callOpenAI(key: String, model: String, prompt: String): String {
        val url = URL("https://api.openai.com/v1/chat/completions")
        val body = JSONObject().put("model", model).put("messages", JSONArray().put(JSONObject().put("role", "user").put("content", prompt)))
        val resp = postJson(url, body.toString(), mapOf("Authorization" to "Bearer $key"))
        return JSONObject(resp).getJSONArray("choices").getJSONObject(0).getJSONObject("message").getString("content").trim()
    }

    private fun callClaude(key: String, model: String, prompt: String): String {
        val url = URL("https://api.anthropic.com/v1/messages")
        val body = JSONObject().put("model", model).put("max_tokens", 512)
            .put("messages", JSONArray().put(JSONObject().put("role", "user").put("content", prompt)))
        val resp = postJson(url, body.toString(), mapOf("x-api-key" to key, "anthropic-version" to "2023-06-01"))
        return JSONObject(resp).getJSONArray("content").getJSONObject(0).getString("text").trim()
    }

    private fun postJson(url: URL, jsonBody: String, headers: Map<String, String>): String {
        val conn = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"; connectTimeout = 15000; readTimeout = 30000; doOutput = true
            setRequestProperty("Content-Type", "application/json")
            headers.forEach { (k, v) -> setRequestProperty(k, v) }
        }
        conn.outputStream.use { it.write(jsonBody.toByteArray(Charsets.UTF_8)) }
        val code = conn.responseCode
        val stream = if (code in 200..299) conn.inputStream else conn.errorStream
        val text = stream.bufferedReader().use { it.readText() }
        if (code !in 200..299) throw RuntimeException("HTTP $code:${text.take(180)}")
        return text
    }
}
