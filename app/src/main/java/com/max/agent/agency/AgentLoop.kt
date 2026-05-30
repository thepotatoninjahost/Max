package com.max.agent.agency

import com.max.agent.models.ModelManager
import com.nexa.sdk.bean.ChatMessage
import com.nexa.sdk.bean.LlmStreamResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class AgentLoop(
    private val modelManager: ModelManager,
    private val agency: Agency
) {
    var onToken: ((String) -> Unit)? = null
    var onStep:  ((String) -> Unit)? = null

    suspend fun run(
        systemPrompt: String,
        history: List<ChatMessage>,
        userMessage: String
    ): String = withContext(Dispatchers.IO) {
        
        val everydayEntry = modelManager.getEverydayEntry() ?: return@withContext "Error: EVERYDAY model not configured."
        if (!modelManager.isSlotLoaded(ModelManager.Slot.EVERYDAY)) {
            modelManager.loadSlotAsync(ModelManager.Slot.EVERYDAY, everydayEntry)
        }

        val messages = mutableListOf(ChatMessage("system", systemPrompt))
        messages.addAll(history)
        messages.add(ChatMessage("user", userMessage))

        var currentTurn = 1
        val maxTurns = 5
        var finalAnswer = ""

        while (currentTurn <= maxTurns) {
            val prompt = modelManager.applyChatTemplateForSlot(ModelManager.Slot.EVERYDAY, messages) ?: break
            val responseBuilder = StringBuilder()
            var actionParsed: Agency.Action? = null

            modelManager.generateStreamFlowForSlot(ModelManager.Slot.EVERYDAY, prompt).collect { res ->
                if (res is LlmStreamResult.Token) {
                    responseBuilder.append(res.text)
                    onToken?.invoke(res.text)
                    if (responseBuilder.length > 8000) {
                        modelManager.stopSlotStream(ModelManager.Slot.EVERYDAY)
                    }
                    if (responseBuilder.contains("</action>")) {
                        actionParsed = agency.parseAction(responseBuilder.toString())
                    }
                    if (actionParsed == null) {
                        extractJsonAction(responseBuilder)?.let { json ->
                            actionParsed = agency.parseAction(json)
                        }
                    }
                    if (actionParsed != null) modelManager.stopSlotStream(ModelManager.Slot.EVERYDAY)
                }
            }

            if (actionParsed == null) {
                finalAnswer = responseBuilder.toString()
                break
            }

            onStep?.invoke("[action] ${actionParsed!!.type}")
            val result = agency.executeAction(actionParsed!!)
            onStep?.invoke(if (result.success) "[ok] ${result.output.take(120)}" else "[fail] ${result.error}")
            
            if (result.isFatal) {
                return@withContext "FATAL SYSTEM ERROR: ${result.error}"
            }

            val resultMsg = "Result: ${result.success}. Output: ${result.output.take(1000)}"
            messages.add(ChatMessage("user", resultMsg))
            currentTurn++
        }
        finalAnswer
    }

    private fun extractJsonAction(buf: CharSequence): String? {
        val typeIdx = buf.indexOf("\"type\"")
        if (typeIdx < 0) return null
        var start = -1
        var rdepth = 0
        for (i in typeIdx downTo 0) {
            val c = buf[i]
            if (c == '}') rdepth++
            else if (c == '{') {
                if (rdepth == 0) { start = i; break }
                rdepth--
            }
        }
        if (start < 0) return null
        var depth = 0
        var inString = false
        var escape = false
        for (i in start until buf.length) {
            val c = buf[i]
            if (escape) { escape = false; continue }
            if (c == '\\') { escape = true; continue }
            if (c == '"') { inString = !inString; continue }
            if (inString) continue
            if (c == '{') depth++
            else if (c == '}') {
                depth--
                if (depth == 0) return buf.substring(start, i + 1)
            }
        }
        return null
    }
}
