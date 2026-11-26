package com.example.engine

import androidx.compose.ui.graphics.Color
import com.example.model.ComponentCatalog
import com.example.model.InstalledContainer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.BufferedWriter
import java.io.File
import java.io.OutputStreamWriter

data class TerminalSegment(
    val text: String,
    val fg: Color = Color(0xFFE2E8F0),
    val bg: Color? = null,
    val bold: Boolean = false,
    val underline: Boolean = false
)

data class TerminalLine(
    val segments: List<TerminalSegment>
)

data class TerminalUiState(
    val lines: List<TerminalLine> = emptyList(),
    val currentInputLine: String = "",
    val promptText: String = "root@linux:~# ",
    val isRunning: Boolean = false,
    val executionModeLabel: String = "Aguardando container",
    val activeContainerId: String? = null,
    val workingDirVirtual: String = "/root",
    val ctrlActive: Boolean = false,
    val altActive: Boolean = false
)

class TerminalEngine(
    private val containerStorage: ContainerStorage,
    private val prootBinaryManager: ProotBinaryManager,
    private val waylandServer: WaylandCompositorServer,
    private val vncClient: VncClient
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _state = MutableStateFlow(TerminalUiState())
    val state: StateFlow<TerminalUiState> = _state.asStateFlow()

    private val bufferLines = mutableListOf<TerminalLine>()
    private var currentSegments = mutableListOf<TerminalSegment>()
    private var curFg = DEFAULT_FG
    private var curBg: Color? = null
    private var curBold = false
    private var curUnderline = false
    private var curInverse = false

    private var nativeProcess: Process? = null
    private var nativeWriter: BufferedWriter? = null
    private var processJob: Job? = null

    private var activeContainer: InstalledContainer? = null
    private var virtualCwd: String = "/root"
    private var isNativeProotMode = false
    private val customEnv = mutableMapOf<String, String>()
    private val commandHistory = mutableListOf<String>()

    // Callbacks into ViewModel for GUI launches from terminal commands
    var onStartWaylandRequested: ((String) -> Unit)? = null
    var onStartVncRequested: ((InstalledContainer) -> Unit)? = null

    init {
        appendAnsiText(
            "\u001B[1;36mProotBox Terminal Engine v1.0\u001B[0m • VT100/ANSI\r\n" +
                "\u001B[90mSelecione ou instale um container Linux para iniciar o shell.\u001B[0m\r\n"
        )
    }

    fun attachContainer(container: InstalledContainer) {
        if (activeContainer?.id == container.id && _state.value.isRunning) return
        stopSession()

        activeContainer = container
        virtualCwd = "/root"
        customEnv.clear()
        customEnv["HOME"] = "/root"
        customEnv["USER"] = "root"
        customEnv["TERM"] = "xterm-256color"
        customEnv["LANG"] = "C.UTF-8"
        customEnv["XDG_RUNTIME_DIR"] = "/tmp/runtime"
        customEnv["WAYLAND_DISPLAY"] = container.waylandDisplay
        customEnv["DISPLAY"] = ":${container.vncPort - 5900}"
        customEnv["PATH"] = "/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin"

        ensureResolvConf(container.rootfsPath)
        clearScreen()
        val prootState = prootBinaryManager.state.value
        val prootBin = File(prootState.binaryPath)

        if (prootState.isInstalled && prootState.directExecSupported && prootBin.exists()) {
            startNativeProotProcess(container, prootBin)
        } else {
            startRootfsBridgeSession(container, prootState.isInstalled)
        }
    }

    private fun ensureResolvConf(rootfsPath: String) {
        try {
            val etc = File(rootfsPath, "etc").apply { mkdirs() }
            val resolv = File(etc, "resolv.conf")
            if (!resolv.exists() || !resolv.isFile) {
                try {
                    android.system.Os.remove(resolv.absolutePath)
                } catch (_: Throwable) {
                }
                resolv.writeText("nameserver 1.1.1.1\nnameserver 8.8.8.8\n")
            }
        } catch (_: Throwable) {
        }
    }

    private fun startNativeProotProcess(container: InstalledContainer, prootBin: File) {
        isNativeProotMode = true
        val rootfs = File(container.rootfsPath)
        val runtimeHostDir = containerStorage.runtimeDir.apply { mkdirs() }
        val shell = when {
            File(rootfs, "bin/bash").exists() -> "/bin/bash"
            else -> "/bin/sh"
        }

        val cmd = listOf(
            prootBin.absolutePath,
            "-0",
            "--link2symlink",
            "-r", rootfs.absolutePath,
            "-b", "/dev",
            "-b", "/proc",
            "-b", "/sys",
            "-b", "${runtimeHostDir.absolutePath}:/tmp/runtime",
            "-w", "/root",
            "/usr/bin/env", "-i",
            "HOME=/root",
            "TERM=xterm-256color",
            "LANG=C.UTF-8",
            "PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin",
            "XDG_RUNTIME_DIR=/tmp/runtime",
            "WAYLAND_DISPLAY=${container.waylandDisplay}",
            "DISPLAY=:${container.vncPort - 5900}",
            shell, "-i"
        )

        processJob = scope.launch {
            try {
                val pb = ProcessBuilder(cmd)
                    .redirectErrorStream(true)
                pb.environment()["PROOT_TMP_DIR"] = prootBinaryManager.prootTmpDir.absolutePath
                pb.environment()["LD_LIBRARY_PATH"] = prootBinaryManager.libDir.absolutePath
                val proc = pb.start()
                nativeProcess = proc
                nativeWriter = BufferedWriter(OutputStreamWriter(proc.outputStream))

                _state.value = _state.value.copy(
                    isRunning = true,
                    activeContainerId = container.id,
                    executionModeLabel = "PRoot Nativo (${container.arch.prootArch})",
                    promptText = "root@${container.distroId}:~# "
                )
                appendAnsiText(
                    "\u001B[1;32m[PRoot Nativo]\u001B[0m Sessão iniciada em ${container.name} (${container.release})\r\n"
                )

                val reader = proc.inputStream
                val buf = ByteArray(4096)
                while (isActive) {
                    val n = reader.read(buf)
                    if (n <= 0) break
                    appendAnsiText(String(buf, 0, n, Charsets.UTF_8))
                }
                val exit = proc.waitFor()
                appendAnsiText("\r\n\u001B[33m[PRoot encerrado com código $exit]\u001B[0m\r\n")
                _state.value = _state.value.copy(isRunning = false)
            } catch (t: Throwable) {
                appendAnsiText(
                    "\u001B[33m[PRoot nativo indisponível (${t.message}) -> ativando RootFS Shell Bridge]\u001B[0m\r\n"
                )
                startRootfsBridgeSession(container, prootBinaryManager.state.value.isInstalled)
            }
        }
    }

    private fun startRootfsBridgeSession(container: InstalledContainer, prootElfPresent: Boolean) {
        isNativeProotMode = false
        val prootLabel = if (prootElfPresent) {
            "RootFS Shell Bridge (PRoot ELF pronto • SELinux W^X)"
        } else {
            "RootFS Shell Bridge (baixe PRoot para execve nativo)"
        }
        val prompt = buildPrompt(container)
        _state.value = _state.value.copy(
            isRunning = true,
            activeContainerId = container.id,
            executionModeLabel = prootLabel,
            promptText = prompt,
            workingDirVirtual = virtualCwd,
            currentInputLine = ""
        )

        appendAnsiText(
            "\u001B[1;36m╔══════════════════════════════════════════════════════╗\u001B[0m\r\n" +
                "\u001B[1;36m║\u001B[0m  \u001B[1;37m${container.name} ${container.release}\u001B[0m (${container.arch.lxcArch}) • ${container.packageManager.label}\r\n" +
                "\u001B[1;36m║\u001B[0m  \u001B[32mWAYLAND_DISPLAY=${container.waylandDisplay}\u001B[0m | \u001B[35mVNC :${container.vncPort - 5900} (${container.vncPort})\u001B[0m\r\n" +
                "\u001B[1;36m╚══════════════════════════════════════════════════════╝\u001B[0m\r\n" +
                "\u001B[90mDigite 'help', 'neofetch', 'ls /', 'wayland-info', 'start-wayland' ou 'start-vnc'.\u001B[0m\r\n"
        )
    }

    fun stopSession() {
        processJob?.cancel()
        processJob = null
        try {
            nativeWriter?.close()
        } catch (_: Throwable) {
        }
        nativeWriter = null
        try {
            nativeProcess?.destroy()
        } catch (_: Throwable) {
        }
        nativeProcess = null
        _state.value = _state.value.copy(isRunning = false)
    }

    fun toggleCtrl() {
        _state.value = _state.value.copy(ctrlActive = !_state.value.ctrlActive)
    }

    fun toggleAlt() {
        _state.value = _state.value.copy(altActive = !_state.value.altActive)
    }

    fun sendSpecialKey(key: String) {
        val ctrl = _state.value.ctrlActive
        if (isNativeProotMode) {
            val bytes = when (key) {
                "ESC" -> "\u001B"
                "TAB" -> "\t"
                "UP" -> "\u001B[A"
                "DOWN" -> "\u001B[B"
                "RIGHT" -> "\u001B[C"
                "LEFT" -> "\u001B[D"
                "HOME" -> "\u001B[H"
                "END" -> "\u001B[F"
                "PGUP" -> "\u001B[5~"
                "PGDN" -> "\u001B[6~"
                else -> key
            }
            sendRawToNative(bytes)
        } else {
            when (key) {
                "TAB" -> {
                    autocompleteInput()
                }
                "UP" -> {
                    val last = commandHistory.lastOrNull()
                    if (last != null) {
                        _state.value = _state.value.copy(currentInputLine = last)
                    }
                }
                "CTRL_C" -> {
                    appendAnsiText("${_state.value.promptText}${_state.value.currentInputLine}^C\r\n")
                    _state.value = _state.value.copy(currentInputLine = "", ctrlActive = false)
                }
                "CTRL_L" -> {
                    clearScreen()
                    _state.value = _state.value.copy(ctrlActive = false)
                }
                else -> if (key.length == 1) {
                    val ch = key[0].lowercaseChar()
                    if (ctrl && ch == 'c') {
                        sendSpecialKey("CTRL_C")
                    } else if (ctrl && ch == 'l') {
                        sendSpecialKey("CTRL_L")
                    } else {
                        updateInput(_state.value.currentInputLine + key)
                    }
                }
            }
        }
        if (ctrl && key !in listOf("CTRL_C", "CTRL_L")) {
            _state.value = _state.value.copy(ctrlActive = false)
        }
    }

    fun updateInput(newText: String) {
        if (isNativeProotMode) {
            _state.value = _state.value.copy(currentInputLine = newText)
            return
        }
        val ctrl = _state.value.ctrlActive
        if (ctrl && newText.isNotEmpty()) {
            val ch = newText.last().lowercaseChar()
            _state.value = _state.value.copy(ctrlActive = false)
            when (ch) {
                'c' -> {
                    sendSpecialKey("CTRL_C")
                    return
                }
                'l' -> {
                    clearScreen()
                    return
                }
                'd' -> {
                    appendAnsiText("exit\r\n")
                    return
                }
            }
        }
        _state.value = _state.value.copy(currentInputLine = newText)
    }

    fun submitCommand(explicitCommand: String? = null) {
        val cmd = (explicitCommand ?: _state.value.currentInputLine).trim()
        if (explicitCommand == null) {
            _state.value = _state.value.copy(currentInputLine = "")
        }

        if (isNativeProotMode) {
            sendRawToNative(cmd + "\n")
            return
        }

        val container = activeContainer
        val prompt = _state.value.promptText
        appendAnsiText("$prompt$cmd\r\n")
        if (cmd.isBlank() || container == null) return

        commandHistory.add(cmd)
        if (commandHistory.size > 100) commandHistory.removeAt(0)

        scope.launch {
            executeBridgeCommand(container, cmd)
        }
    }

    fun clearScreen() {
        synchronized(bufferLines) {
            bufferLines.clear()
            currentSegments.clear()
        }
        emitSnapshot()
    }

    fun appendExternalLog(text: String) {
        appendAnsiText(text)
    }

    private fun sendRawToNative(text: String) {
        scope.launch {
            try {
                nativeWriter?.apply {
                    write(text)
                    flush()
                }
            } catch (t: Throwable) {
                appendAnsiText("\r\n\u001B[31mErro de escrita no PRoot: ${t.message}\u001B[0m\r\n")
            }
        }
    }

    private fun autocompleteInput() {
        val container = activeContainer ?: return
        val input = _state.value.currentInputLine
        val parts = input.split(" ")
        val token = parts.lastOrNull().orEmpty()
        val hostDir = resolveHostFile(container, virtualCwd)
        val candidates = hostDir.listFiles()?.map { it.name }.orEmpty() +
            listOf("neofetch", "wayland-info", "start-wayland", "start-vnc", "uname", "cat", "ls", "cd", "clear")
        val match = candidates.firstOrNull { it.startsWith(token) && it != token }
        if (match != null) {
            val updated = if (parts.size <= 1) match else parts.dropLast(1).joinToString(" ") + " " + match
            _state.value = _state.value.copy(currentInputLine = updated)
        }
    }

    private fun executeBridgeCommand(container: InstalledContainer, fullCmd: String) {
        val tokens = tokenizeCommand(fullCmd)
        if (tokens.isEmpty()) return
        val cmd = tokens[0]
        val args = tokens.drop(1)

        when (cmd) {
            "clear" -> clearScreen()
            "help" -> {
                appendAnsiText(
                    "\u001B[1;36mComandos disponíveis no RootFS Shell:\u001B[0m\r\n" +
                        "  \u001B[32mls, cd, pwd, cat, head, mkdir, touch, rm, find, grep\u001B[0m  (navega no RootFS real)\r\n" +
                        "  \u001B[32muname, whoami, id, env, export, echo, df, free, ps\u001B[0m     (sistema & ambiente)\r\n" +
                        "  \u001B[32mneofetch / fastfetch\u001B[0m                                   (resumo do container)\r\n" +
                        "  \u001B[35mwayland-info, start-wayland\u001B[0m                            (compositor Wayland real)\r\n" +
                        "  \u001B[36mstart-vnc\u001B[0m                                              (inicia desktop VNC :1)\r\n" +
                        "  \u001B[33mpkg-list, cat /root/setup-components.sh\u001B[0m                (componentes instalados)\r\n"
                )
            }
            "pwd" -> appendAnsiText("$virtualCwd\r\n")
            "whoami" -> appendAnsiText("root\r\n")
            "id" -> appendAnsiText("uid=0(root) gid=0(root) groups=0(root)\r\n")
            "uname" -> {
                val hostKernel = runHostCommand("uname -r").trim().ifBlank { "6.1.0-android" }
                if ("-a" in args) {
                    appendAnsiText("Linux ${container.distroId} $hostKernel #1 SMP PREEMPT ${container.arch.prootArch} GNU/Linux\r\n")
                } else {
                    appendAnsiText("Linux\r\n")
                }
            }
            "env" -> {
                customEnv.forEach { (k, v) ->
                    appendAnsiText("\u001B[36m$k\u001B[0m=$v\r\n")
                }
            }
            "export" -> {
                args.forEach { arg ->
                    val idx = arg.indexOf('=')
                    if (idx > 0) {
                        customEnv[arg.substring(0, idx)] = arg.substring(idx + 1)
                    }
                }
            }
            "echo" -> {
                val out = args.joinToString(" ").let { text ->
                    var res = text
                    customEnv.forEach { (k, v) -> res = res.replace("$$k", v) }
                    res
                }
                appendAnsiText("$out\r\n")
            }
            "cd" -> {
                val target = args.firstOrNull() ?: "/root"
                val nextVirtual = normalizeVirtualPath(virtualCwd, target)
                val hostTarget = resolveHostFile(container, nextVirtual)
                if (hostTarget.exists() && hostTarget.isDirectory) {
                    virtualCwd = nextVirtual
                    _state.value = _state.value.copy(
                        workingDirVirtual = virtualCwd,
                        promptText = buildPrompt(container)
                    )
                } else {
                    appendAnsiText("\u001B[31msh: cd: $target: Diretório não encontrado\u001B[0m\r\n")
                }
            }
            "ls" -> {
                val showHidden = args.any { it.startsWith("-") && ('a' in it || 'l' in it) }
                val longFmt = args.any { it.startsWith("-") && 'l' in it }
                val pathArg = args.firstOrNull { !it.startsWith("-") } ?: "."
                val vPath = normalizeVirtualPath(virtualCwd, pathArg)
                val target = resolveHostFile(container, vPath)
                if (!target.exists()) {
                    appendAnsiText("\u001B[31mls: não foi possível acessar '$pathArg': Arquivo ou diretório inexistente\u001B[0m\r\n")
                } else if (target.isFile) {
                    appendAnsiText("${target.name}\r\n")
                } else {
                    val entries = target.listFiles()
                        ?.filter { showHidden || !it.name.startsWith(".") }
                        ?.sortedWith(compareBy({ !it.isDirectory }, { it.name.lowercase() }))
                        .orEmpty()
                    if (longFmt) {
                        appendAnsiText("total ${entries.size}\r\n")
                        entries.forEach { f ->
                            val perms = if (f.isDirectory) "drwxr-xr-x" else if (f.canExecute()) "-rwxr-xr-x" else "-rw-r--r--"
                            val color = if (f.isDirectory) "\u001B[1;34m" else if (f.canExecute()) "\u001B[1;32m" else "\u001B[0m"
                            appendAnsiText("$perms root root ${f.length().toString().padStart(8)} $color${f.name}\u001B[0m\r\n")
                        }
                    } else {
                        val line = entries.joinToString("  ") { f ->
                            if (f.isDirectory) "\u001B[1;34m${f.name}/\u001B[0m"
                            else if (f.canExecute()) "\u001B[1;32m${f.name}*\u001B[0m"
                            else f.name
                        }
                        if (line.isNotEmpty()) appendAnsiText("$line\r\n")
                    }
                }
            }
            "cat", "head" -> {
                val pathArg = args.firstOrNull { !it.startsWith("-") }
                if (pathArg == null) {
                    appendAnsiText("\u001B[31mUso: $cmd <arquivo>\u001B[0m\r\n")
                } else {
                    val file = resolveHostFile(container, normalizeVirtualPath(virtualCwd, pathArg))
                    if (!file.exists() || !file.isFile) {
                        appendAnsiText("\u001B[31m$cmd: $pathArg: Arquivo não encontrado\u001B[0m\r\n")
                    } else {
                        val content = file.readLines()
                        val lines = if (cmd == "head") content.take(15) else content.take(200)
                        lines.forEach { appendAnsiText("$it\r\n") }
                        if (content.size > 200 && cmd == "cat") {
                            appendAnsiText("\u001B[90m... (${content.size - 200} linhas omitidas)\u001B[0m\r\n")
                        }
                    }
                }
            }
            "mkdir" -> {
                val targets = args.filter { !it.startsWith("-") }
                targets.forEach { t ->
                    val dir = resolveHostFile(container, normalizeVirtualPath(virtualCwd, t))
                    if (!dir.mkdirs() && !dir.exists()) {
                        appendAnsiText("\u001B[31mmkdir: falha ao criar '$t'\u001B[0m\r\n")
                    }
                }
            }
            "touch" -> {
                args.forEach { t ->
                    val file = resolveHostFile(container, normalizeVirtualPath(virtualCwd, t))
                    file.parentFile?.mkdirs()
                    if (!file.exists()) file.createNewFile() else file.setLastModified(System.currentTimeMillis())
                }
            }
            "rm" -> {
                val recursive = args.any { it.startsWith("-") && ('r' in it || 'R' in it) }
                args.filter { !it.startsWith("-") }.forEach { t ->
                    val target = resolveHostFile(container, normalizeVirtualPath(virtualCwd, t))
                    if (!target.exists()) {
                        appendAnsiText("\u001B[31mrm: '$t' não existe\u001B[0m\r\n")
                    } else if (target.isDirectory && !recursive) {
                        appendAnsiText("\u001B[31mrm: '$t' é um diretório (use -r)\u001B[0m\r\n")
                    } else {
                        target.deleteRecursively()
                    }
                }
            }
            "find" -> {
                val pathArg = args.firstOrNull { !it.startsWith("-") } ?: "."
                val vPath = normalizeVirtualPath(virtualCwd, pathArg)
                val base = resolveHostFile(container, vPath)
                val rootPrefix = File(container.rootfsPath).canonicalPath
                base.walkTopDown().take(80).forEach { f ->
                    val rel = f.canonicalPath.removePrefix(rootPrefix).ifBlank { "/" }
                    appendAnsiText("$rel\r\n")
                }
            }
            "grep" -> {
                if (args.size < 2) {
                    appendAnsiText("\u001B[31mUso: grep <padrao> <arquivo>\u001B[0m\r\n")
                } else {
                    val pattern = args[0]
                    val file = resolveHostFile(container, normalizeVirtualPath(virtualCwd, args[1]))
                    if (file.isFile) {
                        file.readLines().forEach { line ->
                            if (line.contains(pattern, ignoreCase = true)) {
                                val highlighted = line.replace(pattern, "\u001B[1;31m$pattern\u001B[0m", ignoreCase = true)
                                appendAnsiText("$highlighted\r\n")
                            }
                        }
                    } else {
                        appendAnsiText("\u001B[31mgrep: ${args[1]}: arquivo não encontrado\u001B[0m\r\n")
                    }
                }
            }
            "neofetch", "fastfetch" -> printNeofetch(container)
            "pkg-list" -> {
                appendAnsiText("\u001B[1;36mComponentes configurados em ${container.name}:\u001B[0m\r\n")
                ComponentCatalog.all.forEach { comp ->
                    val installed = comp.id in container.installedComponents
                    val mark = if (installed) "\u001B[32m[✓]\u001B[0m" else "\u001B[90m[ ]\u001B[0m"
                    appendAnsiText("  $mark ${comp.name}\r\n")
                }
            }
            "wayland-info" -> {
                val ws = waylandServer.state.value
                appendAnsiText(
                    "\u001B[1;35mWayland Protocol Status:\u001B[0m\r\n" +
                        "  WAYLAND_DISPLAY = ${ws.displayName}\r\n" +
                        "  XDG_RUNTIME_DIR = ${ws.runtimeDir}\r\n" +
                        "  Transport       = ${ws.transportMode}\r\n" +
                        "  Ativo           = ${ws.isRunning}\r\n" +
                        "  Globais         = ${ws.advertisedGlobals.joinToString(", ")}\r\n"
                )
            }
            "start-wayland" -> {
                waylandServer.start(container.waylandDisplay)
                waylandServer.launchWireSelfTestClient(container.name)
                onStartWaylandRequested?.invoke(container.name)
                appendAnsiText("\u001B[1;35m[Wayland]\u001B[0m Compositor Wayland real iniciado em ${container.waylandDisplay}!\r\n")
            }
            "start-vnc" -> {
                val deLabel = ComponentCatalog.all
                    .firstOrNull { it.category.name == "DESKTOP_ENV" && it.id in container.installedComponents }
                    ?.name ?: "XFCE4 / X11"
                vncClient.startAndConnectContainerDesktop(container.name, deLabel, container.vncPort)
                onStartVncRequested?.invoke(container)
                appendAnsiText("\u001B[1;36m[VNC]\u001B[0m Servidor RFB 3.8 ativo em 127.0.0.1:${container.vncPort} ($deLabel)\r\n")
            }
            else -> {
                // Delegate general POSIX/Android utilities like df, ps, date, id, ip, ping
                val hostOutput = runHostCommand(fullCmd)
                if (hostOutput.isNotBlank()) {
                    appendAnsiText(hostOutput.replace("\n", "\r\n"))
                } else {
                    appendAnsiText("\u001B[33msh: '$cmd' não encontrado ou sem saída. Digite 'help' para comandos.\u001B[0m\r\n")
                }
            }
        }
    }

    private fun printNeofetch(container: InstalledContainer) {
        val osRelease = File(container.rootfsPath, "etc/os-release")
        val prettyName = if (osRelease.exists()) {
            osRelease.readLines()
                .firstOrNull { it.startsWith("PRETTY_NAME=") }
                ?.substringAfter('=')
                ?.trim('"')
                ?: "${container.name} ${container.release}"
        } else {
            "${container.name} ${container.release}"
        }
        val sizeMb = container.sizeBytes / (1024 * 1024)
        val prootOk = prootBinaryManager.state.value.isInstalled
        val waylandOk = waylandServer.state.value.isRunning

        appendAnsiText(
            "\u001B[1;36m       /\\        \u001B[1;32mroot\u001B[0m@\u001B[1;36m${container.distroId}\u001B[0m\r\n" +
                "\u001B[1;36m      /  \\       \u001B[90m────────────────────────\u001B[0m\r\n" +
                "\u001B[1;36m     /\\   \\      \u001B[1;33mOS:\u001B[0m $prettyName\r\n" +
                "\u001B[1;36m    /  \\   \\     \u001B[1;33mArch:\u001B[0m ${container.arch.prootArch} (${container.arch.abiName})\r\n" +
                "\u001B[1;36m   /    \\   \\    \u001B[1;33mPRoot:\u001B[0m ${if (prootOk) "ELF Ativo" else "Não baixado"}\r\n" +
                "\u001B[1;36m  /      \\   \\   \u001B[1;33mWayland:\u001B[0m ${if (waylandOk) "${container.waylandDisplay} (Online)" else "Pronto (${container.waylandDisplay})"}\r\n" +
                "\u001B[1;36m /________\\___\\  \u001B[1;33mVNC:\u001B[0m 127.0.0.1:${container.vncPort}\r\n" +
                "                 \u001B[1;33mRootFS:\u001B[0m ${sizeMb} MB • ${container.installedComponents.size} componentes\r\n"
        )
    }

    private fun runHostCommand(command: String): String {
        return try {
            val proc = ProcessBuilder("/system/bin/sh", "-c", command)
                .redirectErrorStream(true)
                .start()
            val out = proc.inputStream.bufferedReader().readText()
            proc.waitFor()
            out
        } catch (t: Throwable) {
            ""
        }
    }

    private fun resolveHostFile(container: InstalledContainer, virtualPath: String): File {
        val root = File(container.rootfsPath)
        val clean = virtualPath.trimStart('/')
        val candidate = if (clean.isEmpty()) root else File(root, clean)
        return if (candidate.canonicalPath.startsWith(root.canonicalPath)) candidate else root
    }

    private fun normalizeVirtualPath(cwd: String, input: String): String {
        val raw = if (input.startsWith("/")) input else "$cwd/$input"
        val stack = ArrayDeque<String>()
        raw.split("/").forEach { part ->
            when (part) {
                "", "." -> Unit
                ".." -> if (stack.isNotEmpty()) stack.removeLast()
                else -> stack.addLast(part)
            }
        }
        return "/" + stack.joinToString("/")
    }

    private fun buildPrompt(container: InstalledContainer): String {
        val shortDir = if (virtualCwd == "/root") "~" else virtualCwd
        return "root@${container.distroId}:$shortDir# "
    }

    private fun tokenizeCommand(cmd: String): List<String> {
        val out = mutableListOf<String>()
        val cur = StringBuilder()
        var inQuotes = false
        var quoteChar = '"'
        for (ch in cmd) {
            when {
                inQuotes && ch == quoteChar -> inQuotes = false
                !inQuotes && (ch == '"' || ch == '\'') -> {
                    inQuotes = true
                    quoteChar = ch
                }
                !inQuotes && ch.isWhitespace() -> {
                    if (cur.isNotEmpty()) {
                        out.add(cur.toString())
                        cur.clear()
                    }
                }
                else -> cur.append(ch)
            }
        }
        if (cur.isNotEmpty()) out.add(cur.toString())
        return out
    }

    // --- ANSI / VT100 Parser ---
    private fun appendAnsiText(text: String) {
        synchronized(bufferLines) {
            var i = 0
            val textBuf = StringBuilder()

            fun flushSegment() {
                if (textBuf.isNotEmpty()) {
                    val fg = if (curInverse) (curBg ?: Color(0xFF090D12)) else curFg
                    val bg = if (curInverse) curFg else curBg
                    currentSegments.add(
                        TerminalSegment(
                            text = textBuf.toString(),
                            fg = fg,
                            bg = bg,
                            bold = curBold,
                            underline = curUnderline
                        )
                    )
                    textBuf.clear()
                }
            }

            fun commitLine() {
                flushSegment()
                bufferLines.add(TerminalLine(currentSegments.toList()))
                currentSegments.clear()
                if (bufferLines.size > MAX_SCROLLBACK) {
                    bufferLines.removeAt(0)
                }
            }

            while (i < text.length) {
                val c = text[i]
                when (c) {
                    '\u001B' -> {
                        flushSegment()
                        if (i + 1 < text.length && text[i + 1] == '[') {
                            // CSI sequence
                            var j = i + 2
                            while (j < text.length && text[j] !in '@'..'~') {
                                j++
                            }
                            if (j < text.length) {
                                val params = text.substring(i + 2, j)
                                val finalChar = text[j]
                                handleCsi(params, finalChar)
                                i = j + 1
                                continue
                            }
                        } else if (i + 1 < text.length && text[i + 1] == ']') {
                            // OSC sequence terminated by BEL or ST
                            var j = i + 2
                            while (j < text.length && text[j] != '\u0007') j++
                            i = (j + 1).coerceAtMost(text.length)
                            continue
                        }
                        i++
                    }
                    '\n' -> {
                        commitLine()
                        i++
                    }
                    '\r' -> {
                        // Ignore CR before LF or reset line if standalone
                        if (i + 1 < text.length && text[i + 1] != '\n') {
                            flushSegment()
                            currentSegments.clear()
                        }
                        i++
                    }
                    '\b' -> {
                        if (textBuf.isNotEmpty()) {
                            textBuf.deleteCharAt(textBuf.length - 1)
                        }
                        i++
                    }
                    '\t' -> {
                        textBuf.append("    ")
                        i++
                    }
                    '\u0007' -> i++
                    else -> {
                        textBuf.append(c)
                        i++
                    }
                }
            }
            flushSegment()
        }
        emitSnapshot()
    }

    private fun handleCsi(params: String, cmd: Char) {
        when (cmd) {
            'm' -> {
                val codes = if (params.isBlank()) listOf(0)
                else params.split(';').mapNotNull { it.toIntOrNull() }
                var idx = 0
                while (idx < codes.size) {
                    when (val code = codes[idx]) {
                        0 -> {
                            curFg = DEFAULT_FG
                            curBg = null
                            curBold = false
                            curUnderline = false
                            curInverse = false
                        }
                        1 -> curBold = true
                        4 -> curUnderline = true
                        7 -> curInverse = true
                        22 -> curBold = false
                        24 -> curUnderline = false
                        27 -> curInverse = false
                        in 30..37 -> curFg = ansi16Color(code - 30, bright = false)
                        39 -> curFg = DEFAULT_FG
                        in 40..47 -> curBg = ansi16Color(code - 40, bright = false)
                        49 -> curBg = null
                        in 90..97 -> curFg = ansi16Color(code - 90, bright = true)
                        in 100..107 -> curBg = ansi16Color(code - 100, bright = true)
                        38, 48 -> {
                            val isFg = code == 38
                            if (idx + 2 < codes.size && codes[idx + 1] == 5) {
                                val color = ansi256Color(codes[idx + 2])
                                if (isFg) curFg = color else curBg = color
                                idx += 2
                            } else if (idx + 4 < codes.size && codes[idx + 1] == 2) {
                                val r = codes[idx + 2].coerceIn(0, 255)
                                val g = codes[idx + 3].coerceIn(0, 255)
                                val b = codes[idx + 4].coerceIn(0, 255)
                                val color = Color(r, g, b)
                                if (isFg) curFg = color else curBg = color
                                idx += 4
                            }
                        }
                    }
                    idx++
                }
            }
            'J' -> {
                if (params == "2" || params == "3") {
                    bufferLines.clear()
                    currentSegments.clear()
                }
            }
            'K' -> {
                currentSegments.clear()
            }
        }
    }

    private fun ansi16Color(index: Int, bright: Boolean): Color {
        return if (!bright) {
            when (index) {
                0 -> Color(0xFF1E293B)
                1 -> Color(0xFFF43F5E)
                2 -> Color(0xFF10B981)
                3 -> Color(0xFFF59E0B)
                4 -> Color(0xFF38BDF8)
                5 -> Color(0xFFA78BFA)
                6 -> Color(0xFF22D3EE)
                else -> Color(0xFFE2E8F0)
            }
        } else {
            when (index) {
                0 -> Color(0xFF64748B)
                1 -> Color(0xFFFB7185)
                2 -> Color(0xFF34D399)
                3 -> Color(0xFFFBBF24)
                4 -> Color(0xFF60A5FA)
                5 -> Color(0xFFC4B5FD)
                6 -> Color(0xFF67E8F9)
                else -> Color(0xFFFFFFFF)
            }
        }
    }

    private fun ansi256Color(n: Int): Color {
        if (n < 16) return ansi16Color(n % 8, n >= 8)
        if (n >= 232) {
            val gray = 8 + (n - 232) * 10
            return Color(gray, gray, gray)
        }
        val c = n - 16
        val r = (c / 36) * 51
        val g = ((c / 6) % 6) * 51
        val b = (c % 6) * 51
        return Color(r, g, b)
    }

    private fun emitSnapshot() {
        val snapshot = synchronized(bufferLines) {
            if (currentSegments.isNotEmpty()) {
                bufferLines + TerminalLine(currentSegments.toList())
            } else {
                bufferLines.toList()
            }
        }
        _state.value = _state.value.copy(lines = snapshot)
    }

    companion object {
        private const val MAX_SCROLLBACK = 1200
        private val DEFAULT_FG = Color(0xFFE2E8F0)
    }
}
