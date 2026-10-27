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
    /** 단속구분 원문. 공공데이터 코드: 1 속도, 2 신호, 3 버스전용차로, 4 불법주정차, 99 기타(구간단속 등). "01", "1+2" 같은 변형 있음. */
    val code: String = "",
) {
    /** 신호 단속으로만 표시된 카메라(단속구분 2). 실제론 대부분 신호·과속 겸용이라 기본은 포함한다. */
    val signalOnly: Boolean get() = code.trimStart('0') == "2"
}

/**
 * 공공데이터포털 「전국무인교통단속카메라표준데이터」 CSV 파서.
 * 열 순서가 바뀌어도 되도록 헤더 이름으로 찾는다. 파일은 보통 EUC-KR(CP949)이다.
 *
 * 실제 데이터(2026-10, 43,764행)에서 확인한 사실:
 *  - 단속구분은 숫자 코드다. 1=속도, 2=신호(대부분 과속 겸용), 3=버스전용차로, 4=불법주정차, 99=기타. 3·4는 제한속도가 거의 없다.
 *    → 제한속도가 적힌 행은 종류와 무관하게 과속 카메라로 본다(놓치는 쪽이 더 나쁘다). 글자로 "신호"만 적힌 행만 제외.
 *  - 단속구간위치구분도 숫자 코드다. 1=시점, 2=종점("01"/"02"도 있음). 글자(시점/종점)는 설치장소 열에만 가끔 있다.
 *  - 일부 기관(구례군 등)은 일반 카메라에도 위치구분 2를 넣는다. 그대로 종점으로 보면 실제 과속 카메라를 놓친다.
 *    → 길이가 타당하거나, "구간"이 명시돼 있거나, 같은 노선에 짝(시점↔종점)이 있을 때만 구간단속으로 인정한다.
 *  - 과속단속구간길이는 km(1~23)와 m(1,000~18,637)가 섞여 있고 45, 200, 99999 같은 쓰레기 값도 있다.
 */
object CameraCsv {

    /** 믿을 만한 구간단속 길이 범위(m). 밖이면 "모름"으로 본다. */
    const val MIN_SECTION_M = 300
    const val MAX_SECTION_M = 30_000
    /** 길이를 모르는 시점·종점이 같은 노선에서 짝을 찾는 반경(m). */
    const val PARTNER_RADIUS_M = 25_000.0

    fun decode(bytes: ByteArray): String {
        val utf8 = String(bytes, Charsets.UTF_8)
        // UTF-8로 깨지면(대체문자 다수) CP949로 다시 읽는다
        val broken = utf8.count { it == '�' }
        val text = if (broken > 5) String(bytes, cp949()) else utf8
        return text.removePrefix("﻿")
    }

    /** JVM은 MS949, 안드로이드(ICU)는 x-windows-949로 부른다. 둘 다 없으면 EUC-KR. */
    private fun cp949(): Charset = listOf("x-windows-949", "MS949", "EUC-KR")
        .firstNotNullOf { n -> runCatching { Charset.forName(n) }.getOrNull() }

    private class Row(val cam: Camera, val explicit: Boolean, val road: String)

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

        val out = ArrayList<Row>()
        for (r in rows.drop(1)) {
            fun get(i: Int) = if (i in r.indices) r[i].trim() else ""
            val lat = get(iLat).toDoubleOrNull() ?: continue
            val lon = get(iLon).toDoubleOrNull() ?: continue
            val limit = get(iLimit).toDoubleOrNull()?.toInt() ?: continue
            if (limit <= 0 || lat !in 33.0..39.0 || lon !in 124.0..132.0) continue
            val kindText = get(iKind)
            if (kindText.contains("신호") && !kindText.contains("과속")) continue
            val place = get(iPlace)
            val secCode = get(iSection).trimStart('0')
            val kind = when {
                secCode == "1" -> CamKind.SECTION_START
                secCode == "2" -> CamKind.SECTION_END
                place.contains("시점") || place.contains("구간시작") -> CamKind.SECTION_START
                place.contains("종점") || place.contains("구간끝") -> CamKind.SECTION_END
                else -> CamKind.SPOT
            }
            val len = if (kind == CamKind.SPOT) 0
                else get(iLen).replace(",", "").toDoubleOrNull()?.let { lenToMeters(it) } ?: 0
            val name = listOf(get(iRoad), place).filter { it.isNotEmpty() }.joinToString(" ")
            out += Row(Camera(lat, lon, limit, kind, len, name, kindText), place.contains("구간"), get(iRoad).replace(" ", ""))
        }
        return resolveSections(out)
    }

    /**
     * 시점·종점 표시가 믿을 만한지 가린다. 길이가 타당하거나, 설치장소에 "구간"이 명시돼 있거나,
     * 같은 노선 25 km 안에 반대쪽 짝이 있어야 구간단속으로 본다. 아니면 일반 카메라로 되돌린다.
     */
    private fun resolveSections(rows: List<Row>): List<Camera> {
        val starts = rows.filter { it.cam.kind == CamKind.SECTION_START }
        val ends = rows.filter { it.cam.kind == CamKind.SECTION_END }
        fun hasPartner(r: Row, pool: List<Row>) = pool.any { p ->
            p.road == r.road && Geo.distance(r.cam.lat, r.cam.lon, p.cam.lat, p.cam.lon) <= PARTNER_RADIUS_M
        }
        return rows.map { r ->
            when (r.cam.kind) {
                CamKind.SPOT -> r.cam
                CamKind.SECTION_START -> if (r.cam.sectionLenM > 0 || r.explicit || hasPartner(r, ends)) r.cam else r.cam.copy(kind = CamKind.SPOT, sectionLenM = 0)
                CamKind.SECTION_END -> if (r.cam.sectionLenM > 0 || r.explicit || hasPartner(r, starts)) r.cam else r.cam.copy(kind = CamKind.SPOT, sectionLenM = 0)
            }
        }
    }

    /** 구간 길이는 km 또는 m로 들어온다. 100 미만이면 km로 본다. 타당한 범위 밖이면 0(모름). */
    fun lenToMeters(v: Double): Int {
        val m = if (v < 100) (v * 1000).toInt() else v.toInt()
        return if (m in MIN_SECTION_M..MAX_SECTION_M) m else 0
    }

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
