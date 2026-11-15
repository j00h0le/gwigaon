package com.coral.gwigaon.sos.ui

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.core.net.toUri
import com.coral.gwigaon.ui.components.DayNightPreview
import com.coral.gwigaon.ui.components.GwigaonIcons
import com.coral.gwigaon.ui.theme.Gwigaon
import com.coral.gwigaon.ui.theme.GwigaonRadius
import com.coral.gwigaon.ui.theme.GwigaonSize
import com.coral.gwigaon.ui.theme.GwigaonSpace
import com.coral.gwigaon.ui.theme.GwigaonText
import com.coral.gwigaon.ui.theme.GwigaonTheme
import com.coral.gwigaon.ui.theme.palette

/**
 * SOS sent (SPEC sos, `design/d2/proto/driving.html`): the whole panel turns red-ground, everything centred. A badge
 * in two faint rings, "SOS를 보냈어요" and when, what the app does about it, then 112 (opens the dialer with the
 * number; the app never calls by itself) above a quiet "잘못 눌렀어요", which logs `SOS_CANCELLED`; the SOS itself
 * stays in the log and on the 보호자's timeline. Usable while driving: no typing, text ≥ 32, 100 dp buttons.
 */
@Composable
fun SosScreen(
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
    /** "01:13", when the SOS fired; null hides the line. */
    sentAt: String? = null,
    /** The mic was on: the SOS keeps the last minutes as its clip. */
    listening: Boolean = false,
    micIndicator: (@Composable () -> Unit)? = null,
) {
    val p = Gwigaon.palette
    val context = LocalContext.current
    Column(
        modifier
            .fillMaxSize()
            .drawBehind {
                // The prototype's glow: radial, centred a fifth of the way down, fading into the ground by 62 %.
                drawRect(
                    Brush.radialGradient(
                        0f to p.sosSentGlow, GLOW_END to p.sosSentGround,
                        center = Offset(size.width / 2, size.height * GLOW_Y),
                        radius = size.maxDimension,
                    ),
                )
            }
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .padding(horizontal = GwigaonSpace.panelSide, vertical = GwigaonSpace.panelSide)
            .testTag("sos_screen"),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        if (micIndicator != null) Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterEnd) { micIndicator() }
        Column(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Assertive },
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Beacon()
            Text(
                "SOS를 보냈어요",
                style = GwigaonText.headline,
                color = p.textPrimary,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = GwigaonSpace.lg),
            )
            if (sentAt != null) {
                Text(sentAt, style = GwigaonText.detail, color = p.sosSentDetail, modifier = Modifier.padding(top = GwigaonSpace.sm))
            }
            Column(
                Modifier.padding(top = GwigaonSpace.lg),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(GwigaonSpace.sm),
            ) {
                Done("비상 연락처에게 SOS와 위치를 보냈어요")
                if (listening) Done("대화를 저장했어요")
            }
        }
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(GwigaonSpace.sm)) {
            SentButton("112 긴급 통화", GwigaonIcons.Phone, p.primary, p.onPrimary, Modifier.testTag("sos_guidance")) {
                dial(context, "112")
            }
            SentButton("잘못 눌렀어요", null, p.sosSentQuiet, p.textSecondary, Modifier.testTag("sos_cancel"), onCancel)
        }
    }
}

private const val GLOW_Y = 0.22f
private const val GLOW_END = 0.62f

/** The 132 red badge inside two faint rings. */
@Composable
private fun Beacon() {
    val p = Gwigaon.palette
    Box(Modifier.size(GwigaonSize.sosBeacon), contentAlignment = Alignment.Center) {
        Box(Modifier.fillMaxSize().border(GwigaonSize.ringLine, p.sos.copy(alpha = 0.28f), CircleShape))
        Box(Modifier.size(GwigaonSize.sosBeaconInner).border(GwigaonSize.ringLine, p.sos.copy(alpha = 0.45f), CircleShape))
        Box(Modifier.size(GwigaonSize.sosBadge).background(p.sos, CircleShape), contentAlignment = Alignment.Center) {
            Icon(GwigaonIcons.Alert, contentDescription = null, tint = p.onSos, modifier = Modifier.size(GwigaonSize.sosBadgeIcon))
        }
    }
}

@Composable
private fun Done(text: String) {
    val p = Gwigaon.palette
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(GwigaonSpace.sm)) {
        Icon(GwigaonIcons.Done, contentDescription = null, tint = p.sosSentCheck, modifier = Modifier.size(GwigaonSize.sosSentIcon))
        Text(text, style = GwigaonText.body, color = p.textPrimary)
    }
}

@Composable
private fun SentButton(
    text: String,
    icon: ImageVector?,
    color: Color,
    content: Color,
    modifier: Modifier,
    onClick: () -> Unit,
) {
    Surface(
        onClick = onClick,
        modifier = modifier.fillMaxWidth().heightIn(min = GwigaonSize.sosSentButton).semantics { role = Role.Button },
        shape = RoundedCornerShape(GwigaonRadius.card),
        color = color,
        contentColor = content,
    ) {
        Row(horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
            if (icon != null) Icon(icon, contentDescription = null, modifier = Modifier.size(GwigaonSize.sosSentIcon))
            Text(text, style = GwigaonText.buttonSecondary, modifier = Modifier.padding(start = if (icon != null) GwigaonSpace.sm else GwigaonSize.none))
        }
    }
}

/** Opens the phone app with [number] filled in; the person presses call themselves (SPEC: the app never dials). */
private fun dial(context: Context, number: String) {
    try {
        context.startActivity(Intent(Intent.ACTION_DIAL, "tel:$number".toUri()).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    } catch (e: ActivityNotFoundException) {
        Log.w("SosScreen", "no phone app for $number", e)
    }
}

@DayNightPreview
@Composable
internal fun SosScreenPreview() = GwigaonTheme { SosScreen(onCancel = {}, sentAt = "01:13", listening = true) }
