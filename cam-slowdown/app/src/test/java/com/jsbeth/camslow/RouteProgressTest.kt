package com.jsbeth.camslow

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RouteProgressTest {
    // 북쪽으로 약 111 m 간격 점 4개(경도 고정)
    private val route = doubleArrayOf(37.000, 127.0, 37.001, 127.0, 37.002, 127.0, 37.003, 127.0)

    @Test fun splitsOnRoute() {
        val s = RouteProgress.locate(route, 37.0015, 127.0001, 0)!!
        assertEquals(1, s.seg)
        assertEquals(37.0015, s.lat, 1e-6)
        assertEquals(127.0, s.lon, 1e-9)
    }

    @Test fun farFromRouteIsNull() {
        assertNull(RouteProgress.locate(route, 37.0015, 127.002, 0))
    }

    @Test fun neverGoesBack() {
        // 같은 길을 되돌아오는 경로: 갔다가 돌아옴. 돌아오는 중(fromSeg=3)이면 앞으로 가는 구간에 붙지 않는다
        val back = doubleArrayOf(37.000, 127.0, 37.001, 127.0, 37.002, 127.0, 37.001, 127.0, 37.000, 127.0)
        assertEquals(0, RouteProgress.locate(back, 37.0005, 127.0, 0)!!.seg)
        assertEquals(3, RouteProgress.locate(back, 37.0005, 127.0, 3)!!.seg)
    }
}
