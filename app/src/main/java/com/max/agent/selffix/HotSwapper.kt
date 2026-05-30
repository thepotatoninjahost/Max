package com.max.agent.selffix

import android.content.Context
import dalvik.system.DexClassLoader
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.File
import java.util.UUID

class HotSwapper(private val context: Context) {

    data class SwapRecord(
        val id: String = UUID.randomUUID().toString(),
        val className: String,
        val dexPath: String,
        val timestamp: Long = System.currentTimeMillis(),
        val active: Boolean = true
    )

    data class SwapResult(
        val success: Boolean,
        val swapId: String? = null,
        val instance: Any? = null,
        val error: String? = null
    )

    private val swapDir = File(context.filesDir, "hotswap/dex").also { it.mkdirs() }
    private val patchDir = File(context.filesDir, "patches").also { it.mkdirs() }

    private val _swaps = MutableStateFlow<List<SwapRecord>>(emptyList())
    val swaps: StateFlow<List<SwapRecord>> = _swaps

    fun loadFromDex(dexPath: String, className: String): SwapResult = runCatching {
        val loader = DexClassLoader(dexPath, null, null, context.classLoader)
        val clazz = loader.loadClass(className)
        val instance = clazz.getDeclaredConstructor().newInstance()
        
        val record = SwapRecord(className = className, dexPath = dexPath)
        _swaps.value = _swaps.value + record
        
        SwapResult(success = true, swapId = record.id, instance = instance)
    }.getOrElse { e -> SwapResult(success = false, error = e.message ?: e.javaClass.simpleName) }

    fun installDexBytes(bytes: ByteArray, className: String): SwapResult {
        val file = File(swapDir, "${className.substringAfterLast('.')}_${System.currentTimeMillis()}.dex")
        if (file.exists()) { file.delete() }
        file.writeBytes(bytes)
        return loadFromDex(file.absolutePath, className)
    }

    fun rollback(swapId: String): Boolean {
        val current = _swaps.value
        if (current.none { it.id == swapId }) return false
        _swaps.value = current.map { if (it.id == swapId) it.copy(active = false) else it }
        return true
    }

    fun listDexFiles(): List<File> = swapDir.listFiles { f -> f.extension == "dex" }?.toList() ?: emptyList()

    fun listPatches(): List<File> = patchDir.listFiles { f -> f.extension == "kt" }?.toList() ?: emptyList()

    fun savePatchSource(source: String, className: String): File {
        val file = File(patchDir, "${className}_${System.currentTimeMillis()}.kt")
        if (file.exists()) { file.delete() }
        file.writeText(source)
        return file
    }
}
