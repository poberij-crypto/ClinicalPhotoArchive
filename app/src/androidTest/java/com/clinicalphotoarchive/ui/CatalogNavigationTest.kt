package com.clinicalphotoarchive.ui

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.clinicalphotoarchive.MainActivity
import com.clinicalphotoarchive.ClinicalArchiveApplication
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CatalogNavigationTest {
    @get:Rule val rule=createAndroidComposeRule<MainActivity>()
    @Before fun clearDatabase()=runBlocking {
        val app=rule.activity.application as ClinicalArchiveApplication
        check(app.packageName == "com.clinicalphotoarchive.verification") {
            "UI tests must use the isolated verification application, never a patient database"
        }
        app.archiveOperations.withMutation { app.database.photoDao().deleteAll();app.database.patientDao().deleteAll();app.database.categoryDao().deleteAll() }
    }
    @Test fun categoryAndXrayFlowThenBackToCatalog() {
        rule.waitUntil(5000) { rule.onAllNodesWithText("Архив").fetchSemanticsNodes().isNotEmpty() }
        rule.onAllNodesWithText("Архив").assertCountEquals(1)
        rule.onNodeWithText("Без категории").assertExists()
        rule.onNodeWithContentDescription("Добавить раздел").performClick()
        rule.onNodeWithText("Название раздела").performTextInput("Учебная нозология")
        rule.onNodeWithText("Сохранить").performClick()
        rule.waitUntil(5000) { rule.onAllNodesWithText("Учебная нозология").fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithText("Учебная нозология").performClick()
        rule.onNodeWithContentDescription("Добавить пациента").performClick()
        rule.onNodeWithText("Фамилия *").performTextInput("Учебный")
        rule.onNodeWithText("Сохранить").performClick()
        rule.waitUntil(5000) { rule.onAllNodesWithText("Рентген").fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithText("Рентген").performClick()
        rule.onNodeWithContentDescription("Назад").performClick()
        rule.onNodeWithContentDescription("Каталог разделов").performClick()
        rule.onNodeWithText("Без категории").assertExists()
    }
}
