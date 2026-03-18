package com.m3mo.vault.feature.add

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.m3mo.vault.core.common.IdGenerator
import com.m3mo.vault.core.logging.Logger
import com.m3mo.vault.domain.contract.IMemoryRepository
import com.m3mo.vault.domain.model.MemoryItem
import com.m3mo.vault.domain.model.MemoryType
import com.m3mo.vault.domain.usecase.CreateMemoryUseCase
import com.m3mo.vault.domain.usecase.UpdateMemoryUseCase
import com.m3mo.vault.util.technicalMessage
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * AddMemoryViewModel - Handles creating new memories and editing existing ones.
 *
 * Supports two modes:
 * - Create: editMemoryId == null → calls CreateMemoryUseCase.execute()
 * - Edit: editMemoryId != null → calls UpdateMemoryUseCase.updateContent()
 */
class AddMemoryViewModel(
    private val createMemoryUseCase: CreateMemoryUseCase,
    private val updateMemoryUseCase: UpdateMemoryUseCase,
    private val memoryRepository: IMemoryRepository,
    private val idGenerator: IdGenerator,
    private val logger: Logger
) : ViewModel() {

    companion object {
        private const val TAG = "AddMemoryVM"
    }

    // ========== UI State ==========

    private val _content = MutableStateFlow("")
    val content: StateFlow<String> = _content.asStateFlow()

    private val _selectedType = MutableStateFlow(MemoryType.FACT)
    val selectedType: StateFlow<MemoryType> = _selectedType.asStateFlow()

    private val _isSaving = MutableStateFlow(false)
    val isSaving: StateFlow<Boolean> = _isSaving.asStateFlow()

    private val _saveResult = MutableStateFlow<SaveResult?>(null)
    val saveResult: StateFlow<SaveResult?> = _saveResult.asStateFlow()

    private val _isEditMode = MutableStateFlow(false)
    val isEditMode: StateFlow<Boolean> = _isEditMode.asStateFlow()

    // Internal: existing memory being edited
    private var editingMemory: MemoryItem? = null

    // ========== Public Actions ==========

    fun loadForEdit(memoryId: Long) {
        viewModelScope.launch {
            val result = memoryRepository.getById(memoryId)
            result.onSuccess { memory ->
                editingMemory = memory
                _content.value = memory.content
                _selectedType.value = memory.type
                _isEditMode.value = true
                logger.d(TAG, "Loaded memory $memoryId for editing")
            }.onFailure { error ->
                _saveResult.value = SaveResult.Error("Failed to load: ${error.technicalMessage}")
                logger.e(TAG, "Failed to load memory $memoryId: $error")
            }
        }
    }

    fun updateContent(text: String) {
        _content.value = text
    }

    fun selectType(type: MemoryType) {
        _selectedType.value = type
    }

    fun save() {
        val text = _content.value.trim()
        if (text.isBlank()) {
            _saveResult.value = SaveResult.Error("Content cannot be empty")
            return
        }

        viewModelScope.launch {
            _isSaving.value = true
            try {
                if (_isEditMode.value && editingMemory != null) {
                    saveEdit(text)
                } else {
                    saveNew(text)
                }
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                _saveResult.value = SaveResult.Error("Unexpected error: ${e.message}")
                logger.e(TAG, "Save exception", e)
            } finally {
                _isSaving.value = false
            }
        }
    }

    fun clearSaveResult() {
        _saveResult.value = null
    }

    // ========== Internal ==========

    private suspend fun saveNew(text: String) {
        // CreateMemoryUseCase.execute(content, type, source, tags)
        val result = createMemoryUseCase.execute(
            content = text,
            type = _selectedType.value
        )
        result.onSuccess {
            _saveResult.value = SaveResult.Success
            logger.i(TAG, "Memory created: ${_selectedType.value}")
        }.onFailure { error ->
            _saveResult.value = SaveResult.Error("Save failed: ${error.technicalMessage}")
            logger.e(TAG, "Create failed: $error")
        }
    }

    private suspend fun saveEdit(text: String) {
        val existing = editingMemory ?: return

        // FIX: UpdateMemoryUseCase has no execute(MemoryItem) method.
        // Use updateContent(id, newContent) which is the actual API.
        val result = updateMemoryUseCase.updateContent(
            id = existing.id,
            newContent = text
        )
        result.onSuccess { updated ->
            _saveResult.value = SaveResult.Success
            logger.i(TAG, "Memory updated: ${existing.id}")
        }.onFailure { error ->
            _saveResult.value = SaveResult.Error("Update failed: ${error.technicalMessage}")
            logger.e(TAG, "Update failed: $error")
        }
    }
}

sealed class SaveResult {
    data object Success : SaveResult()
    data class Error(val message: String) : SaveResult()
}