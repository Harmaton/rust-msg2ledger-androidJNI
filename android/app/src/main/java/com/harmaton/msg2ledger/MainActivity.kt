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
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.animation.graphics.ExperimentalAnimationGraphicsApi
import androidx.compose.animation.graphics.res.animatedVectorResource
import androidx.compose.animation.graphics.res.rememberAnimatedVectorPainter
import androidx.compose.animation.graphics.vector.AnimatedImageVector
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
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
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
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
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.layout.ContentScale
import androidx.compose.foundation.rememberScrollState
import androidx.core.content.ContextCompat
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.json.JSONArray
import java.math.BigDecimal
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale
import androidx.credentials.CredentialManager
import androidx.credentials.CustomCredential
import androidx.credentials.GetCredentialRequest
import com.google.android.libraries.identity.googleid.GetGoogleIdOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import com.google.android.libraries.identity.googleid.GoogleIdTokenParsingException
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.GoogleAuthProvider
import kotlinx.coroutines.tasks.await

private object Routes {
    const val Onboarding = "onboarding"
    const val Splash = "splash"
    const val Home = "home"
    const val Institutions = "institutions"
    const val Settings = "settings"
    const val Detail = "detail"
    const val Profile = "profile"
    const val Payments = "payments"
    const val TrackedPaybills = "tracked-paybills"
}

private val LedgerCategories = listOf(
    "Send",
    "Receive",
    "PayBill",
    "BuyGoods",
    "Pochi",
    "Airtime",
    "Withdrawal",
)

private val LightColors = lightColorScheme(
    primary = Color(0xFF167A55),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFDDF4E9),
    onPrimaryContainer = Color(0xFF0C422E),
    background = Color(0xFFF5F7F4),
    onBackground = Color(0xFF14251F),
    surface = Color.White,
    onSurface = Color(0xFF14251F),
    surfaceVariant = Color(0xFFEBEFEB),
    onSurfaceVariant = Color(0xFF73817A),
    outline = Color(0xFF73817A),
    error = Color(0xFFB63E34),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFF23A072),
    onPrimary = Color.White,
    primaryContainer = Color(0xFF0C422E),
    onPrimaryContainer = Color(0xFFDDF4E9),
    background = Color(0xFF0F1412),
    onBackground = Color(0xFFE2E8E4),
    surface = Color(0xFF161C19),
    onSurface = Color(0xFFE2E8E4),
    surfaceVariant = Color(0xFF222B27),
    onSurfaceVariant = Color(0xFF9AA8A2),
    outline = Color(0xFF73817A),
    error = Color(0xFFE57368),
)

private data class CategorySpend(
    val name: String,
    val spentCents: Long,
    val count: Int,
    val largestSpendCents: Long = 0,
)

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

private data class LedgerTransaction(
    val code: String,
    val kind: String,
    val category: String,
    val amountCents: Long,
    val counterparty: String,
    val date: String,
    val time: String,
)

private data class TrackedPaybill(
    val name: String,
    val transactionCount: Int,
    val amountCents: Long,
    val transactions: List<LedgerTransaction>,
)
private data class TransactionPage(
    val transactions: List<LedgerTransaction>,
    val nextOffset: Int,
    val hasMore: Boolean,
)

