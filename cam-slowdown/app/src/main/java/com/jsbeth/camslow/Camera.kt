package com.jsbeth.camslow

import java.nio.charset.Charset
import kotlin.math.abs
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
    /** 단속구분 원문. 공공데이터 코드: 1 속도, 2 신호, 3 버스전용차로, 4 불법주정차, 99 기타(구간단속 등). "01", "1+2" 같은 변형 있음. 도로공사 데이터는 "KEC". */
    val code: String = "",
    /** 이 카메라가 단속하는 주행 방향(도, 북=0). 모르면 null(경찰청 데이터). 알면 반대 방향 주행은 무시한다. */
    val bearingDeg: Double? = null,
) {
    val fromKec: Boolean get() = code == KecCsv.CODE
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


/**
 * 한국도로공사 데이터로 방향이 있는 고속도로 구간단속 시점·종점을 만든다. 두 파일이 필요하다.
 *  - 「구간단속현황」(data.go.kr 15104214): 노선, 방향, 시점(이정 km), 시점인근시설물, 종점(이정 km), 종점인근시설물, 연장 … (제한속도 없음)
 *  - 「도로중심선_이정_좌표」(data.ex.co.kr): 노선번호, 도로명, 이정, X좌표값, Y좌표값, GRS80X, GRS80Y — 샘플상 X가 위도, Y가 경도
 * 시점→종점 이정이 커지는지로 주행 방향을 정하고, 중심선 좌표를 주행 방향 오른쪽으로 8 m 옮겨 내 차로 위에 둔다.
 */
object KecCsv {
    const val CODE = "KEC"
    /** 중심선(중앙분리대)에서 내 차로 쪽으로 옮기는 거리(m). */
    const val LANE_OFFSET_M = 8.0
    /** 방위각을 잴 때 쓰는 앞뒤 거리(km). */
    private const val BEARING_STEP_KM = 0.5

    data class Point(val km: Double, val lat: Double, val lon: Double)

    data class Section(
        val route: String, val routeNo: String, val direction: String,
        val startKm: Double, val endKm: Double, val startName: String, val endName: String,
        val lengthM: Int, val limitKmh: Int?,
    )

    /** "경부선"·"경부고속도로"·"경부 고속도로" → "경부". 두 파일의 노선 이름을 맞추기 위한 정규화. */
    fun routeKey(name: String): String = name.replace(" ", "").replace("고속도로", "").replace("고속국도", "")
        .replace("(", "").replace(")", "").removeSuffix("선").trim()

    private fun numKey(no: String): String? = no.trim().trimStart('0').takeIf { it.isNotEmpty() }?.let { "#$it" }

    private fun num(s: String): Double? = s.replace(",", "").replace(Regex("[^0-9.\\-]"), "").toDoubleOrNull()

    private fun findCol(header: List<String>, exact: List<String>, contains: List<String> = emptyList(), exclude: List<String> = emptyList()): Int {
        for (n in exact) header.indexOfFirst { it == n }.takeIf { it >= 0 }?.let { return it }
        for (n in contains) header.indexOfFirst { h -> h.contains(n) && exclude.none { h.contains(it) } }.takeIf { it >= 0 }?.let { return it }
        return -1
    }

    /** 이정 좌표 파일 → 노선 키(이름 키와 "#번호" 둘 다)별로 이정 순 정렬된 점 목록. */
    fun parsePoints(text: String): Map<String, List<Point>> {
        val rows = CameraCsv.splitCsv(text)
        if (rows.isEmpty()) return emptyMap()
        val h = rows[0].map { it.trim() }
        val iNo = findCol(h, listOf("노선번호"), listOf("노선번호", "노선코드"))
        val iName = findCol(h, listOf("도로명", "노선명", "노선"), listOf("도로명", "노선명"))
        val iKm = findCol(h, listOf("이정", "이정(km)"), listOf("이정"), listOf("좌표"))
        val iX = findCol(h, listOf("X좌표값", "X좌표", "X", "x"), listOf("X좌표"), listOf("GRS", "TM"))
        val iY = findCol(h, listOf("Y좌표값", "Y좌표", "Y", "y"), listOf("Y좌표"), listOf("GRS", "TM"))
        require(iKm >= 0 && iX >= 0 && iY >= 0 && (iNo >= 0 || iName >= 0)) {
            "이정 좌표 파일의 열(노선번호/도로명, 이정, X좌표값, Y좌표값)을 찾지 못했습니다. 헤더: ${h.take(10)}"
        }
        val byKey = HashMap<String, MutableList<Point>>()
        for (r in rows.drop(1)) {
            fun get(i: Int) = if (i in r.indices) r[i].trim() else ""
            val km = num(get(iKm)) ?: continue
            val x = num(get(iX)) ?: continue
            val y = num(get(iY)) ?: continue
            val (lat, lon) = when {
                x in 33.0..39.0 && y in 124.0..132.0 -> x to y
                y in 33.0..39.0 && x in 124.0..132.0 -> y to x
                else -> continue
            }
            val p = Point(km, lat, lon)
            val keys = ArrayList<String>(2)
            if (iName >= 0) routeKey(get(iName)).takeIf { it.isNotEmpty() }?.let { keys += it }
            if (iNo >= 0) numKey(get(iNo))?.let { keys += it }
            for (k in keys) byKey.getOrPut(k) { ArrayList() }.add(p)
        }
        return byKey.mapValues { (_, v) -> v.distinctBy { it.km }.sortedBy { it.km } }
    }

    /** 구간단속현황 파일 → 구간 목록. 시점·종점은 이정(km). */
    fun parseSections(text: String): List<Section> {
        val rows = CameraCsv.splitCsv(text)
        if (rows.isEmpty()) return emptyList()
        val h = rows[0].map { it.trim() }
        val iRoute = findCol(h, listOf("노선명", "노선", "도로명"), listOf("노선명", "도로명"), listOf("번호"))
        val iNo = findCol(h, listOf("노선번호"), listOf("노선번호"))
        val iDir = findCol(h, listOf("방향", "주행방향"), listOf("방향"))
        val iStart = findCol(h, listOf("시점", "시점이정", "시점 이정", "시점(km)", "시작이정"), listOf("시점이정", "시작이정", "시점"), listOf("시설", "명"))
        val iEnd = findCol(h, listOf("종점", "종점이정", "종점 이정", "종점(km)", "종료이정"), listOf("종점이정", "종료이정", "종점"), listOf("시설", "명"))
        val iStartName = findCol(h, listOf("시점인근시설물", "시점시설물"), listOf("시점인근", "시점시설"))
        val iEndName = findCol(h, listOf("종점인근시설물", "종점시설물"), listOf("종점인근", "종점시설"))
        val iLen = findCol(h, listOf("연장", "구간길이", "연장(km)", "단속구간길이"), listOf("연장", "길이"))
        val iLimit = findCol(h, listOf("제한속도"), listOf("제한속도"))
        require(iRoute >= 0 && iStart >= 0 && iEnd >= 0) {
            "구간단속현황 파일의 열(노선, 시점, 종점)을 찾지 못했습니다. 헤더: ${h.take(12)}"
        }
        val out = ArrayList<Section>()
        for (r in rows.drop(1)) {
            fun get(i: Int) = if (i in r.indices) r[i].trim() else ""
            val s = num(get(iStart)) ?: continue
            val e = num(get(iEnd)) ?: continue
            if (s == e) continue
            val lenRaw = if (iLen >= 0) num(get(iLen)) else null
            val lenM = lenRaw?.let { CameraCsv.lenToMeters(it) } ?: 0
            val limit = if (iLimit >= 0) num(get(iLimit))?.toInt()?.takeIf { it > 0 } else null
            out += Section(get(iRoute), if (iNo >= 0) get(iNo) else "", if (iDir >= 0) get(iDir) else "",
                s, e, get(iStartName), get(iEndName), lenM, limit)
        }
        return out
    }

    /** 이정 km 위치를 선형 보간. 범위 밖이면 null. */
    private fun posAt(pts: List<Point>, km: Double): Pair<Double, Double>? {
        if (pts.isEmpty() || km < pts.first().km - 0.05 || km > pts.last().km + 0.05) return null
        var lo = 0; var hi = pts.size - 1
        while (lo < hi) { val mid = (lo + hi) / 2; if (pts[mid].km < km) lo = mid + 1 else hi = mid }
        val b = pts[lo]
        if (lo == 0 || b.km == km) return b.lat to b.lon
        val a = pts[lo - 1]
        val t = ((km - a.km) / (b.km - a.km)).coerceIn(0.0, 1.0)
        return (a.lat + (b.lat - a.lat) * t) to (a.lon + (b.lon - a.lon) * t)
    }

    /** 시점·종점 카메라를 만든다. 노선을 못 찾은 구간은 경고 목록에 담는다. */
    fun build(sections: List<Section>, routes: Map<String, List<Point>>, defaultLimit: Int): Pair<List<Camera>, List<String>> {
        val cams = ArrayList<Camera>()
        val warn = ArrayList<String>()
        for (s in sections) {
            val pts = numKey(s.routeNo)?.let { routes[it] } ?: routes[routeKey(s.route)]
            if (pts == null) { warn += "노선 못 찾음: ${s.route} ${s.routeNo}".trim(); continue }
            val forward = s.endKm > s.startKm
            val step = if (forward) BEARING_STEP_KM else -BEARING_STEP_KM
            val start = posAt(pts, s.startKm); val startAhead = posAt(pts, s.startKm + step)
            val end = posAt(pts, s.endKm); val endBefore = posAt(pts, s.endKm - step)
            if (start == null || end == null || startAhead == null || endBefore == null) {
                warn += "이정 범위 밖: ${s.route} ${s.startKm}~${s.endKm}km (좌표 ${pts.first().km}~${pts.last().km}km)"; continue
            }
            val bStart = Geo.bearing(start.first, start.second, startAhead.first, startAhead.second)
            val bEnd = Geo.bearing(endBefore.first, endBefore.second, end.first, end.second)
            val lenM = if (s.lengthM > 0) s.lengthM else CameraCsv.lenToMeters(abs(s.endKm - s.startKm))
            val limit = s.limitKmh ?: defaultLimit
            val base = listOf(s.route, s.direction, "구간단속", "${s.startName}→${s.endName}".trim('→'), "${s.startKm}~${s.endKm}km")
                .filter { it.isNotBlank() }.joinToString(" ")
            val (sLat, sLon) = Geo.offset(start.first, start.second, bStart + 90.0, LANE_OFFSET_M)
            val (eLat, eLon) = Geo.offset(end.first, end.second, bEnd + 90.0, LANE_OFFSET_M)
            cams += Camera(sLat, sLon, limit, CamKind.SECTION_START, lenM, "$base 시점", CODE, bStart)
            cams += Camera(eLat, eLon, limit, CamKind.SECTION_END, lenM, "$base 종점", CODE, bEnd)
        }
        return cams to warn
    }

    /**
     * 경찰청 데이터와 합친다. 도로공사 시점·종점 1.5 km 안에 있는 경찰청 구간 시점·종점은 뺀다
     * (같은 구간을 방향 없이 중복 등록하는 걸 막는다). 일반 카메라는 그대로 둔다.
     */
    fun merge(police: List<Camera>, kec: List<Camera>): List<Camera> {
        if (kec.isEmpty()) return police
        val idx = CameraIndex(kec)
        return police.filter { c -> c.kind == CamKind.SPOT || idx.near(c.lat, c.lon, 1500.0).isEmpty() } + kec
    }
}
