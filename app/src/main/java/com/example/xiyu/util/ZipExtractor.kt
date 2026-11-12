package com.example.xiyu.util

import android.util.Base64
import com.example.xiyu.data.model.FileEntry
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.util.zip.ZipInputStream

object ZipExtractor {

    private const val MAX_FILE_BYTES = 12 * 1024 * 1024 // 12 MB
    private const val MAX_FILES_COUNT = 1500

    private val SKIP_REGEX = Regex(
        "(^|/)(node_modules|dist|build|coverage|\\.next|\\.vercel|__MACOSX|\\.idea|\\.vscode)(/|$)|(^|/)(\\.DS_Store|Thumbs\\.db)$",
        RegexOption.IGNORE_CASE
    )

    private val GIT_REGEX = Regex("(^|/)\\.git(/|$)", RegexOption.IGNORE_CASE)

    fun shouldSkip(path: String, removeGit: Boolean): Boolean {
        if (SKIP_REGEX.containsMatchIn(path)) return true
        if (removeGit && GIT_REGEX.containsMatchIn(path)) return true
        return false
    }

    fun cleanPath(path: String): String {
        return path.replace("\\", "/")
            .replace(Regex("^\\./"), "")
            .replace(Regex("^/+"), "")
    }

    fun extractZip(
        inputStream: InputStream,
        removeGit: Boolean,
        onProgress: (Int, String) -> Unit = { _, _ -> }
    ): List<FileEntry> {
        val entries = mutableListOf<FileEntry>()
        val zis = ZipInputStream(inputStream)
        var zipEntry = zis.nextEntry

        val buffer = ByteArray(8192)

        while (zipEntry != null) {
            val name = cleanPath(zipEntry.name)

            if (!zipEntry.isDirectory && !shouldSkip(name, removeGit)) {
                if (entries.size >= MAX_FILES_COUNT) {
                    throw IllegalStateException("Maximum 1,500 files allowed per upload.")
                }

                val baos = ByteArrayOutputStream()
                var readBytes: Int
                var totalBytes = 0L

                while (zis.read(buffer).also { readBytes = it } != -1) {
                    totalBytes += readBytes
                    if (totalBytes > MAX_FILE_BYTES) {
                        throw IllegalStateException("File $name exceeds 12 MB limit.")
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
                        isSelected = true
                    )
                )

                onProgress(entries.size, name)
            }

            zis.closeEntry()
            zipEntry = zis.nextEntry
        }

        // If all files share a common single root folder prefix, trim it (e.g. project-main/src -> src)
        if (entries.isNotEmpty()) {
            val firstSegment = entries.first().path.split("/").firstOrNull()
            if (firstSegment != null && entries.all { it.path.startsWith("$firstSegment/") }) {
                val trimmed = entries.map {
                    it.copy(path = it.path.removePrefix("$firstSegment/"))
                }
                return trimmed
            }
        }

        return entries
    }

    fun formatSize(bytes: Long): String {
        return when {
            bytes < 1024 -> "$bytes B"
            bytes < 1024 * 1024 -> String.format("%.1f KB", bytes / 1024.0)
            else -> String.format("%.2f MB", bytes / (1024.0 * 1024.0))
        }
    }
}
