package com.clinicalphotoarchive.migrator

import android.content.Context
import android.os.ParcelFileDescriptor
import androidx.annotation.Keep
import java.io.ByteArrayOutputStream
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlin.system.exitProcess

@Keep
class ArchiveUserService : IArchiveService.Stub {

    constructor()

    @Keep
    constructor(@Suppress("UNUSED_PARAMETER") context: Context)

    override fun destroy() {
        exitProcess(0)
    }

    override fun probe(): String {
        val databaseSizeResult = runCommand(
            runAsToybox(
                "wc",
                "-c",
                DATABASE_PATH
            )
        )

        if (databaseSizeResult.exitCode != 0) {
            val error = databaseSizeResult.stderr.ifBlank { databaseSizeResult.stdout }
            return if (isMissingPath(error)) {
                "ERROR|NO_DB"
            } else {
                "ERROR|${sanitize(error)}"
            }
        }

        val databaseBytes = databaseSizeResult.stdout
            .trim()
            .split(Regex("\\s+"))
            .firstOrNull()
            ?.toLongOrNull()
            ?: return "ERROR|Не удалось определить размер базы"

        if (databaseBytes <= 0L) {
            return "ERROR|База данных имеет нулевой размер"
        }

        val photoCount = if (imageDirectoryExists()) {
            val findResult = runCommand(
                runAsToybox(
                    "find",
                    IMAGE_DIRECTORY,
                    "-type",
                    "f"
                )
            )

            if (findResult.exitCode != 0) {
                val error = findResult.stderr.ifBlank { findResult.stdout }
                if (isMissingPath(error)) {
                    0
                } else {
                    return "ERROR|${sanitize(error)}"
                }
            } else {
                findResult.stdout
                    .lineSequence()
                    .count { it.isNotBlank() }
            }
        } else {
            0
        }

        return "OK|$photoCount|$databaseBytes"
    }

    override fun writeArchive(output: ParcelFileDescriptor): String {
        val preflight = probe()
        if (!preflight.startsWith("OK|")) {
            return preflight
        }

        val stopped = runCommand(
            listOf(
                "/system/bin/am",
                "force-stop",
                TARGET_PACKAGE
            ),
            timeoutSeconds = 10
        )

        if (stopped.exitCode != 0) {
            return "ERROR|${sanitize(stopped.stderr.ifBlank { stopped.stdout })}"
        }

        Thread.sleep(350)

        val command = mutableListOf(
            "/system/bin/run-as",
            TARGET_PACKAGE,
            "/system/bin/toybox",
            "tar",
            "-cf",
            "-",
            "databases"
        )

        if (imageDirectoryExists()) {
            command += IMAGE_DIRECTORY
        }

        val process = try {
            ProcessBuilder(command).start()
        } catch (t: Throwable) {
            return "ERROR|${sanitize(t.message ?: t.javaClass.simpleName)}"
        }

        val errorBuffer = ByteArrayOutputStream()
        val errorThread = thread(name = "archive-stderr", start = true) {
            process.errorStream.use { input ->
                input.copyTo(errorBuffer)
            }
        }

        var bytesCopied = 0L
        try {
            ParcelFileDescriptor.AutoCloseOutputStream(output).use { out ->
                process.inputStream.use { input ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        out.write(buffer, 0, read)
                        bytesCopied += read
                    }
                }
                out.flush()
            }
        } catch (t: Throwable) {
            process.destroyForcibly()
            return "ERROR|${sanitize(t.message ?: t.javaClass.simpleName)}"
        }

        val finished = process.waitFor(120, TimeUnit.SECONDS)
        if (!finished) {
            process.destroyForcibly()
            return "ERROR|Превышено время создания архива"
        }

        errorThread.join(3_000)
        val error = errorBuffer.toString(Charsets.UTF_8.name()).trim()

        return if (process.exitValue() == 0 && bytesCopied > 1024L) {
            "OK|$bytesCopied"
        } else {
            "ERROR|${sanitize(error.ifBlank { "Архив не создан, код ${process.exitValue()}" })}"
        }
    }

    private fun imageDirectoryExists(): Boolean {
        val result = runCommand(
            runAsToybox(
                "test",
                "-d",
                IMAGE_DIRECTORY
            )
        )
        return result.exitCode == 0
    }

    private fun runAsToybox(vararg arguments: String): List<String> =
        buildList {
            add("/system/bin/run-as")
            add(TARGET_PACKAGE)
            add("/system/bin/toybox")
            addAll(arguments)
        }

    private fun runCommand(
        command: List<String>,
        timeoutSeconds: Long = 20
    ): CommandResult {
        return try {
            val process = ProcessBuilder(command).start()
            val stdoutBuffer = ByteArrayOutputStream()
            val stderrBuffer = ByteArrayOutputStream()

            val stdoutThread = thread(start = true) {
                process.inputStream.use { it.copyTo(stdoutBuffer) }
            }
            val stderrThread = thread(start = true) {
                process.errorStream.use { it.copyTo(stderrBuffer) }
            }

            val finished = process.waitFor(timeoutSeconds, TimeUnit.SECONDS)
            if (!finished) {
                process.destroyForcibly()
            }

            stdoutThread.join(2_000)
            stderrThread.join(2_000)

            val stdout = stdoutBuffer.toString(Charsets.UTF_8.name())
            val stderr = stderrBuffer.toString(Charsets.UTF_8.name())

            if (!finished) {
                CommandResult(124, stdout, "Команда превысила время ожидания")
            } else {
                CommandResult(process.exitValue(), stdout, stderr)
            }
        } catch (t: Throwable) {
            CommandResult(125, "", t.message ?: t.javaClass.simpleName)
        }
    }

    private fun isMissingPath(value: String): Boolean {
        val text = value.lowercase()
        return "no such file" in text ||
            "no such directory" in text
    }

    private fun sanitize(value: String): String =
        value
            .replace('|', '/')
            .replace('\n', ' ')
            .replace('\r', ' ')
            .trim()
            .take(400)

    private data class CommandResult(
        val exitCode: Int,
        val stdout: String,
        val stderr: String
    )

    companion object {
        const val TARGET_PACKAGE = "com.clinicalphotoarchive"

        private const val DATABASE_PATH = "databases/clinical_photo_archive.db"
        private const val IMAGE_DIRECTORY = "files/clinical_images"
    }
}
