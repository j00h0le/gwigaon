package com.coral.gwigaon.ui.debug

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.Modifier
import com.coral.gwigaon.ui.components.ActionsPreview
import com.coral.gwigaon.ui.components.ArrivalNotesPreview
import com.coral.gwigaon.ui.components.KeypadPreview
import com.coral.gwigaon.ui.components.SosPreview
import com.coral.gwigaon.ui.components.TimelinePreview
import com.coral.gwigaon.ui.components.TripStatusPreview

/**
 * Debug only: the D3a component previews on the device, for day/night screenshots.
 * `adb shell am start -n com.coral.gwigaon/.ui.debug.ComponentGalleryActivity --ei page 0|1|2`
 */
class ComponentGalleryActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val page = intent.getIntExtra("page", 0)
        setContent {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                when (page) {
                    0 -> {
                        TripStatusPreview()
                        TimelinePreview()
                        SosPreview()
                    }
                    1 -> ActionsPreview()
                    else -> {
                        ArrivalNotesPreview()
                        KeypadPreview()
                    }
                }
            }
        }
    }
}
