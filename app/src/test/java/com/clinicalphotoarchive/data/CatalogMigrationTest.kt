package com.clinicalphotoarchive.data

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class CatalogMigrationTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    @Test fun migrationPreservesPhotosAndDeletedHighWaterIds() = runBlocking {
        val name = "migration-test.db"
        context.deleteDatabase(name)
        val path = context.getDatabasePath(name)
        path.parentFile!!.mkdirs()
        SQLiteDatabase.openOrCreateDatabase(path, null).use { db ->
            db.execSQL("CREATE TABLE patients (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, surname TEXT NOT NULL, firstName TEXT NOT NULL, middleName TEXT NOT NULL, chartNumber TEXT NOT NULL, diagnosis TEXT NOT NULL, note TEXT NOT NULL, searchKey TEXT NOT NULL, createdAt INTEGER NOT NULL, updatedAt INTEGER NOT NULL)")
            db.execSQL("CREATE TABLE photos (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, patientId INTEGER NOT NULL, section TEXT NOT NULL, localPath TEXT NOT NULL, description TEXT NOT NULL, capturedAt INTEGER NOT NULL, addedAt INTEGER NOT NULL, FOREIGN KEY(patientId) REFERENCES patients(id) ON DELETE CASCADE)")
            db.execSQL("CREATE INDEX index_photos_patientId ON photos(patientId)")
            db.execSQL("CREATE INDEX index_photos_patientId_section ON photos(patientId,section)")
            db.execSQL("INSERT INTO patients VALUES (50,'Иванов','Алексей','','001','Диагноз','Заметка','иванов',10,20)")
            for ((i,s) in listOf("before","operation","after").withIndex()) db.execSQL("INSERT INTO photos VALUES (?,50,?,'/original.jpg','Описание',30,40)", arrayOf<Any>(80+i,s))
            db.execSQL("UPDATE sqlite_sequence SET seq=500 WHERE name='patients'")
            db.execSQL("UPDATE sqlite_sequence SET seq=800 WHERE name='photos'")
            db.version = 1
        }
        val db = Room.databaseBuilder(context,ClinicalDatabase::class.java,name).addMigrations(DatabaseMigrations.MIGRATION_1_2).build()
        try {
            val p = db.patientDao().getAll().single()
            assertEquals(50L,p.id); assertEquals("Заметка",p.note); assertNull(p.categoryId)
            assertEquals(listOf("before","operation","after"),db.photoDao().getAll().map { it.section })
            assertTrue(db.photoDao().getAll().all { it.localPath=="/original.jpg" && it.description=="Описание" })
            assertTrue(db.patientDao().insert(PatientEntity(surname="Новый"))>500)
            assertTrue(db.photoDao().insert(PhotoEntity(patientId=50,section="xray",localPath="/new.jpg"))>800)
            db.openHelper.writableDatabase.query("PRAGMA foreign_key_check").use { assertEquals(0,it.count) }
        } finally { db.close(); context.deleteDatabase(name) }
    }
    @Test fun deletingCategoryPreservesPatientAndPhotos() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(context,ClinicalDatabase::class.java).build()
        try {
            val repo=CatalogRepository(db)
            val cat=repo.createCategory("Остеомиелит")
            val patient=db.patientDao().insert(PatientEntity(surname="Иванов",categoryId=cat,updatedAt=1))
            db.photoDao().insert(PhotoEntity(patientId=patient,section="before",localPath="/keep.jpg"))
            assertThrows(Exception::class.java) { runBlocking { repo.createCategory(" ОСТЕОМИЕЛИТ ") } }
            repo.renameCategory(cat,"Другой раздел")
            assertEquals(cat,db.patientDao().getAll().single().categoryId)
            repo.deleteCategory(cat)
            assertNull(db.patientDao().getAll().single().categoryId)
            assertTrue(db.patientDao().getAll().single().updatedAt>1)
            assertEquals("/keep.jpg",db.photoDao().getAll().single().localPath)
        } finally { db.close() }
    }
}
