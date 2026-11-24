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
import com.xemantic.annie.Inference.Tokens
import com.xemantic.ai.money.Money
import com.xemantic.ai.money.Ratio
import com.xemantic.ai.money.ZERO
import java.math.BigDecimal

/**
 * The prices of the tokens of a model, in US dollars, by their category, computed exactly, without floating point.
 *
 * @param perMillion the price of a million tokens of each category, e.g. `"8"`.
 */
class Pricing(perMillion: Map<Tokens, BigDecimal>) {

    private val perToken = perMillion.mapValues { (_, price) -> Money.Ratio(price.movePointLeft(6).toPlainString()) }

    /** The cost of the [tokens], or `null` when a category of them has no price. */
    fun cost(tokens: Map<Tokens, Long>): Money? = tokens.entries.fold(Money.ZERO as Money?) { cost, (category, count) ->
        val price = perToken[category] ?: return null
        cost?.plus(Money(count.toString()) * price)
    }

    companion object {

        /**
         * The pricing of a Claude model served by Anthropic, the cache being written at 1.25 times the [input] price
         * for 5 minutes, also when the lifetime is not reported, and at 2 times for 1 hour,
         * the reasoning being output.
         */
        fun claude(input: String, output: String, cacheRead: String): Pricing {
            val base = BigDecimal(input)
            return Pricing(mapOf(
                Tokens.INPUT to base,
                Tokens.CACHE_READ to BigDecimal(cacheRead),
                Tokens.CACHE_WRITE to base * BigDecimal("1.25"),
                Tokens.CACHE_WRITE_5M to base * BigDecimal("1.25"),
                Tokens.CACHE_WRITE_1H to base * BigDecimal(2),
                Tokens.REASONING to BigDecimal(output),
                Tokens.OUTPUT to BigDecimal(output)
            ))
        }

    }

}

/**
 * The list prices of the standard tier, by the provider and the canonical id of the model,
 * the costs they give being the ones Claude Code reports for the same usage.
 * Discounts, e.g. of batches, and premiums, e.g. of fast mode or of data residency, are not considered.
 */
val prices: Map<Pair<String, String>, Pricing> = mapOf(
    ("anthropic" to "claude-fable-5-2") to Pricing.claude(input = "8", output = "40", cacheRead = "0.20"),
    ("anthropic" to "claude-fable-5-1") to Pricing.claude(input = "10", output = "50", cacheRead = "0.25"),
    ("anthropic" to "claude-opus-5-5") to Pricing.claude(input = "4", output = "20", cacheRead = "0.20"),
    ("anthropic" to "claude-sonnet-5-5") to Pricing.claude(input = "2", output = "10", cacheRead = "0.10")
)

/** The cost of this inference in US dollars at the list [prices], or `null` when its model or provider has none. */
val Inference.cost: Money? get() = prices[provider to model]?.cost(tokens)
