package com.foldshield.blocker

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
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

    var currentTab by remember { mutableStateOf(AppTab.TERMINAL) }
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

    var isFetchingEve by remember { mutableStateOf(false) }
    var selectedCategory by remember { mutableStateOf(TradeCategory.ACTIVE) }
    var filterBestRoiOnly by remember { mutableStateOf(false) }
    var searchQuery by remember { mutableStateOf("") }
    var sortMode by remember { mutableStateOf(SortMode.ROI) }
    var eveSummaries by remember { mutableStateOf<List<ItemOrderBookSummary>>(emptyList()) }
    var eveError by remember { mutableStateOf<String?>(null) }
    var cacheTimestamp by remember { mutableLongStateOf(0L) }

    BackHandler(enabled = currentTab != AppTab.TERMINAL) {
        currentTab = AppTab.TERMINAL
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

    LaunchedEffect(Unit) {
        val cached = EveApiClient.loadCache(context)
        if (cached.isNotEmpty()) {
            eveSummaries = cached
            cacheTimestamp = EveApiClient.getCacheTime(context)
        }
    }

    Scaffold(
        containerColor = Color(0xFF0A0F1C),
        bottomBar = {
            NavigationBar(containerColor = Color(0xFF111B2E)) {
                NavigationBarItem(
                    selected = currentTab == AppTab.TERMINAL,
                    onClick = { currentTab = AppTab.TERMINAL },
                    icon = { Text("📊", fontSize = 20.sp) },
                    label = { Text("Терминал", fontSize = 11.sp) },
                    colors = foldNavColors()
                )
                NavigationBarItem(
                    selected = currentTab == AppTab.HOME,
                    onClick = { currentTab = AppTab.HOME },
                    icon = { Text("🎯", fontSize = 20.sp) },
                    label = { Text("План 1T ISK", fontSize = 11.sp) },
                    colors = foldNavColors()
                )
                NavigationBarItem(
                    selected = currentTab == AppTab.SETTINGS,
                    onClick = { currentTab = AppTab.SETTINGS },
                    icon = { Text("⚙️", fontSize = 20.sp) },
                    label = { Text("Портфель / SSO", fontSize = 11.sp) },
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
                    walletCapital = walletCapital,
                    eveSummaries = eveSummaries,
                    cacheTimestamp = cacheTimestamp,
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
                            coroutineScope.launch {
                                val res = EveAuthManager.syncCharacterData(context, eveSummaries)
                                res.onSuccess { data ->
                                    walletCapital = data.totalNetWorthIsk
                                    walletCapitalInput = data.totalNetWorthIsk.toString()
                                    prefs.edit().putLong("wallet_capital_isk", data.totalNetWorthIsk).apply()
                                    ssoSummary = data.summaryForAi
                                    Toast.makeText(context, "Капитал: ${formatIsk(data.totalNetWorthIsk.toDouble())} ISK", Toast.LENGTH_SHORT).show()
                                }.onFailure { err ->
                                    eveError = "SSO: ${err.localizedMessage}"
                                }
                                isSyncingSso = false
                            }
                        }
                    },
                    onUpdateItemCost = { typeId, newCost ->
                        EveApiClient.setCustomCostBasis(context, typeId, newCost)
                        val updated = eveSummaries.map { s ->
                            if (s.item.typeId == typeId) s.copy(item = s.item.copy(myCostBasis = newCost)) else s
                        }
                        eveSummaries = updated
                        EveApiClient.saveCache(context, updated)
                        Toast.makeText(context, "Себестоимость сохранена", Toast.LENGTH_SHORT).show()
                    },
                    onCopyReport = {
                        val displayed = applyFilters(eveSummaries, filterBestRoiOnly, searchQuery, sortMode)
                        val report = EveApiClient.buildAiReport(displayed.ifEmpty { eveSummaries }, ssoSummary, altDebtIsk)
                        copyToClipboard(context, report)
                    }
                )

                AppTab.SETTINGS -> SettingsScreen(
                    altDebtInput = altDebtInput,
                    walletCapitalInput = walletCapitalInput,
                    ssoClientIdInput = ssoClientIdInput,
                    isSsoLogged = isSsoLogged,
                    ssoCharName = ssoCharName,
                    isSyncingSso = isSyncingSso,
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
    walletCapital: Long,
    eveSummaries: List<ItemOrderBookSummary>,
    cacheTimestamp: Long,
    onOpenTerminal: () -> Unit
) {
    Column(modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp)) {
        Text(
            text = "●  GALAXY Z FOLD 5  ·  EVE MARKET TERMINAL",
            color = Color(0xFF60A5FA),
            fontSize = 10.sp,
            fontWeight = FontWeight.Bold,
            fontFamily = FontFamily.Monospace,
            letterSpacing = 1.2.sp
        )
        Spacer(Modifier.height(8.dp))
        Text(text = "FoldShield EVE", color = Color(0xFFF8FAFC), fontSize = 32.sp, fontWeight = FontWeight.Bold)
        Text(
            text = "Торговый терминал Jita 4-4 / Perimeter · Анализ спредов и 30-дневной истории ESI",
            color = Color(0xFF94A3B8),
            fontSize = 12.sp,
            modifier = Modifier.padding(top = 4.dp)
        )
        Spacer(Modifier.height(18.dp))
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
    val configuration = LocalConfiguration.current
    val isUnfoldedFold5 = configuration.screenWidthDp >= 600

    val displayedList = remember(eveSummaries, filterBestRoiOnly, searchQuery, sortMode) {
        applyFilters(eveSummaries, filterBestRoiOnly, searchQuery, sortMode)
    }

    var selectedItemId by remember(displayedList) {
        mutableStateOf(displayedList.firstOrNull()?.item?.typeId)
    }

    Column(modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 12.dp)) {
        // Шапка + Кнопки быстрого действия
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(text = "Jita 4-4 / Perimeter", color = Color(0xFFF8FAFC), fontSize = 20.sp, fontWeight = FontWeight.Bold)
                Text(
                    text = if (isUnfoldedFold5) "Режим: Galaxy Z Fold 5 (Раскрытый экран)" else "Режим: Galaxy Z Fold 5 (Внешний экран)",
                    color = Color(0xFF60A5FA),
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace
                )
            }
            Button(
                onClick = onScan,
                enabled = !isFetchingEve,
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFEAB308)),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.height(42.dp)
            ) {
                Text(
                    text = if (isFetchingEve) "Сканируем..." else "🔄 Сканировать ESI",
                    color = Color(0xFF0A0F1C),
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        }

        Spacer(Modifier.height(10.dp))

        // Категории
        Row(
            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            TradeCategory.values().forEach { cat ->
                val isSel = cat == selectedCategory
                Box(
                    modifier = Modifier
                        .background(if (isSel) Color(0xFFEAB308) else Color(0xFF111B2E), RoundedCornerShape(10.dp))
                        .clickable { onCategoryChange(cat) }
                        .padding(horizontal = 12.dp, vertical = 8.dp)
                ) {
                    Text(
                        text = cat.title,
                        color = if (isSel) Color(0xFF0A0F1C) else Color(0xFF94A3B8),
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }

        Spacer(Modifier.height(8.dp))

        // Поиск + Сортировка + Кнопки SSO / Отчет ИИ
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            OutlinedTextField(
                value = searchQuery,
                onValueChange = onSearchChange,
                modifier = Modifier.weight(1f),
                textStyle = LocalTextStyle.current.copy(color = Color(0xFFF8FAFC), fontSize = 12.sp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = Color(0xFF1E40AF),
                    unfocusedBorderColor = Color(0xFF1E293B),
                    focusedContainerColor = Color(0xFF111B2E),
                    unfocusedContainerColor = Color(0xFF111B2E)
                ),
                shape = RoundedCornerShape(12.dp),
                placeholder = { Text("Поиск по названию или typeId...", color = Color(0xFF64748B), fontSize = 11.sp) },
                maxLines = 1,
                singleLine = true
            )
            Button(
                onClick = onSyncSso,
                enabled = !isSyncingSso,
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2563EB)),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.height(50.dp)
            ) {
                Text(if (isSyncingSso) "⏳" else "🔑 SSO", fontSize = 11.sp, fontWeight = FontWeight.Bold)
            }
            Button(
                onClick = onCopyReport,
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF059669)),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.height(50.dp)
            ) {
                Text("📋 Отчет ИИ", fontSize = 11.sp, fontWeight = FontWeight.Bold)
            }
        }

        Spacer(Modifier.height(8.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            SortMode.values().forEach { mode ->
                val sel = sortMode == mode
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .background(if (sel) Color(0xFF2563EB) else Color(0xFF111B2E), RoundedCornerShape(10.dp))
                        .clickable { onSortModeChange(mode) }
                        .padding(vertical = 8.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(text = mode.label, color = if (sel) Color.White else Color(0xFF94A3B8), fontSize = 11.sp, fontWeight = FontWeight.Bold)
                }
            }
            Box(
                modifier = Modifier
                    .background(if (filterBestRoiOnly) Color(0xFF059669) else Color(0xFF111B2E), RoundedCornerShape(10.dp))
                    .clickable { onFilterChange(!filterBestRoiOnly) }
                    .padding(horizontal = 10.dp, vertical = 8.dp)
            ) {
                Text(text = "ROI ≥ 6%", color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold)
            }
        }

        if (isFetchingEve) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center
            ) {
                CircularProgressIndicator(modifier = Modifier.size(18.dp), color = Color(0xFFEAB308), strokeWidth = 2.5.dp)
                Spacer(Modifier.width(8.dp))
                Text(text = "Опрашиваем стаканы и 30д историю ESI...", color = Color(0xFFEAB308), fontSize = 12.sp)
            }
        }

        eveError?.let { error ->
            Text(text = "⚠️ $error", color = Color(0xFFF87171), fontSize = 11.sp, modifier = Modifier.padding(top = 6.dp))
        }

        Spacer(Modifier.height(10.dp))

        if (displayedList.isEmpty() && !isFetchingEve) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    text = "Нажмите «Сканировать ESI», чтобы загрузить живые стаканы и графики цен.",
                    color = Color(0xFF64748B),
                    fontSize = 13.sp,
                    textAlign = TextAlign.Center
                )
            }
        } else if (isUnfoldedFold5) {
            // РАСКРЫТЫЙ ЭКРАН GALAXY Z FOLD 5: 2 КОЛОНКИ (СЛЕВА СПИСОК, СПРАВА ГРАФИК И СТАКАН)
            val activeSummary = displayedList.find { it.item.typeId == selectedItemId } ?: displayedList.firstOrNull()
            Row(modifier = Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Column(
                    modifier = Modifier
                        .weight(0.42f)
                        .fillMaxHeight()
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    displayedList.forEach { s ->
                        val isSelected = activeSummary?.item?.typeId == s.item.typeId
                        ExpandableOrderBookCard(
                            summary = s,
                            isExpanded = false,
                            isSelectedHighlight = isSelected,
                            onToggleExpand = { selectedItemId = s.item.typeId },
                            onSaveCostBasis = { newCost -> onUpdateItemCost(s.item.typeId, newCost) },
                            onCopyPrice = { price -> copyToClipboard(context, String.format(Locale.US, "%.0f", price)) }
                        )
                    }
                }

                Column(
                    modifier = Modifier
                        .weight(0.58f)
                        .fillMaxHeight()
                        .verticalScroll(rememberScrollState())
                ) {
                    activeSummary?.let { s ->
                        ExpandableOrderBookCard(
                            summary = s,
                            isExpanded = true,
                            isSelectedHighlight = true,
                            onToggleExpand = {},
                            onSaveCostBasis = { newCost -> onUpdateItemCost(s.item.typeId, newCost) },
                            onCopyPrice = { price -> copyToClipboard(context, String.format(Locale.US, "%.0f", price)) }
                        )
                    }
                }
            }
        } else {
            // ВНЕШНИЙ ЭКРАН GALAXY Z FOLD 5: РАСКРЫВАЮЩИЕСЯ КАРТОЧКИ С ГРАФИКОМ ВНУТРИ
            Column(
                modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                displayedList.forEach { s ->
                    val isExpanded = selectedItemId == s.item.typeId
                    ExpandableOrderBookCard(
                        summary = s,
                        isExpanded = isExpanded,
                        isSelectedHighlight = isExpanded,
                        onToggleExpand = {
                            selectedItemId = if (isExpanded) null else s.item.typeId
                        },
                        onSaveCostBasis = { newCost -> onUpdateItemCost(s.item.typeId, newCost) },
                        onCopyPrice = { price -> copyToClipboard(context, String.format(Locale.US, "%.0f", price)) }
                    )
                }
                Spacer(Modifier.height(20.dp))
            }
        }
    }
}