private data class InstitutionSummary(
    val name: String,
    val totalCents: Long,
    val sentCents: Long,
    val receivedCents: Long,
    val transactionCount: Int,
    val transactions: List<LedgerTransaction>,
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
            val context = LocalContext.current
            val preferences = remember {
                context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
            }
            var themeMode by rememberSaveable {
                mutableStateOf(preferences.getString("theme_mode", "system") ?: "system")
            }
            val darkTheme = when (themeMode) {
                "dark" -> true
                "light" -> false
                else -> isSystemInDarkTheme()
            }
            val firebaseAuth = remember { initializeFirebaseAuth(context) }

            MaterialTheme(
                colorScheme = if (darkTheme) DarkColors else LightColors,
            ) {
                Msg2LedgerApp(
                    firebaseAuth = firebaseAuth,
                    themeMode = themeMode,
                    onThemeChange = { newMode ->
                        themeMode = newMode
                        preferences.edit().putString("theme_mode", newMode).apply()
                    },
                    openDatabase = {
                        withContext(Dispatchers.IO) {
                            val database = getDatabasePath("ledger.db")
                            database.parentFile?.mkdirs()
                            check(NativeBridge.nativeOpen(database.absolutePath)) {
                                "Could not open your private ledger database."
                            }
                            ensureLedgerHostStarted(database.parentFile!!.absolutePath)
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
    firebaseAuth: FirebaseAuth?,
    themeMode: String,
    onThemeChange: (String) -> Unit,
    openDatabase: suspend () -> Unit,
    openAppSettings: () -> Unit,
) {
    val context = LocalContext.current
    val preferences = remember { context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE) }
    val onboardingCompleted = remember { preferences.getBoolean("onboarding_completed", false) }
    val startDest = if (onboardingCompleted) Routes.Splash else Routes.Onboarding

    val navController = rememberNavController()
    val backStack by navController.currentBackStackEntryAsState()
    val currentRoute = backStack?.destination?.route
    var selectedCounterparty by rememberSaveable { mutableStateOf("") }
    var signedInEmail by remember { mutableStateOf(firebaseAuth?.currentUser?.email) }
    var refreshKey by remember { mutableStateOf(0) }
    var permissionVersion by remember { mutableStateOf(0) }
    val smsPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { permissionVersion++ }
    val notificationPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { permissionVersion++ }
    var showAiInsightsInfo by rememberSaveable { mutableStateOf(false) }

    LaunchedEffect(currentRoute) {
        if (currentRoute == Routes.Home) refreshKey++
    }

    val showTabs = currentRoute == Routes.Home ||
        currentRoute == Routes.Institutions ||
        currentRoute == Routes.Settings
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            if (currentRoute != Routes.Splash && currentRoute != Routes.Onboarding && currentRoute != null) {
                TopAppBar(
                    navigationIcon = {
                        if (currentRoute != Routes.Home &&
                            currentRoute != Routes.Institutions &&
                            currentRoute != Routes.Settings &&
                            currentRoute != Routes.Splash &&
                            currentRoute != Routes.Onboarding
                        ) {
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
                                    Routes.Institutions -> "Institutions"
                                    Routes.Settings -> "Settings"
                                    Routes.Profile -> "Profile"
                                    Routes.Payments -> "Payments"
                                    Routes.TrackedPaybills -> "Tracked PayBills"
                                    else -> selectedCounterparty
                                },
                                color = MaterialTheme.colorScheme.onBackground,
                                fontWeight = FontWeight.Bold,
                            )
                            if (currentRoute == Routes.Home) {
                                Text(
                                    LocalDate.now().format(
                                        DateTimeFormatter.ofPattern("EEEE, d MMMM", Locale.getDefault()),
                                    ),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
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
                        containerColor = MaterialTheme.colorScheme.background,
                    ),
                )
            }
        },
        bottomBar = {
            if (showTabs) {
                NavigationBar(containerColor = MaterialTheme.colorScheme.surface) {
                    listOf(
                        Routes.Home to "Home",
                        Routes.Institutions to "Banks",
                        Routes.Settings to "Settings",
                    ).forEach { (route, label) ->
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
                                when (route) {
                                    Routes.Home -> Icon(Icons.Filled.Home, contentDescription = null)
                                    Routes.Institutions -> Text(
                                        "M",
                                        fontWeight = FontWeight.Bold,
                                        color = MaterialTheme.colorScheme.primary,
                                    )
                                    else -> Icon(Icons.Filled.Settings, contentDescription = null)
                                }
                            },
                            label = { Text(label) },
                        )
                    }
                }
            }
        },
        floatingActionButton = {
            if (currentRoute == Routes.Home) {
                FloatingActionButton(onClick = { showAiInsightsInfo = true }) {
                    Text("AI", fontWeight = FontWeight.Bold)
                }
            }
        },
    ) { padding ->
        NavHost(
            navController = navController,
            startDestination = startDest,
            modifier = Modifier.padding(padding),
        ) {
            composable(Routes.Onboarding) {
                OnboardingScreen {
                    context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
                        .edit()
                        .putBoolean("onboarding_completed", true)
                        .apply()
                    navController.navigate(Routes.Splash) {
                        popUpTo(Routes.Onboarding) { inclusive = true }
                        launchSingleTop = true
                    }
                }
            }
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
            composable(Routes.Institutions) { InstitutionsScreen() }
            composable(Routes.Settings) {
                SettingsScreen(
                    context = context,
                    permissionVersion = permissionVersion,
                    themeMode = themeMode,
                    onThemeChange = onThemeChange,
                    onRequestSms = {
                        smsPermission.launch(Manifest.permission.RECEIVE_SMS)
                    },
                    onRequestNotifications = {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                        }
                    },
                    openAppSettings = openAppSettings,
                    onProfileClick = { navController.navigate(Routes.Profile) },
                    onPaymentsClick = { navController.navigate(Routes.Payments) },
                    onTrackedPaybillsClick = { navController.navigate(Routes.TrackedPaybills) },
                )
            }
            composable(Routes.Detail) {
                CounterpartyScreen(
                    counterparty = selectedCounterparty,
                    onSaved = { refreshKey++ },
                )
            }
            composable(Routes.Profile) {
                ProfileScreen(
                    auth = firebaseAuth,
                    userEmail = signedInEmail,
                    onAuthChanged = { signedInEmail = it?.email },
                )
            }
            composable(Routes.Payments) { PaymentsComingSoonScreen() }
            composable(Routes.TrackedPaybills) { TrackedPaybillsScreen() }
        }
    }
    if (showAiInsightsInfo) {
        AlertDialog(
            onDismissRequest = { showAiInsightsInfo = false },
            title = { Text("AI insights coming later") },
            text = {
                Text(
                    "This button is reserved for future insights. No transactions are sent to an AI service in this version.",
                )
            },
            confirmButton = {
                TextButton(onClick = { showAiInsightsInfo = false }) { Text("Got it") }
            },
        )
    }
}

@Composable
private fun OnboardingScreen(onFinished: () -> Unit) {
    val pages = listOf(
        Triple(
            R.drawable.ob_privacy,
            "100% Private & Local",
            "Your financial data stays strictly on your device. No cloud sync, complete privacy.",
        ),
        Triple(
            R.drawable.ob_tracking,
            "Automated M-PESA Capture",
            "Automatically queues and parses incoming M-PESA SMS messages in the background as they arrive.",
        ),
        Triple(
            R.drawable.ob_insights,
            "Smart Insights & Budgets",
            "Track daily spending, set payee limits, and receive smart on-device financial guidance.",
        ),
    )
    var currentPage by remember { mutableStateOf(0) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(horizontal = 24.dp, vertical = 16.dp),
    ) {
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState()),
            contentAlignment = Alignment.Center,
        ) {
            AnimatedContent(
                targetState = currentPage,
                transitionSpec = { fadeIn(tween(240)) togetherWith fadeOut(tween(150)) },
                label = "onboarding-page",
            ) { page ->
                val pageContent = pages[page]
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 18.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    Box(
                        modifier = Modifier
                            .size(200.dp)
                            .shadow(12.dp, RoundedCornerShape(42.dp))
                            .clip(RoundedCornerShape(42.dp))
                            .background(
                                Brush.linearGradient(
                                    listOf(
                                        MaterialTheme.colorScheme.primaryContainer,
                                        MaterialTheme.colorScheme.surface,
                                    ),
                                ),
                            ),
                        contentAlignment = Alignment.Center,
                    ) {
                        Image(
                            painter = painterResource(id = pageContent.first),
                            contentDescription = null,
                            modifier = Modifier.size(150.dp),
                        )
                    }
                    Spacer(Modifier.height(30.dp))
                    Text(
                        pageContent.second,
                        style = MaterialTheme.typography.headlineMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onBackground,
                        textAlign = TextAlign.Center,
                    )
                    Spacer(Modifier.height(12.dp))
                    Text(
                        pageContent.third,
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                    )
                }
            }
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                pages.indices.forEach { index ->
                    Box(
                        modifier = Modifier
                            .size(if (index == currentPage) 24.dp else 8.dp, 8.dp)
                            .clip(RoundedCornerShape(4.dp))
                            .background(
                                if (index == currentPage) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.3f),
                            ),
                    )
                }
            }
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(
                    "${currentPage + 1} / ${pages.size}",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Button(
                    onClick = {
                        if (currentPage < pages.lastIndex) currentPage++ else onFinished()
                    },
                    shape = RoundedCornerShape(16.dp),
                ) {
                    Text(if (currentPage == pages.lastIndex) "Get Started" else "Next")
                }
            }
        }
    }
}

