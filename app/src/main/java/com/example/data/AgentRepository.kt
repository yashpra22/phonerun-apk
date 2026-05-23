package com.example.data

import kotlinx.coroutines.flow.Flow

class AgentRepository(private val agentDao: AgentDao) {
    val allHistory: Flow<List<HistoryEntry>> = agentDao.getAllHistory()
    val configFlow: Flow<AgentConfig?> = agentDao.getConfigFlow()

    suspend fun insertHistory(entry: HistoryEntry): Long = agentDao.insertHistory(entry)

    suspend fun updateHistory(entry: HistoryEntry) = agentDao.updateHistory(entry)

    suspend fun deleteHistory(id: Int) = agentDao.deleteHistoryById(id)

    suspend fun clearHistory() = agentDao.clearHistory()

    suspend fun getConfigDirect(): AgentConfig {
        return agentDao.getConfigDirect() ?: AgentConfig()
    }

    suspend fun saveConfig(config: AgentConfig) = agentDao.saveConfig(config)
}
