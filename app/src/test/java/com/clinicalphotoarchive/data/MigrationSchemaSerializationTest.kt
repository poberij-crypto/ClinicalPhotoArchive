package com.clinicalphotoarchive.data

import androidx.room.migration.bundle.SchemaBundle
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

class MigrationSchemaSerializationTest {
    @Test fun migrationHelperCanReadBothExportedSchemas() {
        for(version in 1..2) {
            File("schemas/com.clinicalphotoarchive.data.ClinicalDatabase/$version.json").inputStream().use {
                assertEquals(version,SchemaBundle.deserialize(it).database.version)
            }
        }
    }
}
