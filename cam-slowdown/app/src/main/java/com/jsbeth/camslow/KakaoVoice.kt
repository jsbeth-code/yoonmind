package com.jsbeth.camslow

/**
 * v0.16.15 카카오 음성 안내 종류. 주행 중 들은 종류가 설정 ⑤ 목록에 쌓이고, 종류별로 끌 수 있다.
 * 종류 = 음성 코드(VoiceCode) + 안내 대상(GuideObj가 "Caution(KNSafetyCode_Hump)"처럼 생겼으면 그 종류·코드).
 */
object KakaoVoice {
    private val objRe = Regex("""^(\w+)\((KNSafetyCode_\w+)\)""")

    /** 음성 안내 객체에서 종류 키를 만든다(SDK 버전에 따라 이름이 달라도 죽지 않게 리플렉션). */
    fun kind(v: Any): String {
        fun get(name: String): Any? = runCatching { v.javaClass.getMethod(name).invoke(v) }.getOrNull()
        val code = get("getVoiceCode")?.toString() ?: "?"
        val obj = get("getGuideObj")?.toString() ?: return code
        objRe.find(obj)?.let { return "$code|${it.groupValues[1]}|${it.groupValues[2]}" }
        if (obj.startsWith("TRAFFIC")) return "$code|TRAFFIC"
        return code
    }

    private val codeNames = mapOf(
        "KNVoiceCode_Turn" to "회전 안내",
        "KNVoiceCode_Alert" to "경고음(짧은 소리, 추정)",
        "KNVoiceCode_SchoolZone" to "어린이보호구역",
        "KNVoiceCode_StartGuide" to "안내 시작",
        "KNVoiceCode_GPSConnected" to "GPS 연결됨",
        "KNVoiceCode_CheckingRouteChange" to "빠른 길 찾는 중",
        "KNVoiceCode_RouteUnchanged" to "경로 그대로",
        "KNVoiceCode_BusLaneGuide" to "버스전용차로",
    )
    private val safetyNames = mapOf(
        "Hump" to "과속방지턱",
        "TrafficAccidentPos" to "사고 다발 지역",
        "SpeedViolationCamera" to "과속 카메라",
        "SignalAndSpeedViolationCamera" to "신호·과속 카메라",
        "BoxedSpeedViolationCamera" to "박스형 과속 카메라",
        "SpeedViolationBackwardCamera" to "후면 과속 카메라",
    )

    /** 설정 목록에 보일 이름. 모르는 것은 카카오 이름에서 앞머리만 뗀 그대로. */
    fun label(kind: String): String {
        val p = kind.split("|")
        val code = p[0]
        if (p.size >= 3) {
            val what = p[2].removePrefix("KNSafetyCode_")
            val name = safetyNames[what] ?: what
            val head = when (p[1]) { "Caution" -> "주의"; "Camera" -> "카메라"; else -> p[1] }
            val tail = when (code) { "KNVoiceCode_Alram" -> " (지점 알림음)"; "KNVoiceCode_Safety" -> ""; else -> " (" + code.removePrefix("KNVoiceCode_") + ")" }
            return "$head: $name$tail"
        }
        if (p.size == 2 && p[1] == "TRAFFIC") return (codeNames[code] ?: code.removePrefix("KNVoiceCode_")) + " (교통)"
        return codeNames[code] ?: code.removePrefix("KNVoiceCode_")
    }

    /** 끄면 위험하거나 길을 놓칠 수 있는 종류(목록에서 경고) */
    fun risky(kind: String): Boolean = kind.startsWith("KNVoiceCode_Turn") || kind.contains("|Camera|")

    // ---- v0.16.16 비슷한 종류를 묶어 설정 목록에 보인다(끄기도 묶음 단위)
    /** 묶음 순서와 이름(설정 목록 순서) */
    private val groupNames = linkedMapOf(
        "guide" to "길 안내 (회전·직진·하이패스)",
        "bus" to "버스전용차로",
        "route" to "경로 상태 (이탈·다른 경로·빠른 길 확인)",
        "startend" to "시작·끝 (안내 시작·끝·GPS 연결)",
        "cam_speed" to "과속 카메라 (과속·신호·후면)",
        "cam_movable" to "이동식 카메라",
        "cam_boxed" to "박스형 카메라",
        "cam_section" to "구간단속",
        "cam_other" to "기타 단속 카메라 (끼어들기·주정차 등)",
        "cam_collect" to "교통정보 수집 카메라 (단속 아님)",
        "cam_alarm" to "카메라 지점 알림음",
        "accident" to "사고 위험 구간",
        "sharp" to "급회전 구간",
        "height" to "높이 제한",
        "hump" to "과속방지턱",
        "rest" to "휴게소",
        "school" to "어린이보호구역",
        "alert" to "경고음 (짧은 소리, 추정)",
    )
    private val riskyGroups = setOf("guide", "cam_speed", "cam_movable", "cam_boxed", "cam_section", "cam_other")

    /** 종류 → 묶음. 어디에도 안 맞는 새 종류는 "x:종류"로 따로 한 줄. */
    fun group(kind: String): String {
        val p = kind.split("|")
        val code = p[0].removePrefix("KNVoiceCode_")
        if (p.size >= 3) {
            val what = p[2].removePrefix("KNSafetyCode_")
            if (p[1] == "Camera") return when {
                code == "Alram" -> "cam_alarm"
                what.contains("Movable") -> "cam_movable"
                what.contains("Boxed") -> "cam_boxed"
                what.contains("Section") -> "cam_section"
                what.contains("TrafficCollection") -> "cam_collect"
                what.contains("SpeedViolation") -> "cam_speed"
                else -> "cam_other"
            }
            return when {
                what.contains("Hump") -> "hump"
                what.contains("RestArea") -> "rest"
                what.contains("SharpTurn") -> "sharp"
                what.contains("HeightLimit") -> "height"
                what.contains("Accident") -> "accident"
                what.contains("School") -> "school"
                what.contains("BusLane") -> "bus"
                else -> "x:$kind"
            }
        }
        return when (code) {
            "Turn", "StrateToNext", "Hipass" -> "guide"
            "BusLaneGuide" -> "bus"
            "OutOfRoute", "MultiRoute", "CheckingRouteChange", "RouteUnchanged", "RouteChanged" -> "route"
            "StartGuide", "EndGuide", "GPSConnected" -> "startend"
            "SchoolZone" -> "school"
            "Alert" -> "alert"
            else -> "x:$kind"
        }
    }

    fun groupLabel(g: String): String = groupNames[g] ?: ("기타: " + label(g.removePrefix("x:")))
    fun groupRisky(g: String): Boolean = g in riskyGroups || (g.startsWith("x:") && risky(g.removePrefix("x:")))
    /** 설정 목록 순서: 정해 둔 묶음 순서, 그다음 기타 */
    fun groupOrder(g: String): Int = groupNames.keys.indexOf(g).let { if (it < 0) 1000 else it }
}
