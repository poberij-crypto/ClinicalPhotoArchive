package com.clinicalphotoarchive.util

import android.content.Context
import android.net.Uri
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.clinicalphotoarchive.data.*
import kotlinx.coroutines.runBlocking
import org.json.*
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.util.zip.*

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[28])
class BackupArchiveTest {
    private val context=ApplicationProvider.getApplicationContext<Context>()
    private lateinit var db:ClinicalDatabase
    private lateinit var backup:BackupArchive
    @Before fun setup() { db=Room.inMemoryDatabaseBuilder(context,ClinicalDatabase::class.java).build();backup=BackupArchive(context,db) }
    @After fun close() { db.close() }
    private fun temp(suffix:String)=File.createTempFile("archive-test",suffix,context.cacheDir)
    private fun manifest(version:Int)=JSONObject().put("format","clinical-photo-archive").put("version",version)
        .put("patients",JSONArray().put(JSONObject().put("id",1).put("surname","Иванов")))
        .put("photos",JSONArray())
    private fun zip(json:JSONObject,images:Map<String,ByteArray> = emptyMap()):Uri {
        val file=temp(".zip")
        ZipOutputStream(file.outputStream()).use { z ->
            z.putNextEntry(ZipEntry("manifest.json"));z.write(json.toString().toByteArray());z.closeEntry()
            images.forEach { (path,bytes)->z.putNextEntry(ZipEntry(path));z.write(bytes);z.closeEntry() }
        }
        return Uri.fromFile(file)
    }
    @Test fun roundTripPreservesEmptyCategoriesLinksAndXrayBytes()=runBlocking {
        val repo=CatalogRepository(db)
        val cat=repo.createCategory("Остеомиелит");repo.createCategory("Пустой раздел")
        val patient=db.patientDao().insert(PatientEntity(surname="Иванов",categoryId=cat,diagnosis="Диагноз"))
        val image=File(ImageFiles.imageDir(context),"backup-test-original.jpg").apply { writeBytes(byteArrayOf(1,2,3,4)) }
        db.photoDao().insert(PhotoEntity(patientId=patient,section="xray",localPath=image.absolutePath,description="Рентген",capturedAt=123))
        val out=temp(".zip");backup.exportTo(Uri.fromFile(out))
        ZipFile(out).use { z->assertEquals(2,JSONObject(z.getInputStream(z.getEntry("manifest.json")).reader().readText()).getInt("version")) }
        backup.restoreFrom(Uri.fromFile(out))
        assertEquals(2,db.categoryDao().getAll().size)
        assertEquals(cat,db.patientDao().getAll().single().categoryId)
        val photo=db.photoDao().getAll().single()
        assertEquals("xray",photo.section);assertEquals(123L,photo.capturedAt)
        assertArrayEquals(byteArrayOf(1,2,3,4),File(photo.localPath).readBytes())
    }
    @Test fun versionOneRestoresAllPatientsUncategorizedAndClearsOldCategories()=runBlocking {
        CatalogRepository(db).createCategory("Старый раздел")
        backup.restoreFrom(zip(manifest(1)))
        assertNull(db.patientDao().getAll().single().categoryId)
        assertTrue(db.categoryDao().getAll().isEmpty())
    }
    @Test fun malformedReferencesOrVersionsDoNotReplaceData()=runBlocking {
        val id=db.patientDao().insert(PatientEntity(surname="Сохранить"))
        for (json in listOf(manifest(99),manifest(2).put("categories",JSONArray()).apply { getJSONArray("patients").getJSONObject(0).put("categoryId",88) },
            manifest(1).put("photos",JSONArray().put(JSONObject().put("id",1).put("patientId",1).put("section","xray").put("archiveFile","images/a.jpg"))))) {
            assertThrows(Exception::class.java) { runBlocking { backup.restoreFrom(zip(json,mapOf("images/a.jpg" to byteArrayOf(1)))) } }
            assertEquals(id,db.patientDao().getAll().single().id)
            assertEquals("Сохранить",db.patientDao().getAll().single().surname)
        }
    }
    @Test fun missingImageDoesNotReplaceDataOrLeaveNewFiles()=runBlocking {
        db.patientDao().insert(PatientEntity(surname="Сохранить"))
        val before=ImageFiles.imageDir(context).listFiles()!!.map { it.name }.toSet()
        val json=manifest(1).put("photos",JSONArray().put(JSONObject().put("id",1).put("patientId",1).put("section","before").put("archiveFile","images/missing.jpg")))
        assertThrows(Exception::class.java) { runBlocking { backup.restoreFrom(zip(json)) } }
        assertEquals("Сохранить",db.patientDao().getAll().single().surname)
        assertEquals(before,ImageFiles.imageDir(context).listFiles()!!.map { it.name }.toSet())
    }
    @Test fun failedDatabaseInsertRollsBackAndRemovesStagedFiles()=runBlocking {
        val old=File(ImageFiles.imageDir(context),"rollback-original.jpg").apply { writeBytes(byteArrayOf(7,8)) }
        val id=db.patientDao().insert(PatientEntity(surname="Сохранить"))
        db.photoDao().insert(PhotoEntity(patientId=id,section="before",localPath=old.absolutePath))
        val before=ImageFiles.imageDir(context).listFiles()!!.map { it.name }.toSet()
        db.openHelper.writableDatabase.execSQL("CREATE TRIGGER fail_restore BEFORE INSERT ON patients WHEN NEW.surname='Ошибка' BEGIN SELECT RAISE(ABORT,'injected insert failure'); END")
        val json=manifest(1).apply { getJSONArray("patients").getJSONObject(0).put("surname","Ошибка") }
            .put("photos",JSONArray().put(JSONObject().put("id",1).put("patientId",1).put("section","before").put("archiveFile","images/a.jpg")))
        assertThrows(Exception::class.java) { runBlocking { backup.restoreFrom(zip(json,mapOf("images/a.jpg" to byteArrayOf(1,2,3)))) } }
        assertEquals("Сохранить",db.patientDao().getAll().single().surname)
        assertArrayEquals(byteArrayOf(7,8),old.readBytes())
        assertEquals(before,ImageFiles.imageDir(context).listFiles()!!.map { it.name }.toSet())
    }
    @Test fun traversalAndNormalizedDuplicatePathsAreRejected()=runBlocking {
        val id=db.patientDao().insert(PatientEntity(surname="Сохранить"))
        for (files in listOf(mapOf("../escape.jpg" to byteArrayOf(1)),mapOf("images/a.jpg" to byteArrayOf(1),"images\\a.jpg" to byteArrayOf(2)))) {
            assertThrows(IllegalArgumentException::class.java) { runBlocking { backup.restoreFrom(zip(manifest(1),files)) } }
            assertEquals(id,db.patientDao().getAll().single().id)
        }
    }
}
