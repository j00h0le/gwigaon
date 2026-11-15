package com.coral.gwigaon.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

class ShareTokenTest {
    @Test
    fun tokensAre24UrlSafeCharactersAndDiffer() {
        val tokens = List(200) { ShareToken.generate() }
        assertTrue(tokens.all { it.length == 24 && ShareToken.isValid(it) })
        assertEquals(200, tokens.toSet().size)
    }

    @Test
    fun linkPutsTheTokenInTheFragmentOnly() {
        val t = "abcdefghijklmnopqrstuvwx"
        assertEquals("https://x.github.io/contact/#t=$t", ShareToken.link("https://x.github.io/contact/", t))
        assertEquals("http://127.0.0.1:8080/#t=$t", ShareToken.link("http://127.0.0.1:8080/#old", t))
        assertNull(ShareToken.link("", t))
        assertNull(ShareToken.link("https://x/", "short"))
        assertNull(ShareToken.link("https://x/", "has spaces in it 0123456789"))
    }

    @Test
    fun onlyPublishableKeysEnableSync() {
        fun jwt(role: String) = "eyJhbGciOiJIUzI1NiJ9." +
            Base64.getUrlEncoder().withoutPadding().encodeToString("""{"iss":"x","role":"$role"}""".toByteArray()) + ".sig"

        assertTrue(CloudConfig("http://10.0.2.2:54321", "sb_publishable_abc", "").syncEnabled)
        assertTrue(CloudConfig("http://10.0.2.2:54321", jwt("anon"), "").syncEnabled)
        assertFalse(CloudConfig("http://10.0.2.2:54321", jwt("service_role"), "").syncEnabled)
        assertFalse(CloudConfig("http://10.0.2.2:54321", "sb_secret_abc", "").syncEnabled)
        assertFalse(CloudConfig("http://10.0.2.2:54321", "a.!!!.c", "").syncEnabled)
        assertFalse(CloudConfig("", "sb_publishable_abc", "").syncEnabled)
        assertFalse(CloudConfig("http://x", "", "").syncEnabled)
        assertFalse(CloudConfig("http://x", "sb_publishable_abc", "").toString().contains("sb_publishable"))
    }
}

class QrCodeTest {
    @Test
    fun encodesTheLinkAsASquareWithFinderPatternsAndAQuietZone() {
        val qr = QrCode.encode("http://127.0.0.1:8080/#t=abcdefghijklmnopqrstuvwx")
        assertTrue(qr.size >= 21 + 4)
        // Quiet zone: the outer two rings are light.
        assertTrue((0 until qr.size).none { qr[it, 0] || qr[0, it] || qr[it, 1] || qr[qr.size - 1, it] })
        // Top-left finder pattern starts right after the quiet zone: dark corner, light ring inside, dark core.
        assertTrue(qr[2, 2] && qr[8, 2] && qr[2, 8])
        assertFalse(qr[3, 3])
        assertTrue(qr[5, 5])
    }
}
