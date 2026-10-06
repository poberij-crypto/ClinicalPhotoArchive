package com.clinicalphotoarchive.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(entities = [PatientEntity::class, PhotoEntity::class, CategoryEntity::class], version = 2, exportSchema = true)
abstract class ClinicalDatabase : RoomDatabase() {
    abstract fun patientDao(): PatientDao
    abstract fun photoDao(): PhotoDao
    abstract fun categoryDao(): CategoryDao

    companion object {
        @Volatile private var INSTANCE: ClinicalDatabase? = null
        fun getInstance(context: Context): ClinicalDatabase = INSTANCE ?: synchronized(this) {
            INSTANCE ?: Room.databaseBuilder(
                context.applicationContext,
                ClinicalDatabase::class.java,
                "clinical_photo_archive.db"
            ).addMigrations(DatabaseMigrations.MIGRATION_1_2).build().also { INSTANCE = it }
        }
    }
}
