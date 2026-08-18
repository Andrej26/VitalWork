package com.vitalwork.app.presentation.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import com.vitalwork.app.R

/**
 * Subtle full-screen brand watermark: the VitalWork logo mark (gear + ECG pulse) drawn
 * at very low alpha behind screen content. Place as the first child of a screen-level [Box]
 * so the real content stacks above it.
 */
@Composable
fun BoxScope.LogoWatermark(
    modifier: Modifier = Modifier,
    alpha: Float = 0.05f
) {
    Image(
        painter = painterResource(R.drawable.logo_mark),
        contentDescription = null,
        alpha = alpha,
        modifier = modifier
            .align(Alignment.BottomEnd)
            .size(420.dp)
            // Bleed off the corner so only part of the mark shows — decorative, not competing
            // with content.
            .offset(x = 110.dp, y = 120.dp)
    )
}

/** Convenience wrapper: white/background-colored screen body with the watermark behind [content]. */
@Composable
fun WatermarkedBackground(
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit
) {
    Box(modifier = modifier.fillMaxSize()) {
        LogoWatermark()
        content()
    }
}
