package com.max.agent.safety

import android.content.Context
import java.io.File

/**
 * Isolated sandbox for self-modification testing. Constitution Rule 5.
 *
 * Any code Max wants to change in itself must:
 * 1. Be copied into this sandbox directory
 * 2. Run tests against the sandboxed copy
 * 3. Show results to owner for approval
 * 4. Only after approval, promote to production
 *
 * The sandbox is a mirror of app's internal files, fully isolated.
 */
class Sandbox(private val context: Context) {

    data class TestResult(
        val success: Boolean,
        val testName: String,
        val output: String,
        val errors: String = "",
        val timestamp: Long = System.currentTimeMillis()
    )

    private val sandboxRoot: File
        get() = File(context.filesDir, "sandbox")

    fun initialize() {
        if (!sandboxRoot.exists()) {
            sandboxRoot.mkdirs()
        }
    }

    fun stageFile(sourcePath: String, targetName: String): Boolean {
        return try {
            val source = File(sourcePath)
            if (!source.exists()) return false
            val target = File(sandboxRoot, targetName)
            source.copyTo(target, overwrite = true)
            true
        } catch (e: Exception) {
            false
        }
    }

    fun readStagedFile(targetName: String): String? {
        val file = File(sandboxRoot, targetName)
        return if (file.exists()) file.readText() else null
    }

    fun runTest(testName: String, testLogic: () -> TestResult): TestResult {
        return try {
            testLogic()
        } catch (e: Exception) {
            TestResult(
                success = false,
                testName = testName,
                output = "",
                errors = "Exception: ${e.message}"
            )
        }
    }

    fun promoteFile(targetName: String, destinationPath: String): Boolean {
        return try {
            val source = File(sandboxRoot, targetName)
            if (!source.exists()) return false
            val dest = File(destinationPath)
            dest.parentFile?.mkdirs()
            source.copyTo(dest, overwrite = true)
            true
        } catch (e: Exception) {
            false
        }
    }

    fun clean() {
        sandboxRoot.deleteRecursively()
        sandboxRoot.mkdirs()
    }

    fun listStagedFiles(): List<String> {
        return sandboxRoot.list()?.toList() ?: emptyList()
    }
}
