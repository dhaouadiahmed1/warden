package app.warden.service

/**
 * Minimal IPv4 / TCP / UDP parsing and packet building for the TUN interface.
 *
 * v0.2: this file is the only place that knows about wire formats. The
 * forwarder works with [Packet] objects and asks this file to build
 * response packets, so checksum and header bugs live in one place.
 *
 * Limits, kept on purpose for a starter:
 * - IPv4 only. IPv6 packets are dropped by the caller.
 * - No IP options handling beyond reading IHL; packets with options are
 *   parsed using their real header length, but options are not copied back.
 * - TCP out-of-order segments are not reassembled; see Forwarder.kt.
 */

object PacketParser {

    const val PROTO_TCP = 6
    const val PROTO_UDP = 17
    const val PROTO_ICMP = 1

    const val TCP_FIN = 0x01
    const val TCP_SYN = 0x02
    const val TCP_RST = 0x04
    const val TCP_PSH = 0x08
    const val TCP_ACK = 0x10

    data class Packet(
        val sourceIp: String,
        val destinationIp: String,
        val sourcePort: Int,
        val destinationPort: Int,
        val protocol: Int,
        /** "TCP", "UDP", "ICMP" or "IP-<n>", for the log UI. */
        val protocolName: String,
        // TCP only; 0 for UDP/ICMP.
        val seq: Long,
        val ack: Long,
        val flags: Int,
        /** Payload bytes, copied out of the original buffer. */
        val payload: ByteArray,
        /** Offset of the transport header inside the original packet. */
        val transportOffset: Int
    )

    fun ipToBytes(ip: String): ByteArray {
        val parts = ip.split(".")
        require(parts.size == 4) { "Not an IPv4 address: $ip" }
        return ByteArray(4) { i -> parts[i].toInt().toByte() }
    }

    fun bytesToIp(data: ByteArray, offset: Int): String {
        return (0..3).joinToString(".") { (data[offset + it].toInt() and 0xFF).toString() }
    }

    fun parse(data: ByteArray, length: Int): Packet? {
        if (length < 20) return null
        val version = (data[0].toInt() shr 4) and 0xF
        if (version != 4) return null
        val ihl = (data[0].toInt() and 0xF) * 4
        if (ihl < 20 || length < ihl + 4) return null
        val protocol = data[9].toInt() and 0xFF
        val sourceIp = bytesToIp(data, 12)
        val destinationIp = bytesToIp(data, 16)
        val protocolName = when (protocol) {
            PROTO_TCP -> "TCP"
            PROTO_UDP -> "UDP"
            PROTO_ICMP -> "ICMP"
            else -> "IP-$protocol"
        }
        return when (protocol) {
            PROTO_TCP -> {
                if (length < ihl + 20) return null
                val srcPort = u16(data, ihl)
                val dstPort = u16(data, ihl + 2)
                val seq = u32(data, ihl + 4)
                val ack = u32(data, ihl + 8)
                val dataOffset = ((data[ihl + 12].toInt() shr 4) and 0xF) * 4
                if (dataOffset < 20 || length < ihl + dataOffset) return null
                val flags = data[ihl + 13].toInt() and 0xFF
                val payload = data.copyOfRange(ihl + dataOffset, length)
                Packet(sourceIp, destinationIp, srcPort, dstPort, protocol,
                    protocolName, seq, ack, flags, payload, ihl)
            }
            PROTO_UDP -> {
                if (length < ihl + 8) return null
                val srcPort = u16(data, ihl)
                val dstPort = u16(data, ihl + 2)
                val payload = data.copyOfRange(ihl + 8, length)
                Packet(sourceIp, destinationIp, srcPort, dstPort, protocol,
                    protocolName, 0, 0, 0, payload, ihl)
            }
            else -> {
                // ICMP and other protocols: no ports, no payload forwarding.
                Packet(sourceIp, destinationIp, 0, 0, protocol,
                    protocolName, 0, 0, 0, ByteArray(0), ihl)
            }
        }
    }

