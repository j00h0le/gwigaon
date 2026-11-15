package com.coral.gwigaon.ui.components

import android.content.res.Configuration
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import com.coral.gwigaon.ui.theme.Gwigaon
import com.coral.gwigaon.ui.theme.GwigaonSpace
import com.coral.gwigaon.ui.theme.GwigaonTheme
import com.coral.gwigaon.ui.theme.palette

/** Night and day previews at the driver panel's width (842 dp). */
@Preview(name = "night", widthDp = 842, uiMode = Configuration.UI_MODE_NIGHT_YES or Configuration.UI_MODE_TYPE_NORMAL)
@Preview(name = "day", widthDp = 842, uiMode = Configuration.UI_MODE_NIGHT_NO or Configuration.UI_MODE_TYPE_NORMAL)
annotation class DayNightPreview

/** The panel's window and padding around preview content, in the current day/night palette. */
@Composable
fun PreviewPanel(content: @Composable ColumnScope.() -> Unit) = GwigaonTheme {
    Column(
        Modifier
            .background(Gwigaon.palette.window)
            .padding(horizontal = GwigaonSpace.panelSide, vertical = GwigaonSpace.panelTop),
        verticalArrangement = Arrangement.spacedBy(GwigaonSpace.lg),
        content = content,
    )
}
