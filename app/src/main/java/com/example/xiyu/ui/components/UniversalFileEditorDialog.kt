package com.example.xiyu.ui.components

import android.graphics.BitmapFactory
import android.media.MediaPlayer
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.example.xiyu.ui.theme.*
import com.example.xiyu.util.ZipExtractor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File

private enum class EditorMode {
    CODE,
    TREE_KV,
    MARKDOWN_PREVIEW,
    BINARY_ARCHIVE,
    MEDIA_IMAGE,
    MEDIA_AUDIO
}

@Composable
fun UniversalFileEditorDialog(
    fileName: String,
    filePath: String,
    initialBytes: ByteArray,
    allowRemoteCommit: Boolean = true,
    isSaving: Boolean = false,
    onDismiss: () -> Unit,
    onSaveBytes: (newBytes: ByteArray, commitMessage: String) -> Unit
) {
    val context = LocalContext.current
    val toastController = LocalToastController.current
    val ext = remember(fileName) { fileName.substringAfterLast('.', "").lowercase() }

    val isImageExt = remember(ext) { ext in setOf("png", "jpg", "jpeg", "webp", "gif", "bmp") }
    val isAudioExt = remember(ext) { ext in setOf("mp3", "wav", "ogg", "m4a", "aac", "flac") }
    val isArchiveExt = remember(ext) { ext in setOf("zip", "apk", "jar", "aab", "aar") }
    val isJsonExt = remember(ext) { ext == "json" }
    val isYamlExt = remember(ext) { ext == "yml" || ext == "yaml" }
    val isMarkdownExt = remember(ext) { ext == "md" || ext == "markdown" }

    val looksBinary = remember(initialBytes, ext) {
        if (isImageExt || isAudioExt || isArchiveExt) return@remember true
        if (ext in setOf("so", "dex", "class", "bin", "pdf", "ttf", "otf", "woff", "woff2", "exe", "dll", "ico")) {
            return@remember true
        }
        val sampleSize = minOf(initialBytes.size, 1024)
        var nonPrintable = 0
        for (i in 0 until sampleSize) {
            val b = initialBytes[i].toInt() and 0xFF
            if (b == 0) return@remember true
            if (b < 0x09 || (b in 0x0E..0x1F)) nonPrintable++
        }
        sampleSize > 0 && (nonPrintable.toFloat() / sampleSize) > 0.25f
    }

    var workingBytes by remember(initialBytes) { mutableStateOf(initialBytes) }
    var textBuffer by remember(initialBytes) {
        mutableStateOf(
            if (looksBinary) "" else initialBytes.toString(Charsets.UTF_8)
        )
    }
    var forcedTextEdit by remember { mutableStateOf(false) }

    var activeMode by remember(fileName, looksBinary) {
        mutableStateOf(
            when {
                isImageExt -> EditorMode.MEDIA_IMAGE
                isAudioExt -> EditorMode.MEDIA_AUDIO
                isArchiveExt || looksBinary -> EditorMode.BINARY_ARCHIVE
                else -> EditorMode.CODE
            }
        )
    }

    var commitMessage by remember(fileName) {
        mutableStateOf("Update $fileName via kruzenlab-uploader")
    }
    var showFindReplace by remember { mutableStateOf(false) }
    var findQuery by remember { mutableStateOf("") }
    var replaceQuery by remember { mutableStateOf("") }
    var wordWrap by remember { mutableStateOf(true) }
    var showSyntaxPreview by remember { mutableStateOf(false) }

    val replaceBinaryLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri != null) {
            try {
                context.contentResolver.openInputStream(uri)?.use { stream ->
                    val newBytes = stream.readBytes()
                    workingBytes = newBytes
                    forcedTextEdit = false
                    toastController.showSuccess("Payload biner diganti (${ZipExtractor.formatSize(newBytes.size.toLong())})")
                }
            } catch (e: Exception) {
                toastController.showError("Gagal membaca file pengganti: ${e.localizedMessage}")
            }
        }
    }

    Dialog(
        onDismissRequest = { if (!isSaving) onDismiss() },
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth(0.95f)
                .fillMaxHeight(0.90f)
                .testTag("universal_file_editor_dialog"),
            shape = RoundedCornerShape(24.dp),
            color = BrandCream,
            border = androidx.compose.foundation.BorderStroke(1.dp, BrandBorder),
            tonalElevation = 6.dp
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(16.dp)
            ) {
                // Header
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            Icon(
                                imageVector = when {
                                    isImageExt -> Icons.Default.Image
                                    isAudioExt -> Icons.Default.AudioFile
                                    isArchiveExt -> Icons.Default.FolderZip
                                    looksBinary && !forcedTextEdit -> Icons.Default.Memory
                                    else -> Icons.Default.Code
                                },
                                contentDescription = null,
                                tint = BrandRose,
                                modifier = Modifier.size(18.dp)
                            )
                            Text(
                                text = fileName,
                                fontSize = 15.sp,
                                fontWeight = FontWeight.Bold,
                                color = BrandDark,
                                maxLines = 1
                            )
                        }
                        ThinHighlightText(
                            text = "$filePath · ${ZipExtractor.formatSize(workingBytes.size.toLong())}",
                            maxLines = 1
                        )
                    }

                    IconButton(
                        onClick = { if (!isSaving) onDismiss() },
                        modifier = Modifier.size(34.dp)
                    ) {
                        Icon(Icons.Default.Close, contentDescription = "Tutup Editor", tint = BrandDark)
                    }
                }

                Spacer(modifier = Modifier.height(10.dp))

                // Mode Switcher Bar
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (!looksBinary || forcedTextEdit) {
                        ModeChip(
                            label = "Kode",
                            icon = Icons.Default.Edit,
                            selected = activeMode == EditorMode.CODE,
                            onClick = { activeMode = EditorMode.CODE }
                        )
                    }
                    if (isJsonExt || isYamlExt) {
                        ModeChip(
                            label = "Tree Key-Value",
                            icon = Icons.Default.AccountTree,
                            selected = activeMode == EditorMode.TREE_KV,
                            onClick = { activeMode = EditorMode.TREE_KV }
                        )
                    }
                    if (isMarkdownExt) {
                        ModeChip(
                            label = "Preview MD",
                            icon = Icons.Default.Visibility,
                            selected = activeMode == EditorMode.MARKDOWN_PREVIEW,
                            onClick = { activeMode = EditorMode.MARKDOWN_PREVIEW }
                        )
                    }
                    if (isImageExt) {
                        ModeChip(
                            label = "Gambar",
                            icon = Icons.Default.Image,
                            selected = activeMode == EditorMode.MEDIA_IMAGE,
                            onClick = { activeMode = EditorMode.MEDIA_IMAGE }
                        )
                    }
                    if (isAudioExt) {
                        ModeChip(
                            label = "Audio",
                            icon = Icons.Default.PlayArrow,
                            selected = activeMode == EditorMode.MEDIA_AUDIO,
                            onClick = { activeMode = EditorMode.MEDIA_AUDIO }
                        )
                    }
                    if (isArchiveExt || looksBinary) {
                        ModeChip(
                            label = if (isArchiveExt) "Isi Arsip" else "Biner / Hex",
                            icon = Icons.Default.Memory,
                            selected = activeMode == EditorMode.BINARY_ARCHIVE,
                            onClick = { activeMode = EditorMode.BINARY_ARCHIVE }
                        )
                    }
                    if (looksBinary && !forcedTextEdit) {
                        OutlinedButton(
                            onClick = {
                                forcedTextEdit = true
                                textBuffer = workingBytes.toString(Charsets.UTF_8)
                                activeMode = EditorMode.CODE
                                toastController.showWarning("Mode Teks Paksa aktif untuk file biner")
                            },
                            shape = RoundedCornerShape(12.dp),
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                            modifier = Modifier.height(30.dp)
                        ) {
                            Text("Paksa Edit Teks", fontSize = 10.sp, color = BrandRose)
                        }
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))

                // Main Editor / Inspector Surface
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    shape = RoundedCornerShape(16.dp),
                    color = BrandCardBg,
                    border = androidx.compose.foundation.BorderStroke(1.dp, BrandBorder)
                ) {
                    when (activeMode) {
                        EditorMode.CODE -> {
                            CodeEditorPane(
                                text = textBuffer,
                                onTextChange = { textBuffer = it },
                                fileExtension = ext,
                                showFindReplace = showFindReplace,
                                onToggleFindReplace = { showFindReplace = !showFindReplace },
                                findQuery = findQuery,
                                onFindQueryChange = { findQuery = it },
                                replaceQuery = replaceQuery,
                                onReplaceQueryChange = { replaceQuery = it },
                                wordWrap = wordWrap,
                                onToggleWordWrap = { wordWrap = !wordWrap },
                                showSyntaxPreview = showSyntaxPreview,
                                onToggleSyntaxPreview = { showSyntaxPreview = !showSyntaxPreview }
                            )
                        }

                        EditorMode.TREE_KV -> {
                            KeyValueTreeEditorPane(
                                rawText = textBuffer,
                                isJson = isJsonExt,
                                onUpdatedText = { textBuffer = it }
                            )
                        }

                        EditorMode.MARKDOWN_PREVIEW -> {
                            MarkdownPreviewPane(markdown = textBuffer)
                        }

                        EditorMode.MEDIA_IMAGE -> {
                            MediaImagePane(bytes = workingBytes)
                        }

                        EditorMode.MEDIA_AUDIO -> {
                            MediaAudioPane(fileName = fileName, bytes = workingBytes)
                        }

                        EditorMode.BINARY_ARCHIVE -> {
                            BinaryAndArchivePane(
                                fileName = fileName,
                                bytes = workingBytes,
                                isArchive = isArchiveExt,
                                onReplaceBinary = { replaceBinaryLauncher.launch(arrayOf("*/*")) }
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(10.dp))

                // Footer commit & action buttons
                if (allowRemoteCommit) {
                    OutlinedTextField(
                        value = commitMessage,
                        onValueChange = { commitMessage = it },
                        label = { Text("Pesan Commit", fontSize = 11.sp) },
                        singleLine = true,
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    OutlinedButton(
                        onClick = { if (!isSaving) onDismiss() },
                        enabled = !isSaving,
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("Tutup")
                    }

                    val canSave = (!looksBinary || forcedTextEdit || workingBytes !== initialBytes)
                    if (canSave) {
                        Button(
                            onClick = {
                                val finalBytes = if (activeMode == EditorMode.CODE || activeMode == EditorMode.TREE_KV || activeMode == EditorMode.MARKDOWN_PREVIEW || forcedTextEdit) {
                                    textBuffer.toByteArray(Charsets.UTF_8)
                                } else {
                                    workingBytes
                                }
                                onSaveBytes(finalBytes, commitMessage.ifBlank { "Update $fileName" })
                            },
                            enabled = !isSaving,
                            colors = ButtonDefaults.buttonColors(containerColor = BrandDark, contentColor = BrandCream),
                            shape = RoundedCornerShape(14.dp),
                            modifier = Modifier
                                .weight(1.4f)
                                .testTag("save_file_editor_btn")
                        ) {
                            if (isSaving) {
                                CircularProgressIndicator(color = BrandCream, modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("Menyimpan...")
                            } else {
                                Icon(Icons.Default.Save, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(if (allowRemoteCommit) "Simpan & Commit" else "Simpan Perubahan", fontSize = 12.sp)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ModeChip(
    label: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    selected: Boolean,
    onClick: () -> Unit
) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = { Text(label, fontSize = 11.sp) },
        leadingIcon = { Icon(icon, contentDescription = null, modifier = Modifier.size(14.dp)) },
        shape = RoundedCornerShape(14.dp),
        colors = FilterChipDefaults.filterChipColors(
            selectedContainerColor = BrandDark,
            selectedLabelColor = BrandCream,
            selectedLeadingIconColor = BrandAccent
        )
    )
}

@Composable
private fun CodeEditorPane(
    text: String,
    onTextChange: (String) -> Unit,
    fileExtension: String,
    showFindReplace: Boolean,
    onToggleFindReplace: () -> Unit,
    findQuery: String,
    onFindQueryChange: (String) -> Unit,
    replaceQuery: String,
    onReplaceQueryChange: (String) -> Unit,
    wordWrap: Boolean,
    onToggleWordWrap: () -> Unit,
    showSyntaxPreview: Boolean,
    onToggleSyntaxPreview: () -> Unit
) {
    val linesCount = remember(text) { maxOf(1, text.count { it == '\n' } + 1) }
    val matchCount = remember(text, findQuery) {
        if (findQuery.isBlank()) 0
        else Regex.escape(findQuery).toRegex(RegexOption.IGNORE_CASE).findAll(text).count()
    }

    Column(modifier = Modifier.fillMaxSize().padding(10.dp)) {
        // Toolbar
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            FilterChip(
                selected = showFindReplace,
                onClick = onToggleFindReplace,
                label = { Text("Cari & Ganti", fontSize = 10.sp) },
                leadingIcon = { Icon(Icons.Default.Search, null, modifier = Modifier.size(13.dp)) }
            )
            FilterChip(
                selected = wordWrap,
                onClick = onToggleWordWrap,
                label = { Text("Word Wrap", fontSize = 10.sp) },
                leadingIcon = { Icon(Icons.Default.WrapText, null, modifier = Modifier.size(13.dp)) }
            )
            FilterChip(
                selected = showSyntaxPreview,
                onClick = onToggleSyntaxPreview,
                label = { Text(if (showSyntaxPreview) "Mode Edit" else "Syntax Highlight", fontSize = 10.sp) },
                leadingIcon = { Icon(Icons.Default.Palette, null, modifier = Modifier.size(13.dp)) }
            )
            ThinHighlightBadge("$linesCount baris")
        }

        if (showFindReplace) {
            Spacer(modifier = Modifier.height(6.dp))
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(BrandCream, RoundedCornerShape(10.dp))
                    .padding(8.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = findQuery,
                        onValueChange = onFindQueryChange,
                        placeholder = { Text("Cari ($matchCount cocok)", fontSize = 11.sp) },
                        singleLine = true,
                        modifier = Modifier.weight(1f)
                    )
                    OutlinedTextField(
                        value = replaceQuery,
                        onValueChange = onReplaceQueryChange,
                        placeholder = { Text("Ganti dengan...", fontSize = 11.sp) },
                        singleLine = true,
                        modifier = Modifier.weight(1f)
                    )
                }
                Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
                    Button(
                        onClick = {
                            if (findQuery.isNotBlank()) {
                                onTextChange(text.replace(findQuery, replaceQuery, ignoreCase = false))
                            }
                        },
                        enabled = findQuery.isNotBlank() && matchCount > 0,
                        colors = ButtonDefaults.buttonColors(containerColor = BrandDark),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.height(30.dp)
                    ) {
                        Text("Ganti Semua ($matchCount)", fontSize = 10.sp)
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(6.dp))

        val vScroll = rememberScrollState()
        val hScroll = rememberScrollState()

        Row(
            modifier = Modifier
                .fillMaxSize()
                .background(BrandCream.copy(alpha = 0.5f), RoundedCornerShape(12.dp))
                .border(0.5.dp, BrandBorder, RoundedCornerShape(12.dp))
                .verticalScroll(vScroll)
                .padding(8.dp)
        ) {
            // Line numbers gutter
            Column(
                modifier = Modifier
                    .padding(end = 8.dp)
                    .background(BrandPalePink.copy(alpha = 0.55f), RoundedCornerShape(6.dp))
                    .padding(horizontal = 6.dp, vertical = 4.dp)
            ) {
                for (i in 1..linesCount) {
                    Text(
                        text = i.toString(),
                        fontFamily = FontFamily.Monospace,
                        fontSize = 11.sp,
                        lineHeight = 16.sp,
                        color = BrandTextMuted
                    )
                }
            }

            val codeModifier = if (wordWrap) {
                Modifier.weight(1f)
            } else {
                Modifier
                    .weight(1f)
                    .horizontalScroll(hScroll)
            }

            if (showSyntaxPreview) {
                Text(
                    text = remember(text, fileExtension) { highlightCodeSyntax(text, fileExtension) },
                    fontFamily = FontFamily.Monospace,
                    fontSize = 12.sp,
                    lineHeight = 16.sp,
                    modifier = codeModifier.padding(vertical = 4.dp)
                )
            } else {
                androidx.compose.foundation.text.BasicTextField(
                    value = text,
                    onValueChange = onTextChange,
                    textStyle = TextStyle(
                        fontFamily = FontFamily.Monospace,
                        fontSize = 12.sp,
                        lineHeight = 16.sp,
                        color = BrandDark
                    ),
                    modifier = codeModifier.padding(vertical = 4.dp)
                )
            }
        }
    }
}

private fun highlightCodeSyntax(code: String, ext: String): androidx.compose.ui.text.AnnotatedString {
    val keywords = setOf(
        "package", "import", "fun", "class", "val", "var", "return", "if", "else", "when", "for",
        "while", "suspend", "object", "data", "private", "public", "override", "true", "false", "null",
        "const", "let", "function", "export", "default", "async", "await", "def", "from", "name", "on", "jobs", "steps"
    )
    return buildAnnotatedString {
        val lines = code.split("\n")
        lines.forEachIndexed { idx, line ->
            val trimmed = line.trimStart()
            when {
                trimmed.startsWith("//") || trimmed.startsWith("#") || trimmed.startsWith("<!--") -> {
                    withStyle(SpanStyle(color = Color(0xFF6B7280))) { append(line) }
                }
                else -> {
                    // Simple tokenizer per line for readable keyword/string coloring
                    val regex = Regex("(\"[^\"]*\"|'[^']*'|\\b[A-Za-z_][A-Za-z0-9_]*\\b)")
                    var lastIndex = 0
                    for (match in regex.findAll(line)) {
                        if (match.range.first > lastIndex) {
                            append(line.substring(lastIndex, match.range.first))
                        }
                        val token = match.value
                        when {
                            token.startsWith("\"") || token.startsWith("'") -> {
                                withStyle(SpanStyle(color = Color(0xFF15803D))) { append(token) }
                            }
                            token in keywords || (ext in setOf("yml", "yaml") && line.contains("$token:")) -> {
                                withStyle(SpanStyle(color = Color(0xFF9D174D), fontWeight = FontWeight.SemiBold)) { append(token) }
                            }
                            else -> append(token)
                        }
                        lastIndex = match.range.last + 1
                    }
                    if (lastIndex < line.length) {
                        append(line.substring(lastIndex))
                    }
                }
            }
            if (idx < lines.size - 1) append("\n")
        }
    }
}

@Composable
private fun KeyValueTreeEditorPane(
    rawText: String,
    isJson: Boolean,
    onUpdatedText: (String) -> Unit
) {
    val pairs = remember(rawText, isJson) {
        val list = mutableListOf<Pair<String, String>>()
        if (isJson) {
            try {
                val obj = JSONObject(rawText)
                val keys = obj.keys()
                while (keys.hasNext()) {
                    val k = keys.next()
                    list.add(k to obj.get(k).toString())
                }
            } catch (_: Exception) {}
        } else {
            rawText.lines().forEach { line ->
                val trimmed = line.trim()
                if (trimmed.isNotEmpty() && !trimmed.startsWith("#") && trimmed.contains(":")) {
                    val idx = line.indexOf(":")
                    val k = line.substring(0, idx)
                    val v = line.substring(idx + 1).trim()
                    list.add(k to v)
                }
            }
        }
        list
    }

    var editableRows by remember(pairs) { mutableStateOf<List<Pair<String, String>>>(pairs) }
    var newKey by remember { mutableStateOf("") }
    var newValue by remember { mutableStateOf("") }

    fun syncBack(updatedRows: List<Pair<String, String>>) {
        editableRows = updatedRows
        if (isJson) {
            val obj = JSONObject()
            updatedRows.forEach { (k, v) ->
                val typed: Any = when {
                    v.equals("true", true) -> true
                    v.equals("false", true) -> false
                    v.toLongOrNull() != null -> v.toLong()
                    v.toDoubleOrNull() != null -> v.toDouble()
                    else -> v
                }
                obj.put(k, typed)
            }
            onUpdatedText(obj.toString(2))
        } else {
            onUpdatedText(updatedRows.joinToString("\n") { (k, v) -> "$k: $v" })
        }
    }

    Column(modifier = Modifier.fillMaxSize().padding(12.dp)) {
        Text(
            text = if (isJson) "JSON Key-Value Tree Editor" else "YAML Key-Value Editor",
            fontWeight = FontWeight.Bold,
            fontSize = 13.sp,
            color = BrandDark
        )
        ThinHighlightText("Edit pasangan key-value secara langsung atau tambah entri baru.")
        Spacer(modifier = Modifier.height(8.dp))

        if (editableRows.isEmpty()) {
            Box(modifier = Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                ThinHighlightText("Struktur belum ter-parse atau kosong. Gunakan tab Kode atau tambahkan kunci baru di bawah.")
            }
        } else {
            LazyColumn(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                items(editableRows.size) { index ->
                    val (k, v) = editableRows[index]
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        OutlinedTextField(
                            value = k,
                            onValueChange = { updatedK ->
                                val next = editableRows.toMutableList()
                                next[index] = updatedK to v
                                syncBack(next)
                            },
                            label = { Text("Key", fontSize = 10.sp) },
                            singleLine = true,
                            modifier = Modifier.weight(0.9f)
                        )
                        OutlinedTextField(
                            value = v,
                            onValueChange = { updatedV ->
                                val next = editableRows.toMutableList()
                                next[index] = k to updatedV
                                syncBack(next)
                            },
                            label = { Text("Value", fontSize = 10.sp) },
                            singleLine = true,
                            modifier = Modifier.weight(1.2f)
                        )
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(8.dp))
        HorizontalDivider(color = BrandBorder)
        Spacer(modifier = Modifier.height(8.dp))

        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = newKey,
                onValueChange = { newKey = it },
                placeholder = { Text("Key baru", fontSize = 11.sp) },
                singleLine = true,
                modifier = Modifier.weight(1f)
            )
            OutlinedTextField(
                value = newValue,
                onValueChange = { newValue = it },
                placeholder = { Text("Nilai baru", fontSize = 11.sp) },
                singleLine = true,
                modifier = Modifier.weight(1f)
            )
            Button(
                onClick = {
                    if (newKey.isNotBlank()) {
                        syncBack(editableRows + (newKey.trim() to newValue))
                        newKey = ""
                        newValue = ""
                    }
                },
                colors = ButtonDefaults.buttonColors(containerColor = BrandDark),
                contentPadding = PaddingValues(horizontal = 10.dp),
                shape = RoundedCornerShape(10.dp)
            ) {
                Icon(Icons.Default.Add, contentDescription = "Tambah Key", modifier = Modifier.size(16.dp))
            }
        }
    }
}

@Composable
private fun MarkdownPreviewPane(markdown: String) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        var inCodeBlock = false
        markdown.lines().forEach { line ->
            val trimmed = line.trim()
            if (trimmed.startsWith("```")) {
                inCodeBlock = !inCodeBlock
                return@forEach
            }
            when {
                inCodeBlock -> {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(BrandDark, RoundedCornerShape(6.dp))
                            .padding(horizontal = 10.dp, vertical = 4.dp)
                    ) {
                        Text(
                            text = line,
                            fontFamily = FontFamily.Monospace,
                            fontSize = 11.sp,
                            color = BrandCream
                        )
                    }
                }
                trimmed.startsWith("### ") -> Text(
                    text = trimmed.removePrefix("### "),
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold,
                    color = BrandDark
                )
                trimmed.startsWith("## ") -> Text(
                    text = trimmed.removePrefix("## "),
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                    color = BrandDark
                )
                trimmed.startsWith("# ") -> {
                    Text(
                        text = trimmed.removePrefix("# "),
                        fontSize = 21.sp,
                        fontWeight = FontWeight.ExtraBold,
                        color = BrandDark
                    )
                    HorizontalDivider(color = BrandBorder)
                }
                trimmed.startsWith("> ") -> Surface(
                    color = BrandPalePink.copy(alpha = 0.6f),
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = trimmed.removePrefix("> "),
                        fontSize = 12.sp,
                        color = BrandRose,
                        modifier = Modifier.padding(10.dp)
                    )
                }
                trimmed.startsWith("- ") || trimmed.startsWith("* ") -> Row {
                    Text("• ", fontWeight = FontWeight.Bold, color = BrandRose)
                    Text(trimmed.drop(2), fontSize = 13.sp, color = BrandDark)
                }
                trimmed.isNotEmpty() -> Text(text = line, fontSize = 13.sp, color = BrandDark, lineHeight = 19.sp)
            }
        }
    }
}

@Composable
private fun MediaImagePane(bytes: ByteArray) {
    val bitmap = remember(bytes) {
        try {
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap()
        } catch (_: Exception) {
            null
        }
    }
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(12.dp),
        contentAlignment = Alignment.Center
    ) {
        if (bitmap != null) {
            Image(
                bitmap = bitmap,
                contentDescription = "Preview Gambar",
                contentScale = ContentScale.Fit,
                modifier = Modifier
                    .fillMaxSize()
                    .clip(RoundedCornerShape(12.dp))
            )
        } else {
            ThinHighlightText("Gambar tidak dapat didekode (${ZipExtractor.formatSize(bytes.size.toLong())}).")
        }
    }
}

@Composable
private fun MediaAudioPane(fileName: String, bytes: ByteArray) {
    val context = LocalContext.current
    var isPlaying by remember { mutableStateOf(false) }
    var statusText by remember { mutableStateOf("Siap diputar (${ZipExtractor.formatSize(bytes.size.toLong())})") }
    val playerHolder = remember { mutableStateOf<MediaPlayer?>(null) }

    DisposableEffect(fileName, bytes) {
        onDispose {
            try {
                playerHolder.value?.stop()
                playerHolder.value?.release()
            } catch (_: Exception) {}
            playerHolder.value = null
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(Icons.Default.Audiotrack, contentDescription = null, tint = BrandRose, modifier = Modifier.size(56.dp))
        Spacer(modifier = Modifier.height(10.dp))
        Text(fileName, fontWeight = FontWeight.Bold, fontSize = 15.sp, color = BrandDark)
        Spacer(modifier = Modifier.height(4.dp))
        ThinHighlightText(statusText)
        Spacer(modifier = Modifier.height(16.dp))
        Button(
            onClick = {
                if (isPlaying) {
                    try {
                        playerHolder.value?.stop()
                        playerHolder.value?.release()
                    } catch (_: Exception) {}
                    playerHolder.value = null
                    isPlaying = false
                    statusText = "Dihentikan"
                } else {
                    try {
                        val tmp = File(context.cacheDir, "preview_audio_${fileName.replace("/", "_")}")
                        tmp.writeBytes(bytes)
                        val mp = MediaPlayer().apply {
                            setDataSource(tmp.absolutePath)
                            prepare()
                            setOnCompletionListener {
                                isPlaying = false
                                statusText = "Pemutaran selesai"
                            }
                            start()
                        }
                        playerHolder.value = mp
                        isPlaying = true
                        statusText = "Sedang memutar..."
                    } catch (e: Exception) {
                        statusText = "Gagal memutar audio: ${e.localizedMessage}"
                    }
                }
            },
            colors = ButtonDefaults.buttonColors(containerColor = BrandDark),
            shape = RoundedCornerShape(18.dp)
        ) {
            Icon(if (isPlaying) Icons.Default.Stop else Icons.Default.PlayArrow, contentDescription = null)
            Spacer(modifier = Modifier.width(6.dp))
            Text(if (isPlaying) "Stop Audio" else "Putar Audio")
        }
    }
}

@Composable
private fun BinaryAndArchivePane(
    fileName: String,
    bytes: ByteArray,
    isArchive: Boolean,
    onReplaceBinary: () -> Unit
) {
    var innerEntries by remember(bytes, isArchive) { mutableStateOf(emptyList<com.example.xiyu.util.ZipInspectEntry>()) }
    LaunchedEffect(bytes, isArchive) {
        if (isArchive) {
            innerEntries = withContext(Dispatchers.IO) { ZipExtractor.inspectZipBytes(bytes) }
        }
    }

    val hexDump = remember(bytes) {
        val slice = bytes.take(256)
        buildString {
            slice.chunked(16).forEachIndexed { rowIdx, chunk ->
                append(String.format("%04X  ", rowIdx * 16))
                chunk.forEach { b -> append(String.format("%02X ", b.toInt() and 0xFF)) }
                repeat(16 - chunk.size) { append("   ") }
                append(" ")
                chunk.forEach { b ->
                    val c = (b.toInt() and 0xFF)
                    append(if (c in 32..126) c.toChar() else '.')
                }
                append('\n')
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(14.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = if (isArchive) "Inspektur Arsip ($fileName)" else "Inspektur File Biner / Hex",
                    fontWeight = FontWeight.Bold,
                    fontSize = 13.sp,
                    color = BrandDark
                )
                ThinHighlightText("Ukuran total: ${ZipExtractor.formatSize(bytes.size.toLong())}")
            }
            OutlinedButton(
                onClick = onReplaceBinary,
                shape = RoundedCornerShape(12.dp),
                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)
            ) {
                Icon(Icons.Default.UploadFile, contentDescription = null, modifier = Modifier.size(14.dp))
                Spacer(modifier = Modifier.width(4.dp))
                Text("Ganti Biner", fontSize = 10.sp)
            }
        }

        Spacer(modifier = Modifier.height(10.dp))

        if (isArchive && innerEntries.isNotEmpty()) {
            ThinHighlightText("Daftar berkas di dalam arsip (${innerEntries.size} entri):")
            Spacer(modifier = Modifier.height(6.dp))
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .background(BrandCream.copy(alpha = 0.5f), RoundedCornerShape(10.dp))
                    .padding(8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                items(innerEntries) { item ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                            Icon(
                                imageVector = if (item.isDirectory) Icons.Default.Folder else Icons.Default.InsertDriveFile,
                                contentDescription = null,
                                tint = if (item.isDirectory) BrandRose else BrandDark,
                                modifier = Modifier.size(14.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = item.path,
                                fontFamily = FontFamily.Monospace,
                                fontSize = 11.sp,
                                color = BrandDark,
                                maxLines = 1
                            )
                        }
                        if (!item.isDirectory) {
                            ThinHighlightText(ZipExtractor.formatSize(item.size))
                        }
                    }
                }
            }
        } else {
            ThinHighlightText("Pratinjau Hex & ASCII (256 byte pertama):")
            Spacer(modifier = Modifier.height(6.dp))
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .background(BrandDark, RoundedCornerShape(12.dp))
                    .verticalScroll(rememberScrollState())
                    .horizontalScroll(rememberScrollState())
                    .padding(12.dp)
            ) {
                Text(
                    text = hexDump,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.sp,
                    color = BrandCream
                )
            }
        }
    }
}
