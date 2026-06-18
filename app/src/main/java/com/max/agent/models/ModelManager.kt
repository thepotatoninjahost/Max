package com.max.agent.models

import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.os.Environment
import android.provider.OpenableColumns
import android.util.Log
import com.nexa.sdk.LlmWrapper
import com.nexa.sdk.bean.ChatMessage
import com.nexa.sdk.bean.GenerationConfig
import com.nexa.sdk.bean.LlmCreateInput
import com.nexa.sdk.bean.LlmStreamResult
import com.nexa.sdk.bean.ModelConfig
import com.nexa.sdk.bean.SamplerConfig
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID

class ModelManager(private val context: Context) {

    data class ModelEntry(
        val id: String = UUID.randomUUID().toString(),
        val name: String,
        val path: String,
        val sizeBytes: Long,
        val addedAt: Long = System.currentTimeMillis()
    ) {
        val displaySize: String get() = when {
            sizeBytes >= 1_073_741_824L -> "%.1f GB".format(sizeBytes / 1_073_741_824.0)
            sizeBytes >= 1_048_576L     -> "%.0f MB".format(sizeBytes / 1_048_576.0)
            else                        -> "%.0f KB".format(sizeBytes / 1_024.0)
        }
    }

    data class ModelState(
        val isLoaded: Boolean = false,
        val isLoading: Boolean = false,
        val loadedModel: ModelEntry? = null,
        val error: String? = null
    )

    data class TransferState(
        val active: Boolean = false,
        val label: String = "",
        val fileName: String = "",
        val progress: Float = 0f,
        val bytesTransferred: Long = 0L,
        val totalBytes: Long = -1L,
        val error: String? = null
    ) {
        val progressText: String get() = when {
            totalBytes > 0 -> "${formatBytes(bytesTransferred)} / ${formatBytes(totalBytes)}"
            bytesTransferred > 0 -> formatBytes(bytesTransferred)
            else -> ""
        }

        private fun formatBytes(b: Long) = when {
            b >= 1_073_741_824L -> "%.1f GB".format(b / 1_073_741_824.0)
            b >= 1_048_576L     -> "%.0f MB".format(b / 1_048_576.0)
            else                -> "%.0f KB".format(b / 1_024.0)
        }
    }

    private val errorHandler = CoroutineExceptionHandler { _, t ->
        Log.e("Max", "Unhandled: ${t.message}", t)
        try {
            context.filesDir.resolve("errors.log")
                .appendText("[${System.currentTimeMillis()}] ${t.javaClass.simpleName}: ${t.message}\n${t.stackTraceToString().take(800)}\n\n")
        } catch (_: Exception) {}
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO + errorHandler)

    private val _available = MutableStateFlow<List<ModelEntry>>(emptyList())
    val available: StateFlow<List<ModelEntry>> = _available

    private val _transfer = MutableStateFlow(TransferState())
    val transfer: StateFlow<TransferState> = _transfer

    private var downloadJob: Job? = null

    // ── Dual-slot: EVERYDAY + CODER ───────────────────────────────────────────
    enum class Slot { EVERYDAY, CODER }

    private val _everydayState = MutableStateFlow(ModelState())
    val everydayState: StateFlow<ModelState> = _everydayState

    private val _coderState = MutableStateFlow(ModelState())
    val coderState: StateFlow<ModelState> = _coderState

    private var everydayWrapper: LlmWrapper? = null
    private var coderWrapper: LlmWrapper? = null

    private val modelsDir: File get() = File(context.filesDir, "models").also { it.mkdirs() }

    init {
        scan()
    }

    // ── Scan (only private models dir - reliable) ─────────────────────────────
    fun scan() {
        val files = modelsDir.listFiles { f ->
            f.isFile && f.extension.equals("gguf", ignoreCase = true) && f.canRead()
        } ?: emptyArray()

        Log.i("ModelManager", "SCAN: Found ${files.size} models in private models dir")

        val currentEveryday = _everydayState.value.loadedModel
        val currentCoder = _coderState.value.loadedModel

        _available.value = files.map { f ->
            ModelEntry(
                id = currentEveryday?.path?.takeIf { it == f.absolutePath }?.let { currentEveryday.id }
                    ?: currentCoder?.path?.takeIf { it == f.absolutePath }?.let { currentCoder.id }
                    ?: UUID.randomUUID().toString(),
                name = f.nameWithoutExtension,
                path = f.absolutePath,
                sizeBytes = f.length(),
                addedAt = f.lastModified()
            )
        }.sortedBy { it.name.lowercase() }
    }

