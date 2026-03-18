package com.m3mo.vault.util

/**
 * UiState - Generic wrapper for UI state management.
 *
 * Replaces boolean flags like isLoading/isError with a single sealed class.
 *
 * Usage:
 * ```kotlin
 * val uiState: StateFlow<UiState<List<MemoryItem>>> = ...
 *
 * when (val state = uiState.collectAsState().value) {
 *     is UiState.Loading -> LoadingIndicator()
 *     is UiState.Success -> MemoryList(state.data)
 *     is UiState.Error -> ErrorMessage(state.message)
 *     is UiState.Empty -> EmptyState()
 * }
 * ```
 */
sealed class UiState<out T> {
    /** Initial loading state */
    data object Loading : UiState<Nothing>()

    /** Data loaded successfully */
    data class Success<T>(val data: T) : UiState<T>()

    /** Error occurred */
    data class Error(val message: String, val cause: Throwable? = null) : UiState<Nothing>()

    /** Data loaded but empty */
    data object Empty : UiState<Nothing>()

    val isLoading: Boolean get() = this is Loading
    val isSuccess: Boolean get() = this is Success
    val isError: Boolean get() = this is Error

    /** Get data or null */
    fun dataOrNull(): T? = (this as? Success)?.data
}
