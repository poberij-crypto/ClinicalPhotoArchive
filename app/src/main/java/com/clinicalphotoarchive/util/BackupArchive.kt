package com.clinicalphotoarchive.util

import android.content.Context
import android.net.Uri
import androidx.room.withTransaction
import com.clinicalphotoarchive.data.ClinicalDatabase
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.*
import java.util.UUID
import java.util.zip.*

class BackupArchive(
    private val context: Context,
    private val database: ClinicalDatabase,
    private val operations: ArchiveOperationCoordinator = ArchiveOperationCoordinator(),
    private val journal: ArchiveRecoveryJournal = ArchiveRecoveryJournal(context)
) {
    private val patientDao=database.patientDao()
    private val photoDao=database.photoDao()
    private val categoryDao=database.categoryDao()
    suspend fun exportTo(uri: Uri): BackupResult = operations.withArchive {
        journal.recover(database)
        val data=database.withTransaction { Triple(categoryDao.getAll(),patientDao.getAll(),photoDao.getAll()) }
        val records=data.third.map { photo ->
            val source=File(photo.localPath)
            require(source.isFile && source.length() in 1..MAX_IMAGE_BYTES) { "Не найдена фотография или превышен размер файла: ${source.name}" }
            photo to "images/${photo.id}_${source.name.replace(Regex("[^A-Za-z0-9._-]"),"_")}"
        }
        require(records.size+1<=MAX_ENTRIES) { "Слишком много файлов в архиве" }
        val manifest=BackupManifest.create(data.first,data.second,records).toString().toByteArray(Charsets.UTF_8)
        require(manifest.size<=MAX_MANIFEST_BYTES) { "Слишком большой manifest.json" }
        var total=manifest.size.toLong()
        context.contentResolver.openOutputStream(uri,"w").use { raw ->
            requireNotNull(raw) { "Не удалось открыть выбранный файл для записи" }
            ZipOutputStream(raw.buffered()).use { zip ->
                zip.putNextEntry(ZipEntry(MANIFEST));zip.write(manifest);zip.closeEntry()
                for((photo,path) in records) {
                    zip.putNextEntry(ZipEntry(path))
                    FileInputStream(photo.localPath).use { input ->
                        val count=copyLimited(input,zip,MAX_IMAGE_BYTES) { bytes -> total+=bytes;require(total<=MAX_TOTAL_BYTES) { "Архив превышает 20 ГиБ" } }
                        require(count>0) { "Пустой файл фотографии" }
                    }
                    zip.closeEntry()
                }
            }
        }
        BackupResult(data.second.size,records.size)
    }
    suspend fun restoreFrom(uri: Uri): BackupResult = operations.withArchive {
        journal.recover(database)
        val tempDir=File(context.cacheDir,"clinical_restore_${UUID.randomUUID()}").apply { check(mkdirs()) }
        var committed=false
        try {
            extractArchive(uri,tempDir)
            val manifestFile=File(tempDir,MANIFEST)
            require(manifestFile.isFile) { "Архив не содержит manifest.json" }
            val data=BackupManifest.parse(JSONObject(manifestFile.readText(Charsets.UTF_8)))
            val specs=data.photos.map { spec ->
                val file=safeFile(tempDir,spec.archiveFile)
                require(file.isFile && file.length()>0) { "В архиве отсутствует фотография ${spec.archiveFile}" }
                spec to file
            }
            val imageDir=ImageFiles.imageDir(context)
            val needed=specs.sumOf { it.second.length() }
            require(imageDir.usableSpace>needed+SPACE_RESERVE) { "Недостаточно свободного места для восстановления" }
            val oldPhotos=photoDao.getAll()
            val plans=specs.map { (spec,source) ->
                val extension=source.extension.takeIf { it.length in 1..8 } ?: "jpg"
                val destination=File(imageDir,"RESTORE_${UUID.randomUUID()}.$extension")
                Triple(spec,source,destination)
            }
            journal.registerFiles(oldPhotos.map { it.localPath } + plans.map { it.third.absolutePath })
            val restored=plans.map { (spec,source,destination) ->
                source.inputStream().use { input -> destination.outputStream().use { out -> copyLimited(input,out,MAX_IMAGE_BYTES) { } ;out.fd.sync() } }
                spec.entity.copy(localPath=destination.absolutePath)
            }
            database.withTransaction {
                photoDao.deleteAll();patientDao.deleteAll();categoryDao.deleteAll()
                data.categories.forEach { categoryDao.insert(it) }
                data.patients.forEach { patientDao.insert(it) }
                restored.forEach { photoDao.insert(it) }
            }
            committed=true
            BackupResult(data.patients.size,restored.size)
        } finally {
            // DB references decide which side of the commit survived, including cancellation.
            try {
                withContext(NonCancellable) {
                    if(committed) runCatching { journal.recover(database) }
                    else journal.recover(database)
                }
            } finally { tempDir.deleteRecursively() }
        }
    }
    private fun extractArchive(uri: Uri,root: File) {
        context.contentResolver.openInputStream(uri).use { raw ->
            requireNotNull(raw) { "Не удалось открыть резервную копию" }
            ZipInputStream(raw.buffered()).use { zip ->
                var count=0;var total=0L;val seen=HashSet<String>()
                while(true) {
                    val entry=zip.nextEntry ?: break
                    require(++count<=MAX_ENTRIES) { "Слишком много файлов в архиве" }
                    val dest=safeFile(root,entry.name)
                    require(seen.add(dest.canonicalPath)) { "Повторяющийся путь в архиве" }
                    if(entry.isDirectory) { require(dest.mkdirs() || dest.isDirectory) }
                    else {
                        val limit=if(entry.name==MANIFEST) MAX_MANIFEST_BYTES.toLong() else MAX_IMAGE_BYTES
                        require(dest.parentFile!!.mkdirs() || dest.parentFile!!.isDirectory)
                        dest.outputStream().use { out -> copyLimited(zip,out,limit) { bytes ->
                            total+=bytes
                            require(total<=MAX_TOTAL_BYTES && root.usableSpace>SPACE_RESERVE) { "Превышен размер архива или недостаточно места" }
                        } }
                    }
                    zip.closeEntry()
                }
            }
        }
    }
    private fun safeFile(root: File,path: String): File {
        val value=path.replace('\\','/').removeSuffix("/")
        require(value.isNotBlank() && !value.startsWith('/') && !value.contains(':') && value.split('/').none { it==".." || it=="." || it.isEmpty() }) { "Некорректный путь в архиве" }
        return File(root,value).also { require(it.canonicalPath.startsWith(root.canonicalPath+File.separator)) { "Некорректный путь в архиве" } }
    }
    private fun copyLimited(input: InputStream,output: OutputStream,limit: Long,onBytes: (Long)->Unit): Long {
        val buffer=ByteArray(64*1024);var count=0L
        while(true) {
            val n=input.read(buffer);if(n<0) break
            count+=n;require(count<=limit) { "Файл в архиве превышает допустимый размер" }
            onBytes(n.toLong());output.write(buffer,0,n)
        }
        return count
    }
    data class BackupResult(val patientCount: Int,val photoCount: Int)
    companion object {
        private const val MANIFEST="manifest.json"
        private const val MAX_ENTRIES=100_000
        private const val MAX_MANIFEST_BYTES=16*1024*1024
        private const val MAX_IMAGE_BYTES=512L*1024*1024
        private const val MAX_TOTAL_BYTES=20L*1024*1024*1024
        private const val SPACE_RESERVE=16L*1024*1024
    }
}
