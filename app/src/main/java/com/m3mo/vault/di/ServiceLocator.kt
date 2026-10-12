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

    lateinit var clock: Clock; private set
    lateinit var logger: Logger; private set
    lateinit var eventBus: EventBus; private set
    lateinit var idGenerator: IdGenerator; private set

    lateinit var memoryRepository: IMemoryRepository; private set
    lateinit var settingsRepository: SettingsRepositoryImpl; private set
    lateinit var memoryCache: MemoryCache; private set

    lateinit var decayStrategy: SmartDecayStrategy; private set
    lateinit var semanticMatcher: TfIdfSemanticMatcher; private set
    lateinit var resourceGovernor: ResourceGovernor; private set
    lateinit var pipeline: OptimizationPipeline; private set

    lateinit var searchEngine: SearchEngine; private set

    lateinit var reviewGenerator: ReviewGenerator; private set
    lateinit var memoryBooster: MemoryBooster; private set
    lateinit var usageTracker: UsageTracker; private set
    lateinit var consolidationSuggester: ConsolidationSuggester; private set

    lateinit var createMemoryUseCase: CreateMemoryUseCase; private set
    lateinit var deleteMemoryUseCase: DeleteMemoryUseCase; private set
    lateinit var updateMemoryUseCase: UpdateMemoryUseCase; private set
    lateinit var searchMemoriesUseCase: SearchMemoriesUseCase; private set
    lateinit var runDecayUseCase: RunDecayUseCase; private set

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

            clock = SystemClock()
            idGenerator = SnowflakeIdGenerator()
            logger = ProductionLogger(isDebug = true)
            eventBus = InProcessEventBus(
                bufferCapacity = 64,
                logger = logger
            )

            logger.i(TAG, "Initializing ServiceLocator...")

            val database = AppDatabase.getInstance(appContext)
            val memoryDao = database.memoryDao()
            val settingsDao = database.settingsDao()

            memoryCache = MemoryCache(CachePolicy.STANDARD, clock)
            val jsonCodec = com.m3mo.vault.core.json.DefaultJsonCodec.getInstance()
            settingsRepository = SettingsRepositoryImpl(settingsDao, jsonCodec, clock)
            memoryRepository = MemoryRepositoryImpl(memoryDao, memoryCache, clock, logger)

            val normalizer = DefaultNormalizer()
            val vectorizer = TfIdfVectorizer()

            semanticMatcher = TfIdfSemanticMatcher(vectorizer, TfIdfMatcherConfig.default(), logger)

            decayStrategy = SmartDecayStrategy(DecayConfig.default(), clock, logger)

            val deviceProfile = DeviceProfile.detect(appContext)
            resourceGovernor = ResourceGovernor(deviceProfile, logger)

            val stage1 = Stage1Executor(normalizer, logger)

            val stage2 = Stage2Executor(semanticMatcher, logger)

            val stage3 = Stage3Executor(decayStrategy, logger)

            pipeline = OptimizationPipeline(
                stage1,
                stage2,
                stage3,
                memoryRepository,
                logger
            )

            val keywordMatcher = KeywordMatcher(memoryRepository)

            searchEngine = SearchEngine(memoryRepository, semanticMatcher, keywordMatcher, clock, logger)

            reviewGenerator = ReviewGenerator(memoryRepository, resourceGovernor, clock, logger)
            memoryBooster = MemoryBooster(memoryRepository, decayStrategy, clock, logger)
            usageTracker = UsageTracker(clock, logger)
            consolidationSuggester = ConsolidationSuggester(memoryRepository, semanticMatcher, resourceGovernor, clock, logger)

            createMemoryUseCase = CreateMemoryUseCase(memoryRepository, eventBus, idGenerator, clock)

            deleteMemoryUseCase = DeleteMemoryUseCase(memoryRepository, eventBus)

            updateMemoryUseCase = UpdateMemoryUseCase(memoryRepository, eventBus, clock)

            searchMemoriesUseCase = SearchMemoriesUseCase(memoryRepository, semanticMatcher, eventBus, clock)

            runDecayUseCase = RunDecayUseCase(memoryRepository, decayStrategy, resourceGovernor, eventBus, clock)

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