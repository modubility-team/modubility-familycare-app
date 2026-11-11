package com.familycare.navigation

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.os.Looper
import android.os.SystemClock

class LocationTracker(private val context:Context,private val changed:(PositionFix?,String)->Unit){
    private val manager=context.getSystemService(LocationManager::class.java)
    private var listening=false
    private val vehicleLocation=PleosLocationProbe(context.applicationContext)
    val sdkStatus get()=vehicleLocation.status
    val permitted get()=context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)==PackageManager.PERMISSION_GRANTED
    // Shown when location is off or no position reaches the GPS provider (the rehearsal tool also pauses
    // emulator location while it waits for the tablet), so it must not blame the location setting alone.
    private val noFix="GPS 위치를 받을 수 없어요 · 위치 설정과 수신 상태를 확인해 주세요"
    private val listener=object:LocationListener{
        override fun onLocationChanged(location:Location){accept(location)}
        override fun onProviderDisabled(provider:String){changed(null,noFix)}
        override fun onProviderEnabled(provider:String){changed(null,"GPS 신호를 찾고 있어요")}
        @Deprecated("Platform callback") override fun onStatusChanged(provider:String?,status:Int,extras:Bundle?){}
    }
    private fun accept(location:Location){
        val simulated=location.isFromMockProvider||Build.HARDWARE.contains("ranchu")||Build.HARDWARE.contains("goldfish")
        val fix=PositionFix(GeoPoint(location.latitude,location.longitude),if(location.hasAccuracy())location.accuracy else Float.POSITIVE_INFINITY,
            location.bearing.takeIf{location.hasBearing()},location.speed.takeIf{location.hasSpeed()},location.elapsedRealtimeNanos/1_000_000,simulated)
        if(fix.point.valid())changed(fix,"GPS 연결됨")
    }
    fun start(){
        if(listening)return
        if(!permitted){changed(null,"정확한 위치 권한이 필요해요");return}
        vehicleLocation.start()
        try{
            // Register even while location is off, so updates resume as soon as it is switched back on.
            manager.requestLocationUpdates(LocationManager.GPS_PROVIDER,200,0f,listener,Looper.getMainLooper());listening=true
            if(!manager.isProviderEnabled(LocationManager.GPS_PROVIDER)){changed(null,noFix);return}
            manager.getLastKnownLocation(LocationManager.GPS_PROVIDER)?.takeIf{SystemClock.elapsedRealtime()-it.elapsedRealtimeNanos/1_000_000<10_000}?.let{accept(it)}
        }catch(_:SecurityException){changed(null,"정확한 위치 권한이 필요해요")}
        catch(_:Exception){changed(null,"GPS 위치 서비스를 시작하지 못했어요")}
    }
    fun stop(){if(listening)runCatching{manager.removeUpdates(listener)};listening=false;vehicleLocation.stop()}
}