@Composable
fun ExpandableOrderBookCard(
    summary: ItemOrderBookSummary,
    isExpanded: Boolean,
    isSelectedHighlight: Boolean = false,
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

    val borderColor = if (isSelectedHighlight) Color(0xFF3B82F6) else Color(0xFF1E293B)

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color(0xFF111B2E), RoundedCornerShape(14.dp))
            .border(1.dp, borderColor, RoundedCornerShape(14.dp))
            .clickable { onToggleExpand() }
            .padding(14.dp)
    ) {
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = summary.item.name,
                color = Color(0xFFF8FAFC),
                fontSize = 14.sp,
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

        if (isExpanded) {
            Spacer(Modifier.height(12.dp))
            Box(modifier = Modifier.fillMaxWidth().height(1.dp).background(Color(0xFF1E293B)))
            Spacer(Modifier.height(12.dp))

            // 1. ГРАФИК ИСТОРИИ ЦЕН ESI (7Д / 14Д / 30Д)
            PriceHistoryChartCompose(
                history = summary.history30d ?: emptyList()
            )

            Spacer(Modifier.height(12.dp))

            // 2. БЫСТРОЕ КОПИРОВАНИЕ ЦЕНЫ С ШАГОМ
            if (summary.bestBuy > 0 && summary.bestSell > 0) {
                val step = if (summary.bestSell >= 100_000_000) 100_000.0 else 10_000.0
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = { onCopyPrice(summary.bestBuy + step) },
                        modifier = Modifier.weight(1f).height(40.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1E3A8A)),
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        Text("📋 Buy + шаг", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    }
                    Button(
                        onClick = { onCopyPrice((summary.bestSell - step).coerceAtLeast(1.0)) },
                        modifier = Modifier.weight(1f).height(40.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF065F46)),
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        Text("📋 Sell - шаг", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    }
                }
                Spacer(Modifier.height(10.dp))
            }

            // 3. ТОП-5 SELL И ТОП-5 BUY
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

            // 4. КАЛЬКУЛЯТОР СЕБЕСТОИМОСТИ И РЕ-ЛИСТА
            val currentCost = summary.item.myCostBasis
            if (currentCost != null && currentCost > 0) {
                val breakEvenSell = currentCost / EveApiClient.SELL_NET_MULT
                val myNetProfit = (summary.bestSell * EveApiClient.SELL_NET_MULT) - currentCost
                val myRoi = (myNetProfit / currentCost) * 100.0
                val oneRelistFee = summary.bestSell * EveApiClient.RELIST_FEE_RATE

                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Color(0xFF0A0F1C), RoundedCornerShape(10.dp))
                        .padding(10.dp)
                ) {
                    Text("📊 Защита позиции:", color = Color(0xFF60A5FA), fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    Text("• Твоя себестоимость: ${formatIsk(currentCost)} ISK", color = Color(0xFFCBD5E1), fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                    Text("• Точка безубытка (Jita Sell): ${formatIsk(breakEvenSell)} ISK", color = Color(0xFFEAB308), fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                    Text("• Твой профит по текущему Sell: ${formatIsk(myNetProfit)} ISK (${String.format(Locale.US, "%.1f", myRoi)}%)", color = if (myNetProfit >= 0) Color(0xFF34D399) else Color(0xFFF87171), fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                    Text("• Цена 1 ре-листа (0.292%): -${formatIsk(oneRelistFee)} ISK", color = Color(0xFFF87171), fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                }
                Spacer(Modifier.height(8.dp))
            }

            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = costInput,
                    onValueChange = { costInput = it.filter { ch -> ch.isDigit() } },
                    modifier = Modifier.weight(1f),
                    textStyle = LocalTextStyle.current.copy(color = Color(0xFFF8FAFC), fontSize = 12.sp, fontFamily = FontFamily.Monospace),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = Color(0xFF1E40AF),
                        unfocusedBorderColor = Color(0xFF1E293B),
                        focusedContainerColor = Color(0xFF0A0F1C),
                        unfocusedContainerColor = Color(0xFF0A0F1C)
                    ),
                    shape = RoundedCornerShape(10.dp),
                    placeholder = { Text("Моя себестоимость (ISK)", color = Color(0xFF64748B), fontSize = 11.sp) },
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

@Composable
fun PriceHistoryChartCompose(
    history: List<EsiHistoryEntry>,
    modifier: Modifier = Modifier
) {
    var selectedDays by remember { mutableStateOf(30) }
    val points = remember(history, selectedDays) {
        if (history.size <= selectedDays) history else history.takeLast(selectedDays)
    }

    if (points.size < 2) {
        Text(
            text = "История цен ESI появится после сканирования категории",
            color = Color(0xFF64748B),
            fontSize = 11.sp
        )
        return
    }

    val minPrice = remember(points) { points.minOf { it.lowest }.coerceAtLeast(1.0) }
    val maxPrice = remember(points) { points.maxOf { it.highest }.coerceAtLeast(minPrice + 1.0) }
    val avgVol = remember(points) { points.map { it.volume }.average().toLong() }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(Color(0xFF0A0F1C), RoundedCornerShape(12.dp))
            .padding(12.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text(
                    text = "📈 График истории цен ESI (The Forge)",
                    color = Color(0xFFF8FAFC),
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = "Мин: ${formatIsk(minPrice)} · Макс: ${formatIsk(maxPrice)} · Ср.Vol: $avgVol/д",
                    color = Color(0xFF94A3B8),
                    fontSize = 10.sp,
                    fontFamily = FontFamily.Monospace
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                listOf(7, 14, 30).forEach { d ->
                    val sel = selectedDays == d
                    Box(
                        modifier = Modifier
                            .background(if (sel) Color(0xFF2563EB) else Color(0xFF111B2E), RoundedCornerShape(6.dp))
                            .clickable { selectedDays = d }
                            .padding(horizontal = 8.dp, vertical = 4.dp)
                    ) {
                        Text(text = "${d}Д", color = Color.White, fontSize = 10.sp, fontFamily = FontFamily.Monospace)
                    }
                }
            }
        }

        Spacer(Modifier.height(8.dp))

        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(140.dp)
                .padding(vertical = 4.dp)
        ) {
            val w = size.width
            val h = size.height
            val priceSpan = (maxPrice - minPrice).coerceAtLeast(1.0)
            fun yOf(price: Double): Float =
                (h - ((price - minPrice) / priceSpan * h)).toFloat().coerceIn(0f, h)

            for (i in 0..2) {
                val y = h * (i / 2f)
                drawLine(
                    color = Color(0xFF1E293B),
                    start = Offset(0f, y),
                    end = Offset(w, y),
                    strokeWidth = 1.dp.toPx()
                )
            }

            val avgPath = Path()
            val highPath = Path()
            val lowPath = Path()
            val stepX = w / (points.size - 1).coerceAtLeast(1)

            points.forEachIndexed { idx, pt ->
                val x = idx * stepX
                val yAvg = yOf(pt.average)
                val yHigh = yOf(pt.highest)
                val yLow = yOf(pt.lowest)
                if (idx == 0) {
                    avgPath.moveTo(x, yAvg)
                    highPath.moveTo(x, yHigh)
                    lowPath.moveTo(x, yLow)
                } else {
                    avgPath.lineTo(x, yAvg)
                    highPath.lineTo(x, yHigh)
                    lowPath.lineTo(x, yLow)
                }
            }

            drawPath(
                path = highPath,
                color = Color(0xFF60A5FA).copy(alpha = 0.55f),
                style = Stroke(width = 1.5.dp.toPx())
            )
            drawPath(
                path = lowPath,
                color = Color(0xFF34D399).copy(alpha = 0.55f),
                style = Stroke(width = 1.5.dp.toPx())
            )
            drawPath(
                path = avgPath,
                color = Color(0xFFEAB308),
                style = Stroke(width = 2.2.dp.toPx())
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text("— Желтая: Средняя ESI", color = Color(0xFFEAB308), fontSize = 9.sp)
            Text("— Синяя: Макс. дня", color = Color(0xFF60A5FA), fontSize = 9.sp)
            Text("— Зеленая: Мин. дня", color = Color(0xFF34D399), fontSize = 9.sp)
        }
    }
}

private fun applyFilters(
    source: List<ItemOrderBookSummary>,
    filterBestRoiOnly: Boolean,
    searchQuery: String,
    sortMode: SortMode
): List<ItemOrderBookSummary> {
    val filtered = source.filter { s ->
        val matchesSearch = searchQuery.isBlank() ||
                s.item.name.contains(searchQuery, ignoreCase = true) ||
                s.item.typeId.toString().contains(searchQuery)
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
    altDebtInput: String,
    walletCapitalInput: String,
    ssoClientIdInput: String,
    isSsoLogged: Boolean,
    ssoCharName: String?,
    isSyncingSso: Boolean,
    onSsoClientIdChange: (String) -> Unit,
    onStartSsoLogin: () -> Unit,
    onSyncSso: () -> Unit,
    onLogoutSso: () -> Unit,
    onAltDebtInputChange: (String) -> Unit,
    onSaveAltDebt: () -> Unit,
    onWalletCapitalInputChange: (String) -> Unit,
    onSaveWalletCapital: () -> Unit
) {
    Column(modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp)) {
        Text(text = "Портфель и EVE SSO", color = Color(0xFFF8FAFC), fontSize = 26.sp, fontWeight = FontWeight.Bold)
        Text(
            text = "Авторизация персонажа, рабочий капитал и учет себестоимости",
            color = Color(0xFF94A3B8),
            fontSize = 12.sp,
            modifier = Modifier.padding(top = 6.dp, bottom = 14.dp)
        )

        CardContainer {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("🔑", fontSize = 16.sp)
                Spacer(Modifier.width(8.dp))
                Text(text = "Авторизация EVE Online (SSO)", color = Color(0xFFF8FAFC), fontSize = 17.sp, fontWeight = FontWeight.Bold)
            }
            Text(
                text = "Callback URL для панели CCP: eveauth-foldshield://callback",
                color = Color(0xFF60A5FA),
                fontSize = 11.sp,
                fontFamily = FontFamily.Monospace,
                modifier = Modifier.padding(top = 6.dp, bottom = 10.dp)
            )
            OutlinedTextField(
                value = ssoClientIdInput,
                onValueChange = onSsoClientIdChange,
                modifier = Modifier.fillMaxWidth(),
                textStyle = LocalTextStyle.current.copy(color = Color(0xFFF8FAFC), fontSize = 12.sp, fontFamily = FontFamily.Monospace),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = Color(0xFF1E40AF),
                    unfocusedBorderColor = Color(0xFF1E293B),
                    focusedContainerColor = Color(0xFF0A1220),
                    unfocusedContainerColor = Color(0xFF0A1220)
                ),
                shape = RoundedCornerShape(14.dp),
                placeholder = { Text("Вставь Client ID из developers.eveonline.com", color = Color(0xFF64748B), fontSize = 12.sp) },
                maxLines = 1,
                singleLine = true
            )
            Spacer(Modifier.height(12.dp))
            if (isSsoLogged) {
                Text(
                    text = "✓ Персонаж: ${ssoCharName ?: "Подключен"}",
                    color = Color(0xFF34D399),
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(bottom = 10.dp)
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
                Text("💰", fontSize = 16.sp)
                Spacer(Modifier.width(8.dp))
                Text(text = "Капитал и учет филаментов", color = Color(0xFFF8FAFC), fontSize = 17.sp, fontWeight = FontWeight.Bold)
            }
            Spacer(Modifier.height(12.dp))
            Text(text = "Рабочий капитал (Net Worth ISK):", color = Color(0xFF94A3B8), fontSize = 12.sp)
            Spacer(Modifier.height(6.dp))
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = walletCapitalInput,
                    onValueChange = onWalletCapitalInputChange,
                    modifier = Modifier.weight(1f),
                    textStyle = LocalTextStyle.current.copy(color = Color(0xFFF8FAFC), fontSize = 13.sp, fontFamily = FontFamily.Monospace),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = Color(0xFF1E40AF),
                        unfocusedBorderColor = Color(0xFF1E293B),
                        focusedContainerColor = Color(0xFF0A1220),
                        unfocusedContainerColor = Color(0xFF0A1220)
                    ),
                    shape = RoundedCornerShape(14.dp),
                    placeholder = { Text("Например 65420000000", color = Color(0xFF64748B), fontSize = 12.sp) },
                    maxLines = 1,
                    singleLine = true
                )
                Spacer(Modifier.width(8.dp))
                Button(
                    onClick = onSaveWalletCapital,
                    modifier = Modifier.height(52.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2563EB)),
                    shape = RoundedCornerShape(14.dp)
                ) {
                    Text("Сохр.", fontWeight = FontWeight.Bold)
                }
            }

            Spacer(Modifier.height(14.dp))
            Text(text = "Себестоимость филаментов на альте (ISK):", color = Color(0xFF94A3B8), fontSize = 12.sp)
            Spacer(Modifier.height(6.dp))
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = altDebtInput,
                    onValueChange = onAltDebtInputChange,
                    modifier = Modifier.weight(1f),
                    textStyle = LocalTextStyle.current.copy(color = Color(0xFFF8FAFC), fontSize = 13.sp, fontFamily = FontFamily.Monospace),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = Color(0xFF1E40AF),
                        unfocusedBorderColor = Color(0xFF1E293B),
                        focusedContainerColor = Color(0xFF0A1220),
                        unfocusedContainerColor = Color(0xFF0A1220)
                    ),
                    shape = RoundedCornerShape(14.dp),
                    maxLines = 1,
                    singleLine = true
                )
                Spacer(Modifier.width(8.dp))
                Button(
                    onClick = onSaveAltDebt,
                    modifier = Modifier.height(52.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF059669)),
                    shape = RoundedCornerShape(14.dp)
                ) {
                    Text("Сохр.", fontWeight = FontWeight.Bold)
                }
            }
        }
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
fun CapitalProgressCard(capital: Long) {
    val activeCap = if (capital > 0) capital.toDouble() else FALLBACK_CAPITAL
    val progress = (activeCap / CAPITAL_GOAL_ISK.toDouble()).toFloat().coerceIn(0f, 1f)
    val percentStr = String.format(Locale.US, "%.2f", progress * 100)

    CardContainer {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text("🎯 ПУТЬ К 1 ТРИЛЛИОНУ ISK", color = Color(0xFFEAB308), fontSize = 12.sp, fontWeight = FontWeight.Bold)
            Text("$percentStr%", color = Color(0xFF34D399), fontSize = 14.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
        }
        Spacer(Modifier.height(8.dp))
        Text("${formatIsk(activeCap)} ISK", color = Color(0xFFF8FAFC), fontSize = 22.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
        Spacer(Modifier.height(10.dp))
        LinearProgressIndicator(
            progress = { progress },
            modifier = Modifier.fillMaxWidth().height(10.dp),
            color = Color(0xFFEAB308),
            trackColor = Color(0xFF0A1220)
        )
    }
}

@Composable
fun TerminalShortcutCard(
    eveSummaries: List<ItemOrderBookSummary>,
    cacheTimestamp: Long,
    onOpenTerminal: () -> Unit
) {
    CardContainer {
        Text("⚡ БЫСТРЫЙ ПЕРЕХОД В ТЕРМИНАЛ", color = Color(0xFF60A5FA), fontSize = 12.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(6.dp))
        Text(
            text = if (eveSummaries.isNotEmpty()) "Загружено позиций: ${eveSummaries.size} (обновлено ${formatRelativeTime(cacheTimestamp)})"
            else "Открой терминал и нажми «Сканировать ESI»",
            color = Color(0xFF94A3B8),
            fontSize = 12.sp
        )
        Spacer(Modifier.height(12.dp))
        PrimaryButton("Открыть торговый терминал", Color(0xFF2563EB), onOpenTerminal)
    }
}

@Composable
fun TradePlanCard(summaries: List<ItemOrderBookSummary>, cacheTimestamp: Long, capitalIsk: Long = 0L) {
    val activeCap = if (capitalIsk > 0) capitalIsk.toDouble() else FALLBACK_CAPITAL
    val plan = remember(summaries, activeCap) { TradePlanManager.generatePlan(summaries, activeCap) }
    val totalProfit = plan.sumOf { it.estimatedProfitIsk }

    CardContainer {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text("📋 ТОРГОВЫЙ ПЛАН (ТОП-3)", color = Color(0xFFF8FAFC), fontSize = 15.sp, fontWeight = FontWeight.Bold)
            Text(formatRelativeTime(cacheTimestamp), color = Color(0xFF64748B), fontSize = 11.sp, fontFamily = FontFamily.Monospace)
        }
        Spacer(Modifier.height(12.dp))
        if (totalProfit > 0) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color(0xFF059669).copy(alpha = 0.12f), RoundedCornerShape(12.dp))
                    .padding(12.dp)
            ) {
                Column {
                    Text("Ожидаемый профит топ-3", color = Color(0xFF34D399), fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    Text("${formatIsk(totalProfit)} ISK", color = Color(0xFF34D399), fontSize = 20.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
                }
            }
            Spacer(Modifier.height(12.dp))
        }
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            plan.forEach { action ->
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Color(0xFF0F172A), RoundedCornerShape(10.dp))
                        .padding(12.dp)
                ) {
                    Text(action.title, color = Color(0xFFF8FAFC), fontSize = 13.sp, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(4.dp))
                    Text(action.description, color = Color(0xFF94A3B8), fontSize = 11.sp)
                    if (action.priority == 1 && action.estimatedProfitIsk > 0) {
                        Spacer(Modifier.height(6.dp))
                        Text(
                            text = "≈ ${formatIsk(action.estimatedProfitIsk)} ISK при закупке ${action.volumeAtBest} шт",
                            color = Color(0xFF34D399),
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            fontFamily = FontFamily.Monospace
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun CardContainer(content: @Composable () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color(0xFF111B2E), RoundedCornerShape(24.dp))
            .padding(22.dp)
    ) {
        content()
    }
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

fun copyToClipboard(context: Context, text: String) {
    val cb = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    cb.setPrimaryClip(ClipData.newPlainText("FoldShield", text))
    Toast.makeText(context, "Скопировано: $text", Toast.LENGTH_SHORT).show()
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