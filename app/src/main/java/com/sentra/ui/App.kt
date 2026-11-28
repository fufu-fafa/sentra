package com.sentra.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.BarChart
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.Map
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.sentra.data.AppMode
import com.sentra.nav.NavState
import com.sentra.ui.theme.AppTheme
import com.sentra.ui.theme.Tokens

private enum class ChildTab(val label: String, val icon: ImageVector) {
    Home("Home", Icons.Rounded.Home),
    Map("Map", Icons.Rounded.Map),
    Status("Status", Icons.Rounded.BarChart),
    Settings("Settings", Icons.Rounded.Settings),
}

/** Pages opened on top of the tabs in Child mode. */
sealed interface ChildPage {
    data object Routes : ChildPage
    data class RouteDetail(val id: Long) : ChildPage
    data object LiveLocation : ChildPage
    data object History : ChildPage
    data object Record : ChildPage
    data object Find : ChildPage
    data object Demo : ChildPage

    companion object {
        fun encode(p: ChildPage): String = when (p) {
            Routes -> "routes"
            is RouteDetail -> "route:${p.id}"
            LiveLocation -> "live"
            History -> "history"
            Record -> "record"
            Find -> "find"
            Demo -> "demo"
        }

        fun decode(s: String): ChildPage? = when {
            s == "routes" -> Routes
            s.startsWith("route:") -> s.removePrefix("route:").toLongOrNull()?.let(::RouteDetail)
            s == "live" -> LiveLocation
            s == "history" -> History
            s == "record" -> Record
            s == "find" -> Find
            s == "demo" -> Demo
            else -> null
        }
    }
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

/** Child mode: four tabs, pages on top of them, and the navigation screen over everything during a trip. */
@Composable
private fun ChildApp(vm: AppViewModel, enableBluetooth: () -> Unit) {
    var tab by rememberSaveable { mutableStateOf(ChildTab.Home) }
    // The page stack, saved as text so it survives rotation.
    var stackText by rememberSaveable { mutableStateOf("") }
    val stack = stackText.split('|').filter { it.isNotEmpty() }.mapNotNull(ChildPage::decode)
    fun open(p: ChildPage) { stackText = (stack + p).joinToString("|", transform = ChildPage::encode) }
    fun back() { stackText = stack.dropLast(1).joinToString("|", transform = ChildPage::encode) }
    fun home() { stackText = ""; tab = ChildTab.Home }

    val nav by vm.nav.collectAsState()
    // "Back" on the navigation screen hides it while the trip goes on; a new trip shows it again.
    var navHidden by rememberSaveable(nav.active) { mutableStateOf(false) }
    var arrivalSeen by rememberSaveable(nav.state == NavState.ARRIVED) { mutableStateOf(false) }
    val showNav = nav.active && !navHidden
    val showArrival = !nav.active && nav.state == NavState.ARRIVED && !arrivalSeen

    when {
        showArrival -> {
            BackHandler { arrivalSeen = true; home() }
            ArrivedScreen(vm) { arrivalSeen = true; home() }
            return
        }
        showNav -> {
            BackHandler { navHidden = true }
            NavigationScreen(vm) { navHidden = true }
            return
        }
    }

    val page = stack.lastOrNull()
    if (page != null) {
        BackHandler { back() }
        PageBackground {
            when (page) {
                ChildPage.Routes -> RoutePicker(vm, onBack = ::back, onOpen = { open(ChildPage.RouteDetail(it)) }, onRecord = { open(ChildPage.Record) }, onFind = { open(ChildPage.Find) }, onHistory = { open(ChildPage.History) })
                is ChildPage.RouteDetail -> RouteDetail(vm, page.id, onBack = ::back, onStarted = { stackText = "" })
                ChildPage.LiveLocation -> LiveLocationScreen(vm, onBack = ::back, onResumeNav = { navHidden = false })
                ChildPage.History -> RouteHistoryScreen(vm, onBack = ::back)
                ChildPage.Record -> RecordRoute(vm, onClose = ::back)
                ChildPage.Find -> FindRoute(vm, onClose = ::back)
                ChildPage.Demo -> DemoScreen(vm, onBack = ::back)
            }
        }
        return
    }

    Scaffold(
        containerColor = Tokens.Bg,
        contentWindowInsets = WindowInsets(0),
        bottomBar = {
            BottomTabs(ChildTab.entries.map { Triple(it.label, it.icon, tab == it) }) { tab = ChildTab.entries[it] }
        },
    ) { pad ->
        PageBackground(Modifier.padding(pad)) {
            when (tab) {
                ChildTab.Home -> HomeScreen(
                    vm, enableBluetooth,
                    openRoutes = { open(ChildPage.Routes) },
                    openLive = { open(ChildPage.LiveLocation) },
                    openSettings = { tab = ChildTab.Settings },
                    resumeNav = { navHidden = false },
                )
                ChildTab.Map -> LiveLocationScreen(vm, onBack = null, onResumeNav = { navHidden = false })
                ChildTab.Status -> DeviceStatusScreen(vm, enableBluetooth, openDemo = { open(ChildPage.Demo) })
                ChildTab.Settings -> SettingsScreen(vm)
            }
        }
    }
}

/** First launch: is this the child's phone or a parent's? One APK, two modes. */
@Composable
fun ModeScreen(onChoose: (AppMode) -> Unit) {
    PageBackground {
        Column(
            Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(Modifier.height(40.dp))
            SentraLogo(150.dp)
            Spacer(Modifier.height(28.dp))
            Text(
                "Who is this phone for?",
                style = MaterialTheme.typography.titleLarge, color = Tokens.Text,
            )
            Text(
                "You can change this later in Settings.",
                style = MaterialTheme.typography.bodyMedium, color = AppTheme.extra.muted, textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(20.dp))
            Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                ModeCard(
                    care = false,
                    title = "SENTRA",
                    who = "For the child",
                    body = "Connects to the sleeve and guides routes with vibration and voice.",
                ) { onChoose(AppMode.CHILD) }
                ModeCard(
                    care = true,
                    title = "SENTRA Care",
                    who = "For parents / teachers",
                    body = "Shows the child's location and rings if something goes wrong on the way.",
                ) { onChoose(AppMode.PARENT) }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun ModeCard(care: Boolean, title: String, who: String, body: String, onClick: () -> Unit) {
    Card(
        onClick = onClick,
        onClickLabel = "Choose $title",
        background = if (care) Tokens.PinkSoft else Tokens.Card,
        border = if (care) Tokens.PinkLight.copy(alpha = .5f) else Tokens.Border,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            SentraMark(64.dp, care)
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleLarge, color = if (care) Tokens.Pink else Tokens.Brand)
                Text(who, style = MaterialTheme.typography.labelLarge, color = Tokens.Text)
                Spacer(Modifier.height(2.dp))
                Text(body, color = AppTheme.extra.muted, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}
