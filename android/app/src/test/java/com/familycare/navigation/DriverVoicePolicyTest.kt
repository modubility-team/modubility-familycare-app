package com.familycare.navigation
import org.junit.Test
import org.junit.Assert.*
class DriverVoicePolicyTest {
 @Test fun exactKoreanApprovals(){listOf("승인", "승인해 줘.", "승인해 주세요!", "이 요청 승인").forEach{assertEquals(it,VoiceCommand.APPROVE,DriverVoicePolicy.command(it))}}
 @Test fun negationNeverApproves(){listOf("승인하지 마", "승인하지 말아 주세요").forEach{assertEquals(it,VoiceCommand.REJECT,DriverVoicePolicy.command(it))};listOf("승인해 줘 아니 거절", "아직 승인하지 않을래", "승인하는게 맞나", "모두 승인", "승인하지마 승인").forEach{assertEquals(it,VoiceCommand.UNKNOWN,DriverVoicePolicy.command(it))}}
 @Test fun nextReadCancelAndRepeat(){assertEquals(VoiceCommand.NEXT,DriverVoicePolicy.command("다음 요청"));assertEquals(VoiceCommand.READ,DriverVoicePolicy.command("요청 읽어 줘"));assertEquals(VoiceCommand.REPEAT,DriverVoicePolicy.command("다시 안내해 줘"));assertEquals(VoiceCommand.CANCEL,DriverVoicePolicy.command("취소"))}
 @Test fun snapshotCannotMoveToAnotherRequest(){val r=PassengerRequest("a","note","쉬고 싶어요");assertTrue(DriverVoicePolicy.unchanged(r,r));assertFalse(DriverVoicePolicy.unchanged(r,r.copy(id="b")));assertFalse(DriverVoicePolicy.unchanged(r,r.copy(title="다른 요청")));assertFalse(DriverVoicePolicy.unchanged(r,r.copy(status="confirmed")));assertFalse(DriverVoicePolicy.unchanged(r,null))}
 @Test fun parameterChangeInvalidatesDecision(){val r=PassengerRequest("a","climate","온도",target=22.0);assertFalse(DriverVoicePolicy.unchanged(r,r.copy(target=28.0)));assertFalse(DriverVoicePolicy.unchanged(r,r.copy(options="{\"area\":\"ROW_2_LEFT\"}")))}
 @Test fun movingComfortNotesAndPickup(){assertTrue(DriverVoicePolicy.allowedMoving("note","",true));assertTrue(DriverVoicePolicy.allowedMoving("climate","",true));assertTrue(DriverVoicePolicy.allowedMoving("control","hvac.fan:ROW_1_RIGHT",true));assertTrue(DriverVoicePolicy.allowedMoving("control","seat.vent:ROW_2_LEFT",true));listOf("door.lock:HOOD","window.position:ROW_1_RIGHT","seat.position:ROW_1_LEFT","hvac.fake:x").forEach{assertFalse(DriverVoicePolicy.allowedMoving("control",it,true))};listOf("permission","profile","hold").forEach{assertFalse(DriverVoicePolicy.allowedMoving(it,"",true))}}
 @Test fun pickupExactAndNegated(){
  listOf("픽업 경유해 줘", "동승자 픽업해 줘", "픽업 장소 들러 줘").forEach{assertEquals(VoiceCommand.PICKUP,DriverVoicePolicy.command(it))}
  listOf("픽업하지 마", "픽업 경유해 줘 아니 취소", "다른 사람 픽업해 줘").forEach{assertEquals(VoiceCommand.UNKNOWN,DriverVoicePolicy.command(it))}
  assertTrue(DriverVoicePolicy.allowedMoving("pickup","",true));assertFalse(DriverVoicePolicy.allowedMoving("pickup","",false))
 }
 @Test fun missingVehicleDataBlocksMoving(){assertFalse(DriverVoicePolicy.allowedMoving("climate","",false));assertFalse(DriverVoicePolicy.allowedMoving("note","",false))}
}
