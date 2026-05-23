package com.example.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.graphics.Rect
import android.os.Bundle
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import java.util.Locale
import kotlinx.coroutines.*
import kotlin.coroutines.resume

class AgenticAccessibilityService : AccessibilityService() {

    private val serviceScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var nodeCacheList = mutableListOf<ScreenNode>()

    override fun onServiceConnected() {
        super.onServiceConnected()
        AgentServiceManager.registerService(this)
        serviceScope.launch {
            refreshScreenHierarchy()
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val eventType = event?.eventType
        if (eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED ||
            eventType == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED) {
            serviceScope.launch {
                refreshScreenHierarchy()
                checkAndSkipAds()
            }
        }
    }

    override fun onInterrupt() {
        AgentServiceManager.unregisterService()
    }

    override fun onDestroy() {
        super.onDestroy()
        AgentServiceManager.unregisterService()
        serviceScope.cancel()
    }

    data class WindowMetadata(
        val windowId: Int,
        val windowType: Int,
        val isWindowFocused: Boolean,
        val isWindowActive: Boolean,
        val windowTitle: String?,
        val windowLayer: Int
    )

    suspend fun refreshScreenHierarchy(): List<ScreenNode> = withContext(Dispatchers.Main) {
        val newList = mutableListOf<ScreenNode>()
        
        // Recycle old cached nodes to prevent binder leaks
        for (oldNode in nodeCacheList) {
            try {
                oldNode.originalNode?.recycle()
            } catch (e: Exception) {
                // Ignore
            }
        }
        nodeCacheList.clear()

        val filterAd = try {
            val db = com.example.data.AppDatabase.getDatabase(this@AgenticAccessibilityService)
            val config = withContext(Dispatchers.IO) { db.agentDao.getConfigDirect() }
            config?.filterAdNodes ?: true
        } catch (e: Exception) {
            true
        }

        val activeWindows = try { windows } catch (e: Exception) { null }
        if (!activeWindows.isNullOrEmpty()) {
            for (window in activeWindows) {
                val root = try { window.root } catch (e: Exception) { null }
                if (root != null) {
                    val title = try { window.title?.toString() } catch (e: Exception) { null }
                    val meta = WindowMetadata(
                        windowId = window.id,
                        windowType = window.type,
                        isWindowFocused = window.isFocused,
                        isWindowActive = window.isActive,
                        windowTitle = title,
                        windowLayer = window.layer
                    )
                    traverseNodes(root, meta, "win_${window.id}", newList, filterAd)
                    try {
                        root.recycle()
                    } catch (e: Exception) {
                        // Ignore
                    }
                }
            }
        } else {
            val root = rootInActiveWindow
            if (root != null) {
                val meta = WindowMetadata(
                    windowId = -1,
                    windowType = -1,
                    isWindowFocused = true,
                    isWindowActive = true,
                    windowTitle = "Active Window Fallback",
                    windowLayer = 0
                )
                traverseNodes(root, meta, "fallback", newList, filterAd)
                try {
                    root.recycle()
                } catch (e: Exception) {
                    // Ignore
                }
            }
        }

        nodeCacheList = newList
        AgentServiceManager.updateScreenNodes(newList)
        newList
    }

    private fun isAdNode(node: AccessibilityNodeInfo): Boolean {
        val textStr = (node.text ?: "").toString().lowercase(Locale.getDefault())
        val descStr = (node.contentDescription ?: "").toString().lowercase(Locale.getDefault())
        val idStr = (node.viewIdResourceName ?: "").lowercase(Locale.getDefault())
        
        return textStr.contains("sponsored") || textStr.contains("promoted") || 
               textStr.contains("advertisement") || textStr.contains(" ad ") || 
               textStr.startsWith("ad ") || textStr.endsWith(" ad") ||
               descStr.contains("sponsored") || descStr.contains("promoted") || 
               descStr.contains("advertisement") ||
               idStr.contains("ad_container") || idStr.contains("ad_image") || 
               idStr.contains("ad_video") || idStr.contains("ad_banner") || 
               idStr.contains("card_ad") || idStr.contains("sponsored") ||
               idStr.contains("player_ad") || idStr.contains("promo_holder")
    }

    private fun findSkipButton(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        val textLower = (node.text ?: "").toString().lowercase(Locale.getDefault())
        val descLower = (node.contentDescription ?: "").toString().lowercase(Locale.getDefault())
        val idLower = (node.viewIdResourceName ?: "").lowercase(Locale.getDefault())
        val isSkip = textLower.contains("skip") || descLower.contains("skip") || idLower.contains("skip") ||
                     textLower.contains("close") || descLower.contains("close") || idLower.contains("close") ||
                     textLower.contains("dismiss") || descLower.contains("dismiss") || idLower.contains("dismiss")
        
        if (isSkip && (node.isClickable || node.text != null)) {
            return AccessibilityNodeInfo.obtain(node)
        }
        
        val count = node.childCount
        for (i in 0 until count) {
            val child = try { node.getChild(i) } catch (e: Exception) { null }
            if (child != null) {
                val found = findSkipButton(child)
                try { child.recycle() } catch (e: Exception) {}
                if (found != null) {
                    return found
                }
            }
        }
        return null
    }

    private fun traverseNodes(
        node: AccessibilityNodeInfo?,
        meta: WindowMetadata,
        path: String,
        list: MutableList<ScreenNode>,
        filterAd: Boolean = true
    ) {
        if (node == null) return

        if (filterAd && isAdNode(node)) {
            val skipButton = findSkipButton(node)
            if (skipButton != null) {
                val bounds = Rect()
                skipButton.getBoundsInScreen(bounds)
                if (bounds.width() > 0 && bounds.height() > 0) {
                    val className = skipButton.className?.toString() ?: "android.view.View"
                    val packageName = skipButton.packageName?.toString() ?: ""
                    val parentClass = try { skipButton.parent?.className?.toString() } catch (e: Exception) { null }
                    list.add(
                        ScreenNode(
                            id = list.size + 1,
                            className = className,
                            text = skipButton.text?.toString(),
                            contentDescription = skipButton.contentDescription?.toString(),
                            viewId = skipButton.viewIdResourceName,
                            isClickable = skipButton.isClickable,
                            isEditable = skipButton.isEditable,
                            isScrollable = skipButton.isScrollable,
                            bounds = bounds,
                            originalNode = skipButton,
                            
                            windowId = meta.windowId,
                            windowType = meta.windowType,
                            isWindowFocused = meta.isWindowFocused,
                            isWindowActive = meta.isWindowActive,
                            windowTitle = meta.windowTitle,
                            windowLayer = meta.windowLayer,
                            
                            isEnabled = skipButton.isEnabled,
                            isVisibleToUser = skipButton.isVisibleToUser,
                            isFocusable = skipButton.isFocusable,
                            isSelected = skipButton.isSelected,
                            isChecked = skipButton.isChecked,
                            isLongClickable = skipButton.isLongClickable,
                            childCount = skipButton.childCount,
                            parentClassName = parentClass,
                            packageName = packageName,
                            path = "$path/skipped_ad_child"
                        )
                    )
                    AgentServiceManager.incrementAdSkipped()
                } else {
                    try { skipButton.recycle() } catch (e: Exception) {}
                }
            } else {
                AgentServiceManager.incrementAdSkipped()
            }
            return
        }

        val hasText = !node.text.isNullOrBlank() || !node.contentDescription.isNullOrBlank()
        val isInteractive = node.isClickable || node.isScrollable || node.isEditable || node.isLongClickable

        var shouldCapture = hasText || isInteractive
        
        if (!shouldCapture) {
            val className = node.className?.toString() ?: ""
            val viewId = node.viewIdResourceName ?: ""
            if (className.isNotEmpty() || viewId.isNotEmpty()) {
                val classNameLower = className.lowercase(Locale.getDefault())
                val viewIdLower = viewId.lowercase(Locale.getDefault())
                shouldCapture = KEYWORDS.any { classNameLower.contains(it) || viewIdLower.contains(it) }
            }
        }

        if (shouldCapture) {
            val bounds = Rect()
            node.getBoundsInScreen(bounds)
            
            if (bounds.width() > 0 && bounds.height() > 0) {
                val persistNode = AccessibilityNodeInfo.obtain(node)
                val className = node.className?.toString() ?: "android.view.View"
                val packageName = node.packageName?.toString() ?: ""
                val parentClass = try { node.parent?.className?.toString() } catch (e: Exception) { null }
                
                list.add(
                    ScreenNode(
                        id = list.size + 1,
                        className = className,
                        text = node.text?.toString(),
                        contentDescription = node.contentDescription?.toString(),
                        viewId = node.viewIdResourceName,
                        isClickable = node.isClickable,
                        isEditable = node.isEditable,
                        isScrollable = node.isScrollable,
                        bounds = bounds,
                        originalNode = persistNode,
                        
                        windowId = meta.windowId,
                        windowType = meta.windowType,
                        isWindowFocused = meta.isWindowFocused,
                        isWindowActive = meta.isWindowActive,
                        windowTitle = meta.windowTitle,
                        windowLayer = meta.windowLayer,
                        
                        isEnabled = node.isEnabled,
                        isVisibleToUser = node.isVisibleToUser,
                        isFocusable = node.isFocusable,
                        isSelected = node.isSelected,
                        isChecked = node.isChecked,
                        isLongClickable = node.isLongClickable,
                        childCount = node.childCount,
                        parentClassName = parentClass,
                        packageName = packageName,
                        path = path
                    )
                )
            }
        }

        for (i in 0 until node.childCount) {
            val child = try { node.getChild(i) } catch (e: Exception) { null }
            if (child != null) {
                traverseNodes(child, meta, "$path/$i", list, filterAd)
                try {
                    child.recycle()
                } catch (e: Exception) {
                    // Ignore
                }
            }
        }
    }

    private class BestTracker {
        var score = 0f
        var node: AccessibilityNodeInfo? = null
    }

    private fun findMatchingNode(target: ScreenNode): AccessibilityNodeInfo? {
        val activeWindows = try { windows } catch (e: Exception) { null }
        if (!activeWindows.isNullOrEmpty() && target.windowId != -1) {
            for (window in activeWindows) {
                if (window.id == target.windowId) {
                    val root = try { window.root } catch (e: Exception) { null }
                    if (root != null) {
                        val found = findNodeByPath(root, target.path, "win_${window.id}")
                        try { root.recycle() } catch (e: Exception) {}
                        if (found != null) {
                            return found
                        }
                    }
                }
            }
        }
        
        var bestMatch: AccessibilityNodeInfo? = null
        var bestScore = 0f
        
        if (!activeWindows.isNullOrEmpty()) {
            for (window in activeWindows) {
                val root = try { window.root } catch (e: Exception) { null }
                if (root != null) {
                    val tracker = BestTracker()
                    scoreAndTrackBest(root, target, tracker)
                    try { root.recycle() } catch (e: Exception) {}
                    if (tracker.score > bestScore && tracker.node != null) {
                        bestMatch?.recycle()
                        bestScore = tracker.score
                        bestMatch = tracker.node
                    } else {
                        tracker.node?.recycle()
                    }
                }
            }
        }
        return bestMatch
    }

    private fun findNodeByPath(node: AccessibilityNodeInfo, targetPath: String, currentPath: String): AccessibilityNodeInfo? {
        if (currentPath == targetPath) {
            return AccessibilityNodeInfo.obtain(node)
        }
        for (i in 0 until node.childCount) {
            val child = try { node.getChild(i) } catch (e: Exception) { null }
            if (child != null) {
                val found = findNodeByPath(child, targetPath, "$currentPath/$i")
                try { child.recycle() } catch (e: Exception) {}
                if (found != null) {
                    return found
                }
            }
        }
        return null
    }

    private fun scoreAndTrackBest(node: AccessibilityNodeInfo, target: ScreenNode, tracker: BestTracker) {
        var score = 0f
        val bounds = Rect()
        node.getBoundsInScreen(bounds)
        
        if (node.className?.toString() == target.className) {
            score += 2f
        }
        if (node.packageName?.toString() == target.packageName) {
            score += 1f
        }
        if (!target.text.isNullOrBlank() && node.text?.toString() == target.text) {
            score += 5f
        }
        if (!target.contentDescription.isNullOrBlank() && node.contentDescription?.toString() == target.contentDescription) {
            score += 5f
        }
        if (bounds.width() > 0 && bounds.height() > 0) {
            val overlap = Rect()
            if (overlap.setIntersect(bounds, target.bounds)) {
                score += 3f
            }
            val dist = Math.abs(bounds.centerX() - target.bounds.centerX()) + Math.abs(bounds.centerY() - target.bounds.centerY())
            if (dist < 100) {
                score += (100 - dist) / 50f
            }
        }
        
        if (score > tracker.score) {
            tracker.node?.recycle()
            tracker.score = score
            tracker.node = AccessibilityNodeInfo.obtain(node)
        }
        
        for (i in 0 until node.childCount) {
            val child = try { node.getChild(i) } catch (e: Exception) { null }
            if (child != null) {
                scoreAndTrackBest(child, target, tracker)
                try { child.recycle() } catch (e: Exception) {}
            }
        }
    }

    suspend fun clickCoordinates(pctX: Float, pctY: Float, durationMs: Long = 80L): Boolean = withContext(Dispatchers.Main) {
        val metrics = resources.displayMetrics
        val screenWidth = metrics.widthPixels
        val screenHeight = metrics.heightPixels
        val normX = normalizePct(pctX)
        val normY = normalizePct(pctY)
        val x = normX * screenWidth
        val y = normY * screenHeight
        Log.d("AgenticAccessibilityService", "CLICK_COORDS Input: ($pctX, $pctY) | Normalized: ($normX, $normY) | Pixels: ($x, $y)")
        dispatchTapGestureInternal(x, y, durationMs)
    }

    suspend fun longClickCoordinates(pctX: Float, pctY: Float, durationMs: Long = 750L): Boolean = withContext(Dispatchers.Main) {
        val metrics = resources.displayMetrics
        val screenWidth = metrics.widthPixels
        val screenHeight = metrics.heightPixels
        val normX = normalizePct(pctX)
        val normY = normalizePct(pctY)
        val x = normX * screenWidth
        val y = normY * screenHeight
        Log.d("AgenticAccessibilityService", "LONG_CLICK_COORDS Input: ($pctX, $pctY) | Normalized: ($normX, $normY) | Pixels: ($x, $y)")
        dispatchLongPressGestureInternal(x, y, durationMs)
    }

    suspend fun swipe(pctStartX: Float, pctStartY: Float, pctEndX: Float, pctEndY: Float, durationMs: Long = 450L): Boolean = withContext(Dispatchers.Main) {
        val metrics = resources.displayMetrics
        val screenWidth = metrics.widthPixels
        val screenHeight = metrics.heightPixels
        val normSx = normalizePct(pctStartX)
        val normSy = normalizePct(pctStartY)
        val normEx = normalizePct(pctEndX)
        val normEy = normalizePct(pctEndY)
        val startX = normSx * screenWidth
        val startY = normSy * screenHeight
        val endX = normEx * screenWidth
        val endY = normEy * screenHeight
        Log.d("AgenticAccessibilityService", "SWIPE Input: start=($pctStartX, $pctStartY), end=($pctEndX, $pctEndY) | Normalized: start=($normSx, $normSy), end=($normEx, $normEy) | Pixels: start=($startX, $startY), end=($endX, $endY)")

        val path = Path().apply {
            moveTo(startX, startY)
            lineTo(endX, endY)
        }
        val stroke = GestureDescription.StrokeDescription(path, 0, durationMs)
        val gesture = GestureDescription.Builder().addStroke(stroke).build()

        withContext(Dispatchers.Main) {
            withTimeoutOrNull(durationMs + 1500) {
                suspendCancellableCoroutine<Boolean> { continuation ->
                    try {
                        val started = dispatchGesture(gesture, object : GestureResultCallback() {
                            override fun onCompleted(gestureDescription: GestureDescription?) {
                                super.onCompleted(gestureDescription)
                                if (continuation.isActive) {
                                    continuation.resume(true)
                                }
                            }

                            override fun onCancelled(gestureDescription: GestureDescription?) {
                                super.onCancelled(gestureDescription)
                                if (continuation.isActive) {
                                    continuation.resume(false)
                                }
                            }
                        }, null)

                        if (!started) {
                            if (continuation.isActive) {
                                continuation.resume(false)
                            }
                        }
                    } catch (e: Exception) {
                        if (continuation.isActive) {
                            continuation.resume(false)
                        }
                    }
                }
            } ?: false
        }
    }

    private fun normalizePct(pct: Float): Float {
        val raw = if (pct > 1.0f) pct / 100f else pct
        return raw.coerceIn(0f, 1f)
    }

    suspend fun dumpWindows(): String = withContext(Dispatchers.Main) {
        val activeWindows = try { windows } catch (e: Exception) { null }
        if (activeWindows.isNullOrEmpty()) {
            "No active windows found or accessible"
        } else {
            activeWindows.joinToString("\n") { win ->
                val title = try { win.title?.toString() ?: "Unnamed" } catch (e: Exception) { "Unknown" }
                "HostWindow[Id: ${win.id}, Layer: ${win.layer}, Type: ${win.type}, Title: \"$title\", Focused: ${win.isFocused}, Active: ${win.isActive}]"
            }
        }
    }

    suspend fun clickNode(nodeId: Int): Boolean = withContext(Dispatchers.Main) {
        val screenNode = nodeCacheList.find { it.id == nodeId } ?: return@withContext false
        val resolvedLiveNode = findMatchingNode(screenNode)
        
        val bounds = screenNode.bounds
        val cx = bounds.centerX().toFloat()
        val cy = bounds.centerY().toFloat()
        val pkg = screenNode.packageName ?: ""
        
        val preferPhysical = try {
            val db = com.example.data.AppDatabase.getDatabase(this@AgenticAccessibilityService)
            val config = withContext(Dispatchers.IO) { db.agentDao.getConfigDirect() }
            config?.preferPhysicalGestures ?: true
        } catch (e: Exception) {
            true
        }

        val isNativeRich = screenNode.className.contains("Player", ignoreCase = true) ||
                           screenNode.className.contains("Video", ignoreCase = true) ||
                           screenNode.className.contains("Surface", ignoreCase = true) ||
                           screenNode.className.contains("Gl", ignoreCase = true) ||
                           screenNode.className.contains("Canvas", ignoreCase = true) ||
                           pkg.contains("whatsapp", ignoreCase = true) ||
                           pkg.contains("youtube", ignoreCase = true)

        var success = false
        var methodUsed = ""

        val nodeToUse = resolvedLiveNode ?: screenNode.originalNode

        // Part 1: For native/rich apps or when prioritized, use physical gesture first.
        if ((isNativeRich || preferPhysical) && cx > 0 && cy > 0) {
            success = dispatchTapGestureInternal(cx, cy, 80)
            methodUsed = "PHYSICAL_GESTURE"
        }

        // Part 2: If we didn't try physical click, or physical click failed, try normal action click.
        if (!success && nodeToUse != null) {
            try {
                success = if (nodeToUse.isClickable) {
                    nodeToUse.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                } else {
                    var parent = nodeToUse.parent
                    var clicked = false
                    while (parent != null && !clicked) {
                        if (parent.isClickable) {
                            clicked = parent.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                        }
                        parent = parent.parent
                    }
                    clicked
                }
                if (success) {
                    methodUsed = "ACCESSIBILITY_CLICK"
                }
            } catch (e: Exception) {
                // ignore
            }
        }

        // Part 3: Fallback physical gesture click if accessibility click returned false or failed
        if (!success && cx > 0 && cy > 0) {
            success = dispatchTapGestureInternal(cx, cy, 80)
            methodUsed = "PHYSICAL_GESTURE_FALLBACK"
        }

        // Clean up resolvedLiveNode to prevent leaks
        try {
            resolvedLiveNode?.recycle()
        } catch (e: Exception) {}

        Log.d("AgenticAccessibilityService", "CLICK on node $nodeId [Class: ${screenNode.className}, Pkg: $pkg] at ($cx, $cy) using $methodUsed (Success=$success)")
        
        success
    }

    suspend fun longClickNode(nodeId: Int): Boolean = withContext(Dispatchers.Main) {
        val screenNode = nodeCacheList.find { it.id == nodeId } ?: return@withContext false
        val resolvedLiveNode = findMatchingNode(screenNode)
        
        val bounds = screenNode.bounds
        val cx = bounds.centerX().toFloat()
        val cy = bounds.centerY().toFloat()
        val pkg = screenNode.packageName ?: ""
        
        val preferPhysical = com.example.service.AgentServiceManager.preferPhysicalGestures

        val isNativeRich = screenNode.className.contains("Player", ignoreCase = true) ||
                           screenNode.className.contains("Video", ignoreCase = true) ||
                           screenNode.className.contains("Surface", ignoreCase = true) ||
                           screenNode.className.contains("Gl", ignoreCase = true) ||
                           screenNode.className.contains("Canvas", ignoreCase = true) ||
                           pkg.contains("whatsapp", ignoreCase = true) ||
                           pkg.contains("youtube", ignoreCase = true)

        var success = false
        var methodUsed = ""

        val nodeToUse = resolvedLiveNode ?: screenNode.originalNode

        // For native/rich apps (especially WhatsApp where reaction needs a precise long-press) or when configured, physical first
        if ((isNativeRich || preferPhysical) && cx > 0 && cy > 0) {
            success = dispatchLongPressGestureInternal(cx, cy, 750)
            methodUsed = "PHYSICAL_GESTURE"
        }

        if (!success && nodeToUse != null) {
            try {
                success = nodeToUse.performAction(AccessibilityNodeInfo.ACTION_LONG_CLICK)
                if (!success) {
                    var parent = nodeToUse.parent
                    while (parent != null && !success) {
                        success = parent.performAction(AccessibilityNodeInfo.ACTION_LONG_CLICK)
                        parent = parent.parent
                    }
                }
                if (success) {
                    methodUsed = "ACCESSIBILITY_LONG_CLICK"
                }
            } catch (e: Exception) {
                // Ignore
            }
        }

        if (!success && cx > 0 && cy > 0) {
            success = dispatchLongPressGestureInternal(cx, cy, 750)
            methodUsed = "PHYSICAL_GESTURE_FALLBACK"
        }

        try {
            resolvedLiveNode?.recycle()
        } catch (e: Exception) {}

        Log.d("AgenticAccessibilityService", "LONG_CLICK on node $nodeId [Class: ${screenNode.className}, Pkg: $pkg] at ($cx, $cy) using $methodUsed (Success=$success)")

        success
    }

    private suspend fun dispatchTapGestureInternal(x: Float, y: Float, durationMs: Long = 80L): Boolean {
        val path = Path().apply { moveTo(x, y) }
        val stroke = GestureDescription.StrokeDescription(path, 0, durationMs)
        val gesture = GestureDescription.Builder().addStroke(stroke).build()
        
        return withContext(Dispatchers.Main) {
            withTimeoutOrNull(2000) {
                suspendCancellableCoroutine<Boolean> { continuation ->
                    try {
                        val started = dispatchGesture(gesture, object : GestureResultCallback() {
                            override fun onCompleted(gestureDescription: GestureDescription?) {
                                super.onCompleted(gestureDescription)
                                if (continuation.isActive) {
                                    continuation.resume(true)
                                }
                            }

                            override fun onCancelled(gestureDescription: GestureDescription?) {
                                super.onCancelled(gestureDescription)
                                if (continuation.isActive) {
                                    continuation.resume(false)
                                }
                            }
                        }, null)
                        
                        if (!started) {
                            if (continuation.isActive) {
                                continuation.resume(false)
                            }
                        }
                    } catch (e: Exception) {
                        if (continuation.isActive) {
                            continuation.resume(false)
                        }
                    }
                }
            } ?: false
        }
    }

    private suspend fun dispatchLongPressGestureInternal(x: Float, y: Float, durationMs: Long = 1000L): Boolean {
        val path = Path().apply { moveTo(x, y) }
        val stroke = GestureDescription.StrokeDescription(path, 0, durationMs)
        val gesture = GestureDescription.Builder().addStroke(stroke).build()
        
        return withContext(Dispatchers.Main) {
            withTimeoutOrNull(2500) {
                suspendCancellableCoroutine<Boolean> { continuation ->
                    try {
                        val started = dispatchGesture(gesture, object : GestureResultCallback() {
                            override fun onCompleted(gestureDescription: GestureDescription?) {
                                super.onCompleted(gestureDescription)
                                if (continuation.isActive) {
                                    continuation.resume(true)
                                }
                            }

                            override fun onCancelled(gestureDescription: GestureDescription?) {
                                super.onCancelled(gestureDescription)
                                if (continuation.isActive) {
                                    continuation.resume(false)
                                }
                            }
                        }, null)
                        
                        if (!started) {
                            if (continuation.isActive) {
                                continuation.resume(false)
                            }
                        }
                    } catch (e: Exception) {
                        if (continuation.isActive) {
                            continuation.resume(false)
                        }
                    }
                }
            } ?: false
        }
    }

    suspend fun typeText(nodeId: Int, text: String): Boolean = withContext(Dispatchers.Main) {
        val node = nodeCacheList.find { it.id == nodeId }?.originalNode ?: return@withContext false
        if (!node.isEditable) return@withContext false
        
        val arguments = Bundle()
        arguments.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        try {
            node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, arguments)
        } catch (e: Exception) {
            false
        }
    }

