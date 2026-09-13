package com.flue.launcher.watchface.agent.provider

import java.io.ByteArrayOutputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets
import java.util.Base64
import java.util.UUID

data class OpenAiImageOutput(
    val bytes: ByteArray,
    val format: String,
    val size: String? = null,
)

interface AgentImageGenerationClient {
    fun generate(prompt: String, options: ImageGenerationOptions = ImageGenerationOptions()): OpenAiImageOutput
    fun edit(prompt: String, input: ByteArray, inputName: String, mask: ByteArray? = null, maskName: String? = null, options: ImageGenerationOptions = ImageGenerationOptions()): OpenAiImageOutput
}

/** OpenAI Images API adapter. It deliberately exposes only documented image fields. */
class OpenAiImageClient(
    baseUrl: String,
    private val apiKey: String,
    private val model: String,
    private val connectionFactory: (String) -> HttpURLConnection = { URL(it).openConnection() as HttpURLConnection },
) : AgentImageGenerationClient {
    private val generationUrl = AgentEndpointResolver.imageGeneration(baseUrl)
    private val editUrl = AgentEndpointResolver.imageEdit(baseUrl)

    init {
        require(apiKey.isNotBlank()) { "图片 API 密钥不能为空" }
        require(model.isNotBlank()) { "图片模型 ID 不能为空" }
    }

    override fun generate(prompt: String, options: ImageGenerationOptions): OpenAiImageOutput {
        require(prompt.isNotBlank()) { "图片描述不能为空" }
        // GPT Image models return b64_json by default.  `response_format` is a
        // DALL·E-only field and is rejected by the current GPT Image endpoint,
        // so leave it out to keep both official and compatible providers happy.
        return requestJson(generationUrl, baseFields(prompt, options), options)
    }

    override fun edit(prompt: String, input: ByteArray, inputName: String, mask: ByteArray?, maskName: String?, options: ImageGenerationOptions): OpenAiImageOutput {
        require(prompt.isNotBlank()) { "图片修改描述不能为空" }
        require(input.isNotEmpty()) { "待修改图片不能为空" }
        val boundary = "----FlueAgentImage-${UUID.randomUUID()}"
        val body = MultipartBody(boundary).apply {
            text("model", model)
            text("prompt", prompt)
            options.size?.let { text("size", it) }
            options.quality?.let { text("quality", it) }
            options.background?.let { text("background", it) }
            options.outputFormat?.let { text("output_format", it) }
            options.outputCompression?.let { text("output_compression", it.toString()) }
            options.inputFidelity?.let { text("input_fidelity", it) }
            options.moderation?.let { text("moderation", it) }
            file("image", inputName.ifBlank { "input.png" }, mimeFor(inputName), input)
            if (mask != null) file("mask", maskName?.ifBlank { "mask.png" } ?: "mask.png", mimeFor(maskName), mask)
        }.finish()
        val response = execute("POST", editUrl, "multipart/form-data; boundary=$boundary", body)
        return parseOutput(response, options)
    }

    private fun baseFields(prompt: String, options: ImageGenerationOptions): MutableMap<String, Any?> = linkedMapOf<String, Any?>(
        "model" to model,
        "prompt" to prompt,
        "size" to options.size,
        "quality" to options.quality,
        "background" to options.background,
        "output_format" to options.outputFormat,
        "output_compression" to options.outputCompression,
        "moderation" to options.moderation,
    ).filterValues { it != null }.toMutableMap()

    private fun requestJson(url: String, fields: Map<String, Any?>, options: ImageGenerationOptions): OpenAiImageOutput {
        val response = execute("POST", url, "application/json", AgentJsonWriter.objectValue(fields).toByteArray(StandardCharsets.UTF_8))
        return parseOutput(response, options)
    }

    private fun execute(method: String, url: String, contentType: String, body: ByteArray): String {
        val connection = connectionFactory(url).apply {
            requestMethod = method
            connectTimeout = 20_000
            readTimeout = 120_000
            doInput = true
            doOutput = true
            setRequestProperty("Authorization", "Bearer $apiKey")
            setRequestProperty("Content-Type", contentType)
            setRequestProperty("Accept", "application/json")
            setRequestProperty("User-Agent", "Flue-Agent/${com.flue.launcher.BuildConfig.VERSION_NAME}")
        }
        return try {
            connection.outputStream.use { it.write(body) }
            val status = connection.responseCode
            val stream = if (status in 200..299) connection.inputStream else connection.errorStream
            val text = stream?.bufferedReader(StandardCharsets.UTF_8)?.use { it.readText().take(MAX_RESPONSE_CHARS) }.orEmpty()
            if (status !in 200..299) throw IllegalStateException("图片 API 返回 HTTP $status")
            text
        } finally { connection.disconnect() }
    }

    private fun parseOutput(body: String, options: ImageGenerationOptions): OpenAiImageOutput {
        val root = AgentJsonParser.parse(body) as? AgentJsonValue.Obj ?: throw IllegalStateException("图片 API 返回了无效 JSON")
        val item = (root.array("data")?.items?.firstOrNull() as? AgentJsonValue.Obj)
            ?: throw IllegalStateException("图片 API 没有返回图片")
        val b64 = item.string("b64_json")
        val bytes = if (!b64.isNullOrBlank()) Base64.getDecoder().decode(b64) else {
            val remote = item.string("url") ?: throw IllegalStateException("图片 API 未返回 b64_json 或 URL")
            download(remote)
        }
        require(bytes.size in 1..MAX_IMAGE_BYTES) { "图片结果超过 20 MB" }
        val format = options.outputFormat?.lowercase()?.takeIf { it in setOf("png", "jpeg", "webp") } ?: "png"
        return OpenAiImageOutput(bytes, format, item.string("size") ?: options.size)
    }

    private fun download(url: String): ByteArray {
        val connection = connectionFactory(url).apply {
            requestMethod = "GET"
            connectTimeout = 15_000
            readTimeout = 60_000
            doInput = true
        }
        return try {
            if (connection.responseCode !in 200..299) throw IllegalStateException("图片下载返回 HTTP ${connection.responseCode}")
            val output = ByteArrayOutputStream()
            connection.inputStream.use { input ->
                val buffer = ByteArray(8192)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    output.write(buffer, 0, count)
                    require(output.size() <= MAX_IMAGE_BYTES) { "图片结果超过 20 MB" }
                }
            }
            output.toByteArray()
        } finally { connection.disconnect() }
    }

    private fun mimeFor(name: String?): String = when (name?.substringAfterLast('.', "")?.lowercase()) {
        "jpg", "jpeg" -> "image/jpeg"
        "webp" -> "image/webp"
        else -> "image/png"
    }

    private class MultipartBody(private val boundary: String) {
        private val output = ByteArrayOutputStream()
        fun text(name: String, value: String) {
            output.write("--$boundary\r\n".toByteArray())
            output.write("Content-Disposition: form-data; name=\"$name\"\r\n\r\n".toByteArray())
            output.write(value.toByteArray(StandardCharsets.UTF_8)); output.write("\r\n".toByteArray())
        }
        fun file(name: String, filename: String, mime: String, bytes: ByteArray) {
            output.write("--$boundary\r\n".toByteArray())
            output.write("Content-Disposition: form-data; name=\"$name\"; filename=\"${filename.replace(Regex("[^A-Za-z0-9._-]"), "_")}\"\r\n".toByteArray())
            output.write("Content-Type: $mime\r\n\r\n".toByteArray()); output.write(bytes); output.write("\r\n".toByteArray())
        }
        fun finish(): ByteArray { output.write("--$boundary--\r\n".toByteArray()); return output.toByteArray() }
    }

    private companion object { const val MAX_RESPONSE_CHARS = 2_000_000; const val MAX_IMAGE_BYTES = 20 * 1024 * 1024 }
}
