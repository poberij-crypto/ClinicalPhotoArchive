package com.clinicalphotoarchive.util

import android.content.Context
import android.net.Uri
import com.clinicalphotoarchive.data.*
import com.clinicalphotoarchive.ui.MediaCaptureTarget
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

class PatientMediaStore(
    private val context: Context,
    private val database: ClinicalDatabase,
    private val operations: ArchiveOperationCoordinator = ArchiveOperationCoordinator(),
    private val journal: ArchiveRecoveryJournal = ArchiveRecoveryJournal(context)
) {
    private suspend fun validate(target: MediaCaptureTarget) {
        val patient=database.patientDao().getById(target.patientId)
        require(patient!=null && patient.createdAt==target.patientCreatedAt) { "Пациент был удалён или архив заменён. Снимок не добавлен." }
    }
    suspend fun commitCamera(target: MediaCaptureTarget,path: String) = operations.withMutation {
        val file=File(path)
        journal.registerFile(path)
        try {
            validate(target)
            require(file.isFile && file.length()>0) { "Камера не сохранила снимок" }
            database.photoDao().insert(PhotoEntity(patientId=target.patientId,section=target.section.dbValue,localPath=file.absolutePath))
        } finally { withContext(NonCancellable) { journal.recover(database) } }
    }
    suspend fun importPhotos(target: MediaCaptureTarget,uris: List<Uri>) = operations.withMutation {
        try {
            validate(target)
            val files=uris.map { it to File(ImageFiles.imageDir(context),"IMPORT_${UUID.randomUUID()}.jpg") }
            journal.registerFiles(files.map { it.second.absolutePath })
            for((uri,file) in files) {
                context.contentResolver.openInputStream(uri).use { input ->
                    requireNotNull(input) { "Не удалось открыть изображение" }
                    file.outputStream().use { out -> input.copyTo(out);out.fd.sync() }
                }
                require(file.length()>0) { "Пустой файл изображения" }
                database.photoDao().insert(PhotoEntity(patientId=target.patientId,section=target.section.dbValue,localPath=file.absolutePath))
            }
        } finally { withContext(NonCancellable) { journal.recover(database) } }
    }
}
