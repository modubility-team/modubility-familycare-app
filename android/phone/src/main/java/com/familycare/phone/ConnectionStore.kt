package com.familycare.phone

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import org.json.JSONObject
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Only a validated session credential is persisted; grants and vehicle state are always fetched anew. */
class ConnectionStore(context:Context){
    data class Saved(val link:JoinLink,val paired:Boolean,val expiresAt:Long)
    private val prefs=context.applicationContext.getSharedPreferences("vehicle-session",0)
    companion object { private val lock=Any();private const val ALIAS="family-care-session-v1" }
    private fun key():SecretKey {
        val ks=KeyStore.getInstance("AndroidKeyStore").apply{load(null)}
        (ks.getKey(ALIAS,null) as? SecretKey)?.let{return it}
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES,"AndroidKeyStore").apply{
            init(KeyGenParameterSpec.Builder(ALIAS,KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).setKeySize(256).build())
        }.generateKey()
    }
    fun read():Saved?=synchronized(lock){readLocked()}
    private fun readLocked():Saved? {
        val raw=prefs.getString("sealed",null)?:return null
        return runCatching{
            val envelope=JSONObject(raw)
            val cipher=Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE,key(),GCMParameterSpec(128,Base64.decode(envelope.getString("iv"),Base64.NO_WRAP)))
            val data=JSONObject(cipher.doFinal(Base64.decode(envelope.getString("data"),Base64.NO_WRAP)).toString(Charsets.UTF_8))
            val expiry=data.getLong("expiresAt")
            check(expiry>System.currentTimeMillis())
            Saved(JoinLink.parse(data.getString("link"),BuildConfig.DEBUG),data.getBoolean("paired"),expiry)
        }.getOrElse{prefs.edit().clear().commit();null}
    }
    fun saveVerified(link:JoinLink,paired:Boolean,expiresIn:Int,replace:Boolean=false):Boolean=synchronized(lock){
        val old=readLocked()
        if(!replace&&old?.link!=link)return@synchronized false
        if(expiresIn<=0){if(old?.link==link)prefs.edit().clear().commit();return@synchronized false}
        val expiry=System.currentTimeMillis()+expiresIn.coerceAtMost(7200)*1000L
        if(old?.link==link&&old.paired==paired&&kotlin.math.abs(old.expiresAt-expiry)<60000)return@synchronized true
        val cipher=Cipher.getInstance("AES/GCM/NoPadding").apply{init(Cipher.ENCRYPT_MODE,key())}
        val plain=JSONObject().put("link",link.url()).put("paired",paired).put("expiresAt",expiry).toString().toByteArray(Charsets.UTF_8)
        val data=cipher.doFinal(plain)
        prefs.edit().putString("sealed",JSONObject().put("iv",Base64.encodeToString(cipher.iv,Base64.NO_WRAP)).put("data",Base64.encodeToString(data,Base64.NO_WRAP)).toString()).commit()
    }
    fun clearIfMatches(link:JoinLink)=synchronized(lock){if(readLocked()?.link==link)prefs.edit().clear().commit();Unit}
}
