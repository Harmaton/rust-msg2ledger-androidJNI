package com.harmaton.msg2ledger

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.math.BigDecimal
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

private object AppColors {
    val Ink = Color(0xFF14251F)
    val Muted = Color(0xFF73817A)
    val Green = Color(0xFF167A55)
    val Mint = Color(0xFFDDF4E9)
    val Canvas = Color(0xFFF5F7F4)
    val Card = Color.White
    val Orange = Color(0xFFE9A446)
    val Red = Color(0xFFB63E34)
}

private object Routes {
    const val Splash = "splash"
    const val Home = "home"
    const val Settings = "settings"
    const val Detail = "detail"
}

private data class CategorySpend(val name: String, val spentCents: Long, val count: Int)

private data class Counterparty(
    val name: String,
    val spentCents: Long,
    val count: Int,
    val category: String,
    val limitCents: Long,
    val todaySpentCents: Long,
    val weeklySpentCents: Long,
)

private data class Dashboard(
    val spentCents: Long,
    val receivedCents: Long,
    val transactionCount: Int,
    val averageDailySpendCents: Long,
    val weeklySpentCents: Long,
    val categories: List<CategorySpend>,
    val counterparties: List<Counterparty>,
    val insights: List<String>,
)

private data class PayeeTransaction(
    val code: String,
    val kind: String,
    val category: String,
    val amountCents: Long,
    val date: String,
    val time: String,
)

