package com.clinicalphotoarchive

import android.app.Application
import com.clinicalphotoarchive.data.ClinicalDatabase

class ClinicalArchiveApplication : Application() {
    val database: ClinicalDatabase by lazy { ClinicalDatabase.getInstance(this) }
}
