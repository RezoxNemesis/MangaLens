package com.mangalens.orez.research

import com.mangalens.core.router.UrlEngineRouter
import okhttp3.Dns
import okhttp3.HttpUrl
import java.net.InetAddress
import java.net.Proxy
import java.net.ProxySelector
import java.net.SocketAddress
import java.net.URI
import java.net.UnknownHostException

internal object OrezResearchPublicNetworkPolicy {
    fun requireUrl(url: HttpUrl) {
        require(url.toString().length <= 1024 && UrlEngineRouter.isSafeWebUrl(url.toString()) && url.username.isEmpty() && url.password.isEmpty() &&
            url.fragment == null && '%' !in url.host && url.port == if (url.isHttps) 443 else 80) { "Research supports public HTTP(S) sources on their default ports." }
        // OkHttp can bypass Dns for a literal; admission must cover that actual route too.
        if (':' in url.host || url.host.matches(Regex("[0-9.]+"))) require(isPublic(InetAddress.getByName(url.host))) { "Research refuses a private source address." }
    }
    fun isPublic(address: InetAddress): Boolean {
        val b = address.address.map { it.toInt() and 255 }
        if (b.size == 4) {
            val a = b[0]; val c = b[1]
            return a !in setOf(0, 10, 127) && a < 224 && !(a == 100 && c in 64..127) && !(a == 169 && c == 254) &&
                !(a == 172 && c in 16..31) && !(a == 192 && (c == 168 || c == 0 && b[2] in setOf(0, 2) || c == 88 && b[2] == 99)) &&
                !(a == 198 && (c in 18..19 || c == 51 && b[2] == 100)) && !(a == 203 && c == 0 && b[2] == 113)
        }
        if (b.size != 16) return false
        if (b.take(10).all { it == 0 } && b[10] == 255 && b[11] == 255) return isPublic(InetAddress.getByAddress(address.address.copyOfRange(12, 16)))
        if (b.take(12) == listOf(0, 100, 255, 155, 0, 0, 0, 0, 0, 0, 0, 0)) return isPublic(InetAddress.getByAddress(address.address.copyOfRange(12, 16)))
        if (b[0] and 0xe0 != 0x20) return false
        if (b[0] == 0x20 && b[1] == 1 && (b[2] == 0x0d && b[3] == 0xb8 || b[2] == 0 && (b[3] == 0 || (b[3] and 0xf0) in setOf(0x10, 0x20)))) return false
        // RFC 5180 benchmark /48 and RFC 9637 documentation /20 are not public source routes.
        if (b.take(6) == listOf(0x20, 1, 0, 2, 0, 0) || b[0] == 0x3f && b[1] == 0xff && (b[2] and 0xf0) == 0) return false
        if (b[0] == 0x20 && b[1] == 2) return isPublic(InetAddress.getByAddress(address.address.copyOfRange(2, 6)))
        return true
    }
    fun guardedDns(delegate: Dns = Dns.SYSTEM): Dns = object : Dns {
        override fun lookup(hostname: String): List<InetAddress> {
            val addresses = delegate.lookup(hostname)
            if (addresses.isEmpty() || addresses.any { !isPublic(it) }) throw UnknownHostException("Research source has no permitted public address.")
            return addresses
        }
    }
    fun directOnlyProxy(delegate: ProxySelector? = ProxySelector.getDefault()) = object : ProxySelector() {
        override fun select(uri: URI): List<Proxy> {
            val selected = delegate?.select(uri).orEmpty().ifEmpty { listOf(Proxy.NO_PROXY) }
            require(selected.all { it.type() == Proxy.Type.DIRECT }) { "This research route cannot verify public targets through the configured proxy." }
            return selected
        }
        override fun connectFailed(uri: URI, sa: SocketAddress, failure: java.io.IOException) { delegate?.connectFailed(uri, sa, failure) }
    }
}
