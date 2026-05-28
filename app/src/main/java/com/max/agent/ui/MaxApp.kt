package com.max.agent.ui

import android.Manifest
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.CutCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.max.agent.auth.OwnerAuth
import com.max.agent.core.MaxIdentity
import com.max.agent.core.MaxSystem
import com.max.agent.core.MaxSystem.SystemState
import com.max.agent.models.ModelManager
import com.max.agent.models.ModelManager.ModelEntry
import com.max.agent.models.ModelManager.TransferState
import com.max.agent.safety.ActionLog
import com.max.agent.safety.Constitution
import com.max.agent.safety.PermissionGate.PermissionState
import com.max.agent.voice.VoiceEngine
import com.nexa.sdk.bean.ChatMessage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.*

// ─────────────────────────────────────────────────────────────────────────────
// Abstract Matrix Colors & Styles
// ─────────────────────────────────────────────────────────────────────────────

// === SUPERVILLAIN PALETTE ===
// Dark, gloomy substrate with vivid radioactive accents.
val VoidBlack      = Color(0xFF000000)
val Carbon         = Color(0xFF0A080C)   // slight purple tint
val ObsidianMist   = Color(0xFF1A141F)   // panel surface
val CyanCore       = Color(0xFF39FF14)   // toxic neon green (primary accent)
val AlertRed       = Color(0xFF8B0000)   // blood red (danger / destructive)
val WarningYellow  = Color(0xFFFF6B00)   // hazard orange (warn / caution)
val VenomPurple    = Color(0xFF9D00FF)   // venom purple (secondary accent)
val GhostWhite     = Color(0xFFEAEAE6)   // primary text — dimmed for gloom
val GhostDim       = Color(0xFF2A2A2A)   // borders / muted text

val MatrixFont = FontFamily.Monospace

@Composable
fun MatrixText(text: String, color: Color = GhostWhite, size: Int = 12, weight: FontWeight = FontWeight.Normal, modifier: Modifier = Modifier) {
    Text(text = text, color = color, fontSize = size.sp, fontFamily = MatrixFont, fontWeight = weight, modifier = modifier, letterSpacing = 1.sp)
}

@Composable
fun WireframeButton(text: String, color: Color = CyanCore, onClick: () -> Unit, modifier: Modifier = Modifier, isActive: Boolean = false) {
    Box(
        modifier = modifier
            .border(1.dp, color.copy(alpha = if (isActive) 1f else 0.5f), CutCornerShape(4.dp))
            .background(color.copy(alpha = if (isActive) 0.2f else 0.05f))
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        contentAlignment = Alignment.Center
    ) {
        MatrixText(text, color, 12, FontWeight.Bold)
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Root Coordinate Space
// ─────────────────────────────────────────────────────────────────────────────

@Composable
fun MaxApp(max: MaxSystem) {
    val authState by max.ownerAuth.state.collectAsState()
    
    Box(modifier = Modifier.fillMaxSize().background(VoidBlack).imePadding()) {
        when (authState) {
            OwnerAuth.State.NOT_SETUP -> SetupScreen(max.ownerAuth)
            OwnerAuth.State.LOCKED   -> UnlockScreen(max.ownerAuth)
            OwnerAuth.State.UNLOCKED -> MaxMainContent(max)
        }
    }
}

@Composable
private fun SetupScreen(auth: OwnerAuth) {
    var step by remember { mutableIntStateOf(0) }
    var name by remember { mutableStateOf("") }
    var pass1 by remember { mutableStateOf("") }
    var pass2 by remember { mutableStateOf("") }
    var error by remember { mutableStateOf("") }

    Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier.fillMaxWidth(0.85f).verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(24.dp)
        ) {
            MatrixText("INITIALIZE CORE", CyanCore, 18, FontWeight.Black)
            
            when (step) {
                0 -> {
                    MatrixText("IDENTIFY COMMANDER.", GhostWhite, 12)
                    WireframeButton("PROCEED", CyanCore, { step = 1 }, Modifier.fillMaxWidth())
                }
                1 -> {
                    AbstractInput("HANDLE", name) { name = it }
                    WireframeButton("NEXT", CyanCore, { if (name.isNotBlank()) step = 2 else error = "AWAITING HANDLE" }, Modifier.fillMaxWidth())
                    if (error.isNotBlank()) MatrixText(error, AlertRed)
                }
                2 -> {
                    AbstractInput("PASSPHRASE", pass1, isPassword = true) { pass1 = it }
                    AbstractInput("CONFIRM", pass2, isPassword = true) { pass2 = it }
                    if (error.isNotBlank()) MatrixText(error, AlertRed)
                    WireframeButton("ENCRYPT", CyanCore, {
                        when {
                            pass1.length < 8 -> error = "MIN 8 CHARACTERS"
                            pass1 != pass2   -> error = "MISMATCH"
                            else             -> { auth.saveOwner(name, pass1); step = 3 }
                        }
                    }, Modifier.fillMaxWidth())
                }
                3 -> {
                    MatrixText("BIOMETRIC LINK REQUIRED.", CyanCore, 12)
                    WireframeButton("LINK AND BOOT", CyanCore, { auth.lock() }, Modifier.fillMaxWidth())
                }
            }
        }
    }
}

