package dev.beszel.mobile.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class BeszelApiTest {
    @Test
    fun normalizesHubUrls() {
        assertEquals("https://demo.example.com", BeszelApi.normalizeHubUrl("demo.example.com/"))
        assertEquals("http://192.168.1.5:8090/beszel", BeszelApi.normalizeHubUrl(" http://192.168.1.5:8090/beszel/ "))
    }

    @Test
    fun rejectsUnsupportedSchemes() {
        assertThrows(IllegalArgumentException::class.java) {
            BeszelApi.normalizeHubUrl("ftp://example.com")
        }
    }

    @Test
    fun formatsUptimeAtUsefulBoundaries() {
        assertEquals("1 min", formatUptime(35))
        assertEquals("3 hr", formatUptime(10_800))
        assertEquals("4 days", formatUptime(345_600))
    }
}
