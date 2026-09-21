package com.clinicalphotoarchive.migrator

import org.junit.Assert.*
import org.junit.Test

class LegacyArchiveTest {
    private class Fixture(
        val files: Boolean = true,
        val images: Boolean = true,
        val size: String = "36864\n",
        val failAt: String? = null
    ) {
        val commands = mutableListOf<List<String>>()
        val archive = LegacyArchive { command ->
            commands += command
            assertFalse(command.contains("/system/bin/sh"))
            assertFalse(command.any { it.contains('|') || it.contains("$(") })
            val op = if (command.first() == "/system/bin/am") "force-stop" else command[3]
            if (op == failAt) CommandResult(1, "", "Permission denied: $op")
            else {
                val output = when (op) {
                    "force-stop" -> ""
                    "stat" -> size
                    "test" -> ""
                    "ls" -> if (command.last() == ".") {
                        if (files) "databases\nfiles\n" else "databases\n"
                    } else if (images) "clinical_images\n" else ""
                    "find" -> (1..16).joinToString("") { "files/clinical_images/photo $it\nname.jpg\u0000" }
                    else -> error("Unexpected command $command")
                }
                CommandResult(0, output, "")
            }
        }
    }

    @Test fun reportedNumbersAreDataNotCommands() {
        assertEquals("OK|16|36864", Fixture().archive.probe())
    }

    @Test fun missingOptionalDirectoriesProduceZeroPhotos() {
        for (fixture in listOf(Fixture(files = false), Fixture(images = false))) {
            assertEquals("OK|0|36864", fixture.archive.probe())
            assertFalse(fixture.commands.any { it.contains("find") })
        }
    }

    @Test fun emptyOrInvalidDatabaseIsRejected() {
        for (size in listOf("0", "-1", "", "not a number")) {
            assertThrows(IllegalStateException::class.java) { Fixture(size = size).archive.probe() }
        }
    }

    @Test fun accessErrorsCannotBecomeSuccessfulProbes() {
        for (op in listOf("stat", "ls", "find", "test")) {
            val error = assertThrows(IllegalStateException::class.java) { Fixture(failAt = op).archive.probe() }
            assertTrue(error.message!!.contains("Permission denied"))
        }
    }

    @Test fun archiveStopsAppAndIncludesWholeDatabaseDirectory() {
        for (images in listOf(true, false)) {
            val fixture = Fixture(images = images)
            val command = fixture.archive.prepareArchive()
            assertEquals(listOf("/system/bin/am", "force-stop", "com.clinicalphotoarchive"), fixture.commands.first())
            assertEquals(listOf("/system/bin/run-as", "com.clinicalphotoarchive", "/system/bin/toybox", "tar", "-cf", "-", "databases") +
                if (images) listOf("files/clinical_images") else emptyList<String>(), command)
        }
    }

    @Test fun failedStopAbortsBeforeReadingOrArchiving() {
        val fixture = Fixture(failAt = "force-stop")
        assertThrows(IllegalStateException::class.java) { fixture.archive.prepareArchive() }
        assertEquals(1, fixture.commands.size)
    }
}
