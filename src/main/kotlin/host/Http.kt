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

package com.xemantic.ai.annie.host

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpRequestRetry
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.UserAgent
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.json.Json

/**
 * The HTTP client of every cogitation of the JVM, see [com.xemantic.ai.annie.Agency.http],
 * created on first use, and shared, as a client is safe to use concurrently, and keeps its connections for reuse.
 * It is never closed, its threads being daemons not keeping the JVM alive.
 */
val annieHttpClient: HttpClient by lazy {
    HttpClient(CIO) {
        install(ContentNegotiation) {
            json(Json { ignoreUnknownKeys = true })
        }
        // public APIs limit the rate of requests, and fail now and then, so scripts need not retry on their own
        install(HttpRequestRetry) {
            retryIf(maxRetries = 5) { _, response ->
                response.status.value == 429 || response.status.value >= 500
            }
            // a request timing out is not retried, as it would take minutes, see HttpTimeout
            retryOnException(maxRetries = 5, retryOnTimeout = false)
            exponentialDelay(respectRetryAfterHeader = true)
        }
        install(HttpTimeout) {
            requestTimeoutMillis = 60_000
        }
        // some APIs, e.g. the one of GitHub, refuse requests without it
        install(UserAgent) {
            agent = "honest-annie"
        }
        expectSuccess = true
    }
}
