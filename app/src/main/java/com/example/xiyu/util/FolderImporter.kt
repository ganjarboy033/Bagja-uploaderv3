package com.example.xiyu.util

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import com.example.xiyu.data.model.FileEntry
import java.io.File
import java.io.FileOutputStream

data class FolderImportResult(
    val folderName: String,
    val entries: List<FileEntry>,
    val totalBytes: Long
)

object FolderImporter {

    /**
     * Zero-permission recursive traversal of a user-selected directory via Android SAF (OpenDocumentTree).
     * Streams every file (all extensions & dotfiles unrestricted) directly to disk staging up to 100 MB/file
     * and 500 MB cumulative batch.
     */
    fun importFolderToStaging(
        context: Context,
        treeUri: Uri,
        stagingDir: File,
        removeGit: Boolean = false,
        includeParentFolder: Boolean = false,
        onProgress: (fileCount: Int, currentPath: String, cumulativeBytes: Long) -> Unit = { _, _, _ -> }
    ): FolderImportResult {
        if (!stagingDir.exists()) stagingDir.mkdirs()

        val resolver = context.contentResolver
        val rootDocId = DocumentsContract.getTreeDocumentId(treeUri)
        val rootFolderName = resolveDocumentDisplayName(context, treeUri, rootDocId)
            .ifBlank { "SelectedFolder" }

        val entries = mutableListOf<FileEntry>()
        var cumulativeBytes = 0L
        var fileIndex = 0
        val buffer = ByteArray(8192)

        fun traverse(parentDocId: String, relativeDirPrefix: String) {
            val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, parentDocId)
            val projection = arrayOf(
                DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                DocumentsContract.Document.COLUMN_MIME_TYPE
            )

            resolver.query(childrenUri, projection, null, null, null)?.use { cursor ->
                val idCol = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
                val nameCol = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
                val mimeCol = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_MIME_TYPE)

                while (cursor.moveToNext()) {
                    val childDocId = if (idCol >= 0) cursor.getString(idCol) else continue
                    val childName = if (nameCol >= 0) cursor.getString(nameCol) else continue
                    val mimeType = if (mimeCol >= 0) cursor.getString(mimeCol) ?: "" else ""

                    val relPath = if (relativeDirPrefix.isEmpty()) {
                        ZipExtractor.cleanPath(childName)
                    } else {
                        ZipExtractor.cleanPath("$relativeDirPrefix/$childName")
                    }

                    if (ZipExtractor.shouldSkip(relPath, removeGit)) {
                        continue
                    }

                    val isDirectory = mimeType == DocumentsContract.Document.MIME_TYPE_DIR
                    if (isDirectory) {
                        traverse(childDocId, relPath)
                    } else {
                        if (entries.size >= ZipExtractor.MAX_FILES_COUNT) {
                            throw IllegalStateException("Maksimal ${ZipExtractor.MAX_FILES_COUNT} berkas dalam satu batch upload.")
                        }

                        val fileUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, childDocId)
                        val stagedFile = File(stagingDir, "dir_${fileIndex++}.bin")
                        var fileTotal = 0L

                        val input = resolver.openInputStream(fileUri)
                            ?: throw IllegalStateException("Tidak dapat membuka berkas: $relPath")

                        input.use { stream ->
                            FileOutputStream(stagedFile).use { fos ->
                                var n: Int
                                while (stream.read(buffer).also { n = it } != -1) {
                                    fileTotal += n
                                    cumulativeBytes += n
                                    if (fileTotal > ZipExtractor.MAX_FILE_BYTES) {
                                        stagedFile.delete()
                                        throw IllegalStateException("File \"$relPath\" melebihi batas 100 MB per file.")
                                    }
                                    if (cumulativeBytes > ZipExtractor.MAX_BATCH_BYTES) {
                                        stagedFile.delete()
                                        throw IllegalStateException("Total ukuran folder melebihi batas 500 MB per batch.")
                                    }
                                    fos.write(buffer, 0, n)
                                }
                            }
                        }

                        val finalPath = if (includeParentFolder) "$rootFolderName/$relPath" else relPath
                        entries.add(
                            FileEntry(
                                path = finalPath,
                                contentBase64 = "",
                                size = fileTotal,
                                isSelected = true,
                                rawRelativePath = relPath,
                                localStagingPath = stagedFile.absolutePath
                            )
                        )
                        onProgress(entries.size, finalPath, cumulativeBytes)
                    }
                }
            }
        }

        traverse(rootDocId, "")

        return FolderImportResult(
            folderName = rootFolderName,
            entries = entries,
            totalBytes = cumulativeBytes
        )
    }

    private fun resolveDocumentDisplayName(context: Context, treeUri: Uri, docId: String): String {
        val docUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, docId)
        try {
            context.contentResolver.query(
                docUri,
                arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME),
                null,
                null,
                null
            )?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val idx = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
                    if (idx >= 0) {
                        val value = cursor.getString(idx)
                        if (!value.isNullOrBlank()) return value
                    }
                }
            }
        } catch (_: Exception) {}
        return treeUri.lastPathSegment?.substringAfterLast(':')?.substringAfterLast('/') ?: "SelectedFolder"
    }
}
