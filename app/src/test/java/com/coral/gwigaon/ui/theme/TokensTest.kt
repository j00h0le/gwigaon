package com.coral.gwigaon.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.sp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** DESIGN.md as tokens (D3a): night values match the file, the derived day palette keeps the same contrast floor. */
class TokensTest {
    private val palettes = mapOf("night" to NightPalette, "day" to DayPalette)

    private fun assertReadable(name: String, fg: Color, bg: Color, floor: Double = 4.5) {
        val ratio = contrastRatio(fg, bg)
        assertTrue("$name: %.2f < $floor".format(ratio), ratio >= floor)
    }

    @Test
    fun nightPaletteIsDesignMd() {
        with(NightPalette) {
            assertEquals(Color(0xFF1D1E21), window)
            assertEquals(Color(0xFF131417), sunk)
            assertEquals(Color(0xFF313236), surface)
            assertEquals(Color(0xFF44464E), surfaceHigh)
            assertEquals(Color(0xFF6A57FF), progress)
            assertEquals(Color(0xFFFFA202), caution)
            assertEquals(Color(0xFFFFB62E), cautionText)
            assertEquals(Color(0xFF8C6A2C), cautionPast)
            assertEquals(Color(0xFFFF4C28), sos)
            assertEquals(Color(0xFFFF6A4D), sosText)
            assertEquals(Color(0xFF3A1710), sosGround)
            assertEquals(Color(0xFF2A0F09), sosSentGround)
            assertEquals(Color(0xFF00E074), safe)
        }
    }

    @Test
    fun textKeepsFourAndAHalfToOneOnEverySurfaceInBothPalettes() {
        palettes.forEach { (p, c) ->
            listOf(c.window, c.surface, c.surfaceHigh).forEach { bg ->
                assertReadable("$p primary", c.textPrimary, bg)
                assertReadable("$p secondary", c.textSecondary, bg)
                assertReadable("$p tertiary", c.textTertiary, bg)
            }
        }
    }

    @Test
    fun accentTextIsReadableOnItsGround() {
        palettes.forEach { (p, c) ->
            assertReadable("$p caution on window", c.cautionText, c.window)
            assertReadable("$p caution on tint", c.cautionText, composite(c.cautionTint, c.window))
            assertReadable("$p sos text on ground", c.sosText, c.sosGround)
            assertReadable("$p hint on sos ground", c.textSecondary, c.sosGround)
            assertReadable("$p safe on tint", c.safeText, composite(c.safeTint, c.surface))
            assertReadable("$p on primary", c.onPrimary, c.primary)
            assertReadable("$p sos sent text", c.textPrimary, c.sosSentGround)
        }
    }

    @Test
    fun liveAndPastMarksDifferAndMarkGapsUseTheWindow() {
        palettes.forEach { (_, c) ->
            assertNotEquals(c.caution, c.cautionPast)
            assertNotEquals(c.caution, c.progress)
        }
    }

    @Test
    fun drivingTextIsAtLeastThirtyTwo() {
        assertEquals(52.sp, GwigaonText.status.fontSize)
        assertEquals(112.sp, GwigaonText.numeral.fontSize)
        assertTrue(GwigaonText.detail.fontSize.value >= 32f)
        // D2 left the SOS-bar hint at 28 dp, under the driving floor.
        assertTrue(GwigaonText.sosHint.fontSize.value >= 32f)
        assertTrue(GwigaonText.title.fontSize.value >= 32f)
        assertTrue(GwigaonText.body.fontSize.value >= 32f)
    }

    @Test
    fun numeralsUseTabularFigures() {
        assertEquals("tnum", GwigaonText.numeral.fontFeatureSettings)
        assertEquals("tnum", GwigaonText.time.fontFeatureSettings)
    }

    @Test
    fun contrastOfBlackOnWhiteIsTwentyOne() {
        assertEquals(21.0, contrastRatio(Color.Black, Color.White), 0.01)
        assertEquals(1.0, contrastRatio(Color.White, Color.White), 0.01)
    }
}
