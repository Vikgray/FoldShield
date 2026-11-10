package com.foldshield.blocker

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.security.MessageDigest
import java.security.SecureRandom
import java.text.DecimalFormat
import java.util.Locale

data class EveSsoSyncResult(
    val characterName: String,
    val walletBalance: Long,
    val activeSellOrdersCount: Int,
    val activeBuyOrdersCount: Int,
    val totalEscrowIsk: Double,
    val totalSellValueIsk: Double,
    val totalHangarValueIsk: Double,
    val totalNetWorthIsk: Long,
    val summaryForAi: String
)

object EveAuthManager {
    private const val DEFAULT_CLIENT_ID = ""
    const val REDIRECT_URI = "eveauth-foldshield://callback"
    private const val AUTH_URL = "https://login.eveonline.com/v2/oauth/authorize/"
    private const val TOKEN_URL = "https://login.eveonline.com/v2/oauth/token"

    private fun prefs(context: Context) = context.getSharedPreferences("eve_sso", Context.MODE_PRIVATE)

    fun getClientId(context: Context): String {
        return prefs(context).getString("client_id", DEFAULT_CLIENT_ID)?.trim().orEmpty()
    }

    fun setClientId(context: Context, clientId: String) {
        prefs(context).edit().putString("client_id", clientId.trim()).apply()
    }

    fun isLogged(context: Context): Boolean {
        return prefs(context).getBoolean("is_logged_in", false) &&
                !prefs(context).getString("refresh_token", null).isNullOrBlank()
    }

    fun getCharacterName(context: Context): String? {
        return prefs(context).getString("character_name", null)
    }

    fun getCharacterId(context: Context): Long {
        return prefs(context).getLong("character_id", 0L)
    }

    fun getLastSsoSummary(context: Context): String? {
        return prefs(context).getString("last_sso_summary", null)
    }

    fun logout(context: Context) {
        val savedClientId = getClientId(context)
        prefs(context).edit().clear().putString("client_id", savedClientId).apply()
    }

    fun startLogin(context: Context): Boolean {
        val clientId = getClientId(context)
        if (clientId.isBlank()) return false

        val verifierBytes = ByteArray(32)
        SecureRandom().nextBytes(verifierBytes)
        val codeVerifier = Base64.encodeToString(
            verifierBytes,
            Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING
        )

        val digest = MessageDigest.getInstance("SHA-256").digest(codeVerifier.toByteArray(Charsets.US_ASCII))
        val codeChallenge = Base64.encodeToString(
            digest,
            Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING
        )

        val state = "fs_${System.currentTimeMillis()}"
        prefs(context).edit()
            .putString("pkce_verifier", codeVerifier)
            .putString("oauth_state", state)
            .apply()

        val scopes = "esi-wallet.read_character_wallet.v1 esi-markets.read_character_orders.v1 esi-assets.read_assets.v1"
        val url = Uri.parse(AUTH_URL).buildUpon()
            .appendQueryParameter("response_type", "code")
            .appendQueryParameter("redirect_uri", REDIRECT_URI)
            .appendQueryParameter("client_id", clientId)
            .appendQueryParameter("scope", scopes)
            .appendQueryParameter("code_challenge", codeChallenge)
            .appendQueryParameter("code_challenge_method", "S256")
            .appendQueryParameter("state", state)
            .build()

        val intent = Intent(Intent.ACTION_VIEW, url).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
        return true
    }

