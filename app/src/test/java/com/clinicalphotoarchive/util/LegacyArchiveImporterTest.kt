package com.clinicalphotoarchive.util

import android.content.Context
import android.content.ContextWrapper
import android.database.sqlite.SQLiteDatabase
import android.net.Uri
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.clinicalphotoarchive.data.*
import kotlinx.coroutines.*
import java.util.concurrent.*
import kotlin.coroutines.CoroutineContext
import org.apache.commons.compress.archivers.tar.*
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[28])
class LegacyArchiveImporterTest {
    private val context=ApplicationProvider.getApplicationContext<Context>()
    // Robolectric includes the method name in its cache path. On Windows that
    // exceeds SQLite's native MAX_PATH after the import staging subdirectories.
    private val stage=java.nio.file.Files.createTempDirectory("legacy-stage").toFile()
    private val importerContext=object:ContextWrapper(context) { override fun getCacheDir()=stage }
    @After fun cleanupStage() { stage.deleteRecursively() }
    private fun archive(version:Int, image:Boolean=false):Uri {
        val sqlite=File.createTempFile("legacy-test",".db",context.cacheDir)
        SQLiteDatabase.openOrCreateDatabase(sqlite,null).use { db ->
            db.execSQL("CREATE TABLE patients(id INTEGER PRIMARY KEY,surname TEXT NOT NULL,chartNumber TEXT,createdAt INTEGER)")
            db.execSQL("CREATE TABLE photos(id INTEGER PRIMARY KEY,patientId INTEGER,section TEXT,localPath TEXT)")
            db.execSQL("INSERT INTO patients VALUES(1,'Иванов','001',10),(2,'Петров','002',20)")
            if(image) db.execSQL("INSERT INTO photos VALUES(1,1,'before','/old/a.jpg')")
            db.version=version
        }
        val tar=File.createTempFile("legacy-test",".tar",context.cacheDir)
        SQLiteDatabase.openDatabase(sqlite.absolutePath,null,SQLiteDatabase.OPEN_READONLY).use { assertEquals(version,it.version) }
        TarArchiveOutputStream(tar.outputStream()).use { out ->
            out.putArchiveEntry(TarArchiveEntry("databases/clinical_photo_archive.db").apply { size=sqlite.length() })
            sqlite.inputStream().use { it.copyTo(out) };out.closeArchiveEntry()
            if(image) {
                val bytes=byteArrayOf(1,2,3,4)
                out.putArchiveEntry(TarArchiveEntry("files/clinical_images/a.jpg").apply { size=bytes.size.toLong() })
                out.write(bytes);out.closeArchiveEntry()
            }
        }
        sqlite.delete();return Uri.fromFile(tar)
    }
    @Test fun mergingVersionOnePreservesAssignedCategoryAndAddsUncategorizedPatient()=runBlocking {
        val db=Room.inMemoryDatabaseBuilder(context,ClinicalDatabase::class.java).build()
        try {
            val cat=CatalogRepository(db).createCategory("Остеомиелит")
            db.patientDao().insert(PatientEntity(surname="Иванов",chartNumber="001",categoryId=cat))
            val result=LegacyArchiveImporter(importerContext,db).importFrom(archive(1))
            assertEquals(1,result.patientsAdded);assertEquals(1,result.patientsMerged)
            assertEquals(cat,db.patientDao().getAll().single { it.chartNumber=="001" }.categoryId)
            assertNull(db.patientDao().getAll().single { it.chartNumber=="002" }.categoryId)
        } finally { db.close() }
    }
    @Test fun versionTwoTarIsRejectedWithoutImportingPatients()=runBlocking {
        val db=Room.inMemoryDatabaseBuilder(context,ClinicalDatabase::class.java).build()
        try {
            assertThrows(IllegalArgumentException::class.java) { runBlocking { LegacyArchiveImporter(importerContext,db).importFrom(archive(2)) } }
            assertTrue(db.patientDao().getAll().isEmpty())
        } finally { db.close() }
    }
    @Test fun cancellationAfterCommitKeepsImportedImage()=runBlocking {
        val queue=LinkedBlockingQueue<Runnable>()
        val dispatcher=object:CoroutineDispatcher() {
            override fun dispatch(context:CoroutineContext,block:Runnable) { queue.put(block) }
        }
        val started=CountDownLatch(1)
        val finished=CountDownLatch(1)
        val transactionThread=Executors.newSingleThreadExecutor()
        val queryThread=Executors.newSingleThreadExecutor()
        val db=Room.inMemoryDatabaseBuilder(context,ClinicalDatabase::class.java)
            .setQueryExecutor(queryThread)
            .setTransactionExecutor { task -> transactionThread.execute {
                started.countDown()
                try { task.run() } finally { finished.countDown() }
            } }.build()
        val job=CoroutineScope(dispatcher).launch {
            LegacyArchiveImporter(importerContext,db).importFrom(archive(1,true))
        }
        try {
            val deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(30)
            while(started.count>0 && System.nanoTime()<deadline) {
                val task=queue.poll(100,TimeUnit.MILLISECONDS)
                if(started.count==0L) { if(task!=null) queue.put(task);break }
                task?.run()
            }
            assertEquals("Import transaction started",0,started.count)
            assertTrue("Commit completed while caller dispatcher was paused",finished.await(30,TimeUnit.SECONDS))
            assertFalse("Caller still awaits the committed transaction",job.isCompleted)
            assertEquals(1,db.photoDao().getAll().size)
            job.cancel()
            while(!job.isCompleted && System.nanoTime()<deadline) queue.poll(100,TimeUnit.MILLISECONDS)?.run()
            assertTrue(job.isCompleted)
            val photo=db.photoDao().getAll().single()
            val file=File(photo.localPath)
            assertTrue("Committed photo must retain its file after cancellation",file.isFile)
            assertArrayEquals(byteArrayOf(1,2,3,4),file.readBytes())
        } finally { job.cancel();db.close();transactionThread.shutdownNow();queryThread.shutdownNow() }
    }

}
