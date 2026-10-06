package com.clinicalphotoarchive

import android.app.Application
import com.clinicalphotoarchive.data.ClinicalDatabase
import com.clinicalphotoarchive.util.ArchiveOperationCoordinator
import com.clinicalphotoarchive.util.ArchiveRecoveryJournal

class ClinicalArchiveApplication : Application() {
    val database: ClinicalDatabase by lazy { ClinicalDatabase.getInstance(this) }
    val archiveJournal by lazy { ArchiveRecoveryJournal(this) }
    val archiveOperations by lazy { ArchiveOperationCoordinator { archiveJournal.recover(database) } }
}
