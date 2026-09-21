package com.clinicalphotoarchive.migrator

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Archive
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MaterialTheme {
                Surface(Modifier.fillMaxSize()) {
                    MigratorScreen()
                }
            }
        }
    }
}

@Composable
private fun MigratorScreen(vm: MigratorViewModel = viewModel()) {
    val state by vm.state.collectAsStateWithLifecycle()
    val context = LocalContext.current

    val saveLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/x-tar")
    ) { uri ->
        if (uri != null) vm.exportArchive(uri)
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .navigationBarsPadding()
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Spacer(Modifier.height(16.dp))
        Text("ClinicalPhotoArchive Migrator", style = MaterialTheme.typography.headlineSmall)
        Text(
            "Утилита только читает данные старой debug-версии и создаёт локальный архив. " +
                "Она не удаляет карточки и фотографии и не использует интернет.",
            style = MaterialTheme.typography.bodyMedium
        )

        StatusCard("Старая версия приложения", state.targetInstalled)
        StatusCard("Shizuku запущен", state.shizukuRunning)
        StatusCard("Разрешение Shizuku", state.shizukuPermission)
        StatusCard("Сервис миграции подключён", state.serviceConnected)

        if (!state.shizukuRunning) {
            Button(
                modifier = Modifier.fillMaxWidth(),
                onClick = {
                    val launch = context.packageManager.getLaunchIntentForPackage(SHIZUKU_PACKAGE)
                    if (launch != null) {
                        launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        context.startActivity(launch)
                    } else {
                        vm.clearMessage()
                    }
                }
            ) {
                Text("Открыть Shizuku")
            }
            Text(
                "На Android 11+ Shizuku можно запустить через «Беспроводную отладку». " +
                    "На Android 9–10 для первого запуска Shizuku потребуется ADB с компьютера.",
                style = MaterialTheme.typography.bodySmall
            )
        }

        if (state.shizukuRunning && !state.shizukuPermission) {
            Button(
                modifier = Modifier.fillMaxWidth(),
                onClick = vm::requestShizukuPermission
            ) {
                Text("Разрешить доступ через Shizuku")
            }
        }

        OutlinedButton(
            modifier = Modifier.fillMaxWidth(),
            enabled = !state.busy,
            onClick = vm::refresh
        ) {
            Icon(Icons.Default.Refresh, contentDescription = null)
            Text("  Обновить состояние")
        }

        Button(
            modifier = Modifier.fillMaxWidth(),
            enabled = state.shizukuPermission && state.targetInstalled && !state.busy,
            onClick = vm::probe
        ) {
            Text("Проверить старый архив")
        }

        if (state.archiveReady) {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("Данные доступны", style = MaterialTheme.typography.titleMedium)
                    Text("Фотографий: ${state.photoCount ?: 0}")
                    state.databaseBytes?.let {
                        Text("Размер базы: ${formatBytes(it)}")
                    }
                    Text(
                        "Перед копированием старое приложение будет принудительно остановлено, " +
                            "чтобы SQLite-база сохранилась в согласованном состоянии. Данные не удаляются.",
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }

            Button(
                modifier = Modifier.fillMaxWidth(),
                enabled = !state.busy,
                onClick = { saveLauncher.launch(vm.archiveFileName()) }
            ) {
                Icon(Icons.Default.Archive, contentDescription = null)
                Text("  Создать архив")
            }
        }

        if (state.busy) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically
            ) {
                CircularProgressIndicator()
                Text("   Выполняется…")
            }
        }

        Text(
            "После создания архива не удаляйте старую debug-версию, пока архив не будет импортирован " +
                "в release-версию и данные не будут проверены.",
            style = MaterialTheme.typography.bodySmall
        )
    }

    state.message?.let { message ->
        AlertDialog(
            onDismissRequest = vm::clearMessage,
            title = { Text("Migrator") },
            text = { Text(message) },
            confirmButton = {
                TextButton(onClick = vm::clearMessage) { Text("OK") }
            }
        )
    }
}

@Composable
private fun StatusCard(title: String, ok: Boolean) {
    Card(Modifier.fillMaxWidth()) {
        Row(
            Modifier.padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Icon(
                if (ok) Icons.Default.CheckCircle else Icons.Default.Error,
                contentDescription = null
            )
            Column {
                Text(title, style = MaterialTheme.typography.titleSmall)
                Text(if (ok) "Готово" else "Не готово", style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

private fun formatBytes(value: Long): String {
    val mb = value / (1024.0 * 1024.0)
    return if (mb >= 1.0) String.format(java.util.Locale.ROOT, "%.1f МБ", mb)
    else String.format(java.util.Locale.ROOT, "%.1f КБ", value / 1024.0)
}

private const val SHIZUKU_PACKAGE = "moe.shizuku.privileged.api"
