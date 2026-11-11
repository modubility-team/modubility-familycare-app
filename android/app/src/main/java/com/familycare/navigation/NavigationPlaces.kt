package com.familycare.navigation

import android.content.Context

data class NavigationPlace(val name:String,val point:GeoPoint,val time:Long=System.currentTimeMillis())

/** User-created places only; route/traffic/POI responses are never persisted. Recent choices expire in a day. */
class NavigationPlaces(context:Context){
    private val prefs=context.getSharedPreferences("navigation-user-places",Context.MODE_PRIVATE)
    private val history=mutableListOf<NavigationPlace>()
    fun recent(name:String,point:GeoPoint){history.removeAll{it.point.distance(point)<30};history.add(0,NavigationPlace(name,point));while(history.size>10)history.removeAt(history.lastIndex)}
    fun recents():List<NavigationPlace>{history.removeAll{System.currentTimeMillis()-it.time !in 0..82_800_000};return history.toList()}
    fun saved():List<NavigationPlace> = listOf("집","회사","즐겨찾기 1","즐겨찾기 2","즐겨찾기 3").mapNotNull{slot->
        val pieces=prefs.getString(slot,null)?.split(",")?:return@mapNotNull null
        val lat=pieces.getOrNull(0)?.toDoubleOrNull()?:return@mapNotNull null
        val lon=pieces.getOrNull(1)?.toDoubleOrNull()?:return@mapNotNull null
        GeoPoint(lat,lon).takeIf{it.valid()}?.let{NavigationPlace(slot,it)}
    }
    fun save(slot:String,point:GeoPoint){require(point.valid());prefs.edit().putString(slot,"${point.latitude},${point.longitude}").apply()}
    fun remove(slot:String){prefs.edit().remove(slot).apply()}
    fun clearHistory(){history.clear()}
}
