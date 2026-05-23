package com.example

import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.data.AgentConfig
import com.example.data.HistoryEntry
import com.example.service.ScreenNode
import com.example.ui.theme.*
import com.example.viewmodel.AgentViewModel
import com.example.viewmodel.LogLevel
import com.example.viewmodel.LogMessage
import java.text.SimpleDateFormat
import java.util.*
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MyApplicationTheme {
                Scaffold(
                    modifier = Modifier.fillMaxSize(),
                    contentWindowInsets = WindowInsets.safeDrawing
                ) { innerPadding ->
                    AgentDashboardScreen(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(innerPadding)
                    )
                }
            }
        }
    }
}

@Composable
fun AgentDashboardScreen(
    modifier: Modifier = Modifier,
    viewModel: AgentViewModel = viewModel()
) {
    val isAccessibilityActive by viewModel.isAccessibilityActive.collectAsStateWithLifecycle()
    val liveNodes by viewModel.liveNodes.collectAsStateWithLifecycle()
    val agentConfig by viewModel.agentConfig.collectAsStateWithLifecycle()
    val historyList by viewModel.historyList.collectAsStateWithLifecycle()
    val taskInput by viewModel.taskInput.collectAsStateWithLifecycle()
    val isRunning by viewModel.isRunning.collectAsStateWithLifecycle()
    val logs by viewModel.logs.collectAsStateWithLifecycle()
    val ollamaStatus by viewModel.ollamaStatus.collectAsStateWithLifecycle()
    val isSimulatedMode by viewModel.isSimulatedMode.collectAsStateWithLifecycle()
    val adSkippedCount by viewModel.adSkippedCount.collectAsStateWithLifecycle()

    var showConfigDrawer by remember { mutableStateOf(false) }

    BoxWithConstraints(modifier = modifier.background(MaterialTheme.colorScheme.background)) {
        val isWide = maxWidth > 650.dp

        Column(modifier = Modifier.fillMaxSize()) {
            // Title Header Bar
            HeaderBar(
                ollamaStatus = ollamaStatus,
                isAccessibilityActive = isAccessibilityActive,
                provider = agentConfig?.provider ?: "OLLAMA",
                onToggleSettings = { viewModel.openAccessibilitySettings() }
            )

            if (isWide) {
                // Wide Screen Layout (Side-by-Side Panels)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    Column(
                        modifier = Modifier
                            .weight(1.2f)
                            .fillMaxHeight(),
                        verticalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        CommandCenterCard(
                            taskInput = taskInput,
                            isRunning = isRunning,
                            isSimulatedMode = isSimulatedMode,
                            agentConfig = agentConfig ?: AgentConfig(),
                            showConfigDrawer = showConfigDrawer,
                            onToggleConfig = { showConfigDrawer = !showConfigDrawer },
                            onTaskTextChanged = { viewModel.setTaskInput(it) },
                            onStartTask = { viewModel.startAgentTask(it) },
                            onStopTask = { viewModel.stopAgentTask() },
                            onToggleSimulation = { viewModel.toggleSimulatedMode() },
                            onUpdateConfig = { host, model, provider, maxSteps, stepDelay, preferPhysical, autoSkip, filterAd, themeColor ->
                                viewModel.updateAgentConfig(host, model, provider, maxSteps, stepDelay, preferPhysical, autoSkip, filterAd, themeColor)
                            }
                        )

                        HistoryListCard(
                            historyList = historyList,
                            onClearHistory = { viewModel.clearHistory() }
                        )
                    }

                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight(),
                        verticalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        RealTimeMonitoringCard(
                            modifier = Modifier.weight(1.5f),
                            logs = logs,
                            onClearLogs = { viewModel.clearLogs() },
                            onExportLogs = { viewModel.exportLogs() },
                            onClearPersistentLogs = { viewModel.clearOldLogs() },
                            onDumpWindows = { viewModel.dumpWindows() },
                            onDumpNodes = { viewModel.dumpNodeTree() }
                        )

                        ScreenScannerCard(
                            modifier = Modifier.weight(1f),
                            nodes = liveNodes,
                            isAccessibilityActive = isAccessibilityActive,
                            isSimulatedMode = isSimulatedMode,
                            onRefresh = { viewModel.checkOllamaPing() },
                            onSettingsClick = { viewModel.openAccessibilitySettings() }
                        )

                        UsbGuideCard(
                            ollamaStatus = ollamaStatus
                        )

                        AppKnowledgeShieldCard(
                            adSkippedCount = adSkippedCount,
                            filterAdEnabled = agentConfig?.filterAdNodes ?: true,
                            themeChoice = agentConfig?.videoThemeBannerColor ?: "Cyan",
                            accentColor = getThemeAccentColor(agentConfig?.videoThemeBannerColor ?: "Cyan")
                        )
                    }
                }
            } else {
                // Portrait Layout (Scrollable list of cards)
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .padding(horizontal = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                    contentPadding = PaddingValues(top = 8.dp, bottom = 24.dp)
                ) {
                    item {
                        CommandCenterCard(
                            taskInput = taskInput,
                            isRunning = isRunning,
                            isSimulatedMode = isSimulatedMode,
                            agentConfig = agentConfig ?: AgentConfig(),
                            showConfigDrawer = showConfigDrawer,
                            onToggleConfig = { showConfigDrawer = !showConfigDrawer },
                            onTaskTextChanged = { viewModel.setTaskInput(it) },
                            onStartTask = { viewModel.startAgentTask(it) },
                            onStopTask = { viewModel.stopAgentTask() },
                            onToggleSimulation = { viewModel.toggleSimulatedMode() },
                            onUpdateConfig = { host, model, provider, maxSteps, stepDelay, preferPhysical, autoSkip, filterAd, themeColor ->
                                viewModel.updateAgentConfig(host, model, provider, maxSteps, stepDelay, preferPhysical, autoSkip, filterAd, themeColor)
                            }
                        )
                    }

                        item {
                            RealTimeMonitoringCard(
                                modifier = Modifier
                                                    .fillMaxWidth()
                                                    .height(300.dp),
                                logs = logs,
                                onClearLogs = { viewModel.clearLogs() },
                                onExportLogs = { viewModel.exportLogs() },
                                onClearPersistentLogs = { viewModel.clearOldLogs() },
                                onDumpWindows = { viewModel.dumpWindows() },
                                onDumpNodes = { viewModel.dumpNodeTree() }
                            )
                        }

                    item {
                        ScreenScannerCard(
                            modifier = Modifier
                                                .fillMaxWidth()
                                                .height(260.dp),
                            nodes = liveNodes,
                            isAccessibilityActive = isAccessibilityActive,
                            isSimulatedMode = isSimulatedMode,
                            onRefresh = { viewModel.checkOllamaPing() },
                            onSettingsClick = { viewModel.openAccessibilitySettings() }
                        )
                    }

                    item {
                        UsbGuideCard(
                            ollamaStatus = ollamaStatus
                        )
                    }

                    item {
                        AppKnowledgeShieldCard(
                            adSkippedCount = adSkippedCount,
                            filterAdEnabled = agentConfig?.filterAdNodes ?: true,
                            themeChoice = agentConfig?.videoThemeBannerColor ?: "Cyan",
                            accentColor = getThemeAccentColor(agentConfig?.videoThemeBannerColor ?: "Cyan")
                        )
                    }

                    item {
                        HistoryListCard(
                            historyList = historyList,
                            onClearHistory = { viewModel.clearHistory() }
                        )
                    }
                }
            }
        }
    }
}

