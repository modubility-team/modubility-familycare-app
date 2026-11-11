package com.familycare.phone
import android.content.Context
import android.location.*
import android.os.*
class PhoneLocation(context:Context){
 private val manager=context.getSystemService(LocationManager::class.java);private val main=Handler(Looper.getMainLooper());private var listener:LocationListener?=null;private var timeout:Runnable?=null
 fun cancel(){listener?.let{runCatching{manager.removeUpdates(it)}};listener=null;timeout?.let{main.removeCallbacks(it)};timeout=null}
 @Suppress("MissingPermission") fun once(done:(Location?,String)->Unit){
  cancel();var ended=false
  fun finish(l:Location?,message:String){if(!ended){ended=true;cancel();done(l,message)}}
  fun fresh(l:Location)=l.hasAccuracy()&&l.accuracy in 0f..100f&&(SystemClock.elapsedRealtimeNanos()-l.elapsedRealtimeNanos)/1_000_000 in 0..60000
  val callback=object:LocationListener{override fun onLocationChanged(location:Location){if(fresh(location))finish(location,"현재 위치를 받았어요")};override fun onProviderEnabled(provider:String){};override fun onProviderDisabled(provider:String){};@Deprecated("Legacy callback") override fun onStatusChanged(provider:String?,status:Int,extras:Bundle?){}}
  listener=callback
  try{
   val providers=listOf(LocationManager.GPS_PROVIDER,LocationManager.NETWORK_PROVIDER).filter{manager.isProviderEnabled(it)}
   if(providers.isEmpty()){finish(null,"휴대폰의 위치 기능을 켜 주세요");return}
   providers.forEach{manager.requestLocationUpdates(it,1000,0f,callback,Looper.getMainLooper())}
   providers.mapNotNull{manager.getLastKnownLocation(it)}.filter(::fresh).minByOrNull{it.accuracy}?.let{finish(it,"최근 GPS 위치를 받았어요")}
   if(!ended){timeout=Runnable{finish(null,"정확한 GPS를 받지 못했어요. 창가나 실외에서 다시 시도해 주세요")}.also{main.postDelayed(it,30000)}}
  }catch(_:SecurityException){finish(null,"정확한 위치 권한을 허용하세요")}catch(_:IllegalArgumentException){finish(null,"이 휴대폰에서 GPS 위치를 받지 못했어요")}
 }
}
