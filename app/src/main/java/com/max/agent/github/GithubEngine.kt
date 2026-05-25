package com.max.agent.github

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.Base64
import java.util.zip.ZipInputStream

class GithubEngine(private val context: Context) {

    companion object {
        private const val BUILD_TIMEOUT_MS  = 6 * 60_000L
        private const val POLL_INTERVAL_MS  = 20_000L
    }

    data class Config(
        val token: String,
        val owner: String,
        val repo: String,
        val branch: String = "main"
    )

    data class BuildStatus(
        val runId: Long,
        val status: String,
        val conclusion: String?
    ) {
        val succeeded: Boolean get() = status == "completed" && conclusion == "success"
        val failed: Boolean    get() = status == "completed" && conclusion != null && conclusion != "success"
        val pending: Boolean   get() = status != "completed"
    }

    sealed class EngineState {
        data object Idle                          : EngineState()
        data class Reading(val path: String)      : EngineState()
        data class Writing(val path: String)      : EngineState()
        data class WaitingBuild(val runId: Long)  : EngineState()
        data object Downloading                   : EngineState()
        data object ReadyToInstall                : EngineState()
        data class Error(val message: String)     : EngineState()
    }

    private val _state = MutableStateFlow<EngineState>(EngineState.Idle)
    val state: StateFlow<EngineState> = _state

    private val legacyConfigFile = File(context.filesDir, "config/github.json")

    private val prefs by lazy {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            context,
            "max_github_secure",
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }

    suspend fun configure(token: String, owner: String, repo: String, branch: String = "main") =
        withContext(Dispatchers.IO) {
            prefs.edit()
                .putString("token", token)
                .putString("owner", owner)
                .putString("repo", repo)
                .putString("branch", branch)
                .apply()
            // Best-effort cleanup of legacy plaintext config from prior versions.
            runCatching { if (legacyConfigFile.exists()) legacyConfigFile.delete() }
        }

    fun config(): Config? {
        val token = prefs.getString("token", null)?.takeIf { it.isNotBlank() } ?: return null
        val owner = prefs.getString("owner", null)?.takeIf { it.isNotBlank() } ?: return null
        val repo = prefs.getString("repo", null)?.takeIf { it.isNotBlank() } ?: return null
        val branch = prefs.getString("branch", "main") ?: "main"
        return Config(token, owner, repo, branch)
    }

    fun isConfigured(): Boolean = config() != null

    suspend fun readSourceFile(path: String): String? = withContext(Dispatchers.IO) {
        val cfg = config() ?: return@withContext null
        _state.value = EngineState.Reading(path)
        val url = "https://api.github.com/repos/${cfg.owner}/${cfg.repo}/contents/$path?ref=${cfg.branch}"
        val raw = get(url, cfg.token) ?: run {
            _state.value = EngineState.Error("Could not read $path")
            return@withContext null
        }
        _state.value = EngineState.Idle
        val encoded = JSONObject(raw).getString("content").replace("\\n", "").replace("\n", "")
        String(Base64.getDecoder().decode(encoded))
    }

    suspend fun writeSourceFile(path: String, content: String, commitMessage: String): Boolean =
        withContext(Dispatchers.IO) {
            val cfg = config() ?: return@withContext false
            _state.value = EngineState.Writing(path)

            val sha = runCatching {
                val r = get(
                    "https://api.github.com/repos/${cfg.owner}/${cfg.repo}/contents/$path",
                    cfg.token
                )
                if (r != null) JSONObject(r).optString("sha").takeIf { it.isNotBlank() } else null
            }.getOrNull()

            val body = JSONObject().apply {
                put("message", commitMessage)
                put("content", Base64.getEncoder().encodeToString(content.toByteArray()))
                put("branch", cfg.branch)
                if (sha != null) put("sha", sha)
            }.toString()

            val ok = put(
                "https://api.github.com/repos/${cfg.owner}/${cfg.repo}/contents/$path",
                cfg.token, body
            ) != null

            _state.value = if (ok) EngineState.Idle else EngineState.Error("Failed to write $path")
            ok
        }

