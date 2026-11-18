package com.arcticfoxapps.peeriche

import okhttp3.Authenticator
import okhttp3.Call
import okhttp3.ConnectionPool
import okhttp3.Credentials
import okhttp3.Dns
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.net.InetSocketAddress
import java.net.PasswordAuthentication
import java.net.Proxy
import java.util.WeakHashMap

internal data class ProxyConfiguration(val type: String = "off", val host: String = "", val port: Int = 0,
    val username: String = "", val password: String = "") {
    fun checked(): ProxyConfiguration {
        require(type in listOf("off", "http", "socks5"))
        if (type == "off") return copy(host = host.trim(), username = username.trim())
        require(port in 1..65535)
        val address = host.trim().removeSurrounding("[", "]")
        require(address.isNotEmpty() && address.none { it.isWhitespace() || it in "/\\@?#" })
        val canonical = okhttp3.HttpUrl.Builder().scheme("https").host(address).build().host
        val user = username.trim()
        require(user.none(Char::isISOControl) && password.none { it == '\r' || it == '\n' })
        require(password.isEmpty() || user.isNotEmpty())
        if (type == "socks5") require(user.toByteArray().size <= 255 && password.toByteArray().size <= 255)
        return copy(host = canonical, username = user)
    }
    override fun toString() = "ProxyConfiguration(type=$type, host=$host, port=$port, authenticated=${username.isNotEmpty()})"
}

/** Each new call reads the current configuration, even for cached Retrofit/Coil clients. */
internal class ProxyRouter(private val configuration: () -> ProxyConfiguration, private val readable: () -> Boolean = { true }) {
    private companion object {
        val cleanup = java.util.concurrent.Executors.newSingleThreadExecutor { task ->
            Thread(task, "PeerIche proxy pool cleanup").apply { isDaemon = true }
        }
    }
    private val clients = WeakHashMap<OkHttpClient, OkHttpClient>()
    private var active: ProxyConfiguration? = null
    private var pool = ConnectionPool()
    private var authenticator: Authenticator = Authenticator.NONE

    @Synchronized fun client(base: OkHttpClient): OkHttpClient {
        if (!readable()) throw IOException("Proxy settings unavailable")
        val config = configuration().checked()
        if (config != active) {
            clients.clear()
            val retired = pool
            pool = ConnectionPool(); active = config
            // Closing idle TLS sockets can perform I/O; newCall is also invoked from the UI thread.
            cleanup.execute { retired.evictAll() }
            authenticator = if (config.type == "http" && config.username.isNotEmpty()) Authenticator { route, response ->
                // This also covers OkHttp's pre-emptive CONNECT challenge. Never answer an origin's 401.
                if (route?.proxy?.type() != Proxy.Type.HTTP || response.request.header("Proxy-Authorization") != null) null
                else response.request.newBuilder().header("Proxy-Authorization", Credentials.basic(config.username, config.password, Charsets.UTF_8)).build()
            } else Authenticator.NONE
        }
        return clients.getOrPut(base) {
            val proxy = when (config.type) {
                "http" -> Proxy(Proxy.Type.HTTP, InetSocketAddress.createUnresolved(config.host, config.port))
                "socks5" -> Proxy(Proxy.Type.SOCKS, InetSocketAddress.createUnresolved(config.host, config.port))
                else -> Proxy.NO_PROXY
            }
            val originalDns = base.dns
            base.newBuilder().proxy(proxy).proxyAuthenticator(authenticator).connectionPool(pool)
                .dns(object : Dns { override fun lookup(hostname: String): List<java.net.InetAddress> {
                    // A local proxy is valid even when the catalog client restricts target DNS to public addresses.
                    return if (config.type != "off" && hostname.equals(config.host, ignoreCase = true)) Dns.SYSTEM.lookup(hostname)
                    else originalDns.lookup(hostname)
                } }).build()
        }
    }
    fun calls(base: OkHttpClient): Call.Factory = object : Call.Factory {
        override fun newCall(request: Request): Call = client(base).newCall(request)
    }
}

/** Android's SOCKS implementation requests credentials through the process-local Java authenticator. */
internal class SocksProxyAuthenticator(private val configuration: () -> ProxyConfiguration) : java.net.Authenticator() {
    override fun getPasswordAuthentication(): PasswordAuthentication? {
        val config = configuration()
        if (config.type != "socks5" || config.username.isEmpty() || requestingPort != config.port ||
            !config.host.equals(requestingHost, ignoreCase = true)) return null
        return PasswordAuthentication(config.username, config.password.toCharArray())
    }
}
