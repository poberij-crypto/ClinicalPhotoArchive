package com.clinicalphotoarchive.data

import androidx.room.testing.MigrationTestHelper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CatalogMigrationTest {
    @get:Rule val helper=MigrationTestHelper(InstrumentationRegistry.getInstrumentation(),ClinicalDatabase::class.java)
    @Test fun migratingPreservesPatientPhotoAndForeignKeys() {
        helper.createDatabase("migration-instrumented",1).apply {
            execSQL("INSERT INTO patients VALUES(50,'Иванов','Алексей','','001','Диагноз','Заметка','иванов',10,20)")
            execSQL("INSERT INTO photos VALUES(80,50,'after','/keep.jpg','Описание',30,40)")
            close()
        }
        helper.runMigrationsAndValidate("migration-instrumented",2,true,DatabaseMigrations.MIGRATION_1_2).use { db ->
            db.query("SELECT surname,categoryId FROM patients WHERE id=50").use { c->assertTrue(c.moveToFirst());assertEquals("Иванов",c.getString(0));assertTrue(c.isNull(1)) }
            db.query("SELECT localPath FROM photos WHERE id=80").use { c->assertTrue(c.moveToFirst());assertEquals("/keep.jpg",c.getString(0)) }
            db.query("PRAGMA foreign_key_check").use { assertEquals(0,it.count) }
        }
    }
}
