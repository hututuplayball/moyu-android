package com.moyu.reader

import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import com.moyu.reader.book.TextPageSource
import com.moyu.reader.book.TextReadingSettings
import com.moyu.reader.book.TextThemes
import com.moyu.reader.data.FORMAT_TEXT
import com.moyu.reader.pdf.PageSize
import com.moyu.reader.ui.TextPageSlot
import com.moyu.reader.ui.TextPagesView
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The reader draws text pages through a native view now. It must paint exactly the page the bitmap
 * path used to produce, must never allocate a page bitmap itself, and must repaint when the theme
 * or the chapter window changes what that page index means.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class TextPageDrawTest {
    private val pageSize = PageSize(600, 900)
    private val density = 2f

    @Test fun drawOnPaintsTheSamePageAsTheBitmapPath() = runBlocking {
        withSource { source ->
            val width = 450
            val expected = source.render(0, width)
            val actual = Bitmap.createBitmap(width, expected.height, Bitmap.Config.ARGB_8888)
            source.drawOn(Canvas(actual), 0, width)
            assertTrue("live drawing differs from the bitmap render", expected.sameAs(actual))
        }
    }

    @Test fun drawOnNeverAllocatesAPageBitmap() = runBlocking {
        withSource { source ->
            val canvas = Canvas(Bitmap.createBitmap(300, 450, Bitmap.Config.ARGB_8888))
            source.drawOn(canvas, 0, 300)
            assertNull(source.cachedRender(0, 300))
        }
    }

    @Test fun theLayerDrawsAPageIntoItsSlot() = runBlocking {
        withSource { source ->
            val width = 450
            val height = source.render(0, width).height
            // The slot sits inside the view with an offset: the layer must place the page there.
            val left = 50
            val top = 100
            val view = TextPagesView(RuntimeEnvironment.getApplication())
            view.bind(source, source.renderVersion)
            view.setPages(listOf(TextPageSlot(0, left, top, width, height)))
            view.measure(
                View.MeasureSpec.makeMeasureSpec(left + width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(top + height, View.MeasureSpec.EXACTLY),
            )
            view.layout(0, 0, left + width, top + height)
            val drawn = Bitmap.createBitmap(left + width, top + height, Bitmap.Config.ARGB_8888)
            view.draw(Canvas(drawn))
            val slot = Bitmap.createBitmap(drawn, left, top, width, height)
            assertTrue("the layer did not paint the page in its slot", source.render(0, width).sameAs(slot))
            drawn.recycle()
            slot.recycle()
        }
    }

    @Test fun aThemeChangeMakesTheLivePageRepaint() = runBlocking {
        withSource { source ->
            val night = TextThemes.all.first { it.id == "night" }
            val before = source.renderVersion
            source.restyle(night)
            assertTrue("a repaint must be requested", source.renderVersion != before)
            val width = 300
            val drawn = Bitmap.createBitmap(width, 450, Bitmap.Config.ARGB_8888)
            source.drawOn(Canvas(drawn), 0, width)
            // The page background follows the new theme without waiting for a new layout.
            assertEquals(night.bg.toInt(), drawn.getPixel(0, 0))
            // The ink follows it too: a layout cached by the previous theme must not survive.
            val pixels = IntArray(width * drawn.height)
            drawn.getPixels(pixels, 0, width, 0, 0, width, drawn.height)
            assertTrue("the new ink did not reach the page", pixels.any { it == night.text.toInt() })
            drawn.recycle()
        }
    }

    /** Opens a two-chapter book laid out at the reading size, then releases it on every path. */
    private suspend fun withSource(block: suspend (TextPageSource) -> Unit) {
        val directory = File(RuntimeEnvironment.getApplication().cacheDir, "draw-${System.nanoTime()}")
        assertTrue(directory.mkdirs())
        val file = File(directory, "novel.txt")
        try {
            file.writeText(buildString {
                append("第一章 起点\n\n")
                repeat(40) { append("山间的风把云吹开一线，晨光落在石阶上，远处的鸟鸣一声接着一声地传来。\n\n") }
                append("第二章 行路\n\n")
                repeat(40) { append("他沿着河岸走了很久，水面上的光斑晃动着，像无数枚碎掉又拼好的镜子。\n\n") }
            })
            val source = TextPageSource.open(file, FORMAT_TEXT, TextReadingSettings(), pageSize, density)
            try {
                block(source)
            } finally {
                source.close()
            }
        } finally {
            file.delete()
            directory.delete()
        }
    }
}
