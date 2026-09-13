package com.flue.launcher.watchface.agent

import com.flue.launcher.watchface.agent.tools.HtmlTextExtractor
import com.flue.launcher.watchface.agent.tools.TavilyWebResearchClient
import com.flue.launcher.watchface.agent.tools.WebHttpResponse
import com.flue.launcher.watchface.agent.tools.WebHttpTransport
import com.flue.launcher.watchface.agent.tools.WatchAgentWebSearchRequest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentWebResearchClientTest {
    @Test fun `search parses tavily hits and keeps stable request fields`() {
        var request: String? = null
        val client = TavilyWebResearchClient(
            baseUrl = "https://search.example",
            apiKey = "secret",
            transport = WebHttpTransport { method, url, _, body ->
                assertEquals("POST", method)
                assertEquals("https://search.example/search", url)
                request = body
                WebHttpResponse(200, "{\"results\":[{\"title\":\"A\",\"url\":\"https://a.example\",\"content\":\"snippet\",\"score\":0.8}]}")
            },
        )
        val result = client.search(WatchAgentWebSearchRequest("watchmaker", 3, "advanced"))
        assertEquals("watchmaker", result.query)
        assertEquals("A", result.hits.single().title)
        assertTrue(request.orEmpty().contains("\"search_depth\":\"advanced\""))
        assertTrue(request.orEmpty().contains("\"max_results\":3"))
    }

    @Test fun `extract falls back to bounded html text when API has no content`() {
        val calls = mutableListOf<String>()
        val client = TavilyWebResearchClient(
            baseUrl = "https://search.example",
            apiKey = "secret",
            transport = WebHttpTransport { method, url, _, _ ->
                calls += "$method $url"
                if (url.endsWith("/extract")) WebHttpResponse(200, "{\"results\":[{}]}")
                else WebHttpResponse(200, "<html><script>ignore()</script><h1>Title</h1><p>Hello &amp; world</p></html>", "text/html")
            },
        )
        val result = client.fetchPage("https://example.com/article", maxChars = 1_000)
        assertEquals("http", result.source)
        assertTrue(result.content.contains("Title"))
        assertTrue(result.content.contains("Hello & world"))
        assertEquals(listOf("POST https://search.example/extract", "GET https://example.com/article"), calls)
    }

    @Test fun `html extractor removes scripts and tags`() {
        assertEquals("Heading\nBody", HtmlTextExtractor.extract("<h1>Heading</h1><script>x()</script><p>Body</p>"))
    }
}
