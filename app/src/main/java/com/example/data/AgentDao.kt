package com.example.data

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Dao
interface AgentDao {
    @Query("SELECT * FROM history_entries ORDER BY timestamp DESC")
    fun getAllHistory(): Flow<List<HistoryEntry>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertHistory(entry: HistoryEntry): Long

    @Update
    suspend fun updateHistory(entry: HistoryEntry)

    @Query("DELETE FROM history_entries WHERE id = :id")
    suspend fun deleteHistoryById(id: Int)

    @Query("DELETE FROM history_entries")
    suspend fun clearHistory()

    @Query("SELECT * FROM agent_config WHERE id = 1")
    fun getConfigFlow(): Flow<AgentConfig?>

    @Query("SELECT * FROM agent_config WHERE id = 1")
    suspend fun getConfigDirect(): AgentConfig?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun saveConfig(config: AgentConfig)
}
