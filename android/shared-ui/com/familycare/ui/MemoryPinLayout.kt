package com.familycare.ui

import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.round
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/** How shared memory photos are drawn on a map. */
enum class MemoryPinStyle {
    /** Small round photo whenever the car is not parked in P (or a place is being picked): the size of the destination mark, so it never outweighs the road. */
    Compact,
    /** The 68 dp square used on phone maps. */
    Thumbnail,
    /** Large photo card with its title, its tip on the place the photo was taken. Only while parked in P. */
    Card;
    companion object {
        /**
         * Big cards only while parked in P without driving restrictions, also with guidance on screen (a rest stop mid-trip).
         * Moving, stopping in D (e.g. at a light) or picking a place on the map shows small round photos.
         */
        fun forCar(readOnly:Boolean,parked:Boolean,selecting:Boolean)=if(!readOnly&&parked&&!selecting)Card else Compact
    }
}

/** Marker sizes in dp (drawing multiplies by the display density). Pure Kotlin so it can be unit tested. */
object MemoryPinLayout {
    /** Same as the destination mark on the car map (48 dp). */
    const val COMPACT=48f
    const val COMPACT_RING=3f
    const val THUMBNAIL=68f
    const val THUMBNAIL_INSET=5f
    const val CARD_LONG=140f
    const val CARD_SHORT=105f
    const val CARD_SQUARE=124f
    const val FRAME=6f
    const val CAPTION=30f
    const val TAIL=12f
    const val TAIL_HALF=10f
    const val SHADOW=4f
    /** Clear space under the tip so a tap right on the photo's location still lands on the card. */
    const val HIT=8f
    const val CORNER=8f
    const val TITLE=15f
    const val BADGE=22f
    const val BADGE_TEXT=13f
    /** A card may take about 22% of the map's width, and its photo at most 30% of the map's height. */
    const val CARD_WIDTH_SHARE=.22f
    const val CARD_HEIGHT_SHARE=.30f
    const val MIN_SCALE=.85f
    const val MAX_SCALE=2f
    /** Photos taken this close (metres) always share one marker. */
    const val SAME_PLACE=50.0

    /** [width]×[height] is the whole bitmap; the map places ([anchorX], [anchorY]) of it on the photo's location. */
    data class Box(val photoWidth:Float,val photoHeight:Float,val cardWidth:Float,val cardHeight:Float,
        val width:Float,val height:Float,val anchorX:Float,val anchorY:Float)

    /** Photo area of a card: 4:3 for landscape, 3:4 for portrait, square otherwise. */
    fun cardPhoto(sourceWidth:Int,sourceHeight:Int):Pair<Float,Float> = when {
        sourceWidth<=0||sourceHeight<=0->CARD_LONG to CARD_SHORT
        sourceWidth>=sourceHeight*1.15->CARD_LONG to CARD_SHORT
        sourceHeight>=sourceWidth*1.15->CARD_SHORT to CARD_LONG
        else->CARD_SQUARE to CARD_SQUARE
    }

    /**
     * Card size for a map of [mapWidthDp]×[mapHeightDp]: about 22% of the width, the long photo side at most 30% of the height,
     * 0.85×–2× the base size, in 5% steps so markers are not redrawn for tiny changes. A height of 0 means unknown.
     */
    fun cardScale(mapWidthDp:Float,mapHeightDp:Float=0f):Float{
        if(mapWidthDp<=0f)return 1f
        var scale=mapWidthDp*CARD_WIDTH_SHARE/CARD_LONG
        if(mapHeightDp>0f)scale=minOf(scale,mapHeightDp*CARD_HEIGHT_SHARE/CARD_LONG)
        return (scale.coerceIn(MIN_SCALE,MAX_SCALE)*20).roundToInt()/20f
    }

    /** Title, caption and count badge grow with the card, half as fast and at most 1.4×, so big cards keep a calm title. */
    fun textScale(scale:Float)=(1f+(scale-1f)*.5f).coerceIn(1f,1.4f)

