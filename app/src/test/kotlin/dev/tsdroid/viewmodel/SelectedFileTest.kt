package dev.tsdroid.viewmodel

import java.io.ByteArrayInputStream
import org.junit.Assert.*
import org.junit.Test

class SelectedFileTest {
    @Test fun uploadOriginMustStillMatchTheSessionAndChannel() {
        val origin = UploadOrigin("session-one", 42, "/folder/")
        assertTrue(origin.matches("session-one", 42, true))
        assertFalse(origin.matches("session-two", 42, true))
        assertFalse(origin.matches("session-one", 43, true))
        assertFalse(origin.matches("session-one", 42, false))
        assertFalse(origin.matches(null, null, true))
    }

    @Test fun readsUpToAndIncludingTheLimit() {
        assertArrayEquals(byteArrayOf(1, 2, 3), readSelectedFile(ByteArrayInputStream(byteArrayOf(1, 2, 3)), 3))
        assertArrayEquals(byteArrayOf(), readSelectedFile(ByteArrayInputStream(byteArrayOf()), 0))
    }

    @Test(expected = SelectedFileTooLargeException::class)
    fun rejectsFilesOverTheLimit() {
        readSelectedFile(ByteArrayInputStream(byteArrayOf(1, 2, 3, 4)), 3)
    }

    @Test fun providerNamesCannotEscapeTheSelectedFolder() {
        assertEquals("report.txt", safeUploadName("../folder/report.txt"))
        assertEquals("image.png", safeUploadName("C:\\folder\\image.png"))
        assertEquals("file", safeUploadName(".."))
        assertEquals("ab.txt", safeUploadName("a\u0000b.txt"))
    }
}
