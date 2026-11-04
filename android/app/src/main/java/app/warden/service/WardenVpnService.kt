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
import java.io.FileOutputStream
import java.util.concurrent.atomic.AtomicBoolean

/**
 * No-root firewall engine, v0.2.
 *
 * How it works: VpnService creates a local TUN interface. All traffic is
 * routed through it, on this phone only. Each packet is parsed, its owning
 * app is looked up by source port, and the app's rule is checked.
 *
 * v0.2 change: allowed packets are now actually forwarded. See
 * Forwarder.kt: TCP gets a real socket with a handshake, UDP gets a
 * relay socket. Blocked packets are dropped (UDP) or answered with a
 * TCP RST, so a blocked app fails fast instead of hanging.
 *
 * What v0.2 still does not do: IPv6 forwarding, TCP out-of-order
 * reassembly, and retransmission of our own segments. See README.md.
 * Test on a spare device first; do not rely on any starter as your
 * only protection.
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
    private var forwarder: Forwarder? = null

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
            .setContentText("Traffic stays on this phone. Turn it off in the app to restore normal routing.")
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

        val fd = tun ?: run {
            running.set(false)
            return
        }
        val tunOutput = FileOutputStream(fd.fileDescriptor)
        val tunWriteLock = Any()
        val writeToTun: (ByteArray) -> Unit = { bytes ->
            synchronized(tunWriteLock) {
                runCatching { tunOutput.write(bytes); tunOutput.flush() }
            }
        }
        val fwd = Forwarder(
            writeToTun = writeToTun,
            protectSocket = { socket -> protect(socket) },
            protectDatagram = { socket -> protect(socket) },
            onNewFlow = { destinationIp, protocol ->
                // Log once per flow, not once per packet, or the Log
                // screen would be flooded by every data segment.
                WardenStore.addLog(
                    appName = "Connection",
                    destination = destinationIp,
                    protocol = protocol,
                    action = LogAction.SEEN
                )
            }
        )
        forwarder = fwd

        Thread({
            val input = FileInputStream(fd.fileDescriptor)
            val buffer = ByteArray(32767)
            try {
                while (running.get()) {
                    val length = input.read(buffer)
                    if (length <= 0) continue
                    val packet = PacketParser.parse(buffer, length) ?: continue
                    // Only TCP and UDP are forwarded in v0.2.
                    if (packet.protocol != PacketParser.PROTO_TCP &&
                        packet.protocol != PacketParser.PROTO_UDP
                    ) continue
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
                    if (blocked) {
                        WardenStore.addLog(
                            appName = appName,
                            destination = packet.destinationIp,
                            protocol = packet.protocolName,
                            action = LogAction.BLOCKED
                        )
                        // Blocked TCP: RST so the app fails fast instead
                        // of waiting for a timeout. Blocked UDP: drop.
                        if (packet.protocol == PacketParser.PROTO_TCP) {
                            fwd.resetTcp(packet)
                        }
                        continue
                    }
                    // Allowed: forward. Per-flow logging happens in
                    // Forwarder.onNewFlow, so data packets stay quiet
                    // and the Log screen is not flooded.
                    fwd.handle(packet)
                }
            } catch (e: Exception) {
                Log.w("WardenVpn", "Tunnel loop ended", e)
            }
        }, "warden-tun").start()
    }

    private fun stopTunnel() {
        running.set(false)
        forwarder?.close()
        forwarder = null
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
