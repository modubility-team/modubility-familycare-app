package com.familycare.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.unit.dp

/** Original geometric line symbols; parent controls supply visible/accessibility labels. */
@Composable fun FamilyGlyph(name:String,color:Color,modifier:Modifier=Modifier.size(26.dp)){
    Canvas(modifier){
        val stroke=Stroke(1.8f,cap=StrokeCap.Round,join=StrokeJoin.Round)
        scale(size.width/24f,size.height/24f,pivot=Offset.Zero){
            fun line(x:Float,y:Float,a:Float,b:Float)=drawLine(color,Offset(x,y),Offset(a,b),1.8f,StrokeCap.Round)
            fun path(vararg xy:Float){val p=Path();p.moveTo(xy[0],xy[1]);for(i in 2 until xy.size step 2)p.lineTo(xy[i],xy[i+1]);drawPath(p,color,style=stroke)}
            fun circle(x:Float,y:Float,r:Float)=drawCircle(color,r,Offset(x,y),style=stroke)
            fun rect(x:Float,y:Float,w:Float,h:Float,r:Float=2f)=drawRoundRect(color,Offset(x,y),Size(w,h),CornerRadius(r),style=stroke)
            when(name){
                "home"->{path(3f,11f,12f,3f,21f,11f);path(5f,10f,5f,21f,10f,21f,10f,15f,14f,15f,14f,21f,19f,21f,19f,10f)}
                "route"->{path(12f,2f,21f,21f,12f,17f,3f,21f,12f,2f);line(12f,7f,12f,12f)}
                "search"->{circle(10f,10f,6f);line(15f,15f,21f,21f)}
                "pin"->{circle(12f,9f,3f);val p=Path().apply{moveTo(12f,22f);cubicTo(9f,18f,4f,13f,4f,9f);cubicTo(4f,-1f,20f,-1f,20f,9f);cubicTo(20f,13f,15f,18f,12f,22f)};drawPath(p,color,style=stroke)}
                "car"->{path(3f,16f,3f,11f,6f,5f,18f,5f,21f,11f,21f,16f,3f,16f);line(3f,11f,21f,11f);line(6f,16f,6f,20f);line(18f,16f,18f,20f);line(6f,13f,8f,13f);line(16f,13f,18f,13f)}
                "lock"->{rect(4f,10f,16f,12f,2f);drawArc(color,180f,180f,false,Offset(7f,1f),Size(10f,14f),style=stroke);line(12f,14f,12f,18f)}
                "power"->{drawArc(color,-55f,290f,false,Offset(3f,3f),Size(18f,18f),style=stroke);line(12f,1f,12f,11f)}
                "pet"->{circle(5f,8f,2f);circle(10f,4f,2f);circle(16f,4f,2f);circle(21f,8f,2f);path(5f,19f,7f,14f,12f,11f,17f,14f,19f,19f,16f,22f,12f,20f,8f,22f,5f,19f)}
                "people"->{circle(9f,7f,3f);path(2f,21f,2f,17f,5f,14f,13f,14f,16f,17f,16f,21f);path(17f,4f,20f,6f,20f,9f,17f,11f);path(20f,14f,23f,17f,23f,21f)}
                "bell"->{path(4f,17f,6f,14f,6f,9f);drawArc(color,180f,180f,false,Offset(6f,3f),Size(12f,12f),style=stroke);path(18f,9f,18f,14f,20f,17f,4f,17f);drawArc(color,0f,180f,false,Offset(9f,18f),Size(6f,4f),style=stroke)}
                "chat"->{path(3f,3f,21f,3f,21f,17f,10f,17f,5f,21f,5f,17f,3f,17f,3f,3f);line(7f,8f,17f,8f);line(7f,12f,14f,12f)}
                "cool"->{line(12f,2f,12f,22f);line(3f,7f,21f,17f);line(3f,17f,21f,7f);path(9f,4f,12f,7f,15f,4f);path(9f,20f,12f,17f,15f,20f)}
                "warm"->{for(x in listOf(5f,12f,19f)){val p=Path().apply{moveTo(x,21f);cubicTo(x-5,15f,x+5,10f,x,3f)};drawPath(p,color,style=stroke)}}
                "volume"->{path(3f,9f,7f,9f,13f,4f,13f,20f,7f,15f,3f,15f,3f,9f);line(18f,12f,23f,12f)}
                "rest"->{path(3f,5f,17f,5f,17f,16f,14f,19f,6f,19f,3f,16f,3f,5f);path(17f,7f,22f,7f,22f,12f,17f,12f);line(2f,23f,19f,23f)}
                "clock"->{circle(12f,12f,9f);path(12f,6f,12f,12f,16f,14f)}
                "link"->{path(9f,4f,5f,4f,2f,7f,2f,12f,5f,15f,9f,15f);path(15f,9f,19f,9f,22f,12f,22f,17f,19f,20f,15f,20f);line(8f,12f,16f,12f)}
                "qr"->{rect(2f,2f,7f,7f,0f);rect(15f,2f,7f,7f,0f);rect(2f,15f,7f,7f,0f);path(15f,14f,15f,18f,19f,18f,19f,22f);line(22f,13f,22f,16f);line(12f,22f,15f,22f)}
                "refresh"->{drawArc(color,40f,290f,false,Offset(4f,4f),Size(16f,16f),style=stroke);path(21f,3f,21f,9f,15f,9f)}
                "arrow"->{path(9f,5f,16f,12f,9f,19f)}
                "back"->{path(15f,5f,8f,12f,15f,19f)}
                "expand"->{path(3f,9f,3f,3f,9f,3f);path(15f,3f,21f,3f,21f,9f);path(21f,15f,21f,21f,15f,21f);path(9f,21f,3f,21f,3f,15f)}
                "play"->{path(6f,3f,21f,12f,6f,21f,6f,3f)}
                "stop"->rect(5f,5f,14f,14f,1f)
                "check"->{path(4f,12f,10f,18f,21f,6f)}
                "close"->{line(5f,5f,19f,19f);line(19f,5f,5f,19f)}
                "save"->{path(5f,3f,19f,3f,19f,22f,12f,17f,5f,22f,5f,3f)}
                "trash"->{line(3f,6f,21f,6f);path(9f,6f,9f,3f,15f,3f,15f,6f);path(5f,6f,6f,21f,18f,21f,19f,6f);line(10f,10f,10f,17f);line(14f,10f,14f,17f)}
                else->circle(12f,12f,8f)
            }
        }
    }
}
