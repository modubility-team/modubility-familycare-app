package com.familycare.navigation

/**
 * Driver speech such as "강남역으로 안내해 줘" or "가까운 주유소 찾아 줘" becomes a place search.
 * Rule based and conservative: family proposals, stops, pickups, questions and negations return null so
 * the existing waypoint interpreter (or a clarification) handles them. Nothing here changes a route; the
 * caller searches TMAP, lets the driver pick a result when it is not unambiguous, then plans and starts.
 */
object VoiceDestination {
    data class Request(val query:String,val searchOnly:Boolean)
    private enum class Kind { GO, SEARCH, CHANGE }
    private class Ending(val text:String,val kind:Kind,val needsParticle:Boolean=false)
    private val endings=(listOf("가자","가줘","가주세요","가줄래","가줄래요","가요","갈래","갈래요","갈게","갈게요","가고싶어","가고싶어요","갑시다","가보자",
        "출발해","출발해줘","출발하자","출발해주세요","안내해","안내해줘","안내해주세요","안내해줄래","안내부탁해","안내부탁해요",
        "길안내","길안내해줘","길안내해주세요","길찾아줘","길찾아주세요","경로찾아줘","경로안내","경로안내해줘","데려다줘","데려다주세요","데려다줄래",
        "가는길","가는길알려줘","가는길알려주세요","가는길안내해줘","가는길찾아줘").map{Ending(it,Kind.GO)}+
        listOf("안내","출발").map{Ending(it,Kind.GO,needsParticle=true)}+
        listOf("찾아줘","찾아주세요","찾아줄래","찾아봐","찾아봐줘","검색","검색해","검색해줘","검색해주세요","검색해봐").map{Ending(it,Kind.SEARCH)}+
        listOf("바꿔","바꿔줘","바꿔주세요","변경","변경해","변경해줘","변경해주세요","설정","설정해","설정해줘","설정해주세요","해줘","해주세요","찍어줘").map{Ending(it,Kind.CHANGE)})
        .sortedByDescending{it.text.length}
    // Stops, pickups and places the family sent stay with the waypoint interpreter.
    private val family=listOf("추천","제안","보내준","보낸","보내온","가족이","경유","들러","들렀","들르","들려","들리","찍고","중간","거쳐","추가","픽업",
        "데리러","태우","태워","모시러","아까","그곳","이곳","저곳","거기","저기","그쪽","저쪽","이쪽","그장소","이장소","저장소","일정","말한","얘기한","알려준")
    private val negation=Regex("말고|지마|지말|말아|말자|안가|안갈|가지마|싫|취소|그만|않|아니")
    private val question=Regex("어디야|어디지|어디니|어디에있|얼마나|얼마야|몇분|몇시|언제|뭐야|맞아|맞지|있어|있니|있나|됐어|됐니|막혀|걸려")
    // Several actions in one sentence ("강남역 가서 커피 마시고 …") are not a single destination.
    private val severalActions=Regex("가서|하고|먹고|마시고|보고|그리고|그다음|다음에")
    private val leading=setOf("음","어","아","저","저기요","그럼","그러면","그래","자","이제","지금","우리","나","나를","날","저를","빨리","얼른","바로","일단","좀",
        "그냥","한번","다시","당장","내비","내비게이션","네비","네비게이션","목적지","목적지를","목적지는","목적지은","목적지을","도착지","도착지를","도착지는",
        "새","새로","새로운","여기서","근처","근처에","근처의","근처에서","주변","주변에","주변의","인근","인근의","가까운","가까이","가장","제일","젤",
        "같이","함께","가는","길에","가는길에","경로에","경로상")
    private val trailing=setOf("좀","한번","빨리","바로","얼른","지금","다시","같이","함께")
    private val references=setOf("여기","어디","아무데나","아무곳","목적지","도착지","원래","기존","처음","이전","최종","원래목적지","최종목적지","새","새로운",
        "다른","다음","거","것","곳","데")
    private val nonPlaces=setOf("음성","음악","노래","사진","뉴스","날씨","알림","요청","경로","길","소리","볼륨","라디오","전화","메시지","문자","온도","에어컨",
        "창문","시트","좌석","문","안내","길안내","경유지")
    private val homes=setOf("집","우리집","내집","자택")
    private val offices=setOf("회사","직장","사무실","우리회사","내회사")

    fun compact(text:String)=text.lowercase().filter{it.isLetterOrDigit()}
    private fun finalConsonant(c:Char)=if(c in '가'..'힣')(c.code-0xAC00)%28 else -1
    /** Korean direction particle for a place name: 강남역으로, 서울로. */
    fun ro(name:String):String{val f=name.trimEnd().lastOrNull()?.let(::finalConsonant)?:0;return if(f>0&&f!=8)"으로" else "로"}
    /** "집" and "회사" use the places the driver saved on the destination screen. */
    fun savedSlot(query:String):String?=when(compact(query)){in homes->"집";in offices->"회사";else->null}

