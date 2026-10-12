package com.m3mo.vault.worker

import android.content.Context
import androidx.work.ListenableWorker
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import com.m3mo.vault.behavior.ReviewGenerator
import com.m3mo.vault.core.common.Clock
import com.m3mo.vault.core.event.EventBus
import com.m3mo.vault.core.logging.Logger
import com.m3mo.vault.domain.contract.IMemoryRepository
import com.m3mo.vault.domain.contract.IResourceGovernor
import com.m3mo.vault.domain.usecase.RunDecayUseCase

/**
 * M3moWorkerFactory - Custom WorkerFactory for Dependency Injection
 *
 * Bridges the DI system with WorkManager by providing dependencies
 * to each worker. When Hilt is added in Batch 8, this can be
 * replaced with @HiltWorker + @AssistedInject.
 *
 * ## Setup (in Application class)
 *
 * ```kotlin
 * val config = Configuration.Builder()
 *     .setWorkerFactory(M3moWorkerFactory(...))
 *     .build()
 * WorkManager.initialize(this, config)
 * ```
 */
class M3moWorkerFactory(
    private val runDecayUseCase: RunDecayUseCase,
    private val resourceGovernor: IResourceGovernor,
    private val repository: IMemoryRepository,
    private val reviewGenerator: ReviewGenerator,
    private val eventBus: EventBus,
    private val clock: Clock,
    private val logger: Logger
) : WorkerFactory() {

    override fun createWorker(
        appContext: Context,
        workerClassName: String,
        workerParameters: WorkerParameters
    ): ListenableWorker? {
        return when (workerClassName) {
            SmartDecayWorker::class.java.name -> SmartDecayWorker(
                context = appContext,
                params = workerParameters,
                runDecayUseCase = runDecayUseCase,
                resourceGovernor = resourceGovernor,
                clock = clock,
                logger = logger
            )
            ReviewWorker::class.java.name -> ReviewWorker(
                context = appContext,
                params = workerParameters,
                reviewGenerator = reviewGenerator,
                eventBus = eventBus,
                clock = clock,
                logger = logger
            )
            CleanupWorker::class.java.name -> CleanupWorker(
                context = appContext,
                params = workerParameters,
                repository = repository,
                eventBus = eventBus,
                clock = clock,
                logger = logger
            )
            else -> null
        }
    }
}
