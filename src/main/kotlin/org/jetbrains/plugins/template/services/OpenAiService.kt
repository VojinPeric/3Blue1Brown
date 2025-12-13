package org.jetbrains.plugins.template.services

import com.intellij.openapi.components.Service
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import com.fasterxml.jackson.databind.ObjectMapper

@Service(Service.Level.PROJECT)
class OpenAiService {

    private val mapper = ObjectMapper()

    private val http: HttpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(20))
        .build()

    fun ask(req: AiRequest): String {
        val apiKey = System.getenv("OPENAI_API_KEY")
            ?: error("OPENAI_API_KEY is not set in the runIde process")

        val payload = mapper.createObjectNode().apply {
            put("model", "gpt-4.1")
            put("instructions", buildInstructions())
            put("input", buildInput(req))
        }.toString()

        val request = HttpRequest.newBuilder()
            .uri(URI.create("https://api.openai.com/v1/responses"))
            .timeout(Duration.ofSeconds(60))
            .header("Authorization", "Bearer $apiKey")
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(payload))
            .build()

        val resp = http.send(request, HttpResponse.BodyHandlers.ofString())

        if (resp.statusCode() !in 200..299) {
            error("OpenAI HTTP ${resp.statusCode()}: ${resp.body().take(2000)}")
        }

        return parseOutputText(resp.body())
    }

    private fun buildInstructions(): String =
        """
        You are a senior software engineer helping inside IntelliJ.
        Be precise, reference the provided code, and if something is missing say what you need.
        Return Markdown.
        """.trimIndent()

    private fun buildInput(req: AiRequest): String {
        val snippet = req.selectedSnippet?.takeIf { it.isNotBlank() } ?: "(none)"
        val filePath = req.filePath?.takeIf { it.isNotBlank() } ?: "(unknown)"
        val fileText = req.fileText?.takeIf { it.isNotBlank() } ?: "(not provided)"

        return """
        ## Question
        ${req.question}

        ## File
        Path: $filePath
        Language: ${req.languageHint ?: "(unknown)"}

        ## Selected snippet
        ``` 
        $snippet
        ```

        ## Full file (may be truncated)
        ```
        ${fileText.take(40_000)}
        ```
        """.trimIndent()
    }

    private fun parseOutputText(rawJson: String): String {
        val root = mapper.readTree(rawJson)

        // Responses return output items containing message.content items of type "output_text" :contentReference[oaicite:8]{index=8}
        val outputs = root.path("output")
        val sb = StringBuilder()

        for (out in outputs) {
            if (out.path("type").asText() != "message") continue
            val content = out.path("content")
            for (c in content) {
                if (c.path("type").asText() == "output_text") {
                    sb.append(c.path("text").asText())
                }
            }
        }

        val text = sb.toString().trim()
        return if (text.isNotEmpty()) text else "No text returned from model."
    }
}
