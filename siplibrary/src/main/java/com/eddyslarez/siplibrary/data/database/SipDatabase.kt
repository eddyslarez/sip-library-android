package com.eddyslarez.siplibrary.data.database

import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import android.content.Context
import com.eddyslarez.siplibrary.data.database.entities.*
import com.eddyslarez.siplibrary.data.database.dao.*
import com.eddyslarez.siplibrary.data.database.converters.DatabaseConverters

/**
 * Base de datos principal de la librería SIP
 * 
 * @author Eddys Larez
 */
@Database(
    entities = [
        SipAccountEntity::class,
        CallLogEntity::class,
        CallDataEntity::class,
        ContactEntity::class,
        CallStateHistoryEntity::class,
//        TranscriptionEntity::class,
//        TranscriptionSessionEntity::class
    ],
    version = 2,
    exportSchema = true
)
@TypeConverters(DatabaseConverters::class)
abstract class SipDatabase : RoomDatabase() {
    
    // === DAOs ===
    abstract fun sipAccountDao(): SipAccountDao
    abstract fun callLogDao(): CallLogDao
    abstract fun callDataDao(): CallDataDao
    abstract fun contactDao(): ContactDao
    abstract fun callStateHistoryDao(): CallStateHistoryDao
//    abstract fun transcriptionDao(): TranscriptionDao
//    abstract fun transcriptionSessionDao(): TranscriptionSessionDao
    
    companion object {
        private const val DATABASE_NAME = "sip_database"
        
        @Volatile
        private var INSTANCE: SipDatabase? = null
        
        /**
         * Obtiene la instancia singleton de la base de datos
         */
        fun getDatabase(context: Context): SipDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    SipDatabase::class.java,
                    DATABASE_NAME
                )
                    .addMigrations(
                        DatabaseMigrations.MIGRATION_1_2
                        // Aquí se pueden agregar más migraciones futuras
                    )
                    .fallbackToDestructiveMigration() // Solo para desarrollo
                    .build()
                
                INSTANCE = instance
                instance
            }
        }
        
        /**
         * Cierra la base de datos (útil para testing)
         */
        fun closeDatabase() {
            INSTANCE?.close()
            INSTANCE = null
        }
    }
}

/**
 * Migraciones de base de datos (para versiones futuras)
 */
object DatabaseMigrations {
    
    // Ejemplo de migración de versión 1 a 2
    val MIGRATION_1_2 = object : Migration(1, 2) {
        override fun migrate(database: SupportSQLiteDatabase) {
            // Crear tablas de transcripción
            database.execSQL("""
                CREATE TABLE IF NOT EXISTS transcriptions (
                    id TEXT PRIMARY KEY NOT NULL,
                    sessionId TEXT NOT NULL,
                    callLogId TEXT NOT NULL,
                    text TEXT NOT NULL,
                    confidence REAL NOT NULL,
                    isFinal INTEGER NOT NULL,
                    timestamp INTEGER NOT NULL,
                    duration INTEGER NOT NULL,
                    audioSource TEXT NOT NULL,
                    language TEXT NOT NULL,
                    speakerLabel TEXT,
                    wordCount INTEGER NOT NULL DEFAULT 0,
                    createdAt INTEGER NOT NULL DEFAULT 0,
                    FOREIGN KEY(callLogId) REFERENCES call_logs(id) ON DELETE CASCADE
                )
            """)
            
            database.execSQL("""
                CREATE TABLE IF NOT EXISTS transcription_sessions (
                    id TEXT PRIMARY KEY NOT NULL,
                    callLogId TEXT NOT NULL,
                    callId TEXT NOT NULL,
                    startTime INTEGER NOT NULL,
                    endTime INTEGER,
                    isActive INTEGER NOT NULL DEFAULT 1,
                    language TEXT NOT NULL,
                    audioSource TEXT NOT NULL,
                    transcriptionProvider TEXT NOT NULL,
                    enablePartialResults INTEGER NOT NULL DEFAULT 1,
                    confidenceThreshold REAL NOT NULL DEFAULT 0.5,
                    enableProfanityFilter INTEGER NOT NULL DEFAULT 0,
                    enablePunctuation INTEGER NOT NULL DEFAULT 1,
                    totalTranscriptions INTEGER NOT NULL DEFAULT 0,
                    finalTranscriptions INTEGER NOT NULL DEFAULT 0,
                    partialTranscriptions INTEGER NOT NULL DEFAULT 0,
                    totalWords INTEGER NOT NULL DEFAULT 0,
                    averageConfidence REAL NOT NULL DEFAULT 0.0,
                    speechDuration INTEGER NOT NULL DEFAULT 0,
                    silenceDuration INTEGER NOT NULL DEFAULT 0,
                    audioFramesProcessed INTEGER NOT NULL DEFAULT 0,
                    errorsCount INTEGER NOT NULL DEFAULT 0,
                    averageAudioLevel REAL NOT NULL DEFAULT 0.0,
                    peakAudioLevel REAL NOT NULL DEFAULT 0.0,
                    averageSnr REAL NOT NULL DEFAULT 0.0,
                    clippingDetected INTEGER NOT NULL DEFAULT 0,
                    createdAt INTEGER NOT NULL DEFAULT 0,
                    updatedAt INTEGER NOT NULL DEFAULT 0,
                    FOREIGN KEY(callLogId) REFERENCES call_logs(id) ON DELETE CASCADE
                )
            """)
            
            // Crear índices para transcripciones
            database.execSQL("CREATE INDEX IF NOT EXISTS index_transcriptions_sessionId ON transcriptions(sessionId)")
            database.execSQL("CREATE INDEX IF NOT EXISTS index_transcriptions_callLogId ON transcriptions(callLogId)")
            database.execSQL("CREATE INDEX IF NOT EXISTS index_transcriptions_timestamp ON transcriptions(timestamp)")
            database.execSQL("CREATE INDEX IF NOT EXISTS index_transcriptions_isFinal ON transcriptions(isFinal)")
            database.execSQL("CREATE INDEX IF NOT EXISTS index_transcriptions_speakerLabel ON transcriptions(speakerLabel)")
            
            // Crear índices para sesiones de transcripción
            database.execSQL("CREATE INDEX IF NOT EXISTS index_transcription_sessions_callLogId ON transcription_sessions(callLogId)")
            database.execSQL("CREATE INDEX IF NOT EXISTS index_transcription_sessions_startTime ON transcription_sessions(startTime)")
            database.execSQL("CREATE INDEX IF NOT EXISTS index_transcription_sessions_isActive ON transcription_sessions(isActive)")
        }
    }
    
    // Ejemplo de migración de versión 2 a 3
    val MIGRATION_2_3 = object : Migration(2, 3) {
        override fun migrate(database: SupportSQLiteDatabase) {
            // Ejemplo: Crear nueva tabla
            // database.execSQL("CREATE TABLE IF NOT EXISTS new_table (...)")
        }
    }
}