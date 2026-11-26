package com.example.engine

import android.content.Context
import android.system.Os
import com.example.model.CpuArch
import com.example.model.ProotBinaryState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.apache.commons.compress.archivers.ar.ArArchiveInputStream
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream
import org.json.JSONObject
import org.tukaani.xz.XZInputStream
import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.util.concurrent.TimeUnit
import java.util.zip.ZipInputStream

/**
 * Downloads real compiled Android PRoot ELF binaries from:
 * 1. GitHub Releases API (ahmed-alnassif/proot NDK builds with static talloc, ZhymabekRoman/proot-static)
 * 2. Official Termux APT Repository (packages.termux.dev -> extracts bin/proot + libtalloc from .deb)
 * 3. Direct raw binary fallbacks (skirsten/proot-portable-android-binaries)
 *
 * Note: termux/proot-distro only hosts shell scripts in its releases, not compiled binaries.
 */
class ProotBinaryManager(
    private val context: Context,
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()
) {
    private val _state = MutableStateFlow(ProotBinaryState())
    val state: StateFlow<ProotBinaryState> = _state.asStateFlow()

    val binDir: File
        get() = File(context.filesDir, "bin").apply { mkdirs() }

    val libDir: File
        get() = File(context.filesDir, "lib").apply { mkdirs() }

    val prootTmpDir: File
        get() = File(context.cacheDir, "proot_tmp").apply { mkdirs() }

    val prootFile: File
        get() = File(binDir, "proot")

    suspend fun refreshStatus() = withContext(Dispatchers.IO) {
        val arch = CpuArch.detect()
        val file = prootFile
        if (!file.exists() || file.length() < 1024) {
            _state.value = ProotBinaryState(
                isInstalled = false,
                arch = arch,
                binaryPath = file.absolutePath,
                statusMessage = "Binário PRoot (${arch.prootArch}) não instalado"
            )
            return@withContext
        }

        val elfValid = isValidElf(file)
        val execResult = testExecution(file)
        _state.value = ProotBinaryState(
            isInstalled = true,
            isDownloading = false,
            progress = 1f,
            binaryPath = file.absolutePath,
            arch = arch,
            sizeBytes = file.length(),
            elfVerified = elfValid,
            directExecSupported = execResult.first,
            statusMessage = execResult.second
        )
    }

    suspend fun downloadProotBinaries(
        onLog: (String) -> Unit = {}
    ): Result<ProotBinaryState> = withContext(Dispatchers.IO) {
        val arch = CpuArch.detect()
        _state.value = _state.value.copy(
            isDownloading = true,
            progress = 0.05f,
            arch = arch,
            statusMessage = "Buscando binários PRoot (${arch.prootArch})..."
        )

        val candidates = buildList {
            // 1. Resolve latest release assets from ahmed-alnassif/proot (Android NDK + static talloc)
            resolveGitHubReleaseAsset("ahmed-alnassif/proot", arch, onLog)?.let { add(it) }
            // 2. Resolve latest release assets from ZhymabekRoman/proot-static
            resolveGitHubReleaseAsset("ZhymabekRoman/proot-static", arch, onLog)?.let { add(it) }
            // 3. Official Termux APT repository (.deb -> ar -> data.tar.xz -> bin/proot + libtalloc)
            resolveTermuxDebPackage("proot", arch, onLog)?.let { add(it) }
            // 4. Direct portable Android binary fallback
            addAll(directPortableFallbackUrls(arch))
        }.distinct()

        var lastError: Throwable? = null

        for (url in candidates) {
            try {
                onLog("[PRoot] Baixando: $url")
                val tempDownload = File(binDir, "proot.download")
                downloadToFile(url, tempDownload) { frac, kb ->
                    _state.value = _state.value.copy(
                        isDownloading = true,
                        progress = 0.1f + frac * 0.75f,
                        statusMessage = "Baixando PRoot: $kb KB"
                    )
                }

                // Extract based on archive type (.deb, .zip, or raw ELF)
                val extractedOk = when {
                    url.endsWith(".deb", ignoreCase = true) -> {
                        onLog("[PRoot] Extraindo pacote Termux .deb...")
                        val ok = extractProotFromDeb(tempDownload, prootFile)
                        // Also fetch libtalloc from Termux APT so Termux proot links cleanly
                        if (ok) {
                            resolveTermuxDebPackage("libtalloc", arch, onLog)?.let { tallocUrl ->
                                try {
                                    val tallocDeb = File(binDir, "libtalloc.deb")
                                    downloadToFile(tallocUrl, tallocDeb) { _, _ -> }
                                    extractLibsFromDeb(tallocDeb, libDir)
                                    tallocDeb.delete()
                                    onLog("[PRoot] libtalloc extraída para ${libDir.absolutePath}")
                                } catch (_: Throwable) {
                                }
                            }
                        }
                        ok
                    }
                    url.endsWith(".zip", ignoreCase = true) -> {
                        onLog("[PRoot] Extraindo arquivo .zip...")
                        extractProotFromZip(tempDownload, prootFile)
                    }
                    else -> {
                        if (prootFile.exists()) prootFile.delete()
                        tempDownload.copyTo(prootFile, overwrite = true)
                        true
                    }
                }
                tempDownload.delete()

                if (!extractedOk || !isValidElf(prootFile)) {
                    prootFile.delete()
                    throw IllegalStateException("Arquivo extraído não contém um binário ELF PRoot válido")
                }

                makeExecutable(prootFile)
                onLog("[PRoot] Binário ELF verificado (${prootFile.length() / 1024} KB) com chmod 0755")

                val execResult = testExecution(prootFile)
                onLog("[PRoot] Teste de execução: ${execResult.second}")

                val newState = ProotBinaryState(
                    isInstalled = true,
                    isDownloading = false,
                    progress = 1f,
                    binaryPath = prootFile.absolutePath,
                    arch = arch,
                    sizeBytes = prootFile.length(),
                    elfVerified = true,
                    directExecSupported = execResult.first,
                    statusMessage = execResult.second,
                    sourceUrl = url
                )
                _state.value = newState
                return@withContext Result.success(newState)
            } catch (t: Throwable) {
                lastError = t
                onLog("[PRoot] Falha em $url: ${t.message}")
            }
        }

        val errMsg = lastError?.message ?: "Não foi possível baixar o binário PRoot"
        _state.value = _state.value.copy(
            isDownloading = false,
            progress = 0f,
            statusMessage = "Erro: $errMsg"
        )
        Result.failure(lastError ?: IllegalStateException(errMsg))
    }

    private fun resolveGitHubReleaseAsset(
        repo: String,
        arch: CpuArch,
        onLog: (String) -> Unit
    ): String? {
        return try {
            val req = Request.Builder()
                .url("https://api.github.com/repos/$repo/releases/latest")
                .header("Accept", "application/vnd.github+json")
                .header("User-Agent", "ProotBox-Android/1.0")
                .build()
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return null
                val json = JSONObject(resp.body?.string().orEmpty())
                val assets = json.optJSONArray("assets") ?: return null
                val keywords = when (arch) {
                    CpuArch.ARM64 -> listOf("aarch64", "arm64")
                    CpuArch.ARM32 -> listOf("armv7", "armhf", "arm")
                    CpuArch.X86_64 -> listOf("x86_64", "amd64")
                    CpuArch.X86 -> listOf("x86", "i686", "i386")
                }
                for (i in 0 until assets.length()) {
                    val asset = assets.getJSONObject(i)
                    val name = asset.optString("name").lowercase()
                    val url = asset.optString("browser_download_url")
                    if (name.endsWith(".sha256") || name.endsWith(".md5") || name.endsWith(".sig")) continue
                    val matchesArch = keywords.any { kw ->
                        if (kw == "x86") name.contains("x86") && !name.contains("x86_64")
                        else if (kw == "arm") name.contains("arm") && !name.contains("arm64")
                        else name.contains(kw)
                    }
                    if (matchesArch && url.isNotBlank()) {
                        onLog("[PRoot] Asset encontrado em $repo: $name")
                        return url
                    }
                }
                null
            }
        } catch (_: Throwable) {
            null
        }
    }

    private fun resolveTermuxDebPackage(
        packageName: String,
        arch: CpuArch,
        onLog: (String) -> Unit
    ): String? {
        val termuxArch = when (arch) {
            CpuArch.ARM64 -> "aarch64"
            CpuArch.ARM32 -> "arm"
            CpuArch.X86_64 -> "x86_64"
            CpuArch.X86 -> "i686"
        }
        val base = "https://packages.termux.dev/apt/termux-main"
        val indexUrl = "$base/dists/stable/main/binary-$termuxArch/Packages"
        return try {
            val req = Request.Builder()
                .url(indexUrl)
                .header("User-Agent", "ProotBox-Android/1.0")
                .build()
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return null
                val reader = resp.body?.charStream()?.buffered() ?: return null
                var inTargetPkg = false
                var foundFilename: String? = null
                reader.forEachLine { line ->
                    if (foundFilename != null) return@forEachLine
                    if (line.isBlank()) {
                        inTargetPkg = false
                    } else if (line == "Package: $packageName") {
                        inTargetPkg = true
                    } else if (inTargetPkg && line.startsWith("Filename: ")) {
                        foundFilename = line.removePrefix("Filename: ").trim()
                    }
                }
                foundFilename?.let { path ->
                    val full = "$base/$path"
                    onLog("[PRoot] Termux APT ($packageName): $path")
                    full
                }
            }
        } catch (_: Throwable) {
            null
        }
    }

    private fun directPortableFallbackUrls(arch: CpuArch): List<String> {
        return when (arch) {
            CpuArch.ARM64 -> listOf(
                "https://github.com/skirsten/proot-portable-android-binaries/raw/master/aarch64/proot",
                "https://github.com/ahmed-alnassif/proot/releases/latest/download/proot-aarch64.zip"
            )
            CpuArch.ARM32 -> listOf(
                "https://github.com/skirsten/proot-portable-android-binaries/raw/master/armv7l/proot",
                "https://github.com/ahmed-alnassif/proot/releases/latest/download/proot-armv7a.zip"
            )
            CpuArch.X86_64 -> listOf(
                "https://github.com/skirsten/proot-portable-android-binaries/raw/master/x86_64/proot",
                "https://proot.gitlab.io/proot/bin/proot"
            )
            CpuArch.X86 -> listOf(
                "https://github.com/skirsten/proot-portable-android-binaries/raw/master/i686/proot"
            )
        }
    }

    private fun downloadToFile(
        url: String,
        dest: File,
        onProgress: (Float, Long) -> Unit
    ) {
        val req = Request.Builder()
            .url(url)
            .header("User-Agent", "ProotBox-Android/1.0")
            .build()
        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) {
                throw IllegalStateException("HTTP ${resp.code} ao baixar $url")
            }
            val body = resp.body ?: throw IllegalStateException("Resposta vazia")
            val total = body.contentLength()
            body.byteStream().use { input ->
                FileOutputStream(dest).use { output ->
                    val buf = ByteArray(16 * 1024)
                    var downloaded = 0L
                    var read: Int
                    while (input.read(buf).also { read = it } != -1) {
                        output.write(buf, 0, read)
                        downloaded += read
                        val frac = if (total > 0) (downloaded.toFloat() / total).coerceIn(0f, 1f) else 0.5f
                        onProgress(frac, downloaded / 1024)
                    }
                }
            }
        }
    }

    private fun extractProotFromZip(zipFile: File, targetProot: File): Boolean {
        ZipInputStream(BufferedInputStream(FileInputStream(zipFile))).use { zis ->
            var entry = zis.nextEntry
            while (entry != null) {
                if (!entry.isDirectory) {
                    val baseName = entry.name.substringAfterLast('/')
                    if (baseName == "proot" || (baseName.startsWith("proot") && !baseName.endsWith(".so"))) {
                        FileOutputStream(targetProot).use { out -> zis.copyTo(out) }
                        if (isValidElf(targetProot)) return true
                    } else if (baseName.startsWith("lib") && baseName.contains(".so")) {
                        val libOut = File(libDir, baseName)
                        FileOutputStream(libOut).use { out -> zis.copyTo(out) }
                        makeExecutable(libOut)
                    }
                }
                entry = zis.nextEntry
            }
        }
        return false
    }

    private fun extractProotFromDeb(debFile: File, targetProot: File): Boolean {
        var found = false
        forEachDebDataTarEntry(debFile) { entryName, tarStream ->
            val baseName = entryName.substringAfterLast('/')
            if (entryName.endsWith("/bin/proot") || baseName == "proot") {
                FileOutputStream(targetProot).use { out -> tarStream.copyTo(out) }
                found = isValidElf(targetProot)
            } else if (baseName.startsWith("libtalloc") || baseName == "loader") {
                val extra = File(libDir, baseName)
                FileOutputStream(extra).use { out -> tarStream.copyTo(out) }
                makeExecutable(extra)
            }
        }
        return found
    }

    private fun extractLibsFromDeb(debFile: File, destLibDir: File) {
        forEachDebDataTarEntry(debFile) { entryName, tarStream ->
            val baseName = entryName.substringAfterLast('/')
            if (baseName.contains(".so")) {
                val out = File(destLibDir, baseName)
                FileOutputStream(out).use { fos -> tarStream.copyTo(fos) }
                makeExecutable(out)
                // Create libtalloc.so.2 symlink if versioned .so
                if (baseName.startsWith("libtalloc.so.2.")) {
                    val link = File(destLibDir, "libtalloc.so.2")
                    if (!link.exists()) {
                        try {
                            Os.symlink(out.name, link.absolutePath)
                        } catch (_: Throwable) {
                            out.copyTo(link, overwrite = true)
                        }
                    }
                }
            }
        }
    }

    private fun forEachDebDataTarEntry(
        debFile: File,
        onEntry: (String, InputStream) -> Unit
    ) {
        ArArchiveInputStream(BufferedInputStream(FileInputStream(debFile))).use { arIn ->
            var arEntry = arIn.nextEntry
            while (arEntry != null) {
                val name = arEntry.name
                if (name.startsWith("data.tar")) {
                    val decompressed: InputStream = when {
                        name.endsWith(".xz") -> XZInputStream(arIn)
                        name.endsWith(".gz") -> GzipCompressorInputStream(arIn)
                        else -> arIn
                    }
                    TarArchiveInputStream(decompressed).use { tarIn ->
                        var tarEntry = tarIn.nextEntry
                        while (tarEntry != null) {
                            if (tarEntry.isFile) {
                                onEntry(tarEntry.name, tarIn)
                            }
                            tarEntry = tarIn.nextEntry
                        }
                    }
                    break
                }
                arEntry = arIn.nextEntry
            }
        }
    }

    private fun makeExecutable(file: File) {
        try {
            Os.chmod(file.absolutePath, 493) // 0755
        } catch (_: Throwable) {
            file.setExecutable(true, false)
        }
    }

    private fun isValidElf(file: File): Boolean {
        if (!file.exists() || file.length() < 64) return false
        return try {
            file.inputStream().use { ins ->
                val magic = ByteArray(4)
                if (ins.read(magic) < 4) return false
                magic[0] == 0x7F.toByte() &&
                    magic[1] == 'E'.code.toByte() &&
                    magic[2] == 'L'.code.toByte() &&
                    magic[3] == 'F'.code.toByte()
            }
        } catch (_: Throwable) {
            false
        }
    }

    private fun testExecution(file: File): Pair<Boolean, String> {
        return try {
            val pb = ProcessBuilder(file.absolutePath, "--version")
                .redirectErrorStream(true)
            pb.environment()["PROOT_TMP_DIR"] = prootTmpDir.absolutePath
            pb.environment()["LD_LIBRARY_PATH"] = libDir.absolutePath
            val process = pb.start()
            val out = process.inputStream.bufferedReader().readText().trim()
            val code = process.waitFor()
            if (code == 0 || out.contains("PRoot", ignoreCase = true)) {
                true to "PRoot ativo (${out.lineSequence().firstOrNull() ?: "ELF OK"})"
            } else {
                false to "PRoot ELF verificado (código $code)"
            }
        } catch (t: Throwable) {
            val msg = t.message.orEmpty()
            if ("Permission denied" in msg || "error=13" in msg) {
                false to "ELF verificado • SELinux W^X ativo no kernel (modo Shell Bridge disponível)"
            } else {
                false to "ELF verificado • ${msg.ifBlank { "Execução direta indisponível" }}"
            }
        }
    }
}
