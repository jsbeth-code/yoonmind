package com.jsbeth.camslow

import kotlin.math.atan2

/**
 * 경찰청 카메라의 단속 방향을 데이터의 글에서 뽑는다(안드로이드 의존성 없음).
 *
 * 경찰청 좌표로는 어느 차로·어느 방향 카메라인지 알 수 없다(2026-10 실주행: 망우로 동행 카메라가 서행 차로 위에,
 * 동부간선 북행 구간 시점이 남행 차로 위에 찍혀 있었다). 대신 설치장소 글의 62%에 "A→B"(동부제일병원교차로→신내지하차도교차로),
 * 8%에 "X방향/방면"(인천방향)이 있다. 지명을 도로망 zip의 places.tsv(OSM 지명)로 좌표로 풀면 카메라→B 방위각이 단속 방향이다.
 * 두 방향 중 하나를 고르는 데만 쓰므로 지명 좌표가 대충이어도 된다(허용 각도 90도).
 *
 * 도로노선방향 코드(1/2)는 뜻이 도로마다 다르지만 같은 기관·같은 도로 안에선 일관되므로, 글이나 구간 짝으로 방향이 확정된
 * 카메라가 하나라도 있으면 그 도로의 나머지(코드 있는 것)에 전파한다. 도로의 주축(좌표 PCA) 기준으로 코드의 극성을 배우고,
 * 전파된 방향은 주축 방향이라 굽은 길에선 틀어질 수 있어 역시 느슨하게(90도) 쓴다 — RoadNet.attach가 길과 60~120도 어긋나면 "모름"으로 되돌린다.
 */
object CamDirection {
    class Place(val name: String, val lat: Double, val lon: Double)

    /** 이름 → 그 이름의 지명들. 이름은 normalize()된 형태로 묻는다. */
    fun interface Gazetteer { fun lookup(name: String): List<Place> }

    class Hint(val from: String?, val to: String?)

    class Result(val cameras: List<Camera>, val fromText: Int, val fromCode: Int, val unresolved: Int) {
        fun describe() = "방향 확정 ${cameras.count { it.bearingDeg != null }}개(글 $fromText, 코드 전파 $fromCode), 모름 $unresolved"
    }

    const val TEXT_HEADING_DEG = 90.0
    /** 지명이 카메라에서 이 거리 안이거나 밖이면 안 믿는다(같은 자리 이름·엉뚱한 동명). */
    const val MIN_PLACE_M = 150.0
    const val MAX_PLACE_M = 60_000.0

    private val ARROW = Regex("""([^\s→\-~>()]{2,})\s*(?:→|->|-->|~>|~|⇒|＞|>)\s*([^\s→\-~>()]{2,})""")
    private val TOWARD = Regex("""([가-힣A-Za-z0-9·]{2,}?)\s*(?:방향|방면)""")
    private val STRIP_SUFFIX = Regex("""(방향|방면|쪽|앞|부근|인근|입구|진입|직전|직후)+$""")
    private val JUNCTION_SUFFIX = Regex("""(교차로|사거리|삼거리|네거리|오거리|로터리|회전교차로|IC|JC|JCT|톨게이트|TG)$""")

    /** 설치장소 글에서 "A→B" 또는 "X방향"을 찾는다. 괄호 안이어도 본다. */
    fun parseHint(place: String): Hint? {
        val t = place.trim()
        ARROW.find(t)?.let { m -> return Hint(m.groupValues[1], m.groupValues[2]) }
        TOWARD.find(t)?.let { m -> return Hint(null, m.groupValues[1]) }
        return null
    }

    /** 지명 비교용 정규화: 공백·괄호 제거, 꼬리말(방향·앞·부근) 제거. */
    fun normalize(s: String): String {
        var n = s.replace(" ", "").replace("(", "").replace(")", "").trim()
        n = STRIP_SUFFIX.replace(n, "")
        return n
    }

