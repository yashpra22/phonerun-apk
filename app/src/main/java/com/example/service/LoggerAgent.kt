package com.example.service

import android.content.Context
import android.os.Environment
import android.util.Log
import org.json.JSONObject
import java.io.File
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.*

object LoggerAgent {
    private const val TAG = "LoggerAgent"
    private var currentSessionId: String = ""

    fun startNewSession(): String {
        currentSessionId = "session_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
        return currentSessionId
    }

    fun getCurrentSessionId(): String {
        if (currentSessionId.isEmpty()) {
            startNewSession()
        }
        return currentSessionId
    }

    fun getLogDir(context: Context, subFolder: String = ""): File {
        val publicDir = File(Environment.getExternalStorageDirectory(), "PhoneAgentLogs")
        var dir = File(publicDir, subFolder)
        
        var success = false
        try {
            if (!publicDir.exists()) {
                publicDir.mkdirs()
            }
            if (!dir.exists()) {
                dir.mkdirs()
            }
            val tempFile = File(dir, ".temp")
            tempFile.createNewFile()
            if (tempFile.exists()) {
                tempFile.delete()
                success = true
            }
        } catch (e: Exception) {
            success = false
        }

        if (!success) {
            val fallbackPublic = File(context.getExternalFilesDir(null), "PhoneAgentLogs")
            dir = File(fallbackPublic, subFolder)
            if (!dir.exists()) {
                dir.mkdirs()
            }
        }
        return dir
    }

    fun logEvent(
        context: Context,
        eventType: String,
        stepId: Int,
        packageName: String = "",
        activityName: String = "",
        userRequest: String = "",
        modelName: String = "",
        modelSize: String = "20B",
        promptVersion: String = "1.0",
        rawAiResponse: String = "",
        parsedActionJson: String = "",
        selectedNodeSummary: String = "",
        coordinates: String = "",
        windowSummary: String = "",
        nodeCount: Int = 0,
        screenHashBefore: String = "",
        screenHashAfter: String = "",
        executionResult: String = "",
        errorMessage: String = ""
    ) {
        val sessionId = getCurrentSessionId()
        val timestamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.getDefault()).format(Date())
        
        val eventObj = JSONObject().apply {
            put("timestamp", timestamp)
            put("sessionId", sessionId)
            put("stepId", stepId)
            put("packageName", packageName)
            put("activityName", activityName)
            put("eventType", eventType)
            put("userRequest", userRequest)
            put("modelName", modelName)
            put("modelSize", modelSize)
            put("promptVersion", promptVersion)
            put("rawAiResponse", rawAiResponse)
            put("parsedActionJson", parsedActionJson)
            put("selectedNode", selectedNodeSummary)
            put("coordinates", coordinates)
            put("windowSummary", windowSummary)
            put("nodeCount", nodeCount)
            put("screenHashBefore", screenHashBefore)
            put("screenHashAfter", screenHashAfter)
            put("executionResult", executionResult)
            put("errorMessage", errorMessage)
        }

        val jsonLine = eventObj.toString()
        Log.i(TAG, "[$eventType] $jsonLine")

        val subFolder = when (eventType) {
            "SESSION_START", "SESSION_END" -> "sessions"
            "SCREEN_BEFORE", "SCREEN_AFTER" -> "screens"
            "ACTION_START", "ACTION_RESULT", "GESTURE_EXECUTED", "NODE_SELECTED", "FALLBACK_USED" -> "actions"
            "ERROR" -> "errors"
            else -> "actions"
        }

        try {
            val dir = getLogDir(context, subFolder)
            val sessionDate = sessionId.removePrefix("session_").substringBefore("_")
            val logFile = File(dir, "session_${sessionDate}.jsonl")
            FileWriter(logFile, true).use { writer ->
                writer.append(jsonLine).append("\n")
            }

            val rootDir = getLogDir(context, "")
            val latestFile = File(rootDir, "latest_session.jsonl")
            
            if (eventType == "SESSION_START") {
                if (latestFile.exists()) latestFile.delete()
            }
            FileWriter(latestFile, true).use { writer ->
                writer.append(jsonLine).append("\n")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to write event log: ${e.message}")
        }
    }

    fun exportLogsToPublicStorage(context: Context): String {
        val rootDir = context.getExternalFilesDir(null) ?: return "External storage inaccessible"
        val privateLogsDir = File(rootDir, "PhoneAgentLogs")
        val publicLogsDir = File(Environment.getExternalStorageDirectory(), "PhoneAgentLogs")
        
        try {
            if (!publicLogsDir.exists()) {
                publicLogsDir.mkdirs()
            }
            if (privateLogsDir.exists()) {
                copyDirRecursively(privateLogsDir, publicLogsDir)
                return "Logs successfully exported to ${publicLogsDir.absolutePath}"
            } else {
                // If private logs empty, let's at least make the folders visible in public storage
                File(publicLogsDir, "sessions").mkdirs()
                File(publicLogsDir, "screens").mkdirs()
                File(publicLogsDir, "actions").mkdirs()
                File(publicLogsDir, "errors").mkdirs()
                return "Created empty trace structure at ${publicLogsDir.absolutePath}"
            }
        } catch (e: Exception) {
            Log.e(TAG, "Export failed: ${e.message}")
            return "Export failed: ${e.message}. Ensure Storage Permission is granted."
        }
    }

    fun exportLogs(context: Context): Boolean {
        return try {
            val privateDir = File(context.getExternalFilesDir(null), "PhoneAgentLogs")
            val publicDir = File(Environment.getExternalStorageDirectory(), "PhoneAgentLogs")
            if (privateDir.exists()) {
                if (!publicDir.exists()) {
                    publicDir.mkdirs()
                }
                copyDirRecursively(privateDir, publicDir)
            }
            android.media.MediaScannerConnection.scanFile(
                context,
                arrayOf(publicDir.absolutePath),
                null,
                null
            )
            true
        } catch (e: Exception) {
            Log.e(TAG, "Export failed: ${e.message}")
            false
        }
    }

    fun clearLogs(context: Context): Boolean {
        return try {
            val publicDir = File(Environment.getExternalStorageDirectory(), "PhoneAgentLogs")
            val privateDir = File(context.getExternalFilesDir(null), "PhoneAgentLogs")
            if (publicDir.exists()) {
                publicDir.deleteRecursively()
            }
            if (privateDir.exists()) {
                privateDir.deleteRecursively()
            }
            true
        } catch (e: Exception) {
            Log.e(TAG, "Clear logs failed: ${e.message}")
            false
        }
    }

    private fun copyDirRecursively(src: File, dest: File) {
        if (src.isDirectory) {
            if (!dest.exists()) dest.mkdirs()
            src.list()?.forEach { child ->
                copyDirRecursively(File(src, child), File(dest, child))
            }
        } else {
            src.copyTo(dest, overwrite = true)
        }
    }
}