@OptIn(ExperimentalAnimationGraphicsApi::class)
@Composable
private fun SplashScreen(openDatabase: suspend () -> Unit, onFinished: () -> Unit) {
    var message by remember { mutableStateOf("Preparing your private ledger…") }
    val image = AnimatedImageVector.animatedVectorResource(R.drawable.avd_logo_animated)
    var atEnd by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        atEnd = true
        try {
            openDatabase()
            delay(1200)
            onFinished()
        } catch (error: Exception) {
            message = "Couldn't prepare the local ledger: ${error.message ?: "unknown error"}"
        }
    }
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(28.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Image(
                painter = rememberAnimatedVectorPainter(image, atEnd),
                contentDescription = "msg2ledger logo",
                modifier = Modifier.size(110.dp),
            )
            Spacer(Modifier.height(18.dp))
            Text(
                "msg2ledger",
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onBackground,
            )
            Spacer(Modifier.height(10.dp))
            Text(
                message,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
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
    var selectedCategoryFilter by rememberSaveable { mutableStateOf("All") }
    var categoryTransactions by remember { mutableStateOf<List<LedgerTransaction>>(emptyList()) }
    var transactionOffset by remember { mutableStateOf(0) }
    var hasMoreTransactions by remember { mutableStateOf(false) }
    var loadingTransactions by remember { mutableStateOf(false) }
    var transactionError by remember { mutableStateOf<String?>(null) }
    var transactionToLink by remember { mutableStateOf<LedgerTransaction?>(null) }
    var paybillName by remember { mutableStateOf("") }
    var linkError by remember { mutableStateOf<String?>(null) }
    var linking by remember { mutableStateOf(false) }
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
    suspend fun loadTransactionPage(reset: Boolean) {
        if (loadingTransactions || (!reset && !hasMoreTransactions)) return
        val offset = if (reset) 0 else transactionOffset
        if (reset) {
            categoryTransactions = emptyList()
            transactionOffset = 0
            hasMoreTransactions = false
        }
        loadingTransactions = true
        transactionError = null
        try {
            val page = withContext(Dispatchers.IO) {
                parseTransactionPage(
                    NativeBridge.nativeCategoryTransactions(
                        LocalDate.now().toString(),
                        selectedCategoryFilter,
                        offset,
                        30,
                    ),
                )
            }
            categoryTransactions = if (reset) {
                page.transactions
            } else {
                categoryTransactions + page.transactions
            }
            transactionOffset = page.nextOffset
            hasMoreTransactions = page.hasMore
        } catch (exception: Exception) {
            transactionError = exception.message
                ?: if (reset) "Could not load transactions." else "Could not load more transactions."
        } finally {
            loadingTransactions = false
        }
    }

    LaunchedEffect(refreshKey, selectedCategoryFilter) {
        loadTransactionPage(reset = true)
    }

    suspend fun loadMoreTransactions() {
        loadTransactionPage(reset = false)
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 18.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        error?.let { err ->
            item { ErrorCard(err, onRetry = ::refresh) }
        }
        dashboard?.let { report ->
            item { SpendingSummaryCard(report) }
            item { BudgetOverview(context, report) }
            if (report.insights.isNotEmpty()) {
                item {
                    SectionHeading("Spending coach", "ON-DEVICE INSIGHTS")
                    CardSurface {
                        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            report.insights.forEach { insight ->
                                Row(verticalAlignment = Alignment.Top) {
                                    Text("✦", color = MaterialTheme.colorScheme.primary, fontSize = 18.sp)
                                    Spacer(Modifier.width(10.dp))
                                    Text(insight, color = MaterialTheme.colorScheme.onSurface)
                                }
                            }
                        }
                    }
                }
            }
            item {
                SectionHeading("Where it goes", "TODAY BY CATEGORY")
            }

            // Horizontal Draggable Category Filter
            item {
                val filterCategories = (listOf("All") + LedgerCategories + report.categories.map { it.name })
                    .distinct()
                LazyRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    items(filterCategories, key = { it }) { cat ->
                        FilterChip(
                            selected = selectedCategoryFilter == cat,
                            onClick = { selectedCategoryFilter = cat },
                            label = { Text(cat) },
                        )
                    }
                }
            }

            val filteredCategories = if (selectedCategoryFilter == "All") {
                report.categories
            } else {
                report.categories.filter { it.name == selectedCategoryFilter }
            }

            if (filteredCategories.isEmpty()) {
                item { EmptyHint("No payments found for this category today.") }
            } else {
                items(filteredCategories, key = { it.name }) { category ->
                    EnhancedCategory3DCard(category)
                }
            }

            val filteredCounterparties = if (selectedCategoryFilter == "All") {
                report.counterparties
            } else {
                report.counterparties.filter { it.category == selectedCategoryFilter }
            }

            if (selectedCategoryFilter != "Receive") {
                item { SectionHeading("Regulars", "TAP FOR HISTORY & LIMITS") }
                if (filteredCounterparties.isEmpty()) {
                    item { EmptyHint("No regulars found for this filter.") }
                } else {
                    items(filteredCounterparties, key = { it.name }) { payee ->
                        RegularItemCard(payee, onCounterpartyClick)
                    }
                }
            }

            item {
                SectionHeading(
                    if (selectedCategoryFilter == "All") "Today's transactions"
                    else "$selectedCategoryFilter transactions",
                    "FILTERED BY CATEGORY",
                )
            }
            transactionError?.let { message ->
                item {
                    ErrorCard(message) {
                        scope.launch { loadTransactionPage(reset = true) }
                    }
                }
            }
            if (categoryTransactions.isEmpty() && !loadingTransactions && transactionError == null) {
                item { EmptyHint("No transactions found for this category today.") }
            } else {
                items(categoryTransactions, key = { it.code }) { transaction ->
                    TransactionItemCard(
                        transaction = transaction,
                        onClick = { onCounterpartyClick(transaction.counterparty) },
                        onAttach = { transactionToLink = transaction },
                    )
                }
            }
            if (hasMoreTransactions || loadingTransactions) {
                item {
                    OutlinedButton(
                        onClick = { scope.launch { loadMoreTransactions() } },
                        enabled = !loadingTransactions,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(if (loadingTransactions) "Loading…" else "Load more transactions")
                    }
                }
            }
        }
        if (dashboard == null && !loading && error == null) {
            item { EmptyHint("Your local spending insights are loading.") }
        }
        if (dashboard?.transactionCount == 0) {
            item { EmptyHint("No M-PESA payments recorded today. New messages from MPESA are captured in the background.") }
        }
        item { Spacer(Modifier.height(8.dp)) }
    }

    transactionToLink?.let { transaction ->
        AlertDialog(
            onDismissRequest = {
                if (!linking) {
                    transactionToLink = null
                    paybillName = ""
                    linkError = null
                }
            },
            title = { Text("Link payment to a PayBill") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        "This payment stays in its original category. Add it to a named PayBill group, such as Ndechu.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    OutlinedTextField(
                        value = paybillName,
                        onValueChange = { paybillName = it },
                        label = { Text("PayBill or group name") },
                        singleLine = true,
                    )
                    linkError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                }
            },
            confirmButton = {
                TextButton(
                    enabled = !linking && paybillName.isNotBlank(),
                    onClick = {
                        scope.launch {
                            linking = true
                            linkError = null
                            try {
                                val saved = withContext(Dispatchers.IO) {
                                    NativeBridge.nativeAttachTransactionToPaybill(
                                        transaction.code,
                                        paybillName.trim(),
                                    )
                                }
                                check(saved) { "Could not link this transaction." }
                                transactionToLink = null
                                paybillName = ""
                            } catch (exception: Exception) {
                                linkError = exception.message ?: "Could not link this transaction."
                            } finally {
                                linking = false
                            }
                        }
                    },
                ) {
                    Text(if (linking) "Linking…" else "Link payment")
                }
            },
            dismissButton = {
                TextButton(
                    enabled = !linking,
                    onClick = {
                        transactionToLink = null
                        paybillName = ""
                        linkError = null
                    },
                ) { Text("Cancel") }
            },
        )
    }
}

