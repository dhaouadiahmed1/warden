package app.warden.service

import android.util.Log
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap

/**
 * v0.2 packet forwarder: the piece v0.1 was missing.
 *
 * How it works, in one paragraph: the TUN interface gives us the phone's
 * own packets. Instead of answering them from a fake stack, we open a
 * real socket to the real destination — but first we call
 * [VpnService.protect] on that socket, so its traffic bypasses the TUN
 * and cannot loop back into us. Replies from the socket are wrapped
 * back into IP packets and written to the TUN, where Android delivers
 * them to the original app as if they came from the destination.
 *
 * Two transports:
 * - UDP is stateless: one [DatagramSocket] per flow (client:port ->
 *   server:port), with an idle timeout.
 * - TCP needs a state machine, because the client expects a real TCP
 *   peer: we answer SYN with SYN-ACK, ACK data, and relay bytes both
 *   ways, tracking sequence numbers. See [TcpConnection].
 *
 * Honest limits of this starter implementation:
 * - TCP out-of-order segments are dropped (the client retransmits).
 * - No retransmission of our own segments: if a reply packet is lost,
 *   the client retransmits and we re-ACK / re-send from the socket
 *   stream only on new data. In practice this is fine on a phone's
 *   local TUN, where loss is rare.
 * - No TCP window scaling, SACK, or congestion control: window is
 *   advertised as 65535 and payloads are kept small.
 * - IPv6 is not forwarded; those packets are dropped by the caller.
 * - One thread per TCP connection and per UDP flow. That is fine for
 *   a phone's handful of concurrent connections, not for a server.
 */