// Custom Header Widget
@Composable
fun HeaderBar(
    ollamaStatus: String,
    isAccessibilityActive: Boolean,
    provider: String,
    onToggleSettings: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp),
        colors = CardDefaults.cardColors(containerColor = CharcoalDark),
        shape = RoundedCornerShape(16.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, SlateGray)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(36.dp)
                            .clip(CircleShape)
                            .background(CyanGlow)
                            .border(1.dp, CyanAccent, CircleShape),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.SmartToy,
                            contentDescription = "Ollama Agent Logo",
                            tint = CyanAccent,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                    Spacer(modifier = Modifier.width(12.dp))
                    Column {
                        Text(
                            text = "Ollama Task Agent",
                            style = MaterialTheme.typography.titleMedium.copy(
                                letterSpacing = (-0.5).sp
                            ),
                            fontWeight = FontWeight.Bold,
                            color = CyanAccent
                        )
                        Text(
                            text = "Agentic USB Automation Client",
                            style = MaterialTheme.typography.bodySmall,
                            color = SophisticatedMuted
                        )
                    }
                }

                // Active Mode/Connection Indicator
                ConnectionBadge(status = ollamaStatus, provider = provider)
            }

            Spacer(modifier = Modifier.height(14.dp))
            Divider(color = SlateGray, thickness = 1.dp)
            Spacer(modifier = Modifier.height(12.dp))

            // Accessibility Indicator
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = if (isAccessibilityActive) Icons.Default.CheckCircle else Icons.Default.Warning,
                        contentDescription = "Accessibility State",
                        tint = if (isAccessibilityActive) GreenAccent else AmberAccent,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Column {
                        Text(
                            text = if (isAccessibilityActive) "Accessibility Host Active" else "Accessibility Host Offline",
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = FontWeight.SemiBold,
                            color = Color.White
                        )
                        Text(
                            text = if (isAccessibilityActive) "Ready to execute physical target clicks and text inputs" else "Required to capture screens and inject actions",
                            style = MaterialTheme.typography.labelSmall,
                            color = Color.LightGray.copy(alpha = 0.6f)
                        )
                    }
                }

                Button(
                    onClick = onToggleSettings,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (isAccessibilityActive) SlateGray else AmberAccent,
                        contentColor = if (isAccessibilityActive) Color.White else Color.Black
                    ),
                    shape = RoundedCornerShape(8.dp),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                    modifier = Modifier.height(32.dp)
                ) {
                    Text(
                        text = if (isAccessibilityActive) "Settings" else "Enable",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }
    }
}

@Composable
fun ConnectionBadge(status: String, provider: String) {
    val (color, label) = when {
        provider == "GEMINI" -> CyberCyanColor to "GEMINI (CLOUD)"
        status == "ONLINE" -> GreenAccent to "USB LINKED (ONLINE)"
        status == "OFFLINE" -> RedAccent to "OLLAMA OFFLINE"
        status == "CHECKING" -> AmberAccent to "PINGING..."
        else -> Color.Gray to "UNKNOWN HOST"
    }

    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(color.copy(alpha = 0.12f))
            .border(1.dp, color.copy(alpha = 0.4f), RoundedCornerShape(8.dp))
            .padding(horizontal = 8.dp, vertical = 4.dp)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(6.dp)
                    .clip(CircleShape)
                    .background(color)
            )
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Bold,
                color = color
            )
        }
    }
}

