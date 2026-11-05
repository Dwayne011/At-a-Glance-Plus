package com.ataglanceplus.live

import kotlin.math.max

/**
 * The stock screen's "What if": the user's eToro positions in one stock at a price they choose.
 *
 * Each position's profit/loss is its units times the move from its open price to the chosen
 * price: up for a buy, down for a sell. This is worked out from the position alone, so it does not
 * depend on which feed's price is "now": anchoring on eToro's current P/L and moving it by the
 * gap between the chosen price and Yahoo's price was off by the units times any difference
 * between eToro's and Yahoo's prices (about $365 a cent on 36,500 units). The percentage is
 * against the margin put in, as eToro shows it; a position cannot be worth less than nothing.
 * eToro's own figure can differ by its spread (it values a buy at the bid) and fees, which are
 * not added.
 */
object WhatIf {

    /** A position at the chosen price. */
    class Leg(val position: EtoroSource.Position, val pnl: Double) {
        val value: Double get() = max(0.0, position.amount + pnl)
        val percent: Double get() = if (position.amount != 0.0) 100 * pnl / position.amount else 0.0
    }

    /** All the positions at the chosen price, and their totals. */
    class Result(val legs: List<Leg>) {
        val pnl: Double get() = legs.sumOf { it.pnl }
        val value: Double get() = legs.sumOf { it.value }
        val margin: Double get() = legs.sumOf { it.position.amount }
        val percent: Double get() = if (margin != 0.0) 100 * pnl / margin else 0.0
    }

    private fun direction(p: EtoroSource.Position) = if (p.isBuy) 1.0 else -1.0

    /** [positions] at [price]. [now] is kept for callers; the result does not depend on it. */
    @Suppress("UNUSED_PARAMETER")
    fun at(positions: List<EtoroSource.Position>, now: Double, price: Double): Result =
        Result(positions.map { Leg(it, (price - it.openRate) * it.units * direction(it)) })

    /** How much the whole lot gains for each dollar the price rises (negative when net short). */
    fun exposure(positions: List<EtoroSource.Position>): Double = positions.sumOf { it.units * direction(it) }

    /**
     * The price at which [positions] together are back to no profit or loss: their open prices
     * weighted by units (sells counting against). Null when it cannot be (no net units).
     */
    @Suppress("UNUSED_PARAMETER")
    fun breakEven(positions: List<EtoroSource.Position>, now: Double): Double? {
        val units = exposure(positions)
        if (kotlin.math.abs(units) < 1e-9) return null
        return (positions.sumOf { it.openRate * it.units * direction(it) } / units).takeIf { it > 0 }
    }
}
