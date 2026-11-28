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

package com.xemantic.annie.harness

import com.xemantic.annie.Inference
import com.xemantic.ai.money.Money
import com.xemantic.kotlin.test.assert
import kotlin.test.Test

class PricingTest {

    @Test
    fun `should cost inference of Claude Fable 5_2 as Claude Code does at list prices`() {
        // given
        // the usage of a whole run of Claude Code, which reported its cost as 0.9551768
        val inference = Inference(
            model = "claude-fable-5-2",
            provider = "anthropic",
            tokens = mapOf(
                "input" to 16L,
                "cacheRead" to 285_544L,
                "cacheWrite5m" to 41_574L,
                "reasoning" to 3_670L,
                "output" to 8_385L
            )
        )

        // when
        val cost = inference.cost

        // then
        assert(cost == Money("0.9551768"))
    }

    @Test
    fun `should cost inference of Claude Opus 5_5 as Claude Code does at list prices`() {
        // given
        // the usage of a whole run of Claude Code, which reported its cost as 0.7149498
        val inference = Inference(
            model = "claude-opus-5-5",
            provider = "anthropic",
            tokens = mapOf(
                "input" to 36L,
                "cacheRead" to 713_679L,
                "cacheWrite5m" to 45_870L,
                "reasoning" to 4_808L,
                "output" to 12_328L
            )
        )

        // when
        val cost = inference.cost

        // then
        assert(cost == Money("0.7149498"))
    }

    @Test
    fun `should cost inference of Claude Haiku 5_5 of a prompt of 100K tokens at the lower rate card`() {
        // when
        // the prompt, of the input, the cache read and the cache written, being 100,000 tokens
        val cost = Inference(
            model = "claude-haiku-5-5",
            provider = "anthropic",
            tokens = mapOf(
                "input" to 10L,
                "cacheRead" to 90_000L,
                "cacheWrite5m" to 9_990L,
                "output" to 1_000L
            )
        ).cost

        // then
        assert(cost == Money("0.00264975"))
    }

    @Test
    fun `should cost inference of Claude Haiku 5_5 of a prompt longer than 100K tokens at the higher rate card`() {
        // when
        val cost = Inference(
            model = "claude-haiku-5-5",
            provider = "anthropic",
            tokens = mapOf(
                "input" to 11L,
                "cacheRead" to 90_000L,
                "cacheWrite5m" to 9_990L,
                "output" to 1_000L
            )
        ).cost

        // then
        assert(cost == Money("0.01324925"))
    }

    @Test
    fun `should cost writing to cache living 1 hour twice the input`() {
        // when
        val cost = Inference(
            model = "claude-fable-5-2",
            provider = "anthropic",
            tokens = mapOf("cacheWrite1h" to 1_000L)
        ).cost

        // then
        assert(cost == Money("0.016"))
    }

    @Test
    fun `should not cost inference of token category without price`() {
        // when
        val cost = Inference(
            model = "claude-fable-5-2",
            provider = "anthropic",
            tokens = mapOf("input" to 1L, "audioInput" to 1L, "output" to 1L)
        ).cost

        // then
        assert(cost == null)
    }

    @Test
    fun `should not cost inference of model or provider of unknown prices`() {
        // when
        val unknownModel = Inference(model = "deepseek-v4_1-flash", provider = "llm.example.com", tokens = mapOf("input" to 1L)).cost
        val unknownProvider = Inference(model = "claude-fable-5-2", provider = "bedrock", tokens = mapOf("input" to 1L)).cost

        // then
        assert(unknownModel == null)
        assert(unknownProvider == null)
    }

}
