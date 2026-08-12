package com.clinicalphotoarchive

import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.clinicalphotoarchive.ui.ClinicalArchiveApp
import com.clinicalphotoarchive.ui.ClinicalArchiveTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        setContent {
            ClinicalArchiveTheme {
                ClinicalArchiveApp()
            }
        }
    }
}
