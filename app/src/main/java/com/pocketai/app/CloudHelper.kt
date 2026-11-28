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
            val cleanQ = query.trim()
            fun httpGet(u: String): String = (URL(u).openConnection() as HttpURLConnection).run {
                requestMethod = "GET"; connectTimeout = 6000; readTimeout = 7000
                setRequestProperty("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 Chrome/124.0 Safari/537.36")
                inputStream.bufferedReader().use { it.readText() }
            }
            val out = linkedSetOf<String>()

            // 0. DuckDuckGo HTML snippets
            val ddgH = runCatching { httpGet("https://html.duckduckgo.com/html/?q=" + Uri.encode(cleanQ)) }.getOrDefault("")
            Regex("""class="result__snippet"[^>]*>(.*?)</a>""", RegexOption.DOT_MATCHES_ALL).findAll(ddgH)
                .map { it.groupValues[1].replace(Regex("<[^>]+>"), "").replace("&amp;", "&").replace("&#x27;", "'").trim() }
                .filter { it.length > 25 }.take(4).forEach { out.add("- $it") }

            // 1. Bing HTML snippets (great for tutorials & general web)
            val bingHtml = runCatching { httpGet("https://www.bing.com/search?q=" + Uri.encode(cleanQ)) }.getOrDefault("")
            Regex("""<li class="b_algo".*?<p[^>]*>(.*?)</p>""", RegexOption.DOT_MATCHES_ALL).findAll(bingHtml)
                .map { it.groupValues[1].replace(Regex("<[^>]+>"), "").replace("&amp;", "&").replace("&#039;", "'").replace("&quot;", "\"").trim() }
                .filter { it.length > 25 }.take(4).forEach { out.add("- $it") }

            // 2. DuckDuckGo Instant Answer JSON
            if (out.size < 3) {
                val ddgJson = runCatching { httpGet("https://api.duckduckgo.com/?q=" + Uri.encode(cleanQ) + "&format=json&no_html=1") }.getOrDefault("")
                runCatching {
                    val o = JSONObject(ddgJson)
                    val abs = o.optString("AbstractText", "").trim()
                    if (abs.length > 20) out.add("- $abs")
                    val rel = o.optJSONArray("RelatedTopics")
                    if (rel != null) for (i in 0 until minOf(rel.length(), 4)) {
                        val t = rel.optJSONObject(i)?.optString("Text", "")?.trim().orEmpty()
                        if (t.length > 25) out.add("- $t")
                    }
                }
            }

            // 3. Wikipedia Full-Text Search JSON (never 404s on multi-word topics)
            if (out.size < 3) {
                val wikiS = runCatching { httpGet("https://en.wikipedia.org/w/api.php?action=query&list=search&srsearch=" + Uri.encode(cleanQ) + "&format=json&srlimit=3") }.getOrDefault("")
                runCatching {
                    val arr = JSONObject(wikiS).getJSONObject("query").getJSONArray("search")
                    for (i in 0 until arr.length()) {
                        val snip = arr.getJSONObject(i).optString("snippet", "").replace(Regex("<[^>]+>"), "").replace("&quot;", "\"").trim()
                        if (snip.length > 20) out.add("- [${arr.getJSONObject(i).optString("title")}] $snip")
                    }
                }
            }

            // 4. Google News RSS fallback
            if (out.isEmpty()) {
                val rssXml = runCatching { httpGet("https://news.google.com/rss/search?q=" + Uri.encode(cleanQ) + "&hl=en-IN&gl=IN&ceid=IN:en") }.getOrDefault("")
                Regex("""<title>(.*?)</title>""", RegexOption.DOT_MATCHES_ALL).findAll(rssXml).drop(1)
                    .map { "- " + it.groupValues[1].replace("<![CDATA[", "").replace("]]>", "").trim() }
                    .filter { it.length > 18 }.take(4).forEach { out.add(it) }
            }

            out.joinToString("\n").take(1400)
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
