package com.clinicalphotoarchive.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.FileProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.clinicalphotoarchive.data.PatientEntity
import com.clinicalphotoarchive.data.PhotoEntity
import com.clinicalphotoarchive.data.PhotoSection
import com.clinicalphotoarchive.util.ImageFiles

@Composable
fun ClinicalArchiveApp(vm: AppViewModel = viewModel()) {
    val patients by vm.patients.collectAsStateWithLifecycle()
    val patient by vm.selectedPatient.collectAsStateWithLifecycle()
    val photos by vm.photos.collectAsStateWithLifecycle()
    val section by vm.section.collectAsStateWithLifecycle()
    val query by vm.searchQuery.collectAsStateWithLifecycle()
    var addDialog by remember { mutableStateOf(false) }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val wide = maxWidth >= 700.dp
        if (wide) {
            Row(Modifier.fillMaxSize()) {
                PatientCatalog(
                    modifier = Modifier.width(360.dp).fillMaxHeight(),
                    patients = patients,
                    query = query,
                    onQuery = { vm.searchQuery.value = it },
                    onSelect = vm::selectPatient,
                    onAdd = { addDialog = true }
                )
                Box(Modifier.width(1.dp).fillMaxHeight())
                PatientCard(
                    modifier = Modifier.weight(1f),
                    patient = patient,
                    photos = photos,
                    section = section,
                    showBack = false,
                    onBack = {},
                    vm = vm
                )
            }
        } else {
            if (patient == null) {
                PatientCatalog(
                    modifier = Modifier.fillMaxSize(),
                    patients = patients,
                    query = query,
                    onQuery = { vm.searchQuery.value = it },
                    onSelect = vm::selectPatient,
                    onAdd = { addDialog = true }
                )
            } else {
                PatientCard(
                    modifier = Modifier.fillMaxSize(),
                    patient = patient,
                    photos = photos,
                    section = section,
                    showBack = true,
                    onBack = { vm.selectPatient(null) },
                    vm = vm
                )
            }
        }
    }

    if (addDialog) {
        AddPatientDialog(
            onDismiss = { addDialog = false },
            onSave = { s, f, m, chart, diagnosis, note ->
                vm.addPatient(s, f, m, chart, diagnosis, note)
                addDialog = false
            }
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PatientCatalog(
    modifier: Modifier,
    patients: List<PatientEntity>,
    query: String,
    onQuery: (String) -> Unit,
    onSelect: (Long) -> Unit,
    onAdd: () -> Unit
) {
    Scaffold(
        modifier = modifier,
        topBar = { TopAppBar(title = { Text("Пациенты") }) },
        floatingActionButton = {
            FloatingActionButton(onClick = onAdd) { Icon(Icons.Default.Add, contentDescription = "Добавить пациента") }
        }
    ) { padding ->
        Column(Modifier.padding(padding).padding(horizontal = 12.dp)) {
            OutlinedTextField(
                value = query,
                onValueChange = onQuery,
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Поиск по фамилии / № карты") },
                singleLine = true
            )
            Spacer(Modifier.height(8.dp))
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(patients, key = { it.id }) { p ->
                    Card(Modifier.fillMaxWidth().clickable { onSelect(p.id) }) {
                        Column(Modifier.padding(14.dp)) {
                            Text(p.displayName, style = MaterialTheme.typography.titleMedium)
                            if (p.chartNumber.isNotBlank()) Text("№ карты: ${p.chartNumber}")
                            if (p.diagnosis.isNotBlank()) Text(p.diagnosis, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
                item { Spacer(Modifier.height(80.dp)) }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PatientCard(
    modifier: Modifier,
    patient: PatientEntity?,
    photos: List<PhotoEntity>,
    section: PhotoSection,
    showBack: Boolean,
    onBack: () -> Unit,
    vm: AppViewModel
) {
    if (patient == null) {
        Box(modifier, contentAlignment = Alignment.Center) { Text("Выберите пациента") }
        return
    }
    val context = LocalContext.current
    var pendingCameraPath by remember { mutableStateOf<String?>(null) }
    var editPhoto by remember { mutableStateOf<PhotoEntity?>(null) }
    var fullScreenPhoto by remember { mutableStateOf<PhotoEntity?>(null) }
    var editPatient by remember(patient.id) { mutableStateOf(false) }

    val galleryLauncher = rememberLauncherForActivityResult(ActivityResultContracts.GetMultipleContents()) { uris ->
        if (uris.isNotEmpty()) vm.importPhotos(uris)
    }
    val cameraLauncher = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
        val path = pendingCameraPath
        if (path != null) {
            if (ok) vm.commitCameraFile(path) else vm.discardCameraFile(path)
        }
        pendingCameraPath = null
    }

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text(patient.displayName) },
                navigationIcon = if (showBack) {
                    { IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, contentDescription = "Назад") } }
                } else ({ }),
                actions = {
                    IconButton(onClick = { editPatient = true }) {
                        Icon(Icons.Default.Edit, contentDescription = "Редактировать пациента")
                    }
                }
            )
        }
    ) { padding ->
        Column(Modifier.padding(padding).padding(12.dp).fillMaxSize()) {
            if (patient.chartNumber.isNotBlank()) Text("№ карты: ${patient.chartNumber}")
            if (patient.diagnosis.isNotBlank()) Text("Диагноз: ${patient.diagnosis}")
            if (patient.note.isNotBlank()) Text(patient.note, style = MaterialTheme.typography.bodySmall)
            Spacer(Modifier.height(8.dp))

            TabRow(selectedTabIndex = PhotoSection.entries.indexOf(section)) {
                PhotoSection.entries.forEach { s ->
                    Tab(selected = s == section, onClick = { vm.selectSection(s) }, text = { Text(s.title) })
                }
            }
            Spacer(Modifier.height(8.dp))

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { galleryLauncher.launch("image/*") }) {
                    Icon(Icons.Default.PhotoLibrary, contentDescription = null)
                    Spacer(Modifier.width(6.dp))
                    Text("Галерея")
                }
                Button(onClick = {
                    val file = vm.createCameraFile()
                    pendingCameraPath = file.absolutePath
                    val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
                    cameraLauncher.launch(uri)
                }) {
                    Icon(Icons.Default.CameraAlt, contentDescription = null)
                    Spacer(Modifier.width(6.dp))
                    Text("Камера")
                }
            }
            Spacer(Modifier.height(8.dp))

            LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                items(photos, key = { it.id }) { photo ->
                    PhotoItem(
                        photo = photo,
                        onEdit = { editPhoto = photo },
                        onFullscreen = { fullScreenPhoto = photo },
                        onDelete = { vm.deletePhoto(photo) }
                    )
                }
                item { Spacer(Modifier.height(24.dp)) }
            }
        }
    }

    if (editPatient) {
        EditPatientDialog(
            patient = patient,
            onDismiss = { editPatient = false },
            onSave = { updated ->
                vm.updatePatient(updated)
                editPatient = false
            }
        )
    }

    editPhoto?.let { photo ->
        EditDescriptionDialog(
            photo = photo,
            onDismiss = { editPhoto = null },
            onSave = {
                vm.updatePhotoDescription(photo, it)
                editPhoto = null
            }
        )
    }

    fullScreenPhoto?.let { photo ->
        FullScreenPhotoDialog(photo = photo, onDismiss = { fullScreenPhoto = null })
    }
}

