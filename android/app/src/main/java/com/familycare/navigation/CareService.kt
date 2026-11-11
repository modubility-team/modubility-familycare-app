package com.familycare.navigation
import android.app.*
import android.content.Intent
import android.os.IBinder

/** User-started, no boot receiver and no automatic restart after process death. */
class CareService:Service(){
    override fun onCreate(){
        super.onCreate()
        getSystemService(NotificationManager::class.java).createNotificationChannel(NotificationChannel("care","WithME 이동 연결",NotificationManager.IMPORTANCE_LOW))
        val open=PendingIntent.getActivity(this,0,Intent(this,MainActivity::class.java),PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        startForeground(42,Notification.Builder(this,"care").setSmallIcon(R.drawable.ic_stat_withme).setContentTitle("WithME 연결 중")
            .setContentText("정차 후 앱에서 이동·상태 확인·동승자 연결을 종료할 수 있어요").setContentIntent(open).setOngoing(true).build())
        CareRuntime.get(this).attachService()
    }
    override fun onStartCommand(intent:Intent?,flags:Int,startId:Int)=START_NOT_STICKY
    override fun onBind(intent:Intent?):IBinder?=null
    override fun onDestroy(){CareRuntime.get(this).detachService();super.onDestroy()}
}
