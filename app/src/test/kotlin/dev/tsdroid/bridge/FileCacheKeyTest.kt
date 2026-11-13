package dev.tsdroid.bridge

import org.junit.Assert.*
import org.junit.Test

class FileCacheKeyTest {
    @Test fun serverAddressesAndPortsHaveSeparateSafeCacheDirectories() {
        val first = fileCacheServerKey("example.com:9987")
        assertEquals(first, fileCacheServerKey("example.com:9987"))
        assertNotEquals(first, fileCacheServerKey("example.com:9988"))
        assertNotEquals(fileCacheServerKey("example.com:9987"), fileCacheServerKey("example.com_9987"))
        assertTrue(fileCacheServerKey("[2001:db8::1]:9987").matches(Regex("[a-zA-Z0-9._-]+")))
    }

    @Test fun cachedFilesAreIsolatedByChannelAndCannotTraverseDirectories() {
        assertNotEquals(fileCacheRelativePath(1, "/report.txt"), fileCacheRelativePath(2, "/report.txt"))
        assertNotEquals(fileCacheRelativePath(1, "/a/report.txt"), fileCacheRelativePath(1, "/b/report.txt"))
        val path = fileCacheRelativePath(1, "/../../report.txt")
        assertFalse(path.split('/').any { it == ".." })
        assertTrue(path.endsWith("/report.txt"))
        assertTrue(fileCacheRelativePath(1, "/../..").endsWith("/file"))
    }

    @Test fun fileLinksMustReferToTheConnectedServer() {
        assertTrue(isSameFileServer("Example.com:9987", "example.com", "9987"))
        assertTrue(isSameFileServer("example.com.", "EXAMPLE.com", null))
        assertTrue(isSameFileServer("example.com", "example.com", null))
        assertFalse(isSameFileServer("example.com:9987", "other.example.com", "9987"))
        assertFalse(isSameFileServer("example.com:9987", "example.com", "9988"))
        assertFalse(isSameFileServer("example.com:9987", "example.com", "invalid"))
        assertTrue(isSameFileServer("[2001:db8::1]:9990", "2001:db8::1", "9990"))
        assertTrue(isSameFileServer("[2001:db8::1]:9990", "2001:0db8:0:0:0:0:0:1", "9990"))
        assertFalse(isSameFileServer("[2001:db8::1]:9990", "2001:db8::2", "9990"))
        assertFalse(isSameFileServer("[2001:db8::1]oops", "2001:db8::1", null))
    }

    @Test fun extractsLinksWithoutTreatingIpv6BracketsAsBbCode() {
        assertEquals("ts3file://[2001:db8::1]?channel=3", extractFileLink("[url]ts3file://[2001:db8::1]?channel=3[/url]"))
        assertEquals("ts3file://example.com?channel=3", extractFileLink("see ts3file://example.com?channel=3 now"))
        assertNull(extractFileLink("ts3file://[bad?channel=3"))
        assertNull(extractFileLink("no link"))
    }

    @Test fun shareLinkAuthorityPreservesIpv6AndPort() {
        assertEquals(FileServerEndpoint("2001:db8::1", 9987), fileServerEndpoint("2001:db8::1"))
        assertEquals("[2001:db8::1]", fileServerEndpoint("[2001:db8::1]:9990")?.authority)
        assertEquals(9990, fileServerEndpoint("[2001:db8::1]:9990")?.port)
        assertNull(fileServerEndpoint("server:99999"))
        assertNull(fileServerEndpoint("server/path:9987"))
    }
}