    suspend fun handleAuthCallback(context: Context, uri: Uri): Result<String> = withContext(Dispatchers.IO) {
        try {
            val code = uri.getQueryParameter("code")
                ?: return@withContext Result.failure(Exception("Код авторизации не найден"))
            val verifier = prefs(context).getString("pkce_verifier", null)
                ?: return@withContext Result.failure(Exception("PKCE verifier не найден"))
            val clientId = getClientId(context)
            if (clientId.isBlank()) {
                return@withContext Result.failure(Exception("Client ID пуст"))
            }

            val body = "grant_type=authorization_code" +
                    "&code=${URLEncoder.encode(code, "UTF-8")}" +
                    "&client_id=${URLEncoder.encode(clientId, "UTF-8")}" +
                    "&code_verifier=${URLEncoder.encode(verifier, "UTF-8")}"

            val tokenJson = postForm(TOKEN_URL, body)
            saveTokenResponse(context, tokenJson)
            val charName = getCharacterName(context) ?: "Пилот"
            Result.success(charName)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun getValidAccessToken(context: Context): String = withContext(Dispatchers.IO) {
        val p = prefs(context)
        val currentToken = p.getString("access_token", null)
        val expiresAt = p.getLong("expires_at", 0L)
        if (!currentToken.isNullOrBlank() && System.currentTimeMillis() < expiresAt - 60_000L) {
            return@withContext currentToken
        }

        val refreshToken = p.getString("refresh_token", null)
            ?: throw Exception("Сессия истекла. Войди через EVE SSO.")
        val clientId = getClientId(context)

        val body = "grant_type=refresh_token" +
                "&refresh_token=${URLEncoder.encode(refreshToken, "UTF-8")}" +
                "&client_id=${URLEncoder.encode(clientId, "UTF-8")}"

        val tokenJson = postForm(TOKEN_URL, body)
        saveTokenResponse(context, tokenJson)
        prefs(context).getString("access_token", null) ?: throw Exception("Не удалось обновить токен")
    }

    // Полная синхронизация: Кошелек + Ордера + Склад (Jita 4-4 и структуры Perimeter)
    suspend fun syncCharacterData(
        context: Context,
        cachedSummaries: List<ItemOrderBookSummary> = emptyList()
    ): Result<EveSsoSyncResult> = withContext(Dispatchers.IO) {
        try {
            val token = getValidAccessToken(context)
            val charId = getCharacterId(context)
            val charName = getCharacterName(context) ?: "Scotland Yard Solutions"
            if (charId <= 0L) throw Exception("Некорректный ID персонажа")

            val balance = EveApiClient.esi.getCharacterWallet(charId, "Bearer $token")
            val orders = try {
                EveApiClient.esi.getCharacterOrders(charId, "Bearer $token")
            } catch (_: Exception) {
                emptyList()
            }
            val assets = try {
                EveApiClient.esi.getCharacterAssets(charId, "Bearer $token", page = 1)
            } catch (_: Exception) {
                emptyList()
            }

            // Исключаем PLEX (44992) из всех расчетов капитала и ордеров
            val validOrders = orders.filter { it.typeId != EveApiClient.PLEX_TYPE_ID }
            val buyOrders = validOrders.filter { it.isBuyOrder == true }
            val sellOrders = validOrders.filter { it.isBuyOrder != true }

            val totalEscrow = buyOrders.sumOf { it.escrow ?: (it.price * it.volumeRemain) }
            val totalSellVal = sellOrders.sumOf { it.price * it.volumeRemain }

            val catalogMap = EveApiClient.catalog.associateBy { it.typeId }
            val priceMap = cachedSummaries.associate { it.item.typeId to it.bestSell }

            // Фильтруем предметы в ангаре Jita 4-4 и на структурах (Perimeter), исключая PLEX
            val hangarAssets = assets.filter {
                it.typeId != EveApiClient.PLEX_TYPE_ID && it.locationFlag == "Hangar"
            }

            var totalHangarVal = 0.0
            val jitaHangarLines = mutableListOf<String>()
            val perimeterTransferLines = mutableListOf<String>()
            val df = DecimalFormat("#,###", java.text.DecimalFormatSymbols.getInstance(Locale.US))

            hangarAssets.groupBy { it.typeId to it.locationId }.forEach { (pair, list) ->
                val (typeId, locId) = pair
                val qty = list.sumOf { it.quantity }
                val watched = catalogMap[typeId]
                val customCost = EveApiClient.getCustomCostBasis(context, typeId, watched?.myCostBasis)
                val unitVal = priceMap[typeId]?.takeIf { it > 0 } ?: customCost ?: 0.0
                val stackVal = unitVal * qty

                if (watched != null || stackVal >= 5_000_000.0) {
                    totalHangarVal += stackVal
                    val name = watched?.name ?: "typeId:$typeId"
                    val valStr = if (unitVal > 0) " (~${df.format(stackVal)} ISK)" else ""
                    if (locId == EveApiClient.JITA_4_4) {
                        jitaHangarLines.add("  • [Jita 4-4] $name x$qty$valStr")
                    } else if (locId > 1_000_000_000_000L) {
                        perimeterTransferLines.add("  ⚠️ [ВЫВЕЗТИ ИЗ СТРУКТУРЫ В JITA] $name x$qty$valStr")
                    }
                }
            }

            val totalNetWorth = (balance + totalEscrow + totalSellVal + totalHangarVal).toLong()

            val sb = StringBuilder()
            sb.appendLine("=== ПОРТФЕЛЬ EVE SSO ($charName, без учета PLEX) ===")
            sb.appendLine("• Свободный кэш (Liquid ISK): ${df.format(balance)} ISK")
            sb.appendLine("• В ордерах на покупку (Escrow): ${df.format(totalEscrow)} ISK (${buyOrders.size} орд.)")
            sb.appendLine("• В ордерах на продажу (Sell Orders): ${df.format(totalSellVal)} ISK (${sellOrders.size} орд.)")
            sb.appendLine("• На складе (Hangar): ${df.format(totalHangarVal)} ISK")
            sb.appendLine("• ОБЩИЙ РАБОЧИЙ КАПИТАЛ (Net Worth): ${df.format(totalNetWorth)} ISK")

            if (perimeterTransferLines.isNotEmpty()) {
                sb.appendLine("\n🚚 ВЫКУПЛЕНО В PERIMETER (Перевезти в Jita 4-4):")
                perimeterTransferLines.forEach { sb.appendLine(it) }
            }

            if (jitaHangarLines.isNotEmpty()) {
                sb.appendLine("\n📦 СКЛАД JITA 4-4 (Не выставлено в стакан):")
                jitaHangarLines.forEach { sb.appendLine(it) }
            }

            if (validOrders.isNotEmpty()) {
                sb.appendLine("\n📊 АКТИВНЫЕ ОРДЕРА ПЕРСОНАЖА:")
                validOrders.sortedByDescending { it.price * it.volumeRemain }.take(35).forEach { o ->
                    val side = if (o.isBuyOrder == true) "BUY " else "SELL"
                    val itemName = catalogMap[o.typeId]?.name ?: "typeId:${o.typeId}"
                    val topMarket = cachedSummaries.find { it.item.typeId == o.typeId }
                    val outbidTag = when {
                        topMarket == null -> ""
                        o.isBuyOrder == true && topMarket.bestBuy > o.price + 1.0 -> " [ПЕРЕБИТ! Топ: ${df.format(topMarket.bestBuy)}]"
                        o.isBuyOrder != true && topMarket.bestSell > 0 && topMarket.bestSell < o.price - 1.0 -> " [ПЕРЕБИТ! Топ: ${df.format(topMarket.bestSell)}]"
                        else -> " [ТОП-1]"
                    }
                    sb.appendLine("  [$side] $itemName: ${df.format(o.price)} ISK (${o.volumeRemain}/${o.volumeTotal} шт)$outbidTag")
                }
            }

            val summaryText = sb.toString()
            prefs(context).edit().putString("last_sso_summary", summaryText).apply()

            Result.success(
                EveSsoSyncResult(
                    characterName = charName,
                    walletBalance = balance.toLong(),
                    activeSellOrdersCount = sellOrders.size,
                    activeBuyOrdersCount = buyOrders.size,
                    totalEscrowIsk = totalEscrow,
                    totalSellValueIsk = totalSellVal,
                    totalHangarValueIsk = totalHangarVal,
                    totalNetWorthIsk = totalNetWorth,
                    summaryForAi = summaryText
                )
            )
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private fun postForm(urlStr: String, formBody: String): JSONObject {
        val conn = URL(urlStr).openConnection() as HttpURLConnection
        conn.requestMethod = "POST"
        conn.connectTimeout = 15000
        conn.readTimeout = 15000
        conn.doOutput = true
        conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
        conn.setRequestProperty("Accept", "application/json")
        conn.outputStream.use { it.write(formBody.toByteArray(Charsets.UTF_8)) }

        val code = conn.responseCode
        val stream = if (code in 200..299) conn.inputStream else conn.errorStream
        val respText = stream?.bufferedReader()?.readText().orEmpty()
        if (code !in 200..299) {
            throw Exception("HTTP $code:$respText")
        }
        return JSONObject(respText)
    }

        private fun saveTokenResponse(context: Context, json: JSONObject) {
        val accessToken = json.getString("access_token")
        val oldRefreshToken = prefs(context).getString("refresh_token", "").orEmpty()
        val refreshToken = json.optString("refresh_token", oldRefreshToken).ifBlank { oldRefreshToken }
        val expiresIn = json.optLong("expires_in", 1199L)
        val expiresAt = System.currentTimeMillis() + expiresIn * 1000L

        var charId = getCharacterId(context)
        var charName = getCharacterName(context) ?: "Pilot"
        try {
            val parts = accessToken.split(".")
            if (parts.size >= 2) {
                val payloadJson = String(
                    Base64.decode(parts[1], Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING),
                    Charsets.UTF_8
                )
                val payload = JSONObject(payloadJson)
                val sub = payload.optString("sub", "")
                val extractedId = sub.substringAfterLast(":").toLongOrNull()
                if (extractedId != null && extractedId > 0L) charId = extractedId
                val extractedName = payload.optString("name", "")
                if (extractedName.isNotBlank()) charName = extractedName
            }
        } catch (_: Exception) {}

        prefs(context).edit()
            .putString("access_token", accessToken)
            .putString("refresh_token", refreshToken)
            .putLong("expires_at", expiresAt)
            .putLong("character_id", charId)
            .putString("character_name", charName)
            .putBoolean("is_logged_in", true)
            .apply()
    }
}
