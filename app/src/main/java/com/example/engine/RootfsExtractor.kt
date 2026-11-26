package com.example.engine

import android.system.Os
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream
import org.tukaani.xz.XZInputStream
import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.util.concurrent.TimeUnit

class RootfsExtractor(
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(25, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()
) {
    data class DownloadProgress(
        val downloadedBytes: Long,
        val totalBytes: Long,
        val fraction: Float
    )

    data class ExtractResult(
        val entryCount: Int,
        val extractedBytes: Long
    )

    suspend fun downloadFile(
        urls: List<String>,
        destinationFile: File,
        onProgress: (DownloadProgress) -> Unit,
        onLog: (String) -> Unit
    ): Result<File> = withContext(Dispatchers.IO) {
        destinationFile.parentFile?.mkdirs()
        var lastError: Throwable? = null

        for (url in urls.filter { it.isNotBlank() }) {
            try {
                onLog("[Download] Conectando: $url")
                val request = Request.Builder()
                    .url(url)
                    .header("User-Agent", "ProotBox-Android/1.0")
                    .build()

                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        throw IllegalStateException("HTTP ${response.code} para $url")
                    }
                    val body = response.body ?: throw IllegalStateException("Corpo vazio para $url")
                    val totalBytes = body.contentLength()
                    onLog("[Download] Tamanho informado: ${if (totalBytes > 0) "${totalBytes / (1024 * 1024)} MB" else "streaming"}")

                    body.byteStream().use { input ->
                        FileOutputStream(destinationFile).use { output ->
                            val buffer = ByteArray(32 * 1024)
                            var downloaded = 0L
                            var read: Int
                            var lastReportTime = 0L
                            while (input.read(buffer).also { read = it } != -1) {
                                output.write(buffer, 0, read)
                                downloaded += read
                                val now = System.currentTimeMillis()
                                if (now - lastReportTime >= 150L) {
                                    lastReportTime = now
                                    val fraction = if (totalBytes > 0) {
                                        (downloaded.toFloat() / totalBytes.toFloat()).coerceIn(0f, 1f)
                                    } else {
                                        0.5f
                                    }
                                    onProgress(DownloadProgress(downloaded, totalBytes, fraction))
                                }
                            }
                            onProgress(DownloadProgress(downloaded, totalBytes, 1f))
                        }
                    }
                }
                onLog("[Download] Concluído (${destinationFile.length() / 1024} KB)")
                return@withContext Result.success(destinationFile)
            } catch (t: Throwable) {
                lastError = t
                onLog("[Download] Falha em $url: ${t.message}")
            }
        }
        Result.failure(lastError ?: IllegalStateException("Falha ao baixar rootfs"))
    }

    suspend fun extractTarArchive(
        archiveFile: File,
        targetRootfsDir: File,
        onEntryExtracted: (count: Int, bytes: Long, currentName: String) -> Unit,
        onLog: (String) -> Unit
    ): Result<ExtractResult> = withContext(Dispatchers.IO) {
        try {
            if (targetRootfsDir.exists()) {
                targetRootfsDir.deleteRecursively()
            }
            targetRootfsDir.mkdirs()

            val canonicalRoot = targetRootfsDir.canonicalPath
            var count = 0
            var totalExtractedBytes = 0L
            val deferredLinks = mutableListOf<Pair<File, String>>()

            BufferedInputStream(FileInputStream(archiveFile), 64 * 1024).use { buffered ->
                val decompressedStream: InputStream = wrapDecompressor(archiveFile.name, buffered)
                TarArchiveInputStream(decompressedStream).use { tarIn ->
                    var entry: TarArchiveEntry? = tarIn.nextEntry
                    val buffer = ByteArray(32 * 1024)

                    while (entry != null) {
                        val cleanName = entry.name.removePrefix("./").removePrefix("/")
                        if (cleanName.isBlank() || cleanName.startsWith("dev/")) {
                            entry = tarIn.nextEntry
                            continue
                        }

                        val outFile = File(targetRootfsDir, cleanName)
                        val canonicalOut = outFile.canonicalPath
                        if (!canonicalOut.startsWith(canonicalRoot)) {
                            entry = tarIn.nextEntry
                            continue
                        }

                        when {
                            entry.isDirectory -> {
                                unlinkIfSymlink(outFile)
                                outFile.mkdirs()
                                applyModeSafe(outFile, entry.mode)
                            }
                            entry.isSymbolicLink -> {
                                outFile.parentFile?.mkdirs()
                                unlinkForce(outFile)
                                try {
                                    Os.symlink(entry.linkName, outFile.absolutePath)
                                } catch (_: Throwable) {
                                    deferredLinks.add(outFile to entry.linkName)
                                }
                            }
                            entry.isLink -> {
                                val target = File(targetRootfsDir, entry.linkName.removePrefix("./").removePrefix("/"))
                                outFile.parentFile?.mkdirs()
                                unlinkForce(outFile)
                                if (target.exists() && target.isFile) {
                                    target.copyTo(outFile, overwrite = true)
                                } else {
                                    deferredLinks.add(outFile to target.absolutePath)
                                }
                            }
                            entry.isFile -> {
                                outFile.parentFile?.mkdirs()
                                unlinkForce(outFile)
                                try {
                                    FileOutputStream(outFile).use { fos ->
                                        var len: Int
                                        while (tarIn.read(buffer).also { len = it } != -1) {
                                            fos.write(buffer, 0, len)
                                            totalExtractedBytes += len
                                        }
                                    }
                                    applyModeSafe(outFile, entry.mode)
                                } catch (_: Throwable) {
                                    // Skip special files whose parent is a broken symlink inside archive
                                }
                            }
                        }

                        count++
                        if (count % 120 == 0) {
                            onEntryExtracted(count, totalExtractedBytes, cleanName)
                        }
                        entry = tarIn.nextEntry
                    }
                }
            }

            // Retry any deferred symlinks
            for ((linkFile, targetPath) in deferredLinks) {
                try {
                    if (!linkFile.exists()) {
                        Os.symlink(targetPath, linkFile.absolutePath)
                    }
                } catch (_: Throwable) {
                }
            }

            onEntryExtracted(count, totalExtractedBytes, "Concluído")
            onLog("[Extract] $count entradas extraídas (${totalExtractedBytes / (1024 * 1024)} MB)")
            Result.success(ExtractResult(count, totalExtractedBytes))
        } catch (t: Throwable) {
            onLog("[Extract] Erro: ${t.message}")
            Result.failure(t)
        }
    }

    private fun wrapDecompressor(fileName: String, buffered: BufferedInputStream): InputStream {
        val lower = fileName.lowercase()
        if (lower.endsWith(".xz") || lower.endsWith(".txz")) {
            return XZInputStream(buffered)
        }
        if (lower.endsWith(".gz") || lower.endsWith(".tgz")) {
            return GzipCompressorInputStream(buffered)
        }
        // Inspect magic bytes if filename doesn't have extension
        buffered.mark(8)
        val header = ByteArray(6)
        val read = buffered.read(header)
        buffered.reset()
        if (read >= 2 && header[0] == 0x1F.toByte() && header[1] == 0x8B.toByte()) {
            return GzipCompressorInputStream(buffered)
        }
        if (read >= 6 &&
            header[0] == 0xFD.toByte() &&
            header[1] == 0x37.toByte() &&
            header[2] == 0x7A.toByte() &&
            header[3] == 0x58.toByte() &&
            header[4] == 0x5A.toByte() &&
            header[5] == 0x00.toByte()
        ) {
            return XZInputStream(buffered)
        }
        return buffered
    }

    private fun unlinkForce(file: File) {
        try {
            Os.remove(file.absolutePath)
        } catch (_: Throwable) {
            if (file.exists()) file.delete()
        }
    }

    private fun unlinkIfSymlink(file: File) {
        // If File.exists() is false or Os.lstat shows symlink, remove it before mkdirs()
        try {
            val st = Os.lstat(file.absolutePath)
            if (android.system.OsConstants.S_ISLNK(st.st_mode)) {
                Os.remove(file.absolutePath)
            }
        } catch (_: Throwable) {
        }
    }

    private fun applyModeSafe(file: File, mode: Int) {
        if (mode <= 0) return
        try {
            Os.chmod(file.absolutePath, mode and 0x1FF)
        } catch (_: Throwable) {
            if ((mode and 0x49) != 0) {
                file.setExecutable(true, false)
            }
        }
    }
}