@Composable
private fun EnhancedCategory3DCard(category: CategorySpend) {
    val bgRes = when (category.name.lowercase()) {
        "food", "food & groceries" -> R.drawable.cat_bg_food
        "shopping", "buygoods" -> R.drawable.cat_bg_shopping
        "transport", "withdrawal" -> R.drawable.cat_bg_transport
        "bills", "paybill" -> R.drawable.cat_bg_bills
        "airtime" -> R.drawable.cat_bg_airtime
        "rent", "housing" -> R.drawable.cat_bg_rent
        "utilities", "kplc" -> R.drawable.cat_bg_utilities
        "family", "friends", "pochi" -> R.drawable.cat_bg_family
        "health" -> R.drawable.cat_bg_health
        "education" -> R.drawable.cat_bg_education
        "entertainment" -> R.drawable.cat_bg_entertainment
        "savings" -> R.drawable.cat_bg_savings
        "business", "till" -> R.drawable.cat_bg_business
        else -> R.drawable.cat_bg_other
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .height(110.dp)
            .shadow(6.dp, RoundedCornerShape(24.dp)),
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = Color.Transparent),
    ) {
        Box(
            modifier = Modifier.fillMaxSize(),
        ) {
            Image(
                painter = painterResource(id = bgRes),
                contentDescription = null,
                modifier = Modifier.matchParentSize(),
                contentScale = ContentScale.Crop,
            )
            Box(
                modifier = Modifier
                    .matchParentSize()
                    .background(Color.Black.copy(alpha = 0.28f)),
            )
            Row(
                modifier = Modifier
                    .matchParentSize()
                    .padding(20.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        category.name,
                        color = Color.White,
                        fontWeight = FontWeight.Bold,
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Text(
                        "${category.count} ${if (category.count == 1) "payment" else "payments"}",
                        color = Color.White.copy(alpha = 0.85f),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                Column(horizontalAlignment = Alignment.End) {
                    Text(
                        formatKsh(category.spentCents),
                        color = Color.White,
                        fontWeight = FontWeight.Bold,
                        style = MaterialTheme.typography.titleMedium,
                    )
                    if (category.largestSpendCents > 0) {
                        Text(
                            "Largest: ${formatKsh(category.largestSpendCents)}",
                            color = Color.White.copy(alpha = 0.9f),
                            style = MaterialTheme.typography.labelSmall,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun TransactionItemCard(
    transaction: LedgerTransaction,
    onClick: () -> Unit,
    onAttach: () -> Unit,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 13.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(
                    transaction.counterparty,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    "${transaction.kind.replaceFirstChar { it.uppercase() }} · ${transaction.category} · ${transaction.time}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(
                formatKsh(transaction.amountCents),
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface,
            )
            if (transaction.kind == "sent") {
                TextButton(onClick = onAttach) { Text("Link") }
            }
        }
    }
}

@Composable
private fun RegularItemCard(payee: Counterparty, onCounterpartyClick: (String) -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .shadow(2.dp, RoundedCornerShape(18.dp)),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { onCounterpartyClick(payee.name) }
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(payee.name, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface)
                Text(
                    "${payee.category} · ${payee.count} ${if (payee.count == 1) "payment" else "payments"}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Column(horizontalAlignment = Alignment.End) {
                Text(formatKsh(payee.spentCents), fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface)
                if (payee.limitCents > 0) {
                    Text(
                        "Daily cap ${formatKsh(payee.limitCents)}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun SpendingSummaryCard(report: Dashboard) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(26.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primary),
    ) {
        Box {
            CoinIllustration(
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .padding(end = 14.dp),
                tint = Color.White,
                coinColor = Color(0xFFFFCF5A),
            )
            Column(
                modifier = Modifier.padding(22.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(
                    "SPENT TODAY",
                    color = MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.78f),
                    style = MaterialTheme.typography.labelLarge,
                    letterSpacing = 1.1.sp,
                )
                Text(
                    formatKsh(report.spentCents),
                    modifier = Modifier.fillMaxWidth(0.7f),
                    color = MaterialTheme.colorScheme.onPrimary,
                    style = MaterialTheme.typography.headlineLarge,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    "${report.transactionCount} transactions · ${formatKsh(report.receivedCents)} received",
                    modifier = Modifier.fillMaxWidth(0.72f),
                    color = MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.82f),
                )
            }
        }
    }
}

@Composable
private fun CoinIllustration(modifier: Modifier = Modifier, tint: Color, coinColor: Color) {
    val transition = rememberInfiniteTransition(label = "coin-motion")
    val outgoingX by transition.animateFloat(
        initialValue = -8f,
        targetValue = 10f,
        animationSpec = infiniteRepeatable(tween(1300), RepeatMode.Reverse),
        label = "outgoing-coin",
    )
    val incomingX by transition.animateFloat(
        initialValue = 9f,
        targetValue = -9f,
        animationSpec = infiniteRepeatable(tween(1500), RepeatMode.Reverse),
        label = "incoming-coin",
    )
    Box(modifier = modifier.size(width = 100.dp, height = 72.dp)) {
        Box(
            modifier = Modifier
                .align(Alignment.CenterStart)
                .offset(x = outgoingX.dp)
                .size(43.dp)
                .shadow(5.dp, CircleShape)
                .clip(CircleShape)
                .background(Brush.linearGradient(listOf(coinColor, Color(0xFFE99C18)))),
            contentAlignment = Alignment.Center,
        ) {
            Text("M", color = Color(0xFF49330C), fontWeight = FontWeight.Black, fontSize = 18.sp)
        }
        Box(
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .offset(x = incomingX.dp)
                .size(35.dp)
                .shadow(4.dp, CircleShape)
                .clip(CircleShape)
                .background(Brush.linearGradient(listOf(Color(0xFFFFE08B), coinColor))),
            contentAlignment = Alignment.Center,
        ) {
            Text("M", color = Color(0xFF49330C), fontWeight = FontWeight.Black, fontSize = 15.sp)
        }
        Text(
            "→",
            modifier = Modifier
                .align(Alignment.Center)
                .offset(x = (-10).dp, y = (-22).dp),
            color = tint.copy(alpha = 0.8f),
            fontWeight = FontWeight.Bold,
            fontSize = 18.sp,
        )
        Text(
            "←",
            modifier = Modifier
                .align(Alignment.Center)
                .offset(x = 10.dp, y = 22.dp),
            color = tint.copy(alpha = 0.65f),
            fontWeight = FontWeight.Bold,
            fontSize = 15.sp,
        )
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
            HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant)
            BudgetLine("Last 7 days", report.weeklySpentCents, weeklyBudget)
        }
    }
}

@Composable
private fun BudgetLine(label: String, spent: Long, budget: Long) {
    Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(label, modifier = Modifier.weight(1f), fontWeight = FontWeight.Medium, color = MaterialTheme.colorScheme.onSurface)
            Text(
                if (budget > 0) "${formatKsh(spent)} / ${formatKsh(budget)}" else "${formatKsh(spent)} · no budget set",
                color = if (budget > 0 && spent > budget) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall,
            )
        }
        if (budget > 0) {
            val progress = (spent.toFloat() / budget.toFloat()).coerceIn(0f, 1f)
            androidx.compose.material3.LinearProgressIndicator(
                progress = { progress },
                modifier = Modifier.fillMaxWidth(),
                color = if (spent > budget) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                trackColor = MaterialTheme.colorScheme.primaryContainer,
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
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 18.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        error?.let { err ->
            item { ErrorCard(err, onRetry = ::refresh) }
        }
        report?.let { details ->
            item {
                CardSurface {
                    Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
                        Text("TOTAL SPENT", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(formatKsh(details.totalSpentCents), style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)
                        Text("${details.transactionCount} transactions · ${formatKsh(details.weeklyAverageCents)} average per week", color = MaterialTheme.colorScheme.onSurface)
                    }
                }
            }
            item { SectionHeading("Spending pattern", "ALL AVAILABLE HISTORY") }
            item {
                CardSurface {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text(
                            details.busiestDay?.let { "You spend most here on $it." }
                                ?: "No outgoing payments found for this regular yet.",
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        details.weekdaySpending.forEach { (day, amount) ->
                            AmountRow(day, "weekday total", formatKsh(amount))
                        }
                    }
                }
            }
            item { SectionHeading("Category & limit", "CAP IS PER DAY") }
            item {
                CardSurface {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text("Assign a category", fontWeight = FontWeight.Medium, color = MaterialTheme.colorScheme.onSurface)
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
                            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
                        ) {
                            Text(if (saving) "Saving…" else "Save category and limit")
                        }
                    }
                }
            }
            item { SectionHeading("Transactions", "NEWEST FIRST") }
            if (details.transactions.isEmpty()) {
                item { EmptyHint("There are no parsed transactions for this regular yet.") }
            } else {
                items(details.transactions, key = { it.code }) { transaction ->
                    CardSurface {
                        Column(
                            modifier = Modifier.fillMaxWidth(),
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
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ProfileScreen(
    auth: FirebaseAuth?,
    userEmail: String?,
    onAuthChanged: (com.google.firebase.auth.FirebaseUser?) -> Unit,
) {
    var email by rememberSaveable { mutableStateOf("") }
    var password by rememberSaveable { mutableStateOf("") }
    var creatingAccount by rememberSaveable { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 18.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item {
            CardSurface {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        "Your ledger stays on this device",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Text(
                        "Sign-in manages your account only. Transactions are not uploaded or synced.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        if (userEmail != null) {
            item {
                CardSurface {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text("Signed in", style = MaterialTheme.typography.labelLarge)
                        Text(userEmail, fontWeight = FontWeight.SemiBold)
                        OutlinedButton(
                            onClick = {
                                auth?.signOut()
                                onAuthChanged(null)
                                message = "You are signed out."
                            },
                        ) { Text("Sign out") }
                    }
                }
            }
        } else {
            item {
                CardSurface {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text(
                            if (creatingAccount) "Create your account" else "Sign in",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                        )
                        if (auth == null) {
                            Text(
                                "Firebase is not configured yet. Add the Firebase project values described in README.md to your private Gradle properties, then rebuild.",
                                color = MaterialTheme.colorScheme.error,
                            )
                        }
                        if (auth != null && BuildConfig.FIREBASE_WEB_CLIENT_ID.isBlank()) {
                            Text(
                                "Google sign-in needs firebaseWebClientId configured. Email and password sign-in are available.",
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                        OutlinedTextField(
                            value = email,
                            onValueChange = { email = it },
                            modifier = Modifier.fillMaxWidth(),
                            label = { Text("Email") },
                            singleLine = true,
                            enabled = auth != null && !busy,
                        )
                        OutlinedTextField(
                            value = password,
                            onValueChange = { password = it },
                            modifier = Modifier.fillMaxWidth(),
                            label = { Text("Password") },
                            singleLine = true,
                            visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(),
                            enabled = auth != null && !busy,
                        )
                        Button(
                            onClick = {
                                scope.launch {
                                    busy = true
                                    error = null
                                    message = null
                                    try {
                                        val authClient = checkNotNull(auth) {
                                            "Firebase is not configured."
                                        }
                                        val result = withContext(Dispatchers.IO) {
                                            if (creatingAccount) {
                                                authClient.createUserWithEmailAndPassword(
                                                    email.trim(),
                                                    password,
                                                ).await()
                                            } else {
                                                authClient.signInWithEmailAndPassword(
                                                    email.trim(),
                                                    password,
                                                ).await()
                                            }
                                        }
                                        onAuthChanged(result.user)
                                        message = if (creatingAccount) {
                                            "Account created and signed in."
                                        } else {
                                            "Signed in successfully."
                                        }
                                    } catch (exception: Exception) {
                                        error = exception.message ?: "Firebase sign-in failed."
                                    } finally {
                                        busy = false
                                    }
                                }
                            },
                            enabled = auth != null && !busy && email.isNotBlank() && password.length >= 6,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(
                                when {
                                    busy -> "Please wait…"
                                    creatingAccount -> "Create account with email"
                                    else -> "Sign in with email"
                                },
                            )
                        }
                        OutlinedButton(
                            onClick = {
                                scope.launch {
                                    busy = true
                                    error = null
                                    message = null
                                    try {
                                        val webClientId = BuildConfig.FIREBASE_WEB_CLIENT_ID
                                        check(webClientId.isNotBlank()) {
                                            "Add firebaseWebClientId to your private Gradle properties to enable Google sign-in."
                                        }
                                        val authClient = checkNotNull(auth) {
                                            "Firebase is not configured."
                                        }
                                        val googleOption = GetGoogleIdOption.Builder()
                                            .setFilterByAuthorizedAccounts(false)
                                            .setServerClientId(webClientId)
                                            .setAutoSelectEnabled(false)
                                            .build()
                                        val request = GetCredentialRequest.Builder()
                                            .addCredentialOption(googleOption)
                                            .build()
                                        val credentialResult = CredentialManager
                                            .create(context)
                                            .getCredential(context, request)
                                        val credential = credentialResult.credential
                                        check(
                                            credential is CustomCredential &&
                                                credential.type == GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL,
                                        ) { "Google did not return a valid sign-in credential." }
                                        val googleToken = try {
                                            GoogleIdTokenCredential.createFrom(credential.data).idToken
                                        } catch (exception: GoogleIdTokenParsingException) {
                                            throw IllegalStateException(
                                                "Could not read the Google sign-in token.",
                                                exception,
                                            )
                                        }
                                        val firebaseCredential =
                                            GoogleAuthProvider.getCredential(googleToken, null)
                                        val result = withContext(Dispatchers.IO) {
                                            authClient.signInWithCredential(firebaseCredential).await()
                                        }
                                        onAuthChanged(result.user)
                                        message = "Signed in with Google."
                                    } catch (exception: Exception) {
                                        error = exception.message ?: "Google sign-in failed."
                                    } finally {
                                        busy = false
                                    }
                                }
                            },
                            enabled = auth != null &&
                                BuildConfig.FIREBASE_WEB_CLIENT_ID.isNotBlank() &&
                                !busy,
                            modifier = Modifier.fillMaxWidth(),
                        ) { Text("Continue with Google") }
                        TextButton(
                            onClick = {
                                creatingAccount = !creatingAccount
                                error = null
                                message = null
                            },
                        ) {
                            Text(
                                if (creatingAccount) "Already have an account? Sign in"
                                else "New here? Create an account",
                            )
                        }
                        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                        message?.let { Text(it, color = MaterialTheme.colorScheme.primary) }
                    }
                }
            }
        }
    }
}

@Composable
private fun PaymentsComingSoonScreen() {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(22.dp),
        verticalArrangement = Arrangement.Center,
    ) {
        CardSurface {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Payments are coming soon", style = MaterialTheme.typography.titleLarge)
                Text(
                    "Subscriptions and the seven-day trial will be added later. No payment is requested or processed in this version.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun TrackedPaybillsScreen() {
    var paybills by remember { mutableStateOf<List<TrackedPaybill>>(emptyList()) }
    var error by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(true) }
    val scope = rememberCoroutineScope()

    fun refresh() {
        scope.launch {
            loading = true
            error = null
            try {
                paybills = withContext(Dispatchers.IO) {
                    parseTrackedPaybills(NativeBridge.nativeTrackedPaybills())
                }
            } catch (exception: Exception) {
                error = exception.message ?: "Could not load tracked PayBills."
            } finally {
                loading = false
            }
        }
    }
    LaunchedEffect(Unit) { refresh() }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 18.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item {
            Text(
                "Link any outgoing payment to a named PayBill group. A linked payment keeps its original category and can be part of only one group at a time.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        item { OutlinedButton(onClick = ::refresh) { Text("Refresh") } }
        error?.let { text -> item { ErrorCard(text, onRetry = ::refresh) } }
        if (paybills.isEmpty() && !loading && error == null) {
            item { EmptyHint("No tracked PayBills yet. Use Link on a transaction to create a group.") }
        }
        items(paybills, key = { it.name }) { paybill ->
            CardSurface {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(paybill.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Text(
                        "${paybill.transactionCount} linked payments · ${formatKsh(paybill.amountCents)}",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    paybill.transactions.forEach { transaction ->
                        HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant)
                        AmountRow(
                            transaction.counterparty,
                            "${transaction.kind.replaceFirstChar { it.uppercase() }} · ${transaction.category} · ${transaction.date}",
                            formatKsh(transaction.amountCents),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun InstitutionsScreen() {
    val institutions = listOf("M-PESA", "NCBA", "I&M Bank", "Co-op Bank", "KCB")
    var selectedInstitution by rememberSaveable { mutableStateOf("M-PESA") }
    var report by remember { mutableStateOf<List<InstitutionSummary>>(emptyList()) }
    var error by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(false) }
    var importMessage by remember { mutableStateOf<String?>(null) }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    fun refresh() {
        scope.launch {
            loading = true
            error = null
            try {
                report = withContext(Dispatchers.IO) {
                    parseInstitutionReport(NativeBridge.nativeInstitutionReport())
                }
            } catch (exception: Exception) {
                error = exception.message ?: "Could not load institutions."
            } finally {
                loading = false
            }
        }
    }

    val statementPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri != null) {
            scope.launch {
                loading = true
                error = null
                importMessage = null
                try {
                    val rows = withContext(Dispatchers.IO) {
                        val contents = context.contentResolver.openInputStream(uri)
                            ?.bufferedReader()
                            ?.use { it.readText() }
                            ?: error("Could not read the selected statement file.")
                        require(contents.length <= 10_000_000) {
                            "Statement files must be 10 MB or smaller."
                        }
                        parseStatementCsv(contents)
                    }
                    check(rows.length() > 0) { "The statement has no transaction rows." }
                    val result = withContext(Dispatchers.IO) {
                        JSONObject(
                            NativeBridge.nativeImportStatement(
                                selectedInstitution,
                                rows.toString(),
                            ),
                        )
                    }
                    importMessage =
                        "Imported ${result.optInt("imported_count")} new transactions from $selectedInstitution."
                    report = withContext(Dispatchers.IO) {
                        parseInstitutionReport(NativeBridge.nativeInstitutionReport())
                    }
                } catch (exception: Exception) {
                    error = exception.message ?: "Could not import this statement."
                } finally {
                    loading = false
                }
            }
        }
    }
    LaunchedEffect(Unit) { refresh() }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 18.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item {
            CardSurface {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        "Track your institutions",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        "M-PESA remains your default and continues to use its existing SMS pipeline. Bank statements are imported locally; institutions are ranked by total amount transacted.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        item {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(institutions, key = { it }) { institution ->
                    FilterChip(
                        selected = selectedInstitution == institution,
                        onClick = {
                            selectedInstitution = institution
                            importMessage = null
                        },
                        label = { Text(institution) },
                    )
                }
            }
        }
        if (selectedInstitution != "M-PESA") {
            item {
                OutlinedButton(
                    onClick = {
                        statementPicker.launch(
                            arrayOf("text/*", "application/csv", "application/vnd.ms-excel"),
                        )
                    },
                    enabled = !loading,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(if (loading) "Importing…" else "Import $selectedInstitution CSV statement")
                }
            }
            item {
                Text(
                    "CSV columns: date, description, amount, type (debit/credit), and optional time and reference. Bank SMS import will be added after institution-specific formats are verified.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        item {
            SectionHeading("Institutions by total amount", "ALL AVAILABLE HISTORY")
        }
        error?.let { message -> item { ErrorCard(message, onRetry = ::refresh) } }
        if (report.isEmpty() && !loading && error == null) {
            item { EmptyHint("No parsed M-PESA payments or bank statement imports yet.") }
        }
        items(report, key = { it.name }) { institution ->
            CardSurface {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        institution.name,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        formatKsh(institution.totalCents),
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    Text(
                        "${institution.transactionCount} transactions · ${formatKsh(institution.sentCents)} out · ${formatKsh(institution.receivedCents)} in",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodySmall,
                    )
                    institution.transactions.take(5).forEach { transaction ->
                        HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant)
                        AmountRow(
                            transaction.counterparty,
                            "${transaction.kind.replaceFirstChar { it.uppercase() }} · ${transaction.date} · ${transaction.time}",
                            formatKsh(transaction.amountCents),
                        )
                    }
                    if (institution.transactions.size > 5) {
                        Text(
                            "Showing the 5 most recent transactions",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
        importMessage?.let { message ->
            item { Text(message, color = MaterialTheme.colorScheme.primary) }
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
    themeMode: String,
    onThemeChange: (String) -> Unit,
    onRequestSms: () -> Unit,
    onRequestNotifications: () -> Unit,
    openAppSettings: () -> Unit,
    onProfileClick: () -> Unit,
    onPaymentsClick: () -> Unit,
    onTrackedPaybillsClick: () -> Unit,
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

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 18.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item {
            SettingsGroup("ACCOUNT & PAYMENTS") {
                Button(
                    onClick = onProfileClick,
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
                ) { Text("Profile · Sign in or create account") }
                OutlinedButton(
                    onClick = onTrackedPaybillsClick,
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Tracked PayBills") }
                OutlinedButton(
                    onClick = onPaymentsClick,
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Payments · Coming soon") }
                Text(
                    "Your ledger remains on this device. Signing in does not upload or sync transactions.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
        item {
            SettingsGroup("APPEARANCE & THEME") {
                Text("App Theme", fontWeight = FontWeight.Medium, color = MaterialTheme.colorScheme.onSurface)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("system" to "System", "light" to "Light", "dark" to "Dark").forEach { (mode, label) ->
                        FilterChip(
                            selected = themeMode == mode,
                            onClick = { onThemeChange(mode) },
                            label = { Text(label) },
                        )
                    }
                }
                Text(
                    "Choose between system default, light, or dark mode.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
        item {
            SettingsGroup("BACKGROUND CAPTURE") {
                SettingsStatusRow(
                    "M-PESA SMS access",
                    if (smsAllowed) "Enabled · MPESA sender only" else "Permission required",
                )
                Text(
                    "Only messages broadcast by sender MPESA are queued. Message contents and the ledger remain on this device.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall,
                )
                Button(
                    onClick = if (smsAllowed) openAppSettings else onRequestSms,
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
                ) {
                    Text(if (smsAllowed) "Review app permissions" else "Allow SMS capture")
                }
                if (!smsAllowed) {
                    OutlinedButton(onClick = openAppSettings) {
                        Text("Open app settings if Android blocks this permission")
                    }
                }
            }
        }
        item {
            SettingsGroup("SPENDING CHECK-INS") {
                Text("Notify me", fontWeight = FontWeight.Medium, color = MaterialTheme.colorScheme.onSurface)
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
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
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
        }
        item {
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
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
                ) {
                    Text("Save budgets and schedule")
                }
                status?.let { Text(it, color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodySmall) }
            }
        }
        item {
            SettingsGroup("YOUR DATA") {
                SettingsStatusRow("Database", "Private on-device SQLite")
                SettingsStatusRow("Background jobs", "Apalis queue supervised by Rivium")
                Text(
                    "M-PESA messages are retained locally to parse and review. Notifications are generated on the device; no external AI or financial service is contacted.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
        item { Spacer(Modifier.height(8.dp)) }
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
        Text(title, fontWeight = FontWeight.Medium, color = MaterialTheme.colorScheme.onSurface)
        Spacer(Modifier.width(10.dp))
        Text(value, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun CardSurface(content: @Composable () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(22.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
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
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.labelSmall,
                letterSpacing = 1.sp,
            )
        }
        Text(title, color = MaterialTheme.colorScheme.onBackground, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun AmountRow(title: String, subtitle: String, amount: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, fontWeight = FontWeight.Medium, color = MaterialTheme.colorScheme.onSurface)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Text(amount, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface)
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
                    .background(MaterialTheme.colorScheme.primaryContainer),
                contentAlignment = Alignment.Center,
            ) {
                Text("i", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
            }
            Spacer(Modifier.width(10.dp))
            Text(text, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
private fun ErrorCard(message: String, onRetry: () -> Unit) {
    CardSurface {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(message, color = MaterialTheme.colorScheme.error)
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
                name = item.optString("name"),
                spentCents = item.optLong("spent_cents"),
                count = item.optInt("count"),
                largestSpendCents = item.optLong("largest_spend_cents", item.optLong("spent_cents")),
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

private fun parseTransactionPage(encoded: String): TransactionPage {
    val json = JSONObject(encoded)
    return TransactionPage(
        transactions = json.array("transactions") { item ->
            item.toLedgerTransaction()
        },
        nextOffset = json.optInt("next_offset"),
        hasMore = json.optBoolean("has_more"),
    )
}

private fun parseTrackedPaybills(encoded: String): List<TrackedPaybill> {
    val json = JSONObject(encoded)
    return json.array("paybills") { item ->
        TrackedPaybill(
            name = item.optString("name"),
            transactionCount = item.optInt("transaction_count"),
            amountCents = item.optLong("amount_cents"),
            transactions = item.array("transactions") { transaction ->
                transaction.toLedgerTransaction()
            },
        )
    }
}

private fun parseInstitutionReport(encoded: String): List<InstitutionSummary> {
    val json = JSONObject(encoded)
    return json.array("institutions") { item ->
        InstitutionSummary(
            name = item.optString("name"),
            totalCents = item.optLong("total_cents"),
            sentCents = item.optLong("sent_cents"),
            receivedCents = item.optLong("received_cents"),
            transactionCount = item.optInt("transaction_count"),
            transactions = item.array("transactions") { it.toLedgerTransaction() },
        )
    }
}

private fun parseStatementCsv(contents: String): JSONArray {
    val rows = parseCsvRows(contents)
    require(rows.size > 1) { "The statement must include a header and at least one transaction." }
    val headers = rows.first().map { it.normalizedHeader() }
    fun column(vararg names: String): Int? =
        headers.indexOfFirst { it in names }.takeIf { it >= 0 }

    val dateColumn = column("date", "transactiondate", "valuedate")
        ?: error("CSV is missing a date column.")
    val descriptionColumn = column("description", "details", "narration", "particulars")
        ?: error("CSV is missing a description/details column.")
    val amountColumn = column("amount", "transactionamount", "value")
        ?: error("CSV is missing an amount column.")
    val typeColumn = column("type", "direction", "transactiontype")
    val timeColumn = column("time", "transactiontime")
    val referenceColumn = column("reference", "ref", "transactionreference", "transactionid")

    val result = JSONArray()
    rows.drop(1).forEachIndexed { index, row ->
        if (row.all(String::isBlank)) return@forEachIndexed
        fun value(column: Int?): String = column?.let { row.getOrNull(it).orEmpty().trim() }.orEmpty()
        val date = value(dateColumn)
        val description = value(descriptionColumn)
        val amountText = value(amountColumn).replace(",", "").replace("KES", "", ignoreCase = true)
            .replace("KSh", "", ignoreCase = true).trim()
        require(date.isNotBlank()) { "CSV row ${index + 2} has no date." }
        require(description.isNotBlank()) { "CSV row ${index + 2} has no description." }
        val rawAmount = try {
            BigDecimal(amountText)
        } catch (_: NumberFormatException) {
            error("CSV row ${index + 2} has an invalid amount.")
        }
        val amountCents = try {
            rawAmount.abs().movePointRight(2).longValueExact()
        } catch (_: ArithmeticException) {
            error("CSV row ${index + 2} has an amount with more than two decimal places or is too large.")
        }
        val kindFromColumn = value(typeColumn).lowercase(Locale.ROOT)
        val kind = when (kindFromColumn) {
            "debit", "withdrawal", "sent", "outgoing", "payment" -> "sent"
            "credit", "deposit", "received", "incoming" -> "received"
            "" -> if (rawAmount.signum() < 0) "sent" else ""
            else -> error("CSV row ${index + 2} has an unknown transaction type: $kindFromColumn")
        }
        require(kind.isNotBlank()) {
            "CSV row ${index + 2} needs a debit/credit type (or a negative amount for a debit)."
        }
        result.put(
            JSONObject()
                .put("date", date)
                .put("time", value(timeColumn))
                .put("kind", kind)
                .put("amount_cents", amountCents)
                .put("description", description)
                .put("reference", value(referenceColumn)),
        )
    }
    return result
}

private fun String.normalizedHeader(): String =
    lowercase(Locale.ROOT).filter(Char::isLetterOrDigit)

private fun parseCsvRows(contents: String): List<List<String>> {
    val rows = mutableListOf<List<String>>()
    var row = mutableListOf<String>()
    val field = StringBuilder()
    var quoted = false
    var index = 0
    while (index < contents.length) {
        val char = contents[index]
        when {
            char == '"' && quoted && index + 1 < contents.length && contents[index + 1] == '"' -> {
                field.append('"')
                index++
            }
            char == '"' -> quoted = !quoted
            char == ',' && !quoted -> {
                row += field.toString()
                field.clear()
            }
            (char == '\n' || char == '\r') && !quoted -> {
                if (char == '\r' && index + 1 < contents.length && contents[index + 1] == '\n') {
                    index++
                }
                row += field.toString()
                field.clear()
                if (row.any(String::isNotBlank)) rows += row
                row = mutableListOf()
            }
            else -> field.append(char)
        }
        index++
    }
    require(!quoted) { "The CSV contains an unterminated quoted field." }
    row += field.toString()
    if (row.any(String::isNotBlank)) rows += row
    return rows
}

private fun JSONObject.toLedgerTransaction() = LedgerTransaction(
    code = optString("code"),
    kind = optString("kind", "sent"),
    category = optString("category"),
    amountCents = optLong("amount_cents"),
    counterparty = optString("counterparty"),
    date = optString("date"),
    time = optString("time"),
)

private fun initializeFirebaseAuth(context: Context): FirebaseAuth? {
    if (
        BuildConfig.FIREBASE_API_KEY.isBlank() ||
        BuildConfig.FIREBASE_APP_ID.isBlank() ||
        BuildConfig.FIREBASE_PROJECT_ID.isBlank() ||
        BuildConfig.FIREBASE_SENDER_ID.isBlank()
    ) {
        return null
    }
    val app = FirebaseApp.getApps(context)
        .firstOrNull { it.name == FirebaseApp.DEFAULT_APP_NAME }
        ?: FirebaseApp.initializeApp(
            context,
            FirebaseOptions.Builder()
                .setApiKey(BuildConfig.FIREBASE_API_KEY)
                .setApplicationId(BuildConfig.FIREBASE_APP_ID)
                .setProjectId(BuildConfig.FIREBASE_PROJECT_ID)
                .setGcmSenderId(BuildConfig.FIREBASE_SENDER_ID)
                .build(),
        )
        ?: throw IllegalStateException("Firebase could not initialize with the configured project.")
    return FirebaseAuth.getInstance(app)
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
