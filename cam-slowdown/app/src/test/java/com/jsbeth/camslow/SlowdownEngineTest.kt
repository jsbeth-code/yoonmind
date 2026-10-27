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
            e.update(Fix(la, lo, v, 0.0, acc)).filter { it !is EngineEvent.Hold }.forEach { out += it to n }
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
        // 넓은 도로: 카메라 좌표가 왼쪽(중앙분리대 쪽 + GPS 오차). 통과 전에 "경로 이탈" 복귀가 나가면 안 된다.
        // 도심(제한 60)은 왼쪽 8m 기준, 고속도로(제한 100)는 15m 기준이라 허용 범위 안의 값으로 시험한다.
        for ((kmh, cross, limit) in listOf(Triple(50.0, -10.0, 60), Triple(60.0, -12.0, 60), Triple(110.0, -18.0, 100))) {
            val ev = driveNorth(listOf(cam(cross, 3000.0, limit = limit)), kmh, 0.0, 5000.0)
            assertEquals("$kmh/$cross 이벤트 수", 2, ev.size)
            val un = upAt(ev)
            assertTrue("$kmh/$cross 복귀 위치 $un", un - 3000.0 in 90.0..170.0)
        }
    }

    @Test fun cityTolerance_isNarrowerThanHighway() {
        // 왼쪽 18m 카메라: 도심(제한 60, 왼쪽 8m+거리의 3%)에선 반대 차로로 보고 무시, 고속도로(제한 100, 15m+3%)에선 잡는다
        assertEquals(0, driveNorth(listOf(cam(-18.0, 3000.0, limit = 60)), 60.0, 0.0, 5000.0).size)
        assertEquals(2, driveNorth(listOf(cam(-18.0, 3000.0, limit = 100)), 100.0, 0.0, 5000.0).size)
    }

    @Test fun directedCamera_wideSymmetricTolerance() {
        // 방향을 아는 카메라는 왼쪽 30m에 있어도(반대 차로가 아니라고 확인된 셈) 잡고, 반대 방향이면 무시
        val mine = cam(-30.0, 3000.0, limit = 100).copy(bearingDeg = 0.0)
        assertEquals(2, driveNorth(listOf(mine), 100.0, 0.0, 5000.0).size)
        val opposite = cam(8.0, 3000.0, limit = 100).copy(bearingDeg = 180.0)
        assertEquals(0, driveNorth(listOf(opposite), 100.0, 0.0, 5000.0).size)
    }

    @Test fun deadReckoning_slowsButNeverRestores() {
        // GPS가 2000m에서 끊기고 추측 위치로 계속 감 → 3000m 카메라에 감속은 하고, 통과해도 복귀는 안 함. GPS가 돌아오면 복귀.
        val e = SlowdownEngine(CameraIndex(listOf(cam(8.0, 3000.0, limit = 100))))
        val v = 100 / 3.6
        val ev = ArrayList<Pair<EngineEvent, Double>>()
        var n = 0.0
        while (n < 2000) { val (la, lo) = at(0.0, n); e.update(Fix(la, lo, v, 0.0)).forEach { ev += it to n }; n += v }
        while (n < 4000) { val (la, lo) = at(0.0, n); e.update(Fix(la, lo, v, 0.0, 5.0, estimated = true)).forEach { ev += it to n }; n += v }
        assertEquals(1, ev.size)
        assertTrue(ev[0].first is EngineEvent.Down); assertTrue(3000.0 - ev[0].second in 200.0..600.0)
        assertTrue(e.isSlowed)
        // 실제 GPS 복귀: 카메라를 300m 넘게 지난 위치 → 경로 이탈 규칙으로 5 fix 뒤 복귀
        repeat(6) { val (la, lo) = at(0.0, n); e.update(Fix(la, lo, v, 0.0)).forEach { ev += it to n }; n += v }
        assertEquals(2, ev.size); assertTrue(ev[1].first is EngineEvent.Up)
    }

    @Test fun schoolZone_enterAndLeave() {
        val zone = Camera(at(0.0, 3000.0).first, at(0.0, 3000.0).second, 30, CamKind.SPOT, 0, "OO초등학교", ZoneCsv.CODE)
        val e = SlowdownEngine(CameraIndex(emptyList()), EngineConfig(schoolZoneOn = true, schoolZoneRadiusM = 300.0), CameraIndex(listOf(zone)))
        val v = 40 / 3.6
        val ev = ArrayList<Pair<EngineEvent, Double>>()
        var n = 0.0
        while (n <= 5000) { val (la, lo) = at(0.0, n); e.update(Fix(la, lo, v, 0.0)).forEach { ev += it to n }; n += v }
        assertEquals(2, ev.size)
        val (down, dn) = ev[0]; assertTrue(down is EngineEvent.Down); assertEquals(ZoneCsv.CODE, (down as EngineEvent.Down).camera.code)
        assertTrue("진입 $dn", 3000.0 - dn in 280.0..310.0)
        val (up, un) = ev[1]; assertTrue(up is EngineEvent.Up); assertEquals("보호구역 벗어남", (up as EngineEvent.Up).reason)
        assertTrue("이탈 $un", un - 3000.0 in 390.0..420.0)
        // 꺼져 있으면 아무 일 없음
        assertEquals(0, SlowdownEngine(CameraIndex(emptyList()), EngineConfig(schoolZoneOn = false), CameraIndex(listOf(zone))).let { e2 ->
            var m = 0.0; var c = 0
            while (m <= 5000) { val (la, lo) = at(0.0, m); c += e2.update(Fix(la, lo, v, 0.0)).size; m += v }; c
        })
    }

    @Test fun schoolZoneAndCamera_singleDownUp() {
        // 보호구역 안에 카메라가 있을 때: 진입 때 한 번 내리고, 카메라 통과와 구역 이탈 중 늦은 쪽에서 한 번 올린다
        val zone = Camera(at(0.0, 3000.0).first, at(0.0, 3000.0).second, 30, CamKind.SPOT, 0, "OO초등학교", ZoneCsv.CODE)
        val e = SlowdownEngine(CameraIndex(listOf(cam(5.0, 3100.0, limit = 30))), EngineConfig(schoolZoneOn = true, schoolZoneRadiusM = 300.0), CameraIndex(listOf(zone)))
        val v = 40 / 3.6
        val ev = ArrayList<Pair<EngineEvent, Double>>()
        var n = 0.0
        while (n <= 5000) { val (la, lo) = at(0.0, n); e.update(Fix(la, lo, v, 0.0)).forEach { ev += it to n }; n += v }
        assertEquals("이벤트 ${ev.map { it.first::class.simpleName + "@" + it.second.toInt() }}", 2, ev.size)
        assertTrue(ev[0].first is EngineEvent.Down); assertTrue(ev[1].first is EngineEvent.Up)
        assertTrue(ev[1].second - 3000.0 in 390.0..420.0)
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

    @Test fun decode_detectsDamagedUtf8Copy() {
        val cp949 = (header + "A1,경기,경부고속도로,서울방향,1,100,,,37.1,127.1\n").toByteArray(charset("MS949"))
        assertEquals(1, CameraCsv.parse(CameraCsv.decode(cp949)).size)                 // CP949 원본: 정상
        val damaged = String(cp949, Charsets.UTF_8).toByteArray(Charsets.UTF_8)         // UTF-8로 잘못 읽어 다시 저장한 사본
        val err = runCatching { CameraCsv.decode(damaged) }.exceptionOrNull()
        assertTrue(err is IllegalArgumentException); assertTrue(err!!.message!!.contains("깨졌습니다"))
        val utf8 = (header + "A1,경기,경부고속도로,서울방향,1,100,,,37.1,127.1\n").toByteArray(Charsets.UTF_8)
        assertEquals(1, CameraCsv.parse(CameraCsv.decode(utf8)).size)                  // 정상 UTF-8
    }

    @Test fun zoneCsv_parse() {
        val csv = "시설종류,대상시설명,소재지도로명주소,위도,경도,관리기관명\n" +
            "초등학교,서울OO초등학교,서울 어딘가,37.55,126.98,서울시\n" +
            "어린이집,OO어린이집,경기 어딘가,37.3,127.1,성남시\n" +
            "유치원,좌표없음,,,,\n"
        val z = ZoneCsv.parse(csv)
        assertEquals(2, z.size)
        assertEquals("서울OO초등학교 초등학교", z[0].name); assertEquals(ZoneCsv.CODE, z[0].code); assertEquals(30, z[0].limitKmh)
    }

    // ---- v0.6: 구간단속 방향·유지 검사·상한·좁은 길·직접 복귀

    /** 동부간선도로형 데이터: 의정부 방향 구간(시점 남쪽 → 종점 북쪽, 방향 코드 1)과 서울 방향 구간(방향 코드 2). */
    private val dirHeader = "무인교통단속카메라관리번호,시도명,도로노선명,도로노선방향,설치장소,단속구분,제한속도,단속구간위치구분,과속단속구간길이,위도,경도\n"
    private fun dongbuCsv(): String {
        fun row(id: String, dir: String, place: String, pos: String, len: String, east: Double, north: Double): String {
            val (la, lo) = at(east, north); return "$id,서울,동부간선도로,$dir,$place,99,80,$pos,$len,$la,$lo\n"
        }
        return dirHeader +
            row("N1", "1", "면목삼익아파트 3차로", "1", "7", 8.0, 2000.0) +      // 북진 시점(7 km)
            row("N2", "1", "면목삼익아파트 1,2차로", "1", "7", 8.0, 2000.0) +
            row("N3", "1", "상계마들아파트 3차로", "2", "7", 8.0, 9000.0) +      // 북진 종점
            row("N4", "1", "상계마들아파트 1,2차로", "2", "7", 8.0, 9000.0) +
            row("S1", "2", "상계근린공원 시점", "1", "3", -10.0, 12000.0) +      // 남진 시점(3 km)
            row("S2", "2", "초안산지하차도 종점", "2", "3", -10.0, 9000.0)        // 남진 종점
    }

    @Test fun pairing_derivesSectionDirection() {
        val list = CameraCsv.parse(dongbuCsv())
        val byName = list.associateBy { it.name }
        val n1 = byName.getValue("동부간선도로 면목삼익아파트 3차로")
        assertEquals(CamKind.SECTION_START, n1.kind); assertEquals(7000, n1.sectionLenM)
        assertEquals("북진 시점 방향", 0.0, n1.bearingDeg!!, 1.0)
        assertEquals(CameraCsv.LOOSE_HEADING_DEG, n1.headingTolDeg!!, 1e-9)
        assertTrue(n1.hasPartner); assertEquals(at(8.0, 9000.0).first, n1.partnerLat!!, 1e-9)
        val n3 = byName.getValue("동부간선도로 상계마들아파트 3차로")
        assertEquals(0.0, n3.bearingDeg!!, 1.0); assertTrue(n3.hasPartner)
        val s1 = byName.getValue("동부간선도로 상계근린공원 시점")
        assertEquals("남진 시점 방향", 180.0, s1.bearingDeg!!, 1.0)
    }

    @Test fun pairing_oppositeDirectionStart_ignoredWhileDriving() {
        val cams = CameraCsv.parse(dongbuCsv())
        val e = SlowdownEngine(CameraIndex(cams))
        val v = 80 / 3.6
        val ev = ArrayList<Pair<EngineEvent, Double>>()
        // 남진(성수 방향): 북진 구간 시점(2000)을 반대로 지나친다. 아무 일도 없어야 한다(2026-10 실제 사고 재현).
        var n = 4000.0
        while (n >= 500) { val (la, lo) = at(0.0, n); e.update(Fix(la, lo, v, 180.0)).forEach { ev += it to n }; n -= v }
        assertEquals("남진 이벤트 ${ev.map { it.first::class.simpleName + "@" + it.second.toInt() }}", 0, ev.size)
        // 북진: 시점에서 내리고 종점(9000) 지나 복귀
        val e2 = SlowdownEngine(CameraIndex(cams))
        n = 0.0
        while (n <= 11_000) { val (la, lo) = at(0.0, n); e2.update(Fix(la, lo, v, 0.0)).forEach { ev += it to n }; n += v }
        assertTrue(ev.any { it.first is EngineEvent.Down && 2000.0 - it.second in 100.0..600.0 })
        assertTrue(ev.any { it.first is EngineEvent.Hold })
        val up = ev.first { it.first is EngineEvent.Up }
        assertTrue("북진 복귀 ${up.second}", up.second - 9000.0 in 90.0..200.0)
    }

    @Test fun pairing_noBearingWhenAmbiguousOrMismatch() {
        fun row(id: String, road: String, dir: String, pos: String, len: String, east: Double, north: Double): String {
            val (la, lo) = at(east, north); return "$id,서울,$road,$dir,x,99,80,$pos,$len,$la,$lo\n"
        }
        val csv = dirHeader +
            row("A1", "길A", "", "1", "7", 8.0, 0.0) + row("A2", "길A", "", "2", "7", 8.0, 2000.0) +        // 길이 7 km인데 종점 2 km → 안 맞음
            row("B1", "길B", "", "1", "", 8.0, 0.0) + row("B2", "길B", "", "2", "", 8.0, 3000.0) + row("B3", "길B", "", "2", "", 8.0, -3000.0) + // 길이 모름, 양쪽에 종점 → 모호
            row("C1", "길C", "", "1", "", 8.0, 0.0) + row("C2", "길C", "", "2", "", 8.0, 4000.0)           // 길이 모름, 종점 하나 → 방향 붙음
        val byName = CameraCsv.parse(csv).associateBy { it.name.substringBefore(" ") + it.lat.toString() }
        val cams = CameraCsv.parse(csv)
        fun find(id: Double, road: String) = cams.first { it.name.startsWith(road) && Math.abs(it.lat - at(0.0, id).first) < 1e-7 }
        assertEquals(null, find(0.0, "길A").bearingDeg)
        assertEquals(null, find(0.0, "길B").bearingDeg)
        assertEquals(0.0, find(0.0, "길C").bearingDeg!!, 1.0)
        assertTrue(byName.isNotEmpty())
    }

    @Test fun looseHeading_curvedApproachStillCaught() {
        // 느슨한 방향(120도): 추정 방향이 북(0)인데 100도 방향으로 접근해도 잡는다. 150도면(사실상 반대) 무시.
        val start = cam(0.0, 0.0, kind = CamKind.SECTION_START, len = 3000).copy(bearingDeg = 0.0, headingTolDeg = 120.0)
        fun drive(course: Double): Int {
            val e = SlowdownEngine(CameraIndex(listOf(start)))
            val v = 60 / 3.6; var d = -1500.0; var c = 0
            val rad = Math.toRadians(course)
            while (d <= 300) { val (la, lo) = at(d * Math.sin(rad), d * Math.cos(rad)); c += e.update(Fix(la, lo, v, course)).count { it is EngineEvent.Down }; d += v }
            return c
        }
        assertEquals(1, drive(100.0))
        assertEquals(0, drive(150.0))
    }

    @Test fun holdProgress_wrongWayWithoutBearing_releasesQuickly() {
        // 방향을 못 붙인 시점(짝만 있음)을 반대로 지나침 → 종점 반대쪽으로 300 m 멀어지면 "반대 방향" 복귀
        val start = cam(-8.0, 2000.0, kind = CamKind.SECTION_START, len = 7000).copy(partnerLat = at(0.0, 9000.0).first, partnerLon = at(0.0, 9000.0).second)
        val e = SlowdownEngine(CameraIndex(listOf(start)))
        val v = 80 / 3.6
        val ev = ArrayList<Pair<EngineEvent, Double>>()
        var n = 4000.0
        while (n >= 0) { val (la, lo) = at(0.0, n); e.update(Fix(la, lo, v, 180.0)).forEach { ev += it to n }; n -= v }
        val up = ev.first { it.first is EngineEvent.Up }
        assertEquals("반대 방향", (up.first as EngineEvent.Up).reason)
        // 진행 방향이 구간 방향과 뒤집힌 fix 5개(약 110 m) 뒤, 늦어도 300 m 멀어졌을 때
        assertTrue("복귀 위치 ${up.second}", 2000.0 - up.second in 100.0..420.0)
    }

    @Test fun holdProgress_uTurnWithoutPartner_releases() {
        // 짝도 길이도 모르는 시점: 지난 뒤 유턴하면 5 fix 안에 복귀
        val start = cam(8.0, 2000.0, kind = CamKind.SECTION_START)
        val e = SlowdownEngine(CameraIndex(listOf(start)))
        val v = 60 / 3.6
        val events = ArrayList<EngineEvent>()
        var n = 0.0
        while (n <= 2600) { val (la, lo) = at(0.0, n); events += e.update(Fix(la, lo, v, 0.0)); n += v }
        assertTrue(e.isSlowed); assertTrue(events.any { it is EngineEvent.Hold })
        var cnt = 0
        while (cnt < 12 && e.isSlowed) { n -= v; val (la, lo) = at(20.0, n); events += e.update(Fix(la, lo, v, 180.0)); cnt++ }
        assertTrue("유턴 뒤 $cnt fix", cnt in 5..6)
        assertEquals("반대 방향", (events.last() as EngineEvent.Up).reason)
    }

    @Test fun holdProgress_partnerReached_shortensHold() {
        // 종점 카메라가 데이터에 없어도(또는 못 봐도) 짝 종점 위치에 닿으면 복귀 거리만 남긴다. 길이 20 km가 틀려도.
        val start = cam(8.0, 2000.0, kind = CamKind.SECTION_START, len = 20_000).copy(partnerLat = at(8.0, 6000.0).first, partnerLon = at(8.0, 6000.0).second)
        val ev = driveNorth(listOf(start), 100.0, 0.0, 12_000.0)
        val up = ev.first { it.first is EngineEvent.Up }
        assertTrue("짝 종점 기준 복귀 ${up.second}", up.second - 6000.0 in 90.0..200.0)
    }

    @Test fun unknownLengthNoPartner_cappedAt10km() {
        val ev = driveNorth(listOf(cam(8.0, 1000.0, kind = CamKind.SECTION_START)), 100.0, 0.0, 14_000.0)
        val up = ev.first { it.first is EngineEvent.Up }
        assertTrue("상한 복귀 ${up.second}", up.second - 11_000.0 in 90.0..200.0)
    }

    @Test fun narrowRoadCamera_notCaughtFromParallelRoad() {
        // 제한 30 카메라가 오른쪽 30 m(옆 길)에 있으면 무시. 제한 60이면 도심 허용폭 40 m 안이라 잡는다. 제한 30이라도 12 m면 잡는다.
        assertEquals(0, driveNorth(listOf(cam(30.0, 3000.0, limit = 30)), 60.0, 0.0, 5000.0).size)
        assertEquals(2, driveNorth(listOf(cam(30.0, 3000.0, limit = 60)), 60.0, 0.0, 5000.0).size)
        assertEquals(2, driveNorth(listOf(cam(12.0, 3000.0, limit = 30)), 60.0, 0.0, 5000.0).size)
    }

    @Test fun manualRelease_noRetriggerUntilPassed() {
        val e = SlowdownEngine(CameraIndex(listOf(cam(8.0, 1000.0), cam(8.0, 3000.0))))
        val v = 25.0
        val events = ArrayList<EngineEvent>()
        var n = 0.0
        while (n < 800) { val (la, lo) = at(0.0, n); events += e.update(Fix(la, lo, v, 0.0)); n += v }
        assertTrue(e.isSlowed)
        assertTrue(e.release()); assertFalse(e.isSlowed); assertFalse(e.release())
        // 같은 카메라(1000)를 지날 때까지 다시 안 잡고, 다음 카메라(3000)는 정상
        while (n < 4000) { val (la, lo) = at(0.0, n); events += e.update(Fix(la, lo, v, 0.0)); n += v }
        val downs = events.filterIsInstance<EngineEvent.Down>()
        assertEquals("Down 수 $downs", 2, downs.size)
        assertEquals(1, events.filterIsInstance<EngineEvent.Up>().size)
    }

    @Test fun status_exposesTriggerAndHold() {
        val e = SlowdownEngine(CameraIndex(listOf(cam(8.0, 2000.0, kind = CamKind.SECTION_START, len = 5000))))
        val v = 100 / 3.6
        var n = 500.0
        val (la, lo) = at(0.0, n)
        e.update(Fix(la, lo, v, 0.0))
        assertEquals(e.triggerDistance(v, 100), e.status.triggerM!!, 1e-9)
        while (n < 2500) { val (a, b) = at(0.0, n); e.update(Fix(a, b, v, 0.0)); n += v }
        assertEquals("구간단속", e.status.holdKind)
        assertTrue("남은 거리 ${e.status.holdRemainM}", e.status.holdRemainM!! in 4400.0..4700.0)
        assertTrue(e.status.note.contains("종점까지"))
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

class KecCsvTest {
    private val lat0 = 37.0
    private val lon0 = 127.0
    private val mPerDegLat = 111_195.0
    private val mPerDegLon = 111_195.0 * Math.cos(Math.toRadians(lat0))
    private fun at(east: Double, north: Double) = Pair(lat0 + north / mPerDegLat, lon0 + east / mPerDegLon)

    /** 북쪽으로 곧게 뻗은 가상 노선: 이정 0~20 km, 1 km 간격. X=위도, Y=경도(도로공사 샘플 그대로). */
    private fun pointsCsv(): String = buildString {
        append("노선번호,도로명,이정,X좌표값,Y좌표값,GRS80X좌표값,GRS80Y좌표값\n")
        for (km in 0..20) { val (la, lo) = at(0.0, km * 1000.0); append("0010,경부고속도로,$km.0,$la,$lo,0,0\n") }
    }

    private val sectionsCsv = "노선,방향,시점,시점인근시설물,종점,종점인근시설물,연장\n" +
        "경부선,서울방향,5.0,A휴게소,12.0,B IC,7.0\n" +     // 북진(이정 증가)
        "경부선,부산방향,12.0,B IC,5.0,A휴게소,7km\n" +     // 남진(이정 감소), 연장에 단위
        "없는선,서울방향,1.0,,3.0,,2\n"                     // 노선 못 찾음

    @Test fun routeKeyNormalizes() {
        assertEquals("경부", KecCsv.routeKey("경부선"))
        assertEquals("경부", KecCsv.routeKey("경부고속도로"))
        assertEquals("경부", KecCsv.routeKey("경부 고속도로"))
        assertEquals("남해제2지", KecCsv.routeKey("남해고속도로제2지선"))
        assertEquals("남해제2지", KecCsv.routeKey("남해제2지선"))
    }

    @Test fun parsePoints_latLonEitherOrder() {
        val routes = KecCsv.parsePoints(pointsCsv())
        assertEquals(21, routes.getValue("경부").size)
        assertEquals(21, routes.getValue("#10").size)
        // X/Y가 바뀐 파일도 읽는다
        val swapped = pointsCsv().lines().mapIndexed { i, l ->
            if (i == 0 || l.isBlank()) l else l.split(",").let { f -> listOf(f[0], f[1], f[2], f[4], f[3], f[5], f[6]).joinToString(",") }
        }.joinToString("\n")
        val r2 = KecCsv.parsePoints(swapped)
        assertEquals(routes.getValue("경부")[3].lat, r2.getValue("경부")[3].lat, 1e-9)
    }

    @Test fun parseSections_columnsAndUnits() {
        val secs = KecCsv.parseSections(sectionsCsv)
        assertEquals(3, secs.size)
        assertEquals(5.0, secs[0].startKm, 1e-9); assertEquals(12.0, secs[0].endKm, 1e-9)
        assertEquals(7000, secs[0].lengthM); assertEquals(7000, secs[1].lengthM)
        assertEquals("A휴게소", secs[0].startName); assertEquals("B IC", secs[0].endName)
    }

    @Test fun build_directionalCamerasOnOwnCarriageway() {
        val (cams, warn) = KecCsv.build(KecCsv.parseSections(sectionsCsv), KecCsv.parsePoints(pointsCsv()), 100)
        assertEquals(1, warn.size); assertTrue(warn[0].contains("없는선"))
        assertEquals(4, cams.size)
        val nStart = cams[0]; val nEnd = cams[1]; val sStart = cams[2]; val sEnd = cams[3]
        assertEquals(CamKind.SECTION_START, nStart.kind); assertEquals(CamKind.SECTION_END, nEnd.kind)
        assertEquals(0.0, nStart.bearingDeg!!, 1.0); assertEquals(180.0, sStart.bearingDeg!!, 1.0)
        // 북진 시점: 5 km 지점, 진행 방향 오른쪽(동쪽) 8 m
        val (e, n) = Geo.toLocal(lat0, lon0, nStart.lat, nStart.lon)
        assertEquals(5000.0, n, 2.0); assertEquals(8.0, e, 0.5)
        val (e2, n2) = Geo.toLocal(lat0, lon0, sEnd.lat, sEnd.lon)
        assertEquals(5000.0, n2, 2.0); assertEquals(-8.0, e2, 0.5)   // 남진 종점은 서쪽
        assertEquals(7000, nStart.sectionLenM); assertEquals(100, nStart.limitKmh)
        assertTrue(nStart.fromKec); assertTrue(nEnd.name.contains("종점"))
    }

    @Test fun engine_usesOnlyMatchingDirection() {
        val (cams, _) = KecCsv.build(KecCsv.parseSections(sectionsCsv), KecCsv.parsePoints(pointsCsv()), 100)
        val e = SlowdownEngine(CameraIndex(cams))
        val v = 100 / 3.6
        val ev = ArrayList<Pair<EngineEvent, Double>>()
        var n = 0.0
        while (n <= 15_000) { val (la, lo) = at(0.0, n); e.update(Fix(la, lo, v, 0.0)).filter { it !is EngineEvent.Hold }.forEach { ev += it to n }; n += v }
        // 북진: 남진 구간의 시점(12 km, 방위 180)과 종점(5 km)은 무시. 내 구간 5 km에서 내리고 12 km 종점 지나 복귀.
        assertEquals(2, ev.size)
        assertTrue(ev[0].first is EngineEvent.Down); assertTrue(5000.0 - ev[0].second in 200.0..700.0)
        assertTrue(ev[1].first is EngineEvent.Up); assertTrue("복귀 ${ev[1].second}", ev[1].second - 12_000.0 in 90.0..170.0)
        // 남진: 반대로
        val e2 = SlowdownEngine(CameraIndex(cams))
        val ev2 = ArrayList<Pair<EngineEvent, Double>>()
        n = 15_000.0
        while (n >= 0) { val (la, lo) = at(0.0, n); e2.update(Fix(la, lo, v, 180.0)).filter { it !is EngineEvent.Hold }.forEach { ev2 += it to n }; n -= v }
        assertEquals(2, ev2.size)
        assertTrue(ev2[0].second - 12_000.0 in 200.0..700.0)
        assertTrue("남진 복귀 ${ev2[1].second}", 5000.0 - ev2[1].second in 90.0..170.0)
    }

    @Test fun merge_dropsPoliceSectionsNearKec() {
        val (kec, _) = KecCsv.build(KecCsv.parseSections(sectionsCsv), KecCsv.parsePoints(pointsCsv()), 100)
        val (la, lo) = at(10.0, 5050.0)
        val (la2, lo2) = at(10.0, 8000.0)
        val police = listOf(
            Camera(la, lo, 100, CamKind.SECTION_START, 7000, "경찰청 시점(도로공사 시점 50m 옆)"),
            Camera(la2, lo2, 100, CamKind.SPOT, 0, "경찰청 일반 카메라"),
        )
        val merged = KecCsv.merge(police, kec)
        assertEquals(1 + kec.size, merged.size)
        assertTrue(merged.any { it.name == "경찰청 일반 카메라" })
        assertFalse(merged.any { it.name.startsWith("경찰청 시점") })
    }

    @Test fun assignBearings_bySideOfCenterline() {
        val routes = KecCsv.parsePoints(pointsCsv())   // 북쪽으로 뻗은 중심선, 이정 증가 = 북진
        val right = Camera(at(10.0, 7000.0).first, at(10.0, 7000.0).second, 100, CamKind.SPOT, 0, "오른쪽 10m")
        val left = Camera(at(-12.0, 7000.0).first, at(-12.0, 7000.0).second, 100, CamKind.SPOT, 0, "왼쪽 12m")
        val onLine = Camera(at(1.0, 7000.0).first, at(1.0, 7000.0).second, 100, CamKind.SPOT, 0, "중심선 위")
        val far = Camera(at(200.0, 7000.0).first, at(200.0, 7000.0).second, 100, CamKind.SPOT, 0, "200m 밖")
        val city = Camera(at(10.0, 7000.0).first, at(10.0, 7000.0).second, 50, CamKind.SPOT, 0, "제한 50")
        val out = KecCsv.assignBearings(listOf(right, left, onLine, far, city), routes)
        assertEquals(0.0, out[0].bearingDeg!!, 1.0)     // 오른쪽 = 북진용
        assertEquals(180.0, out[1].bearingDeg!!, 1.0)   // 왼쪽 = 남진용
        assertEquals(null, out[2].bearingDeg)
        assertEquals(null, out[3].bearingDeg)
        assertEquals(null, out[4].bearingDeg)
    }

    @Test fun inheritLimits_fromNearbyPoliceSection() {
        val (kec, _) = KecCsv.build(KecCsv.parseSections(sectionsCsv), KecCsv.parsePoints(pointsCsv()), 100)
        val police = listOf(
            Camera(at(10.0, 5200.0).first, at(10.0, 5200.0).second, 110, CamKind.SECTION_START, 7000, "경찰청 시점 200m 옆"),
            Camera(at(10.0, 5000.0).first, at(10.0, 5000.0).second, 80, CamKind.SPOT, 0, "일반 카메라(무시)"),
        )
        val out = KecCsv.inheritLimits(kec, police, 100)
        assertEquals(110, out[0].limitKmh)     // 북진 시점(5km)은 경찰청 시점에서 110
        assertEquals(100, out[1].limitKmh)     // 북진 종점(12km)은 근처에 없어 기본값
    }

    @Test fun build_matchesRouteVariants() {
        // 이정좌표 쪽 표기: 서산영덕선(청주상주선) 0~20km, 부산포항선 0~20km, 호남선의 지선, 수도권제2순환선(전체)·파주양주선(지선)
        val pts = buildString {
            append("노선번호,도로명,이정,X좌표값,Y좌표값\n")
            fun route(no: String, name: String, eastOffsetM: Double) {
                for (km in 0..20) { val (la, lo) = at(eastOffsetM, km * 1000.0); append("$no,$name,$km.0,$la,$lo\n") }
            }
            route("0303", "서산영덕선(청주상주선)", 0.0)
            route("0655", "부산포항선", 5000.0)
            route("2510", "호남선의 지선", 10000.0)
            route("4000", "수도권제2순환선", 15000.0)
            route("4003", "파주양주선", 20000.0)
        }
        val secs = "노선,방향,시점,시점인근시설물,종점,종점인근시설물,연장\n" +
            "당진영덕선(청주영덕),영덕,3,A,9,B,6\n" +          // 옛 이름 + 괄호 → 서산영덕선(청주상주선)
            "동해선(부산포항),포항,2,A,8,B,6\n" +              // 괄호 안 이름 → 부산포항선
            "호남지선,논산,1,A,7,B,6\n" +                      // 호남선의 지선
            "수도권제2순환선(파주-양주),파주,4,A,10,B,6\n" +   // 괄호 안 → 파주양주선
            "수도권제2순환선(화도-양평),화도,12,A,18,B,6\n" +  // 괄호 안 노선 없음 → 괄호 뺀 이름으로
            "없는선,서울,1,A,5,B,4\n"
        val (cams, warn) = KecCsv.build(KecCsv.parseSections(secs), KecCsv.parsePoints(pts), 100)
        assertEquals("경고 $warn", 1, warn.size); assertTrue(warn[0].contains("없는선"))
        assertEquals(10, cams.size)
        fun east(c: Camera) = Geo.toLocal(lat0, lon0, c.lat, c.lon).first
        assertEquals(0.0, east(cams[0]), 20.0)        // 서산영덕
        assertEquals(5000.0, east(cams[2]), 20.0)     // 부산포항선
        assertEquals(10000.0, east(cams[4]), 20.0)    // 호남지선
        assertEquals(20000.0, east(cams[6]), 20.0)    // 파주양주선
        assertEquals(15000.0, east(cams[8]), 20.0)    // 수도권제2순환선
    }

    @Test fun angleDiff() {
        assertEquals(20.0, Geo.angleDiff(350.0, 10.0), 1e-9)
        assertEquals(180.0, Geo.angleDiff(0.0, 180.0), 1e-9)
        assertEquals(90.0, Geo.angleDiff(45.0, 315.0), 1e-9)
    }
}
