package app.warden.service

/** Minimal IPv4 header parsing for the TUN interface. No external library. */
object PacketParser {

    data class Packet(
        val destinationIp: String,
        val protocol: String,
        val sourcePort: Int
    )

    fun parse(data: ByteArray, length: Int): Packet? {
        if (length < 20) return null
        val version = (data[0].toInt() shr 4) and 0xF
        if (version != 4) return null // v0.1 handles IPv4 only; IPv6 is a v0.2 item.
        val ihl = (data[0].toInt() and 0xF) * 4
        if (length < ihl + 4) return null
        val protocolNumber = data[9].toInt() and 0xFF
        val protocol = when (protocolNumber) {
            6 -> "TCP"
            17 -> "UDP"
            1 -> "ICMP"
            else -> "IP-$protocolNumber"
        }
        val dest = (16..19).joinToString(".") { (data[it].toInt() and 0xFF).toString() }
        val sourcePort = if (protocolNumber == 6 || protocolNumber == 17) {
            ((data[ihl].toInt() and 0xFF) shl 8) or (data[ihl + 1].toInt() and 0xFF)
        } else 0
        return Packet(dest, protocol, sourcePort)
    }
}
