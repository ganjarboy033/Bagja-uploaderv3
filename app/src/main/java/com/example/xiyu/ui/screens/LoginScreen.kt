package com.example.xiyu.ui.screens

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.example.xiyu.data.model.AccountEntity
import com.example.xiyu.data.model.UserProfile
import com.example.xiyu.data.repository.GitHubRepository
import com.example.xiyu.ui.components.*
import com.example.xiyu.ui.navigation.Screen
import com.example.xiyu.ui.theme.*
import com.example.xiyu.util.OAuthHelper
import kotlinx.coroutines.launch

@Composable
fun LoginScreen(
    repository: GitHubRepository,
    currentUser: UserProfile?,
    onNavigate: (Screen) -> Unit,
    modifier: Modifier = Modifier
) {
    val palette = LocalKruzenPalette.current
    val context = LocalContext.current
    val scrollState = rememberScrollState()
    val scope = rememberCoroutineScope()
    val toastController = LocalToastController.current

    var tokenInput by remember { mutableStateOf("") }
    var isLoading by remember { mutableStateOf(false) }

    // Saved accounts from database
    val savedAccounts by repository.dbHelper.savedAccounts.collectAsState()
    var accountToRemove by remember { mutableStateOf<AccountEntity?>(null) }
    var accountToSwitch by remember { mutableStateOf<AccountEntity?>(null) }

    // OAuth configuration states
    var clientIdInput by remember { mutableStateOf(repository.dbHelper.configState.value.clientId) }
    var clientSecretInput by remember { mutableStateOf(repository.dbHelper.configState.value.clientSecret) }
    var sessionPasswordInput by remember { mutableStateOf(repository.dbHelper.configState.value.sessionPassword) }
    var isOAuthSettingsExpanded by remember { mutableStateOf(false) }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(palette.cream)
            .verticalScroll(scrollState)
            .padding(20.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // Back to home button
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(
                onClick = { onNavigate(Screen.Home) },
                modifier = Modifier.testTag("login_back_home_btn")
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "Back",
                    tint = BrandDark
                )
            }
            Text(
                text = "Kembali ke Beranda",
                fontSize = 12.sp,
                color = BrandTextMuted
            )
        }

        Spacer(modifier = Modifier.height(10.dp))

        // Main Login Card
        Surface(
            shape = RoundedCornerShape(26.dp),
            border = androidx.compose.foundation.BorderStroke(1.dp, palette.border),
            color = palette.cardBg,
            shadowElevation = 2.dp,
            modifier = Modifier
                .fillMaxWidth()
                .testTag("login_card")
        ) {
            Column(
                modifier = Modifier.padding(22.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // Emblem
                Box(
                    modifier = Modifier
                        .size(56.dp)
                        .clip(RoundedCornerShape(16.dp))
                        .background(BrandDark),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "⚡",
                        fontSize = 26.sp,
                        color = BrandCream
                    )
                }

                Spacer(modifier = Modifier.height(12.dp))

                Text(
                    text = "KRUZENLAB ACCOUNT MANAGER",
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    color = BrandRose,
                    letterSpacing = 1.8.sp
                )

                Spacer(modifier = Modifier.height(4.dp))

                Text(
                    text = "Akses Langsung GitHub",
                    fontFamily = FontFamily.SansSerif,
                    fontWeight = FontWeight.Bold,
                    fontSize = 22.sp,
                    textAlign = TextAlign.Center,
                    color = BrandDark
                )

                Spacer(modifier = Modifier.height(4.dp))

                ThinHighlightText(
                    text = "Otentikasi multi-akun, OAuth 2.0 Chrome Redirect, atau GitHub PAT.",
                    textAlign = TextAlign.Center
                )

                Spacer(modifier = Modifier.height(16.dp))

                // Active Account Banner
                if (currentUser != null) {
                    Surface(
                        shape = RoundedCornerShape(16.dp),
                        color = BrandPalePink,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(
                            modifier = Modifier.padding(14.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Text(
                                text = "Akun Aktif: @${currentUser.login}",
                                fontWeight = FontWeight.Bold,
                                fontSize = 13.sp,
                                color = BrandDark
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Button(
                                    onClick = { onNavigate(Screen.Dashboard) },
                                    colors = ButtonDefaults.buttonColors(containerColor = BrandDark),
                                    shape = RoundedCornerShape(20.dp),
                                    modifier = Modifier.testTag("open_dashboard_from_login")
                                ) {
                                    Text("Buka Dashboard", fontSize = 11.sp)
                                }
                                OutlinedButton(
                                    onClick = {
                                        repository.logout()
                                        toastController.showSuccess("Sesi akun diputuskan")
                                    },
                                    shape = RoundedCornerShape(20.dp)
                                ) {
                                    Text("Putuskan", fontSize = 11.sp, color = BrandRose)
                                }
                            }
                        }
                    }
                    Spacer(modifier = Modifier.height(16.dp))
                }

                // Saved Accounts Quick Switch Section
                if (savedAccounts.isNotEmpty()) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(BrandCream.copy(alpha = 0.6f), RoundedCornerShape(16.dp))
                            .padding(12.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                text = "Akun Tersimpan (${savedAccounts.size})",
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold,
                                color = BrandDark
                            )
                            ThinHighlightText("1-Klik switch")
                        }
                        Spacer(modifier = Modifier.height(8.dp))

                        savedAccounts.forEach { acc ->
                            val isCurrent = acc.login == currentUser?.login
                            Surface(
                                shape = RoundedCornerShape(10.dp),
                                color = if (isCurrent) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f) else MaterialTheme.colorScheme.surface,
                                border = androidx.compose.foundation.BorderStroke(0.5.dp, BrandBorder),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 3.dp)
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 10.dp, vertical = 6.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    if (!acc.avatarUrl.isNullOrBlank()) {
                                        AsyncImage(
                                            model = acc.avatarUrl,
                                            contentDescription = null,
                                            modifier = Modifier
                                                .size(24.dp)
                                                .clip(CircleShape)
                                        )
                                    } else {
                                        Icon(Icons.Default.AccountCircle, null, modifier = Modifier.size(24.dp), tint = BrandRose)
                                    }
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(text = "@${acc.login}", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                                        ThinHighlightText(text = if (acc.isOAuth) "OAuth login" else "PAT token")
                                    }

                                    if (!isCurrent) {
                                        Button(
                                            onClick = { accountToSwitch = acc },
                                            shape = RoundedCornerShape(8.dp),
                                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                                            modifier = Modifier.height(26.dp)
                                        ) {
                                            Text("Switch", fontSize = 10.sp)
                                        }
                                    } else {
                                        ThinHighlightBadge("Aktif")
                                    }

                                    Spacer(modifier = Modifier.width(4.dp))
                                    IconButton(
                                        onClick = { accountToRemove = acc },
                                        modifier = Modifier.size(26.dp)
                                    ) {
                                        Icon(Icons.Default.Close, null, modifier = Modifier.size(14.dp), tint = MaterialTheme.colorScheme.error)
                                    }
                                }
                            }
                        }
                    }
                    Spacer(modifier = Modifier.height(14.dp))
                }

                // PRIMARY BUTTON: Chrome GitHub Redirect Login
                Button(
                    onClick = {
                        val activeClientId = OAuthHelper.getClientId(context)
                        val launched = OAuthHelper.launchChromeOAuth(context, activeClientId)
                        if (launched) {
                            toastController.showInfo("Membuka otorisasi GitHub di Chrome...", isLoading = true)
                        } else {
                            toastController.showError("Gagal membuka Chrome.")
                        }
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = BrandDark,
                        contentColor = BrandCream
                    ),
                    shape = RoundedCornerShape(22.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(46.dp)
                        .testTag("chrome_github_login_btn")
                ) {
                    Icon(
                        imageVector = Icons.Default.Language,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                        tint = BrandAccent
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "Masuk via Chrome (GitHub Redirect)",
                        fontWeight = FontWeight.Bold,
                        fontSize = 12.sp
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.OpenInNew,
                        contentDescription = null,
                        modifier = Modifier.size(14.dp)
                    )
                }

                Spacer(modifier = Modifier.height(8.dp))

                // Fast Action: Open Chrome to create GitHub token with prefilled scopes
                OutlinedButton(
                    onClick = {
                        OAuthHelper.openChromeForTokenCreation(context)
                        toastController.showInfo("Membuka halaman token GitHub di Chrome...")
                    },
                    shape = RoundedCornerShape(22.dp),
                    border = androidx.compose.foundation.BorderStroke(1.dp, BrandBorder),
                    colors = ButtonDefaults.outlinedButtonColors(containerColor = BrandCardBg),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(40.dp)
                        .testTag("open_chrome_token_btn")
                ) {
                    Icon(
                        imageVector = Icons.Default.VpnKey,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                        tint = BrandRose
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "Buat Token GitHub di Chrome (1-Klik)",
                        color = BrandDark,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 11.sp
                    )
                }

                Spacer(modifier = Modifier.height(14.dp))

                // Divider with "ATAU"
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    HorizontalDivider(modifier = Modifier.weight(1f), color = BrandBorder)
                    Text(
                        text = "ATAU TEMPEL TOKEN (PAT)",
                        fontSize = 10.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = BrandTextMuted,
                        modifier = Modifier.padding(horizontal = 10.dp)
                    )
                    HorizontalDivider(modifier = Modifier.weight(1f), color = BrandBorder)
                }

                Spacer(modifier = Modifier.height(12.dp))

                // Token input
                OutlinedTextField(
                    value = tokenInput,
                    onValueChange = { tokenInput = it },
                    label = { Text("GitHub Token (PAT)", fontSize = 12.sp) },
                    placeholder = { Text("ghp_... atau github_pat_...", fontSize = 12.sp) },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    shape = RoundedCornerShape(14.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = palette.rose,
                        unfocusedBorderColor = palette.border,
                        focusedLabelColor = palette.rose
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("github_token_input")
                )

                Spacer(modifier = Modifier.height(6.dp))

                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = palette.palePink.copy(alpha = 0.7f),
                    border = androidx.compose.foundation.BorderStroke(0.5.dp, palette.border),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 7.dp),
                        verticalAlignment = Alignment.Top
                    ) {
                        Icon(
                            imageVector = Icons.Default.VerifiedUser,
                            contentDescription = null,
                            tint = palette.rose,
                            modifier = Modifier.size(14.dp).padding(top = 1.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        ThinHighlightText(
                            text = "Tips >1.000 File: Disarankan Classic PAT (ghp_..., No Expiration) dengan scope 'repo' & 'workflow' agar sesi tidak putus HTTP 401 di tengah push.",
                            fontSize = 10.sp,
                            color = palette.dark
                        )
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))

                Button(
                    onClick = {
                        if (tokenInput.isBlank()) {
                            toastController.showError("Masukkan token GitHub Anda!")
                            return@Button
                        }
                        isLoading = true
                        toastController.showInfo("Memverifikasi token...", isLoading = true)
                        scope.launch {
                            repository.loginWithToken(tokenInput).fold(
                                onSuccess = { profile ->
                                    isLoading = false
                                    toastController.showSuccess("Selamat datang @${profile.login}!")
                                    tokenInput = ""
                                    onNavigate(Screen.Dashboard)
                                },
                                onFailure = { error ->
                                    isLoading = false
                                    toastController.showError("Gagal verifikasi: ${error.localizedMessage}")
                                }
                            )
                        }
                    },
                    enabled = !isLoading && tokenInput.isNotBlank(),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = BrandDark,
                        contentColor = BrandCream
                    ),
                    shape = RoundedCornerShape(18.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(42.dp)
                        .testTag("connect_github_btn")
                ) {
                    if (isLoading) {
                        CircularProgressIndicator(color = BrandCream, modifier = Modifier.size(16.dp))
                    } else {
                        Text("Verifikasi & Simpan Akun", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    }
                }

                Spacer(modifier = Modifier.height(14.dp))

                // Expandable OAuth & Session Config with Fallback & Thin Highlight
                Surface(
                    shape = RoundedCornerShape(16.dp),
                    color = BrandCream.copy(alpha = 0.5f),
                    border = androidx.compose.foundation.BorderStroke(1.dp, BrandBorder),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { isOAuthSettingsExpanded = !isOAuthSettingsExpanded },
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Default.Tune, contentDescription = null, tint = BrandRose, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = "OAuth & Session Settings (Fallback Bawaan)",
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = BrandDark
                                )
                            }
                            Icon(
                                imageVector = if (isOAuthSettingsExpanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                                contentDescription = null,
                                tint = BrandRose,
                                modifier = Modifier.size(18.dp)
                            )
                        }

                        if (isOAuthSettingsExpanded) {
                            Spacer(modifier = Modifier.height(10.dp))

                            ThinHighlightText(
                                text = "Petunjuk: Jika kolom berikut dikosongkan, aplikasi otomatis menggunakan kredensial fallback default tanpa error."
                            )

                            Spacer(modifier = Modifier.height(6.dp))

                            OutlinedTextField(
                                value = clientIdInput,
                                onValueChange = { clientIdInput = it },
                                label = { Text("Client ID (Opsional)", fontSize = 11.sp) },
                                placeholder = { Text(OAuthHelper.FALLBACK_CLIENT_ID, fontSize = 11.sp) },
                                singleLine = false,
                                minLines = 1,
                                maxLines = 3,
                                shape = RoundedCornerShape(10.dp),
                                modifier = Modifier.fillMaxWidth()
                            )

                            Spacer(modifier = Modifier.height(6.dp))

                            OutlinedTextField(
                                value = clientSecretInput,
                                onValueChange = { clientSecretInput = it },
                                label = { Text("Client Secret (Opsional)", fontSize = 11.sp) },
                                placeholder = { Text(OAuthHelper.FALLBACK_CLIENT_SECRET, fontSize = 10.sp) },
                                singleLine = false,
                                minLines = 1,
                                maxLines = 3,
                                shape = RoundedCornerShape(10.dp),
                                modifier = Modifier.fillMaxWidth()
                            )

                            Spacer(modifier = Modifier.height(6.dp))

                            OutlinedTextField(
                                value = sessionPasswordInput,
                                onValueChange = { sessionPasswordInput = it },
                                label = { Text("Session Password (Min 32 Char)", fontSize = 11.sp) },
                                placeholder = { Text(OAuthHelper.FALLBACK_SESSION_PASSWORD, fontSize = 10.sp) },
                                singleLine = false,
                                minLines = 1,
                                maxLines = 3,
                                shape = RoundedCornerShape(10.dp),
                                modifier = Modifier.fillMaxWidth()
                            )

                            Spacer(modifier = Modifier.height(4.dp))

                            ThinHighlightText(
                                text = "Status Fallback: ${if (clientIdInput.isBlank()) "Default aktif (${OAuthHelper.FALLBACK_CLIENT_ID})" else "Kustom aktif"}"
                            )

                            Spacer(modifier = Modifier.height(8.dp))

                            Button(
                                onClick = {
                                    scope.launch {
                                        val cur = repository.dbHelper.configState.value
                                        repository.dbHelper.saveConfig(
                                            cur.copy(
                                                clientId = clientIdInput.trim(),
                                                clientSecret = clientSecretInput.trim(),
                                                sessionPassword = sessionPasswordInput.trim().ifEmpty { OAuthHelper.FALLBACK_SESSION_PASSWORD }
                                            )
                                        )
                                        OAuthHelper.saveOAuthCredentials(
                                            context,
                                            clientIdInput,
                                            clientSecretInput,
                                            sessionPasswordInput.ifEmpty { OAuthHelper.FALLBACK_SESSION_PASSWORD }
                                        )
                                        toastController.showSuccess("Konfigurasi OAuth & Sesi tersimpan!")
                                    }
                                },
                                colors = ButtonDefaults.buttonColors(containerColor = BrandDark),
                                shape = RoundedCornerShape(14.dp),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text("Simpan Konfigurasi Sesi", fontSize = 11.sp)
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(10.dp))

                // Demo Workspace Mode Button
                OutlinedButton(
                    onClick = {
                        repository.enableDemoMode()
                        toastController.showSuccess("Masuk ke Mode Demo Workspace KruzenLab")
                        onNavigate(Screen.Dashboard)
                    },
                    shape = RoundedCornerShape(18.dp),
                    border = androidx.compose.foundation.BorderStroke(1.dp, BrandBorder),
                    colors = ButtonDefaults.outlinedButtonColors(containerColor = BrandPalePink),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(38.dp)
                        .testTag("demo_mode_btn")
                ) {
                    Text(
                        text = "⚡ Masuk Mode Demo Workspace (Tanpa Akun)",
                        color = BrandDark,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 11.sp
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Attribution Footer
        AttributionFooter()
    }

    // Modal: Confirmation for Switching Account
    if (accountToSwitch != null) {
        val target = accountToSwitch!!
        UniversalConfirmDialog(
            title = "Beralih Akun",
            message = "Apakah Anda ingin mengganti sesi aktif ke @${target.login}?",
            confirmText = "Beralih",
            onConfirm = {
                scope.launch {
                    toastController.showInfo("Beralih ke @${target.login}...", isLoading = true)
                    repository.switchAccount(target).fold(
                        onSuccess = {
                            toastController.showSuccess("Beralih ke @${it.login}")
                            onNavigate(Screen.Dashboard)
                        },
                        onFailure = { err ->
                            toastController.showError("Gagal beralih: ${err.localizedMessage}")
                        }
                    )
                }
            },
            onDismiss = { accountToSwitch = null }
        )
    }

    // Modal: Confirmation for Removing Account
    if (accountToRemove != null) {
        val target = accountToRemove!!
        UniversalConfirmDialog(
            title = "Hapus Akun Tersimpan",
            message = "Hapus token akun @${target.login} dari penyimpanan lokal perangkat?",
            confirmText = "Hapus",
            isDestructive = true,
            onConfirm = {
                scope.launch {
                    repository.removeAccount(target.login)
                    toastController.showSuccess("Akun @${target.login} dihapus dari daftar tersimpan")
                }
            },
            onDismiss = { accountToRemove = null }
        )
    }
}
