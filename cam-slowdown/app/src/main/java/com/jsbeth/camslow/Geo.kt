package com.jsbeth.camslow

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/** 짧은 거리(수 km 이내)용 지리 계산. 등장방형 근사로 충분히 정확하다. */
object Geo {
    private const val R = 6_371_000.0

    /** 기준점(lat0, lon0)에서 본 대상점의 (동쪽 m, 북쪽 m). */
    fun toLocal(lat0: Double, lon0: Double, lat: Double, lon: Double): Pair<Double, Double> {
        val east = Math.toRadians(lon - lon0) * R * cos(Math.toRadians(lat0))
        val north = Math.toRadians(lat - lat0) * R
        return east to north
    }

    fun distance(lat0: Double, lon0: Double, lat: Double, lon: Double): Double {
        val (e, n) = toLocal(lat0, lon0, lat, lon)
        return sqrt(e * e + n * n)
    }

    /** 진행 방향(도, 북=0 시계방향) 기준으로 대상까지의 (전방 m, 우측 m). 우측이 +. */
    fun alongCross(lat0: Double, lon0: Double, courseDeg: Double, lat: Double, lon: Double): Pair<Double, Double> {
        val (e, n) = toLocal(lat0, lon0, lat, lon)
        val c = Math.toRadians(courseDeg)
        val along = e * sin(c) + n * cos(c)
        val cross = e * cos(c) - n * sin(c)
        return along to cross
    }

    fun bearing(lat0: Double, lon0: Double, lat: Double, lon: Double): Double {
        val (e, n) = toLocal(lat0, lon0, lat, lon)
        return (Math.toDegrees(atan2(e, n)) + 360.0) % 360.0
    }

    /** 두 방위각의 차이(0~180도). */
    fun angleDiff(a: Double, b: Double): Double {
        val d = abs((a - b) % 360.0)
        return if (d > 180.0) 360.0 - d else d
    }

    /** 점을 방위각 bearingDeg 방향으로 meters만큼 옮긴 (lat, lon). */
    fun offset(lat: Double, lon: Double, bearingDeg: Double, meters: Double): Pair<Double, Double> {
        val b = Math.toRadians(bearingDeg)
        val dN = meters * cos(b)
        val dE = meters * sin(b)
        val lat2 = lat + Math.toDegrees(dN / R)
        val lon2 = lon + Math.toDegrees(dE / (R * cos(Math.toRadians(lat))))
        return lat2 to lon2
    }
}
