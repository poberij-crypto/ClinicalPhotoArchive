package com.clinicalphotoarchive.ui

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.clinicalphotoarchive.ClinicalArchiveApplication
import com.clinicalphotoarchive.data.PatientEntity
import com.clinicalphotoarchive.data.PhotoEntity
import com.clinicalphotoarchive.data.PhotoSection
import com.clinicalphotoarchive.util.BackupArchive
import com.clinicalphotoarchive.util.ImageFiles\nimport com.clinicalphotoarchive.util.LegacyArchiveImporter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class AppViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application as ClinicalArchiveApplication
    private val patientDao = app.database.patientDao()
    private val photoDao = app.database.photoDao()
    private val backupArchive = BackupArchive(application, app.database)\n    private val legacyArchiveImporter = LegacyArchiveImporter(application, app.database)

    val searchQuery = MutableStateFlow("")
    private val selectedPatientId = MutableStateFlow<Long?>(null)
    val section = MutableStateFlow(PhotoSection.BEFORE)

    val archiveBusy = MutableStateFlow(false)
    val archiveMessage = MutableStateFlow<String?>(null)

    val patients: StateFlow<List<PatientEntity>> = searchQuery
        .flatMapLatest { patientDao.observeAll(it.trim().lowercase(Locale.ROOT)) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val selectedPatient: StateFlow<PatientEntity?> = selectedPatientId
        .flatMapLatest { id -> if (id == null) flowOf(null) else patientDao.observeById(id) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val photos: StateFlow<List<PhotoEntity>> = combine(selectedPatientId, section) { id, s -> id to s }
        .flatMapLatest { (id, s) ->
            if (id == null) flowOf(emptyList()) else photoDao.observeSection(id, s.dbValue)
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun selectPatient(id: Long?) { selectedPatientId.value = id }
    fun selectSection(value: PhotoSection) { section.value = value }

    fun addPatient(surname: String, firstName: String, middleName: String, chartNumber: String, diagnosis: String, note: String) {
        if (surname.isBlank()) return
        viewModelScope.launch {
            val id = patientDao.insert(PatientEntity(
                surname = surname.trim(), firstName = firstName.trim(), middleName = middleName.trim(),
                chartNumber = chartNumber.trim(), diagnosis = diagnosis.trim(), note = note.trim(),
                searchKey = patientSearchKey(surname, firstName, middleName, chartNumber)
            ))
            selectedPatientId.value = id
        }
    }

    fun updatePatient(patient: PatientEntity) {
        viewModelScope.launch {
            patientDao.update(patient.copy(
                searchKey = patientSearchKey(patient.surname, patient.firstName, patient.middleName, patient.chartNumber),
                updatedAt = System.currentTimeMillis()
            ))
        }
    }

    private fun patientSearchKey(surname: String, firstName: String, middleName: String, chartNumber: String): String =
        listOf(surname, firstName, middleName, chartNumber).joinToString(" ").trim().lowercase(Locale.ROOT)

    fun deletePatient(patient: PatientEntity) {
        viewModelScope.launch {
            val files = photoDao.getForPatient(patient.id)
            patientDao.delete(patient)
            withContext(Dispatchers.IO) { files.forEach { ImageFiles.delete(it.localPath) } }
            if (selectedPatientId.value == patient.id) selectedPatientId.value = null
        }
    }

    fun createCameraFile() = ImageFiles.createCameraFile(getApplication())
    fun discardCameraFile(path: String) { viewModelScope.launch(Dispatchers.IO) { ImageFiles.delete(path) } }

    fun commitCameraFile(path: String) {
        val patientId = selectedPatientId.value ?: return
        val currentSection = section.value
        viewModelScope.launch {
            photoDao.insert(PhotoEntity(patientId = patientId, section = currentSection.dbValue, localPath = path))
        }
    }

    fun importPhotos(uris: List<Uri>) {
        val patientId = selectedPatientId.value ?: return
        val currentSection = section.value
        viewModelScope.launch {
            uris.forEach { uri ->
                val file = withContext(Dispatchers.IO) { ImageFiles.importUri(getApplication(), uri) }
                photoDao.insert(PhotoEntity(patientId = patientId, section = currentSection.dbValue, localPath = file.absolutePath))
            }
        }
    }

    fun updatePhotoDescription(photo: PhotoEntity, description: String) {
        viewModelScope.launch { photoDao.update(photo.copy(description = description.trim())) }
    }

    fun deletePhoto(photo: PhotoEntity) {
        viewModelScope.launch {
            photoDao.delete(photo)
            withContext(Dispatchers.IO) { ImageFiles.delete(photo.localPath) }
        }
    }

    fun backupFileName(): String {
        val stamp = SimpleDateFormat("yyyyMMdd_HHmm", Locale.ROOT).format(Date())
        return "ClinicalPhotoArchive_backup_$stamp.zip"
    }

    fun exportBackup(uri: Uri) {
        if (archiveBusy.value) return
        archiveBusy.value = true
        archiveMessage.value = null
        viewModelScope.launch {
            runCatching {
                withContext(Dispatchers.IO) { backupArchive.exportTo(uri) }
            }.onSuccess { result ->
                archiveMessage.value = "Резервная копия создана: ${result.patientCount} пациентов, ${result.photoCount} фотографий."
            }.onFailure { error ->
                archiveMessage.value = "Не удалось создать резервную копию: ${error.message ?: "неизвестная ошибка"}"
            }
            archiveBusy.value = false
        }
    }

    fun restoreBackup(uri: Uri) {
        if (archiveBusy.value) return
        archiveBusy.value = true
        archiveMessage.value = null
        viewModelScope.launch {
            runCatching {
                withContext(Dispatchers.IO) { backupArchive.restoreFrom(uri) }
            }.onSuccess { result ->
                selectedPatientId.value = null
                section.value = PhotoSection.BEFORE
                searchQuery.value = ""
                archiveMessage.value = "Архив восстановлен: ${result.patientCount} пациентов, ${result.photoCount} фотографий."
            }.onFailure { error ->
                archiveMessage.value = "Восстановление не выполнено: ${error.message ?: "неизвестная ошибка"}"
            }
            archiveBusy.value = false
        }
    }

    fun importLegacyArchive(uri: Uri) {
        if (archiveBusy.value) return
        archiveBusy.value = true
        archiveMessage.value = null
        viewModelScope.launch {
            runCatching {
                withContext(Dispatchers.IO) { legacyArchiveImporter.importFrom(uri) }
            }.onSuccess { result ->
                selectedPatientId.value = null
                section.value = PhotoSection.BEFORE
                searchQuery.value = ""
                archiveMessage.value =
                    "Импорт завершён: добавлено пациентов — ${result.patientsAdded}, " +
                        "объединено с существующими — ${result.patientsMerged}, " +
                        "добавлено фотографий — ${result.photosAdded}, " +
                        "пропущено повторных фотографий — ${result.photosSkipped}."
            }.onFailure { error ->
                archiveMessage.value =
                    "Импорт из старой версии не выполнен: ${error.message ?: "неизвестная ошибка"}"
            }
            archiveBusy.value = false
        }
    }

    fun clearArchiveMessage() {
        archiveMessage.value = null
    }
}
