package app.warden.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import app.warden.data.DnsProvider
import app.warden.data.WardenStore

class WardenViewModel(application: Application) : AndroidViewModel(application) {

    val isProtectionOn = WardenStore.isProtectionOn
    val apps = WardenStore.apps
    val logs = WardenStore.logs
    val stats = WardenStore.stats
    val dns = WardenStore.dns
    val blockTrackers = WardenStore.blockTrackers

    init {
        WardenStore.loadApps(application)
        WardenStore.seedDemoLogsIfEmpty()
    }

    fun toggleBlock(packageName: String) {
        WardenStore.toggleBlock(getApplication(), packageName)
    }

    fun setDns(provider: DnsProvider) {
        WardenStore.setDns(getApplication(), provider)
    }

    fun setBlockTrackers(on: Boolean) {
        WardenStore.setBlockTrackers(getApplication(), on)
    }
}
