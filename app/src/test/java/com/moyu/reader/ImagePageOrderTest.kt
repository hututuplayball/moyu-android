package com.moyu.reader

import com.moyu.reader.page.COMIC_PATH_ORDER
import com.moyu.reader.page.isComicImage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ImagePageOrderTest {
    private fun sorted(names: List<String>) = names.sortedWith(COMIC_PATH_ORDER)

    @Test fun numbersSortNaturallyInsteadOfLexically() {
        assertEquals(
            listOf("1.jpg", "2.jpg", "10.jpg", "page 3.png", "page 20.png"),
            sorted(listOf("10.jpg", "page 20.png", "1.jpg", "page 3.png", "2.jpg")),
        )
    }

    @Test fun chapterFoldersKeepTheirNumericOrder() {
        assertEquals(
            listOf("第1话/1.jpg", "第1话/10.jpg", "第2话/1.jpg", "第10话/1.jpg"),
            sorted(listOf("第10话/1.jpg", "第2话/1.jpg", "第1话/10.jpg", "第1话/1.jpg")),
        )
    }

    @Test fun zeroPaddingDoesNotChangeTheOrder() {
        assertEquals(listOf("page-02.jpg", "page-2.jpg", "page-10.jpg"), sorted(listOf("page-10.jpg", "page-02.jpg", "page-2.jpg")))
    }

    @Test fun onlyImageFilesBecomePages() {
        assertTrue(isComicImage("001.JPG"))
        assertTrue(isComicImage("cover.webp"))
        assertTrue(isComicImage("scan.PNG"))
        assertFalse(isComicImage("info.txt"))
        assertFalse(isComicImage("Thumbs.db"))
        assertFalse(isComicImage("noextension"))
    }
}
