package com.clinicalphotoarchive.migrator

import android.content.Context
import android.os.ParcelFileDescriptor
import androidx.annotation.Keep
import java.io.OutputStream
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import kotlin.system.exitProcess

@Keep
class ArchiveUserService : IArchiveService.Stub {
    constructor()

    @Keep
    constructor(@Suppress("UNUSED_PARAMETER") context: Context)

    override fun destroy() { exitProcess(0) }

    private val archive = LegacyArchive(::runCommand)

    override fun probe(): String = try {
        archive.probe()
    } catch (e: Exception) {
        "ERROR|${sanitize(e.message ?: e.javaClass.simpleName)}"
    }

    override fun writeArchive(output: ParcelFileDescriptor): String = try {
        // Close the service's descriptor even when preflight/force-stop fails.
        ParcelFileDescriptor.AutoCloseOutputStream(output).use { out ->
            val command = archive.prepareArchive()
            val result = execute(command, out, 300)
            check(result.exitCode == 0) {
                result.stderr.ifBlank { "Архив не создан, код ${result.exitCode}" }
            }
            val bytesCopied = result.stdout.toLong()
            check(bytesCopied > 1024L) { "Полученный архив слишком мал или пуст" }
            out.flush()
            "OK|$bytesCopied"
        }
    } catch (e: Exception) {
        "ERROR|${sanitize(e.message ?: e.javaClass.simpleName)}"
    }

    private fun runCommand(command: List<String>): CommandResult = execute(command, null, 25)

    /** Drain both pipes concurrently; apply the deadline before waiting for EOF. */
    private fun execute(command: List<String>, output: OutputStream?, timeout: Long): CommandResult {
        val process = ProcessBuilder(command).start()
        val readers = Executors.newFixedThreadPool(2) { task ->
            Thread(task, "migrator-process-io").apply { isDaemon = true }
        }
        try {
            process.outputStream.close()
            val stdout = readers.submit(Callable {
                process.inputStream.use { input ->
                    if (output == null) input.bufferedReader(Charsets.UTF_8).readText()
                    else input.copyTo(output).toString()
                }
            })
            val stderr = readers.submit(Callable {
                process.errorStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            })
            if (!process.waitFor(timeout, TimeUnit.SECONDS)) {
                throw TimeoutException("Команда превысила время ожидания")
            }
            return CommandResult(process.exitValue(), stdout.get(5, TimeUnit.SECONDS), stderr.get(5, TimeUnit.SECONDS))
        } finally {
            if (process.isAlive) process.destroyForcibly()
            readers.shutdownNow()
            process.inputStream.close()
            process.errorStream.close()
        }
    }

    private fun sanitize(value: String): String =
        value.replace('|', '/').replace('\n', ' ').replace('\r', ' ').trim().take(400)

    companion object {
        const val TARGET_PACKAGE = LegacyArchive.TARGET_PACKAGE
    }
}
