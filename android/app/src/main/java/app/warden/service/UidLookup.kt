package app.warden.service

import java.io.File

/**
 * Maps a local source port to the owning app UID by reading the kernel
 * socket tables, the same technique used by other no-root firewalls.
 * Returns null when the socket is already closed or not visible.
 */
object UidLookup {

    fun findUid(sourcePort: Int): Int? {
        if (sourcePort == 0) return null
        val hexPort = sourcePort.toString(16).uppercase().padStart(4, '0')
        for (path in listOf("/proc/net/tcp", "/proc/net/tcp6", "/proc/net/udp", "/proc/net/udp6")) {
            val file = File(path)
            if (!file.canRead()) continue
            val lines = runCatching { file.readLines() }.getOrNull() ?: continue
            for (line in lines.drop(1)) {
                val parts = line.trim().split(Regex("\\s+"))
                // Columns: sl local_address rem_address st ... uid ...
                if (parts.size < 8) continue
                val local = parts[1]
                val port = local.substringAfter(":", "")
                if (port == hexPort) {
                    return parts[7].toIntOrNull()
                }
            }
        }
        return null
    }
}
