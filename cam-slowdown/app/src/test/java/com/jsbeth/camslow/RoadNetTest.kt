package com.jsbeth.camslow

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * tools/make_sample_osm.py → tools/osm2roads.py 로 만든 sample-roads.zip(테스트 리소스)으로 맵매칭을 검증한다.
 * 도로 배치는 make_sample_osm.py 머리말 참고. 좌표계: 기준점 (37.05, 127.05)에서 동·북 m.
 */
class RoadNetTest {
    private val lat0 = 37.05
    private val lon0 = 127.05
    private val mPerDegLat = 111_195.0
    private val mPerDegLon = 111_195.0 * Math.cos(Math.toRadians(lat0))

    private fun at(east: Double, north: Double) = Pair(lat0 + north / mPerDegLat, lon0 + east / mPerDegLon)

    private fun cam(east: Double, north: Double, limit: Int = 80, name: String = "c$east/$north"): Camera {
        val (la, lo) = at(east, north)
        return Camera(la, lo, limit, CamKind.SPOT, 0, name, "1")
    }

    private fun net(cams: List<Camera>): RoadNet {
        val tmp = File.createTempFile("roads", ".zip")
        tmp.deleteOnExit()
        javaClass.getResourceAsStream("/sample-roads.zip")!!.use { it.copyTo(tmp.outputStream()) }
        return RoadNet.open(tmp, cams)
    }

    private fun RoadNet.viewAt(east: Double, north: Double, course: Double, lookahead: Double = 2000.0): RoadView? {
        val (la, lo) = at(east, north)
        return view(la, lo, course, lookahead)
    }

    private fun RoadView.names() = ahead.map { it.cam.name }

    @Test fun tileLoads_andSnapsToRoad() {
        val n = net(emptyList())
        assertEquals(1, n.tileCount)
        assertTrue(n.info.contains("format=KRRT2"))
        val (la, lo) = at(0.0, 2000.0)
        val tile = n.ensure(la, lo)!!
        assertEquals(9, tile.wayCount)
        assertTrue(tile.oneway(0)); assertFalse(tile.oneway(2)); assertEquals(80, tile.maxspeed(0)); assertEquals(60, tile.maxspeed(8))
        val v = n.viewAt(0.0, 2000.0, 0.0)!!
        assertEquals(0.0, v.bearingDeg, 0.5)
        assertEquals(80, v.maxspeedKmh)
        assertTrue(v.distM < 1.0)
        assertEquals("간선도로", v.roadName)
        assertEquals("간선도로 · 북 · 지도 제한 80", v.describe())
        assertEquals("지방도 7", n.viewAt(1000.0, 1000.0, 0.0)!!.roadName)
        assertEquals("", n.viewAt(2000.0, -800.0, 0.0)!!.roadName)
        assertTrue(n.viewAt(2000.0, -800.0, 0.0)!!.describe().startsWith("이름 없는 길 · 북"))
        // 도로에서 60m 떨어진 들판 → 없음
        assertNull(n.viewAt(-120.0, 2000.0, 0.0))
        // 방향이 90° 다르면 없음
        assertNull(n.viewAt(0.0, 2000.0, 90.0))
    }

    @Test fun parallelRoad_excluded_oppositeCarriageway_unknownDirection_servesBoth() {
        val side = cam(55.0, 3000.0, 50, "옆길")
        val mine = cam(5.0, 3200.0, 80, "내길")
        val opposite = cam(-17.0, 2500.0, 80, "반대편")
        val n = net(listOf(side, mine, opposite))
        val v = n.viewAt(0.0, 2000.0, 0.0)!!
        // 옆길(안 이어진 도로)은 빠지고, 방향 모르는 반대 차로 카메라는 양쪽에 다 붙는다(좌표 좌우로 방향을 추정하지 않음)
        assertEquals(listOf("반대편", "내길"), v.names())
        assertEquals(1200.0, v.ahead.first { it.cam.name == "내길" }.alongM, 15.0)
        assertTrue(n.isAttached(side)); assertTrue(n.isAttached(opposite))
        assertEquals(true, n.servesHeading(opposite, 0.0))
        assertEquals(true, n.servesHeading(opposite, 180.0))
        val south = n.viewAt(-12.0, 3000.0, 180.0)!!
        assertEquals(listOf("반대편"), south.names())
        assertEquals(500.0, south.ahead[0].alongM, 15.0)
        // 남행 카메라를 도로에 내린 점은 남행 차로(east=-12) 위
        val (_, lo12) = at(-12.0, 2500.0)
        assertEquals(lo12, south.ahead[0].lon, 2e-5)
    }

