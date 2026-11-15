package com.coral.gwigaon.trip.ui

import com.coral.gwigaon.sync.LinkConnection
import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.time.Instant

class LinkTextsTest {
    @Test
    fun connectionLineForEveryState() {
        assertEquals("연결 준비 중", LinkTexts.connection(null))
        assertEquals("연결 준비 중", LinkTexts.connection(LinkConnection.Preparing))
        assertEquals("클라우드가 없는 빌드라 QR을 쓸 수 없어요", LinkTexts.connection(LinkConnection.NoCloud))
        assertEquals("아직 아무도 연결하지 않았어요", LinkTexts.connection(LinkConnection.NotConnected))
        assertEquals("연결됨 · 휴대폰 2대", LinkTexts.connection(LinkConnection.Connected(2, Instant.parse("2026-11-14T10:00:00Z"))))
        assertEquals("연결 상태를 확인하지 못했어요", LinkTexts.connection(LinkConnection.Unknown))
    }
}