// 1. Goal Command Center UI Component
@Composable
fun CommandCenterCard(
    taskInput: String,
    isRunning: Boolean,
    isSimulatedMode: Boolean,
    agentConfig: AgentConfig,
    showConfigDrawer: Boolean,
    onToggleConfig: () -> Unit,
    onTaskTextChanged: (String) -> Unit,
    onStartTask: (String) -> Unit,
    onStopTask: () -> Unit,
    onToggleSimulation: () -> Unit,
    onUpdateConfig: (String, String, String, Int, Long, Boolean, Boolean, Boolean, String) -> Unit
) {
    var hostUrlState by remember(agentConfig.ollamaHost) { mutableStateOf(agentConfig.ollamaHost) }
    var modelNameState by remember(agentConfig.ollamaModel) { mutableStateOf(agentConfig.ollamaModel) }
    var providerState by remember(agentConfig.provider) { mutableStateOf(agentConfig.provider) }
    var maxStepsState by remember(agentConfig.maxSteps) { mutableStateOf(agentConfig.maxSteps) }
    var stepDelayState by remember(agentConfig.stepDelayMs) { mutableStateOf(agentConfig.stepDelayMs) }
    var preferPhysicalState by remember(agentConfig.preferPhysicalGestures) { mutableStateOf(agentConfig.preferPhysicalGestures) }
    var autoSkipAdsState by remember(agentConfig.autoSkipAds) { mutableStateOf(agentConfig.autoSkipAds) }
    var filterAdNodesState by remember(agentConfig.filterAdNodes) { mutableStateOf(agentConfig.filterAdNodes) }
    var videoThemeBannerColorState by remember(agentConfig.videoThemeBannerColor) { mutableStateOf(agentConfig.videoThemeBannerColor) }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("command_center_card"),
        colors = CardDefaults.cardColors(containerColor = CharcoalDark),
        shape = RoundedCornerShape(16.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, SlateGray)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.Launch,
                        contentDescription = "Command Mode",
                        tint = CyanAccent,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "Agent Goal Control Room",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        color = Color.White
                    )
                }

                IconButton(onClick = onToggleConfig) {
                    Icon(
                        imageVector = Icons.Default.Settings,
                        contentDescription = "Toggle Parameters Config",
                        tint = if (showConfigDrawer) CyanAccent else Color.LightGray
                    )
                }
            }

            AnimatedVisibility(
                visible = showConfigDrawer,
                enter = expandVertically() + fadeIn(),
                exit = shrinkVertically() + fadeOut()
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 12.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(CyberBlack)
                        .padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Text(
                        text = "LLM Provider Configuration Setting",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                        color = CyanAccent
                    )

                    // Tab Selector: Ollama vs Gemini
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .background(CharcoalDark)
                            .padding(4.dp)
                    ) {
                        listOf("OLLAMA", "GEMINI").forEach { label ->
                            val isSelected = providerState == label
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .clip(RoundedCornerShape(6.dp))
                                    .background(if (isSelected) SlateGray else Color.Transparent)
                                    .clickable {
                                        providerState = label
                                        onUpdateConfig(hostUrlState, modelNameState, label, maxStepsState, stepDelayState, preferPhysicalState, autoSkipAdsState, filterAdNodesState, videoThemeBannerColorState)
                                    }
                                    .padding(vertical = 6.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = label,
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = FontWeight.Bold,
                                    color = if (isSelected) CyanAccent else Color.LightGray
                                )
                            }
                        }
                    }

                    if (providerState == "OLLAMA") {
                        TextField(
                            value = hostUrlState,
                            onValueChange = { hostUrlState = it },
                            label = { Text("Ollama Server URL", color = Color.Gray) },
                            singleLine = true,
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedTextColor = Color.White,
                                unfocusedTextColor = Color.LightGray,
                                focusedContainerColor = CharcoalDark,
                                unfocusedContainerColor = CharcoalDark,
                                cursorColor = CyanAccent
                            ),
                            modifier = Modifier.fillMaxWidth()
                        )

                        TextField(
                            value = modelNameState,
                            onValueChange = { modelNameState = it },
                            label = { Text("Ollama Model (e.g. llama3.2, mistral)", color = Color.Gray) },
                            singleLine = true,
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedTextColor = Color.White,
                                unfocusedTextColor = Color.LightGray,
                                focusedContainerColor = CharcoalDark,
                                unfocusedContainerColor = CharcoalDark,
                                cursorColor = CyanAccent
                            ),
                            modifier = Modifier.fillMaxWidth()
                        )
                    } else {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(8.dp))
                                .background(CharcoalDark.copy(alpha = 0.5f))
                                .padding(8.dp)
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    Icons.Default.Lock,
                                    contentDescription = "Lock",
                                    tint = CyanAccent,
                                    modifier = Modifier.size(16.dp)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = "Using Secure Server-Side Gemini API key injected from AI Studio Secrets",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = Color.LightGray
                                )
                            }
                        }
                    }

                    Divider(color = SlateGray.copy(alpha = 0.5f), thickness = 1.dp)

                    Text(
                        text = "Agent Execution Limits & Optimization",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                        color = CyanAccent
                    )

                    // 1. Max Steps Settings (Numeric input / Controls)
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Max Execution Steps Limit: $maxStepsState",
                                style = MaterialTheme.typography.bodyMedium,
                                color = Color.White
                            )
                            Text(
                                text = "Stops loop to prevent infinite steps",
                                style = MaterialTheme.typography.labelSmall,
                                color = Color.Gray
                            )
                        }
                        
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Button(
                                onClick = { if (maxStepsState > 1) maxStepsState-- },
                                colors = ButtonDefaults.buttonColors(containerColor = CharcoalDark, contentColor = CyanAccent),
                                contentPadding = PaddingValues(0.dp),
                                modifier = Modifier.size(36.dp),
                                shape = RoundedCornerShape(8.dp)
                            ) {
                                Text("-", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                            }
                            Text(
                                text = "$maxStepsState",
                                color = Color.White,
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Bold
                            )
                            Button(
                                onClick = { if (maxStepsState < 100) maxStepsState++ },
                                colors = ButtonDefaults.buttonColors(containerColor = CharcoalDark, contentColor = CyanAccent),
                                contentPadding = PaddingValues(0.dp),
                                modifier = Modifier.size(36.dp),
                                shape = RoundedCornerShape(8.dp)
                            ) {
                                Text("+", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                            }
                        }
                    }

                    // 2. Step Delay (Slider)
                    Column(modifier = Modifier.fillMaxWidth()) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "Delay Between Steps: ${stepDelayState}ms",
                                style = MaterialTheme.typography.bodyMedium,
                                color = Color.White
                            )
                            Text(
                                text = "Adapts loop speeds",
                                style = MaterialTheme.typography.labelSmall,
                                color = Color.Gray
                            )
                        }
                        Slider(
                            value = stepDelayState.toFloat(),
                            onValueChange = { stepDelayState = it.toLong() },
                            valueRange = 500f..5000f,
                            steps = 9,
                            colors = SliderDefaults.colors(
                                thumbColor = CyanAccent,
                                activeTrackColor = CyanAccent,
                                inactiveTrackColor = SlateGray
                            ),
                            modifier = Modifier.fillMaxWidth()
                        )
                    }

                    // 3. Priority Coordinate Tap Toggle
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Prefer Coordinates Clicks First",
                                style = MaterialTheme.typography.bodyMedium,
                                color = Color.White
                            )
                            Text(
                                text = "Uses physical absolute tap gestures first, then accessibility click",
                                style = MaterialTheme.typography.labelSmall,
                                color = Color.Gray
                            )
                        }
                        Switch(
                            checked = preferPhysicalState,
                            onCheckedChange = { preferPhysicalState = it },
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = CyberBlack,
                                checkedTrackColor = CyanAccent,
                                uncheckedThumbColor = Color.DarkGray,
                                uncheckedTrackColor = CharcoalDark
                            )
                        )
                    }

                    Spacer(modifier = Modifier.height(10.dp))
                    Divider(color = SlateGray.copy(alpha = 0.5f), thickness = 1.dp)
                    Spacer(modifier = Modifier.height(6.dp))

                    Text(
                        text = "Autonomous Ad Skipper & App Shield Settings",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                        color = CyanAccent
                    )

                    // Auto Skip Ads Switch
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Automated Video Ad Skipper",
                                style = MaterialTheme.typography.bodyMedium,
                                color = Color.White
                            )
                            Text(
                                text = "Instantly clicks skip buttons in apps like YouTube",
                                style = MaterialTheme.typography.labelSmall,
                                color = Color.Gray
                            )
                        }
                        Switch(
                            checked = autoSkipAdsState,
                            onCheckedChange = { autoSkipAdsState = it },
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = CyberBlack,
                                checkedTrackColor = CyanAccent,
                                uncheckedThumbColor = Color.DarkGray,
                                uncheckedTrackColor = CharcoalDark
                            )
                        )
                    }

                    // Filter Ad Nodes Switch
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Filter Ad Nodes from Prompt",
                                style = MaterialTheme.typography.bodyMedium,
                                color = Color.White
                            )
                            Text(
                                text = "Hides commercial tags to optimize model context",
                                style = MaterialTheme.typography.labelSmall,
                                color = Color.Gray
                            )
                        }
                        Switch(
                            checked = filterAdNodesState,
                            onCheckedChange = { filterAdNodesState = it },
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = CyberBlack,
                                checkedTrackColor = CyanAccent,
                                uncheckedThumbColor = Color.DarkGray,
                                uncheckedTrackColor = CharcoalDark
                            )
                        )
                    }

                    // Video Theme Banner Color selector
                    Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(
                            text = "Visual Banner Theme Custom Color",
                            style = MaterialTheme.typography.bodyMedium,
                            color = Color.White
                        )
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(8.dp))
                                .background(CharcoalDark)
                                .padding(4.dp),
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            listOf("Cyan", "Amber", "Red", "Green").forEach { colorName ->
                                val isSelected = videoThemeBannerColorState.lowercase(java.util.Locale.getDefault()) == colorName.lowercase(java.util.Locale.getDefault())
                                val choiceColor = getThemeAccentColor(colorName)
                                Box(
                                    modifier = Modifier
                                        .weight(1f)
                                        .clip(RoundedCornerShape(6.dp))
                                        .background(if (isSelected) choiceColor.copy(alpha = 0.2f) else Color.Transparent)
                                        .border(1.dp, if (isSelected) choiceColor else Color.Transparent, RoundedCornerShape(6.dp))
                                        .clickable { videoThemeBannerColorState = colorName }
                                        .padding(vertical = 6.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        text = colorName,
                                        style = MaterialTheme.typography.labelSmall,
                                        fontWeight = FontWeight.Bold,
                                        color = if (isSelected) choiceColor else Color.LightGray
                                    )
                                }
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(10.dp))

                    Button(
                        onClick = { onUpdateConfig(hostUrlState, modelNameState, providerState, maxStepsState, stepDelayState, preferPhysicalState, autoSkipAdsState, filterAdNodesState, videoThemeBannerColorState) },
                        colors = ButtonDefaults.buttonColors(containerColor = CyanAccent, contentColor = CyberBlack),
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(38.dp)
                    ) {
                        Text(
                            text = "Save Technical Parameters",
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            // Task Prompt Input Field
            TextField(
                value = taskInput,
                onValueChange = onTaskTextChanged,
                placeholder = {
                    Text(
                        text = "Enter a command (e.g., 'Open WhatsApp, message John Doe hello', 'Open camera to take a photo')",
                        color = Color.Gray,
                        style = MaterialTheme.typography.bodyMedium
                    )
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(96.dp)
                    .testTag("task_input_field"),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedTextColor = Color.White,
                    unfocusedTextColor = Color.LightGray,
                    focusedContainerColor = CyberBlack,
                    unfocusedContainerColor = CyberBlack,
                    cursorColor = CyanAccent,
                    focusedBorderColor = CyanAccent,
                    unfocusedBorderColor = SlateGray
                ),
                shape = RoundedCornerShape(12.dp)
            )

            Spacer(modifier = Modifier.height(14.dp))

            // Simulated Simulator Switch Toggle (Crucial fallback setting)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
                    .background(CyberBlack)
                    .padding(horizontal = 12.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.Science,
                        contentDescription = "Sandbox",
                        tint = if (isSimulatedMode) CyanAccent else Color.Gray,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Column {
                        Text(
                            text = "Virtual Sandbox Mode",
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Bold,
                            color = Color.White
                        )
                        Text(
                            text = "Simulates automation loops inside the browser emulator",
                            style = MaterialTheme.typography.labelSmall,
                            color = Color.LightGray.copy(alpha = 0.5f)
                        )
                    }
                }

                Switch(
                    checked = isSimulatedMode,
                    onCheckedChange = { onToggleSimulation() },
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = CyanAccent,
                        checkedTrackColor = CyanAccent.copy(alpha = 0.3f),
                        uncheckedThumbColor = Color.Gray,
                        uncheckedTrackColor = SlateGray
                    ),
                    modifier = Modifier.scale(0.85f)
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Execution Action Button Control
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                if (isRunning) {
                    Button(
                        onClick = onStopTask,
                        colors = ButtonDefaults.buttonColors(containerColor = RedAccent, contentColor = Color.Black),
                        shape = CircleShape,
                        modifier = Modifier
                            .weight(1f)
                            .height(48.dp)
                            .testTag("stop_agent_btn")
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Stop, contentDescription = "Stop icon")
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Force Halt Agent", fontWeight = FontWeight.Bold)
                        }
                    }
                } else {
                    Button(
                        onClick = { onStartTask(taskInput) },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = CyanAccent,
                            contentColor = SophisticatedOnPrimary
                        ),
                        shape = CircleShape,
                        modifier = Modifier
                            .weight(1f)
                            .height(48.dp)
                            .testTag("execute_prompt_btn")
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.PlayArrow, contentDescription = "Play icon")
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Launch Agent Workspace", fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        }
    }
}

