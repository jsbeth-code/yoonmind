package com.jsbeth.camslow

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SlowdownEngineTest {
    private val lat0 = 37.0
    private val lon0 = 127.0
    private val mPerDegLat = 111_195.0
    private val mPerDegLon = 111_195.0 * Math.cos(Math.toRadians(lat0))

    /** 기준점에서 (동 m, 북 m) 위치. */
    private fun at(east: Double, north: Double) = Pair(lat0 + north / mPerDegLat, lon0 + east / mPerDegLon)

    private fun cam(east: Double, north: Double, limit: Int = 100, kind: CamKind = CamKind.SPOT, len: Int = 0): Camera {
        val (la, lo) = at(east, north)
        return Camera(la, lo, limit, kind, len, "test")
    }

    /** 북쪽으로 일정 속도 주행. 각 이벤트와 발생 위치(북 m)를 돌려준다. */
    private fun driveNorth(
        cams: List<Camera>, kmh: Double, fromN: Double, toN: Double, cfg: EngineConfig = EngineConfig(),
    ): List<Pair<EngineEvent, Double>> {
        val e = SlowdownEngine(CameraIndex(cams), cfg)
        val v = kmh / 3.6
        val out = ArrayList<Pair<EngineEvent, Double>>()
        var n = fromN
        while (n <= toN) {
            val (la, lo) = at(0.0, n)
            e.update(Fix(la, lo, v, 0.0)).forEach { out += it to n }
            n += v
        }
        return out
    }

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

    @Test fun oppositeCarriagewayCamera_ignored() {
        // 반대 차로(왼쪽 30m) 카메라
        val ev = driveNorth(listOf(cam(-30.0, 3000.0)), 110.0, 0.0, 5000.0)
        assertEquals(0, ev.size)
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

    @Test fun csvParse_cp949Header() {
        val csv = "무인교통단속카메라관리번호,시도명,도로노선명,설치장소,단속구분,제한속도,단속구간위치구분,과속단속구간길이,위도,경도\n" +
            "A1,경기,경부고속도로,\"서울방향, 100k\",2,100,,,37.1,127.1\n" +
            "A2,경기,경부고속도로,구간시점,2,100,시점,7.5,37.2,127.2\n" +
            "A3,경기,국도,신호,1,60,,,37.3,127.3\n" +
            "A4,경기,국도,잘못된좌표,2,60,,,0,0\n"
        val bytes = csv.toByteArray(charset("MS949"))
        val list = CameraCsv.parse(CameraCsv.decode(bytes))
        assertEquals(2, list.size)
        assertEquals("경부고속도로 서울방향, 100k", list[0].name)
        assertEquals(CamKind.SECTION_START, list[1].kind)
        assertEquals(7500, list[1].sectionLenM)
    }
}
