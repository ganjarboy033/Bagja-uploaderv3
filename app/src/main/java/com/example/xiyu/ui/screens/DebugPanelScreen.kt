package com.example.xiyu.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.xiyu.data.database.AppConfigEntity
import com.example.xiyu.data.database.AppDatabaseHelper
import com.example.xiyu.data.database.NetworkLogRecord
import com.example.xiyu.data.model.GitHubRepo
import com.example.xiyu.data.model.ThemeMode
import com.example.xiyu.data.model.ThemeSettingsEntity
import com.example.xiyu.data.model.UserProfile
import com.example.xiyu.data.repository.GitHubRepository
import com.example.xiyu.ui.components.*
import com.example.xiyu.ui.navigation.Screen
import com.example.xiyu.ui.theme.*
import com.example.xiyu.util.HttpErrorAdvisor
import com.example.xiyu.util.OAuthHelper
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

enum class DebugTab {
    CONFIG, NETWORK, DEVICE, COMMITS
}

private fun routeToScreen(route: String?): Screen? = when (route) {
    Screen.Login.route -> Screen.Login
    Screen.Upload.route -> Screen.Upload
    Screen.RepoExplorer.route -> Screen.RepoExplorer
    Screen.DebugPanel.route -> Screen.DebugPanel
    Screen.Dashboard.route -> Screen.Dashboard
    Screen.WorkflowRelease.route -> Screen.WorkflowRelease
    Screen.CiCdMonitor.route -> Screen.CiCdMonitor
    Screen.Downloads.route -> Screen.Downloads
    else -> null
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DebugPanelScreen(
    repository: GitHubRepository,
    currentUser: UserProfile?,
    onNavigate: (Screen) -> Unit,
    modifier: Modifier = Modifier
) {
    val palette = LocalKruzenPalette.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val scrollState = rememberScrollState()
    val toastController = LocalToastController.current

    var activeTab by remember { mutableStateOf(DebugTab.CONFIG) }

    val configState by repository.dbHelper.configState.collectAsState()
    val networkLogs by repository.dbHelper.recentLogs.collectAsState()
    val latestHttpError by repository.dbHelper.latestHttpError.collectAsState()
    val commitHistory by repository.dbHelper.commitHistory.collectAsState()
    val repoWebhooks by repository.dbHelper.repoWebhooks.collectAsState()

    var clientId by remember(configState.clientId) { mutableStateOf(configState.clientId) }
    var clientSecret by remember(configState.clientSecret) { mutableStateOf(configState.clientSecret) }
    var sessionPassword by remember(configState.sessionPassword) { mutableStateOf(configState.sessionPassword) }
    var apiUrl by remember(configState.githubApiUrl) { mutableStateOf(configState.githubApiUrl) }
    var defaultBranch by remember(configState.defaultBranch) { mutableStateOf(configState.defaultBranch) }
    var autoReadme by remember(configState.autoReadme) { mutableStateOf(configState.autoReadme) }
    var autoLicense by remember(configState.autoLicense) { mutableStateOf(configState.autoLicense) }
    var autoGitignore by remember(configState.autoGitignore) { mutableStateOf(configState.autoGitignore) }

    // Theme hex inputs synced with configState.themeSettings
    var hexPrimaryInput by remember(configState.themeSettings.customPrimaryHex) {
        mutableStateOf(configState.themeSettings.customPrimaryHex)
    }
    var hexSecondaryInput by remember(configState.themeSettings.customSecondaryHex) {
        mutableStateOf(configState.themeSettings.customSecondaryHex)
    }
    var hexAccentInput by remember(configState.themeSettings.customAccentHex) {
        mutableStateOf(configState.themeSettings.customAccentHex)
    }
    var hexTextInput by remember(configState.themeSettings.customTextHex) {
        mutableStateOf(configState.themeSettings.customTextHex)
    }

    // Vercel webhook manager states
    var availableRepos by remember { mutableStateOf<List<GitHubRepo>>(emptyList()) }
    var webhookRepoInput by remember { mutableStateOf("") }
    var webhookUrlInput by remember { mutableStateOf("") }
    var isRepoDropdownExpanded by remember { mutableStateOf(false) }
    var deployingWebhookRepo by remember { mutableStateOf<String?>(null) }

    var isTestingPing by remember { mutableStateOf(false) }
    var showResetDbConfirmDialog by remember { mutableStateOf(false) }
    var showSaveConfigConfirmDialog by remember { mutableStateOf(false) }
    var selectedLogForAdvice by remember { mutableStateOf<NetworkLogRecord?>(null) }

    LaunchedEffect(Unit) {
        repository.getRepositories().onSuccess { list ->
            availableRepos = list
            if (webhookRepoInput.isBlank() && list.isNotEmpty()) {
                webhookRepoInput = list.first().fullName
            }
        }
    }

    fun updateConfigImmediate(transform: (AppConfigEntity) -> AppConfigEntity) {
        scope.launch {
            val updated = transform(repository.dbHelper.configState.value)
            repository.dbHelper.saveConfig(updated)
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(palette.cream)
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
                tint = palette.rose,
                modifier = Modifier.size(16.dp)
            )
            Spacer(modifier = Modifier.width(6.dp))
            Text(
                text = "Kembali ke Dashboard",
                fontSize = 12.sp,
                color = palette.textMuted
            )
        }

        // Header
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "KRUZENLAB CONTROL CENTER ⚡",
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    color = palette.rose,
                    letterSpacing = 1.6.sp
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = "Panel Debug & OAuth",
                    fontFamily = FontFamily.SansSerif,
                    fontWeight = FontWeight.Bold,
                    fontSize = 24.sp,
                    color = palette.dark
                )
                ThinHighlightText("Pengaturan fitur, tema UI, OAuth & SQLite backend.", color = palette.textMuted)
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
                    color = palette.palePink
                ) {
                    Text(
                        text = "SQLite Backend",
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        color = palette.rose,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(14.dp))

        // Tab Row
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            DebugTabButton("Kustomisasi", Icons.Default.Tune, activeTab == DebugTab.CONFIG, palette) {
                activeTab = DebugTab.CONFIG
            }
            DebugTabButton("Log Jaringan", Icons.Default.NetworkCheck, activeTab == DebugTab.NETWORK, palette) {
                activeTab = DebugTab.NETWORK
            }
            DebugTabButton("Database", Icons.Default.Storage, activeTab == DebugTab.COMMITS, palette) {
                activeTab = DebugTab.COMMITS
            }
            DebugTabButton("Info Sistem", Icons.Default.Info, activeTab == DebugTab.DEVICE, palette) {
                activeTab = DebugTab.DEVICE
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        when (activeTab) {
            DebugTab.CONFIG -> {
                // 1. Credentials & Session Card
                Surface(
                    shape = RoundedCornerShape(24.dp),
                    border = androidx.compose.foundation.BorderStroke(1.dp, palette.border),
                    color = palette.cardBg,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(18.dp)) {
                        Text(
                            text = "Kustomisasi Kredensial & Sesi",
                            fontWeight = FontWeight.Bold,
                            fontSize = 15.sp,
                            color = palette.dark
                        )
                        ThinHighlightText(
                            text = "Tersedia nilai fallback otomatis jika kolom dikosongkan tanpa memicu error.",
                            color = palette.textMuted
                        )

                        Spacer(modifier = Modifier.height(14.dp))

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
                                    Icon(Icons.Default.ContentCopy, contentDescription = "Salin", tint = palette.rose, modifier = Modifier.size(18.dp))
                                }
                            },
                            modifier = Modifier.fillMaxWidth().testTag("config_session_password_input")
                        )

                        val len = sessionPassword.length
                        val isValidLen = len >= 32
                        ThinHighlightText(
                            text = if (isValidLen) "✓ Panjang: $len karakter (Memenuhi syarat >= 32 char)"
                            else "⚠️ Panjang: $len karakter (Disarankan minimal 32 karakter)",
                            color = if (isValidLen) palette.success else MaterialTheme.colorScheme.error
                        )

                        Spacer(modifier = Modifier.height(10.dp))

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
                            text = "Fallback: ${if (clientId.isBlank()) "Aktif (${OAuthHelper.FALLBACK_CLIENT_ID})" else "Nilai kustom aktif"}",
                            color = palette.textMuted
                        )

                        Spacer(modifier = Modifier.height(10.dp))

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
                            text = "Fallback: ${if (clientSecret.isBlank()) "Default aktif (${OAuthHelper.FALLBACK_CLIENT_SECRET})" else "Nilai kustom aktif"}",
                            color = palette.textMuted
                        )

                        Spacer(modifier = Modifier.height(10.dp))

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

                        Text("Template Standar Upload", fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = palette.dark)
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

                        Button(
                            onClick = { showSaveConfigConfirmDialog = true },
                            colors = ButtonDefaults.buttonColors(containerColor = palette.dark, contentColor = palette.cream),
                            shape = RoundedCornerShape(20.dp),
                            modifier = Modifier.fillMaxWidth().height(46.dp).testTag("save_config_btn")
                        ) {
                            Icon(Icons.Default.Save, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Simpan ke Database Backend", fontWeight = FontWeight.Bold, fontSize = 12.sp)
                        }
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                // 2. Master Feature Control Toggles (10 Safety Switches)
                Surface(
                    shape = RoundedCornerShape(24.dp),
                    border = androidx.compose.foundation.BorderStroke(1.dp, palette.border),
                    color = palette.cardBg,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            Icon(Icons.Default.ToggleOn, contentDescription = null, tint = palette.rose, modifier = Modifier.size(20.dp))
                            Text(
                                text = "Panel Kontrol Fitur (Safety Toggles)",
                                fontWeight = FontWeight.Bold,
                                fontSize = 15.sp,
                                color = palette.dark
                            )
                        }
                        ThinHighlightText(
                            text = "Matikan fitur kapan saja untuk kembali ke perilaku standar bawaan. Tersimpan otomatis ke SQLite.",
                            color = palette.textMuted
                        )

                        Spacer(modifier = Modifier.height(4.dp))

                        FeatureToggleRow(
                            title = "High-Speed Editor (>100 KB)",
                            subtitle = "Virtualisasi LazyColumn per baris anti-lag & quick-symbol bar. OFF = editor bawaan.",
                            checked = configState.useFastHighSpeedEditor,
                            palette = palette,
                            onCheckedChange = { v -> updateConfigImmediate { it.copy(useFastHighSpeedEditor = v) } }
                        )
                        FeatureToggleRow(
                            title = "ZIP Deep Editor (Bongkar & Repack)",
                            subtitle = "Bongkar arsip .zip, edit/tambah/hapus isi, lalu zip ulang & commit.",
                            checked = configState.enableZipDeepEditor,
                            palette = palette,
                            onCheckedChange = { v -> updateConfigImmediate { it.copy(enableZipDeepEditor = v) } }
                        )
                        FeatureToggleRow(
                            title = "Notifikasi Sistem Aktivitas",
                            subtitle = "Kirim notifikasi status upload, unduhan ZIP/APK, & alert error HTTP.",
                            checked = configState.enableSystemNotifications,
                            palette = palette,
                            onCheckedChange = { v -> updateConfigImmediate { it.copy(enableSystemNotifications = v) } }
                        )
                        FeatureToggleRow(
                            title = "Log Error Advisor & Tombol (i)",
                            subtitle = "Penjelasan arti, penyebab, & solusi error HTTP 401/403/404/409 di log jaringan.",
                            checked = configState.enableLogErrorAdvisor,
                            palette = palette,
                            onCheckedChange = { v -> updateConfigImmediate { it.copy(enableLogErrorAdvisor = v) } }
                        )
                        FeatureToggleRow(
                            title = "Branch Compare & Merge (Files)",
                            subtitle = "Bandingkan diff file antar-branch dan gabungkan langsung di File Manager.",
                            checked = configState.enableBranchCompare,
                            palette = palette,
                            onCheckedChange = { v -> updateConfigImmediate { it.copy(enableBranchCompare = v) } }
                        )
                        FeatureToggleRow(
                            title = "Tombol Instal APK Langsung",
                            subtitle = "Tampilkan tombol 'Instal APK' di tab Downloads via FileProvider aman.",
                            checked = configState.enableAutoInstallApk,
                            palette = palette,
                            onCheckedChange = { v -> updateConfigImmediate { it.copy(enableAutoInstallApk = v) } }
                        )
                        FeatureToggleRow(
                            title = "Smart .gitignore Filter (Uploader)",
                            subtitle = "Saring node_modules, build/, .gradle, dan file .gitignore saat import ZIP/Folder.",
                            checked = configState.enableSmartGitignore,
                            palette = palette,
                            onCheckedChange = { v -> updateConfigImmediate { it.copy(enableSmartGitignore = v) } }
                        )
                        FeatureToggleRow(
                            title = "Rate Limit Pacer (Anti-401/403)",
                            subtitle = "Jeda mikro & batching otomatis saat mengunggah ratusan hingga ribuan file.",
                            checked = configState.enableRateLimitPacer,
                            palette = palette,
                            onCheckedChange = { v -> updateConfigImmediate { it.copy(enableRateLimitPacer = v) } }
                        )
                        FeatureToggleRow(
                            title = "Vercel Webhook 1-Click Deploy",
                            subtitle = "Aktifkan trigger Deploy Hook Vercel per repositori di Files & Settings.",
                            checked = configState.enableVercelWebhook,
                            palette = palette,
                            onCheckedChange = { v -> updateConfigImmediate { it.copy(enableVercelWebhook = v) } }
                        )
                        FeatureToggleRow(
                            title = "Global Repo Code Search",
                            subtitle = "Pencarian teks/kode lintas seluruh berkas repositori di tab Files.",
                            checked = configState.enableGlobalRepoSearch,
                            palette = palette,
                            onCheckedChange = { v -> updateConfigImmediate { it.copy(enableGlobalRepoSearch = v) } }
                        )
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                // 3. Theme & Typography Scale Customization Card
                val curTheme = configState.themeSettings
                Surface(
                    shape = RoundedCornerShape(24.dp),
                    border = androidx.compose.foundation.BorderStroke(1.dp, palette.border),
                    color = palette.cardBg,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                Icon(Icons.Default.Palette, contentDescription = null, tint = palette.rose, modifier = Modifier.size(20.dp))
                                Text(
                                    text = "Kustomisasi Tema & Skala Teks",
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 15.sp,
                                    color = palette.dark
                                )
                            }
                            TextButton(
                                onClick = {
                                    hexPrimaryInput = ""
                                    hexSecondaryInput = ""
                                    hexAccentInput = ""
                                    hexTextInput = ""
                                    updateConfigImmediate { it.copy(themeSettings = ThemeSettingsEntity()) }
                                    toastController.showSuccess("Tema dikembalikan ke Mode Bawaan KruzenLab")
                                },
                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)
                            ) {
                                Text("Reset ke Bawaan", fontSize = 11.sp, color = palette.rose, maxLines = 1, softWrap = false)
                            }
                        }

                        Text("Mode Tema", fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = palette.dark)
                        Row(
                            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            listOf(
                                ThemeMode.DEFAULT_BRAND to "Bawaan",
                                ThemeMode.LIGHT to "Terang",
                                ThemeMode.DARK to "Gelap",
                                ThemeMode.SYSTEM to "Sistem"
                            ).forEach { (mode, label) ->
                                FilterChip(
                                    selected = curTheme.themeMode == mode,
                                    onClick = {
                                        updateConfigImmediate {
                                            it.copy(themeSettings = it.themeSettings.copy(themeMode = mode))
                                        }
                                    },
                                    label = { Text(label, fontSize = 11.sp) },
                                    shape = RoundedCornerShape(14.dp)
                                )
                            }
                        }

                        Text("Skala Ukuran Teks & UI", fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = palette.dark)
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            listOf(
                                0.85f to "0.85x",
                                1.0f to "1.0x",
                                1.15f to "1.15x",
                                1.30f to "1.30x"
                            ).forEach { (scale, label) ->
                                val selected = kotlin.math.abs(curTheme.fontScale - scale) < 0.02f
                                FilterChip(
                                    selected = selected,
                                    onClick = {
                                        updateConfigImmediate {
                                            it.copy(themeSettings = it.themeSettings.copy(fontScale = scale))
                                        }
                                    },
                                    label = { Text(label, fontSize = 11.sp) },
                                    shape = RoundedCornerShape(14.dp),
                                    modifier = Modifier.weight(1f)
                                )
                            }
                        }

                        Text("Kustomisasi Warna Hex (Opsional)", fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = palette.dark)
                        ThinHighlightText("Pilih swatch cepat atau masukkan kode Hex (mis. #2D232E) untuk mengubah warna secara real-time.", color = palette.textMuted)

                        HexColorPickerRow(
                            label = "Primary (BrandDark)",
                            hexValue = hexPrimaryInput,
                            swatches = listOf("#2D232E", "#1E3A8A", "#0F766E", "#6D28D9"),
                            palette = palette,
                            onValueChange = { hexPrimaryInput = it },
                            onApply = { hex ->
                                hexPrimaryInput = hex
                                updateConfigImmediate {
                                    it.copy(themeSettings = it.themeSettings.copy(customPrimaryHex = hex.trim()))
                                }
                            }
                        )
                        HexColorPickerRow(
                            label = "Secondary (BrandRose)",
                            hexValue = hexSecondaryInput,
                            swatches = listOf("#8E5A5E", "#2563EB", "#0D9488", "#9333EA"),
                            palette = palette,
                            onValueChange = { hexSecondaryInput = it },
                            onApply = { hex ->
                                hexSecondaryInput = hex
                                updateConfigImmediate {
                                    it.copy(themeSettings = it.themeSettings.copy(customSecondaryHex = hex.trim()))
                                }
                            }
                        )
                        HexColorPickerRow(
                            label = "Accent (Highlight)",
                            hexValue = hexAccentInput,
                            swatches = listOf("#C8A86B", "#F59E0B", "#10B981", "#EC4899"),
                            palette = palette,
                            onValueChange = { hexAccentInput = it },
                            onApply = { hex ->
                                hexAccentInput = hex
                                updateConfigImmediate {
                                    it.copy(themeSettings = it.themeSettings.copy(customAccentHex = hex.trim()))
                                }
                            }
                        )
                        HexColorPickerRow(
                            label = "Warna Teks Utama",
                            hexValue = hexTextInput,
                            swatches = listOf("#251F24", "#111827", "#F6EFEA", "#1E293B"),
                            palette = palette,
                            onValueChange = { hexTextInput = it },
                            onApply = { hex ->
                                hexTextInput = hex
                                updateConfigImmediate {
                                    it.copy(themeSettings = it.themeSettings.copy(customTextHex = hex.trim()))
                                }
                            }
                        )
                    }
                }

                // 4. Vercel Deploy Hooks Manager (only if toggle is ON)
                if (configState.enableVercelWebhook) {
                    Spacer(modifier = Modifier.height(16.dp))
                    Surface(
                        shape = RoundedCornerShape(24.dp),
                        border = androidx.compose.foundation.BorderStroke(1.dp, palette.border),
                        color = palette.cardBg,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                Icon(Icons.Default.RocketLaunch, contentDescription = null, tint = palette.rose, modifier = Modifier.size(18.dp))
                                Text(
                                    text = "Vercel Deploy Hooks per Repositori",
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 15.sp,
                                    color = palette.dark
                                )
                            }
                            ThinHighlightText(
                                text = "Simpan Deploy Hook URL Vercel untuk memicu build & deploy 1-klik dari sini atau dari tab Files.",
                                color = palette.textMuted
                            )

                            if (availableRepos.isNotEmpty()) {
                                ExposedDropdownMenuBox(
                                    expanded = isRepoDropdownExpanded,
                                    onExpandedChange = { isRepoDropdownExpanded = !isRepoDropdownExpanded }
                                ) {
                                    OutlinedTextField(
                                        value = webhookRepoInput,
                                        onValueChange = { webhookRepoInput = it },
                                        label = { Text("Repositori (owner/repo)") },
                                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = isRepoDropdownExpanded) },
                                        singleLine = true,
                                        shape = RoundedCornerShape(12.dp),
                                        modifier = Modifier.menuAnchor().fillMaxWidth()
                                    )
                                    ExposedDropdownMenu(
                                        expanded = isRepoDropdownExpanded,
                                        onDismissRequest = { isRepoDropdownExpanded = false }
                                    ) {
                                        availableRepos.forEach { repo ->
                                            DropdownMenuItem(
                                                text = { Text(repo.fullName, fontSize = 12.sp) },
                                                onClick = {
                                                    webhookRepoInput = repo.fullName
                                                    val existing = repoWebhooks.find { it.repoFullName == repo.fullName }
                                                    if (existing != null) webhookUrlInput = existing.vercelHookUrl
                                                    isRepoDropdownExpanded = false
                                                }
                                            )
                                        }
                                    }
                                }
                            } else {
                                OutlinedTextField(
                                    value = webhookRepoInput,
                                    onValueChange = { webhookRepoInput = it },
                                    label = { Text("Repositori (owner/repo)") },
                                    placeholder = { Text("username/my-web-app") },
                                    singleLine = true,
                                    shape = RoundedCornerShape(12.dp),
                                    modifier = Modifier.fillMaxWidth()
                                )
                            }

                            OutlinedTextField(
                                value = webhookUrlInput,
                                onValueChange = { webhookUrlInput = it },
                                label = { Text("Vercel Deploy Hook URL") },
                                placeholder = { Text("https://api.vercel.com/v1/integrations/deploy/...") },
                                singleLine = true,
                                shape = RoundedCornerShape(12.dp),
                                modifier = Modifier.fillMaxWidth()
                            )

                            Button(
                                onClick = {
                                    val repoKey = webhookRepoInput.trim()
                                    val hookUrl = webhookUrlInput.trim()
                                    if (repoKey.isEmpty() || !hookUrl.startsWith("http")) {
                                        toastController.showError("Isi owner/repo dan URL Webhook Vercel yang valid!")
                                        return@Button
                                    }
                                    scope.launch {
                                        repository.dbHelper.saveRepoWebhook(repoKey, hookUrl)
                                        toastController.showSuccess("Webhook Vercel untuk $repoKey tersimpan!")
                                        webhookUrlInput = ""
                                    }
                                },
                                colors = ButtonDefaults.buttonColors(containerColor = palette.dark, contentColor = palette.cream),
                                shape = RoundedCornerShape(16.dp),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Icon(Icons.Default.AddLink, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("Simpan Deploy Hook", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                            }

                            if (repoWebhooks.isNotEmpty()) {
                                HorizontalDivider(color = palette.border)
                                val sdf = remember { SimpleDateFormat("dd MMM HH:mm", Locale.getDefault()) }
                                repoWebhooks.forEach { item ->
                                    Surface(
                                        shape = RoundedCornerShape(12.dp),
                                        color = palette.cream.copy(alpha = 0.65f),
                                        border = androidx.compose.foundation.BorderStroke(1.dp, palette.border),
                                        modifier = Modifier.fillMaxWidth()
                                    ) {
                                        Row(
                                            modifier = Modifier.padding(10.dp),
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Column(modifier = Modifier.weight(1f)) {
                                                Text(item.repoFullName, fontWeight = FontWeight.Bold, fontSize = 12.sp, color = palette.dark)
                                                ThinHighlightText(item.vercelHookUrl, maxLines = 1, color = palette.textMuted)
                                                if (item.lastDeployedAt > 0L) {
                                                    ThinHighlightText("Terakhir deploy: ${sdf.format(Date(item.lastDeployedAt))}", fontSize = 10.sp, color = palette.rose)
                                                }
                                            }
                                            Spacer(modifier = Modifier.width(6.dp))
                                            Button(
                                                onClick = {
                                                    deployingWebhookRepo = item.repoFullName
                                                    scope.launch {
                                                        repository.triggerVercelWebhook(item.repoFullName, item.vercelHookUrl).fold(
                                                            onSuccess = { msg -> toastController.showSuccess(msg) },
                                                            onFailure = { err -> toastController.showError("Deploy gagal: ${err.localizedMessage}") }
                                                        )
                                                        deployingWebhookRepo = null
                                                    }
                                                },
                                                enabled = deployingWebhookRepo != item.repoFullName,
                                                shape = RoundedCornerShape(10.dp),
                                                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                                                colors = ButtonDefaults.buttonColors(containerColor = palette.dark, contentColor = palette.cream),
                                                modifier = Modifier.height(30.dp)
                                            ) {
                                                Text(
                                                    if (deployingWebhookRepo == item.repoFullName) "..." else "Deploy",
                                                    fontSize = 10.sp,
                                                    maxLines = 1,
                                                    softWrap = false
                                                )
                                            }
                                            IconButton(
                                                onClick = {
                                                    scope.launch {
                                                        repository.dbHelper.deleteRepoWebhook(item.repoFullName)
                                                        toastController.showSuccess("Hook ${item.repoFullName} dihapus")
                                                    }
                                                },
                                                modifier = Modifier.size(30.dp)
                                            ) {
                                                Icon(Icons.Default.DeleteOutline, contentDescription = "Hapus Hook", tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(16.dp))
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }

            DebugTab.NETWORK -> {
                Surface(
                    shape = RoundedCornerShape(24.dp),
                    border = androidx.compose.foundation.BorderStroke(1.dp, palette.border),
                    color = palette.cardBg,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "Log Jaringan (${networkLogs.size})",
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 14.sp,
                                    color = palette.dark,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                ThinHighlightText("Riwayat REST API & Advisor", color = palette.textMuted)
                            }
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(2.dp)
                            ) {
                                RefreshIconButton(
                                    isRefreshing = isTestingPing,
                                    iconSize = 16.dp,
                                    contentDescription = "Refresh Log Jaringan",
                                    onClick = {
                                        repository.dbHelper.refreshAllSynchronously()
                                        toastController.showSuccess("Log jaringan dimuat ulang")
                                    },
                                    modifier = Modifier.size(28.dp)
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
                                    enabled = !isTestingPing,
                                    contentPadding = PaddingValues(horizontal = 6.dp, vertical = 2.dp)
                                ) {
                                    Text(
                                        text = "Ping",
                                        fontSize = 11.sp,
                                        color = palette.rose,
                                        maxLines = 1,
                                        softWrap = false
                                    )
                                }
                                TextButton(
                                    onClick = {
                                        scope.launch {
                                            repository.dbHelper.clearLogs()
                                            toastController.showSuccess("Log dibersihkan")
                                        }
                                    },
                                    contentPadding = PaddingValues(horizontal = 6.dp, vertical = 2.dp),
                                    modifier = Modifier.testTag("clear_network_logs_btn")
                                ) {
                                    Text(
                                        text = "Bersihkan",
                                        fontSize = 11.sp,
                                        color = palette.textMuted,
                                        maxLines = 1,
                                        softWrap = false
                                    )
                                }
                            }
                        }

                        // Live Error Advisor Banner
                        if (configState.enableLogErrorAdvisor && latestHttpError != null) {
                            val errLog = latestHttpError!!
                            val advice = remember(errLog) { HttpErrorAdvisor.explain(errLog) }
                            Spacer(modifier = Modifier.height(10.dp))
                            Surface(
                                shape = RoundedCornerShape(14.dp),
                                color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.7f),
                                border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.error.copy(alpha = 0.4f)),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                                            Icon(Icons.Default.ErrorOutline, contentDescription = null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(16.dp))
                                            Spacer(modifier = Modifier.width(6.dp))
                                            Text(advice.title, fontWeight = FontWeight.Bold, fontSize = 12.sp, color = MaterialTheme.colorScheme.onErrorContainer)
                                        }
                                        IconButton(
                                            onClick = { repository.dbHelper.dismissLatestHttpError() },
                                            modifier = Modifier.size(24.dp)
                                        ) {
                                            Icon(Icons.Default.Close, contentDescription = "Tutup Banner", modifier = Modifier.size(14.dp))
                                        }
                                    }
                                    Text(advice.solution, fontSize = 11.sp, color = MaterialTheme.colorScheme.onErrorContainer)
                                    val targetScreen = routeToScreen(advice.quickActionRoute)
                                    if (!advice.quickActionLabel.isNullOrBlank() && targetScreen != null) {
                                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                            Button(
                                                onClick = {
                                                    repository.dbHelper.dismissLatestHttpError()
                                                    onNavigate(targetScreen)
                                                },
                                                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                                                shape = RoundedCornerShape(10.dp),
                                                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 2.dp),
                                                modifier = Modifier.height(28.dp)
                                            ) {
                                                Text(advice.quickActionLabel, fontSize = 10.sp, maxLines = 1, softWrap = false)
                                            }
                                            TextButton(
                                                onClick = { selectedLogForAdvice = errLog },
                                                contentPadding = PaddingValues(horizontal = 6.dp, vertical = 2.dp),
                                                modifier = Modifier.height(28.dp)
                                            ) {
                                                Text("Detail (i)", fontSize = 10.sp, maxLines = 1, softWrap = false)
                                            }
                                        }
                                    }
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(10.dp))

                        if (networkLogs.isEmpty()) {
                            Box(
                                modifier = Modifier.fillMaxWidth().padding(vertical = 30.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Text("Belum ada log panggilan jaringan.", fontSize = 12.sp, color = palette.textMuted)
                            }
                        } else {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .heightIn(max = 420.dp)
                                    .verticalScroll(rememberScrollState()),
                                verticalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                val sdf = remember { SimpleDateFormat("HH:mm:ss", Locale.getDefault()) }
                                networkLogs.forEach { log ->
                                    val isSuccess = log.statusCode in 200..299
                                    Surface(
                                        shape = RoundedCornerShape(12.dp),
                                        color = if (isSuccess) palette.cream.copy(alpha = 0.6f) else MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.5f),
                                        modifier = Modifier.fillMaxWidth()
                                    ) {
                                        Row(
                                            modifier = Modifier.padding(10.dp),
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Surface(
                                                shape = RoundedCornerShape(6.dp),
                                                color = if (isSuccess) palette.dark else MaterialTheme.colorScheme.error
                                            ) {
                                                Text(
                                                    text = log.method,
                                                    fontSize = 9.sp,
                                                    fontWeight = FontWeight.Bold,
                                                    color = palette.cream,
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
                                                    color = palette.dark,
                                                    maxLines = 1,
                                                    overflow = TextOverflow.Ellipsis
                                                )
                                                ThinHighlightText(
                                                    text = "${log.summary} · ${log.durationMs}ms · ${sdf.format(Date(log.timestamp))}",
                                                    color = palette.textMuted
                                                )
                                            }
                                            Text(
                                                text = "${log.statusCode}",
                                                fontFamily = FontFamily.Monospace,
                                                fontWeight = FontWeight.Bold,
                                                fontSize = 12.sp,
                                                color = if (isSuccess) palette.success else MaterialTheme.colorScheme.error
                                            )
                                            if (configState.enableLogErrorAdvisor) {
                                                IconButton(
                                                    onClick = { selectedLogForAdvice = log },
                                                    modifier = Modifier.size(28.dp)
                                                ) {
                                                    Icon(
                                                        imageVector = Icons.Default.Info,
                                                        contentDescription = "Penjelasan Status HTTP",
                                                        tint = if (isSuccess) palette.rose else MaterialTheme.colorScheme.error,
                                                        modifier = Modifier.size(16.dp)
                                                    )
                                                }
                                            }
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
                    border = androidx.compose.foundation.BorderStroke(1.dp, palette.border),
                    color = palette.cardBg,
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
                                    color = palette.dark
                                )
                                ThinHighlightText("Tersimpan di tabel commit_history", color = palette.textMuted)
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
                                    Text("Reset DB", fontSize = 11.sp, color = MaterialTheme.colorScheme.error, maxLines = 1, softWrap = false)
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(10.dp))

                        if (commitHistory.isEmpty()) {
                            Box(
                                modifier = Modifier.fillMaxWidth().padding(vertical = 30.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Text("Belum ada catatan commit yang tersimpan di database.", fontSize = 12.sp, color = palette.textMuted)
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
                                        border = androidx.compose.foundation.BorderStroke(1.dp, palette.border),
                                        color = palette.cream.copy(alpha = 0.5f),
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
                                                    color = palette.dark
                                                )
                                                Text(
                                                    text = sdf.format(Date(commit.timestamp)),
                                                    fontSize = 10.sp,
                                                    color = palette.textMuted
                                                )
                                            }
                                            Spacer(modifier = Modifier.height(4.dp))
                                            Text(
                                                text = commit.commitMessage,
                                                fontSize = 11.sp,
                                                color = palette.dark
                                            )
                                            Spacer(modifier = Modifier.height(4.dp))
                                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                                ThinHighlightBadge("SHA: ${commit.commitSha.take(8)}")
                                                ThinHighlightText("Branch: ${commit.branch} · ${commit.fileCount} file", color = palette.textMuted)
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
                    border = androidx.compose.foundation.BorderStroke(1.dp, palette.border),
                    color = palette.cardBg,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(18.dp)) {
                        Text("Informasi Sistem & kruzenlab-uploader", fontWeight = FontWeight.Bold, fontSize = 14.sp, color = palette.dark)
                        Spacer(modifier = Modifier.height(10.dp))

                        InfoRow("Aplikasi", "kruzenlab-uploader (DEV BAGJA / KruzenLab)", palette)
                        InfoRow("Application ID", "com.aistudio.xiyugithub.uplkrd", palette)
                        InfoRow("Versi APK", "1.0.0 (Release-Ready)", palette)
                        InfoRow("Perangkat", "${Build.MANUFACTURER} ${Build.MODEL}", palette)
                        InfoRow("Versi Android", "Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})", palette)
                        InfoRow("Database Backend", "${AppDatabaseHelper.DATABASE_NAME} (v${AppDatabaseHelper.DATABASE_VERSION})", palette)
                        InfoRow("Sesi Pengguna", currentUser?.let { "@${it.login} (${it.name ?: "User"})" } ?: "Belum Terhubung", palette)
                        InfoRow("Mode Demo", if (repository.isDemoMode.value) "Aktif ⚡" else "Nonaktif", palette)

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
                            colors = ButtonDefaults.buttonColors(containerColor = palette.dark, contentColor = palette.cream),
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

    // Modal: Log Explanation Dialog (i)
    if (selectedLogForAdvice != null) {
        val logItem = selectedLogForAdvice!!
        val advice = remember(logItem) { HttpErrorAdvisor.explain(logItem) }
        AlertDialog(
            onDismissRequest = { selectedLogForAdvice = null },
            title = {
                Text(advice.title, fontWeight = FontWeight.Bold, fontSize = 15.sp, color = palette.dark)
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("${logItem.method} ${logItem.url}", fontFamily = FontFamily.Monospace, fontSize = 10.sp, color = palette.rose)
                    Text("Arti:", fontWeight = FontWeight.Bold, fontSize = 12.sp, color = palette.dark)
                    Text(advice.meaning, fontSize = 12.sp, color = palette.textDark)
                    Text("Kemungkinan Penyebab:", fontWeight = FontWeight.Bold, fontSize = 12.sp, color = palette.dark)
                    Text(advice.cause, fontSize = 12.sp, color = palette.textDark)
                    Text("Solusi yang Disarankan:", fontWeight = FontWeight.Bold, fontSize = 12.sp, color = palette.dark)
                    Surface(
                        shape = RoundedCornerShape(10.dp),
                        color = palette.palePink,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(advice.solution, fontSize = 12.sp, color = palette.dark, modifier = Modifier.padding(10.dp))
                    }
                }
            },
            confirmButton = {
                val targetScreen = routeToScreen(advice.quickActionRoute)
                if (!advice.quickActionLabel.isNullOrBlank() && targetScreen != null) {
                    Button(
                        onClick = {
                            selectedLogForAdvice = null
                            onNavigate(targetScreen)
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = palette.dark, contentColor = palette.cream)
                    ) {
                        Text(advice.quickActionLabel)
                    }
                } else {
                    Button(
                        onClick = { selectedLogForAdvice = null },
                        colors = ButtonDefaults.buttonColors(containerColor = palette.dark, contentColor = palette.cream)
                    ) {
                        Text("Mengerti")
                    }
                }
            },
            dismissButton = {
                TextButton(onClick = { selectedLogForAdvice = null }) {
                    Text("Tutup", color = palette.rose)
                }
            },
            shape = RoundedCornerShape(20.dp),
            containerColor = palette.cardBg
        )
    }

    // Modal: Confirmation for Saving Config (preserving toggles & themeSettings)
    if (showSaveConfigConfirmDialog) {
        UniversalConfirmDialog(
            title = "Simpan Konfigurasi & Kredensial?",
            message = "Perubahan pada Client ID, Secret, dan Session Password akan disimpan secara persisten di database SQLite.",
            confirmText = "Simpan",
            onConfirm = {
                scope.launch {
                    val newConfig = configState.copy(
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
private fun FeatureToggleRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    palette: KruzenPalette,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(palette.cream.copy(alpha = 0.45f))
            .clickable { onCheckedChange(!checked) }
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(text = title, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = palette.dark)
            ThinHighlightText(text = subtitle, fontSize = 10.sp, color = palette.textMuted)
        }
        Spacer(modifier = Modifier.width(8.dp))
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange
        )
    }
}

@Composable
private fun HexColorPickerRow(
    label: String,
    hexValue: String,
    swatches: List<String>,
    palette: KruzenPalette,
    onValueChange: (String) -> Unit,
    onApply: (String) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(label, fontSize = 11.sp, fontWeight = FontWeight.Medium, color = palette.textMuted)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                swatches.forEach { hex ->
                    val swatchColor = parseHexColorOrNull(hex) ?: palette.dark
                    Box(
                        modifier = Modifier
                            .size(20.dp)
                            .clip(CircleShape)
                            .background(swatchColor)
                            .border(
                                width = if (hexValue.equals(hex, ignoreCase = true)) 2.dp else 1.dp,
                                color = if (hexValue.equals(hex, ignoreCase = true)) palette.accent else palette.border,
                                shape = CircleShape
                            )
                            .clickable { onApply(hex) }
                    )
                }
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            OutlinedTextField(
                value = hexValue,
                onValueChange = onValueChange,
                placeholder = { Text("#RRGGBB (kosong = bawaan)", fontSize = 11.sp) },
                singleLine = true,
                shape = RoundedCornerShape(10.dp),
                modifier = Modifier.weight(1f)
            )
            OutlinedButton(
                onClick = { onApply(hexValue) },
                shape = RoundedCornerShape(10.dp),
                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)
            ) {
                Text("Terapkan", fontSize = 11.sp, maxLines = 1, softWrap = false)
            }
        }
    }
}

@Composable
private fun DebugTabButton(
    label: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    isSelected: Boolean,
    palette: KruzenPalette,
    onClick: () -> Unit
) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(14.dp),
        color = if (isSelected) palette.dark else palette.cardBg,
        border = if (isSelected) null else androidx.compose.foundation.BorderStroke(1.dp, palette.border)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 9.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = if (isSelected) palette.accent else palette.rose,
                modifier = Modifier.size(13.dp)
            )
            Spacer(modifier = Modifier.width(4.dp))
            Text(
                text = label,
                fontSize = 10.sp,
                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                color = if (isSelected) palette.cream else palette.dark,
                maxLines = 1,
                softWrap = false
            )
        }
    }
}

@Composable
private fun InfoRow(label: String, value: String, palette: KruzenPalette) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(text = label, fontSize = 11.sp, color = palette.textMuted)
        Text(text = value, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = palette.dark)
    }
}