    suspend fun latestBuild(): BuildStatus? = withContext(Dispatchers.IO) {
        val cfg = config() ?: return@withContext null
        val r = get(
            "https://api.github.com/repos/${cfg.owner}/${cfg.repo}/actions/runs?per_page=1&branch=${cfg.branch}",
            cfg.token
        ) ?: return@withContext null
        val runs = JSONObject(r).getJSONArray("workflow_runs")
        if (runs.length() == 0) return@withContext null
        val run = runs.getJSONObject(0)
        BuildStatus(
            runId      = run.getLong("id"),
            status     = run.getString("status"),
            conclusion = run.optString("conclusion").takeIf { it.isNotEmpty() && it != "null" }
        )
    }

    suspend fun waitForBuild(timeoutMs: Long = BUILD_TIMEOUT_MS): BuildStatus? {
        val start = System.currentTimeMillis()
        var last: BuildStatus? = null
        while (System.currentTimeMillis() - start < timeoutMs) {
            last = latestBuild()
            if (last != null) {
                _state.value = EngineState.WaitingBuild(last.runId)
                if (!last.pending) return last
            }
            delay(POLL_INTERVAL_MS)
        }
        return last
    }

    suspend fun downloadLatestApk(): File? = withContext(Dispatchers.IO) {
        val cfg = config() ?: return@withContext null
        val build = latestBuild() ?: return@withContext null
        if (!build.succeeded) return@withContext null

        _state.value = EngineState.Downloading

        val artJson = get(
            "https://api.github.com/repos/${cfg.owner}/${cfg.repo}/actions/runs/${build.runId}/artifacts",
            cfg.token
        ) ?: return@withContext null

        val artifacts = JSONObject(artJson).getJSONArray("artifacts")
        var downloadUrl: String? = null
        for (i in 0 until artifacts.length()) {
            val a = artifacts.getJSONObject(i)
            if (a.getString("name").contains("Max", ignoreCase = true)) {
                downloadUrl = a.getString("archive_download_url")
                break
            }
        }
        if (downloadUrl == null) return@withContext null

        val destDir = File(context.filesDir, "updates").apply { mkdirs() }
        val zipFile = File(destDir, "update.zip")

        val conn = URL(downloadUrl).openConnection() as HttpURLConnection
        conn.setRequestProperty("Authorization", "token ${cfg.token}")
        conn.setRequestProperty("Accept", "application/vnd.github.v3+json")
        conn.setRequestProperty("User-Agent", "Max-Agent")
        conn.instanceFollowRedirects = true
        conn.connect()
        zipFile.outputStream().use { conn.inputStream.copyTo(it) }

        val apkFile = File(destDir, "Max-update.apk")
        ZipInputStream(zipFile.inputStream()).use { zis ->
            var entry = zis.nextEntry
            while (entry != null) {
                if (entry.name.endsWith(".apk")) {
                    apkFile.outputStream().use { zis.copyTo(it) }
                    break
                }
                entry = zis.nextEntry
            }
        }
        zipFile.delete()

        if (apkFile.exists()) {
            _state.value = EngineState.ReadyToInstall
            apkFile
        } else {
            _state.value = EngineState.Error("APK not found in artifact zip")
            null
        }
    }

    private fun get(url: String, token: String): String? = runCatching {
        val c = URL(url).openConnection() as HttpURLConnection
        c.setRequestProperty("Authorization", "token $token")
        c.setRequestProperty("Accept", "application/vnd.github.v3+json")
        c.setRequestProperty("User-Agent", "Max-Agent")
        c.connect()
        if (c.responseCode == 200) c.inputStream.bufferedReader().readText() else null
    }.getOrNull()

    private fun put(url: String, token: String, body: String): String? = runCatching {
        val c = URL(url).openConnection() as HttpURLConnection
        c.requestMethod = "PUT"
        c.doOutput = true
        c.setRequestProperty("Authorization", "token $token")
        c.setRequestProperty("Accept", "application/vnd.github.v3+json")
        c.setRequestProperty("Content-Type", "application/json")
        c.setRequestProperty("User-Agent", "Max-Agent")
        c.outputStream.use { it.write(body.toByteArray()) }
        if (c.responseCode in 200..201) c.inputStream.bufferedReader().readText() else null
    }.getOrNull()
}
