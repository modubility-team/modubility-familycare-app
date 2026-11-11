package com.familycare.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.text.TextPaint
import android.text.TextUtils
import android.util.Base64
import kotlin.math.ceil

/** Map marker bitmaps for shared photos. Sizes come from [MemoryPinLayout]. */
object MemoryPinIcons {
    private val navy=Color.rgb(0,44,95)
    private val ink=Color.rgb(16,21,28)

    /** A marker bitmap and the point of it (fractions of its size) that sits on the photo's location. */
    class Icon(val bitmap:Bitmap,val anchorX:Float,val anchorY:Float)

    /** Decodes a shared JPEG (thumbnail or full photo) and draws it as a marker; null when it cannot be read. */
    fun icon(encoded:String,density:Float,style:MemoryPinStyle,title:String="",count:Int=1,scale:Float=1f):Icon?{
        val source=decode(encoded,MemoryPinLayout.decodeTarget(style,density,scale))?:return null
        return runCatching{iconOf(source,density,style,title,count,scale)}.getOrNull().also{source.recycle()}
    }

    fun fromEncoded(encoded:String,density:Float,style:MemoryPinStyle,title:String="",count:Int=1):Bitmap?=icon(encoded,density,style,title,count)?.bitmap

    /** Sub-samples large originals so a 1024 px photo is never fully decoded just for a marker. */
    fun decode(encoded:String,targetShort:Int):Bitmap?=runCatching{
        require(encoded.isNotEmpty()&&encoded.length<=350000)
        val bytes=Base64.decode(encoded,Base64.NO_WRAP)
        val bounds=BitmapFactory.Options().apply{inJustDecodeBounds=true}
        BitmapFactory.decodeByteArray(bytes,0,bytes.size,bounds)
        require(bounds.outWidth in 1..2048&&bounds.outHeight in 1..2048)
        val options=BitmapFactory.Options().apply{inSampleSize=MemoryPinLayout.sampleSize(bounds.outWidth,bounds.outHeight,targetShort)}
        BitmapFactory.decodeByteArray(bytes,0,bytes.size,options)
    }.getOrNull()

    fun draw(source:Bitmap,density:Float,style:MemoryPinStyle,title:String="",count:Int=1,scale:Float=1f):Bitmap=iconOf(source,density,style,title,count,scale).bitmap

    private fun iconOf(source:Bitmap,density:Float,style:MemoryPinStyle,title:String,count:Int,scale:Float):Icon{
        val box=MemoryPinLayout.box(style,source.width,source.height,scale)
        val width=if(style==MemoryPinStyle.Thumbnail)(box.width*density).toInt().coerceAtLeast(68) else ceil(box.width*density).toInt().coerceAtLeast(1)
        val height=if(style==MemoryPinStyle.Thumbnail)width else ceil(box.height*density).toInt().coerceAtLeast(1)
        val result=Bitmap.createBitmap(width,height,Bitmap.Config.ARGB_8888)
        val canvas=Canvas(result)
        val paint=Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        when(style){
            MemoryPinStyle.Thumbnail->thumbnail(canvas,paint,source,density,width.toFloat())
            MemoryPinStyle.Compact->compact(canvas,paint,source,density,width.toFloat())
            MemoryPinStyle.Card->card(canvas,paint,source,box,density,tip(box,density),title,count,MemoryPinLayout.textScale(scale))
        }
        return Icon(result,.5f,if(style==MemoryPinStyle.Card)tip(box,density)/height else .5f)
    }

    /** Pixel row of the card's tip: everything below it is clear tap space. */
    private fun tip(box:MemoryPinLayout.Box,density:Float)=(MemoryPinLayout.SHADOW+box.cardHeight+MemoryPinLayout.TAIL)*density

    private fun src(crop:IntArray)=Rect(crop[0],crop[1],crop[0]+crop[2],crop[1]+crop[3])

    /** The original phone-map marker: photo in a white rounded square with a navy edge. */
    private fun thumbnail(c:Canvas,paint:Paint,source:Bitmap,density:Float,size:Float){
        val inset=MemoryPinLayout.THUMBNAIL_INSET*density;val corner=MemoryPinLayout.CORNER*density
        paint.style=Paint.Style.FILL;paint.color=Color.WHITE
        c.drawRoundRect(RectF(0f,0f,size,size),corner,corner,paint)
        c.drawBitmap(source,src(MemoryPinLayout.crop(source.width,source.height,1f,1f)),RectF(inset,inset,size-inset,size-inset),paint)
        paint.color=navy;paint.style=Paint.Style.STROKE;paint.strokeWidth=2*density
        c.drawRoundRect(RectF(density,density,size-density,size-density),corner,corner,paint)
    }

