package com.max.agent.models

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.StatFs
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import android.system.Os
import android.system.OsConstants
import android.util.Log
import com.nexa.sdk.LlmWrapper
import com.nexa.sdk.bean.ChatMessage
import com.nexa.sdk.bean.GenerationConfig
import com.nexa.sdk.bean.LlmApplyChatTemplateOutput
import com.nexa.sdk.bean.LlmCreateInput
import com.nexa.sdk.bean.LlmStreamResult
import com.nexa.sdk.bean.ModelConfig
import com.max.agent.MaxApplication
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject
import java.io.*
import java.util.UUID

class ModelManager(private val context: Context) {

    enum class Slot { EVERYDAY, CODER }

    data class ModelEntry(
        val id: String,
        val name: String,
        val path: String,
        val sizeBytes: Long,
        val addedAt: Long,
        val sha256: String? = null,
        val contextLength: Int = 32768,
        val paramSize: String? = null
    ) {
        val displaySize: String
            get() = if (sizeBytes >= 1_073_741_824L) {
                String.format("%.2f GB", sizeBytes / 1_073_741_824.0)
            } else {
                String.format("%.2f MB", sizeBytes / 1_048_576.0)
            }
    }

    data class ModelState(
        val isLoading: Boolean = false,
        val isLoaded: Boolean = false,
        val loadedModel: ModelEntry? = null,
        val error: String? = null
    )

    data class TransferState(
        val active: Boolean = false,
        val label: String = "",
        val fileName: String = "",
        val bytesTransferred: Long = 0L,
        val totalBytes: Long = 0L,
        val progress: Float = 0f,
        val error: String? = null
    ) {
        val progressText: String
            get() = when {
                error != null -> "error: $error"
                !active -> label.ifEmpty { fileName.ifEmpty { "idle" } }
                totalBytes > 0L -> "%.0f%% — %s".format(progress * 100f, fileName)
                else -> label.ifEmpty { fileName }
            }
    }

    private val errorHandler = CoroutineExceptionHandler { _, t ->
        Log.e("ModelManager", "Unhandled exception", t)
        appendErrorLog(t)
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO + errorHandler)
    private val slotMutex = Mutex()

    private var everydayWrapper: LlmWrapper? = null
    private var coderWrapper: LlmWrapper? = null

    private val modelsDir: File = File(context.filesDir, "models").also { it.mkdirs() }
    private val slotConfigFile = File(context.filesDir, "config/model_slots.json").also { it.parentFile?.mkdirs() }
    private val dbFile = File(context.filesDir, "models_db.json")

    private val _available = MutableStateFlow<List<ModelEntry>>(emptyList())
    val available: StateFlow<List<ModelEntry>> = _available.asStateFlow()

    private val _everydayState = MutableStateFlow(ModelState())
    val everydayState: StateFlow<ModelState> = _everydayState.asStateFlow()
    val state: StateFlow<ModelState> get() = _everydayState

    private val _coderState = MutableStateFlow(ModelState())
    val coderState: StateFlow<ModelState> = _coderState.asStateFlow()

    private val _transfer = MutableStateFlow(TransferState())
    val transfer: StateFlow<TransferState> = _transfer.asStateFlow()

    init {
        // Do NOT auto-load saved slots on startup. Previous code did this,
        // which created a crash loop: if a model crashed during load, the path
        // was saved, and every subsequent launch tried to reload it and crashed
        // again before the UI even appeared.
        //
        // Saved slots are now loaded lazily — the user loads manually from
        // the Models tab. loadSlotConfig() exposes the saved paths for the
        // UI to show "last loaded" hints without actually loading.
        scan()
        preWarmAllModels()
    }

    fun onCleared() {
        scope.cancel()
        runBlocking { releaseAllSlotsSafely() }
    }

    // ====================== Core API ======================

    fun isSlotLoaded(slot: Slot): Boolean =
        (if (slot == Slot.EVERYDAY) everydayWrapper else coderWrapper) != null

    fun getSlotEntry(slot: Slot): ModelEntry? =
        (if (slot == Slot.EVERYDAY) everydayState.value else coderState.value).loadedModel

    fun getEverydayEntry(): ModelEntry? = everydayState.value.loadedModel
    fun getCoderEntry(): ModelEntry? = coderState.value.loadedModel

