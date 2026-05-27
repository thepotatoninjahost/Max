package com.max.agent.safety

import com.max.agent.safety.Constitution.RiskLevel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import java.util.UUID

/**
 * Permission gate enforcing Constitution Rules 2, 3, 4, 8, 9.
 *
 * Every important action routes through requestPermission().
 * Critical actions require double confirmation.
 * Approvals expire after 2 minutes.
 */
class PermissionGate {

    data class PermissionRequest(
        val id: String = UUID.randomUUID().toString(),
        val action: String,
        val reason: String,
        val riskLevel: RiskLevel,
        val details: String = "",
        val isCritical: Boolean = false,
        val timestamp: Long = System.currentTimeMillis()
    )

    sealed class PermissionState {
        data object Idle : PermissionState()
        data class AwaitingFirstApproval(val request: PermissionRequest) : PermissionState()
        data class AwaitingSecondApproval(val request: PermissionRequest) : PermissionState()
        data class Approved(val request: PermissionRequest) : PermissionState()
        data class Denied(val request: PermissionRequest, val reason: String) : PermissionState()
        data object LockedDown : PermissionState()
    }

    private val _state = MutableStateFlow<PermissionState>(PermissionState.Idle)
    val state: StateFlow<PermissionState> = _state

    private var lockdown: Boolean = false

    fun requestPermission(
        action: String,
        reason: String,
        riskLevel: RiskLevel,
        details: String = "",
        isCritical: Boolean = false
    ) {
        if (lockdown) {
            _state.value = PermissionState.Denied(
                PermissionRequest(action = action, reason = reason, riskLevel = riskLevel),
                "System is in lockdown."
            )
            return
        }

        val request = PermissionRequest(
            action = action,
            reason = reason,
            riskLevel = riskLevel,
            details = details,
            isCritical = isCritical
        )

        _state.value = PermissionState.AwaitingFirstApproval(request)
    }

    fun approve() {
        val current = _state.value
        when (current) {
            is PermissionState.AwaitingFirstApproval -> {
                if (isExpired(current.request)) {
                    _state.value = PermissionState.Denied(current.request, "Approval expired.")
                    return
                }
                if (current.request.isCritical) {
                    _state.value = PermissionState.AwaitingSecondApproval(current.request)
                } else {
                    _state.value = PermissionState.Approved(current.request)
                }
            }
            is PermissionState.AwaitingSecondApproval -> {
                if (isExpired(current.request)) {
                    _state.value = PermissionState.Denied(current.request, "Approval expired.")
                    return
                }
                _state.value = PermissionState.Approved(current.request)
            }
            else -> {}
        }
    }

    fun deny(reason: String = "Denied by owner.") {
        val current = _state.value
        if (current is PermissionState.AwaitingFirstApproval ||
            current is PermissionState.AwaitingSecondApproval
        ) {
            val request = (current as? PermissionState.AwaitingFirstApproval)?.request
                ?: (current as PermissionState.AwaitingSecondApproval).request
            _state.value = PermissionState.Denied(request, reason)
        }
    }

    fun reset() {
        _state.value = PermissionState.Idle
    }

    fun lockdown() {
        lockdown = true
        _state.value = PermissionState.LockedDown
    }

    fun unlock() {
        lockdown = false
        _state.value = PermissionState.Idle
    }

    fun isLockedDown(): Boolean = lockdown

    fun stopNow() {
        lockdown()
    }

    enum class Outcome { APPROVED, DENIED, EXPIRED, LOCKED_DOWN }

    suspend fun requestAndAwait(
        action: String,
        requestedBy: String,
        reason: String,
        riskLevel: RiskLevel,
        details: String = "",
        isCritical: Boolean = false,
        timeoutMs: Long = 2 * 60 * 1000L
    ): Outcome {
        if (lockdown) return Outcome.LOCKED_DOWN
        // Tag requestedBy onto the request details so the audit trail surfaces who asked.
        val taggedDetails = if (details.isBlank()) "by:$requestedBy" else "by:$requestedBy — $details"
        requestPermission(action, reason, riskLevel, taggedDetails, isCritical)
        val settled = withTimeoutOrNull(timeoutMs) {
            _state.first { s ->
                s is PermissionState.Approved ||
                    s is PermissionState.Denied ||
                    s is PermissionState.LockedDown
            }
        }
        return when (settled) {
            is PermissionState.Approved -> Outcome.APPROVED
            is PermissionState.Denied -> Outcome.DENIED
            is PermissionState.LockedDown -> Outcome.LOCKED_DOWN
            else -> Outcome.EXPIRED
        }
    }

    private fun isExpired(request: PermissionRequest): Boolean {
        val expirationMs = 2 * 60 * 1000L
        return System.currentTimeMillis() - request.timestamp > expirationMs
    }
}
