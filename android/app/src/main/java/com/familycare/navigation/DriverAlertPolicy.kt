package com.familycare.navigation

data class DriverNotice(val text:String,val alertCode:String?=null,val requestIds:Set<String> = emptySet(),val feedbackId:Int?=null)

/** Short bounded announcements; pickup speech is built by the server from GPS address and an explicit landmark. */
class DriverAlertPolicy {
    private val heardAlerts=mutableSetOf<String>()
    private val alertTimes=mutableMapOf<String,Long>()
    private val heardRequests=mutableSetOf<String>()
    private val firstSeen=mutableMapOf<String,Long>()
    private var feedback=0
    private var nextAt=0L
    private var feedbackAt=Long.MIN_VALUE/2
    fun next(now:Long,alerts:List<CareAlert>,requests:List<PassengerRequest>,feedbackId:Int,feedbackText:String):DriverNotice?{
        heardAlerts.retainAll(alerts.map{it.code}.toSet())
        val ids=requests.map{it.id}.toSet()
        heardRequests.retainAll(ids);firstSeen.keys.retainAll(ids)
        requests.forEach{firstSeen.putIfAbsent(it.id,now)}
        if(feedbackId<feedback)feedback=feedbackId
        if(now<nextAt)return null
        alerts.sortedByDescending{it.level}.firstOrNull{
            it.code !in heardAlerts && now-(alertTimes[it.code]?:Long.MIN_VALUE/2)>=120000
        }?.let{return DriverNotice(it.title,alertCode=it.code)}
        val fresh=requests.filter{it.status=="pending"&&it.id !in heardRequests&&now-firstSeen.getValue(it.id)<=60000}
        fresh.firstOrNull{it.kind=="pickup"}?.let{return DriverNotice(it.title.take(120),requestIds=setOf(it.id))}
        if(fresh.isNotEmpty()){
            val text=if(fresh.size>1)"동승자 요청이 도착했어요. 정차 후 확인해 주세요." else when(fresh[0].kind){
                "destination"->"새 목적지 제안이 도착했어요. 음성 명령으로 경유할 수 있어요."
                "waypoint"->"새 경유지 제안이 도착했어요. 음성 명령으로 경유할 수 있어요."
                else->when(fresh[0].title){
                    "다음 안전한 장소에서 쉬고 싶어요"->"동승자가 휴식을 요청했어요. 다음 안전한 장소에서 쉬어 가세요."
                    "조금 더 시원하게 해 주세요"->"동승자가 조금 더 시원하게 해 달라고 요청했어요."
                    "조금 더 따뜻하게 해 주세요"->"동승자가 조금 더 따뜻하게 해 달라고 요청했어요."
                    "음악 소리를 줄여 주세요"->"동승자가 음악 소리를 줄여 달라고 요청했어요."
                    else->"동승자 요청이 도착했어요. 정차 후 확인해 주세요."
                }
            }
            return DriverNotice(text,requestIds=fresh.map{it.id}.toSet())
        }
        if(feedbackId!=feedback&&feedbackText.isNotBlank()&&now-feedbackAt>=30000)
            return DriverNotice(feedbackText,feedbackId=feedbackId)
        return null
    }
    fun delivered(notice:DriverNotice,now:Long){
        notice.alertCode?.let{heardAlerts.add(it);alertTimes[it]=now}
        heardRequests.addAll(notice.requestIds)
        notice.feedbackId?.let{feedback=it;feedbackAt=now}
        nextAt=now+15000
    }
    fun deferred(now:Long){nextAt=now+10000}
}
