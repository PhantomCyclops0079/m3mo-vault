# M3mo Vault

[![Kotlin](https://img.shields.io/badge/Kotlin-2.0-blue.svg)](https://kotlinlang.org)
[![Android](https://img.shields.io/badge/Min%20SDK-26-green.svg)](https://developer.android.com)
[![Status](https://img.shields.io/badge/Status-MVP-purple.svg)]()
[![Patent](https://img.shields.io/badge/Patent-Pending-orange.svg)]()

An offline-first Android application for personal memory management. Records facts, events, and tasks, 
then uses a multi-factor decay algorithm to automatically manage what stays relevant and what fades — entirely on-device, no cloud, no network.

**Patent Pending:** US Provisional Application #63/942,399 — *"Offline Memory Lifecycle Management System and Method for Mobile AI Software"* (Filed Dec 2025)

---

## What It Does

You enter short text memories (facts, episodes, tasks). 

The app stores them locally in SQLite and runs a daily background process that adjusts each memory's weight based on 7 factors: how recently you accessed it, how often, whether you rated it important, whether you pinned it, how old it is, how large it is, and a base decay rate. High-value memories retain weight. Neglected memories decay toward archival.

The algorithm is called **SmartDecay**. It's deterministic, explainable (every weight change has a traceable factor breakdown), and runs in under 9ms for 10,000+ memories.

## What It Doesn't Do (Yet)

- No LLM / AI model integration (architecture supports it via pluggable interfaces, but current version uses TF-IDF for semantic matching and rule-based decay)
- No voice input
- No cloud sync
- English only

---

## Architecture

```
UI Layer (Jetpack Compose + Material 3)
    │ viewModelFactory { }
    ▼
ServiceLocator (7-Layer Manual DI)
    │
    ├── Domain (UseCases, Models, Contracts, Events)
    │
    ├── Data (Room + FTS4, L1 LRU Cache, Repository)
    │
    ├── Memory Engine
    │   ├── SmartDecay (7-Factor Rule-Based Decay)
    │   ├── TF-IDF Semantic Matcher
    │   └── 3-Stage Optimization Pipeline
    │
    ├── Search (FTS4 Keyword + TF-IDF Semantic, Hybrid Scoring)
    │
    ├── Behavior (Weekly Review, Usage Tracking, Memory Boosting)
    │
    └── Workers (WorkManager: Daily Decay, Weekly Review, Cleanup)
```

**Why ServiceLocator instead of Hilt:** Faster builds (no annotation processing), errors traceable to exact line numbers, 
and the file itself serves as the system's architecture map. One file to understand the entire dependency graph.

## SmartDecay Algorithm

Each daily cycle, every eligible memory gets a weight adjustment based on:

| # | Factor           | Effect                                         |
|---|------------------|------------------------------------------------|
| 1 | Base Decay       | -1 weight per cycle (configurable)             |
| 2 | Grace Period     | New memories (< 7 days) skip decay entirely    |
| 3 | Recency Penalty  | Not accessed > 14 days → up to 2x faster decay |
| 4 | Frequency Bonus  | Accessed > 5 times → up to 2 weight protection |
| 5 | Confidence Bonus | User rating 4-5 → up to 2 weight protection    |
| 6 | Size Penalty     | Content > 1000 chars → up to 1 extra decay     |
| 7 | Pin Protection   | Pinned → zero decay, always                    |

Weight range: 1 (minimum, candidate for archival) to 16 (maximum).

### Validated Performance

Tested with ablation study (SmartDecay vs. naive linear decay) on both synthetic and randomized datasets:

| Test                    | Dataset                                              | Result                                                 |
|------------------------ |------------------------------------------------------|-----------------------------------------------------   |
| Ablation (synthetic)    | 3,500 memories, 7 profiles, 10 cycles                | **12x** differentiation (SmartDecay) vs **2x** (naive) |
| Ablation (randomized)   | 3,000 memories, uniform random attributes, 10 cycles | **11.6x** differentiation vs **4.7x** (naive)          |
| Scale                   | 10,500 memories                                      | 8.16ms total, **0.78µs/memory**                        |
| Factor isolation        | 6 active factors tested independently                | All 6 produce measurably different outcomes            |
| 30-day progression      | 5 profiles tracked over 30 daily cycles              | 5 distinct decay curves from same weight range         |

SmartDecay preserved 50% of randomized memories' weights unchanged (vs. 5% for naive), while still aggressively decaying low-value entries. Full test code in `app/src/test/java/com/m3mo/vault/test/patent/`.

## Search

Hybrid search combining SQLite FTS4 (keyword) and TF-IDF (semantic similarity):

| Dataset      | FTS4 Latency | Full-scan Latency | Improvement |
|--------------|--------------|-------------------|-------------|
| 1K memories  | 5-10ms       | 50-100ms          |    ~10x     |
| 10K memories | 10-20ms      | 500-2000ms        |    ~100x    |

Scoring: `HybridScore = (Keyword × 0.4) + (Semantic × 0.6) + bonuses (recency, weight, pin)`

## Tech Stack

| Layer       | Stack                                      |
|-------------|--------------------------------------------|
| Language    | Kotlin                                     |
| UI          | Jetpack Compose, Material Design 3         |
| Database    | Room (SQLite) + FTS4 full-text search      |
| Concurrency | Kotlin Coroutines, StateFlow               |
| Background  | Android WorkManager                        |
| DI          | Manual ServiceLocator (7-layer)            |
| Locking     | Optimistic locking (version field)         |
| IDs         | Snowflake (offline-friendly, time-ordered) |
| Cache       | LRU LinkedHashMap with TTL                 |
| Testing     | JUnit + FakeClock + NoOpLogger             |

## Project Structure

```
app/src/main/java/com/m3mo/vault/
├── core/           # AppResult, Clock, EventBus, Logger, IdGenerator
├── domain/         # MemoryItem, UseCases, Contracts, Events
├── data/           # Room DB, DAO, Cache, Repository
├── memory/         # SmartDecay, TF-IDF, Optimization Pipeline
├── search/         # SearchEngine, KeywordMatcher (FTS4)
├── runtime/        # DeviceProfile, ResourceGovernor
├── behavior/       # ReviewGenerator, MemoryBooster, UsageTracker
├── worker/         # SmartDecayWorker, ReviewWorker, CleanupWorker
├── subscriber/     # Cache, Search, Boost, Metrics subscribers
├── di/             # ServiceLocator
├── feature/        # Home, Add, Search, Review, Settings (MVVM)
└── ui/             # Theme, Navigation, Shared Components

app/src/test/java/com/m3mo/vault/test/patent/
├── SmartDecayPatentTest.kt       # Ablation, scale, factor isolation tests
├── NaiveDecayStrategy.kt         # Baseline for comparison
└── PatentTestDataGenerator.kt    # Synthetic + randomized data generation
```

## Implementation & Running

```bash
# Clone
git clone https://github.com/PhantomCyclops0079/m3mo-vault.git

# Open in Android Studio (Ladybug or newer)
# Sync Gradle, then Run on device/emulator (API 26+)

# Run patent validation tests (no device needed)
./gradlew :app:testDebugUnitTest --tests "com.m3mo.vault.test.patent.SmartDecayPatentTest"
```

## Current Status

The app is a functional MVP. All 4 tabs work (Home, Search, Review, Settings). Tested on Pixel 8 Pro with zero crashes at current version.

**What works:** Create/edit/delete memories, type filtering, hybrid search with 3 modes, weekly review generation, SmartDecay background scheduling, worker status monitoring, cache statistics.

**Known limitations:** No prefix search (must type full words), no task completion checkbox, no pin/unpin from UI, TF-IDF semantic matching is word-frequency based (not deep semantic understanding). 
No local LLM implementation so far, but might be updated in future versions, and my current choice would be Gemma3-1B-IT on Kaggle.

## License

MIT License. See [LICENSE](LICENSE) for details.

The SmartDecay algorithm is subject to US Provisional Patent Application #63/942,399. 
The patent covers the specific combination of multi-factor decay with device-resource-aware semantic matching in an offline pipeline. 
Using this code for personal or educational purposes is fine. Commercial use of the patented algorithm should consider the patent status.
