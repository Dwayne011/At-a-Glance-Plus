package com.ataglanceplus.live

import kotlin.math.max

/**
 * The stock screen's "What if": the user's eToro positions in one stock at a price they choose.
 *
 * Each position's profit/loss starts from eToro's own current figure (so at today's price it
 * matches eToro exactly, its spread and fees included) and moves by its units for each dollar the
 * price moves: up for a buy, down for a sell. [now] is the price that figure belongs to. The
 * percentage is against the margin put in, as eToro shows it; a position cannot be worth less than
 * nothing. Fees that grow with time (overnight fees) are not added.
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

    /** [positions] at [price], when eToro's figures are for [now]. */
    fun at(positions: List<EtoroSource.Position>, now: Double, price: Double): Result =
        Result(positions.map { Leg(it, it.pnl + (price - now) * it.units * direction(it)) })

    /** How much the whole lot gains for each dollar the price rises (negative when net short). */
    fun exposure(positions: List<EtoroSource.Position>): Double = positions.sumOf { it.units * direction(it) }

    /** The price at which [positions] together are back to no profit or loss, or null when it cannot be (no net units). */
    fun breakEven(positions: List<EtoroSource.Position>, now: Double): Double? {
        val units = exposure(positions)
        if (kotlin.math.abs(units) < 1e-9) return null
        return (now - positions.sumOf { it.pnl } / units).takeIf { it > 0 }
    }
}
