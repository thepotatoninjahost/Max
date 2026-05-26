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
            OwnerAuth.State.LOCKED   -> UnlockScreen(max.ownerAuth, max)
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
private fun UnlockScreen(auth: OwnerAuth, max: MaxSystem) {
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

// ─────────────────────────────────────────────────────────────────────────────
// Abstract Chat (Uplink)
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun ChatTab(max: MaxSystem) {
    val modelState by max.modelManager.state.collectAsState()
    val voiceMode by max.voiceEngine.mode.collectAsState()
    val transcript by max.voiceEngine.transcript.collectAsState()
    val rms by max.voiceEngine.rms.collectAsState()
    val voiceConfig by max.voiceEngine.config.collectAsState()
    
    val messages = max.conversationHistory
    var inputText by remember { mutableStateOf("") }
    var isGenerating by remember { mutableStateOf(max.isGenerating) }
    var generationJob by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val sendRef = remember { mutableStateOf<((String) -> Unit)?>(null) }
    
    val permLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) sendRef.value?.let { send -> max.voiceEngine.startListening({ send(it) }) }
    }

    LaunchedEffect(Unit) {
        if (messages.isNotEmpty()) listState.animateScrollToItem(messages.size - 1)
    }
    LaunchedEffect(messages.size) {
    }

    fun sendMessage(text: String) {
        if (text.isBlank() || isGenerating) return
        if (text.trim().equals("STOP NOW", ignoreCase = true)) { max.stopNow(); return }

        messages.add(MaxSystem.UiMessage("user", text))
        val replyIdx = messages.size
        messages.add(MaxSystem.UiMessage("assistant", "…"))
        isGenerating = true; max.isGenerating = true
        max.agentLoop.onToken = null; max.agentLoop.onStep = null

        generationJob = scope.launch(Dispatchers.IO) {
            try {
                val finalAnswer = max.agentLoop.run(MaxIdentity.buildSystemPrompt(), messages.dropLast(2).map { ChatMessage(it.role, it.content) }, text)
                val clean = finalAnswer.replace(Regex("(?m)^Thought:.*$"), "").replace(Regex("<action>[\\s\\S]*?</action>"), "").replace(Regex("(?m)^\\[.*?\\].*$"), "").trim()
                withContext(Dispatchers.Main) {
                    messages[replyIdx] = messages[replyIdx].copy(content = clean.ifBlank { "DONE." })
                    isGenerating = false; max.isGenerating = false
                    if (voiceConfig.autoSpeak && clean.isNotBlank()) max.voiceEngine.speak(clean) { if (voiceConfig.handsFree) max.voiceEngine.startListening({ sendMessage(it) }) }
                }
            } catch (e: CancellationException) {
                withContext(Dispatchers.Main) { messages[replyIdx] = messages[replyIdx].copy(content = "[PROCESS HALTED]"); isGenerating = false; max.isGenerating = false }
                throw e
            } catch (e: Exception) {
                withContext(Dispatchers.Main) { messages[replyIdx] = messages[replyIdx].copy(content = "[FAULT: ${e.message}]"); isGenerating = false; max.isGenerating = false }
            }
        }
    }

    sendRef.value = ::sendMessage

    Column(modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        LazyColumn(state = listState, modifier = Modifier.weight(1f).fillMaxWidth(), contentPadding = PaddingValues(vertical = 16.dp)) {
            items(messages) { msg ->
                val isUser = msg.role == "user"
                Column(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp), horizontalAlignment = if (isUser) Alignment.End else Alignment.Start) {
                    MatrixText(if (isUser) "CMD_TX" else "SYS_RX", if (isUser) GhostDim else CyanCore, 8)
                    Box(modifier = Modifier.border(1.dp, if (isUser) GhostDim else CyanCore.copy(alpha = 0.3f), CutCornerShape(4.dp)).padding(12.dp)) {
                        MatrixText(msg.content.ifBlank { "▋" }, GhostWhite, 12)
                    }
                }
            }
            if (isGenerating) item { MatrixText("PROCESSING_DATA_STREAM...", CyanCore, 10) }
        }

        if (voiceMode == VoiceEngine.Mode.LISTENING && transcript.isNotBlank()) {
            MatrixText("RX: $transcript", AlertRed, 10, modifier = Modifier.padding(vertical = 4.dp))
        }

        // Voice Config Toggles (Restored)
        Row(modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            WireframeButton("VOICE", if (voiceConfig.autoSpeak) CyanCore else GhostDim, { max.voiceEngine.updateConfig { copy(autoSpeak = !autoSpeak) } }, Modifier.weight(1f), voiceConfig.autoSpeak)
            WireframeButton("HANDS-FREE", if (voiceConfig.handsFree) CyanCore else GhostDim, { max.voiceEngine.updateConfig { copy(handsFree = !handsFree) } }, Modifier.weight(1f), voiceConfig.handsFree)
            WireframeButton("LOCAL STT", if (voiceConfig.preferOffline) CyanCore else GhostDim, { max.voiceEngine.updateConfig { copy(preferOffline = !preferOffline) } }, Modifier.weight(1f), voiceConfig.preferOffline)
        }

        Row(modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp), verticalAlignment = Alignment.Bottom) {
            Box(modifier = Modifier.weight(1f).border(1.dp, CyanCore, CutCornerShape(4.dp)).background(Carbon).padding(12.dp)) {
                BasicTextField(
                    value = inputText,
                    onValueChange = { inputText = it },
                    textStyle = TextStyle(color = GhostWhite, fontSize = 14.sp, fontFamily = MatrixFont),
                    cursorBrush = SolidColor(CyanCore),
                    modifier = Modifier.fillMaxWidth().defaultMinSize(minHeight = 48.dp),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Default),
                    maxLines = 6
                )
                if (inputText.isEmpty()) MatrixText("Transmit command...", GhostDim, 14)
            }
            Spacer(Modifier.width(8.dp))
            
            // Abstract Mic/Send/Stop Cluster
            if (isGenerating || voiceMode == VoiceEngine.Mode.SPEAKING) {
                Box(modifier = Modifier.size(48.dp).border(1.dp, AlertRed, CutCornerShape(4.dp)).clickable { generationJob?.cancel(); isGenerating = false; max.isGenerating = false; max.voiceEngine.stopSpeaking(); max.voiceEngine.stopListening() }, contentAlignment = Alignment.Center) {
                    Box(modifier = Modifier.size(16.dp).background(AlertRed))
                }
            } else if (inputText.isNotBlank()) {
                Box(modifier = Modifier.size(48.dp).background(CyanCore, CutCornerShape(4.dp)).clickable { sendMessage(inputText.trim()); inputText = "" }, contentAlignment = Alignment.Center) {
                    Icon(Icons.Default.Send, null, tint = VoidBlack)
                }
            } else {
                val micScale by animateFloatAsState(if (voiceMode == VoiceEngine.Mode.LISTENING) 1f + (rms.coerceIn(0f, 10f) / 20f) else 1f, label = "")
                Box(modifier = Modifier.size(48.dp).graphicsLayer { scaleX = micScale; scaleY = micScale }.border(1.dp, if (voiceMode == VoiceEngine.Mode.LISTENING) AlertRed else CyanCore, CutCornerShape(4.dp)).clickable {
                    permLauncher.launch(Manifest.permission.RECORD_AUDIO)
                    if (voiceMode == VoiceEngine.Mode.LISTENING) max.voiceEngine.stopListening() else max.voiceEngine.startListening({ sendMessage(it) })
                }, contentAlignment = Alignment.Center) {
                    Icon(Icons.Default.Mic, null, tint = if (voiceMode == VoiceEngine.Mode.LISTENING) AlertRed else CyanCore)
                }
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Abstract Cores (Models)
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun ModelsTab(max: MaxSystem) {
    val modelState by max.modelManager.state.collectAsState()
    val available by max.modelManager.available.collectAsState()
    val transferState by max.modelManager.transfer.collectAsState()
    var urlInput by remember { mutableStateOf("") }
    
    val everydaySlotState by max.modelManager.everydayState.collectAsState()
    val coderSlotState by max.modelManager.coderState.collectAsState()
    var configuredCoderName by remember { mutableStateOf(max.modelManager.getSlotEntry(ModelManager.Slot.CODER)?.name) }
    var configuredEverydayPath by remember { mutableStateOf(max.modelManager.getSlotEntry(ModelManager.Slot.EVERYDAY)?.path) }

    val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? -> uri?.let { max.modelManager.importFromUri(it) } }

    LazyColumn(modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        
        // Active Matrix Config (Restored Dual Slots)
        item {
            Column(modifier = Modifier.fillMaxWidth().border(1.dp, CyanCore, CutCornerShape(4.dp)).padding(16.dp)) {
                MatrixText("SLOT CONFIGURATION", CyanCore, 14, FontWeight.Bold)
                Spacer(Modifier.height(12.dp))
                
                MatrixText("PRIMARY (PERSISTENT)", GhostDim, 10)
                val configuredEverydayName = max.modelManager.getModelByPath(configuredEverydayPath)?.name
                val primaryLoading = everydaySlotState.isLoading
                val primaryError = everydaySlotState.error
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                    val label = when {
                        primaryLoading -> "⟳ LOADING: ${everydaySlotState.loadedModel?.name ?: configuredEverydayName ?: "..."}"
                        everydaySlotState.isLoaded -> "● ${everydaySlotState.loadedModel?.name}"
                        configuredEverydayName != null -> "○ CACHED: $configuredEverydayName"
                        else -> "○ UNASSIGNED"
                    }
                    MatrixText(label, if (everydaySlotState.isLoaded || configuredEverydayName != null) WarningYellow else AlertRed, 12, modifier = Modifier.weight(1f))
                    if (everydaySlotState.isLoaded || everydaySlotState.isLoading) {
                        WireframeButton("UNLOAD", AlertRed, { max.modelManager.releaseSlot(ModelManager.Slot.EVERYDAY); max.modelManager.saveSlotConfig(null, configuredCoderName?.let { max.modelManager.getSlotEntry(ModelManager.Slot.CODER)?.path }); configuredEverydayPath = null })
                    }
                }
                if (primaryError != null) {
                    MatrixText("✗ ERR: $primaryError", AlertRed, 10, modifier = Modifier.fillMaxWidth().padding(start = 12.dp, bottom = 4.dp))
                }

                Spacer(Modifier.height(8.dp))
                MatrixText("SECONDARY (TACTICAL)", VenomPurple, 10)
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                    MatrixText(if (coderSlotState.isLoaded) "● ${coderSlotState.loadedModel?.name}" else if (configuredCoderName != null) "○ CACHED: $configuredCoderName" else "○ UNASSIGNED", if (coderSlotState.isLoaded) VenomPurple else if (configuredCoderName != null) WarningYellow else AlertRed, 12, modifier = Modifier.weight(1f))
                    if (coderSlotState.isLoaded || coderSlotState.isLoading) {
                        WireframeButton("UNLOAD", AlertRed, { max.modelManager.releaseSlot(ModelManager.Slot.CODER); max.modelManager.saveSlotConfig(configuredEverydayPath, null); configuredCoderName = null })
                    }
                }

                Spacer(Modifier.height(16.dp))
                WireframeButton("PURGE ALL CORES", AlertRed, {
                    max.modelManager.releaseSlot(ModelManager.Slot.EVERYDAY)
                    max.modelManager.releaseSlot(ModelManager.Slot.CODER)
                    max.modelManager.saveSlotConfig(null, null)
                    configuredEverydayPath = null
                    configuredCoderName = null
                }, Modifier.fillMaxWidth())
            }
        }

        item { MatrixText("AVAILABLE CORES", VenomPurple, 16, FontWeight.Bold) }
        
        items(available) { entry ->
            val isLoaded = modelState.isLoaded && modelState.loadedModel?.path == entry.path
            Box(modifier = Modifier.fillMaxWidth().border(1.dp, if (isLoaded) CyanCore else GhostDim, CutCornerShape(4.dp)).padding(16.dp)) {
                Column {
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Column {
                            MatrixText(entry.name, if (isLoaded) CyanCore else GhostWhite, 14, FontWeight.Bold)
                            MatrixText("VOL: ${entry.displaySize}", GhostDim, 10)
                        }
                        Icon(Icons.Default.Delete, "Delete", tint = if (isLoaded) GhostDim else AlertRed, modifier = Modifier.clickable(enabled = !isLoaded) { max.modelManager.deleteModel(entry) })
                    }
                    Spacer(Modifier.height(12.dp))
                    
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        WireframeButton("SET PRI", GhostDim, { max.modelManager.saveSlotConfig(entry.path, max.modelManager.getSlotEntry(ModelManager.Slot.CODER)?.path); max.modelManager.loadSlot(ModelManager.Slot.EVERYDAY, entry); configuredEverydayPath = entry.path }, Modifier.weight(1f))
                        WireframeButton("SET SEC", GhostDim, { max.modelManager.saveSlotConfig(configuredEverydayPath, entry.path); configuredCoderName = entry.name }, Modifier.weight(1f))
                    }
                }
            }
        }
        
        item {
            Spacer(Modifier.height(16.dp))
            if (transferState.active || transferState.error != null) {
                Column(modifier = Modifier.border(1.dp, if (transferState.error != null) AlertRed else CyanCore, CutCornerShape(4.dp)).padding(12.dp).fillMaxWidth()) {
                    MatrixText(transferState.error ?: "${transferState.label}: ${transferState.fileName}", if (transferState.error != null) AlertRed else GhostWhite)
                    if (transferState.totalBytes > 0) LinearProgressIndicator(progress = { transferState.progress }, modifier = Modifier.fillMaxWidth().padding(top = 8.dp), color = CyanCore, trackColor = Carbon)
                    Spacer(Modifier.height(8.dp))
                    WireframeButton(if (transferState.active) "ABORT" else "DISMISS", AlertRed, { if (transferState.active) max.modelManager.cancelTransfer() else max.modelManager.clearTransferError() })
                }
                Spacer(Modifier.height(16.dp))
            }

            MatrixText("ACQUIRE NEW CORE", GhostDim, 12)
            WireframeButton("IMPORT LOCAL .GGUF", GhostDim, { filePicker.launch(arrayOf("*/*")) }, Modifier.fillMaxWidth().padding(vertical = 8.dp))
            AbstractInput("REMOTE_URI", urlInput) { urlInput = it }
            WireframeButton("EXECUTE DOWNLOAD", CyanCore, {
                if (urlInput.isNotBlank()) {
                    max.networkGuard.ownerRequestInternet()
                    max.modelManager.downloadModel(urlInput, urlInput.substringAfterLast('/'))
                    urlInput = ""
                }
            }, Modifier.fillMaxWidth())
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Abstract Shell (Terminal)
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun TerminalTab(max: MaxSystem) {
    val scope = rememberCoroutineScope()
    val history by max.terminal.history.collectAsState()
    var input by remember { mutableStateOf("") }
    val listState = rememberLazyListState()

    LaunchedEffect(history.size) { if (history.isNotEmpty()) listState.animateScrollToItem(history.size - 1) }

    Column(modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        MatrixText("RAW SHELL ACCESS", CyanCore, 16, FontWeight.Bold)
        Spacer(Modifier.height(8.dp))
        
        LazyColumn(state = listState, modifier = Modifier.weight(1f).fillMaxWidth()) {
            items(history) { entry ->
                Column(modifier = Modifier.padding(vertical = 4.dp)) {
                    MatrixText(">> ${entry.command}", GhostWhite)
                    if (entry.output.isNotBlank()) MatrixText(entry.output, if (entry.isError) AlertRed else GhostDim)
                }
            }
        }
        
        Row(modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            MatrixText(">> ", CyanCore)
            BasicTextField(
                value = input,
                onValueChange = { input = it },
                textStyle = TextStyle(color = GhostWhite, fontSize = 14.sp, fontFamily = MatrixFont),
                cursorBrush = SolidColor(CyanCore),
                modifier = Modifier.weight(1f),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(onSend = { if (input.isNotBlank()) { scope.launch { max.terminal.exec(input) }; input = "" } })
            )
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Abstract Diag (System)
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun SystemTab(max: MaxSystem) {
    val scope = rememberCoroutineScope()
    val sysCtrl by max.systemController.state.collectAsState()
    val resources by max.resourceMonitor.state.collectAsState()
    val network by max.networkStateMonitor.state.collectAsState()

    LazyColumn(modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        item { MatrixText("SYSTEM DIAGNOSTICS", CyanCore, 16, FontWeight.Bold) }
        
        item {
            Column(modifier = Modifier.border(1.dp, GhostDim, CutCornerShape(4.dp)).padding(16.dp).fillMaxWidth()) {
                MatrixText("HARDWARE TELEMETRY", GhostWhite, 12, FontWeight.Bold)
                Spacer(Modifier.height(8.dp))
                MatrixText("CPU: %.1f%%".format(resources.cpuPercent), CyanCore)
                MatrixText("MEM: ${resources.ramUsedMb} / ${resources.ramTotalMb} MB", CyanCore)
                MatrixText("PWR: ${resources.batteryPct}% [${resources.batteryTempC}C]", CyanCore)
                MatrixText("STORAGE: %.1fGB free".format(resources.storageFreeGb), CyanCore)
            }
        }
        
        item {
            Column(modifier = Modifier.border(1.dp, GhostDim, CutCornerShape(4.dp)).padding(16.dp).fillMaxWidth()) {
                MatrixText("NETWORK LINK", GhostWhite, 12, FontWeight.Bold)
                Spacer(Modifier.height(8.dp))
                MatrixText("STATUS: ${if (network.isConnected) "ESTABLISHED" else "SEVERED"}", if (network.isConnected) CyanCore else AlertRed)
                MatrixText("ROUTE: ${network.transportLabel}", GhostDim)
                network.ipAddress?.let { MatrixText("IP: $it", GhostDim) }
                Spacer(Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    WireframeButton("PING", GhostDim, { scope.launch { max.networkStateMonitor.pingMs() } }, Modifier.weight(1f))
                    WireframeButton("REFRESH", GhostDim, { max.networkStateMonitor.refresh() }, Modifier.weight(1f))
                }
            }
        }

        // Restored Audio and Display controls
        item {
            Column(modifier = Modifier.border(1.dp, GhostDim, CutCornerShape(4.dp)).padding(16.dp).fillMaxWidth()) {
                MatrixText("ENVIRONMENT CONTROL", GhostWhite, 12, FontWeight.Bold)
                Spacer(Modifier.height(16.dp))
                
                MatrixText("AUDIO MATRIX", CyanCore, 10)
                AbstractSlider("MEDIA", sysCtrl.mediaVolumePct) { max.systemController.setVolumePct(android.media.AudioManager.STREAM_MUSIC, it) }
                AbstractSlider("RING", sysCtrl.ringVolumePct) { max.systemController.setVolumePct(android.media.AudioManager.STREAM_RING, it) }
                AbstractSlider("ALARM", sysCtrl.alarmVolumePct) { max.systemController.setVolumePct(android.media.AudioManager.STREAM_ALARM, it) }
                
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(vertical = 8.dp)) {
                    WireframeButton("SILENT", GhostDim, { max.systemController.setRingerSilent() }, Modifier.weight(1f))
                    WireframeButton("VIBRATE", GhostDim, { max.systemController.setRingerVibrate() }, Modifier.weight(1f))
                    WireframeButton("NORMAL", GhostDim, { max.systemController.setRingerNormal() }, Modifier.weight(1f))
                }

                HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp), color = GhostDim.copy(alpha = 0.3f))
                
                MatrixText("LUMINANCE", CyanCore, 10)
                if (!max.systemController.canWriteSettings()) {
                    WireframeButton("REQ PERM: WRITE_SETTINGS", AlertRed, { max.systemController.openWriteSettingsPage() }, Modifier.fillMaxWidth())
                } else {
                    AbstractSlider("BRIGHTNESS", sysCtrl.brightnessPct) { max.systemController.setBrightnessPct(it) }
                }
            }
        }
    }
}

@Composable
private fun AbstractSlider(label: String, value: Int, onValueChange: (Int) -> Unit) {
    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            MatrixText(label, GhostDim, 10)
            MatrixText("$value%", CyanCore, 10)
        }
        Slider(
            value = value.toFloat(),
            onValueChange = { onValueChange(it.toInt()) },
            valueRange = 0f..100f,
            colors = SliderDefaults.colors(thumbColor = CyanCore, activeTrackColor = CyanCore, inactiveTrackColor = GhostDim)
        )
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Abstract Logs (Restored)
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun LogTab(entries: List<ActionLog.LogEntry>) {
    val fmt = remember { SimpleDateFormat("MM-dd HH:mm:ss", Locale.getDefault()) }
    Column(modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        MatrixText("ACTIVITY REGISTRY", CyanCore, 16, FontWeight.Bold)
        Spacer(Modifier.height(8.dp))
        
        if (entries.isEmpty()) {
            MatrixText("NO DATA RECORDED", GhostDim)
        } else {
            LazyColumn(modifier = Modifier.weight(1f)) {
                items(entries) { e ->
                    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp).border(1.dp, if (e.approved) GhostDim else AlertRed.copy(alpha = 0.5f), CutCornerShape(4.dp)).padding(12.dp)) {
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            MatrixText(e.action, GhostWhite, 12, FontWeight.Bold)
                            MatrixText(if (e.approved) "OK" else "DENIED", if (e.approved) CyanCore else AlertRed, 10)
                        }
                        Spacer(Modifier.height(4.dp))
                        MatrixText("TIME: ${fmt.format(Date(e.timestamp))} | REQ: ${e.requestedBy} | RISK: ${e.riskLevel}", GhostDim, 10)
                    }
                }
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Abstract Rules (Restored)
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun RulesTab() {
    Column(modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        MatrixText("IMMUTABLE DIRECTIVES", CyanCore, 16, FontWeight.Bold)
        MatrixText("v${Constitution.VERSION}", GhostDim, 10)
        Spacer(Modifier.height(16.dp))
        
        LazyColumn(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            items(Constitution.RULES) { rule ->
                Column(modifier = Modifier.fillMaxWidth().border(1.dp, GhostDim.copy(alpha = 0.5f), CutCornerShape(4.dp)).padding(12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(bottom = 8.dp)) {
                        Box(modifier = Modifier.background(CyanCore.copy(alpha = 0.2f), CutCornerShape(2.dp)).padding(horizontal = 6.dp, vertical = 2.dp)) {
                            MatrixText(rule.number.toString(), CyanCore, 12, FontWeight.Bold)
                        }
                        Spacer(Modifier.width(8.dp))
                        MatrixText(rule.title, GhostWhite, 12, FontWeight.Bold)
                        Spacer(Modifier.weight(1f))
                        MatrixText(rule.category.uppercase(), GhostDim, 8)
                    }
                    MatrixText(rule.statement, GhostDim, 10)
                }
            }
        }
    }
}
