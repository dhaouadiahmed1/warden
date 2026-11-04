package app.warden.ui.screens

import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import app.warden.data.AppRule
import app.warden.data.DnsProvider
import app.warden.data.LogAction
import app.warden.ui.WardenViewModel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private data class Tab(val route: String, val label: String, val icon: ImageVector)

private val tabs = listOf(
    Tab("home", "Home", Icons.Filled.Home),
    Tab("apps", "Apps", Icons.Filled.List),
    Tab("map", "Map", Icons.Filled.Place),
    Tab("log", "Log", Icons.Filled.List),
    Tab("settings", "Setting", Icons.Filled.Settings)
)

@Composable
fun WardenApp(viewModel: WardenViewModel, onToggleProtection: () -> Unit) {
    val navController = rememberNavController()
    var currentRoute by remember { mutableStateOf("home") }
    Scaffold(
        bottomBar = {
            NavigationBar {
                tabs.forEach { tab ->
                    NavigationBarItem(
                        selected = currentRoute == tab.route,
                        onClick = {
                            currentRoute = tab.route
                            navController.navigate(tab.route) {
                                popUpTo("home")
                                launchSingleTop = true
                            }
                        },
                        icon = { androidx.compose.material3.Icon(tab.icon, contentDescription = tab.label) },
                        label = { Text(tab.label) }
                    )
                }
            }
        }
    ) { padding ->
        NavHost(
            navController = navController,
            startDestination = "home",
            modifier = Modifier.padding(padding)
        ) {
            composable("home") { HomeScreen(viewModel, onToggleProtection) }
            composable("apps") { AppsScreen(viewModel) }
            composable("map") { MapScreen(viewModel) }
            composable("log") { LogScreen(viewModel) }
            composable("settings") { SettingsScreen(viewModel) }
        }
    }
}

@Composable
fun HomeScreen(viewModel: WardenViewModel, onToggleProtection: () -> Unit) {
    val isOn by viewModel.isProtectionOn.collectAsState()
    val stats by viewModel.stats.collectAsState()
    val dns by viewModel.dns.collectAsState()
    var showWarning by remember { mutableStateOf(false) }

    if (showWarning) {
        AlertDialog(
            onDismissRequest = { showWarning = false },
            title = { Text("Test build — read this first") },
            text = {
                Text(
                    "This is v0.1. Turning Protection ON starts the local VPN " +
                        "and logs traffic, but packet forwarding is not finished yet, " +
                        "so apps will appear offline while it is on. Turn it OFF " +
                        "to restore internet. Test on a spare device first. " +
                        "Rules, logs and the dashboard already work with it off."
                )
            },
            confirmButton = {
                TextButton(onClick = { showWarning = false; onToggleProtection() }) {
                    Text("I understand, turn it on")
                }
            },
            dismissButton = {
                TextButton(onClick = { showWarning = false }) { Text("Cancel") }
            }
        )
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Text("Warden", style = MaterialTheme.typography.displayMedium, fontWeight = FontWeight.Bold)
            Text("A free, open-source firewall for Android that needs no root.")
            Spacer(Modifier.height(8.dp))
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primary),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            if (isOn) "Protection is on" else "Protection is off",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onPrimary
                        )
                        Text(
                            if (isOn) "TAP TO PAUSE" else "TAP TO START",
                            color = MaterialTheme.colorScheme.onPrimary
                        )
                    }
                    Switch(
                        checked = isOn,
                        onCheckedChange = {
                            if (it) showWarning = true else onToggleProtection()
                        }
                    )
                }
            }
            Spacer(Modifier.height(8.dp))
            Text("DNS: " + dns.label + "  •  Encrypted in v0.2  •  Everything stays on your phone.")
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                StatCard("BLOCKED", stats.blockedToday.toString(), Modifier.weight(1f))
                StatCard("CONNECT", stats.connectionsToday.toString(), Modifier.weight(1f))
            }
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                StatCard("APPS", stats.appsWithRules.toString(), Modifier.weight(1f))
                StatCard("DOMAINS", stats.domainsSeen.toString(), Modifier.weight(1f))
            }
        }
        item {
            Text("Tools", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Text("Trackers: blocked when a tracker rule matches. Data use and full tracker lists arrive with the v0.2 forwarder.")
        }
    }
}

