package com.familycare.navigation

/**
 * Which shared photo the car fetches in full next (for the large map cards and the photo viewer).
 * The photo being viewed goes first, then the rest in shared order; each is tried at most [tries] times per connection.
 */
object MemoryImageQueue {
    fun next(ids:List<String>,cached:Set<String>,misses:Map<String,Int>,preferred:String="",tries:Int=3):String?{
        fun wanted(id:String)=id.isNotBlank()&&id !in cached&&(misses[id]?:0)<tries
        return preferred.takeIf{it in ids&&wanted(it)}?:ids.firstOrNull(::wanted)
    }
}
