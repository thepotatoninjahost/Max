package com.max.agent.system

import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.net.wifi.WifiManager
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

class SystemController(private val context: Context) {

    data class Snapshot(
        val mediaVolumePct: Int = 0,
        val ringVolumePct: Int = 0,
        val alarmVolumePct: Int = 0,
        val notifVolumePct: Int = 0,
        val brightnessPct: Int = 50,
        val isWifiEnabled: Boolean = false,
        val isBluetoothEnabled: Boolean = false,
        val isPowerSaveMode: Boolean = false,
        val ringerMode: String = "Normal"
    )

    private val audio = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val wifi = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
    private val power = context.getSystemService(Context.POWER_SERVICE) as PowerManager
    private val btManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager

    private val _state = MutableStateFlow(refresh())
    val state: StateFlow<Snapshot> = _state

    // ── Audio ───────────────────────────────────────────────────────────────

    fun getVolumePct(stream: Int): Int {
        val max = audio.getStreamMaxVolume(stream).coerceAtLeast(1)
        return (audio.getStreamVolume(stream) * 100) / max
    }

    fun setVolumePct(stream: Int, pct: Int) {
        val max = audio.getStreamMaxVolume(stream)
        audio.setStreamVolume(stream, ((pct / 100f) * max).toInt().coerceIn(0, max), 0)
        _state.value = refresh()
    }

    fun muteMedia() = setVolumePct(AudioManager.STREAM_MUSIC, 0)
    fun maxMedia() = setVolumePct(AudioManager.STREAM_MUSIC, 100)

    fun setRingerSilent() { runCatching { audio.ringerMode = AudioManager.RINGER_MODE_SILENT } }
    fun setRingerVibrate() { runCatching { audio.ringerMode = AudioManager.RINGER_MODE_VIBRATE } }
    fun setRingerNormal() { runCatching { audio.ringerMode = AudioManager.RINGER_MODE_NORMAL } }

    // ── Brightness ──────────────────────────────────────────────────────────

    fun getBrightnessPct(): Int {
        val raw = Settings.System.getInt(context.contentResolver, Settings.System.SCREEN_BRIGHTNESS, 128)
        return (raw * 100) / 255
    }

    fun setBrightnessPct(pct: Int) {
        if (!Settings.System.canWrite(context)) { openWriteSettingsPage(); return }
        runCatching {
            val value = ((pct / 100f) * 255).toInt().coerceIn(1, 255)
            Settings.System.putInt(context.contentResolver, Settings.System.SCREEN_BRIGHTNESS_MODE,
                Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL)
            Settings.System.putInt(context.contentResolver, Settings.System.SCREEN_BRIGHTNESS, value)
            _state.value = refresh()
        }
    }

    // ── WiFi ────────────────────────────────────────────────────────────────

    fun isWifiEnabled(): Boolean = wifi.isWifiEnabled

    fun openWifiPanel() {
        val intent = if (Build.VERSION.SDK_INT >= 29)
            Intent(Settings.Panel.ACTION_WIFI)
        else
            Intent(Settings.ACTION_WIFI_SETTINGS)
        context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    fun openInternetPanel() {
        if (Build.VERSION.SDK_INT >= 29) {
            context.startActivity(Intent(Settings.Panel.ACTION_INTERNET_CONNECTIVITY)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } else openWifiPanel()
    }

    // ── Bluetooth ───────────────────────────────────────────────────────────

    fun isBluetoothEnabled(): Boolean = try {
        btManager?.adapter?.isEnabled == true
    } catch (e: SecurityException) { 
        false // API 31+ requires BLUETOOTH_CONNECT permission
    }

    fun openBluetoothPanel() {
        val intent = Intent(Settings.ACTION_BLUETOOTH_SETTINGS)
        context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    fun getBondedDevices(): List<String> = try {
        btManager?.adapter?.bondedDevices?.map { it.name ?: "Unknown" } ?: emptyList()
    } catch (e: SecurityException) { 
        emptyList() 
    }

    // ── Power ───────────────────────────────────────────────────────────────

    fun isPowerSaveMode(): Boolean = power.isPowerSaveMode

    fun openBatterySaverSettings() {
        context.startActivity(Intent(Settings.ACTION_BATTERY_SAVER_SETTINGS)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    // ── Hotspot / NFC ────────────────────────────────────────────────────────

    fun openHotspotSettings() {
        context.startActivity(Intent("android.settings.TETHER_SETTINGS")
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    fun openNfcSettings() {
        context.startActivity(Intent(Settings.ACTION_NFC_SETTINGS)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    // ── Write Settings permission ────────────────────────────────────────────

    fun canWriteSettings(): Boolean = Settings.System.canWrite(context)

    fun openWriteSettingsPage() {
        context.startActivity(Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS)
            .setData(android.net.Uri.parse("package:${context.packageName}"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    // ── State snapshot ───────────────────────────────────────────────────────

    fun refresh(): Snapshot {
        val ringerLabel = when (runCatching { audio.ringerMode }.getOrDefault(AudioManager.RINGER_MODE_NORMAL)) {
            AudioManager.RINGER_MODE_SILENT -> "Silent"
            AudioManager.RINGER_MODE_VIBRATE -> "Vibrate"
            else -> "Normal"
        }
        return Snapshot(
            mediaVolumePct = getVolumePct(AudioManager.STREAM_MUSIC),
            ringVolumePct = getVolumePct(AudioManager.STREAM_RING),
            alarmVolumePct = getVolumePct(AudioManager.STREAM_ALARM),
            notifVolumePct = getVolumePct(AudioManager.STREAM_NOTIFICATION),
            brightnessPct = getBrightnessPct(),
            isWifiEnabled = isWifiEnabled(),
            isBluetoothEnabled = isBluetoothEnabled(),
            isPowerSaveMode = isPowerSaveMode(),
            ringerMode = ringerLabel
        )
    }

    fun refreshState() { _state.value = refresh() }
}
