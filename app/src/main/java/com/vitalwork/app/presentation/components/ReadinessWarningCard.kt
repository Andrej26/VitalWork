package com.vitalwork.app.presentation.components

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Warning
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.app.ActivityCompat
import com.vitalwork.app.data.system.SessionPrerequisite
import com.vitalwork.app.ui.theme.AlertRed
import com.vitalwork.app.ui.theme.AlertRedBorder
import com.vitalwork.app.ui.theme.AlertRedContainer
import com.vitalwork.app.ui.theme.AlertRedDeep
import com.vitalwork.app.ui.theme.BrandFontFamily

/**
 * Conditional warning card listing session prerequisites that are currently missing, each with a
 * one-tap **Fix**. Renders nothing when [missing] is empty, so it only appears when there's a
 * real problem (a silent revocation, or a never-granted permission). Hosted at the top of Home
 * and as a backup banner on the active-session screen.
 */
@Composable
fun ReadinessWarningCard(
    missing: Set<SessionPrerequisite>,
    onFix: (SessionPrerequisite) -> Unit,
    modifier: Modifier = Modifier
) {
    if (missing.isEmpty()) return

    // Stable display order regardless of set iteration order.
    val ordered = SessionPrerequisite.entries.filter { it in missing }

    // Outline-style alert card: white ground, red outline + icon bubble + eyebrow — red marks a
    // *blocking* state (sessions shouldn't start until fixed); advisory cards use amber instead.
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.4.dp, AlertRedBorder)
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(32.dp)
                        .clip(CircleShape)
                        .background(AlertRedContainer),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Outlined.Warning,
                        contentDescription = null,
                        tint = AlertRed,
                        modifier = Modifier.size(17.dp)
                    )
                }
                Spacer(modifier = Modifier.width(10.dp))
                Column {
                    Text(
                        text = "ACTION REQUIRED",
                        fontFamily = BrandFontFamily,
                        fontWeight = FontWeight.Bold,
                        fontSize = 9.sp,
                        letterSpacing = 0.9.sp,
                        color = AlertRed
                    )
                    Text(
                        text = "Setup needed before sessions",
                        style = MaterialTheme.typography.titleSmall,
                        color = AlertRedDeep
                    )
                }
            }

            ordered.forEach { prerequisite ->
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                // Whole row is one clickable target; the red pill is the visual affordance.
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onFix(prerequisite) }
                        .padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = labelFor(prerequisite),
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.weight(1f)
                    )
                    Spacer(modifier = Modifier.width(10.dp))
                    Surface(
                        shape = RoundedCornerShape(999.dp),
                        color = AlertRed,
                        contentColor = MaterialTheme.colorScheme.onError
                    ) {
                        Text(
                            text = "Fix",
                            fontFamily = BrandFontFamily,
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 11.sp,
                            modifier = Modifier.padding(horizontal = 13.dp, vertical = 4.dp)
                        )
                    }
                }
            }
        }
    }
}

private fun labelFor(prerequisite: SessionPrerequisite): String = when (prerequisite) {
    SessionPrerequisite.NOTIFICATIONS ->
        "Notifications off — the recording status won't show"
    SessionPrerequisite.BATTERY_OPTIMIZATION ->
        "Battery limits active — sessions may stop when locked"
    SessionPrerequisite.BLUETOOTH ->
        "Bluetooth permission needed for the pulse sensor"
    SessionPrerequisite.MICROPHONE ->
        "Microphone permission needed for respiration"
}

/**
 * Handles a permission-request *result* with the permanently-denied fallback. The host launches
 * the permission normally; when the result is "denied", it calls this. If the system will no
 * longer show the dialog (permanently denied — [ActivityCompat.shouldShowRequestPermissionRationale]
 * is false), we open the app's settings page so the operator can grant it manually, instead of
 * the Fix button silently doing nothing.
 *
 * Note: a permanently-denied `launch(...)` returns "denied" immediately without UI, so routing
 * the fallback through the result callback is reliable — no pre-guessing required.
 */
fun onPermissionDenied(context: Context, permission: String) {
    val activity = context.findActivity()
    val willShowDialogNextTime =
        activity != null && ActivityCompat.shouldShowRequestPermissionRationale(activity, permission)
    if (!willShowDialogNextTime) {
        openAppSettings(context)
    }
    // Otherwise the user simply declined a real dialog; leave the card to prompt again on next tap.
}

/** Opens this app's system settings details page (for permanently-denied permissions). */
fun openAppSettings(context: Context) {
    val intent = Intent(
        Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
        Uri.fromParts("package", context.packageName, null)
    ).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
    try {
        context.startActivity(intent)
    } catch (e: Exception) {
        // No settings activity available; nothing more we can do from here.
    }
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is android.content.ContextWrapper -> baseContext.findActivity()
    else -> null
}
