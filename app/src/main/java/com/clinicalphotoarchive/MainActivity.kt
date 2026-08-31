package com.clinicalphotoarchive

import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.clinicalphotoarchive.ui.AppViewModel
import com.clinicalphotoarchive.ui.BackupControls
import com.clinicalphotoarchive.ui.ClinicalArchiveApp
import com.clinicalphotoarchive.ui.ClinicalArchiveTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        setContent {
            ClinicalArchiveTheme {
                val vm: AppViewModel = viewModel()
                Box(Modifier.fillMaxSize()) {
                    ClinicalArchiveApp(vm)
                    BackupControls(
                        vm = vm,
                        modifier = Modifier
                            .align(Alignment.BottomStart)
                            .padding(start = 16.dp, bottom = 20.dp)
                    )
                }
            }
        }
    }
}
