package com.example.model

import android.os.Build

enum class CpuArch(
    val abiName: String,
    val lxcArch: String,
    val prootArch: String,
    val alpineArch: String,
    val ubuntuArch: String
) {
    ARM64("arm64-v8a", "arm64", "aarch64", "aarch64", "arm64"),
    ARM32("armeabi-v7a", "armhf", "arm", "armv7", "armhf"),
    X86_64("x86_64", "amd64", "x86_64", "x86_64", "amd64"),
    X86("x86", "i386", "i686", "x86", "i386");

    companion object {
        fun detect(): CpuArch {
            val abis = Build.SUPPORTED_ABIS.orEmpty()
            for (abi in abis) {
                when (abi) {
                    "arm64-v8a" -> return ARM64
                    "armeabi-v7a", "armeabi" -> return ARM32
                    "x86_64" -> return X86_64
                    "x86" -> return X86
                }
            }
            return ARM64
        }
    }
}

enum class PackageManagerType(val command: String, val label: String) {
    APK("apk", "APK (Alpine)"),
    APT("apt-get", "APT (Debian/Ubuntu)"),
    PACMAN("pacman", "Pacman (Arch)"),
    DNF("dnf", "DNF (Fedora/RHEL)"),
    XBPS("xbps-install", "XBPS (Void)"),
    ZYPPER("zypper", "Zypper (openSUSE)");

    fun installCommand(packages: List<String>): String {
        if (packages.isEmpty()) return ""
        val joined = packages.joinToString(" ")
        return when (this) {
            APK -> "apk update && apk add --no-cache $joined"
            APT -> "DEBIAN_FRONTEND=noninteractive apt-get update && DEBIAN_FRONTEND=noninteractive apt-get install -y --no-install-recommends $joined"
            PACMAN -> "pacman -Sy --noconfirm $joined"
            DNF -> "dnf install -y $joined"
            XBPS -> "xbps-install -Sy $joined"
            ZYPPER -> "zypper --non-interactive install $joined"
        }
    }

    companion object {
        fun forDistro(distroId: String): PackageManagerType {
            val lower = distroId.lowercase()
            return when {
                "alpine" in lower -> APK
                "arch" in lower -> PACMAN
                "fedora" in lower || "rocky" in lower || "alma" in lower || "centos" in lower -> DNF
                "void" in lower -> XBPS
                "opensuse" in lower || "suse" in lower -> ZYPPER
                else -> APT
            }
        }
    }
}

data class DistroCatalogItem(
    val id: String,
    val name: String,
    val release: String,
    val arch: CpuArch,
    val packageManager: PackageManagerType,
    val rootfsUrl: String,
    val fallbackUrl: String = "",
    val estimatedSizeMb: Int,
    val description: String,
    val accentHex: Long,
    val sourceLabel: String = "LXC Official",
    val isCustom: Boolean = false
)

enum class ComponentCategory(val title: String, val subtitle: String) {
    CORE_RUNTIME("Núcleo & Runtime", "Binários PRoot, RootFS e configuração base"),
    DISPLAY_SERVER("Display & Protocolos", "Servidor gráfico Wayland real e servidor VNC"),
    DESKTOP_ENV("Interfaces Gráficas (DE / WM)", "Ambientes de desktop completos ou gerenciadores Wayland"),
    APPLICATIONS("Apps, Áudio & Ferramentas", "Navegador, compiladores, ponte de áudio e utilitários")
}

data class InstallComponent(
    val id: String,
    val name: String,
    val category: ComponentCategory,
    val description: String,
    val estimatedSizeMb: Int,
    val required: Boolean = false,
    val recommended: Boolean = false,
    val defaultSelected: Boolean = false,
    val packagesByPm: Map<PackageManagerType, List<String>> = emptyMap(),
    val configNote: String = ""
) {
    fun packagesFor(pm: PackageManagerType): List<String> {
        return packagesByPm[pm] ?: packagesByPm[PackageManagerType.APT].orEmpty()
    }
}

