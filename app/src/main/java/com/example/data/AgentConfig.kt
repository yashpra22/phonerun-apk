package com.example.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "agent_config")
data class AgentConfig(
    @PrimaryKey val id: Int = 1,
    val ollamaHost: String = "http://127.0.0.1:11434", // Default for USB reverse-adb tethering
    val ollamaModel: String = "llama3.2",
    val provider: String = "OLLAMA",  // OLLAMA or GEMINI
    val maxSteps: Int = 15,
    val stepDelayMs: Long = 1600L,
    val preferPhysicalGestures: Boolean = true,
    val autoSkipAds: Boolean = true,
    val filterAdNodes: Boolean = true,
    val videoThemeBannerColor: String = "Cyan"
)
