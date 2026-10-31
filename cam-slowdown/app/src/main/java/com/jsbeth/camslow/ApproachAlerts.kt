package com.jsbeth.camslow

/**
 * 카메라 접근 음성 안내와 과속 경고음(안드로이드 의존성 없음 — 단위 테스트로 검증).
 *
 *  - 앞 카메라가 500 m 안에 처음 들어오면 "500미터 앞, 제한속도 50 과속카메라입니다"(announce가 켜져 있으면 늘).
 *    처음 본 거리가 500 m보다 짧으면 그 거리(50 m 단위)로 한 번만.
 *  - 300 m·100 m: 과속(제한 + margin 초과)일 때만, 또는 distanceAlways면 늘 "300미터"(과속이면 ", 감속하세요").
 *  - 과속이면 경고음: 500~300 m 2초마다, 300~100 m 1초마다, 100 m 안 0.5초마다. 카메라를 지나거나 속도가 내려가면 멈춤.
 *  - 구간단속 유지 중엔 구간 제한 + margin을 넘으면 2초마다 경고음(말은 하지 않음).
 * overspeedKmh는 화면 깜빡임용: 지금 경고 중이면 제한 대비 초과 속도, 아니면 null.
 */
class ApproachAlerts(
    var margin: Int = 7,
    var announce: Boolean = true,
    var distanceAlways: Boolean = false,
    var beepOn: Boolean = true,
) {
    sealed class Out {
        data class Say(val text: String) : Out()
        object Beep : Out()
    }

    var overspeedKmh: Int? = null
        private set
    /** 경고 기준이 된 제한속도(화면용). */
    var overLimit: Int? = null
        private set

    private var camKey: String? = null
    private val said = HashSet<Int>()
    private var lastBeepAt = Long.MIN_VALUE / 2

    fun reset() { camKey = null; said.clear(); overspeedKmh = null; overLimit = null; lastBeepAt = Long.MIN_VALUE / 2 }

    /**
     * nowMs: 단조 시계. cam: 다음 카메라(없으면 null). sectionLimit: 구간단속 유지 중이면 구간 제한속도.
     */
    fun update(nowMs: Long, cam: Camera?, distM: Double?, speedKmh: Double, sectionLimit: Int?): List<Out> {
        val out = ArrayList<Out>()
        overspeedKmh = null; overLimit = null

        if (sectionLimit != null) {
            val over = speedKmh - sectionLimit
            if (over > margin) {
                overspeedKmh = over.toInt(); overLimit = sectionLimit
                if (beepOn && nowMs - lastBeepAt >= 2_000) { out += Out.Beep; lastBeepAt = nowMs }
            }
            return out
        }

        if (cam == null || distM == null || distM < 0 || distM > 520) return out
        val key = "${cam.lat},${cam.lon},${cam.limitKmh}"
        if (key != camKey) { camKey = key; said.clear() }
        val limit = cam.limitKmh
        val over = speedKmh - limit
        val speeding = over > margin
        val what = when (cam.kind) {
            CamKind.SECTION_START -> "구간단속 시작"
            CamKind.SECTION_END -> "구간단속 끝"
            else -> "과속카메라"
        }

        // 지나온 체크포인트는 한 번에 처리: 가장 가까운(작은) 것 하나만 말한다
        val due = CHECKPOINTS.filter { distM <= it + 15 && it !in said }
        if (due.isNotEmpty()) {
            said += due
            val cp = due.min()
            if (500 in due && announce) {
                val d = if (distM >= 485) 500 else (Math.round(distM / 50.0) * 50).toInt().coerceAtLeast(50)
                out += Out.Say("${d}미터 앞, 제한속도 $limit $what" + "입니다")
            } else if (cp < 500 && (speeding || distanceAlways)) {
                out += Out.Say("${cp}미터" + if (speeding) ", 감속하세요" else "")
            }
        }

        if (speeding) {
            overspeedKmh = over.toInt(); overLimit = limit
            val gap = when {
                distM > 300 -> 2_000
                distM > 100 -> 1_000
                else -> 500
            }
            if (beepOn && nowMs - lastBeepAt >= gap) { out += Out.Beep; lastBeepAt = nowMs }
        }
        return out
    }

    companion object {
        val CHECKPOINTS = listOf(500, 300, 100)
    }
}