private data class CounterpartyReport(
    val category: String,
    val limitCents: Long,
    val totalSpentCents: Long,
    val transactionCount: Int,
    val weeklyAverageCents: Long,
    val busiestDay: String?,
    val weekdaySpending: List<Pair<String, Long>>,
    val transactions: List<PayeeTransaction>,
)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                Msg2LedgerApp(
                    openDatabase = {
                        withContext(Dispatchers.IO) {
                            val database = getDatabasePath("ledger.db")
                            database.parentFile?.mkdirs()
                            check(NativeBridge.nativeOpen(database.absolutePath)) {
                                "Could not open your private ledger database."
                            }
                            val result = NativeHost.nativeStart(
                                arrayOf("--root", database.parentFile!!.absolutePath),
                            )
                            check(result == 0 || result == 1) {
                                NativeHost.nativeLastError().ifBlank {
                                    "Could not start the background ledger service (code $result)."
                                }
                            }
                            val interval = getSharedPreferences(PREFERENCES, MODE_PRIVATE)
                                .getInt("notification_interval_hours", 24)
                            scheduleSpendingNotifications(this@MainActivity, interval)
                        }
                    },
                    openAppSettings = {
                        startActivity(
                            Intent(
                                Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                Uri.fromParts("package", packageName, null),
                            ),
                        )
                    },
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun Msg2LedgerApp(
    openDatabase: suspend () -> Unit,
    openAppSettings: () -> Unit,
) {
    val context = LocalContext.current
    val navController = rememberNavController()
    val backStack by navController.currentBackStackEntryAsState()
    val currentRoute = backStack?.destination?.route
    var selectedCounterparty by rememberSaveable { mutableStateOf("") }
    var refreshKey by remember { mutableStateOf(0) }
    var permissionVersion by remember { mutableStateOf(0) }
    val scope = rememberCoroutineScope()
    val smsPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { permissionVersion++ }
    val notificationPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { permissionVersion++ }

    LaunchedEffect(currentRoute) {
        if (currentRoute == Routes.Home) refreshKey++
    }

    val showTabs = currentRoute == Routes.Home || currentRoute == Routes.Settings
    Scaffold(
        containerColor = AppColors.Canvas,
        topBar = {
            if (currentRoute != Routes.Splash && currentRoute != null) {
                TopAppBar(
                    navigationIcon = {
                        if (currentRoute == Routes.Detail) {
                            IconButton(onClick = { navController.popBackStack() }) {
                                Icon(Icons.Filled.ArrowBack, contentDescription = "Back")
                            }
                        }
                    },
                    title = {
                        Column {
                            Text(
                                when (currentRoute) {
                                    Routes.Home -> "msg2ledger"
                                    Routes.Settings -> "Settings"
                                    else -> selectedCounterparty
                                },
                                color = AppColors.Ink,
                                fontWeight = FontWeight.Bold,
                            )
                            if (currentRoute == Routes.Home) {
                                Text(
                                    LocalDate.now().format(
                                        DateTimeFormatter.ofPattern("EEEE, d MMMM", Locale.getDefault()),
                                    ),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = AppColors.Muted,
                                )
                            }
                        }
                    },
                    actions = {
                        if (currentRoute == Routes.Home) {
                            IconButton(onClick = { refreshKey++ }) {
                                Icon(Icons.Filled.Refresh, contentDescription = "Refresh ledger")
                            }
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = AppColors.Canvas,
                    ),
                )
            }
        },
        bottomBar = {
            if (showTabs) {
                NavigationBar(containerColor = AppColors.Card) {
                    listOf(Routes.Home to "Home", Routes.Settings to "Settings").forEach { (route, label) ->
                        val selected = backStack?.destination?.hierarchy
                            ?.any { it.route == route } == true
                        NavigationBarItem(
                            selected = selected,
                            onClick = {
                                navController.navigate(route) {
                                    popUpTo(navController.graph.findStartDestination().id) {
                                        saveState = true
                                    }
                                    launchSingleTop = true
                                    restoreState = true
                                }
                            },
                            icon = {
                                Icon(
                                    if (route == Routes.Home) Icons.Filled.Home else Icons.Filled.Settings,
                                    contentDescription = null,
                                )
                            },
                            label = { Text(label) },
                        )
                    }
                }
            }
        },
    ) { padding ->
        NavHost(
            navController = navController,
            startDestination = Routes.Splash,
            modifier = Modifier.padding(padding),
        ) {
            composable(Routes.Splash) {
                SplashScreen(openDatabase) {
                    navController.navigate(Routes.Home) {
                        popUpTo(Routes.Splash) { inclusive = true }
                        launchSingleTop = true
                    }
                }
            }
            composable(Routes.Home) {
                HomeScreen(
                    refreshKey = refreshKey,
                    onCounterpartyClick = { counterparty ->
                        selectedCounterparty = counterparty
                        navController.navigate(Routes.Detail)
                    },
                )
            }
            composable(Routes.Settings) {
                SettingsScreen(
                    context = context,
                    permissionVersion = permissionVersion,
                    onRequestSms = {
                        smsPermission.launch(Manifest.permission.RECEIVE_SMS)
                    },
                    onRequestNotifications = {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                        }
                    },
                    openAppSettings = openAppSettings,
                )
            }
            composable(Routes.Detail) {
                CounterpartyScreen(
                    counterparty = selectedCounterparty,
                    onSaved = { refreshKey++ },
                )
            }
        }
    }
}

@Composable
private fun SplashScreen(openDatabase: suspend () -> Unit, onFinished: () -> Unit) {
    var message by remember { mutableStateOf("Preparing your private ledger…") }
    LaunchedEffect(Unit) {
        try {
            openDatabase()
            onFinished()
        } catch (error: Exception) {
            message = "Couldn't prepare the local ledger: ${error.message ?: "unknown error"}"
        }
    }
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(28.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Box(
                modifier = Modifier
                    .size(82.dp)
                    .clip(RoundedCornerShape(26.dp))
                    .background(AppColors.Green),
                contentAlignment = Alignment.Center,
            ) {
                Text("M", color = Color.White, fontSize = 40.sp, fontWeight = FontWeight.Bold)
            }
            Spacer(Modifier.height(18.dp))
            Text("msg2ledger", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(10.dp))
            Text(message, color = AppColors.Muted, textAlign = TextAlign.Center)
        }
    }
}

