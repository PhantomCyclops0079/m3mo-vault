package com.m3mo.vault.di

import android.annotation.SuppressLint
import android.content.Context
import com.m3mo.vault.behavior.ConsolidationSuggester
import com.m3mo.vault.behavior.MemoryBooster
import com.m3mo.vault.behavior.ReviewGenerator
import com.m3mo.vault.behavior.UsageTracker
import com.m3mo.vault.core.common.Clock
import com.m3mo.vault.core.common.IdGenerator
import com.m3mo.vault.core.common.SnowflakeIdGenerator
import com.m3mo.vault.core.common.SystemClock
import com.m3mo.vault.core.event.EventBus
import com.m3mo.vault.core.event.InProcessEventBus
import com.m3mo.vault.core.event.SubscriberRegistry
import com.m3mo.vault.core.logging.Logger
import com.m3mo.vault.core.logging.ProductionLogger
import com.m3mo.vault.data.cache.CachePolicy
import com.m3mo.vault.data.cache.MemoryCache
import com.m3mo.vault.data.db.AppDatabase
import com.m3mo.vault.data.repository.MemoryRepositoryImpl
import com.m3mo.vault.data.repository.SettingsRepositoryImpl
import com.m3mo.vault.domain.contract.DecayConfig
import com.m3mo.vault.domain.contract.IMemoryRepository
import com.m3mo.vault.domain.usecase.CreateMemoryUseCase
import com.m3mo.vault.domain.usecase.DeleteMemoryUseCase
import com.m3mo.vault.domain.usecase.RunDecayUseCase
import com.m3mo.vault.domain.usecase.SearchMemoriesUseCase
import com.m3mo.vault.domain.usecase.UpdateMemoryUseCase
import com.m3mo.vault.memory.decay.SmartDecayStrategy
import com.m3mo.vault.memory.normalize.DefaultNormalizer
import com.m3mo.vault.memory.pipeline.OptimizationPipeline
import com.m3mo.vault.memory.pipeline.Stage1Executor
import com.m3mo.vault.memory.pipeline.Stage2Executor
import com.m3mo.vault.memory.pipeline.Stage3Executor
import com.m3mo.vault.memory.semantic.TfIdfMatcherConfig
import com.m3mo.vault.memory.semantic.TfIdfSemanticMatcher
import com.m3mo.vault.memory.semantic.TfIdfVectorizer
import com.m3mo.vault.runtime.DeviceProfile
import com.m3mo.vault.runtime.ResourceGovernor
import com.m3mo.vault.search.KeywordMatcher
import com.m3mo.vault.search.SearchEngine
import com.m3mo.vault.subscriber.setupSubscribers
import com.m3mo.vault.worker.M3moWorkerFactory
import com.m3mo.vault.worker.OptimizationScheduler

@SuppressLint("StaticFieldLeak")
object ServiceLocator {

    // ========== Layer 1: Core ==========
    lateinit var clock: Clock; private set
    lateinit var logger: Logger; private set
    lateinit var eventBus: EventBus; private set
    lateinit var idGenerator: IdGenerator; private set

    // ========== Layer 2: Data ==========
    lateinit var memoryRepository: IMemoryRepository; private set
    lateinit var settingsRepository: SettingsRepositoryImpl; private set
    lateinit var memoryCache: MemoryCache; private set

    // ========== Layer 3: Engine ==========
    lateinit var decayStrategy: SmartDecayStrategy; private set
    lateinit var semanticMatcher: TfIdfSemanticMatcher; private set
    lateinit var resourceGovernor: ResourceGovernor; private set
    lateinit var pipeline: OptimizationPipeline; private set

    // ========== Layer 4: Search ==========
    lateinit var searchEngine: SearchEngine; private set

    // ========== Layer 5: Behavior ==========
    lateinit var reviewGenerator: ReviewGenerator; private set
    lateinit var memoryBooster: MemoryBooster; private set
    lateinit var usageTracker: UsageTracker; private set
    lateinit var consolidationSuggester: ConsolidationSuggester; private set

    // ========== Layer 6: UseCases ==========
    lateinit var createMemoryUseCase: CreateMemoryUseCase; private set
    lateinit var deleteMemoryUseCase: DeleteMemoryUseCase; private set
    lateinit var updateMemoryUseCase: UpdateMemoryUseCase; private set
    lateinit var searchMemoriesUseCase: SearchMemoriesUseCase; private set
    lateinit var runDecayUseCase: RunDecayUseCase; private set

    // ========== Layer 7: Workers ==========
    lateinit var workerFactory: M3moWorkerFactory; private set
    lateinit var optimizationScheduler: OptimizationScheduler; private set

    private var subscriberRegistry: SubscriberRegistry? = null

    @Volatile
    private var isInitialized = false
    private const val TAG = "ServiceLocator"

