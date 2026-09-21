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
        val result = runCommand(
            listOf(
                "/system/bin/run-as",
                TARGET_PACKAGE,
                "/system/bin/sh",
                "-c",
                PROBE_COMMAND
            )
        )
        return if (result.exitCode == 0) {
            result.stdout.trim()
        } else {
            "ERROR|${sanitize(result.stderr.ifBlank { result.stdout })}"
        }
    }

    override fun writeArchive(output: ParcelFileDescriptor): String {
        val preflight = probe()
        if (!preflight.startsWith("OK|")) {
            return preflight
        }

        runCatching {
            ProcessBuilder("/system/bin/am", "force-stop", TARGET_PACKAGE)
                .start()
                .waitFor(10, TimeUnit.SECONDS)
        }

        Thread.sleep(350)

        val process = ProcessBuilder(
            "/system/bin/run-as",
            TARGET_PACKAGE,
            "/system/bin/sh",
            "-c",
            ARCHIVE_COMMAND
        ).start()

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

        val finished = process.waitFor(90, TimeUnit.SECONDS)
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

    private fun runCommand(command: List<String>): CommandResult {
        return try {
            val process = ProcessBuilder(command).start()
            val stdout = process.inputStream.bufferedReader().use { it.readText() }
            val stderr = process.errorStream.bufferedReader().use { it.readText() }
            val finished = process.waitFor(20, TimeUnit.SECONDS)
            if (!finished) {
                process.destroyForcibly()
                CommandResult(124, stdout, "Команда превысила время ожидания")
            } else {
                CommandResult(process.exitValue(), stdout, stderr)
            }
        } catch (t: Throwable) {
            CommandResult(125, "", t.message ?: t.javaClass.simpleName)
        }
    }

    private fun sanitize(value: String): String =
        value.replace('|', '/').replace('\n', ' ').replace('\r', ' ').trim().take(400)

    private data class CommandResult(
        val exitCode: Int,
        val stdout: String,
        val stderr: String
    )

    companion object {
        const val TARGET_PACKAGE = "com.clinicalphotoarchive"

        private const val PROBE_COMMAND =
            "if [ ! -f databases/clinical_photo_archive.db ]; then " +
                "echo NO_DB; exit 42; fi; " +
                "P=0; " +
                "if [ -d files/clinical_images ]; then " +
                "P=\$(/system/bin/toybox find files/clinical_images -type f 2>/dev/null | /system/bin/toybox wc -l); fi; " +
                "D=\$(/system/bin/toybox wc -c < databases/clinical_photo_archive.db 2>/dev/null); " +
                "echo OK|\$P|\$D"

        private const val ARCHIVE_COMMAND =
            "set -e; " +
                "if [ -d files/clinical_images ]; then " +
                "exec /system/bin/toybox tar -cf - databases files/clinical_images; " +
                "else exec /system/bin/toybox tar -cf - databases; fi"
    }
}
