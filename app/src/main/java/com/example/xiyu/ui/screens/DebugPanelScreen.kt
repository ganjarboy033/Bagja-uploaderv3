package com.example.xiyu.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.xiyu.data.database.AppConfigEntity
import com.example.xiyu.data.database.AppDatabaseHelper
import com.example.xiyu.data.model.UserProfile
import com.example.xiyu.data.repository.GitHubRepository
import com.example.xiyu.ui.components.*
import com.example.xiyu.ui.navigation.Screen
import com.example.xiyu.ui.theme.*
import com.example.xiyu.util.OAuthHelper
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

enum class DebugTab {
    CONFIG, NETWORK, DEVICE, COMMITS
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DebugPanelScreen(
    repository: GitHubRepository,
    currentUser: UserProfile?,
    onNavigate: (Screen) -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val scrollState = rememberScrollState()
    val toastController = LocalToastController.current

    var activeTab by remember { mutableStateOf(DebugTab.CONFIG) }

    val configState by repository.dbHelper.configState.collectAsState()
    val networkLogs by repository.dbHelper.recentLogs.collectAsState()
    val commitHistory by repository.dbHelper.commitHistory.collectAsState()

    var clientId by remember(configState) { mutableStateOf(configState.clientId) }
    var clientSecret by remember(configState) { mutableStateOf(configState.clientSecret) }
    var sessionPassword by remember(configState) { mutableStateOf(configState.sessionPassword) }
    var apiUrl by remember(configState) { mutableStateOf(configState.githubApiUrl) }
    var defaultBranch by remember(configState) { mutableStateOf(configState.defaultBranch) }
    var autoReadme by remember(configState) { mutableStateOf(configState.autoReadme) }
    var autoLicense by remember(configState) { mutableStateOf(configState.autoLicense) }
    var autoGitignore by remember(configState) { mutableStateOf(configState.autoGitignore) }

    var isTestingPing by remember { mutableStateOf(false) }
    var showResetDbConfirmDialog by remember { mutableStateOf(false) }
    var showSaveConfigConfirmDialog by remember { mutableStateOf(false) }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(BrandCream)
            .verticalScroll(scrollState)
            .padding(horizontal = 20.dp, vertical = 16.dp)
            .testTag("debug_panel_screen")
    ) {
        // Back navigation
        Row(
            modifier = Modifier
                .clickable { onNavigate(Screen.Dashboard) }
                .padding(bottom = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                contentDescription = "Back",
                tint = BrandRose,
                modifier = Modifier.size(16.dp)
            )
            Spacer(modifier = Modifier.width(6.dp))
            Text(
                text = "Kembali ke Dashboard",
                fontSize = 12.sp,
                color = BrandTextMuted
            )
        }

        // Header
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text(
                    text = "KRUZENLAB CONTROL CENTER ⚡",
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    color = BrandRose,
                    letterSpacing = 1.6.sp
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = "Panel Debug & OAuth",
                    fontFamily = FontFamily.SansSerif,
                    fontWeight = FontWeight.Bold,
                    fontSize = 24.sp,
                    color = BrandDark
                )
                ThinHighlightText("Pengaturan kustomisasi session & SQLite backend.")
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                RefreshIconButton(
                    isRefreshing = isTestingPing,
                    contentDescription = "Refresh Settings & SQLite",
                    onClick = {
                        repository.dbHelper.refreshAllSynchronously()
                        toastController.showSuccess("Status sesi & database disinkronkan")
                    },
                    modifier = Modifier.testTag("refresh_settings_header_btn")
                )
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = BrandPalePink
                ) {
                    Text(
                        text = "SQLite Backend",
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        color = BrandRose,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(14.dp))

        // Tab Row
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            DebugTabButton("Kustomisasi", Icons.Default.Tune, activeTab == DebugTab.CONFIG) {
                activeTab = DebugTab.CONFIG
            }
            DebugTabButton("Log Jaringan", Icons.Default.NetworkCheck, activeTab == DebugTab.NETWORK) {
                activeTab = DebugTab.NETWORK
            }
            DebugTabButton("Database", Icons.Default.Storage, activeTab == DebugTab.COMMITS) {
                activeTab = DebugTab.COMMITS
            }
            DebugTabButton("Info Sistem", Icons.Default.Info, activeTab == DebugTab.DEVICE) {
                activeTab = DebugTab.DEVICE
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        when (activeTab) {
            DebugTab.CONFIG -> {
                Surface(
                    shape = RoundedCornerShape(24.dp),
                    border = androidx.compose.foundation.BorderStroke(1.dp, BrandBorder),
                    color = BrandCardBg,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(18.dp)) {
                        Text(
                            text = "Kustomisasi Kredensial & Sesi",
                            fontWeight = FontWeight.Bold,
                            fontSize = 15.sp,
                            color = BrandDark
                        )
                        ThinHighlightText(
                            text = "Tersedia nilai fallback otomatis jika kolom dikosongkan tanpa memicu error."
                        )

                        Spacer(modifier = Modifier.height(14.dp))

                        // Session Password
                        OutlinedTextField(
                            value = sessionPassword,
                            onValueChange = { sessionPassword = it },
                            label = { Text("SESSION_PASSWORD (Backend Secret)") },
                            singleLine = false,
                            minLines = 1,
                            maxLines = 3,
                            shape = RoundedCornerShape(12.dp),
                            trailingIcon = {
                                IconButton(onClick = {
                                    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                    clipboard.setPrimaryClip(ClipData.newPlainText("Session Password", sessionPassword))
                                    toastController.showSuccess("Password sesi disalin!")
                                }) {
                                    Icon(Icons.Default.ContentCopy, contentDescription = "Salin", tint = BrandRose, modifier = Modifier.size(18.dp))
                                }
                            },
                            modifier = Modifier.fillMaxWidth().testTag("config_session_password_input")
                        )

                        // Length counter and thin highlight indicator
                        val len = sessionPassword.length
                        val isValidLen = len >= 32
                        ThinHighlightText(
                            text = if (isValidLen) "✓ Panjang: $len karakter (Memenuhi syarat >= 32 char)"
                            else "⚠️ Panjang: $len karakter (Disarankan minimal 32 karakter)",
                            color = if (isValidLen) Color(0xFF15803D) else MaterialTheme.colorScheme.error
                        )

                        Spacer(modifier = Modifier.height(10.dp))

                        // GitHub Client ID
                        OutlinedTextField(
                            value = clientId,
                            onValueChange = { clientId = it },
                            label = { Text("GitHub OAuth Client ID") },
                            placeholder = { Text(OAuthHelper.FALLBACK_CLIENT_ID) },
                            singleLine = false,
                            minLines = 1,
                            maxLines = 3,
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.fillMaxWidth().testTag("config_client_id_input")
                        )
                        ThinHighlightText(
                            text = "Fallback: ${if (clientId.isBlank()) "Aktif (${OAuthHelper.FALLBACK_CLIENT_ID})" else "Nilai kustom aktif"}"
                        )

                        Spacer(modifier = Modifier.height(10.dp))

                        // GitHub Client Secret
                        OutlinedTextField(
                            value = clientSecret,
                            onValueChange = { clientSecret = it },
                            label = { Text("GitHub OAuth Client Secret") },
                            placeholder = { Text(OAuthHelper.FALLBACK_CLIENT_SECRET) },
                            singleLine = false,
                            minLines = 1,
                            maxLines = 3,
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.fillMaxWidth().testTag("config_client_secret_input")
                        )
                        ThinHighlightText(
                            text = "Fallback: ${if (clientSecret.isBlank()) "Default aktif (${OAuthHelper.FALLBACK_CLIENT_SECRET})" else "Nilai kustom aktif"}"
                        )

                        Spacer(modifier = Modifier.height(10.dp))

                        // GitHub Base API URL
                        OutlinedTextField(
                            value = apiUrl,
                            onValueChange = { apiUrl = it },
                            label = { Text("GitHub API Base URL") },
                            placeholder = { Text("https://api.github.com/") },
                            singleLine = true,
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.fillMaxWidth().testTag("config_api_url_input")
                        )

                        Spacer(modifier = Modifier.height(10.dp))

                        // Default branch
                        OutlinedTextField(
                            value = defaultBranch,
                            onValueChange = { defaultBranch = it },
                            label = { Text("Default Git Branch") },
                            placeholder = { Text("main") },
                            singleLine = true,
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.fillMaxWidth()
                        )

                        Spacer(modifier = Modifier.height(14.dp))

                        // Template Presets
                        Text("Template Standar Upload", fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = BrandDark)
                        Spacer(modifier = Modifier.height(6.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            FilterChip(
                                selected = autoReadme,
                                onClick = { autoReadme = !autoReadme },
                                label = { Text("Auto README", fontSize = 11.sp) },
                                shape = RoundedCornerShape(14.dp)
                            )
                            FilterChip(
                                selected = autoLicense.isNotEmpty(),
                                onClick = {
                                    autoLicense = if (autoLicense == "MIT") "Apache 2.0" else "MIT"
                                },
                                label = { Text("Licence: $autoLicense", fontSize = 11.sp) },
                                shape = RoundedCornerShape(14.dp)
                            )
                        }

                        Spacer(modifier = Modifier.height(16.dp))

                        // Save Button triggering Confirmation Modal
                        Button(
                            onClick = { showSaveConfigConfirmDialog = true },
                            colors = ButtonDefaults.buttonColors(containerColor = BrandDark),
                            shape = RoundedCornerShape(20.dp),
                            modifier = Modifier.fillMaxWidth().height(46.dp).testTag("save_config_btn")
                        ) {
                            Icon(Icons.Default.Save, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Simpan ke Database Backend", fontWeight = FontWeight.Bold, fontSize = 12.sp)
                        }
                    }
                }
            }

            DebugTab.NETWORK -> {
                Surface(
                    shape = RoundedCornerShape(24.dp),
                    border = androidx.compose.foundation.BorderStroke(1.dp, BrandBorder),
                    color = BrandCardBg,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column {
                                Text(
                                    text = "Log Jaringan HTTP (${networkLogs.size})",
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 14.sp,
                                    color = BrandDark
                                )
                                ThinHighlightText("Riwayat panggilan REST API")
                            }
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                RefreshIconButton(
                                    isRefreshing = isTestingPing,
                                    iconSize = 16.dp,
                                    contentDescription = "Refresh Log Jaringan",
                                    onClick = {
                                        repository.dbHelper.refreshAllSynchronously()
                                        toastController.showSuccess("Log jaringan dimuat ulang")
                                    },
                                    modifier = Modifier.size(30.dp)
                                )
                                TextButton(
                                    onClick = {
                                        scope.launch {
                                            isTestingPing = true
                                            toastController.showInfo("Menguji koneksi ke GitHub API...", isLoading = true)
                                            repository.getRepositories()
                                            isTestingPing = false
                                            toastController.showSuccess("Ping selesai!")
                                        }
                                    },
                                    enabled = !isTestingPing
                                ) {
                                    Text("Test Ping", fontSize = 11.sp, color = BrandRose)
                                }
                                TextButton(onClick = {
                                    scope.launch {
                                        repository.dbHelper.clearLogs()
                                        toastController.showSuccess("Log dibersihkan")
                                    }
                                }) {
                                    Text("Bersihkan", fontSize = 11.sp, color = BrandTextMuted)
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(10.dp))

                        if (networkLogs.isEmpty()) {
                            Box(
                                modifier = Modifier.fillMaxWidth().padding(vertical = 30.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Text("Belum ada log panggilan jaringan.", fontSize = 12.sp, color = BrandTextMuted)
                            }
                        } else {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .heightIn(max = 400.dp)
                                    .verticalScroll(rememberScrollState()),
                                verticalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                val sdf = remember { SimpleDateFormat("HH:mm:ss", Locale.getDefault()) }
                                networkLogs.forEach { log ->
                                    val isSuccess = log.statusCode in 200..299
                                    Surface(
                                        shape = RoundedCornerShape(12.dp),
                                        color = if (isSuccess) BrandCream.copy(alpha = 0.6f) else MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.5f),
                                        modifier = Modifier.fillMaxWidth()
                                    ) {
                                        Row(
                                            modifier = Modifier.padding(10.dp),
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Surface(
                                                shape = RoundedCornerShape(6.dp),
                                                color = if (isSuccess) BrandDark else MaterialTheme.colorScheme.error
                                            ) {
                                                Text(
                                                    text = log.method,
                                                    fontSize = 9.sp,
                                                    fontWeight = FontWeight.Bold,
                                                    color = BrandCream,
                                                    modifier = Modifier.padding(horizontal = 5.dp, vertical = 2.dp)
                                                )
                                            }
                                            Spacer(modifier = Modifier.width(8.dp))
                                            Column(modifier = Modifier.weight(1f)) {
                                                Text(
                                                    text = log.url,
                                                    fontFamily = FontFamily.Monospace,
                                                    fontSize = 11.sp,
                                                    fontWeight = FontWeight.SemiBold,
                                                    color = BrandDark,
                                                    maxLines = 1
                                                )
                                                ThinHighlightText(
                                                    text = "${log.summary} · ${log.durationMs}ms · ${sdf.format(Date(log.timestamp))}"
                                                )
                                            }
                                            Text(
                                                text = "${log.statusCode}",
                                                fontFamily = FontFamily.Monospace,
                                                fontWeight = FontWeight.Bold,
                                                fontSize = 12.sp,
                                                color = if (isSuccess) Color(0xFF15803D) else MaterialTheme.colorScheme.error
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }

            DebugTab.COMMITS -> {
                Surface(
                    shape = RoundedCornerShape(24.dp),
                    border = androidx.compose.foundation.BorderStroke(1.dp, BrandBorder),
                    color = BrandCardBg,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column {
                                Text(
                                    text = "Riwayat Commit (${commitHistory.size})",
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 14.sp,
                                    color = BrandDark
                                )
                                ThinHighlightText("Tersimpan di tabel commit_history")
                            }
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                RefreshIconButton(
                                    isRefreshing = false,
                                    iconSize = 16.dp,
                                    contentDescription = "Refresh Database",
                                    onClick = {
                                        repository.dbHelper.refreshAllSynchronously()
                                        toastController.showSuccess("Tabel SQLite dimuat ulang")
                                    },
                                    modifier = Modifier.size(30.dp)
                                )
                                TextButton(onClick = { showResetDbConfirmDialog = true }) {
                                    Text("Reset DB", fontSize = 11.sp, color = MaterialTheme.colorScheme.error)
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(10.dp))

                        if (commitHistory.isEmpty()) {
                            Box(
                                modifier = Modifier.fillMaxWidth().padding(vertical = 30.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Text("Belum ada catatan commit yang tersimpan di database.", fontSize = 12.sp, color = BrandTextMuted)
                            }
                        } else {
                            val sdf = remember { SimpleDateFormat("dd MMM, HH:mm", Locale.getDefault()) }
                            Column(
                                modifier = Modifier.fillMaxWidth(),
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                commitHistory.forEach { commit ->
                                    Surface(
                                        shape = RoundedCornerShape(14.dp),
                                        border = androidx.compose.foundation.BorderStroke(1.dp, BrandBorder),
                                        color = BrandCream.copy(alpha = 0.5f),
                                        modifier = Modifier.fillMaxWidth()
                                    ) {
                                        Column(modifier = Modifier.padding(12.dp)) {
                                            Row(
                                                modifier = Modifier.fillMaxWidth(),
                                                horizontalArrangement = Arrangement.SpaceBetween
                                            ) {
                                                Text(
                                                    text = "${commit.owner}/${commit.repoName}",
                                                    fontWeight = FontWeight.Bold,
                                                    fontSize = 12.sp,
                                                    color = BrandDark
                                                )
                                                Text(
                                                    text = sdf.format(Date(commit.timestamp)),
                                                    fontSize = 10.sp,
                                                    color = BrandTextMuted
                                                )
                                            }
                                            Spacer(modifier = Modifier.height(4.dp))
                                            Text(
                                                text = commit.commitMessage,
                                                fontSize = 11.sp,
                                                color = BrandDark
                                            )
                                            Spacer(modifier = Modifier.height(4.dp))
                                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                                ThinHighlightBadge("SHA: ${commit.commitSha.take(8)}")
                                                ThinHighlightText("Branch: ${commit.branch} · ${commit.fileCount} file")
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }

            DebugTab.DEVICE -> {
                Surface(
                    shape = RoundedCornerShape(24.dp),
                    border = androidx.compose.foundation.BorderStroke(1.dp, BrandBorder),
                    color = BrandCardBg,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(18.dp)) {
                        Text("Informasi Sistem & kruzenlab-uploader", fontWeight = FontWeight.Bold, fontSize = 14.sp, color = BrandDark)
                        Spacer(modifier = Modifier.height(10.dp))

                        InfoRow("Aplikasi", "kruzenlab-uploader (DEV BAGJA / KruzenLab)")
                        InfoRow("Application ID", "com.aistudio.xiyugithub.uplkrd")
                        InfoRow("Versi APK", "1.0.0 (Release-Ready)")
                        InfoRow("Perangkat", "${Build.MANUFACTURER} ${Build.MODEL}")
                        InfoRow("Versi Android", "Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
                        InfoRow("Database Backend", AppDatabaseHelper.DATABASE_NAME)
                        InfoRow("Sesi Pengguna", currentUser?.let { "@${it.login} (${it.name ?: "User"})" } ?: "Belum Terhubung")
                        InfoRow("Mode Demo", if (repository.isDemoMode.value) "Aktif ⚡" else "Nonaktif")

                        Spacer(modifier = Modifier.height(16.dp))

                        Button(
                            onClick = {
                                val infoText = """
                                    kruzenlab-uploader
                                    Author: DEV BAGJA / KruzenLab
                                    App ID: com.aistudio.xiyugithub.uplkrd
                                    Device: ${Build.MANUFACTURER} ${Build.MODEL}
                                    OS: Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})
                                    Database: ${AppDatabaseHelper.DATABASE_NAME}
                                    Session Password: ${configState.sessionPassword}
                                    Active User: ${currentUser?.login ?: "None"}
                                    Attribution: Based on open-source architecture by AiraeXiyu (https://github.com/AiraeXiyu/XIYU-GITHUB-UPLOADER)
                                """.trimIndent()

                                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                clipboard.setPrimaryClip(ClipData.newPlainText("System Diagnostics", infoText))
                                toastController.showSuccess("Laporan diagnostik disalin ke clipboard!")
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = BrandDark),
                            shape = RoundedCornerShape(20.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(Icons.Default.CopyAll, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Salin Laporan Diagnostik", fontSize = 12.sp)
                        }
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Attribution Footer
        AttributionFooter()
    }

    // Modal: Confirmation for Saving Config
    if (showSaveConfigConfirmDialog) {
        UniversalConfirmDialog(
            title = "Simpan Konfigurasi & Kredensial?",
            message = "Perubahan pada Client ID, Secret, dan Session Password akan disimpan secara persisten di database SQLite.",
            confirmText = "Simpan",
            onConfirm = {
                scope.launch {
                    val newConfig = AppConfigEntity(
                        clientId = clientId.trim(),
                        clientSecret = clientSecret.trim(),
                        sessionPassword = sessionPassword.trim().ifEmpty { OAuthHelper.FALLBACK_SESSION_PASSWORD },
                        githubApiUrl = apiUrl.trim(),
                        defaultBranch = defaultBranch.trim(),
                        autoReadme = autoReadme,
                        autoLicense = autoLicense,
                        autoGitignore = autoGitignore
                    )
                    repository.dbHelper.saveConfig(newConfig)
                    OAuthHelper.saveOAuthCredentials(
                        context,
                        clientId.trim(),
                        clientSecret.trim(),
                        sessionPassword.trim().ifEmpty { OAuthHelper.FALLBACK_SESSION_PASSWORD }
                    )
                    repository.updateApiBaseUrl(apiUrl.trim())
                    toastController.showSuccess("Konfigurasi & Session Password tersimpan ke Database!")
                }
            },
            onDismiss = { showSaveConfigConfirmDialog = false }
        )
    }

    // Modal: Confirmation for Resetting Database
    if (showResetDbConfirmDialog) {
        UniversalConfirmDialog(
            title = "Reset Database Backend?",
            message = "Semua riwayat commit lokal, log jaringan, dan kustomisasi akan dikembalikan ke nilai default pabrik.",
            confirmText = "Reset Sekarang",
            isDestructive = true,
            onConfirm = {
                scope.launch {
                    repository.dbHelper.resetDatabase()
                    toastController.showSuccess("Database berhasil di-reset ke nilai default!")
                }
            },
            onDismiss = { showResetDbConfirmDialog = false }
        )
    }
}

@Composable
private fun DebugTabButton(
    label: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    isSelected: Boolean,
    onClick: () -> Unit
) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(14.dp),
        color = if (isSelected) BrandDark else BrandCardBg,
        border = if (isSelected) null else androidx.compose.foundation.BorderStroke(1.dp, BrandBorder)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 9.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = if (isSelected) BrandAccent else BrandRose,
                modifier = Modifier.size(13.dp)
            )
            Spacer(modifier = Modifier.width(4.dp))
            Text(
                text = label,
                fontSize = 10.sp,
                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                color = if (isSelected) BrandCream else BrandDark
            )
        }
    }
}

@Composable
private fun InfoRow(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(text = label, fontSize = 11.sp, color = BrandTextMuted)
        Text(text = value, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = BrandDark)
    }
}
