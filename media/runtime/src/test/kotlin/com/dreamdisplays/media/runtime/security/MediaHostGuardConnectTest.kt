package com.dreamdisplays.media.runtime.security

import com.dreamdisplays.util.net.DreamHttpClient
import com.sun.net.httpserver.HttpServer
import java.net.InetAddress
import java.net.InetSocketAddress
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class MediaHostGuardConnectTest {
    private val server = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0).apply {
        createContext("/") { ex -> ex.sendResponseHeaders(200, 2); ex.responseBody.use { it.write("ok".toByteArray()) } }
        start()
    }
    private val url = "http://127.0.0.1:${server.address.port}/segment.ts"

    @AfterTest
    fun stop() = server.stop(0)

    @Test
    fun `guarded media fetches never reach a loopback or private address`() {
        assertEquals(200, DreamHttpClient.execute(url).code) // the endpoint itself answers
        assertFailsWith<MediaHostGuard.HostRefusedException> { DreamHttpClient.execute(url, MediaHostGuard.guarded(url)) }
        val byName = "http://localhost:${server.address.port}/segment.ts"
        assertFailsWith<MediaHostGuard.HostRefusedException> { DreamHttpClient.execute(byName, MediaHostGuard.guarded(byName)) }
    }

    @Test
    fun `this client's own loopback endpoints stay reachable`() {
        MediaHostGuard.trustLocalUrls { it.startsWith(url) }
        assertEquals(200, DreamHttpClient.execute(url, MediaHostGuard.guarded(url)).code)
    }
}
