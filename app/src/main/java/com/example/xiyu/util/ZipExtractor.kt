package com.example.xiyu.util

import android.util.Base64
import com.example.xiyu.data.model.FileEntry
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.util.zip.ZipInputStream

data class ZipInspectEntry(
    val path: String,
    val size: Long,
    val isDirectory: Boolean
)

object ZipExtractor {

    const val MAX_FILE_BYTES = 100L * 1024L * 1024L // 100 MB per single file (GitHub Git Data limit)
    const val MAX_BATCH_BYTES = 500L * 1024L * 1024L // 500 MB cumulative batch limit
    const val MAX_FILES_COUNT = 5000

    private val GIT_REGEX = Regex("(^|/)\\.git(/|$)", RegexOption.IGNORE_CASE)

    /**
     * Unrestricted file extraction: does NOT filter out dotfiles, config files, binaries, or extensions.
     * Only optionally strips nested .git object folders if explicitly requested by user toggle.
     */
    fun shouldSkip(path: String, removeGit: Boolean): Boolean {
        if (path.isBlank()) return true
        if (removeGit && GIT_REGEX.containsMatchIn(path)) return true
        return false
    }

    fun cleanPath(path: String): String {
        return path.replace("\\", "/")
            .replace(Regex("^\\./"), "")
            .replace(Regex("^/+"), "")
    }

    /**
     * Disk-backed streaming extraction supporting large archives (10MB - 100MB/file, 500MB/batch)
     * without holding Base64 blobs in JVM RAM.
     */
    fun extractZipToStaging(
        inputStream: InputStream,
        stagingDir: File,
        removeGit: Boolean = false,
        onProgress: (Int, String, Long) -> Unit = { _, _, _ -> }
    ): List<FileEntry> {
        if (!stagingDir.exists()) stagingDir.mkdirs()

        val entries = mutableListOf<FileEntry>()
        val zis = ZipInputStream(inputStream)
        var zipEntry = zis.nextEntry
        val buffer = ByteArray(8192)
        var cumulativeBytes = 0L
        var index = 0

        while (zipEntry != null) {
            val name = cleanPath(zipEntry.name)

            if (!zipEntry.isDirectory && !shouldSkip(name, removeGit)) {
                if (entries.size >= MAX_FILES_COUNT) {
                    throw IllegalStateException("Maksimal $MAX_FILES_COUNT berkas dalam satu batch upload.")
                }

                val outFile = File(stagingDir, "zip_${index++}.bin")
                var fileTotal = 0L
                var readBytes: Int

                FileOutputStream(outFile).use { fos ->
                    while (zis.read(buffer).also { readBytes = it } != -1) {
                        fileTotal += readBytes
                        cumulativeBytes += readBytes
                        if (fileTotal > MAX_FILE_BYTES) {
                            outFile.delete()
                            throw IllegalStateException("File \"$name\" melebihi batas 100 MB per file.")
                        }
                        if (cumulativeBytes > MAX_BATCH_BYTES) {
                            outFile.delete()
                            throw IllegalStateException("Total ukuran batch melebihi batas 500 MB.")
                        }
                        fos.write(buffer, 0, readBytes)
                    }
                }

                entries.add(
                    FileEntry(
                        path = name,
                        contentBase64 = "",
                        size = fileTotal,
                        isSelected = true,
                        rawRelativePath = name,
                        localStagingPath = outFile.absolutePath
                    )
                )

                onProgress(entries.size, name, cumulativeBytes)
            }

            zis.closeEntry()
            zipEntry = zis.nextEntry
        }

        // Preserve root structure unless every file shares a single archive wrapper folder
        if (entries.size > 1) {
            val firstSegment = entries.first().path.split("/").firstOrNull()
            if (firstSegment != null &&
                !firstSegment.startsWith(".") &&
                entries.all { it.path.startsWith("$firstSegment/") }
            ) {
                return entries.map {
                    val stripped = it.path.removePrefix("$firstSegment/")
                    it.copy(path = stripped, rawRelativePath = stripped)
                }
            }
        }

        return entries
    }

    /**
     * Streams a raw ZIP or large binary file directly to staging disk without unpacking or OOM.
     */
    fun readRawToStaging(
        inputStream: InputStream,
        targetPath: String,
        stagingDir: File,
        onProgress: (Long) -> Unit = {}
    ): FileEntry {
        if (!stagingDir.exists()) stagingDir.mkdirs()
        val clean = cleanPath(targetPath)
        val outFile = File(stagingDir, "raw_${System.currentTimeMillis()}.bin")
        val buffer = ByteArray(8192)
        var readBytes: Int
        var total = 0L

        FileOutputStream(outFile).use { fos ->
            while (inputStream.read(buffer).also { readBytes = it } != -1) {
                total += readBytes
                if (total > MAX_FILE_BYTES) {
                    outFile.delete()
                    throw IllegalStateException("Berkas \"$clean\" melebihi batas 100 MB per file GitHub.")
                }
                fos.write(buffer, 0, readBytes)
                if (total % (512 * 1024) < 8192) {
                    onProgress(total)
                }
            }
        }

        return FileEntry(
            path = clean,
            contentBase64 = "",
            size = total,
            isSelected = true,
            rawRelativePath = clean,
            localStagingPath = outFile.absolutePath
        )
    }

