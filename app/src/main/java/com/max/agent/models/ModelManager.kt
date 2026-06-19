package com.max.agent.models

import android.content.Context
import android.net.Uri
import android.os.StatFs
import android.provider.OpenableColumns
import android.system.Os
import android.system.OsConstants
import android.util.Log
import com.nexa.sdk.LlmWrapper
import com.nexa.sdk.bean.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject
import java.io.*
import java.util.UUID
import kotlin.random.Random

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
        get() = if (sizeBytes >= 1073741824) String.format("%.2f GB", sizeBytes / 1073741824.0) else String.format("%.2f MB", sizeBytes / 1048576.0)
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
        Log.e("Max", "Unhandled: ${t.message}", t)
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

    // --- Core API & Legacy Bridges ---

    fun isSlotLoaded(slot: Slot): Boolean = (if (slot == Slot.EVERYDAY) everydayWrapper else coderWrapper) != null

    fun getSlotEntry(slot: Slot): ModelEntry? = (if (slot == Slot.EVERYDAY) everydayState.value else coderState.value).loadedModel
    fun getEverydayEntry(): ModelEntry? = everydayState.value.loadedModel
    fun getCoderEntry(): ModelEntry? = coderState.value.loadedModel

    fun getModelByPath(path: String): ModelEntry? = available.value.find { it.path == path }

    fun cancelTransfer() { _transfer.value = TransferState() }
    fun clearTransferError() { _transfer.value = _transfer.value.copy(error = null) }

    fun applyChatTemplateForSlot(slot: Slot, prompt: String): String =
        (if (slot == Slot.EVERYDAY) everydayWrapper else coderWrapper)?.applyChatTemplate(prompt) ?: prompt

    fun applyChatTemplate(prompt: String): String = applyChatTemplateForSlot(Slot.EVERYDAY, prompt)

    fun generateStreamFlowForSlot(slot: Slot, prompt: String): Flow<String> =
        (if (slot == Slot.EVERYDAY) everydayWrapper else coderWrapper)?.generateStreamFlow(prompt) ?: flowOf("Error: Model not loaded")

    fun generateStreamFlow(prompt: String): Flow<String> = generateStreamFlowForSlot(Slot.EVERYDAY, prompt)

    fun stopSlotStream(slot: Slot) { (if (slot == Slot.EVERYDAY) everydayWrapper else coderWrapper)?.stopStream() }

    fun stopStream() {
        everydayWrapper?.stopStream()
        coderWrapper?.stopStream()
    }

    suspend fun loadSlotAsync(slot: Slot, entry: ModelEntry): Boolean = suspendCancellableCoroutine { cont ->
        loadSlot(slot, entry) { success -> if (cont.isActive) cont.resume(success) }
    }

    fun loadSlotConfig() { loadSavedSlots() }

    fun saveSlotConfig(slot: Slot, entry: ModelEntry) { writeSlotConfig(slot, entry.path) }

    fun saveSlotConfig(slotName: String, entryData: Any?) {
        val mappedSlot = if (slotName.equals("coder", true)) Slot.CODER else Slot.EVERYDAY
        val mappedPath = when (entryData) {
            is ModelEntry -> entryData.path
            is String -> entryData
            else -> return
        }
        writeSlotConfig(mappedSlot, mappedPath)
    }

    // --- Offline Native Database ---

    private fun getAllFromDb(): List<ModelEntry> {
        if (!dbFile.exists()) return emptyList()
        val list = mutableListOf<ModelEntry>()
        try {
            val array = JSONArray(dbFile.readText())
            for (i in 0 until array.length()) {
                val obj = array.getJSONObject(i)
                list.add(ModelEntry(
                    id = obj.getString("id"), name = obj.getString("name"), path = obj.getString("path"),
                    sizeBytes = obj.getLong("sizeBytes"), addedAt = obj.getLong("addedAt"),
                    sha256 = obj.optString("sha256").takeIf { it.isNotEmpty() },
                    contextLength = obj.optInt("contextLength", 32768),
                    paramSize = obj.optString("paramSize").takeIf { it.isNotEmpty() }
                ))
            }
        } catch (_: Exception) {}
        return list
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
            list.forEach {
                val obj = JSONObject().apply {
                    put("id", it.id)
                    put("name", it.name)
                    put("path", it.path)
                    put("sizeBytes", it.sizeBytes)
                    put("addedAt", it.addedAt)
                    put("sha256", it.sha256)
                    put("contextLength", it.contextLength)
                    put("paramSize", it.paramSize)
                }
                array.put(obj)
            }
            dbFile.writeText(array.toString(2))
        } catch (_: Exception) {}
    }

    // --- Hardware & IO Logic ---

    fun scan() {
        val files = modelsDir.listFiles { f -> f.isFile && f.extension.equals("gguf", ignoreCase = true) && f.canRead() } ?: emptyArray()
        val currentDb = getAllFromDb()

        _available.value = files.mapNotNull { f ->
            if (!validateGguf(f)) {
                f.delete()
                null
            } else {
                var entry = currentDb.find { it.path == f.absolutePath }
                if (entry == null) {
                    entry = ModelEntry(
                        id = UUID.randomUUID().toString(),
                        name = f.nameWithoutExtension,
                        path = f.absolutePath,
                        sizeBytes = f.length(),
                        addedAt = f.lastModified()
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
                val name = cursor?.use { if (it.moveToFirst()) it.getString(0) else "imported.gguf" } ?: "imported.gguf"
                dest = File(modelsDir, ensureGgufExtension(name))
                if (dest.exists()) throw Exception("File already exists")
                context.contentResolver.openInputStream(uri)?.use { input ->
                    FileOutputStream(dest).use { output ->
                        input.copyTo(output)
                    }
                }
                if (!validateGguf(dest)) throw Exception("Invalid GGUF file")
                scan()
                onComplete(true)
            } catch (e: Exception) {
                dest?.delete()
                onComplete(false)
            }
        }
    }

    fun loadSlot(slot: Slot, entry: ModelEntry, onComplete: (Boolean) -> Unit = {}) {
        scope.launch {
            slotMutex.withLock {
                // Release the opposite slot to manage memory
                releaseSlotInternal(if (slot == Slot.EVERYDAY) Slot.CODER else Slot.EVERYDAY)
                releaseSlotInternal(slot)

                val stateFlow = if (slot == Slot.EVERYDAY) _everydayState else _coderState
                stateFlow.value = ModelState(isLoading = true, loadedModel = entry)

                val success = tryHardwareAcceleratedLoad(slot, entry, stateFlow)
                if (!success) {
                    stateFlow.value = ModelState(error = "Failed to load model. Check logs.")
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
        val ramGb = getTotalRamGb()

        val dynamicContext = if (ramGb >= 12) entry.contextLength else 16384

        try {
            val config = ModelConfig(dynamicContext, layers, dynamicContext / 2)
            val createInput = LlmCreateInput(entry.name, entry.path, config, backend, device)
            val result = LlmWrapper.builder()
                .llmCreateInput(createInput)
                .build()

            if (result.isSuccess) {
                val wrapper = result.getOrThrow()
                if (slot == Slot.EVERYDAY) everydayWrapper = wrapper else coderWrapper = wrapper
                stateFlow.value = ModelState(isLoaded = true, loadedModel = entry)
                writeSlotConfig(slot, entry.path)
                return true
            } else {
                appendErrorLog(Exception("LLM load failed: ${result.exceptionOrNull()?.message}"))
            }
        } catch (e: Exception) {
            appendErrorLog(e)
        }
        return false
    }

    fun releaseSlot(slot: Slot) {
        scope.launch {
            slotMutex.withLock {
                releaseSlotInternal(slot)
            }
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
                val address = Os.mmap(0L, size, OsConstants.PROT_READ, OsConstants.MAP_SHARED, raf.fd, 0L)
                Os.munmap(address, size)
            }
        } catch (_: Exception) {}
    }

    private fun getTotalRamGb(): Int {
        val memInfo = android.app.ActivityManager.MemoryInfo()
        (context.getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager).getMemoryInfo(memInfo)
        return (memInfo.totalMem / 1073741824.0).toInt()
    }

    private suspend fun benchmarkAndGetHardwareProfile(entry: ModelEntry): Triple<String, String, Int> {
        val prefs = context.getSharedPreferences("nexa_hardware_profile", Context.MODE_PRIVATE)
        val cachedBackend = prefs.getString("backend", null)
        val cachedDevice = prefs.getString("device", null)

        if (cachedBackend != null && cachedDevice != null) {
            val optimalLayers = if (getTotalRamGb() >= 12) 999 else if (getTotalRamGb() >= 8) 60 else 32
            return Triple(cachedBackend, cachedDevice, optimalLayers)
        }

        val ramGb = getTotalRamGb()
        val optimalLayers = if (ramGb >= 12) 999 else if (ramGb >= 8) 60 else 32

        val configs = listOf(
            Triple("qnn", "npu", optimalLayers),
            Triple("vulkan", "gpu", optimalLayers),
            Triple("cpu", "cpu", 0)
        )

        for ((backend, device, layers) in configs) {
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

    private fun appendErrorLog(t: Throwable) {
        try {
            val logFile = context.filesDir.resolve("errors.log")
            logFile.parentFile?.mkdirs()
            logFile.appendText("${System.currentTimeMillis()}: ${t.message} | ${t.stackTraceToString().take(500)}\n")
        } catch (_: Exception) {}
    }

    private fun InputStream.readInt(): Int {
        val b = ByteArray(4)
        return if (read(b) == 4) {
            ((b[0].toInt() and 0xFF) shl 24) or
            ((b[1].toInt() and 0xFF) shl 16) or
            ((b[2].toInt() and 0xFF) shl 8) or
            (b[3].toInt() and 0xFF)
        } else -1
    }

    private fun validateGguf(file: File): Boolean {
        if (!file.exists() || file.length() < 4) return false
        return try {
            FileInputStream(file).use { it.readInt() == 0x47475546 } // 'GGUF' magic
        } catch (e: Exception) { false }
    }

    private fun writeSlotConfig(slot: Slot, path: String) {
        try {
            val json = if (slotConfigFile.exists()) JSONObject(slotConfigFile.readText()) else JSONObject()
            json.put(if (slot == Slot.EVERYDAY) "everyday" else "coder", path)
            slotConfigFile.parentFile?.mkdirs()
            slotConfigFile.writeText(json.toString(2))
        } catch (_: Exception) {}
    }

    private fun loadSavedSlots() {
        if (!slotConfigFile.exists()) return
        try {
            val json = JSONObject(slotConfigFile.readText())
            listOf(Slot.EVERYDAY to "everyday", Slot.CODER to "coder").forEach { (slot, key) ->
                val path = json.optString(key).takeIf { it.isNotEmpty() } ?: return@forEach
                val entry = getAllFromDb().find { it.path == path } ?: return@forEach
                loadSlot(slot, entry)
            }
        } catch (_: Exception) {}
    }

    fun hasEnoughSpace(required: Long): Boolean {
        return try {
            StatFs(modelsDir.absolutePath).availableBytes > required + 512 * 1024 * 1024L
        } catch (_: Exception) {
            true // fallback
        }
    }

    private fun ensureGgufExtension(name: String): String =
        if (name.endsWith(".gguf", ignoreCase = true)) name else "$name.gguf"
}
