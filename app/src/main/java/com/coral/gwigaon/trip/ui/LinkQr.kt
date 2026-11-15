package com.coral.gwigaon.trip.ui

import android.util.Log
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.coral.gwigaon.sync.LinkConnection
import com.coral.gwigaon.sync.OwnerLinkRepository
import com.coral.gwigaon.sync.QrCode
import com.coral.gwigaon.sync.QrMatrix
import com.coral.gwigaon.ui.theme.Gwigaon
import com.coral.gwigaon.ui.theme.GwigaonRadius
import com.coral.gwigaon.ui.theme.GwigaonSize
import com.coral.gwigaon.ui.theme.GwigaonSpace
import com.coral.gwigaon.ui.theme.GwigaonText
import com.coral.gwigaon.ui.theme.palette
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/** The account QR as 설정 shows it (ADR 0019). [url] null: no QR to show (no cloud in this build). */
data class LinkUiState(
    val url: String? = null,
    val connection: LinkConnection? = null,
    val confirmingReplace: Boolean = false,
    val replacing: Boolean = false,
)

/** What 설정 says about the QR. */
object LinkTexts {
    const val TITLE = "모니터링 QR을 공유해 주세요"
    const val LEDE = "운행 시작부터 안전 귀가까지 같이 봐줘요"
    const val HOW = "QR을 찍어 함께 볼 사람에게 보내 주세요"
    const val HOW_DETAIL = "가족, 친구 누구든 괜찮아요 · 앱 설치도 가입도 없어요"
    const val REPLACE = "QR 다시 만들기"
    const val REPLACE_WARN = "새 QR을 만들면 연결된 휴대폰은 더 볼 수 없어요"
    const val REPLACE_CONFIRM = "다시 만들기"
    const val REPLACE_CANCEL = "취소"
    const val QR_DESCRIPTION = "모니터링 QR 코드"
    const val HOME_LINK = "QR 확인하기"

    fun connection(c: LinkConnection?): String = when (c) {
        null, LinkConnection.Preparing -> "연결 준비 중"
        LinkConnection.NoCloud -> "클라우드가 없는 빌드라 QR을 쓸 수 없어요"
        LinkConnection.NotConnected -> "아직 아무도 연결하지 않았어요"
        is LinkConnection.Connected -> "연결됨 · 휴대폰 ${c.phones}대"
        LinkConnection.Unknown -> "연결 상태를 확인하지 못했어요"
    }
}

/**
 * Keeps the account QR on screen: makes the link on first look, uploads it and polls who opened it while 설정 shows
 * it ([watch]), and replaces it on QR 다시 만들기.
 */
class LinkViewModel(
    private val repository: OwnerLinkRepository,
    private val contactPageUrl: String,
    private val poll: Duration = 5.seconds,
) : ViewModel() {
    private val local = MutableStateFlow(LinkUiState())
    private var watcher: Job? = null

    val state: StateFlow<LinkUiState> =
        combine(local, repository.link) { s, link -> s.copy(url = link?.url(contactPageUrl)) }
            .stateIn(viewModelScope, SharingStarted.Eagerly, local.value)

    /** Starts polling (idempotent); [stopWatching] when the QR leaves the screen. */
    fun watch() {
        if (watcher?.isActive == true) return
        watcher = viewModelScope.launch {
            while (isActive) {
                refresh()
                delay(poll)
            }
        }
    }

    fun stopWatching() {
        watcher?.cancel()
        watcher = null
        local.update { it.copy(confirmingReplace = false) }
    }

    private suspend fun refresh() {
        repository.ensure()
        val c = runCatching { repository.connection() }.onFailure { Log.w(TAG, "link state unreadable", it) }.getOrDefault(LinkConnection.Unknown)
        local.update { it.copy(connection = c) }
    }

    fun askReplace() = local.update { it.copy(confirmingReplace = true) }

    fun cancelReplace() = local.update { it.copy(confirmingReplace = false) }

    fun replace() {
        if (local.value.replacing) return
        local.update { it.copy(replacing = true, confirmingReplace = false, connection = LinkConnection.Preparing) }
        viewModelScope.launch {
            repository.replace()
            refresh()
            local.update { it.copy(replacing = false) }
        }
    }

    private companion object {
        const val TAG = "LinkViewModel"
    }
}

/** The QR itself: dark on white in night mode too (phone cameras read it that way), with a white quiet zone. */
@Composable
internal fun LinkQr(url: String?, size: Dp, modifier: Modifier = Modifier) {
    val p = Gwigaon.palette
    val shape = RoundedCornerShape(GwigaonRadius.tile)
    if (url == null) {
        Box(modifier.size(size).clip(shape).background(p.surfaceHigh).testTag("link_qr_missing"), contentAlignment = Alignment.Center) {
            Text("QR 없음", style = GwigaonText.labelStrong, color = p.textTertiary)
        }
        return
    }
    val qr = remember(url) { QrCode.encode(url) }
    Box(
        modifier
            .size(size)
            .clip(shape)
            .background(Color.White)
            .padding(GwigaonSize.qrQuiet)
            .semantics { contentDescription = LinkTexts.QR_DESCRIPTION }
            .testTag("link_qr"),
    ) {
        QrCanvas(qr, Modifier.fillMaxSize())
    }
}

@Composable
private fun QrCanvas(qr: QrMatrix, modifier: Modifier) {
    Canvas(modifier) {
        val cell = size.minDimension / qr.size
        for (y in 0 until qr.size) {
            for (x in 0 until qr.size) {
                if (qr[x, y]) drawRect(Color.Black, Offset(x * cell, y * cell), Size(cell + 0.5f, cell + 0.5f))
            }
        }
    }
}

/** Under the QR: whether anyone has connected, and QR 다시 만들기 with its inline confirm. */
@Composable
internal fun LinkStatus(link: LinkUiState, actions: SettingsActions, modifier: Modifier = Modifier) {
    val p = Gwigaon.palette
    val connected = link.connection is LinkConnection.Connected
    Column(modifier, verticalArrangement = Arrangement.spacedBy(GwigaonSpace.sm)) {
        Text(
            LinkTexts.connection(link.connection),
            style = GwigaonText.rowTitle,
            color = if (connected) p.safeText else p.textSecondary,
            modifier = Modifier.testTag("link_connection"),
        )
        if (link.url == null) return@Column
        if (link.confirmingReplace) {
            Text(LinkTexts.REPLACE_WARN, style = GwigaonText.detail, color = p.cautionText)
            Row(horizontalArrangement = Arrangement.spacedBy(GwigaonSpace.sm)) {
                RowAction(LinkTexts.REPLACE_CONFIRM, actions.replaceLink, Modifier.testTag("link_replace_confirm"), p.surfaceHigh)
                RowAction(LinkTexts.REPLACE_CANCEL, actions.cancelReplaceLink, Modifier.testTag("link_replace_cancel"), p.surfaceHigh)
            }
        } else {
            RowAction(LinkTexts.REPLACE, actions.askReplaceLink, Modifier.testTag("link_replace"), p.surfaceHigh)
        }
    }
}
