package com.phosphor.player.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** OSD text in phosphor green, like the on-screen display of a 2000s set. */
@Composable
fun OsdText(
    text: String,
    modifier: Modifier = Modifier,
    color: Color = Crt.OsdGreen,
    size: Int = 13,
) {
    Text(
        text = text,
        modifier = modifier,
        color = color,
        fontFamily = Crt.Mono,
        fontWeight = FontWeight.Bold,
        fontSize = size.sp,
        letterSpacing = 1.sp,
    )
}

/** A chunky raised button, like a key on the TV's front panel or remote. */
@Composable
fun PanelButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    accent: Boolean = false,
    enabled: Boolean = true,
) {
    val top = if (accent) Color(0xFF1F6B3A) else Crt.ButtonTop
    val bottom = if (accent) Color(0xFF134A27) else Crt.ButtonFace
    Box(
        modifier = modifier
            .shadow(4.dp, RoundedCornerShape(10.dp))
            .clip(RoundedCornerShape(10.dp))
            .background(Brush.verticalGradient(listOf(top, bottom)))
            .border(1.dp, Crt.BezelEdge, RoundedCornerShape(10.dp))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 12.dp)
            .sizeIn(minWidth = 52.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            color = if (accent) Crt.OsdGreen else Crt.TextMain,
            fontFamily = Crt.Mono,
            fontWeight = FontWeight.Bold,
            fontSize = 15.sp,
        )
    }
}

/** Small caption label used above control groups. */
@Composable
fun SectionLabel(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        modifier = modifier,
        color = Crt.TextDim,
        fontFamily = Crt.Mono,
        fontSize = 11.sp,
        letterSpacing = 2.sp,
    )
}
