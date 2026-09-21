package com.clinicalphotoarchive.ui

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Archive
import androidx.compose.material.icons.filled.Restore
import androidx.compose.material.icons.filled.SaveAlt
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle

@Composable
fun BackupControls(vm: AppViewModel, modifier: Modifier = Modifier) {
    val busy by vm.archiveBusy.collectAsStateWithLifecycle()
    val message by vm.archiveMessage.collectAsStateWithLifecycle()
    var menuExpanded by remember { mutableStateOf(false) }
    var pendingRestoreUri by remember { mutableStateOf<Uri?>(null) }\n    var pendingLegacyUri by remember { mutableStateOf<Uri?>(null) }

    val backupLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/zip")
    ) { uri ->
        if (uri != null) vm.exportBackup(uri)
    }

    val restoreLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) pendingRestoreUri = uri
    }

    val legacyImportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) pendingLegacyUri = uri
    }

    Box(modifier) {
        ExtendedFloatingActionButton(
            onClick = { if (!busy) menuExpanded = true },
            icon = {
                if (busy) {
                    CircularProgressIndicator()
                } else {
                    Icon(Icons.Default.Archive, contentDescription = null)
                }
            },
            text = { Text(if (busy) "Обработка…" else "Архив") }
        )

        DropdownMenu(
            expanded = menuExpanded,
            onDismissRequest = { menuExpanded = false }
        ) {
            DropdownMenuItem(
                text = { Text("Создать резервную копию") },
                leadingIcon = { Icon(Icons.Default.SaveAlt, contentDescription = null) },
                onClick = {
                    menuExpanded = false
                    backupLauncher.launch(vm.backupFileName())
                }
            )
            DropdownMenuItem(
                text = { Text("Восстановить из копии") },
                leadingIcon = { Icon(Icons.Default.Restore, contentDescription = null) },
                onClick = {
                    menuExpanded = false
                    restoreLauncher.launch(arrayOf("application/zip", "application/octet-stream"))
                }
            )
            DropdownMenuItem(
                text = { Text("Импорт из старой версии") },
                leadingIcon = { Icon(Icons.Default.Archive, contentDescription = null) },
                onClick = {
                    menuExpanded = false
                    legacyImportLauncher.launch(
                        arrayOf(
                            "application/x-tar",
                            "application/x-gtar",
                            "application/octet-stream",
                            "*/*"
                        )
                    )
                }
            )
        }
    }

    pendingRestoreUri?.let { uri ->
        AlertDialog(
            onDismissRequest = { pendingRestoreUri = null },
            title = { Text("Восстановить архив?") },
            text = {
                Text(
                    "Текущие карточки пациентов и фотографии будут полностью заменены данными " +
                        "из выбранной резервной копии. Перед продолжением рекомендуется создать " +
                        "резервную копию текущего архива."
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    pendingRestoreUri = null
                    vm.restoreBackup(uri)
                }) { Text("Восстановить") }
            },
            dismissButton = {
                TextButton(onClick = { pendingRestoreUri = null }) { Text("Отмена") }
            }
        )
    }

    pendingLegacyUri?.let { uri ->
        AlertDialog(
            onDismissRequest = { pendingLegacyUri = null },
            title = { Text("Импортировать старую базу?") },
            text = {
                Text(
                    "Текущие данные не будут удалены. Карточки из старой debug-версии будут " +
                        "добавлены к существующим. При совпадении номера карты пациент будет " +
                        "объединён с существующей карточкой; повторные фотографии будут пропущены. " +
                        "Рекомендуется заранее создать резервную копию текущего архива."
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    pendingLegacyUri = null
                    vm.importLegacyArchive(uri)
                }) { Text("Импортировать") }
            },
            dismissButton = {
                TextButton(onClick = { pendingLegacyUri = null }) { Text("Отмена") }
            }
        )
    }

    message?.let { text ->
        AlertDialog(
            onDismissRequest = vm::clearArchiveMessage,
            title = { Text("Архив") },
            text = { Text(text) },
            confirmButton = {
                TextButton(onClick = vm::clearArchiveMessage) { Text("OK") }
            }
        )
    }
}
