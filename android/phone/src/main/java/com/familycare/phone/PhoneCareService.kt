package com.familycare.phone

import android.app.*
import android.content.Intent
import android.os.*
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

/** Maintains the user-approved LAN connection independently of the Activity. */
class PhoneCareService:Service(){
    companion object {
        @Volatile var running=false;private set
        @Volatile var lastStatusAt=0L;private set
        @Volatile private var activeLink:JoinLink?=null
        fun ensure(context:android.content.Context,connection:JoinLink){
            if(running&&activeLink==connection)return
            context.startForegroundService(Intent(context,PhoneCareService::class.java).putExtra("link",connection.url()))
        }
    }
    private val store by lazy{ConnectionStore(this)}
    private val main=Handler(Looper.getMainLooper())
    private val worker=Executors.newSingleThreadExecutor()
    private var link:JoinLink?=null
    private var generation=0
    private var busy=false
    private var arrived=false
    private var connectionLost=false
    private val delivered=mutableMapOf<String,String>()
    private val manager get()=getSystemService(NotificationManager::class.java)
    override fun onBind(intent:Intent?)=null
    override fun onCreate(){super.onCreate()
        manager.createNotificationChannel(NotificationChannel("vehicle-connection","차량 연결 유지",NotificationManager.IMPORTANCE_LOW))
        manager.createNotificationChannel(NotificationChannel("care-alerts","가족 케어 알림",NotificationManager.IMPORTANCE_HIGH))
    }
    private fun open()=PendingIntent.getActivity(this,0,Intent(this,PhoneActivity::class.java),PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    private fun notification(text:String,ongoing:Boolean)=Notification.Builder(this,if(ongoing)"vehicle-connection" else "care-alerts")
        .setSmallIcon(android.R.drawable.ic_dialog_info).setContentTitle("WithME").setContentText(text)
        .setStyle(Notification.BigTextStyle().bigText(text)).setContentIntent(open()).setOngoing(ongoing).setAutoCancel(!ongoing)
        .setVisibility(Notification.VISIBILITY_PRIVATE)
    override fun onStartCommand(intent:Intent?,flags:Int,startId:Int):Int{
        if(intent?.action=="stop"){
            val saved=store.read();val previous=saved?.link?:link
            if(previous!=null){store.clearIfMatches(previous);if(saved?.paired==true)Thread{
                runCatching{val c=URL("${previous.origin}/api/sessions/${previous.session}/leave-device").openConnection() as HttpURLConnection
                    try{c.requestMethod="POST";c.doOutput=true;c.connectTimeout=4000;c.readTimeout=5000;c.instanceFollowRedirects=false;c.setRequestProperty("Content-Type","application/json");c.setRequestProperty("Authorization","Bearer ${previous.token}");c.outputStream.use{it.write("{}".toByteArray())};c.inputStream.close()}finally{c.disconnect()}}
            }.start()}
            stopSelf();return START_NOT_STICKY
        }
        val saved=store.read()
        val parsed=if(intent==null)saved?.link else runCatching{JoinLink.parse(intent.getStringExtra("link").orEmpty(),BuildConfig.DEBUG)}.getOrNull()
        if(parsed==null||saved?.link!=parsed){stopSelf();return START_NOT_STICKY}
        if(running&&link==parsed)return START_STICKY
        generation++;link=parsed;busy=false;arrived=false;connectionLost=false;delivered.clear()
        val stop=PendingIntent.getService(this,1,Intent(this,PhoneCareService::class.java).setAction("stop"),PendingIntent.FLAG_IMMUTABLE)
        startForeground(100,notification("차량 상태를 확인 중 · 같은 Wi-Fi 연결이 필요해요",true).addAction(Notification.Action.Builder(null,"연결 해제",stop).build()).build())
        running=true;activeLink=parsed;main.removeCallbacks(tick);main.post(tick);return START_STICKY
    }
    private fun alert(id:Int,text:String){if(getSharedPreferences("care-notifications",0).getBoolean("enabled",false))runCatching{manager.notify(id,notification(text,false).build())}}
    private fun updateConnection(text:String){
        val stop=PendingIntent.getService(this,1,Intent(this,PhoneCareService::class.java).setAction("stop"),PendingIntent.FLAG_IMMUTABLE)
        runCatching{manager.notify(100,notification(text,true).addAction(Notification.Action.Builder(null,"연결 해제",stop).build()).build())}
    }
    private val tick=object:Runnable{override fun run(){
        val c=link?:return
        if(store.read()?.link!=c){stopSelf();return}
        if(!busy){busy=true;val version=generation;worker.execute{
            val result=runCatching{
                val http=URL("${c.origin}/api/sessions/${c.session}/status").openConnection() as HttpURLConnection
                try{http.requestMethod="POST";http.connectTimeout=4000;http.readTimeout=5000;http.instanceFollowRedirects=false;http.doOutput=true
                    http.setRequestProperty("Content-Type","application/json");http.setRequestProperty("Authorization","Bearer ${c.token}")
                    val client=getSharedPreferences("client",0).getString("id","")
                    http.outputStream.use{it.write(JSONObject().put("client",client).toString().toByteArray())}
                    if(http.responseCode==403||http.responseCode==410)throw Ended()
                    check(http.responseCode==200)
                    val bytes=http.inputStream.use{input->val out=java.io.ByteArrayOutputStream();val buffer=ByteArray(4096);while(true){val count=input.read(buffer);if(count<0)break;check(out.size()+count<=524288);out.write(buffer,0,count)};out.toByteArray()};JSONObject(bytes.toString(Charsets.UTF_8))
                }finally{http.disconnect()}
            }
            main.post{if(version!=generation)return@post;busy=false
                result.onSuccess{data->
                    val saved=store.read()
                    if(saved?.link!=c||!store.saveVerified(c,saved.paired,data.optInt("expiresIn",0))){stopSelf();return@onSuccess}
                    lastStatusAt=SystemClock.elapsedRealtime()
                    if(data.optBoolean("stale")){if(!connectionLost){alert(101,"차량 상태 미확인 · 차량과 네트워크를 직접 확인하세요");updateConnection("차량 응답 확인 중 · 연결 정보 유지")};connectionLost=true;return@onSuccess}
                    connectionLost=false
                    manager.cancel(101);updateConnection("차량 연결 중 · 앱을 나가도 연결을 유지합니다")
                    if(!getSharedPreferences("care-notifications",0).getBoolean("enabled",false)){delivered.clear();return@onSuccess}
                    val prefs=getSharedPreferences("care-notifications",0)
                    val remote=data.optJSONObject("notificationPreferences")?:JSONObject()
                    val state=data.optJSONObject("state")?:JSONObject()
                    if(state.optBoolean("arrived")&&!arrived&&prefs.getBoolean("arrival",true)&&remote.optBoolean("arrival",true))alert(102,"차량이 목적지에 도착했어요")
                    arrived=state.optBoolean("arrived")
                    val alerts=data.optJSONArray("alerts")
                    val active=mutableSetOf<String>()
                    val inbox=data.optJSONArray("careInbox")
                    if(inbox!=null)(0 until inbox.length()).forEach{i->val r=inbox.getJSONObject(i);val k="inbox:"+r.optString("id");active.add(k);if(delivered[k]==null)alert(200+kotlin.math.abs(k.hashCode()%10000),"담당 보호자 요청: "+r.optString("title"));delivered[k]="shown"}
                    val situation=data.optJSONObject("situation")
                    if(situation?.optBoolean("restDue")==true){val k="situation-rest";active.add(k);val stamp=com.familycare.ui.RestStopText.arrivalKey(situation);if(delivered[k]!=stamp)alert(200+kotlin.math.abs(k.hashCode()%10000),com.familycare.ui.RestStopText.phone(situation));delivered[k]=stamp}
                    if(alerts!=null)(0 until alerts.length()).forEach{i->val a=alerts.getJSONObject(i);val key=a.optString("code");active.add(key)
                        val stamp="${a.optLong("startedAt")}:${a.optInt("level")}:${a.optBoolean("escalated")}:${a.optBoolean("acknowledged")}"
                        val receive=if(key=="rear")prefs.getBoolean("rear",true)else prefs.getBoolean("care",true)
                        if(receive&&remote.optBoolean("care",true)&&!a.optBoolean("acknowledged")&&delivered[key]!=stamp)alert(200+kotlin.math.abs(key.hashCode()%10000),a.optString("title")+if(a.optBoolean("escalated"))" · 아직 확인되지 않았어요" else "")
                        delivered[key]=stamp
                    }
                    (delivered.keys-active).forEach{key->manager.cancel(200+kotlin.math.abs(key.hashCode()%10000))}
                    delivered.keys.retainAll(active)
                }.onFailure{error->
                    if(error is Ended){store.clearIfMatches(c);alert(101,"차량 연결이 종료됐어요. 새 QR로 연결하세요");stopSelf()}
                    else if(!connectionLost){connectionLost=true;updateConnection("네트워크 재연결 중 · 같은 Wi-Fi를 확인하세요");alert(101,"차량 서버 연결 끊김 · 상태를 직접 확인하세요")}
                }
            }
        }}
        main.postDelayed(this,5000)
    }}
    private class Ended:Exception()
    override fun onDestroy(){generation++;running=false;activeLink=null;link=null;main.removeCallbacks(tick);worker.shutdownNow();stopForeground(STOP_FOREGROUND_REMOVE);super.onDestroy()}
}
