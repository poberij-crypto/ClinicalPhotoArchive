package com.clinicalphotoarchive.ui

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import androidx.room.withTransaction
import com.clinicalphotoarchive.ClinicalArchiveApplication
import com.clinicalphotoarchive.data.*
import com.clinicalphotoarchive.util.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalCoroutinesApi::class)
class AppViewModel(application: Application,private val saved: SavedStateHandle = SavedStateHandle()): AndroidViewModel(application) {
    private val app=application as ClinicalArchiveApplication
    private val db=app.database
    private val patientDao=db.patientDao()
    private val photoDao=db.photoDao()
    private val repository=CatalogRepository(db)
    private val backup=BackupArchive(app,db,app.archiveOperations,app.archiveJournal)
    private val legacy=LegacyArchiveImporter(app,db,app.archiveOperations,app.archiveJournal)
    private val media=PatientMediaStore(app,db,app.archiveOperations,app.archiveJournal)
    val searchQuery=MutableStateFlow(saved.get<String>("query") ?: "")
    private val patientId=saved.getStateFlow<Long?>("patient",null)
    private val categoryKey=saved.getStateFlow("category",-1L)
    val section=MutableStateFlow(PhotoSection.entries.firstOrNull { it.dbValue==saved.get<String>("section") } ?: PhotoSection.BEFORE)
    private val viewerId=saved.getStateFlow<Long?>("viewer",null)
    private val workBusy=MutableStateFlow(false)
    val archiveBusy=app.archiveOperations.archiveBusy
    val archiveMessage=MutableStateFlow<String?>(null)
    private val cameraId=saved.getStateFlow<Long?>("camera.patient",null)
    private val galleryId=saved.getStateFlow<Long?>("gallery.patient",null)
    val mediaPending=combine(cameraId,galleryId) { a,b -> a!=null || b!=null }.stateIn(viewModelScope,SharingStarted.Eagerly,cameraId.value!=null || galleryId.value!=null)
    val busy=combine(workBusy,archiveBusy,mediaPending) { a,b,c -> a||b||c }.stateIn(viewModelScope,SharingStarted.Eagerly,false)
    val catalogSelection=categoryKey.map { when { it<0 -> CatalogSelection.Root;it==0L -> CatalogSelection.Uncategorized;else -> CatalogSelection.Category(it) } }
        .stateIn(viewModelScope,SharingStarted.Eagerly,CatalogSelection.Root)
    val categories=db.categoryDao().observeWithCounts().onEach { items ->
        if(categoryKey.value>0 && items.none { it.category.id==categoryKey.value }) selectCategory(null)
    }.stateIn(viewModelScope,SharingStarted.WhileSubscribed(5000),emptyList())
    val uncategorizedCount=patientDao.observeUncategorizedCount().stateIn(viewModelScope,SharingStarted.WhileSubscribed(5000),0)
    val patients=combine(categoryKey,searchQuery) { key,query -> key to query.trim().lowercase(Locale.ROOT) }.flatMapLatest { (key,query) ->
        if(key<0) flowOf(emptyList()) else patientDao.observeCategory(key.takeIf { it>0 },query)
    }.stateIn(viewModelScope,SharingStarted.WhileSubscribed(5000),emptyList())
    val selectedPatient=patientId.flatMapLatest { id -> if(id==null) flowOf(null) else patientDao.observeById(id).onEach {
        if(it==null) { saved["patient"]=null;saved["viewer"]=null }
    } }.stateIn(viewModelScope,SharingStarted.WhileSubscribed(5000),null)
    val photos=combine(patientId,section) { id,s -> id to s }.flatMapLatest { (id,s) ->
        if(id==null) flowOf(emptyList()) else photoDao.observeSection(id,s.dbValue)
    }.stateIn(viewModelScope,SharingStarted.WhileSubscribed(5000),emptyList())
    val fullScreenPhoto=viewerId.flatMapLatest { id -> if(id==null) flowOf(null) else photoDao.observeById(id).onEach { if(it==null) saved["viewer"]=null } }
        .stateIn(viewModelScope,SharingStarted.WhileSubscribed(5000),null)
    val selectedPatientId: StateFlow<Long?> = patientId
    init {
        work { app.archiveOperations.withMutation {} }
        viewModelScope.launch { searchQuery.collect { saved["query"]=it } }
        viewModelScope.launch { section.collect { saved["section"]=it.dbValue } }
    }
    fun selectPatient(id: Long?) { saved["patient"]=id;closePhoto() }
    fun selectSection(value: PhotoSection) { section.value=value;closePhoto() }
    fun selectCategory(id: Long?) { saved["category"]=id ?: 0L;selectPatient(null) }
    fun openCatalogRoot() { saved["category"]=-1L;selectPatient(null) }
    fun openPhoto(id: Long) { saved["viewer"]=id }
    fun closePhoto() { saved["viewer"]=null }
    fun navigateBack() { when { viewerId.value!=null -> closePhoto();patientId.value!=null -> selectPatient(null);else -> openCatalogRoot() } }
    fun currentCategoryId(): Long? = categoryKey.value.takeIf { it>0 }
    fun categoryTitle(): String = if(categoryKey.value<=0) "Без категории" else categories.value.firstOrNull { it.category.id==categoryKey.value }?.category?.name ?: "Раздел"
    private fun work(onSuccess: ()->Unit = {},block: suspend ()->Unit) {
        if(workBusy.value || archiveBusy.value) return
        workBusy.value=true;archiveMessage.value=null
        viewModelScope.launch {
            try { withContext(Dispatchers.IO) { block() };onSuccess() }
            catch(c: CancellationException) { throw c }
            catch(t: Exception) { archiveMessage.value=t.message ?: "Не удалось выполнить операцию" }
            finally { workBusy.value=false }
        }
    }
    private fun mutation(onSuccess: ()->Unit = {},block: suspend ()->Unit) = work(onSuccess) { app.archiveOperations.withMutation { block() } }
    fun createCategory(name: String,onSuccess: ()->Unit = {}) = mutation(onSuccess) { repository.createCategory(name) }
    fun renameCategory(id: Long,name: String,onSuccess: ()->Unit = {}) = mutation(onSuccess) { repository.renameCategory(id,name) }
    fun deleteCategory(id: Long,onSuccess: ()->Unit = {}) = mutation({ if(categoryKey.value==id) selectCategory(null);onSuccess() }) { repository.deleteCategory(id) }
    fun addPatient(surname: String,firstName: String,middleName: String,chartNumber: String,diagnosis: String,note: String,
        categoryId: Long? = currentCategoryId(),onSuccess: ()->Unit = {}) {
        if(surname.isBlank()) return
        var id=0L
        mutation({ saved["category"]=categoryId ?: 0L;selectPatient(id);onSuccess() }) {
            db.withTransaction {
                if(categoryId!=null) requireNotNull(db.categoryDao().getById(categoryId)) { "Раздел уже удалён" }
                id=patientDao.insert(PatientEntity(surname=surname.trim(),firstName=firstName.trim(),middleName=middleName.trim(),
                    chartNumber=chartNumber.trim(),diagnosis=diagnosis.trim(),note=note.trim(),categoryId=categoryId,
                    searchKey=searchKey(surname,firstName,middleName,chartNumber)))
            }
        }
    }
    fun updatePatient(patient: PatientEntity,onSuccess: ()->Unit = {}) = mutation({
        if(patient.categoryId!=currentCategoryId()) selectPatient(null)
        onSuccess()
    }) {
        db.withTransaction {
            requireNotNull(patientDao.getById(patient.id)) { "Пациент уже удалён" }
            require(patient.surname.isNotBlank()) { "Введите фамилию пациента" }
            if(patient.categoryId!=null) requireNotNull(db.categoryDao().getById(patient.categoryId)) { "Раздел уже удалён" }
            patientDao.update(patient.copy(searchKey=searchKey(patient.surname,patient.firstName,patient.middleName,patient.chartNumber),updatedAt=System.currentTimeMillis()))
        }
    }
    fun deletePatient(patient: PatientEntity) = mutation({ selectPatient(null) }) {
        val current=requireNotNull(patientDao.getById(patient.id)) { "Пациент уже удалён" }
        app.archiveJournal.registerFiles(photoDao.getForPatient(patient.id).map { it.localPath })
        try { patientDao.delete(current) } finally { withContext(NonCancellable) { app.archiveJournal.recover(db) } }
    }
    fun updatePhotoDescription(photo: PhotoEntity,description: String) = mutation {
        val current=requireNotNull(photoDao.getById(photo.id)) { "Снимок уже удалён" }
        photoDao.update(current.copy(description=description.trim()))
    }
    fun deletePhoto(photo: PhotoEntity) = mutation {
        val current=requireNotNull(photoDao.getById(photo.id)) { "Снимок уже удалён" }
        app.archiveJournal.registerFile(current.localPath)
        try { photoDao.delete(current) } finally { withContext(NonCancellable) { app.archiveJournal.recover(db) } }
    }
    private fun rememberTarget(prefix: String): Boolean {
        if(workBusy.value || archiveBusy.value || cameraId.value!=null || galleryId.value!=null) return false
        val p=selectedPatient.value ?: return false
        saved["$prefix.patient"]=p.id;saved["$prefix.created"]=p.createdAt;saved["$prefix.section"]=section.value.dbValue
        return true
    }
    private fun target(prefix: String): MediaCaptureTarget? {
        val id=saved.get<Long>("$prefix.patient") ?: return null
        val s=PhotoSection.entries.firstOrNull { it.dbValue==saved.get<String>("$prefix.section") } ?: return null
        return MediaCaptureTarget(id,s,saved.get<Long>("$prefix.created") ?: return null)
    }
    private fun clearTarget(prefix: String) { saved["$prefix.patient"]=null;saved["$prefix.created"]=null;saved["$prefix.section"]=null }
    fun prepareGallery(): Boolean = rememberTarget("gallery")
    fun createCameraFile(): File {
        check(rememberTarget("camera")) { "Не удалось начать съёмку" }
        return ImageFiles.createCameraFile(app).also { saved["camera.path"]=it.absolutePath }
    }
    fun completeCamera(ok: Boolean) {
        val t=target("camera");val path=saved.get<String>("camera.path")
        clearTarget("camera");saved["camera.path"]=null
        if(path==null) return
        if(!ok || t==null) discardCameraFile(path) else work { media.commitCamera(t,path) }
    }
    fun commitCameraFile(path: String) { if(saved.get<String>("camera.path")==path) completeCamera(true) else discardCameraFile(path) }
    fun discardCameraFile(path: String) { viewModelScope.launch(Dispatchers.IO) { ImageFiles.delete(path) } }
    fun importPhotos(uris: List<Uri>) {
        val t=target("gallery");clearTarget("gallery")
        if(t!=null && uris.isNotEmpty()) work { media.importPhotos(t,uris) }
    }
    fun reportError(t: Exception) { archiveMessage.value=t.message ?: "Не удалось выполнить операцию" }
    fun backupFileName(): String = "ClinicalPhotoArchive_backup_${SimpleDateFormat("yyyyMMdd_HHmm",Locale.ROOT).format(Date())}.zip"
    private fun resetAfterRestore() { openCatalogRoot();section.value=PhotoSection.BEFORE;searchQuery.value="" }
    private fun archive(block: suspend ()->Unit) {
        if(cameraId.value!=null || galleryId.value!=null) { archiveMessage.value="Завершите добавление снимков перед обработкой архива";return }
        work(block=block)
    }
    fun exportBackup(uri: Uri) = archive {
        val result=backup.exportTo(uri)
        withContext(Dispatchers.Main) { archiveMessage.value="Резервная копия создана: ${result.patientCount} пациентов, ${result.photoCount} снимков." }
    }
    fun restoreBackup(uri: Uri) = archive {
        val result=backup.restoreFrom(uri)
        withContext(Dispatchers.Main) { resetAfterRestore();archiveMessage.value="Архив восстановлен: ${result.patientCount} пациентов, ${result.photoCount} снимков." }
    }
    fun importLegacyArchive(uri: Uri) = archive {
        val result=legacy.importFrom(uri)
        withContext(Dispatchers.Main) { resetAfterRestore();archiveMessage.value="Импорт завершён: добавлено пациентов — ${result.patientsAdded}, объединено — ${result.patientsMerged}, снимков — ${result.photosAdded}." }
    }
    fun clearArchiveMessage() { archiveMessage.value=null }
    private fun searchKey(vararg parts: String): String = parts.joinToString(" ").trim().lowercase(Locale.ROOT)
}