    @Test fun directedCamera_onOppositeCarriageway_attachesToOwnDirectionFarAway() {
        // 북행 구간 시점이 남행 차로(east=-12) 위에 찍힘(동부간선 면목) — 같은 이름(간선도로)의 북행 차로에 붙는다
        val start = cam(-12.0, 2500.0, 80, "북행시점").copy(bearingDeg = 0.0, headingTolDeg = 120.0, roadName = "간선도로")
        val n = net(listOf(start))
        assertEquals(listOf("북행시점"), n.viewAt(0.0, 2000.0, 0.0)!!.names())
        assertEquals(emptyList<String>(), n.viewAt(-12.0, 3000.0, 180.0)!!.names())
        val rc = n.viewAt(0.0, 2000.0, 0.0)!!.ahead[0]
        val (_, lo0) = at(0.0, 2500.0)
        assertEquals(lo0, rc.lon, 2e-5)   // 도로에 내린 점은 북행 차로 위
        assertEquals(500.0, rc.alongM, 15.0)
    }

    @Test fun directedCamera_twoWayRoad_sideFromBearing_notFromCoordinate() {
        // 왕복 2차선(east=1000)에서 좌표는 왼쪽(994)인데 글로 북행이라고 알면 북행
        val north = cam(994.0, 2000.0, 60, "북행").copy(bearingDeg = 10.0, headingTolDeg = 90.0)
        val south = cam(1005.0, 2000.0, 60, "남행").copy(bearingDeg = 170.0, headingTolDeg = 90.0)
        val sideways = cam(1001.0, 2200.0, 60, "어긋남").copy(bearingDeg = 90.0, headingTolDeg = 90.0)   // 길과 90° → 모름 = 양쪽
        val n = net(listOf(north, south, sideways))
        assertEquals(setOf("북행", "어긋남"), n.viewAt(1000.0, 1000.0, 0.0)!!.names().toSet())
        assertEquals(setOf("남행", "어긋남"), n.viewAt(1000.0, 3000.0, 180.0)!!.names().toSet())
    }

    @Test fun roadNameMatch_preferredOverNearerSideRoad() {
        // 간선(east=0)과 옆길(east=50) 사이, 옆길에 더 가까운(east=32) 좌표지만 도로노선명이 간선도로면 간선에 붙는다
        val c = cam(32.0, 2000.0, 80, "교차로좌표").copy(roadName = "간선도로")
        val plain = cam(32.0, 1500.0, 80, "이름없음")
        val n = net(listOf(c, plain))
        assertEquals(listOf("교차로좌표"), n.viewAt(0.0, 1000.0, 0.0)!!.names())
        // 이름 없으면 가까운 옆길
        assertEquals(listOf("이름없음"), n.viewAt(50.0, 1000.0, 0.0)!!.names())
        // "도로"→"로" 같은 이름으로 본다
        val c2 = cam(32.0, 2500.0, 80, "축약").copy(roadName = "간선로")
        assertEquals(listOf("축약"), net(listOf(c2)).viewAt(0.0, 1000.0, 0.0)!!.names())
    }

    @Test fun medianCamera_attachedToBothCarriageways() {
        val median = cam(-5.0, 2500.0, 80, "중앙")
        val n = net(listOf(median))
        assertEquals(listOf("중앙"), n.viewAt(0.0, 2000.0, 0.0)!!.names())
        assertEquals(listOf("중앙"), n.viewAt(-12.0, 3000.0, 180.0)!!.names())
    }

