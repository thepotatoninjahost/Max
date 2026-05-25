package com.max.agent.safety

import android.content.Context
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.File

/**
 * Append-mostly transparency log. Constitution Rule 6.
 *
 * Every action is recorded. Erasure must go through [purge] which writes a final
 * audit entry so wipes are never silent. Persistence failures are themselves
 * surfaced as log entries instead of being swallowed.
 *
 * NOTE: This class does NOT perform cryptographic tamper detection. A previous
 * version of the docstring claimed it did — that claim was false and has been
 * removed. Adding a Keystore-backed HMAC over the log is tracked as future work.
 */
class ActionLog(private val context: Context) {

    data class LogEntry(
        val timestamp: Long = System.currentTimeMillis(),
        val action: String,
        val requestedBy: String,
        val riskLevel: String,
        val approved: Boolean,
        val outcome: String
    )

    private val logFile = File(context.filesDir, "action_log.json")
    private val gson = Gson()
    private val type = object : TypeToken<List<LogEntry>>() {}.type

    private val _entries = MutableStateFlow<List<LogEntry>>(emptyList())
    val entries: StateFlow<List<LogEntry>> = _entries

    init { reload() }

    fun record(entry: LogEntry) {
        val next = (_entries.value + entry).takeLast(MAX_IN_MEMORY)
        _entries.value = next
        persist(next)
    }

    fun record(
        action: String,
        requestedBy: String,
        riskLevel: String,
        approved: Boolean,
        outcome: String
    ) = record(LogEntry(
        action = action,
        requestedBy = requestedBy,
        riskLevel = riskLevel,
        approved = approved,
        outcome = outcome
    ))

    /**
     * Wipe the log. Writes one final audit entry first so the wipe is auditable
     * (you can see when it happened and who requested it, even if older entries
     * are gone).
     */
    fun purge(reason: String, requestedBy: String): LogEntry {
        val audit = LogEntry(
            action = "LOG_PURGE",
            requestedBy = requestedBy,
            riskLevel = Constitution.RiskLevel.High.label,
            approved = true,
            outcome = "Reason: $reason"
        )
        _entries.value = listOf(audit)
        persist(listOf(audit))
        return audit
    }

    fun reload() {
        try {
            if (logFile.exists()) {
                val json = logFile.readText()
                if (json.isNotBlank()) {
                    val list: List<LogEntry> = gson.fromJson(json, type) ?: emptyList()
                    _entries.value = list
                }
            }
        } catch (e: Exception) {
            _entries.value = listOf(
                LogEntry(
                    action = "LOG_RELOAD_ERROR",
                    requestedBy = "system",
                    riskLevel = Constitution.RiskLevel.High.label,
                    approved = false,
                    outcome = e.message ?: e.javaClass.simpleName
                )
            )
        }
    }

    private fun persist(entries: List<LogEntry>) {
        try {
            logFile.parentFile?.mkdirs()
            logFile.writeText(gson.toJson(entries))
        } catch (e: Exception) {
            _entries.value = entries + LogEntry(
                action = "LOG_PERSIST_ERROR",
                requestedBy = "system",
                riskLevel = Constitution.RiskLevel.High.label,
                approved = false,
                outcome = e.message ?: e.javaClass.simpleName
            )
        }
    }

    companion object {
        private const val MAX_IN_MEMORY = 1000
    }
}
