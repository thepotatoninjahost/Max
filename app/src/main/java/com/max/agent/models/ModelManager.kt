package com.max.agent.models

import android.content.Context
import android.net.Uri
import android.os.StatFs
import android.provider.OpenableColumns
import android.system.Os
import android.system.OsConstants
import android.util.Log
import androidx.room.*
import com.nexa.sdk.LlmWrapper
import com.nexa.sdk.bean.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject
import java.io.*
import java.security.MessageDigest
import java.util.UUID

enum class Slot { EVERYDAY, CODER }

@Entity(tableName = "models")
data class ModelEntry(
    @PrimaryKey val id: String,
    val name: String,
    val path: String,
    val sizeBytes: Long,
    val addedAt: Long,
    val sha256: String? = null,
    val contextLength: Int = 32768,
    val paramSize: String? = null
)

@Dao
interface ModelDao {
    @Query("SELECT * FROM models ORDER BY name ASC")
    suspend fun getAll(): List<ModelEntry>
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(model: ModelEntry)
    @Delete
    suspend fun delete(model: ModelEntry)
    @Query("SELECT * FROM models WHERE path = :path LIMIT 1")
    suspend fun getByPath(path: String): ModelEntry?
}

@Database(entities = [ModelEntry::class], version = 1, exportSchema = false)
abstract class ModelDatabase : RoomDatabase() {
    abstract fun modelDao(): ModelDao
}