@Composable
private fun HomeScreen(
    refreshKey: Int,
    onCounterpartyClick: (String) -> Unit,
) {
    var dashboard by remember { mutableStateOf<Dashboard?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    fun refresh() {
        scope.launch {
            loading = true
            error = null
            try {
                dashboard = withContext(Dispatchers.IO) {
                    parseDashboard(
                        NativeBridge.nativeDashboard(LocalDate.now().toString()),
                    )
                }
            } catch (exception: Exception) {
                error = exception.message ?: "Could not load the ledger."
            } finally {
                loading = false
            }
        }
    }

    LaunchedEffect(refreshKey) { refresh() }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 18.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        error?.let { ErrorCard(it, onRetry = ::refresh) }
        dashboard?.let { report ->
            SpendingSummaryCard(report)
            BudgetOverview(context, report)
            if (report.insights.isNotEmpty()) {
                SectionHeading("Spending coach", "ON-DEVICE INSIGHTS")
                CardSurface {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        report.insights.forEach { insight ->
                            Row(verticalAlignment = Alignment.Top) {
                                Text("✦", color = AppColors.Green, fontSize = 18.sp)
                                Spacer(Modifier.width(10.dp))
                                Text(insight, color = AppColors.Ink)
                            }
                        }
                    }
                }
            }
            SectionHeading("Where it goes", "TODAY BY CATEGORY")
            if (report.categories.isEmpty()) {
                EmptyHint("No spending categories for today yet.")
            } else {
                CardSurface {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        report.categories.forEach { category ->
                            AmountRow(
                                category.name,
                                "${category.count} payments",
                                formatKsh(category.spentCents),
                            )
                        }
                    }
                }
            }
            SectionHeading("Regulars", "TAP FOR HISTORY & LIMITS")
            if (report.counterparties.isEmpty()) {
                EmptyHint("Your PayBill, Pochi and other regulars will appear after M-PESA messages are captured.")
            } else {
                CardSurface {
                    Column {
                        report.counterparties.take(12).forEachIndexed { index, payee ->
                            if (index > 0) HorizontalDivider(color = AppColors.Canvas)
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { onCounterpartyClick(payee.name) }
                                    .padding(vertical = 13.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Column(Modifier.weight(1f)) {
                                    Text(payee.name, fontWeight = FontWeight.SemiBold, color = AppColors.Ink)
                                    Text(
                                        "${payee.category} · ${payee.count} ${
                                            if (payee.count == 1) "payment" else "payments"
                                        }",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = AppColors.Muted,
                                    )
                                }
                                Column(horizontalAlignment = Alignment.End) {
                                    Text(formatKsh(payee.spentCents), fontWeight = FontWeight.SemiBold)
                                    if (payee.limitCents > 0) {
                                        Text(
                                            "Daily cap ${formatKsh(payee.limitCents)}",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = AppColors.Muted,
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
        if (dashboard == null && !loading && error == null) {
            EmptyHint("Your local spending insights are loading.")
        }
        if (dashboard?.transactionCount == 0) {
            EmptyHint("No M-PESA payments recorded today. New messages from MPESA are captured in the background.")
        }
        Spacer(Modifier.height(8.dp))
    }
}

@Composable
private fun SpendingSummaryCard(report: Dashboard) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(26.dp),
        colors = CardDefaults.cardColors(containerColor = AppColors.Green),
    ) {
        Column(
            modifier = Modifier.padding(22.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                "SPENT TODAY",
                color = Color.White.copy(alpha = 0.78f),
                style = MaterialTheme.typography.labelLarge,
                letterSpacing = 1.1.sp,
            )
            Text(
                formatKsh(report.spentCents),
                color = Color.White,
                style = MaterialTheme.typography.headlineLarge,
                fontWeight = FontWeight.Bold,
            )
            Text(
                "${report.transactionCount} transactions · ${formatKsh(report.receivedCents)} received",
                color = Color.White.copy(alpha = 0.82f),
            )
        }
    }
}

@Composable
private fun BudgetOverview(context: Context, report: Dashboard) {
    val preferences = remember {
        context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
    }
    val dailyBudget = preferences.getLong("daily_budget_cents", 0)
    val weeklyBudget = preferences.getLong("weekly_budget_cents", 0)
    SectionHeading("Budgets", "SET IN SETTINGS")
    CardSurface {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            BudgetLine("Today", report.spentCents, dailyBudget)
            HorizontalDivider(color = AppColors.Canvas)
            BudgetLine("Last 7 days", report.weeklySpentCents, weeklyBudget)
        }
    }
}

@Composable
private fun BudgetLine(label: String, spent: Long, budget: Long) {
    Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(label, modifier = Modifier.weight(1f), fontWeight = FontWeight.Medium)
            Text(
                if (budget > 0) "${formatKsh(spent)} / ${formatKsh(budget)}" else "${formatKsh(spent)} · no budget set",
                color = if (budget > 0 && spent > budget) AppColors.Red else AppColors.Muted,
                style = MaterialTheme.typography.bodySmall,
            )
        }
        if (budget > 0) {
            val progress = (spent.toFloat() / budget.toFloat()).coerceIn(0f, 1f)
            androidx.compose.material3.LinearProgressIndicator(
                progress = { progress },
                modifier = Modifier.fillMaxWidth(),
                color = if (spent > budget) AppColors.Red else AppColors.Green,
                trackColor = AppColors.Mint,
            )
        }
    }
}

@Composable
private fun CounterpartyScreen(counterparty: String, onSaved: () -> Unit) {
    var report by remember(counterparty) { mutableStateOf<CounterpartyReport?>(null) }
    var category by remember(counterparty) { mutableStateOf("Other") }
    var limitAmount by remember(counterparty) { mutableStateOf("") }
    var error by remember(counterparty) { mutableStateOf<String?>(null) }
    var saving by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    fun refresh() {
        scope.launch {
            error = null
            try {
                val loaded = withContext(Dispatchers.IO) {
                    parseCounterpartyReport(NativeBridge.nativeCounterpartyDetail(counterparty))
                }
                report = loaded
                category = loaded.category.takeIf {
                    it in listOf(
                        "Shopping",
                        "Food",
                        "Transport",
                        "Bills",
                        "Airtime",
                        "Miscellaneous",
                        "Gifts",
                        "Other",
                    )
                } ?: "Other"
                limitAmount = if (loaded.limitCents > 0) formatAmount(loaded.limitCents) else ""
            } catch (exception: Exception) {
                error = exception.message ?: "Could not load this payment history."
            }
        }
    }

    LaunchedEffect(counterparty) { refresh() }
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 18.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        error?.let { ErrorCard(it, onRetry = ::refresh) }
        report?.let { details ->
            CardSurface {
                Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
                    Text("TOTAL SPENT", style = MaterialTheme.typography.labelMedium, color = AppColors.Muted)
                    Text(formatKsh(details.totalSpentCents), style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
                    Text("${details.transactionCount} transactions · ${formatKsh(details.weeklyAverageCents)} average per week")
                }
            }
            SectionHeading("Spending pattern", "ALL AVAILABLE HISTORY")
            CardSurface {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        details.busiestDay?.let { "You spend most here on $it." }
                            ?: "No outgoing payments found for this regular yet.",
                        color = AppColors.Ink,
                    )
                    details.weekdaySpending.forEach { (day, amount) ->
                        AmountRow(day, "weekday total", formatKsh(amount))
                    }
                }
            }
            SectionHeading("Category & limit", "CAP IS PER DAY")
            CardSurface {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("Assign a category", fontWeight = FontWeight.Medium)
                    CategoryPicker(category) { category = it }
                    OutlinedTextField(
                        value = limitAmount,
                        onValueChange = { limitAmount = it },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("Daily alert limit (Ksh)") },
                        supportingText = { Text("Leave blank to turn off this payee's daily alert.") },
                        singleLine = true,
                    )
                    Button(
                        onClick = {
                            scope.launch {
                                saving = true
                                error = null
                                try {
                                    val cents = parseCents(limitAmount) ?: 0L
                                    check(cents >= 0) { "Enter a valid non-negative Ksh amount." }
                                    check(withContext(Dispatchers.IO) {
                                        NativeBridge.nativeSetCounterpartySettings(
                                            counterparty,
                                            category,
                                            cents,
                                        )
                                    }) { "Could not save this payee's settings." }
                                    onSaved()
                                    refresh()
                                } catch (exception: Exception) {
                                    error = exception.message ?: "Could not save the limit."
                                } finally {
                                    saving = false
                                }
                            }
                        },
                        enabled = !saving,
                        colors = ButtonDefaults.buttonColors(containerColor = AppColors.Green),
                    ) {
                        Text(if (saving) "Saving…" else "Save category and limit")
                    }
                }
            }
            SectionHeading("Transactions", "NEWEST FIRST")
            if (details.transactions.isEmpty()) {
                EmptyHint("There are no parsed transactions for this regular yet.")
            } else {
                CardSurface {
                    Column {
                        details.transactions.forEachIndexed { index, transaction ->
                            if (index > 0) HorizontalDivider(color = AppColors.Canvas)
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 12.dp),
                                verticalArrangement = Arrangement.spacedBy(3.dp),
                            ) {
                                AmountRow(
                                    transaction.category,
                                    "${transaction.date} · ${transaction.time}",
                                    formatKsh(transaction.amountCents),
                                )
                                Text(
                                    "${transaction.kind.replaceFirstChar { it.uppercase() }} · ${transaction.code}",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = AppColors.Muted,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun CategoryPicker(value: String, onSelected: (String) -> Unit) {
    val categories = listOf(
        "Shopping",
        "Food",
        "Transport",
        "Bills",
        "Airtime",
        "Miscellaneous",
        "Gifts",
        "Other",
    )
    categories.chunked(3).forEach { row ->
        Row(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
            row.forEach { option ->
                FilterChip(
                    selected = value == option,
                    onClick = { onSelected(option) },
                    label = { Text(option) },
                )
            }
        }
    }
}

@Composable
private fun SettingsScreen(
    context: Context,
    permissionVersion: Int,
    onRequestSms: () -> Unit,
    onRequestNotifications: () -> Unit,
    openAppSettings: () -> Unit,
) {
    @Suppress("UNUSED_VARIABLE")
    val permissionRefresh = permissionVersion
    val smsAllowed = remember(permissionVersion) {
        ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.RECEIVE_SMS,
        ) == PackageManager.PERMISSION_GRANTED
    }
    val notificationsAllowed = remember(permissionVersion) {
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS,
            ) == PackageManager.PERMISSION_GRANTED
    }
    val preferences = remember {
        context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
    }
    var dailyBudget by remember {
        mutableStateOf(formatAmount(preferences.getLong("daily_budget_cents", 0)))
    }
    var weeklyBudget by remember {
        mutableStateOf(formatAmount(preferences.getLong("weekly_budget_cents", 0)))
    }
    var interval by remember {
        mutableStateOf(preferences.getInt("notification_interval_hours", 24))
    }
    var status by remember { mutableStateOf<String?>(null) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 18.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        SettingsGroup("BACKGROUND CAPTURE") {
            SettingsStatusRow(
                "M-PESA SMS access",
                if (smsAllowed) "Enabled · MPESA sender only" else "Permission required",
            )
            Text(
                "Only messages broadcast by sender MPESA are queued. Message contents and the ledger remain on this device.",
                color = AppColors.Muted,
                style = MaterialTheme.typography.bodySmall,
            )
            Button(
                onClick = if (smsAllowed) openAppSettings else onRequestSms,
                colors = ButtonDefaults.buttonColors(containerColor = AppColors.Green),
            ) {
                Text(if (smsAllowed) "Review app permissions" else "Allow SMS capture")
            }
            if (!smsAllowed) {
                OutlinedButton(onClick = openAppSettings) {
                    Text("Open app settings if Android blocks this permission")
                }
            }
        }
        SettingsGroup("SPENDING CHECK-INS") {
            Text("Notify me", fontWeight = FontWeight.Medium)
            val intervals = listOf(
                1 to "1 hour",
                2 to "2 hours",
                3 to "3 hours",
                24 to "Daily",
                168 to "Weekly",
            )
            intervals.chunked(3).forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                    row.forEach { (hours, label) ->
                        FilterChip(
                            selected = interval == hours,
                            onClick = { interval = hours },
                            label = { Text(label) },
                        )
                    }
                }
            }
            Text(
                "Android schedules background work inexactly and may defer a check to save battery.",
                color = AppColors.Muted,
                style = MaterialTheme.typography.bodySmall,
            )
            SettingsStatusRow(
                "Notifications",
                if (notificationsAllowed) "Enabled" else "Permission required",
            )
            if (!notificationsAllowed) {
                OutlinedButton(onClick = onRequestNotifications) {
                    Text("Allow notifications")
                }
            }
        }
        SettingsGroup("BUDGETS") {
            OutlinedTextField(
                value = dailyBudget,
                onValueChange = { dailyBudget = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Daily budget (Ksh)") },
                singleLine = true,
            )
            OutlinedTextField(
                value = weeklyBudget,
                onValueChange = { weeklyBudget = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Weekly budget (Ksh)") },
                singleLine = true,
            )
            Button(
                onClick = {
                    val daily = parseCents(dailyBudget)
                    val weekly = parseCents(weeklyBudget)
                    if (daily == null || weekly == null) {
                        status = "Enter valid non-negative amounts for both budgets."
                    } else {
                        preferences.edit()
                            .putLong("daily_budget_cents", daily)
                            .putLong("weekly_budget_cents", weekly)
                            .apply()
                        try {
                            scheduleSpendingNotifications(context, interval)
                            status = "Budgets and check-in schedule saved."
                        } catch (exception: IllegalArgumentException) {
                            status = "Could not schedule the selected reminder: ${exception.message}"
                        }
                    }
                },
                colors = ButtonDefaults.buttonColors(containerColor = AppColors.Green),
            ) {
                Text("Save budgets and schedule")
            }
            status?.let { Text(it, color = AppColors.Green, style = MaterialTheme.typography.bodySmall) }
        }
        SettingsGroup("YOUR DATA") {
            SettingsStatusRow("Database", "Private on-device SQLite")
            SettingsStatusRow("Background jobs", "Apalis queue supervised by Rivium")
            Text(
                "M-PESA messages are retained locally to parse and review. Notifications are generated on the device; no external AI or financial service is contacted.",
                color = AppColors.Muted,
                style = MaterialTheme.typography.bodySmall,
            )
        }
        Spacer(Modifier.height(8.dp))
    }
}

@Composable
private fun SettingsGroup(title: String, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(9.dp)) {
        SectionHeading(title)
        CardSurface(content)
    }
}

@Composable
private fun SettingsStatusRow(title: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(title, fontWeight = FontWeight.Medium, color = AppColors.Ink)
        Spacer(Modifier.width(10.dp))
        Text(value, color = AppColors.Muted, style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun CardSurface(content: @Composable () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(22.dp),
        colors = CardDefaults.cardColors(containerColor = AppColors.Card),
    ) {
        Column(
            modifier = Modifier.padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            content = { content() },
        )
    }
}

@Composable
private fun SectionHeading(title: String, eyebrow: String? = null) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        if (eyebrow != null) {
            Text(
                eyebrow,
                color = AppColors.Muted,
                style = MaterialTheme.typography.labelSmall,
                letterSpacing = 1.sp,
            )
        }
        Text(title, color = AppColors.Ink, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun AmountRow(title: String, subtitle: String, amount: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, fontWeight = FontWeight.Medium, color = AppColors.Ink)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = AppColors.Muted)
        }
        Text(amount, fontWeight = FontWeight.SemiBold, color = AppColors.Ink)
    }
}

