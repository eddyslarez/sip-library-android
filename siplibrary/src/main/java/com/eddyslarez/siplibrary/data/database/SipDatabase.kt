package com.eddyslarez.siplibrary.data.database

import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.sqlite.db.SupportSQLiteDatabase
import android.content.Context
import com.eddyslarez.siplibrary.data.database.entities.*
import com.eddyslarez.siplibrary.data.database.dao.*
import com.eddyslarez.siplibrary.data.database.converters.DatabaseConverters
import com.eddyslarez.siplibrary.utils.log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.util.concurrent.Executors

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
        AppConfigEntity::class,
        AssistantConfigEntity::class,
        BlacklistEntity::class,
        AssistantCallLogEntity::class
    ],
    version = 3, // INCREMENTAR VERSION PARA NUEVAS TABLAS
    exportSchema = false
)
@TypeConverters(DatabaseConverters::class)
abstract class SipDatabase : RoomDatabase() {

    abstract fun sipAccountDao(): SipAccountDao
    abstract fun callLogDao(): CallLogDao
    abstract fun callDataDao(): CallDataDao
    abstract fun contactDao(): ContactDao
    abstract fun callStateDao(): CallHistoryDao
    abstract fun appConfigDao(): AppConfigDao
    abstract fun assistantConfigDao(): AssistantConfigDao  // NUEVO DAO
    abstract fun blacklistDao(): BlacklistDao              // NUEVO DAO
    abstract fun assistantCallLogDao(): AssistantCallLogDao // NUEVO DAO

    companion object {
        @Volatile
        private var INSTANCE: SipDatabase? = null
        private val LOCK = Any()

        fun getDatabase(context: Context): SipDatabase {
            return INSTANCE ?: synchronized(LOCK) {
                INSTANCE ?: buildDatabase(context).also { INSTANCE = it }
            }
        }

        private fun buildDatabase(context: Context): SipDatabase {
            return Room.databaseBuilder(
                context.applicationContext,
                SipDatabase::class.java,
                "sip_database"
            )
                .fallbackToDestructiveMigration() // TEMPORAL: para desarrollo
                .enableMultiInstanceInvalidation()
                .setJournalMode(RoomDatabase.JournalMode.WRITE_AHEAD_LOGGING)
                .setQueryCallback(object : RoomDatabase.QueryCallback {
                    override fun onQuery(sqlQuery: String, bindArgs: List<Any?>) {
                        log.d { "Query: $sqlQuery" }
                    }
                }, Executors.newSingleThreadExecutor())
                .addCallback(object : RoomDatabase.Callback() {
                    override fun onCreate(db: SupportSQLiteDatabase) {
                        super.onCreate(db)
                        log.d { "Database created successfully" }

                        // Crear configuración por defecto
                        CoroutineScope(Dispatchers.IO).launch {
                            try {
                                val instance = getDatabase(context)
                                val defaultConfig = AppConfigEntity()
                                instance.appConfigDao().insertConfig(defaultConfig)
                                log.d { "Default configuration created" }
                            } catch (e: Exception) {
                                log.e { "Error creating default configuration: ${e.message}" }
                            }
                        }
                    }

                    override fun onOpen(db: SupportSQLiteDatabase) {
                        super.onOpen(db)
                        log.d { "Database opened successfully" }

                        try {
                            db.execSQL("PRAGMA integrity_check;")
                            log.d { "Database integrity check passed" }
                        } catch (e: Exception) {
                            log.e { "Database integrity check failed: ${e.message}" }
                        }
                    }
                })
                .build()
        }

        /**
         * NUEVO: Método para cerrar la base de datos de forma segura
         */
        fun closeDatabase() {
            synchronized(LOCK) {
                INSTANCE?.let { instance ->
                    if (instance.isOpen) {
                        try {
                            instance.close()
                            log.d { "Database instance closed" }
                        } catch (e: Exception) {
                            log.e { "Error closing database instance: ${e.message}" }
                        }
                    }
                }
                INSTANCE = null
            }
        }

        /**
         * NUEVO: Verificar si la base de datos existe y está disponible
         */
        fun isDatabaseAvailable(context: Context): Boolean {
            return try {
                val dbFile = context.getDatabasePath("sip_database")
                dbFile.exists() && dbFile.canRead()
            } catch (e: Exception) {
                false
            }
        }
    }
}