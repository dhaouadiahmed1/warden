package app.warden.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Intent
import android.net.VpnService
import android.os.ParcelFileDescriptor
import android.util.Log
import app.warden.R
import app.warden.data.LogAction
import app.warden.data.WardenStore
import java.io.FileInputStream
import java.util.concurrent.atomic.AtomicBoolean

/**
 * No-root firewall engine, v0.1.
 *
 * How it works: VpnService creates a local TUN interface. All traffic is
 * routed through it, on this phone only. Each packet is parsed, its owning
 * app is looked up by source port, and the app's rule is checked.
 * Blocked packets are dropped. Allowed packets are logged as SEEN.
 *
 * IMPORTANT v0.1 LIMIT (read README.md before daily use):
 * Packet forwarding back to the internet (the TCP/UDP proxy) is NOT
 * finished yet. While Protection is ON in this starter, allowed traffic
 * is logged but not forwarded, so apps will appear offline until you
 * turn Protection OFF. v0.2 is exactly that forwarder. Do not rely on
 * v0.1 as your only protection, and test it on a spare device first.
 */
class WardenVpnService : VpnService() {

    companion object {
        const val ACTION_START = "app.warden.START"
        const val ACTION_STOP = "app.warden.STOP"
        private const val CHANNEL_ID = "warden_protection"
        private const val NOTIFICATION_ID = 1
    }

    private var tun: ParcelFileDescriptor? = null
    private val running = AtomicBoolean(false)

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopTunnel()
                stopSelf()
                return START_NOT_STICKY
            }
            else -> startTunnel()
        }
        return START_STICKY
    }

    private fun startTunnel() {
        if (running.getAndSet(true)) return
        createChannel()
        val notification: Notification = Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("Warden protection is on")
            .setContentText("Traffic stays on this phone. Turn it off in the app to restore internet (v0.1 test build).")
            .setSmallIcon(R.drawable.ic_shield)
            .build()
        startForeground(NOTIFICATION_ID, notification)

        val builder = Builder()
            .setSession("Warden")
            .addAddress("10.0.0.2", 32)
            .addRoute("0.0.0.0", 0)
            .addDnsServer(WardenStore.dns.value.address)
            // Keep Warden's own traffic out of its own tunnel.
            .addDisallowedApplication(packageName)
        tun = builder.establish()
        WardenStore.setProtectionOn(true)

        Thread({
            val fd = tun ?: return@Thread
            val input = FileInputStream(fd.fileDescriptor)
            val buffer = ByteArray(32767)
            try {
                while (running.get()) {
                    val length = input.read(buffer)
                    if (length <= 0) continue
                    val packet = PacketParser.parse(buffer, length) ?: continue
                    val uid = UidLookup.findUid(packet.sourcePort)
                    val packages = if (uid != null) {
                        packageManager.getPackagesForUid(uid)
                    } else null
                    val packageName = packages?.firstOrNull()
                    val appName = packageName?.let {
                        runCatching {
                            packageManager.getApplicationLabel(
                                packageManager.getApplicationInfo(it, 0)
                            ).toString()
                        }.getOrNull()
                    } ?: "Unknown app"
                    val blocked = WardenStore.isBlocked(packageName)
                    WardenStore.addLog(
                        appName = appName,
                        destination = packet.destinationIp,
                        protocol = packet.protocol,
                        action = if (blocked) LogAction.BLOCKED else LogAction.SEEN
                    )
                    // Blocked: drop (do nothing). Allowed: v0.1 does NOT
                    // forward yet -- see the class comment and README.md.
                    // v0.2 adds the forwarder here, before this point.
                }
            } catch (e: Exception) {
                Log.w("WardenVpn", "Tunnel loop ended", e)
            }
        }, "warden-tun").start()
    }

    private fun stopTunnel() {
        running.set(false)
        runCatching { tun?.close() }
        tun = null
        WardenStore.setProtectionOn(false)
        stopForeground(STOP_FOREGROUND_REMOVE)
    }

    private fun createChannel() {
        val manager = getSystemService(NotificationManager::class.java)
        if (manager.getNotificationChannel(CHANNEL_ID) == null) {
            manager.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    "Warden protection",
                    NotificationManager.IMPORTANCE_LOW
                )
            )
        }
    }

    override fun onDestroy() {
        stopTunnel()
        super.onDestroy()
    }
}
