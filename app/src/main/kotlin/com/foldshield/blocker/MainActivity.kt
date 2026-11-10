package com.foldshield.blocker

import android.Manifest
import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.text.DecimalFormat
import java.util.Locale

enum class AppTab { HOME, TERMINAL, SETTINGS }
enum class SortMode(val label: String) {
    ROI("ROI"),
    PROFIT("Профит"),
    VOLUME("Объём")
}

const val CAPITAL_GOAL_ISK = 1_000_000_000_000L
private const val FALLBACK_CAPITAL = 22_150_000_000.0

class MainActivity : ComponentActivity() {
    private var pendingAuthUri by mutableStateOf<Uri?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        handleIncomingIntent(intent)
        enableEdgeToEdge()
        setContent {
            FoldShieldTheme {
                MainScreen(
                    authRedirectUri = pendingAuthUri,
                    onAuthUriConsumed = { pendingAuthUri = null }
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIncomingIntent(intent)
    }

    private fun handleIncomingIntent(intent: Intent?) {
        val data = intent?.data ?: return
        if (data.scheme == "eveauth-foldshield" || data.host == "localhost") {
            pendingAuthUri = data
        }
    }
}

@Composable
fun FoldShieldTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = Color(0xFF60A5FA),
            onPrimary = Color.White,
            background = Color(0xFF0A0F1C),
            surface = Color(0xFF111B2E),
            onSurface = Color(0xFFF8FAFC)
        ),
        content = content
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
    authRedirectUri: Uri? = null,
    onAuthUriConsumed: () -> Unit = {}
) {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("foldshield_prefs", Context.MODE_PRIVATE) }
    val coroutineScope = rememberCoroutineScope()

    var currentTab by remember { mutableStateOf(AppTab.HOME) }

    var isActive by remember { mutableStateOf(ShieldVpnService.isRunning) }
    var blocked by remember { mutableIntStateOf(0) }
    var queries by remember { mutableIntStateOf(0) }
    var domains by remember { mutableIntStateOf(0) }

    var whitelist by remember { mutableStateOf(setOf<String>()) }
    var newWhitelistDomain by remember { mutableStateOf("") }
    var autostartEnabled by remember { mutableStateOf(prefs.getBoolean("autostart", false)) }
    var altDebtIsk by remember { mutableLongStateOf(prefs.getLong("alt_debt_isk", 2_691_431_356L)) }
    var altDebtInput by remember { mutableStateOf(altDebtIsk.toString()) }

    var walletCapital by remember { mutableLongStateOf(prefs.getLong("wallet_capital_isk", 0L)) }
    var walletCapitalInput by remember { mutableStateOf(if (walletCapital > 0) walletCapital.toString() else "") }

    // Состояния EVE SSO
    var ssoClientIdInput by remember { mutableStateOf(EveAuthManager.getClientId(context)) }
    var isSsoLogged by remember { mutableStateOf(EveAuthManager.isLogged(context)) }
    var ssoCharName by remember { mutableStateOf(EveAuthManager.getCharacterName(context)) }
    var isSyncingSso by remember { mutableStateOf(false) }
    var ssoSummary by remember { mutableStateOf<String?>(EveAuthManager.getLastSsoSummary(context)) }

    var isDownloading by remember { mutableStateOf(false) }
    var urlInput by remember { mutableStateOf("https://market.yandex.ru/product/123?sku=55&utm_source=tg&yclid=998877&fbclid=IwAR123") }
    var cleaned by remember { mutableStateOf<String?>(null) }

    var isFetchingEve by remember { mutableStateOf(false) }
    var selectedCategory by remember { mutableStateOf(TradeCategory.ACTIVE) }
    var filterBestRoiOnly by remember { mutableStateOf(false) }
    var searchQuery by remember { mutableStateOf("") }
    var sortMode by remember { mutableStateOf(SortMode.ROI) }
    var eveSummaries by remember { mutableStateOf<List<ItemOrderBookSummary>>(emptyList()) }
    var eveError by remember { mutableStateOf<String?>(null) }
    var cacheTimestamp by remember { mutableLongStateOf(0L) }

    BackHandler(enabled = currentTab != AppTab.HOME) {
        currentTab = AppTab.HOME
    }

    LaunchedEffect(authRedirectUri) {
        val uri = authRedirectUri ?: return@LaunchedEffect
        onAuthUriConsumed()
        isSyncingSso = true
        val res = EveAuthManager.handleAuthCallback(context, uri)
        res.onSuccess { charName ->
            isSsoLogged = true
            ssoCharName = charName
            Toast.makeText(context, "Вход выполнен: $charName", Toast.LENGTH_SHORT).show()
            val sync = EveAuthManager.syncCharacterData(context, eveSummaries)
            sync.onSuccess { data ->
                walletCapital = data.totalNetWorthIsk
                walletCapitalInput = data.totalNetWorthIsk.toString()
                prefs.edit().putLong("wallet_capital_isk", data.totalNetWorthIsk).apply()
                ssoSummary = data.summaryForAi
                Toast.makeText(context, "Капитал (Net Worth): ${formatIsk(data.totalNetWorthIsk.toDouble())} ISK", Toast.LENGTH_LONG).show()
            }
        }.onFailure { err ->
            eveError = "Ошибка SSO: ${err.localizedMessage}"
            Toast.makeText(context, "Ошибка входа: ${err.localizedMessage}", Toast.LENGTH_LONG).show()
        }
        isSyncingSso = false
    }

    val vpnLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) startVpn(context)
    }
    val notifLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }

    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= 33 && context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        whitelist = prefs.getStringSet("whitelist_domains", emptySet())?.toSet() ?: emptySet()
        val cached = EveApiClient.loadCache(context)
        if (cached.isNotEmpty()) {
            eveSummaries = cached
            cacheTimestamp = EveApiClient.getCacheTime(context)
        }
    }

    LaunchedEffect(Unit) {
        while (true) {
            blocked = ShieldVpnService.blockedCount.get()
            queries = ShieldVpnService.totalQueries.get()
            domains = ShieldVpnService.loadedDomainCount
            isActive = ShieldVpnService.isRunning
            delay(1000)
        }
    }

    Scaffold(
        containerColor = Color(0xFF0A0F1C),
        bottomBar = {
            NavigationBar(containerColor = Color(0xFF111B2E)) {
                NavigationBarItem(
                    selected = currentTab == AppTab.HOME,
                    onClick = { currentTab = AppTab.HOME },
                    icon = { Text("🏠", fontSize = 20.sp) },
                    label = { Text("Главная", fontSize = 11.sp) },
                    colors = foldNavColors()
                )
                NavigationBarItem(
                    selected = currentTab == AppTab.TERMINAL,
                    onClick = { currentTab = AppTab.TERMINAL },
                    icon = { Text("🛒", fontSize = 20.sp) },
                    label = { Text("Терминал", fontSize = 11.sp) },
                    colors = foldNavColors()
                )
                NavigationBarItem(
                    selected = currentTab == AppTab.SETTINGS,
                    onClick = { currentTab = AppTab.SETTINGS },
                    icon = { Text("⚙️", fontSize = 20.sp) },
                    label = { Text("Настройки", fontSize = 11.sp) },
                    colors = foldNavColors()
                )
            }
        }
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .background(Brush.verticalGradient(listOf(Color(0xFF0A0F1C), Color(0xFF0F172A), Color(0xFF0A0F1C))))
        ) {
            when (currentTab) {
                AppTab.HOME -> HomeScreen(
                    isActive = isActive,
                    blocked = blocked,
                    queries = queries,
                    domains = domains,
                    walletCapital = walletCapital,
                    eveSummaries = eveSummaries,
                    cacheTimestamp = cacheTimestamp,
                    onToggleVpn = {
                        if (isActive) stopVpn(context) else {
                            val prepare = VpnService.prepare(context)
                            if (prepare != null) vpnLauncher.launch(prepare) else startVpn(context)
                        }
                    },
                    onOpenTerminal = { currentTab = AppTab.TERMINAL }
                )

                AppTab.TERMINAL -> TerminalScreen(
                    isFetchingEve = isFetchingEve,
                    isSsoLogged = isSsoLogged,
                    isSyncingSso = isSyncingSso,
                    selectedCategory = selectedCategory,
                    filterBestRoiOnly = filterBestRoiOnly,
                    searchQuery = searchQuery,
                    sortMode = sortMode,
                    eveSummaries = eveSummaries,
                    ssoSummary = ssoSummary,
                    eveError = eveError,
                    onCategoryChange = { selectedCategory = it },
                    onFilterChange = { filterBestRoiOnly = it },
                    onSearchChange = { searchQuery = it },
                    onSortModeChange = { sortMode = it },
                    onScan = {
                        isFetchingEve = true
                        eveError = null
                        coroutineScope.launch {
                            try {
                                val result = withContext(Dispatchers.IO) {
                                    EveApiClient.fetchCategorySnapshot(context, selectedCategory)
                                }
                                if (result.isNotEmpty()) {
                                    eveSummaries = result
                                    EveApiClient.saveCache(context, result)
                                    cacheTimestamp = System.currentTimeMillis()
                                } else {
                                    eveError = "Пустой ответ ESI."
                                }
                            } catch (e: Exception) {
                                eveError = "Ошибка: ${e.localizedMessage}"
                            } finally {
                                isFetchingEve = false
                            }
                        }
                    },
                    onSyncSso = {
                        if (!isSsoLogged) {
                            currentTab = AppTab.SETTINGS
                            Toast.makeText(context, "Войди через EVE SSO в настройках", Toast.LENGTH_SHORT).show()
                        } else {
                            isSyncingSso = true
                            eveError = null
                            coroutineScope.launch {
                                val res = EveAuthManager.syncCharacterData(context, eveSummaries)
                                res.onSuccess { data ->
                                    walletCapital = data.totalNetWorthIsk
                                    walletCapitalInput = data.totalNetWorthIsk.toString()
                                    prefs.edit().putLong("wallet_capital_isk", data.totalNetWorthIsk).apply()
                                    ssoSummary = data.summaryForAi
                                    Toast.makeText(context, "Net Worth: ${formatIsk(data.totalNetWorthIsk.toDouble())} ISK", Toast.LENGTH_SHORT).show()
                                }.onFailure { err ->
                                    eveError = "Ошибка SSO: ${err.localizedMessage}"
                                }
                                isSyncingSso = false
                            }
                        }
                    },
                    onUpdateItemCost = { typeId, newCost ->
                        EveApiClient.setCustomCostBasis(context, typeId, newCost)
                        eveSummaries = eveSummaries.map { s ->
                            if (s.item.typeId == typeId) s.copy(item = s.item.copy(myCostBasis = newCost)) else s
                        }
                        Toast.makeText(context, "Себестоимость сохранена", Toast.LENGTH_SHORT).show()
                    },
                    onCopyReport = {
                        val displayed = applyFilters(eveSummaries, filterBestRoiOnly, searchQuery, sortMode)
                        val report = EveApiClient.buildAiReport(displayed.ifEmpty { eveSummaries }, ssoSummary, altDebtIsk)
                        copyToClipboard(context, report)
                    }
                )

                AppTab.SETTINGS -> SettingsScreen(
                    whitelist = whitelist,
                    newWhitelistDomain = newWhitelistDomain,
                    autostartEnabled = autostartEnabled,
                    altDebtInput = altDebtInput,
                    walletCapitalInput = walletCapitalInput,
                    ssoClientIdInput = ssoClientIdInput,
                    isSsoLogged = isSsoLogged,
                    ssoCharName = ssoCharName,
                    isSyncingSso = isSyncingSso,
                    isDownloading = isDownloading,
                    urlInput = urlInput,
                    cleaned = cleaned,
                    onSsoClientIdChange = { ssoClientIdInput = it },
                    onStartSsoLogin = {
                        EveAuthManager.setClientId(context, ssoClientIdInput)
                        if (!EveAuthManager.startLogin(context)) {
                            Toast.makeText(context, "Сначала вставь Client ID из панели CCP!", Toast.LENGTH_LONG).show()
                        }
                    },
                    onSyncSso = {
                        isSyncingSso = true
                        coroutineScope.launch {
                            val res = EveAuthManager.syncCharacterData(context, eveSummaries)
                            res.onSuccess { data ->
                                walletCapital = data.totalNetWorthIsk
                                walletCapitalInput = data.totalNetWorthIsk.toString()
                                prefs.edit().putLong("wallet_capital_isk", data.totalNetWorthIsk).apply()
                                ssoSummary = data.summaryForAi
                                Toast.makeText(context, "Синхронизировано: ${formatIsk(data.totalNetWorthIsk.toDouble())} ISK", Toast.LENGTH_LONG).show()
                            }.onFailure { err ->
                                Toast.makeText(context, "Ошибка: ${err.localizedMessage}", Toast.LENGTH_LONG).show()
                            }
                            isSyncingSso = false
                        }
                    },
                    onLogoutSso = {
                        EveAuthManager.logout(context)
                        isSsoLogged = false
                        ssoCharName = null
                        ssoSummary = null
                        Toast.makeText(context, "Выход из EVE SSO выполнен", Toast.LENGTH_SHORT).show()
                    },
                    onNewWhitelistDomainChange = { newWhitelistDomain = it },
                    onAddWhitelist = {
                        if (newWhitelistDomain.isNotBlank()) {
                            val newSet = whitelist + newWhitelistDomain.trim().lowercase()
                            whitelist = newSet
                            prefs.edit().putStringSet("whitelist_domains", newSet).apply()
                            newWhitelistDomain = ""
                            safeStartService(context, Intent(context, ShieldVpnService::class.java).apply { action = "UPDATE_WHITELIST" })
                        }
                    },
                    onRemoveWhitelist = { domain ->
                        val newSet = whitelist - domain
                        whitelist = newSet
                        prefs.edit().putStringSet("whitelist_domains", newSet).apply()
                        safeStartService(context, Intent(context, ShieldVpnService::class.java).apply { action = "UPDATE_WHITELIST" })
                    },
                    onAutostartChange = { enabled ->
                        autostartEnabled = enabled
                        prefs.edit().putBoolean("autostart", enabled).apply()
                    },
                    onAltDebtInputChange = { altDebtInput = it.filter { ch -> ch.isDigit() } },
                    onSaveAltDebt = {
                        val v = altDebtInput.toLongOrNull() ?: 0L
                        altDebtIsk = v
                        prefs.edit().putLong("alt_debt_isk", v).apply()
                        Toast.makeText(context, "Сохранено", Toast.LENGTH_SHORT).show()
                    },
                    onWalletCapitalInputChange = { walletCapitalInput = it.filter { ch -> ch.isDigit() } },
                    onSaveWalletCapital = {
                        val v = walletCapitalInput.toLongOrNull() ?: 0L
                        walletCapital = v
                        prefs.edit().putLong("wallet_capital_isk", v).apply()
                        Toast.makeText(context, "Капитал обновлен", Toast.LENGTH_SHORT).show()
                    },
                    onDownloadBlocklist = {
                        isDownloading = true
                        coroutineScope.launch {
                            val success = downloadBlocklist(context)
                            isDownloading = false
                            if (success) {
                                Toast.makeText(context, "База обновлена!", Toast.LENGTH_SHORT).show()
                                safeStartService(context, Intent(context, ShieldVpnService::class.java).apply { action = "RELOAD_BLOCKLIST" })
                            } else {
                                Toast.makeText(context, "Ошибка скачивания", Toast.LENGTH_SHORT).show()
                            }
                        }
                    },
                    onUrlInputChange = { urlInput = it },
                    onSanitizeUrl = {
                        val result = sanitizeUrl(urlInput)
                        cleaned = result
                        copyToClipboard(context, result)
                    }
                )
            }
        }
    }
}