object ComponentCatalog {
    const val ID_PROOT_BIN = "proot_binary"
    const val ID_BASE_ROOTFS = "base_rootfs"
    const val ID_DNS_CERTS = "dns_certs"
    const val ID_WAYLAND_SERVER = "wayland_server"
    const val ID_TIGERVNC = "tigervnc_server"
    const val ID_XWAYLAND = "xwayland_bridge"
    const val ID_DE_XFCE4 = "de_xfce4"
    const val ID_DE_LXQT = "de_lxqt"
    const val ID_DE_SWAY = "de_sway"
    const val ID_DE_LABWC = "de_labwc"
    const val ID_DE_WESTON = "de_weston"
    const val ID_AUDIO_BRIDGE = "audio_bridge"
    const val ID_BROWSER_FIREFOX = "browser_firefox"
    const val ID_DEV_TOOLS = "dev_tools"
    const val ID_CLI_UTILS = "cli_utils"

    val all: List<InstallComponent> = listOf(
        InstallComponent(
            id = ID_PROOT_BIN,
            name = "Binários PRoot (estático)",
            category = ComponentCategory.CORE_RUNTIME,
            description = "Motor PRoot nativo para a arquitetura do dispositivo (sem necessidade de root)",
            estimatedSizeMb = 4,
            required = true,
            recommended = true,
            defaultSelected = true,
            configNote = "Baixado em files/bin/proot com permissão 0755"
        ),
        InstallComponent(
            id = ID_BASE_ROOTFS,
            name = "Base RootFS Linux",
            category = ComponentCategory.CORE_RUNTIME,
            description = "Sistema de arquivos raiz da distribuição selecionada/digitada",
            estimatedSizeMb = 45,
            required = true,
            recommended = true,
            defaultSelected = true,
            configNote = "Extraído com links simbólicos e permissões POSIX"
        ),
        InstallComponent(
            id = ID_DNS_CERTS,
            name = "DNS, Locales & Certificados TLS",
            category = ComponentCategory.CORE_RUNTIME,
            description = "Configura /etc/resolv.conf (1.1.1.1 / 8.8.8.8), ca-certificates e tzdata",
            estimatedSizeMb = 8,
            recommended = true,
            defaultSelected = true,
            packagesByPm = mapOf(
                PackageManagerType.APK to listOf("ca-certificates", "tzdata", "bash"),
                PackageManagerType.APT to listOf("ca-certificates", "locales", "sudo", "bash"),
                PackageManagerType.PACMAN to listOf("ca-certificates", "sudo", "bash"),
                PackageManagerType.DNF to listOf("ca-certificates", "sudo", "bash"),
                PackageManagerType.XBPS to listOf("ca-certificates", "sudo", "bash"),
                PackageManagerType.ZYPPER to listOf("ca-certificates", "sudo", "bash")
            )
        ),
        InstallComponent(
            id = ID_WAYLAND_SERVER,
            name = "Suporte Wayland Real (wayland-0)",
            category = ComponentCategory.DISPLAY_SERVER,
            description = "Ativa compositor Wayland nativo (${'$'}XDG_RUNTIME_DIR/wayland-0) + libs cliente Wayland",
            estimatedSizeMb = 18,
            recommended = true,
            defaultSelected = true,
            packagesByPm = mapOf(
                PackageManagerType.APK to listOf("wayland-libs-client", "wayland-utils", "mesa-dri-gallium"),
                PackageManagerType.APT to listOf("libwayland-client0", "wayland-utils", "libgl1-mesa-dri", "dbus-user-session"),
                PackageManagerType.PACMAN to listOf("wayland", "wayland-utils", "mesa"),
                PackageManagerType.DNF to listOf("wayland-devel", "wayland-utils", "mesa-dri-drivers"),
                PackageManagerType.XBPS to listOf("wayland", "wayland-utils", "mesa-dri"),
                PackageManagerType.ZYPPER to listOf("libwayland-client0", "wayland-utils", "Mesa-dri")
            )
        ),
        InstallComponent(
            id = ID_TIGERVNC,
            name = "Servidor VNC Integrado (TigerVNC)",
            category = ComponentCategory.DISPLAY_SERVER,
            description = "Servidor RFB/X11 na porta 5901 (:1) para conexão direta com o Visualizador VNC",
            estimatedSizeMb = 34,
            recommended = true,
            defaultSelected = true,
            packagesByPm = mapOf(
                PackageManagerType.APK to listOf("tigervnc", "xauth", "font-dejavu"),
                PackageManagerType.APT to listOf("tigervnc-standalone-server", "tigervnc-common", "xauth", "fonts-dejavu-core"),
                PackageManagerType.PACMAN to listOf("tigervnc", "xorg-xauth", "ttf-dejavu"),
                PackageManagerType.DNF to listOf("tigervnc-server", "xorg-x11-xauth", "dejavu-sans-fonts"),
                PackageManagerType.XBPS to listOf("tigervnc", "xauth", "dejavu-fonts-ttf"),
                PackageManagerType.ZYPPER to listOf("tigervnc", "xauth", "dejavu-fonts")
            )
        ),
        InstallComponent(
            id = ID_XWAYLAND,
            name = "XWayland Bridge",
            category = ComponentCategory.DISPLAY_SERVER,
            description = "Camada de compatibilidade para executar aplicativos X11 dentro da sessão Wayland",
            estimatedSizeMb = 28,
            defaultSelected = false,
            packagesByPm = mapOf(
                PackageManagerType.APK to listOf("xwayland", "dbus-x11"),
                PackageManagerType.APT to listOf("xwayland", "dbus-x11"),
                PackageManagerType.PACMAN to listOf("xorg-xwayland"),
                PackageManagerType.DNF to listOf("xorg-x11-server-Xwayland"),
                PackageManagerType.XBPS to listOf("xorg-server-xwayland"),
                PackageManagerType.ZYPPER to listOf("xwayland")
            )
        ),
        InstallComponent(
            id = ID_DE_XFCE4,
            name = "Interface Gráfica XFCE4",
            category = ComponentCategory.DESKTOP_ENV,
            description = "Desktop completo leve e estável (painel, gerenciador de arquivos Thunar, terminal)",
            estimatedSizeMb = 210,
            recommended = true,
            defaultSelected = true,
            packagesByPm = mapOf(
                PackageManagerType.APK to listOf("xfce4", "xfce4-terminal", "dbus-x11", "thunar"),
                PackageManagerType.APT to listOf("xfce4", "xfce4-terminal", "dbus-x11", "thunar"),
                PackageManagerType.PACMAN to listOf("xfce4", "xfce4-terminal", "thunar"),
                PackageManagerType.DNF to listOf("xfce4-session", "xfce4-panel", "xfce4-terminal", "thunar"),
                PackageManagerType.XBPS to listOf("xfce4", "xfce4-terminal", "dbus"),
                PackageManagerType.ZYPPER to listOf("patterns-xfce-xfce", "xfce4-terminal")
            )
        ),
        InstallComponent(
            id = ID_DE_LXQT,
            name = "Interface Gráfica LXQt + Openbox",
            category = ComponentCategory.DESKTOP_ENV,
            description = "Desktop Qt moderno e ultra-rápido com baixo consumo de RAM",
            estimatedSizeMb = 175,
            defaultSelected = false,
            packagesByPm = mapOf(
                PackageManagerType.APK to listOf("lxqt-desktop", "openbox", "qterminal", "pcmanfm-qt"),
                PackageManagerType.APT to listOf("lxqt-core", "openbox", "qterminal", "pcmanfm-qt"),
                PackageManagerType.PACMAN to listOf("lxqt", "openbox", "qterminal"),
                PackageManagerType.DNF to listOf("lxqt-session", "openbox", "qterminal"),
                PackageManagerType.XBPS to listOf("lxqt", "openbox", "qterminal"),
                PackageManagerType.ZYPPER to listOf("lxqt-session", "openbox", "qterminal")
            )
        ),
        InstallComponent(
            id = ID_DE_SWAY,
            name = "Sway (Compositor Wayland Tiling)",
            category = ComponentCategory.DESKTOP_ENV,
            description = "Gerenciador de janelas Wayland nativo (compatível com i3) + Foot terminal + Waybar",
            estimatedSizeMb = 85,
            defaultSelected = false,
            packagesByPm = mapOf(
                PackageManagerType.APK to listOf("sway", "foot", "waybar", "swaybg"),
                PackageManagerType.APT to listOf("sway", "foot", "waybar", "swaybg"),
                PackageManagerType.PACMAN to listOf("sway", "foot", "waybar"),
                PackageManagerType.DNF to listOf("sway", "foot", "waybar"),
                PackageManagerType.XBPS to listOf("sway", "foot", "waybar"),
                PackageManagerType.ZYPPER to listOf("sway", "foot", "waybar")
            )
        ),
        InstallComponent(
            id = ID_DE_LABWC,
            name = "Labwc (Wayland Stacking WM)",
            category = ComponentCategory.DESKTOP_ENV,
            description = "Compositor Wayland estilo Openbox com janelas flutuantes + Wofi launcher",
            estimatedSizeMb = 62,
            defaultSelected = false,
            packagesByPm = mapOf(
                PackageManagerType.APK to listOf("labwc", "foot", "waybar", "wofi"),
                PackageManagerType.APT to listOf("labwc", "foot", "waybar", "wofi"),
                PackageManagerType.PACMAN to listOf("labwc", "foot", "waybar", "wofi"),
                PackageManagerType.DNF to listOf("labwc", "foot", "waybar", "wofi"),
                PackageManagerType.XBPS to listOf("labwc", "foot", "waybar", "wofi"),
                PackageManagerType.ZYPPER to listOf("labwc", "foot", "waybar", "wofi")
            )
        ),
        InstallComponent(
            id = ID_DE_WESTON,
            name = "Weston (Compositor Wayland Oficial)",
            category = ComponentCategory.DESKTOP_ENV,
            description = "Compositor de referência Wayland com terminal e demos nativos",
            estimatedSizeMb = 55,
            defaultSelected = false,
            packagesByPm = mapOf(
                PackageManagerType.APK to listOf("weston", "weston-terminal"),
                PackageManagerType.APT to listOf("weston"),
                PackageManagerType.PACMAN to listOf("weston"),
                PackageManagerType.DNF to listOf("weston"),
                PackageManagerType.XBPS to listOf("weston"),
                PackageManagerType.ZYPPER to listOf("weston")
            )
        ),
        InstallComponent(
            id = ID_AUDIO_BRIDGE,
            name = "Áudio PulseAudio / PipeWire",
            category = ComponentCategory.APPLICATIONS,
            description = "Suporte de áudio TCP/socket para saída sonora de aplicativos desktop",
            estimatedSizeMb = 22,
            defaultSelected = false,
            packagesByPm = mapOf(
                PackageManagerType.APK to listOf("pulseaudio", "pulseaudio-utils"),
                PackageManagerType.APT to listOf("pulseaudio", "pulseaudio-utils"),
                PackageManagerType.PACMAN to listOf("pulseaudio"),
                PackageManagerType.DNF to listOf("pulseaudio", "pulseaudio-utils"),
                PackageManagerType.XBPS to listOf("pulseaudio"),
                PackageManagerType.ZYPPER to listOf("pulseaudio")
            )
        ),
        InstallComponent(
            id = ID_BROWSER_FIREFOX,
            name = "Navegador Firefox ESR",
            category = ComponentCategory.APPLICATIONS,
            description = "Navegador web completo com suporte nativo Wayland (MOZ_ENABLE_WAYLAND=1) e X11",
            estimatedSizeMb = 145,
            defaultSelected = false,
            packagesByPm = mapOf(
                PackageManagerType.APK to listOf("firefox-esr"),
                PackageManagerType.APT to listOf("firefox-esr"),
                PackageManagerType.PACMAN to listOf("firefox"),
                PackageManagerType.DNF to listOf("firefox"),
                PackageManagerType.XBPS to listOf("firefox"),
                PackageManagerType.ZYPPER to listOf("MozillaFirefox")
            )
        ),
        InstallComponent(
            id = ID_DEV_TOOLS,
            name = "Ferramentas Dev (Git, GCC, Python, Node)",
            category = ComponentCategory.APPLICATIONS,
            description = "Toolchain de desenvolvimento com git, curl, build-base/build-essential, python3 e nodejs",
            estimatedSizeMb = 160,
            defaultSelected = false,
            packagesByPm = mapOf(
                PackageManagerType.APK to listOf("git", "curl", "build-base", "python3", "nodejs", "npm"),
                PackageManagerType.APT to listOf("git", "curl", "build-essential", "python3", "python3-pip", "nodejs"),
                PackageManagerType.PACMAN to listOf("git", "curl", "base-devel", "python", "nodejs"),
                PackageManagerType.DNF to listOf("git", "curl", "gcc", "make", "python3", "nodejs"),
                PackageManagerType.XBPS to listOf("git", "curl", "base-devel", "python3", "nodejs"),
                PackageManagerType.ZYPPER to listOf("git", "curl", "gcc", "make", "python3", "nodejs")
            )
        ),
        InstallComponent(
            id = ID_CLI_UTILS,
            name = "Utilitários CLI (htop, neofetch, nano, vim, tmux)",
            category = ComponentCategory.APPLICATIONS,
            description = "Ferramentas essenciais de linha de comando e monitoramento de sistema",
            estimatedSizeMb = 24,
            recommended = true,
            defaultSelected = true,
            packagesByPm = mapOf(
                PackageManagerType.APK to listOf("htop", "fastfetch", "nano", "vim", "tmux", "socat", "wget"),
                PackageManagerType.APT to listOf("htop", "neofetch", "nano", "vim", "tmux", "socat", "wget"),
                PackageManagerType.PACMAN to listOf("htop", "fastfetch", "nano", "vim", "tmux", "socat", "wget"),
                PackageManagerType.DNF to listOf("htop", "fastfetch", "nano", "vim", "tmux", "socat", "wget"),
                PackageManagerType.XBPS to listOf("htop", "fastfetch", "nano", "vim", "tmux", "socat", "wget"),
                PackageManagerType.ZYPPER to listOf("htop", "fastfetch", "nano", "vim", "tmux", "socat", "wget")
            )
        )
    )
}

