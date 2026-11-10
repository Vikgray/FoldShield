package com.foldshield.blocker

import java.util.Locale
import kotlin.math.max
import kotlin.math.min

data class TradeAction(
    val title: String,
    val description: String,
    val priority: Int,
    val typeId: Int = 0,
    val volumeAtBest: Int = 0,
    val buyPrice: Double = 0.0,
    val sellPrice: Double = 0.0,
    val netProfitPerUnit: Double = 0.0,
    val netRoiPercent: Double = 0.0,
    val estimatedProfitIsk: Double = 0.0
)

object TradePlanManager {
    fun generatePlan(
        summaries: List<ItemOrderBookSummary>,
        capitalIsk: Double = 0.0
    ): List<TradeAction> {
        val actions = mutableListOf<TradeAction>()

        val profitable = summaries
            .filter { it.bestBuy > 0 && it.bestSell > 0 && it.netRoiPercent >= 6.0 && it.avgDailyVolume7d >= 3 }
            .sortedByDescending { it.netProfitPerUnit * min(it.avgDailyVolume7d, 10L) }

        if (profitable.isEmpty()) {
            actions.add(
                TradeAction(
                    title = "⏸ Пауза рынка",
                    description = "Нет позиций с Net ROI ≥ 6% и оборотом от 3 шт/д. Ждём движения стаканов.",
                    priority = 2
                )
            )
            return actions
        }

        val maxAllocationPerItem = if (capitalIsk > 0) capitalIsk * 0.15 else 3_000_000_000.0

        profitable.take(3).forEach { s ->
            val maxByCapital = max(1, (maxAllocationPerItem / (s.bestBuy * 1.005)).toInt())
            val maxByDailyVol = max(1, (s.avgDailyVolume7d * 0.25).toInt())
            val recommendedQty = min(maxByCapital, min(maxByDailyVol, 25))
            val profitTotal = s.netProfitPerUnit * recommendedQty
            val roiFmt = String.format(Locale.US, "%.1f", s.netRoiPercent)

            actions.add(
                TradeAction(
                    title = "🛒 ${s.item.name}",
                    description = "Buy (Perimeter): ${formatIsk(s.bestBuy)} → Sell (Jita 4-4): ${formatIsk(s.bestSell)} ISK\n" +
                            "Чистый профит: +${formatIsk(s.netProfitPerUnit)}/шт (ROI +$roiFmt%, оборот ${s.avgDailyVolume7d}/д)",
                    priority = 1,
                    typeId = s.item.typeId,
                    volumeAtBest = recommendedQty,
                    buyPrice = s.bestBuy,
                    sellPrice = s.bestSell,
                    netProfitPerUnit = s.netProfitPerUnit,
                    netRoiPercent = s.netRoiPercent,
                    estimatedProfitIsk = profitTotal
                )
            )
        }

        return actions
    }
}