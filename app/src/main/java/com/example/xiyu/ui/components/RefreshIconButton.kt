package com.example.xiyu.ui.components

import androidx.compose.animation.core.*
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.example.xiyu.ui.theme.BrandDark
import com.example.xiyu.ui.theme.BrandRose

@Composable
fun RefreshIconButton(
    isRefreshing: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    contentDescription: String = "Refresh",
    tint: Color = BrandDark,
    iconSize: Dp = 20.dp
) {
    val infiniteTransition = rememberInfiniteTransition(label = "refresh_spin")
    val angle by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 850, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "refresh_angle"
    )

    IconButton(
        onClick = { if (!isRefreshing) onClick() },
        modifier = modifier.minimumInteractiveComponentSize()
    ) {
        Icon(
            imageVector = Icons.Default.Refresh,
            contentDescription = contentDescription,
            tint = if (isRefreshing) BrandRose else tint,
            modifier = Modifier
                .size(iconSize)
                .rotate(if (isRefreshing) angle else 0f)
        )
    }
}
