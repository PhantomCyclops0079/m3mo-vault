package com.m3mo.vault.util

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider

/**
 * ViewModelFactory - Generic helper for manual DI.
 *
 * Eliminates the need for per-ViewModel Factory classes.
 *
 * Usage in NavGraph:
 * ```kotlin
 * val vm: HomeViewModel = viewModel(
 *     factory = viewModelFactory {
 *         HomeViewModel(ServiceLocator.memoryRepository)
 *     }
 * )
 * ```
 */
@Suppress("UNCHECKED_CAST")
fun <VM : ViewModel> viewModelFactory(initializer: () -> VM): ViewModelProvider.Factory {
    return object : ViewModelProvider.Factory {
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            return initializer() as T
        }
    }
}
