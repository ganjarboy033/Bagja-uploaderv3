package com.example.xiyu.ui.components

import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay

enum class ToastType {
    SUCCESS, // Green
    ERROR,   // Red
    WARNING, // Yellow / Amber
    INFO     // Blue
}

data class ToastMessage(
    val id: Long = System.currentTimeMillis(),
    val message: String,
    val type: ToastType = ToastType.INFO,
    val isLoading: Boolean = false,
    val durationMs: Long = 3500
)

class ToastController {
    var currentToast by mutableStateOf<ToastMessage?>(null)
        private set

    fun showSuccess(message: String) {
        show(message, ToastType.SUCCESS)
    }

    fun showError(message: String) {
        show(message, ToastType.ERROR, durationMs = 5000)
    }

    fun showWarning(message: String) {
        show(message, ToastType.WARNING, durationMs = 4500)
    }

    fun showInfo(message: String, isLoading: Boolean = false) {
        show(message, ToastType.INFO, isLoading = isLoading)
    }

    fun show(
        message: String,
        type: ToastType = ToastType.INFO,
        isLoading: Boolean = false,
        durationMs: Long = 3500
    ) {
        currentToast = ToastMessage(
            message = message,
            type = type,
            isLoading = isLoading,
            durationMs = durationMs
        )
    }

    fun dismiss() {
        currentToast = null
    }
}

val LocalToastController = compositionLocalOf { ToastController() }

@Composable
fun ToastHost(
    controller: ToastController,
    modifier: Modifier = Modifier
) {
    val toast = controller.currentToast

    LaunchedEffect(toast) {
        if (toast != null && !toast.isLoading) {
            delay(toast.durationMs)
            if (controller.currentToast?.id == toast.id) {
                controller.dismiss()
            }
        }
    }

    AnimatedVisibility(
        visible = toast != null,
        enter = fadeIn() + slideInVertically(initialOffsetY = { -it }),
        exit = fadeOut() + slideOutVertically(targetOffsetY = { -it }),
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp)
    ) {
        if (toast != null) {
            val (bgColor, borderColor, textColor, icon) = when (toast.type) {
                ToastType.SUCCESS -> Quad(
                    Color(0xFF0F3822), // deep forest green
                    Color(0xFF22C55E), // green accent
                    Color(0xFFDCFCE7),
                    Icons.Default.CheckCircle
                )
                ToastType.ERROR -> Quad(
                    Color(0xFF3B1214), // deep maroon red
                    Color(0xFFEF4444), // red accent
                    Color(0xFFFEE2E2),
                    Icons.Default.Warning
                )
                ToastType.WARNING -> Quad(
                    Color(0xFF382A0F), // deep amber
                    Color(0xFFF59E0B), // amber accent
                    Color(0xFFFEF3C7),
                    Icons.Default.Warning
                )
                ToastType.INFO -> Quad(
                    Color(0xFF0F2642), // deep navy blue
                    Color(0xFF3B82F6), // blue accent
                    Color(0xFFDBEAFE),
                    Icons.Default.Info
                )
            }

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .shadow(8.dp, RoundedCornerShape(14.dp))
                    .clip(RoundedCornerShape(14.dp))
                    .background(bgColor)
                    .border(1.dp, borderColor.copy(alpha = 0.5f), RoundedCornerShape(14.dp))
                    .padding(horizontal = 14.dp, vertical = 12.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    if (toast.isLoading) {
                        CircularProgressIndicator(
                            color = borderColor,
                            strokeWidth = 2.5.dp,
                            modifier = Modifier.size(20.dp)
                        )
                    } else {
                        Icon(
                            imageVector = icon,
                            contentDescription = toast.type.name,
                            tint = borderColor,
                            modifier = Modifier.size(20.dp)
                        )
                    }

                    Text(
                        text = toast.message,
                        color = textColor,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium,
                        modifier = Modifier.weight(1f)
                    )

                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = "Dismiss",
                        tint = textColor.copy(alpha = 0.7f),
                        modifier = Modifier
                            .size(18.dp)
                            .clickable { controller.dismiss() }
                    )
                }
            }
        }
    }
}

private data class Quad<A, B, C, D>(val first: A, val second: B, val third: C, val fourth: D)