@Composable
private fun AbstractInput(label: String, value: String, isPassword: Boolean = false, singleLine: Boolean = true, onValueChange: (String) -> Unit) {
    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
        MatrixText(label, GhostDim, 10)
        Spacer(Modifier.height(4.dp))
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            textStyle = TextStyle(color = CyanCore, fontSize = 16.sp, fontFamily = MatrixFont),
            visualTransformation = if (isPassword) PasswordVisualTransformation() else androidx.compose.ui.text.input.VisualTransformation.None,
            cursorBrush = SolidColor(CyanCore),
            singleLine = singleLine,
            modifier = Modifier.fillMaxWidth().drawBehind {
                drawLine(GhostDim, Offset(0f, size.height), Offset(size.width, size.height), 2f)
            }.padding(bottom = 4.dp)
        )
    }
}

@Composable
private fun UnlockScreen(auth: OwnerAuth) {
    val activity = LocalContext.current as? androidx.fragment.app.FragmentActivity
    var showPass by remember { mutableStateOf(false) }
    var passInput by remember { mutableStateOf("") }
    var error by remember { mutableStateOf("") }

    LaunchedEffect(Unit) {
        activity?.let {
            auth.showBiometric(it, onSuccess = { }, onFail = { showPass = true })
        } ?: run { showPass = true }
    }

    Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(24.dp), modifier = Modifier.fillMaxWidth(0.8f)) {
            MatrixText("AUTHENTICATE", CyanCore, 18, FontWeight.Black)

            if (showPass) {
                AbstractInput("KEY", passInput, isPassword = true) { passInput = it; error = "" }
                if (error.isNotBlank()) MatrixText(error, AlertRed)
                WireframeButton("DECRYPT", CyanCore, {
                    if (!auth.verifyPassphrase(passInput)) { error = "ACCESS DENIED"; passInput = "" }
                }, Modifier.fillMaxWidth())
            } else {
                MatrixText("AWAITING BIOMETRICS...", GhostDim)
                Box(modifier = Modifier.clickable { showPass = true }.padding(8.dp)) {
                    MatrixText("MANUAL OVERRIDE", CyanCore, 10)
                }
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Abstract Matrix Architecture
// ─────────────────────────────────────────────────────────────────────────────

@Composable
fun MaxMainContent(max: MaxSystem) {
    var currentTab by remember { mutableIntStateOf(0) }
    val systemState by max.systemState.collectAsState()
    val permissionState by max.permissionGate.state.collectAsState()
    val networkState by max.networkGuard.state.collectAsState()
    val logEntries by max.actionLog.entries.collectAsState()
    Box(modifier = Modifier.fillMaxSize().background(VoidBlack)) {
        // Main Content Layer
        Box(modifier = Modifier.fillMaxSize().padding(top = 80.dp, bottom = 80.dp)) {
            when (currentTab) {
                0 -> ChatTab(max)
                1 -> ModelsTab(max)
                2 -> TerminalTab(max)
                3 -> SystemTab(max)
                4 -> LogTab(logEntries)
                5 -> RulesTab()
            }
        }

        // Floating HUD Header (Replaces TopBar & Drawer Actions)
        HUDHeader(systemState, networkState, max)

        // Permission Override Layer
        PermissionOverlay(permissionState, max)

        // Orbital Navigation Matrix (Bottom Right)
        OrbitalNav(currentTab) { currentTab = it }
    }
}

@Composable
private fun HUDHeader(state: SystemState, netState: com.max.agent.network.NetworkGuard.NetworkState, max: MaxSystem) {
    val status = when (state) {
        is SystemState.Ready -> "ACTIVE"
        is SystemState.LockedDown -> "HALTED"
        is SystemState.Initializing -> "BOOTING"
        is SystemState.Error -> "FAULT"
        else -> "OFFLINE"
    }
    val color = if (state is SystemState.Error || state is SystemState.LockedDown) AlertRed else CyanCore
    val netEnabled = netState == com.max.agent.network.NetworkGuard.NetworkState.ENABLED_BY_OWNER

    Column(
        modifier = Modifier.fillMaxWidth().background(VoidBlack.copy(alpha = 0.9f)).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.Top) {
            Column {
                MatrixText("NEXUS_UI_v4", GhostDim, 8)
                MatrixText("SYS_STATE: [$status]", color, 12, FontWeight.Bold)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (netEnabled) {
                    WireframeButton("NET: ON", CyanCore, { max.networkGuard.ownerDisableInternet() }, Modifier.height(36.dp), isActive = true)
                } else {
                    WireframeButton("NET: OFF", GhostDim, { max.networkGuard.ownerRequestInternet() }, Modifier.height(36.dp))
                }
                
                if (state is SystemState.LockedDown) {
                    WireframeButton("UNLOCK", CyanCore, { max.unlockSystem() }, Modifier.height(36.dp))
                } else {
                    WireframeButton("HALT", AlertRed, { max.stopNow() }, Modifier.height(36.dp))
                }
            }
        }
        HorizontalDivider(color = GhostDim.copy(alpha = 0.3f))
    }
}

@Composable
private fun OrbitalNav(current: Int, onSelect: (Int) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    val rotation by animateFloatAsState(if (expanded) 45f else 0f, label = "rot")

    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.BottomEnd) {
        Box(modifier = Modifier.padding(24.dp), contentAlignment = Alignment.BottomEnd) {
            
            // Nodes (Expanded to include all original features)
            val tabs = listOf("UPLINK", "CORES", "SHELL", "DIAG", "LOGS", "DIRECTIVES")
            tabs.forEachIndexed { index, label ->
                val offset by animateFloatAsState(if (expanded) ((tabs.size - index) * 60f) else 0f, spring(dampingRatio = Spring.DampingRatioMediumBouncy), label = "off")
                val alpha by animateFloatAsState(if (expanded) 1f else 0f, tween(200), label = "alpha")
                
                Box(
                    modifier = Modifier
                        .offset(y = (-offset).dp)
                        .graphicsLayer { this.alpha = alpha }
                        .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { onSelect(index); expanded = false },
                    contentAlignment = Alignment.CenterEnd
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        MatrixText(label, if (current == index) CyanCore else GhostWhite, 10, if (current == index) FontWeight.Bold else FontWeight.Normal)
                        Spacer(Modifier.width(16.dp))
                        Box(modifier = Modifier.size(8.dp).background(if (current == index) CyanCore else GhostDim, CircleShape))
                    }
                }
            }

            // Core Trigger
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .graphicsLayer { rotationZ = rotation }
                    .border(1.dp, CyanCore, CutCornerShape(12.dp))
                    .background(Carbon, CutCornerShape(12.dp))
                    .clickable { expanded = !expanded },
                contentAlignment = Alignment.Center
            ) {
                Icon(Icons.Default.Add, null, tint = CyanCore)
            }
        }
    }
}

