package com.dreamdisplays.util.net

import com.sun.net.httpserver.HttpServer
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class DreamHttpClientAddressPolicyTest {
    private val server = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0).apply {
        createContext("/ok") { ex -> ex.sendResponseHeaders(200, 2); ex.responseBody.use { it.write("ok".toByteArray()) } }
        createContext("/hop") { ex ->
            // Redirects to the same server under a name the test policy refuses
            ex.responseHeaders.add("Location", "http://localhost:${address.port}/ok")
            ex.sendResponseHeaders(302, -1); ex.close()
        }
        start()
    }
    private val base = "http://127.0.0.1:${server.address.port}"
    private val seen = CopyOnWriteArrayList<String>()

    /** Allows the IP literal, refuses anything reached through the name `localhost`. */
    private val policy = DreamHttpClient.AddressPolicy { host, address ->
        seen += "$host=${address.hostAddress}"
        if (host == "localhost") throw IOException("refused $host")
    }

    @AfterTest
    fun stop() = server.stop(0)

    @Test
    fun `an ip-literal host is checked on the connection it actually made`() {
        val response = DreamHttpClient.execute("$base/ok", DreamHttpClient.RequestOptions(addressPolicy = policy))
        assertEquals(200, response.code)
        assertTrue(seen.any { it.startsWith("127.0.0.1=") }, seen.toString())
        val refuseAll = DreamHttpClient.AddressPolicy { _, _ -> throw IOException("refused") }
        assertFailsWith<IOException> { DreamHttpClient.execute("$base/ok", DreamHttpClient.RequestOptions(addressPolicy = refuseAll)) }
    }

    @Test
    fun `a redirect hop goes through the policy too`() {
        assertFailsWith<IOException> { DreamHttpClient.execute("$base/hop", DreamHttpClient.RequestOptions(addressPolicy = policy)) }
        // Without a policy the same redirect is followed
        assertEquals(200, DreamHttpClient.execute("$base/hop").code)
    }
}