@Composable
private fun EmptyHint(text: String) {
    CardSurface {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(AppColors.Mint),
                contentAlignment = Alignment.Center,
            ) {
                Text("i", color = AppColors.Green, fontWeight = FontWeight.Bold)
            }
            Spacer(Modifier.width(10.dp))
            Text(text, color = AppColors.Muted, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
private fun ErrorCard(message: String, onRetry: () -> Unit) {
    CardSurface {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(message, color = AppColors.Red)
            OutlinedButton(onClick = onRetry) { Text("Try again") }
        }
    }
}

private fun parseDashboard(encoded: String): Dashboard {
    val json = JSONObject(encoded)
    return Dashboard(
        spentCents = json.optLong("spent_cents"),
        receivedCents = json.optLong("received_cents"),
        transactionCount = json.optInt("transaction_count"),
        averageDailySpendCents = json.optLong("average_daily_spend_cents"),
        weeklySpentCents = json.optLong("weekly_spent_cents"),
        categories = json.array("categories") { item ->
            CategorySpend(
                item.optString("name"),
                item.optLong("spent_cents"),
                item.optInt("count"),
            )
        },
        counterparties = json.array("counterparties") { item ->
            Counterparty(
                name = item.optString("name"),
                spentCents = item.optLong("spent_cents"),
                count = item.optInt("count"),
                category = item.optString("category"),
                limitCents = item.optLong("limit_cents"),
                todaySpentCents = item.optLong("today_spent_cents"),
                weeklySpentCents = item.optLong("weekly_spent_cents"),
            )
        },
        insights = json.stringArray("insights"),
    )
}

private fun parseCounterpartyReport(encoded: String): CounterpartyReport {
    val json = JSONObject(encoded)
    return CounterpartyReport(
        category = json.optString("category", "Other"),
        limitCents = json.optLong("limit_cents"),
        totalSpentCents = json.optLong("total_spent_cents"),
        transactionCount = json.optInt("transaction_count"),
        weeklyAverageCents = json.optLong("weekly_average_cents"),
        busiestDay = json.optString("busiest_day").takeUnless { it == "null" || it.isBlank() },
        weekdaySpending = json.array("weekday_spending") {
            it.optString("day") to it.optLong("spent_cents")
        }.filter { (_, amount) -> amount > 0 },
        transactions = json.array("transactions") { item ->
            PayeeTransaction(
                code = item.optString("code"),
                kind = item.optString("kind"),
                category = item.optString("category"),
                amountCents = item.optLong("amount_cents"),
                date = item.optString("date"),
                time = item.optString("time"),
            )
        },
    )
}

private fun <T> JSONObject.array(key: String, parse: (JSONObject) -> T): List<T> {
    val array = optJSONArray(key) ?: return emptyList()
    return (0 until array.length()).mapNotNull { index ->
        val item = array.opt(index)
        when (item) {
            is JSONObject -> parse(item)
            else -> null
        }
    }
}

private fun JSONObject.stringArray(key: String): List<String> {
    val array = optJSONArray(key) ?: return emptyList()
    return (0 until array.length()).map(array::optString)
}

private fun formatKsh(cents: Long): String {
    val whole = (cents / 100).toString()
    return "Ksh${whole.reversed().chunked(3).joinToString(",").reversed()}"
}

private fun formatAmount(cents: Long): String =
    BigDecimal.valueOf(cents, 2).stripTrailingZeros().toPlainString()

private fun parseCents(value: String): Long? {
    if (value.isBlank()) return 0
    return runCatching {
        BigDecimal(value.replace(",", "").trim())
            .movePointRight(2)
            .longValueExact()
            .takeIf { it >= 0 }
    }.getOrNull()
}