    @Test fun junction_followsContinuationAndForks_notSideRoad() {
        val next = cam(4.0, 4500.0, 80, "이어진길")
        val perpendicular = cam(300.0, 3996.0, 60, "옆으로 꺾는 길")
        val fork = cam(171.0 + 4.0, 4470.0, 80, "갈림길")
        val n = net(listOf(next, perpendicular, fork))
        val v = n.viewAt(0.0, 2000.0, 0.0, lookahead = 3000.0)!!
        assertEquals(setOf("이어진길", "갈림길"), v.names().toSet())
        assertEquals(2500.0, v.ahead.first { it.cam.name == "이어진길" }.alongM, 15.0)
        assertEquals(2500.0, v.ahead.first { it.cam.name == "갈림길" }.alongM, 25.0)
        // 옆으로 꺾는 길을 달릴 때는 그 길 카메라만
        val e = n.viewAt(100.0, 4000.0, 90.0)!!
        assertEquals(listOf("옆으로 꺾는 길"), e.names())
    }

    @Test fun twoWayRoad_unknownDirection_servesBothSides() {
        val right = cam(1005.0, 2000.0, 60, "오른쪽")
        val left = cam(994.0, 2000.0, 60, "왼쪽")
        val center = cam(1001.0, 2000.0, 60, "가운데")
        val n = net(listOf(right, left, center))
        val all = setOf("오른쪽", "왼쪽", "가운데")
        assertEquals(all, n.viewAt(1000.0, 1000.0, 0.0)!!.names().toSet())
        assertEquals(all, n.viewAt(1000.0, 3000.0, 180.0)!!.names().toSet())
        assertEquals(60, n.viewAt(1000.0, 1000.0, 0.0)!!.maxspeedKmh)
    }

    @Test fun curve_alongIsPathDistance() {
        // 커브 끝(2500,500)에서 동쪽으로 달리는 차의 오른쪽(남쪽) 4m
        val end = cam(2500.0, 496.0, 60, "커브끝")
        val n = net(listOf(end))
        val v = n.viewAt(2000.0, -800.0, 0.0)!!
        assertEquals(listOf("커브끝"), v.names())
        // 직선 800 + 사분원 785 ≈ 1585 (직선거리는 1393)
        assertEquals(1585.0, v.ahead[0].alongM, 40.0)
    }

    @Test fun engineWithRoads_ignoresParallelRoadCamera() {
        val side = cam(55.0, 3000.0, 50, "옆길")
        val mine = cam(5.0, 3200.0, 80, "내길")
        val cams = listOf(side, mine)
        val n = net(cams)
        val e = SlowdownEngine(CameraIndex(cams), EngineConfig(), roads = n)
        val v = 80 / 3.6
        val ev = ArrayList<Pair<EngineEvent, Double>>()
        var north = 0.0
        while (north <= 4000) { val (la, lo) = at(0.0, north); e.update(Fix(la, lo, v, 0.0)).forEach { ev += it to north }; north += v }
        assertEquals(2, ev.size)
        val down = ev[0].first as EngineEvent.Down
        assertEquals("내길", down.camera.name)
        assertTrue("감속 위치 ${ev[0].second}", 3200.0 - ev[0].second in 200.0..320.0)
        assertEquals("간선도로 · 북 · 지도 제한 80", e.status.road)
        // 옆길 카메라는 기하적으로 보면 오른쪽 55m인데(도심 허용폭 밖이기도 하지만) 도로에 붙어 있어 경로로만 판단
        // 도로망 없이 60km/h로 달리면 속도 타당성도 안 걸리고 허용폭(25+3%)으로만 거른다 → 역시 0건
        val e2 = SlowdownEngine(CameraIndex(listOf(side)), EngineConfig())
        north = 0.0
        val ev2 = ArrayList<EngineEvent>()
        while (north <= 4000) { val (la, lo) = at(0.0, north); ev2 += e2.update(Fix(la, lo, 60 / 3.6, 0.0)); north += 60 / 3.6 }
        assertEquals(0, ev2.size)
    }

