package com.flue.launcher.watchface.agent.tools

import com.flue.launcher.watchface.agent.provider.AgentJsonParser
import com.flue.launcher.watchface.agent.provider.AgentJsonValue
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

data class WatchAgentWebSearchRequest(
    val query: String,
    val maxResults: Int = 5,
    val searchDepth: String = "basic",
)

data class WatchAgentWebSearchHit(
    val title: String,
    val url: String,
    val snippet: String,
    val score: Double? = null,
)

data class WatchAgentWebSearchResult(
    val query: String,
    val hits: List<WatchAgentWebSearchHit>,
)

data class WatchAgentWebPageResult(
    val url: String,
    val title: String? = null,
    val content: String,
    val source: String = "tavily",
    val truncated: Boolean = false,
)

interface WatchAgentWebResearchClient {
    fun search(request: WatchAgentWebSearchRequest): WatchAgentWebSearchResult
    fun fetchPage(url: String, maxChars: Int = DEFAULT_MAX_PAGE_CHARS): WatchAgentWebPageResult

    companion object {
        const val DEFAULT_MAX_PAGE_CHARS = 24_000
    }
}

data class WebHttpResponse(val statusCode: Int, val body: String, val contentType: String? = null)

fun interface WebHttpTransport {
    fun execute(method: String, url: String, headers: Map<String, String>, body: String?): WebHttpResponse
}

/** A bounded blocking transport. Tool scheduling runs it on the IO dispatcher. */
class UrlConnectionWebHttpTransport : WebHttpTransport {
    override fun execute(method: String, url: String, headers: Map<String, String>, body: String?): WebHttpResponse {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = 15_000
            readTimeout = 30_000
            doInput = true
            if (body != null) doOutput = true
            headers.forEach { (name, value) -> setRequestProperty(name, value) }
        }
        return try {
            body?.let { connection.outputStream.use { output -> output.write(it.toByteArray(StandardCharsets.UTF_8)) } }
            val status = connection.responseCode
            val stream = if (status in 200..299) connection.inputStream else connection.errorStream
            val text = stream?.bufferedReader(StandardCharsets.UTF_8)?.use { it.readText().take(MAX_HTTP_BODY_CHARS) } ?: ""
            WebHttpResponse(status, text, connection.contentType)
        } finally {
            connection.disconnect()
        }
    }

    private companion object { const val MAX_HTTP_BODY_CHARS = 1_000_000 }
}

