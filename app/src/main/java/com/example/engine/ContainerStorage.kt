package com.example.engine

import android.content.Context
import com.example.model.ComponentCatalog
import com.example.model.CpuArch
import com.example.model.InstalledContainer
import com.example.model.PackageManagerType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

class ContainerStorage(private val context: Context) {

    val containersRootDir: File
        get() = File(context.filesDir, "containers").apply { mkdirs() }

    val runtimeDir: File
        get() = File(context.filesDir, "runtime").apply { mkdirs() }

    val binDir: File
        get() = File(context.filesDir, "bin").apply { mkdirs() }

    fun containerDir(containerId: String): File = File(containersRootDir, containerId)

    fun rootfsDir(containerId: String): File = File(containerDir(containerId), "rootfs")

    suspend fun loadAllContainers(): List<InstalledContainer> = withContext(Dispatchers.IO) {
        val dirs = containersRootDir.listFiles()?.filter { it.isDirectory } ?: return@withContext emptyList()
        dirs.mapNotNull { dir ->
            val manifest = File(dir, "container.json")
            if (manifest.exists()) parseManifest(manifest) else null
        }.sortedByDescending { it.lastRunAt }
    }

    suspend fun saveContainer(container: InstalledContainer) = withContext(Dispatchers.IO) {
        val dir = containerDir(container.id).apply { mkdirs() }
        val json = JSONObject().apply {
            put("id", container.id)
            put("name", container.name)
            put("distroId", container.distroId)
            put("release", container.release)
            put("arch", container.arch.name)
            put("packageManager", container.packageManager.name)
            put("rootfsPath", container.rootfsPath)
            put("createdAt", container.createdAt)
            put("lastRunAt", container.lastRunAt)
            put("sizeBytes", container.sizeBytes)
            put("vncPort", container.vncPort)
            put("waylandDisplay", container.waylandDisplay)
            put("accentHex", container.accentHex)
            put("installedComponents", JSONArray(container.installedComponents.toList()))
            put("selectedPendingComponents", JSONArray(container.selectedPendingComponents.toList()))
        }
        File(dir, "container.json").writeText(json.toString(2))
    }

    suspend fun deleteContainer(containerId: String): Boolean = withContext(Dispatchers.IO) {
        containerDir(containerId).deleteRecursively()
    }

    suspend fun computeDirectorySize(dir: File): Long = withContext(Dispatchers.IO) {
        if (!dir.exists()) return@withContext 0L
        var total = 0L
        dir.walkTopDown().forEach { file ->
            if (file.isFile) {
                total += file.length()
            }
        }
        total
    }

