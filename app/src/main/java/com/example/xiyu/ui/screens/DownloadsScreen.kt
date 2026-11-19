package com.example.xiyu.ui.screens

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import com.example.xiyu.data.model.DownloadRecord
import com.example.xiyu.ui.components.*
import com.example.xiyu.ui.theme.*
import com.example.xiyu.util.ZipExtractor
import com.example.xiyu.viewmodel.AppStateViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun DownloadsScreen(
    appViewModel: AppStateViewModel,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val toastController = LocalToastController.current
    val downloads by appViewModel.downloads.collectAsState()

    var isRefreshing by remember { mutableStateOf(false) }
    var recordToDelete by remember { mutableStateOf<DownloadRecord?>(null) }
    var recordToInspect by remember { mutableStateOf<Pair<DownloadRecord, ByteArray>?>(null) }
    var recordPendingExport by remember { mutableStateOf<DownloadRecord?>(null) }

    val exportDocumentLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("*/*")
    ) { uri ->
        val target = recordPendingExport
        recordPendingExport = null
        if (uri != null && target != null) {
            scope.launch {
                try {
                    withContext(Dispatchers.IO) {
                        val src = File(target.localPath)
                        if (!src.exists()) throw IllegalStateException("File internal tidak ditemukan")
                        context.contentResolver.openOutputStream(uri)?.use { out ->
                            src.inputStream().use { inp -> inp.copyTo(out) }
                        }
                    }
                    toastController.showSuccess("Disimpan ke penyimpanan perangkat: ${target.fileName}")
                } catch (e: Exception) {
                    toastController.showError("Gagal mengekspor file: ${e.localizedMessage}")
                }
            }
        }
    }

    val sdf = remember { SimpleDateFormat("dd MMM HH:mm", Locale.getDefault()) }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(BrandCream)
            .padding(horizontal = 16.dp, vertical = 10.dp)
            .testTag("downloads_screen")
    ) {
        // Header with Manual Refresh Control
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "KRUZENLAB DOWNLOADS ⚡",
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    color = BrandRose,
                    letterSpacing = 1.5.sp
                )
                Text(
                    text = "Artifak & Unduhan (${downloads.size})",
                    fontFamily = FontFamily.SansSerif,
                    fontWeight = FontWeight.Bold,
                    fontSize = 20.sp,
                    color = BrandDark
                )
                ThinHighlightText("Penyimpanan internal untuk APK, ZIP Actions & Build Logs.")
            }

            RefreshIconButton(
                isRefreshing = isRefreshing,
                contentDescription = "Refresh Downloads",
                onClick = {
                    isRefreshing = true
                    appViewModel.refreshDownloads()
                    isRefreshing = false
                    toastController.showSuccess("Daftar unduhan diperbarui (${downloads.size} berkas)")
                },
                modifier = Modifier.testTag("refresh_downloads_btn")
            )
        }

        Spacer(modifier = Modifier.height(12.dp))

        if (downloads.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        imageVector = Icons.Default.DownloadForOffline,
                        contentDescription = null,
                        tint = BrandRose,
                        modifier = Modifier.size(52.dp)
                    )
                    Spacer(modifier = Modifier.height(10.dp))
                    Text(
                        text = "Belum ada berkas tersimpan.",
                        fontWeight = FontWeight.SemiBold,
                        color = BrandDark
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    ThinHighlightText("Unduh artifak build atau simpan log dari tab Workflows.")
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                items(downloads, key = { it.id }) { item ->
                    Surface(
                        shape = RoundedCornerShape(16.dp),
                        color = BrandCardBg,
                        border = androidx.compose.foundation.BorderStroke(1.dp, BrandBorder),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.padding(14.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Box(
                                    modifier = Modifier
                                        .size(38.dp)
                                        .background(BrandPalePink, RoundedCornerShape(10.dp)),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(
                                        imageVector = when (item.fileType) {
                                            "apk" -> Icons.Default.Android
                                            "zip" -> Icons.Default.FolderZip
                                            "log" -> Icons.Default.Description
                                            else -> Icons.Default.InsertDriveFile
                                        },
                                        contentDescription = null,
                                        tint = BrandDark,
                                        modifier = Modifier.size(20.dp)
                                    )
                                }
                                Spacer(modifier = Modifier.width(10.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = item.fileName,
                                        fontSize = 13.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = BrandDark,
                                        maxLines = 1
                                    )
                                    ThinHighlightText(
                                        "${item.repoFullName} · ${ZipExtractor.formatSize(item.sizeBytes)} · ${sdf.format(Date(item.createdAt))}"
                                    )
                                }
                                ThinHighlightBadge(item.fileType.uppercase())
                            }

                            Spacer(modifier = Modifier.height(10.dp))

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                OutlinedButton(
                                    onClick = {
                                        scope.launch {
                                            try {
                                                val bytes = withContext(Dispatchers.IO) {
                                                    val f = File(item.localPath)
                                                    if (!f.exists()) throw IllegalStateException("File hilang dari penyimpanan internal")
                                                    f.readBytes()
                                                }
                                                recordToInspect = item to bytes
                                            } catch (e: Exception) {
                                                toastController.showError("Gagal membuka: ${e.localizedMessage}")
                                            }
                                        }
                                    },
                                    shape = RoundedCornerShape(10.dp),
                                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                                    modifier = Modifier.height(32.dp)
                                ) {
                                    Icon(Icons.Default.Visibility, null, modifier = Modifier.size(14.dp))
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text("Inspeksi", fontSize = 10.sp)
                                }

                                if (item.fileType == "zip") {
                                    Button(
                                        onClick = {
                                            scope.launch {
                                                toastController.showInfo("Mengekstrak ${item.fileName}...", isLoading = true)
                                                appViewModel.repository.extractZipDownloadEntry(item).fold(
                                                    onSuccess = { list ->
                                                        toastController.showSuccess("Berhasil mengekstrak ${list.size} berkas!")
                                                    },
                                                    onFailure = { err ->
                                                        toastController.showError("Gagal ekstrak: ${err.localizedMessage}")
                                                    }
                                                )
                                            }
                                        },
                                        colors = ButtonDefaults.buttonColors(containerColor = BrandDark),
                                        shape = RoundedCornerShape(10.dp),
                                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                                        modifier = Modifier.height(32.dp)
                                    ) {
                                        Icon(Icons.Default.Unarchive, null, modifier = Modifier.size(14.dp))
                                        Spacer(modifier = Modifier.width(4.dp))
                                        Text("Ekstrak", fontSize = 10.sp)
                                    }
                                }

                                OutlinedButton(
                                    onClick = {
                                        recordPendingExport = item
                                        exportDocumentLauncher.launch(item.fileName)
                                    },
                                    shape = RoundedCornerShape(10.dp),
                                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                                    modifier = Modifier.height(32.dp)
                                ) {
                                    Icon(Icons.Default.SaveAlt, null, modifier = Modifier.size(14.dp))
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text("Ekspor", fontSize = 10.sp)
                                }

                                IconButton(
                                    onClick = {
                                        try {
                                            val f = File(item.localPath)
                                            val uri = FileProvider.getUriForFile(
                                                context,
                                                "${context.packageName}.fileprovider",
                                                f
                                            )
                                            val shareIntent = Intent(Intent.ACTION_SEND).apply {
                                                type = "*/*"
                                                putExtra(Intent.EXTRA_STREAM, uri)
                                                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                            }
                                            context.startActivity(Intent.createChooser(shareIntent, "Bagikan ${item.fileName}"))
                                        } catch (e: Exception) {
                                            toastController.showError("Gagal membagikan file: ${e.localizedMessage}")
                                        }
                                    },
                                    modifier = Modifier.size(32.dp)
                                ) {
                                    Icon(Icons.Default.Share, contentDescription = "Bagikan", tint = BrandRose, modifier = Modifier.size(16.dp))
                                }

                                IconButton(
                                    onClick = { recordToDelete = item },
                                    modifier = Modifier.size(32.dp)
                                ) {
                                    Icon(Icons.Default.DeleteOutline, contentDescription = "Hapus", tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(16.dp))
                                }
                            }
                        }
                    }
                }
            }
        }

        AttributionFooter()
    }

    if (recordToInspect != null) {
        val (rec, bytes) = recordToInspect!!
        UniversalFileEditorDialog(
            fileName = rec.fileName,
            filePath = "Downloads/${rec.fileName}",
            initialBytes = bytes,
            allowRemoteCommit = false,
            onDismiss = { recordToInspect = null },
            onSaveBytes = { newBytes, _ ->
                scope.launch {
                    withContext(Dispatchers.IO) {
                        File(rec.localPath).writeBytes(newBytes)
                    }
                    appViewModel.refreshDownloads()
                    recordToInspect = null
                    toastController.showSuccess("Perubahan berkas lokal disimpan")
                }
            }
        )
    }

    if (recordToDelete != null) {
        val target = recordToDelete!!
        UniversalConfirmDialog(
            title = "Hapus Unduhan Internal?",
            message = "Berkas \"${target.fileName}\" akan dihapus permanen dari penyimpanan internal aplikasi.",
            confirmText = "Hapus",
            isDestructive = true,
            onConfirm = {
                scope.launch {
                    appViewModel.repository.deleteInternalDownload(target)
                    toastController.showSuccess("${target.fileName} dihapus dari Downloads")
                }
            },
            onDismiss = { recordToDelete = null }
        )
    }
}
