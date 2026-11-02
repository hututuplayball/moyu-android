package com.moyu.reader

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.IntSize
import com.moyu.reader.ui.PdfPageLayout
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class TallPageTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun heavilyZoomedStripReportsItsTrueHeightWithoutPackingConstraints() {
        var measured = IntSize.Zero
        compose.setContent {
            Box(Modifier.fillMaxSize()) {
                // 5x on a 5400x64800 page: 324000px tall, beyond the 18-bit Constraints ceiling.
                PdfPageLayout(IntSize(5400, 324000), IntSize(1080, 1920),
                    Modifier.onGloballyPositioned { measured = it.size })
            }
        }
        compose.waitForIdle()
        assertEquals(IntSize(5400, 324000), measured)
    }
}
