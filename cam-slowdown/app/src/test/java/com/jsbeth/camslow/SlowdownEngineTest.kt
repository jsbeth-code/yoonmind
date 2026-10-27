package com.jsbeth.camslow

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SlowdownEngineTest {
    private val lat0 = 37.0
    private val lon0 = 127.0
    private val mPerDegLat = 111_195.0
    private val mPerDegLon = 111_195.0 * Math.cos(Math.toRadians(lat0))

    /** 기준점에서 (동 m, 북 m) 위치. */
    private fun at(east: Double, north: Double) = Pair(lat0 + north / mPerDegLat, lon0 + east / mPerDegLon)

    private fun cam(east: Double, north: Double, limit: Int = 100, kind: CamKind = CamKind.SPOT, len: Int = 0, code: String = "1"): Camera {
        val (la, lo) = at(east, north)
        return Camera(la, lo, limit, kind, len, "test", code)
    }

    /** 북쪽으로 일정 속도 주행. 각 이벤트와 발생 위치(북 m)를 돌려준다. */
    private fun driveNorth(
        cams: List<Camera>, kmh: Double, fromN: Double, toN: Double, cfg: EngineConfig = EngineConfig(), acc: Double? = null,
    ): List<Pair<EngineEvent, Double>> {
        val e = SlowdownEngine(CameraIndex(cams), cfg)
        val v = kmh / 3.6
        val out = ArrayList<Pair<EngineEvent, Double>>()
        var n = fromN
        while (n <= toN) {
            val (la, lo) = at(0.0, n)
            e.update(Fix(la, lo, v, 0.0, acc)).forEach { out += it to n }
            n += v
        }
        return out
    }

    private fun downAt(ev: List<Pair<EngineEvent, Double>>) = ev.first { it.first is EngineEvent.Down }.second
    private fun upAt(ev: List<Pair<EngineEvent, Double>>) = ev.first { it.first is EngineEvent.Up }.second

    @Test fun spotCamera_downBeforeAndUpAfter() {
        val ev = driveNorth(listOf(cam(8.0, 3000.0)), 110.0, 0.0, 5000.0)
        assertEquals(2, ev.size)
        val (down, dn) = ev[0]
        assertTrue(down is EngineEvent.Down)
        val before = 3000.0 - dn
        assertTrue("감속 시작 거리 $before", before in 250.0..450.0)
        val (up, un) = ev[1]
        assertTrue(up is EngineEvent.Up)
        assertTrue("복귀 위치 $un", un - 3000.0 in 90.0..170.0)
    }

    @Test fun triggerDistanceFormula() {
        val e = SlowdownEngine(CameraIndex(emptyList()))
        // (30.56² − 27.78²)/1.4 + 30.56×4 + 150 ≈ 388
        assertEquals(388.0, e.triggerDistance(110 / 3.6, 100), 3.0)
        // 제한속도 이하면 제동거리 0
        assertEquals(90 / 3.6 * 4 + 150, e.triggerDistance(90 / 3.6, 100), 0.01)
    }

    @Test fun fixedTriggerDistance() {
        val ev = driveNorth(listOf(cam(8.0, 3000.0)), 110.0, 0.0, 5000.0, EngineConfig(fixedTriggerM = 500))
        val before = 3000.0 - downAt(ev)
        assertTrue("고정 거리 감속 시작 $before", before in 440.0..500.0)
        // 속도가 달라도 같은 거리
        val ev2 = driveNorth(listOf(cam(8.0, 3000.0)), 60.0, 0.0, 5000.0, EngineConfig(fixedTriggerM = 500))
        assertTrue(3000.0 - downAt(ev2) in 470.0..501.0)
    }

    @Test fun bleLatencyStartsEarlier() {
        // BLE로 클릭을 보내는 5초 동안 110km/h면 153m 더 가므로 그만큼 일찍
        val ev = driveNorth(listOf(cam(8.0, 3000.0)), 110.0, 0.0, 5000.0, EngineConfig(fixedTriggerM = 500, bleLatencyS = 5.0))
        val before = 3000.0 - downAt(ev)
        assertTrue("BLE 지연 반영 $before", before in 600.0..653.0)
    }

    @Test fun oppositeCarriagewayCamera_ignored() {
        // 반대 차로(왼쪽 30m) 카메라
        val ev = driveNorth(listOf(cam(-30.0, 3000.0)), 110.0, 0.0, 5000.0)
        assertEquals(0, ev.size)
    }

    @Test fun wideRoadCameraOnLeft_noReleaseBeforeCamera() {
        // 넓은 도로: 카메라 좌표가 왼쪽 15~18m(중앙분리대 쪽 + GPS 오차). 통과 전에 "경로 이탈" 복귀가 나가면 안 된다.
        for ((kmh, cross) in listOf(50.0 to -15.0, 60.0 to -18.0, 110.0 to -18.0)) {
            val ev = driveNorth(listOf(cam(cross, 3000.0, limit = 60)), kmh, 0.0, 5000.0)
            assertEquals("$kmh/$cross 이벤트 수", 2, ev.size)
            val un = upAt(ev)
            assertTrue("$kmh/$cross 복귀 위치 $un", un - 3000.0 in 90.0..170.0)
        }
    }

    @Test fun cameraBehind_ignored() {
        val ev = driveNorth(listOf(cam(5.0, -500.0)), 110.0, 0.0, 3000.0)
        assertEquals(0, ev.size)
    }

    @Test fun sectionWithLength_holdsUntilEnd() {
        val ev = driveNorth(listOf(cam(8.0, 2000.0, kind = CamKind.SECTION_START, len = 5000)), 100.0, 0.0, 9000.0)
        assertEquals(2, ev.size)
        assertTrue(ev[1].first is EngineEvent.Up)
        assertTrue("구간 복귀 위치 ${ev[1].second}", ev[1].second - 7000.0 in 90.0..170.0)
    }

    @Test fun sectionWithoutLength_releasedAtEndCamera() {
        val cams = listOf(
            cam(8.0, 2000.0, kind = CamKind.SECTION_START),
            cam(8.0, 6000.0, kind = CamKind.SECTION_END),
        )
        val ev = driveNorth(cams, 100.0, 0.0, 9000.0)
        assertEquals(2, ev.size)
        assertTrue("구간 복귀 위치 ${ev[1].second}", ev[1].second - 6000.0 in 90.0..170.0)
    }

    @Test fun sectionEndCamera_overridesWrongLength() {
        // 데이터의 길이(20km)가 틀려도 종점 카메라를 지나면 거기서 복귀
        val cams = listOf(
            cam(8.0, 2000.0, kind = CamKind.SECTION_START, len = 20_000),
            cam(8.0, 5000.0, kind = CamKind.SECTION_END, len = 20_000),
        )
        val ev = driveNorth(cams, 100.0, 0.0, 9000.0)
        assertEquals(2, ev.size)
        assertTrue("종점 기준 복귀 ${ev[1].second}", ev[1].second - 5000.0 in 90.0..170.0)
    }

    @Test fun mirroredEndAtSectionStart_ignored() {
        // 반대 방향 구간의 종점은 내 시점과 같은 자리(왼쪽 10m)에 있다. 그걸 종점으로 보고 바로 복귀하면 안 된다.
        val cams = listOf(
            cam(8.0, 2000.0, kind = CamKind.SECTION_START, len = 7000),
            cam(-10.0, 2000.0, kind = CamKind.SECTION_END, len = 7000),
        )
        val ev = driveNorth(cams, 100.0, 0.0, 10_000.0)
        assertEquals(2, ev.size)
        assertTrue("시점 옆 종점 무시 ${ev[1].second}", ev[1].second - 9000.0 in 90.0..170.0)
    }

    @Test fun consecutiveCameras_singleDownUp() {
        val ev = driveNorth(listOf(cam(8.0, 3000.0), cam(8.0, 3400.0)), 110.0, 0.0, 6000.0)
        assertEquals(2, ev.size)
        assertTrue(ev[1].second > 3400.0)
    }

    @Test fun lowLimitFilteredByMinLimit() {
        val ev = driveNorth(listOf(cam(8.0, 3000.0, limit = 50)), 60.0, 0.0, 5000.0, EngineConfig(minLimitKmh = 80))
        assertEquals(0, ev.size)
    }

    @Test fun skipIfBelowLimit() {
        val ev = driveNorth(listOf(cam(8.0, 3000.0, limit = 100)), 90.0, 0.0, 5000.0, EngineConfig(skipIfBelowLimit = true))
        assertEquals(0, ev.size)
    }

    @Test fun signalCameras_optional() {
        val signal = listOf(cam(8.0, 3000.0, limit = 60, code = "2"))
        assertEquals(2, driveNorth(signal, 80.0, 0.0, 5000.0).size)
        assertEquals(0, driveNorth(signal, 80.0, 0.0, 5000.0, EngineConfig(includeSignalCams = false)).size)
    }

    @Test fun inaccurateFixes_keepState() {
        // 정확도 80m fix만 들어오면 판단하지 않는다
        val e = SlowdownEngine(CameraIndex(listOf(cam(8.0, 300.0))))
        val v = 20.0
        var n = 0.0
        repeat(10) { val (la, lo) = at(0.0, n); assertEquals(0, e.update(Fix(la, lo, v, 0.0, 80.0)).size); n += v }
        assertFalse(e.isSlowed)
        assertTrue(e.status.note.contains("GPS 부정확"))
        // 정확한 fix가 오면 바로 판단
        val (la, lo) = at(0.0, n)
        assertEquals(1, e.update(Fix(la, lo, v, 0.0, 5.0)).size)
    }

    @Test fun turningAway_restores() {
        val e = SlowdownEngine(CameraIndex(listOf(cam(8.0, 1000.0))))
        val v = 25.0
        val events = ArrayList<EngineEvent>()
        var n = 0.0
        while (n < 800) { val (la, lo) = at(0.0, n); events += e.update(Fix(la, lo, v, 0.0)); n += v }
        assertTrue(e.isSlowed)
        // 동쪽으로 꺾어 빠져나감
        var east = 0.0
        repeat(20) { east += v; val (la, lo) = at(east, n); events += e.update(Fix(la, lo, v, 90.0)) }
        assertTrue(events.last() is EngineEvent.Up)
        assertTrue(!e.isSlowed)
    }

    @Test fun stoppedKeepsState() {
        val e = SlowdownEngine(CameraIndex(listOf(cam(8.0, 200.0))))
        val (la, lo) = at(0.0, 0.0)
        assertEquals(1, e.update(Fix(la, lo, 20.0, 0.0)).size)
        repeat(30) { assertEquals(0, e.update(Fix(la, lo, 0.0, null)).size) }
        assertTrue(e.isSlowed)
    }

    private val header = "무인교통단속카메라관리번호,시도명,도로노선명,설치장소,단속구분,제한속도,단속구간위치구분,과속단속구간길이,위도,경도\n"

    @Test fun csvParse_realCodes() {
        // 실제 데이터 규칙: 단속구분 1=속도, 2=신호, 4=불법주정차(제한속도 보통 없음); 단속구간위치구분 1=시점, 2=종점; 길이는 km 또는 m
        val csv = header +
            "A1,경기,경부고속도로,\"서울방향, 100k\",1,100,,,37.1,127.1\n" +
            "A2,경기,중부고속도로,구간 시작,99,100,01,7.5,37.2,127.2\n" +
            "A3,경기,중부고속도로,구간 끝,99,100,02,7.5,37.26,127.26\n" +
            "A4,경기,국도,하귀교차로,2,70,,,37.3,127.3\n" +
            "A5,경기,국도,신호전용,신호,60,,,37.35,127.3\n" +
            "A6,경기,국도,잘못된좌표,1,60,,,0,0\n" +
            "A7,경기,시도,주차타워 앞,4,0,,,37.4,127.4\n" +
            "A8,전남,구례로,식자재마트앞,1,50,2,0,35.2,127.46\n"
        val list = CameraCsv.parse(CameraCsv.decode(csv.toByteArray(charset("MS949"))))
        assertEquals(listOf("A1", "A2", "A3", "A4", "A8").size, list.size)
        val a1 = list[0]
        assertEquals("경부고속도로 서울방향, 100k", a1.name)
        assertEquals(CamKind.SPOT, a1.kind); assertEquals(100, a1.limitKmh); assertFalse(a1.signalOnly)
        assertEquals(CamKind.SECTION_START, list[1].kind); assertEquals(7500, list[1].sectionLenM)
        assertEquals(CamKind.SECTION_END, list[2].kind); assertEquals(7500, list[2].sectionLenM)
        assertEquals(CamKind.SPOT, list[3].kind); assertTrue(list[3].signalOnly)
        // 구례군식: 일반 카메라에 위치구분 2 + 길이 0 → 종점이 아니라 일반 카메라로 (놓치면 안 됨)
        assertEquals(CamKind.SPOT, list[4].kind); assertEquals(50, list[4].limitKmh)
    }

    @Test fun csvParse_sectionByPartnerOrText() {
        val csv = header +
            "S1,경기,중부고속도로,254.9km 창원방향,1,100,1,,37.20,127.20\n" +      // 길이 없음, 같은 노선에 종점 짝 있음 → 시점
            "E1,경기,중부고속도로,263.0km 창원방향,1,100,2,,37.26,127.26\n" +      // → 종점
            "T1,인천,남동대로,구월중학교직전(구간단속 시점),1,60,,,37.45,126.70\n" + // 글자로 "구간" 명시 → 시점
            "N1,경기,경춘로,호평동 버스종점사거리 부근,1,60,,,37.65,127.25\n" +     // "종점" 글자지만 짝 없음 → 일반
            "N2,전남,땅끝로,땅끝만물수퍼앞(땅끝→남창교차로),1,60,,,34.30,126.52\n" + // "끝"은 구간 표시가 아님 → 일반
            "L1,경기,안성로,안성초등학교 앞,1,30,,45,37.00,127.27\n"               // 위치구분 없음 → 길이가 있어도 일반
        val list = CameraCsv.parse(csv)
        val byName = list.associateBy { it.name }
        assertEquals(CamKind.SECTION_START, byName.getValue("중부고속도로 254.9km 창원방향").kind)
        assertEquals(CamKind.SECTION_END, byName.getValue("중부고속도로 263.0km 창원방향").kind)
        assertEquals(CamKind.SECTION_START, byName.getValue("남동대로 구월중학교직전(구간단속 시점)").kind)
        assertEquals(CamKind.SPOT, byName.getValue("경춘로 호평동 버스종점사거리 부근").kind)
        assertEquals(CamKind.SPOT, byName.getValue("땅끝로 땅끝만물수퍼앞(땅끝→남창교차로)").kind)
        assertEquals(CamKind.SPOT, byName.getValue("안성로 안성초등학교 앞").kind)
        assertEquals(0, byName.getValue("안성로 안성초등학교 앞").sectionLenM)
    }

    @Test fun csvParse_lengthUnitsAndClamp() {
        assertEquals(7500, CameraCsv.lenToMeters(7.5))
        assertEquals(300, CameraCsv.lenToMeters(0.3))
        assertEquals(11283, CameraCsv.lenToMeters(11283.0))
        assertEquals(0, CameraCsv.lenToMeters(45.0))     // 45km: 쓰레기 값
        assertEquals(0, CameraCsv.lenToMeters(200.0))    // 200m: 너무 짧음
        assertEquals(0, CameraCsv.lenToMeters(99999.0))
    }
}
