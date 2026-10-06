package com.clinicalphotoarchive.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.clinicalphotoarchive.data.*

@Composable
internal fun PatientEditorDialog(patient: PatientEntity?,categories: List<CategoryEntity>,initialCategoryId: Long?,busy: Boolean,onDismiss: ()->Unit,onSave: (PatientEntity)->Unit) {
    var surname by rememberSaveable(patient?.id) { mutableStateOf(patient?.surname ?: "") }
    var first by rememberSaveable(patient?.id) { mutableStateOf(patient?.firstName ?: "") }
    var middle by rememberSaveable(patient?.id) { mutableStateOf(patient?.middleName ?: "") }
    var chart by rememberSaveable(patient?.id) { mutableStateOf(patient?.chartNumber ?: "") }
    var diagnosis by rememberSaveable(patient?.id) { mutableStateOf(patient?.diagnosis ?: "") }
    var note by rememberSaveable(patient?.id) { mutableStateOf(patient?.note ?: "") }
    var categoryId by rememberSaveable(patient?.id) { mutableStateOf(patient?.categoryId ?: initialCategoryId) }
    AlertDialog(onDismissRequest={if(!busy) onDismiss()},title={Text(if(patient==null) "Новый пациент" else "Редактировать пациента")},
        text={Column(Modifier.verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(6.dp)) {
            CategoryPicker(categories,categoryId,!busy) {categoryId=it}
            OutlinedTextField(surname,{surname=it},enabled=!busy,label={Text("Фамилия *")})
            OutlinedTextField(first,{first=it},enabled=!busy,label={Text("Имя")})
            OutlinedTextField(middle,{middle=it},enabled=!busy,label={Text("Отчество")})
            OutlinedTextField(chart,{chart=it},enabled=!busy,label={Text("№ карты")})
            OutlinedTextField(diagnosis,{diagnosis=it},enabled=!busy,label={Text("Диагноз / клинический случай")})
            OutlinedTextField(note,{note=it},enabled=!busy,label={Text("Заметка")})
        }},confirmButton={TextButton(enabled=!busy && surname.isNotBlank(),onClick={
            val base=patient ?: PatientEntity(surname="")
            onSave(base.copy(surname=surname.trim(),firstName=first.trim(),middleName=middle.trim(),chartNumber=chart.trim(),diagnosis=diagnosis.trim(),note=note.trim(),categoryId=categoryId))
        }) {Text("Сохранить")}},dismissButton={TextButton(enabled=!busy,onClick=onDismiss) {Text("Отмена")}})
}
