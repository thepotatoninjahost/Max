package com.max.agent.auth

import android.content.Context
import android.content.SharedPreferences
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.UUID
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

class OwnerAuth(private val context: Context) {

    enum class State { NOT_SETUP, LOCKED, UNLOCKED }

    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val _state = MutableStateFlow(
        if (isSetupComplete()) State.LOCKED else State.NOT_SETUP
    )
    val state: StateFlow<State> = _state

    fun isSetupComplete(): Boolean =
        prefs.getString(KEY_HASH, null) != null

    fun ownerName(): String =
        prefs.getString(KEY_NAME, DEFAULT_NAME) ?: DEFAULT_NAME

    fun saveOwner(name: String, passphrase: String) {
        val salt = UUID.randomUUID().toString()
        prefs.edit()
            .putString(KEY_NAME, name.trim().ifBlank { DEFAULT_NAME })
            .putString(KEY_SALT, salt)
            .putString(KEY_HASH, hash(passphrase, salt))
            .apply()
        _state.value = State.UNLOCKED
    }

    fun verifyPassphrase(passphrase: String): Boolean {
        val salt = prefs.getString(KEY_SALT, null) ?: return false
        val stored = prefs.getString(KEY_HASH, null) ?: return false
        val match = stored == hash(passphrase, salt)
        if (match) _state.value = State.UNLOCKED
        return match
    }

    fun lock() { _state.value = State.LOCKED }

    fun showBiometric(
        activity: FragmentActivity,
        onSuccess: () -> Unit,
        onFail: (String) -> Unit
    ) {
        val manager = BiometricManager.from(context)
        val canAuth = manager.canAuthenticate(
            BiometricManager.Authenticators.BIOMETRIC_STRONG or
            BiometricManager.Authenticators.DEVICE_CREDENTIAL
        )
        if (canAuth != BiometricManager.BIOMETRIC_SUCCESS) {
            onFail("Biometric not available on this device")
            return
        }
        val executor = ContextCompat.getMainExecutor(context)
        val prompt = BiometricPrompt(activity, executor,
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    _state.value = State.UNLOCKED
                    onSuccess()
                }
                override fun onAuthenticationError(code: Int, msg: CharSequence) {
                    onFail(msg.toString())
                }
                override fun onAuthenticationFailed() {
                    // do nothing
                }
            }
        )
        val info = BiometricPrompt.PromptInfo.Builder()
            .setTitle("Verify Identity")
            .setSubtitle("Biometric required to access Max")
            .setAllowedAuthenticators(
                BiometricManager.Authenticators.BIOMETRIC_STRONG or
                BiometricManager.Authenticators.DEVICE_CREDENTIAL
            )
            .build()
        prompt.authenticate(info)
    }

    private fun hash(passphrase: String, salt: String): String {
        val spec = PBEKeySpec(
            passphrase.toCharArray(),
            salt.toByteArray(),
            PBKDF2_ITERATIONS,
            PBKDF2_KEY_LENGTH
        )
        return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
            .generateSecret(spec)
            .encoded
            .joinToString("") { "%02x".format(it) }
    }

    companion object {
        private const val PREFS_NAME          = "owner_auth"
        private const val KEY_HASH            = "passphrase_hash"
        private const val KEY_SALT            = "salt"
        private const val KEY_NAME            = "owner_name"
        private const val DEFAULT_NAME        = "Owner"
        private const val PBKDF2_ITERATIONS   = 100_000
        private const val PBKDF2_KEY_LENGTH   = 256
    }
}
