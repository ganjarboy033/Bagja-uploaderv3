package com.example.xiyu.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Reusable Thin Highlight Text component.
 * Implements the required subtle styling: font-light, text-xs, muted accent with ~70-75% opacity.
 */
@Composable
fun ThinHighlightText(
    text: String,
    modifier: Modifier = Modifier,
    fontSize: TextUnit = 11.sp,
    color: Color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.72f),
    textAlign: TextAlign = TextAlign.Start,
    maxLines: Int = Int.MAX_VALUE
) {
    Text(
        text = text,
        modifier = modifier,
        fontSize = fontSize,
        fontWeight = FontWeight.Light,
        color = color,
        textAlign = textAlign,
        lineHeight = 15.sp,
        maxLines = maxLines
    )
}

/**
 * Badge with thin border and subtle highlight background for technical / fallback status indicators.
 */
@Composable
fun ThinHighlightBadge(
    text: String,
    modifier: Modifier = Modifier,
    badgeColor: Color = MaterialTheme.colorScheme.primary
) {
    Text(
        text = text,
        modifier = modifier
            .clip(RoundedCornerShape(6.dp))
            .background(badgeColor.copy(alpha = 0.12f))
            .border(0.75.dp, badgeColor.copy(alpha = 0.35f), RoundedCornerShape(6.dp))
            .padding(horizontal = 6.dp, vertical = 2.dp),
        fontSize = 10.sp,
        fontWeight = FontWeight.Light,
        color = badgeColor.copy(alpha = 0.90f),
        letterSpacing = 0.5.sp
    )
}
