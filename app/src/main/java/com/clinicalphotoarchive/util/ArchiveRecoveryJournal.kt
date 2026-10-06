package com.clinicalphotoarchive.util

import android.content.Context
import android.util.AtomicFile
import com.clinicalphotoarchive.data.ClinicalDatabase
import org.json.JSONArray
import java.io.File

/** Candidates are persisted before file creation / DB deletion, then checked against DB. */
class ArchiveRecoveryJournal(context: Context) {
    private val root = ImageFiles.imageDir(context).canonicalFile
    private val journal = AtomicFile(File(context.filesDir,"archive-recovery.json"))
    private fun safe(path: String): File {
        val file = File(path).canonicalFile
        require(file.path.startsWith(root.path + File.separator)) { "Некорректный путь в журнале восстановления" }
        return file
    }
    private fun read(): MutableSet<String> {
        val bytes = try { journal.readFully() } catch (_: java.io.FileNotFoundException) { return linkedSetOf() }
        val array = JSONArray(String(bytes,Charsets.UTF_8))
        return (0 until array.length()).map { safe(array.getString(it)).path }.toCollection(linkedSetOf())
    }
    private fun write(paths: Collection<String>) {
        val stream = journal.startWrite()
        try {
            stream.write(JSONArray(paths.toList()).toString().toByteArray(Charsets.UTF_8))
            journal.finishWrite(stream)
        } catch (t: Throwable) { journal.failWrite(stream);throw t }
    }
    @Synchronized fun registerFile(path: String) = registerFiles(listOf(path))
    @Synchronized fun registerFiles(paths: Collection<String>) {
        val entries=read()
        paths.forEach { entries.add(safe(it).path) }
        write(entries)
    }
    suspend fun recover(database: ClinicalDatabase) {
        val references = database.photoDao().getAll().map { File(it.localPath).canonicalPath }.toSet()
        synchronized(this) {
            val remaining=linkedSetOf<String>()
            for (path in read()) {
                if (path !in references) {
                    val file=safe(path)
                    if (file.exists() && !file.delete()) remaining.add(path)
                }
            }
            write(remaining)
        }
    }
}