@Composable
private fun foldNavColors() = NavigationBarItemDefaults.colors(
    selectedIconColor = Color(0xFF60A5FA),
    selectedTextColor = Color(0xFF60A5FA),
    unselectedIconColor = Color(0xFF64748B),
    unselectedTextColor = Color(0xFF64748B),
    indicatorColor = Color(0xFF2563EB).copy(alpha = 0.2f)
)

@Composable
fun HomeScreen(
    isActive: Boolean,
    blocked: Int,
    queries: Int,
    domains: Int,
    walletCapital: Long,
    eveSummaries: List<ItemOrderBookSummary>,
    cacheTimestamp: Long,
    onToggleVpn: () -> Unit,
    onOpenTerminal: () -> Unit
) {
    Column(modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp)) {
        Text(text = "●  GALAXY Z FOLD 5  ·  KNOX 0x0", color = Color(0xFF60A5FA), fontSize = 10.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace, letterSpacing = 1.2.sp)
        Spacer(Modifier.height(8.dp))
        Text(text = "FoldShield", color = Color(0xFFF8FAFC), fontSize = 34.sp, fontWeight = FontWeight.Bold)
        Text(text = "Локальный DNS-щит · Торговый терминал Jita / Perimeter", color = Color(0xFF64748B), fontSize = 12.sp, modifier = Modifier.padding(top = 4.dp))
        Spacer(Modifier.height(20.dp))

        HeroCard(isActive = isActive, onToggle = onToggleVpn)

        Spacer(Modifier.height(14.dp))

        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            StatCell(blocked.toString(), "ЗАБЛОКИР.", Color(0xFF34D399), Modifier.weight(1f))
            StatCell(queries.toString(), "ЗАПРОСОВ", Color(0xFF60A5FA), Modifier.weight(1f))
            StatCell(if (domains > 0) domains.toString() else "—", "ДОМЕНОВ", Color(0xFFA78BFA), Modifier.weight(1f))
        }

        Spacer(Modifier.height(14.dp))

        CapitalProgressCard(capital = walletCapital)

        Spacer(Modifier.height(14.dp))

        TradePlanCard(summaries = eveSummaries, cacheTimestamp = cacheTimestamp, capitalIsk = walletCapital)

        Spacer(Modifier.height(14.dp))

        TerminalShortcutCard(
            eveSummaries = eveSummaries,
            cacheTimestamp = cacheTimestamp,
            onOpenTerminal = onOpenTerminal
        )

        Spacer(Modifier.height(24.dp))
    }
}