    // Legacy in-memory wrappers kept for backward compatibility
    fun extractZip(
        inputStream: InputStream,
        removeGit: Boolean = false,
        onProgress: (Int, String) -> Unit = { _, _ -> }
    ): List<FileEntry> {
        val entries = mutableListOf<FileEntry>()
        val zis = ZipInputStream(inputStream)
        var zipEntry = zis.nextEntry
        val buffer = ByteArray(8192)
        var cumulativeBytes = 0L

        while (zipEntry != null) {
            val name = cleanPath(zipEntry.name)

            if (!zipEntry.isDirectory && !shouldSkip(name, removeGit)) {
                if (entries.size >= MAX_FILES_COUNT) {
                    throw IllegalStateException("Maximum 5,000 files allowed per batch upload.")
                }

                val baos = ByteArrayOutputStream()
                var readBytes: Int
                var totalBytes = 0L

                while (zis.read(buffer).also { readBytes = it } != -1) {
                    totalBytes += readBytes
                    cumulativeBytes += readBytes
                    if (totalBytes > MAX_FILE_BYTES) {
                        throw IllegalStateException("File $name exceeds 100 MB single-file limit.")
                    }
                    if (cumulativeBytes > MAX_BATCH_BYTES) {
                        throw IllegalStateException("Total batch exceeds 500 MB limit.")
                    }
                    baos.write(buffer, 0, readBytes)
                }

                val bytes = baos.toByteArray()
                val base64 = Base64.encodeToString(bytes, Base64.NO_WRAP)

                entries.add(
                    FileEntry(
                        path = name,
                        contentBase64 = base64,
                        size = bytes.size.toLong(),
                        isSelected = true,
                        rawRelativePath = name
                    )
                )

                onProgress(entries.size, name)
            }

            zis.closeEntry()
            zipEntry = zis.nextEntry
        }

        if (entries.size > 1) {
            val firstSegment = entries.first().path.split("/").firstOrNull()
            if (firstSegment != null &&
                !firstSegment.startsWith(".") &&
                entries.all { it.path.startsWith("$firstSegment/") }
            ) {
                return entries.map {
                    val stripped = it.path.removePrefix("$firstSegment/")
                    it.copy(path = stripped, rawRelativePath = stripped)
                }
            }
        }

        return entries
    }

    fun readRawAsSingleEntry(inputStream: InputStream, targetPath: String): FileEntry {
        val baos = ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        var readBytes: Int
        var total = 0L
        while (inputStream.read(buffer).also { readBytes = it } != -1) {
            total += readBytes
            if (total > MAX_FILE_BYTES) {
                throw IllegalStateException("File exceeds 100 MB limit.")
            }
            baos.write(buffer, 0, readBytes)
        }
        val bytes = baos.toByteArray()
        val base64 = Base64.encodeToString(bytes, Base64.NO_WRAP)
        val clean = cleanPath(targetPath)
        return FileEntry(
            path = clean,
            contentBase64 = base64,
            size = bytes.size.toLong(),
            isSelected = true,
            rawRelativePath = clean
        )
    }

    /**
     * Inspects any ZIP/APK/JAR file on disk without loading entire payload into RAM.
     */
    fun inspectZipFile(file: File): List<ZipInspectEntry> {
        if (!file.exists()) return emptyList()
        return FileInputStream(file).use { inspectZipStream(it) }
    }

    /**
     * Inspects any ZIP/APK/JAR binary payload to display inner files & sizes in the inspector.
     */
    fun inspectZipBytes(bytes: ByteArray): List<ZipInspectEntry> {
        return inspectZipStream(ByteArrayInputStream(bytes))
    }

    fun inspectZipStream(inputStream: InputStream): List<ZipInspectEntry> {
        val list = mutableListOf<ZipInspectEntry>()
        try {
            ZipInputStream(inputStream).use { zis ->
                var entry = zis.nextEntry
                val buf = ByteArray(4096)
                while (entry != null && list.size < MAX_FILES_COUNT) {
                    var computedSize = entry.size
                    if (computedSize < 0 && !entry.isDirectory) {
                        var count = 0L
                        var n: Int
                        while (zis.read(buf).also { n = it } != -1) {
                            count += n
                        }
                        computedSize = count
                    }
                    list.add(
                        ZipInspectEntry(
                            path = cleanPath(entry.name),
                            size = computedSize.coerceAtLeast(0L),
                            isDirectory = entry.isDirectory
                        )
                    )
                    zis.closeEntry()
                    entry = zis.nextEntry
                }
            }
        } catch (_: Exception) {}
        return list.sortedWith(compareBy({ !it.isDirectory }, { it.path.lowercase() }))
    }

    fun formatSize(bytes: Long): String {
        return when {
            bytes < 1024 -> "$bytes B"
            bytes < 1024 * 1024 -> String.format("%.1f KB", bytes / 1024.0)
            else -> String.format("%.2f MB", bytes / (1024.0 * 1024.0))
        }
    }
}