@Composable
fun StatCard(label: String, value: String, modifier: Modifier = Modifier) {
    Card(modifier = modifier) {
        Column(Modifier.padding(16.dp)) {
            Text(value, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
            Text(label)
        }
    }
}

@Composable
fun AppsScreen(viewModel: WardenViewModel) {
    val apps by viewModel.apps.collectAsState()
    var query by remember { mutableStateOf("") }
    var filter by remember { mutableStateOf("All") }
    val filtered = apps.filter { app ->
        (query.isBlank() || app.appName.contains(query, ignoreCase = true)) &&
            (filter == "All" || (filter == "With rules" && app.blocked) ||
                (filter == "System" && app.isSystemApp))
    }
    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Text(
            apps.size.toString() + " apps can go online. Tap one to block or allow it.",
            style = MaterialTheme.typography.titleMedium
        )
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = query, onValueChange = { query = it },
            label = { Text("Search apps") }, modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("All", "With rules", "System").forEach { name ->
                FilterChip(
                    selected = filter == name,
                    onClick = { filter = name },
                    label = { Text(name) }
                )
            }
        }
        LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(filtered, key = { it.packageName }) { app ->
                AppRow(app, onToggle = { viewModel.toggleBlock(app.packageName) })
            }
        }
    }
}

@Composable
fun AppRow(app: AppRule, onToggle: () -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth().padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Text(app.appName, fontWeight = FontWeight.Bold)
                Text(if (app.blocked) "Blocked" else "Allowed", style = MaterialTheme.typography.bodySmall)
            }
            Switch(checked = !app.blocked, onCheckedChange = { onToggle() })
        }
    }
}

@Composable
fun MapScreen(viewModel: WardenViewModel) {
    val logs by viewModel.logs.collectAsState()
    val destinations = logs.map { it.destination }.distinct()
    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Text("Where it went", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        Text("v0.1 shows destinations from the log. The world map with per-country traffic is a v0.2 screen, once the forwarder resolves domains.")
        Spacer(Modifier.height(12.dp))
        LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(destinations) { dest ->
                Card(Modifier.fillMaxWidth()) { Text(dest, Modifier.padding(12.dp)) }
            }
        }
    }
}

@Composable
fun LogScreen(viewModel: WardenViewModel) {
    val logs by viewModel.logs.collectAsState()
    val format = remember { SimpleDateFormat("HH:mm:ss", Locale.getDefault()) }
    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Text("Activity — today", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(logs.reversed()) { log ->
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp)) {
                        Text(log.appName, fontWeight = FontWeight.Bold)
                        Text(log.destination + "  •  " + log.protocol)
                        Text(
                            format.format(Date(log.timeMillis)) + "  •  " +
                                if (log.action == LogAction.BLOCKED) "Blocked" else "Seen",
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun SettingsScreen(viewModel: WardenViewModel) {
    val dns by viewModel.dns.collectAsState()
    val blockTrackers by viewModel.blockTrackers.collectAsState()
    Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Setting", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        Text("DNS provider (used by the local tunnel):")
        DnsProvider.values().forEach { provider ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(provider.label + " (" + provider.address + ")", Modifier.weight(1f))
                Switch(
                    checked = dns == provider,
                    onCheckedChange = { if (it) viewModel.setDns(provider) }
                )
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Block trackers", fontWeight = FontWeight.Bold)
                Text("Applies to tracker rules in the v0.2 forwarder.")
            }
            Switch(checked = blockTrackers, onCheckedChange = { viewModel.setBlockTrackers(it) })
        }
        Text("Privacy: no account, no ads, no trackers in this app, no analytics. Rules and logs never leave this phone.")
        Text("No root needed. Protection uses Android's VpnService, with the tunnel beginning and ending on this phone.")
    }
}
