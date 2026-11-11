package com.familycare.phone

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest

/**
 * This phone's copies of the shared memory photos (app-private storage, newest 30). The full photo is a file per id;
 * the small index (no full photo) feeds the gallery while not connected and the sync plan.
 */
class MemoryStore(context:Context){
    private val dir=File(context.filesDir,"shared-memories").apply{mkdirs()}
    private val prefs=context.getSharedPreferences("memory-sync",0)
    private fun file(id:String)=File(dir,MessageDigest.getInstance("SHA-256").digest(id.toByteArray()).joinToString(""){"%02x".format(it)}+".json")
    init{importAlbum(File(context.filesDir,"family-album"))}
    /** Photos saved earlier with '지금 연 사진을 앨범에 저장' join the kept copies once, so they can be shared again too. */
    private fun importAlbum(album:File){
        if(prefs.getBoolean("album-imported",false))return
        album.listFiles()?.filter{it.name.endsWith(".json")&&it.length()<=400000}?.forEach{f->runCatching{save(JSONObject(f.readText()))}}
        prefs.edit().putBoolean("album-imported",true).apply()
    }
    var enabled:Boolean
        get()=prefs.getBoolean("enabled",true)
        set(value){prefs.edit().putBoolean("enabled",value).apply()}
    fun entries():List<JSONObject> =runCatching{JSONArray(prefs.getString("index","[]")).let{a->(0 until a.length()).map{a.getJSONObject(it)}}}.getOrDefault(emptyList())
    fun locals()=entries().map{MemorySyncPlan.Local(it.optString("id"),it.optString("captured"))}
    fun full(id:String):JSONObject?=runCatching{JSONObject(file(id).readText())}.getOrNull()?.takeIf{it.optString("id")==id}
    private fun write(list:List<JSONObject>){val a=JSONArray();list.forEach{a.put(it)};prefs.edit().putString("index",a.toString()).apply()}
    /** Keeps a photo as the server returned it (id, title, captured, latitude, longitude, image, thumbnail). */
    fun save(m:JSONObject):Boolean{
        val id=m.optString("id");val image=m.optString("image");val thumb=m.optString("thumbnail")
        if(id.length !in 1..80||image.isBlank()||image.length>350000||thumb.isBlank()||!m.optDouble("latitude").isFinite()||!m.optDouble("longitude").isFinite())return false
        val copy=JSONObject();listOf("id","title","captured","latitude","longitude","image","thumbnail").forEach{copy.put(it,m.opt(it)?:return false)}
        file(id).writeText(copy.toString())
        val small=JSONObject();listOf("id","title","captured","latitude","longitude","thumbnail").forEach{small.put(it,copy.get(it))}
        val list=listOf(small)+entries().filterNot{it.optString("id")==id}
        val drop=MemorySyncPlan.overflow(list.map{MemorySyncPlan.Local(it.optString("id"),it.optString("captured"))}).toSet()
        drop.forEach{file(it).delete()}
        write(list.filterNot{it.optString("id") in drop})
        return true
    }
    fun remove(id:String){file(id).delete();write(entries().filterNot{it.optString("id")==id})}
    fun clear(){dir.listFiles()?.forEach{it.delete()};prefs.edit().remove("index").remove("session").remove("seen").apply()}
    /** Ids seen before this poll in [session]; remembers [now] as seen too. */
    fun seen(session:String,now:Collection<String>):Set<String>{
        val (before,after)=MemorySyncPlan.seen(prefs.getString("session",null),prefs.getStringSet("seen",emptySet())!!.toSet(),session,now)
        if(after!=before||prefs.getString("session",null)!=session)prefs.edit().putString("session",session).putStringSet("seen",after).apply()
        return before
    }
}
