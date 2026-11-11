package com.familycare.phone

/**
 * Keeps the shared memory photos alive across reconnects: each phone (내 차 and Family Care) keeps a copy of the
 * photos it saw in the session, and after the PC server restarts (a new session, empty) it shares them again so both
 * apps and the car map see them. Pure so it is unit-tested on the JVM.
 */
object MemorySyncPlan {
    const val SERVER_LIMIT=12
    const val LOCAL_LIMIT=30
    data class Local(val id:String,val captured:String)
    /** One step per poll: keep a copy of a photo first, else share one back; [forget] runs every time. */
    data class Step(val fetch:String?,val upload:String?,val forget:List<String>)
    /**
     * [server]: photo ids in the session now. [seen]: ids this phone already saw in this same session; one that is gone
     * now was deleted by someone, so the copy is forgotten instead of shared again. [failed]: uploads refused this session.
     */
    fun next(server:List<String>,local:List<Local>,seen:Set<String>,failed:Set<String> = emptySet()):Step{
        val onServer=server.toSet()
        val forget=local.filter{it.id in seen&&it.id !in onServer}.map{it.id}
        val kept=local.filterNot{it.id in forget}
        val localIds=kept.map{it.id}.toSet()
        val fetch=server.firstOrNull{it !in localIds}
        val upload=if(onServer.size>=SERVER_LIMIT)null
            else kept.filter{it.id !in onServer&&it.id !in seen&&it.id !in failed}.maxByOrNull{it.captured}?.id
        return Step(fetch,upload,forget)
    }
    /** The ids already seen before this poll, and what to remember after it (reset when the session changes). */
    fun seen(storedSession:String?,stored:Set<String>,session:String,now:Collection<String>):Pair<Set<String>,Set<String>>{
        val before=if(storedSession==session)stored else emptySet()
        return before to (before+now)
    }
    /** Newest photos first, at most [LOCAL_LIMIT]; returns the ids to drop. */
    fun overflow(local:List<Local>,limit:Int=LOCAL_LIMIT)=local.sortedByDescending{it.captured}.drop(limit).map{it.id}
}
