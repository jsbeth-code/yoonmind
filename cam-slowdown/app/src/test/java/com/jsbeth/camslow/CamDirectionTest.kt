package com.jsbeth.camslow

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** 설치장소 글(A→B, X방향)과 도로노선방향 코드로 단속 방향을 뽑는 CamDirection 검증. 지명은 sample-roads.zip의 places.tsv. */
class CamDirectionTest {
    private val lat0 = 37.05
    private val lon0 = 127.05
    private val mPerDegLat = 111_195.0
    private val mPerDegLon = 111_195.0 * Math.cos(Math.toRadians(lat0))
    private fun at(east: Double, north: Double) = Pair(lat0 + north / mPerDegLat, lon0 + east / mPerDegLon)

    private fun cam(east: Double, north: Double, place: String, road: String = "간선도로", code: String = "", agency: String = "경찰"): Camera {
        val (la, lo) = at(east, north)
        return Camera(la, lo, 80, CamKind.SPOT, 0, "$road $place", "1", roadName = road, dirCode = code, placeText = place, agency = agency)
    }

    private fun net(): RoadNet {
        val tmp = File.createTempFile("roads", ".zip")
        tmp.deleteOnExit()
        javaClass.getResourceAsStream("/sample-roads.zip")!!.use { it.copyTo(tmp.outputStream()) }
        return RoadNet.open(tmp)
    }

    @Test fun parseHint_arrowAndToward() {
        val h = CamDirection.parseHint("망우사거리(망우동, 동부제일병원교차로→신내지하차도교차로)")!!
        assertEquals("동부제일병원교차로", h.from); assertEquals("신내지하차도교차로", h.to)
        val a = CamDirection.parseHint("삼익세라믹아파트앞(삼호교->태화루)")!!
        assertEquals("삼호교", a.from); assertEquals("태화루", a.to)
        val t = CamDirection.parseHint("작전동 이마트 직전(인천방향)")!!
        assertNull(t.from); assertEquals("인천", t.to)
        val m = CamDirection.parseHint("현동어린이집(안동 방면)")!!
        assertEquals("안동", m.to)
        assertNull(CamDirection.parseHint("상봉지하차도교차로"))
        assertNull(CamDirection.parseHint("상계마들아파트 1,2차로"))
    }

    @Test fun candidates_expandAbbreviations() {
        assertTrue(CamDirection.candidates("산본초교").contains("산본초등학교"))
        assertTrue(CamDirection.candidates("성전면사무소").contains("성전면"))
        assertTrue(CamDirection.candidates("삼육서울병원앞교차로").contains("삼육서울병원"))
        assertTrue(CamDirection.candidates("서울IC").contains("서울나들목"))
        assertEquals("안동", CamDirection.candidates("안동 방면")[0])
        assertTrue(CamDirection.candidates("레이크타운사거리").contains("레이크타운교차로"))
    }

    @Test fun gazetteer_readsOnlyWantedNames_andResolvesBearing() {
        val n = net()
        assertTrue(n.hasPlaces)
        val g = n.gazetteer(setOf("북쪽교차로", "남쪽역", "없는이름"))
        assertEquals(1, g.lookup("북쪽교차로").size)
        assertEquals(0, g.lookup("동쪽초등학교").size)   // 안 읽음
        val c = cam(0.0, 2000.0, "간선 어딘가(남쪽역→북쪽교차로)")
        val b = CamDirection.bearingFromText(c, g)!!
        assertEquals(0.0, Geo.angleDiff(b, 0.0), 1.5)
        // B를 못 풀면 A→카메라
        val c2 = cam(0.0, 2000.0, "간선 어딘가(남쪽역→모르는곳)")
        assertEquals(0.0, Geo.angleDiff(CamDirection.bearingFromText(c2, g)!!, 0.0), 1.5)
        // 방향만: 카메라→X
        val c3 = cam(0.0, 2000.0, "간선 어딘가(남쪽역 방향)")
        assertEquals(180.0, CamDirection.bearingFromText(c3, n.gazetteer(setOf("남쪽역")))!!, 1.0)
        // 너무 가까운(150 m 안) 지명은 안 믿는다
        val c4 = cam(0.0, 3950.0, "바로 옆(북쪽교차로 방향)")
        assertNull(CamDirection.bearingFromText(c4, g))
    }

    @Test fun assign_textThenCodePropagation() {
        val n = net()
        val cams = listOf(
            cam(0.0, 1000.0, "A(남동→북동)", code = "1"),            // 글로 북행
            cam(0.0, 1500.0, "B", code = "1"),                         // 코드 1 → 북행으로 전파
            cam(0.0, 2000.0, "C", code = "2"),                         // 코드 2 → 반대(남행)
            cam(0.0, 2500.0, "D"),                                     // 코드 없음 → 모름
            cam(1000.0, 1000.0, "E", road = "지방도", code = "2"),      // 다른 도로, 앵커 없음 → 모름
        )
        val res = CamDirection.assign(cams, n.gazetteer(CamDirection.wantedNames(cams)))
        assertEquals(1, res.fromText); assertEquals(2, res.fromCode); assertEquals(2, res.unresolved)
        val out = res.cameras
        assertEquals(0.0, Geo.angleDiff(out[0].bearingDeg!!, 0.0), 1.5); assertEquals("text", out[0].bearingSource)
        assertEquals(0.0, Geo.angleDiff(out[1].bearingDeg!!, 0.0), 1.5); assertEquals("code", out[1].bearingSource)
        assertEquals(0.0, Geo.angleDiff(out[2].bearingDeg!!, 180.0), 1.5)
        assertNull(out[3].bearingDeg); assertNull(out[4].bearingDeg)
        assertEquals(CamDirection.TEXT_HEADING_DEG, out[1].headingTolDeg!!, 0.0)
        // 전파된 방향으로 도로망에 붙으면 북행 차로만 본다
        n.setCameras(out)
        val (la, lo) = at(0.0, 500.0)
        val names = n.view(la, lo, 0.0, 3000.0)!!.ahead.map { it.cam.placeText }
        assertEquals(listOf("A(남동→북동)", "B", "D"), names)
        val (la2, lo2) = at(-12.0, 3000.0)
        assertEquals(listOf("D", "C"), n.view(la2, lo2, 180.0, 3000.0)!!.ahead.map { it.cam.placeText })
    }

    @Test fun propagate_conflictingVotes_skipsCode() {
        val cams = mutableListOf(
            cam(0.0, 1000.0, "A", code = "1").copy(bearingDeg = 0.0, bearingSource = "text"),
            cam(0.0, 1500.0, "B", code = "1").copy(bearingDeg = 180.0, bearingSource = "text"),
            cam(0.0, 2000.0, "C", code = "1"),
        )
        assertEquals(0, CamDirection.propagate(cams))
        assertNull(cams[2].bearingDeg)
    }

    @Test fun principalAxis_ofNorthSouthRoad() {
        val axis = CamDirection.principalAxis(listOf(cam(0.0, 0.0, "a"), cam(10.0, 1000.0, "b"), cam(-10.0, 2000.0, "c")))!!
        assertTrue(axis < 2.0 || axis > 178.0)
        assertNotNull(CamDirection.principalAxis(listOf(cam(0.0, 0.0, "a"), cam(1000.0, 0.0, "b"))))
        assertNull(CamDirection.principalAxis(listOf(cam(0.0, 0.0, "a"), cam(5.0, 5.0, "b"))))
    }
}
