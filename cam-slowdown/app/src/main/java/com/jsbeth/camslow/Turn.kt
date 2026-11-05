package com.jsbeth.camslow

/** 회전 안내 방향(카카오 경로 안내의 회전 코드를 단순화). 안드로이드 의존성 없음. */
enum class TurnType(val label: String, val arrow: String) {
    STRAIGHT("직진", "↑"),
    LEFT("좌회전", "↰"),
    RIGHT("우회전", "↱"),
    SLIGHT_LEFT("왼쪽 방향", "↖"),
    SLIGHT_RIGHT("오른쪽 방향", "↗"),
    UTURN("유턴", "↶"),
    EXIT_LEFT("왼쪽 출구", "↖"),
    EXIT_RIGHT("오른쪽 출구", "↗"),
    EXIT("출구", "↗"),
    ENTER("진입", "↑"),
    ROTARY("회전교차로", "⟳"),
    VIA("경유지", "⚑"),
    ARRIVE("목적지", "⚑"),
    OTHER("안내", "↑");

    companion object {
        private val CLOCK = Regex("""(?:direction_|_)(\d{1,2})(?:oclock)?$""")

        /**
         * 카카오 회전 코드 이름(KNRGCode의 enum 이름, 예: "KNRGCode_RightTurn")에서 방향을 고른다.
         * SDK 버전마다 이름이 조금씩 달라 글자로 판단한다.
         */
        fun fromCodeName(name: String?): TurnType {
            val n = (name ?: "").lowercase()
            return when {
                n.isEmpty() -> OTHER
                "goal" in n || "arriv" in n || "destination" in n || "end" == n.substringAfterLast('_') -> ARRIVE
                n.endsWith("_via") -> VIA
                "uturn" in n || "u_turn" in n -> UTURN
                "rotary" in n || "roundabout" in n -> ROTARY
                ("out" in n || "exit" in n) && "left" in n -> EXIT_LEFT
                ("out" in n || "exit" in n) && "right" in n -> EXIT_RIGHT
                "outhighway" in n || "outcityway" in n || "exit" in n -> EXIT
                "inhighway" in n || "incityway" in n -> ENTER
                "leftdirection" in n -> SLIGHT_LEFT
                "rightdirection" in n -> SLIGHT_RIGHT
                CLOCK.find(n) != null -> when (CLOCK.find(n)!!.groupValues[1].toInt()) {
                    12, 0 -> STRAIGHT
                    1, 2 -> SLIGHT_RIGHT
                    3, 4, 5 -> RIGHT
                    6 -> UTURN
                    7, 8, 9 -> LEFT
                    else -> SLIGHT_LEFT // 10, 11
                }
                "slightright" in n || "rightside" in n || "right_side" in n -> SLIGHT_RIGHT
                "slightleft" in n || "leftside" in n || "left_side" in n -> SLIGHT_LEFT
                "left" in n -> LEFT
                "right" in n -> RIGHT
                "straight" in n -> STRAIGHT
                else -> OTHER
            }
        }
    }
}

/** 다음 회전 하나. distM: 내 위치에서의 거리(m). toward: "신반포로" 같은 방면·도로 이름(없으면 빈 글자). */
data class TurnInfo(val type: TurnType, val distM: Int, val toward: String) {
    /** "우회전 · 신반포로 방면" */
    fun line(): String = type.label + (if (toward.isNotBlank()) " · ${toward.trim()}" + (if (toward.trim().endsWith("방면")) "" else " 방면") else "")

    companion object {
        /** 620 → "620 m", 1234 → "1.2 km" */
        fun dist(m: Int): String = if (m >= 1000) "%.1f km".format(m / 1000.0) else "${(m / 10) * 10} m"
    }
}
