package com.m3mo.vault.data.mapper

import com.m3mo.vault.data.entity.MemoryEntity
import com.m3mo.vault.domain.model.MemoryItem
import com.m3mo.vault.domain.model.MemorySource
import com.m3mo.vault.domain.model.MemoryType
import com.m3mo.vault.domain.model.ProcessingState
import org.json.JSONArray
import org.json.JSONException

/**
 * MemoryMapper - Converts between MemoryEntity (data layer) and MemoryItem (domain layer)
 *
 * Responsibilities:
 * - Entity <-> Domain model conversion
 * - JSON serialization for tags
 * - Enum <-> String conversion
 *
 * This keeps data layer concerns (Room entities) separate from domain logic.
 */
object MemoryMapper {

    // ========== Entity -> Domain ==========

    /**
     * Convert MemoryEntity to MemoryItem
     */
    fun toDomain(entity: MemoryEntity): MemoryItem {
        return MemoryItem(
            id = entity.id,
            content = entity.content,
            normalizedContent = entity.normalizedContent,
            type = parseMemoryType(entity.type),
            state = parseProcessingState(entity.state),
            weight = entity.weight,
            isPinned = entity.isPinned,
            confidence = entity.confidence,
            isCompleted = entity.isCompleted,
            accessCount = entity.accessCount,
            embedding = entity.embedding,
            source = parseMemorySource(entity.source),
            tags = parseTags(entity.tags),
            createdAt = entity.createdAt,
            updatedAt = entity.updatedAt,
            lastAccessedAt = entity.lastAccessedAt,
            version = entity.version,
            schemaVersion = entity.schemaVersion
        )
    }

    /**
     * Convert list of entities to domain models
     */
    fun toDomainList(entities: List<MemoryEntity>): List<MemoryItem> {
        return entities.map { toDomain(it) }
    }

    // ========== Domain -> Entity ==========

    /**
     * Convert MemoryItem to MemoryEntity
     */
    fun toEntity(item: MemoryItem): MemoryEntity {
        return MemoryEntity(
            id = item.id,
            content = item.content,
            normalizedContent = item.normalizedContent,
            type = item.type.name,
            state = item.state.name,
            weight = item.weight,
            isPinned = item.isPinned,
            confidence = item.confidence,
            isCompleted = item.isCompleted,
            accessCount = item.accessCount,
            embedding = item.embedding,
            source = item.source.name,
            tags = serializeTags(item.tags),
            createdAt = item.createdAt,
            updatedAt = item.updatedAt,
            lastAccessedAt = item.lastAccessedAt,
            version = item.version,
            schemaVersion = item.schemaVersion
        )
    }

    /**
     * Convert list of domain models to entities
     */
    fun toEntityList(items: List<MemoryItem>): List<MemoryEntity> {
        return items.map { toEntity(it) }
    }

    // ========== Enum Parsing (String -> Enum) ==========

    private fun parseMemoryType(value: String): MemoryType {
        return try {
            MemoryType.valueOf(value)
        } catch (e: IllegalArgumentException) {
            MemoryType.FACT // Default fallback
        }
    }

    private fun parseProcessingState(value: String): ProcessingState {
        return try {
            ProcessingState.valueOf(value)
        } catch (e: IllegalArgumentException) {
            ProcessingState.NEW // Default fallback
        }
    }

    private fun parseMemorySource(value: String): MemorySource {
        return try {
            MemorySource.valueOf(value)
        } catch (e: IllegalArgumentException) {
            MemorySource.TEXT // Default fallback
        }
    }

    // ========== Tags Serialization ==========

    /**
     * Parse tags from JSON string
     */
    private fun parseTags(json: String): List<String> {
        if (json.isBlank() || json == "[]") {
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
     * Serialize tags to JSON string
     */
    private fun serializeTags(tags: List<String>): String {
        if (tags.isEmpty()) {
            return "[]"
        }
        return JSONArray(tags).toString()
    }
}

// ========== Extension Functions ==========

/**
 * Extension to convert entity to domain
 */
fun MemoryEntity.toDomain(): MemoryItem = MemoryMapper.toDomain(this)

/**
 * Extension to convert domain to entity
 */
fun MemoryItem.toEntity(): MemoryEntity = MemoryMapper.toEntity(this)

/**
 * Extension for list conversion
 */
fun List<MemoryEntity>.toDomainList(): List<MemoryItem> = MemoryMapper.toDomainList(this)

/**
 * Extension for list conversion
 */
fun List<MemoryItem>.toEntityList(): List<MemoryEntity> = MemoryMapper.toEntityList(this)
