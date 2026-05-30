package com.max.agent.scripting

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import org.mozilla.javascript.EcmaError
import org.mozilla.javascript.EvaluatorException
import org.mozilla.javascript.ScriptableObject
import java.io.File
import java.util.UUID

/**
 * Real runtime code execution via Mozilla Rhino JavaScript engine.
 *
 * Scripts are actual JavaScript that runs in the JVM with full access
 * to Android APIs via Rhino's Java bridge.
 */
class ScriptingEngine(private val context: Context) {

    data class ScriptResult(
        val id: String = UUID.randomUUID().toString(),
        val script: String,
        val output: Any?,
        val success: Boolean,
        val error: String? = null,
        val durationMs: Long = 0L
    )

    data class ScriptRecord(
        val id: String = UUID.randomUUID().toString(),
        val name: String,
        val source: String,
        val autorun: Boolean,
        val createdAt: Long = System.currentTimeMillis(),
        var lastResult: ScriptResult? = null
    )

    private val autorunDir = File(context.filesDir, "scripts/autorun").also { it.mkdirs() }
    private val savedDir   = File(context.filesDir, "scripts/saved").also  { it.mkdirs() }

    private val _log = MutableStateFlow<List<String>>(emptyList())
    val log: StateFlow<List<String>> = _log

    private val _scripts = MutableStateFlow<List<ScriptRecord>>(emptyList())
    val scripts: StateFlow<List<ScriptRecord>> = _scripts

    // ── Public API ────────────────────────────────────────────────────────────

    suspend fun execute(
        script:   String,
        bindings: Map<String, Any> = emptyMap(),
        maxSystem: Any? = null
    ): ScriptResult = withContext(Dispatchers.Default) {
        val start = System.currentTimeMillis()
        val outputLines = java.util.Collections.synchronizedList(mutableListOf<String>())

        val rhinoCtx = org.mozilla.javascript.Context.enter()
        try {
            rhinoCtx.optimizationLevel = -1   // Required on Android — no JIT
            rhinoCtx.languageVersion = org.mozilla.javascript.Context.VERSION_ES6

            val scope = rhinoCtx.initStandardObjects()

            put(scope, "ctx",      context,              rhinoCtx)
            put(scope, "filesDir", context.filesDir.absolutePath, rhinoCtx)
            maxSystem?.let { put(scope, "max", it, rhinoCtx) }

            val logFn = object : org.mozilla.javascript.BaseFunction() {
                override fun call(
                    cx: org.mozilla.javascript.Context,
                    scope: org.mozilla.javascript.Scriptable,
                    thisObj: org.mozilla.javascript.Scriptable,
                    args: Array<Any>
                ): Any {
                    val msg = args.joinToString(" ") { org.mozilla.javascript.Context.toString(it) }
                    outputLines.add(msg)
                    return msg
                }
            }
            ScriptableObject.putProperty(scope, "log", logFn)

            val runOnMainFn = object : org.mozilla.javascript.BaseFunction() {
                override fun call(
                    cx: org.mozilla.javascript.Context,
                    s:  org.mozilla.javascript.Scriptable,
                    thisObj: org.mozilla.javascript.Scriptable,
                    args: Array<Any>
                ): Any {
                    val fn = args.firstOrNull() as? org.mozilla.javascript.Function ?: return false
                    
                    val handler = android.os.Handler(android.os.Looper.getMainLooper())
                    handler.post {
                        val mainCtx = org.mozilla.javascript.Context.enter()
                        try {
                            mainCtx.optimizationLevel = -1
                            val mainScope = mainCtx.newObject(s)
                            mainScope.prototype = s
                            mainScope.parentScope = null
                            fn.call(mainCtx, mainScope, mainScope, emptyArray())
                        } catch (e: Exception) {
                            android.util.Log.e("MaxScript", "runOnMain execution error: ${e.message}")
                        } finally {
                            org.mozilla.javascript.Context.exit()
                        }
                    }
                    return true
                }
            }
            ScriptableObject.putProperty(scope, "runOnMain", runOnMainFn)

            bindings.forEach { (k, v) -> put(scope, k, v, rhinoCtx) }

            val result = rhinoCtx.evaluateString(scope, script, "<max_script>", 1, null)
            val resultStr = if (result == null || result == org.mozilla.javascript.Context.getUndefinedValue())
                outputLines.joinToString("\n")
            else
                org.mozilla.javascript.Context.toString(result)

            appendLog("✓ Script executed (${System.currentTimeMillis() - start}ms): $resultStr")

            ScriptResult(
                script    = script,
                output    = resultStr,
                success   = true,
                durationMs = System.currentTimeMillis() - start
            )
        } catch (e: EcmaError) {
            val msg = "EcmaError line ${e.lineNumber()}: ${e.errorMessage}"
            appendLog("✗ $msg")
            ScriptResult(script = script, output = null, success = false, error = msg, durationMs = System.currentTimeMillis() - start)
        } catch (e: EvaluatorException) {
            val msg = "EvalError: ${e.message}"
            appendLog("✗ $msg")
            ScriptResult(script = script, output = null, success = false, error = msg, durationMs = System.currentTimeMillis() - start)
        } catch (e: Exception) {
            val msg = "Error: ${e.message}"
            appendLog("✗ $msg")
            ScriptResult(script = script, output = null, success = false, error = msg, durationMs = System.currentTimeMillis() - start)
        } finally {
            org.mozilla.javascript.Context.exit()
        }
    }

    fun save(name: String, source: String, autorun: Boolean): ScriptRecord {
        val safeName = name.replace(Regex("[^a-zA-Z0-9_\\-]"), "_")
        val file = if (autorun) File(autorunDir, "$safeName.js")
                   else         File(savedDir,   "$safeName.js")
        file.writeText(source)
        val record = ScriptRecord(name = safeName, source = source, autorun = autorun)
        _scripts.value = _scripts.value.filterNot { it.name == safeName } + record
        appendLog("Saved script: ${file.absolutePath}")
        return record
    }

    fun delete(name: String) {
        File(autorunDir, "$name.js").delete()
        File(savedDir,   "$name.js").delete()
        _scripts.value = _scripts.value.filterNot { it.name == name }
    }

    suspend fun runAutorun(maxSystem: Any? = null) {
        val files = autorunDir.listFiles { f -> f.extension == "js" } ?: return
        for (file in files.sortedBy { it.name }) {
            appendLog("Autorun: ${file.name}")
            execute(file.readText(), maxSystem = maxSystem)
        }
    }

    fun listScripts(): List<ScriptRecord> {
        val autorun = autorunDir.listFiles { f -> f.extension == "js" }
            ?.map { ScriptRecord(name = it.nameWithoutExtension, source = it.readText(), autorun = true) }
            ?: emptyList()
        val saved = savedDir.listFiles { f -> f.extension == "js" }
            ?.map { ScriptRecord(name = it.nameWithoutExtension, source = it.readText(), autorun = false) }
            ?: emptyList()
        return autorun + saved
    }

    private fun put(
        scope: org.mozilla.javascript.Scriptable,
        name: String,
        value: Any,
        @Suppress("UNUSED_PARAMETER") cx: org.mozilla.javascript.Context
    ) {
        ScriptableObject.putProperty(scope, name, org.mozilla.javascript.Context.javaToJS(value, scope))
    }

    private fun appendLog(msg: String) {
        val ts = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US).format(java.util.Date())
        _log.value = (_log.value + "[$ts] $msg").takeLast(200)
        Log.d("MaxScript", msg)
    }
}
