package com.clinicalphotoarchive.util

import android.content.Context
import android.net.Uri
import androidx.room.withTransaction
import com.clinicalphotoarchive.data.ClinicalDatabase
import com.clinicalphotoarchive.data.PatientEntity
import com.clinicalphotoarchive.data.PhotoEntity
import com.clinicalphotoarchive.data.PhotoSection
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

class BackupArchive(
    private val context: Context,
    private val database: ClinicalDatabase
) {
    private val patientDao = database.patientDao()
    private val photoDao = database.photoDao()

    suspend fun exportTo(uri: Uri): BackupResult {
        val patients = patientDao.getAll()
        val photos = photoDao.getAll()

        val photoRecords = photos.map { photo ->
            val source = File(photo.localPath)
            require(source.isFile) { "Не найден файл фотографии: ${source.name}" }
            val entryName = "images/${photo.id}_${source.name.replace(Regex("[^A-Za-z0-9._-]"), "_")}" 
            photo to entryName
        }

        val manifest = JSONObject().apply {
            put("format", FORMAT_NAME)
            put("version", FORMAT_VERSION)
            put("createdAt", System.currentTimeMillis())
            put("patients", JSONArray().apply {
                patients.forEach { patient -> put(patient.toJson()) }
            })
            put("photos", JSONArray().apply {
                photoRecords.forEach { (photo, entryName) ->
                    put(photo.toJson(entryName))
                }
            })
        }

        context.contentResolver.openOutputStream(uri, "w").use { rawOutput ->
            requireNotNull(rawOutput) { "Не удалось открыть выбранный файл для записи" }
            ZipOutputStream(rawOutput.buffered()).use { zip ->
                zip.putNextEntry(ZipEntry(MANIFEST))
                zip.write(manifest.toString().toByteArray(Charsets.UTF_8))
                zip.closeEntry()

                photoRecords.forEach { (photo, entryName) ->
                    zip.putNextEntry(ZipEntry(entryName))
                    FileInputStream(photo.localPath).use { input -> input.copyTo(zip) }
                    zip.closeEntry()
                }
            }
        }

        return BackupResult(patients.size, photos.size)
    }

    suspend fun restoreFrom(uri: Uri): BackupResult {
        val tempDir = File(context.cacheDir, "clinical_restore_${UUID.randomUUID()}").apply { mkdirs() }
        val restoredFiles = mutableListOf<File>()
        try {
            extractArchive(uri, tempDir)
            val manifestFile = File(tempDir, MANIFEST)
            require(manifestFile.isFile) { "Архив не содержит manifest.json" }

            val manifest = JSONObject(manifestFile.readText(Charsets.UTF_8))
            require(manifest.optString("format") == FORMAT_NAME) { "Неверный формат резервной копии" }
            require(manifest.optInt("version", -1) == FORMAT_VERSION) {
                "Эта версия резервной копии пока не поддерживается"
            }

            val patients = parsePatients(manifest.getJSONArray("patients"))
            val photoSpecs = parsePhotos(manifest.getJSONArray("photos"), patients.map { it.id }.toSet())

            val imageDir = ImageFiles.imageDir(context)
            val restoredPhotos = photoSpecs.map { spec ->
                val extracted = safeFile(tempDir, spec.archiveFile)
                require(extracted.isFile) { "В архиве отсутствует фотография ${spec.archiveFile}" }
                val extension = extracted.extension.takeIf { it.length in 1..8 } ?: "jpg"
                val destination = File(imageDir, "RESTORE_${System.currentTimeMillis()}_${UUID.randomUUID()}.$extension")
                extracted.copyTo(destination, overwrite = false)
                restoredFiles += destination
                spec.entity.copy(localPath = destination.absolutePath)
            }

            val oldPhotos = photoDao.getAll()
            try {
                database.withTransaction {
                    photoDao.deleteAll()
                    patientDao.deleteAll()
                    patients.forEach { patientDao.insert(it) }
                    restoredPhotos.forEach { photoDao.insert(it) }
                }
            } catch (t: Throwable) {
                restoredFiles.forEach { runCatching { it.delete() } }
                throw t
            }

            oldPhotos.forEach { old -> runCatching { File(old.localPath).delete() } }
            return BackupResult(patients.size, restoredPhotos.size)
        } finally {
            tempDir.deleteRecursively()
        }
    }

    private fun extractArchive(uri: Uri, destinationRoot: File) {
        context.contentResolver.openInputStream(uri).use { rawInput ->
            requireNotNull(rawInput) { "Не удалось открыть резервную копию" }
            ZipInputStream(rawInput.buffered()).use { zip ->
                var count = 0
                while (true) {
                    val entry = zip.nextEntry ?: break
                    count++
                    require(count <= MAX_ENTRIES) { "Слишком много файлов в архиве" }
                    val destination = safeFile(destinationRoot, entry.name)
                    if (entry.isDirectory) {
                        destination.mkdirs()
                    } else {
                        destination.parentFile?.mkdirs()
                        FileOutputStream(destination).use { output -> zip.copyTo(output) }
                    }
                    zip.closeEntry()
                }
            }
        }
    }

    private fun safeFile(root: File, relativePath: String): File {
        val normalized = relativePath.replace('\\', '/')
        require(normalized.isNotBlank() && !normalized.startsWith('/')) { "Некорректный путь в архиве" }
        require(normalized.split('/').none { it == ".." }) { "Некорректный путь в архиве" }
        val file = File(root, normalized)
        val rootPath = root.canonicalFile.path + File.separator
        require(file.canonicalFile.path.startsWith(rootPath)) { "Некорректный путь в архиве" }
        return file
    }

    private fun parsePatients(array: JSONArray): List<PatientEntity> {
        val result = ArrayList<PatientEntity>(array.length())
        val ids = HashSet<Long>()
        for (i in 0 until array.length()) {
            val json = array.getJSONObject(i)
            val id = json.getLong("id")
            require(id > 0 && ids.add(id)) { "Повторяющийся или некорректный ID пациента" }
            val surname = json.getString("surname")
            require(surname.isNotBlank()) { "В резервной копии найден пациент без фамилии" }
            result += PatientEntity(
                id = id,
                surname = surname,
                firstName = json.optString("firstName"),
                middleName = json.optString("middleName"),
                chartNumber = json.optString("chartNumber"),
                diagnosis = json.optString("diagnosis"),
                note = json.optString("note"),
                searchKey = json.optString("searchKey"),
                createdAt = json.optLong("createdAt", System.currentTimeMillis()),
                updatedAt = json.optLong("updatedAt", System.currentTimeMillis())
            )
        }
        return result
    }

    private fun parsePhotos(array: JSONArray, patientIds: Set<Long>): List<PhotoRestoreSpec> {
        val result = ArrayList<PhotoRestoreSpec>(array.length())
        val ids = HashSet<Long>()
        val validSections = PhotoSection.entries.map { it.dbValue }.toSet()
        for (i in 0 until array.length()) {
            val json = array.getJSONObject(i)
            val id = json.getLong("id")
            val patientId = json.getLong("patientId")
            val section = json.getString("section")
            val archiveFile = json.getString("archiveFile")
            require(id > 0 && ids.add(id)) { "Повторяющийся или некорректный ID фотографии" }
            require(patientId in patientIds) { "Фотография ссылается на отсутствующего пациента" }
            require(section in validSections) { "Неизвестный раздел фотографии" }
            require(archiveFile.startsWith("images/")) { "Некорректный путь фотографии в архиве" }
            result += PhotoRestoreSpec(
                entity = PhotoEntity(
                    id = id,
                    patientId = patientId,
                    section = section,
                    localPath = "",
                    description = json.optString("description"),
                    capturedAt = json.optLong("capturedAt", System.currentTimeMillis()),
                    addedAt = json.optLong("addedAt", System.currentTimeMillis())
                ),
                archiveFile = archiveFile
            )
        }
        return result
    }

    private fun PatientEntity.toJson() = JSONObject().apply {
        put("id", id)
        put("surname", surname)
        put("firstName", firstName)
        put("middleName", middleName)
        put("chartNumber", chartNumber)
        put("diagnosis", diagnosis)
        put("note", note)
        put("searchKey", searchKey)
        put("createdAt", createdAt)
        put("updatedAt", updatedAt)
    }

    private fun PhotoEntity.toJson(archiveFile: String) = JSONObject().apply {
        put("id", id)
        put("patientId", patientId)
        put("section", section)
        put("archiveFile", archiveFile)
        put("description", description)
        put("capturedAt", capturedAt)
        put("addedAt", addedAt)
    }

    data class BackupResult(val patientCount: Int, val photoCount: Int)

    private data class PhotoRestoreSpec(
        val entity: PhotoEntity,
        val archiveFile: String
    )

    companion object {
        private const val FORMAT_NAME = "clinical-photo-archive"
        private const val FORMAT_VERSION = 1
        private const val MANIFEST = "manifest.json"
        private const val MAX_ENTRIES = 100_000
    }
}
