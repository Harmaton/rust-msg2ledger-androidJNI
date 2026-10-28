package com.harmaton.msg2ledger

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
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
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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
}

private object Routes {
    const val Splash = "splash"
    const val Home = "home"
    const val Settings = "settings"
}

private data class TabItem(val route: String, val label: String)

class MainActivity : ComponentActivity() {
    private external fun nativeOpen(path: String): Boolean
    private external fun nativeIngestMessage(sender: String, raw: String): String
    private external fun nativeDailySummary(date: String): String

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        System.loadLibrary("service")

        val database = getDatabasePath("ledger.db")
        database.parentFile?.mkdirs()

        setContent {
            MaterialTheme {
                Msg2LedgerApp(
                    openDatabase = { withContext(Dispatchers.IO) { nativeOpen(database.absolutePath) } },
                    ingestMessage = { sender, raw ->
                        withContext(Dispatchers.IO) { nativeIngestMessage(sender, raw) }
                    },
                    dailySummary = { date ->
                        withContext(Dispatchers.IO) { nativeDailySummary(date) }
                    },
                )
            }
        }
    }
}

@Composable
private fun Msg2LedgerApp(
    openDatabase: suspend () -> Boolean,
    ingestMessage: suspend (String, String) -> String,
    dailySummary: suspend (String) -> String,
) {
    val navController = rememberNavController()
    val backStack by navController.currentBackStackEntryAsState()
    val currentRoute = backStack?.destination?.route
    val tabs = listOf(
        TabItem(Routes.Home, "Home"),
        TabItem(Routes.Settings, "Settings"),
    )

    Scaffold(
        containerColor = AppColors.Canvas,
        bottomBar = {
            if (currentRoute == Routes.Home || currentRoute == Routes.Settings) {
                NavigationBar(containerColor = AppColors.Card) {
                    tabs.forEach { tab ->
                        val selected = backStack?.destination?.hierarchy
                            ?.any { it.route == tab.route } == true
                        NavigationBarItem(
                            selected = selected,
                            onClick = {
                                navController.navigate(tab.route) {
                                    popUpTo(navController.graph.findStartDestination().id) {
                                        saveState = true
                                    }
                                    launchSingleTop = true
                                    restoreState = true
                                }
                            },
                            icon = {
                                Icon(
                                    imageVector = if (tab.route == Routes.Home) {
                                        Icons.Filled.Home
                                    } else {
                                        Icons.Filled.Settings
                                    },
                                    contentDescription = null,
                                )
                            },
                            label = { Text(tab.label) },
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
                SplashScreen(
                    openDatabase = openDatabase,
                    onFinished = {
                        navController.navigate(Routes.Home) {
                            popUpTo(Routes.Splash) { inclusive = true }
                            launchSingleTop = true
                        }
                    },
                )
            }
            composable(Routes.Home) {
                HomeScreen(
                    ingestMessage = ingestMessage,
                    dailySummary = dailySummary,
                )
            }
            composable(Routes.Settings) {
                SettingsScreen()
            }
        }
    }
}

@Composable
private fun SplashScreen(
    openDatabase: suspend () -> Boolean,
    onFinished: () -> Unit,
) {
    var status by remember { mutableStateOf("Preparing your private ledger…") }
    LaunchedEffect(Unit) {
        try {
            check(openDatabase()) { "Could not open the local ledger." }
            delay(650)
            onFinished()
        } catch (error: Exception) {
            status = "Couldn't open your local ledger. ${error.message ?: ""}".trim()
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(AppColors.Green)
            .padding(32.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Box(
                modifier = Modifier
                    .size(88.dp)
                    .clip(RoundedCornerShape(28.dp))
                    .background(Color.White.copy(alpha = 0.14f)),
                contentAlignment = Alignment.Center,
            ) {
                Text("M", color = Color.White, fontSize = 42.sp, fontWeight = FontWeight.Bold)
            }
            Spacer(Modifier.height(22.dp))
            Text(
                "msg2ledger",
                color = Color.White,
                fontSize = 30.sp,
                fontWeight = FontWeight.Bold,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                "Your money, made clearer.",
                color = Color.White.copy(alpha = 0.78f),
                style = MaterialTheme.typography.bodyLarge,
            )
            Spacer(Modifier.height(32.dp))
            CircularProgressIndicator(
                modifier = Modifier.size(24.dp),
                color = Color.White,
                strokeWidth = 2.dp,
            )
            Spacer(Modifier.height(14.dp))
            Text(
                status,
                color = Color.White.copy(alpha = 0.8f),
                style = MaterialTheme.typography.bodySmall,
                textAlign = TextAlign.Center,
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun HomeScreen(
    ingestMessage: suspend (String, String) -> String,
    dailySummary: suspend (String) -> String,
) {
    val scope = rememberCoroutineScope()
    val today = remember { LocalDate.now() }
    var summary by rememberSaveable { mutableStateOf<String?>(null) }
    var activity by rememberSaveable { mutableStateOf("Your ledger is ready for its first message.") }
    var busy by remember { mutableStateOf(false) }

    Scaffold(
        containerColor = AppColors.Canvas,
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            "Good day",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold,
                            color = AppColors.Ink,
                        )
                        Text(
                            today.format(DateTimeFormatter.ofPattern("EEEE, d MMMM", Locale.getDefault())),
                            style = MaterialTheme.typography.bodySmall,
                            color = AppColors.Muted,
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = AppColors.Canvas),
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(28.dp),
                colors = CardDefaults.cardColors(containerColor = AppColors.Green),
            ) {
                Column(
                    modifier = Modifier.padding(24.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "TODAY'S SPENDING",
                            color = Color.White.copy(alpha = 0.74f),
                            style = MaterialTheme.typography.labelLarge,
                            letterSpacing = 1.2.sp,
                        )
                        Spacer(Modifier.weight(1f))
                        Box(
                            modifier = Modifier
                                .size(36.dp)
                                .clip(CircleShape)
                                .background(Color.White.copy(alpha = 0.14f)),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text("K", color = Color.White, fontWeight = FontWeight.Bold)
                        }
                    }
                    Text(
                        summary?.substringBefore(". Biggest:")
                            ?: "No spending summary yet",
                        color = Color.White,
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        "Your transactions stay on this device.",
                        color = Color.White.copy(alpha = 0.75f),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Button(
                        onClick = {
                            scope.launch {
                                busy = true
                                summary = runCatching {
                                    dailySummary(today.format(DateTimeFormatter.ofPattern("dd/MM/yy")))
                                }.getOrElse { "Couldn't load today's summary: ${it.message}" }
                                busy = false
                            }
                        },
                        enabled = !busy,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Color.White,
                            contentColor = AppColors.Green,
                        ),
                        shape = RoundedCornerShape(14.dp),
                    ) {
                        if (busy) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(18.dp),
                                strokeWidth = 2.dp,
                                color = AppColors.Green,
                            )
                            Spacer(Modifier.width(10.dp))
                        }
                        Text("Refresh today's summary", fontWeight = FontWeight.SemiBold)
                    }
                }
            }

            SectionHeading("Your ledger", "LOCAL & PRIVATE")
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(22.dp),
                colors = CardDefaults.cardColors(containerColor = AppColors.Card),
            ) {
                Column(
                    modifier = Modifier.padding(20.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .size(44.dp)
                                .clip(CircleShape)
                                .background(AppColors.Mint),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text("↗", color = AppColors.Green, fontSize = 22.sp)
                        }
                        Spacer(Modifier.width(12.dp))
                        Column {
                            Text("Capture a test transaction", fontWeight = FontWeight.SemiBold)
                            Text(
                                "Checks the local Rust + SQLite flow",
                                style = MaterialTheme.typography.bodySmall,
                                color = AppColors.Muted,
                            )
                        }
                    }
                    HorizontalDivider(color = AppColors.Canvas)
                    Text(
                        activity,
                        style = MaterialTheme.typography.bodyMedium,
                        color = AppColors.Muted,
                    )
                    OutlinedButton(
                        onClick = {
                            scope.launch {
                                busy = true
                                val message = demoMessage(today)
                                activity = runCatching {
                                    ingestMessage("MPESA", message)
                                }.getOrElse { "Storage failed: ${it.message}" }
                                summary = null
                                busy = false
                            }
                        },
                        enabled = !busy,
                        shape = RoundedCornerShape(14.dp),
                    ) {
                        Text("Add sample transaction")
                    }
                }
            }

            summary?.let { result ->
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(22.dp),
                    colors = CardDefaults.cardColors(containerColor = AppColors.Card),
                ) {
                    Column(
                        modifier = Modifier.padding(20.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text("DAILY OVERVIEW", color = AppColors.Muted, style = MaterialTheme.typography.labelMedium)
                        Text(result, color = AppColors.Ink, style = MaterialTheme.typography.bodyLarge)
                    }
                }
            }

            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(22.dp),
                colors = CardDefaults.cardColors(containerColor = Color(0xFFFFF2DF)),
            ) {
                Row(
                    modifier = Modifier.padding(18.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("✦", color = AppColors.Orange, fontSize = 20.sp)
                    Spacer(Modifier.width(12.dp))
                    Text(
                        "Automatic SMS capture and spending insights are coming next.",
                        color = AppColors.Ink,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
            Spacer(Modifier.height(8.dp))
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SettingsScreen() {
    Scaffold(
        containerColor = AppColors.Canvas,
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("Settings", fontWeight = FontWeight.Bold, color = AppColors.Ink)
                        Text(
                            "Make msg2ledger yours",
                            style = MaterialTheme.typography.bodySmall,
                            color = AppColors.Muted,
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = AppColors.Canvas),
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            SettingsGroup(title = "PREFERENCES") {
                SettingsInfoRow("Currency", "Kenyan shilling (Ksh)")
                HorizontalDivider(color = AppColors.Canvas)
                SettingsInfoRow("Daily reminder", "Not scheduled in this preview")
            }
            SettingsGroup(title = "YOUR DATA") {
                SettingsInfoRow("Storage", "On-device SQLite database")
                HorizontalDivider(color = AppColors.Canvas)
                SettingsInfoRow("SMS capture", "Not enabled in this demo")
                HorizontalDivider(color = AppColors.Canvas)
                Text(
                    "M-PESA message text is retained locally for parsing and review. The ledger is in this app's private storage.",
                    color = AppColors.Muted,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            SettingsGroup(title = "ABOUT") {
                SettingsInfoRow("Version", "0.1.0 · Android preview")
                HorizontalDivider(color = AppColors.Canvas)
                Text(
                    "Your financial messages are sensitive. This preview keeps its ledger in the app's private storage.",
                    color = AppColors.Muted,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
    }
}

@Composable
private fun SettingsGroup(
    title: String,
    content: @Composable () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        SectionHeading(title)
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(22.dp),
            colors = CardDefaults.cardColors(containerColor = AppColors.Card),
        ) {
            Column(
                modifier = Modifier.padding(horizontal = 18.dp, vertical = 6.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                content()
            }
        }
    }
}

@Composable
private fun SettingsInfoRow(title: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(title, color = AppColors.Ink, fontWeight = FontWeight.Medium)
        Spacer(Modifier.width(14.dp))
        Text(
            value,
            color = AppColors.Muted,
            style = MaterialTheme.typography.bodySmall,
            textAlign = TextAlign.End,
        )
    }
}

@Composable
private fun SectionHeading(title: String, trailing: String? = null) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            title,
            color = AppColors.Ink,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
        )
        if (trailing != null) {
            Spacer(Modifier.weight(1f))
            Text(
                trailing,
                color = AppColors.Muted,
                style = MaterialTheme.typography.labelSmall,
                letterSpacing = 0.8.sp,
            )
        }
    }
}

private fun demoMessage(date: LocalDate): String {
    val code = "TEST${System.currentTimeMillis().toString().takeLast(8)}"
    val mpesaDate = date.format(DateTimeFormatter.ofPattern("d/M/yy"))
    return "$code Confirmed. Ksh75.00 sent to DEMO CAFE 0712345678 on $mpesaDate at 1:15 PM."
}
