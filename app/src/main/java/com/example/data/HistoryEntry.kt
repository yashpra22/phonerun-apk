package com.example.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "history_entries")
data class HistoryEntry(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val taskPrompt: String,
    val timestamp: Long = System.currentTimeMillis(),
    val status: String, // RUNNING, SUCCESS, FAILED
    val stepsCompleted: Int = 0,
    val finalMessage: String = ""
)