    @Test fun engineWithRoads_mapSpeedGapDropsMisattachedCamera() {
        // 간선(지도 제한 80)에 30 카메라가 붙었다 = 좌표가 잘못 붙은 것 → 무시
        val wrong = cam(5.0, 3200.0, 30, "잘못붙음")
        val n = net(listOf(wrong))
        val e = SlowdownEngine(CameraIndex(listOf(wrong)), EngineConfig(), roads = n)
        val v = 80 / 3.6
        var north = 0.0
        val ev = ArrayList<EngineEvent>()
        while (north <= 4000) { val (la, lo) = at(0.0, north); ev += e.update(Fix(la, lo, v, 0.0)); north += v }
        assertEquals(0, ev.size)
        // 끄면 잡는다
        val e2 = SlowdownEngine(CameraIndex(listOf(wrong)), EngineConfig(roadMaxspeedGapKmh = 0), roads = net(listOf(wrong)))
        north = 0.0
        val ev2 = ArrayList<EngineEvent>()
        while (north <= 4000) { val (la, lo) = at(0.0, north); ev2 += e2.update(Fix(la, lo, v, 0.0)); north += v }
        assertEquals(2, ev2.size)
    }

    @Test fun engineWithRoads_turningOff_restores() {
        // 이어진 길 4150m 카메라에 감속(60km/h 감속 거리 ~217m → 3933m쯤)한 뒤 4000m 교차로에서 동쪽으로 꺾음 → 경로 이탈 복귀
        val next = cam(4.0, 4150.0, 80, "이어진길")
        val n = net(listOf(next))
        val e = SlowdownEngine(CameraIndex(listOf(next)), EngineConfig(), roads = n)
        val v = 60 / 3.6
        val ev = ArrayList<EngineEvent>()
        var north = 3000.0
        while (north < 4000) { val (la, lo) = at(0.0, north); ev += e.update(Fix(la, lo, v, 0.0)); north += v }
        assertEquals(1, ev.size); assertTrue(ev[0] is EngineEvent.Down)
        var east = 0.0
        while (east < 500) { val (la, lo) = at(east, 4000.0); ev += e.update(Fix(la, lo, v, 90.0)); east += v }
        assertEquals(2, ev.size)
        assertEquals("경로 이탈", (ev[1] as EngineEvent.Up).reason)
    }

    @Test fun engineWithRoads_offRoad_fallsBackToGeometry() {
        // 도로망에 없는 길(동쪽 1500m 들판)을 달리면 "도로 미확인"으로 GPS 기하 판단 — 안 붙은 카메라는 잡는다
        val lone = cam(1508.0, 3000.0, 60, "외딴")
        val n = net(listOf(lone))
        assertFalse(n.ensure(at(1500.0, 0.0).first, at(1500.0, 0.0).second) == null)
        assertFalse(n.isAttached(lone))
        val e = SlowdownEngine(CameraIndex(listOf(lone)), EngineConfig(), roads = n)
        val v = 60 / 3.6
        val ev = ArrayList<EngineEvent>()
        var north = 0.0
        while (north <= 4000) { val (la, lo) = at(1500.0, north); ev += e.update(Fix(la, lo, v, 0.0)); north += v }
        assertEquals(2, ev.size)
        assertNotNull(e.status.road)
        assertTrue(e.status.road!!.startsWith("도로 미확인"))
    }

    @Test fun tileParse_roundTripsVarints() {
        // 음수 좌표·큰 차이도 깨지지 않는지: 파서가 읽은 노드 좌표가 샘플 배치와 일치
        val n = net(emptyList())
        val (la, lo) = at(0.0, 0.0)
        val tile = n.ensure(la, lo)!!
        val first = tile.node(0, 0)
        assertEquals(lat0, tile.lat(first), 1e-6)
        assertEquals(lon0, tile.lon(first), 1e-6)
        val last = tile.node(0, tile.nodeCount(0) - 1)
        assertEquals(4000.0, Geo.distance(tile.lat(first), tile.lon(first), tile.lat(last), tile.lon(last)), 1.0)
        // 교차 노드: (0,4000)은 way 1·2·4·5가 만난다
        val j = tile.junctionEntries(last)!!
        assertEquals(4, j.size)
    }
}
