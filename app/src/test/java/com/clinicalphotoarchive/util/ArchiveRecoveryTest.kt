package com.clinicalphotoarchive.util

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.clinicalphotoarchive.data.*
import kotlinx.coroutines.*
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[28])
class ArchiveRecoveryTest {
    private val context=ApplicationProvider.getApplicationContext<Context>()
    @Test fun recoveryDeletesOnlyJournalFilesWithoutDatabaseReferences()=runBlocking {
        val db=Room.inMemoryDatabaseBuilder(context,ClinicalDatabase::class.java).build()
        try {
            val dir=ImageFiles.imageDir(context)
            val keep=File(dir,"journal-keep.jpg").apply { writeBytes(byteArrayOf(7)) }
            val remove=File(dir,"journal-remove.jpg").apply { writeBytes(byteArrayOf(8)) }
            val unrelated=File(dir,"unrelated.jpg").apply { writeBytes(byteArrayOf(9)) }
            val id=db.patientDao().insert(PatientEntity(surname="Тест"))
            db.photoDao().insert(PhotoEntity(patientId=id,section="before",localPath=keep.absolutePath))
            val journal=ArchiveRecoveryJournal(context)
            journal.registerFile(keep.absolutePath);journal.registerFile(remove.absolutePath)
            ArchiveRecoveryJournal(context).recover(db)
            assertTrue(keep.exists());assertFalse(remove.exists());assertTrue(unrelated.exists())
            keep.delete();unrelated.delete();Unit
        } finally { db.close() }
    }
    @Test fun coordinatorDoesNotLetMutationChangeExportSnapshot()=runBlocking {
        val operations=ArchiveOperationCoordinator()
        val entered=CompletableDeferred<Unit>();val release=CompletableDeferred<Unit>()
        var mutated=false
        val archive=launch { operations.withArchive { entered.complete(Unit);release.await();assertFalse(mutated) } }
        entered.await()
        val mutation=launch { operations.withMutation { mutated=true } }
        delay(50);assertFalse(mutated);assertTrue(operations.archiveBusy.value)
        release.complete(Unit);archive.join();mutation.join()
        assertTrue(mutated);assertFalse(operations.archiveBusy.value)
    }
}
