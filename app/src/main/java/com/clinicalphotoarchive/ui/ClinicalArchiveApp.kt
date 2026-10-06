package com.clinicalphotoarchive.ui

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.adaptive.ExperimentalMaterial3AdaptiveApi
import androidx.compose.material3.adaptive.layout.*
import androidx.compose.material3.adaptive.navigation.rememberListDetailPaneScaffoldNavigator
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel

@OptIn(ExperimentalMaterial3AdaptiveApi::class)
@Composable
fun ClinicalArchiveApp(vm: AppViewModel = viewModel()) {
    val selection by vm.catalogSelection.collectAsStateWithLifecycle()
    val id by vm.selectedPatientId.collectAsStateWithLifecycle()
    val patient by vm.selectedPatient.collectAsStateWithLifecycle()
    val categories by vm.categories.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()
    val viewer by vm.fullScreenPhoto.collectAsStateWithLifecycle()
    var addPatient by rememberSaveable { mutableStateOf(false) }
    val context=LocalContext.current
    // Launchers remain mounted while the catalogue/patient panes change.
    val gallery=rememberLauncherForActivityResult(ActivityResultContracts.GetMultipleContents(),vm::importPhotos)
    val camera=rememberLauncherForActivityResult(ActivityResultContracts.TakePicture(),vm::completeCamera)
    val navigator=rememberListDetailPaneScaffoldNavigator<Long>()
    LaunchedEffect(id) {
        val role=if(id==null) ListDetailPaneScaffoldRole.List else ListDetailPaneScaffoldRole.Detail
        if(navigator.currentDestination?.pane!=role || navigator.currentDestination?.contentKey!=id) navigator.navigateTo(role,id)
    }
    BackHandler(enabled=id!=null || selection!=CatalogSelection.Root) { vm.navigateBack() }
    Box(Modifier.fillMaxSize()) {
        ListDetailPaneScaffold(directive=navigator.scaffoldDirective,value=navigator.scaffoldValue,
            listPane={AnimatedPane {
                if(selection==CatalogSelection.Root) CategoryCatalog(vm) {addPatient=true}
                else PatientCatalog(vm) {addPatient=true}
            }},detailPane={AnimatedPane {
                PatientCard(vm,onGallery={if(vm.prepareGallery()) {
                    try { gallery.launch("image/*") } catch(t: Exception) { vm.importPhotos(emptyList());vm.reportError(t) }
                }},onCamera={
                    try {
                        val file=vm.createCameraFile()
                        camera.launch(FileProvider.getUriForFile(context,"${context.packageName}.files",file))
                    } catch(t: Exception) { vm.completeCamera(false);vm.reportError(t) }
                })
            }})
        BackupControls(vm,Modifier.align(Alignment.BottomStart).navigationBarsPadding().padding(start=16.dp,bottom=16.dp))
    }
    if(addPatient) PatientEditorDialog(null,categories.map {it.category},vm.currentCategoryId(),busy,{addPatient=false}) { p ->
        vm.addPatient(p.surname,p.firstName,p.middleName,p.chartNumber,p.diagnosis,p.note,p.categoryId) {addPatient=false}
    }
    viewer?.let { ZoomablePhotoViewer(it,vm::closePhoto) }
}
