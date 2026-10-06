package com.clinicalphotoarchive.ui

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.clinicalphotoarchive.data.*
import com.clinicalphotoarchive.util.ImageFiles
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun PatientCatalog(vm: AppViewModel,onAdd: ()->Unit) {
    val patients by vm.patients.collectAsStateWithLifecycle()
    val query by vm.searchQuery.collectAsStateWithLifecycle()
    val categories by vm.categories.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()
    Scaffold(topBar={TopAppBar(title={Text(vm.categoryTitle())},navigationIcon={
        IconButton(onClick=vm::openCatalogRoot) {Icon(Icons.AutoMirrored.Filled.ArrowBack,"Каталог разделов")}
    })},floatingActionButton={FloatingActionButton(onClick={if(!busy) onAdd()}) {Icon(Icons.Default.Add,"Добавить пациента")}}) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).padding(horizontal=12.dp)) {
            OutlinedTextField(query,{vm.searchQuery.value=it},Modifier.fillMaxWidth(),label={Text("Поиск по фамилии / № карты")},singleLine=true)
            Spacer(Modifier.height(8.dp))
            LazyColumn(verticalArrangement=Arrangement.spacedBy(8.dp)) {
                items(patients,key={it.id}) { p -> Card(Modifier.fillMaxWidth().clickable {vm.selectPatient(p.id)}) {
                    Column(Modifier.padding(14.dp)) {
                        Text(p.displayName,style=MaterialTheme.typography.titleMedium)
                        if(p.chartNumber.isNotBlank()) Text("№ карты: ${p.chartNumber}")
                        if(p.diagnosis.isNotBlank()) Text(p.diagnosis,style=MaterialTheme.typography.bodySmall)
                    }
                } }
                if(patients.isEmpty()) item {Text(if(query.isBlank()) "В этом разделе пока нет пациентов" else "Пациенты не найдены",Modifier.padding(16.dp))}
                item {Spacer(Modifier.height(100.dp))}
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun PatientCard(vm: AppViewModel,onGallery: ()->Unit,onCamera: ()->Unit) {
    val patient by vm.selectedPatient.collectAsStateWithLifecycle()
    val photos by vm.photos.collectAsStateWithLifecycle()
    val section by vm.section.collectAsStateWithLifecycle()
    val categories by vm.categories.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()
    val p=patient
    if(p==null) {Box(Modifier.fillMaxSize(),contentAlignment=Alignment.Center) {Text("Выберите пациента")};return}
    var editing by rememberSaveable(p.id) {mutableStateOf(false)}
    var deleting by rememberSaveable(p.id) {mutableStateOf(false)}
    var editPhotoId by rememberSaveable(p.id) {mutableStateOf<Long?>(null)}
    var deletePhotoId by rememberSaveable(p.id) {mutableStateOf<Long?>(null)}
    Scaffold(topBar={TopAppBar(title={Text(p.displayName)},navigationIcon={
        IconButton(onClick={vm.selectPatient(null)}) {Icon(Icons.AutoMirrored.Filled.ArrowBack,"Назад")}
    },actions={
        IconButton(enabled=!busy,onClick={editing=true}) {Icon(Icons.Default.Edit,"Редактировать пациента")}
        IconButton(enabled=!busy,onClick={deleting=true}) {Icon(Icons.Default.Delete,"Удалить карточку пациента")}
    })}) { padding ->
        // Metadata is part of the scrolling content: large fonts and landscape
        // never push the media controls permanently beyond the viewport.
        LazyColumn(Modifier.fillMaxSize().padding(padding).padding(horizontal=12.dp),verticalArrangement=Arrangement.spacedBy(10.dp)) {
            item {
                Text("Раздел: ${if(p.categoryId==null) "Без категории" else categories.firstOrNull {it.category.id==p.categoryId}?.category?.name ?: "Раздел"}")
                if(p.chartNumber.isNotBlank()) Text("№ карты: ${p.chartNumber}")
                if(p.diagnosis.isNotBlank()) Text("Диагноз: ${p.diagnosis}")
                if(p.note.isNotBlank()) Text(p.note,style=MaterialTheme.typography.bodySmall)
            }
            item {ScrollableTabRow(selectedTabIndex=PhotoSection.entries.indexOf(section),edgePadding=0.dp) {
                PhotoSection.entries.forEach { s -> Tab(selected=s==section,onClick={vm.selectSection(s)},text={Text(s.title)}) }
            }}
            item {FlowRow(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                Button(enabled=!busy,onClick=onGallery) {Icon(Icons.Default.PhotoLibrary,null);Spacer(Modifier.width(6.dp));Text("Галерея")}
                Button(enabled=!busy,onClick=onCamera) {Icon(Icons.Default.CameraAlt,null);Spacer(Modifier.width(6.dp));Text("Камера")}
            }}
            items(photos,key={it.id}) { photo -> PhotoItem(photo,busy,{editPhotoId=photo.id},{vm.openPhoto(photo.id)},{deletePhotoId=photo.id}) }
            if(photos.isEmpty()) item {Text("В разделе «${section.title}» пока нет снимков",Modifier.padding(16.dp))}
            item {Spacer(Modifier.height(100.dp))}
        }
    }
    if(editing) PatientEditorDialog(p,categories.map {it.category},p.categoryId,busy,{editing=false}) {vm.updatePatient(it) {editing=false}}
    if(deleting) AlertDialog(onDismissRequest={if(!busy) deleting=false},title={Text("Удалить карточку пациента?")},
        text={Text("Карточка «${p.displayName}» и все связанные снимки будут удалены. При необходимости сначала создайте резервную копию через «Архив».")},
        confirmButton={TextButton(enabled=!busy,onClick={deleting=false;vm.deletePatient(p)}) {Text("Удалить")}},
        dismissButton={TextButton(enabled=!busy,onClick={deleting=false}) {Text("Отмена")}})
    photos.firstOrNull {it.id==editPhotoId}?.let { photo ->
        var description by rememberSaveable(photo.id) {mutableStateOf(photo.description)}
        AlertDialog(onDismissRequest={if(!busy) editPhotoId=null},title={Text("Описание фотографии")},
            text={OutlinedTextField(description,{description=it},Modifier.fillMaxWidth(),enabled=!busy,minLines=3)},
            confirmButton={TextButton(enabled=!busy,onClick={vm.updatePhotoDescription(photo,description);editPhotoId=null}) {Text("Сохранить")}},
            dismissButton={TextButton(enabled=!busy,onClick={editPhotoId=null}) {Text("Отмена")}})
    }
    photos.firstOrNull {it.id==deletePhotoId}?.let { photo ->
        AlertDialog(onDismissRequest={deletePhotoId=null},title={Text("Удалить снимок?")},text={Text("Снимок будет удалён с устройства. При необходимости сначала создайте резервную копию.")},
            confirmButton={TextButton(enabled=!busy,onClick={deletePhotoId=null;vm.deletePhoto(photo)}) {Text("Удалить")}},
            dismissButton={TextButton(onClick={deletePhotoId=null}) {Text("Отмена")}})
    }
}
@Composable
private fun PhotoItem(photo: PhotoEntity,busy: Boolean,onEdit: ()->Unit,onFullscreen: ()->Unit,onDelete: ()->Unit) {
    val bitmap by produceState<ImageBitmap?>(null,photo.localPath) {
        value=withContext(Dispatchers.IO) {ImageFiles.loadBitmap(photo.localPath,1400)?.asImageBitmap()}
    }
    Card(Modifier.fillMaxWidth()) {
        Column {
            val image=bitmap
            if(image!=null) Image(image,photo.description,Modifier.fillMaxWidth().height(260.dp).clickable(onClick=onFullscreen),contentScale=ContentScale.Crop)
            else Text("Снимок загружается или недоступен",Modifier.padding(16.dp).clickable(onClick=onFullscreen))
            Row(Modifier.fillMaxWidth().padding(10.dp),verticalAlignment=Alignment.CenterVertically) {
                Text(photo.description.ifBlank {"Нажмите, чтобы добавить описание"},Modifier.weight(1f).clickable(enabled=!busy,onClick=onEdit))
                IconButton(enabled=!busy,onClick=onDelete) {Icon(Icons.Default.Delete,"Удалить снимок")}
            }
        }
    }
}
