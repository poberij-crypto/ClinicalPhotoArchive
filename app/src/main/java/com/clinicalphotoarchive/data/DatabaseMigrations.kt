package com.clinicalphotoarchive.data

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

object DatabaseMigrations {
    val MIGRATION_1_2 = object : Migration(1,2) {
        override fun migrate(db: SupportSQLiteDatabase) {
            fun sequence(table: String): Long = db.query("SELECT seq FROM sqlite_sequence WHERE name=?",arrayOf(table)).use {
                if(it.moveToFirst()) it.getLong(0) else 0L
            }
            val patientsSequence=sequence("patients")
            val photosSequence=sequence("photos")
            db.execSQL("CREATE TABLE IF NOT EXISTS categories (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, name TEXT NOT NULL, nameKey TEXT NOT NULL, sortOrder INTEGER NOT NULL, createdAt INTEGER NOT NULL)")
            db.execSQL("CREATE UNIQUE INDEX index_categories_nameKey ON categories(nameKey)")
            // Remove the dependent table before replacing patients: DROP TABLE with FK
            // enforcement would otherwise cascade-delete the old photographs.
            db.execSQL("CREATE TEMP TABLE migration_photos AS SELECT * FROM photos")
            db.execSQL("DROP TABLE photos")
            db.execSQL("CREATE TABLE patients_new (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, surname TEXT NOT NULL, firstName TEXT NOT NULL, middleName TEXT NOT NULL, chartNumber TEXT NOT NULL, diagnosis TEXT NOT NULL, note TEXT NOT NULL, searchKey TEXT NOT NULL, createdAt INTEGER NOT NULL, updatedAt INTEGER NOT NULL, categoryId INTEGER, FOREIGN KEY(categoryId) REFERENCES categories(id) ON UPDATE NO ACTION ON DELETE SET NULL)")
            db.execSQL("INSERT INTO patients_new SELECT *,NULL FROM patients")
            db.execSQL("DROP TABLE patients")
            db.execSQL("ALTER TABLE patients_new RENAME TO patients")
            db.execSQL("CREATE INDEX index_patients_categoryId ON patients(categoryId)")
            db.execSQL("CREATE TABLE photos (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, patientId INTEGER NOT NULL, section TEXT NOT NULL, localPath TEXT NOT NULL, description TEXT NOT NULL, capturedAt INTEGER NOT NULL, addedAt INTEGER NOT NULL, FOREIGN KEY(patientId) REFERENCES patients(id) ON UPDATE NO ACTION ON DELETE CASCADE)")
            db.execSQL("INSERT INTO photos SELECT * FROM migration_photos")
            db.execSQL("DROP TABLE migration_photos")
            db.execSQL("CREATE INDEX index_photos_patientId ON photos(patientId)")
            db.execSQL("CREATE INDEX index_photos_patientId_section ON photos(patientId,section)")
            for ((table,seq) in listOf("patients" to patientsSequence,"photos" to photosSequence)) {
                db.execSQL("UPDATE sqlite_sequence SET seq=MAX(seq,?) WHERE name=?",arrayOf<Any>(seq,table))
                db.execSQL("INSERT INTO sqlite_sequence(name,seq) SELECT ?,? WHERE NOT EXISTS(SELECT 1 FROM sqlite_sequence WHERE name=?)",arrayOf<Any>(table,seq,table))
            }
            db.query("PRAGMA foreign_key_check").use { check(!it.moveToFirst()) { "Миграция нарушила связи данных" } }
        }
    }
}
