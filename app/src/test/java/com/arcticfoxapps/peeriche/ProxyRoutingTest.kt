package com.arcticfoxapps.peeriche

import okhttp3.Credentials
import okhttp3.Dns
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.Assert.*
import org.junit.Test
import java.io.BufferedInputStream
import java.io.Closeable
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit

class ProxyRoutingTest {
    private class Fixture(val serve: (Socket) -> Unit) : Closeable {
        val server = ServerSocket(0, 10, java.net.InetAddress.getByName("127.0.0.1"))
        val port get() = server.localPort
        @Volatile var failure: Throwable? = null
        private val worker = Thread {
            try { while (!server.isClosed) server.accept().use { it.soTimeout = 3000; serve(it) } }
            catch (error: Throwable) { if (!server.isClosed) failure = error }
        }.apply { isDaemon = true; start() }
        override fun close() { server.close(); worker.join(1000); failure?.let { throw AssertionError(it) } }
    }
    private fun line(input: java.io.InputStream): String {
        val bytes = java.io.ByteArrayOutputStream()
        while (true) { val byte = input.read(); if (byte < 0 || byte == 10) break; if (byte != 13) bytes.write(byte); check(bytes.size() < 8192) }
        return bytes.toString("UTF-8")
    }
    private fun request(input: java.io.InputStream): List<String> {
        val lines = mutableListOf(line(input))
        while (true) { val next = line(input); if (next.isEmpty()) return lines; lines += next }
    }
    private fun reply(socket: Socket, code: Int = 200, body: String = "proxied", extra: String = "") {
        socket.getOutputStream().write("HTTP/1.1 $code OK\r\nContent-Length: ${body.toByteArray().size}\r\nConnection: close\r\n$extra\r\n$body".toByteArray())
    }
    private fun client() = OkHttpClient.Builder().connectTimeout(2, TimeUnit.SECONDS).readTimeout(3, TimeUnit.SECONDS)
        .callTimeout(5, TimeUnit.SECONDS).dns(object : Dns {
            override fun lookup(hostname: String): List<java.net.InetAddress> = throw java.net.UnknownHostException("Origin DNS must stay at the proxy")
        }).build()

    @Test fun validatesEndpointAndKeepsPasswordsOutOfDiagnostics() {
        assertEquals("proxy.example", ProxyConfiguration("http", " PROXY.example ", 8080).checked().host)
        assertEquals("::1", ProxyConfiguration("socks5", "[::1]", 1080).checked().host)
        for (host in listOf("", "http://proxy.example", "user:password@proxy.example", "proxy.example/path", "proxy example"))
            assertTrue(host, runCatching { ProxyConfiguration("http", host, 8080).checked() }.isFailure)
        for (port in listOf(0, -1, 65536)) assertTrue(runCatching { ProxyConfiguration("http", "proxy.example", port).checked() }.isFailure)
        assertTrue(runCatching { ProxyConfiguration("http", "proxy.example", 80, password = "secret").checked() }.isFailure)
        assertFalse(ProxyConfiguration("http", "proxy.example", 80, "user", "secret").toString().contains("secret"))
    }

    @Test(timeout = 10000) fun cachedFactoryUsesNewProxyConfigurationAndRemoteOriginDns() {
        val requests = CopyOnWriteArrayList<List<String>>()
        Fixture { socket -> requests += request(BufferedInputStream(socket.getInputStream())); reply(socket) }.use { proxy ->
            var config = ProxyConfiguration()
            val router = ProxyRouter({ config })
            val factory = router.calls(client())
            config = ProxyConfiguration("http", "127.0.0.1", proxy.port)
            factory.newCall(Request.Builder().url("http://unresolvable.example/video").build()).execute().use { assertEquals("proxied", it.body!!.string()) }
            assertEquals("GET http://unresolvable.example/video HTTP/1.1", requests.single().first())
            config = ProxyConfiguration()
            assertTrue(runCatching { factory.newCall(Request.Builder().url("http://unresolvable.example/video").build()).execute().close() }.isFailure)
            assertEquals(1, requests.size)
        }
    }

