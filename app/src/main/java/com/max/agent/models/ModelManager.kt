package com.max.agent.models

import android.content.Context
import android.net.Uri
import android.os.StatFs
import android.provider.OpenableColumns
import android.system.Os
import android.system.OsConstants
import android.util.Log
import com.nexa.sdk.LlmWrapper
import com.nexa.sdk.bean.LlmCreateInput
import com.nexa.sdk.bean.ModelConfig
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject
import java.io.*
import java.util.UUID

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
        get() = if (sizeBytes >= 1_073_741_824) {
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
)

class ModelManager(private val context: Context) {

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
        loadSavedSlots()
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

    fun applyChatTemplateForSlot(slot: Slot, prompt: String): String =
        (if (slot == Slot.EVERYDAY) everydayWrapper else coderWrapper)?.applyChatTemplate(prompt) ?: prompt

    fun applyChatTemplate(prompt: String): String = applyChatTemplateForSlot(Slot.EVERYDAY, prompt)

    fun generateStreamFlowForSlot(slot: Slot, prompt: String): Flow<String> =
        (if (slot == Slot.EVERYDAY) everydayWrapper else coderWrapper)?.generateStreamFlow(prompt)
            ?: flowOf("Error: Model not loaded")

    fun generateStreamFlow(prompt: String): Flow<String> = generateStreamFlowForSlot(Slot.EVERYDAY, prompt)

    fun stopSlotStream(slot: Slot) {
        (if (slot == Slot.EVERYDAY) everydayWrapper else coderWrapper)?.stopStream()
    }

    fun stopStream() {
        everydayWrapper?.stopStream()
        coderWrapper?.stopStream()
    }

    suspend fun loadSlotAsync(slot: Slot, entry: ModelEntry): Boolean =
        suspendCancellableCoroutine { cont ->
            loadSlot(slot, entry) { success ->
                if (cont.isActive) cont.resume(success)
            }
        }

    fun loadSlotConfig() = loadSavedSlots()

    fun saveSlotConfig(slot: Slot, entry: ModelEntry) = writeSlotConfig(slot, entry.path)

    fun saveSlotConfig(slotName: String, entryData: Any?) {
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
                    FileOutputStream(dest).use { output ->
                        input.copyTo(output)
                    }
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

    // ====================== Model Loading ======================

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

    private suspend fun tryHardwareAcceleratedLoad(
        slot: Slot,
        entry: ModelEntry,
        stateFlow: MutableStateFlow<ModelState>
    ): Boolean {
        val (backend, device, layers) = benchmarkAndGetHardwareProfile(entry)
        val dynamicContext = if (getTotalRamGb() >= 12) entry.contextLength else 16384

        try {
            val config = ModelConfig(dynamicContext, layers, dynamicContext / 2)
            val input = LlmCreateInput(entry.name, entry.path, config, backend, device)

            val result = LlmWrapper.builder()
                .llmCreateInput(input)
                .build()

            if (result.isSuccess) {
                val wrapper = result.getOrThrow()
                if (slot == Slot.EVERYDAY) everydayWrapper = wrapper else coderWrapper = wrapper

                stateFlow.value = ModelState(isLoaded = true, loadedModel = entry)
                writeSlotConfig(slot, entry.path)
                return true
            } else {
                appendErrorLog(Exception("LLM creation failed: ${result.exceptionOrNull()?.message}"))
            }
        } catch (e: Exception) {
            appendErrorLog(e)
        }
        return false
    }

    fun releaseSlot(slot: Slot) {
        scope.launch {
            slotMutex.withLock { releaseSlotInternal(slot) }
        }
    }

    private suspend fun releaseSlotInternal(slot: Slot) {
        if (slot == Slot.EVERYDAY) {
            everydayWrapper?.stopStream()
            everydayWrapper?.close()
            everydayWrapper = null
            _everydayState.value = ModelState()
        } else {
            coderWrapper?.stopStream()
            coderWrapper?.close()
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

    // ====================== Hardware & Utils ======================

    private fun getTotalRamGb(): Int {
        val memInfo = android.app.ActivityManager.MemoryInfo()
        (context.getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager)
            .getMemoryInfo(memInfo)
        return (memInfo.totalMem / 1_073_741_824.0).toInt()
    }

    private suspend fun benchmarkAndGetHardwareProfile(entry: ModelEntry): Triple<String, String, Int> {
        val prefs = context.getSharedPreferences("nexa_hardware_profile", Context.MODE_PRIVATE)
        val cachedBackend = prefs.getString("backend", null)
        val cachedDevice = prefs.getString("device", null)

        val ramGb = getTotalRamGb()
        val optimalLayers = when {
            ramGb >= 12 -> 999
            ramGb >= 8 -> 60
            else -> 32
        }

        if (cachedBackend != null && cachedDevice != null) {
            return Triple(cachedBackend, cachedDevice, optimalLayers)
        }

        val testConfigs = listOf(
            Triple("qnn", "npu", optimalLayers),
            Triple("vulkan", "gpu", optimalLayers),
            Triple("cpu", "cpu", 0)
        )

        for ((backend, device, layers) in testConfigs) {
            try {
                val testConfig = ModelConfig(128, layers, 64)
                val testInput = LlmCreateInput(entry.name, entry.path, testConfig, backend, device)

                val result = LlmWrapper.builder()
                    .llmCreateInput(testInput)
                    .build()

                if (result.isSuccess) {
                    result.getOrThrow().apply {
                        stopStream()
                        close()
                    }
                    prefs.edit()
                        .putString("backend", backend)
                        .putString("device", device)
                        .apply()
                    return Triple(backend, device, layers)
                }
            } catch (_: Exception) {}
        }
        return Triple("cpu", "cpu", 0)
    }

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
            FileInputStream(file).use { it.readInt() == 0x47475546 } // GGUF magic
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
