package com.max.agent.ui

import android.Manifest
import android.content.Intent
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
import androidx.compose.ui.text.style.TextOverflow
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

val VoidBlack      = Color(0xFF000000)
val Carbon         = Color(0xFF0A080C)
val ObsidianMist   = Color(0xFF1A141F)
val CyanCore       = Color(0xFF39FF14)
val AlertRed       = Color(0xFF8B0000)
val WarningYellow  = Color(0xFFFF6B00)
val VenomPurple    = Color(0xFF9D00FF)
val GhostWhite     = Color(0xFFEAEAE6)
val GhostDim       = Color(0xFF2A2A2A)

val MatrixFont = FontFamily.Monospace

@Composable
fun MatrixText(text: String, color: Color = GhostWhite, size: Int = 12, weight: FontWeight = FontWeight.Normal, modifier: Modifier = Modifier) {
    Text(
        text = text,
        color = color,
        fontSize = size.sp,
        fontFamily = MatrixFont,
        fontWeight = weight,
        modifier = modifier,
        letterSpacing = 1.sp,
        softWrap = true,
        overflow = TextOverflow.Visible
    )
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

@Composable
fun MaxMainContent(max: MaxSystem) {
    var currentTab by remember { mutableIntStateOf(0) }
    val systemState by max.systemState.collectAsState()
    val permissionState by max.permissionGate.state.collectAsState()
    val networkState by max.networkGuard.state.collectAsState()
    val logEntries by max.actionLog.entries.collectAsState()
    Box(modifier = Modifier.fillMaxSize().background(VoidBlack)) {
        Box(modifier = Modifier.fillMaxSize().padding(top = 80.dp, bottom = 80.dp)) {
            when (currentTab) {
                0 -> ChatTab(max)
                1 -> ModelsTab(max)
                2 -> TerminalTab(max)
                3 -> SystemTab(max)
                4 -> LogTab(max)
                5 -> RulesTab(max)
                6 -> ConfigTab(max)
            }
        }

        HUDHeader(systemState, networkState, max)
        PermissionOverlay(permissionState, max)
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
            
            val tabs = listOf("UPLINK", "CORES", "SHELL", "DIAG", "LOGS", "DIRECTIVES", "CONFIG")
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
// Functional content tabs — each wired to a live engine.
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun ChatTab(max: MaxSystem) {
    val messages = max.conversationHistory
    val listState = rememberLazyListState()
    var input by remember { mutableStateOf("") }
    val voiceMode by max.voiceEngine.mode.collectAsState()
    val listening = voiceMode == VoiceEngine.Mode.LISTENING

    LaunchedEffect(messages.size, max.streamingText) {
        if (messages.isNotEmpty()) listState.animateScrollToItem(messages.size - 1)
    }

    Column(Modifier.fillMaxSize().padding(horizontal = 12.dp)) {
        if (messages.isEmpty()) {
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                MatrixText("UPLINK SECURE — AWAITING DIRECTIVE", CyanCore, 13)
            }
        } else {
            LazyColumn(
                state = listState,
                modifier = Modifier.weight(1f).fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                items(messages) { m ->
                    val isUser = m.role == "user"
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start) {
                        Column(
                            Modifier
                                .fillMaxWidth(0.86f)
                                .border(1.dp, (if (isUser) CyanCore else GhostDim).copy(alpha = 0.6f), CutCornerShape(6.dp))
                                .background((if (isUser) CyanCore else GhostWhite).copy(alpha = 0.06f))
                                .padding(10.dp)
                        ) {
                            MatrixText(if (isUser) "OWNER" else "MAX", if (isUser) CyanCore else VenomPurple, 9, FontWeight.Bold)
                            Spacer(Modifier.height(4.dp))
                            MatrixText(m.content.ifBlank { if (max.isGenerating && !isUser) "…" else "" }, GhostWhite, 13)
                        }
                    }
                }
            }
        }

        if (max.isGenerating && max.stepStatus.isNotBlank()) {
            MatrixText(max.stepStatus, WarningYellow, 10, modifier = Modifier.padding(vertical = 4.dp))
        }

        Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.Bottom) {
            Box(Modifier.weight(1f)) {
                AbstractInput("MESSAGE", input, singleLine = false) { input = it }
            }
            Spacer(Modifier.width(8.dp))
            WireframeButton(if (listening) "…" else "MIC", if (listening) WarningYellow else GhostDim, {
                if (listening) max.stopVoiceInput()
                else max.startVoiceInput { text -> input = text }
            }, Modifier.height(44.dp), isActive = listening)
            Spacer(Modifier.width(8.dp))
            if (max.isGenerating) {
                WireframeButton("STOP", AlertRed, { max.stopGeneration() }, Modifier.height(44.dp))
            } else {
                WireframeButton("SEND", CyanCore, {
                    if (input.isNotBlank()) { max.sendUserMessage(input); input = "" }
                }, Modifier.height(44.dp))
            }
        }
        if (messages.isNotEmpty()) {
            WireframeButton("CLEAR SESSION", GhostDim, { max.clearConversation() }, Modifier.fillMaxWidth())
            Spacer(Modifier.height(4.dp))
        }
    }
}