@Composable
fun TerminalScreen(
    isFetchingEve: Boolean,
    isSsoLogged: Boolean,
    isSyncingSso: Boolean,
    selectedCategory: TradeCategory,
    filterBestRoiOnly: Boolean,
    searchQuery: String,
    sortMode: SortMode,
    eveSummaries: List<ItemOrderBookSummary>,
    ssoSummary: String?,
    eveError: String?,
    onCategoryChange: (TradeCategory) -> Unit,
    onFilterChange: (Boolean) -> Unit,
    onSearchChange: (String) -> Unit,
    onSortModeChange: (SortMode) -> Unit,
    onScan: () -> Unit,
    onSyncSso: () -> Unit,
    onUpdateItemCost: (Int, Double?) -> Unit,
    onCopyReport: () -> Unit
) {
    val context = LocalContext.current
    var expandedItemId by remember { mutableStateOf<Int?>(null) }

    Column(modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp)) {
        Text(text = "EVE Online: Терминал", color = Color(0xFFF8FAFC), fontSize = 26.sp, fontWeight = FontWeight.Bold)
        Text(text = "Нажми на карточку товара для просмотра 5 стенок и калькулятора ре-листа", color = Color(0xFF94A3B8), fontSize = 12.sp, modifier = Modifier.padding(top = 6.dp, bottom = 14.dp))

        CardContainer {
            Row(modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TradeCategory.values().forEach { cat ->
                    val isSel = selectedCategory == cat
                    Box(
                        modifier = Modifier
                            .background(if (isSel) Color(0xFF2563EB) else Color(0xFF0A1220), RoundedCornerShape(10.dp))
                            .clickable { onCategoryChange(cat) }
                            .padding(horizontal = 12.dp, vertical = 8.dp)
                    ) {
                        Text(text = cat.title, color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }

            Spacer(Modifier.height(10.dp))

            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                Text(text = "Только ROI ≥ 6% (Топ-25)", color = Color(0xFF34D399), fontSize = 12.sp, fontWeight = FontWeight.Bold)
                Switch(
                    checked = filterBestRoiOnly,
                    onCheckedChange = onFilterChange,
                    colors = SwitchDefaults.colors(checkedThumbColor = Color.White, checkedTrackColor = Color(0xFF059669))
                )
            }

            OutlinedTextField(
                value = searchQuery,
                onValueChange = onSearchChange,
                modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
                textStyle = LocalTextStyle.current.copy(color = Color(0xFFF8FAFC), fontSize = 12.sp),
                colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = Color(0xFF1E40AF), unfocusedBorderColor = Color(0xFF1E293B), focusedContainerColor = Color(0xFF0A1220), unfocusedContainerColor = Color(0xFF0A1220)),
                shape = RoundedCornerShape(12.dp),
                leadingIcon = { Text("🔍", fontSize = 14.sp) },
                placeholder = { Text("Поиск по названию или typeId...", color = Color(0xFF64748B), fontSize = 12.sp) },
                maxLines = 1,
                singleLine = true
            )

            Spacer(Modifier.height(6.dp))

            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                SortMode.values().forEach { mode ->
                    val sel = sortMode == mode
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .background(if (sel) Color(0xFF2563EB) else Color(0xFF0A1220), RoundedCornerShape(10.dp))
                            .clickable { onSortModeChange(mode) }
                            .padding(vertical = 10.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(text = mode.label, color = if (sel) Color.White else Color(0xFF94A3B8), fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }

            Spacer(Modifier.height(12.dp))

            PrimaryButton(
                text = if (isFetchingEve) "Сканируем ${selectedCategory.title}..." else "Сканировать ${selectedCategory.title}",
                color = Color(0xFFEAB308),
                enabled = !isFetchingEve,
                onClick = onScan
            )

            Spacer(Modifier.height(8.dp))

            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = onSyncSso,
                    enabled = !isSyncingSso,
                    modifier = Modifier.weight(1f).height(48.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2563EB)),
                    shape = RoundedCornerShape(14.dp)
                ) {
                    Text(
                        text = if (isSyncingSso) "⏳ SSO..." else if (isSsoLogged) "🔄 Синхр. SSO" else "🔑 Вход SSO",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold
                    )
                }

                Button(
                    onClick = onCopyReport,
                    modifier = Modifier.weight(1f).height(48.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF059669)),
                    shape = RoundedCornerShape(14.dp)
                ) {
                    Text("📋 Отчет ИИ", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                }
            }

            if (isFetchingEve) {
                Row(modifier = Modifier.fillMaxWidth().padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
                    CircularProgressIndicator(modifier = Modifier.size(20.dp), color = Color(0xFFEAB308), strokeWidth = 3.dp)
                    Spacer(Modifier.size(10.dp))
                    Text(text = "Опрашиваем ESI...", color = Color(0xFFEAB308), fontSize = 12.sp)
                }
            }

            if (!isFetchingEve) {
                ssoSummary?.let {
                    Spacer(Modifier.height(10.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("✓", fontSize = 14.sp, color = Color(0xFF34D399), fontWeight = FontWeight.Bold)
                        Spacer(Modifier.width(6.dp))
                        Text(text = "Кошелек, ордера и склад синхронизированы по SSO", color = Color(0xFF34D399), fontSize = 12.sp)
                    }
                }
                eveError?.let { error ->
                    Spacer(Modifier.height(10.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("⚠️", fontSize = 14.sp)
                        Spacer(Modifier.width(6.dp))
                        Text(text = error, color = Color(0xFFF87171), fontSize = 12.sp)
                    }
                }
            }

            Spacer(Modifier.height(14.dp))

            val displayedList = remember(eveSummaries, filterBestRoiOnly, searchQuery, sortMode) {
                applyFilters(eveSummaries, filterBestRoiOnly, searchQuery, sortMode)
            }

            if (displayedList.isNotEmpty()) {
                Column(
                    modifier = Modifier.fillMaxWidth().background(Color(0xFF0A1220), RoundedCornerShape(12.dp)).padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    displayedList.forEach { s ->
                        val isExpanded = expandedItemId == s.item.typeId
                        ExpandableOrderBookCard(
                            summary = s,
                            isExpanded = isExpanded,
                            onToggleExpand = {
                                expandedItemId = if (isExpanded) null else s.item.typeId
                            },
                            onSaveCostBasis = { newCost ->
                                onUpdateItemCost(s.item.typeId, newCost)
                            },
                            onCopyPrice = { price ->
                                copyToClipboard(context, String.format(Locale.US, "%.0f", price))
                            }
                        )
                    }
                }
            } else if (!isFetchingEve) {
                Text(text = "Пока пусто. Жми «Сканировать».", color = Color(0xFF64748B), fontSize = 12.sp, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(vertical = 16.dp))
            }
        }

        Spacer(Modifier.height(24.dp))
    }
}

@Composable
fun ExpandableOrderBookCard(
    summary: ItemOrderBookSummary,
    isExpanded: Boolean,
    onToggleExpand: () -> Unit,
    onSaveCostBasis: (Double?) -> Unit,
    onCopyPrice: (Double) -> Unit
) {
    val roiColor = when {
        summary.netRoiPercent >= 6.5 -> Color(0xFF34D399)
        summary.netRoiPercent > 0.0 -> Color(0xFFEAB308)
        else -> Color(0xFFF87171)
    }
    val isTopSignal = summary.netRoiPercent >= 6.0 && summary.avgDailyVolume7d >= 2
    val hoursWall = summary.hoursToClearFirstWall
    val isHeavyWall = hoursWall >= 12.0

    var costInput by remember(summary.item.myCostBasis) {
        mutableStateOf(summary.item.myCostBasis?.toLong()?.toString() ?: "")
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color(0xFF0F172A), RoundedCornerShape(12.dp))
            .clickable { onToggleExpand() }
            .padding(12.dp)
    ) {
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = summary.item.name,
                color = Color(0xFFF8FAFC),
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.weight(1f)
            )
            if (isHeavyWall) {
                Box(
                    modifier = Modifier
                        .background(Color(0xFFEAB308).copy(alpha = 0.18f), RoundedCornerShape(6.dp))
                        .padding(horizontal = 6.dp, vertical = 2.dp)
                ) {
                    Text(
                        text = "🧱 Стена ${String.format(Locale.US, "%.0f", hoursWall)}ч",
                        color = Color(0xFFEAB308),
                        fontSize = 9.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
                Spacer(Modifier.width(6.dp))
            } else if (isTopSignal) {
                Box(
                    modifier = Modifier
                        .background(Color(0xFF34D399).copy(alpha = 0.15f), RoundedCornerShape(6.dp))
                        .padding(horizontal = 6.dp, vertical = 2.dp)
                ) {
                    Text(text = "TOP", color = Color(0xFF34D399), fontSize = 9.sp, fontWeight = FontWeight.Bold)
                }
                Spacer(Modifier.width(6.dp))
            }
            val roiText = if (summary.bestBuy > 0 && summary.bestSell > 0) "${String.format(Locale.US, "%.1f", summary.netRoiPercent)}%" else "Н/Д"
            Text(text = "ROI: $roiText", color = roiColor, fontSize = 13.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
        }

        Spacer(Modifier.height(4.dp))

        if (summary.bestBuy > 0 && summary.bestSell > 0) {
            Text(
                text = "Buy: ${formatIsk(summary.bestBuy)} → Sell: ${formatIsk(summary.bestSell)}",
                color = Color(0xFF94A3B8), fontSize = 11.sp, fontFamily = FontFamily.Monospace
            )
            Text(
                text = "Профит: ${formatIsk(summary.netProfitPerUnit)} ISK | Vol: ${summary.avgDailyVolume7d}/д (1-я: ${summary.firstWallVolume} шт)",
                color = Color(0xFF60A5FA), fontSize = 11.sp, fontFamily = FontFamily.Monospace
            )
        } else {
            Text(
                text = "Стакан пуст (нет активных ордеров в Jita / Perimeter)",
                color = Color(0xFFF87171), fontSize = 11.sp, fontFamily = FontFamily.Monospace
            )
        }

        // РАСКРЫВАЮЩАЯСЯ ПАНЕЛЬ (5 СТЕНОК + КАЛЬКУЛЯТОР БЕЗУБЫТОЧНОСТИ И РЕ-ЛИСТА)
        if (isExpanded) {
            Spacer(Modifier.height(10.dp))
            Box(modifier = Modifier.fillMaxWidth().height(1.dp).background(Color(0xFF1E293B)))
            Spacer(Modifier.height(10.dp))

            // Быстрое копирование цены с шагом 100k ISK
            if (summary.bestBuy > 0 && summary.bestSell > 0) {
                val step = if (summary.bestSell >= 100_000_000) 100_000.0 else 10_000.0
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = { onCopyPrice(summary.bestBuy + step) },
                        modifier = Modifier.weight(1f).height(38.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1E3A8A)),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Text("📋 Buy + шаг", fontSize = 10.sp, fontWeight = FontWeight.Bold)
                    }
                    Button(
                        onClick = { onCopyPrice((summary.bestSell - step).coerceAtLeast(1.0)) },
                        modifier = Modifier.weight(1f).height(38.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF065F46)),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Text("📋 Sell - шаг", fontSize = 10.sp, fontWeight = FontWeight.Bold)
                    }
                }
                Spacer(Modifier.height(10.dp))
            }

            // Топ-5 SELL и Топ-5 BUY
            Text("🔻 SELL Jita 4-4 (нажми на строку для копирования):", color = Color(0xFFF87171), fontSize = 11.sp, fontWeight = FontWeight.Bold)
            summary.topSellsJita.forEachIndexed { idx, (price, vol) ->
                Text(
                    text = "  #${idx + 1}: ${formatIsk(price)} ISK  (${vol} шт)",
                    color = Color(0xFFE2E8F0),
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace,
                    modifier = Modifier.fillMaxWidth().clickable { onCopyPrice(price) }.padding(vertical = 2.dp)
                )
            }

            Spacer(Modifier.height(6.dp))

            Text("🔺 BUY The Forge (нажми на строку для копирования):", color = Color(0xFF34D399), fontSize = 11.sp, fontWeight = FontWeight.Bold)
            summary.topBuysForge.forEachIndexed { idx, (price, vol) ->
                Text(
                    text = "  #${idx + 1}: ${formatIsk(price)} ISK  (${vol} шт)",
                    color = Color(0xFFE2E8F0),
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace,
                    modifier = Modifier.fillMaxWidth().clickable { onCopyPrice(price) }.padding(vertical = 2.dp)
                )
            }

            Spacer(Modifier.height(10.dp))

            // Калькулятор твоей себестоимости, точки безубыточности и стоимости 1 ре-листа
            val currentCost = summary.item.myCostBasis
            if (currentCost != null && currentCost > 0) {
                val breakEvenSell = currentCost / EveApiClient.SELL_NET_MULT
                val relistFee = summary.bestSell * EveApiClient.RELIST_FEE_RATE
                val myNetProfit = (summary.bestSell * EveApiClient.SELL_NET_MULT) - currentCost
                val myNetRoi = (myNetProfit / currentCost) * 100.0

                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Color(0xFF0A1220), RoundedCornerShape(8.dp))
                        .padding(10.dp)
                ) {
                    Text("📦 Мой склад / Защита от ре-листа:", color = Color(0xFF60A5FA), fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    Text("• Моя себестоимость: ${formatIsk(currentCost)} ISK", color = Color(0xFFCBD5E1), fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                    Text("• Безубыток в Jita 4-4: ${formatIsk(breakEvenSell)} ISK", color = Color(0xFFEAB308), fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                    Text("• Цена 1 ре-листа (0.292%): -${formatIsk(relistFee)} ISK/шт", color = Color(0xFFF87171), fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                    Text(
                        text = "• Мой профит по топ-Sell: ${formatIsk(myNetProfit)} ISK (${String.format(Locale.US, "%.1f", myNetRoi)}%)",
                        color = if (myNetProfit > 0) Color(0xFF34D399) else Color(0xFFF87171),
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace
                    )
                }
                Spacer(Modifier.height(8.dp))
            }

            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = costInput,
                    onValueChange = { costInput = it.filter { ch -> ch.isDigit() } },
                    modifier = Modifier.weight(1f),
                    textStyle = LocalTextStyle.current.copy(color = Color(0xFFF8FAFC), fontSize = 11.sp, fontFamily = FontFamily.Monospace),
                    colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = Color(0xFF1E40AF), unfocusedBorderColor = Color(0xFF1E293B), focusedContainerColor = Color(0xFF0A1220), unfocusedContainerColor = Color(0xFF0A1220)),
                    shape = RoundedCornerShape(10.dp),
                    placeholder = { Text("Моя себестоимость (ISK)...", color = Color(0xFF64748B), fontSize = 11.sp) },
                    maxLines = 1,
                    singleLine = true
                )
                Spacer(Modifier.width(6.dp))
                Button(
                    onClick = { onSaveCostBasis(costInput.toDoubleOrNull()) },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2563EB)),
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier.height(48.dp)
                ) {
                    Text("Сохр.", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

private fun applyFilters(source: List<ItemOrderBookSummary>, filterBestRoiOnly: Boolean, searchQuery: String, sortMode: SortMode): List<ItemOrderBookSummary> {
    val filtered = source.filter { s ->
        val matchesSearch = searchQuery.isBlank() || s.item.name.contains(searchQuery, ignoreCase = true) || s.item.typeId.toString().contains(searchQuery)
        val matchesRoi = !filterBestRoiOnly || (s.netRoiPercent >= 6.0 && s.avgDailyVolume7d >= 2)
        matchesSearch && matchesRoi
    }.let { list -> if (filterBestRoiOnly) list.sortedByDescending { it.netRoiPercent }.take(25) else list }

    return when (sortMode) {
        SortMode.ROI -> filtered.sortedByDescending { it.netRoiPercent }
        SortMode.PROFIT -> filtered.sortedByDescending {
            val buyVol = it.topBuysForge.firstOrNull { p -> p.first == it.bestBuy }?.second ?: 0
            it.netProfitPerUnit * buyVol
        }
        SortMode.VOLUME -> filtered.sortedByDescending { it.avgDailyVolume7d }
    }
}

@Composable
fun SettingsScreen(
    whitelist: Set<String>,
    newWhitelistDomain: String,
    autostartEnabled: Boolean,
    altDebtInput: String,
    walletCapitalInput: String,
    ssoClientIdInput: String,
    isSsoLogged: Boolean,
    ssoCharName: String?,
    isSyncingSso: Boolean,
    isDownloading: Boolean,
    urlInput: String,
    cleaned: String?,
    onSsoClientIdChange: (String) -> Unit,
    onStartSsoLogin: () -> Unit,
    onSyncSso: () -> Unit,
    onLogoutSso: () -> Unit,
    onNewWhitelistDomainChange: (String) -> Unit,
    onAddWhitelist: () -> Unit,
    onRemoveWhitelist: (String) -> Unit,
    onAutostartChange: (Boolean) -> Unit,
    onAltDebtInputChange: (String) -> Unit,
    onSaveAltDebt: () -> Unit,
    onWalletCapitalInputChange: (String) -> Unit,
    onSaveWalletCapital: () -> Unit,
    onDownloadBlocklist: () -> Unit,
    onUrlInputChange: (String) -> Unit,
    onSanitizeUrl: () -> Unit
) {
    Column(modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp)) {
        Text(text = "Настройки", color = Color(0xFFF8FAFC), fontSize = 26.sp, fontWeight = FontWeight.Bold)
        Text(text = "EVE SSO, база блокировок и параметры портфеля", color = Color(0xFF94A3B8), fontSize = 12.sp, modifier = Modifier.padding(top = 6.dp, bottom = 14.dp))

        CardContainer {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("🔑", fontSize = 16.sp)
                Spacer(Modifier.width(8.dp))
                Text(text = "Авторизация EVE Online (SSO)", color = Color(0xFFF8FAFC), fontSize = 17.sp, fontWeight = FontWeight.Bold)
            }
            Text(
                text = "Callback URL для панели CCP: eveauth-foldshield://callback",
                color = Color(0xFF60A5FA), fontSize = 11.sp, fontFamily = FontFamily.Monospace, modifier = Modifier.padding(top = 6.dp, bottom = 10.dp)
            )

            OutlinedTextField(
                value = ssoClientIdInput,
                onValueChange = onSsoClientIdChange,
                modifier = Modifier.fillMaxWidth(),
                textStyle = LocalTextStyle.current.copy(color = Color(0xFFF8FAFC), fontSize = 12.sp, fontFamily = FontFamily.Monospace),
                colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = Color(0xFF1E40AF), unfocusedBorderColor = Color(0xFF1E293B), focusedContainerColor = Color(0xFF0A1220), unfocusedContainerColor = Color(0xFF0A1220)),
                shape = RoundedCornerShape(14.dp),
                placeholder = { Text("Вставь Client ID из developers.eveonline.com", color = Color(0xFF64748B), fontSize = 12.sp) },
                maxLines = 1,
                singleLine = true
            )

            Spacer(Modifier.height(12.dp))

            if (isSsoLogged) {
                Text(
                    text = "✓ Персонаж: ${ssoCharName ?: "Подключен"}",
                    color = Color(0xFF34D399), fontSize = 13.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(bottom = 10.dp)
                )
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = onSyncSso,
                        enabled = !isSyncingSso,
                        modifier = Modifier.weight(1f).height(48.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF059669)),
                        shape = RoundedCornerShape(14.dp)
                    ) {
                        Text(if (isSyncingSso) "Загрузка..." else "🔄 Синхр. портфель", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    }
                    Button(
                        onClick = onLogoutSso,
                        modifier = Modifier.height(48.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFDC2626)),
                        shape = RoundedCornerShape(14.dp)
                    ) {
                        Text("Выйти", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    }
                }
            } else {
                PrimaryButton("🔑 Войти через EVE Online", Color(0xFF2563EB), onStartSsoLogin)
            }
        }

        Spacer(Modifier.height(14.dp))

        CardContainer {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("⬇️", fontSize = 16.sp)
                Spacer(Modifier.width(8.dp))
                Text(text = "База блокировок", color = Color(0xFFF8FAFC), fontSize = 17.sp, fontWeight = FontWeight.Bold)
            }
            if (isDownloading) {
                Row(modifier = Modifier.fillMaxWidth().padding(top = 16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
                    CircularProgressIndicator(modifier = Modifier.size(24.dp), color = Color(0xFF60A5FA), strokeWidth = 3.dp)
                }
            } else {
                Spacer(Modifier.height(12.dp))
                PrimaryButton("Скачать свежую базу", Color(0xFF8B5CF6), onDownloadBlocklist)
            }
        }

        Spacer(Modifier.height(14.dp))

        CardContainer {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("🔒", fontSize = 16.sp)
                Spacer(Modifier.width(8.dp))
                Text(text = "Исключения (Белый список)", color = Color(0xFFF8FAFC), fontSize = 17.sp, fontWeight = FontWeight.Bold)
            }
            Text(text = "Домены отсюда НИКОГДА не будут блокироваться.", color = Color(0xFF94A3B8), fontSize = 12.sp, modifier = Modifier.padding(top = 6.dp, bottom = 16.dp))
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = newWhitelistDomain, onValueChange = onNewWhitelistDomainChange, modifier = Modifier.weight(1f),
                    textStyle = LocalTextStyle.current.copy(color = Color(0xFFF8FAFC), fontSize = 13.sp),
                    colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = Color(0xFF1E40AF), unfocusedBorderColor = Color(0xFF1E293B), focusedContainerColor = Color(0xFF0A1220), unfocusedContainerColor = Color(0xFF0A1220), cursorColor = Color(0xFF60A5FA)),
                    shape = RoundedCornerShape(14.dp), placeholder = { Text("example.com", color = Color(0xFF64748B), fontSize = 13.sp) }, maxLines = 1, singleLine = true
                )
                Spacer(Modifier.width(8.dp))
                Button(onClick = onAddWhitelist, colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2563EB)), shape = RoundedCornerShape(14.dp), modifier = Modifier.height(56.dp)) {
                    Text("Добавить", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                }
            }
            if (whitelist.isNotEmpty()) {
                Spacer(Modifier.height(12.dp))
                whitelist.forEach { domain ->
                    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Text(text = domain, color = Color(0xFFF8FAFC), fontSize = 13.sp, fontFamily = FontFamily.Monospace)
                        Text("✕", color = Color(0xFFF87171), fontSize = 16.sp, fontWeight = FontWeight.Bold, modifier = Modifier.clickable { onRemoveWhitelist(domain) })
                    }
                }
            }
        }

        Spacer(Modifier.height(14.dp))

        CardContainer {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("✓", fontSize = 16.sp, color = Color(0xFF059669), fontWeight = FontWeight.Bold)
                Spacer(Modifier.width(8.dp))
                Text(text = "Санитайзер ссылок", color = Color(0xFFF8FAFC), fontSize = 17.sp, fontWeight = FontWeight.Bold)
            }
            Text(text = "Вырезает utm_*, yclid, fbclid, gclid, si и другие трекеры.", color = Color(0xFF94A3B8), fontSize = 12.sp, modifier = Modifier.padding(top = 6.dp, bottom = 16.dp))
            OutlinedTextField(
                value = urlInput, onValueChange = onUrlInputChange, modifier = Modifier.fillMaxWidth(),
                textStyle = LocalTextStyle.current.copy(color = Color(0xFFF8FAFC), fontSize = 13.sp),
                colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = Color(0xFF1E40AF), unfocusedBorderColor = Color(0xFF1E293B), focusedContainerColor = Color(0xFF0A1220), unfocusedContainerColor = Color(0xFF0A1220), cursorColor = Color(0xFF60A5FA)),
                shape = RoundedCornerShape(14.dp), placeholder = { Text("Вставьте ссылку…", color = Color(0xFF64748B), fontSize = 13.sp) }, maxLines = 3
            )
            cleaned?.let { url -> Text(text = url, color = Color(0xFF34D399), fontSize = 12.sp, fontFamily = FontFamily.Monospace, modifier = Modifier.padding(top = 12.dp)) }
            Spacer(Modifier.height(16.dp))
            PrimaryButton("Очистить и скопировать", Color(0xFF059669), onSanitizeUrl)
        }

        Spacer(Modifier.height(14.dp))

        CardContainer {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("⚙️", fontSize = 16.sp)
                Spacer(Modifier.width(8.dp))
                Text(text = "Общие", color = Color(0xFFF8FAFC), fontSize = 17.sp, fontWeight = FontWeight.Bold)
            }
            Spacer(Modifier.height(12.dp))

            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(text = "Автозапуск", color = Color(0xFFF8FAFC), fontSize = 15.sp, fontWeight = FontWeight.Bold)
                    Text(text = "Включать защиту после перезагрузки", color = Color(0xFF94A3B8), fontSize = 11.sp)
                }
                Switch(checked = autostartEnabled, onCheckedChange = onAutostartChange, colors = SwitchDefaults.colors(checkedThumbColor = Color.White, checkedTrackColor = Color(0xFF2563EB), uncheckedThumbColor = Color(0xFF64748B), uncheckedTrackColor = Color(0xFF1E293B)))
            }

            Spacer(Modifier.height(16.dp))

            Text(text = "Рабочий капитал (Net Worth ISK)", color = Color(0xFFF8FAFC), fontSize = 15.sp, fontWeight = FontWeight.Bold)
            Text(text = "Считается автоматически по EVE SSO (Кэш + Ордера + Склад, без PLEX).", color = Color(0xFF94A3B8), fontSize = 11.sp, modifier = Modifier.padding(bottom = 8.dp))
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = walletCapitalInput, onValueChange = onWalletCapitalInputChange, modifier = Modifier.weight(1f),
                    textStyle = LocalTextStyle.current.copy(color = Color(0xFFF8FAFC), fontSize = 13.sp, fontFamily = FontFamily.Monospace),
                    colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = Color(0xFF1E40AF), unfocusedBorderColor = Color(0xFF1E293B), focusedContainerColor = Color(0xFF0A1220), unfocusedContainerColor = Color(0xFF0A1220)),
                    shape = RoundedCornerShape(14.dp), placeholder = { Text("65000000000", color = Color(0xFF64748B), fontSize = 13.sp) }, maxLines = 1, singleLine = true
                )
                Spacer(Modifier.width(8.dp))
                Button(onClick = onSaveWalletCapital, colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2563EB)), shape = RoundedCornerShape(14.dp), modifier = Modifier.height(56.dp)) {
                    Text("OK", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                }
            }

            Spacer(Modifier.height(16.dp))

            Text(text = "Себестоимость филаментов на альте", color = Color(0xFFF8FAFC), fontSize = 15.sp, fontWeight = FontWeight.Bold)
            Text(text = "Учет взятых со склада филаментов (прибыль альта не учитывается).", color = Color(0xFF94A3B8), fontSize = 11.sp, modifier = Modifier.padding(bottom = 8.dp))
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = altDebtInput, onValueChange = onAltDebtInputChange, modifier = Modifier.weight(1f),
                    textStyle = LocalTextStyle.current.copy(color = Color(0xFFF8FAFC), fontSize = 13.sp, fontFamily = FontFamily.Monospace),
                    colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = Color(0xFF1E40AF), unfocusedBorderColor = Color(0xFF1E293B), focusedContainerColor = Color(0xFF0A1220), unfocusedContainerColor = Color(0xFF0A1220)),
                    shape = RoundedCornerShape(14.dp), placeholder = { Text("2 691 431 356", color = Color(0xFF64748B), fontSize = 13.sp) }, maxLines = 1, singleLine = true
                )
                Spacer(Modifier.width(8.dp))
                Button(onClick = onSaveAltDebt, colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2563EB)), shape = RoundedCornerShape(14.dp), modifier = Modifier.height(56.dp)) {
                    Text("OK", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                }
            }
        }

        Spacer(Modifier.height(24.dp))
    }
}