@Composable
private fun PhotoItem(
    photo: PhotoEntity,
    onEdit: () -> Unit,
    onFullscreen: () -> Unit,
    onDelete: () -> Unit
) {
    val bitmap = remember(photo.localPath) { ImageFiles.loadBitmap(photo.localPath, 1400)?.asImageBitmap() }
    Card(shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth()) {
        Column {
            if (bitmap != null) {
                Image(
                    bitmap = bitmap,
                    contentDescription = photo.description,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(260.dp)
                        .pointerInput(photo.id) {
                            detectTapGestures(onDoubleTap = { onFullscreen() })
                        },
                    contentScale = ContentScale.Crop
                )
            }
            Row(Modifier.fillMaxWidth().padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    if (photo.description.isBlank()) "Нажмите, чтобы добавить описание" else photo.description,
                    modifier = Modifier.weight(1f).clickable(onClick = onEdit)
                )
                IconButton(onClick = onDelete) { Icon(Icons.Default.Delete, contentDescription = "Удалить") }
            }
        }
    }
}

@Composable
private fun FullScreenPhotoDialog(photo: PhotoEntity, onDismiss: () -> Unit) {
    val bitmap = remember(photo.localPath) { ImageFiles.loadBitmap(photo.localPath, 4096)?.asImageBitmap() }
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            dismissOnBackPress = true,
            dismissOnClickOutside = false,
            usePlatformDefaultWidth = false
        )
    ) {
        Box(Modifier.fillMaxSize().background(Color.Black)) {
            if (bitmap != null) {
                Image(
                    bitmap = bitmap,
                    contentDescription = photo.description,
                    modifier = Modifier.fillMaxSize().padding(8.dp),
                    contentScale = ContentScale.Fit
                )
            }
            IconButton(
                onClick = onDismiss,
                modifier = Modifier.align(Alignment.TopEnd).padding(12.dp)
            ) {
                Icon(Icons.Default.Close, contentDescription = "Закрыть", tint = Color.White)
            }
            if (photo.description.isNotBlank()) {
                Text(
                    text = photo.description,
                    color = Color.White,
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .background(Color.Black.copy(alpha = 0.65f))
                        .padding(14.dp)
                )
            }
        }
    }
}