    /** 찾아볼 이름 후보(앞의 것부터). 초교↔초등학교, IC↔나들목, 교차로/사거리 꼬리 떼기 등. */
    fun candidates(raw: String): List<String> {
        val base = normalize(raw)
        if (base.length < 2) return emptyList()
        val out = LinkedHashSet<String>()
        out += base
        fun swapTail(from: String, to: String) { if (base.endsWith(from)) out += base.dropLast(from.length) + to }
        swapTail("초교", "초등학교"); swapTail("초등교", "초등학교"); swapTail("초", "초등학교")
        swapTail("중교", "중학교"); swapTail("고교", "고등학교"); swapTail("여고", "여자고등학교"); swapTail("여중", "여자중학교")
        swapTail("IC", "나들목"); swapTail("JC", "분기점"); swapTail("JCT", "분기점"); swapTail("TG", "요금소"); swapTail("톨게이트", "요금소")
        swapTail("면사무소", "면"); swapTail("읍사무소", "읍"); swapTail("동사무소", "동"); swapTail("주민센터", "동")
        swapTail("시청", "시"); swapTail("군청", "군"); swapTail("구청", "구")
        swapTail("사거리", "교차로"); swapTail("네거리", "교차로"); swapTail("삼거리", "교차로"); swapTail("오거리", "교차로"); swapTail("교차로", "사거리")
        // 꼬리 떼기: 삼육서울병원앞교차로 → 삼육서울병원
        JUNCTION_SUFFIX.find(base)?.let { m -> val core = normalize(base.substring(0, m.range.first)); if (core.length >= 2) out += core }
        return out.toList()
    }

    /** 카메라 근처(150 m~60 km)에서 이름이 맞는 지명 하나. 같은 이름이 여럿이고 서로 60도 넘게 다른 방향이면 모름(null). */
    fun resolve(raw: String, lat: Double, lon: Double, gaz: Gazetteer): Place? {
        for (name in candidates(raw)) {
            val hits = gaz.lookup(name).filter { Geo.distance(lat, lon, it.lat, it.lon) in MIN_PLACE_M..MAX_PLACE_M }
            if (hits.isEmpty()) continue
            val best = hits.minBy { Geo.distance(lat, lon, it.lat, it.lon) }
            val b0 = Geo.bearing(lat, lon, best.lat, best.lon)
            val near = hits.filter { Geo.distance(lat, lon, it.lat, it.lon) <= 3 * Geo.distance(lat, lon, best.lat, best.lon) }
            if (near.any { Geo.angleDiff(b0, Geo.bearing(lat, lon, it.lat, it.lon)) > 60.0 }) return null
            return best
        }
        return null
    }

    /** 글에서 방향(도). B가 풀리면 카메라→B, 아니면 A→카메라. */
    fun bearingFromText(cam: Camera, gaz: Gazetteer): Double? {
        val h = parseHint(cam.placeText) ?: return null
        h.to?.let { resolve(it, cam.lat, cam.lon, gaz) }?.let { return Geo.bearing(cam.lat, cam.lon, it.lat, it.lon) }
        h.from?.let { resolve(it, cam.lat, cam.lon, gaz) }?.let { return Geo.bearing(it.lat, it.lon, cam.lat, cam.lon) }
        return null
    }

    /** 글에 나오는 지명 후보 전부(places.tsv에서 이것만 골라 읽기 위해). */
    fun wantedNames(cams: List<Camera>): Set<String> {
        val out = HashSet<String>()
        for (c in cams) {
            if (c.bearingDeg != null) continue
            val h = parseHint(c.placeText) ?: continue
            h.to?.let { out += candidates(it) }
            h.from?.let { out += candidates(it) }
        }
        return out
    }

