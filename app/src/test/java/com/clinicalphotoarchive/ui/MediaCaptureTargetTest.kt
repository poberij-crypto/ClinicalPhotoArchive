package com.clinicalphotoarchive.ui

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.clinicalphotoarchive.data.*
import com.clinicalphotoarchive.util.*
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[28])
class MediaCaptureTargetTest {
    private val context=ApplicationProvider.getApplicationContext<Context>()
    @Test fun capturedTargetKeepsOriginalPatientAndSection()=runBlocking {
        val db=Room.inMemoryDatabaseBuilder(context,ClinicalDatabase::class.java).build()
        try {
            val id=db.patientDao().insert(PatientEntity(surname="Первый",createdAt=10))
            val target=MediaCaptureTarget(id,PhotoSection.XRAY,10)
            db.patientDao().insert(PatientEntity(surname="Второй"))
            val path=File(ImageFiles.imageDir(context),"camera-target-test.jpg").apply { writeBytes(byteArrayOf(1)) }
            PatientMediaStore(context,db).commitCamera(target,path.absolutePath)
            val photo=db.photoDao().getAll().single()
            assertEquals(id,photo.patientId);assertEquals("xray",photo.section)
            path.delete();Unit
        } finally { db.close() }
    }
    @Test fun deletedOrReplacedPatientDoesNotReceiveCameraFileAndFileIsCleaned()=runBlocking {
        val db=Room.inMemoryDatabaseBuilder(context,ClinicalDatabase::class.java).build()
        try {
            val id=db.patientDao().insert(PatientEntity(surname="Старый",createdAt=10))
            val target=MediaCaptureTarget(id,PhotoSection.XRAY,10)
            db.patientDao().deleteAll()
            db.patientDao().insert(PatientEntity(id=id,surname="Другой",createdAt=20))
            val path=File(ImageFiles.imageDir(context),"camera-deleted-test.jpg").apply { writeBytes(byteArrayOf(1)) }
            assertThrows(IllegalArgumentException::class.java) { runBlocking { PatientMediaStore(context,db).commitCamera(target,path.absolutePath) } }
            assertTrue(db.photoDao().getAll().isEmpty());assertFalse(path.exists())
        } finally { db.close() }
    }
}
