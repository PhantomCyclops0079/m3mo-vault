package com.m3mo.vault.data.db

import androidx.room.TypeConverter
import org.json.JSONArray
import org.json.JSONException

/**
 * Converters - Room type converters for complex types
 *
 * Converts between:
 * - List<String> <-> JSON string (for tags)
 * - Other complex types as needed
 *
 * Uses org.json directly for simplicity and reliability.
 */
class Converters {

    /**
     * Convert List<String> to JSON string for storage
     */
    @TypeConverter
    fun fromStringList(list: List<String>?): String {
        if (list.isNullOrEmpty()) return "[]"
        return JSONArray(list).toString()
    }

    /**
     * Convert JSON string back to List<String>
     */
    @TypeConverter
    fun toStringList(json: String?): List<String> {
        if (json.isNullOrBlank() || json == "[]") {
            return emptyList()
        }
        return try {
            val array = JSONArray(json)
            val list = mutableListOf<String>()
            for (i in 0 until array.length()) {
                list.add(array.getString(i))
            }
            list
        } catch (e: JSONException) {
            emptyList()
        }
    }

    /**
     * Convert List<Float> to JSON string (for embeddings)
     */
    @TypeConverter
    fun fromFloatList(list: List<Float>?): String? {
        if (list.isNullOrEmpty()) return null
        return JSONArray(list).toString()
    }

    /**
     * Convert JSON string back to List<Float>
     */
    @TypeConverter
    fun toFloatList(json: String?): List<Float>? {
        if (json.isNullOrBlank()) return null
        return try {
            val array = JSONArray(json)
            val list = mutableListOf<Float>()
            for (i in 0 until array.length()) {
                list.add(array.getDouble(i).toFloat())
            }
            list
        } catch (e: JSONException) {
            null
        }
    }

    /**
     * Convert List<Int> to JSON string
     */
    @TypeConverter
    fun fromIntList(list: List<Int>?): String? {
        if (list.isNullOrEmpty()) return null
        return JSONArray(list).toString()
    }

    /**
     * Convert JSON string back to List<Int>
     */
    @TypeConverter
    fun toIntList(json: String?): List<Int>? {
        if (json.isNullOrBlank()) return null
        return try {
            val array = JSONArray(json)
            val list = mutableListOf<Int>()
            for (i in 0 until array.length()) {
                list.add(array.getInt(i))
            }
            list
        } catch (e: JSONException) {
            null
        }
    }

    /**
     * Convert List<Long> to JSON string
     */
    @TypeConverter
    fun fromLongList(list: List<Long>?): String? {
        if (list.isNullOrEmpty()) return null
        return JSONArray(list).toString()
    }

    /**
     * Convert JSON string back to List<Long>
     */
    @TypeConverter
    fun toLongList(json: String?): List<Long>? {
        if (json.isNullOrBlank()) return null
        return try {
            val array = JSONArray(json)
            val list = mutableListOf<Long>()
            for (i in 0 until array.length()) {
                list.add(array.getLong(i))
            }
            list
        } catch (e: JSONException) {
            null
        }
    }
}
