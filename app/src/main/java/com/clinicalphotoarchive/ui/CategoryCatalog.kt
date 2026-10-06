package com.clinicalphotoarchive.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.dp
import com.clinicalphotoarchive.data.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun CategoryCatalog(vm: AppViewModel,onAddPatient: ()->Unit) {
    val categories by vm.categories.collectAsState()
    val uncat by vm.uncategorizedCount.collectAsState()
    val busy by vm.busy.collectAsState()
    var showName by rememberSaveable { mutableStateOf(false) }
    var renameId by rememberSaveable { mutableStateOf<Long?>(null) }
    var deleteId by rememberSaveable { mutableStateOf<Long?>(null) }
    Scaffold(topBar={ TopAppBar(title={ Text("Каталог разделов") },actions={
        IconButton(onClick=onAddPatient,enabled=!busy) { Icon(Icons.Default.PersonAdd,"Добавить пациента") }
    }) },floatingActionButton={
        FloatingActionButton(onClick={ if(!busy) { renameId=null;showName=true } }) { Icon(Icons.Default.Add,"Добавить раздел") }
    }) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding).padding(horizontal=12.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
            item { CategoryRow("Без категории",uncat,!busy,{vm.selectCategory(null)}) }
            items(categories,key={it.category.id}) { item ->
                CategoryRow(item.category.name,item.patientCount,!busy,{vm.selectCategory(item.category.id)},
                    onRename={renameId=item.category.id;showName=true},onDelete={deleteId=item.category.id})
            }
            if(categories.isEmpty()) item { Text("Создайте нозологические разделы для организации каталога.",Modifier.padding(16.dp)) }
            item { Spacer(Modifier.height(100.dp)) }
        }
    }
    if(showName) {
        CategoryNameDialog(categories.firstOrNull { it.category.id==renameId }?.category,busy,{showName=false}) { name ->
            val id=renameId
            if(id==null) vm.createCategory(name) { showName=false }
            else vm.renameCategory(id,name) { showName=false }
        }
    }
    categories.firstOrNull { it.category.id==deleteId }?.let { item ->
        AlertDialog(onDismissRequest={if(!busy) deleteId=null},title={ Text("Удалить «${item.category.name}»?") },
            text={ Text("Пациентов в разделе: ${item.patientCount}. Они перейдут в «Без категории». Пациенты и все их снимки сохранятся.") },
            confirmButton={ TextButton(enabled=!busy,onClick={vm.deleteCategory(item.category.id) {deleteId=null}}) {Text("Удалить")} },
            dismissButton={ TextButton(enabled=!busy,onClick={deleteId=null}) {Text("Отмена")} })
    }
}

@Composable
private fun CategoryRow(name: String,count: Int,enabled: Boolean,onOpen: ()->Unit,onRename: (()->Unit)?=null,onDelete: (()->Unit)?=null) {
    Card(Modifier.fillMaxWidth()) {
        Row(verticalAlignment=Alignment.CenterVertically) {
            Column(Modifier.weight(1f).clickable(enabled=enabled,onClick=onOpen).padding(16.dp)) {
                Text(name,style=MaterialTheme.typography.titleMedium)
                Text("Пациентов: $count",style=MaterialTheme.typography.bodyMedium)
            }
            if(onRename!=null) IconButton(enabled=enabled,onClick=onRename) { Icon(Icons.Default.Edit,"Переименовать $name") }
            if(onDelete!=null) IconButton(enabled=enabled,onClick=onDelete) { Icon(Icons.Default.Delete,"Удалить раздел $name") }
        }
    }
}
@Composable
private fun CategoryNameDialog(category: CategoryEntity?,busy: Boolean,onDismiss: ()->Unit,onSave: (String)->Unit) {
    var name by rememberSaveable(category?.id) { mutableStateOf(category?.name ?: "") }
    AlertDialog(onDismissRequest={if(!busy) onDismiss()},title={Text(if(category==null) "Новый раздел" else "Переименовать раздел")},
        text={OutlinedTextField(name,{name=it},label={Text("Название раздела")},enabled=!busy,singleLine=true)},
        confirmButton={TextButton(enabled=!busy && name.isNotBlank(),onClick={onSave(name)}) {Text("Сохранить")}},
        dismissButton={TextButton(enabled=!busy,onClick=onDismiss) {Text("Отмена")}})
}

@Composable
internal fun CategoryPicker(categories: List<CategoryEntity>,id: Long?,enabled: Boolean,onSelect: (Long?)->Unit) {
    var expanded by remember { mutableStateOf(false) }
    Column {
        Text("Нозологический раздел",style=MaterialTheme.typography.labelLarge)
        Box {
            OutlinedButton(enabled=enabled,onClick={expanded=true},modifier=Modifier.fillMaxWidth()) {
                Text(if(id==null) "Без категории" else categories.firstOrNull { it.id==id }?.name ?: "Раздел удалён")
            }
            DropdownMenu(expanded,onDismissRequest={expanded=false},modifier=Modifier.heightIn(max=320.dp)) {
                DropdownMenuItem(text={Text("Без категории")},onClick={onSelect(null);expanded=false})
                categories.forEach { c -> DropdownMenuItem(text={Text(c.name)},onClick={onSelect(c.id);expanded=false}) }
            }
        }
    }
}
