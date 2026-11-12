package com.example.xiyu.ui.components

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.xiyu.ui.theme.BrandBorder
import com.example.xiyu.ui.theme.BrandDark
import com.example.xiyu.ui.theme.BrandRose

@Composable
fun AttributionFooter(
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val attributionUrl = "https://github.com/AiraeXiyu/XIYU-GITHUB-UPLOADER"

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 16.dp, horizontal = 20.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        HorizontalDivider(color = BrandBorder.copy(alpha = 0.6f), thickness = 0.75.dp)
        Spacer(modifier = Modifier.height(12.dp))
        Text(
            text = "kruzenlab-uploader",
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold,
            color = BrandDark
        )
        Text(
            text = "Developed by DEV BAGJA / KruzenLab",
            fontSize = 10.sp,
            fontWeight = FontWeight.Medium,
            color = BrandRose
        )
        Spacer(modifier = Modifier.height(6.dp))
        Text(
            text = "Based on open-source architecture by AiraeXiyu (https://github.com/AiraeXiyu/XIYU-GITHUB-UPLOADER)",
            fontSize = 10.sp,
            fontWeight = FontWeight.Light,
            color = BrandDark.copy(alpha = 0.7f),
            textAlign = TextAlign.Center,
            modifier = Modifier.clickable {
                try {
                    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(attributionUrl)).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    context.startActivity(intent)
                } catch (_: Exception) {}
            }
        )
    }
}