    fun box(style:MemoryPinStyle,sourceWidth:Int,sourceHeight:Int,scale:Float=1f):Box = when(style) {
        MemoryPinStyle.Compact->{val photo=COMPACT-COMPACT_RING*2;Box(photo,photo,COMPACT,COMPACT,COMPACT,COMPACT,.5f,.5f)}
        MemoryPinStyle.Thumbnail->{val photo=THUMBNAIL-THUMBNAIL_INSET*2;Box(photo,photo,THUMBNAIL,THUMBNAIL,THUMBNAIL,THUMBNAIL,.5f,.5f)}
        MemoryPinStyle.Card->{
            val (w,h)=cardPhoto(sourceWidth,sourceHeight).let{(a,b)->a*scale to b*scale}
            val cardWidth=w+FRAME*2;val cardHeight=FRAME+h+CAPTION*textScale(scale)
            val tip=SHADOW+cardHeight+TAIL;val height=tip+HIT
            Box(w,h,cardWidth,cardHeight,cardWidth+SHADOW*2,height,.5f,tip/height)
        }
    }

    /** Shortest photo side in pixels a decode must keep for [style] to stay sharp. */
    fun decodeTarget(style:MemoryPinStyle,density:Float,scale:Float=1f):Int=(when(style){
        MemoryPinStyle.Compact->COMPACT-COMPACT_RING*2
        MemoryPinStyle.Thumbnail->THUMBNAIL-THUMBNAIL_INSET*2
        MemoryPinStyle.Card->CARD_SQUARE*scale
    }*density.coerceAtLeast(.5f)).roundToInt().coerceAtLeast(1)

    /** Largest power-of-two BitmapFactory sample size that still keeps the short side at least [targetShort] pixels. */
    fun sampleSize(width:Int,height:Int,targetShort:Int):Int{
        var size=1
        while(minOf(width,height)/(size*2)>=targetShort.coerceAtLeast(1))size*=2
        return size
    }

    /** Centre crop of the source with the target's aspect ratio, as [left, top, width, height] in source pixels. */
    fun crop(sourceWidth:Int,sourceHeight:Int,targetWidth:Float,targetHeight:Float):IntArray{
        val aspect=targetWidth/targetHeight
        return if(sourceWidth>sourceHeight*aspect){
            val w=(sourceHeight*aspect).roundToInt().coerceIn(1,sourceWidth);intArrayOf((sourceWidth-w)/2,0,w,sourceHeight)
        }else{
            val h=(sourceWidth/aspect).roundToInt().coerceIn(1,sourceHeight);intArrayOf(0,(sourceHeight-h)/2,sourceWidth,h)
        }
    }

    /**
     * How far apart (metres) photos may be and still share one marker, so markers do not cover each other at the map's
     * current scale ([metersPerDp], 0 when unknown). Never below [SAME_PLACE]; rounded to half zoom steps (×√2) so small
     * zoom changes do not redraw the markers. Phone thumbnails are never merged.
     */
    fun groupMeters(style:MemoryPinStyle,scale:Float,metersPerDp:Double):Double{
        if(style==MemoryPinStyle.Thumbnail||!metersPerDp.isFinite()||metersPerDp<=0.0)return SAME_PLACE
        // Cards may overlap by a fifth before they merge; dots merge once they touch.
        val sizeDp=if(style==MemoryPinStyle.Card)(CARD_LONG*scale+FRAME*2)*.8 else COMPACT.toDouble()
        val meters=sizeDp*metersPerDp
        if(meters<=SAME_PLACE)return SAME_PLACE
        return SAME_PLACE*2.0.pow(round(2*ln(meters/SAME_PLACE)/ln(2.0))/2)
    }

    /**
     * Photos taken within [meters] of each other share one marker so they do not hide each other.
     * Groups keep the order photos were taken in; the marker shows the newest (last) one with the count.
     */
    fun group(pins:List<MemoryPin>,meters:Double=SAME_PLACE):List<List<MemoryPin>>{
        val groups=mutableListOf<MutableList<MemoryPin>>()
        pins.sortedWith(compareBy<MemoryPin>{it.captured}.thenBy{it.id}).forEach{p->
            val near=groups.firstOrNull{g->distance(g.first().latitude,g.first().longitude,p.latitude,p.longitude)<=meters}
            if(near!=null)near.add(p)else groups.add(mutableListOf(p))
        }
        return groups
    }

    fun distance(lat1:Double,lon1:Double,lat2:Double,lon2:Double):Double{
        val r=Math.toRadians(1.0)
        val a=sin((lat2-lat1)*r/2).let{it*it}+cos(lat1*r)*cos(lat2*r)*sin((lon2-lon1)*r/2).let{it*it}
        return 2*6371000.0*asin(sqrt(a.coerceIn(0.0,1.0)))
    }
}
