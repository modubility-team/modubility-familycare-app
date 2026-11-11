package com.familycare.navigation

/** Use actual ignition when available; explicitly unsupported platforms use the approved connection lifetime. */
class IgnitionSession(private var supported:Boolean?=true) {
    var epoch=""; private set
    val on get()=epoch.isNotEmpty()
    val mode get()=when(supported){true->"ignition";false->"connection";null->"unavailable"}
    fun configure(value:Boolean?){if(value!=supported){supported=value;epoch=""}}
    fun update(value:Double?,sharing:Boolean=false){
        val active=when(supported){true->value==4.0||value==5.0;false->sharing;null->false}
        if(active){if(epoch.isEmpty())epoch=java.util.UUID.randomUUID().toString()}else epoch=""
    }
}
