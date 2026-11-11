package com.familycare.phone

import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.lifecycle.ViewModelProvider

class PhoneActivity:ComponentActivity(){
    val model:PhoneModel by lazy{ViewModelProvider(this)[PhoneModel::class.java]}
    override fun onCreate(state:Bundle?){
        super.onCreate(state)
        acceptLink(intent)
        setContent{PhoneScreen(model,openOtherApp=::openOtherApp){name,lat,lon->
            val query=if(lat!=null&&lon!=null)"$lat,$lon ($name)" else name
            val link=Uri.parse("geo:0,0?q="+Uri.encode(query))
            try{startActivity(Intent(Intent.ACTION_VIEW,link))}
            catch(_:Exception){Toast.makeText(this,"이 기기에 지도 앱이 없어요. 목적지는 차량으로 제안할 수 있어요.",Toast.LENGTH_LONG).show()}
        }}
    }
    override fun onNewIntent(intent:Intent){super.onNewIntent(intent);acceptLink(intent)}
    fun openOtherApp(){
        val link=model.connectionForOtherApp()?:return
        val product=if(BuildConfig.PRODUCT=="navigation")"care" else "navigation"
        val target="com.familycare.phone.$product"
        // Limit the credential to our counterpart, signed by the same developer.
        if(packageManager.checkSignatures(packageName,target)!=PackageManager.SIGNATURE_MATCH){
            Toast.makeText(this,"같은 설치 묶음의 WithME 내 차·WithME 가족 앱을 둘 다 설치해 주세요.",Toast.LENGTH_LONG).show()
            return
        }
        val next=Intent(Intent.ACTION_VIEW,Uri.parse("familycare-$product://join?url="+Uri.encode(link)))
            .setClassName(target,"com.familycare.phone.PhoneActivity")
            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        try{startActivity(next);model.clearHandoff()}catch(_:Exception){
            Toast.makeText(this,"다른 앱을 열지 못했어요. 최신 WithME 내 차·WithME 가족 APK를 설치해 주세요.",Toast.LENGTH_LONG).show()
        }
    }
    private fun acceptLink(intent:Intent){
        val data=intent.data
        if(data?.scheme=="familycare-${BuildConfig.PRODUCT}"&&data.host=="join"){
            // Receiving a link only fills the field; the user explicitly connects.
            if(!model.receiveLink(data.getQueryParameter("url").orEmpty())){
                Toast.makeText(this,"연결 링크를 확인해 주세요. 차량 QR을 다시 열어 주세요.",Toast.LENGTH_LONG).show()
            }
            intent.data=null
        }
    }
    override fun onStart(){super.onStart();model.resume()}
    override fun onStop(){model.pause();super.onStop()}
}
