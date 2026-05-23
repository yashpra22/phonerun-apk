package com.example.service

import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

data class ScreenNode(
    val id: Int,
    val className: String,
    val text: String?,
    val contentDescription: String?,
    val viewId: String?,
    val isClickable: Boolean,
    val isEditable: Boolean,
    val isScrollable: Boolean,
    val bounds: Rect,
    val originalNode: AccessibilityNodeInfo? = null,
    
    // Window metadata for multi-window support
    val windowId: Int = -1,
    val windowType: Int = -1,
    val isWindowFocused: Boolean = false,
    val isWindowActive: Boolean = false,
    val windowTitle: String? = null,
    val windowLayer: Int = -1,
    
    // Additional view attributes for custom/native containers
    val isEnabled: Boolean = true,
    val isVisibleToUser: Boolean = true,
    val isFocusable: Boolean = false,
    val isSelected: Boolean = false,
    val isChecked: Boolean = false,
    val isLongClickable: Boolean = false,
    val childCount: Int = 0,
    val parentClassName: String? = null,
    val packageName: String? = null,
    val path: String = ""
) {
    fun toActionString(): String {
        val type = className.substringAfterLast('.')
        val label = when {
            !text.isNullOrBlank() -> "text=\"$text\""
            !contentDescription.isNullOrBlank() -> "desc=\"$contentDescription\""
            !viewId.isNullOrBlank() -> "id=\"${viewId.substringAfterLast("/")}\""
            else -> "element"
        }
        val actions = mutableListOf<String>()
        if (isClickable) actions.add("clickable")
        if (isEditable) actions.add("editable")
        if (isScrollable) actions.add("scrollable")
        if (isLongClickable) actions.add("long_clickable")
        val actionStr = if (actions.isNotEmpty()) " (${actions.joinToString()})" else ""
        
        val winTypeStr = when (windowType) {
            1 -> "APP"
            2 -> "IME"
            3 -> "SYSTEM"
            4 -> "OVERLAY"
            else -> "UNKNOWN"
        }
        val winInfo = if (windowId != -1) " [WinId: $windowId, Type: $winTypeStr, Title: \"${windowTitle ?: ""}\", Active: $isWindowActive, Focused: $isWindowFocused]" else ""
        val boundsStr = " bounds=[${bounds.left},${bounds.top},${bounds.right},${bounds.bottom}]"
        
        val extraAttrs = mutableListOf<String>()
        if (isSelected) extraAttrs.add("selected")
        if (isChecked) extraAttrs.add("checked")
        if (!isEnabled) extraAttrs.add("disabled")
        val attrStr = if (extraAttrs.isNotEmpty()) " attrs=${extraAttrs.toString()}" else ""
        
        val pkgStr = if (!packageName.isNullOrBlank()) " pkg=\"$packageName\"" else ""
        return "[$id] $type: $label$actionStr$winInfo$pkgStr$attrStr$boundsStr"
    }
}

object AgentServiceManager {
    @Volatile var preferPhysicalGestures: Boolean = true

    private val _isServiceActive = MutableStateFlow(false)
    val isServiceActive: StateFlow<Boolean> = _isServiceActive

    private val _detectedNodes = MutableStateFlow<List<ScreenNode>>(emptyList())
    val detectedNodes: StateFlow<List<ScreenNode>> = _detectedNodes

    private val _adSkippedCount = MutableStateFlow(0)
    val adSkippedCount: StateFlow<Int> = _adSkippedCount

    fun incrementAdSkipped() {
        _adSkippedCount.value = _adSkippedCount.value + 1
    }

    private var activeServiceInstance: AgenticAccessibilityService? = null

    fun registerService(service: AgenticAccessibilityService) {
        activeServiceInstance = service
        _isServiceActive.value = true
    }

    fun unregisterService() {
        activeServiceInstance = null
        _isServiceActive.value = false
        _detectedNodes.value = emptyList()
    }

    fun updateScreenNodes(nodes: List<ScreenNode>) {
        _detectedNodes.value = nodes
    }

    suspend fun captureCurrentScreen(): List<ScreenNode> {
        val service = activeServiceInstance ?: return emptyList()
        return service.refreshScreenHierarchy()
    }

    suspend fun clickNode(nodeId: Int): Boolean {
        val service = activeServiceInstance ?: return false
        return service.clickNode(nodeId)
    }

    suspend fun longClickNode(nodeId: Int): Boolean {
        val service = activeServiceInstance ?: return false
        return service.longClickNode(nodeId)
    }

    suspend fun typeText(nodeId: Int, text: String): Boolean {
        val service = activeServiceInstance ?: return false
        return service.typeText(nodeId, text)
    }

    suspend fun performBack(): Boolean {
        val service = activeServiceInstance ?: return false
        return service.performGlobalBack()
    }

    suspend fun performHome(): Boolean {
        val service = activeServiceInstance ?: return false
        return service.performGlobalHome()
    }

    suspend fun scrollNode(nodeId: Int, directionForward: Boolean): Boolean {
        val service = activeServiceInstance ?: return false
        return service.scrollNode(nodeId, directionForward)
    }

    suspend fun clickCoordinates(pctX: Float, pctY: Float, durationMs: Long = 80L): Boolean {
        val service = activeServiceInstance ?: return false
        return service.clickCoordinates(pctX, pctY, durationMs)
    }

    suspend fun longClickCoordinates(pctX: Float, pctY: Float, durationMs: Long = 750L): Boolean {
        val service = activeServiceInstance ?: return false
        return service.longClickCoordinates(pctX, pctY, durationMs)
    }

    suspend fun swipe(pctStartX: Float, pctStartY: Float, pctEndX: Float, pctEndY: Float, durationMs: Long = 450L): Boolean {
        val service = activeServiceInstance ?: return false
        return service.swipe(pctStartX, pctStartY, pctEndX, pctEndY, durationMs)
    }

    suspend fun dumpCurrentWindows(): String {
        val service = activeServiceInstance ?: return "Accessibility Service is offline"
        return service.dumpWindows()
    }
}