// 2. Real-time Monitoring Terminal UI Component
@Composable
fun RealTimeMonitoringCard(
    modifier: Modifier = Modifier,
    logs: List<LogMessage>,
    onClearLogs: () -> Unit,
    onExportLogs: () -> Unit,
    onClearPersistentLogs: () -> Unit,
    onDumpWindows: suspend () -> String,
    onDumpNodes: suspend () -> String
) {
    val listState = rememberLazyListState()
    var showDumpDialog by remember { mutableStateOf(false) }
    var dumpText by remember { mutableStateOf("") }
    val coroutineScope = rememberCoroutineScope()

    // Professional Auto-scroll terminal hook
    LaunchedEffect(logs.size) {
        if (logs.isNotEmpty()) {
            listState.animateScrollToItem(logs.size - 1)
        }
    }

    if (showDumpDialog) {
        AlertDialog(
            onDismissRequest = { showDumpDialog = false },
            title = { Text("Accessibility Diagnostic Trace", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = Color.White) },
            text = {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(300.dp)
                        .background(TerminalBackground)
                        .border(1.dp, TerminalBorder, RoundedCornerShape(8.dp))
                        .padding(8.dp)
                ) {
                    LazyColumn(modifier = Modifier.fillMaxSize()) {
                        item {
                            Text(
                                text = dumpText,
                                fontFamily = FontFamily.Monospace,
                                fontSize = 11.sp,
                                color = Color.LightGray
                            )
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showDumpDialog = false }) {
                    Text("Dismiss", color = CyanAccent)
                }
            },
            containerColor = CharcoalDark,
            titleContentColor = Color.White,
            textContentColor = Color.White
        )
    }

    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = CharcoalDark),
        shape = RoundedCornerShape(16.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, SlateGray)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.Terminal,
                        contentDescription = "Monitoring Console",
                        tint = CyanAccent,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "Real-time Execution Monitor",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        color = Color.White
                    )
                }

                IconButton(onClick = onClearLogs, modifier = Modifier.size(28.dp)) {
                    Icon(
                        imageVector = Icons.Default.DeleteSweep,
                        contentDescription = "Clear Session Logs",
                        tint = Color.LightGray.copy(alpha = 0.6f),
                        modifier = Modifier.size(18.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            // Styled Console Window Box
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .clip(RoundedCornerShape(8.dp))
                    .background(TerminalBackground)
                    .border(1.dp, TerminalBorder, RoundedCornerShape(8.dp))
                    .padding(8.dp)
            ) {
                if (logs.isEmpty()) {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "Execution Console is idling...\nEnter a goal above and run to watch logs live.",
                            style = MaterialTheme.typography.bodySmall,
                            color = Color.LightGray.copy(0.4f),
                            textAlign = TextAlign.Center
                        )
                    }
                } else {
                    LazyColumn(
                        state = listState,
                        modifier = Modifier.fillMaxSize(),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        items(logs) { log ->
                            LogItemRow(log = log)
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            // Debug and persist log actions row
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedButton(
                    onClick = onExportLogs,
                    modifier = Modifier.weight(1f).height(38.dp),
                    shape = RoundedCornerShape(8.dp),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = CyanAccent),
                    border = androidx.compose.foundation.BorderStroke(1.dp, CyanAccent.copy(alpha = 0.5f)),
                    contentPadding = PaddingValues(0.dp)
                ) {
                    Icon(Icons.Default.SaveAlt, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Export Logs", fontSize = 10.sp)
                }
                
                OutlinedButton(
                    onClick = onClearPersistentLogs,
                    modifier = Modifier.weight(1f).height(38.dp),
                    shape = RoundedCornerShape(8.dp),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.LightGray),
                    border = androidx.compose.foundation.BorderStroke(1.dp, Color.LightGray.copy(alpha = 0.5f)),
                    contentPadding = PaddingValues(0.dp)
                ) {
                    Icon(Icons.Default.DeleteForever, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Clear Files", fontSize = 10.sp)
                }

                OutlinedButton(
                    onClick = {
                        coroutineScope.launch {
                            dumpText = "ACTIVE WINDOWS:\n" + onDumpWindows() + "\n\nSCREEN NODES:\n" + onDumpNodes()
                            showDumpDialog = true
                        }
                    },
                    modifier = Modifier.weight(1f).height(38.dp),
                    shape = RoundedCornerShape(8.dp),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.LightGray),
                    border = androidx.compose.foundation.BorderStroke(1.dp, Color.LightGray.copy(alpha = 0.5f)),
                    contentPadding = PaddingValues(0.dp)
                ) {
                    Icon(Icons.Default.BugReport, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Dump Screen", fontSize = 10.sp)
                }
            }
        }
    }
}