@Composable
private fun ModelsTab(max: MaxSystem) {
    val available by max.modelManager.available.collectAsState()
    val everyday by max.modelManager.everydayState.collectAsState()
    val coder by max.modelManager.coderState.collectAsState()
    val transfer by max.modelManager.transfer.collectAsState()
    val context = LocalContext.current
    var importStatus by remember { mutableStateOf("") }

    val fileLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            context.contentResolver.takePersistableUriPermission(
                uri, Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
            importStatus = "Importing file…"
            max.modelManager.importFromUri(uri) { success ->
                importStatus = if (success) "Imported." else "Import failed — not a valid .gguf?"
            }
        }
    }

    val folderLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        if (uri != null) {
            importStatus = "Scanning folder for .gguf…"
            max.modelManager.scanAndImportFromTree(
                treeUri = uri,
                onProgress = { name -> importStatus = "Importing $name…" },
                onComplete = { count ->
                    importStatus = if (count > 0) "Imported $count model(s). Tap RESCAN." else "No .gguf found in that folder."
                }
            )
        } else {
            importStatus = "Folder selection cancelled."
        }
    }

    Column(Modifier.fillMaxSize().padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        MatrixText("COMPUTE CORES", CyanCore, 14, FontWeight.Black)

        Column(Modifier.fillMaxWidth().border(1.dp, GhostDim, CutCornerShape(6.dp)).padding(10.dp)) {
            val eState = if (everyday.isLoading) "loading…" else if (everyday.isLoaded) "loaded" else "idle"
            val cState = if (coder.isLoading) "loading…" else if (coder.isLoaded) "loaded" else "idle"
            MatrixText("PRIMARY: ${everyday.loadedModel?.name ?: "none"} [$eState]", GhostWhite, 11)
            everyday.error?.let { MatrixText("  err: $it", AlertRed, 9) }
            MatrixText("CODER:   ${coder.loadedModel?.name ?: "none"} [$cState]", GhostWhite, 11)
            coder.error?.let { MatrixText("  err: $it", AlertRed, 9) }
        }

        if (transfer.active) {
            Column(Modifier.fillMaxWidth().border(1.dp, WarningYellow, CutCornerShape(6.dp)).padding(10.dp)) {
                MatrixText("${transfer.label} ${transfer.fileName}", WarningYellow, 10)
                LinearProgressIndicator(
                    progress = { transfer.progress.coerceIn(0f, 1f) },
                    modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
                    color = CyanCore, trackColor = GhostDim
                )
                MatrixText(transfer.progressText, GhostDim, 9)
                WireframeButton("CANCEL", AlertRed, { max.modelManager.cancelTransfer() }, Modifier.fillMaxWidth())
            }
        }
        transfer.error?.let {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                MatrixText("xfer err: $it", AlertRed, 9)
                WireframeButton("DISMISS", GhostDim, { max.modelManager.clearTransferError() }, Modifier.height(30.dp))
            }
        }

        WireframeButton("RESCAN STORAGE", CyanCore, { max.modelManager.scan() }, Modifier.fillMaxWidth())
        WireframeButton("IMPORT .gguf FILE", CyanCore, { fileLauncher.launch(arrayOf("*/*")) }, Modifier.fillMaxWidth())
        WireframeButton("SCAN DOWNLOADS FOLDER", WarningYellow, { folderLauncher.launch(null) }, Modifier.fillMaxWidth())

        if (importStatus.isNotBlank()) {
            MatrixText(importStatus, WarningYellow, 10, modifier = Modifier.fillMaxWidth())
        }

        if (available.isEmpty()) {
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                MatrixText("NO LOCAL MODELS — IMPORT A .gguf OR SCAN YOUR DOWNLOADS FOLDER", GhostDim, 11)
            }
        } else {
            LazyColumn(Modifier.weight(1f).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(available) { entry ->
                    Column(Modifier.fillMaxWidth().border(1.dp, GhostDim.copy(alpha = 0.5f), CutCornerShape(6.dp)).padding(10.dp)) {
                        MatrixText(entry.name, GhostWhite, 11, FontWeight.Bold, modifier = Modifier.fillMaxWidth())
                        MatrixText(entry.displaySize, GhostDim, 9, modifier = Modifier.fillMaxWidth())
                        Spacer(Modifier.height(6.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            WireframeButton("→ PRIMARY", CyanCore, {
                                max.modelManager.loadSlot(ModelManager.Slot.EVERYDAY, entry) {
                                    max.modelManager.saveSlotConfig(entry.path, max.modelManager.getCoderEntry()?.path)
                                }
                            }, Modifier.weight(1f).height(34.dp))
                            WireframeButton("→ CODER", VenomPurple, {
                                max.modelManager.loadSlot(ModelManager.Slot.CODER, entry) {
                                    max.modelManager.saveSlotConfig(max.modelManager.getEverydayEntry()?.path, entry.path)
                                }
                            }, Modifier.weight(1f).height(34.dp))
                            WireframeButton("DEL", AlertRed, { max.modelManager.deleteModel(entry) }, Modifier.height(34.dp))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun TerminalTab(max: MaxSystem) {
    val history by max.terminal.history.collectAsState()
    val isRunning by max.terminal.isRunning.collectAsState()
    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState()
    var cmd by remember { mutableStateOf("") }

    LaunchedEffect(history.size) {
        if (history.isNotEmpty()) listState.animateScrollToItem(history.size - 1)
    }

    Column(Modifier.fillMaxSize().padding(12.dp)) {
        MatrixText("INTERACTIVE SHELL", CyanCore, 14, FontWeight.Black)
        Spacer(Modifier.height(8.dp))
        if (history.isEmpty()) {
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                MatrixText("SHELL READY — sh -c <cmd>", GhostDim, 11)
            }
        } else {
            LazyColumn(state = listState, modifier = Modifier.weight(1f).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                items(history) { h ->
                    Column(Modifier.fillMaxWidth()) {
                        MatrixText("$ ${h.command}", CyanCore, 11, FontWeight.Bold)
                        if (h.output.isNotBlank()) {
                            MatrixText(h.output.take(4000), if (h.isError) AlertRed else GhostWhite, 10)
                        }
                        MatrixText("exit=${h.exitCode}", if (h.isError) AlertRed else GhostDim, 8)
                    }
                }
            }
        }
        Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.Bottom) {
            Box(Modifier.weight(1f)) { AbstractInput("COMMAND", cmd) { cmd = it } }
            Spacer(Modifier.width(8.dp))
            WireframeButton(if (isRunning) "BUSY" else "RUN", CyanCore, {
                val c = cmd.trim()
                if (c.isNotBlank() && !isRunning) { scope.launch { max.terminal.exec(c) }; cmd = "" }
            }, Modifier.height(44.dp), isActive = isRunning)
        }
        if (history.isNotEmpty()) {
            Spacer(Modifier.height(4.dp))
            WireframeButton("CLEAR", GhostDim, { max.terminal.clearHistory() }, Modifier.fillMaxWidth())
        }
    }
}

@Composable
private fun SystemTab(max: MaxSystem) {
    val res by max.resourceMonitor.state.collectAsState()
    val net by max.networkStateMonitor.state.collectAsState()
    val sys by max.systemController.state.collectAsState()
    val everyday by max.modelManager.everydayState.collectAsState()
    val coder by max.modelManager.coderState.collectAsState()
    val enforcement by max.networkGuard.enforcement.collectAsState()

    Column(Modifier.fillMaxSize().padding(12.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        MatrixText("DIAGNOSTIC MATRIX", CyanCore, 14, FontWeight.Black)

        @Composable fun line(label: String, value: String, color: Color = GhostWhite) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                MatrixText(label, GhostDim, 10)
                MatrixText(value, color, 10, FontWeight.Bold)
            }
        }

        line("BATTERY", "${res.batteryPct}% ${if (res.isCharging) "(charging)" else ""} ${res.batteryTempC.toInt()}°C")
        line("CPU", "${res.cpuPercent.toInt()}%")
        line("RAM", "${res.ramUsedMb}/${res.ramTotalMb} MB (${res.ramUsedPct.toInt()}%)")
        line("STORAGE", "%.1f / %.1f GB free".format(res.storageFreeGb, res.storageTotalGb))
        line("THERMAL", res.thermalStatus, if (res.thermalStatus == "Normal") CyanCore else WarningYellow)
        HorizontalDivider(color = GhostDim.copy(alpha = 0.3f))
        line("NETWORK", if (net.isConnected) "${net.transportLabel} (${net.signalBars}/4 bars)" else "OFFLINE", if (net.isConnected) CyanCore else AlertRed)
        line("VALIDATED", if (net.hasValidatedInternet) "yes" else "no")
        net.ipAddress?.let { line("IP", it) }
        line("INTERNET POLICY", if (max.networkGuard.isInternetAllowed()) "ALLOWED" else "RECALLED", if (max.networkGuard.isInternetAllowed()) CyanCore else AlertRed)
        line("ENFORCEMENT", enforcement.name, when (enforcement) {
            com.max.agent.network.NetworkGuard.Enforcement.ENGAGED -> AlertRed
            com.max.agent.network.NetworkGuard.Enforcement.NEEDS_CONSENT -> WarningYellow
            else -> GhostWhite
        })
        HorizontalDivider(color = GhostDim.copy(alpha = 0.3f))
        line("MEDIA VOL", "${sys.mediaVolumePct}%")
        line("RING VOL", "${sys.ringVolumePct}%")
        line("RINGER", sys.ringerMode)
        line("BRIGHTNESS", "${sys.brightnessPct}%")
        line("WIFI", if (sys.isWifiEnabled) "on" else "off")
        line("BLUETOOTH", if (sys.isBluetoothEnabled) "on" else "off")
        line("POWER-SAVE", if (sys.isPowerSaveMode) "on" else "off")
        HorizontalDivider(color = GhostDim.copy(alpha = 0.3f))
        line("MODEL PRIMARY", everyday.loadedModel?.name ?: "none")
        line("MODEL CODER", coder.loadedModel?.name ?: "none")
        line("GITHUB", if (max.githubEngine.isConfigured()) "configured" else "not configured", if (max.githubEngine.isConfigured()) CyanCore else GhostDim)

        Spacer(Modifier.height(8.dp))
        WireframeButton("REFRESH", CyanCore, {
            max.systemController.refreshState()
            max.networkStateMonitor.refresh()
        }, Modifier.fillMaxWidth())
    }
}

@Composable
private fun LogTab(max: MaxSystem) {
    val entries by max.actionLog.entries.collectAsState()
    val tampered by max.actionLog.tampered.collectAsState()
    val fmt = remember { SimpleDateFormat("HH:mm:ss", Locale.US) }
    val context = androidx.compose.ui.platform.LocalContext.current
    var crashLog by remember { mutableStateOf<String?>(null) }
    var errorLog by remember { mutableStateOf<String?>(null) }
    var logRefresh by remember { mutableIntStateOf(0) }

    // Read crash.log AND errors.log if they exist
    LaunchedEffect(logRefresh) {
        val crashFile = java.io.File(context.filesDir, "crash.log")
        val errorFile = java.io.File(context.filesDir, "errors.log")
        crashLog = if (crashFile.exists()) crashFile.readText() else null
        errorLog = if (errorFile.exists()) errorFile.readText() else null
    }

    Column(Modifier.fillMaxSize().padding(12.dp)) {
        MatrixText("AUDIT STREAM", CyanCore, 14, FontWeight.Black)

        // ── Crash log banner (shown if app crashed on last run) ──
        if (crashLog != null) {
            Spacer(Modifier.height(8.dp))
            Column(Modifier.fillMaxWidth().border(2.dp, AlertRed, CutCornerShape(6.dp)).background(AlertRed.copy(alpha = 0.15f)).padding(10.dp)) {
                MatrixText("⚠ LAST CRASH CAPTURED — READ TO ZO", AlertRed, 12, FontWeight.Black)
                Spacer(Modifier.height(6.dp))
                MatrixText(crashLog!!.take(800), AlertRed, 9)
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    WireframeButton("REFRESH", GhostDim, { logRefresh++ }, Modifier.weight(1f).height(34.dp))
                    WireframeButton("CLEAR", AlertRed, {
                        java.io.File(context.filesDir, "crash.log").delete()
                        crashLog = null
                    }, Modifier.weight(1f).height(34.dp))
                }
            }
        }

        // ── Error log (model load failures, SDK errors, etc.) ──
        if (errorLog != null) {
            Spacer(Modifier.height(8.dp))
            Column(Modifier.fillMaxWidth().border(1.dp, WarningYellow, CutCornerShape(6.dp)).background(WarningYellow.copy(alpha = 0.08f)).padding(10.dp)) {
                MatrixText("ERROR LOG", WarningYellow, 12, FontWeight.Bold)
                Spacer(Modifier.height(6.dp))
                MatrixText(errorLog!!.take(1200), GhostWhite, 9, modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    WireframeButton("REFRESH", GhostDim, { logRefresh++ }, Modifier.weight(1f).height(34.dp))
                    WireframeButton("CLEAR", WarningYellow, {
                        java.io.File(context.filesDir, "errors.log").delete()
                        errorLog = null
                    }, Modifier.weight(1f).height(34.dp))
                }
            }
        }

        if (tampered) {
            Box(Modifier.fillMaxWidth().border(2.dp, AlertRed, CutCornerShape(6.dp)).background(AlertRed.copy(alpha = 0.15f)).padding(10.dp)) {
                MatrixText("⚠ LOG TAMPER DETECTED — SYSTEM LOCKED (RULE 6)", AlertRed, 12, FontWeight.Black)
            }
            Spacer(Modifier.height(8.dp))
        }
        if (entries.isEmpty()) {
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                MatrixText("NO ACTIONS RECORDED", GhostDim, 11)
            }
        } else {
            LazyColumn(Modifier.weight(1f).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                items(entries.reversed()) { e ->
                    val riskColor = when (e.riskLevel) {
                        Constitution.RiskLevel.High.label -> AlertRed
                        Constitution.RiskLevel.Medium.label -> WarningYellow
                        else -> CyanCore
                    }
                    Column(Modifier.fillMaxWidth().border(1.dp, GhostDim.copy(alpha = 0.4f), CutCornerShape(4.dp)).padding(8.dp)) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            MatrixText(fmt.format(Date(e.timestamp)), GhostDim, 9)
                            MatrixText(e.riskLevel, riskColor, 9, FontWeight.Bold)
                        }
                        MatrixText(e.action, GhostWhite, 11, FontWeight.Bold)
                        MatrixText("by ${e.requestedBy}", GhostDim, 9)
                        MatrixText(if (e.approved) "✓ ${e.outcome.take(160)}" else "✗ ${e.outcome.take(160)}", if (e.approved) CyanCore else AlertRed, 9)
                    }
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        WireframeButton("PURGE (AUDITED)", AlertRed, {
            max.actionLog.purge("Owner-initiated purge", max.ownerAuth.ownerName())
        }, Modifier.fillMaxWidth())
    }
}

@Composable
private fun RulesTab(max: MaxSystem) {
    var refresh by remember { mutableIntStateOf(0) }
    val rules = remember(refresh) { MaxIdentity.getRules() }
    val hasPrompt = remember(refresh) { MaxIdentity.hasCustomPrompt() }
    var newRule by remember { mutableStateOf("") }
    var promptDraft by remember { mutableStateOf("") }
    var inject by remember { mutableStateOf(MaxIdentity.injectLiveContext) }

    Column(Modifier.fillMaxSize().padding(12.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        MatrixText("STANDING DIRECTIVES", CyanCore, 14, FontWeight.Black)

        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            MatrixText("INJECT LIVE CONTEXT", GhostWhite, 11)
            WireframeButton(if (inject) "ON" else "OFF", if (inject) CyanCore else GhostDim, {
                inject = !inject; MaxIdentity.setInjectLiveContext(inject)
            }, Modifier.height(34.dp), isActive = inject)
        }

        MatrixText("CUSTOM PROMPT: ${if (hasPrompt) "ACTIVE (overrides base)" else "default"}", if (hasPrompt) WarningYellow else GhostDim, 10)

        if (rules.isEmpty()) {
            MatrixText("NO RUNTIME RULES", GhostDim, 11)
        } else {
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                rules.forEachIndexed { i, r ->
                    Row(Modifier.fillMaxWidth().border(1.dp, GhostDim.copy(alpha = 0.4f), CutCornerShape(4.dp)).padding(8.dp)) {
                        MatrixText("${i + 1}. $r", GhostWhite, 11)
                    }
                }
            }
        }

        AbstractInput("NEW DIRECTIVE", newRule, singleLine = false) { newRule = it }
        WireframeButton("ADD DIRECTIVE", CyanCore, {
            if (newRule.isNotBlank()) { MaxIdentity.addRule(newRule.trim()); newRule = ""; refresh++ }
        }, Modifier.fillMaxWidth())
        WireframeButton("CLEAR ALL DIRECTIVES", AlertRed, {
            MaxIdentity.clearRules(); refresh++
        }, Modifier.fillMaxWidth())

        HorizontalDivider(color = GhostDim.copy(alpha = 0.3f))
        AbstractInput("OVERRIDE SYSTEM PROMPT", promptDraft, singleLine = false) { promptDraft = it }
        WireframeButton("SET PROMPT", VenomPurple, {
            if (promptDraft.isNotBlank()) { MaxIdentity.updatePrompt(promptDraft.trim()); refresh++ }
        }, Modifier.fillMaxWidth())
    }
}

@Composable
private fun ConfigTab(max: MaxSystem) {
    val scope = rememberCoroutineScope()
    var refresh by remember { mutableIntStateOf(0) }
    val configured = remember(refresh) { max.githubEngine.isConfigured() }
    val existing = remember(refresh) { max.githubEngine.config() }

    var token by remember { mutableStateOf("") }
    var owner by remember(refresh) { mutableStateOf(existing?.owner ?: "") }
    var repo by remember(refresh) { mutableStateOf(existing?.repo ?: "") }
    var branch by remember(refresh) { mutableStateOf(existing?.branch ?: "main") }
    var status by remember { mutableStateOf("") }

    val voiceCfg by max.voiceEngine.config.collectAsState()
    val accessibilityActive by MaxAccessibilityService.isActive.collectAsState()

    Column(Modifier.fillMaxSize().padding(12.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        MatrixText("SELF-MODIFICATION LINK", CyanCore, 14, FontWeight.Black)
        MatrixText(
            if (configured) "GITHUB: CONFIGURED (${existing?.owner}/${existing?.repo}@${existing?.branch})" else "GITHUB: NOT CONFIGURED",
            if (configured) CyanCore else WarningYellow, 10
        )

        AbstractInput("PAT TOKEN", token, isPassword = true) { token = it }
        AbstractInput("OWNER", owner) { owner = it }
        AbstractInput("REPO", repo) { repo = it }
        AbstractInput("BRANCH", branch) { branch = it }
        if (status.isNotBlank()) MatrixText(status, if (status.startsWith("✓")) CyanCore else AlertRed, 10)

        WireframeButton("SAVE LINK", CyanCore, {
            when {
                token.isBlank() && !configured -> status = "✗ Token required"
                owner.isBlank() || repo.isBlank() -> status = "✗ Owner and repo required"
                else -> scope.launch {
                    val effectiveToken = token.ifBlank { existing?.token ?: "" }
                    if (effectiveToken.isBlank()) { status = "✗ Token required"; return@launch }
                    max.configureGithub(effectiveToken, owner.trim(), repo.trim(), branch.trim().ifBlank { "main" })
                    token = ""; status = "✓ Link saved"; refresh++
                }
            }
        }, Modifier.fillMaxWidth())

        WireframeButton("CHECK BUILD STATUS", VenomPurple, {
            scope.launch {
                val build = runCatching { max.githubEngine.latestBuild() }.getOrNull()
                status = if (build == null) "✗ No build status (configure link first)"
                else "✓ run=${build.runId} ${build.status}/${build.conclusion ?: "-"}"
            }
        }, Modifier.fillMaxWidth())

        HorizontalDivider(color = GhostDim.copy(alpha = 0.3f))
        MatrixText("VOICE", CyanCore, 14, FontWeight.Black)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            MatrixText("AUTO-SPEAK REPLIES", GhostWhite, 11)
            WireframeButton(if (voiceCfg.autoSpeak) "ON" else "OFF", if (voiceCfg.autoSpeak) CyanCore else GhostDim, {
                max.voiceEngine.updateConfig { copy(autoSpeak = !autoSpeak) }
            }, Modifier.height(34.dp), isActive = voiceCfg.autoSpeak)
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            MatrixText("PREFER OFFLINE STT", GhostWhite, 11)
            WireframeButton(if (voiceCfg.preferOffline) "ON" else "OFF", if (voiceCfg.preferOffline) CyanCore else GhostDim, {
                max.voiceEngine.updateConfig { copy(preferOffline = !preferOffline) }
            }, Modifier.height(34.dp), isActive = voiceCfg.preferOffline)
        }

        HorizontalDivider(color = GhostDim.copy(alpha = 0.3f))
        MatrixText("ACCESSIBILITY BRIDGE", CyanCore, 14, FontWeight.Black)
        MatrixText(
            if (accessibilityActive) "SCREEN-READ SERVICE: ACTIVE" else "SCREEN-READ SERVICE: INACTIVE",
            if (accessibilityActive) CyanCore else WarningYellow, 10
        )
        WireframeButton("OPEN ACCESSIBILITY SETTINGS", GhostDim, {
            max.openAccessibilitySettings()
        }, Modifier.fillMaxWidth())
    }
}
