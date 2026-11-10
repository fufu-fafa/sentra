package com.sentra.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.sentra.data.AppMode
import com.sentra.ui.theme.AppTheme
import com.sentra.ui.theme.Tokens

private enum class ChildTab(val label: String, val icon: ImageVector) {
    Home("Beranda", Icons.Filled.Home),
    Routes("Rute", Icons.Filled.LocationOn),
    Demo("Demo", Icons.Filled.PlayArrow),
    Settings("Setelan", Icons.Filled.Settings),
}

@Composable
fun App(vm: AppViewModel, askPermissions: (AppMode) -> Unit, enableBluetooth: () -> Unit) {
    val settings by vm.settings.collectAsState()
    val mode = settings.mode
    // Ask once the phone's role is known: a parent's phone is never asked for Bluetooth or location.
    LaunchedEffect(mode) { if (mode != null) askPermissions(mode) }
    when (mode) {
        null -> ModeScreen(vm::setMode)
        AppMode.PARENT -> CareScreen(vm)
        AppMode.CHILD -> ChildApp(vm, enableBluetooth)
    }
}

@Composable
private fun ChildApp(vm: AppViewModel, enableBluetooth: () -> Unit) {
    var tab by rememberSaveable { mutableStateOf(ChildTab.Home) }
    Scaffold(
        containerColor = Tokens.Bg,
        bottomBar = {
            BottomTabs(ChildTab.entries.map { Triple(it.label, it.icon, tab == it) }) { tab = ChildTab.entries[it] }
        },
    ) { pad ->
        Column(Modifier.padding(pad).fillMaxSize()) {
            when (tab) {
                ChildTab.Home -> HomeScreen(vm, enableBluetooth) { tab = ChildTab.Routes }
                ChildTab.Routes -> RouteScreen(vm)
                ChildTab.Demo -> DemoScreen(vm)
                ChildTab.Settings -> SettingsScreen(vm)
            }
        }
    }
}

/** Bottom bar with text under every icon, so nothing depends on recognising an icon (§9.7). */
@Composable
fun BottomTabs(tabs: List<Triple<String, ImageVector, Boolean>>, onSelect: (Int) -> Unit) {
    NavigationBar(containerColor = Tokens.Card) {
        tabs.forEachIndexed { i, (label, icon, selected) ->
            NavigationBarItem(
                selected = selected,
                onClick = { onSelect(i) },
                icon = { Icon(icon, contentDescription = null) },
                label = { Text(label) },
                colors = NavigationBarItemDefaults.colors(
                    selectedIconColor = Tokens.Accent,
                    selectedTextColor = Tokens.Accent,
                    indicatorColor = Tokens.Border,
                    unselectedIconColor = Tokens.Muted,
                    unselectedTextColor = Tokens.Muted,
                ),
            )
        }
    }
}

val HistoryIcon: ImageVector get() = Icons.AutoMirrored.Filled.List

/** First launch: is this the child's phone or a parent's? One APK, two modes. */
@Composable
fun ModeScreen(onChoose: (AppMode) -> Unit) {
    Scaffold(containerColor = Tokens.Bg) { pad ->
        Column(Modifier.padding(pad).fillMaxSize()) {
            ScreenTitle("Selamat datang", "HP ini untuk siapa? Bisa diganti nanti di Setelan.")
            Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                ModeCard(
                    "Anak",
                    "HP ini dibawa anak, tersambung ke sleeve dan memandu rute.",
                ) { onChoose(AppMode.CHILD) }
                ModeCard(
                    "Orang tua",
                    "HP ini menampilkan lokasi anak dan berbunyi bila ada masalah di jalan.",
                ) { onChoose(AppMode.PARENT) }
            }
        }
    }
}

@Composable
private fun ModeCard(title: String, body: String, onClick: () -> Unit) {
    Card(Modifier.clickable(onClickLabel = "Pilih mode $title", onClick = onClick)) {
        Text(title, style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.height(4.dp))
        Text(body, color = AppTheme.extra.muted)
    }
}
