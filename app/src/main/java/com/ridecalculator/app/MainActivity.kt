
package com.ridecalculator.app

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.ridecalculator.app.service.MonitoringService

class MainActivity : ComponentActivity() {

    private val notificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) {
            // Monitoring can still proceed if notifications are denied.
            launchScreenCaptureConsent()
        }

    private val screenCaptureLauncher =
        registerForActivityResult(
            ActivityResultContracts.StartActivityForResult()
        ) { result ->
            if (result.resultCode == Activity.RESULT_OK && result.data != null) {
                val serviceIntent = Intent(this, MonitoringService::class.java).apply {
                    action = MonitoringService.ACTION_START
                    putExtra(MonitoringService.EXTRA_RESULT_CODE, result.resultCode)
                    putExtra(MonitoringService.EXTRA_RESULT_DATA, result.data)
                }

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    startForegroundService(serviceIntent)
                } else {
                    startService(serviceIntent)
                }
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    Column(
                        modifier = Modifier.fillMaxSize().padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        Text("Ride Calculator", style = MaterialTheme.typography.headlineMedium)

                        Spacer(modifier = Modifier.height(24.dp))

                        Button(onClick = { startRideMonitoring() }) {
                            Text("Start Monitoring")
                        }

                        Spacer(modifier = Modifier.height(16.dp))

                        Button(onClick = { stopRideMonitoring() }) {
                            Text("Stop Monitoring")
                        }

                        Spacer(modifier = Modifier.height(20.dp))
                        Text("When prompted, select the inDrive app only (not the entire screen).")
                    }
                }
            }
        }
    }

    private fun startRideMonitoring() {
        if (!Settings.canDrawOverlays(this)) {
            val settingsIntent = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName")
            )
            startActivity(settingsIntent)
            return
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.POST_NOTIFICATIONS
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            launchScreenCaptureConsent()
        }
    }

    private fun launchScreenCaptureConsent() {
        val projectionManager =
            getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager

        screenCaptureLauncher.launch(
            projectionManager.createScreenCaptureIntent()
        )
    }

    private fun stopRideMonitoring() {
        val serviceIntent = Intent(this, MonitoringService::class.java).apply {
            action = MonitoringService.ACTION_STOP
        }

        // Stop is not a foreground-service start; use the normal service start path.
        startService(serviceIntent)
    }
}
