package com.jsbeth.camslow

import kotlin.math.sqrt

/**
 * 경로선 위 내 위치. 지나온 부분(회색)과 남은 부분(파랑)을 가르는 점을 찾는다.
 * 앞으로만 나아간다(fromSeg부터 앞쪽 WINDOW_M 안에서만 찾음) — 같은 길을 되돌아오는 경로에서 뒤 구간에 붙지 않도록.
 */
object RouteProgress {
    class Split(val seg: Int, val lat: Double, val lon: Double)

    const val WINDOW_M = 3000.0
    const val MAX_OFF_M = 60.0
    private const val TIE_M = 2.0

    /** route = 위도, 경도 번갈아. 경로에서 MAX_OFF_M 넘게 떨어져 있으면 null. */
    fun locate(route: DoubleArray, lat: Double, lon: Double, fromSeg: Int, windowM: Double = WINDOW_M): Split? {
        val n = route.size / 2
        if (n < 2) return null
        var best = Double.MAX_VALUE; var bs = -1; var bt = 0.0
        var walked = 0.0
        var i = fromSeg.coerceIn(0, n - 2)
        while (i < n - 1 && walked <= windowM) {
            val (ax, ay) = Geo.toLocal(lat, lon, route[2 * i], route[2 * i + 1])
            val (bx, by) = Geo.toLocal(lat, lon, route[2 * i + 2], route[2 * i + 3])
            val dx = bx - ax; val dy = by - ay; val l2 = dx * dx + dy * dy
            val t = if (l2 < 1e-6) 0.0 else ((-ax * dx - ay * dy) / l2).coerceIn(0.0, 1.0)
            val px = ax + t * dx; val py = ay + t * dy
            val d = sqrt(px * px + py * py)
            // 뒤 구간은 2 m 넘게 더 가까울 때만 고른다 — 겹치는 길(되돌아오는 경로)에선 앞 구간 우선
            if (d + TIE_M < best) { best = d; bs = i; bt = t }
            walked += sqrt(l2)
            i++
        }
        if (bs < 0 || best > MAX_OFF_M) return null
        return Split(bs,
            route[2 * bs] + bt * (route[2 * bs + 2] - route[2 * bs]),
            route[2 * bs + 1] + bt * (route[2 * bs + 3] - route[2 * bs + 1]))
    }
}
