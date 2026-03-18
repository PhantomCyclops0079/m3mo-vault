package com.m3mo.vault.feature.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.m3mo.vault.core.event.EventBus
import com.m3mo.vault.core.logging.Logger
import com.m3mo.vault.domain.contract.IMemoryRepository
import com.m3mo.vault.domain.model.MemoryItem
import com.m3mo.vault.domain.model.MemoryType
import com.m3mo.vault.domain.model.ProcessingState
import com.m3mo.vault.domain.usecase.DeleteMemoryUseCase
import com.m3mo.vault.util.UiState
import com.m3mo.vault.util.dataOrNull
import com.m3mo.vault.util.technicalMessage
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class HomeViewModel(
    private val memoryRepository: IMemoryRepository,
    private val deleteMemoryUseCase: DeleteMemoryUseCase,
    private val eventBus: EventBus,
    private val logger: Logger
) : ViewModel() {

    companion object { private const val TAG = "HomeVM" }

    private val _uiState = MutableStateFlow<UiState<List<MemoryItem>>>(UiState.Loading)
    val uiState: StateFlow<UiState<List<MemoryItem>>> = _uiState.asStateFlow()

    private val _selectedFilter = MutableStateFlow<MemoryType?>(null)
    val selectedFilter: StateFlow<MemoryType?> = _selectedFilter.asStateFlow()

    private val _stats = MutableStateFlow(HomeStats())
    val stats: StateFlow<HomeStats> = _stats.asStateFlow()

    private val _deleteResult = MutableStateFlow<DeleteResult?>(null)
    val deleteResult: StateFlow<DeleteResult?> = _deleteResult.asStateFlow()

    private var allMemories: List<MemoryItem> = emptyList()

    init { loadMemories() }

    fun loadMemories() {
        viewModelScope.launch {
            _uiState.value = UiState.Loading
            try {
                val result = memoryRepository.getByState(ProcessingState.STORED)
                result.onSuccess { memories ->
                    val optimized = memoryRepository.getByState(ProcessingState.OPTIMIZED).dataOrNull() ?: emptyList()
                    val newMemories = memoryRepository.getByState(ProcessingState.NEW).dataOrNull() ?: emptyList()

                    allMemories = (memories + optimized + newMemories).sortedByDescending { it.weight }
                    updateStats()
                    applyFilter()
                }.onFailure { error ->
                    _uiState.value = UiState.Error("Failed to load: ${error.technicalMessage}")
                }
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                _uiState.value = UiState.Error("System error: ${e.message}")
            }
        }
    }

    fun setFilter(type: MemoryType?) {
        _selectedFilter.value = type
        applyFilter()
    }

    fun deleteMemory(id: Long) {
        viewModelScope.launch {
            val result = deleteMemoryUseCase.execute(id)
            result.onSuccess {
                _deleteResult.value = DeleteResult.Success
                loadMemories()
            }.onFailure { error ->
                _deleteResult.value = DeleteResult.Error(error.technicalMessage)
            }
        }
    }

    fun clearDeleteResult() { _deleteResult.value = null }

    private fun applyFilter() {
        val filter = _selectedFilter.value
        val filtered = if (filter == null) allMemories else allMemories.filter { it.type == filter }
        _uiState.value = if (filtered.isEmpty()) UiState.Empty else UiState.Success(filtered)
    }

    private fun updateStats() {
        _stats.value = HomeStats(
            total = allMemories.size,
            facts = allMemories.count { it.type == MemoryType.FACT },
            episodes = allMemories.count { it.type == MemoryType.EPISODE },
            tasks = allMemories.count { it.type == MemoryType.TASK },
            pinned = allMemories.count { it.isPinned },
            avgWeight = if (allMemories.isEmpty()) 0.0 else allMemories.map { it.weight }.average()
        )
    }
}

data class HomeStats(
    val total: Int = 0, val facts: Int = 0, val episodes: Int = 0,
    val tasks: Int = 0, val pinned: Int = 0, val avgWeight: Double = 0.0
)

sealed class DeleteResult {
    data object Success : DeleteResult()
    data class Error(val message: String) : DeleteResult()
}