    /** 방향 없는 카메라에 글·코드 전파로 방향을 붙인 새 목록. */
    fun assign(cams: List<Camera>, gaz: Gazetteer): Result {
        val out = cams.toMutableList()
        var fromText = 0
        for (i in out.indices) {
            val c = out[i]
            if (c.bearingDeg != null || c.placeText.isEmpty()) continue
            val b = bearingFromText(c, gaz) ?: continue
            out[i] = c.copy(bearingDeg = b, headingTolDeg = TEXT_HEADING_DEG, bearingSource = "text")
            fromText++
        }
        val fromCode = propagate(out)
        val unresolved = out.count { it.bearingDeg == null && !it.fromKec }
        return Result(out, fromText, fromCode, unresolved)
    }

    /**
     * 같은 (기관, 도로) 안에서 코드 1/2의 극성을 배워 방향 없는 카메라에 붙인다. 극성 = 도로 주축(PCA) 기준 앞/뒤.
     * 주축과 60도 넘게 어긋난 표는 세지 않고, 표가 하나라도 반대면(3/4 미만 일치) 그 코드는 안 쓴다. 한 코드만 배우면 다른 코드는 반대.
     */
    fun propagate(out: MutableList<Camera>): Int {
        val groups = HashMap<String, MutableList<Int>>()
        for (i in out.indices) {
            val c = out[i]
            if (c.dirCode.isEmpty() || c.roadName.isBlank() || c.fromKec) continue
            groups.getOrPut(c.agency + "|" + c.roadName.replace(" ", "")) { ArrayList() } += i
        }
        var n = 0
        for (idx in groups.values) {
            if (idx.size < 2) continue
            val axis = principalAxis(idx.map { out[it] }) ?: continue
            fun sign(b: Double): Int? {
                val d = Geo.angleDiff(b, axis)
                return when { d <= 60.0 -> 1; d >= 120.0 -> -1; else -> null }
            }
            val votes = HashMap<String, IntArray>()  // code -> [plus, minus]
            for (i in idx) {
                val c = out[i]; val b = c.bearingDeg ?: continue
                val s = sign(b) ?: continue
                val v = votes.getOrPut(c.dirCode) { IntArray(2) }
                if (s > 0) v[0]++ else v[1]++
            }
            val pol = HashMap<String, Int>()
            for ((code, v) in votes) {
                val tot = v[0] + v[1]
                if (v[0] >= 0.75 * tot && v[0] > 0) pol[code] = 1 else if (v[1] >= 0.75 * tot && v[1] > 0) pol[code] = -1
            }
            if (pol.isEmpty()) continue
            if ("1" in pol && "2" !in pol) pol["2"] = -pol["1"]!!
            if ("2" in pol && "1" !in pol) pol["1"] = -pol["2"]!!
            for (i in idx) {
                val c = out[i]
                if (c.bearingDeg != null) continue
                val p = pol[c.dirCode] ?: continue
                out[i] = c.copy(bearingDeg = if (p > 0) axis else (axis + 180.0) % 360.0, headingTolDeg = TEXT_HEADING_DEG, bearingSource = "code")
                n++
            }
        }
        return n
    }

    /** 좌표들의 주축 방위각(0~180도, 북=0). 점이 한곳에 몰려 있으면 null. */
    fun principalAxis(cams: List<Camera>): Double? {
        if (cams.size < 2) return null
        val la = cams.sumOf { it.lat } / cams.size; val lo = cams.sumOf { it.lon } / cams.size
        var sxx = 0.0; var syy = 0.0; var sxy = 0.0
        for (c in cams) {
            val (x, y) = Geo.toLocal(la, lo, c.lat, c.lon)
            sxx += x * x; syy += y * y; sxy += x * y
        }
        if (sxx + syy < 100.0 * 100.0) return null
        val ang = 0.5 * atan2(2 * sxy, sxx - syy)   // x(동) 기준 라디안
        val bearing = (90.0 - Math.toDegrees(ang) + 360.0) % 360.0
        return bearing % 180.0
    }
}
