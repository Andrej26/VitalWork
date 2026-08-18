package com.vitalwork.app.presentation.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vitalwork.app.R
import com.vitalwork.app.ui.theme.BrandDeepTeal
import com.vitalwork.app.ui.theme.BrandFontFamily

/**
 * The VitalWork brand lockup: the wordmark with the gear logo standing in for the "o" of "Work"
 * ("VitalW⚙rk"). The gear scales with [fontSize] so the lockup can be reused at any size.
 */
@Composable
fun VitalWorkLockup(
    modifier: Modifier = Modifier,
    fontSize: TextUnit = 28.sp
) {
    val gearSize = with(LocalDensity.current) { (fontSize * 0.82f).toDp() }
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = "VitalW",
            fontFamily = BrandFontFamily,
            fontWeight = FontWeight.SemiBold,
            fontSize = fontSize,
            letterSpacing = 0.sp,
            color = BrandDeepTeal
        )
        Image(
            painter = painterResource(R.drawable.logo_mark),
            contentDescription = null,
            modifier = Modifier
                .padding(horizontal = 1.dp)
                .size(gearSize)
                // Sit the gear on the text baseline like an "o", not the cap height.
                .offset(y = 1.5.dp)
        )
        Text(
            text = "rk",
            fontFamily = BrandFontFamily,
            fontWeight = FontWeight.SemiBold,
            fontSize = fontSize,
            letterSpacing = 0.sp,
            color = BrandDeepTeal
        )
    }
}