    fun loadSlot(slot: Slot, entry: ModelEntry, onComplete: (Boolean) -> Unit = {}) {
        if (slot == Slot.EVERYDAY) releaseSlot(Slot.CODER) else releaseSlot(Slot.EVERYDAY)

        val stateFlow = if (slot == Slot.EVERYDAY) _everydayState else _coderState
        stateFlow.value = ModelState(isLoading = true, loadedModel = entry)

        scope.launch {
            val file = File(entry.path)
            val diag = "path=${file.absolutePath}, exists=${file.exists()}, readable=${file.canRead()}, size=${file.length()}"
            Log.i("ModelManager", "LOAD ATTEMPT $slot: $diag")

            if (!file.exists() || !file.canRead()) {
                val err = "FILE ERROR: $diag"
                stateFlow.value = ModelState(error = err)
                Log.e("ModelManager", err)
                onComplete(false)
                return@launch
            }

            var wrapper: LlmWrapper? = null
            val attempts = mutableListOf<String>()

            val configs = listOf(
                Triple("cpu_gpu", "dev0", 999),  // NPU first
                Triple("cpu_gpu", "gpu", 999)
            )

            for ((pluginId, deviceId, layers) in configs) {
                val label = "$pluginId/$deviceId"
                try {
                    val input = LlmCreateInput(
                        model_name = entry.name,
                        model_path = entry.path,
                        config = ModelConfig(nCtx = 8192, nGpuLayers = layers, max_tokens = 4096),
                        plugin_id = pluginId,
                        device_id = deviceId
                    )

                    val result = LlmWrapper.builder().llmCreateInput(input).build()
                    result.onSuccess { w ->
                        wrapper = w
                        attempts.add("$label = SUCCESS")
                        Log.i("ModelManager", "SUCCESS $slot $label")
                    }.onFailure { e ->
                        attempts.add("$label = FAIL: ${e.message}")
                        Log.w("ModelManager", "FAIL $slot $label: ${e.message}")
                    }

                    if (wrapper != null) break
                } catch (t: Throwable) {
                    attempts.add("$label = CRASH: ${t.javaClass.simpleName}")
                    Log.e("ModelManager", "CRASH $slot $label", t)
                }
            }

            if (wrapper != null) {
                if (slot == Slot.EVERYDAY) everydayWrapper = wrapper else coderWrapper = wrapper
                stateFlow.value = ModelState(isLoaded = true, loadedModel = entry)
                Log.i("ModelManager", "FINAL SUCCESS: $slot ${entry.name}")
                onComplete(true)
            } else {
                val err = "LOAD FAILED $slot. Attempts: ${attempts.joinToString(" | ")}"
                stateFlow.value = ModelState(error = err)
                Log.e("ModelManager", err)
                onComplete(false)
            }
        }
    }

    suspend fun loadSlotAsync(slot: Slot, entry: ModelEntry): Boolean {
        val done = kotlinx.coroutines.CompletableDeferred<Boolean>()
        loadSlot(slot, entry) { done.complete(it) }
        return done.await()
    }

    fun releaseSlot(slot: Slot) {
        if (slot == Slot.EVERYDAY) {
            runCatching { everydayWrapper?.destroy() }
                .onFailure { Log.e("ModelManager", "destroy() threw for EVERYDAY slot", it) }
            everydayWrapper = null
            _everydayState.value = ModelState()
        } else {
            runCatching { coderWrapper?.destroy() }
                .onFailure { Log.e("ModelManager", "destroy() threw for CODER slot", it) }
            coderWrapper = null
            _coderState.value = ModelState()
        }
    }