@Composable
fun CapitalProgressCard(capital: Long) {
    CardContainer {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("🛒", fontSize = 16.sp)
            Spacer(Modifier.width(8.dp))
            Text(text = "Путь к 1 триллиону", color = Color(0xFFF8FAFC), fontSize = 17.sp, fontWeight = FontWeight.Bold)
        }
        Spacer(Modifier.height(12.dp))

        if (capital > 0) {
            val progress = (capital.toFloat() / CAPITAL_GOAL_ISK.toFloat()).coerceIn(0f, 1f)
            val percent = String.format(Locale.US, "%.2f", progress * 100.0)
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(text = "$percent%", color = Color(0xFF34D399), fontSize = 22.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
                Text(text = "${formatIsk(capital.toDouble())} / 1 трлн", color = Color(0xFF94A3B8), fontSize = 12.sp, fontFamily = FontFamily.Monospace, modifier = Modifier.align(Alignment.CenterVertically))
            }
            Spacer(Modifier.height(8.dp))
            LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth().height(8.dp), color = Color(0xFF34D399), trackColor = Color(0xFF1E293B))
            Spacer(Modifier.height(8.dp))
            val remaining = CAPITAL_GOAL_ISK - capital
            Text(text = "Осталось: ${formatIsk(remaining.toDouble())} ISK", color = Color(0xFF64748B), fontSize = 11.sp, fontFamily = FontFamily.Monospace)
        } else {
            Text(text = "Нажми «🔄 Синхр. SSO» в Терминале, чтобы подтянуть общий рабочий капитал.", color = Color(0xFF94A3B8), fontSize = 12.sp)
        }
    }
}