    suspend fun scrollNode(nodeId: Int, directionForward: Boolean): Boolean = withContext(Dispatchers.Main) {
        val node = nodeCacheList.find { it.id == nodeId }?.originalNode ?: return@withContext false
        val action = if (directionForward) {
            AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
        } else {
            AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
        }
        try {
            node.performAction(action)
        } catch (e: Exception) {
            false
        }
    }

    suspend fun performGlobalBack(): Boolean = withContext(Dispatchers.Main) {
        performGlobalAction(GLOBAL_ACTION_BACK)
    }

    suspend fun performGlobalHome(): Boolean = withContext(Dispatchers.Main) {
        performGlobalAction(GLOBAL_ACTION_HOME)
    }

    private fun checkAndSkipAds() {
        serviceScope.launch(Dispatchers.Default) {
            try {
                val db = com.example.data.AppDatabase.getDatabase(this@AgenticAccessibilityService)
                val config = db.agentDao.getConfigDirect()
                if (config?.autoSkipAds != true) return@launch

                val listCopy = synchronized(nodeCacheList) { ArrayList(nodeCacheList) }
                for (node in listCopy) {
                    val textStr = (node.text ?: "").lowercase(Locale.getDefault())
                    val descStr = (node.contentDescription ?: "").lowercase(Locale.getDefault())
                    val idStr = (node.viewId ?: "").lowercase(Locale.getDefault())
                    
                    val isSkipButton = textStr.contains("skip ad") || textStr.contains("skipad") ||
                            textStr.contains("skip advertisement") || textStr.contains("close ad") ||
                            descStr.contains("skip ad") || descStr.contains("skipad") ||
                            idStr.contains("skip_ad") || idStr.contains("skipad") || idStr.contains("skip_button") ||
                            idStr.contains("close_ad") || (textStr.contains("skip") && textStr.contains("ad")) ||
                            textStr == "skip" || descStr == "skip"
                    
                    if (isSkipButton && (node.isClickable || node.isEnabled)) {
                        withContext(Dispatchers.Main) {
                            Log.d("AgenticService", "Auto-Click Skip Ad triggered: bounds=${node.bounds}")
                            var done = false
                            try {
                                done = node.originalNode?.performAction(AccessibilityNodeInfo.ACTION_CLICK) ?: false
                            } catch (e: Exception) {
                                Log.e("AgenticService", "Auto-click action failed: ${e.message}")
                            }
                            
                            if (!done && node.bounds.centerX() > 0 && node.bounds.centerY() > 0) {
                                done = clickCoordinates(
                                    pctX = (node.bounds.centerX().toFloat() / resources.displayMetrics.widthPixels) * 100f,
                                    pctY = (node.bounds.centerY().toFloat() / resources.displayMetrics.heightPixels) * 100f,
                                    durationMs = 100L
                                )
                            }
                            
                            if (done) {
                                AgentServiceManager.incrementAdSkipped()
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e("AgenticService", "Error in checkAndSkipAds: ${e.message}")
            }
        }
    }

    companion object {
        private val KEYWORDS = listOf(
            "player", "video", "surface", "texture", "gl", "canvas", "webview", "compose", "image",
            "button", "toolbar", "menu", "controls", "recycler", "list", "viewpager", "seekbar", "slider"
        )
    }
}
