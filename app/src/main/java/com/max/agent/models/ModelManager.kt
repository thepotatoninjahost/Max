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

enum class Slot { EVERYDAY, CODER }

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

    private fun appendErrorLog(t: Throwable) {
        try {
            context.filesDir.resolve("errors.log").appendText("${System.currentTimeMillis()}: ${t.message}\n")
        } catch (_: Exception) {}
    }

    private fun validateGguf(file: File): Boolean {
        if (!file.exists() || file.length() < 4) return false
        return try {
            FileInputStream(file).use { it.readInt() == 0x47475546 }
        } catch (e: Exception) { false }
    }

    // === Metadata & Scan ===
    private fun saveMetadata(file: File, entry: ModelEntry) {
        try {
            val json = JSONObject().apply {
                put("id", entry.id)
                put("addedAt", entry.addedAt)
                put("sha256", entry.sha256)
                put("contextLength", entry.contextLength)
                put("paramSize", entry.paramSize)
            }
            File(metadataDir, "${file.name}.json").writeText(json.toString())
        } catch (_: Exception) {}
    }

    private fun restoreOrGenerateEntry(file: File): ModelEntry {
        val metaFile = File(metadataDir, "${file.name}.json")
        return if (metaFile.exists()) {
            try {
                val json = JSONObject(metaFile.readText())
                ModelEntry(
                    id = json.getString("id"),
                    name = file.nameWithoutExtension,
                    path = file.absolutePath,
                    sizeBytes = file.length(),
                    addedAt = json.optLong("addedAt", file.lastModified()),
                    sha256 = json.optString("sha256").takeIf { it.isNotEmpty() },
                    contextLength = json.optInt("contextLength", 32768),
                    paramSize = json.optString("paramSize").takeIf { it.isNotEmpty() }
                )
            } catch (_: Exception) {
                generateEntry(file)
            }
        } else generateEntry(file)
    }

    private fun generateEntry(file: File): ModelEntry {
        val id = UUID.randomUUID().toString()
        val entry = ModelEntry(id, file.nameWithoutExtension, file.absolutePath, file.length(), file.lastModified())
        saveMetadata(file, entry)
        return entry
    }

    fun scan() {
        val files = modelsDir.listFiles { f ->
            f.isFile && f.extension.equals("gguf", ignoreCase = true) && f.canRead()
        } ?: emptyArray()

        _available.value = files.mapNotNull { f ->
            if (!validateGguf(f)) {
                f.delete()
                return@mapNotNull null
            }
            restoreOrGenerateEntry(f)
        }.sortedBy { it.name.lowercase() }
    }

    // === Download with Queue ===
    fun downloadModel(url: String, fileName: String, expectedSha256: String? = null, onComplete: (Boolean) -> Unit = {}) {
        scope.launch {
            downloadMutex.withLock {
                if (downloadQueue.any { it.isActive }) {
                    _transfer.value = TransferState(error = "One download at a time")
                    onComplete(false)
                    return@launch
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
                _transfer.value = _transfer.value.copy(
                    bytesTransferred = done,
                    progress = if (total > 0) done.toFloat() / total else 0f
                )
            }

            if (!success || !validateGguf(destFile)) throw Exception("Invalid download")
            if (expectedSha256 != null && !verifySha256(destFile, expectedSha256)) {
                throw Exception("SHA256 check failed")
            }

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
        (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "HEAD"
            connectTimeout = 15000
            readTimeout = 15000
        }.use { it.contentLengthLong }
    } catch (e: Exception) { -1L }

    private suspend fun streamWithProgress(url: String, dest: File, onProgress: (Long, Long) -> Unit): Boolean = withContext(Dispatchers.IO) {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 30000; readTimeout = 60000
        }
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
            while (input.read(buffer).also { bytesRead = it } != -1) {
                digest.update(buffer, 0, bytesRead)
            }
        }
        digest.digest().joinToString("") { "%02x".format(it) } == expected.lowercase()
    } catch (e: Exception) { false }

    // === Import from URI ===
    fun importFromUri(uri: Uri, onComplete: (Boolean) -> Unit = {}) {
        scope.launch {
            var dest: File? = null
            try {
                val cursor: Cursor? = context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)
                val name = (cursor?.use {
                    it.moveToFirst()
                    it.getString(0)
                } ?: "imported.gguf").let { ensureGgufExtension(it) }

                dest = File(modelsDir, name)
                if (dest.exists()) throw Exception("Already exists")

                val size = cursor?.use { it.getLong(1) } ?: -1L
                if (size > 0 && !hasEnoughSpace(size)) throw Exception("No space")

                context.contentResolver.openInputStream(uri)?.use { input ->
                    FileOutputStream(dest).use { output ->
                        input.copyTo(output, 2 * 1024 * 1024)
                    }
                } ?: throw Exception("Cannot read URI")

                if (!validateGguf(dest)) throw Exception("Invalid GGUF")
                scan()
                onComplete(true)
            } catch (e: Exception) {
                dest?.delete()
                onComplete(false)
            }
        }
    }

    // === Loading ===
    fun loadSlot(slot: Slot, entry: ModelEntry, onComplete: (Boolean) -> Unit = {}) {
        loadJobs[slot]?.cancel()
        if (slot == Slot.EVERYDAY) releaseSlot(Slot.CODER) else releaseSlot(Slot.EVERYDAY)

        val stateFlow = if (slot == Slot.EVERYDAY) _everydayState else _coderState
        stateFlow.value = ModelState(isLoading = true, loadedModel = entry)

        loadJobs[slot] = scope.launch {
            val success = withTimeoutOrNull(180_000L) {
                tryLoadWithFallbacks(slot, entry, stateFlow)
            } ?: false

            if (!success) stateFlow.value = ModelState(error = "Load failed or timed out")
            onComplete(success)
        }
    }

    private suspend fun tryLoadWithFallbacks(slot: Slot, entry: ModelEntry, stateFlow: MutableStateFlow<ModelState>): Boolean {
        val file = File(entry.path)
        if (!file.exists() || !validateGguf(file)) {
            stateFlow.value = ModelState(error = "Invalid GGUF")
            return false
        }

        val configs = listOf(
            Triple("npu", "dev0", 999),
            Triple("npu", null, 999),
            Triple("cpu_gpu", "dev0", 999),
            Triple("cpu_gpu", "gpu", 80),
            Triple("cpu_gpu", null, 40)
        )

        for ((pluginId, deviceId, layers) in configs) {
            try {
                val input = LlmCreateInput(
                    model_name = entry.name,
                    model_path = entry.path,
                    config = ModelConfig(nCtx = 32768, nGpuLayers = layers, max_tokens = 16384),
                    plugin_id = pluginId,
                    device_id = deviceId
                )
                val result = LlmWrapper.builder().llmCreateInput(input).build()
                if (result.isSuccess) {
                    assignWrapper(slot, result.getOrThrow())
                    stateFlow.value = ModelState(isLoaded = true, loadedModel = entry)
                    saveSlotConfig(slot, entry)
                    Log.i("ModelManager", "Loaded ${entry.name} on $pluginId/$deviceId")
                    return true
                }
            } catch (e: Exception) {
                Log.w("ModelManager", "Failed $pluginId/$deviceId: ${e.message}")
            }
        }
        stateFlow.value = ModelState(error = "All acceleration paths failed")
        return false
    }

    private fun assignWrapper(slot: Slot, wrapper: LlmWrapper) {
        if (slot == Slot.EVERYDAY) everydayWrapper = wrapper else coderWrapper = wrapper
    }

    fun releaseSlot(slot: Slot) {
        loadJobs[slot]?.cancel()
        val wrapper = if (slot == Slot.EVERYDAY) everydayWrapper else coderWrapper
        try { wrapper?.close() } catch (_: Exception) {}
        if (slot == Slot.EVERYDAY) everydayWrapper = null else coderWrapper = null
        val stateFlow = if (slot == Slot.EVERYDAY) _everydayState else _coderState
        stateFlow.value = ModelState()
    }

    // === Persistence ===
    private fun saveSlotConfig(slot: Slot, entry: ModelEntry) {
        try {
            val current = if (slotConfigFile.exists()) JSONObject(slotConfigFile.readText()) else JSONObject()
            current.put("everyday", if (slot == Slot.EVERYDAY) entry.path else current.optString("everyday"))
            current.put("coder", if (slot == Slot.CODER) entry.path else current.optString("coder"))
            slotConfigFile.writeText(current.toString())
        } catch (_: Exception) {}
    }

    private fun loadSavedSlots() {
        if (!slotConfigFile.exists()) return
        try {
            val json = JSONObject(slotConfigFile.readText())
            listOf(Slot.EVERYDAY to "everyday", Slot.CODER to "coder").forEach { (slot, key) ->
                val path = json.optString(key).takeIf { it.isNotEmpty() } ?: return@forEach
                val file = File(path)
                if (file.exists() && validateGguf(file)) {
                    val entry = restoreOrGenerateEntry(file)
                    loadSlot(slot, entry) // background reload
                }
            }
        } catch (_: Exception) {}
    }

    fun hasEnoughSpace(required: Long): Boolean {
        val stat = StatFs(modelsDir.absolutePath)
        return stat.availableBytes > required + 512 * 1024 * 1024L
    }

    private fun ensureGgufExtension(name: String): String =
        if (name.endsWith(".gguf", ignoreCase = true)) name else "$name.gguf"

    // Delete, cancel, etc. can be added easily
}