    internal fun stripParticle(token:String):String{
        val t=token
        fun before()=finalConsonant(t[t.length-2])
        return when{
            t.length>2&&(t.endsWith("으로")||t.endsWith("까지")||t.endsWith("이야")||t.endsWith("이요")||t.endsWith("예요"))->t.dropLast(2)
            t.length>3&&t.endsWith("이에요")->t.dropLast(3)
            // 로 follows a vowel or ㄹ (서울로, 제주도로) or a Latin/digit name (COEX로); 대학로·종로 keep it.
            t.length>2&&t.endsWith("로")&&before().let{it==0||it==8||it==-1}->t.dropLast(1)
            t.length>1&&t.endsWith("에")->t.dropLast(1)
            t.length>2&&t.endsWith("을")&&before()>0->t.dropLast(1)
            t.length>2&&t.endsWith("를")&&before()==0->t.dropLast(1)
            else->t
        }
    }
    private fun headBefore(spaced:String,compactIndex:Int):String{
        var seen=0
        for(i in spaced.indices){if(seen==compactIndex)return spaced.substring(0,i).trim();if(spaced[i].isLetterOrDigit())seen++}
        return spaced.trim()
    }

    fun parse(text:String):Request?{
        val spaced=text.replace(Regex("[.,!?~。！？…\"'“”‘’]")," ").replace(Regex("\\s+")," ").trim()
        val all=compact(spaced)
        if(all.length !in 2..80||all.none{it in '가'..'힣'})return null
        if(negation.containsMatchIn(all)||question.containsMatchIn(all))return null
        val destinationWord="목적지" in all||"도착지" in all
        val ending=endings.firstOrNull{all.endsWith(it.text)&&(it.kind!=Kind.CHANGE||destinationWord)}
        val head=when{
            ending!=null->headBefore(spaced,all.length-ending.text.length)
            all.startsWith("목적지")||all.startsWith("도착지")->spaced
            else->return null
        }
        val kind=ending?.kind?:Kind.GO
        val headCompact=compact(head)
        if(headCompact.isEmpty()||family.any{it in headCompact}||severalActions.containsMatchIn(headCompact))return null
        val tokens=head.split(' ').filter{it.isNotBlank()}.toMutableList()
        if(ending?.needsParticle==true&&listOf("으로","로","까지","에").none{tokens.last().endsWith(it)})return null
        while(tokens.isNotEmpty()&&compact(tokens.first()) in leading)tokens.removeAt(0)
        while(tokens.isNotEmpty()&&compact(tokens.last()) in trailing)tokens.removeAt(tokens.lastIndex)
        // "강남역을 목적지로 설정해 줘"
        if(tokens.size>1&&compact(tokens.last()).let{it.startsWith("목적지")||it.startsWith("도착지")})tokens.removeAt(tokens.lastIndex)
        if(tokens.isEmpty())return null
        tokens[tokens.lastIndex]=stripParticle(tokens.last())
        val query=tokens.joinToString(" ").trim()
        val c=compact(query)
        if(c in references||c in nonPlaces||c.length>40||c.length<2&&savedSlot(query)==null)return null
        return Request(query,kind==Kind.SEARCH)
    }

    /** Exact names first, then names starting with / containing the query; the server's distance order is kept within each group. */
    fun <T> rank(query:String,items:List<T>,name:(T)->String):List<T>{
        val q=compact(query)
        fun group(n:String)=when{n==q->0;n.startsWith(q)->1;q in n->2;else->3}
        return items.withIndex().sortedWith(compareBy({group(compact(name(it.value)))},{it.index})).map{it.value}
    }
    /** Guide straight away only when the result is unambiguous; otherwise the driver picks a number. */
    fun <T> direct(query:String,ranked:List<T>,name:(T)->String):T?{
        if(ranked.size==1)return ranked[0]
        val q=compact(query)
        return ranked.filter{compact(name(it))==q}.singleOrNull()
    }

    private val strongOrdinals=listOf(listOf("첫번째","첫째","1번","일번"),listOf("두번째","둘째","2번"),listOf("세번째","셋째","3번","삼번"),
        listOf("네번째","넷째","4번"),listOf("다섯번째","다섯째","5번","오번"))
    private val weakOrdinals=listOf(listOf("하나","처음","첫번","1"),listOf("둘","2"),listOf("셋","3"),listOf("넷","4"),listOf("다섯","5"))
    /** Spoken choice among listed places: "두 번째", "2번", or a name that only one result contains. */
    fun choice(text:String,names:List<String>):Int?{
        val s=compact(text)
        strongOrdinals.forEachIndexed{i,words->if(words.any{s.startsWith(it)})return i}
        val named=names.withIndex().filter{(_,name)->val n=compact(name);n.length>=2&&(n in s||s.length>=2&&s in n)}
        if(named.size==1)return named[0].index
        val bare=s.removeSuffix("이요").removeSuffix("요").removeSuffix("번").removeSuffix("으로").removeSuffix("로").removeSuffix("거")
        weakOrdinals.forEachIndexed{i,words->if(bare in words)return i}
        return null
    }
    fun ordinal(text:String):Int?=choice(text,emptyList())
}
