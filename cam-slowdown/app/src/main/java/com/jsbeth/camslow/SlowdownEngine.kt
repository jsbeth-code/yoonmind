package com.jsbeth.camslow

import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin

data class Fix(
    val lat: Double,
    val lon: Double,
    val speedMps: Double,
    /** GPS 진행 방향(도). 정지 중이면 null. */
    val courseDeg: Double?,
    /** GPS 수평 정확도(m). 모르면 null. */
    val accM: Double? = null,
    /** GPS가 끊긴 동안 마지막 속도·방향으로 추정한 위치(추측 항법). 감속 판단만 하고 복귀는 하지 않는다. */
    val estimated: Boolean = false,
)

data class EngineConfig(
    /** 감속을 시작할 때 가정하는 감속도(m/s²). 작을수록 일찍, 천천히 내린다. */
    val decelMps2: Double = 0.7,
    /** FSD가 프로필 변경에 반응하기까지의 여유 시간(초). */
    val reactionS: Double = 4.0,
    /** 계산된 감속 거리에 더하는 여유(m). */
    val marginM: Double = 150.0,
    /** 0보다 크면 속도와 무관하게 카메라 이 거리(m) 앞에서 감속 신호를 보낸다(고정 거리 모드). */
    val fixedTriggerM: Int = 0,
    /** BLE로 감속 클릭을 다 보내는 데 걸리는 시간(초). 그만큼 일찍 시작한다. */
    val bleLatencyS: Double = 0.0,
    /** 카메라 통과 후 복귀까지의 거리(m). */
    val restoreAfterM: Double = 100.0,
    /** 이 제한속도 미만 카메라는 무시(예: 80으로 두면 고속도로·자동차전용도로만). */
    val minLimitKmh: Int = 0,
    /** 현재 속도가 이미 제한속도 이하면 감속 신호를 생략. */
    val skipIfBelowLimit: Boolean = false,
    /** 구간단속 길이를 모를 때 최대 유지 거리(m). */
    val maxSectionHoldM: Double = 30_000.0,
    /**
     * 방향을 모르는 카메라의 허용폭(m) — GPS 기하로 판단할 때. 도심(제한속도 < hwyLimitKmh)은 좁게, 고속도로는 넓게.
     * 왼쪽이 좁은 건 반대 차로를 거르기 위해(우측통행). 여기에 거리 비례분(leftSlope·rightSlope × 앞 거리)이 더해진다.
     */
    val cityLeftTolM: Double = 8.0,
    val cityRightTolM: Double = 25.0,
    val hwyLeftTolM: Double = 15.0,
    val hwyRightTolM: Double = 60.0,
    /** 거리 비례 허용폭. 방향 평활화로 GPS 방향 오차가 1°쯤이라 300 m 앞에서 왼쪽 +4.5 m, 오른쪽 +9 m면 충분하다. */
    val leftSlope: Double = 0.015,
    val rightSlope: Double = 0.03,
    val hwyRightSlope: Double = 0.05,
    /** 이 제한속도부터 고속도로용 허용폭을 쓴다. */
    val hwyLimitKmh: Int = 80,
    /** 방향을 아는 카메라(도로공사·중심선 추정)는 반대 방향이 각도로 걸러지므로 좌우 같게 넓게 본다. */
    val directedTolM: Double = 40.0,
    /** 이미 목표로 잡은 카메라를 추적할 때의 최소 왼쪽 허용폭(m). 넓은 도로에서 카메라 직전에 "경로 이탈"로 보지 않도록. */
    val trackLeftTolM: Double = 25.0,
    /** 이 횟수만큼 연속으로 카메라가 앞 시야에서 벗어나면 다른 길로 빠진 것으로 보고 복귀. */
    val lostFixes: Int = 5,
    /** 수평 정확도가 이보다 나쁜 GPS fix는 판단에 쓰지 않고 상태만 유지한다(m). */
    val maxAccM: Double = 40.0,
    /** 신호 단속으로만 표시된 카메라(단속구분 2)도 포함. */
    val includeSignalCams: Boolean = true,
    /** 구간 시점 통과 후 이 거리(m) 안에서 보이는 종점은 반대 방향 구간의 종점으로 보고 무시. */
    val minSectionM: Double = 500.0,
    /** 방향을 아는 카메라는 내 진행 방향과 이 각도(도) 넘게 다르면 무시. */
    val maxHeadingDiffDeg: Double = 60.0,
    /** 어린이보호구역 시설 반경 안에 들어가면 감속, 반경+복귀거리 밖으로 나가면 복귀. */
    val schoolZoneOn: Boolean = false,
    val schoolZoneRadiusM: Double = 300.0,
    val lookaheadM: Double = 2_000.0,
    /** GPS 방향 평활화에 쓰는 최근 fix 수. 서로 이 각도(도) 안일 때만 평균한다(커브에선 그대로). */
    val smoothFixes: Int = 3,
    val smoothMaxDiffDeg: Double = 15.0,
    /** 카메라가 이 횟수만큼 연속으로 앞 시야에 있어야 감속 후보가 된다(한 순간의 GPS 튐 방지). */
    val confirmFixes: Int = 3,
    /**
     * 속도 타당성: 최근 fastHoldFixes개 fix 동안 내 속도가 제한속도 × fastRatio 이상이면 그 카메라는 다른 도로의 것으로 본다
     * (FSD가 50 도로를 75 km/h로 달릴 리 없다). GPS 기하로 판단하는 카메라에만 적용하고, 도로망으로 내 길 위에 있다고
     * 확인된 카메라에는 적용하지 않는다.
     */
    val fastRatio: Double = 1.5,
    val fastHoldFixes: Int = 5,
    /** 도로망(OSM) 맵매칭 사용. 데이터가 없으면 자연히 GPS 기하만 쓴다. */
    val useRoads: Boolean = true,
    /** 내 도로의 OSM 제한속도가 카메라 제한속도보다 이만큼(km/h) 이상 높으면 잘못 붙은 카메라로 보고 뺀다. 0이면 안 함. */
    val roadMaxspeedGapKmh: Int = 25,
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
    /** 보호구역 감속 중이면 그 이름. */
    val zone: String? = null,
    val estimated: Boolean = false,
    /** 도로망 상태: "동부간선도로 · 북동 · 지도 제한 80", "도로 미확인 — …", 도로망이 없으면 null. */
    val road: String? = null,
    /** 이번 판단에 쓴 진행 방향(도로 방향 또는 평활화한 GPS 방향). 저속·정지면 null. */
    val courseDeg: Double? = null,
    /** 앞 시야의 카메라(가까운 순). 화면 지도용. */
    val ahead: List<AheadCam> = emptyList(),
    /** 다음 카메라의 감속 신호 거리(m). */
    val triggerM: Double? = null,
)