    fun getModelByPath(path: String): ModelEntry? = available.value.find { it.path == path }

    fun cancelTransfer() { _transfer.value = TransferState() }
    fun clearTransferError() { _transfer.value = _transfer.value.copy(error = null) }

    /**
     * Applies the model's chat template to a list of ChatMessages and returns the
     * formatted prompt string, or null if the slot has no loaded model or templating fails.
     *
     * Nexa SDK 0.0.24 signature:
     *   suspend fun applyChatTemplate(
     *       messages: Array<ChatMessage>,
     *       tools: String,
     *       enableThinking: Boolean,
     *       verbose: Boolean
     *   ): Result<LlmApplyChatTemplateOutput>
     * Called positionally to avoid any parameter-name drift.
     */
    suspend fun applyChatTemplateForSlot(slot: Slot, messages: List<ChatMessage>): String? {
        val wrapper = (if (slot == Slot.EVERYDAY) everydayWrapper else coderWrapper) ?: return null
        return runCatching {
            wrapper.applyChatTemplate(messages.toTypedArray(), null, false, false)
                .getOrThrow().formattedText
        }.onFailure { appendErrorLog(it) }.getOrNull()
    }

    suspend fun applyChatTemplate(messages: List<ChatMessage>): String? =
        applyChatTemplateForSlot(Slot.EVERYDAY, messages)

    /**
     * Streams generation for a slot. Returns Flow<LlmStreamResult> (Token/Error/Completed).
     * Nexa SDK 0.0.24: generateStreamFlow(prompt: String, config: GenerationConfig): Flow<LlmStreamResult>
     */
    fun generateStreamFlowForSlot(slot: Slot, prompt: String, maxTokens: Int = 4096): Flow<LlmStreamResult> {
        val wrapper = (if (slot == Slot.EVERYDAY) everydayWrapper else coderWrapper)
            ?: return flowOf(LlmStreamResult.Error(IllegalStateException("Error: Model not loaded")))
        val config = GenerationConfig().apply { this.maxTokens = maxTokens }
        return try {
            wrapper.generateStreamFlow(prompt, config)
        } catch (e: Exception) {
            appendErrorLog(e)
            flowOf(LlmStreamResult.Error(e))
        }
    }

    fun generateStreamFlow(prompt: String, maxTokens: Int = 4096): Flow<LlmStreamResult> =
        generateStreamFlowForSlot(Slot.EVERYDAY, prompt, maxTokens)

    /**
     * Non-suspend: launches an internal coroutine to invoke the suspend wrapper.stopStream().
     * Kept non-suspend so callers inside non-suspend lambdas (e.g. runCatching { }) still compile.
     */
    fun stopSlotStream(slot: Slot) {
        val wrapper = (if (slot == Slot.EVERYDAY) everydayWrapper else coderWrapper) ?: return
        scope.launch { runCatching { wrapper.stopStream() } }
    }

    fun stopStream() {
        scope.launch {
            runCatching { everydayWrapper?.stopStream() }
            runCatching { coderWrapper?.stopStream() }
        }
    }

    /**
     * Releases both slots asynchronously (fire-and-forget). Used by MaxSystem.shutdown().
     */
    fun releaseCurrent() {
        scope.launch { releaseAllSlotsSafely() }
    }

    suspend fun loadSlotAsync(slot: Slot, entry: ModelEntry): Boolean =
        suspendCancellableCoroutine { cont ->
            loadSlot(slot, entry) { success ->
                if (cont.isActive) cont.resumeWith(Result.success(success))
            }
        }

    /**
     * Read-only access to the persisted slot config. Returns (everydayPath, coderPath).
     * Does NOT auto-load; callers (MaxSystem) load explicitly.
     */
    fun loadSlotConfig(): Pair<String?, String?> {
        if (!slotConfigFile.exists()) return null to null
        return try {
            val json = JSONObject(slotConfigFile.readText())
            json.optString("everyday").takeIf { it.isNotEmpty() } to
                json.optString("coder").takeIf { it.isNotEmpty() }
        } catch (e: Exception) {
            Log.e("ModelManager", "Failed to read slot config", e)
            null to null
        }
    }

    fun saveSlotConfig(slot: Slot, entry: ModelEntry) = writeSlotConfig(slot, entry.path)

