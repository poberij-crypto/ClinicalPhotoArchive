package com.clinicalphotoarchive.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(entities = [PatientEntity::class, PhotoEntity::class], version = 1, exportSchema = true)
abstract class ClinicalDatabase : RoomDatabase() {
    abstract fun patientDao(): PatientDao
    abstract fun photoDao(): PhotoDao

    companion object {
        @Volatile private var INSTANCE: ClinicalDatabase? = null
        fun getInstance(context: Context): ClinicalDatabase = INSTANCE ?: synchronized(this) {
            INSTANCE ?: Room.databaseBuilder(
                context.applicationContext,
                ClinicalDatabase::class.java,
                "clinical_photo_archive.db"
            ).build().also { INSTANCE = it }
        }
    }
}