    suspend fun applyChatTemplateForSlot(slot: Slot, messages: List<ChatMessage>): String? {
        val w = if (slot == Slot.EVERYDAY) everydayWrapper else coderWrapper
        return w?.applyChatTemplate(messages.toTypedArray(), null, false)?.getOrNull()?.formattedText
    }

    fun generateStreamFlowForSlot(slot: Slot, prompt: String, maxTokens: Int = 2048): Flow<LlmStreamResult> {
        val w = (if (slot == Slot.EVERYDAY) everydayWrapper else coderWrapper)
            ?: error("${slot.name} model not loaded")
        return w.generateStreamFlow(prompt, GenerationConfig(
            maxTokens = maxTokens,
            samplerConfig = SamplerConfig(temperature = 0.7f, topP = 0.9f)
        ))
    }

    suspend fun stopSlotStream(slot: Slot) {
        if (slot == Slot.EVERYDAY) everydayWrapper?.stopStream()
        else coderWrapper?.stopStream()
    }

    fun isSlotLoaded(slot: Slot) =
        if (slot == Slot.EVERYDAY) _everydayState.value.isLoaded
        else _coderState.value.isLoaded

    // ── Slot config (persisted) ──────────────────────────────────────────────
    private val slotConfigFile = File(context.filesDir, "config/model_slots.json")

    fun saveSlotConfig(everydayPath: String?, coderPath: String?) {
        slotConfigFile.parentFile?.mkdirs()
        slotConfigFile.writeText(JSONObject().apply {
            everydayPath?.let { put("everyday", it) }
            coderPath?.let { put("coder", it) }
        }.toString())
    }

    fun getSlotEntry(slot: Slot): ModelEntry? {
        val config = runCatching {
            JSONObject(slotConfigFile.readText())
        }.getOrNull() ?: return null
        val key = if (slot == Slot.EVERYDAY) "everyday" else "coder"
        val path = config.optString(key).ifBlank { return null }

        return _available.value.firstOrNull { it.path == path } ?: ModelEntry(
            name = File(path).nameWithoutExtension,
            path = path,
            sizeBytes = File(path).length()
        )
    }

    fun getModelByPath(path: String?): ModelEntry? {
        if (path.isNullOrBlank()) return null
        return _available.value.firstOrNull { it.path == path }
    }

    fun getCoderEntry() = getSlotEntry(Slot.CODER)
    fun getEverydayEntry() = getSlotEntry(Slot.EVERYDAY)

    fun loadSlotConfig(): Pair<String?, String?> {
        if (!slotConfigFile.exists()) return Pair(null, null)
        return try {
            val obj = JSONObject(slotConfigFile.readText())
            Pair(obj.optString("everyday").takeIf { it.isNotBlank() },
                 obj.optString("coder").takeIf { it.isNotBlank() })
        } catch (e: Exception) { Pair(null, null) }
    }

    fun loadModel(entry: ModelEntry, onComplete: (Boolean) -> Unit = {}) =
        loadSlot(Slot.EVERYDAY, entry, onComplete)

    fun releaseCurrent() {
        everydayWrapper?.destroy()
        everydayWrapper = null
        val s = _everydayState.value
        if (s.isLoaded || s.isLoading || s.error != null) {
            _everydayState.value = ModelState()
        }
    }

    fun deleteModel(entry: ModelEntry) {
        if (_everydayState.value.loadedModel?.path == entry.path) releaseCurrent()
        File(entry.path).delete()
        scan()
    }

    // ── SAF Import ────────────────────────────────────────────────────────────
    fun importFromUri(uri: Uri, onComplete: (Boolean) -> Unit = {}) {
        if (_transfer.value.active) return
        scope.launch {
            try {
                val (displayName, fileSize) = resolveUriMeta(uri)
                val safeName = ensureGgufExtension(displayName)
                val destFile = File(modelsDir, safeName)

                _transfer.value = TransferState(
                    active = true, label = "Importing",
                    fileName = safeName, totalBytes = fileSize
                )

                context.contentResolver.openInputStream(uri)?.use { input ->
                    destFile.outputStream().use { output ->
                        streamWithProgress(input, output, fileSize) { done, total ->
                            _transfer.value = _transfer.value.copy(
                                bytesTransferred = done,
                                progress = if (total > 0) done.toFloat() / total else 0f
                            )
                        }
                    }
                } ?: throw Exception("Cannot open URI stream")

                scan()
                _transfer.value = TransferState()
                onComplete(true)
            } catch (e: Exception) {
                _transfer.value = TransferState(error = "Import failed: ${e.message}")
                onComplete(false)
            }
        }
    }