data class ModelState(
    val isLoading: Boolean = false,
    val isLoaded: Boolean = false,
    val loadedModel: ModelEntry? = null,
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
    
    private val db = Room.databaseBuilder(context.applicationContext, ModelDatabase::class.java, "models.db").build()
    private val modelDao = db.modelDao()

    private val _available = MutableStateFlow<List<ModelEntry>>(emptyList())
    val available: StateFlow<List<ModelEntry>> = _available.asStateFlow()

    private val _everydayState = MutableStateFlow(ModelState())
    val everydayState: StateFlow<ModelState> = _everydayState.asStateFlow()

    private val _coderState = MutableStateFlow(ModelState())
    val coderState: StateFlow<ModelState> = _coderState.asStateFlow()

    init {
        scope.launch {
            scan()
            loadSavedSlots()
            preWarmAllModels()
        }
    }

    fun onCleared() {
        scope.cancel()
        runBlocking { releaseAllSlotsSafely() }
    }

    // --- Public API ---
    fun isSlotLoaded(slot: Slot): Boolean = (if (slot == Slot.EVERYDAY) everydayWrapper else coderWrapper) != null
    fun getSlotEntry(slot: Slot): ModelEntry? = (if (slot == Slot.EVERYDAY) everydayState.value else coderState.value).loadedModel
    fun getModelByPath(path: String): ModelEntry? = available.value.find { it.path == path }

    fun deleteModel(entry: ModelEntry) {
        scope.launch {
            File(entry.path).delete()
            modelDao.delete(entry)
            scan()
        }
    }

    fun applyChatTemplateForSlot(slot: Slot, prompt: String): String = 
        (if (slot == Slot.EVERYDAY) everydayWrapper else coderWrapper)?.applyChatTemplate(prompt) ?: prompt

    fun generateStreamFlowForSlot(slot: Slot, prompt: String): Flow<String> = 
        (if (slot == Slot.EVERYDAY) everydayWrapper else coderWrapper)?.generateStreamFlow(prompt) ?: flowOf("Error: Model not loaded")

    fun stopSlotStream(slot: Slot) { (if (slot == Slot.EVERYDAY) everydayWrapper else coderWrapper)?.stopStream() }

    // --- Architecture Upgrades ---
    private fun preWarmAllModels() {
        scope.launch { modelDao.getAll().forEach { preWarmModel(it.path) } }
    }

    private fun preWarmModel(path: String) {
        try {
            val file = File(path)
            if (!file.exists()) return
            RandomAccessFile(file, "r").use { raf ->
                val size = raf.length()
                if (size > 0) {
                    val address = Os.mmap(0L, size, OsConstants.PROT_READ, OsConstants.MAP_SHARED, raf.fd, 0L)
                    Os.munmap(address, size)
                }
            }
        } catch (_: Exception) {}
    }

    private suspend fun benchmarkAndGetHardwareProfile(entry: ModelEntry): Triple<String, String, Int> {
        val prefs = context.getSharedPreferences("nexa_hardware_profile", Context.MODE_PRIVATE)
        val bestBackend = prefs.getString("backend", null)
        val bestDevice = prefs.getString("device", null)
        
        val memInfo = android.app.ActivityManager.MemoryInfo()
        (context.getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager).getMemoryInfo(memInfo)
        val ramGb = (memInfo.totalMem / 1073741824.0).toInt()
        val optimalLayers = if (ramGb >= 12) 999 else if (ramGb >= 8) 60 else 32

        if (bestBackend != null && bestDevice != null) return Triple(bestBackend, bestDevice, optimalLayers)

        val configs = listOf(Triple("qnn", "npu", optimalLayers), Triple("vulkan", "gpu", optimalLayers), Triple("cpu", "cpu", 0))
        for ((backend, device, layers) in configs) {
            try {
                val testConfig = ModelConfig(128, layers, 64)
                val result = LlmWrapper.builder().llmCreateInput(LlmCreateInput(entry.name, entry.path, testConfig, backend, device)).build()
                if (result.isSuccess) {
                    result.getOrThrow().apply { stopStream(); close() }
                    prefs.edit().putString("backend", backend).putString("device", device).apply()
                    return Triple(backend, device, layers)
                }
            } catch (_: Exception) {}
        }
        return Triple("cpu", "cpu", 0)
    }

    // --- Core Logic ---
    private fun appendErrorLog(t: Throwable) {
        try { context.filesDir.resolve("errors.log").appendText("${System.currentTimeMillis()}: ${t.message}\n") } catch (_: Exception) {}
    }

    private fun InputStream.readInt(): Int {
        val b = ByteArray(4)
        return if (read(b) == 4) ((b[0].toInt() and 0xFF) shl 24) or ((b[1].toInt() and 0xFF) shl 16) or ((b[2].toInt() and 0xFF) shl 8) or (b[3].toInt() and 0xFF) else -1
    }

    private fun validateGguf(file: File): Boolean {
        if (!file.exists() || file.length() < 4) return false
        return try { FileInputStream(file).use { it.readInt() == 0x47475546 } } catch (e: Exception) { false }
    }

    suspend fun scan() = withContext(Dispatchers.IO) {
        val files = modelsDir.listFiles { f -> f.isFile && f.extension.equals("gguf", ignoreCase = true) && f.canRead() } ?: emptyArray()
        
        val validEntries = files.mapNotNull { f ->
            if (!validateGguf(f)) { f.delete(); null } 
            else {
                var entry = modelDao.getByPath(f.absolutePath)
                if (entry == null) {
                    entry = ModelEntry(UUID.randomUUID().toString(), f.nameWithoutExtension, f.absolutePath, f.length(), f.lastModified())
                    modelDao.insert(entry)
                }
                entry
            }
        }.sortedBy { it.name.lowercase() }

        modelDao.getAll().forEach { dbEntry ->
            if (validEntries.none { it.id == dbEntry.id }) modelDao.delete(dbEntry)
        }
        _available.value = validEntries
    }

    fun importFromUri(uri: Uri, onComplete: (Boolean) -> Unit = {}) {
        scope.launch {
            var dest: File? = null
            try {
                val cursor = context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                val name = cursor?.use { if (it.moveToFirst()) it.getString(0) else "imported.gguf" } ?: "imported.gguf"
                dest = File(modelsDir, ensureGgufExtension(name))
                if (dest.exists()) throw Exception("Already exists")
                context.contentResolver.openInputStream(uri)?.use { input -> FileOutputStream(dest).use { output -> input.copyTo(output) } }
                if (!validateGguf(dest)) throw Exception("Invalid")
                scan(); onComplete(true)
            } catch (e: Exception) { dest?.delete(); onComplete(false) }
        }
    }

    fun loadSlot(slot: Slot, entry: ModelEntry, onComplete: (Boolean) -> Unit = {}) {
        scope.launch {
            slotMutex.withLock {
                releaseSlotInternal(if (slot == Slot.EVERYDAY) Slot.CODER else Slot.EVERYDAY)
                releaseSlotInternal(slot)
                
                val stateFlow = if (slot == Slot.EVERYDAY) _everydayState else _coderState
                stateFlow.value = ModelState(isLoading = true, loadedModel = entry)
                
                val success = tryHardwareAcceleratedLoad(slot, entry, stateFlow)
                if (!success) stateFlow.value = ModelState(error = "Hardware allocation failed")
                onComplete(success)
            }
        }
    }

    private suspend fun tryHardwareAcceleratedLoad(slot: Slot, entry: ModelEntry, stateFlow: MutableStateFlow<ModelState>): Boolean {
        val (backend, device, layers) = benchmarkAndGetHardwareProfile(entry)
        val ramGb = ((context.getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager)
            .apply { getMemoryInfo(android.app.ActivityManager.MemoryInfo()) }
            .let { android.app.ActivityManager.MemoryInfo().totalMem } / 1073741824.0).toInt()
        val dynamicContext = if (ramGb >= 12) entry.contextLength else 16384

        try {
            val config = ModelConfig(dynamicContext, layers, dynamicContext / 2)
            val result = LlmWrapper.builder().llmCreateInput(LlmCreateInput(entry.name, entry.path, config, backend, device)).build()
            
            if (result.isSuccess) {
                if (slot == Slot.EVERYDAY) everydayWrapper = result.getOrThrow() else coderWrapper = result.getOrThrow()
                stateFlow.value = ModelState(isLoaded = true, loadedModel = entry)
                saveSlotConfig(slot, entry)
                return true
            }
        } catch (e: Exception) { appendErrorLog(Exception("Load failed: ${e.message}")) }
        return false
    }

    fun releaseSlot(slot: Slot) { scope.launch { slotMutex.withLock { releaseSlotInternal(slot) } } }

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

    private fun saveSlotConfig(slot: Slot, entry: ModelEntry) {
        try {
            val json = if (slotConfigFile.exists()) JSONObject(slotConfigFile.readText()) else JSONObject()
            json.put(if (slot == Slot.EVERYDAY) "everyday" else "coder", entry.path)
            slotConfigFile.writeText(json.toString())
        } catch (_: Exception) {}
    }

    private suspend fun loadSavedSlots() {
        if (!slotConfigFile.exists()) return
        try {
            val json = JSONObject(slotConfigFile.readText())
            listOf(Slot.EVERYDAY to "everyday", Slot.CODER to "coder").forEach { (slot, key) ->
                val path = json.optString(key).takeIf { it.isNotEmpty() } ?: return@forEach
                modelDao.getByPath(path)?.let { loadSlot(slot, it) }
            }
        } catch (_: Exception) {}
    }

    fun hasEnoughSpace(required: Long): Boolean = StatFs(modelsDir.absolutePath).availableBytes > required + 512 * 1024 * 1024L
    private fun ensureGgufExtension(name: String): String = if (name.endsWith(".gguf", true)) name else "$name.gguf"
}