    /** Not parked: a small round photo, the size of the destination mark. */
    private fun compact(c:Canvas,paint:Paint,source:Bitmap,density:Float,size:Float){
        val r=size/2f;val inner=r-MemoryPinLayout.COMPACT_RING*density
        paint.style=Paint.Style.FILL;paint.color=Color.WHITE
        c.drawCircle(r,r,r-density*.5f,paint)
        val crop=MemoryPinLayout.crop(source.width,source.height,1f,1f)
        val scale=inner*2/crop[2]
        val shader=BitmapShader(source,Shader.TileMode.CLAMP,Shader.TileMode.CLAMP)
        shader.setLocalMatrix(Matrix().apply{setScale(scale,scale);postTranslate(r-inner-crop[0]*scale,r-inner-crop[1]*scale)})
        paint.shader=shader;c.drawCircle(r,r,inner,paint);paint.shader=null
        paint.style=Paint.Style.STROKE;paint.strokeWidth=1.5f*density;paint.color=navy
        c.drawCircle(r,r,r-.75f*density,paint)
    }

    /** Parked: a large photo card with the title under it and a tip pointing at where it was taken. [grow] scales the title and badge. */
    private fun card(c:Canvas,paint:Paint,source:Bitmap,box:MemoryPinLayout.Box,density:Float,tip:Float,title:String,count:Int,grow:Float){
        fun px(dp:Float)=dp*density
        val left=px(MemoryPinLayout.SHADOW);val top=px(MemoryPinLayout.SHADOW)
        val right=left+px(box.cardWidth);val bottom=top+px(box.cardHeight);val cx=(left+right)/2f
        val shape=Path().apply{addRoundRect(RectF(left,top,right,bottom),px(MemoryPinLayout.CORNER),px(MemoryPinLayout.CORNER),Path.Direction.CW)}
        val tail=Path().apply{
            moveTo(cx-px(MemoryPinLayout.TAIL_HALF),bottom-px(1f));lineTo(cx,tip-px(1f));lineTo(cx+px(MemoryPinLayout.TAIL_HALF),bottom-px(1f));close()
        }
        shape.op(tail,Path.Op.UNION)
        paint.style=Paint.Style.FILL;paint.color=Color.WHITE
        paint.setShadowLayer(px(3f),0f,px(1f),Color.argb(90,0,0,0))
        c.drawPath(shape,paint)
        paint.clearShadowLayer()
        val photo=RectF(left+px(MemoryPinLayout.FRAME),top+px(MemoryPinLayout.FRAME),left+px(MemoryPinLayout.FRAME+box.photoWidth),top+px(MemoryPinLayout.FRAME+box.photoHeight))
        c.drawBitmap(source,src(MemoryPinLayout.crop(source.width,source.height,box.photoWidth,box.photoHeight)),photo,paint)
        paint.style=Paint.Style.STROKE;paint.strokeWidth=px(1.5f);paint.color=navy
        c.drawPath(shape,paint)
        val titlePaint=TextPaint(Paint.ANTI_ALIAS_FLAG).apply{color=ink;textSize=px(MemoryPinLayout.TITLE*grow);typeface=Typeface.DEFAULT_BOLD;textAlign=Paint.Align.CENTER}
        val label=TextUtils.ellipsize(title.trim(),titlePaint,photo.width()-px(8f*grow),TextUtils.TruncateAt.END).toString()
        val metrics=titlePaint.fontMetrics
        c.drawText(label,cx,(photo.bottom+bottom)/2f-(metrics.ascent+metrics.descent)/2f,titlePaint)
        if(count>1){
            val badge=TextPaint(Paint.ANTI_ALIAS_FLAG).apply{color=Color.WHITE;textSize=px(MemoryPinLayout.BADGE_TEXT*grow);typeface=Typeface.DEFAULT_BOLD;textAlign=Paint.Align.CENTER}
            val words="${count}장";val w=badge.measureText(words)+px(16f*grow);val h=px(MemoryPinLayout.BADGE*grow);val gap=px(6f*grow)
            val pill=RectF(photo.right-gap-w,photo.top+gap,photo.right-gap,photo.top+gap+h)
            paint.style=Paint.Style.FILL;paint.color=navy
            c.drawRoundRect(pill,h/2f,h/2f,paint)
            val m=badge.fontMetrics
            c.drawText(words,pill.centerX(),pill.centerY()-(m.ascent+m.descent)/2f,badge)
        }
    }
}
