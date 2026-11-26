package com.example

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.DesktopWindows
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material.icons.outlined.CloudDownload
import androidx.compose.material.icons.outlined.DesktopWindows
import androidx.compose.material.icons.outlined.Layers
import androidx.compose.material.icons.outlined.Terminal
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.example.ui.MainViewModel
import com.example.ui.Routes
import com.example.ui.screens.ContainersScreen
import com.example.ui.screens.DisplayScreen
import com.example.ui.screens.InstallerScreen
import com.example.ui.screens.TerminalScreen
import com.example.ui.theme.ProotBoxTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val viewModel: MainViewModel = viewModel()
            val settings by viewModel.settings.collectAsStateWithLifecycle()

            ProotBoxTheme(darkTheme = settings.darkTheme) {
                ProotBoxApp(viewModel = viewModel)
            }
        }
    }
}

private data class NavItem(
    val route: String,
    val label: String,
    val selectedIcon: ImageVector,
    val unselectedIcon: ImageVector,
    val testTag: String
)

private val navItems = listOf(
    NavItem(
        route = Routes.CONTAINERS,
        label = "Containers",
        selectedIcon = Icons.Filled.Layers,
        unselectedIcon = Icons.Outlined.Layers,
        testTag = "nav_containers"
    ),
    NavItem(
        route = Routes.INSTALLER,
        label = "Instalar",
        selectedIcon = Icons.Filled.CloudDownload,
        unselectedIcon = Icons.Outlined.CloudDownload,
        testTag = "nav_installer"
    ),
    NavItem(
        route = Routes.TERMINAL,
        label = "Terminal",
        selectedIcon = Icons.Filled.Terminal,
        unselectedIcon = Icons.Outlined.Terminal,
        testTag = "nav_terminal"
    ),
    NavItem(
        route = Routes.DISPLAY,
        label = "Wayland/VNC",
        selectedIcon = Icons.Filled.DesktopWindows,
        unselectedIcon = Icons.Outlined.DesktopWindows,
        testTag = "nav_display"
    )
)

