package com.familycare.navigation
import org.junit.Test
import android.car.Car
import android.car.VehiclePropertyIds
import android.car.hardware.property.CarPropertyManager
import androidx.test.platform.app.InstrumentationRegistry
class IgnitionProbeTest {
 @Test fun readActualIgnition(){
  val c=Car.createCar(InstrumentationRegistry.getInstrumentation().targetContext)
  try{val pm=c.getCarManager(Car.PROPERTY_SERVICE) as CarPropertyManager
   for(id in listOf(VehiclePropertyIds.IGNITION_STATE,VehiclePropertyIds.GEAR_SELECTION)){
    try{val config=pm.getCarPropertyConfig(id);println("IGNITION_PROBE id=$id config=$config value="+pm.getProperty<Any>(id,0))}catch(e:Exception){println("IGNITION_PROBE id=$id error=$e")}
   }
  }finally{c.disconnect()}
 }
}