    fun init(context: Context) {
        if (isInitialized) return
        synchronized(this) {
            if (isInitialized) return
            val appContext = context.applicationContext

            // ===== Layer 1: Core =====
            clock = SystemClock()
            idGenerator = SnowflakeIdGenerator()
            logger = ProductionLogger(isDebug = true)
            // InProcessEventBus(bufferCapacity, replayCount, onBufferOverflow, logger)
            // replayCount and onBufferOverflow have defaults
            eventBus = InProcessEventBus(
                bufferCapacity = 64,
                logger = logger
            )

            logger.i(TAG, "Initializing ServiceLocator...")

            // ===== Layer 2: Data =====
            val database = AppDatabase.getInstance(appContext)
            val memoryDao = database.memoryDao()
            val settingsDao = database.settingsDao()

            // MemoryCache(policy, clock)
            memoryCache = MemoryCache(CachePolicy.STANDARD, clock)
            val jsonCodec = com.m3mo.vault.core.json.DefaultJsonCodec.getInstance()
            settingsRepository = SettingsRepositoryImpl(settingsDao, jsonCodec, clock)
            memoryRepository = MemoryRepositoryImpl(memoryDao, memoryCache, clock, logger)

            // ===== Layer 3: Engine =====
            val normalizer = DefaultNormalizer()
            val vectorizer = TfIdfVectorizer()

            // FIX: TfIdfSemanticMatcher(vectorizer, config, logger) — was missing config
            semanticMatcher = TfIdfSemanticMatcher(vectorizer, TfIdfMatcherConfig.default(), logger)

            // FIX: SmartDecayStrategy(config, clock, logger) — was missing config and logger
            decayStrategy = SmartDecayStrategy(DecayConfig.default(), clock, logger)

            val deviceProfile = DeviceProfile.detect(appContext)
            resourceGovernor = ResourceGovernor(deviceProfile, logger)

            // Stage1Executor(normalizer, logger) — 2 params only
            val stage1 = Stage1Executor(normalizer, logger)

            // FIX: Stage2Executor(semanticMatcher, logger) — no resourceGovernor
            val stage2 = Stage2Executor(semanticMatcher, logger)

            // FIX: Stage3Executor(decayStrategy, logger) — no clock
            val stage3 = Stage3Executor(decayStrategy, logger)

            // FIX: OptimizationPipeline(s1, s2, s3, repository, logger) — was passing resourceGovernor instead of repository
            pipeline = OptimizationPipeline(
                stage1,
                stage2,
                stage3,
                memoryRepository,
                logger
            )

            // ===== Layer 4: Search =====
            // FIX: KeywordMatcher(repository, normalizer) — no logger param; normalizer has default
            val keywordMatcher = KeywordMatcher(memoryRepository)

            // FIX: SearchEngine(repository, semanticMatcher, keywordMatcher, clock, logger) — was missing clock
            searchEngine = SearchEngine(memoryRepository, semanticMatcher, keywordMatcher, clock, logger)

            // ===== Layer 5: Behavior =====
            // ReviewGenerator(repository, governor, clock, logger)
            reviewGenerator = ReviewGenerator(memoryRepository, resourceGovernor, clock, logger)
            // MemoryBooster(repository, decayStrategy: SmartDecayStrategy, clock, logger)
            memoryBooster = MemoryBooster(memoryRepository, decayStrategy, clock, logger)
            // UsageTracker(clock, logger)
            usageTracker = UsageTracker(clock, logger)
            // ConsolidationSuggester(repository, semanticMatcher, governor, clock, logger)
            consolidationSuggester = ConsolidationSuggester(memoryRepository, semanticMatcher, resourceGovernor, clock, logger)

            // ===== Layer 6: UseCases =====
            // CreateMemoryUseCase(repository, eventBus, idGenerator, clock)
            createMemoryUseCase = CreateMemoryUseCase(memoryRepository, eventBus, idGenerator, clock)

            // FIX: DeleteMemoryUseCase(repository, eventBus) — only 2 params, no clock
            deleteMemoryUseCase = DeleteMemoryUseCase(memoryRepository, eventBus)

            // UpdateMemoryUseCase(repository, eventBus, clock)
            updateMemoryUseCase = UpdateMemoryUseCase(memoryRepository, eventBus, clock)

            // FIX: SearchMemoriesUseCase(repository, semanticMatcher, eventBus, clock) — NOT SearchEngine
            searchMemoriesUseCase = SearchMemoriesUseCase(memoryRepository, semanticMatcher, eventBus, clock)

            // RunDecayUseCase(repository, decayStrategy, resourceGovernor, eventBus, clock)
            runDecayUseCase = RunDecayUseCase(memoryRepository, decayStrategy, resourceGovernor, eventBus, clock)

            // ===== Layer 7: Workers =====
            workerFactory = M3moWorkerFactory(
                runDecayUseCase = runDecayUseCase,
                resourceGovernor = resourceGovernor,
                repository = memoryRepository,
                reviewGenerator = reviewGenerator,
                eventBus = eventBus,
                clock = clock,
                logger = logger
            )
            optimizationScheduler = OptimizationScheduler(appContext, logger)

            // FIX: setupSubscribers param name is "cache", not "memoryCache"
            subscriberRegistry = setupSubscribers(
                eventBus = eventBus,
                logger = logger,
                cache = memoryCache,
                usageTracker = usageTracker,
                memoryBooster = memoryBooster
            )

            isInitialized = true
            logger.i(TAG, "ServiceLocator initialized successfully!")
        }
    }

    fun startSubscribers() {
        try {
            subscriberRegistry?.startAll()
            logger.i(TAG, "Subscribers active")
        } catch (e: Exception) {
            logger.e(TAG, "Failed to start subscribers", e)
        }
    }

    fun scheduleWorkers() {
        optimizationScheduler.scheduleAll()
    }

    fun isReady(): Boolean = isInitialized
}