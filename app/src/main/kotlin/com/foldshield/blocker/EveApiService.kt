package com.foldshield.blocker

import android.content.Context
import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.POST
import retrofit2.http.Path
import retrofit2.http.Query
import java.text.DecimalFormat
import java.util.Locale

data class EsiMarketOrder(
    @SerializedName("order_id") val orderId: Long,
    @SerializedName("type_id") val typeId: Int,
    @SerializedName("location_id") val locationId: Long,
    @SerializedName("volume_remain") val volumeRemain: Int,
    @SerializedName("price") val price: Double,
    @SerializedName("is_buy_order") val isBuyOrder: Boolean,
    @SerializedName("range") val range: String
)

data class EsiCharacterOrder(
    @SerializedName("order_id") val orderId: Long,
    @SerializedName("type_id") val typeId: Int,
    @SerializedName("location_id") val locationId: Long,
    @SerializedName("volume_remain") val volumeRemain: Int,
    @SerializedName("volume_total") val volumeTotal: Int,
    @SerializedName("price") val price: Double,
    @SerializedName("is_buy_order") val isBuyOrder: Boolean? = false,
    @SerializedName("escrow") val escrow: Double? = null
)

data class EsiAssetItem(
    @SerializedName("item_id") val itemId: Long,
    @SerializedName("type_id") val typeId: Int,
    @SerializedName("location_id") val locationId: Long,
    @SerializedName("location_flag") val locationFlag: String,
    @SerializedName("quantity") val quantity: Int,
    @SerializedName("is_singleton") val isSingleton: Boolean? = false
)

data class EsiHistoryEntry(
    @SerializedName("date") val date: String,
    @SerializedName("volume") val volume: Long,
    @SerializedName("average") val average: Double,
    @SerializedName("highest") val highest: Double,
    @SerializedName("lowest") val lowest: Double
)

data class EsiIdName(
    @SerializedName("id") val id: Int,
    @SerializedName("name") val name: String
)

data class EsiUniverseIdsResponse(
    @SerializedName("inventory_types") val inventoryTypes: List<EsiIdName>?
)

enum class TradeCategory(val title: String) {
    ACTIVE("⭐ Активные"),
    IMPLANTS("🧠 Импланты"),
    SHIELD("🛡️ Щиты"),
    ARMOR("🔧 Броня"),
    EW_PROP("⚡ ПвП / РЭБ"),
    HULLS_CONS("📦 Расходники / Шипы"),
    ALL("🌐 Все 100")
}

data class WatchedItem(
    val typeId: Int,
    val name: String,
    val category: TradeCategory,
    val myCostBasis: Double? = null,
    val regionId: Int = 10000002
)

data class ItemOrderBookSummary(
    val item: WatchedItem,
    val topSellsJita: List<Pair<Double, Int>>,
    val topBuysForge: List<Pair<Double, Int>>,
    val avgDailyVolume7d: Long,
    val bestSell: Double,
    val bestBuy: Double,
    val netProfitPerUnit: Double,
    val netRoiPercent: Double
) {
    val firstWallVolume: Int
        get() = topSellsJita.firstOrNull()?.second ?: 0

    val hoursToClearFirstWall: Double
        get() {
            val hourlyVol = avgDailyVolume7d.toDouble() / 24.0
            return if (hourlyVol > 0.05 && firstWallVolume > 0) firstWallVolume / hourlyVol else 0.0
        }
}

interface EveEsiApi {
    @GET("markets/{region_id}/orders/")
    suspend fun getOrders(
        @Path("region_id") regionId: Int,
        @Query("type_id") typeId: Int,
        @Query("order_type") orderType: String = "all"
    ): List<EsiMarketOrder>

    @GET("markets/{region_id}/history/")
    suspend fun getHistory(
        @Path("region_id") regionId: Int,
        @Query("type_id") typeId: Int
    ): List<EsiHistoryEntry>

    @POST("universe/ids/")
    suspend fun resolveItemIds(
        @Body names: List<String>
    ): EsiUniverseIdsResponse

    @GET("characters/{character_id}/wallet/")
    suspend fun getCharacterWallet(
        @Path("character_id") characterId: Long,
        @Header("Authorization") authHeader: String
    ): Double