    @Test(timeout = 10000) fun httpAuthenticationIsSentToProxyAndDoesNotReplaceServerAuthorization() {
        val requests = CopyOnWriteArrayList<List<String>>()
        val expected = Credentials.basic("proxy-user", "proxy-password", Charsets.UTF_8)
        Fixture { socket ->
            val headers = request(BufferedInputStream(socket.getInputStream())); requests += headers
            if (headers.none { it == "Proxy-Authorization: $expected" }) reply(socket, 407, "", "Proxy-Authenticate: Basic realm=\"fixture\"\r\n")
            else reply(socket)
        }.use { proxy ->
            val router = ProxyRouter({ ProxyConfiguration("http", "127.0.0.1", proxy.port, "proxy-user", "proxy-password") })
            router.calls(client()).newCall(Request.Builder().url("http://origin.example/api/v1/users/me").header("Authorization", "Bearer account-fixture").build())
                .execute().use { assertEquals(200, it.code) }
            assertEquals(2, requests.size)
            assertTrue(requests.last().contains("Authorization: Bearer account-fixture"))
            assertTrue(requests.last().contains("Proxy-Authorization: $expected"))
        }
    }

    @Test(timeout = 10000) fun httpsAuthenticatesConnectWithoutSendingOriginRequestToUnverifiedTunnel() {
        val requests = CopyOnWriteArrayList<List<String>>()
        Fixture { socket -> requests += request(BufferedInputStream(socket.getInputStream())); reply(socket, 403, "") }.use { proxy ->
            val router = ProxyRouter({ ProxyConfiguration("http", "127.0.0.1", proxy.port, "user", "secret") })
            assertTrue(runCatching { router.calls(client()).newCall(Request.Builder().url("https://origin.example/video").build()).execute().close() }.isFailure)
            assertEquals("CONNECT origin.example:443 HTTP/1.1", requests.single().first())
            assertTrue(requests.single().any { it == "Proxy-Authorization: ${Credentials.basic("user", "secret", Charsets.UTF_8)}" })
            assertTrue(requests.single().none { it.startsWith("GET ") || it.startsWith("Authorization:") })
        }
    }

    @Test(timeout = 10000) fun unavailableProxyNeverFallsBackToDirectAndUnreadableSettingsBlockTraffic() {
        val requests = CopyOnWriteArrayList<List<String>>()
        Fixture { socket -> requests += request(BufferedInputStream(socket.getInputStream())); reply(socket) }.use { origin ->
            val closedPort = ServerSocket(0).use { it.localPort }
            val router = ProxyRouter({ ProxyConfiguration("http", "127.0.0.1", closedPort) })
            val url = "http://127.0.0.1:${origin.port}/"
            assertTrue(runCatching { router.calls(OkHttpClient()).newCall(Request.Builder().url(url).build()).execute().close() }.isFailure)
            assertTrue(requests.isEmpty())
            val blocked = ProxyRouter({ ProxyConfiguration() }, { false })
            assertTrue(runCatching { blocked.calls(OkHttpClient()).newCall(Request.Builder().url(url).build()) }.isFailure)
            assertTrue(requests.isEmpty())
        }
    }

    @Test(timeout = 10000) fun socks5SupportsPasswordAuthenticationAndUnresolvedDestination() {
        val previous = java.net.Authenticator::class.java.getMethod("getDefault").invoke(null) as java.net.Authenticator?
        val destinations = CopyOnWriteArrayList<String>()
        Fixture { socket ->
            val input = BufferedInputStream(socket.getInputStream()); val out = socket.getOutputStream()
            assertEquals(5, input.read()); val methods = ByteArray(input.read()); check(input.read(methods) == methods.size)
            assertTrue(methods.contains(2)); out.write(byteArrayOf(5, 2)); out.flush()
            assertEquals(1, input.read()); val user = ByteArray(input.read()); check(input.read(user) == user.size)
            val password = ByteArray(input.read()); check(input.read(password) == password.size)
            assertEquals("user", user.toString(Charsets.UTF_8)); assertEquals("secret", password.toString(Charsets.UTF_8))
            out.write(byteArrayOf(1, 0)); out.flush()
            assertEquals(5, input.read()); assertEquals(1, input.read()); assertEquals(0, input.read()); assertEquals(3, input.read())
            val host = ByteArray(input.read()); check(input.read(host) == host.size); destinations += host.toString(Charsets.UTF_8)
            input.read(); input.read(); out.write(byteArrayOf(5, 0, 0, 1, 127, 0, 0, 1, 0, 80)); out.flush()
            request(input); reply(socket)
        }.use { proxy ->
            val config = ProxyConfiguration("socks5", "127.0.0.1", proxy.port, "user", "secret")
            java.net.Authenticator.setDefault(SocksProxyAuthenticator { config })
            try {
                ProxyRouter({ config }).calls(client()).newCall(Request.Builder().url("http://socks-origin.example/video").build())
                    .execute().use { assertEquals("proxied", it.body!!.string()) }
                assertEquals(listOf("socks-origin.example"), destinations)
            } finally { java.net.Authenticator.setDefault(previous) }
        }
    }
}
