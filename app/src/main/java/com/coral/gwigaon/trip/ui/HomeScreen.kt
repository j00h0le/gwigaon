package com.coral.gwigaon.trip.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.tooling.preview.Preview
import com.coral.gwigaon.trip.HomeKind
import com.coral.gwigaon.trip.HomeRule
import com.coral.gwigaon.trip.HomeTexts
import com.coral.gwigaon.trip.Settings
import com.coral.gwigaon.trip.SettingsField
import com.coral.gwigaon.trip.SettingsTexts
import com.coral.gwigaon.ui.components.DayNightPreview
import com.coral.gwigaon.ui.components.GwigaonIcons
import com.coral.gwigaon.ui.components.IconTile
import com.coral.gwigaon.ui.theme.Gwigaon
import com.coral.gwigaon.ui.theme.GwigaonRadius
import com.coral.gwigaon.ui.theme.GwigaonSize
import com.coral.gwigaon.ui.theme.GwigaonSpace
import com.coral.gwigaon.ui.theme.GwigaonText
import com.coral.gwigaon.ui.theme.GwigaonTheme
import com.coral.gwigaon.ui.theme.palette
import com.coral.gwigaon.vehicle.Destination

/**
 * Home (`design/d2/proto/home.html`), parked only with no trip: two doors, 설정 for the car owner and 운행 시작 for the
 * driver. Until 설정 is complete, 설정 is the primary door and 운행 시작 looks unavailable (it still opens the start
 * screen, which tells the driver to go without 귀가온).
 */
@Composable
fun HomeScreen(
    settings: Settings,
    onSettings: () -> Unit,
    onStart: () -> Unit,
    modifier: Modifier = Modifier,
    /** 운행 기록: the last trip's report (ADR 0017 amendment); null when there is no past trip. */
    onHistory: (() -> Unit)? = null,
    /** QR 확인하기: the account QR (ADR 0019), once 설정 is done; null in builds without the link. */
    onQr: (() -> Unit)? = null,
) {
    val p = Gwigaon.palette
    val kind = HomeRule.kind(settings)
    BoxWithConstraints(modifier.fillMaxSize().background(p.window).testTag("home_screen")) {
        val full = maxWidth >= GwigaonSize.fullFrom
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .heightIn(min = maxHeight)
                .padding(
                    horizontal = if (full) GwigaonSpace.xl + GwigaonSpace.sm else GwigaonSpace.panelSide,
                    vertical = GwigaonSpace.panelTop,
                ),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(GwigaonSpace.sm)) {
                IconTile(GwigaonIcons.Logo, p.surface, p.textPrimary)
                Text("귀가온", style = GwigaonText.title, color = p.textPrimary, modifier = Modifier.weight(1f))
                if (onQr != null && kind == HomeKind.READY) HeaderLink(GwigaonIcons.Mobile, LinkTexts.HOME_LINK, onQr, "home_qr")
                if (onHistory != null) HeaderLink(GwigaonIcons.Clock, SettingsTexts.HISTORY, onHistory, "home_history")
            }
            val (title, lede) = when (kind) {
                HomeKind.READY -> (if (full) "오늘도 집까지 함께 갈게요" else "오늘도 집까지\n함께 갈게요") to HomeTexts.READY_LEDE
                HomeKind.FIRST -> HomeTexts.FIRST_TITLE to HomeTexts.FIRST_LEDE
                HomeKind.PARTIAL -> HomeTexts.PARTIAL_TITLE to partialLede(settings)
            }
            ScreenTitle(title, lede, Modifier.padding(top = GwigaonSpace.xl))
            Spacer(Modifier.weight(1f).heightIn(min = GwigaonSpace.xl))
            val settingsDoor: @Composable (Modifier) -> Unit = { m ->
                when (kind) {
                    HomeKind.READY -> Door(DoorStyle.SECONDARY, GwigaonIcons.Sliders, "차 주인", "설정", SettingsTexts.doorSummary(settings), onSettings, full, m.testTag("home_settings"))
                    HomeKind.FIRST -> Door(DoorStyle.PRIMARY, GwigaonIcons.Sliders, "차 주인", "처음 설정하기", HomeTexts.FIRST_DOOR, onSettings, full, m.testTag("home_settings"))
                    HomeKind.PARTIAL -> Door(DoorStyle.PRIMARY, GwigaonIcons.Sliders, "차 주인", "설정 이어서 하기", partialSum(settings), onSettings, full, m.testTag("home_settings"))
                }
            }
            val startDoor: @Composable (Modifier) -> Unit = { m ->
                if (kind == HomeKind.READY) {
                    Door(DoorStyle.PRIMARY, GwigaonIcons.Wheel, "기사님", "운행 시작", "목적지: ${settings.home?.name.orEmpty()}", onStart, full, m.testTag("start"))
                } else {
                    Door(DoorStyle.UNAVAILABLE, GwigaonIcons.Wheel, "기사님", "운행 시작", HomeTexts.START_LOCKED, onStart, full, m.testTag("start"))
                }
            }
            if (full) {
                Row(horizontalArrangement = Arrangement.spacedBy(GwigaonSpace.md + GwigaonSpace.xs / 2)) {
                    settingsDoor(Modifier.weight(1f))
                    startDoor(Modifier.weight(1f))
                }
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(GwigaonSpace.md)) {
                    settingsDoor(Modifier)
                    startDoor(Modifier)
                }
            }
            if (kind == HomeKind.READY) {
                Row(
                    Modifier.padding(top = GwigaonSpace.md),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(GwigaonSpace.sm),
                ) {
                    Icon(GwigaonIcons.Shift, contentDescription = null, tint = p.textTertiary, modifier = Modifier.size(GwigaonSize.backIcon))
                    Text(HomeTexts.GEAR_HINT, style = GwigaonText.labelStrong, color = p.textTertiary)
                }
            }
        }
    }
}

