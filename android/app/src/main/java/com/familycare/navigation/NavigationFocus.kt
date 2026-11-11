package com.familycare.navigation

import android.car.Car
import android.car.CarAppFocusManager
import android.content.Context
import android.os.Handler
import android.os.Looper

class NavigationFocus(context:Context,private val changed:(Boolean)->Unit){
    private var manager:CarAppFocusManager?=null
    private var closed=false
    private val owner=object:CarAppFocusManager.OnAppFocusOwnershipCallback{
        override fun onAppFocusOwnershipGranted(type:Int){if(!closed)changed(true)}
        override fun onAppFocusOwnershipLost(type:Int){if(!closed)changed(false)}
    }
    private var car:Car?=Car.createCar(context,Handler(Looper.getMainLooper()),Car.CAR_WAIT_TIMEOUT_DO_NOT_WAIT){car,ready->
        if(closed)car.disconnect() else if(ready){
            manager=runCatching{car.getCarManager(Car.APP_FOCUS_SERVICE) as CarAppFocusManager}.getOrNull()
        }else{manager=null;changed(false)}
    }
    fun request():Boolean=runCatching{
        manager?.requestAppFocus(CarAppFocusManager.APP_FOCUS_TYPE_NAVIGATION,owner)==CarAppFocusManager.APP_FOCUS_REQUEST_SUCCEEDED
    }.getOrDefault(false)
    fun release(){runCatching{manager?.abandonAppFocus(owner)};changed(false)}
    fun close(){release();closed=true;manager=null;runCatching{car?.disconnect()};car=null}
}
