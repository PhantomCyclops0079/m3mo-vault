package com.m3mo.vault.core.json

import com.m3mo.vault.core.common.AppResult
import com.m3mo.vault.core.logging.Logger
import com.m3mo.vault.core.logging.NoOpLogger
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.lang.reflect.Type

/**
 * JsonCodec - Lightweight JSON Serialization
 *
 * Why not Gson/Moshi/Kotlin Serialization?
 * - Smaller APK size (no extra dependencies)
 * - Faster for simple structures
 * - Full control over serialization
 * - Explicit handling of nulls/defaults
 *
 * Performance Notes:
 * - Always register JsonAdapter for high-frequency objects
 * - Reflection fallback is SLOW on Android (only for debug/rare cases)
 * - Enable strictMode=true in production to catch missing adapters
 *
 * For complex objects, consider adding Kotlin Serialization later.
 */
interface JsonCodec {

    /**
     * Serialize object to JSON string
     */
    fun <T : Any> encode(value: T): AppResult<String, JsonError>

    /**
     * Deserialize JSON string to object
     */
    fun <T : Any> decode(json: String, clazz: Class<T>): AppResult<T, JsonError>

    /**
     * Serialize object to JSONObject
     */
    fun <T : Any> toJsonObject(value: T): AppResult<JSONObject, JsonError>

    /**
     * Deserialize JSONObject to object
     */
    fun <T : Any> fromJsonObject(json: JSONObject, clazz: Class<T>): AppResult<T, JsonError>
}

/**
 * JSON errors
 */
sealed class JsonError {
    data class ParseError(val message: String, val cause: Throwable? = null) : JsonError()
    data class SerializationError(val message: String, val cause: Throwable? = null) : JsonError()
    data class MissingField(val fieldName: String) : JsonError()
    data class TypeMismatch(val expected: String, val actual: String) : JsonError()
    data class MissingAdapter(val className: String) : JsonError()
}

/**
 * Default implementation using org.json
 *
 * @param strictMode If true, requires explicit adapter registration (recommended for production)
 * @param logger Logger for debugging adapter usage
 */
class DefaultJsonCodec(
    private val strictMode: Boolean = false,
    private val logger: Logger = NoOpLogger
) : JsonCodec {

    private val adapters = mutableMapOf<Class<*>, JsonAdapter<*>>()

    init {
        // Register built-in adapters
        registerBuiltInAdapters()
    }

    /**
     * Register a custom adapter for a type
     */
    fun <T : Any> registerAdapter(clazz: Class<T>, adapter: JsonAdapter<T>) {
        adapters[clazz] = adapter
        logger.d("JsonCodec", "Registered adapter for ${clazz.simpleName}")
    }

    override fun <T : Any> encode(value: T): AppResult<String, JsonError> {
        return toJsonObject(value).map { it.toString() }
    }

    override fun <T : Any> decode(json: String, clazz: Class<T>): AppResult<T, JsonError> {
        return try {
            val jsonObject = JSONObject(json)
            fromJsonObject(jsonObject, clazz)
        } catch (e: JSONException) {
            AppResult.failure(JsonError.ParseError("Invalid JSON: ${e.message}", e))
        }
    }

    @Suppress("UNCHECKED_CAST")
    override fun <T : Any> toJsonObject(value: T): AppResult<JSONObject, JsonError> {
        return try {
            val adapter = adapters[value::class.java] as? JsonAdapter<T>

            if (adapter != null) {
                AppResult.success(adapter.toJson(value))
            } else {
                if (strictMode) {
                    // Strict mode: Require explicit adapter registration
                    val error = JsonError.MissingAdapter(value::class.java.name)
                    logger.e("JsonCodec", "Missing adapter for ${value::class.java.simpleName} in strict mode")
                    AppResult.failure(error)
                } else {
                    // Debug mode: Warn and fallback to reflection
                    logger.w("JsonCodec", "Using SLOW reflection for ${value::class.java.simpleName}. " +
                            "Register an adapter for better performance.")
                    AppResult.success(reflectToJson(value))
                }
            }
        } catch (e: Exception) {
            AppResult.failure(JsonError.SerializationError("Failed to serialize: ${e.message}", e))
        }
    }

    @Suppress("UNCHECKED_CAST")
    override fun <T : Any> fromJsonObject(json: JSONObject, clazz: Class<T>): AppResult<T, JsonError> {
        return try {
            val adapter = adapters[clazz] as? JsonAdapter<T>
            if (adapter != null) {
                AppResult.success(adapter.fromJson(json))
            } else {
                if (strictMode) {
                    val error = JsonError.MissingAdapter(clazz.name)
                    logger.e("JsonCodec", "Missing adapter for ${clazz.simpleName} in strict mode")
                    AppResult.failure(error)
                } else {
                    AppResult.failure(
                        JsonError.SerializationError(
                            "No adapter registered for ${clazz.name}. " +
                                    "Reflection-based deserialization not supported."
                        )
                    )
                }
            }
        } catch (e: JSONException) {
            AppResult.failure(JsonError.ParseError("Failed to parse: ${e.message}", e))
        } catch (e: Exception) {
            AppResult.failure(JsonError.SerializationError("Failed to deserialize: ${e.message}", e))
        }
    }

    /**
     * Reflection-based serialization (SLOW - only for debug/rare cases)
     */
    private fun reflectToJson(value: Any): JSONObject {
        val json = JSONObject()
        value::class.java.declaredFields.forEach { field ->
            field.isAccessible = true
            val fieldValue = field.get(value)
            if (fieldValue != null) {
                json.put(field.name, fieldValue)
            }
        }
        return json
    }

    private fun registerBuiltInAdapters() {
        // Map adapter
        registerAdapter(Map::class.java, MapJsonAdapter())
    }

    companion object {
        @Volatile
        private var instance: DefaultJsonCodec? = null

        /**
         * Get singleton instance (debug mode)
         */
        fun getInstance(): DefaultJsonCodec {
            return instance ?: synchronized(this) {
                instance ?: DefaultJsonCodec(strictMode = false).also { instance = it }
            }
        }

        /**
         * Initialize with custom configuration
         */
        fun init(strictMode: Boolean, logger: Logger = NoOpLogger): DefaultJsonCodec {
            return DefaultJsonCodec(strictMode, logger).also { instance = it }
        }
    }
}