/** Quiet text action in the header (운행 기록, QR 확인하기): icon, label, chevron; a full touch target, parked only. */
@Composable
private fun HeaderLink(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, onClick: () -> Unit, tag: String) {
    val p = Gwigaon.palette
    Row(
        Modifier
            .heightIn(min = GwigaonSize.touchMin)
            .clip(RoundedCornerShape(GwigaonRadius.tile))
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = GwigaonSpace.sm)
            .testTag(tag),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(GwigaonSpace.xs),
    ) {
        Icon(icon, contentDescription = null, tint = p.textSecondary, modifier = Modifier.size(GwigaonSize.backIcon))
        Text(label, style = GwigaonText.labelStrong, color = p.textSecondary)
        Icon(GwigaonIcons.Chevron, contentDescription = null, tint = p.textTertiary, modifier = Modifier.size(GwigaonSize.backIcon))
    }
}

private fun partialLede(s: Settings): String = when (HomeRule.missing(s).firstOrNull()) {
    SettingsField.HOME -> "주소가 아직 없어요."
    else -> "모니터링 QR를 아직 공유하지 않았어요."
}

private fun partialSum(s: Settings): String = when (HomeRule.missing(s).firstOrNull()) {
    SettingsField.HOME -> "주소를 골라 주세요"
    else -> "모니터링 QR을 공유해 주세요"
}

private enum class DoorStyle { PRIMARY, SECONDARY, UNAVAILABLE }

/** A door: who it is for, what it does, a one-line summary; primary is white like every primary action. */
@Composable
private fun Door(
    style: DoorStyle,
    icon: ImageVector,
    who: String,
    title: String,
    summary: String,
    onClick: () -> Unit,
    full: Boolean,
    modifier: Modifier,
) {
    val p = Gwigaon.palette
    val (ground, tile, glyph) = when (style) {
        DoorStyle.PRIMARY -> Triple(p.primary, p.onPrimary, p.primary)
        DoorStyle.SECONDARY -> Triple(p.surface, p.surfaceHigh, p.textPrimary)
        DoorStyle.UNAVAILABLE -> Triple(Color.Transparent, p.surface, p.textTertiary)
    }
    val strong = if (style == DoorStyle.PRIMARY) p.onPrimary else p.textPrimary
    val quiet = if (style == DoorStyle.PRIMARY) p.onPrimary.copy(alpha = 0.68f) else p.textTertiary
    Surface(
        onClick = onClick,
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = if (full) GwigaonSize.doorFull else GwigaonSize.door)
            .semantics {
                role = Role.Button
                if (style == DoorStyle.UNAVAILABLE) stateDescription = "아직 사용할 수 없음"
            },
        shape = RoundedCornerShape(GwigaonSize.doorRadius),
        color = ground,
        border = if (style == DoorStyle.UNAVAILABLE) BorderStroke(GwigaonSize.chipBorder, p.surface) else null,
    ) {
        Row(
            Modifier.padding(horizontal = GwigaonSpace.lg, vertical = GwigaonSize.cardPaddingTop),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(GwigaonSpace.md),
        ) {
            Box(
                Modifier.size(GwigaonSize.doorTile).clip(RoundedCornerShape(GwigaonRadius.key)).background(tile),
                contentAlignment = Alignment.Center,
            ) {
                Icon(icon, contentDescription = null, tint = glyph, modifier = Modifier.size(GwigaonSize.doorIcon))
            }
            Column(Modifier.weight(1f)) {
                Text(who, style = GwigaonText.labelStrong, color = quiet)
                Text(
                    title,
                    style = GwigaonText.action,
                    color = if (style == DoorStyle.UNAVAILABLE) p.textTertiary else strong,
                    modifier = Modifier.padding(top = GwigaonSpace.xs),
                )
                Text(summary, style = GwigaonText.label, color = quiet, modifier = Modifier.padding(top = GwigaonSpace.xs))
            }
            if (style != DoorStyle.UNAVAILABLE) {
                Icon(GwigaonIcons.Chevron, contentDescription = null, tint = strong, modifier = Modifier.size(GwigaonSize.chevron))
            }
        }
    }
}

private val PREVIEW_READY = Settings(home = Destination("양재역", 37.4846, 127.0342), contacts = listOf("엄마", "동생"))

@DayNightPreview
@Composable
internal fun HomeReadyPreview() = GwigaonTheme { HomeScreen(PREVIEW_READY, {}, {}) }

@Preview(widthDp = 842, heightDp = 1210)
@Composable
internal fun HomeFirstPreview() = GwigaonTheme { HomeScreen(Settings(), {}, {}) }

@Preview(widthDp = 1682, heightDp = 1210)
@Composable
internal fun HomeFullPreview() = GwigaonTheme { HomeScreen(PREVIEW_READY, {}, {}, onHistory = {}, onQr = {}) }
