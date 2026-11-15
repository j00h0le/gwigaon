package com.coral.gwigaon.sync

import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel

/** A QR code as a square grid of modules, quiet zone included; drawn by the share card. */
class QrMatrix(val size: Int, private val dark: BooleanArray) {
    operator fun get(x: Int, y: Int): Boolean = dark[y * size + x]
}

/** The share link as a QR code (ZXing core, encoding only). */
object QrCode {
    /** Quiet zone in modules: 2 is enough for phone cameras on a screen and keeps the modules large. */
    private const val MARGIN = 2

    fun encode(text: String): QrMatrix {
        val hints = mapOf(
            EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M,
            EncodeHintType.MARGIN to MARGIN,
            EncodeHintType.CHARACTER_SET to "UTF-8",
        )
        // Size 0 asks for the natural size: one bit per module.
        val bits = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, 0, 0, hints)
        val n = bits.width
        return QrMatrix(n, BooleanArray(n * n) { bits[it % n, it / n] })
    }
}
