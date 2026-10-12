# M3mo Vault

[![Kotlin](https://img.shields.io/badge/Kotlin-2.0-blue.svg)](https://kotlinlang.org)
[![Android](https://img.shields.io/badge/Min%20SDK-26-green.svg)](https://developer.android.com)
[![Status](https://img.shields.io/badge/Status-Prototype-purple.svg)]()

An offline Android prototype for storing personal facts, episodes, and tasks. It combines local SQLite storage, FTS4 search, TF-IDF ranking, and a rule-based maintenance algorithm called SmartDecay.

The app has no cloud-sync service. Android system backup is enabled in this build.

**Design history:** U.S. provisional application 63/942,399 — *"Offline Memory Lifecycle Management System and Method for Mobile AI Software"* — was submitted on December 16, 2025. It is not a granted patent. I do not plan to pursue a non-provisional application.
**[Demo Video](https://youtube.com/shorts/C30H1X8U_PE?si=_8SAzgQWdnWmPdaQ)**
---

## What It Does

You enter short text memories (facts, episodes, tasks).

The app stores entries locally in SQLite. A background worker is scheduled daily to apply SmartDecay to eligible records: unpinned entries in STORED or OPTIMIZED state with weight above 1. Device constraints and batch limits affect when records are processed. New entries start in NEW, outside that eligible set.

**SmartDecay** calculates weight changes using record age, access history, ratings, content length, and configurable decay settings. Pin bypasses decay. Given the same inputs, configuration, and processing time, the calculation is deterministic and returns a factor breakdown.

In a JVM unit-test benchmark, the decay calculation itself processed 10,500 synthetic memories in 8.16ms. That figure covers only the in-memory factor calculation; it was not measured on an Android device, and does not include the database reads and writes that surround it in a real run.

## What It Doesn't Do (Yet)

- No language-model integration; retrieval currently uses FTS4 and TF-IDF, with rule-based maintenance
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

**Dependency wiring:** Dependencies are wired explicitly in ServiceLocator, which provides a central place to inspect the app's dependency graph.

## SmartDecay Algorithm

Under the default configuration, positive contributions increase calculated decay and negative contributions reduce it.

| # | Factor | Default behavior |
|---|---|---|
| 1 | Base Decay | Add 1 per processing cycle. |
| 2 | Grace Period | Subtract 1 while the record is less than 7 days old, cancelling the base contribution. |
| 3 | Recency Penalty | Add 1 after 14 days without access, or 2 after 28 days. |
| 4 | Frequency Bonus | Subtract 1 at 5–9 recorded accesses, or 2 at 10 or more. |
| 5 | Confidence Bonus | Ratings 3, 4, and 5 subtract 0.5, 1, and 2 respectively. Ratings 1–2 provide no reduction; an unrated record defaults to 3. |
| 6 | Size Penalty | Add 0.5 for 1,001–5,000 characters, or 1 above 5,000. |
| 7 | Pin Protection | Skip decay entirely. |

Contributions are summed, truncated toward zero, and clamped at zero before being subtracted from the current weight.

Weights start at 8 and remain within 1–16. When a changed weight reaches 1, the rule recommends tombstoning; otherwise, reaching 2 recommends archiving. Archived records leave the daily decay query, so archiving does not automatically lead to deletion.

Pin protects against archiving or tombstoning through this decay path, but does not prevent manual deletion.

### Experimental Evidence

A separate synthetic experiment summary reports weight outcomes under configured scenarios. The experiment harness is not included in this public snapshot. These results do not establish retrieval quality or Android device performance.

## Search

Hybrid search uses SQLite FTS4 to select keyword candidates, then TF-IDF cosine similarity helps rank those candidates. Reranking cannot recover a record excluded by the candidate search.

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
```

## Implementation & Running

```bash
# Clone
git clone https://github.com/PhantomCyclops0079/m3mo-vault.git

# Open in Android Studio (Ladybug or newer)
# Sync Gradle, then Run on device/emulator (API 26+)
```

## Current Status

This is a prototype with Home, Search, Review, and Settings screens. I installed it on a Pixel 8 Pro and used it for day-to-day entries; that is informal personal use, not a QA pass or a device benchmark. Development is currently paused.

**Features:** Local entry storage, type filtering, three search modes, weekly review generation, background maintenance scheduling, worker status monitoring, and cache statistics.

**Known limitations:** No prefix-search UI, no task completion checkbox, no pin/unpin or rating controls, and no language-model integration. TF-IDF matching relies on word overlap and term weighting.

The optimization pipeline and user controls still have unfinished connections. The background decay worker has a separate execution path.

## License

MIT License. See [LICENSE](LICENSE) for details.
