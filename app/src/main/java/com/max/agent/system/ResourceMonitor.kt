package com.max.agent.system

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import android.os.Environment
import android.os.PowerManager
import android.os.StatFs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.io.File

class ResourceMonitor(private val context: Context) {

    data class Snapshot(
        val cpuPercent: Float = 0f,
        val ramUsedMb: Long = 0L,
        val ramTotalMb: Long = 0L,
        val storageFreeGb: Float = 0f,
        val storageTotalGb: Float = 0f,
        val batteryPct: Int = 0,
        val isCharging: Boolean = false,
        val batteryTempC: Float = 0f,
        val thermalStatus: String = "Normal",
        val timestamp: Long = System.currentTimeMillis()
    ) {
        val ramUsedPct: Float get() = if (ramTotalMb > 0) ramUsedMb / ramTotalMb.toFloat() * 100f else 0f
        val storageUsedPct: Float get() = if (storageTotalGb > 0) (1f - storageFreeGb / storageTotalGb) * 100f else 0f
        val ramFreeMb: Long get() = ramTotalMb - ramUsedMb
    }

    private val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
    private val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
    private val batteryManager = context.getSystemService(Context.BATTERY_SERVICE) as BatteryManager

    private val errorHandler = CoroutineExceptionHandler { _, t ->
        android.util.Log.e("Max", "Unhandled: ${t.message}", t)
        try { 
            context.filesDir.resolve("errors.log")
                .appendText("[${System.currentTimeMillis()}] ${t.javaClass.simpleName}: ${t.message}\n${t.stackTraceToString().take(800)}\n\n") 
        } catch (_: Exception) {}
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO + errorHandler)
    private var monitorJob: Job? = null

    private val _state = MutableStateFlow(sample())
    val state: StateFlow<Snapshot> = _state

    fun startMonitoring(intervalMs: Long = 4_000L) {
        monitorJob?.cancel()
        monitorJob = scope.launch {
            while (true) {
                _state.value = sample()
                delay(intervalMs)
            }
        }
    }

    fun stopMonitoring() { monitorJob?.cancel() }

    fun sample(): Snapshot {
        val memInfo = ActivityManager.MemoryInfo().also { activityManager.getMemoryInfo(it) }
        val ramUsedMb = (memInfo.totalMem - memInfo.availMem) / 1_048_576L
        val ramTotalMb = memInfo.totalMem / 1_048_576L

        val stat = StatFs(Environment.getDataDirectory().path)
        val storageFreeGb = stat.availableBlocksLong * stat.blockSizeLong / 1_073_741_824f
        val storageTotalGb = stat.blockCountLong * stat.blockSizeLong / 1_073_741_824f

        val batteryIntent = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val batteryPct = batteryManager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        val batteryTemp = (batteryIntent?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) ?: 0) / 10f
        val plugged = batteryIntent?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0

        val thermalStatus = if (Build.VERSION.SDK_INT >= 29) {
            when (powerManager.currentThermalStatus) {
                PowerManager.THERMAL_STATUS_NONE -> "Normal"
                PowerManager.THERMAL_STATUS_LIGHT -> "Light"
                PowerManager.THERMAL_STATUS_MODERATE -> "Moderate"
                PowerManager.THERMAL_STATUS_SEVERE -> "Severe"
                PowerManager.THERMAL_STATUS_CRITICAL -> "Critical"
                PowerManager.THERMAL_STATUS_EMERGENCY -> "Emergency"
                PowerManager.THERMAL_STATUS_SHUTDOWN -> "Shutdown"
                else -> "Unknown"
            }
        } else "N/A"

        return Snapshot(
            cpuPercent = readCpuUsage(),
            ramUsedMb = ramUsedMb,
            ramTotalMb = ramTotalMb,
            storageFreeGb = storageFreeGb,
            storageTotalGb = storageTotalGb,
            batteryPct = batteryPct,
            isCharging = plugged != 0,
            batteryTempC = batteryTemp,
            thermalStatus = thermalStatus
        )
    }

    private var prevIdle = 0L
    private var prevTotal = 0L

    private fun readCpuUsage(): Float {
        return try {
            val line = File("/proc/stat").bufferedReader().use { it.readLine() } ?: return 0f
            if (!line.startsWith("cpu ")) return 0f
            
            val tokens = line.split("\\s+".toRegex()).drop(1).mapNotNull { it.toLongOrNull() }
            val idle = tokens.getOrElse(3) { 0L }
            val total = tokens.sum()
            
            val dIdle = idle - prevIdle
            val dTotal = total - prevTotal
            prevIdle = idle
            prevTotal = total
            
            if (dTotal == 0L) 0f else ((dTotal - dIdle).toFloat() / dTotal * 100f).coerceIn(0f, 100f)
        } catch (e: Exception) { 
            0f 
        }
    }
}