@Composable
private fun PermissionOverlay(state: PermissionState, max: MaxSystem) {
    AnimatedVisibility(visible = state !is PermissionState.Idle, enter = fadeIn(), exit = fadeOut()) {
        Box(modifier = Modifier.fillMaxSize().background(VoidBlack.copy(alpha = 0.95f)), contentAlignment = Alignment.Center) {
            Column(modifier = Modifier.border(1.dp, AlertRed, CutCornerShape(8.dp)).background(Carbon).padding(24.dp).fillMaxWidth(0.9f)) {
                when (state) {
                    is PermissionState.AwaitingFirstApproval, is PermissionState.AwaitingSecondApproval -> {
                        val req = (state as? PermissionState.AwaitingFirstApproval)?.request ?: (state as PermissionState.AwaitingSecondApproval).request
                        val isSecond = state is PermissionState.AwaitingSecondApproval
                        val riskColor = when (req.riskLevel) {
                            is Constitution.RiskLevel.High -> AlertRed
                            is Constitution.RiskLevel.Medium -> WarningYellow
                            else -> CyanCore
                        }
                        
                        MatrixText(if (isSecond) "CONFIRMATION OVERRIDE" else "AUTHORIZATION REQUIRED", riskColor, 14, FontWeight.Bold)
                        Spacer(Modifier.height(12.dp))
                        MatrixText("ACTION: ${req.action}", GhostWhite)
                        MatrixText("DETAILS: ${req.reason}", GhostDim)
                        MatrixText("RISK: ${req.riskLevel.label}", riskColor)
                        Spacer(Modifier.height(24.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                            WireframeButton("APPROVE", CyanCore, { max.permissionGate.approve() }, Modifier.weight(1f))
                            WireframeButton("DENY", AlertRed, { max.permissionGate.deny() }, Modifier.weight(1f))
                        }
                    }
                    is PermissionState.Approved -> {
                        MatrixText("OVERRIDE ACCEPTED", CyanCore)
                        Spacer(Modifier.height(16.dp))
                        WireframeButton("CLOSE", GhostWhite, { max.permissionGate.reset() }, Modifier.fillMaxWidth())
                    }
                    is PermissionState.Denied -> {
                        MatrixText("OVERRIDE REJECTED", AlertRed)
                        Spacer(Modifier.height(16.dp))
                        WireframeButton("CLOSE", GhostWhite, { max.permissionGate.reset() }, Modifier.fillMaxWidth())
                    }
                    is PermissionState.LockedDown -> {
                        MatrixText("CRITICAL: SYSTEM LOCKED", AlertRed, 16, FontWeight.Black)
                    }
                    else -> {}
                }
            }
        }
    }
}

// ─────────────────────
