package com.arcticfoxapps.peeriche

import java.net.IDN
import java.net.InetAddress
import java.security.KeyStore
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager

internal fun broadcastDnsMatches(hostname: String, certificateName: String): Boolean {
    val host = runCatching { IDN.toASCII(hostname.trimEnd('.')).lowercase() }.getOrNull() ?: return false
    val name = certificateName.trimEnd('.').lowercase()
    if ('*' !in name) return host == name
    if (!name.startsWith("*.") || name.count { it == '*' } != 1) return false
    val suffix = name.substring(2)
    if ('.' !in suffix || !host.endsWith(".$suffix")) return false
    return host.substring(0, host.length - suffix.length - 1).let { it.isNotBlank() && '.' !in it }
}

/** RootEncoder uses raw SSLSocket. Add hostname checks to the platform chain validation. */
internal fun broadcastTlsTrust(host: String): X509TrustManager {
    val factory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).apply { init(null as KeyStore?) }
    val platform = factory.trustManagers.filterIsInstance<X509TrustManager>().first()
    val ip = host.contains(':') || host.matches(Regex("[0-9]+(?:\\.[0-9]+){3}"))
    return object : X509TrustManager {
        override fun getAcceptedIssuers() = platform.acceptedIssuers
        override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) = platform.checkClientTrusted(chain, authType)
        override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {
            platform.checkServerTrusted(chain, authType)
            val matches = chain.firstOrNull()?.subjectAlternativeNames.orEmpty().any { entry ->
                if (entry.size < 2) false
                else if (!ip && entry[0] == 2) broadcastDnsMatches(host, entry[1] as? String ?: "")
                else if (ip && entry[0] == 7) runCatching {
                    val value = entry[1]
                    val address = if (value is ByteArray) InetAddress.getByAddress(value) else InetAddress.getByName(value as String)
                    InetAddress.getByName(host).address.contentEquals(address.address)
                }.getOrDefault(false) else false
            }
            if (!matches) throw CertificateException("RTMPS certificate hostname mismatch")
        }
    }
}