    suspend fun writeContainerBootstrapFiles(
        container: InstalledContainer,
        selectedComponents: Set<String>
    ) = withContext(Dispatchers.IO) {
        val rootfs = File(container.rootfsPath)
        val etcDir = ensureRealDir(File(rootfs, "etc"))
        val rootHome = ensureRealDir(File(rootfs, "root"))
        val tmpDir = ensureRealDir(File(rootfs, "tmp"))
        val runtimeSubdir = ensureRealDir(File(tmpDir, "runtime"))
        val localBin = ensureRealDir(File(rootfs, "usr/local/bin"))

        // Always replace any dangling /etc/resolv.conf symlink (common in Ubuntu/Debian/Fedora systemd rootfs)
        safeWriteText(
            File(etcDir, "resolv.conf"),
            """
            nameserver 1.1.1.1
            nameserver 8.8.8.8
            nameserver 9.9.9.9
            """.trimIndent() + "\n"
        )
        safeWriteText(
            File(etcDir, "hosts"),
            """
            127.0.0.1 localhost
            ::1 localhost
            """.trimIndent() + "\n"
        )

        // Write component setup script tailored to the distro's package manager
        val pm = container.packageManager
        val packagesToInstall = ComponentCatalog.all
            .filter { it.id in selectedComponents }
            .flatMap { it.packagesFor(pm) }
            .distinct()

        val installLine = if (packagesToInstall.isNotEmpty()) {
            pm.installCommand(packagesToInstall)
        } else {
            "echo 'Nenhum pacote extra selecionado.'"
        }

        val setupScript = File(rootHome, "setup-components.sh")
        safeWriteText(
            setupScript,
            """
            #!/bin/sh
            set -e
            echo "[ProotBox] Configurando componentes para ${container.name} (${container.release})..."
            export HOME=/root
            export PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin
            $installLine
            echo "[ProotBox] Componentes instalados com sucesso!"
            """.trimIndent() + "\n"
        )

        // Write VNC startup helper script
        val vncSessionCmd = when {
            ComponentCatalog.ID_DE_XFCE4 in selectedComponents -> "exec dbus-launch --exit-with-session startxfce4"
            ComponentCatalog.ID_DE_LXQT in selectedComponents -> "exec dbus-launch --exit-with-session startlxqt"
            ComponentCatalog.ID_DE_SWAY in selectedComponents -> "exec sway"
            ComponentCatalog.ID_DE_LABWC in selectedComponents -> "exec labwc"
            ComponentCatalog.ID_DE_WESTON in selectedComponents -> "exec weston"
            else -> "xterm & exec openbox-session"
        }

        val vncDir = ensureRealDir(File(rootHome, ".vnc"))
        safeWriteText(
            File(vncDir, "xstartup"),
            """
            #!/bin/sh
            unset SESSION_MANAGER
            unset DBUS_SESSION_BUS_ADDRESS
            export XDG_RUNTIME_DIR=/tmp/runtime
            $vncSessionCmd
            """.trimIndent() + "\n"
        )

        safeWriteText(
            File(localBin, "start-vnc"),
            """
            #!/bin/sh
            export HOME=/root
            export USER=root
            export DISPLAY=:1
            export XDG_RUNTIME_DIR=/tmp/runtime
            mkdir -p /tmp/runtime
            chmod 700 /tmp/runtime
            vncserver -kill :1 >/dev/null 2>&1 || true
            vncserver :1 -geometry 1280x720 -depth 24 -SecurityTypes None -localhost no
            echo "VNC ativo em 127.0.0.1:5901"
            """.trimIndent() + "\n"
        )

        val waylandClientCmd = when {
            ComponentCatalog.ID_DE_SWAY in selectedComponents -> "sway"
            ComponentCatalog.ID_DE_LABWC in selectedComponents -> "labwc"
            ComponentCatalog.ID_DE_WESTON in selectedComponents -> "weston"
            else -> "weston-terminal"
        }

        safeWriteText(
            File(localBin, "start-wayland"),
            """
            #!/bin/sh
            export XDG_RUNTIME_DIR=/tmp/runtime
            export WAYLAND_DISPLAY=${container.waylandDisplay}
            export MOZ_ENABLE_WAYLAND=1
            export QT_QPA_PLATFORM=wayland
            export GDK_BACKEND=wayland
            export SDL_VIDEODRIVER=wayland
            mkdir -p /tmp/runtime
            chmod 700 /tmp/runtime
            echo "Wayland socket: ${'$'}XDG_RUNTIME_DIR/${'$'}WAYLAND_DISPLAY"
            exec $waylandClientCmd
            """.trimIndent() + "\n"
        )

        // Write .profile / .bashrc with Wayland + VNC env
        safeWriteText(
            File(rootHome, ".profile"),
            """
            export HOME=/root
            export TERM=xterm-256color
            export LANG=C.UTF-8
            export XDG_RUNTIME_DIR=/tmp/runtime
            export WAYLAND_DISPLAY=${container.waylandDisplay}
            export DISPLAY=:1
            export MOZ_ENABLE_WAYLAND=1
            export QT_QPA_PLATFORM=wayland
            export GDK_BACKEND=wayland
            mkdir -p /tmp/runtime 2>/dev/null || true
            """.trimIndent() + "\n"
        )

        // Mark scripts executable
        listOf(setupScript, File(vncDir, "xstartup"), File(localBin, "start-vnc"), File(localBin, "start-wayland"))
            .forEach { script ->
                try {
                    android.system.Os.chmod(script.absolutePath, 493) // 0755
                } catch (_: Throwable) {
                    script.setExecutable(true, false)
                }
            }
        try {
            android.system.Os.chmod(runtimeSubdir.absolutePath, 448) // 0700
        } catch (_: Throwable) {
        }
    }

    private fun ensureRealDir(dir: File): File {
        if (!dir.exists()) {
            try {
                android.system.Os.remove(dir.absolutePath)
            } catch (_: Throwable) {
            }
            dir.mkdirs()
        }
        return dir
    }

    private fun safeWriteText(file: File, content: String) {
        file.parentFile?.let { ensureRealDir(it) }
        try {
            // Unlink even if file is a dangling symlink (where File.exists() returns false!)
            android.system.Os.remove(file.absolutePath)
        } catch (_: Throwable) {
        }
        file.writeText(content)
    }

    private fun parseManifest(file: File): InstalledContainer? {
        return try {
            val json = JSONObject(file.readText())
            val installedArray = json.optJSONArray("installedComponents") ?: JSONArray()
            val pendingArray = json.optJSONArray("selectedPendingComponents") ?: installedArray
            InstalledContainer(
                id = json.getString("id"),
                name = json.getString("name"),
                distroId = json.getString("distroId"),
                release = json.getString("release"),
                arch = CpuArch.valueOf(json.optString("arch", CpuArch.detect().name)),
                packageManager = PackageManagerType.valueOf(json.optString("packageManager", PackageManagerType.APT.name)),
                rootfsPath = json.getString("rootfsPath"),
                createdAt = json.optLong("createdAt", System.currentTimeMillis()),
                lastRunAt = json.optLong("lastRunAt", System.currentTimeMillis()),
                sizeBytes = json.optLong("sizeBytes", 0L),
                installedComponents = (0 until installedArray.length()).map { installedArray.getString(it) }.toSet(),
                selectedPendingComponents = (0 until pendingArray.length()).map { pendingArray.getString(it) }.toSet(),
                vncPort = json.optInt("vncPort", 5901),
                waylandDisplay = json.optString("waylandDisplay", "wayland-0"),
                accentHex = json.optLong("accentHex", 0xFF22D3EE)
            )
        } catch (_: Throwable) {
            null
        }
    }
}
