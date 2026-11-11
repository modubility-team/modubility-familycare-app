package com.familycare.ui
import androidx.compose.material3.*
import androidx.compose.runtime.*
import kotlin.math.round
@OptIn(ExperimentalMaterial3Api::class)
@Composable fun PermissionRange(low:Double,high:Double,minimum:Double,maximum:Double,step:Double,onChange:(Double,Double)->Unit){
 fun display(v:Double)=if(step>=1)v.toInt().toString()else "%.1f".format(v)
 Text("허용 범위  ${display(low)} ~ ${display(high)}")
 if(maximum>minimum){
  RangeSlider(value=low.toFloat()..high.toFloat(),onValueChange={v->
   fun snap(n:Float)=(minimum+round((n-minimum)/step)*step).coerceIn(minimum,maximum)
   onChange(snap(v.start),snap(v.endInclusive))
  },valueRange=minimum.toFloat()..maximum.toFloat(),steps=((maximum-minimum)/step).toInt().minus(1).coerceIn(0,99))
 }
}
