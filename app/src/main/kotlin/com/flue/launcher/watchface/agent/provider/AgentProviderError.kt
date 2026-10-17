package com.flue.launcher.watchface.agent.provider

import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLException

class AgentProviderException(
    val displayMessage: String,
    cause: Throwable? = null,
) : IOException(displayMessage, cause)

object AgentErrorSanitizer {
    fun forHttp(
        providerName: String,
        statusCode: Int,
        body: String?,
        secrets: Collection<String> = emptyList(),
    ): String {
        val safeProvider = providerName.trim().take(80).ifBlank { "AI 服务商" }
        val raw = body.orEmpty()
            .replace(Regex("<[^>]*>"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
        val redacted = redact(raw, secrets).take(240)
        val detail = redacted.ifBlank { "服务端未提供详细信息" }
        return "$safeProvider 请求失败（HTTP $statusCode）：$detail"
    }

    fun forThrowable(providerName: String, error: Throwable, secrets: Collection<String> = emptyList()): String {
        val message = redact(error.message.orEmpty(), secrets).replace(Regex("\\s+"), " ").trim()
        val phase = when (error) {
            is UnknownHostException -> "DNS 解析"
            is SSLException -> "TLS 握手"
            is ConnectException -> "连接"
            is SocketTimeoutException -> "读取超时"
            is IOException -> "网络读取"
            else -> "解析"
        }
        return "${providerName.trim().take(80).ifBlank { "AI 服务商" }} ${phase}失败：${message.take(240).ifBlank { "未提供详细信息" }}"
    }

    private fun redact(value: String, secrets: Collection<String>): String {
        var output = value
        secrets.filter { it.isNotBlank() }.forEach { output = output.replace(it, "[已隐藏]") }
        output = output.replace(
            Regex("(?i)(api[-_ ]?key|authorization|x-api-key|access[-_ ]?token)\\s*[:=]\\s*[^,; ]+"),
            "$1=[已隐藏]",
        )
        return output
    }
}
