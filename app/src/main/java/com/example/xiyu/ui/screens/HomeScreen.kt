package com.example.xiyu.ui.screens

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.xiyu.R
import com.example.xiyu.data.model.UserProfile
import com.example.xiyu.ui.components.AttributionFooter
import com.example.xiyu.ui.components.ThinHighlightBadge
import com.example.xiyu.ui.components.ThinHighlightText
import com.example.xiyu.ui.navigation.Screen
import com.example.xiyu.ui.theme.*

@Composable
fun HomeScreen(
    currentUser: UserProfile?,
    onNavigate: (Screen) -> Unit,
    modifier: Modifier = Modifier
) {
    val scrollState = rememberScrollState()
    val palette = LocalKruzenPalette.current

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(palette.cream)
            .verticalScroll(scrollState)
            .padding(bottom = 32.dp)
            .testTag("home_screen")
    ) {
        // Hero Section
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 16.dp)
        ) {
            // Little sparkle chip
            Surface(
                shape = RoundedCornerShape(20.dp),
                border = androidx.compose.foundation.BorderStroke(1.dp, palette.border),
                color = palette.cardBg.copy(alpha = 0.9f),
                modifier = Modifier.padding(bottom = 16.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Bolt,
                        contentDescription = null,
                        tint = palette.accent,
                        modifier = Modifier.size(14.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = "kruzenlab-uploader · Cloud Git Engine ⚡",
                        fontSize = 12.sp,
                        color = palette.rose,
                        fontWeight = FontWeight.Medium
                    )
                }
            }

            // Headline
            Text(
                text = "Cloud Git Manager.",
                fontFamily = FontFamily.SansSerif,
                fontWeight = FontWeight.Bold,
                fontSize = 32.sp,
                lineHeight = 38.sp,
                color = palette.dark
            )
            Text(
                text = "Atomic Batch Uploader.",
                fontFamily = FontFamily.SansSerif,
                fontWeight = FontWeight.Bold,
                fontSize = 32.sp,
                lineHeight = 38.sp,
                color = palette.rose
            )

            Spacer(modifier = Modifier.height(12.dp))

            Text(
                text = "Kelola repositori GitHub, unggah ZIP/file batch dengan commit atomic tunggal, edit kode di cloud, dan monitor CI/CD workflows dalam satu genggaman.",
                fontSize = 13.sp,
                lineHeight = 20.sp,
                color = palette.textMuted
            )

            Spacer(modifier = Modifier.height(18.dp))

            // Action buttons
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Button(
                    onClick = { onNavigate(Screen.Upload) },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = palette.dark,
                        contentColor = palette.cream
                    ),
                    shape = RoundedCornerShape(24.dp),
                    contentPadding = PaddingValues(horizontal = 18.dp, vertical = 12.dp),
                    modifier = Modifier.testTag("start_uploading_btn")
                ) {
                    Icon(
                        imageVector = Icons.Default.CloudUpload,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "Batch Uploader",
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 13.sp
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowForward,
                        contentDescription = null,
                        modifier = Modifier.size(14.dp)
                    )
                }

                OutlinedButton(
                    onClick = { onNavigate(Screen.RepoExplorer) },
                    border = androidx.compose.foundation.BorderStroke(1.dp, palette.border),
                    colors = ButtonDefaults.outlinedButtonColors(
                        containerColor = palette.palePink,
                        contentColor = palette.dark
                    ),
                    shape = RoundedCornerShape(24.dp),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
                    modifier = Modifier.testTag("explorer_btn")
                ) {
                    Icon(
                        imageVector = Icons.Default.Folder,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = "Files CRUD",
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 13.sp
                    )
                }
            }

            Spacer(modifier = Modifier.height(18.dp))

            // Badges
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                ThinHighlightBadge("OAuth & Session Fallback")
                ThinHighlightBadge("Git Data API v3")
                ThinHighlightBadge("SQLite Backend DB")
            }

            Spacer(modifier = Modifier.height(20.dp))

            // Hero Banner Card
            Card(
                shape = RoundedCornerShape(24.dp),
                border = androidx.compose.foundation.BorderStroke(2.dp, palette.cardBg),
                elevation = CardDefaults.cardElevation(defaultElevation = 4.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(280.dp)
                    .testTag("hero_photo_card")
            ) {
                Box(modifier = Modifier.fillMaxSize()) {
                    Image(
                        painter = painterResource(id = R.drawable.xiyu_photo),
                        contentDescription = "kruzenlab banner",
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize()
                    )

                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(
                                androidx.compose.ui.graphics.Brush.verticalGradient(
                                    colors = listOf(
                                        androidx.compose.ui.graphics.Color.Transparent,
                                        BrandDark.copy(alpha = 0.85f)
                                    ),
                                    startY = 200f
                                )
                            )
                    )

                    Surface(
                        shape = RoundedCornerShape(14.dp),
                        color = palette.cardBg.copy(alpha = 0.95f),
                        border = androidx.compose.foundation.BorderStroke(1.dp, palette.border),
                        modifier = Modifier
                            .padding(14.dp)
                            .align(Alignment.TopStart)
                    ) {
                        Text(
                            text = "kruzenlab-uploader ⚡",
                            fontSize = 11.sp,
                            color = palette.dark,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp)
                        )
                    }

                    Column(
                        modifier = Modifier
                            .align(Alignment.BottomStart)
                            .padding(18.dp)
                    ) {
                        Text(
                            text = "Atomic Single-Tree Commit",
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Bold,
                            color = BrandCream
                        )
                        Spacer(modifier = Modifier.height(2.dp))
                        ThinHighlightText(
                            text = "POST blob → POST tree → POST commit → PATCH ref (0-commit auto-init)",
                            color = BrandCream.copy(alpha = 0.85f)
                        )
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(10.dp))

        // Feature cards
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            FeatureCard(
                icon = Icons.Default.CloudUpload,
                title = "Atomic Batch ZIP Uploader",
                description = "Ekstraksi ZIP langsung di Android, filter folder sampah, lalu push puluhan file dalam 1 commit tunggal.",
                testTag = "feature_card_zip",
                palette = palette
            )
            FeatureCard(
                icon = Icons.Default.Folder,
                title = "Cloud File Manager & Code Editor",
                description = "Buat file/folder (.gitkeep), rename secara rekursif, hapus file batch, dan edit kode langsung di remote GitHub.",
                testTag = "feature_card_repos",
                palette = palette
            )
            FeatureCard(
                icon = Icons.Default.PlayCircle,
                title = "Actions & Release Monitor",
                description = "Pantau status GitHub Actions workflow runs, trigger re-run, dan buat GitHub Release dengan tag.",
                testTag = "feature_card_actions",
                palette = palette
            )
            FeatureCard(
                icon = Icons.Default.Tune,
                title = "OAuth & Session Customizer",
                description = "Atur Client ID, Secret, dan Session Password secara mandiri dengan automatic fallback dan thin highlight styling.",
                testTag = "feature_card_auth",
                palette = palette
            )
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Attribution Footer
        AttributionFooter()
    }
}

@Composable
private fun FeatureCard(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    description: String,
    testTag: String,
    palette: KruzenPalette = LocalKruzenPalette.current
) {
    Surface(
        shape = RoundedCornerShape(18.dp),
        color = palette.cardBg,
        border = androidx.compose.foundation.BorderStroke(1.dp, palette.border),
        shadowElevation = 1.dp,
        modifier = Modifier
            .fillMaxWidth()
            .testTag(testTag)
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(palette.palePink),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = palette.rose,
                    modifier = Modifier.size(20.dp)
                )
            }
            Spacer(modifier = Modifier.width(12.dp))
            Column {
                Text(
                    text = title,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = palette.dark
                )
                Spacer(modifier = Modifier.height(2.dp))
                ThinHighlightText(
                    text = description,
                    fontSize = 11.sp
                )
            }
        }
    }
}
