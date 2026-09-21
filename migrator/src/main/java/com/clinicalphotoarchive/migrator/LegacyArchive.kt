package com.clinicalphotoarchive.migrator

internal data class CommandResult(val exitCode: Int, val stdout: String, val stderr: String)

/** Commands are argument lists, never shell scripts. No patient data leave the device. */
internal class LegacyArchive(private val run: (List<String>) -> CommandResult) {
    fun probe(): String {
        val size = checked(privateCommand("stat", "-c", "%s", "databases/clinical_photo_archive.db"))
            .trim().toLongOrNull() ?: error("Не удалось прочитать размер базы")
        check(size > 0) { "Файл базы найден, но имеет нулевой размер" }
        val photos = if (hasImages()) {
            checked(privateCommand("find", "files/clinical_images", "-type", "f", "-print0"))
                .count { it == '\u0000' }
        } else 0
        // This is a Kotlin string; its separators never reach a shell.
        return "OK|$photos|$size"
    }

    fun prepareArchive(): List<String> {
        checked(listOf("/system/bin/am", "force-stop", TARGET_PACKAGE))
        probe() // Recheck after stopping, before any output is written.
        return privateCommand("tar", "-cf", "-", "databases") +
            if (hasImages()) listOf("files/clinical_images") else emptyList()
    }

    private fun hasImages(): Boolean {
        if ("files" !in checked(privateCommand("ls", "-a", ".")).lineSequence().toList()) return false
        if ("clinical_images" !in checked(privateCommand("ls", "-a", "files")).lineSequence().toList()) return false
        checked(privateCommand("test", "-d", "files/clinical_images"))
        return true
    }

    private fun checked(command: List<String>): String {
        val result = run(command)
        check(result.exitCode == 0) {
            result.stderr.ifBlank { result.stdout }.ifBlank { "Команда завершилась с кодом ${result.exitCode}" }
        }
        return result.stdout
    }

    companion object {
        const val TARGET_PACKAGE = "com.clinicalphotoarchive"
        fun privateCommand(vararg args: String): List<String> =
            listOf("/system/bin/run-as", TARGET_PACKAGE, "/system/bin/toybox") + args
    }
}
