package com.familycare.navigation

import android.content.Context
import android.os.Handler
import android.os.Looper
import ai.pleos.playground.location.fused.FusedLocation
import ai.pleos.playground.location.fused.listener.FusedLocationListener
import ai.pleos.playground.location.fused.model.LocationData

/** Vehicle location capability/quality diagnostics; never treats confidence as metres. */
class PleosLocationProbe(private val context:Context){
    private val main=Handler(Looper.getMainLooper())
    private var sdk:FusedLocation?=null
    private var generation=0
    private var initialized=false
    private var callback:FusedLocationListener?=null
    private var lastAt=0L
    private var detail="미확인"
    val status get()=if(lastAt>0&&System.currentTimeMillis()-lastAt !in 0..10000)"차량 융합 위치 지연 · Android GPS 사용" else detail
    fun start(){
        if(sdk!=null)return
        val version=++generation
        try{
            val client=FusedLocation(context)
            if(!client.isSupported()){detail="차량 융합 위치 미지원 · Android GPS 사용";return}
            sdk=client;initialized=true;client.initialize()
            val listener=object:FusedLocationListener{
                override fun onChanged(location:LocationData?){main.post{
                    if(version!=generation)return@post
                    lastAt=location?.timestampMillis?:0
                    detail=if(location!=null&&GeoPoint(location.latitude,location.longitude).valid()&&System.currentTimeMillis()-lastAt in 0..10000)
                        "차량 융합 위치 수신 · 길안내 기준은 Android GPS" else "차량 융합 위치 대기 · Android GPS 사용"
                }}
                override fun onError(exception:Exception){main.post{
                    if(version==generation){lastAt=0;detail="차량 융합 위치 접근 불가 · Android GPS 사용"}
                }}
            }
            callback=listener;client.registerFusedLocationCallback(listener);client.getLastKnownLocation(listener)
            detail="차량 융합 위치 확인 중 · Android GPS 사용"
        }catch(_:Exception){stop();detail="차량 융합 위치 초기화 실패 · Android GPS 사용"}
    }
    fun stop(){
        generation++
        sdk?.let{client->callback?.let{runCatching{client.unregisterFusedLocationCallback(it)}};if(initialized)runCatching{client.release()}}
        callback=null;sdk=null;initialized=false;lastAt=0;detail="차량 융합 위치 확인 중지"
    }
}
