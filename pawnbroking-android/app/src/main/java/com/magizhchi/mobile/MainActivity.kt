package com.magizhchi.mobile

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.core.content.ContextCompat
import com.magizhchi.mobile.ui.AppNav
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    private val askNotif = registerForActivityResult(
        ActivityResultContracts.RequestPermission()) { /* user choice */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED) {
            askNotif.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        setContent { App() }
    }
}

@Composable
fun App() {
    MaterialTheme {
        Surface { AppNav() }
    }
}
