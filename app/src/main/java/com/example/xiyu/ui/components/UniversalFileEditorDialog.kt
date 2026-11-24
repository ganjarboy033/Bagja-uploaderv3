package com.example.xiyu.ui.components

import android.graphics.BitmapFactory
import android.media.MediaPlayer
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
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
import com.example.xiyu.data.database.AppDatabaseHelper
import com.example.xiyu.ui.theme.*
import com.example.xiyu.util.ZipExtractor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

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
    val palette = LocalKruzenPalette.current
    val toastController = LocalToastController.current
    val appConfig by remember { AppDatabaseHelper.getInstance(context).configState }.collectAsState()
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
    val isLargeText = remember(initialBytes) { initialBytes.size > 100 * 1024 }
    var wordWrap by remember(isLargeText, appConfig.useFastHighSpeedEditor) {
        mutableStateOf(!(appConfig.useFastHighSpeedEditor && isLargeText))
    }
    var showSyntaxPreview by remember { mutableStateOf(false) }
    var fastEditorFlushProvider by remember { mutableStateOf<(() -> String)?>(null) }

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
            color = palette.cream,
            border = androidx.compose.foundation.BorderStroke(1.dp, palette.border),
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
                                tint = palette.rose,
                                modifier = Modifier.size(18.dp)
                            )
                            Text(
                                text = fileName,
                                fontSize = 15.sp,
                                fontWeight = FontWeight.Bold,
                                color = palette.textDark,
                                maxLines = 1
                            )
                            if (appConfig.useFastHighSpeedEditor && (!looksBinary || forcedTextEdit)) {
                                Surface(
                                    color = palette.palePink,
                                    shape = RoundedCornerShape(6.dp)
                                ) {
                                    Text(
                                        text = if (isLargeText) "FAST LITE" else "FAST",
                                        fontSize = 9.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = palette.dark,
                                        modifier = Modifier.padding(horizontal = 5.dp, vertical = 2.dp)
                                    )
                                }
                            }
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
                        Icon(Icons.Default.Close, contentDescription = "Tutup Editor", tint = palette.textDark)
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
                            Text("Paksa Edit Teks", fontSize = 10.sp, color = palette.rose)
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
                    color = palette.cardBg,
                    border = androidx.compose.foundation.BorderStroke(1.dp, palette.border)
                ) {
                    when (activeMode) {
                        EditorMode.CODE -> {
                            if (appConfig.useFastHighSpeedEditor) {
                                VirtualizedFastCodeEditorPane(
                                    text = textBuffer,
                                    onTextChange = { textBuffer = it },
                                    fileExtension = ext,
                                    isLargeFile = isLargeText,
                                    showFindReplace = showFindReplace,
                                    onToggleFindReplace = { showFindReplace = !showFindReplace },
                                    findQuery = findQuery,
                                    onFindQueryChange = { findQuery = it },
                                    replaceQuery = replaceQuery,
                                    onReplaceQueryChange = { replaceQuery = it },
                                    wordWrap = wordWrap,
                                    onToggleWordWrap = { wordWrap = !wordWrap },
                                    showSyntaxPreview = showSyntaxPreview,
                                    onToggleSyntaxPreview = { showSyntaxPreview = !showSyntaxPreview },
                                    onRegisterFlush = { provider -> fastEditorFlushProvider = provider }
                                )
                            } else {
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
                                enableZipDeepEditor = appConfig.enableZipDeepEditor,
                                useFastEditorForInnerText = appConfig.useFastHighSpeedEditor,
                                onReplaceBinary = { replaceBinaryLauncher.launch(arrayOf("*/*")) },
                                onApplyModifiedArchiveBytes = { newZipBytes ->
                                    workingBytes = newZipBytes
                                    toastController.showSuccess("ZIP dikemas ulang (${ZipExtractor.formatSize(newZipBytes.size.toLong())}). Klik Simpan & Commit untuk mengunggah.")
                                }
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

                    val canSave = (!looksBinary || forcedTextEdit || !workingBytes.contentEquals(initialBytes))
                    if (canSave) {
                        Button(
                            onClick = {
                                val finalBytes = if (activeMode == EditorMode.CODE || activeMode == EditorMode.TREE_KV || activeMode == EditorMode.MARKDOWN_PREVIEW || forcedTextEdit) {
                                    val latestText = if (activeMode == EditorMode.CODE && appConfig.useFastHighSpeedEditor) {
                                        fastEditorFlushProvider?.invoke() ?: textBuffer
                                    } else {
                                        textBuffer
                                    }
                                    latestText.toByteArray(Charsets.UTF_8)
                                } else {
                                    workingBytes
                                }
                                onSaveBytes(finalBytes, commitMessage.ifBlank { "Update $fileName" })
                            },
                            enabled = !isSaving,
                            colors = ButtonDefaults.buttonColors(containerColor = palette.dark, contentColor = palette.cream),
                            shape = RoundedCornerShape(14.dp),
                            modifier = Modifier
                                .weight(1.4f)
                                .testTag("save_file_editor_btn")
                        ) {
                            if (isSaving) {
                                CircularProgressIndicator(color = palette.cream, modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
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
    val palette = LocalKruzenPalette.current
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = { Text(label, fontSize = 11.sp) },
        leadingIcon = { Icon(icon, contentDescription = null, modifier = Modifier.size(14.dp)) },
        shape = RoundedCornerShape(14.dp),
        colors = FilterChipDefaults.filterChipColors(
            selectedContainerColor = palette.dark,
            selectedLabelColor = palette.cream,
            selectedLeadingIconColor = palette.accent
        )
    )
}

/**
 * High-speed Pure Compose Virtualized Line Editor:
 * - LazyColumn renders ONLY visible lines (no 10,000-Text allocation freeze on >100 KB files).
 * - Synchronized line gutter + active line highlight + quick-symbol bar.
 * - Dispatchers.Default 300ms debounced stats & Find/Replace matching.
 * - Auto-Lite Mode automatically disables heavy soft word-wrap for >100 KB files.
 */
@Composable
private fun VirtualizedFastCodeEditorPane(
    text: String,
    onTextChange: (String) -> Unit,
    fileExtension: String,
    isLargeFile: Boolean,
    showFindReplace: Boolean,
    onToggleFindReplace: () -> Unit,
    findQuery: String,
    onFindQueryChange: (String) -> Unit,
    replaceQuery: String,
    onReplaceQueryChange: (String) -> Unit,
    wordWrap: Boolean,
    onToggleWordWrap: () -> Unit,
    showSyntaxPreview: Boolean,
    onToggleSyntaxPreview: () -> Unit,
    onRegisterFlush: ((() -> String) -> Unit)
) {
    val palette = LocalKruzenPalette.current
    val linesState = remember { mutableStateListOf<String>() }
    var activeLineIndex by remember { mutableStateOf(0) }
    var editVersion by remember { mutableStateOf(0) }
    var debouncedLinesCount by remember { mutableStateOf(1) }
    var debouncedMatchCount by remember { mutableStateOf(0) }
    var isInitialized by remember { mutableStateOf(false) }

    val listState = rememberLazyListState()
    val hScrollState = rememberScrollState()
    val scope = rememberCoroutineScope()

    LaunchedEffect(text) {
        if (!isInitialized) {
            val split = withContext(Dispatchers.Default) {
                if (text.isEmpty()) listOf("") else text.split("\n")
            }
            linesState.clear()
            linesState.addAll(split)
            debouncedLinesCount = split.size
            isInitialized = true
        }
    }

    LaunchedEffect(Unit) {
        onRegisterFlush {
            val joined = linesState.joinToString("\n")
            onTextChange(joined)
            joined
        }
    }

    // Debounce 300ms on Dispatchers.Default for line count, Find match count & buffer sync
    LaunchedEffect(editVersion, findQuery, isInitialized) {
        if (!isInitialized) return@LaunchedEffect
        delay(300L)
        val snapshot = linesState.toList()
        val (joined, matches) = withContext(Dispatchers.Default) {
            val full = snapshot.joinToString("\n")
            val count = if (findQuery.isBlank()) {
                0
            } else {
                Regex.escape(findQuery).toRegex(RegexOption.IGNORE_CASE).findAll(full).count()
            }
            full to count
        }
        debouncedLinesCount = snapshot.size.coerceAtLeast(1)
        debouncedMatchCount = matches
        if (joined != text) {
            onTextChange(joined)
        }
    }

    fun insertQuickSymbol(symbol: String) {
        if (linesState.isEmpty()) linesState.add("")
        val idx = activeLineIndex.coerceIn(0, linesState.lastIndex)
        linesState[idx] = linesState[idx] + symbol
        editVersion++
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
                label = { Text(if (wordWrap) "Wrap ON" else "Lite No-Wrap", fontSize = 10.sp) },
                leadingIcon = { Icon(Icons.Default.WrapText, null, modifier = Modifier.size(13.dp)) }
            )
            FilterChip(
                selected = showSyntaxPreview,
                onClick = onToggleSyntaxPreview,
                label = { Text(if (showSyntaxPreview) "Mode Edit" else "Syntax Warna", fontSize = 10.sp) },
                leadingIcon = { Icon(Icons.Default.Palette, null, modifier = Modifier.size(13.dp)) }
            )
            ThinHighlightBadge("$debouncedLinesCount baris")
        }

        if (isLargeFile) {
            Spacer(modifier = Modifier.height(4.dp))
            Surface(
                color = palette.palePink.copy(alpha = 0.7f),
                shape = RoundedCornerShape(8.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = "Auto-Lite Mode aktif (>100 KB): Virtualized LazyColumn me-render baris yang terlihat saja agar tetap 60 FPS.",
                    fontSize = 10.sp,
                    color = palette.dark,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                )
            }
        }

        // Quick-symbol bar
        Spacer(modifier = Modifier.height(6.dp))
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(5.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            val symbols = listOf("{", "}", "(", ")", ";", "=", "\"", "/", "  ")
            symbols.forEach { sym ->
                val label = if (sym == "  ") "Tab" else sym
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = palette.cream,
                    border = androidx.compose.foundation.BorderStroke(1.dp, palette.border),
                    modifier = Modifier
                        .clickable(enabled = !showSyntaxPreview) { insertQuickSymbol(sym) }
                ) {
                    Text(
                        text = label,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold,
                        fontSize = 12.sp,
                        color = palette.textDark,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp)
                    )
                }
            }
            Surface(
                shape = RoundedCornerShape(8.dp),
                color = palette.dark,
                modifier = Modifier.clickable(enabled = !showSyntaxPreview) {
                    val idx = (activeLineIndex + 1).coerceIn(0, linesState.size)
                    linesState.add(idx, "")
                    activeLineIndex = idx
                    editVersion++
                }
            ) {
                Text(
                    text = "+Baris",
                    fontSize = 10.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = palette.cream,
                    modifier = Modifier.padding(horizontal = 9.dp, vertical = 5.dp)
                )
            }
        }

        if (showFindReplace) {
            Spacer(modifier = Modifier.height(6.dp))
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(palette.cream, RoundedCornerShape(10.dp))
                    .padding(8.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = findQuery,
                        onValueChange = onFindQueryChange,
                        placeholder = { Text("Cari ($debouncedMatchCount cocok)", fontSize = 11.sp) },
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
                Row(
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    TextButton(
                        onClick = {
                            if (findQuery.isNotBlank()) {
                                val firstIdx = linesState.indexOfFirst { it.contains(findQuery, ignoreCase = true) }
                                if (firstIdx >= 0) {
                                    activeLineIndex = firstIdx
                                    scope.launch { listState.animateScrollToItem(firstIdx) }
                                }
                            }
                        },
                        enabled = findQuery.isNotBlank() && debouncedMatchCount > 0
                    ) {
                        Text("Lompat ke Cocok", fontSize = 10.sp, color = palette.rose)
                    }
                    Button(
                        onClick = {
                            if (findQuery.isNotBlank()) {
                                scope.launch {
                                    val replaced = withContext(Dispatchers.Default) {
                                        linesState.map { it.replace(findQuery, replaceQuery, ignoreCase = false) }
                                    }
                                    linesState.clear()
                                    linesState.addAll(replaced)
                                    editVersion++
                                    onTextChange(replaced.joinToString("\n"))
                                }
                            }
                        },
                        enabled = findQuery.isNotBlank() && debouncedMatchCount > 0,
                        colors = ButtonDefaults.buttonColors(containerColor = palette.dark, contentColor = palette.cream),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.height(30.dp)
                    ) {
                        Text("Ganti Semua ($debouncedMatchCount)", fontSize = 10.sp)
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(6.dp))

        val codeTextModifier = if (wordWrap) {
            Modifier.fillMaxWidth()
        } else {
            Modifier
                .fillMaxWidth()
                .horizontalScroll(hScrollState)
        }

        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxSize()
                .background(palette.cream.copy(alpha = 0.55f), RoundedCornerShape(12.dp))
                .border(0.5.dp, palette.border, RoundedCornerShape(12.dp))
                .padding(vertical = 6.dp)
        ) {
            itemsIndexed(
                items = linesState,
                key = { index, _ -> index }
            ) { index, lineText ->
                val isActive = index == activeLineIndex
                val rowBg = when {
                    isActive -> palette.palePink.copy(alpha = 0.48f)
                    findQuery.isNotBlank() && lineText.contains(findQuery, ignoreCase = true) -> palette.accent.copy(alpha = 0.16f)
                    else -> Color.Transparent
                }

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(rowBg)
                        .padding(horizontal = 6.dp, vertical = 1.dp),
                    verticalAlignment = Alignment.Top
                ) {
                    // Synchronized Gutter Number
                    Box(
                        modifier = Modifier
                            .width(44.dp)
                            .background(
                                if (isActive) palette.rose.copy(alpha = 0.22f) else palette.palePink.copy(alpha = 0.35f),
                                RoundedCornerShape(4.dp)
                            )
                            .padding(horizontal = 4.dp, vertical = 2.dp),
                        contentAlignment = Alignment.CenterEnd
                    ) {
                        Text(
                            text = "${index + 1}",
                            fontFamily = FontFamily.Monospace,
                            fontSize = 10.sp,
                            lineHeight = 17.sp,
                            fontWeight = if (isActive) FontWeight.Bold else FontWeight.Normal,
                            color = if (isActive) palette.dark else palette.textMuted
                        )
                    }

                    Spacer(modifier = Modifier.width(6.dp))

                    Box(modifier = Modifier.weight(1f)) {
                        if (showSyntaxPreview) {
                            Text(
                                text = remember(lineText, fileExtension) {
                                    highlightSingleLineSyntax(lineText, fileExtension)
                                },
                                fontFamily = FontFamily.Monospace,
                                fontSize = 12.sp,
                                lineHeight = 17.sp,
                                modifier = codeTextModifier.padding(vertical = 2.dp)
                            )
                        } else {
                            BasicTextField(
                                value = lineText,
                                onValueChange = { updated ->
                                    if (updated.contains('\n')) {
                                        val parts = updated.split('\n')
                                        linesState[index] = parts.first()
                                        if (parts.size > 1) {
                                            linesState.addAll(index + 1, parts.drop(1))
                                            activeLineIndex = index + parts.size - 1
                                        }
                                    } else {
                                        linesState[index] = updated
                                    }
                                    editVersion++
                                },
                                cursorBrush = SolidColor(palette.rose),
                                textStyle = TextStyle(
                                    fontFamily = FontFamily.Monospace,
                                    fontSize = 12.sp,
                                    lineHeight = 17.sp,
                                    color = palette.textDark
                                ),
                                modifier = codeTextModifier
                                    .padding(vertical = 2.dp)
                                    .onFocusChanged { focusState ->
                                        if (focusState.isFocused) {
                                            activeLineIndex = index
                                        }
                                    }
                            )
                        }
                    }
                }
            }
        }
    }
}

private fun highlightSingleLineSyntax(line: String, ext: String): androidx.compose.ui.text.AnnotatedString {
    val keywords = setOf(
        "package", "import", "fun", "class", "val", "var", "return", "if", "else", "when", "for",
        "while", "suspend", "object", "data", "private", "public", "override", "true", "false", "null",
        "const", "let", "function", "export", "default", "async", "await", "def", "from", "name", "on", "jobs", "steps"
    )
    return buildAnnotatedString {
        val trimmed = line.trimStart()
        when {
            trimmed.startsWith("//") || trimmed.startsWith("#") || trimmed.startsWith("<!--") -> {
                withStyle(SpanStyle(color = Color(0xFF6B7280))) { append(line) }
            }
            else -> {
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
    }
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
    val palette = LocalKruzenPalette.current
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
                    .background(palette.cream, RoundedCornerShape(10.dp))
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
                        colors = ButtonDefaults.buttonColors(containerColor = palette.dark, contentColor = palette.cream),
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
                .background(palette.cream.copy(alpha = 0.5f), RoundedCornerShape(12.dp))
                .border(0.5.dp, palette.border, RoundedCornerShape(12.dp))
                .verticalScroll(vScroll)
                .padding(8.dp)
        ) {
            // Line numbers gutter
            Column(
                modifier = Modifier
                    .padding(end = 8.dp)
                    .background(palette.palePink.copy(alpha = 0.55f), RoundedCornerShape(6.dp))
                    .padding(horizontal = 6.dp, vertical = 4.dp)
            ) {
                for (i in 1..linesCount) {
                    Text(
                        text = i.toString(),
                        fontFamily = FontFamily.Monospace,
                        fontSize = 11.sp,
                        lineHeight = 16.sp,
                        color = palette.textMuted
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
                BasicTextField(
                    value = text,
                    onValueChange = onTextChange,
                    cursorBrush = SolidColor(palette.rose),
                    textStyle = TextStyle(
                        fontFamily = FontFamily.Monospace,
                        fontSize = 12.sp,
                        lineHeight = 16.sp,
                        color = palette.textDark
                    ),
                    modifier = codeModifier.padding(vertical = 4.dp)
                )
            }
        }
    }
}

private fun highlightCodeSyntax(code: String, ext: String): androidx.compose.ui.text.AnnotatedString {
    return buildAnnotatedString {
        val lines = code.split("\n")
        lines.forEachIndexed { idx, line ->
            append(highlightSingleLineSyntax(line, ext))
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
    val palette = LocalKruzenPalette.current
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
            color = palette.textDark
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
        HorizontalDivider(color = palette.border)
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
                colors = ButtonDefaults.buttonColors(containerColor = palette.dark, contentColor = palette.cream),
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
    val palette = LocalKruzenPalette.current
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
                            .background(palette.dark, RoundedCornerShape(6.dp))
                            .padding(horizontal = 10.dp, vertical = 4.dp)
                    ) {
                        Text(
                            text = line,
                            fontFamily = FontFamily.Monospace,
                            fontSize = 11.sp,
                            color = palette.cream
                        )
                    }
                }
                trimmed.startsWith("### ") -> Text(
                    text = trimmed.removePrefix("### "),
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold,
                    color = palette.textDark
                )
                trimmed.startsWith("## ") -> Text(
                    text = trimmed.removePrefix("## "),
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                    color = palette.textDark
                )
                trimmed.startsWith("# ") -> {
                    Text(
                        text = trimmed.removePrefix("# "),
                        fontSize = 21.sp,
                        fontWeight = FontWeight.ExtraBold,
                        color = palette.textDark
                    )
                    HorizontalDivider(color = palette.border)
                }
                trimmed.startsWith("> ") -> Surface(
                    color = palette.palePink.copy(alpha = 0.6f),
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = trimmed.removePrefix("> "),
                        fontSize = 12.sp,
                        color = palette.rose,
                        modifier = Modifier.padding(10.dp)
                    )
                }
                trimmed.startsWith("- ") || trimmed.startsWith("* ") -> Row {
                    Text("• ", fontWeight = FontWeight.Bold, color = palette.rose)
                    Text(trimmed.drop(2), fontSize = 13.sp, color = palette.textDark)
                }
                trimmed.isNotEmpty() -> Text(text = line, fontSize = 13.sp, color = palette.textDark, lineHeight = 19.sp)
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
    val palette = LocalKruzenPalette.current
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
        Icon(Icons.Default.Audiotrack, contentDescription = null, tint = palette.rose, modifier = Modifier.size(56.dp))
        Spacer(modifier = Modifier.height(10.dp))
        Text(fileName, fontWeight = FontWeight.Bold, fontSize = 15.sp, color = palette.textDark)
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
            colors = ButtonDefaults.buttonColors(containerColor = palette.dark, contentColor = palette.cream),
            shape = RoundedCornerShape(18.dp)
        ) {
            Icon(if (isPlaying) Icons.Default.Stop else Icons.Default.PlayArrow, contentDescription = null)
            Spacer(modifier = Modifier.width(6.dp))
            Text(if (isPlaying) "Stop Audio" else "Putar Audio")
        }
    }
}

private data class ZipWorkspaceFile(
    val relativePath: String,
    val file: File,
    val sizeBytes: Long
)

@Composable
private fun BinaryAndArchivePane(
    fileName: String,
    bytes: ByteArray,
    isArchive: Boolean,
    enableZipDeepEditor: Boolean,
    useFastEditorForInnerText: Boolean,
    onReplaceBinary: () -> Unit,
    onApplyModifiedArchiveBytes: (ByteArray) -> Unit
) {
    val context = LocalContext.current
    val palette = LocalKruzenPalette.current
    val toastController = LocalToastController.current
    val scope = rememberCoroutineScope()

    var innerEntries by remember(bytes, isArchive) { mutableStateOf(emptyList<com.example.xiyu.util.ZipInspectEntry>()) }
    var workspaceDir by remember { mutableStateOf<File?>(null) }
    var workspaceFiles by remember { mutableStateOf(emptyList<ZipWorkspaceFile>()) }
    var isUnpacking by remember { mutableStateOf(false) }

    var editingZipFile by remember { mutableStateOf<ZipWorkspaceFile?>(null) }
    var editingZipText by remember { mutableStateOf("") }

    var showAddEntryDialog by remember { mutableStateOf(false) }
    var newEntryRelPath by remember { mutableStateOf("") }
    var newEntryContent by remember { mutableStateOf("") }

    DisposableEffect(Unit) {
        onDispose {
            workspaceDir?.let { runCatching { it.deleteRecursively() } }
        }
    }

    LaunchedEffect(bytes, isArchive) {
        if (isArchive) {
            innerEntries = withContext(Dispatchers.IO) { ZipExtractor.inspectZipBytes(bytes) }
        }
    }

    fun refreshWorkspaceList(dir: File) {
        scope.launch {
            val list = withContext(Dispatchers.IO) {
                val rootPath = dir.canonicalPath
                dir.walkTopDown()
                    .filter { it.isFile }
                    .map { f ->
                        val rel = f.canonicalPath.removePrefix(rootPath).removePrefix(File.separator).replace(File.separatorChar, '/')
                        ZipWorkspaceFile(rel, f, f.length())
                    }
                    .sortedBy { it.relativePath.lowercase() }
                    .toList()
            }
            workspaceFiles = list
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
                    color = palette.textDark
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

        // ZIP Deep Editor Action Row
        if (isArchive && enableZipDeepEditor && fileName.endsWith(".zip", ignoreCase = true)) {
            Spacer(modifier = Modifier.height(8.dp))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                if (workspaceDir == null) {
                    Button(
                        onClick = {
                            scope.launch {
                                isUnpacking = true
                                val dirRes = withContext(Dispatchers.IO) {
                                    runCatching {
                                        val targetDir = File(context.cacheDir, "zip_workspace/${System.currentTimeMillis()}").apply { mkdirs() }
                                        val rootCanon = targetDir.canonicalPath
                                        ZipInputStream(ByteArrayInputStream(bytes)).use { zis ->
                                            var entry = zis.nextEntry
                                            val buf = ByteArray(8192)
                                            while (entry != null) {
                                                if (!entry.isDirectory) {
                                                    val rel = ZipExtractor.cleanPath(entry.name)
                                                    val outFile = File(targetDir, rel)
                                                    if (!outFile.canonicalPath.startsWith(rootCanon + File.separator)) {
                                                        throw SecurityException("Invalid ZIP entry: $rel")
                                                    }
                                                    outFile.parentFile?.mkdirs()
                                                    FileOutputStream(outFile).use { fos ->
                                                        var n: Int
                                                        while (zis.read(buf).also { n = it } != -1) {
                                                            fos.write(buf, 0, n)
                                                        }
                                                    }
                                                }
                                                zis.closeEntry()
                                                entry = zis.nextEntry
                                            }
                                        }
                                        targetDir
                                    }
                                }
                                isUnpacking = false
                                dirRes.fold(
                                    onSuccess = { dir ->
                                        workspaceDir = dir
                                        refreshWorkspaceList(dir)
                                        toastController.showSuccess("Arsip dibongkar ke workspace cache!")
                                    },
                                    onFailure = { err ->
                                        toastController.showError("Gagal membongkar ZIP: ${err.localizedMessage}")
                                    }
                                )
                            }
                        },
                        enabled = !isUnpacking,
                        colors = ButtonDefaults.buttonColors(containerColor = palette.dark, contentColor = palette.cream),
                        shape = RoundedCornerShape(10.dp),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                    ) {
                        Icon(Icons.Default.FolderOpen, contentDescription = null, modifier = Modifier.size(14.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(if (isUnpacking) "Membongkar..." else "Bongkar & Edit Isi ZIP", fontSize = 11.sp)
                    }
                } else {
                    OutlinedButton(
                        onClick = { showAddEntryDialog = true },
                        shape = RoundedCornerShape(10.dp),
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)
                    ) {
                        Icon(Icons.Default.NoteAdd, contentDescription = null, modifier = Modifier.size(14.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Tambah File", fontSize = 10.sp)
                    }
                    Button(
                        onClick = {
                            val dir = workspaceDir ?: return@Button
                            scope.launch {
                                val repackRes = withContext(Dispatchers.IO) {
                                    runCatching {
                                        val baos = ByteArrayOutputStream()
                                        ZipOutputStream(baos).use { zos ->
                                            val rootCanon = dir.canonicalPath
                                            dir.walkTopDown().filter { it.isFile }.forEach { file ->
                                                val rel = file.canonicalPath.removePrefix(rootCanon)
                                                    .removePrefix(File.separator)
                                                    .replace(File.separatorChar, '/')
                                                zos.putNextEntry(ZipEntry(rel))
                                                FileInputStream(file).use { it.copyTo(zos) }
                                                zos.closeEntry()
                                            }
                                        }
                                        baos.toByteArray()
                                    }
                                }
                                repackRes.fold(
                                    onSuccess = { newBytes ->
                                        onApplyModifiedArchiveBytes(newBytes)
                                    },
                                    onFailure = { err ->
                                        toastController.showError("Gagal zip ulang: ${err.localizedMessage}")
                                    }
                                )
                            }
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = palette.dark, contentColor = palette.cream),
                        shape = RoundedCornerShape(10.dp),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp)
                    ) {
                        Icon(Icons.Default.Archive, contentDescription = null, modifier = Modifier.size(14.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Zip Ulang & Terapkan", fontSize = 10.sp)
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(10.dp))

        if (workspaceDir != null) {
            ThinHighlightText("Workspace Edit ZIP (${workspaceFiles.size} berkas) — klik file untuk edit:")
            Spacer(modifier = Modifier.height(6.dp))
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .background(palette.cream.copy(alpha = 0.55f), RoundedCornerShape(10.dp))
                    .padding(8.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                items(workspaceFiles, key = { it.relativePath }) { wf ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .background(palette.cardBg)
                            .clickable {
                                scope.launch {
                                    val txt = withContext(Dispatchers.IO) {
                                        runCatching { wf.file.readText(Charsets.UTF_8) }.getOrNull()
                                    }
                                    if (txt != null) {
                                        editingZipFile = wf
                                        editingZipText = txt
                                    } else {
                                        toastController.showWarning("File biner tidak dapat diedit sebagai teks")
                                    }
                                }
                            }
                            .padding(horizontal = 8.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                            Icon(Icons.Default.EditNote, contentDescription = null, tint = palette.rose, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Column {
                                Text(
                                    text = wf.relativePath,
                                    fontFamily = FontFamily.Monospace,
                                    fontSize = 11.sp,
                                    color = palette.textDark,
                                    maxLines = 1
                                )
                                Text(
                                    text = ZipExtractor.formatSize(wf.sizeBytes),
                                    fontSize = 9.sp,
                                    color = palette.textMuted
                                )
                            }
                        }
                        IconButton(
                            onClick = {
                                scope.launch {
                                    withContext(Dispatchers.IO) { wf.file.delete() }
                                    workspaceDir?.let { refreshWorkspaceList(it) }
                                    toastController.showInfo("${wf.relativePath} dihapus dari workspace ZIP")
                                }
                            },
                            modifier = Modifier.size(28.dp)
                        ) {
                            Icon(Icons.Default.DeleteOutline, contentDescription = "Hapus entri ZIP", tint = palette.error, modifier = Modifier.size(15.dp))
                        }
                    }
                }
            }
        } else if (isArchive && innerEntries.isNotEmpty()) {
            ThinHighlightText("Daftar berkas di dalam arsip (${innerEntries.size} entri):")
            Spacer(modifier = Modifier.height(6.dp))
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .background(palette.cream.copy(alpha = 0.5f), RoundedCornerShape(10.dp))
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
                                tint = if (item.isDirectory) palette.rose else palette.textDark,
                                modifier = Modifier.size(14.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = item.path,
                                fontFamily = FontFamily.Monospace,
                                fontSize = 11.sp,
                                color = palette.textDark,
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
                    .background(palette.dark, RoundedCornerShape(12.dp))
                    .verticalScroll(rememberScrollState())
                    .horizontalScroll(rememberScrollState())
                    .padding(12.dp)
            ) {
                Text(
                    text = hexDump,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.sp,
                    color = palette.cream
                )
            }
        }
    }

    // Inline Editor for a file inside ZIP Workspace
    editingZipFile?.let { target ->
        AlertDialog(
            onDismissRequest = { editingZipFile = null },
            title = { Text("Edit ${target.relativePath}", fontSize = 14.sp, fontWeight = FontWeight.Bold) },
            text = {
                OutlinedTextField(
                    value = editingZipText,
                    onValueChange = { editingZipText = it },
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 200.dp, max = 360.dp),
                    textStyle = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 12.sp)
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        scope.launch {
                            withContext(Dispatchers.IO) {
                                target.file.writeText(editingZipText, Charsets.UTF_8)
                            }
                            editingZipFile = null
                            workspaceDir?.let { refreshWorkspaceList(it) }
                            toastController.showSuccess("Entri ${target.relativePath} diperbarui")
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = palette.dark, contentColor = palette.cream)
                ) {
                    Text("Simpan Entri")
                }
            },
            dismissButton = {
                TextButton(onClick = { editingZipFile = null }) { Text("Batal") }
            }
        )
    }

    // Add Entry Dialog for ZIP Workspace
    if (showAddEntryDialog) {
        AlertDialog(
            onDismissRequest = { showAddEntryDialog = false },
            title = { Text("Tambah File ke ZIP", fontSize = 15.sp, fontWeight = FontWeight.Bold) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = newEntryRelPath,
                        onValueChange = { newEntryRelPath = it },
                        label = { Text("Path relatif (misal src/config.json)") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = newEntryContent,
                        onValueChange = { newEntryContent = it },
                        label = { Text("Isi berkas") },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(140.dp)
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        val dir = workspaceDir ?: return@Button
                        val clean = ZipExtractor.cleanPath(newEntryRelPath)
                        if (clean.isNotBlank()) {
                            scope.launch {
                                withContext(Dispatchers.IO) {
                                    val newFile = File(dir, clean)
                                    newFile.parentFile?.mkdirs()
                                    newFile.writeText(newEntryContent, Charsets.UTF_8)
                                }
                                showAddEntryDialog = false
                                newEntryRelPath = ""
                                newEntryContent = ""
                                refreshWorkspaceList(dir)
                            }
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = palette.dark, contentColor = palette.cream)
                ) {
                    Text("Tambahkan")
                }
            },
            dismissButton = {
                TextButton(onClick = { showAddEntryDialog = false }) { Text("Batal") }
            }
        )
    }
}
