package com.jsbeth.camslow

import java.nio.charset.Charset
import kotlin.math.cos
import kotlin.math.floor

enum class CamKind { SPOT, SECTION_START, SECTION_END }

data class Camera(
    val lat: Double,
    val lon: Double,
    val limitKmh: Int,
    val kind: CamKind,
    /** 구간단속 길이(m). 모르면 0. */
    val sectionLenM: Int,
    val name: String,
)

/**
 * 공공데이터포털 「전국무인교통단속카메라표준데이터」 CSV 파서.
 * 열 순서가 바뀌어도 되도록 헤더 이름으로 찾는다. 파일은 보통 EUC-KR(CP949)이다.
 */
object CameraCsv {

    fun decode(bytes: ByteArray): String {
        val utf8 = String(bytes, Charsets.UTF_8)
        // UTF-8로 깨지면(대체문자 다수) CP949로 다시 읽는다
        val broken = utf8.count { it == '�' }
        val text = if (broken > 5) String(bytes, Charset.forName("MS949")) else utf8
        return text.removePrefix("﻿")
    }

    fun parse(text: String): List<Camera> {
        val rows = splitCsv(text)
        if (rows.isEmpty()) return emptyList()
        val header = rows[0].map { it.trim() }
        fun col(vararg names: String): Int =
            names.firstNotNullOfOrNull { n -> header.indexOfFirst { it == n }.takeIf { it >= 0 } }
                ?: names.firstNotNullOfOrNull { n -> header.indexOfFirst { it.contains(n) }.takeIf { it >= 0 } }
                ?: -1

        val iLat = col("위도")
        val iLon = col("경도")
        val iLimit = col("제한속도")
        require(iLat >= 0 && iLon >= 0 && iLimit >= 0) {
            "필수 열(위도/경도/제한속도)을 찾지 못했습니다. 헤더: ${header.take(10)}"
        }
        val iKind = col("단속구분")
        val iSection = col("단속구간위치구분")
        val iLen = col("과속단속구간길이")
        val iPlace = col("설치장소")
        val iRoad = col("도로노선명")

        val out = ArrayList<Camera>()
        for (r in rows.drop(1)) {
            fun get(i: Int) = if (i in r.indices) r[i].trim() else ""
            val lat = get(iLat).toDoubleOrNull() ?: continue
            val lon = get(iLon).toDoubleOrNull() ?: continue
            val limit = get(iLimit).toDoubleOrNull()?.toInt() ?: continue
            if (limit <= 0 || lat !in 33.0..39.0 || lon !in 124.0..132.0) continue
            // 단속구분: 과속 단속이 아닌 순수 신호단속(값 "1" 또는 "신호")은 제외
            val kindText = get(iKind)
            if (kindText == "1" || (kindText.contains("신호") && !kindText.contains("과속"))) continue
            val sec = get(iSection)
            val kind = when {
                sec.contains("시점") || sec.contains("시작") -> CamKind.SECTION_START
                sec.contains("종점") || sec.contains("끝") -> CamKind.SECTION_END
                else -> CamKind.SPOT
            }
            val len = get(iLen).replace(",", "").toDoubleOrNull()?.let { lenToMeters(it) } ?: 0
            val name = listOf(get(iRoad), get(iPlace)).filter { it.isNotEmpty() }.joinToString(" ")
            out += Camera(lat, lon, limit, kind, len, name)
        }
        return out
    }

    /** 구간 길이는 km 또는 m로 들어온다. 100 미만이면 km로 본다. */
    private fun lenToMeters(v: Double): Int = if (v < 100) (v * 1000).toInt() else v.toInt()

    /** 따옴표를 지원하는 간단한 CSV 분리기. */
    fun splitCsv(text: String): List<List<String>> {
        val rows = ArrayList<List<String>>()
        var row = ArrayList<String>()
        val cell = StringBuilder()
        var quoted = false
        var i = 0
        while (i < text.length) {
            val ch = text[i]
            if (quoted) {
                if (ch == '"') {
                    if (i + 1 < text.length && text[i + 1] == '"') { cell.append('"'); i++ } else quoted = false
                } else cell.append(ch)
            } else when (ch) {
                '"' -> quoted = true
                ',' -> { row.add(cell.toString()); cell.clear() }
                '\r' -> {}
                '\n' -> { row.add(cell.toString()); cell.clear(); rows.add(row); row = ArrayList() }
                else -> cell.append(ch)
            }
            i++
        }
        if (cell.isNotEmpty() || row.isNotEmpty()) { row.add(cell.toString()); rows.add(row) }
        return rows.filter { r -> r.any { it.isNotBlank() } }
    }
}

/** 약 1km 격자로 나눠 근처 카메라만 빠르게 찾는다. */
class CameraIndex(val cameras: List<Camera>) {
    private val cell = 0.01
    private val grid = HashMap<Long, MutableList<Camera>>()

    init {
        for (c in cameras) grid.getOrPut(key(cellOf(c.lat), cellOf(c.lon))) { ArrayList() }.add(c)
    }

    private fun cellOf(v: Double) = floor(v / cell).toInt()
    private fun key(a: Int, b: Int) = (a.toLong() shl 32) or (b.toLong() and 0xffffffffL)

    fun near(lat: Double, lon: Double, radiusM: Double): List<Camera> {
        val dLat = radiusM / 111_000.0
        val dLon = radiusM / (111_000.0 * cos(Math.toRadians(lat)))
        val out = ArrayList<Camera>()
        for (a in cellOf(lat - dLat)..cellOf(lat + dLat))
            for (b in cellOf(lon - dLon)..cellOf(lon + dLon))
                grid[key(a, b)]?.let { list ->
                    list.filterTo(out) { Geo.distance(lat, lon, it.lat, it.lon) <= radiusM }
                }
        return out
    }
}