    /**
     * Persist a slot assignment by symbolic name. Accepts a nullable slotName and a nullable
     * entry payload (ModelEntry or raw path String); no-ops on null/unrecognized input.
     */
    fun saveSlotConfig(slotName: String?, entryData: Any?) {
        if (slotName == null) return
        val mappedSlot = if (slotName.equals("coder", ignoreCase = true)) Slot.CODER else Slot.EVERYDAY
        val mappedPath = when (entryData) {
            is ModelEntry -> entryData.path
            is String -> entryData
            else -> return
        }
        writeSlotConfig(mappedSlot, mappedPath)
    }

    // ====================== Database ======================

    private fun getAllFromDb(): List<ModelEntry> {
        if (!dbFile.exists()) return emptyList()
        return try {
            val array = JSONArray(dbFile.readText())
            List(array.length()) { i ->
                val obj = array.getJSONObject(i)
                ModelEntry(
                    id = obj.getString("id"),
                    name = obj.getString("name"),
                    path = obj.getString("path"),
                    sizeBytes = obj.getLong("sizeBytes"),
                    addedAt = obj.getLong("addedAt"),
                    sha256 = obj.optString("sha256").takeIf { it.isNotEmpty() },
                    contextLength = obj.optInt("contextLength", 32768),
                    paramSize = obj.optString("paramSize").takeIf { it.isNotEmpty() }
                )
            }
        } catch (e: Exception) {
            Log.e("ModelManager", "DB read failed", e)
            emptyList()
        }
    }

    private fun saveToDb(entry: ModelEntry) {
        val current = getAllFromDb().toMutableList()
        current.removeAll { it.path == entry.path }
        current.add(entry)
        writeDb(current)
    }

    private fun writeDb(list: List<ModelEntry>) {
        try {
            val array = JSONArray()
            list.forEach { entry ->
                val obj = JSONObject().apply {
                    put("id", entry.id)
                    put("name", entry.name)
                    put("path", entry.path)
                    put("sizeBytes", entry.sizeBytes)
                    put("addedAt", entry.addedAt)
                    put("sha256", entry.sha256)
                    put("contextLength", entry.contextLength)
                    put("paramSize", entry.paramSize)
                }
                array.put(obj)
            }
            dbFile.writeText(array.toString(2))
        } catch (e: Exception) {
            Log.e("ModelManager", "Failed to write DB", e)
        }
    }

    // ====================== Scanning & Import ======================

    fun scan() {
        val files = modelsDir.listFiles { f ->
            f.isFile && f.extension.equals("gguf", ignoreCase = true) && f.canRead()
        } ?: emptyArray()

        val currentDb = getAllFromDb()

        _available.value = files.mapNotNull { file ->
            if (!validateGguf(file)) {
                file.delete()
                null
            } else {
                var entry = currentDb.find { it.path == file.absolutePath }
                if (entry == null) {
                    entry = ModelEntry(
                        id = UUID.randomUUID().toString(),
                        name = file.nameWithoutExtension,
                        path = file.absolutePath,
                        sizeBytes = file.length(),
                        addedAt = file.lastModified()
                    )
                    saveToDb(entry)
                }
                entry
            }
        }.sortedBy { it.name.lowercase() }
    }

    fun deleteModel(entry: ModelEntry) {
        scope.launch {
            File(entry.path).delete()
            val current = getAllFromDb().toMutableList()
            current.removeAll { it.id == entry.id }
            writeDb(current)
            scan()
        }
    }

    fun importFromUri(uri: Uri, onComplete: (Boolean) -> Unit = {}) {
        scope.launch {
            var dest: File? = null
            try {
                val cursor = context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                val name = cursor?.use {
                    if (it.moveToFirst()) it.getString(0) else "imported.gguf"
                } ?: "imported.gguf"

                dest = File(modelsDir, ensureGgufExtension(name))
                if (dest.exists()) throw IOException("File already exists")

                context.contentResolver.openInputStream(uri)?.use { input ->
                    FileOutputStream(dest).use { output -> input.copyTo(output) }
                }

                if (!validateGguf(dest)) throw IOException("Invalid GGUF file")
                scan()
                onComplete(true)
            } catch (e: Exception) {
                dest?.delete()
                onComplete(false)
            }
        }
    }

