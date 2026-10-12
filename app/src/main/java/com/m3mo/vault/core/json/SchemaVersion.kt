package com.m3mo.vault.core.json

import com.m3mo.vault.core.common.AppResult
import org.json.JSONObject

/**
 * SchemaVersion - Data Schema Versioning
 *
 * Handles version evolution of serialized data:
 * - Export/import compatibility
 * - WAL record versioning
 * - Settings migration
 *
 * Key principle: Always be able to read old data,
 * always write in the latest format.
 */
data class SchemaVersion(
    val major: Int,
    val minor: Int,
    val patch: Int = 0
) : Comparable<SchemaVersion> {

    override fun compareTo(other: SchemaVersion): Int {
        return when {
            major != other.major -> major - other.major
            minor != other.minor -> minor - other.minor
            else -> patch - other.patch
        }
    }

    /**
     * Check if this version is compatible with another
     * Compatible = same major version
     */
    fun isCompatibleWith(other: SchemaVersion): Boolean {
        return major == other.major
    }

    /**
     * Check if this version can read data from another version
     * Can read = other version <= this version
     */
    fun canRead(other: SchemaVersion): Boolean {
        return other <= this
    }

    /**
     * Check if migration is needed
     */
    fun needsMigration(from: SchemaVersion): Boolean {
        return from < this
    }

    override fun toString(): String = "$major.$minor.$patch"

    companion object {
        /**
         * Current schema versions for different data types
         */
        val MEMORY_FACT = SchemaVersion(2, 0, 0)
        val MEMORY_EPISODE = SchemaVersion(2, 0, 0)
        val MEMORY_TASK = SchemaVersion(1, 0, 0)
        val SETTINGS = SchemaVersion(3, 0, 0)
        val EXPORT = SchemaVersion(2, 0, 0)
        val WAL_RECORD = SchemaVersion(1, 0, 0)
        val METRICS = SchemaVersion(1, 0, 0)

        /**
         * Parse version string "major.minor.patch"
         */
        fun parse(versionString: String): AppResult<SchemaVersion, String> {
            val parts = versionString.split(".")
            return try {
                when (parts.size) {
                    1 -> AppResult.success(SchemaVersion(parts[0].toInt(), 0, 0))
                    2 -> AppResult.success(SchemaVersion(parts[0].toInt(), parts[1].toInt(), 0))
                    3 -> AppResult.success(SchemaVersion(parts[0].toInt(), parts[1].toInt(), parts[2].toInt()))
                    else -> AppResult.failure("Invalid version format: $versionString")
                }
            } catch (e: NumberFormatException) {
                AppResult.failure("Invalid version number: $versionString")
            }
        }

        /**
         * Parse or use default
         */
        fun parseOrDefault(versionString: String?, default: SchemaVersion): SchemaVersion {
            if (versionString == null) return default
            return parse(versionString).getOrDefault(default)
        }
    }
}

/**
 * Schema migration interface
 */
interface SchemaMigration<T> {
    val fromVersion: SchemaVersion
    val toVersion: SchemaVersion

    /**
     * Migrate data from old version to new version
     */
    fun migrate(data: T): AppResult<T, MigrationError>
}

/**
 * Migration errors
 */
sealed class MigrationError {
    data class UnsupportedVersion(val version: SchemaVersion) : MigrationError()
    data class MigrationFailed(val message: String, val cause: Throwable? = null) : MigrationError()
    data class DataCorrupted(val message: String) : MigrationError()
}

/**
 * Migration chain - applies multiple migrations in sequence
 */