@Composable
private fun AddPatientDialog(
    onDismiss: () -> Unit,
    onSave: (String, String, String, String, String, String) -> Unit
) {
    var surname by remember { mutableStateOf("") }
    var firstName by remember { mutableStateOf("") }
    var middleName by remember { mutableStateOf("") }
    var chart by remember { mutableStateOf("") }
    var diagnosis by remember { mutableStateOf("") }
    var note by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Новый пациент") },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                OutlinedTextField(surname, { surname = it }, label = { Text("Фамилия *") })
                OutlinedTextField(firstName, { firstName = it }, label = { Text("Имя") })
                OutlinedTextField(middleName, { middleName = it }, label = { Text("Отчество") })
                OutlinedTextField(chart, { chart = it }, label = { Text("№ карты") })
                OutlinedTextField(diagnosis, { diagnosis = it }, label = { Text("Диагноз / клинический случай") })
                OutlinedTextField(note, { note = it }, label = { Text("Заметка") })
            }
        },
        confirmButton = {
            TextButton(enabled = surname.isNotBlank(), onClick = { onSave(surname, firstName, middleName, chart, diagnosis, note) }) {
                Text("Сохранить")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Отмена") } }
    )
}

@Composable
private fun EditPatientDialog(
    patient: PatientEntity,
    onDismiss: () -> Unit,
    onSave: (PatientEntity) -> Unit
) {
    var surname by remember(patient.id) { mutableStateOf(patient.surname) }
    var firstName by remember(patient.id) { mutableStateOf(patient.firstName) }
    var middleName by remember(patient.id) { mutableStateOf(patient.middleName) }
    var chart by remember(patient.id) { mutableStateOf(patient.chartNumber) }
    var diagnosis by remember(patient.id) { mutableStateOf(patient.diagnosis) }
    var note by remember(patient.id) { mutableStateOf(patient.note) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Редактировать пациента") },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                OutlinedTextField(surname, { surname = it }, label = { Text("Фамилия *") })
                OutlinedTextField(firstName, { firstName = it }, label = { Text("Имя") })
                OutlinedTextField(middleName, { middleName = it }, label = { Text("Отчество") })
                OutlinedTextField(chart, { chart = it }, label = { Text("№ карты") })
                OutlinedTextField(diagnosis, { diagnosis = it }, label = { Text("Диагноз / клинический случай") })
                OutlinedTextField(note, { note = it }, label = { Text("Заметка") })
            }
        },
        confirmButton = {
            TextButton(
                enabled = surname.isNotBlank(),
                onClick = {
                    onSave(
                        patient.copy(
                            surname = surname.trim(),
                            firstName = firstName.trim(),
                            middleName = middleName.trim(),
                            chartNumber = chart.trim(),
                            diagnosis = diagnosis.trim(),
                            note = note.trim()
                        )
                    )
                }
            ) { Text("Сохранить") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Отмена") } }
    )
}

@Composable
private fun EditDescriptionDialog(photo: PhotoEntity, onDismiss: () -> Unit, onSave: (String) -> Unit) {
    var text by remember(photo.id) { mutableStateOf(photo.description) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Описание фотографии") },
        text = { OutlinedTextField(text, { text = it }, modifier = Modifier.fillMaxWidth(), minLines = 3) },
        confirmButton = { TextButton(onClick = { onSave(text) }) { Text("Сохранить") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Отмена") } }
    )
}
