package com.clinicalphotoarchive.util

import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.net.Uri
import androidx.room.withTransaction
import com.clinicalphotoarchive.data.ClinicalDatabase
import com.clinicalphotoarchive.data.PatientEntity
import com.clinicalphotoarchive.data.PhotoEntity
import com.clinicalphotoarchive.data.PhotoSection
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import java.io.BufferedInputStream
import java.io.File
import java.io.FileOutputStream
import java.util.Locale
import java.util.UUID

class LegacyArchiveImporter(
    private val context: Context,
    private val database: ClinicalDatabase
) {
    private val patientDao = database.patientDao()
    private val photoDao = database.photoDao()

    suspend fun importFrom(uri: Uri): ImportResult {
        val tempDir = File(context.cacheDir, "legacy_import_${UUID.randomUUID()}").apply { mkdirs() }
        val copiedFiles = mutableListOf<File>()

        try {
            val scan = scanArchive(uri, tempDir)
            val legacy = readLegacyDatabase(scan.databaseFile, scan.imageEntries)

            val currentPatients = patientDao.getAll()
            val currentPhotos = photoDao.getAll()
            val existingMatches = matchPatients(legacy.patients, currentPatients)

            val photosToImport = legacy.photos.filterNot { photo ->
                val currentPatientId = existingMatches[photo.patientId] ?: return@filterNot false
                currentPhotos.any { existing ->
                    existing.patientId == currentPatientId &&
                        existing.section == photo.section &&
                        existing.capturedAt == photo.capturedAt &&
                        existing.addedAt == photo.addedAt &&
                        existing.description == photo.description &&
                        File(existing.localPath).isFile &&
                        File(existing.localPath).length() == photo.archiveSize
                }
            }

            val staged = stageImages(uri, photosToImport, copiedFiles)

            try {
                database.withTransaction {
                    val idMap = existingMatches.toMutableMap()

                    legacy.patients.forEach { patient ->
                        if (idMap[patient.id] == null) {
                            val newId = patientDao.insert(
                                patient.copy(
                                    id = 0,
                                    searchKey = patientSearchKey(patient)
                                )
                            )
                            idMap[patient.id] = newId
                        }
                    }

                    staged.forEach { stagedPhoto ->
                        val mappedPatientId = requireNotNull(idMap[stagedPhoto.legacy.patientId]) {
                            "Не удалось сопоставить пациента для фотографии"
                        }
                        photoDao.insert(
                            PhotoEntity(
                                patientId = mappedPatientId,
                                section = stagedPhoto.legacy.section,
                                localPath = stagedPhoto.destination.absolutePath,
                                description = stagedPhoto.legacy.description,
                                capturedAt = stagedPhoto.legacy.capturedAt,
                                addedAt = stagedPhoto.legacy.addedAt
                            )
                        )
                    }
                }
            } catch (t: Throwable) {
                copiedFiles.forEach { runCatching { it.delete() } }
                throw t
            }

            return ImportResult(
                patientsAdded = legacy.patients.size - existingMatches.size,
                patientsMerged = existingMatches.size,
                photosAdded = staged.size,
                photosSkipped = legacy.photos.size - staged.size
            )
        } finally {
            tempDir.deleteRecursively()
        }
    }

    private fun scanArchive(uri: Uri, tempDir: File): ArchiveScan {
        val dbDir = File(tempDir, "databases").apply { mkdirs() }
        val imageEntries = LinkedHashMap<String, ArchiveImage>()

        openTar(uri).use { tar ->
            var count = 0
            while (true) {
                val entry = tar.nextTarEntry ?: break
                count++
                require(count <= MAX_ENTRIES) { "Слишком много файлов в legacy-архиве" }
                validateEntry(entry)

                val path = normalizePath(entry.name)
                if (entry.isDirectory) continue

                when {
                    path == DB_NAME || path == WAL_NAME || path == SHM_NAME -> {
                        val destination = File(dbDir, path.substringAfterLast('/'))
                        copyCurrentEntry(tar, destination, MAX_DATABASE_FILE_BYTES)
                    }

                    path.startsWith(IMAGE_PREFIX) -> {
                        val baseName = path.substringAfterLast('/')
                        require(baseName.isNotBlank()) { "Некорректное имя фотографии в архиве" }
                        require(!imageEntries.containsKey(baseName)) {
                            "В legacy-архиве есть фотографии с одинаковым именем: $baseName"
                        }
                        require(entry.size in 1..MAX_IMAGE_BYTES) {
                            "Некорректный размер фотографии: $baseName"
                        }
                        imageEntries[baseName] = ArchiveImage(path, entry.size)
                    }
                }
            }
        }

        val dbFile = File(dbDir, "clinical_photo_archive.db")
        require(dbFile.isFile && dbFile.length() > 0L) {
            "Legacy-архив не содержит clinical_photo_archive.db"
        }
        return ArchiveScan(dbFile, imageEntries)
    }

    private fun readLegacyDatabase(
        dbFile: File,
        imageEntries: Map<String, ArchiveImage>
    ): LegacyData {
        val sqlite = SQLiteDatabase.openDatabase(
            dbFile.absolutePath,
            null,
            SQLiteDatabase.OPEN_READONLY
        )

        try {
            val patients = readPatients(sqlite)
            require(patients.isNotEmpty()) { "В старой базе нет карточек пациентов" }
            val patientIds = patients.map { it.id }.toSet()
            val photos = readPhotos(sqlite, patientIds, imageEntries)
            return LegacyData(patients, photos)
        } finally {
            sqlite.close()
        }
    }

    private fun readPatients(db: SQLiteDatabase): List<PatientEntity> {
        val result = ArrayList<PatientEntity>()
        val seenIds = HashSet<Long>()

        db.query("patients", null, null, null, null, null, "id ASC").use { cursor ->
            requireColumn(cursor, "id")
            requireColumn(cursor, "surname")

            while (cursor.moveToNext()) {
                val id = cursor.longValue("id")
                val surname = cursor.stringValue("surname")
                require(id > 0 && seenIds.add(id)) { "Некорректный ID пациента в старой базе" }
                require(surname.isNotBlank()) { "В старой базе найдена карточка без фамилии" }

                result += PatientEntity(
                    id = id,
                    surname = surname,
                    firstName = cursor.stringValueOrDefault("firstName"),
                    middleName = cursor.stringValueOrDefault("middleName"),
                    chartNumber = cursor.stringValueOrDefault("chartNumber"),
                    diagnosis = cursor.stringValueOrDefault("diagnosis"),
                    note = cursor.stringValueOrDefault("note"),
                    searchKey = cursor.stringValueOrDefault("searchKey"),
                    createdAt = cursor.longValueOrDefault("createdAt", System.currentTimeMillis()),
                    updatedAt = cursor.longValueOrDefault("updatedAt", System.currentTimeMillis())
                )
            }
        }
        return result
    }

    private fun readPhotos(
        db: SQLiteDatabase,
        patientIds: Set<Long>,
        imageEntries: Map<String, ArchiveImage>
    ): List<LegacyPhoto> {
        val result = ArrayList<LegacyPhoto>()
        val seenIds = HashSet<Long>()
        val usedArchiveEntries = HashSet<String>()
        val validSections = PhotoSection.entries.map { it.dbValue }.toSet()

        db.query("photos", null, null, null, null, null, "id ASC").use { cursor ->
            requireColumn(cursor, "id")
            requireColumn(cursor, "patientId")
            requireColumn(cursor, "section")
            requireColumn(cursor, "localPath")

            while (cursor.moveToNext()) {
                val id = cursor.longValue("id")
                val patientId = cursor.longValue("patientId")
                val section = cursor.stringValue("section")
                val oldPath = cursor.stringValue("localPath")
                val baseName = File(oldPath).name
                val archiveImage = imageEntries[baseName]

                require(id > 0 && seenIds.add(id)) { "Некорректный ID фотографии в старой базе" }
                require(patientId in patientIds) { "Фотография ссылается на отсутствующего пациента" }
                require(section in validSections) { "Неизвестный раздел фотографии: $section" }
                require(baseName.isNotBlank() && archiveImage != null) {
                    "В архиве отсутствует фотография, указанная в базе: $baseName"
                }
                require(usedArchiveEntries.add(archiveImage.path)) {
                    "Один файл фотографии используется несколькими записями: $baseName"
                }

                result += LegacyPhoto(
                    id = id,
                    patientId = patientId,
                    section = section,
                    archiveEntry = archiveImage.path,
                    originalName = baseName,
                    archiveSize = archiveImage.size,
                    description = cursor.stringValueOrDefault("description"),
                    capturedAt = cursor.longValueOrDefault("capturedAt", System.currentTimeMillis()),
                    addedAt = cursor.longValueOrDefault("addedAt", System.currentTimeMillis())
                )
            }
        }
        return result
    }

    private suspend fun matchPatients(
        legacy: List<PatientEntity>,
        current: List<PatientEntity>
    ): Map<Long, Long> {
        val result = LinkedHashMap<Long, Long>()

        legacy.forEach { old ->
            val match = if (old.chartNumber.isNotBlank()) {
                current
                    .filter { it.chartNumber.trim().equals(old.chartNumber.trim(), ignoreCase = true) }
                    .singleOrNull()
            } else {
                current
                    .filter {
                        sameName(it, old) &&
                            it.createdAt == old.createdAt
                    }
                    .singleOrNull()
            }
            if (match != null) result[old.id] = match.id
        }
        return result
    }

    private fun sameName(a: PatientEntity, b: PatientEntity): Boolean =
        a.surname.trim().equals(b.surname.trim(), ignoreCase = true) &&
            a.firstName.trim().equals(b.firstName.trim(), ignoreCase = true) &&
            a.middleName.trim().equals(b.middleName.trim(), ignoreCase = true)

    private fun patientSearchKey(patient: PatientEntity): String =
        listOf(patient.surname, patient.firstName, patient.middleName, patient.chartNumber)
            .joinToString(" ")
            .trim()
            .lowercase(Locale.ROOT)

    private fun stageImages(
        uri: Uri,
        photos: List<LegacyPhoto>,
        copiedFiles: MutableList<File>
    ): List<StagedPhoto> {
        if (photos.isEmpty()) return emptyList()

        val planByEntry = photos.associateBy { it.archiveEntry }
        val staged = LinkedHashMap<String, StagedPhoto>()
        val imageDir = ImageFiles.imageDir(context)

        try {
            openTar(uri).use { tar ->
                while (true) {
                    val entry = tar.nextTarEntry ?: break
                    validateEntry(entry)
                    if (entry.isDirectory) continue

                    val path = normalizePath(entry.name)
                    val legacy = planByEntry[path] ?: continue
                    require(entry.size == legacy.archiveSize) {
                        "Размер фотографии изменился между проверкой и импортом: ${legacy.originalName}"
                    }

                    val safeName = legacy.originalName.replace(Regex("[^A-Za-z0-9._-]"), "_")
                        .takeLast(96)
                        .ifBlank { "image.jpg" }
                    val destination = File(
                        imageDir,
                        "LEGACY_${legacy.id}_${UUID.randomUUID()}_$safeName"
                    )

                    copyCurrentEntry(tar, destination, MAX_IMAGE_BYTES)
                    copiedFiles += destination
                    staged[path] = StagedPhoto(legacy, destination)
                }
            }

            require(staged.size == photos.size) {
                "Не все фотографии удалось прочитать из legacy-архива"
            }
            return photos.map { legacy ->
                requireNotNull(staged[legacy.archiveEntry]) {
                    "Не найдена фотография ${legacy.originalName}"
                }
            }
        } catch (t: Throwable) {
            copiedFiles.forEach { runCatching { it.delete() } }
            throw t
        }
    }

    private fun openTar(uri: Uri): TarArchiveInputStream {
        val raw = context.contentResolver.openInputStream(uri)
        requireNotNull(raw) { "Не удалось открыть legacy-архив" }
        return TarArchiveInputStream(BufferedInputStream(raw))
    }

    private fun validateEntry(entry: TarArchiveEntry) {
        require(!entry.isSymbolicLink && !entry.isLink) {
            "Legacy-архив содержит недопустимую ссылку"
        }
        require(entry.size >= 0L) { "Некорректный размер файла в legacy-архиве" }
        normalizePath(entry.name)
    }

    private fun normalizePath(raw: String): String {
        var value = raw.replace('\\', '/')
        while (value.startsWith("./")) value = value.removePrefix("./")
        require(value.isNotBlank() && !value.startsWith('/')) {
            "Некорректный путь в legacy-архиве"
        }
        require(value.split('/').none { it == ".." }) {
            "Некорректный путь в legacy-архиве"
        }
        return value
    }

    private fun copyCurrentEntry(
        tar: TarArchiveInputStream,
        destination: File,
        maxBytes: Long
    ) {
        destination.parentFile?.mkdirs()
        var total = 0L
        try {
            FileOutputStream(destination).use { output ->
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                while (true) {
                    val read = tar.read(buffer)
                    if (read < 0) break
                    total += read
                    require(total <= maxBytes) { "Файл в legacy-архиве слишком большой" }
                    output.write(buffer, 0, read)
                }
                output.flush()
            }
        } catch (t: Throwable) {
            destination.delete()
            throw t
        }
        require(total > 0L) { "В legacy-архиве найден пустой файл" }
    }

    private fun requireColumn(cursor: Cursor, name: String) {
        require(cursor.getColumnIndex(name) >= 0) {
            "Старая база имеет неподдерживаемую структуру: отсутствует $name"
        }
    }

    private fun Cursor.stringValue(name: String): String {
        val index = getColumnIndex(name)
        require(index >= 0) { "В старой базе отсутствует поле $name" }
        return if (isNull(index)) "" else getString(index)
    }

    private fun Cursor.stringValueOrDefault(name: String, default: String = ""): String {
        val index = getColumnIndex(name)
        return if (index < 0 || isNull(index)) default else getString(index)
    }

    private fun Cursor.longValue(name: String): Long {
        val index = getColumnIndex(name)
        require(index >= 0) { "В старой базе отсутствует поле $name" }
        return getLong(index)
    }

    private fun Cursor.longValueOrDefault(name: String, default: Long): Long {
        val index = getColumnIndex(name)
        return if (index < 0 || isNull(index)) default else getLong(index)
    }

    data class ImportResult(
        val patientsAdded: Int,
        val patientsMerged: Int,
        val photosAdded: Int,
        val photosSkipped: Int
    )

    private data class ArchiveScan(
        val databaseFile: File,
        val imageEntries: Map<String, ArchiveImage>
    )

    private data class ArchiveImage(
        val path: String,
        val size: Long
    )

    private data class LegacyData(
        val patients: List<PatientEntity>,
        val photos: List<LegacyPhoto>
    )

    private data class LegacyPhoto(
        val id: Long,
        val patientId: Long,
        val section: String,
        val archiveEntry: String,
        val originalName: String,
        val archiveSize: Long,
        val description: String,
        val capturedAt: Long,
        val addedAt: Long
    )

    private data class StagedPhoto(
        val legacy: LegacyPhoto,
        val destination: File
    )

    companion object {
        private const val DB_NAME = "databases/clinical_photo_archive.db"
        private const val WAL_NAME = "databases/clinical_photo_archive.db-wal"
        private const val SHM_NAME = "databases/clinical_photo_archive.db-shm"
        private const val IMAGE_PREFIX = "files/clinical_images/"

        private const val MAX_ENTRIES = 200_000
        private const val MAX_DATABASE_FILE_BYTES = 512L * 1024L * 1024L
        private const val MAX_IMAGE_BYTES = 2L * 1024L * 1024L * 1024L
    }
}
