package com.coral.gwigaon.ui.components

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.unit.dp

/**
 * The prototypes' line icons (24 viewport, round caps), drawn as vectors so no icon library is needed.
 * Tint them with `Icon(tint = …)`.
 */
object GwigaonIcons {
    private fun circle(cx: Float, cy: Float, r: Float) =
        "M${cx - r} ${cy}a$r $r 0 1 0 ${2 * r} 0a$r $r 0 1 0 ${-2 * r} 0"

    private fun icon(name: String, width: Float, vararg paths: String): ImageVector =
        ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f).apply {
            paths.forEach { d ->
                addPath(
                    pathData = PathParser().parsePathString(d).toNodes(),
                    stroke = SolidColor(Color.Black),
                    strokeLineWidth = width,
                    strokeLineCap = StrokeCap.Round,
                    strokeLineJoin = StrokeJoin.Round,
                )
            }
        }.build()

    val Warn = icon("warn", 2.6f, "M12 8v5M12 17h.01")
    val Stop = icon("stop", 2.6f, "M9 8v8M15 8v8")
    val Done = icon("done", 2.6f, "M5 12l5 5 9-10")
    val Start = icon("start", 2.6f, circle(12f, 12f, 3f))
    val Pin = icon("pin", 2.2f, "M12 21s7-6.2 7-11a7 7 0 0 0-14 0c0 4.8 7 11 7 11z", circle(12f, 10f, 2.5f))
    val Door = icon("door", 2.2f, "M6 21V4h12v17M3 21h18M14 12h.01")
    val Key = icon("key", 2.2f, circle(8f, 15f, 4f), "M11 12l9-9M17 6l3 3")
    val Speaker = icon("speaker", 2.2f, "M4 9v6h4l5 4V5L8 9H4z", "M16.5 8.5a5 5 0 0 1 0 7")
    val Phone = icon(
        "phone", 2.2f,
        "M5 4h4l2 5-2.5 1.5a11 11 0 0 0 5 5L15 13l5 2v4a2 2 0 0 1-2 2A16 16 0 0 1 3 6a2 2 0 0 1 2-2",
    )
    val Alert = icon("alert", 2.6f, "M12 6.5v7M12 17.5h.01")
    val Back = icon("back", 2.4f, "M15 5l-7 7 7 7")
    val Ask = icon("ask", 2.6f, "M9.5 9a2.5 2.5 0 1 1 3.5 2.3c-.6.3-1 .9-1 1.6v.6M12 17h.01")
    val Loud = icon("loud", 2.4f, "M4 10v4M8 7v10M12 4v16M16 8v8M20 11v2")
    val Word = icon("word", 2.4f, "M4 5h16v11H9l-5 4z", "M12 8v3M12 13.5h.01")
    val Delete = icon("delete", 2.2f, "M9 5h11v14H9l-6-7z", "M11.5 9.5l5 5M16.5 9.5l-5 5") // ✕ centred in the body (D3f H14)

    // Home, start screen and 설정 (D3c).
    val Logo = icon("logo", 2.2f, "M4 11l8-7 8 7v9H4z", "M9 15l2 2 4-4")
    val Home = icon("home", 2.2f, "M4 11l8-7 8 7v9H4z", "M10 20v-5h4v5")
    val Wheel = icon("wheel", 2.2f, circle(12f, 12f, 9f), circle(12f, 12f, 2.5f), "M3.5 10.5h6M14.5 10.5h6M12 14.5V21")
    val Sliders = icon(
        "sliders", 2.2f, "M4 6h10M18 6h2M4 12h4M12 12h8M4 18h12",
        circle(16f, 6f, 2f), circle(10f, 12f, 2f), circle(18f, 18f, 2f),
    )
    val Chevron = icon("chevron", 2.6f, "M9 5l7 7-7 7")
    val Shift = icon("shift", 2.2f, "M8 3h8a2 2 0 0 1 2 2v14a2 2 0 0 1-2 2H8a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2z", "M12 7v6M9.5 10.5L12 13l2.5-2.5")
    val Shield = icon("shield", 2.2f, "M12 3l8 3v6c0 4.5-3.4 8.2-8 9-4.6-.8-8-4.5-8-9V6l8-3z", "M8.5 12l2.5 2.5 4.5-5")
    val Mic = icon("mic", 2.2f, "M9 6a3 3 0 0 1 6 0v5a3 3 0 0 1-6 0z", "M5 11a7 7 0 0 0 14 0M12 18v3")
    val Flag = icon("flag", 2.2f, "M5 21V4M5 4h11l-2 4 2 4H5")
    val Lock = icon("lock", 2.2f, "M7 11h10a2 2 0 0 1 2 2v6a2 2 0 0 1-2 2H7a2 2 0 0 1-2-2v-6a2 2 0 0 1 2-2z", "M8 11V7a4 4 0 0 1 8 0v4")
    val People = icon(
        "people", 2.2f, circle(9f, 8f, 3.5f), "M2.5 20a6.5 6.5 0 0 1 13 0",
        "M16 4.5a3.5 3.5 0 0 1 0 7M18 14a6.5 6.5 0 0 1 3.5 6",
    )
    val Person = icon("person", 2.2f, circle(12f, 8f, 4f), "M4 21a8 8 0 0 1 16 0")
    val Car = icon("car", 2.2f, "M5 17h14v-5l-2-5H7l-2 5z", circle(8f, 17f, 2f), circle(16f, 17f, 2f))
    val Mobile = icon("mobile", 2.2f, "M9.5 2.5h5a2.5 2.5 0 0 1 2.5 2.5v14a2.5 2.5 0 0 1-2.5 2.5h-5A2.5 2.5 0 0 1 7 19V5a2.5 2.5 0 0 1 2.5-2.5z", "M11 18.5h2")
    val Clock = icon("clock", 2.2f, circle(12f, 12f, 8f), "M12 8v4l3 2")
    val Seat = icon("seat", 2.2f, "M7 3h4l1 9h6l1 8M7 3l-1 9 2 8h11")
    val Info = icon("info", 2.2f, circle(12f, 12f, 9f), "M12 11v5.5M12 7.5h.01")
    val Close = icon("close", 2.4f, "M6 6l12 12M18 6L6 18")
}
