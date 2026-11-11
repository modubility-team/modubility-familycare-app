package com.familycare.navigation

object ControlPolicy {
    fun valid(value:Double,min:Double,max:Double)=value.isFinite()&&value in min..max&&value==kotlin.math.floor(value)
}
