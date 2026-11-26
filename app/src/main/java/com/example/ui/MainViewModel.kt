package com.example.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.AppPreferences
import com.example.data.UserSettings
import com.example.engine.ContainerStorage
import com.example.engine.DistroRegistryRepository
import com.example.engine.ProotBinaryManager
import com.example.engine.RootfsExtractor
import com.example.engine.TerminalEngine
import com.example.engine.TerminalUiState
import com.example.engine.VncClient
import com.example.engine.VncUiState
import com.example.engine.WaylandCompositorServer
import com.example.engine.WaylandState
import com.example.model.ComponentCatalog
import com.example.model.CpuArch
import com.example.model.DistroCatalogItem
import com.example.model.InstallJobState
import com.example.model.InstallPhase
import com.example.model.InstalledContainer
import com.example.model.ProotBinaryState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.io.File
import java.util.UUID

enum class DisplaySubTab {
    WAYLAND,
    VNC
}

class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val prefs = AppPreferences(application)
    val containerStorage = ContainerStorage(application)
    val prootManager = ProotBinaryManager(application)
    val distroRegistry = DistroRegistryRepository()
    private val rootfsExtractor = RootfsExtractor()
    val waylandServer = WaylandCompositorServer(containerStorage.runtimeDir)
    val vncClient = VncClient()
    val terminalEngine = TerminalEngine(
        containerStorage = containerStorage,
        prootBinaryManager = prootManager,
        waylandServer = waylandServer,
        vncClient = vncClient
    )

    val deviceArch: CpuArch = CpuArch.detect()

    val settings: StateFlow<UserSettings> = prefs.settingsFlow.stateIn(
        scope = viewModelScope,
        started = SharingStarted.Eagerly,
        initialValue = UserSettings()
    )

    val prootState: StateFlow<ProotBinaryState> = prootManager.state
    val waylandState: StateFlow<WaylandState> = waylandServer.state
    val vncState: StateFlow<VncUiState> = vncClient.state
    val terminalState: StateFlow<TerminalUiState> = terminalEngine.state

    private val _containers = MutableStateFlow<List<InstalledContainer>>(emptyList())
    val containers: StateFlow<List<InstalledContainer>> = _containers.asStateFlow()

    private val _activeContainer = MutableStateFlow<InstalledContainer?>(null)
    val activeContainer: StateFlow<InstalledContainer?> = _activeContainer.asStateFlow()

    private val _distroQuery = MutableStateFlow("ubuntu")
    val distroQuery: StateFlow<String> = _distroQuery.asStateFlow()

    private val _catalogMatches = MutableStateFlow<List<DistroCatalogItem>>(
        distroRegistry.getFeaturedCatalog(deviceArch)
    )
    val catalogMatches: StateFlow<List<DistroCatalogItem>> = _catalogMatches.asStateFlow()

    private val _selectedDistro = MutableStateFlow(
        distroRegistry.getFeaturedCatalog(deviceArch).first { it.id == "ubuntu" }
    )
    val selectedDistro: StateFlow<DistroCatalogItem> = _selectedDistro.asStateFlow()

    private val _selectedComponentIds = MutableStateFlow(
        ComponentCatalog.all.filter { it.defaultSelected || it.required }.map { it.id }.toSet()
    )
    val selectedComponentIds: StateFlow<Set<String>> = _selectedComponentIds.asStateFlow()

    private val _installJob = MutableStateFlow(InstallJobState())
    val installJob: StateFlow<InstallJobState> = _installJob.asStateFlow()

    private val _displaySubTab = MutableStateFlow(DisplaySubTab.WAYLAND)
    val displaySubTab: StateFlow<DisplaySubTab> = _displaySubTab.asStateFlow()

    private val _navigationEvent = MutableStateFlow<String?>(null)
    val navigationEvent: StateFlow<String?> = _navigationEvent.asStateFlow()

    private var searchJob: Job? = null
    private var installCoroutine: Job? = null

    init {
        terminalEngine.onStartWaylandRequested = { _ ->
            _displaySubTab.value = DisplaySubTab.WAYLAND
        }
        terminalEngine.onStartVncRequested = { _ ->
            _displaySubTab.value = DisplaySubTab.VNC
        }

        viewModelScope.launch {
            prootManager.refreshStatus()
            reloadContainers()
            waylandServer.start("wayland-0")
        }
    }

    fun consumeNavigationEvent() {
        _navigationEvent.value = null
    }

    fun setDisplaySubTab(tab: DisplaySubTab) {
        _displaySubTab.value = tab
    }

    fun toggleDarkTheme() {
        viewModelScope.launch {
            prefs.setDarkTheme(!settings.value.darkTheme)
        }
    }

    fun adjustTerminalFontSize(delta: Int) {
        viewModelScope.launch {
            prefs.setTerminalFontSize(settings.value.terminalFontSizeSp + delta)
        }
    }

    fun downloadProotOnly() {
        viewModelScope.launch {
            prootManager.downloadProotBinaries { log ->
                appendInstallLog(log)
            }
        }
    }

    fun updateDistroQuery(query: String) {
        _distroQuery.value = query
        searchJob?.cancel()
        searchJob = viewModelScope.launch {
            delay(250)
            val results = distroRegistry.searchLxcDistros(query, deviceArch)
            _catalogMatches.value = results
            if (query.isNotBlank()) {
                val exact = results.firstOrNull {
                    it.id.equals(query.trim(), ignoreCase = true) ||
                        it.name.equals(query.trim(), ignoreCase = true)
                }
                if (exact != null) {
                    _selectedDistro.value = exact
                }
            }
        }
    }

    fun selectDistroItem(item: DistroCatalogItem) {
        _selectedDistro.value = item
        _distroQuery.value = item.id
    }

    fun resolveTypedDistroAndOpenInstaller(rawInput: String) {
        viewModelScope.launch {
            val query = rawInput.trim().ifBlank { "ubuntu" }
            _distroQuery.value = query
            val resolved = distroRegistry.resolveDistroQuery(query, deviceArch)
            _selectedDistro.value = resolved
            _navigationEvent.value = Routes.INSTALLER
        }
    }

    fun toggleComponent(componentId: String) {
        val comp = ComponentCatalog.all.firstOrNull { it.id == componentId } ?: return
        if (comp.required) return
        val current = _selectedComponentIds.value.toMutableSet()
        if (componentId in current) {
            current.remove(componentId)
        } else {
            current.add(componentId)
        }
        _selectedComponentIds.value = current
    }

    fun applyComponentPreset(presetName: String) {
        val required = ComponentCatalog.all.filter { it.required }.map { it.id }.toSet()
        _selectedComponentIds.value = when (presetName) {
            "minimal" -> required + setOf(ComponentCatalog.ID_DNS_CERTS, ComponentCatalog.ID_CLI_UTILS)
            "wayland" -> required + setOf(
                ComponentCatalog.ID_DNS_CERTS,
                ComponentCatalog.ID_WAYLAND_SERVER,
                ComponentCatalog.ID_XWAYLAND,
                ComponentCatalog.ID_DE_SWAY,
                ComponentCatalog.ID_CLI_UTILS
            )
            "xfce_vnc" -> required + setOf(
                ComponentCatalog.ID_DNS_CERTS,
                ComponentCatalog.ID_WAYLAND_SERVER,
                ComponentCatalog.ID_TIGERVNC,
                ComponentCatalog.ID_DE_XFCE4,
                ComponentCatalog.ID_CLI_UTILS
            )
            "full" -> ComponentCatalog.all.map { it.id }.toSet()
            else -> ComponentCatalog.all.filter { it.defaultSelected || it.required }.map { it.id }.toSet()
        }
    }

    fun startFullInstallation(customContainerName: String = "") {
        if (_installJob.value.isRunning) return
        val typedQuery = _distroQuery.value.trim().ifBlank { _selectedDistro.value.id }
        val chosenComponents = _selectedComponentIds.value

        installCoroutine = viewModelScope.launch(Dispatchers.IO) {
            try {
                _installJob.value = InstallJobState(
                    phase = InstallPhase.DOWNLOADING_PROOT,
                    isRunning = true,
                    progress = 0.05f,
                    detail = "Verificando binários PRoot (${deviceArch.prootArch})...",
                    logs = listOf("[Setup] Iniciando provisionamento para '$typedQuery' (${deviceArch.abiName})")
                )

                // 1. Download PRoot binary if missing
                if (ComponentCatalog.ID_PROOT_BIN in chosenComponents && !prootManager.state.value.isInstalled) {
                    val prootRes = prootManager.downloadProotBinaries { log -> appendInstallLog(log) }
                    if (prootRes.isFailure) {
                        appendInstallLog("[Aviso] PRoot fallback ativo: ${prootRes.exceptionOrNull()?.message}")
                    }
                } else {
                    appendInstallLog("[PRoot] Binário já presente: ${prootManager.prootFile.absolutePath}")
                }

                // 2. Resolve Distro RootFS URL
                updateJob(InstallPhase.RESOLVING_DISTRO, 0.16f, "Resolvendo imagem LXC/RootFS para '$typedQuery'...")
                val distro = distroRegistry.resolveDistroQuery(typedQuery, deviceArch)
                _selectedDistro.value = distro
                appendInstallLog("[Distro] Resolvido: ${distro.name} ${distro.release} (${distro.sourceLabel})")
                appendInstallLog("[Distro] URL: ${distro.rootfsUrl}")

                // 3. Download RootFS archive
                updateJob(InstallPhase.DOWNLOADING_ROOTFS, 0.22f, "Baixando ${distro.name} ${distro.release}...")
                val cacheArchive = File(
                    getApplication<Application>().cacheDir,
                    "rootfs_${distro.id}_${System.currentTimeMillis()}.tar.xz"
                )
                val downloadResult = rootfsExtractor.downloadFile(
                    urls = listOf(distro.rootfsUrl, distro.fallbackUrl),
                    destinationFile = cacheArchive,
                    onProgress = { prog ->
                        val mapped = 0.22f + prog.fraction * 0.36f
                        _installJob.value = _installJob.value.copy(
                            phase = InstallPhase.DOWNLOADING_ROOTFS,
                            progress = mapped,
                            downloadedBytes = prog.downloadedBytes,
                            totalBytes = prog.totalBytes,
                            detail = "Baixando RootFS: ${prog.downloadedBytes / (1024 * 1024)} MB" +
                                if (prog.totalBytes > 0) " / ${prog.totalBytes / (1024 * 1024)} MB" else ""
                        )
                    },
                    onLog = { log -> appendInstallLog(log) }
                )

                if (downloadResult.isFailure) {
                    throw downloadResult.exceptionOrNull()
                        ?: IllegalStateException("Falha no download do RootFS")
                }

                // 4. Extract RootFS into container directory
                val containerId = "${distro.id}_${UUID.randomUUID().toString().take(6)}"
                val rootfsDir = containerStorage.rootfsDir(containerId)
                updateJob(InstallPhase.EXTRACTING_ROOTFS, 0.60f, "Extraindo RootFS para $containerId...")

                val extractResult = rootfsExtractor.extractTarArchive(
                    archiveFile = cacheArchive,
                    targetRootfsDir = rootfsDir,
                    onEntryExtracted = { count, bytes, currentName ->
                        val prog = (0.60f + (count.coerceAtMost(15000) / 15000f) * 0.22f).coerceAtMost(0.84f)
                        _installJob.value = _installJob.value.copy(
                            phase = InstallPhase.EXTRACTING_ROOTFS,
                            progress = prog,
                            extractedEntries = count,
                            detail = "Extraindo ($count arquivos • ${bytes / (1024 * 1024)} MB): ${currentName.takeLast(32)}"
                        )
                    },
                    onLog = { log -> appendInstallLog(log) }
                )
                cacheArchive.delete()

                if (extractResult.isFailure) {
                    throw extractResult.exceptionOrNull()
                        ?: IllegalStateException("Falha ao extrair RootFS")
                }

                // 5. Write DNS, Wayland, VNC, and Component Setup scripts
                updateJob(InstallPhase.CONFIGURING_CONTAINER, 0.87f, "Gerando configuração Wayland, VNC e DNS...")
                val displayName = customContainerName.trim().ifBlank { "${distro.name} ${distro.release}" }
                val totalSize = containerStorage.computeDirectorySize(rootfsDir)

                val container = InstalledContainer(
                    id = containerId,
                    name = displayName,
                    distroId = distro.id,
                    release = distro.release,
                    arch = deviceArch,
                    packageManager = distro.packageManager,
                    rootfsPath = rootfsDir.absolutePath,
                    createdAt = System.currentTimeMillis(),
                    lastRunAt = System.currentTimeMillis(),
                    sizeBytes = totalSize,
                    installedComponents = chosenComponents,
                    selectedPendingComponents = chosenComponents,
                    vncPort = 5901,
                    waylandDisplay = "wayland-0",
                    accentHex = distro.accentHex
                )

                containerStorage.writeContainerBootstrapFiles(container, chosenComponents)
                appendInstallLog("[Config] Scripts gravados: /root/setup-components.sh, /usr/local/bin/start-vnc, /usr/local/bin/start-wayland")

                // 6. Run package installer inside PRoot if native execve is permitted on this device
                updateJob(InstallPhase.INSTALLING_PACKAGES, 0.92f, "Aplicando pacotes selecionados (${chosenComponents.size} módulos)...")
                val prootState = prootManager.state.value
                if (prootState.isInstalled && prootState.directExecSupported) {
                    runPackageSetupInProot(container, File(prootState.binaryPath))
                } else {
                    val pkgCount = ComponentCatalog.all
                        .filter { it.id in chosenComponents }
                        .flatMap { it.packagesFor(container.packageManager) }
                        .distinct()
                        .size
                    appendInstallLog("[Pacotes] $pkgCount pacotes mapeados via ${container.packageManager.label} em /root/setup-components.sh")
                    if (ComponentCatalog.ID_WAYLAND_SERVER in chosenComponents) {
                        waylandServer.start(container.waylandDisplay)
                        appendInstallLog("[Wayland] Compositor real ativado em ${waylandServer.state.value.socketPath}")
                    }
                }

                // 7. Save container manifest
                val finalSize = containerStorage.computeDirectorySize(rootfsDir)
                val finalContainer = container.copy(sizeBytes = finalSize)
                containerStorage.saveContainer(finalContainer)
                reloadContainers()
                selectActiveContainer(finalContainer)

                _installJob.value = _installJob.value.copy(
                    phase = InstallPhase.COMPLETED,
                    isRunning = false,
                    progress = 1f,
                    detail = "${finalContainer.name} instalado (${finalSize / (1024 * 1024)} MB)!"
                )
                appendInstallLog("[Pronto] Container '${finalContainer.name}' instalado e pronto!")
            } catch (t: Throwable) {
                appendInstallLog("[ERRO] ${t.message}")
                _installJob.value = _installJob.value.copy(
                    phase = InstallPhase.ERROR,
                    isRunning = false,
                    errorMessage = t.message ?: "Erro desconhecido na instalação",
                    detail = "Erro: ${t.message}"
                )
            }
        }
    }

    fun updateExistingContainerComponents(container: InstalledContainer, newComponents: Set<String>) {
        viewModelScope.launch(Dispatchers.IO) {
            val updated = container.copy(
                installedComponents = newComponents,
                selectedPendingComponents = newComponents
            )
            containerStorage.writeContainerBootstrapFiles(updated, newComponents)
            containerStorage.saveContainer(updated)
            reloadContainers()
            if (_activeContainer.value?.id == updated.id) {
                _activeContainer.value = updated
            }
            terminalEngine.appendExternalLog(
                "\r\n\u001B[1;32m[Config]\u001B[0m Componentes atualizados para ${updated.name} (${newComponents.size} ativos).\r\n"
            )
        }
    }

    fun selectActiveContainer(container: InstalledContainer) {
        _activeContainer.value = container
        viewModelScope.launch {
            prefs.setSelectedContainerId(container.id)
        }
        terminalEngine.attachContainer(container)
    }

    fun openContainerInTerminal(container: InstalledContainer) {
        viewModelScope.launch {
            val touched = container.copy(lastRunAt = System.currentTimeMillis())
            containerStorage.saveContainer(touched)
            reloadContainers()
            selectActiveContainer(touched)
            _navigationEvent.value = Routes.TERMINAL
        }
    }

    fun launchContainerWayland(container: InstalledContainer) {
        selectActiveContainer(container)
        waylandServer.start(container.waylandDisplay)
        waylandServer.launchWireSelfTestClient(container.name)
        _displaySubTab.value = DisplaySubTab.WAYLAND
        _navigationEvent.value = Routes.DISPLAY
    }

    fun launchContainerVnc(container: InstalledContainer) {
        selectActiveContainer(container)
        val deLabel = ComponentCatalog.all
            .firstOrNull { it.category.name == "DESKTOP_ENV" && it.id in container.installedComponents }
            ?.name ?: "XFCE4 Desktop"
        vncClient.startAndConnectContainerDesktop(container.name, deLabel, container.vncPort)
        _displaySubTab.value = DisplaySubTab.VNC
        _navigationEvent.value = Routes.DISPLAY
    }

    fun deleteContainer(container: InstalledContainer) {
        viewModelScope.launch {
            if (_activeContainer.value?.id == container.id) {
                terminalEngine.stopSession()
                _activeContainer.value = null
            }
            containerStorage.deleteContainer(container.id)
            reloadContainers()
        }
    }

    fun cancelInstall() {
        installCoroutine?.cancel()
        installCoroutine = null
        _installJob.value = _installJob.value.copy(
            isRunning = false,
            phase = InstallPhase.IDLE,
            detail = "Instalação cancelada pelo usuário"
        )
    }

    private suspend fun reloadContainers() {
        val list = containerStorage.loadAllContainers()
        _containers.value = list
        val preferredId = settings.value.selectedContainerId
        val active = list.firstOrNull { it.id == preferredId } ?: list.firstOrNull()
        if (active != null && _activeContainer.value == null) {
            _activeContainer.value = active
            terminalEngine.attachContainer(active)
        }
    }

    private fun runPackageSetupInProot(container: InstalledContainer, prootBin: File) {
        try {
            val runtimeHostDir = containerStorage.runtimeDir.apply { mkdirs() }
            val pb = ProcessBuilder(
                prootBin.absolutePath,
                "-0", "--link2symlink",
                "-r", container.rootfsPath,
                "-b", "/dev", "-b", "/proc", "-b", "/sys",
                "-b", "${runtimeHostDir.absolutePath}:/tmp/runtime",
                "-w", "/root",
                "/bin/sh", "/root/setup-components.sh"
            ).redirectErrorStream(true)
            pb.environment()["PROOT_TMP_DIR"] = prootManager.prootTmpDir.absolutePath
            pb.environment()["LD_LIBRARY_PATH"] = prootManager.libDir.absolutePath
            val proc = pb.start()

            proc.inputStream.bufferedReader().useLines { lines ->
                lines.forEach { line ->
                    appendInstallLog("[pkg] $line")
                }
            }
            proc.waitFor()
        } catch (t: Throwable) {
            appendInstallLog("[pkg] Execução direta ignorada: ${t.message}")
        }
    }

    private fun updateJob(phase: InstallPhase, progress: Float, detail: String) {
        _installJob.value = _installJob.value.copy(
            phase = phase,
            progress = progress,
            detail = detail
        )
    }

    private fun appendInstallLog(line: String) {
        val current = _installJob.value.logs
        _installJob.value = _installJob.value.copy(
            logs = (current + line).takeLast(120)
        )
    }

    override fun onCleared() {
        super.onCleared()
        terminalEngine.stopSession()
        vncClient.disconnect()
        vncClient.stopLocalBridge()
        waylandServer.stop()
    }
}

object Routes {
    const val CONTAINERS = "containers"
    const val INSTALLER = "installer"
    const val TERMINAL = "terminal"
    const val DISPLAY = "display"
}
