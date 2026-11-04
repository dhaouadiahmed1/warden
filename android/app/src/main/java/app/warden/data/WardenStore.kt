package app.warden.data

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Single in-process store shared by the UI and the VPN service.
 * Everything stays on the phone: rules and logs are kept in
 * SharedPreferences (rules only) and in memory (logs), never uploaded.
 */
object WardenStore {

    private val prefsName = "warden_prefs"
    private val keyBlocked = "blocked_packages"
    private val keyDns = "dns_provider"
    private val keyBlockTrackers = "block_trackers"

    private val _isProtectionOn = MutableStateFlow(false)
    val isProtectionOn: StateFlow<Boolean> = _isProtectionOn.asStateFlow()

    private val _apps = MutableStateFlow<List<AppRule>>(emptyList())
    val apps: StateFlow<List<AppRule>> = _apps.asStateFlow()

    private val _logs = MutableStateFlow<List<ConnectionLog>>(emptyList())
    val logs: StateFlow<List<ConnectionLog>> = _logs.asStateFlow()

    private val _stats = MutableStateFlow(Stats())
    val stats: StateFlow<Stats> = _stats.asStateFlow()

    private val _dns = MutableStateFlow(DnsProvider.CLOUDFLARE)
    val dns: StateFlow<DnsProvider> = _dns.asStateFlow()

    private val _blockTrackers = MutableStateFlow(true)
    val blockTrackers: StateFlow<Boolean> = _blockTrackers.asStateFlow()

    fun setProtectionOn(on: Boolean) {
        _isProtectionOn.value = on
    }

    fun loadApps(context: Context) {
        val prefs = context.getSharedPreferences(prefsName, Context.MODE_PRIVATE)
        val blocked = prefs.getStringSet(keyBlocked, emptySet()) ?: emptySet()
        val dnsName = prefs.getString(keyDns, DnsProvider.CLOUDFLARE.name)
        _dns.value = runCatching { DnsProvider.valueOf(dnsName!!) }
            .getOrDefault(DnsProvider.CLOUDFLARE)
        _blockTrackers.value = prefs.getBoolean(keyBlockTrackers, true)

        val pm = context.packageManager
        val installed = pm.getInstalledApplications(PackageManager.GET_META_DATA)
        val rules = installed
            .map { info ->
                AppRule(
                    packageName = info.packageName,
                    appName = pm.getApplicationLabel(info).toString(),
                    isSystemApp = (info.flags and ApplicationInfo.FLAG_SYSTEM) != 0,
                    blocked = blocked.contains(info.packageName)
                )
            }
            .sortedWith(compareBy({ it.isSystemApp }, { it.appName.lowercase() }))
        _apps.value = rules
        refreshStats()
    }

    fun toggleBlock(context: Context, packageName: String) {
        val updated = _apps.value.map {
            if (it.packageName == packageName) it.copy(blocked = !it.blocked) else it
        }
        _apps.value = updated
        val blocked = updated.filter { it.blocked }.map { it.packageName }.toSet()
        context.getSharedPreferences(prefsName, Context.MODE_PRIVATE)
            .edit().putStringSet(keyBlocked, blocked).apply()
        refreshStats()
    }

    fun setDns(context: Context, provider: DnsProvider) {
        _dns.value = provider
        context.getSharedPreferences(prefsName, Context.MODE_PRIVATE)
            .edit().putString(keyDns, provider.name).apply()
    }

    fun setBlockTrackers(context: Context, on: Boolean) {
        _blockTrackers.value = on
        context.getSharedPreferences(prefsName, Context.MODE_PRIVATE)
            .edit().putBoolean(keyBlockTrackers, on).apply()
    }

    fun isBlocked(packageName: String?): Boolean {
        if (packageName == null) return false
        return _apps.value.firstOrNull { it.packageName == packageName }?.blocked == true
    }

    fun addLog(appName: String, destination: String, protocol: String, action: LogAction) {
        val entry = ConnectionLog(
            id = System.currentTimeMillis(),
            timeMillis = System.currentTimeMillis(),
            appName = appName,
            destination = destination,
            protocol = protocol,
            action = action
        )
        val kept = (_logs.value + entry).takeLast(200)
        _logs.value = kept
        val current = _stats.value
        _stats.value = current.copy(
            blockedToday = current.blockedToday + if (action == LogAction.BLOCKED) 1 else 0,
            connectionsToday = current.connectionsToday + 1,
            domainsSeen = kept.map { it.destination }.toSet().size
        )
    }

    private fun refreshStats() {
        _stats.value = _stats.value.copy(
            appsWithRules = _apps.value.count { it.blocked }
        )
    }

    /** Demo entries so the screens are not empty before the VPN ever runs. */
    fun seedDemoLogsIfEmpty() {
        if (_logs.value.isNotEmpty()) return
        val now = System.currentTimeMillis()
        val demo = listOf(
            ConnectionLog(1, now - 120_000, "Chrome", "142.250.72.14", "TCP", LogAction.SEEN),
            ConnectionLog(2, now - 90_000, "Instagram", "157.240.1.35", "TCP", LogAction.SEEN),
            ConnectionLog(3, now - 60_000, "WhatsApp", "157.240.22.53", "UDP", LogAction.SEEN)
        )
        _logs.value = demo
        _stats.value = _stats.value.copy(
            connectionsToday = demo.size,
            domainsSeen = demo.map { it.destination }.toSet().size
        )
    }
}