class MigrationChain<T>(
    private val migrations: List<SchemaMigration<T>>
) {
    /**
     * Migrate data from source version to target version
     */
    fun migrate(
        data: T,
        fromVersion: SchemaVersion,
        toVersion: SchemaVersion
    ): AppResult<T, MigrationError> {
        if (fromVersion >= toVersion) {
            return AppResult.success(data)
        }

        val applicableMigrations = migrations
            .filter { it.fromVersion >= fromVersion && it.toVersion <= toVersion }
            .sortedBy { it.fromVersion }

        var currentData = data
        var currentVersion = fromVersion

        for (migration in applicableMigrations) {
            if (migration.fromVersion != currentVersion) {
                return AppResult.failure(
                    MigrationError.MigrationFailed(
                        "Missing migration from $currentVersion to ${migration.fromVersion}"
                    )
                )
            }

            when (val result = migration.migrate(currentData)) {
                is AppResult.Success -> {
                    currentData = result.value
                    currentVersion = migration.toVersion
                }
                is AppResult.Failure -> return result
            }
        }

        if (currentVersion < toVersion) {
            return AppResult.failure(
                MigrationError.MigrationFailed(
                    "Missing migration from $currentVersion to $toVersion"
                )
            )
        }

        return AppResult.success(currentData)
    }
}

/**
 * Versioned JSON wrapper
 */
data class VersionedJson(
    val version: SchemaVersion,
    val type: String,
    val data: JSONObject
) {
    fun toJson(): JSONObject {
        return JSONObject().apply {
            put("_version", version.toString())
            put("_type", type)
            put("data", data)
        }
    }

    companion object {
        fun fromJson(json: JSONObject): AppResult<VersionedJson, String> {
            return try {
                val versionStr = json.optString("_version", "1.0.0")
                val type = json.optString("_type", "unknown")
                val data = json.optJSONObject("data") ?: JSONObject()

                SchemaVersion.parse(versionStr).map { version ->
                    VersionedJson(version, type, data)
                }
            } catch (e: Exception) {
                AppResult.failure("Failed to parse versioned JSON: ${e.message}")
            }
        }

        /**
         * Create a new versioned JSON
         */
        fun create(
            version: SchemaVersion,
            type: String,
            block: JSONObject.() -> Unit
        ): VersionedJson {
            val data = JSONObject().apply(block)
            return VersionedJson(version, type, data)
        }
    }
}

/**
 * Helper to add version info to JSONObject
 */
fun JSONObject.withVersion(version: SchemaVersion): JSONObject {
    put("_schemaVersion", version.toString())
    return this
}

/**
 * Helper to extract version from JSONObject
 */
fun JSONObject.extractVersion(default: SchemaVersion): SchemaVersion {
    val versionStr = optString("_schemaVersion", null)
    return SchemaVersion.parseOrDefault(versionStr, default)
}


/**
 * Example: Migrate MemoryFact from v1 to v2
 * v2 adds: processingState, userConfidence fields
 */
class MemoryFactMigrationV1ToV2 : SchemaMigration<JSONObject> {
    override val fromVersion = SchemaVersion(1, 0, 0)
    override val toVersion = SchemaVersion(2, 0, 0)

    override fun migrate(data: JSONObject): AppResult<JSONObject, MigrationError> {
        return try {
            if (!data.has("processingState")) {
                data.put("processingState", "STORED")
            }
            if (!data.has("userConfidence")) {
                data.put("userConfidence", 0.5)
            }
            if (!data.has("version")) {
                data.put("version", 1)
            }

            data.put("_schemaVersion", toVersion.toString())

            AppResult.success(data)
        } catch (e: Exception) {
            AppResult.failure(MigrationError.MigrationFailed("V1->V2 migration failed", e))
        }
    }
}

/**
 * Example: Settings migration from v2 to v3
 * v3 adds: useFuzzyMerge, fuzzyThreshold
 */
class SettingsMigrationV2ToV3 : SchemaMigration<JSONObject> {
    override val fromVersion = SchemaVersion(2, 0, 0)
    override val toVersion = SchemaVersion(3, 0, 0)

    override fun migrate(data: JSONObject): AppResult<JSONObject, MigrationError> {
        return try {
            if (!data.has("memoryUseFuzzyMerge")) {
                data.put("memoryUseFuzzyMerge", false)
            }
            if (!data.has("memoryFuzzyThreshold")) {
                data.put("memoryFuzzyThreshold", 0.85)
            }

            data.put("_schemaVersion", toVersion.toString())
            AppResult.success(data)
        } catch (e: Exception) {
            AppResult.failure(MigrationError.MigrationFailed("V2->V3 migration failed", e))
        }
    }
}
