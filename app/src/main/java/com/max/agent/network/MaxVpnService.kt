package com.max.agent.network

import android.content.Intent
import android.net.VpnService
import android.os.ParcelFileDescriptor
import java.io.FileInputStream
import java.nio.ByteBuffer
import kotlin.concurrent.thread

/**
 * No-route VpnService. When [ACTION_BLOCK] is received it stands up a TUN
 * interface that owns the device's entire default route (0.0.0.0/0 and ::/0)
 * but is connected to nothing. Every outbound packet from every app is read
 * off the TUN fd and discarded — a true OS-level network blackhole. No data
 * leaves the device while engaged.
 *
 * This is the hard-enforcement backing for [NetworkGuard.recallInternet]. It
 * supersedes the previous in-process policy-flag-only approach.
 */
class MaxVpnService : VpnService() {

    @Volatile private var tunInterface: ParcelFileDescriptor? = null
    @Volatile private var drainThread: Thread? = null
    @Volatile private var running = false

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_BLOCK -> startBlocking()
            ACTION_ALLOW -> stopBlocking()
            else -> stopBlocking()
        }
        return START_STICKY
    }

    private fun startBlocking() {
        if (running) return
        val builder = Builder()
            .setSession("Max Network Recall")
            .addAddress("10.255.255.1", 32)
            .addRoute("0.0.0.0", 0)
            .setBlocking(true)
        runCatching { builder.addAddress("fd00:0:0:0:0:0:0:1", 128); builder.addRoute("::", 0) }

        // Exempt nothing: even Max itself is offline while recalled. This is the
        // strongest interpretation of the owner's "internet recalled" command.
        val tun = runCatching { builder.establish() }.getOrNull()
        if (tun == null) {
            android.util.Log.e(TAG, "Failed to establish TUN interface")
            running = false
            return
        }
        tunInterface = tun
        running = true

        drainThread = thread(name = "max-vpn-drain", isDaemon = true) {
            val input = FileInputStream(tun.fileDescriptor)
            val packet = ByteBuffer.allocate(32_767)
            try {
                while (running) {
                    val len = input.read(packet.array())
                    if (len <= 0) {
                        if (len < 0) break
                        Thread.sleep(5)
                        continue
                    }
                    // Drop the packet on the floor: clear the buffer, read the next one.
                    packet.clear()
                }
            } catch (e: Exception) {
                if (running) android.util.Log.w(TAG, "drain loop ended: ${e.message}")
            } finally {
                runCatching { input.close() }
            }
        }
        android.util.Log.i(TAG, "Network enforcement ENGAGED — all outbound traffic blocked")
    }

    private fun stopBlocking() {
        running = false
        drainThread?.interrupt()
        drainThread = null
        runCatching { tunInterface?.close() }
        tunInterface = null
        android.util.Log.i(TAG, "Network enforcement DISENGAGED")
        stopSelf()
    }

    override fun onRevoke() {
        // The OS or another VPN app revoked us; reflect reality.
        stopBlocking()
        super.onRevoke()
    }

    override fun onDestroy() {
        stopBlocking()
        super.onDestroy()
    }

    companion object {
        private const val TAG = "MaxVpnService"
        const val ACTION_BLOCK = "com.max.agent.network.action.BLOCK"
        const val ACTION_ALLOW = "com.max.agent.network.action.ALLOW"
    }
}