    // ── Download ──────────────────────────────────────────────────────────────
    fun downloadModel(url: String, fileName: String, onComplete: (Boolean) -> Unit = {}) {
        if (_transfer.value.active) return
        val safeName = ensureGgufExtension(fileName.ifBlank { url.substringAfterLast('/') })
        val tempFile = File(modelsDir, "$safeName.part")
        val destFile = File(modelsDir, safeName)

        _transfer.value = TransferState(active = true, label = "Downloading", fileName = safeName)

        downloadJob = scope.launch {
            try {
                val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                    connectTimeout = 15_000
                    readTimeout = 60_000
                    connect()
                }
                val total = conn.contentLengthLong
                _transfer.value = _transfer.value.copy(totalBytes = total)

                conn.inputStream.use { input ->
                    tempFile.outputStream().use { output ->
                        streamWithProgress(input, output, total) { done, t ->
                            if (!_transfer.value.active) throw InterruptedException("Cancelled")
                            _transfer.value = _transfer.value.copy(
                                bytesTransferred = done,
                                progress = if (t > 0) done.toFloat() / t else 0f
                            )
                        }
                    }
                }

                tempFile.renameTo(destFile)
                scan()
                _transfer.value = TransferState()
                onComplete(true)
            } catch (e: InterruptedException) {
                tempFile.delete()
                _transfer.value = TransferState()
                onComplete(false)
            } catch (e: Exception) {
                tempFile.delete()
                _transfer.value = TransferState(error = "Download failed: ${e.message}")
                onComplete(false)
            }
        }
    }

    fun cancelTransfer() {
        _transfer.value = _transfer.value.copy(active = false)
        downloadJob?.cancel()
        downloadJob = null
    }

    fun clearTransferError() {
        if (!_transfer.value.active) _transfer.value = TransferState()
    }

    // ── Inference ─────────────────────────────────────────────────────────────
    suspend fun applyChatTemplate(messages: List<ChatMessage>): String? =
        applyChatTemplateForSlot(Slot.EVERYDAY, messages)

    fun generateStreamFlow(prompt: String, maxTokens: Int = 2048): Flow<LlmStreamResult> =
        generateStreamFlowForSlot(Slot.EVERYDAY, prompt, maxTokens)

    suspend fun stopStream() { stopSlotStream(Slot.EVERYDAY) }

    // ── Helpers ───────────────────────────────────────────────────────────────
    private fun resolveUriMeta(uri: Uri): Pair<String, Long> {
        val cursor: Cursor? = context.contentResolver.query(uri, null, null, null, null)
        return cursor?.use { c ->
            c.moveToFirst()
            val nameIdx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            val sizeIdx = c.getColumnIndex(OpenableColumns.SIZE)
            val name = if (nameIdx >= 0) c.getString(nameIdx) else "model.gguf"
            val size = if (sizeIdx >= 0) c.getLong(sizeIdx) else -1L
            name to size
        } ?: ("model.gguf" to -1L)
    }

    private fun ensureGgufExtension(name: String) =
        if (name.endsWith(".gguf", ignoreCase = true)) name else "$name.gguf"

    private fun streamWithProgress(
        input: java.io.InputStream,
        output: java.io.OutputStream,
        total: Long,
        onProgress: (done: Long, total: Long) -> Unit
    ) {
        val buf = ByteArray(256 * 1024)
        var done = 0L
        var read = input.read(buf)
        while (read >= 0) {
            output.write(buf, 0, read)
            done += read
            onProgress(done, total)
            read = input.read(buf)
        }
    }
}
