package com.familycare.phone

import java.net.URI

/** Validated credentials are encrypted by ConnectionStore; local HTTP is accepted solely by debug builds. */
data class JoinLink(val origin:String,val session:String,val token:String) {
    fun url()="$origin/join#$session.$token"
    companion object {
        fun parse(raw:String,localDemo:Boolean):JoinLink {
            val u=URI(raw.trim())
            require(u.userInfo==null && u.query==null && u.path in listOf("/join","/navigation","/care")) { "차량에서 발급한 연결 링크를 입력하세요" }
            val host=u.host?.lowercase()?:error("서버 주소를 확인하세요")
            require(u.port in listOf(-1) || u.port in 1..65535) { "포트 번호를 확인하세요" }
            val octets=host.split('.').mapNotNull{it.toIntOrNull()?.takeIf{n->n in 0..255}}
            val privateIp=octets.size==4&&(octets[0]==127||octets[0]==10||octets[0]==192&&octets[1]==168||octets[0]==172&&octets[1] in 16..31)
            require(u.scheme=="https" || localDemo&&u.scheme=="http"&&(host=="localhost"||privateIp)) { "HTTPS 링크 또는 연결한 PC의 내부 Wi-Fi 주소만 사용할 수 있어요" }
            val match=Regex("^([A-Za-z0-9_-]{16})\\.([A-Za-z0-9_-]{43})$").matchEntire(u.fragment.orEmpty())?:error("연결 코드가 올바르지 않아요")
            return JoinLink("${u.scheme}://${u.rawAuthority}",match.groupValues[1],match.groupValues[2])
        }
    }
}
