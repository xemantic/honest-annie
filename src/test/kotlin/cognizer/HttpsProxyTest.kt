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

import com.xemantic.kotlin.test.assert
import kotlin.test.Test

class HttpsProxyTest {

    @Test
    fun `should not use a proxy when none is set`() {
        // when
        val proxy = httpsProxy("api.fireworks.ai", env = emptyMap())

        // then
        assert(proxy == null)
    }

    @Test
    fun `should read the proxy with its credentials from HTTPS_PROXY`() {
        // when
        val proxy = httpsProxy(
            "api.fireworks.ai",
            env = mapOf("HTTPS_PROXY" to "http://agent:s3cr%40t@pier-egress-proxy:8080")
        )

        // then
        assert(proxy == HttpsProxy(host = "pier-egress-proxy", port = 8080, user = "agent", password = "s3cr@t"))
    }

    @Test
    fun `should read the lowercase https_proxy, defaulting the port of a scheme`() {
        // when
        val proxy = httpsProxy("api.fireworks.ai", env = mapOf("https_proxy" to "http://proxy.example"))

        // then
        assert(proxy == HttpsProxy(host = "proxy.example", port = 80, user = null, password = null))
    }

    @Test
    fun `should not use the proxy for a host matching NO_PROXY`() {
        // given
        val env = mapOf(
            "HTTPS_PROXY" to "http://proxy.example:3128",
            "NO_PROXY" to "localhost, .internal,api.example.com"
        )

        // then
        assert(httpsProxy("localhost", env) == null)
        assert(httpsProxy("models.internal", env) == null)
        assert(httpsProxy("api.example.com", env) == null)
        assert(httpsProxy("api.fireworks.ai", env) != null)
    }

    @Test
    fun `should not use any proxy when NO_PROXY is a wildcard`() {
        // when
        val proxy = httpsProxy(
            "api.fireworks.ai",
            env = mapOf("HTTPS_PROXY" to "http://proxy.example:3128", "no_proxy" to "*")
        )

        // then
        assert(proxy == null)
    }

}