@Composable
fun TerminalShortcutCard(eveSummaries: List<ItemOrderBookSummary>, cacheTimestamp: Long, onOpenTerminal: () -> Unit) {
    CardContainer {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("🛒", fontSize = 16.sp)
            Spacer(Modifier.width(8.dp))
            Text(text = "EVE Online: Терминал", color = Color(0xFFF8FAFC), fontSize = 17.sp, fontWeight = FontWeight.Bold)
        }
        Text(
            text = if (eveSummaries.isEmpty()) "Сканирование стаканов Jita / Perimeter, SSO и отчёты ИИ."
            else "Кэш: ${eveSummaries.size} позиций · ${formatRelativeTime(cacheTimestamp)}",
            color = Color(0xFF94A3B8), fontSize = 11.sp, modifier = Modifier.padding(top = 4.dp, bottom = 12.dp)
        )
        PrimaryButton("Открыть терминал", Color(0xFFEAB308), onOpenTerminal)
    }
}

@Composable
fun TradePlanCard(summaries: List<ItemOrderBookSummary>, cacheTimestamp: Long, capitalIsk: Long) {
    CardContainer {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("🔄", fontSize = 16.sp)
            Spacer(Modifier.width(8.dp))
            Text(text = "План на день", color = Color(0xFFF8FAFC), fontSize = 17.sp, fontWeight = FontWeight.Bold)
        }
        Text(
            text = when {
                summaries.isEmpty() -> "Нет данных. Зайди в терминал и просканируй стаканы."
                cacheTimestamp > 0 -> "Кэш от ${formatRelativeTime(cacheTimestamp)}"
                else -> "Основано на последнем снимке ESI"
            },
            color = Color(0xFF94A3B8), fontSize = 12.sp, modifier = Modifier.padding(top = 6.dp, bottom = 12.dp)
        )

        if (summaries.isNotEmpty()) {
            val effectiveCapital = if (capitalIsk > 0 && capitalIsk > 10_000_000) capitalIsk.toDouble() else FALLBACK_CAPITAL
            val plan = remember(summaries, capitalIsk) { TradePlanManager.generatePlan(summaries, capitalIsk = effectiveCapital) }
            val totalProfit = plan.sumOf { it.estimatedProfitIsk }

            if (totalProfit > 0) {
                Box(modifier = Modifier.fillMaxWidth().background(Color(0xFF059669).copy(alpha = 0.12f), RoundedCornerShape(12.dp)).padding(12.dp)) {
                    Column {
                        Text("Ожидаемый профит топ-3", color = Color(0xFF34D399), fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        Text("${formatIsk(totalProfit)} ISK", color = Color(0xFF34D399), fontSize = 20.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
                    }
                }
                Spacer(Modifier.height(12.dp))
            }

            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                plan.forEach { action ->
                    Column(modifier = Modifier.fillMaxWidth().background(Color(0xFF0F172A), RoundedCornerShape(10.dp)).padding(12.dp)) {
                        Text(action.title, color = Color(0xFFF8FAFC), fontSize = 13.sp, fontWeight = FontWeight.Bold)
                        Spacer(Modifier.height(4.dp))
                        Text(action.description, color = Color(0xFF94A3B8), fontSize = 11.sp)
                        if (action.priority == 1 && action.estimatedProfitIsk > 0) {
                            Spacer(Modifier.height(6.dp))
                            Text("≈ ${formatIsk(action.estimatedProfitIsk)} ISK при закупке ${action.volumeAtBest} шт", color = Color(0xFF34D399), fontSize = 11.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun HeroCard(isActive: Boolean, onToggle: () -> Unit) {
    Column(modifier = Modifier.fillMaxWidth().background(Brush.linearGradient(listOf(Color(0xFF16284A), Color(0xFF0E1A2E))), RoundedCornerShape(28.dp)).padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        StatusOrb(isActive)
        Spacer(Modifier.height(20.dp))
        Text(text = if (isActive) "ЗАЩИТА АКТИВНА" else "ЗАЩИТА ОТКЛЮЧЕНА", color = if (isActive) Color(0xFF34D399) else Color(0xFFF87171), fontSize = 15.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.5.sp, textAlign = TextAlign.Center)
        Text(text = if (isActive) "DNS-запросы фильтруются на устройстве" else "Нажмите кнопку, чтобы включить локальную фильтрацию", color = Color(0xFF94A3B8), fontSize = 12.sp, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 10.dp, bottom = 24.dp))
        PrimaryButton(text = if (isActive) "ОСТАНОВИТЬ ЗАЩИТУ" else "ВКЛЮЧИТЬ ЗАЩИТУ", color = if (isActive) Color(0xFFDC2626) else Color(0xFF2563EB), onClick = onToggle)
    }
}

@Composable
fun StatusOrb(isActive: Boolean) {
    val color = if (isActive) Color(0xFF34D399) else Color(0xFFF87171)
    val transition = rememberInfiniteTransition(label = "orb")
    val pulseAlpha by transition.animateFloat(initialValue = 1f, targetValue = 0.4f, animationSpec = infiniteRepeatable(tween(1400, easing = LinearEasing), RepeatMode.Reverse), label = "pulseAlpha")
    val effectiveAlpha = if (isActive) pulseAlpha else 0.6f
    Box(modifier = Modifier.size(88.dp), contentAlignment = Alignment.Center) {
        Box(modifier = Modifier.size(88.dp).alpha(effectiveAlpha).background(Brush.radialGradient(listOf(color.copy(alpha = 0.55f), Color.Transparent)), CircleShape))
        Box(modifier = Modifier.size(28.dp).background(color, CircleShape))
    }
}

@Composable
fun StatCell(value: String, label: String, color: Color, modifier: Modifier = Modifier) {
    Column(modifier = modifier.background(Color(0xFF131E33), RoundedCornerShape(18.dp)).padding(vertical = 16.dp, horizontal = 10.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(text = value, color = color, fontSize = 20.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
        Text(text = label, color = Color(0xFF64748B), fontSize = 9.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.8.sp, modifier = Modifier.padding(top = 6.dp))
    }
}

@Composable
fun CardContainer(content: @Composable () -> Unit) {
    Column(modifier = Modifier.fillMaxWidth().background(Color(0xFF111B2E), RoundedCornerShape(24.dp)).padding(22.dp)) { content() }
}

@Composable
fun PrimaryButton(text: String, color: Color, onClick: () -> Unit, enabled: Boolean = true) {
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier.fillMaxWidth().height(52.dp),
        colors = ButtonDefaults.buttonColors(containerColor = color, disabledContainerColor = color.copy(alpha = 0.35f)),
        shape = RoundedCornerShape(16.dp)
    ) {
        Text(text = text, fontSize = 13.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.5.sp)
    }
}

fun startVpn(context: Context) {
    safeStartService(context, Intent(context, ShieldVpnService::class.java).apply { action = "START_SHIELD" })
}

fun stopVpn(context: Context) {
    safeStartService(context, Intent(context, ShieldVpnService::class.java).apply { action = "STOP_SHIELD" })
}

fun safeStartService(context: Context, intent: Intent) {
    try { context.startService(intent) } catch (_: Exception) {}
}

fun copyToClipboard(context: Context, text: String) {
    val cb = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    cb.setPrimaryClip(ClipData.newPlainText("FoldShield", text))
    Toast.makeText(context, "Скопировано: $text", Toast.LENGTH_SHORT).show()
}

fun sanitizeUrl(rawUrl: String): String {
    if (rawUrl.isEmpty()) return ""
    return try {
        val uri = Uri.parse(rawUrl)
        if (uri.host == null) return rawUrl
        val builder = uri.buildUpon().clearQuery()
        for (name in uri.queryParameterNames) {
            val lower = name.lowercase()
            val isTracker = lower.startsWith("utm_") || lower.startsWith("aff_") ||
                    lower == "fbclid" || lower == "gclid" || lower == "yclid" || lower == "ymclid" ||
                    lower == "msclkid" || lower == "igshid" || lower == "si" || lower == "_openstat" ||
                    lower == "twclid" || lower == "ttclid" || lower == "mc_eid" || lower == "_ga" || lower == "_gl"
            if (!isTracker) { uri.getQueryParameters(name).forEach { v -> builder.appendQueryParameter(name, v) } }
        }
        builder.build().toString()
    } catch (_: Exception) { rawUrl }
}

suspend fun downloadBlocklist(context: Context): Boolean = withContext(Dispatchers.IO) {
    return@withContext try {
        val connection = URL("https://raw.githubusercontent.com/StevenBlack/hosts/master/hosts").openConnection() as HttpURLConnection
        connection.connectTimeout = 15000
        connection.readTimeout = 15000
        connection.connect()
        if (connection.responseCode == 200) {
            val file = File(context.filesDir, "custom_blocklist.txt")
            val tempFile = File(context.filesDir, "custom_blocklist.tmp")
            connection.inputStream.use { input -> tempFile.outputStream().use { output -> input.copyTo(output) } }
            val head = try { tempFile.bufferedReader().use { it.readText().take(1000) } } catch (_: Exception) { "" }
            val looksLikeHosts = head.contains("0.0.0.0") || head.contains("127.0.0.1") || head.lineSequence().any { it.trim().startsWith("#") }
            if (looksLikeHosts && tempFile.length() > 10_000) {
                if (file.exists()) file.delete()
                tempFile.renameTo(file)
                true
            } else {
                tempFile.delete()
                false
            }
        } else false
    } catch (_: Exception) { false }
}

fun formatIsk(amount: Double): String {
    if (amount.isNaN() || amount.isInfinite()) return "0"
    return DecimalFormat("#,###", java.text.DecimalFormatSymbols.getInstance(Locale.US)).format(amount)
}

fun formatRelativeTime(timestampMs: Long): String {
    if (timestampMs <= 0) return "—"
    val diffSec = (System.currentTimeMillis() - timestampMs) / 1000
    return when {
        diffSec < 60 -> "${diffSec}с назад"
        diffSec < 3600 -> "${diffSec / 60}м назад"
        diffSec < 86_400 -> "${diffSec / 3600}ч назад"
        else -> "${diffSec / 86_400}д назад"
    }
}