@Composable
fun ProotBoxApp(viewModel: MainViewModel) {
    val navController = rememberNavController()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route ?: Routes.CONTAINERS

    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val prootState by viewModel.prootState.collectAsStateWithLifecycle()
    val waylandState by viewModel.waylandState.collectAsStateWithLifecycle()
    val vncState by viewModel.vncState.collectAsStateWithLifecycle()
    val terminalState by viewModel.terminalState.collectAsStateWithLifecycle()
    val containers by viewModel.containers.collectAsStateWithLifecycle()
    val activeContainer by viewModel.activeContainer.collectAsStateWithLifecycle()
    val distroQuery by viewModel.distroQuery.collectAsStateWithLifecycle()
    val catalogMatches by viewModel.catalogMatches.collectAsStateWithLifecycle()
    val selectedDistro by viewModel.selectedDistro.collectAsStateWithLifecycle()
    val selectedComponentIds by viewModel.selectedComponentIds.collectAsStateWithLifecycle()
    val installJob by viewModel.installJob.collectAsStateWithLifecycle()
    val displaySubTab by viewModel.displaySubTab.collectAsStateWithLifecycle()
    val navEvent by viewModel.navigationEvent.collectAsStateWithLifecycle()

    LaunchedEffect(navEvent) {
        val target = navEvent ?: return@LaunchedEffect
        navController.navigate(target) {
            popUpTo(navController.graph.findStartDestination().id) {
                saveState = true
            }
            launchSingleTop = true
            restoreState = true
        }
        viewModel.consumeNavigationEvent()
    }

    fun navigateTo(route: String) {
        navController.navigate(route) {
            popUpTo(navController.graph.findStartDestination().id) {
                saveState = true
            }
            launchSingleTop = true
            restoreState = true
        }
    }

    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val useRail = maxWidth >= 600.dp

        Scaffold(
            contentWindowInsets = WindowInsets.safeDrawing,
            bottomBar = {
                if (!useRail) {
                    NavigationBar(
                        windowInsets = WindowInsets.navigationBars,
                        containerColor = MaterialTheme.colorScheme.surface
                    ) {
                        navItems.forEach { item ->
                            val selected = currentRoute == item.route
                            NavigationBarItem(
                                selected = selected,
                                onClick = { navigateTo(item.route) },
                                icon = {
                                    Icon(
                                        imageVector = if (selected) item.selectedIcon else item.unselectedIcon,
                                        contentDescription = item.label
                                    )
                                },
                                label = { Text(item.label, maxLines = 1) },
                                modifier = Modifier.testTag(item.testTag)
                            )
                        }
                    }
                }
            }
        ) { innerPadding ->
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
            ) {
                if (useRail) {
                    NavigationRail(
                        containerColor = MaterialTheme.colorScheme.surface,
                        modifier = Modifier.windowInsetsPadding(WindowInsets.navigationBars)
                    ) {
                        navItems.forEach { item ->
                            val selected = currentRoute == item.route
                            NavigationRailItem(
                                selected = selected,
                                onClick = { navigateTo(item.route) },
                                icon = {
                                    Icon(
                                        imageVector = if (selected) item.selectedIcon else item.unselectedIcon,
                                        contentDescription = item.label
                                    )
                                },
                                label = { Text(item.label) },
                                modifier = Modifier.testTag(item.testTag)
                            )
                        }
                    }
                }

                NavHost(
                    navController = navController,
                    startDestination = Routes.CONTAINERS,
                    modifier = Modifier.weight(1f)
                ) {
                    composable(Routes.CONTAINERS) {
                        ContainersScreen(
                            deviceArch = viewModel.deviceArch,
                            prootState = prootState,
                            waylandState = waylandState,
                            containers = containers,
                            activeContainer = activeContainer,
                            darkTheme = settings.darkTheme,
                            onToggleTheme = { viewModel.toggleDarkTheme() },
                            onDownloadProot = { viewModel.downloadProotOnly() },
                            onTypeAndInstallDistro = { query ->
                                viewModel.resolveTypedDistroAndOpenInstaller(query)
                            },
                            onOpenTerminal = { viewModel.openContainerInTerminal(it) },
                            onLaunchWayland = { viewModel.launchContainerWayland(it) },
                            onLaunchVnc = { viewModel.launchContainerVnc(it) },
                            onUpdateComponents = { c, comps ->
                                viewModel.updateExistingContainerComponents(c, comps)
                            },
                            onDeleteContainer = { viewModel.deleteContainer(it) }
                        )
                    }
                    composable(Routes.INSTALLER) {
                        InstallerScreen(
                            distroQuery = distroQuery,
                            catalogMatches = catalogMatches,
                            selectedDistro = selectedDistro,
                            selectedComponentIds = selectedComponentIds,
                            installJob = installJob,
                            onQueryChange = { viewModel.updateDistroQuery(it) },
                            onSelectDistro = { viewModel.selectDistroItem(it) },
                            onToggleComponent = { viewModel.toggleComponent(it) },
                            onApplyPreset = { viewModel.applyComponentPreset(it) },
                            onStartInstall = { customName -> viewModel.startFullInstallation(customName) },
                            onCancelInstall = { viewModel.cancelInstall() },
                            onOpenInstalledTerminal = { navigateTo(Routes.TERMINAL) }
                        )
                    }
                    composable(Routes.TERMINAL) {
                        TerminalScreen(
                            terminalState = terminalState,
                            containers = containers,
                            activeContainer = activeContainer,
                            fontSizeSp = settings.terminalFontSizeSp,
                            onSelectContainer = { viewModel.selectActiveContainer(it) },
                            onInputChange = { viewModel.terminalEngine.updateInput(it) },
                            onSubmitCommand = { viewModel.terminalEngine.submitCommand(it) },
                            onSpecialKey = { viewModel.terminalEngine.sendSpecialKey(it) },
                            onToggleCtrl = { viewModel.terminalEngine.toggleCtrl() },
                            onToggleAlt = { viewModel.terminalEngine.toggleAlt() },
                            onClear = { viewModel.terminalEngine.clearScreen() },
                            onAdjustFontSize = { delta -> viewModel.adjustTerminalFontSize(delta) }
                        )
                    }
                    composable(Routes.DISPLAY) {
                        DisplayScreen(
                            subTab = displaySubTab,
                            waylandState = waylandState,
                            vncState = vncState,
                            activeContainer = activeContainer,
                            onSelectSubTab = { viewModel.setDisplaySubTab(it) },
                            onStartWayland = { viewModel.waylandServer.start() },
                            onStopWayland = { viewModel.waylandServer.stop() },
                            onTestWaylandClient = {
                                viewModel.waylandServer.launchWireSelfTestClient(
                                    activeContainer?.name ?: "Linux"
                                )
                            },
                            onWaylandPointer = { x, y, pressed ->
                                viewModel.waylandServer.sendPointerEvent(x, y, pressed)
                            },
                            onWaylandKey = { label ->
                                viewModel.waylandServer.sendKeyEvent(label)
                            },
                            onVncConnect = { host, port, pw ->
                                viewModel.vncClient.connect(host, port, pw)
                            },
                            onVncDisconnect = { viewModel.vncClient.disconnect() },
                            onLaunchContainerVnc = {
                                val c = activeContainer
                                if (c != null) {
                                    viewModel.launchContainerVnc(c)
                                } else {
                                    viewModel.vncClient.startAndConnectContainerDesktop(
                                        "ProotBox Linux",
                                        "XFCE4 Desktop",
                                        5901
                                    )
                                }
                            },
                            onVncPointer = { x, y, mask ->
                                viewModel.vncClient.sendPointerEvent(x, y, mask)
                            },
                            onVncKey = { sym, down ->
                                viewModel.vncClient.sendKeyEvent(sym, down)
                            }
                        )
                    }
                }
            }
        }
    }
}