    /**
     * Scans a Storage Access Framework directory tree (e.g. the Downloads folder)
     * for .gguf model files and imports every one found into the local models
     * directory. Recurses into subdirectories. No storage permissions required —
     * the user grants URI access via ACTION_OPEN_DOCUMENT_TREE and we persist it.
     *
     * @param treeUri The tree URI returned by OpenDocumentTree
     * @param onProgress Optional callback with the name of each file being imported
     * @param onComplete Called on the IO scope with the count of imported models
     */
    fun scanAndImportFromTree(
        treeUri: Uri,
        onProgress: (String) -> Unit = {},
        onComplete: (Int) -> Unit = {}
    ) {
        scope.launch {
            var imported = 0
            try {
                context.contentResolver.takePersistableUriPermission(
                    treeUri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
                val docId = DocumentsContract.getTreeDocumentId(treeUri)
                val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, docId)
                imported = walkTreeAndImport(childrenUri, onProgress)
                scan()
            } catch (e: Exception) {
                appendErrorLog(e)
            } finally {
                _transfer.value = TransferState()
            }
            onComplete(imported)
        }
    }

    /**
     * Recursively walks a SAF documents tree, importing every .gguf file found.
     */
    private suspend fun walkTreeAndImport(
        childrenUri: Uri,
        onProgress: (String) -> Unit
    ): Int {
        var imported = 0
        val projection = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_SIZE
        )
        try {
            context.contentResolver.query(childrenUri, projection, null, null, null)?.use { cursor ->
                while (cursor.moveToNext()) {
                    val docId = cursor.getString(0)
                    val name = cursor.getString(1) ?: continue
                    val mime = cursor.getString(2)
                    val size = cursor.getLong(3)

                    if (DocumentsContract.Document.MIME_TYPE_DIR == mime) {
                        val subChildren = DocumentsContract.buildChildDocumentsUriUsingTree(childrenUri, docId)
                        imported += walkTreeAndImport(subChildren, onProgress)
                    } else if (name.endsWith(".gguf", ignoreCase = true)) {
                        val fileUri = DocumentsContract.buildDocumentUriUsingTree(childrenUri, docId)
                        onProgress(name)
                        _transfer.value = TransferState(
                            active = true,
                            label = "Importing",
                            fileName = name,
                            totalBytes = size
                        )
                        val dest = File(modelsDir, ensureGgufExtension(name))
                        if (dest.exists()) {
                            _transfer.value = TransferState(active = false, label = "Already imported: $name")
                            imported++
                            continue
                        }
                        if (!hasEnoughSpace(size)) {
                            _transfer.value = TransferState(error = "Not enough storage for $name")
                            continue
                        }
                        val success = copyUriToFile(fileUri, dest, size)
                        if (success && validateGguf(dest)) {
                            imported++
                            _transfer.value = TransferState(active = false, label = "Imported: $name")
                        } else {
                            dest.delete()
                            _transfer.value = TransferState(error = "Invalid or failed: $name")
                        }
                    }
                }
            }
        } catch (e: Exception) {
            appendErrorLog(e)
        }
        return imported
    }

    /**
     * Copies a content URI to a local file with progress tracking.
     */
    private fun copyUriToFile(uri: Uri, dest: File, expectedSize: Long): Boolean {
        return try {
            context.contentResolver.openInputStream(uri)?.use { input ->
                FileOutputStream(dest).use { output ->
                    val buf = ByteArray(8192)
                    var read: Int
                    var total = 0L
                    while (input.read(buf).also { read = it } > 0) {
                        output.write(buf, 0, read)
                        total += read
                        _transfer.value = _transfer.value.copy(
                            bytesTransferred = total,
                            progress = if (expectedSize > 0) (total.toFloat() / expectedSize) else 0f
                        )
                    }
                }
            } ?: return false
            true
        } catch (e: Exception) {
            appendErrorLog(e)
            false
        }
    }

    // ====================== Model Loading (Nexa SDK 0.0.24) ======================

    fun loadSlot(slot: Slot, entry: ModelEntry, onComplete: (Boolean) -> Unit = {}) {
        scope.launch {
            slotMutex.withLock {
                releaseSlotInternal(if (slot == Slot.EVERYDAY) Slot.CODER else Slot.EVERYDAY)
                releaseSlotInternal(slot)

                val stateFlow = if (slot == Slot.EVERYDAY) _everydayState else _coderState
                stateFlow.value = ModelState(isLoading = true, loadedModel = entry)

                val success = tryHardwareAcceleratedLoad(slot, entry, stateFlow)
                if (!success) {
                    stateFlow.value = ModelState(error = "Failed to load model. See error log.")
                }
                onComplete(success)
            }
        }
    }

    /**
     * Architecture: NPU-first, catch-and-fallback. No benchmark loading.
     *
     * The S25's Snapdragon NPU is the correct acceleration path for Nexa SDK.
     * We do NOT pre-load the model to test the plugin — that double-loads into
     * NPU memory and crashes. Instead:
     *
     *   1. Try NPU directly with full layer offload.
     *   2. If it fails, try CPU/GPU.
     *   3. If that fails, report error.
     *
     * The successful profile is cached so subsequent loads skip the fallback path.
     */
    private suspend fun tryHardwareAcceleratedLoad(
        slot: Slot,
        entry: ModelEntry,
        stateFlow: MutableStateFlow<ModelState>
    ): Boolean {
        // ── Guard: Nexa SDK must be initialized before any model load ──
        if (!MaxApplication.sdkInitialized) {
            val errMsg = MaxApplication.sdkInitError ?: "Nexa SDK not initialized"
            stateFlow.value = ModelState(error = errMsg)
            appendErrorLog(RuntimeException("SDK init guard: $errMsg"))
            return false
        }

        val ramGb = getTotalRamGb()
        val contextSize = computeAdaptiveContext(ramGb)

        // ── Attempt 1: NPU (the correct path for Snapdragon S25) ──
        val npuConfig = ModelConfig(nCtx = contextSize, nGpuLayers = 999)
        val npuInput = LlmCreateInput(
            model_name = "",
            model_path = entry.path,
            tokenizer_path = null,
            config = npuConfig,
            plugin_id = "npu",
            device_id = null
        )

        var loaded = false
        LlmWrapper.builder()
            .llmCreateInput(npuInput)
            .build()
            .onSuccess { wrapper ->
                if (slot == Slot.EVERYDAY) everydayWrapper = wrapper else coderWrapper = wrapper
                stateFlow.value = ModelState(isLoaded = true, loadedModel = entry)
                writeSlotConfig(slot, entry.path)
                cacheHardwareProfile("npu", "")
                loaded = true
            }
            .onFailure { e ->
                appendErrorLog(e)
            }

        if (loaded) return true

        // ── Attempt 2: CPU/GPU fallback (only if NPU genuinely failed) ──
        appendErrorLog(RuntimeException("NPU load failed — attempting CPU/GPU fallback."))
        val cpuConfig = ModelConfig(nCtx = contextSize, nGpuLayers = 999)
        val cpuInput = LlmCreateInput(
            model_name = "",
            model_path = entry.path,
            tokenizer_path = null,
            config = cpuConfig,
            plugin_id = "cpu_gpu",
            device_id = "gpu"
        )

        LlmWrapper.builder()
            .llmCreateInput(cpuInput)
            .build()
            .onSuccess { wrapper ->
                if (slot == Slot.EVERYDAY) everydayWrapper = wrapper else coderWrapper = wrapper
                stateFlow.value = ModelState(isLoaded = true, loadedModel = entry)
                writeSlotConfig(slot, entry.path)
                cacheHardwareProfile("cpu_gpu", "gpu")
                loaded = true
            }
            .onFailure { e ->
                appendErrorLog(e)
            }

        return loaded
    }

    /**
     * Context window sizing based on available RAM — not model size.
     * The S25 has 12GB; after OS and app overhead ~7-8GB is free.
     * A 9B model (~5.5GB weights) + 8K KV cache (~0.5GB) fits comfortably.
     */
    private fun computeAdaptiveContext(ramGb: Int): Int = when {
        ramGb >= 12 -> 8192
        ramGb >= 8  -> 4096
        else        -> 2048
    }

    private fun cacheHardwareProfile(pluginId: String, deviceId: String) {
        context.getSharedPreferences("nexa_hardware_profile", Context.MODE_PRIVATE)
            .edit()
            .putString("pluginId", pluginId)
            .putString("deviceId", deviceId)
            .apply()
    }

    private fun clearCachedHardwareProfile() {
        context.getSharedPreferences("nexa_hardware_profile", Context.MODE_PRIVATE)
            .edit().clear().apply()
    }

    fun releaseSlot(slot: Slot) {
        scope.launch {
            slotMutex.withLock { releaseSlotInternal(slot) }
        }
    }

    private suspend fun releaseSlotInternal(slot: Slot) {
        if (slot == Slot.EVERYDAY) {
            runCatching { everydayWrapper?.stopStream() }.onFailure { appendErrorLog(it) }
            runCatching { everydayWrapper?.close() }.onFailure { appendErrorLog(it) }
            everydayWrapper = null
            _everydayState.value = ModelState()
        } else {
            runCatching { coderWrapper?.stopStream() }.onFailure { appendErrorLog(it) }
            runCatching { coderWrapper?.close() }.onFailure { appendErrorLog(it) }
            coderWrapper = null
            _coderState.value = ModelState()
        }
    }

    private suspend fun releaseAllSlotsSafely() {
        slotMutex.withLock {
            releaseSlotInternal(Slot.EVERYDAY)
            releaseSlotInternal(Slot.CODER)
        }
    }

    // ====================== Hardware Info ======================

    private fun getTotalRamGb(): Int {
        val memInfo = android.app.ActivityManager.MemoryInfo()
        (context.getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager)
            .getMemoryInfo(memInfo)
        return (memInfo.totalMem / 1_073_741_824L).toInt()
    }

    // ====================== Utils ======================

    private fun preWarmAllModels() {
        scope.launch {
            getAllFromDb().forEach { preWarmModel(it.path) }
        }
    }

    private fun preWarmModel(path: String) {
        try {
            val file = File(path)
            if (!file.exists() || file.length() == 0L) return
            RandomAccessFile(file, "r").use { raf ->
                val size = raf.length()
                val addr = Os.mmap(0L, size, OsConstants.PROT_READ, OsConstants.MAP_SHARED, raf.fd, 0L)
                Os.munmap(addr, size)
            }
        } catch (_: Exception) {}
    }

    private fun appendErrorLog(t: Throwable) {
        try {
            val logFile = File(context.filesDir, "errors.log")
            logFile.parentFile?.mkdirs()
            logFile.appendText("${System.currentTimeMillis()}: ${t.message}\n${t.stackTraceToString().take(800)}\n\n")
        } catch (_: Exception) {}
    }

    private fun InputStream.readInt(): Int {
        val bytes = ByteArray(4)
        return if (read(bytes) == 4) {
            ((bytes[0].toInt() and 0xFF) shl 24) or
            ((bytes[1].toInt() and 0xFF) shl 16) or
            ((bytes[2].toInt() and 0xFF) shl 8) or
            (bytes[3].toInt() and 0xFF)
        } else -1
    }

    private fun validateGguf(file: File): Boolean {
        if (!file.exists() || file.length() < 4) return false
        return try {
            FileInputStream(file).use { it.readInt() == 0x47475546 }
        } catch (e: Exception) {
            false
        }
    }

    private fun writeSlotConfig(slot: Slot, path: String) {
        try {
            val json = if (slotConfigFile.exists()) JSONObject(slotConfigFile.readText()) else JSONObject()
            json.put(if (slot == Slot.EVERYDAY) "everyday" else "coder", path)
            slotConfigFile.parentFile?.mkdirs()
            slotConfigFile.writeText(json.toString(2))
        } catch (e: Exception) {
            Log.e("ModelManager", "Failed to write slot config", e)
        }
    }

    private fun loadSavedSlots() {
        if (!slotConfigFile.exists()) return
        try {
            val json = JSONObject(slotConfigFile.readText())
            listOf(Slot.EVERYDAY to "everyday", Slot.CODER to "coder").forEach { (slot, key) ->
                val path = json.optString(key).takeIf { it.isNotEmpty() } ?: return@forEach
                getAllFromDb().find { it.path == path }?.let { entry ->
                    loadSlot(slot, entry)
                }
            }
        } catch (e: Exception) {
            Log.e("ModelManager", "Failed to load saved slots", e)
        }
    }

    fun hasEnoughSpace(required: Long): Boolean = try {
        StatFs(modelsDir.absolutePath).availableBytes > required + 512 * 1024 * 1024L
    } catch (_: Exception) {
        true
    }

    private fun ensureGgufExtension(name: String): String =
        if (name.endsWith(".gguf", ignoreCase = true)) name else "$name.gguf"
}