class Forwarder(
    /** Write one complete IP packet back into the TUN. Must be thread-safe. */
    private val writeToTun: (ByteArray) -> Unit,
    /** Protect a socket so its traffic bypasses the TUN. Returns false on failure. */
    private val protectSocket: (Socket) -> Boolean,
    private val protectDatagram: (DatagramSocket) -> Boolean,
    /** Called once per new connection/flow, for the Log screen. */
    private val onNewFlow: (destinationIp: String, protocol: String) -> Unit
) {

    private val tcpConnections = ConcurrentHashMap<String, TcpConnection>()
    private val udpSessions = ConcurrentHashMap<String, UdpSession>()

    @Volatile
    private var closed = false

    /** Handle one parsed packet from the TUN. Called on the TUN reader thread. */
    fun handle(packet: PacketParser.Packet) {
        if (closed) return
        when (packet.protocol) {
            PacketParser.PROTO_TCP -> handleTcp(packet)
            PacketParser.PROTO_UDP -> handleUdp(packet)
            else -> Unit // ICMP and others: dropped, not forwarded.
        }
    }

    /** Send a TCP RST to the client and forget the connection. Used for blocked apps. */
    fun resetTcp(packet: PacketParser.Packet) {
        val key = tcpKey(packet)
        tcpConnections.remove(key)?.close()
        val rst = PacketParser.buildTcpPacket(
            sourceIp = packet.destinationIp, destinationIp = packet.sourceIp,
            sourcePort = packet.destinationPort, destinationPort = packet.sourcePort,
            seq = 0, ack = packet.seq + 1,
            flags = PacketParser.TCP_RST or PacketParser.TCP_ACK, payload = ByteArray(0)
        )
        runCatching { writeToTun(rst) }
    }

    fun close() {
        closed = true
        tcpConnections.values.forEach { it.close() }
        tcpConnections.clear()
        udpSessions.values.forEach { it.close() }
        udpSessions.clear()
    }

    // ---- TCP ----

    private fun tcpKey(p: PacketParser.Packet) =
        "${p.sourceIp}:${p.sourcePort}->${p.destinationIp}:${p.destinationPort}"

    private fun handleTcp(packet: PacketParser.Packet) {
        val key = tcpKey(packet)
        val flags = packet.flags
        val isSyn = (flags and PacketParser.TCP_SYN) != 0
        val isRst = (flags and PacketParser.TCP_RST) != 0
        val isFin = (flags and PacketParser.TCP_FIN) != 0

        if (isRst) {
            tcpConnections.remove(key)?.close()
            return
        }

        var conn = tcpConnections[key]
        if (conn == null) {
            if (!isSyn) return // Data for an unknown connection: drop.
            conn = TcpConnection(packet)
            tcpConnections[key] = conn
            onNewFlow(packet.destinationIp, "TCP")
            conn.start()
            return
        }
        if (isFin) {
            conn.onClientFin(packet)
            return
        }
        conn.onClientPacket(packet)
    }

    private inner class TcpConnection(private val first: PacketParser.Packet) {
        // Our sequence numbers: start from a non-zero value so a stale
        // segment from an old connection cannot be mistaken for ours.
        @Volatile
        var serverSeq: Long = (System.currentTimeMillis() % 1_000_000) + 1000

        @Volatile
        var clientSeqExpected: Long = first.seq + 1

        @Volatile
        var established = false

        @Volatile
        private var socket: Socket? = null

        @Volatile
        private var closedConn = false

        fun start() {
            // 1. Answer the handshake immediately, so the app does not wait.
            val synAck = PacketParser.buildTcpPacket(
                sourceIp = first.destinationIp, destinationIp = first.sourceIp,
                sourcePort = first.destinationPort, destinationPort = first.sourcePort,
                seq = serverSeq, ack = clientSeqExpected,
                flags = PacketParser.TCP_SYN or PacketParser.TCP_ACK, payload = ByteArray(0)
            )
            serverSeq += 1
            runCatching { writeToTun(synAck) }

            // 2. Connect the real socket in the background. protect() is
            //    the critical call: without it, this socket's traffic
            //    would enter the TUN and loop forever.
            Thread({
                try {
                    val s = Socket()
                    if (!protectSocket(s)) {
                        Log.w("WardenFwd", "protect() failed for TCP ${first.destinationIp}")
                        close()
                        return@Thread
                    }
                    s.soTimeout = 0
                    s.connect(
                        java.net.InetSocketAddress(
                            InetAddress.getByName(first.destinationIp), first.destinationPort
                        ), 10_000
                    )
                    socket = s
                    pumpSocketToTun(s)
                } catch (e: Exception) {
                    Log.w("WardenFwd", "TCP connect failed ${first.destinationIp}:${first.destinationPort}", e)
                    sendRst()
                    close()
                }
            }, "warden-tcp-connect").start()
        }

        /** Client data / ACK packets after the handshake. */
        fun onClientPacket(packet: PacketParser.Packet) {
            val s = socket
            if (packet.payload.isEmpty()) {
                // Pure ACK (e.g. acknowledging our data, or completing the
                // handshake). Mark established on the first such ACK.
                if (!established && packet.ack == serverSeq) established = true
                return
            }
            if (s == null || s.isClosed) return
            // Duplicate retransmission: re-ACK, do not forward twice.
            if (packet.seq < clientSeqExpected) {
                sendAck()
                return
            }
            // Out-of-order: drop; the client will retransmit in order.
            if (packet.seq > clientSeqExpected) return
            try {
                s.getOutputStream().write(packet.payload)
                s.getOutputStream().flush()
                clientSeqExpected += packet.payload.size
                sendAck()
            } catch (e: IOException) {
                Log.w("WardenFwd", "TCP write failed", e)
                sendRst()
                close()
            }
        }

        fun onClientFin(packet: PacketParser.Packet) {
            // FIN consumes one sequence number.
            clientSeqExpected = packet.seq + packet.payload.size + 1
            sendAck()
            // Half-close: we stop sending, tell the client we are done too.
            try {
                socket?.shutdownOutput()
            } catch (_: Exception) {
            }
            val fin = PacketParser.buildTcpPacket(
                sourceIp = first.destinationIp, destinationIp = first.sourceIp,
                sourcePort = first.destinationPort, destinationPort = first.sourcePort,
                seq = serverSeq, ack = clientSeqExpected,
                flags = PacketParser.TCP_FIN or PacketParser.TCP_ACK, payload = ByteArray(0)
            )
            serverSeq += 1
            runCatching { writeToTun(fin) }
            close()
        }

        private fun sendAck() {
            val ack = PacketParser.buildTcpPacket(
                sourceIp = first.destinationIp, destinationIp = first.sourceIp,
                sourcePort = first.destinationPort, destinationPort = first.sourcePort,
                seq = serverSeq, ack = clientSeqExpected,
                flags = PacketParser.TCP_ACK, payload = ByteArray(0)
            )
            runCatching { writeToTun(ack) }
        }

        private fun sendRst() {
            val rst = PacketParser.buildTcpPacket(
                sourceIp = first.destinationIp, destinationIp = first.sourceIp,
                sourcePort = first.destinationPort, destinationPort = first.sourcePort,
                seq = serverSeq, ack = clientSeqExpected,
                flags = PacketParser.TCP_RST or PacketParser.TCP_ACK, payload = ByteArray(0)
            )
            runCatching { writeToTun(rst) }
        }

        private fun pumpSocketToTun(s: Socket) {
            val buffer = ByteArray(16384)
            try {
                val input = s.getInputStream()
                while (!closedConn) {
                    val n = input.read(buffer)
                    if (n < 0) break // Remote closed: send FIN.
                    if (n == 0) continue
                    val data = buffer.copyOf(n)
                    val pkt = PacketParser.buildTcpPacket(
                        sourceIp = first.destinationIp, destinationIp = first.sourceIp,
                        sourcePort = first.destinationPort, destinationPort = first.sourcePort,
                        seq = serverSeq, ack = clientSeqExpected,
                        flags = PacketParser.TCP_PSH or PacketParser.TCP_ACK, payload = data
                    )
                    serverSeq += n
                    writeToTun(pkt)
                }
            } catch (e: Exception) {
                if (!closedConn) Log.w("WardenFwd", "TCP read failed", e)
            } finally {
                // Remote EOF: FIN to the client, then clean up.
                if (!closedConn) {
                    val fin = PacketParser.buildTcpPacket(
                        sourceIp = first.destinationIp, destinationIp = first.sourceIp,
                        sourcePort = first.destinationPort, destinationPort = first.sourcePort,
                        seq = serverSeq, ack = clientSeqExpected,
                        flags = PacketParser.TCP_FIN or PacketParser.TCP_ACK, payload = ByteArray(0)
                    )
                    serverSeq += 1
                    runCatching { writeToTun(fin) }
                }
                close()
            }
        }

        fun close() {
            if (closedConn) return
            closedConn = true
            runCatching { socket?.close() }
            tcpConnections.remove(tcpKey(first))
        }
    }

    // ---- UDP ----

    private fun udpKey(p: PacketParser.Packet) =
        "${p.sourceIp}:${p.sourcePort}->${p.destinationIp}:${p.destinationPort}"

    private fun handleUdp(packet: PacketParser.Packet) {
        val key = udpKey(packet)
        var session = udpSessions[key]
        if (session == null) {
            session = UdpSession(packet)
            udpSessions[key] = session
            onNewFlow(packet.destinationIp, "UDP")
            session.start()
        }
        session.send(packet)
    }

    private inner class UdpSession(private val first: PacketParser.Packet) {
        @Volatile
        private var socket: DatagramSocket? = null

        @Volatile
        private var closedSession = false

        @Volatile
        var lastActivity = System.currentTimeMillis()

        fun start() {
            Thread({
                try {
                    val s = DatagramSocket()
                    if (!protectDatagram(s)) {
                        Log.w("WardenFwd", "protect() failed for UDP ${first.destinationIp}")
                        close()
                        return@Thread
                    }
                    socket = s
                    val buffer = ByteArray(16384)
                    while (!closedSession) {
                        val recv = DatagramPacket(buffer, buffer.size)
                        s.receive(recv)
                        lastActivity = System.currentTimeMillis()
                        val data = recv.data.copyOf(recv.length)
                        val pkt = PacketParser.buildUdpPacket(
                            sourceIp = first.destinationIp, destinationIp = first.sourceIp,
                            sourcePort = first.destinationPort, destinationPort = first.sourcePort,
                            payload = data
                        )
                        writeToTun(pkt)
                    }
                } catch (e: Exception) {
                    if (!closedSession) Log.w("WardenFwd", "UDP session ended", e)
                } finally {
                    close()
                }
            }, "warden-udp").start()

            // Idle cleanup: a UDP flow with no traffic for 2 minutes is gone.
            Thread({
                while (!closedSession) {
                    try {
                        Thread.sleep(30_000)
                    } catch (_: InterruptedException) {
                        break
                    }
                    if (System.currentTimeMillis() - lastActivity > 120_000) close()
                }
            }, "warden-udp-cleanup").start()
        }

        fun send(packet: PacketParser.Packet) {
            val s = socket ?: return
            lastActivity = System.currentTimeMillis()
            try {
                val out = DatagramPacket(
                    packet.payload, packet.payload.size,
                    InetAddress.getByName(packet.destinationIp), packet.destinationPort
                )
                s.send(out)
            } catch (e: Exception) {
                Log.w("WardenFwd", "UDP send failed", e)
            }
        }

        fun close() {
            if (closedSession) return
            closedSession = true
            runCatching { socket?.close() }
            udpSessions.remove(udpKey(first))
        }
    }
}
