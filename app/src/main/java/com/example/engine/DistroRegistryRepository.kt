package com.example.engine

import com.example.model.CpuArch
import com.example.model.DistroCatalogItem
import com.example.model.PackageManagerType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

class DistroRegistryRepository(
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()
) {
    private var cachedLxcEntries: List<LxcIndexEntry>? = null

    data class LxcIndexEntry(
        val distro: String,
        val release: String,
        val arch: String,
        val variant: String,
        val buildId: String,
        val path: String
    )

    fun getFeaturedCatalog(arch: CpuArch = CpuArch.detect()): List<DistroCatalogItem> {
        val lxcArch = arch.lxcArch
        val alpineArch = arch.alpineArch
        val ubuntuArch = arch.ubuntuArch

        return listOf(
            DistroCatalogItem(
                id = "alpine",
                name = "Alpine Linux",
                release = "3.21",
                arch = arch,
                packageManager = PackageManagerType.APK,
                rootfsUrl = "https://dl-cdn.alpinelinux.org/alpine/v3.21/releases/$alpineArch/alpine-minirootfs-3.21.3-$alpineArch.tar.gz",
                fallbackUrl = "https://images.linuxcontainers.org/images/alpine/3.21/$lxcArch/default/",
                estimatedSizeMb = 4,
                description = "Ultra-leve (~4 MB), ideal para terminal rápido, Wayland e containers ágeis",
                accentHex = 0xFF22D3EE,
                sourceLabel = "Alpine CDN Oficial"
            ),
            DistroCatalogItem(
                id = "ubuntu",
                name = "Ubuntu",
                release = "24.04 Noble",
                arch = arch,
                packageManager = PackageManagerType.APT,
                rootfsUrl = "https://cdimage.ubuntu.com/ubuntu-base/releases/24.04/release/ubuntu-base-24.04.2-base-$ubuntuArch.tar.gz",
                fallbackUrl = "https://images.linuxcontainers.org/images/ubuntu/noble/$lxcArch/default/",
                estimatedSizeMb = 32,
                description = "Ubuntu LTS oficial com APT completo, excelente suporte a XFCE4, Sway e Firefox",
                accentHex = 0xFFF97316,
                sourceLabel = "Ubuntu Base Oficial"
            ),
            DistroCatalogItem(
                id = "debian",
                name = "Debian",
                release = "12 Bookworm",
                arch = arch,
                packageManager = PackageManagerType.APT,
                rootfsUrl = "https://images.linuxcontainers.org/images/debian/bookworm/$lxcArch/default/",
                estimatedSizeMb = 48,
                description = "Estável e versátil com amplo catálogo APT para servidores e desktop VNC/Wayland",
                accentHex = 0xFFE11D48,
                sourceLabel = "LinuxContainers LXC"
            ),
            DistroCatalogItem(
                id = "archlinux",
                name = "Arch Linux",
                release = "Current",
                arch = arch,
                packageManager = PackageManagerType.PACMAN,
                rootfsUrl = "https://images.linuxcontainers.org/images/archlinux/current/$lxcArch/default/",
                estimatedSizeMb = 95,
                description = "Rolling release minimalista com Pacman e pacotes Wayland/Sway/Labwc atualizados",
                accentHex = 0xFF38BDF8,
                sourceLabel = "LinuxContainers LXC"
            ),
            DistroCatalogItem(
                id = "fedora",
                name = "Fedora",
                release = "41",
                arch = arch,
                packageManager = PackageManagerType.DNF,
                rootfsUrl = "https://images.linuxcontainers.org/images/fedora/41/$lxcArch/default/",
                estimatedSizeMb = 72,
                description = "Workstation moderna com DNF, Wayland nativo e stack PipeWire/Mesa recente",
                accentHex = 0xFF60A5FA,
                sourceLabel = "LinuxContainers LXC"
            ),
            DistroCatalogItem(
                id = "kali",
                name = "Kali Linux",
                release = "Rolling",
                arch = arch,
                packageManager = PackageManagerType.APT,
                rootfsUrl = "https://images.linuxcontainers.org/images/kali/current/$lxcArch/default/",
                estimatedSizeMb = 88,
                description = "Distribuição Debian-based voltada para auditoria de redes e segurança",
                accentHex = 0xFF818CF8,
                sourceLabel = "LinuxContainers LXC"
            ),
            DistroCatalogItem(
                id = "voidlinux",
                name = "Void Linux",
                release = "Current",
                arch = arch,
                packageManager = PackageManagerType.XBPS,
                rootfsUrl = "https://images.linuxcontainers.org/images/voidlinux/current/$lxcArch/default/",
                estimatedSizeMb = 36,
                description = "Distribuição independente leve com XBPS e runit",
                accentHex = 0xFF10B981,
                sourceLabel = "LinuxContainers LXC"
            ),
            DistroCatalogItem(
                id = "opensuse",
                name = "openSUSE",
                release = "Tumbleweed",
                arch = arch,
                packageManager = PackageManagerType.ZYPPER,
                rootfsUrl = "https://images.linuxcontainers.org/images/opensuse/tumbleweed/$lxcArch/default/",
                estimatedSizeMb = 68,
                description = "Rolling release com Zypper e excelente integração de desktop",
                accentHex = 0xFF34D399,
                sourceLabel = "LinuxContainers LXC"
            )
        )
    }

    suspend fun fetchLxcIndex(forceRefresh: Boolean = false): List<LxcIndexEntry> = withContext(Dispatchers.IO) {
        if (!forceRefresh && cachedLxcEntries != null) {
            return@withContext cachedLxcEntries!!
        }
        val entries = mutableListOf<LxcIndexEntry>()
        try {
            val req = Request.Builder()
                .url("https://images.linuxcontainers.org/meta/1.0/index-user")
                .header("User-Agent", "ProotBox-Android/1.0")
                .build()
            client.newCall(req).execute().use { resp ->
                if (resp.isSuccessful) {
                    val body = resp.body?.string().orEmpty()
                    body.lineSequence().forEach { line ->
                        val parts = line.split(';')
                        if (parts.size >= 6) {
                            entries.add(
                                LxcIndexEntry(
                                    distro = parts[0].trim(),
                                    release = parts[1].trim(),
                                    arch = parts[2].trim(),
                                    variant = parts[3].trim(),
                                    buildId = parts[4].trim(),
                                    path = parts[5].trim()
                                )
                            )
                        }
                    }
                }
            }
        } catch (_: Throwable) {
            // Network unavailable or blocked: caller falls back to direct URLs
        }
        if (entries.isNotEmpty()) {
            cachedLxcEntries = entries
        }
        entries
    }

    suspend fun resolveDistroQuery(
        rawQuery: String,
        arch: CpuArch = CpuArch.detect()
    ): DistroCatalogItem = withContext(Dispatchers.IO) {
        val trimmed = rawQuery.trim()
        if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) {
            val fileName = trimmed.substringAfterLast('/').ifBlank { "custom-rootfs" }
            val guessedPm = PackageManagerType.forDistro(trimmed)
            return@withContext DistroCatalogItem(
                id = "custom_${fileName.take(12).lowercase().replace(Regex("[^a-z0-9]"), "")}",
                name = "Custom RootFS",
                release = fileName.take(24),
                arch = arch,
                packageManager = guessedPm,
                rootfsUrl = trimmed,
                estimatedSizeMb = 45,
                description = "Container personalizado a partir da URL informada",
                accentHex = 0xFFA78BFA,
                sourceLabel = "URL Direta",
                isCustom = true
            )
        }

        val parts = trimmed.lowercase().split(":", limit = 2)
        val distroQuery = normalizeDistroAlias(parts[0].trim())
        val releaseQuery = parts.getOrNull(1)?.trim()

        // Try live LXC index first for exact release + build path
        val lxcIndex = fetchLxcIndex()
        val matchingLxc = lxcIndex.filter { entry ->
            entry.arch.equals(arch.lxcArch, ignoreCase = true) &&
                entry.variant.equals("default", ignoreCase = true) &&
                (entry.distro.equals(distroQuery, ignoreCase = true) ||
                    entry.distro.contains(distroQuery, ignoreCase = true)) &&
                (releaseQuery == null || entry.release.contains(releaseQuery, ignoreCase = true))
        }

        val chosenLxc = matchingLxc.lastOrNull()
        val featured = getFeaturedCatalog(arch).firstOrNull {
            it.id.equals(distroQuery, ignoreCase = true) ||
                it.name.contains(distroQuery, ignoreCase = true)
        }

        if (chosenLxc != null) {
            val basePath = chosenLxc.path.trimStart('/')
            val tarballUrl = "https://images.linuxcontainers.org/$basePath/rootfs.tar.xz"
            val displayName = chosenLxc.distro.replaceFirstChar { it.uppercase() }
            return@withContext DistroCatalogItem(
                id = chosenLxc.distro.lowercase(),
                name = featured?.name ?: displayName,
                release = chosenLxc.release,
                arch = arch,
                packageManager = PackageManagerType.forDistro(chosenLxc.distro),
                rootfsUrl = featured?.rootfsUrl?.takeIf {
                    featured.id == "alpine" || featured.id == "ubuntu"
                } ?: tarballUrl,
                fallbackUrl = tarballUrl,
                estimatedSizeMb = featured?.estimatedSizeMb ?: 45,
                description = featured?.description
                    ?: "Imagem oficial LXC ($displayName ${chosenLxc.release} • ${arch.lxcArch})",
                accentHex = featured?.accentHex ?: 0xFF22D3EE,
                sourceLabel = "LXC Index (${chosenLxc.buildId.take(10)})",
                isCustom = featured == null
            )
        }

        if (featured != null) {
            // If featured points to an LXC directory URL without rootfs.tar.xz, resolve directory listing
            val resolvedUrl = resolveLxcDirectoryIfNeeded(featured.rootfsUrl)
            return@withContext featured.copy(
                release = releaseQuery ?: featured.release,
                rootfsUrl = resolvedUrl
            )
        }

        // Construct best-effort LXC path for typed distro
        val rel = releaseQuery ?: "current"
        val guessedUrl = resolveLxcDirectoryIfNeeded(
            "https://images.linuxcontainers.org/images/$distroQuery/$rel/${arch.lxcArch}/default/"
        )
        val displayName = distroQuery.replaceFirstChar { it.uppercase() }.ifBlank { "Linux" }
        DistroCatalogItem(
            id = distroQuery.ifBlank { "linux" },
            name = displayName,
            release = rel,
            arch = arch,
            packageManager = PackageManagerType.forDistro(distroQuery),
            rootfsUrl = guessedUrl,
            estimatedSizeMb = 45,
            description = "Imagem Linux resolvida dinamicamente para $displayName ($rel)",
            accentHex = 0xFFA78BFA,
            sourceLabel = "LXC Registry",
            isCustom = true
        )
    }

    suspend fun searchLxcDistros(query: String, arch: CpuArch = CpuArch.detect()): List<DistroCatalogItem> =
        withContext(Dispatchers.IO) {
            val q = query.trim().lowercase()
            val featured = getFeaturedCatalog(arch)
            if (q.isBlank()) return@withContext featured

            val localMatches = featured.filter {
                it.name.lowercase().contains(q) ||
                    it.id.lowercase().contains(q) ||
                    it.release.lowercase().contains(q)
            }

            val index = fetchLxcIndex()
            val lxcMatches = index
                .filter {
                    it.arch.equals(arch.lxcArch, ignoreCase = true) &&
                        it.variant == "default" &&
                        (it.distro.lowercase().contains(q) || it.release.lowercase().contains(q))
                }
                .distinctBy { "${it.distro}:${it.release}" }
                .take(8)
                .map { entry ->
                    val basePath = entry.path.trimStart('/')
                    DistroCatalogItem(
                        id = entry.distro.lowercase(),
                        name = entry.distro.replaceFirstChar { c -> c.uppercase() },
                        release = entry.release,
                        arch = arch,
                        packageManager = PackageManagerType.forDistro(entry.distro),
                        rootfsUrl = "https://images.linuxcontainers.org/$basePath/rootfs.tar.xz",
                        estimatedSizeMb = 48,
                        description = "Build LXC ${entry.buildId.take(10)} • ${entry.arch}",
                        accentHex = 0xFF22D3EE,
                        sourceLabel = "LXC Official"
                    )
                }

            (localMatches + lxcMatches).distinctBy { "${it.id}:${it.release}" }
        }

    private fun resolveLxcDirectoryIfNeeded(url: String): String {
        if (!url.endsWith("/")) return url
        return try {
            val req = Request.Builder()
                .url(url)
                .header("User-Agent", "ProotBox-Android/1.0")
                .build()
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return url
                val html = resp.body?.string().orEmpty()
                // Find latest timestamp build directory href="2026.../ "
                val dirRegex = Regex("""href="(\d{8}_\d{2}:\d{2}/?)"""")
                val latestDir = dirRegex.findAll(html).lastOrNull()?.groupValues?.get(1)
                if (latestDir != null) {
                    val cleanSub = latestDir.trimEnd('/')
                    "$url$cleanSub/rootfs.tar.xz"
                } else {
                    "${url}rootfs.tar.xz"
                }
            }
        } catch (_: Throwable) {
            url
        }
    }

    private fun normalizeDistroAlias(raw: String): String = when (raw) {
        "arch" -> "archlinux"
        "void" -> "voidlinux"
        "suse", "tumbleweed" -> "opensuse"
        "rocky" -> "rockylinux"
        "alma" -> "almalinux"
        "kali-linux" -> "kali"
        else -> raw
    }
}