@Composable
fun LogItemRow(log: LogMessage) {
    val levelColor = when (log.level) {
        LogLevel.SYSTEM -> TerminalBlue
        LogLevel.THINKING -> CyanAccent
        LogLevel.ACTION -> SophisticatedText
        LogLevel.SUCCESS -> GreenAccent
        LogLevel.ERROR -> RedAccent
    }

    val levelTag = when (log.level) {
        LogLevel.SYSTEM -> "[SYS]"
        LogLevel.THINKING -> "[THINK]"
        LogLevel.ACTION -> "[ACTION]"
        LogLevel.SUCCESS -> "[OK]"
        LogLevel.ERROR -> "[FAIL]"
    }

    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.Top
        ) {
            Text(
                text = log.timestamp,
                fontFamily = FontFamily.Monospace,
                fontSize = 11.sp,
                color = SophisticatedMuted,
                modifier = Modifier.width(52.dp)
            )

            Text(
                text = levelTag,
                fontFamily = FontFamily.Monospace,
                fontSize = 11.sp,
                color = levelColor,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.width(60.dp)
            )

            Text(
                text = log.text,
                fontFamily = FontFamily.Monospace,
                fontSize = 11.sp,
                color = if (log.level == LogLevel.ACTION) SophisticatedTextLight else levelColor,
                fontStyle = if (log.level == LogLevel.ACTION) FontStyle.Italic else FontStyle.Normal,
                modifier = Modifier.weight(1f)
            )
        }
    }
}