    /** Build a complete IPv4 packet carrying a TCP segment. */
    fun buildTcpPacket(
        sourceIp: String, destinationIp: String,
        sourcePort: Int, destinationPort: Int,
        seq: Long, ack: Long, flags: Int, payload: ByteArray
    ): ByteArray {
        val tcpLen = 20 + payload.size
        val tcp = ByteArray(tcpLen)
        put16(tcp, 0, sourcePort)
        put16(tcp, 2, destinationPort)
        put32(tcp, 4, seq)
        put32(tcp, 8, ack)
        tcp[12] = 0x50 // data offset 5 (20 bytes), no options
        tcp[13] = flags.toByte()
        put16(tcp, 14, 65535) // window
        put16(tcp, 16, 0) // checksum, filled below
        put16(tcp, 18, 0) // urgent pointer
        payload.copyInto(tcp, 20)
        val tcpChecksum = tcpChecksum(sourceIp, destinationIp, tcp)
        put16(tcp, 16, tcpChecksum)
        return buildIpPacket(sourceIp, destinationIp, PROTO_TCP, tcp)
    }

    /** Build a complete IPv4 packet carrying a UDP datagram. */
    fun buildUdpPacket(
        sourceIp: String, destinationIp: String,
        sourcePort: Int, destinationPort: Int, payload: ByteArray
    ): ByteArray {
        val udpLen = 8 + payload.size
        val udp = ByteArray(udpLen)
        put16(udp, 0, sourcePort)
        put16(udp, 2, destinationPort)
        put16(udp, 4, udpLen)
        put16(udp, 6, 0) // UDP checksum 0 = "none" for IPv4; accepted by stacks.
        payload.copyInto(udp, 8)
        return buildIpPacket(sourceIp, destinationIp, PROTO_UDP, udp)
    }

    private fun buildIpPacket(
        sourceIp: String, destinationIp: String, protocol: Int, transport: ByteArray
    ): ByteArray {
        val totalLen = 20 + transport.size
        val ip = ByteArray(totalLen)
        ip[0] = 0x45 // version 4, IHL 5
        ip[1] = 0
        put16(ip, 2, totalLen)
        put16(ip, 4, (System.currentTimeMillis() % 65535).toInt())
        put16(ip, 6, 0x4000) // DF flag, no fragmentation
        ip[8] = 64 // TTL
        ip[9] = protocol.toByte()
        put16(ip, 10, 0) // checksum, filled below
        ipToBytes(sourceIp).copyInto(ip, 12)
        ipToBytes(destinationIp).copyInto(ip, 16)
        put16(ip, 10, checksum(ip, 0, 20))
        transport.copyInto(ip, 20)
        return ip
    }

    private fun tcpChecksum(sourceIp: String, destinationIp: String, tcp: ByteArray): Int {
        // Pseudo-header: src, dst, zero, protocol, TCP length, then TCP segment.
        val pseudo = ByteArray(12 + tcp.size)
        ipToBytes(sourceIp).copyInto(pseudo, 0)
        ipToBytes(destinationIp).copyInto(pseudo, 4)
        pseudo[8] = 0
        pseudo[9] = PROTO_TCP.toByte()
        put16(pseudo, 10, tcp.size)
        tcp.copyInto(pseudo, 12)
        return checksum(pseudo, 0, pseudo.size)
    }

    /** Internet checksum (RFC 1071): sum 16-bit words, fold carries, complement. */
    private fun checksum(data: ByteArray, offset: Int, length: Int): Int {
        var sum = 0L
        var i = offset
        val end = offset + length
        while (i + 1 < end) {
            sum += ((data[i].toInt() and 0xFF) shl 8) or (data[i + 1].toInt() and 0xFF)
            i += 2
        }
        if (i < end) sum += (data[i].toInt() and 0xFF) shl 8
        while ((sum shr 16) != 0L) sum = (sum and 0xFFFF) + (sum shr 16)
        return sum.inv().toInt() and 0xFFFF
    }

    fun u16(data: ByteArray, offset: Int): Int =
        ((data[offset].toInt() and 0xFF) shl 8) or (data[offset + 1].toInt() and 0xFF)

    fun u32(data: ByteArray, offset: Int): Long =
        ((data[offset].toLong() and 0xFF) shl 24) or
            ((data[offset + 1].toLong() and 0xFF) shl 16) or
            ((data[offset + 2].toLong() and 0xFF) shl 8) or
            (data[offset + 3].toLong() and 0xFF)

    private fun put16(data: ByteArray, offset: Int, value: Int) {
        data[offset] = ((value shr 8) and 0xFF).toByte()
        data[offset + 1] = (value and 0xFF).toByte()
    }

    private fun put32(data: ByteArray, offset: Int, value: Long) {
        data[offset] = ((value shr 24) and 0xFF).toByte()
        data[offset + 1] = ((value shr 16) and 0xFF).toByte()
        data[offset + 2] = ((value shr 8) and 0xFF).toByte()
        data[offset + 3] = (value and 0xFF).toByte()
    }
}