/** Tavily search + extract integration used by the optional Agent network tools. */
class TavilyWebResearchClient(
    baseUrl: String,
    private val apiKey: String,
    private val defaultMaxResults: Int = 5,
    private val defaultSearchDepth: String = "basic",
    private val fetchEnabled: Boolean = true,
    private val transport: WebHttpTransport = UrlConnectionWebHttpTransport(),
) : WatchAgentWebResearchClient {
    private val root = baseUrl.trim().trimEnd('/').ifBlank { "https://api.tavily.com" }

    init {
        require(apiKey.isNotBlank()) { "网络搜索 API 密钥不能为空" }
        require(URI(root).scheme in setOf("http", "https")) { "网络搜索 API 地址必须使用 HTTP(S)" }
    }

    override fun search(request: WatchAgentWebSearchRequest): WatchAgentWebSearchResult {
        val query = request.query.trim().takeIf { it.isNotBlank() } ?: throw WatchAgentToolException("invalid_arguments", "搜索关键词不能为空")
        val count = request.maxResults.coerceIn(1, 50)
        val depth = request.searchDepth.takeIf { it in setOf("basic", "advanced") } ?: defaultSearchDepth
        val response = post("/search", mapOf(
            "api_key" to apiKey,
            "query" to query,
            "search_depth" to depth,
            "max_results" to count,
            "include_answer" to false,
            "include_images" to false,
        ))
        val root = parseObject(response)
        val hits = root.array("results")?.items.orEmpty().mapNotNull { item ->
            val obj = item as? AgentJsonValue.Obj ?: return@mapNotNull null
            val url = obj.string("url")?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            WatchAgentWebSearchHit(
                title = obj.string("title").orEmpty().ifBlank { url },
                url = url,
                snippet = obj.string("content").orEmpty().take(4_000),
                score = obj.number("score"),
            )
        }
        return WatchAgentWebSearchResult(query, hits)
    }

    override fun fetchPage(url: String, maxChars: Int): WatchAgentWebPageResult {
        if (!fetchEnabled) throw WatchAgentToolException("web_fetch_disabled", "网页抓取已在 Agent 设置中关闭")
        val cleanUrl = validatePageUrl(url)
        val response = runCatching { post("/extract", mapOf("api_key" to apiKey, "urls" to listOf(cleanUrl))) }.getOrNull()
        if (response != null && response.statusCode in 200..299) {
            val root = parseObject(response)
            val first = root.array("results")?.items?.firstOrNull() as? AgentJsonValue.Obj
            val content = first?.string("raw_content")?.takeIf { it.isNotBlank() }
            if (content != null) {
                val bounded = content.take(maxChars.coerceIn(1_000, WatchAgentWebResearchClient.DEFAULT_MAX_PAGE_CHARS))
                return WatchAgentWebPageResult(cleanUrl, content = bounded, truncated = bounded.length < content.length)
            }
        }
        // Extract can be unavailable for a site. A bounded GET still provides page scraping.
        return fetchHtml(cleanUrl, maxChars)
    }

    private fun fetchHtml(url: String, maxChars: Int): WatchAgentWebPageResult {
        val response = transport.execute("GET", url, mapOf("Accept" to "text/html, text/plain", "User-Agent" to "Flue-Agent/1.0"), null)
        if (response.statusCode !in 200..299) throw WatchAgentToolException("web_fetch_failed", "网页返回 HTTP ${response.statusCode}")
        val plain = HtmlTextExtractor.extract(response.body)
        val limit = maxChars.coerceIn(1_000, WatchAgentWebResearchClient.DEFAULT_MAX_PAGE_CHARS)
        val bounded = plain.take(limit)
        return WatchAgentWebPageResult(url, content = bounded, source = "http", truncated = bounded.length < plain.length)
    }

    private fun post(path: String, values: Map<String, Any?>): WebHttpResponse {
        val response = transport.execute(
            "POST",
            root + path,
            mapOf("Content-Type" to "application/json", "Accept" to "application/json"),
            com.flue.launcher.watchface.agent.provider.AgentJsonWriter.objectValue(values),
        )
        if (response.statusCode !in 200..299) throw WatchAgentToolException("web_search_failed", "网络搜索 API 返回 HTTP ${response.statusCode}")
        return response
    }

    private fun parseObject(response: WebHttpResponse): AgentJsonValue.Obj =
        AgentJsonParser.parse(response.body) as? AgentJsonValue.Obj
            ?: throw WatchAgentToolException("web_search_failed", "网络搜索 API 返回了无效 JSON")

    private fun validatePageUrl(value: String): String {
        val uri = runCatching { URI(value.trim()) }.getOrNull()
            ?: throw WatchAgentToolException("invalid_url", "网页地址无效")
        require(uri.scheme in setOf("http", "https") && !uri.host.isNullOrBlank()) { "网页地址必须是 HTTP(S)" }
        require(uri.userInfo == null) { "网页地址不能包含用户凭据" }
        return uri.toASCIIString()
    }
}

private fun AgentJsonValue.Obj.number(name: String): Double? =
    (value(name) as? AgentJsonValue.Num)?.value

/** HTML-to-text conversion intentionally stays small and dependency-free. */
object HtmlTextExtractor {
    fun extract(html: String): String = html
        .replace(Regex("(?is)<(script|style|noscript|svg)[^>]*>.*?</\\1>"), " ")
        .replace(Regex("(?is)<br\\s*/?>"), "\n")
        .replace(Regex("(?is)</(p|div|li|h[1-6]|tr|article|section)>"), "\n")
        .replace(Regex("(?is)<[^>]+>"), " ")
        .replace("&nbsp;", " ")
        .replace("&amp;", "&")
        .replace("&lt;", "<")
        .replace("&gt;", ">")
        .replace(Regex("[ \\t]+"), " ")
        .replace(Regex(" *\\n *"), "\n")
        .replace(Regex("\\n{3,}"), "\n\n")
        .trim()
}