// 3. Screen Scanner Hierarchy Scanner UI Component
@Composable
fun ScreenScannerCard(
    modifier: Modifier = Modifier,
    nodes: List<ScreenNode>,
    isAccessibilityActive: Boolean,
    isSimulatedMode: Boolean,
    onRefresh: () -> Unit,
    onSettingsClick: () -> Unit
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = CharcoalDark),
        shape = RoundedCornerShape(16.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, SlateGray)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.Visibility,
                        contentDescription = "Screen Scanning Nodes",
                        tint = CyanAccent,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "Android UI Inspector Node Tree",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        color = Color.White
                    )
                }

                IconButton(onClick = onRefresh, modifier = Modifier.size(28.dp)) {
                    Icon(
                        imageVector = Icons.Default.Refresh,
                        contentDescription = "Sync Nodes Tree",
                        tint = CyanAccent,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .clip(RoundedCornerShape(8.dp))
                    .background(CyberBlack)
                    .border(1.dp, SlateGray, RoundedCornerShape(8.dp))
                    .padding(8.dp)
            ) {
                if (!isAccessibilityActive && !isSimulatedMode) {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(12.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        Text(
                            text = "Accessibility Host Service Disabled",
                            style = MaterialTheme.typography.bodySmall,
                            color = RedAccent,
                            fontWeight = FontWeight.Bold,
                            textAlign = TextAlign.Center
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = "Turn on Accessibility settings or activate Virtual Sandbox Mode to inspect mock trees.",
                            style = MaterialTheme.typography.labelMedium,
                            color = Color.LightGray,
                            textAlign = TextAlign.Center
                        )
                        Spacer(modifier = Modifier.height(10.dp))
                        Button(
                            onClick = onSettingsClick,
                            colors = ButtonDefaults.buttonColors(containerColor = AmberAccent, contentColor = Color.Black),
                            shape = RoundedCornerShape(6.dp),
                            modifier = Modifier.height(28.dp)
                        ) {
                            Text("Activate Services", style = MaterialTheme.typography.labelSmall)
                        }
                    }
                } else if (nodes.isEmpty()) {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "Scanning layout tree components...",
                            style = MaterialTheme.typography.bodySmall,
                            color = Color.LightGray.copy(0.4f),
                            textAlign = TextAlign.Center
                        )
                    }
                } else {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        items(nodes) { node ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 2.dp)
                                    .clip(RoundedCornerShape(4.dp))
                                    .background(CharcoalDark.copy(alpha = 0.5f))
                                    .padding(horizontal = 6.dp, vertical = 4.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = node.toActionString(),
                                    fontFamily = FontFamily.Monospace,
                                    fontSize = 10.sp,
                                    color = if (node.isClickable) CyanAccent else Color.LightGray,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f)
                                )

                                if (node.isClickable) {
                                    Box(
                                        modifier = Modifier
                                            .clip(RoundedCornerShape(4.dp))
                                            .background(CyanAccent.copy(alpha = 0.15f))
                                            .padding(horizontal = 4.dp, vertical = 2.dp)
                                    ) {
                                        Text(
                                            "touchable",
                                            fontFamily = FontFamily.Monospace,
                                            fontSize = 8.sp,
                                            color = CyanAccent,
                                            fontWeight = FontWeight.Bold
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

// 4. USB Guide Terminal instructions UI Component
@Composable
fun UsbGuideCard(ollamaStatus: String) {
    val clipboardManager = LocalClipboardManager.current
    val context = LocalContext.current
    var copiedState by remember { mutableStateOf(false) }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = CharcoalDark),
        shape = RoundedCornerShape(16.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, SlateGray)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.Usb,
                        contentDescription = "USB Tethering Setup",
                        tint = CyanAccent,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "Physical Laptop USB Forwarding Guide",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        color = Color.White
                    )
                }

                Box(
                    modifier = Modifier
                        .clip(CircleShape)
                        .background(if (ollamaStatus == "ONLINE") GreenAccent.copy(alpha = 0.15f) else Color.Transparent)
                        .padding(horizontal = 6.dp, vertical = 2.dp)
                ) {
                    Text(
                        text = if (ollamaStatus == "ONLINE") "USB READY" else "LINK PENDING",
                        style = MaterialTheme.typography.labelSmall,
                        color = if (ollamaStatus == "ONLINE") GreenAccent else Color.LightGray,
                        fontWeight = FontWeight.Bold
                    )
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            // Web Emulator / Troubleshooting Warning Alert
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = RedAccent.copy(alpha = 0.08f)),
                border = androidx.compose.foundation.BorderStroke(1.dp, RedAccent.copy(alpha = 0.3f)),
                shape = RoundedCornerShape(12.dp)
            ) {
                Row(
                    modifier = Modifier.padding(12.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.Top
                ) {
                    Icon(
                        imageVector = Icons.Default.Warning,
                        contentDescription = "Troubleshooting Warning",
                        tint = RedAccent,
                        modifier = Modifier.size(20.dp)
                    )
                    Column {
                        Text(
                            text = "Cloud Web Emulator Limitation Note",
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.Bold,
                            color = RedAccent
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "If you are running this app via the AI Studio web browser preview (Streaming Emulator), it runs on a remote cloud node. 'adb reverse' on your local computer cannot route to this cloud virtual device.\n\nOption A: Download & install the APK/AAB or Export ZIP from the top-left menu onto your real physical phone connected to your laptop via USB.\n\nOption B: In Settings, select 'GEMINI' as provider to use direct cloud AI models, or enable 'Action Simulation Mode' for instant local demo sandbox execution.",
                            style = MaterialTheme.typography.bodySmall,
                            color = Color.LightGray
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(14.dp))

            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                GuidanceRow(step = "1", title = "Connect your Android device to physical laptop via standard USB cable.")
                GuidanceRow(step = "2", title = "Ensure USB debugging is enabled under Developer Settings on your mobile.")
                GuidanceRow(step = "3", title = "Ollama model must run active on laptop (listening to port 11434).")
                GuidanceRow(step = "4", title = "Run this port-reversing ADB command inside terminal on your laptop:")

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .background(CyberBlack)
                        .border(1.dp, SlateGray, RoundedCornerShape(8.dp))
                        .clickable {
                            clipboardManager.setText(AnnotatedString("adb reverse tcp:11434 tcp:11434"))
                            Toast
                                .makeText(context, "Command copied to clipboard", Toast.LENGTH_SHORT)
                                .show()
                            copiedState = true
                        }
                        .padding(horizontal = 12.dp, vertical = 8.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "adb reverse tcp:11434 tcp:11434",
                            fontFamily = FontFamily.Monospace,
                            fontSize = 11.sp,
                            color = CyanAccent,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.weight(1f)
                        )

                        Icon(
                            imageVector = if (copiedState) Icons.Default.Check else Icons.Default.ContentCopy,
                            contentDescription = "Copy adb port command",
                            tint = if (copiedState) GreenAccent else Color.LightGray,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun GuidanceRow(step: String, title: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.Top
    ) {
        Box(
            modifier = Modifier
                .size(18.dp)
                .clip(CircleShape)
                .background(SlateGray),
            contentAlignment = Alignment.Center
        ) {
            Text(
                step,
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold,
                color = Color.White
            )
        }
        Text(
            title,
            style = MaterialTheme.typography.bodySmall,
            color = Color.LightGray
        )
    }
}

// 5. Historial Execution Activity UI Component
@Composable
fun HistoryListCard(
    historyList: List<HistoryEntry>,
    onClearHistory: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("history_list_card"),
        colors = CardDefaults.cardColors(containerColor = CharcoalDark),
        shape = RoundedCornerShape(16.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, SlateGray)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.History,
                        contentDescription = "Execution logs history",
                        tint = CyanAccent,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "Historic Task Log Execution List",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        color = Color.White
                    )
                }

                if (historyList.isNotEmpty()) {
                    TextButton(onClick = onClearHistory) {
                        Text(
                            text = "Wipe Logs",
                            style = MaterialTheme.typography.labelSmall,
                            color = RedAccent,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            if (historyList.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(84.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(CyberBlack)
                        .padding(12.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "No previous agent execution histories.",
                        style = MaterialTheme.typography.bodySmall,
                        color = Color.LightGray.copy(alpha = 0.5f)
                    )
                }
            } else {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 240.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(CyberBlack)
                        .border(1.dp, SlateGray, RoundedCornerShape(8.dp)),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(8.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        items(historyList) { item ->
                            HistoryItemRow(item = item)
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun HistoryItemRow(item: HistoryEntry) {
    val statusColor = when (item.status) {
        "SUCCESS" -> GreenAccent
        "RUNNING" -> AmberAccent
        "FAILED", "TIMEOUT" -> RedAccent
        else -> Color.Gray
    }

    val displayDate = SimpleDateFormat("MMM dd, HH:mm", Locale.getDefault()).format(Date(item.timestamp))

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(6.dp))
            .background(CharcoalDark)
            .padding(8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(6.dp)
                        .clip(CircleShape)
                        .background(statusColor)
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = item.taskPrompt,
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.SemiBold,
                    color = Color.White,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            if (item.finalMessage.isNotEmpty()) {
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = item.finalMessage,
                    style = MaterialTheme.typography.labelSmall,
                    color = Color.LightGray.copy(alpha = 0.6f)
                )
            }
        }

        Spacer(modifier = Modifier.width(8.dp))

        Column(horizontalAlignment = Alignment.End) {
            Text(
                displayDate,
                style = MaterialTheme.typography.labelSmall,
                color = Color.LightGray.copy(0.4f)
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                "steps: ${item.stepsCompleted}",
                style = MaterialTheme.typography.labelSmall,
                color = CyanAccent,
                fontWeight = FontWeight.Bold
            )
        }
    }
}

// Custom color definition extensions
val CyberCyanColor = Color(0xFFB6C4FF)

fun getThemeAccentColor(choice: String): Color {
    return when (choice.lowercase(java.util.Locale.getDefault())) {
        "amber" -> Color(0xFFFFC400)
        "red" -> Color(0xFFFF1744)
        "green" -> Color(0xFF1DE9B6)
        else -> Color(0xFF00E5FF) // Cyan default
    }
}

@Composable
fun AppKnowledgeShieldCard(
    adSkippedCount: Int,
    filterAdEnabled: Boolean,
    themeChoice: String,
    accentColor: Color
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("app_knowledge_shield_card"),
        colors = CardDefaults.cardColors(containerColor = CharcoalDark),
        shape = RoundedCornerShape(16.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, SlateGray)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            // Header
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.Security,
                        contentDescription = "Intelligence Guard Shield",
                        tint = accentColor,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "Auto-Skipper & App Shield Hub",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        color = Color.White
                    )
                }

                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .background(accentColor.copy(alpha = 0.12f))
                        .padding(horizontal = 8.dp, vertical = 3.dp)
                ) {
                    Text(
                        text = themeChoice.uppercase(java.util.Locale.getDefault()),
                        style = MaterialTheme.typography.labelSmall,
                        color = accentColor,
                        fontWeight = FontWeight.Bold
                    )
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Dynamic video theme banner with real-time stats
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
                    .background(androidx.compose.ui.graphics.Brush.horizontalGradient(listOf(CyberBlack, CharcoalDark)))
                    .border(1.dp, accentColor.copy(alpha = 0.3f), RoundedCornerShape(8.dp))
                    .padding(12.dp)
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = "AD BYPASS & SHIELD MONITOR",
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        color = accentColor
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Auto-Skipped Video Ads",
                                style = MaterialTheme.typography.labelSmall,
                                color = Color.Gray
                            )
                            Text(
                                text = "$adSkippedCount skip actions",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                color = if (adSkippedCount > 0) Color.Green else Color.White
                            )
                        }

                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Context Filters Status",
                                style = MaterialTheme.typography.labelSmall,
                                color = Color.Gray
                            )
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                Box(
                                    modifier = Modifier
                                        .size(6.dp)
                                        .clip(CircleShape)
                                        .background(if (filterAdEnabled) Color.Green else Color.DarkGray)
                                )
                                Text(
                                    text = if (filterAdEnabled) "ACTIVE (CLEAN CONTEXT)" else "PAUSED (RAW CAPTURE)",
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = FontWeight.Bold,
                                    color = if (filterAdEnabled) Color.Green else Color.LightGray
                                )
                            }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(14.dp))

            // Application UI & Features Guidelines
            Text(
                text = "HOW IT WORKS & GESTURE IMPACTS",
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Bold,
                color = accentColor
            )
            Spacer(modifier = Modifier.height(6.dp))

            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                // Key guideline 1
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.Top) {
                    Text("•", color = accentColor, fontWeight = FontWeight.Bold)
                    Column {
                        Text(
                            text = "Dual Engine Control Room Theme",
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            color = Color.White
                        )
                        Text(
                            text = "Runs accessibility hierarchy scans to extract text, bounds & attributes, translating user prompts into physical gestures on-device.",
                            style = MaterialTheme.typography.bodySmall,
                            color = Color.LightGray
                        )
                    }
                }

                // Key guideline 2
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.Top) {
                    Text("•", color = accentColor, fontWeight = FontWeight.Bold)
                    Column {
                        Text(
                            text = "Gesture Mechanics Impact",
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            color = Color.White
                        )
                        Text(
                            text = "CLICK maps to target IDs; fallback CLICK_COORDS bypasses custom container click bounds. LONG_CLICK reveals emoji panels (e.g. in WhatsApp). SWIPE performs continuous scrolling.",
                            style = MaterialTheme.typography.bodySmall,
                            color = Color.LightGray
                        )
                    }
                }

                // Key guideline 3
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.Top) {
                    Text("•", color = accentColor, fontWeight = FontWeight.Bold)
                    Column {
                        Text(
                            text = "Continuous Accuracy Improvements",
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            color = Color.White
                        )
                        Text(
                            text = "While standard layouts achieve 95%+ precision, we still need to improve segmentation for custom graphics canvas layers, game controllers (e.g., PUBG), and slow-loading overlays to eliminate loops entirely.",
                            style = MaterialTheme.typography.bodySmall,
                            color = Color.LightGray
                        )
                    }
                }
            }
        }
    }
}
