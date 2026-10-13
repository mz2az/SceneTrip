package com.mz2az.scenetrip

import com.mz2az.scenetrip.auth.AccountReply
import com.mz2az.scenetrip.data.ImageDns
import com.mz2az.scenetrip.data.ImageRequests
import okhttp3.Dns
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.net.InetAddress

class AccountAndImageTests {
    @Test
    fun restorationAndNicknameIgnoreOldEpochAndWrongUser() {
        assertTrue(AccountReply.restoration(3, 3, true))
        assertFalse(AccountReply.restoration(2, 3, true))
        assertFalse(AccountReply.restoration(3, 3, false))
        assertTrue(AccountReply.nickname(3, 3, "a", "a", "a"))
        assertFalse(AccountReply.nickname(2, 3, "a", "a", "a"))
        assertFalse(AccountReply.nickname(3, 3, "a", "b", "a"))
        assertFalse(AccountReply.nickname(3, 3, "a", "a", "b"))
        assertFalse(AccountReply.nickname(3, 3, null, null, "a"))
    }

    @Test
    fun signedImageRequestKeepsOriginalHostAndEncodedQueryWithoutApiCredentials() {
        val query = "X-Amz-Credential=test%2F20261013%2Fregion%2Fs3%2Faws4_request&X-Amz-SignedHeaders=host&X-Amz-Signature=example"
        val request = ImageRequests.request("http://localhost:9000/media/a%20b.jpg?$query")
        assertEquals("localhost", request.url.host)
        assertEquals(9000, request.url.port)
        assertEquals("/media/a%20b.jpg", request.url.encodedPath)
        assertEquals(query, request.url.encodedQuery)
        assertNull(request.header("Authorization"))
        assertNull(request.header("X-Install-Id"))
        assertTrue(ImageRequests.client("http://10.0.2.2:8081/v1").interceptors.isEmpty())
    }

    @Test
    fun onlyLocalEmulatorBuildRoutesLocalImageSocketsToItsHost() {
        val fallbackAddress = InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1))
        val names = mutableListOf<String>()
        val fallback =
            object : Dns {
                override fun lookup(hostname: String): List<InetAddress> {
                    names += hostname
                    return listOf(fallbackAddress)
                }
            }
        val local = ImageDns("http://10.0.2.2:8081/v1", fallback)
        assertEquals("10.0.2.2", local.lookup("localhost").single().hostAddress)
        assertEquals("10.0.2.2", local.lookup("127.0.0.1").single().hostAddress)
        assertEquals(fallbackAddress, local.lookup("images.example.test").single())
        assertEquals(listOf("images.example.test"), names)
        val production = ImageDns("https://api.example.test/v1", fallback)
        assertEquals(fallbackAddress, production.lookup("localhost").single())
    }
}