data class ProotBinaryState(
    val isInstalled: Boolean = false,
    val isDownloading: Boolean = false,
    val progress: Float = 0f,
    val binaryPath: String = "",
    val arch: CpuArch = CpuArch.detect(),
    val sizeBytes: Long = 0L,
    val elfVerified: Boolean = false,
    val directExecSupported: Boolean = false,
    val statusMessage: String = "Binário PRoot ainda não baixado",
    val sourceUrl: String = ""
)

data class InstalledContainer(
    val id: String,
    val name: String,
    val distroId: String,
    val release: String,
    val arch: CpuArch,
    val packageManager: PackageManagerType,
    val rootfsPath: String,
    val createdAt: Long,
    val lastRunAt: Long,
    val sizeBytes: Long,
    val installedComponents: Set<String>,
    val selectedPendingComponents: Set<String> = installedComponents,
    val vncPort: Int = 5901,
    val waylandDisplay: String = "wayland-0",
    val accentHex: Long = 0xFF22D3EE
)

enum class InstallPhase(val label: String) {
    IDLE("Pronto"),
    DOWNLOADING_PROOT("Baixando binários PRoot"),
    RESOLVING_DISTRO("Consultando repositório da distro"),
    DOWNLOADING_ROOTFS("Baixando RootFS do container"),
    EXTRACTING_ROOTFS("Extraindo RootFS POSIX"),
    CONFIGURING_CONTAINER("Configurando DNS, Wayland & VNC"),
    INSTALLING_PACKAGES("Executando instalação dos componentes"),
    COMPLETED("Container pronto para uso"),
    ERROR("Falha na instalação")
}

data class InstallJobState(
    val phase: InstallPhase = InstallPhase.IDLE,
    val isRunning: Boolean = false,
    val progress: Float = 0f,
    val detail: String = "",
    val downloadedBytes: Long = 0L,
    val totalBytes: Long = 0L,
    val extractedEntries: Int = 0,
    val logs: List<String> = emptyList(),
    val errorMessage: String? = null
)