data class AheadCam(val cam: Camera, val alongM: Double, val viaRoad: Boolean)

/**
 * 카메라 접근 판단 상태기계. 안드로이드 의존성이 없어 JVM 단위 테스트로 검증한다.
 *  대기 → (카메라가 감속거리 안에 들어옴, 또는 보호구역 진입) → 감속(Down)
 *  감속 → (카메라 통과 후 restoreAfterM, 구간단속이면 구간 끝까지, 보호구역이면 벗어난 뒤) → 복귀(Up)
 * 카메라와 보호구역이 겹치면 Down/Up은 한 번씩만 나간다.
 *
 * 앞 카메라를 고르는 방법은 둘이다.
 *  - 도로망(RoadMap)이 있고 내 위치가 도로에 붙으면: 도로를 따라 앞으로 이어지는 길 위의 카메라(경로 거리). 다른 도로에
 *    붙은 카메라는 아무리 가까워도 뺀다. 어느 도로에도 안 붙은 카메라만 아래 기하 판단으로 보충한다.
 *  - 아니면(도로망 없음, 도로 밖, 모호): GPS 진행 방향(평활화) 기준 앞·좌우 허용폭 안의 카메라.
 */
class SlowdownEngine(
    private val index: CameraIndex,
    var config: EngineConfig = EngineConfig(),
    private val zones: CameraIndex = CameraIndex(emptyList()),
    private val roads: RoadMap? = null,
) {

    private var target: Camera? = null
    private var passedHoldUntilOdo: Double? = null
    private var passedAtOdo = 0.0
    private var lostCount = 0
    private var odo = 0.0
    private var last: Fix? = null
    private var zone: Camera? = null
    private val courses = ArrayDeque<Double>()
    private val speeds = ArrayDeque<Double>()
    private val seen = HashMap<Camera, Int>()

    var status = EngineStatus(false, null, null, null, 0.0, "대기")
        private set

    val isSlowed get() = target != null || zone != null
    val cameras: List<Camera> get() = index.cameras
    val cameraCount: Int get() = index.cameras.size
    val zoneCount: Int get() = zones.cameras.size
    val hasRoads: Boolean get() = roads != null && config.useRoads
    fun camerasNear(lat: Double, lon: Double, radiusM: Double): List<Camera> = index.near(lat, lon, radiusM)

    fun reset() {
        target = null; passedHoldUntilOdo = null; lostCount = 0; last = null; zone = null
        courses.clear(); speeds.clear(); seen.clear()
    }

    /** 감속 신호를 보내기 시작할 거리(m). 고정 거리 모드면 그 값, 아니면 제동거리 + 반응거리 + 여유. BLE 송출 시간은 둘 다에 더한다. */
    fun triggerDistance(speedMps: Double, limitKmh: Int): Double {
        val ble = speedMps * config.bleLatencyS
        if (config.fixedTriggerM > 0) return config.fixedTriggerM + ble
        val vl = limitKmh / 3.6
        val brake = max(0.0, (speedMps * speedMps - vl * vl) / (2 * config.decelMps2))
        return brake + speedMps * config.reactionS + config.marginM + ble
    }

    /** 최근 fix들의 GPS 방향이 서로 가까우면(직선 주행) 원형 평균, 아니면(커브) 지금 값. */
    fun smoothedCourse(raw: Double): Double {
        if (courses.isEmpty() || courses.any { Geo.angleDiff(it, raw) > config.smoothMaxDiffDeg }) return raw
        var x = sin(Math.toRadians(raw)); var y = cos(Math.toRadians(raw))
        for (c in courses) { x += sin(Math.toRadians(c)); y += cos(Math.toRadians(c)) }
        return (Math.toDegrees(atan2(x, y)) + 360.0) % 360.0
    }

    /** 최근 fastHoldFixes개 fix 모두 제한속도 × fastRatio 이상으로 달렸나. */
    private fun implausiblyFast(limitKmh: Int): Boolean {
        if (config.fastRatio <= 0 || speeds.size < config.fastHoldFixes) return false
        val th = limitKmh / 3.6 * config.fastRatio
        return speeds.all { it >= th }
    }

    /** 앞 카메라 하나. viaRoad = 도로망 경로로 확인된 것(along은 경로 거리). */
    private class Rel(val cam: Camera, val along: Double, val cross: Double, val viaRoad: Boolean = false)

    /** 카메라 종류별 기본 허용폭 (왼쪽, 오른쪽, 오른쪽 비례). */
    private fun baseTol(cam: Camera): Triple<Double, Double, Double> = when {
        cam.bearingDeg != null -> Triple(config.directedTolM, config.directedTolM, config.rightSlope)
        cam.limitKmh >= config.hwyLimitKmh -> Triple(config.hwyLeftTolM, config.hwyRightTolM, config.hwyRightSlope)
        else -> Triple(config.cityLeftTolM, config.cityRightTolM, config.rightSlope)
    }

    private fun isAhead(along: Double, cross: Double, cam: Camera, tracking: Boolean = false): Boolean {
        val (lb, rb, rs) = baseTol(cam)
        val left = (if (tracking) max(lb, config.trackLeftTolM) else lb) + config.leftSlope * max(0.0, along)
        val right = rb + rs * max(0.0, along)
        return cross >= -left && cross <= right
    }

    fun update(fix: Fix): List<EngineEvent> {
        val acc = fix.accM
        if (acc != null && acc > config.maxAccM && !fix.estimated) {
            // 정확도가 나쁜 fix로는 판단하지 않는다. 거리 적산도 멈춘다(복귀가 늦어지는 쪽 = 안전).
            status = status.copy(note = (if (isSlowed) "감속 유지" else "대기") + "(GPS 부정확 ±${acc.toInt()}m)")
            return emptyList()
        }
        val slowedBefore = isSlowed
        if (!fix.estimated) {
            last?.let { p -> if (fix.speedMps >= 1.0) odo += Geo.distance(p.lat, p.lon, fix.lat, fix.lon) }
            last = fix
            speeds.addLast(fix.speedMps); while (speeds.size > config.fastHoldFixes) speeds.removeFirst()
        }
        val speedKmh = fix.speedMps * 3.6
        val rawCourse = fix.courseDeg
        if (rawCourse == null || fix.speedMps < 3.0) {
            // 정지·저속에선 방향을 믿을 수 없으니 상태를 유지한다
            courses.clear()
            status = status.copy(speedKmh = speedKmh, note = if (isSlowed) "감속 유지(저속)" else "대기(저속)")
            return emptyList()
        }
        val smooth = smoothedCourse(rawCourse)
        if (!fix.estimated) { courses.addLast(rawCourse); while (courses.size > config.smoothFixes - 1) courses.removeFirst() }

        // 도로망: 내 도로와 앞길의 카메라
        val view = if (hasRoads) roads!!.view(fix.lat, fix.lon, smooth, config.lookaheadM) else null
        val course = view?.bearingDeg ?: smooth

        fun passesFilter(c: Camera) = c.limitKmh >= config.minLimitKmh && (config.includeSignalCams || !c.signalOnly)
        val rel = index.near(fix.lat, fix.lon, config.lookaheadM)
            .filter { passesFilter(it) }
            .filter { c -> c.bearingDeg?.let { Geo.angleDiff(course, it) <= config.maxHeadingDiffDeg } ?: true }
            .filter { c -> roads?.servesHeading(c, course) != false }
            .map { c -> Geo.alongCross(fix.lat, fix.lon, course, c.lat, c.lon).let { (a, x) -> Rel(c, a, x) } }

        val ahead: List<Rel> = if (view != null) {
            val gap = config.roadMaxspeedGapKmh
            val onRoad = view.ahead
                .filter { passesFilter(it.cam) }
                .filter { gap <= 0 || view.maxspeedKmh <= 0 || it.cam.limitKmh > view.maxspeedKmh - gap }
                .map { Rel(it.cam, it.alongM, 0.0, viaRoad = true) }
            val loose = rel.filter { !roads!!.isAttached(it.cam) && it.along > 0 && isAhead(it.along, it.cross, it.cam) }
            (onRoad + loose).sortedBy { it.along }
        } else rel.filter { it.along > 0 && isAhead(it.along, it.cross, it.cam) }.sortedBy { it.along }

        // 연속 확인 횟수 갱신: 지금 앞에 있는 카메라는 +1, 사라진 카메라는 0으로
        val nowSeen = HashSet<Camera>()
        for (r in ahead) if (nowSeen.add(r.cam)) seen[r.cam] = (seen[r.cam] ?: 0) + 1
        seen.keys.retainAll(nowSeen)

        val next = ahead.firstOrNull { it.cam.kind != CamKind.SECTION_END }

        fun triggerCandidate(): Rel? = ahead.firstOrNull { r ->
            r.cam.kind != CamKind.SECTION_END &&
                r.along <= triggerDistance(fix.speedMps, r.cam.limitKmh) &&
                (seen[r.cam] ?: 0) >= config.confirmFixes &&
                !(config.skipIfBelowLimit && speedKmh <= r.cam.limitKmh) &&
                !(!r.viaRoad && r.cam.bearingDeg == null && implausiblyFast(r.cam.limitKmh))
        }

        var camDown: Rel? = null
        var camUpReason: String? = null
        val t = target
        if (t == null) {
            triggerCandidate()?.let { r ->
                target = r.cam; passedHoldUntilOdo = null; lostCount = 0
                camDown = r
            }
        } else if (!fix.estimated) {
            // 추측 위치로는 통과·이탈·복귀를 판단하지 않는다(실제 GPS가 돌아온 뒤에)
            val hold = passedHoldUntilOdo
            if (hold == null) {
                val entry = ahead.firstOrNull { it.cam == t }
                val (gAlong, gCross) = Geo.alongCross(fix.lat, fix.lon, course, t.lat, t.lon)
                val along = entry?.along ?: gAlong
                // 도로망으로 붙은 카메라는 "내 앞길에서 사라짐", 아니면 "허용폭(추적용) 밖"이 이탈
                val attachedNow = view != null && roads!!.isAttached(t)
                // 통과 = 바로 옆을 지나침. 교차로에서 꺾어 카메라가 옆쪽 멀리(가로 30m 밖) 남았으면 통과가 아니라 이탈
                if (along <= 0 && along > -300 && (!attachedNow || kotlin.math.abs(gCross) <= 30.0)) {
                    val extra = if (t.kind == CamKind.SECTION_START)
                        (if (t.sectionLenM > 0) t.sectionLenM.toDouble() else config.maxSectionHoldM) else 0.0
                    passedHoldUntilOdo = odo + config.restoreAfterM + extra
                    passedAtOdo = odo
                    lostCount = 0
                } else {
                    val lostNow = along <= -300 || (entry == null && (attachedNow || !isAhead(gAlong, gCross, t, tracking = true)))
                    if (lostNow) {
                        // 카메라를 지나치지 않았는데 앞에서 사라짐 = 다른 길로 빠짐
                        if (++lostCount >= config.lostFixes) { target = null; passedHoldUntilOdo = null; lostCount = 0; camUpReason = "경로 이탈" }
                    } else lostCount = 0
                }
            } else {
                // 구간단속: 종점 카메라를 지나면 거기서부터 복귀 거리만 남긴다(길이를 알아도 — 데이터의 길이가 틀릴 수 있다).
                // 시점 바로 옆의 종점은 반대 방향 구간의 종점이므로 minSectionM 이후부터만 인정한다.
                if (t.kind == CamKind.SECTION_START && odo - passedAtOdo >= config.minSectionM &&
                    rel.any { it.cam.kind == CamKind.SECTION_END && it.along in -60.0..0.0 && isAhead(0.0, it.cross, it.cam) }
                ) passedHoldUntilOdo = minOf(hold, odo + config.restoreAfterM)
                if (odo >= (passedHoldUntilOdo ?: hold)) {
                    val again = triggerCandidate()
                    if (again != null) {
                        // 다음 카메라가 이미 가까우면 올리지 않고 그대로 이어간다
                        target = again.cam; passedHoldUntilOdo = null; lostCount = 0
                    } else { target = null; passedHoldUntilOdo = null; lostCount = 0; camUpReason = "카메라 통과" }
                }
            }
        }

        // 어린이보호구역: 실제 위치로만 판단. 반경 안이면 진입, 반경+복귀거리 밖이면 이탈.
        var zoneEntered: Camera? = null
        var zoneLeft = false
        if (!fix.estimated && config.schoolZoneOn && zones.cameras.isNotEmpty()) {
            val z = zone
            if (z == null) {
                zones.near(fix.lat, fix.lon, config.schoolZoneRadiusM)
                    .minByOrNull { Geo.distance(fix.lat, fix.lon, it.lat, it.lon) }
                    ?.let { zone = it; zoneEntered = it }
            } else if (zones.near(fix.lat, fix.lon, config.schoolZoneRadiusM + config.restoreAfterM).isEmpty()) {
                zone = null; zoneLeft = true
            }
        }

        // 카메라와 보호구역을 합쳐 Down/Up은 전이 때만 한 번씩
        val events = ArrayList<EngineEvent>()
        val slowedAfter = isSlowed
        if (!slowedBefore && slowedAfter) {
            camDown?.let { events += EngineEvent.Down(it.cam, it.along) }
                ?: zoneEntered?.let { events += EngineEvent.Down(it, Geo.distance(fix.lat, fix.lon, it.lat, it.lon)) }
        } else if (slowedBefore && !slowedAfter) {
            events += EngineEvent.Up(camUpReason ?: (if (zoneLeft) "보호구역 벗어남" else "복귀"))
        }

        val nowTarget = target
        status = EngineStatus(
            slowed = slowedAfter,
            target = nowTarget,
            nextCamera = next?.cam,
            nextDistM = next?.along,
            speedKmh = speedKmh,
            note = when {
                fix.estimated && slowedAfter -> "감속 중(추측 항법)"
                fix.estimated -> "대기(추측 항법)"
                nowTarget == null && zone != null -> "보호구역 감속 중"
                nowTarget == null -> "대기"
                passedHoldUntilOdo != null -> "통과, 복귀 대기 ${max(0.0, passedHoldUntilOdo!! - odo).toInt()}m"
                else -> "감속 중"
            },
            zone = zone?.name,
            estimated = fix.estimated,
            courseDeg = course,
            ahead = ahead.map { AheadCam(it.cam, it.along, it.viaRoad) },
            triggerM = next?.let { triggerDistance(fix.speedMps, it.cam.limitKmh) },
            road = when {
                !hasRoads -> null
                view == null -> "도로 미확인 — GPS 방향으로 판단"
                else -> view.describe()
            },
        )
        return events
    }
}
