package com.example.xiyu.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.example.xiyu.data.model.RateLimitInfo
import com.example.xiyu.data.model.UserProfile
import com.example.xiyu.ui.navigation.Screen
import com.example.xiyu.ui.theme.*

@Composable
fun Topbar(
    currentUser: UserProfile?,
    rateLimit: RateLimitInfo?,
    currentScreen: String,
    onNavigate: (Screen) -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .testTag("app_topbar"),
        color = BrandCream.copy(alpha = 0.98f),
        tonalElevation = 2.dp
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                // Logo & brand title (kruzenlab-uploader by DEV BAGJA)
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .clickable { onNavigate(Screen.Home) }
                        .testTag("brand_logo")
                ) {
                    Box(
                        modifier = Modifier
                            .size(34.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(BrandDark),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "⚡",
                            color = BrandCream,
                            fontSize = 16.sp
                        )
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    Column {
                        Text(
                            text = "kruzenlab-uploader",
                            fontFamily = FontFamily.SansSerif,
                            fontWeight = FontWeight.Bold,
                            fontSize = 15.sp,
                            color = BrandDark
                        )
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            ThinHighlightText(
                                text = "DEV BAGJA · KruzenLab",
                                fontSize = 10.sp,
                                color = BrandRose
                            )
                        }
                    }
                }

                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    // Rate Limit badge if available
                    if (rateLimit != null) {
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(8.dp))
                                .background(BrandCardBg)
                                .border(0.5.dp, BrandBorder, RoundedCornerShape(8.dp))
                                .padding(horizontal = 6.dp, vertical = 3.dp)
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    text = "API: ",
                                    fontSize = 9.sp,
                                    fontWeight = FontWeight.Light,
                                    color = BrandRose
                                )
                                Text(
                                    text = "${rateLimit.remaining}/${rateLimit.limit}",
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = if (rateLimit.remaining < 500) MaterialTheme.colorScheme.error else BrandDark
                                )
                            }
                        }
                    }

                    // Debug & Settings quick button
                    IconButton(
                        onClick = { onNavigate(Screen.DebugPanel) },
                        modifier = Modifier
                            .size(32.dp)
                            .testTag("topbar_debug_btn")
                    ) {
                        Icon(
                            imageVector = Icons.Default.Tune,
                            contentDescription = "Debug Panel",
                            tint = if (currentScreen == Screen.DebugPanel.route) BrandDark else BrandRose,
                            modifier = Modifier.size(18.dp)
                        )
                    }

                    // User account badge
                    if (currentUser != null) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .clip(RoundedCornerShape(16.dp))
                                .border(1.dp, BrandBorder, RoundedCornerShape(16.dp))
                                .background(BrandCardBg)
                                .clickable { onNavigate(Screen.Login) }
                                .padding(horizontal = 8.dp, vertical = 4.dp)
                                .testTag("user_profile_chip")
                        ) {
                            if (!currentUser.avatarUrl.isNullOrBlank()) {
                                AsyncImage(
                                    model = currentUser.avatarUrl,
                                    contentDescription = "Avatar",
                                    modifier = Modifier
                                        .size(20.dp)
                                        .clip(CircleShape)
                                )
                            } else {
                                Icon(
                                    imageVector = Icons.Default.AccountCircle,
                                    contentDescription = "User",
                                    tint = BrandRose,
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                text = "@${currentUser.login}",
                                fontSize = 11.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = BrandDark,
                                maxLines = 1
                            )
                        }
                    } else {
                        OutlinedButton(
                            onClick = { onNavigate(Screen.Login) },
                            shape = RoundedCornerShape(16.dp),
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                            modifier = Modifier.testTag("sign_in_nav_btn")
                        ) {
                            Text(
                                text = "Login",
                                fontSize = 11.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = BrandDark
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(6.dp))

            // Navigation tabs with horizontal scrolling for mobile friendliness
            val scrollState = rememberScrollState()
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(scrollState),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                NavTab(
                    icon = Icons.Default.Home,
                    label = "Home",
                    isSelected = currentScreen == Screen.Home.route,
                    onClick = { onNavigate(Screen.Home) },
                    testTag = "nav_home"
                )
                NavTab(
                    icon = Icons.Default.SpaceDashboard,
                    label = "Dashboard",
                    isSelected = currentScreen == Screen.Dashboard.route,
                    onClick = { onNavigate(Screen.Dashboard) },
                    testTag = "nav_dashboard"
                )
                NavTab(
                    icon = Icons.Default.Folder,
                    label = "Files",
                    isSelected = currentScreen == Screen.RepoExplorer.route,
                    onClick = { onNavigate(Screen.RepoExplorer) },
                    testTag = "nav_explorer"
                )
                NavTab(
                    icon = Icons.Default.CloudUpload,
                    label = "Uploader",
                    isSelected = currentScreen == Screen.Upload.route,
                    onClick = { onNavigate(Screen.Upload) },
                    testTag = "nav_uploader"
                )
                NavTab(
                    icon = Icons.Default.PlayCircle,
                    label = "Workflows",
                    isSelected = currentScreen == Screen.WorkflowRelease.route,
                    onClick = { onNavigate(Screen.WorkflowRelease) },
                    testTag = "nav_workflows"
                )
                NavTab(
                    icon = Icons.Default.Tune,
                    label = "Settings",
                    isSelected = currentScreen == Screen.DebugPanel.route,
                    onClick = { onNavigate(Screen.DebugPanel) },
                    testTag = "nav_debug"
                )
            }
        }
    }
}

@Composable
private fun NavTab(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    isSelected: Boolean,
    onClick: () -> Unit,
    testTag: String,
    modifier: Modifier = Modifier
) {
    val bgColor = if (isSelected) BrandDark else BrandCardBg.copy(alpha = 0.8f)
    val contentColor = if (isSelected) BrandCream else BrandDark
    val borderStroke = if (isSelected) null else 1.dp

    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(14.dp),
        color = bgColor,
        border = if (borderStroke != null) androidx.compose.foundation.BorderStroke(borderStroke, BrandBorder) else null,
        modifier = modifier
            .height(32.dp)
            .testTag(testTag)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center,
            modifier = Modifier.padding(horizontal = 10.dp)
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = if (isSelected) BrandAccent else BrandRose,
                modifier = Modifier.size(14.dp)
            )
            Spacer(modifier = Modifier.width(4.dp))
            Text(
                text = label,
                fontSize = 11.sp,
                fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Medium,
                color = contentColor,
                maxLines = 1
            )
        }
    }
}
