package com.clinicalphotoarchive.migrator

import android.app.Application
import android.content.ComponentName
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.net.Uri
import android.os.IBinder
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import rikka.shizuku.Shizuku
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class MigratorUiState(
    val shizukuRunning: Boolean = false,
    val shizukuPermission: Boolean = false,
    val serviceConnected: Boolean = false,
    val targetInstalled: Boolean = false,
    val archiveReady: Boolean = false,
    val photoCount: Int? = null,
    val databaseBytes: Long? = null,
    val busy: Boolean = false,
    val message: String? = null
)

class MigratorViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application

    private val _state = MutableStateFlow(MigratorUiState())
    val state: StateFlow<MigratorUiState> = _state.asStateFlow()

    @Volatile
    private var service: IArchiveService? = null
    private var binding = false

    private val serviceArgs = Shizuku.UserServiceArgs(
        ComponentName(app.packageName, ArchiveUserService::class.java.name)
    )
        .processNameSuffix("archive_migrator")
        .daemon(false)
        .version(2)
        .tag("clinical-photo-archive-migrator-v1")

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            service = IArchiveService.Stub.asInterface(binder)
            binding = false
            _state.value = _state.value.copy(serviceConnected = service != null)
            probe()
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            service = null
            binding = false
            _state.value = _state.value.copy(serviceConnected = false, archiveReady = false)
        }
    }

    private val binderReceivedListener = Shizuku.OnBinderReceivedListener {
        refresh()
    }

    private val binderDeadListener = Shizuku.OnBinderDeadListener {
        service = null
        binding = false
        _state.value = _state.value.copy(
            shizukuRunning = false,
            shizukuPermission = false,
            serviceConnected = false,
            archiveReady = false
        )
    }

    private val permissionListener = Shizuku.OnRequestPermissionResultListener { requestCode, grantResult ->
        if (requestCode == REQUEST_SHIZUKU) {
            refresh()
            if (grantResult == PackageManager.PERMISSION_GRANTED) {
                bindService()
            }
        }
    }

    init {
        Shizuku.addBinderReceivedListenerSticky(binderReceivedListener)
        Shizuku.addBinderDeadListener(binderDeadListener)
        Shizuku.addRequestPermissionResultListener(permissionListener)
        refresh()
    }

    fun refresh() {
        val installed = isTargetInstalled()
        val running = runCatching { Shizuku.pingBinder() }.getOrDefault(false)
        val permission = if (running) {
            runCatching { Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED }
                .getOrDefault(false)
        } else {
            false
        }

        _state.value = _state.value.copy(
            targetInstalled = installed,
            shizukuRunning = running,
            shizukuPermission = permission,
            serviceConnected = service != null
        )

        if (running && permission && installed) {
            bindService()
        }
    }

    fun requestShizukuPermission() {
        if (!runCatching { Shizuku.pingBinder() }.getOrDefault(false)) {
            setMessage("Shizuku РЅРµ Р·Р°РїСѓС‰РµРЅ. РЎРЅР°С‡Р°Р»Р° Р·Р°РїСѓСЃС‚РёС‚Рµ Shizuku.")
            return
        }
        runCatching {
            Shizuku.requestPermission(REQUEST_SHIZUKU)
        }.onFailure {
            setMessage("РќРµ СѓРґР°Р»РѕСЃСЊ Р·Р°РїСЂРѕСЃРёС‚СЊ СЂР°Р·СЂРµС€РµРЅРёРµ Shizuku: ${it.message}")
        }
    }

    fun probe() {
        val remote = service ?: run {
            bindService()
            return
        }
        if (_state.value.busy) return

        _state.value = _state.value.copy(busy = true, archiveReady = false, message = null)
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching { remote.probe() }
            }
            result.onSuccess { parseProbe(it) }
                .onFailure { setMessage("РћС€РёР±РєР° РїСЂРѕРІРµСЂРєРё: ${it.message ?: "РЅРµРёР·РІРµСЃС‚РЅР°СЏ РѕС€РёР±РєР°"}") }
            _state.value = _state.value.copy(busy = false)
        }
    }

    fun exportArchive(uri: Uri) {
        val remote = service ?: run {
            setMessage("РЎРµСЂРІРёСЃ РјРёРіСЂР°С†РёРё РЅРµ РїРѕРґРєР»СЋС‡С‘РЅ.")
            return
        }
        if (!_state.value.archiveReady || _state.value.busy) return

        _state.value = _state.value.copy(busy = true, message = null)
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    app.contentResolver.openFileDescriptor(uri, "w")?.use { pfd ->
                        remote.writeArchive(pfd)
                    } ?: "ERROR|РќРµ СѓРґР°Р»РѕСЃСЊ РѕС‚РєСЂС‹С‚СЊ РІС‹Р±СЂР°РЅРЅС‹Р№ С„Р°Р№Р»"
                }
            }
            result.onSuccess { raw ->
                if (raw.startsWith("OK|")) {
                    val bytes = raw.substringAfter('|').toLongOrNull()
                    val sizeText = bytes?.let { formatBytes(it) } ?: "РЅРµРёР·РІРµСЃС‚РЅС‹Р№ СЂР°Р·РјРµСЂ"
                    setMessage(
                        "РђСЂС…РёРІ СЃРѕР·РґР°РЅ СѓСЃРїРµС€РЅРѕ ($sizeText). РЎС‚Р°СЂРѕРµ РїСЂРёР»РѕР¶РµРЅРёРµ Рё РµРіРѕ РґР°РЅРЅС‹Рµ РЅРµ СѓРґР°Р»РµРЅС‹. " +
                            "Р¤Р°Р№Р» СЃРѕС…СЂР°РЅС‘РЅ РІ РІС‹Р±СЂР°РЅРЅСѓСЋ РІР°РјРё РїР°РїРєСѓ."
                    )
                } else {
                    setMessage("РќРµ СѓРґР°Р»РѕСЃСЊ СЃРѕР·РґР°С‚СЊ Р°СЂС…РёРІ: ${raw.substringAfter('|', raw)}")
                }
            }.onFailure {
                setMessage("РќРµ СѓРґР°Р»РѕСЃСЊ СЃРѕР·РґР°С‚СЊ Р°СЂС…РёРІ: ${it.message ?: "РЅРµРёР·РІРµСЃС‚РЅР°СЏ РѕС€РёР±РєР°"}")
            }
            _state.value = _state.value.copy(busy = false)
        }
    }

    fun archiveFileName(): String {
        val stamp = SimpleDateFormat("yyyyMMdd_HHmm", Locale.ROOT).format(Date())
        return "ClinicalPhotoArchive_legacy_$stamp.tar"
    }

    fun clearMessage() {
        _state.value = _state.value.copy(message = null)
    }

    private fun bindService() {
        val current = _state.value
        if (service != null || binding || !current.shizukuRunning || !current.shizukuPermission || !current.targetInstalled) {
            return
        }
        binding = true
        runCatching {
            Shizuku.bindUserService(serviceArgs, connection)
        }.onFailure {
            binding = false
            setMessage("РќРµ СѓРґР°Р»РѕСЃСЊ РїРѕРґРєР»СЋС‡РёС‚СЊ СЃРµСЂРІРёСЃ РјРёРіСЂР°С†РёРё: ${it.message}")
        }
    }

    private fun parseProbe(raw: String) {
        if (!raw.startsWith("OK|")) {
            _state.value = _state.value.copy(
                archiveReady = false,
                photoCount = null,
                databaseBytes = null,
                message = when {
                    raw.contains("not debuggable", ignoreCase = true) ->
                        "РЈСЃС‚Р°РЅРѕРІР»РµРЅРЅР°СЏ РІРµСЂСЃРёСЏ РЅРµ РґРѕРїСѓСЃРєР°РµС‚ run-as. Р­С‚Р° СѓС‚РёР»РёС‚Р° СЂР°СЃСЃС‡РёС‚Р°РЅР° РЅР° СЃС‚Р°СЂС‹Рµ debug-СЃР±РѕСЂРєРё."
                    raw.contains("unknown package", ignoreCase = true) ||
                        raw.contains("package not found", ignoreCase = true) ->
                        "РЎС‚Р°СЂР°СЏ РІРµСЂСЃРёСЏ ClinicalPhotoArchive РЅРµ РЅР°Р№РґРµРЅР°."
                    raw.contains("NO_DB", ignoreCase = true) ->
                        "РџСЂРёР»РѕР¶РµРЅРёРµ РЅР°Р№РґРµРЅРѕ, РЅРѕ Р±Р°Р·Р° clinical_photo_archive.db РѕС‚СЃСѓС‚СЃС‚РІСѓРµС‚."
                    else -> "Р”РѕСЃС‚СѓРї Рє СЃС‚Р°СЂРѕР№ Р±Р°Р·Рµ РЅРµ РїРѕР»СѓС‡РµРЅ: ${raw.substringAfter('|', raw)}"
                }
            )
            return
        }

        val parts = raw.split('|')
        val photos = parts.getOrNull(1)?.trim()?.toIntOrNull()
        val dbBytes = parts.getOrNull(2)?.trim()?.toLongOrNull()
        if (photos == null || photos < 0 || dbBytes == null || dbBytes <= 0) {
            _state.value = _state.value.copy(archiveReady = false, photoCount = null, databaseBytes = null,
                message = "Получен некорректный ответ проверки данных")
            return
        }
        _state.value = _state.value.copy(
            archiveReady = true,
            photoCount = photos,
            databaseBytes = dbBytes,
            message = null
        )
    }

    @Suppress("DEPRECATION")
    private fun isTargetInstalled(): Boolean = try {
        app.packageManager.getPackageInfo(ArchiveUserService.TARGET_PACKAGE, 0)
        true
    } catch (_: PackageManager.NameNotFoundException) {
        false
    }

    private fun setMessage(text: String) {
        _state.value = _state.value.copy(message = text)
    }

    private fun formatBytes(value: Long): String {
        val mb = value / (1024.0 * 1024.0)
        return if (mb >= 1.0) String.format(Locale.ROOT, "%.1f РњР‘", mb)
        else String.format(Locale.ROOT, "%.1f РљР‘", value / 1024.0)
    }

    override fun onCleared() {
        Shizuku.removeBinderReceivedListener(binderReceivedListener)
        Shizuku.removeBinderDeadListener(binderDeadListener)
        Shizuku.removeRequestPermissionResultListener(permissionListener)
        if (service != null) {
            runCatching { Shizuku.unbindUserService(serviceArgs, connection, true) }
        }
        service = null
        super.onCleared()
    }

    companion object {
        private const val REQUEST_SHIZUKU = 9104
    }
}
