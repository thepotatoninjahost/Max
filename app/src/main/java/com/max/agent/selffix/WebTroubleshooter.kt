package com.max.agent.selffix

import com.max.agent.network.NetworkGuard
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

class WebTroubleshooter(private val networkGuard: NetworkGuard) {

    data class SearchResult(
        val title: String,
        val url: String,
        val snippet: String,
        val codeBlocks: List<String> = emptyList()
    )

    suspend fun searchForFix(errorMessage: String, platform: String = "Android Kotlin"): List<SearchResult> {
        if (!networkGuard.isInternetAllowed()) return emptyList()
        return withContext(Dispatchers.IO) {
            runCatching {
                val query = URLEncoder.encode("$platform $errorMessage fix site:stackoverflow.com OR site:github.com", "UTF-8")
                val html = fetch("https://html.duckduckgo.com/html/?q=$query")
                parseResults(html)
            }.getOrDefault(emptyList())
        }
    }

    suspend fun fetchCodeBlocks(url: String): List<String> {
        if (!networkGuard.isInternetAllowed()) return emptyList()
        return withContext(Dispatchers.IO) {
            runCatching {
                val html = fetch(url)
                extractCodeBlocks(html)
            }.getOrDefault(emptyList())
        }
    }

    private fun fetch(urlString: String): String {
        var conn: HttpURLConnection? = null
        return try {
            conn = URL(urlString).openConnection() as HttpURLConnection
            conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 15)")
            conn.connectTimeout = 10_000
            conn.readTimeout = 10_000
            conn.inputStream.bufferedReader().readText()
        } finally {
            // Explicitly disconnect the socket to prevent resource exhaustion.
            conn?.disconnect()
        }
    }

    private fun parseResults(html: String): List<SearchResult> {
        val results = mutableListOf<SearchResult>()
        val resultRegex = Regex("""class="result__body"[^>]*>(.*?)</div>""", RegexOption.DOT_MATCHES_ALL)
        val titleRegex = Regex("""class="result__title"[^>]*>(.*?)</a>""", RegexOption.DOT_MATCHES_ALL)
        val snippetRegex = Regex("""class="result__snippet"[^>]*>(.*?)</a>""", RegexOption.DOT_MATCHES_ALL)
        val linkRegex = Regex("""result__url[^>]*href="([^"]+)"""")
        val stripTags = Regex("<[^>]+>")

        resultRegex.findAll(html).take(5).forEach { m ->
            val block = m.groupValues[1]
            val title = titleRegex.find(block)?.groupValues?.get(1)?.replace(stripTags, "")?.trim() ?: return@forEach
            val snippet = snippetRegex.find(block)?.groupValues?.get(1)?.replace(stripTags, "")?.trim() ?: ""
            val link = linkRegex.find(block)?.groupValues?.get(1) ?: ""
            results += SearchResult(title = title, url = link, snippet = snippet)
        }
        return results
    }

    private fun extractCodeBlocks(html: String): List<String> {
        val codeRegex = Regex("""<(?:pre|code)[^>]*>(.*?)</(?:pre|code)>""", RegexOption.DOT_MATCHES_ALL)
        val stripTags = Regex("<[^>]+>")
        return codeRegex.findAll(html)
            .map { it.groupValues[1].replace(stripTags, "").replace("&lt;", "<").replace("&gt;", ">").replace("&amp;", "&").trim() }
            .filter { it.length in 30..4000 }
            .take(8)
            .toList()
    }
}
