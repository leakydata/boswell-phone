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
import androidx.compose.runtime.setValue
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

    androidx.compose.runtime.LaunchedEffect(startTab) {
        when {
            startTab == "ask" || startTab == "todo" -> nav.navigate(startTab)
            startTab?.startsWith("conversation:") == true -> startTab.split(":").let { p ->
                val id = p.getOrNull(1)?.toLongOrNull() ?: return@let
                nav.navigate("conversation/$id?line=${p.getOrNull(2)?.toLongOrNull() ?: -1}&play=true")
            }
        }
    }
    fun openConversation(id: Long, line: Long? = null, play: Boolean = false) = nav.navigate("conversation/$id?line=${line ?: -1}&play=$play")
    fun go(tab: String) = nav.navigate(tab) {
        popUpTo(nav.graph.findStartDestination().id) { saveState = true }
        launchSingleTop = true
        restoreState = true
    }

    val ctx = androidx.compose.ui.platform.LocalContext.current
    val activity = ctx as android.app.Activity
    val pairLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.StartIntentSenderForResult()) { device.refreshSync(activity) }
    val pair: () -> Unit = {
        ui.savedAddress?.let { address ->
            net.boswell.phone.sync.OmiCompanion.pair(activity, address,
                launch = { sender -> pairLauncher.launch(androidx.activity.result.IntentSenderRequest.Builder(sender).build()) },
                done = { err -> if (err != null) net.boswell.phone.capture.CaptureRepository.log("pairing: $err"); device.refreshSync(activity) })
        }
    }
    var setupDone by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(net.boswell.phone.setup.Setup.done(ctx)) }
    var learningVoice by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }
    if (!setupDone || learningVoice) {
        net.boswell.phone.setup.SetupScreen(device, onPair = pair, voiceOnly = learningVoice && setupDone,
            onFinish = { setupDone = true; learningVoice = false; archive.refresh(force = true) })
        return
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
                TodayScreen(archive, pad, onOpen = { openConversation(it) }, onSearch = { nav.navigate("search") }, onDevice = { go("device") }, onTodos = { go("todo") },
                    onRecordings = { d -> nav.navigate("recordings/${d.toEpochDay()}") })
            }
            composable("people") {
                PeopleScreen(archive, pad, onPerson = { nav.navigate("person/$it") }, onOpenConversation = { openConversation(it) },
                    onLearnVoice = { learningVoice = true }, onReview = { nav.navigate("review") })
            }
            composable("review") { ReviewScreen(archive, onBack = { nav.popBackStack() }) }
            composable("ask") {
                AskScreen(pad, onSetup = { go("device") }, onUsage = { nav.navigate("usage") },
                    onMoment = { conv, line -> openConversation(conv, line, play = true) }, onLogs = { nav.navigate("logs") })
            }
            composable("logs") { LogsScreen(onBack = { nav.popBackStack() }) }
            composable("todo") { TodoScreen(pad) }
            composable("triggers") { TriggersScreen(onBack = { nav.popBackStack() }) }
            composable("usage") { UsageScreen(onBack = { nav.popBackStack() }) }
            composable("compare?clips={clips}", arguments = listOf(navArgument("clips") { type = NavType.StringType; nullable = true; defaultValue = null })) { e ->
                CompareScreen(e.arguments?.getString("clips")?.split(",")?.filter { it.isNotBlank() }, onBack = { nav.popBackStack() })
            }
            composable("recordings/{day}", arguments = listOf(navArgument("day") { type = NavType.LongType })) { e ->
                RecordingsScreen(archive, java.time.LocalDate.ofEpochDay(e.arguments!!.getLong("day")), onBack = { nav.popBackStack() },
                    onOpen = { openConversation(it) }, onCompare = { nav.navigate("compare?clips=${it.joinToString(",")}") })
            }
            composable("device") {
                DeviceScreen(ui, cap, device, pad, onTriggers = { nav.navigate("triggers") }, onUsage = { nav.navigate("usage") },
                    onCompare = { nav.navigate("compare") },
                    onPair = pair, onSetup = { net.boswell.phone.setup.Setup.setDone(ctx, false); setupDone = false })
            }
            composable(
                "conversation/{id}?line={line}&play={play}",
                arguments = listOf(navArgument("id") { type = NavType.LongType }, navArgument("line") { type = NavType.LongType; defaultValue = -1L },
                    navArgument("play") { type = NavType.BoolType; defaultValue = false }),
            ) { e ->
                val line = e.arguments!!.getLong("line").takeIf { it >= 0 }
                ConversationScreen(archive, e.arguments!!.getLong("id"), line, onBack = { nav.popBackStack() }, onPerson = { nav.navigate("person/$it") },
                    playFocus = e.arguments!!.getBoolean("play"))
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