    @GET("characters/{character_id}/orders/")
    suspend fun getCharacterOrders(
        @Path("character_id") characterId: Long,
        @Header("Authorization") authHeader: String
    ): List<EsiCharacterOrder>

    @GET("characters/{character_id}/assets/")
    suspend fun getCharacterAssets(
        @Path("character_id") characterId: Long,
        @Header("Authorization") authHeader: String,
        @Query("page") page: Int = 1
    ): List<EsiAssetItem>
}

object EveApiClient {
    private const val ESI_BASE_URL = "https://esi.evetech.net/latest/"
    const val PLEX_TYPE_ID = 44992
    private const val PLEX_REGION_ID = 19000001
    const val JITA_4_4 = 60003760L

    const val BUY_COST_MULT = 1.005
    const val SELL_NET_MULT = 1.0 - 0.048358
    const val RELIST_FEE_RATE = 0.00292

    private val requestSemaphore = Semaphore(12)
    private val resolveMutex = Mutex()
    @Volatile private var resolvedIdsByName: Map<String, Int> = emptyMap()

    val catalog: List<WatchedItem> = listOf(
        // 1. АКТИВНЫЕ ПОЗИЦИИ
        WatchedItem(20509, "High-grade Amulet Omega", TradeCategory.ACTIVE, 1269315000.0),
        WatchedItem(53714, "High-grade Nirvana Omega", TradeCategory.ACTIVE),
        WatchedItem(42215, "High-grade Asklepian Omega", TradeCategory.ACTIVE, 1120575000.0),
        WatchedItem(47027, "High-grade Mimesis Omega", TradeCategory.ACTIVE, 579684000.0),
        WatchedItem(19021, "Corpum A-Type Medium Armor Repairer", TradeCategory.ACTIVE, 262305000.0),
        WatchedItem(19187, "Pithum C-Type Medium Shield Booster", TradeCategory.ACTIVE, 77226210.0),
        WatchedItem(19200, "Gist X-Type Large Shield Booster", TradeCategory.ACTIVE, 208537500.0),
        WatchedItem(19015, "Coreli A-Type Small Armor Repairer", TradeCategory.ACTIVE, 27104850.0),
        WatchedItem(19031, "Corelum C-Type Medium Armor Repairer", TradeCategory.ACTIVE, 51445950.0),
        WatchedItem(18937, "Corpus X-Type Armor Explosive Hardener", TradeCategory.ACTIVE, 166428000.0),
        WatchedItem(14252, "Dark Blood Warp Scrambler", TradeCategory.ACTIVE, 99595500.0),
        WatchedItem(14256, "Dread Guristas Warp Scrambler", TradeCategory.ACTIVE, 35376000.0),
        WatchedItem(81951, "Consortium Mobile Tractor Unit", TradeCategory.ACTIVE, 802857083.0),
        WatchedItem(56139, "Cataclysmic Electrical Filament", TradeCategory.ACTIVE, 53828627.0),
        WatchedItem(4347, "Pithum A-Type Multispectrum Shield Hardener", TradeCategory.ACTIVE),
        WatchedItem(89607, "Odysseus", TradeCategory.ACTIVE, 1800000000.0),

        // 2. ИМПЛАНТЫ
        WatchedItem(20505, "High-grade Amulet Epsilon", TradeCategory.IMPLANTS),
        WatchedItem(20503, "High-grade Amulet Delta", TradeCategory.IMPLANTS),
        WatchedItem(20507, "High-grade Amulet Gamma", TradeCategory.IMPLANTS),
        WatchedItem(20499, "High-grade Amulet Beta", TradeCategory.IMPLANTS),
        WatchedItem(53713, "High-grade Nirvana Epsilon", TradeCategory.IMPLANTS),
        WatchedItem(53712, "High-grade Nirvana Delta", TradeCategory.IMPLANTS),
        WatchedItem(53711, "High-grade Nirvana Gamma", TradeCategory.IMPLANTS),
        WatchedItem(42214, "High-grade Asklepian Epsilon", TradeCategory.IMPLANTS),
        WatchedItem(42213, "High-grade Asklepian Delta", TradeCategory.IMPLANTS),
        WatchedItem(42212, "High-grade Asklepian Gamma", TradeCategory.IMPLANTS),
        WatchedItem(47026, "High-grade Mimesis Epsilon", TradeCategory.IMPLANTS),
        WatchedItem(47025, "High-grade Mimesis Delta", TradeCategory.IMPLANTS),
        WatchedItem(20508, "High-grade Halo Omega", TradeCategory.IMPLANTS),
        WatchedItem(20504, "High-grade Halo Epsilon", TradeCategory.IMPLANTS),
        WatchedItem(19556, "High-grade Snake Omega", TradeCategory.IMPLANTS),
        WatchedItem(19555, "High-grade Snake Epsilon", TradeCategory.IMPLANTS),
        WatchedItem(20161, "High-grade Crystal Omega", TradeCategory.IMPLANTS),
        WatchedItem(20157, "High-grade Crystal Epsilon", TradeCategory.IMPLANTS),
        WatchedItem(22119, "Mid-grade Amulet Omega", TradeCategory.IMPLANTS),
        WatchedItem(53726, "Mid-grade Nirvana Omega", TradeCategory.IMPLANTS),
        WatchedItem(42221, "Mid-grade Asklepian Omega", TradeCategory.IMPLANTS),

        // 3. ЩИТЫ
        WatchedItem(19198, "Pith X-Type Large Shield Booster", TradeCategory.SHIELD),
        WatchedItem(19191, "Pith X-Type X-Large Shield Booster", TradeCategory.SHIELD),
        WatchedItem(19196, "Pith A-Type Large Shield Booster", TradeCategory.SHIELD),
        WatchedItem(19202, "Gist X-Type X-Large Shield Booster", TradeCategory.SHIELD),
        WatchedItem(19199, "Gist A-Type Large Shield Booster", TradeCategory.SHIELD),
        WatchedItem(19185, "Pithum A-Type Medium Shield Booster", TradeCategory.SHIELD),
        WatchedItem(19183, "Pithum B-Type Medium Shield Booster", TradeCategory.SHIELD),
        WatchedItem(19189, "Gistum A-Type Medium Shield Booster", TradeCategory.SHIELD),
        WatchedItem(19193, "Gistum B-Type Medium Shield Booster", TradeCategory.SHIELD),
        WatchedItem(19195, "Gistum C-Type Medium Shield Booster", TradeCategory.SHIELD),
        WatchedItem(4348, "Pithum B-Type Multispectrum Shield Hardener", TradeCategory.SHIELD),
        WatchedItem(4349, "Pithum C-Type Multispectrum Shield Hardener", TradeCategory.SHIELD),
        WatchedItem(19231, "Pith X-Type Shield Boost Amplifier", TradeCategory.SHIELD),
        WatchedItem(19229, "Pith A-Type Shield Boost Amplifier", TradeCategory.SHIELD),
        WatchedItem(19301, "Gist X-Type Shield Boost Amplifier", TradeCategory.SHIELD),
        WatchedItem(19284, "Pith X-Type EM Shield Hardener", TradeCategory.SHIELD),
        WatchedItem(19288, "Pith X-Type Thermal Shield Hardener", TradeCategory.SHIELD),
        WatchedItem(15681, "Republic Fleet Large Shield Extender", TradeCategory.SHIELD),

        // 4. БРОНЯ
        WatchedItem(19023, "Corpum B-Type Medium Armor Repairer", TradeCategory.ARMOR),
        WatchedItem(19025, "Corpum C-Type Medium Armor Repairer", TradeCategory.ARMOR),
        WatchedItem(19027, "Corelum A-Type Medium Armor Repairer", TradeCategory.ARMOR),
        WatchedItem(19029, "Corelum B-Type Medium Armor Repairer", TradeCategory.ARMOR),
        WatchedItem(19033, "Corpus X-Type Large Armor Repairer", TradeCategory.ARMOR),
        WatchedItem(19019, "Corpus A-Type Large Armor Repairer", TradeCategory.ARMOR),
        WatchedItem(19037, "Centus X-Type Large Armor Repairer", TradeCategory.ARMOR),
        WatchedItem(19035, "Centus A-Type Large Armor Repairer", TradeCategory.ARMOR),
        WatchedItem(19009, "Centii A-Type Small Armor Repairer", TradeCategory.ARMOR),
        WatchedItem(18935, "Corpus X-Type Armor EM Hardener", TradeCategory.ARMOR),
        WatchedItem(18943, "Corpus X-Type Armor Thermal Hardener", TradeCategory.ARMOR),
        WatchedItem(19325, "Coreli A-Type Multispectrum Coating", TradeCategory.ARMOR),
        WatchedItem(20160, "Imperial Navy Multispectrum Energized Membrane", TradeCategory.ARMOR),
        WatchedItem(18881, "Corpum A-Type Multispectrum Energized Membrane", TradeCategory.ARMOR),
        WatchedItem(18883, "Corpum B-Type Multispectrum Energized Membrane", TradeCategory.ARMOR),
        WatchedItem(18893, "Corpum C-Type Multispectrum Energized Membrane", TradeCategory.ARMOR),

        // 5. ПВП / РЭБ
        WatchedItem(14262, "True Sansha Warp Scrambler", TradeCategory.EW_PROP),
        WatchedItem(14254, "Shadow Serpentis Warp Scrambler", TradeCategory.EW_PROP),
        WatchedItem(14260, "Domination Warp Scrambler", TradeCategory.EW_PROP),
        WatchedItem(14244, "Dark Blood Warp Disruptor", TradeCategory.EW_PROP),
        WatchedItem(14264, "True Sansha Warp Disruptor", TradeCategory.EW_PROP),
        WatchedItem(14258, "Dread Guristas Warp Disruptor", TradeCategory.EW_PROP),
        WatchedItem(14236, "Federation Navy Stasis Webifier", TradeCategory.EW_PROP),
        WatchedItem(14238, "Dark Blood Stasis Webifier", TradeCategory.EW_PROP),
        WatchedItem(19279, "Gist X-Type 500MN Microwarpdrive", TradeCategory.EW_PROP),
        WatchedItem(19327, "Corelum A-Type 50MN Microwarpdrive", TradeCategory.EW_PROP),
        WatchedItem(19263, "Gistum B-Type 50MN Microwarpdrive", TradeCategory.EW_PROP),
        WatchedItem(19251, "Coreli A-Type 5MN Microwarpdrive", TradeCategory.EW_PROP),
        WatchedItem(15766, "Republic Fleet Gyrostabilizer", TradeCategory.EW_PROP),
        WatchedItem(15790, "Federation Navy Magnetic Field Stabilizer", TradeCategory.EW_PROP),
        WatchedItem(13920, "Dread Guristas Ballistic Control System", TradeCategory.EW_PROP),

        // 6. РАСХОДНИКИ И КОРАБЛИ
        WatchedItem(40520, "Large Skill Injector", TradeCategory.HULLS_CONS),
        WatchedItem(45635, "Small Skill Injector", TradeCategory.HULLS_CONS),
        WatchedItem(46375, "Daily Alpha Injector", TradeCategory.HULLS_CONS),
        WatchedItem(40519, "Skill Extractor", TradeCategory.HULLS_CONS),
        WatchedItem(52568, "HyperNet Relay", TradeCategory.HULLS_CONS),
        WatchedItem(56140, "Cataclysmic Dark Filament", TradeCategory.HULLS_CONS),
        WatchedItem(56142, "Cataclysmic Exotic Filament", TradeCategory.HULLS_CONS),
        WatchedItem(56141, "Cataclysmic Firestorm Filament", TradeCategory.HULLS_CONS),
        WatchedItem(56143, "Cataclysmic Gamma Filament", TradeCategory.HULLS_CONS),
        WatchedItem(17715, "Gila", TradeCategory.HULLS_CONS),
        WatchedItem(33468, "Astero", TradeCategory.HULLS_CONS),
        WatchedItem(33470, "Stratios", TradeCategory.HULLS_CONS),
        WatchedItem(33818, "Orthrus", TradeCategory.HULLS_CONS),
        WatchedItem(33820, "Barghest", TradeCategory.HULLS_CONS)
    )

