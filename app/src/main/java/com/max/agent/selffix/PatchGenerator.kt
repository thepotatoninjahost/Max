package com.max.agent.selffix

import com.max.agent.models.ModelManager
import com.nexa.sdk.bean.ChatMessage
import com.nexa.sdk.bean.LlmStreamResult
import java.io.File
import java.util.UUID

class PatchGenerator(
    private val modelManager: ModelManager,
    private val hotSwapper: HotSwapper
) {

    data class Patch(
        val id: String = UUID.randomUUID().toString(),
        val targetClass: String,
        val sourceCode: String,
        val description: String,
        val savedPath: String? = null,
        val timestamp: Long = System.currentTimeMillis()
    )

    suspend fun generate(
        errorMessage: String,
        failedCode: String = "",
        webContext: String = "",
        targetClass: String = "UnknownClass"
    ): Patch? {
        val messages = listOf(
            ChatMessage("system", SYSTEM_PROMPT),
            ChatMessage("user", buildRequest(errorMessage, failedCode, webContext))
        )
        val prompt = modelManager.applyChatTemplate(messages) ?: return null

        val sb = StringBuilder()
        runCatching {
            modelManager.generateStreamFlow(prompt, maxTokens = 4096).collect { result ->
                when (result) {
                    is LlmStreamResult.Token -> sb.append(result.text)
                    else -> {}
                }
            }
        }

        val response = sb.toString()
        val code = extractCode(response)
        if (code.isBlank()) return null

        val file = hotSwapper.savePatchSource(code, targetClass)
        return Patch(
            targetClass = targetClass,
            sourceCode = code,
            description = response.lines().firstOrNull { it.isNotBlank() }?.take(120) ?: "Patch for $targetClass",
            savedPath = file.absolutePath
        )
    }

    private fun buildRequest(error: String, code: String, web: String) = buildString {
        append("Error: $error\n")
        if (code.isNotBlank()) append("\nFailing code:\n\n$code\n\n")
        if (web.isNotBlank()) append("\nWeb context:\n$web\n")
        append("\nProvide the fixed Kotlin code.")
    }

    private fun extractCode(response: String): String {
        val regex = Regex("(?is)`{3}(?:kotlin|java)?\\s*\\n(.*?)\\n\\s*`{3}")
        val match = regex.find(response) ?: return ""
        return match.groupValues[1].trim()
    }

    companion object {
        private val SYSTEM_PROMPT = """
            You are a Kotlin/Android bug-fixer. Rules:
            1. Diagnose the root cause in one line.
            2. Output ONLY the corrected Kotlin code inside a standard code block (using 3 backticks).
            3. No explanations after the code. No TODOs. No stubs.
        """.trimIndent()
    }
}
