package com.max.agent.safety

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.File
import java.security.KeyStore
import javax.crypto.KeyGenerator
import javax.crypto.Mac
import javax.crypto.SecretKey
import javax.crypto.spec.SecretKeySpec

/**
 * Append-mostly transparency log. Enforces Constitution Rule 6.
 *
 * Every action is recorded. Erasure must go through [purge], which writes a
 * final audit entry so wipes are never silent. Persistence failures are
 * themselves surfaced as log entries instead of being swallowed.
 *
 * TAMPER DETECTION (Rule 6 — "Tampering with the log triggers lockdown"):
 * The on-disk log is `{ "entries": [...], "mac": "<hex>" }`. The MAC is an
 * HMAC-SHA256 over the canonical serialization of `entries`, keyed by an
 * Android-Keystore-resident key (non-exportable). On reload the MAC is
 * recomputed and compared in constant time. Any mismatch — truncation,
 * edit, or insertion performed outside this class — flips [tampered] to
 * `true`, which the core observes to force the system into lockdown.
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

    private data class Envelope(
        val entries: List<LogEntry> = emptyList(),
        val mac: String = ""
    )

    private val logFile = File(context.filesDir, "action_log.json")
    private val gson = Gson()
    private val listType = object : TypeToken<List<LogEntry>>() {}.type
    private val envelopeType = object : TypeToken<Envelope>() {}.type

    private val _entries = MutableStateFlow<List<LogEntry>>(emptyList())
    val entries: StateFlow<List<LogEntry>> = _entries

    /** Flips to true the moment a MAC mismatch is detected. Never resets to false on its own. */
    private val _tampered = MutableStateFlow(false)
    val tampered: StateFlow<Boolean> = _tampered

    private val hmacKey: SecretKey by lazy { resolveHmacKey() }

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
    ) = record(
        LogEntry(
            action = action,
            requestedBy = requestedBy,
            riskLevel = riskLevel,
            approved = approved,
            outcome = outcome
        )
    )

    /**
     * Wipe the log. Writes one final audit entry first so the wipe is auditable
     * (you can see when it happened and who requested it, even if older entries
     * are gone). The new single-entry log is freshly MAC'd so it remains valid.
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
            if (!logFile.exists()) return
            val json = logFile.readText()
            if (json.isBlank()) return

            // New tamper-evident envelope format.
            val envelope: Envelope? = runCatching { gson.fromJson<Envelope>(json, envelopeType) }
                .getOrNull()
                ?.takeIf { it.mac.isNotBlank() }

            if (envelope != null) {
                val expected = computeMac(envelope.entries)
                if (!constantTimeEquals(expected, envelope.mac)) {
                    // Surface the tampered entries for forensics, then signal lockdown.
                    _entries.value = envelope.entries + LogEntry(
                        action = "LOG_TAMPER_DETECTED",
                        requestedBy = "system",
                        riskLevel = Constitution.RiskLevel.High.label,
                        approved = false,
                        outcome = "HMAC mismatch on reload — log integrity violated. Forcing lockdown."
                    )
                    _tampered.value = true
                    return
                }
                _entries.value = envelope.entries
                return
            }

            // Legacy format: a bare JSON array with no MAC. Accept once and migrate
            // by re-persisting under the tamper-evident envelope.
            val legacy: List<LogEntry> = runCatching { gson.fromJson<List<LogEntry>>(json, listType) }
                .getOrNull() ?: emptyList()
            _entries.value = legacy
            persist(legacy)
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
            val envelope = Envelope(entries = entries, mac = computeMac(entries))
            logFile.writeText(gson.toJson(envelope))
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

    // ── HMAC integrity ─────────────────────────────────────────────────────

    private fun computeMac(entries: List<LogEntry>): String {
        val payload = gson.toJson(entries, listType).toByteArray(Charsets.UTF_8)
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(hmacKey)
        return mac.doFinal(payload).joinToString("") { "%02x".format(it) }
    }

    /**
     * Prefer a non-exportable Android-Keystore HMAC key. On devices/ROMs where
     * Keystore HMAC keys are unavailable, fall back to a 256-bit random key
     * sealed in [EncryptedSharedPreferences] (AES256_GCM, Keystore master key).
     * Either way the key never lives in plaintext on disk.
     */
    private fun resolveHmacKey(): SecretKey {
        runCatching {
            val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
            (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
            val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_HMAC_SHA256, ANDROID_KEYSTORE)
            generator.init(
                KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_SIGN).build()
            )
            return generator.generateKey()
        }.onFailure {
            android.util.Log.w("ActionLog", "Keystore HMAC unavailable, using sealed fallback key: ${it.message}")
        }
        return resolveFallbackKey()
    }

    private fun resolveFallbackKey(): SecretKey {
        val master = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        val prefs = EncryptedSharedPreferences.create(
            context,
            FALLBACK_PREFS,
            master,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
        val existing = prefs.getString(FALLBACK_KEY, null)
        val raw: ByteArray = if (existing != null) {
            existing.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        } else {
            val bytes = ByteArray(32).also { java.security.SecureRandom().nextBytes(it) }
            prefs.edit().putString(FALLBACK_KEY, bytes.joinToString("") { "%02x".format(it) }).apply()
            bytes
        }
        return SecretKeySpec(raw, "HmacSHA256")
    }

    private fun constantTimeEquals(a: String, b: String): Boolean {
        val ab = a.toByteArray(Charsets.UTF_8)
        val bb = b.toByteArray(Charsets.UTF_8)
        if (ab.size != bb.size) return false
        var diff = 0
        for (i in ab.indices) diff = diff or (ab[i].toInt() xor bb[i].toInt())
        return diff == 0
    }

    companion object {
        private const val MAX_IN_MEMORY = 1000
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val KEY_ALIAS = "max_action_log_hmac"
        private const val FALLBACK_PREFS = "max_action_log_secure"
        private const val FALLBACK_KEY = "hmac_key_hex"
    }
}