    private val okHttpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .addInterceptor { chain ->
                val req = chain.request().newBuilder()
                    .header("User-Agent", "FoldShield/1.0 (Android; EVE Trader)")
                    .build()
                chain.proceed(req)
            }
            .build()
    }

    val esi: EveEsiApi by lazy {
        Retrofit.Builder()
            .baseUrl(ESI_BASE_URL)
            .client(okHttpClient)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(EveEsiApi::class.java)
    }

    fun getCustomCostBasis(context: Context, typeId: Int, defaultCost: Double?): Double? {
        val prefs = context.getSharedPreferences("eve_custom_costs", Context.MODE_PRIVATE)
        if (!prefs.contains("cost_$typeId")) return defaultCost
        val v = prefs.getFloat("cost_$typeId", -1f).toDouble()
        return if (v > 0) v else null
    }

    fun setCustomCostBasis(context: Context, typeId: Int, cost: Double?) {
        val prefs = context.getSharedPreferences("eve_custom_costs", Context.MODE_PRIVATE)
        if (cost == null || cost <= 0.0) {
            prefs.edit().remove("cost_$typeId").apply()
        } else {
            prefs.edit().putFloat("cost_$typeId", cost.toFloat()).apply()
        }
    }

    private suspend fun ensureCatalogIdsResolved() {
        if (resolvedIdsByName.isNotEmpty()) return
        resolveMutex.withLock {
            if (resolvedIdsByName.isNotEmpty()) return
            try {
                val allNames = catalog.map { it.name }.distinct()
                val response = esi.resolveItemIds(allNames)
                val map = mutableMapOf<String, Int>()
                response.inventoryTypes?.forEach { entry ->
                    map[entry.name.lowercase(Locale.US)] = entry.id
                }
                if (map.isNotEmpty()) {
                    resolvedIdsByName = map
                }
            } catch (_: Exception) {}
        }
    }

    suspend fun fetchCategorySnapshot(context: Context, category: TradeCategory): List<ItemOrderBookSummary> = coroutineScope {
        ensureCatalogIdsResolved()
        val targetList = if (category == TradeCategory.ALL) catalog else catalog.filter { it.category == category }
        targetList.map { rawItem ->
            async {
                requestSemaphore.withPermit {
                    try {
                        val verifiedId = resolvedIdsByName[rawItem.name.lowercase(Locale.US)] ?: rawItem.typeId
                        val customCost = getCustomCostBasis(context, verifiedId, rawItem.myCostBasis)
                        val item = rawItem.copy(typeId = verifiedId, myCostBasis = customCost)

                        val orders = esi.getOrders(regionId = item.regionId, typeId = item.typeId)
                        val history = try { esi.getHistory(regionId = item.regionId, typeId = item.typeId) } catch (_: Exception) { emptyList() }

                        val isPlex = item.regionId == PLEX_REGION_ID

                        val sellsAll = orders.filter { !it.isBuyOrder && it.price > 100_000.0 }
                        val buysAll  = orders.filter { it.isBuyOrder  && it.price > 100_000.0 }

                        val sellsSource = if (isPlex) sellsAll
                        else sellsAll.filter { it.locationId == JITA_4_4 }.ifEmpty { sellsAll }

                        val sells = sellsSource
                            .groupBy { it.price }
                            .map { (price, list) -> price to list.sumOf { it.volumeRemain } }
                            .sortedBy { it.first }
                            .take(5)

                        val bestSell = sells.firstOrNull()?.first ?: 0.0
                        val filteredBuys = buysAll.filter {
                            if (bestSell > 10_000_000.0) it.price >= (bestSell * 0.15) else true
                        }

                        val buys = filteredBuys
                            .groupBy { it.price }
                            .map { (price, list) -> price to list.sumOf { it.volumeRemain } }
                            .sortedByDescending { it.first }
                            .take(5)

                        val bestBuy = buys.firstOrNull()?.first ?: 0.0

                        val buyCostWithScc = bestBuy * BUY_COST_MULT
                        val sellNetAfterTaxes = bestSell * SELL_NET_MULT
                        val netProfit = if (bestSell > 0 && bestBuy > 0) sellNetAfterTaxes - buyCostWithScc else 0.0

                        val rawRoi = if (buyCostWithScc > 0 && bestSell > 0) (netProfit / buyCostWithScc) * 100.0 else 0.0
                        val netRoi = if (rawRoi > 500.0 || rawRoi < -100.0) 0.0 else rawRoi

                        val avgVol7d = if (history.isNotEmpty()) {
                            history.takeLast(7).map { it.volume }.average().toLong()
                        } else 0L

                        ItemOrderBookSummary(
                            item = item,
                            topSellsJita = sells,
                            topBuysForge = buys,
                            avgDailyVolume7d = avgVol7d,
                            bestSell = bestSell,
                            bestBuy = bestBuy,
                            netProfitPerUnit = netProfit,
                            netRoiPercent = netRoi
                        )
                    } catch (_: Exception) {
                        null
                    }
                }
            }
        }.awaitAll().filterNotNull()
    }

    fun saveCache(context: Context, summaries: List<ItemOrderBookSummary>) {
        try {
            val prefs = context.getSharedPreferences("eve_cache", Context.MODE_PRIVATE)
            val json = Gson().toJson(summaries)
            prefs.edit().putString("last_snapshot", json).putLong("cache_time", System.currentTimeMillis()).apply()
        } catch (_: Exception) {}
    }

    fun getCacheTime(context: Context): Long {
        return try {
            context.getSharedPreferences("eve_cache", Context.MODE_PRIVATE).getLong("cache_time", 0L)
        } catch (_: Exception) { 0L }
    }

    fun loadCache(context: Context): List<ItemOrderBookSummary> {
        return try {
            val prefs = context.getSharedPreferences("eve_cache", Context.MODE_PRIVATE)
            val json = prefs.getString("last_snapshot", null) ?: return emptyList()
            val type = object : TypeToken<List<ItemOrderBookSummary>>() {}.type
            val list: List<ItemOrderBookSummary> = Gson().fromJson(json, type)
            list.map { s ->
                val updatedCost = getCustomCostBasis(context, s.item.typeId, s.item.myCostBasis)
                s.copy(item = s.item.copy(myCostBasis = updatedCost))
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun buildAiReport(
        summaries: List<ItemOrderBookSummary>,
        ssoSummaryText: String?,
        altFilamentDebtIsk: Long = 2_691_431_356L
    ): String {
        val df = DecimalFormat("#,###", java.text.DecimalFormatSymbols.getInstance(Locale.US))
        val sb = StringBuilder()
        sb.appendLine("=== EVE TRADING REPORT (FOLDSHIELD -> AI) ===")
        sb.appendLine("Учет филаментов на альте (по себестоимости, без учета прибыли альта): ${df.format(altFilamentDebtIsk)} ISK")
        if (!ssoSummaryText.isNullOrBlank()) {
            sb.appendLine("\n--- ДАННЫЕ ПЕРСОНАЖА (EVE SSO) ---")
            sb.appendLine(ssoSummaryText.trim())
        }
        sb.appendLine("\n--- ЖИВЫЕ СТАКАНЫ ESI (${summaries.size} поз.) ---")
        for (s in summaries) {
            val roiStr = String.format(Locale.US, "%.2f", s.netRoiPercent)
            val costInfo = s.item.myCostBasis?.let { " · Себест: ${df.format(it)}" } ?: ""
            val wallHours = String.format(Locale.US, "%.1f", s.hoursToClearFirstWall)
            sb.appendLine("\n[${s.item.typeId}] ${s.item.name} (Vol 7d: ${s.avgDailyVolume7d}/д · 1-я стена: ${s.firstWallVolume} шт ~${wallHours}ч$costInfo)")
            sb.appendLine("  Профит с 1-х мест: ${df.format(s.netProfitPerUnit)} ISK (Net ROI: $roiStr%)")
            val sellWalls = s.topSellsJita.joinToString(" · ") { "${df.format(it.first)} (${it.second} шт)" }
            val buyWalls  = s.topBuysForge.joinToString(" · ") { "${df.format(it.first)} (${it.second} шт)" }
            sb.appendLine("  SELL Jita 4-4: $sellWalls")
            sb.appendLine("  BUY  The Forge: $buyWalls")
        }
        return sb.toString()
    }
}
