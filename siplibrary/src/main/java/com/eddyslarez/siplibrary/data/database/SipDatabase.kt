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
        CallStateHistoryEntity::class
    ],
    version = 1,
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
                        // Aquí se pueden agregar migraciones futuras
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
            // Ejemplo: Agregar nueva columna
            // database.execSQL("ALTER TABLE sip_accounts ADD COLUMN newColumn TEXT")
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