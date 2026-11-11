package com.familycare.ui
import org.json.JSONArray
import org.json.JSONObject

/** What the PC server found (TMAP) for where a memory photo was taken: its address and places within 300 m. */
data class MemoryPlaceGuess(val address:String,val building:String,val place:FoundPlace?,val places:List<FoundPlace>,val query:String)

/**
 * Fills in the memory composer from the photo's own GPS location: the address, the nearby place as the chosen place,
 * the search box and its results, and the title while it is still the automatic one. The user can still search or pick
 * another place; a title the user typed is never replaced. Pure except [request]/[parse], so it is unit-tested on the JVM.
 */
object MemoryPlaceFill {
    const val DEFAULT_TITLE="여행의 한 장"
    const val PHOTO_LOCATION="사진의 촬영 위치"
    fun request(latitude:Double,longitude:Double):JSONObject=JSONObject().put("point",JSONArray().put(longitude).put(latitude)).put("consent",true)
    fun parse(o:JSONObject):MemoryPlaceGuess{
        fun place(p:JSONObject?)=p?.let{runCatching{FoundPlace(it.getString("name").trim().take(120),it.getDouble("latitude"),it.getDouble("longitude"),it.optString("detail").take(200))}.getOrNull()}
            ?.takeIf{it.name.isNotBlank()&&MapPlaces.valid(it.latitude,it.longitude)}
        val list=o.optJSONArray("places")
        val places=(0 until (list?.length()?:0)).mapNotNull{place(list?.optJSONObject(it))}.take(6)
        return MemoryPlaceGuess(o.optString("address").trim().take(200),o.optString("building").trim().take(120),place(o.optJSONObject("place")),places,o.optString("query").trim().take(120))
    }
    /** Shown after '선택한 장소': the nearby place, else the building, else the address. */
    fun label(g:MemoryPlaceGuess)=g.place?.name?:g.building.ifBlank{g.address}.ifBlank{PHOTO_LOCATION}
    /** Search placeholders that are not real place names, so they never become a title. */
    private val placeholders=setOf("검색한 주소","지도에서 선택한 곳",PHOTO_LOCATION)
    /** The automatic title follows the chosen place; once the user edits the title ([auto] false) it is kept. */
    fun title(current:String,auto:Boolean,placeName:String?)=
        if(auto&&!placeName.isNullOrBlank()&&placeName.trim() !in placeholders)placeName.trim().take(80) else current
    /** The address part of a search result's detail line (without distance or facility notes). */
    fun addressOf(p:FoundPlace)=p.detail.substringBefore(" · ").trim()
    fun info(g:MemoryPlaceGuess)=when{
        g.place!=null->"촬영 위치로 주소와 장소를 채웠어요. 다른 곳이면 아래 목록에서 고르거나 다시 검색하세요."
        g.address.isNotBlank()->"촬영 위치의 주소를 채웠어요. 근처 장소 이름은 찾지 못해 주소로 표시해요. 필요하면 검색해 주세요."
        else->"촬영 위치는 찾았지만 주소를 찾지 못했어요. 필요하면 장소 이름이나 주소를 검색해 주세요."
    }
    fun status(g:MemoryPlaceGuess)=if(g.places.isEmpty())"" else "사진 위치 근처 ${g.places.size}곳 · TMAP"
    /** Applies only while the location is still the photo's own: a place the user already picked wins. */
    fun stillPhotoLocation(latitude:Double?,longitude:Double?,photoLatitude:Double,photoLongitude:Double)=latitude==photoLatitude&&longitude==photoLongitude
}
