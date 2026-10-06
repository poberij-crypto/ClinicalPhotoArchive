package com.clinicalphotoarchive.ui

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModelStore
import androidx.test.core.app.ApplicationProvider
import com.clinicalphotoarchive.ClinicalArchiveApplication
import com.clinicalphotoarchive.util.ImageFiles
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLooper
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[28])
class AppStartupTest {
    @Test fun startupRecoversUnreferencedImageAndReleasesBusyState() {
        val app=ApplicationProvider.getApplicationContext<ClinicalArchiveApplication>()
        val orphan=File(ImageFiles.imageDir(app),"TEST_startup.jpg").apply {writeBytes(byteArrayOf(1))}
        app.archiveJournal.registerFiles(listOf(orphan.absolutePath))
        val vm=AppViewModel(app,SavedStateHandle())
        val store=ViewModelStore().apply {put("test",vm)}
        try {
            val deadline=System.nanoTime()+10_000_000_000L
            while((orphan.exists() || vm.busy.value) && System.nanoTime()<deadline) {
                ShadowLooper.idleMainLooper();Thread.sleep(10)
            }
            ShadowLooper.idleMainLooper()
            assertFalse("Startup recovery must remove the orphan",orphan.exists())
            assertFalse("Startup recovery must release UI busy state",vm.busy.value)
            assertNull(vm.archiveMessage.value)
        } finally {store.clear();orphan.delete()}
    }
}
