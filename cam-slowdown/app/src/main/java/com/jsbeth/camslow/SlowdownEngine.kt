package com.jsbeth.camslow

import kotlin.math.max

data class Fix(
    val lat: Double,
    val lon: Double,
    val speedMps: Double,
    /** GPS 진행 방향(도). 정지 중이면 null. */
    val courseDeg: Double?,
)

data class EngineConfig(
    /** 감속을 시작할 때 가정하는 감속도(m/s²). 작을수록 일찍, 천천히 내린다. */
    val decelMps2: Double = 0.7,
    /** FSD가 프로필 변경에 반응하기까지의 여유 시간(초). */
    val reactionS: Double = 4.0,
    /** 계산된 감속 거리에 더하는 여유(m). */
    val marginM: Double = 150.0,
    /** 카메라 통과 후 복귀까지의 거리(m). */
    val restoreAfterM: Double = 100.0,
    /** 이 제한속도 미만 카메라는 무시(예: 80으로 두면 고속도로·자동차전용도로만). */
    val minLimitKmh: Int = 0,
    /** 현재 속도가 이미 제한속도 이하면 감속 신호를 생략. */
    val skipIfBelowLimit: Boolean = false,
    /** 구간단속 길이를 모를 때 최대 유지 거리(m). */
    val maxSectionHoldM: Double = 30_000.0,
    /** 진행 방향 왼쪽 허용 폭(m). 반대 차로 카메라를 거르기 위해 작게 둔다(우측통행). */
    val leftTolM: Double = 12.0,
    /** 진행 방향 오른쪽 허용 폭(m). */
    val rightTolM: Double = 60.0,
    val lookaheadM: Double = 2_000.0,
)

sealed class EngineEvent {
    data class Down(val camera: Camera, val distM: Double) : EngineEvent()
    data class Up(val reason: String) : EngineEvent()
}

data class EngineStatus(
    val slowed: Boolean,
    val target: Camera?,
    val nextCamera: Camera?,
    val nextDistM: Double?,
    val speedKmh: Double,
    val note: String,
)

/**
 * 카메라 접근 판단 상태기계. 안드로이드 의존성이 없어 JVM 단위 테스트로 검증한다.
 *  대기 → (카메라가 감속거리 안에 들어옴) → 감속(Down)
 *  감속 → (카메라 통과 후 restoreAfterM, 구간단속이면 구간 끝까지) → 복귀(Up)
 */
class SlowdownEngine(private val index: CameraIndex, var config: EngineConfig = EngineConfig()) {

    private var target: Camera? = null
    private var passedHoldUntilOdo: Double? = null
    private var lostCount = 0
    private var odo = 0.0
    private var last: Fix? = null

    var status = EngineStatus(false, null, null, null, 0.0, "대기")
        private set

    val isSlowed get() = target != null

    fun reset() {
        target = null; passedHoldUntilOdo = null; lostCount = 0; last = null
    }

    fun triggerDistance(speedMps: Double, limitKmh: Int): Double {
        val vl = limitKmh / 3.6
        val brake = max(0.0, (speedMps * speedMps - vl * vl) / (2 * config.decelMps2))
        return brake + speedMps * config.reactionS + config.marginM
    }

    private data class Rel(val cam: Camera, val along: Double, val cross: Double)

    private fun isAhead(along: Double, cross: Double): Boolean {
        val left = config.leftTolM + 0.03 * max(0.0, along)
        val right = config.rightTolM + 0.10 * max(0.0, along)
        return cross >= -left && cross <= right
    }

    fun update(fix: Fix): List<EngineEvent> {
        last?.let { p -> if (fix.speedMps >= 1.0) odo += Geo.distance(p.lat, p.lon, fix.lat, fix.lon) }
        last = fix
        val speedKmh = fix.speedMps * 3.6
        val course = fix.courseDeg
        if (course == null || fix.speedMps < 3.0) {
            // 정지·저속에선 방향을 믿을 수 없으니 상태를 유지한다
            status = status.copy(speedKmh = speedKmh, note = if (isSlowed) "감속 유지(저속)" else "대기(저속)")
            return emptyList()
        }

        val rel = index.near(fix.lat, fix.lon, config.lookaheadM)
            .filter { it.limitKmh >= config.minLimitKmh }
            .map { c -> Geo.alongCross(fix.lat, fix.lon, course, c.lat, c.lon).let { (a, x) -> Rel(c, a, x) } }
        val ahead = rel.filter { it.along > 0 && isAhead(it.along, it.cross) }.sortedBy { it.along }
        val next = ahead.firstOrNull { it.cam.kind != CamKind.SECTION_END }

        fun triggerCandidate(): Rel? = ahead.firstOrNull { r ->
            r.cam.kind != CamKind.SECTION_END &&
                r.along <= triggerDistance(fix.speedMps, r.cam.limitKmh) &&
                !(config.skipIfBelowLimit && speedKmh <= r.cam.limitKmh)
        }

        val events = ArrayList<EngineEvent>()
        val t = target
        if (t == null) {
            triggerCandidate()?.let { r ->
                target = r.cam; passedHoldUntilOdo = null; lostCount = 0
                events += EngineEvent.Down(r.cam, r.along)
            }
        } else {
            val hold = passedHoldUntilOdo
            if (hold == null) {
                val (along, cross) = Geo.alongCross(fix.lat, fix.lon, course, t.lat, t.lon)
                if (along <= 0 && along > -300) {
                    val extra = if (t.kind == CamKind.SECTION_START)
                        (if (t.sectionLenM > 0) t.sectionLenM.toDouble() else config.maxSectionHoldM) else 0.0
                    passedHoldUntilOdo = odo + config.restoreAfterM + extra
                    lostCount = 0
                } else if (along <= -300 || !isAhead(along, cross)) {
                    // 카메라를 지나치지 않았는데 앞에서 사라짐 = 다른 길로 빠짐
                    if (++lostCount >= 5) { release(events, "경로 이탈") }
                } else lostCount = 0
            } else {
                // 길이를 모르는 구간단속: 종점 카메라를 지나면 거기서부터 복귀 거리만 남긴다
                if (t.kind == CamKind.SECTION_START && t.sectionLenM <= 0 &&
                    rel.any { it.cam.kind == CamKind.SECTION_END && it.along in -60.0..0.0 && isAhead(0.0, it.cross) }
                ) passedHoldUntilOdo = minOf(hold, odo + config.restoreAfterM)
                if (odo >= (passedHoldUntilOdo ?: hold)) {
                    val again = triggerCandidate()
                    if (again != null) {
                        // 다음 카메라가 이미 가까우면 올리지 않고 그대로 이어간다
                        target = again.cam; passedHoldUntilOdo = null; lostCount = 0
                    } else release(events, "카메라 통과")
                }
            }
        }

        val nowTarget = target
        status = EngineStatus(
            slowed = nowTarget != null,
            target = nowTarget,
            nextCamera = next?.cam,
            nextDistM = next?.along,
            speedKmh = speedKmh,
            note = when {
                nowTarget == null -> "대기"
                passedHoldUntilOdo != null -> "통과, 복귀 대기 ${max(0.0, passedHoldUntilOdo!! - odo).toInt()}m"
                else -> "감속 중"
            },
        )
        return events
    }

    private fun release(events: MutableList<EngineEvent>, reason: String) {
        target = null; passedHoldUntilOdo = null; lostCount = 0
        events += EngineEvent.Up(reason)
    }
}