/**
 * Adapter interface for custom serialization
 */
interface JsonAdapter<T> {
    fun toJson(value: T): JSONObject
    fun fromJson(json: JSONObject): T
}

/**
 * Map adapter
 */
class MapJsonAdapter : JsonAdapter<Map<*, *>> {
    override fun toJson(value: Map<*, *>): JSONObject {
        return JSONObject(value)
    }

    override fun fromJson(json: JSONObject): Map<String, Any?> {
        val map = mutableMapOf<String, Any?>()
        json.keys().forEach { key ->
            map[key] = json.opt(key)
        }
        return map
    }
}

/**
 * Registry for centralized adapter registration
 * Call this from Application.onCreate()
 */
object JsonCodecRegistry {
    /**
     * Register all core adapters
     * Add your domain-specific adapters here
     */
    fun registerCoreAdapters(codec: DefaultJsonCodec) {
        // Example: Register high-frequency objects
        // codec.registerAdapter(MemoryFact::class.java, MemoryFactJsonAdapter())
        // codec.registerAdapter(MemoryEpisode::class.java, MemoryEpisodeJsonAdapter())
        // codec.registerAdapter(OptimizationMetrics::class.java, MetricsJsonAdapter())

        // TODO: Add adapters here as you implement domain objects
    }
}

// ========== Extension Functions ==========

/**
 * Safe get with default
 */
fun JSONObject.getStringOrDefault(key: String, default: String = ""): String {
    return optString(key, default)
}

fun JSONObject.getIntOrDefault(key: String, default: Int = 0): Int {
    return optInt(key, default)
}

fun JSONObject.getLongOrDefault(key: String, default: Long = 0L): Long {
    return optLong(key, default)
}

fun JSONObject.getDoubleOrDefault(key: String, default: Double = 0.0): Double {
    return optDouble(key, default)
}

fun JSONObject.getBooleanOrDefault(key: String, default: Boolean = false): Boolean {
    return optBoolean(key, default)
}

/**
 * Safe get with AppResult
 */
fun JSONObject.getStringResult(key: String): AppResult<String, JsonError> {
    return if (has(key)) {
        AppResult.success(getString(key))
    } else {
        AppResult.failure(JsonError.MissingField(key))
    }
}

fun JSONObject.getIntResult(key: String): AppResult<Int, JsonError> {
    return if (has(key)) {
        AppResult.success(getInt(key))
    } else {
        AppResult.failure(JsonError.MissingField(key))
    }
}

fun JSONObject.getLongResult(key: String): AppResult<Long, JsonError> {
    return if (has(key)) {
        AppResult.success(getLong(key))
    } else {
        AppResult.failure(JsonError.MissingField(key))
    }
}

/**
 * Convert JSONArray to List
 */
fun JSONArray.toStringList(): List<String> {
    val list = mutableListOf<String>()
    for (i in 0 until length()) {
        list.add(getString(i))
    }
    return list
}

fun JSONArray.toIntList(): List<Int> {
    val list = mutableListOf<Int>()
    for (i in 0 until length()) {
        list.add(getInt(i))
    }
    return list
}

fun JSONArray.toLongList(): List<Long> {
    val list = mutableListOf<Long>()
    for (i in 0 until length()) {
        list.add(getLong(i))
    }
    return list
}

fun <T> JSONArray.toList(transform: (JSONObject) -> T): List<T> {
    val list = mutableListOf<T>()
    for (i in 0 until length()) {
        list.add(transform(getJSONObject(i)))
    }
    return list
}

/**
 * Convert List to JSONArray
 */
fun <T> List<T>.toJsonArray(): JSONArray {
    return JSONArray(this)
}

fun <T> List<T>.toJsonArray(transform: (T) -> JSONObject): JSONArray {
    val array = JSONArray()
    forEach { array.put(transform(it)) }
    return array
}

/**
 * Pretty print JSON
 */
fun JSONObject.toPrettyString(indent: Int = 2): String {
    return toString(indent)
}

fun JSONArray.toPrettyString(indent: Int = 2): String {
    return toString(indent)
}

/**
 * Merge two JSONObjects
 */
fun JSONObject.merge(other: JSONObject): JSONObject {
    other.keys().forEach { key ->
        put(key, other.get(key))
    }
    return this
}

/**
 * Deep copy a JSONObject
 */
fun JSONObject.deepCopy(): JSONObject {
    return JSONObject(toString())
}
