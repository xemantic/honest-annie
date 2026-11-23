/*
 * honest-annie - A recursive harness for cognizers acting through Kotlin scripts
 * Copyright (C) 2026  Kazimierz Pogoda / Xemantic
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package com.xemantic.ai.annie.cognizer

import java.net.InetSocketAddress
import java.net.Proxy
import java.net.URI
import java.net.URLDecoder

/**
 * The HTTP proxy tunneling the HTTPS connections to a cognizer, with its basic credentials, when given.
 *
 * The JVM ignores the `HTTPS_PROXY` environment variable honored by curl and most other clients,
 * so an egress allowing only an authenticated proxy, e.g. the one of a sandbox, would leave the cognizer unreachable.
 */
data class HttpsProxy(
    val host: String,
    val port: Int,
    val user: String?,
    val password: String?
) {

    /** The proxy as the HTTP clients of the SDKs take it, the address resolved when connecting. */
    val proxy: Proxy get() = Proxy(Proxy.Type.HTTP, InetSocketAddress.createUnresolved(host, port))

}

/**
 * The proxy of `HTTPS_PROXY`, or of `https_proxy`, to connect to the [targetHost] through,
 * unless the host matches `NO_PROXY`, or `no_proxy`: a comma-separated list of hosts and domain suffixes, or `*`.
 */
fun httpsProxy(
    targetHost: String,
    env: Map<String, String> = System.getenv()
): HttpsProxy? {
    val url = (env["HTTPS_PROXY"] ?: env["https_proxy"])?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    val noProxy = (env["NO_PROXY"] ?: env["no_proxy"]).orEmpty()
        .split(',').map { it.trim().lowercase() }.filter { it.isNotEmpty() }
    val host = targetHost.lowercase()
    if (noProxy.any { it == "*" || host == it.removePrefix(".") || host.endsWith(if (it.startsWith('.')) it else ".$it") }) {
        return null
    }
    val uri = URI(if ("://" in url) url else "http://$url")
    val credentials = uri.rawUserInfo?.split(':', limit = 2)?.map { URLDecoder.decode(it, Charsets.UTF_8) }
    return HttpsProxy(
        host = checkNotNull(uri.host) { "No host in the proxy URL of HTTPS_PROXY" },
        port = if (uri.port != -1) uri.port else if (uri.scheme == "https") 443 else 80,
        user = credentials?.getOrNull(0),
        password = credentials?.getOrNull(1)
    )
}
