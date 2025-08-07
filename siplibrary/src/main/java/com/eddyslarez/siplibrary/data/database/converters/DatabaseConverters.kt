package com.eddyslarez.siplibrary.data.database.converters

import androidx.room.TypeConverter
import com.eddyslarez.siplibrary.data.models.*

/**
 * Convertidores de tipos para Room Database
 * 
 * @author Eddys Larez
 */
class DatabaseConverters {
    
    // === REGISTRATION STATE ===
    
    @TypeConverter
    fun fromRegistrationState(state: RegistrationState): String {
        return state.name
    }
    
    @TypeConverter
    fun toRegistrationState(state: String): RegistrationState {
        return try {
            RegistrationState.valueOf(state)
        } catch (e: IllegalArgumentException) {
            RegistrationState.NONE
        }
    }
    
    // === CALL DIRECTIONS ===
    
    @TypeConverter
    fun fromCallDirections(direction: CallDirections): String {
        return direction.name
    }
    
    @TypeConverter
    fun toCallDirections(direction: String): CallDirections {
        return try {
            CallDirections.valueOf(direction)
        } catch (e: IllegalArgumentException) {
            CallDirections.OUTGOING
        }
    }
    
    // === CALL TYPES ===
    
    @TypeConverter
    fun fromCallTypes(type: CallTypes): String {
        return type.name
    }
    
    @TypeConverter
    fun toCallTypes(type: String): CallTypes {
        return try {
            CallTypes.valueOf(type)
        } catch (e: IllegalArgumentException) {
            CallTypes.SUCCESS
        }
    }
    
    // === CALL STATE ===
    
    @TypeConverter
    fun fromCallState(state: CallState): String {
        return state.name
    }
    
    @TypeConverter
    fun toCallState(state: String): CallState {
        return try {
            CallState.valueOf(state)
        } catch (e: IllegalArgumentException) {
            CallState.IDLE
        }
    }
    
    // === CALL ERROR REASON ===
    
    @TypeConverter
    fun fromCallErrorReason(reason: CallErrorReason): String {
        return reason.name
    }
    
    @TypeConverter
    fun toCallErrorReason(reason: String): CallErrorReason {
        return try {
            CallErrorReason.valueOf(reason)
        } catch (e: IllegalArgumentException) {
            CallErrorReason.NONE
        }
    }
    
    // === STRING MAPS (para parámetros adicionales) ===
    
    @TypeConverter
    fun fromStringMap(map: Map<String, String>?): String? {
        if (map == null) return null
        return map.entries.joinToString(";") { "${it.key}=${it.value}" }
    }
    
    @TypeConverter
    fun toStringMap(data: String?): Map<String, String> {
        if (data.isNullOrEmpty()) return emptyMap()
        return try {
            data.split(";").associate { pair ->
                val (key, value) = pair.split("=", limit = 2)
                key to value
            }
        } catch (e: Exception) {
            emptyMap()
        }
    }
    
    // === STRING LISTS ===
    
    @TypeConverter
    fun fromStringList(list: List<String>?): String? {
        return list?.joinToString(",")
    }
    
    @TypeConverter
    fun toStringList(data: String?): List<String> {
        return if (data.isNullOrEmpty()) {
            emptyList()
        } else {
            data.split(",").map { it.trim() }
        }
    }
    
    // === STRING SETS ===
    
    @TypeConverter
    fun fromStringSet(set: Set<String>?): String? {
        return set?.joinToString(",")
    }
    
    @TypeConverter
    fun toStringSet(data: String?): Set<String> {
        return if (data.isNullOrEmpty()) {
            emptySet()
        } else {
            data.split(",").map { it.trim() }.toSet()
        }
    }
}