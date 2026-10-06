package com.clinicalphotoarchive.ui

import android.graphics.Bitmap
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.clinicalphotoarchive.*
import com.clinicalphotoarchive.data.*
import com.clinicalphotoarchive.util.ImageFiles
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class PhotoViewerTest {
    @get:Rule val rule=createAndroidComposeRule<MainActivity>()
    private var image: File?=null
    @After fun cleanup() { image?.delete() }
    @Test fun viewerZoomControlsResetAndClose() {
        val app=rule.activity.application as ClinicalArchiveApplication
        check(app.packageName == "com.clinicalphotoarchive.verification") {
            "UI tests must use the isolated verification package"
        }
        runBlocking { app.archiveOperations.withMutation {
            val id=app.database.patientDao().insert(PatientEntity(surname="Фото"))
            val file=File(ImageFiles.imageDir(app),"TEST_${UUID.randomUUID()}.jpg")
            val bitmap=Bitmap.createBitmap(400,200,Bitmap.Config.ARGB_8888)
            file.outputStream().use {bitmap.compress(Bitmap.CompressFormat.JPEG,90,it)};bitmap.recycle();image=file
            app.database.photoDao().insert(PhotoEntity(patientId=id,section="before",localPath=file.absolutePath,description="Учебный снимок"))
        } }
        rule.onNodeWithText("Без категории").performClick()
        rule.waitUntil(5000) {rule.onAllNodesWithText("Фото").fetchSemanticsNodes().isNotEmpty()}
        rule.onNodeWithText("Фото").performClick()
        rule.waitUntil(5000) {rule.onAllNodesWithContentDescription("Учебный снимок").fetchSemanticsNodes().isNotEmpty()}
        rule.onNodeWithContentDescription("Учебный снимок").performClick()
        rule.onNodeWithContentDescription("Закрыть").assertExists()
        rule.waitUntil(5000) {rule.onAllNodesWithContentDescription("Увеличить").fetchSemanticsNodes().any {!it.config.contains(SemanticsProperties.Disabled)}}
        rule.onNodeWithContentDescription("Увеличить").performClick()
        rule.onNodeWithText("1.3×").assertExists()
        rule.onNodeWithText("Сбросить").performClick()
        rule.onNodeWithText("1.0×").assertExists()
        rule.onNodeWithContentDescription("Закрыть").performClick()
        rule.onNodeWithContentDescription("Закрыть").assertDoesNotExist()
    }
}
