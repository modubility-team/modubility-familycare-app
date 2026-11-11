package com.familycare.navigation

import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.os.Bundle
import android.widget.Toast
import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.activity.result.contract.ActivityResultContracts

class MainActivity : ComponentActivity() {
    private val care by lazy { CareRuntime.get(this) }
    private val notificationPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { care.publish() }
    private val locationPermission = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { care.navigation.permissionChanged() }
    private var startAfterPermission = false
    private val speedPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        care.speedPermissionChanged(granted)
        if(startAfterPermission && granted) care.startTrip()
        startAfterPermission = false
    }
    private fun askSpeed(start: Boolean) {
        if(!care.engine.ux.showDetails) return
        if(checkSelfPermission("android.car.permission.CAR_SPEED") == PackageManager.PERMISSION_GRANTED) {
            care.speedPermissionChanged(true); if(start) care.startTrip()
        } else { startAfterPermission = start; speedPermission.launch("android.car.permission.CAR_SPEED") }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { MaterialTheme {
            CareScreen(care,onLocationPermission={
                if(care.engine.ux.showDetails)locationPermission.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION,Manifest.permission.ACCESS_COARSE_LOCATION))
            }, onOpenNavigation = {
                if(care.engine.ux.showDetails) {
                    val intent=packageManager.getLaunchIntentForPackage("ai.umos.maps.android.navigation.app")
                    try {
                        if(intent!=null) startActivity(intent)
                        else Toast.makeText(this,"차량의 내비 앱을 찾을 수 없어요",Toast.LENGTH_LONG).show()
                    } catch(_:Exception) { Toast.makeText(this,"차량 메뉴에서 내비를 열어 주세요",Toast.LENGTH_LONG).show() }
                }
            }, onStartTrip = { askSpeed(true) }, onSpeedPermission = { askSpeed(false) }, onResize = {
                if (care.engine.ux.showDetails) requestedOrientation =
                    if (resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE)
                        ActivityInfo.SCREEN_ORIENTATION_PORTRAIT else ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
            }, onNotification = {
                if (care.engine.ux.showDetails && Build.VERSION.SDK_INT >= 33 &&
                    checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
                    notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
            })
        } }
    }

    override fun onStart() {
        super.onStart()
        care.attach()
    }

    override fun onStop() {
        care.detach()
        super.onStop()
    }
}
