package net.boswell.phone.ui

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument

private data class Tab(val route: String, val label: String, val icon: ImageVector)

private val tabs = listOf(
    Tab("today", "Today", Icons.Filled.DateRange),
    Tab("ask", "Ask", Icons.AutoMirrored.Filled.Send),
    Tab("todo", "To-do", Icons.Filled.CheckCircle),
    Tab("people", "People", Icons.Filled.Person),
    Tab("device", "Device", Icons.Filled.Settings),
)

@Composable
fun BoswellApp(device: MainViewModel, startTab: String? = null) {
    val nav = rememberNavController()
    val archive: ArchiveViewModel = viewModel()
    val ui by device.ui.collectAsStateWithLifecycle()
    val cap by device.capture.collectAsStateWithLifecycle()
    val entry by nav.currentBackStackEntryAsState()
    val route = entry?.destination?.route
    val onTab = tabs.any { it.route == route }

    androidx.compose.runtime.LaunchedEffect(startTab) { if (startTab == "ask" || startTab == "todo") nav.navigate(startTab) }
    fun openConversation(id: Long, line: Long? = null) = nav.navigate("conversation/$id?line=${line ?: -1}")
    fun go(tab: String) = nav.navigate(tab) {
        popUpTo(nav.graph.findStartDestination().id) { saveState = true }
        launchSingleTop = true
        restoreState = true
    }

    Scaffold(bottomBar = {
        if (onTab) NavigationBar {
            for (t in tabs) NavigationBarItem(
                selected = route == t.route,
                onClick = { go(t.route); archive.refresh() },
                icon = { Icon(t.icon, null) },
                label = { Text(t.label) },
            )
        }
    }) { pad ->
        NavHost(nav, startDestination = "today") {
            composable("today") {
                TodayScreen(archive, pad, onOpen = { openConversation(it) }, onSearch = { nav.navigate("search") }, onDevice = { go("device") }, onTodos = { go("todo") })
            }
            composable("people") {
                PeopleScreen(archive, pad, onPerson = { nav.navigate("person/$it") }, onOpenConversation = { openConversation(it) })
            }
            composable("ask") { AskScreen(pad, onSetup = { go("device") }) }
            composable("todo") { TodoScreen(pad) }
            composable("triggers") { TriggersScreen(onBack = { nav.popBackStack() }) }
            composable("device") {
                val activity = androidx.compose.ui.platform.LocalContext.current as android.app.Activity
                val launcher = androidx.activity.compose.rememberLauncherForActivityResult(
                    androidx.activity.result.contract.ActivityResultContracts.StartIntentSenderForResult()) { device.refreshSync(activity) }
                DeviceScreen(ui, cap, device, pad, onTriggers = { nav.navigate("triggers") }, onPair = {
                    val address = ui.savedAddress ?: return@DeviceScreen
                    net.boswell.phone.sync.OmiCompanion.pair(activity, address,
                        launch = { sender -> launcher.launch(androidx.activity.result.IntentSenderRequest.Builder(sender).build()) },
                        done = { err -> if (err != null) net.boswell.phone.capture.CaptureRepository.log("pairing: $err"); device.refreshSync(activity) })
                })
            }
            composable(
                "conversation/{id}?line={line}",
                arguments = listOf(navArgument("id") { type = NavType.LongType }, navArgument("line") { type = NavType.LongType; defaultValue = -1L }),
            ) { e ->
                val line = e.arguments!!.getLong("line").takeIf { it >= 0 }
                ConversationScreen(archive, e.arguments!!.getLong("id"), line, onBack = { nav.popBackStack() }, onPerson = { nav.navigate("person/$it") })
            }
            composable("person/{id}", arguments = listOf(navArgument("id") { type = NavType.LongType })) { e ->
                PersonScreen(archive, e.arguments!!.getLong("id"), onBack = { nav.popBackStack() }, onOpen = { openConversation(it) })
            }
            composable("search") {
                SearchScreen(archive, onBack = { nav.popBackStack() }, onOpen = { id, line -> openConversation(id, line) })
            }
        }
    }
}
