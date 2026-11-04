package app.warden.data

/** One installed app, with its firewall rule. Blocked = no internet for it. */
data class AppRule(
    val packageName: String,
    val appName: String,
    val isSystemApp: Boolean,
    val blocked: Boolean = false
)

enum class LogAction { SEEN, BLOCKED }

data class ConnectionLog(
    val id: Long,
    val timeMillis: Long,
    val appName: String,
    val destination: String,
    val protocol: String,
    val action: LogAction
)

data class Stats(
    val blockedToday: Int = 0,
    val connectionsToday: Int = 0,
    val appsWithRules: Int = 0,
    val domainsSeen: Int = 0
)

/** DNS provider used by the VPN tunnel. The tunnel itself is local only. */
enum class DnsProvider(val label: String, val address: String) {
    CLOUDFLARE("Cloudflare", "1.1.1.1"),
    GOOGLE("Google", "8.8.8.8"),
    QUAD9("Quad9", "9.9.9.9")
}
