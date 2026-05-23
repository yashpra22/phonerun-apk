package com.example.viewmodel

import android.app.Application
import android.content.Intent
import android.provider.Settings
import android.widget.Toast
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.*
import com.example.network.AgentLLMService
import com.example.service.AgentServiceManager
import com.example.service.LoggerAgent
import com.example.service.ScreenNode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import java.text.SimpleDateFormat
import java.util.*

enum class LogLevel {
    SYSTEM, THINKING, ACTION, SUCCESS, ERROR
}

data class LogMessage(
    val timestamp: String = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date()),
    val level: LogLevel,
    val text: String
)

class AgentViewModel(application: Application) : AndroidViewModel(application) {

    private val database = AppDatabase.getDatabase(application)
    private val repository = AgentRepository(database.agentDao)
    private val llmService = AgentLLMService()

    val isAccessibilityActive = AgentServiceManager.isServiceActive
    val liveNodes = AgentServiceManager.detectedNodes
    val adSkippedCount = AgentServiceManager.adSkippedCount

    val agentConfig = repository.configFlow.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = AgentConfig()
    )

    val historyList = repository.allHistory.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = emptyList()
    )

    private val _taskInput = MutableStateFlow("")
    val taskInput: StateFlow<String> = _taskInput

    private val _isRunning = MutableStateFlow(false)
    val isRunning: StateFlow<Boolean> = _isRunning

    private val _logs = MutableStateFlow<List<LogMessage>>(emptyList())
    val logs: StateFlow<List<LogMessage>> = _logs

    private val _ollamaStatus = MutableStateFlow("UNKNOWN")
    val ollamaStatus: StateFlow<String> = _ollamaStatus

    private val _isSimulatedMode = MutableStateFlow(false)
    val isSimulatedMode: StateFlow<Boolean> = _isSimulatedMode

    private var agentJob: Job? = null
    private val okHttpClient = OkHttpClient.Builder()
        .connectTimeout(3, java.util.concurrent.TimeUnit.SECONDS)
        .build()

    init {
        viewModelScope.launch {
            while (true) {
                checkOllamaPing()
                delay(8000)
            }
        }
    }

    fun setTaskInput(value: String) {
        _taskInput.value = value
    }

    fun toggleSimulatedMode() {
        _isSimulatedMode.value = !_isSimulatedMode.value
        addLog(LogLevel.SYSTEM, "Simulation Mode toggled: ${_isSimulatedMode.value}")
    }

    fun addLog(level: LogLevel, text: String) {
        val currentList = _logs.value.toMutableList()
        currentList.add(LogMessage(level = level, text = text))
        _logs.value = currentList
    }

    fun clearLogs() {
        _logs.value = emptyList()
    }

    fun updateAgentConfig(
        host: String,
        model: String,
        provider: String,
        maxSteps: Int,
        stepDelayMs: Long,
        preferPhysicalGestures: Boolean,
        autoSkipAds: Boolean,
        filterAdNodes: Boolean,
        videoThemeBannerColor: String
    ) {
        viewModelScope.launch(Dispatchers.IO) {
            val current = repository.getConfigDirect()
            val updated = current.copy(
                ollamaHost = host,
                ollamaModel = model,
                provider = provider,
                maxSteps = maxSteps,
                stepDelayMs = stepDelayMs,
                preferPhysicalGestures = preferPhysicalGestures,
                autoSkipAds = autoSkipAds,
                filterAdNodes = filterAdNodes,
                videoThemeBannerColor = videoThemeBannerColor
            )
            repository.saveConfig(updated)
            com.example.service.AgentServiceManager.preferPhysicalGestures = preferPhysicalGestures
            viewModelScope.launch(Dispatchers.Main) {
                addLog(LogLevel.SYSTEM, "Config Saved: Host: $host | Model: $model | Provider: $provider | Max Steps: $maxSteps | Step Delay: ${stepDelayMs}ms | Coordinates Priority: $preferPhysicalGestures | AutoSkip: $autoSkipAds | FilterAds: $filterAdNodes | Theme: $videoThemeBannerColor")
                checkOllamaPing()
            }
        }
    }

    fun checkOllamaPing() {
        viewModelScope.launch(Dispatchers.IO) {
            val config = repository.getConfigDirect()
            if (config.provider == "GEMINI") {
                _ollamaStatus.value = "N/A (GEMINI ACTIVE)"
                return@launch
            }
            _ollamaStatus.value = "CHECKING"
            val host = config.ollamaHost.trimEnd('/')
            val request = Request.Builder().url(host).get().build()
            try {
                okHttpClient.newCall(request).execute().use { response ->
                    if (response.isSuccessful || response.code == 200 || response.code == 404) {
                        _ollamaStatus.value = "ONLINE"
                    } else {
                        _ollamaStatus.value = "OFFLINE"
                    }
                }
            } catch (e: Exception) {
                _ollamaStatus.value = "OFFLINE"
            }
        }
    }

    fun startAgentTask(userTask: String) {
        if (userTask.isBlank()) {
            Toast.makeText(getApplication(), "Please enter a contextual task prompt", Toast.LENGTH_SHORT).show()
            return
        }
        _isRunning.value = true
        _taskInput.value = userTask
        clearLogs()
        addLog(LogLevel.SYSTEM, "🚀 Starting Agentic Task loop...")
        addLog(LogLevel.SYSTEM, "Task Goal: \"$userTask\"")

        val sessId = LoggerAgent.startNewSession()
        LoggerAgent.logEvent(
            context = getApplication(),
            eventType = "SESSION_START",
            stepId = 0,
            userRequest = userTask,
            packageName = "system",
            executionResult = "Session initialized with ID $sessId"
        )

        var historyId = 0L
        viewModelScope.launch {
            historyId = repository.insertHistory(
                HistoryEntry(taskPrompt = userTask, status = "RUNNING", stepsCompleted = 0)
            )
        }

        agentJob = viewModelScope.launch(Dispatchers.Default) {
            val startConfig = repository.getConfigDirect()
            com.example.service.AgentServiceManager.preferPhysicalGestures = startConfig.preferPhysicalGestures
            val maxSteps = startConfig.maxSteps
            val customStepDelay = startConfig.stepDelayMs

            var step = 0
            val actionHistoryList = mutableListOf<String>()
            val lastActions = mutableListOf<com.example.network.AgentActionResponse>()
            var isCompleted = false
            var youtubePlayerTapped = false

            var lastScreenHashCode = 0
            while (isRunning.value && step < maxSteps && !isCompleted) {
                step++
                addLog(LogLevel.SYSTEM, "------- Step $step / $maxSteps -------")

                val currentConfig = repository.getConfigDirect()
                var nodes = emptyList<ScreenNode>()

                if (_isSimulatedMode.value || !isAccessibilityActive.value) {
                    nodes = getMockNodes(step, userTask)
                    addLog(LogLevel.SYSTEM, "[SIMULATION] Extracted ${nodes.size} mock layout nodes")
                } else {
                    nodes = AgentServiceManager.captureCurrentScreen()
                    addLog(LogLevel.SYSTEM, "Fetched ${nodes.size} screen elements from system")
                    
                    val windowIds = nodes.map { it.windowId }.distinct()
                    val clickables = nodes.filter { it.isClickable }.size
                    val mediaViews = nodes.filter {
                        val cls = it.className.lowercase(Locale.getDefault())
                        cls.contains("player") || cls.contains("video") || cls.contains("surface") || cls.contains("canvas") || cls.contains("gl")
                    }.size
                    addLog(LogLevel.SYSTEM, "🔍 [DIAGNOSTICS] Active Windows: ${windowIds.size} $windowIds | Clickable Nodes: $clickables | Custom/Media Conts: $mediaViews")
                }

                if (nodes.isEmpty()) {
                    addLog(LogLevel.SYSTEM, "⚠️ No elements found. Retrying in 2s...")
                    LoggerAgent.logEvent(
                        context = getApplication(),
                        eventType = "ERROR",
                        stepId = step,
                        userRequest = userTask,
                        errorMessage = "Empty screen nodes list detected"
                    )
                    delay(2000)
                    continue
                }

                // UI Change Detection
                val currentHash = nodes.map { "${it.id}:${it.text ?: ""}:${it.contentDescription ?: ""}:${it.bounds}" }.hashCode()
                val activePkg = nodes.firstOrNull { !it.packageName.isNullOrBlank() }?.packageName ?: ""
                
                if (step > 1) {
                    val changed = currentHash != lastScreenHashCode
                    addLog(LogLevel.SYSTEM, "🔄 UI Change Detected: $changed (Layout Hash: ${Integer.toHexString(currentHash)})")
                }

                LoggerAgent.logEvent(
                    context = getApplication(),
                    eventType = "SCREEN_BEFORE",
                    stepId = step,
                    packageName = activePkg,
                    userRequest = userTask,
                    nodeCount = nodes.size,
                    screenHashBefore = Integer.toHexString(currentHash),
                    windowSummary = "Nodes: ${nodes.size}, Pkg: $activePkg, Windows: ${nodes.map { it.windowId }.distinct().size}"
                )

                // 2. Rank nodes to limit size for small models (Heuristic scoring)
                val rankedNodes = rankNodes(nodes, userTask)
                val stringifiedNodes = rankedNodes.joinToString("\n") { it.toActionString() }
                addLog(LogLevel.SYSTEM, "Configuring request context payload (Nodes: ${nodes.size}, Ranked: ${rankedNodes.size})...")

                // Try to find if this is a YouTube player task to trigger player surface tap rules
                val isYouTubeActive = activePkg == "com.google.android.youtube"
                val isPlayerTask = userTask.lowercase(Locale.getDefault()).let {
                    it.contains("speed") || it.contains("fullscreen") || it.contains("player") || 
                    it.contains("settings") || it.contains("pause") || it.contains("play") || 
                    it.contains("forward") || it.contains("back") || it.contains("seek") || 
                    it.contains("caption") || it.contains("quality") || it.contains("timeline")
                }
                val isWhatsAppActive = activePkg == "com.whatsapp"
                val isNativeOrCanvasActive = nodes.any { node ->
                    val cls = node.className.lowercase(Locale.getDefault())
                    cls.contains("surface") || cls.contains("gl") || cls.contains("canvas")
                }

                // Deterministic Rule Preemptions to make the agent 100% reliable
                var response = if (isYouTubeActive && isPlayerTask && (!youtubePlayerTapped || !areYouTubeControlsVisible(nodes))) {
                    youtubePlayerTapped = true
                    
                    val playerNode = nodes.find { node ->
                        val cls = node.className.lowercase(Locale.getDefault())
                        val id = (node.viewId ?: "").lowercase(Locale.getDefault())
                        cls.contains("player") || cls.contains("video") || 
                        cls.contains("surface") || cls.contains("texture") || 
                        cls.contains("gl") || id.contains("player") || id.contains("watch")
                    }

                    val pctX: Float
                    val pctY: Float
                    val reasonDesc: String

                    if (playerNode != null) {
                        val metrics = getApplication<Application>().resources.displayMetrics
                        pctX = playerNode.bounds.centerX().toFloat() / metrics.widthPixels
                        pctY = playerNode.bounds.centerY().toFloat() / metrics.heightPixels
                        reasonDesc = "Found active player surface [${playerNode.id}]. Tapping to wake up player controls."
                    } else {
                        val isLandscape = getApplication<Application>().resources.displayMetrics.widthPixels > getApplication<Application>().resources.displayMetrics.heightPixels
                        pctX = 0.5f
                        pctY = if (isLandscape) 0.5f else 0.32f
                        reasonDesc = "Could not find a specific player surface node. Tapping center fallback coords ($pctX, $pctY) to reveal controls."
                    }

                    addLog(LogLevel.SYSTEM, "🤖 [DETERMINISTIC RULE] YouTube Player Tap: $reasonDesc")
                    
                    LoggerAgent.logEvent(
                        context = getApplication(),
                        eventType = "FALLBACK_USED",
                        stepId = step,
                        packageName = "com.google.android.youtube",
                        userRequest = userTask,
                        rawAiResponse = "Deterministic player surface click",
                        parsedActionJson = "CLICK_COORDS at ($pctX, $pctY)"
                    )

                    com.example.network.AgentActionResponse(
                        thought = "YouTube hides overlays. I need to tap player container to make button nodes active.",
                        action = "CLICK_COORDS",
                        package_name = "com.google.android.youtube",
                        pctX = pctX,
                        pctY = pctY,
                        durationMs = 80L,
                        text = "Reveal hidden YouTube player controls before searching for buttons"
                    )
                } else {
                    // Normal execution via LLM Planner
                    LoggerAgent.logEvent(
                        context = getApplication(),
                        eventType = "LLM_PROMPT_SENT",
                        stepId = step,
                        packageName = activePkg,
                        userRequest = userTask,
                        nodeCount = nodes.size,
                        screenHashBefore = Integer.toHexString(currentHash)
                    )

                    val apiResponse = try {
                        llmService.getNextAction(
                            provider = currentConfig.provider,
                            hostUrl = currentConfig.ollamaHost,
                            modelName = currentConfig.ollamaModel,
                            taskGoal = userTask,
                            screenNodes = stringifiedNodes,
                            actionHistory = actionHistoryList,
                            currentPackage = activePkg,
                            isYouTube = isYouTubeActive,
                            isWhatsApp = isWhatsAppActive,
                            isNativeOrCanvas = isNativeOrCanvasActive
                        )
                    } catch (e: Exception) {
                        e.printStackTrace()
                        null
                    }

                    if (apiResponse != null) {
                        LoggerAgent.logEvent(
                            context = getApplication(),
                            eventType = "LLM_RAW_RESPONSE",
                            stepId = step,
                            packageName = activePkg,
                            userRequest = userTask,
                            rawAiResponse = apiResponse.thought,
                            parsedActionJson = apiResponse.toString()
                        )
                    }
                    apiResponse
                }

                if (response == null) {
                    addLog(LogLevel.ERROR, "Model failed to return next step action.")
                    LoggerAgent.logEvent(
                        context = getApplication(),
                        eventType = "ERROR",
                        stepId = step,
                        packageName = activePkg,
                        errorMessage = "LLM planning or API connection failed"
                    )
                    _isRunning.value = false
                    break
                }

                // Loop Detection & Anti-stuck Escapes
                var isRepeatedActionWithNoChange = false
                if (lastActions.size >= 1) {
                    val last = lastActions.last()
                    val isSameAction = last.action == response.action && last.targetId == response.targetId
                    val isSameCoords = Math.abs(last.pctX - response.pctX) < 0.02f && Math.abs(last.pctY - response.pctY) < 0.02f
                    val noLayoutChange = currentHash == lastScreenHashCode
                    
                    if ((isSameAction || isSameCoords) && noLayoutChange) {
                        isRepeatedActionWithNoChange = true
                    }
                }
                lastActions.add(response)
                if (lastActions.size > 5) lastActions.removeAt(0)

                if (isRepeatedActionWithNoChange) {
                    addLog(LogLevel.SYSTEM, "⚠️ Stuck in a loop. Same action with no layout change. Forcing escape press back...")
                    LoggerAgent.logEvent(
                        context = getApplication(),
                        eventType = "FALLBACK_USED",
                        stepId = step,
                        packageName = activePkg,
                        executionResult = "Escape stuck loop: Forcing backward retry"
                    )
                    response = com.example.network.AgentActionResponse(
                        thought = "Detected a visual loop. Moving back to dismiss modal dialogs and reset layout state.",
                        action = "PRESS_BACK",
                        package_name = activePkg
                    )
                }

                addLog(LogLevel.THINKING, "🧠 Agent Chain of Thought:\n${response.thought}")
                addLog(LogLevel.ACTION, "⚡ Action Selected: ${response.action} " +
                        "${if(response.targetId != -1) "on Node [${response.targetId}]" else ""} " +
                        "${if(response.pctX >= 0 && response.pctY >= 0) "at Coords (${response.pctX}, ${response.pctY})" else ""} " +
                        "${if(response.package_name.isNotEmpty()) "(${response.package_name})" else ""} " +
                        if (response.text.isNotEmpty()) "(\"${response.text}\")" else ""
                )

                LoggerAgent.logEvent(
                    context = getApplication(),
                    eventType = "LLM_PARSED_ACTION",
                    stepId = step,
                    packageName = activePkg,
                    parsedActionJson = response.toString()
                )

                // Log selected Node info
                if (response.targetId != -1) {
                    val selNode = nodes.find { it.id == response.targetId }
                    if (selNode != null) {
                        LoggerAgent.logEvent(
                            context = getApplication(),
                            eventType = "NODE_SELECTED",
                            stepId = step,
                            packageName = activePkg,
                            selectedNodeSummary = selNode.toActionString()
                        )
                    }
                }

                actionHistoryList.add("Step $step: Action=${response.action}, Node=${response.targetId}, Coords=(${response.pctX}, ${response.pctY}), EndCoords=(${response.pctEndX}, ${response.pctEndY}), Package=${response.package_name}, CustomText=${response.text}")

                LoggerAgent.logEvent(
                    context = getApplication(),
                    eventType = "ACTION_START",
                    stepId = step,
                    packageName = activePkg,
                    parsedActionJson = response.toString(),
                    coordinates = "(${response.pctX}, ${response.pctY})"
                )

                var actionStatus = false
                val selectedNodeId = response.targetId

                if (_isSimulatedMode.value || !isAccessibilityActive.value) {
                    delay(1800)
                    actionStatus = true
                    addLog(LogLevel.SUCCESS, "[SIMULATION] Executed Action: ${response.action} successfully!")
                } else {
                    actionStatus = when (response.action.uppercase(Locale.getDefault())) {
                        "OPEN_APP" -> {
                            val pkg = response.package_name
                            if (pkg.isNotEmpty()) {
                                try {
                                    addLog(LogLevel.SYSTEM, "Checking installation status for: $pkg")
                                    val launchIntent = getApplication<Application>().packageManager.getLaunchIntentForPackage(pkg)
                                    if (launchIntent != null) {
                                        addLog(LogLevel.SYSTEM, "App found. Initiating launch for: $pkg")
                                        launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                        getApplication<Application>().startActivity(launchIntent)
                                        delay(2500)
                                        true
                                    } else {
                                        addLog(LogLevel.ERROR, "Launch intent not found. Trying direct main category fallback...")
                                        val fallbackIntent = getApplication<Application>().packageManager.getLeanbackLaunchIntentForPackage(pkg)
                                        if (fallbackIntent != null) {
                                            addLog(LogLevel.SYSTEM, "Found Leanback launcher. Launching: $pkg")
                                            fallbackIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                            getApplication<Application>().startActivity(fallbackIntent)
                                            delay(2500)
                                            true
                                        } else {
                                            val directIntent = Intent(Intent.ACTION_MAIN).apply {
                                                addCategory(Intent.CATEGORY_LAUNCHER)
                                                setPackage(pkg)
                                                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                            }
                                            try {
                                                getApplication<Application>().startActivity(directIntent)
                                                addLog(LogLevel.SUCCESS, "Successfully bypassed launcher constraint to boot: $pkg")
                                                delay(2500)
                                                true
                                            } catch (ex: Exception) {
                                                addLog(LogLevel.ERROR, "App package not installed or inaccessible: $pkg. Try installing it, or double-check the identifier.")
                                                false
                                            }
                                        }
                                    }
                                } catch (e: Exception) {
                                    addLog(LogLevel.ERROR, "Error opening app $pkg: ${e.message}")
                                    false
                                }
                            } else {
                                addLog(LogLevel.ERROR, "Empty package name received from model")
                                false
                            }
                        }
                        "CLICK", "CLICK_NODE" -> {
                            if (selectedNodeId != -1) {
                                val beforeHash = currentHash
                                val ok = AgentServiceManager.clickNode(selectedNodeId)
                                delay(customStepDelay)

                                val afterNodes = AgentServiceManager.captureCurrentScreen()
                                val afterHash = afterNodes.map { "${it.id}:${it.text ?: ""}:${it.contentDescription ?: ""}:${it.bounds}" }.hashCode()

                                if (ok && beforeHash == afterHash) {
                                    addLog(LogLevel.SYSTEM, "⚠️ Action click succeeded but layout hash didn't change! Retrying via physical coordinates...")
                                    LoggerAgent.logEvent(
                                        context = getApplication(),
                                        eventType = "FALLBACK_USED",
                                        stepId = step,
                                        packageName = activePkg,
                                        executionResult = "Accessibility Click failed to trigger layout change. Retrying via physical coords click."
                                    )
                                    val targetNode = nodes.find { it.id == selectedNodeId }
                                    if (targetNode != null) {
                                        val displayMetrics = getApplication<Application>().resources.displayMetrics
                                        val cx = targetNode.bounds.centerX().toFloat() / displayMetrics.widthPixels
                                        val cy = targetNode.bounds.centerY().toFloat() / displayMetrics.heightPixels
                                        AgentServiceManager.clickCoordinates(cx, cy, 80L)
                                        delay(customStepDelay)
                                        true
                                    } else ok
                                } else {
                                    ok
                                }
                            } else false
                        }
                        "LONG_CLICK", "LONG_CLICK_NODE" -> {
                            if (selectedNodeId != -1) {
                                val ok = AgentServiceManager.longClickNode(selectedNodeId)
                                delay(customStepDelay)
                                ok
                            } else false
                        }
                        "INPUT_TEXT" -> {
                            if (selectedNodeId != -1) {
                                val ok = AgentServiceManager.typeText(selectedNodeId, response.text)
                                delay(customStepDelay)
                                ok
                            } else false
                        }
                        "CLICK_COORDS" -> {
                            val x = response.pctX
                            val y = response.pctY
                            val dur = response.durationMs
                            if (x >= 0 && y >= 0) {
                                val ok = AgentServiceManager.clickCoordinates(x, y, dur)
                                delay(customStepDelay)
                                ok
                            } else {
                                addLog(LogLevel.ERROR, "Invalid percent coordinates for CLICK_COORDS: ($x, $y)")
                                false
                            }
                        }
                        "LONG_CLICK_COORDS" -> {
                            val x = response.pctX
                            val y = response.pctY
                            val dur = response.durationMs
                            if (x >= 0 && y >= 0) {
                                val ok = AgentServiceManager.longClickCoordinates(x, y, dur)
                                delay(customStepDelay)
                                ok
                            } else {
                                addLog(LogLevel.ERROR, "Invalid percent coordinates for LONG_CLICK_COORDS: ($x, $y)")
                                false
                            }
                        }
                        "SWIPE" -> {
                            val sx = response.pctX
                            val sy = response.pctY
                            val ex = response.pctEndX
                            val ey = response.pctEndY
                            val dur = response.durationMs
                            if (sx >= 0 && sy >= 0 && ex >= 0 && ey >= 0) {
                                val ok = AgentServiceManager.swipe(sx, sy, ex, ey, dur)
                                delay(customStepDelay + dur)
                                ok
                            } else {
                                addLog(LogLevel.ERROR, "Invalid percent coordinates for SWIPE: start=($sx, $sy), end=($ex, $ey)")
                                false
                            }
                        }
                        "PRESS_BACK" -> {
                            val ok = AgentServiceManager.performBack()
                            delay(customStepDelay)
                            ok
                        }
                        "PRESS_HOME" -> {
                            val ok = AgentServiceManager.performHome()
                            delay(customStepDelay)
                            ok
                        }
                        "WAIT" -> {
                            val dur = if (response.durationMs > 0) response.durationMs else 1000L
                            delay(dur)
                            true
                        }
                        "FINISH", "DONE" -> {
                            isCompleted = true
                            true
                        }
                        else -> {
                            addLog(LogLevel.ERROR, "Unidentified instruction: ${response.action}")
                            false
                        }
                    }
                    if (actionStatus) {
                        addLog(LogLevel.SUCCESS, "[HARDWARE ACTION] Action complete successfully.")
                    } else if (response.action.uppercase(Locale.getDefault()) != "FINISH" && response.action.uppercase(Locale.getDefault()) != "DONE") {
                        addLog(LogLevel.ERROR, "[HARDWARE ACTION] Action perform failed. Accessibility node might be offline.")
                    }
                }

                // Fetch new screen state after executing the action
                val postNodes = if (_isSimulatedMode.value || !isAccessibilityActive.value) {
                    emptyList()
                } else {
                    AgentServiceManager.captureCurrentScreen()
                }
                val postHash = postNodes.map { "${it.id}:${it.text ?: ""}:${it.contentDescription ?: ""}:${it.bounds}" }.hashCode()

                LoggerAgent.logEvent(
                    context = getApplication(),
                    eventType = "ACTION_RESULT",
                    stepId = step,
                    packageName = activePkg,
                    executionResult = if (actionStatus) "SUCCESS" else "FAILED",
                    screenHashAfter = Integer.toHexString(postHash)
                )

                LoggerAgent.logEvent(
                    context = getApplication(),
                    eventType = "SCREEN_AFTER",
                    stepId = step,
                    packageName = activePkg,
                    nodeCount = postNodes.size,
                    screenHashAfter = Integer.toHexString(postHash)
                )

                lastScreenHashCode = postHash

                if (response.action.uppercase(Locale.getDefault()) == "FINISH" || response.action.uppercase(Locale.getDefault()) == "DONE") {
                    isCompleted = true
                    addLog(LogLevel.SUCCESS, "🏆 Complete user command successfully! Status summary: ${response.text}")
                    viewModelScope.launch {
                        if (historyId != 0L) {
                            repository.updateHistory(
                                HistoryEntry(
                                    id = historyId.toInt(),
                                    taskPrompt = userTask,
                                    status = "SUCCESS",
                                    stepsCompleted = step,
                                    finalMessage = "Completed: ${response.text}"
                                )
                            )
                        }
                    }
                    LoggerAgent.logEvent(
                        context = getApplication(),
                        eventType = "SESSION_END",
                        stepId = step,
                        packageName = activePkg,
                        executionResult = "Completed: ${response.text}"
                    )
                    break
                }

                delay(1200)
            }

            if (!isCompleted && step >= maxSteps) {
                addLog(LogLevel.ERROR, "Hit loop prevention threshold ($maxSteps steps limit). Halting execution.")
                viewModelScope.launch {
                    if (historyId != 0L) {
                        repository.updateHistory(
                            HistoryEntry(
                                id = historyId.toInt(),
                                taskPrompt = userTask,
                                status = "TIMEOUT",
                                stepsCompleted = step,
                                finalMessage = "Limit exceeded ($maxSteps steps limit)"
                            )
                        )
                    }
                }
                LoggerAgent.logEvent(
                    context = getApplication(),
                    eventType = "SESSION_END",
                    stepId = step,
                    packageName = "system",
                    executionResult = "Max steps timeout threshold reached ($maxSteps limit)"
                )
            }

            viewModelScope.launch(Dispatchers.Main) {
                _isRunning.value = false
            }
        }
    }

    fun stopAgentTask() {
        agentJob?.cancel()
        _isRunning.value = false
        addLog(LogLevel.ERROR, "🛑 Command loop forcefully stopped.")
    }

    fun clearHistory() {
        viewModelScope.launch(Dispatchers.IO) {
            repository.clearHistory()
        }
    }

    fun openAccessibilitySettings() {
        try {
            val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            getApplication<Application>().startActivity(intent)
        } catch (e: Exception) {
            Toast.makeText(getApplication(), "Could not open settings.", Toast.LENGTH_SHORT).show()
        }
    }

    private fun getMockNodes(step: Int, task: String): List<ScreenNode> {
        val r = android.graphics.Rect(0, 0, 100, 100)
        return when {
            task.lowercase().contains("whatsapp") -> {
                when (step) {
                    1 -> listOf(
                        ScreenNode(1, "android.widget.TextView", "Home Screen Launcher", null, null, false, false, false, r),
                        ScreenNode(2, "android.widget.ImageView", "WhatsApp App Icon", "Launch WhatsApp chat", "com.android.launcher:id/whatsapp", true, false, false, r),
                        ScreenNode(3, "android.widget.ImageView", "System Phone Dial", "Phone application switcher", null, true, false, false, r)
                    )
                    2 -> listOf(
                        ScreenNode(1, "android.widget.TextView", "WhatsApp Active Chats", null, null, false, false, false, r),
                        ScreenNode(2, "android.widget.TextView", "John Doe (Online)", "Tap to chat with John", "com.whatsapp:id/chat_john", true, false, false, r),
                        ScreenNode(3, "android.widget.EditText", "Filter search contacts list", "Search contact searchbar", "com.whatsapp:id/menu_search", true, true, false, r)
                    )
                    3 -> listOf(
                        ScreenNode(1, "android.widget.TextView", "Chat screen with John Doe", null, null, false, false, false, r),
                        ScreenNode(2, "android.widget.EditText", "Write your message to John", "Chat messaging edittext box", "com.whatsapp:id/entry_text_box", true, true, false, r),
                        ScreenNode(3, "android.widget.ImageView", "Stickers and GIFs emoji button", null, null, true, false, false, r)
                    )
                    else -> listOf(
                        ScreenNode(1, "android.widget.TextView", "Chat screen with John Doe", null, null, false, false, false, r),
                        ScreenNode(2, "android.widget.TextView", "how are you", null, null, false, false, false, r),
                        ScreenNode(3, "android.widget.ImageView", "Send button", "Click trigger text deliver", "com.whatsapp:id/send_button", true, false, false, r)
                    )
                }
            }
            task.lowercase().contains("photo") || task.lowercase().contains("camera") -> {
                listOf(
                    ScreenNode(1, "android.widget.TextView", "System Viewfinder Preview", "Camera real viewfinder block", null, false, false, false, r),
                    ScreenNode(2, "android.widget.ImageButton", "Record shutter icon", "Shutter trigger shoot snapshot", "com.android.camera:id/shutter", true, false, false, r),
                    ScreenNode(3, "android.widget.ImageButton", "Toggle front camera swap", "Camera rotate", null, true, false, false, r)
                )
            }
            else -> {
                listOf(
                    ScreenNode(1, "android.widget.TextView", "Android System Browser Screen", null, null, false, false, false, r),
                    ScreenNode(2, "android.widget.EditText", "Enter prompt url query search", "Search browser input field", "com.android.chrome:id/url_bar", true, true, false, r),
                    ScreenNode(3, "android.widget.Button", "Confirm navigation", "Click confirm google search action", "com.android.chrome:id/search_btn", true, false, false, r)
                )
            }
        }
    }

    private fun areYouTubeControlsVisible(nodes: List<ScreenNode>): Boolean {
        return nodes.any { node ->
            val id = (node.viewId ?: "").lowercase(Locale.getDefault())
            val desc = (node.contentDescription ?: "").lowercase(Locale.getDefault())
            val text = (node.text ?: "").lowercase(Locale.getDefault())
            id.contains("play_button") || id.contains("pause_button") ||
            id.contains("next_button") || id.contains("prev_button") ||
            id.contains("fullscreen") || desc.contains("play") ||
            desc.contains("pause") || desc.contains("fullscreen") ||
            desc.contains("more options") || text.contains("1.5x") || text.contains("playback speed")
        }
    }

    private fun rankNodes(nodes: List<ScreenNode>, userTask: String): List<ScreenNode> {
        val keywords = userTask.lowercase(Locale.getDefault()).split("\\s+".toRegex())
        return nodes.sortedByDescending { node ->
            var score = 0
            if (node.isClickable) score += 15
            if (node.isEditable) score += 15
            if (!node.text.isNullOrBlank()) score += 10
            if (!node.contentDescription.isNullOrBlank()) score += 10
            if (!node.viewId.isNullOrBlank()) score += 5
            
            val textContent = "${node.viewId ?: ""} ${node.text ?: ""} ${node.contentDescription ?: ""}".lowercase(Locale.getDefault())
            for (kw in keywords) {
                if (kw.length > 2 && textContent.contains(kw)) {
                    score += 30
                }
            }
            score
        }.take(25)
    }

    fun exportLogs() {
        viewModelScope.launch(Dispatchers.IO) {
            val success = com.example.service.LoggerAgent.exportLogs(getApplication())
            viewModelScope.launch(Dispatchers.Main) {
                if (success) {
                    Toast.makeText(getApplication(), "Logs successfully copied to main storage PhoneAgentLogs folder!", Toast.LENGTH_LONG).show()
                } else {
                    Toast.makeText(getApplication(), "Failed to write raw logs to root. Falling back or request SD card rights.", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    fun clearOldLogs() {
        viewModelScope.launch(Dispatchers.IO) {
            val success = com.example.service.LoggerAgent.clearLogs(getApplication())
            viewModelScope.launch(Dispatchers.Main) {
                if (success) {
                    Toast.makeText(getApplication(), "All session logs historically cleared.", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(getApplication(), "No logs found to erase.", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    suspend fun dumpWindows(): String {
        return AgentServiceManager.dumpCurrentWindows()
    }

    suspend fun dumpNodeTree(): String {
        val current = AgentServiceManager.captureCurrentScreen()
        return if (current.isEmpty()) "No nodes available or accessibility service offline."
        else current.joinToString("\n") { it.toActionString() }
    }
}
