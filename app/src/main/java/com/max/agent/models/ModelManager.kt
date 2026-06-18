package com.max.agent.models

import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.os.StatFs
import android.provider.OpenableColumns
import android.util.Log
import com.nexa.sdk.LlmWrapper
import com.nexa.sdk.bean.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject
import java.io.*
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
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
)

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
    private val downloadMutex = Mutex()
    private val downloadQueue = mutableListOf<Job>()
    private val loadJobs = mutableMapOf<Slot, Job>()

    private var everydayWrapper: LlmWrapper? = null
    private var coderWrapper: LlmWrapper? = null

    private val modelsDir: File = File(context.filesDir, "models").also { it.mkdirs() }
    private val slotConfigFile = File(context.filesDir, "config/model_slots.json").also { it.parentFile?.mkdirs() }
    private val metadataDir = File(context.filesDir, "model_metadata").also { it.mkdirs() }

    val available: StateFlow<List<ModelEntry>> = MutableStateFlow(emptyList())
    private val _available get() = available as MutableStateFlow

    val transfer: StateFlow<TransferState> = MutableStateFlow(TransferState())
    private val _transfer get() = transfer as MutableStateFlow

    val everydayState: StateFlow<ModelState> = MutableStateFlow(ModelState())
    private val _everydayState get() = everydayState as MutableStateFlow

    val coderState: StateFlow<ModelState> = MutableStateFlow(ModelState())
    private val _coderState get() = coderState as MutableStateFlow

    init {
        loadSavedSlots()
        scan()
    }

    fun onCleared() {
        scope.cancel()
        releaseSlot(Slot.EVERYDAY)
        releaseSlot(Slot.CODER)
        downloadQueue.forEach { it.cancel() }
    }

    // --- Public API ---
    fun isSlotLoaded(slot: Slot): Boolean = (if (slot == Slot.EVERYDAY) everydayWrapper else coderWrapper) != null

    fun getSlotEntry(slot: Slot): ModelEntry? = (if (slot == Slot.EVERYDAY) everydayState.value else coderState.value).loadedModel

    fun getModelByPath(path: String): ModelEntry? = available.value.find { it.path == path }

    fun deleteModel(entry: ModelEntry) {
        scope.launch {
            File(entry.path).delete()
            File(metadataDir, "${File(entry.path).name}.json").delete()
            scan()
        }
    }

    fun cancelTransfer() {
        downloadQueue.forEach { it.cancel() }
        _transfer.value = TransferState()
    }

    fun clearTransferError() { _transfer.value = _transfer.value.copy(error = null) }

    fun applyChatTemplateForSlot(slot: Slot, prompt: String): String = 
        (if (slot == Slot.EVERYDAY) everydayWrapper else coderWrapper)?.applyChatTemplate(prompt) ?: prompt

    fun generateStreamFlowForSlot(slot: Slot, prompt: String): Flow<String> = 
        (if (slot == Slot.EVERYDAY) everydayWrapper else coderWrapper)?.generateStreamFlow(prompt) ?: flowOf("Error: Model not loaded")

    fun stopSlotStream(slot: Slot) { (if (slot == Slot.EVERYDAY) everydayWrapper else coderWrapper)?.stopStream() }

    // --- Core Logic ---
    private fun appendErrorLog(t: Throwable) {
        try { context.filesDir.resolve("errors.log").appendText("${System.currentTimeMillis()}: ${t.message}\n") } catch (_: Exception) {}
    }

    private fun InputStream.readInt(): Int {
        val b = ByteArray(4)
        return if (read(b) == 4) {
            ((b[0].toInt() and 0xFF) shl 24) or ((b[1].toInt() and 0xFF) shl 16) or ((b[2].toInt() and 0xFF) shl 8) or (b[3].toInt() and 0xFF)
        } else -1
    }

    private fun validateGguf(file: File): Boolean {
        if (!file.exists() || file.length() < 4) return false
        return try { FileInputStream(file).use { it.readInt() == 0x47475546 } } catch (e: Exception) { false }
    }

    private fun saveMetadata(file: File, entry: ModelEntry) {
        try {
            val json = JSONObject().apply {
                put("id", entry.id); put("addedAt", entry.addedAt); put("sha256", entry.sha256)
                put("contextLength", entry.contextLength); put("paramSize", entry.paramSize)
            }
            File(metadataDir, "${file.name}.json").writeText(json.toString())
        } catch (_: Exception) {}
    }

    private fun restoreOrGenerateEntry(file: File): ModelEntry {
        val metaFile = File(metadataDir, "${file.name}.json")
        if (metaFile.exists()) {
            try {
                val json = JSONObject(metaFile.readText())
                return ModelEntry(json.getString("id"), file.nameWithoutExtension, file.absolutePath, file.length(), 
                    json.optLong("addedAt", file.lastModified()), json.optString("sha256").takeIf { it.isNotEmpty() }, 
                    json.optInt("contextLength", 32768), json.optString("paramSize").takeIf { it.isNotEmpty() })
            } catch (_: Exception) {}
        }
        return generateEntry(file)
    }

    private fun generateEntry(file: File): ModelEntry {
        val entry = ModelEntry(UUID.randomUUID().toString(), file.nameWithoutExtension, file.absolutePath, file.length(), file.lastModified())
        saveMetadata(file, entry)
        return entry
    }

    fun scan() {
        val files = modelsDir.listFiles { f -> f.isFile && f.extension.equals("gguf", ignoreCase = true) && f.canRead() } ?: emptyArray()
        _available.value = files.mapNotNull { f ->
            if (!validateGguf(f)) { f.delete(); null } else restoreOrGenerateEntry(f)
        }.sortedBy { it.name.lowercase() }
    }

    fun downloadModel(url: String, fileName: String, expectedSha256: String? = null, onComplete: (Boolean) -> Unit = {}) {
        scope.launch {
            downloadMutex.withLock {
                if (downloadQueue.any { it.isActive }) {
                    _transfer.value = TransferState(error = "One download at a time")
                    onComplete(false); return@launch
                }
                val job = scope.launch { performDownload(url, fileName, expectedSha256, onComplete) }
                downloadQueue.add(job)
                job.invokeOnCompletion { downloadQueue.remove(job) }
            }
        }
    }

    private suspend fun performDownload(url: String, fileName: String, expectedSha256: String?, onComplete: (Boolean) -> Unit) {
        var destFile: File? = null
        try {
            if (!url.startsWith("https://")) throw Exception("HTTPS required")
            val safeName = ensureGgufExtension(fileName)
            destFile = File(modelsDir, safeName)
            if (destFile.exists()) throw Exception("Model already exists")
            val size = getRemoteSize(url)
            if (size > 0 && !hasEnoughSpace(size)) throw Exception("Insufficient storage")
            _transfer.value = TransferState(active = true, label = "Downloading $safeName", fileName = safeName, totalBytes = size)
            val success = streamWithProgress(url, destFile) { done, total ->
                _transfer.value = _transfer.value.copy(bytesTransferred = done, progress = if (total > 0) done.toFloat() / total else 0f)
            }
            if (!success || !validateGguf(destFile)) throw Exception("Invalid download")
            if (expectedSha256 != null && !verifySha256(destFile, expectedSha256)) throw Exception("SHA256 check failed")
            scan()
            _transfer.value = TransferState()
            onComplete(true)
        } catch (e: Exception) {
            destFile?.delete()
            _transfer.value = TransferState(error = e.message)
            onComplete(false)
        }
    }

    private fun getRemoteSize(url: String): Long = try {
        (URL(url).openConnection() as HttpURLConnection).apply { requestMethod = "HEAD"; connectTimeout = 15000; readTimeout = 15000 }.use { it.contentLengthLong }
    } catch (e: Exception) { -1L }

    private suspend fun streamWithProgress(url: String, dest: File, onProgress: (Long, Long) -> Unit): Boolean = withContext(Dispatchers.IO) {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply { connectTimeout = 30000; readTimeout = 60000 }
        conn.connect()
        val total = conn.contentLengthLong
        conn.inputStream.use { input ->
            FileOutputStream(dest).use { output ->
                val buffer = ByteArray(2 * 1024 * 1024)
                var totalBytes = 0L
                var bytesRead: Int
                while (input.read(buffer).also { bytesRead = it } != -1) {
                    if (!isActive) throw CancellationException()
                    output.write(buffer, 0, bytesRead)
                    totalBytes += bytesRead
                    onProgress(totalBytes, total)
                }
            }
        }
        true
    }

    private fun verifySha256(file: File, expected: String): Boolean = try {
        val digest = MessageDigest.getInstance("SHA-256")
        FileInputStream(file).use { input ->
            val buffer = ByteArray(8192)
            var bytesRead: Int
            while (input.read(buffer).also { bytesRead = it } != -1) digest.update(buffer, 0, bytesRead)
        }
        digest.digest().joinToString("") { "%02x".format(it) } == expected.lowercase()
    } catch (e: Exception) { false }

    fun importFromUri(uri: Uri, onComplete: (Boolean) -> Unit = {}) {
        scope.launch {
            var dest: File? = null
            try {
                val cursor = context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)
                val name = cursor?.use { if (it.moveToFirst()) it.getString(0) else "imported.gguf" } ?: "imported.gguf"
                dest = File(modelsDir, ensureGgufExtension(name))
                if (dest.exists()) throw Exception("Already exists")
                context.contentResolver.openInputStream(uri)?.use { input ->
                    FileOutputStream(dest).use { output -> input.copyTo(output) }
                } ?: throw Exception("Read error")
                if (!validateGguf(dest)) throw Exception("Invalid")
                scan(); onComplete(true)
            } catch (e: Exception) { dest?.delete(); onComplete(false) }
        }
    }

    fun loadSlot(slot: Slot, entry: ModelEntry, onComplete: (Boolean) -> Unit = {}) {
        loadJobs[slot]?.cancel()
        if (slot == Slot.EVERYDAY) releaseSlot(Slot.CODER) else releaseSlot(Slot.EVERYDAY)
        val stateFlow = if (slot == Slot.EVERYDAY) _everydayState else _coderState
        stateFlow.value = ModelState(isLoading = true, loadedModel = entry)
        loadJobs[slot] = scope.launch {
            val success = tryLoadWithFallbacks(slot, entry, stateFlow)
            if (!success) stateFlow.value = ModelState(error = "Load failed")
            onComplete(success)
        }
    }

    private suspend fun tryLoadWithFallbacks(slot: Slot, entry: ModelEntry, stateFlow: MutableStateFlow<ModelState>): Boolean {
        val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
        val memoryInfo = android.app.ActivityManager.MemoryInfo()
        activityManager.getMemoryInfo(memoryInfo)

        val availableRamGb = (memoryInfo.totalMem / (1024 * 1024 * 1024.0)).toInt()
        val optimalLayers = if (availableRamGb >= 12) 999 else if (availableRamGb >= 8) 60 else 32
        val dynamicContext = if (availableRamGb >= 12) entry.contextLength else 16384

        val configs = listOf(
            Triple("qnn", "npu", optimalLayers),         // Targets Qualcomm Hexagon NPU
            Triple("vulkan", "gpu", optimalLayers),      // Targets Snapdragon Adreno GPU
            Triple("cpu", "cpu", 0)                      // Absolute fallback
        )

        for ((backend, device, layers) in configs) {
            try {
                val config = ModelConfig(dynamicContext, layers, dynamicContext / 2)
                val result = LlmWrapper.builder()
                    .llmCreateInput(LlmCreateInput(entry.name, entry.path, config, backend, device))
                    .build()

                if (result.isSuccess) {
                    if (slot == Slot.EVERYDAY) everydayWrapper = result.getOrThrow() else coderWrapper = result.getOrThrow()
                    stateFlow.value = ModelState(isLoaded = true, loadedModel = entry)
                    saveSlotConfig(slot, entry)
                    return true
                }
            } catch (e: Exception) {
                appendErrorLog(Exception("Snapdragon Backend $backend failed: ${e.message}"))
            }
        }
        return false
    }

    fun releaseSlot(slot: Slot) {
        loadJobs[slot]?.cancel()
        if (slot == Slot.EVERYDAY) { everydayWrapper?.close(); everydayWrapper = null; _everydayState.value = ModelState() }
        else { coderWrapper?.close(); coderWrapper = null; _coderState.value = ModelState() }
    }

    fun saveSlotConfig(slot: Slot, entry: ModelEntry) {
        try {
            val json = if (slotConfigFile.exists()) JSONObject(slotConfigFile.readText()) else JSONObject()
            json.put(if (slot == Slot.EVERYDAY) "everyday" else "coder", entry.path)
            slotConfigFile.writeText(json.toString())
        } catch (_: Exception) {}
    }

    private fun loadSavedSlots() {
        if (!slotConfigFile.exists()) return
        try {
            val json = JSONObject(slotConfigFile.readText())
            listOf(Slot.EVERYDAY to "everyday", Slot.CODER to "coder").forEach { (slot, key) ->
                val path = json.optString(key).takeIf { it.isNotEmpty() } ?: return@forEach
                val file = File(path)
                if (file.exists()) loadSlot(slot, restoreOrGenerateEntry(file))
            }
        } catch (_: Exception) {}
    }

    fun hasEnoughSpace(required: Long): Boolean = StatFs(modelsDir.absolutePath).availableBytes > required + 512 * 1024 * 1024L
    private fun ensureGgufExtension(name: String): String = if (name.endsWith(".gguf", true)) name else "$name.gguf"
}
