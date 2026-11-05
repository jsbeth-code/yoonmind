package com.jsbeth.camslow

/**
 * 메인 화면 카메라 카드의 색·게이지·아래 줄 계산(안드로이드 의존성 없음 — 단위 테스트로 검증). 그리는 것은 MainActivity·GaugeView.
 *
 *  색: 평소 초록, 감속 중 파랑, 과속 빨강(깜빡임), 카카오 끊김(예비 판단) 주황.
 *  게이지: 카메라 500 m 앞부터 내 차 점이 오른쪽 끝(카메라)으로 다가가고, 남은 막대(차 → 카메라)가 줄어든다.
 *          감속 신호 지점은 점선 눈금. 남은 막대 색은 흰색 → 노랑(감속 신호 150 m 전부터) → 빨강(감속 신호 지점을 지났거나 감속 중).
 */
object CamCard {
    enum class Mode { OFF, NORMAL, SLOWING, OVER, KAKAO_DOWN }

    const val GREEN = 0xFF3FA57C.toInt()
    const val BLUE = 0xFF5B7FD6.toInt()
    const val RED = 0xFFE5484D.toInt()
    const val RED_DARK = 0xFFB8282D.toInt()
    const val ORANGE = 0xFFE8954A.toInt()
    const val OFF = 0xFF9A9088.toInt()
    const val NAVY = 0xFF1E2740.toInt()

    const val BAR_WHITE = 0xFFFFFFFF.toInt()
    const val BAR_YELLOW = 0xFFFFD84D.toInt()
    const val BAR_RED = 0xFFFF8A80.toInt()

    /** 게이지가 보여 주는 거리(m). */
    const val RANGE_M = 500.0
    /** 감속 신호 이만큼 전부터 남은 막대를 노랗게. */
    const val YELLOW_BEFORE_M = 150.0

    fun mode(running: Boolean, slowed: Boolean, overspeed: Boolean, kakaoDown: Boolean): Mode = when {
        !running -> Mode.OFF
        overspeed -> Mode.OVER
        slowed -> Mode.SLOWING
        kakaoDown -> Mode.KAKAO_DOWN
        else -> Mode.NORMAL
    }

    fun color(mode: Mode, blinkOn: Boolean = false): Int = when (mode) {
        Mode.OFF -> OFF
        Mode.NORMAL -> GREEN
        Mode.SLOWING -> BLUE
        Mode.OVER -> if (blinkOn) RED_DARK else RED
        Mode.KAKAO_DOWN -> ORANGE
    }

    /** 카드 아래 짙은 줄 색(카드 색보다 어둡게). */
    fun darker(c: Int, f: Float = 0.82f): Int {
        val r = ((c shr 16 and 0xFF) * f).toInt(); val g = ((c shr 8 and 0xFF) * f).toInt(); val b = ((c and 0xFF) * f).toInt()
        return (0xFF shl 24) or (r shl 16) or (g shl 8) or b
    }

    /** carFrac: 막대 왼쪽 끝(500 m)에서 0, 카메라에서 1. triggerFrac: 감속 신호 눈금(500 m 밖이면 null). */
    data class Gauge(val carFrac: Float, val triggerFrac: Float?, val remainColor: Int)

    fun gauge(distM: Double?, triggerM: Double?, slowed: Boolean): Gauge? {
        if (distM == null || distM < 0) return null
        val car = (1.0 - distM.coerceAtMost(RANGE_M) / RANGE_M).toFloat()
        val trig = triggerM?.takeIf { it in 0.0..RANGE_M }?.let { (1.0 - it / RANGE_M).toFloat() }
        val toSignal = if (triggerM != null) distM - triggerM else Double.MAX_VALUE
        val color = when {
            slowed || toSignal <= 0 -> BAR_RED
            toSignal <= YELLOW_BEFORE_M -> BAR_YELLOW
            else -> BAR_WHITE
        }
        return Gauge(car, trig, color)
    }

    /**
     * 아래 짙은 줄. nextDistM·triggerM은 다음 카메라 기준. usual·slow는 프로필 이름.
     * holdText: 구간단속·통과 유지 중이면 그 설명(있으면 우선).
     */
    fun bottomLine(mode: Mode, nextDistM: Double?, triggerM: Double?, usual: String, slow: String, autoRestore: Boolean, holdText: String? = null): String = when {
        mode == Mode.OFF -> "감시 꺼짐"
        mode == Mode.OVER -> "속도를 줄이세요"
        holdText != null -> holdText
        mode == Mode.SLOWING -> if (autoRestore) "카메라 지나면 ${usual}로 복귀" else "카메라 지나면 직접 복귀"
        nextDistM == null -> "2 km 안에 카메라 없음 · $usual 유지"
        triggerM == null -> "$usual → $slow"
        mode == Mode.KAKAO_DOWN -> "${triggerM.toInt()} m 앞에서 감속 · 카카오 연결 안 됨"
        nextDistM - triggerM > 0 -> "${(nextDistM - triggerM).toInt()} m 더 가면 감속 · $usual → $slow"
        else -> "곧 감속 신호 · $usual → $slow"
    }

    /** 상태 줄: "● 작동 중 · 경로 따라감" 등. */
    fun statusLine(mode: Mode, following: Boolean, slowLabel: String, overLimit: Int?, over: Int?): String = when (mode) {
        Mode.OFF -> "○ 꺼짐"
        Mode.OVER -> "⚠ 과속 · 제한 ${overLimit ?: "–"} · +${over ?: 0}"
        Mode.SLOWING -> "▼ 감속 중 · $slowLabel"
        Mode.KAKAO_DOWN -> "● 작동 중 · 예비 판단"
        Mode.NORMAL -> if (following) "● 작동 중 · 경로 따라감" else "● 작동 중"
    }
}
