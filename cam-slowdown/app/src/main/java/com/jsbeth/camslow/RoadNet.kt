package com.jsbeth.camslow

import java.io.File
import java.util.zip.ZipFile
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * 도로망(OpenStreetMap) 맵매칭. 안드로이드 의존성이 없어 JVM 단위 테스트로 검증한다.
 *
 * 내 위치를 도로 선형에 붙여(스냅) "지금 어느 도로, 어느 방향"을 알고, 그 도로를 따라 앞으로 이어지는
 * 길(교차로에서 50° 안으로 꺾이는 모든 갈래) 위에 붙은 카메라만 후보로 삼는다. 옆 도로·반대편 차로의
 * 카메라는 구조적으로 빠진다. 카메라는 데이터를 읽을 때 가장 가까운 도로(30 m 안)에 미리 붙여 두고,
 * 중앙선 기준 오른쪽이면 그 방향, 왼쪽이면 반대 방향(우측통행)으로 본다.
 *
 * 데이터는 tools/osm2roads.py 가 만든 kr-roads.zip(타일별 바이너리). 타일은 위도 0.2° × 경도 0.25°이고
 * 경계 3 km 밖까지 겹쳐 담겨 있어, 현재 위치가 든 타일 하나만으로 2 km 앞까지 볼 수 있다.
 */
interface RoadMap {
    /** 위치를 도로에 붙이고 앞길의 카메라를 돌려준다. 35 m 안에 방향이 맞는 도로가 없으면 null. */
    fun view(lat: Double, lon: Double, courseDeg: Double, lookaheadM: Double): RoadView?
    /** 어떤 도로에든 붙은 카메라인가(현재 타일 기준). 붙은 카메라는 경로로만 판단하고, 안 붙은 카메라는 GPS 기하로 판단한다. */
    fun isAttached(cam: Camera): Boolean
    /** 붙은 카메라가 내 진행 방향의 차량을 단속하는가. 안 붙었으면 null. */
    fun servesHeading(cam: Camera, courseDeg: Double): Boolean?
    /** 화면 지도용: 반경 안 도로 세그먼트. 도로망이 없으면 null. view() 뒤에 부른다(같은 타일·경로 기준). */
    fun snapshot(lat: Double, lon: Double, radiusM: Double): MapSegments? = null
}

/**
 * 지도 그리기용 도로 조각. xy는 기준점(내 위치)에서 본 (동 m, 북 m)가 세그먼트마다 4개씩(x1 y1 x2 y2).
 * kind: 0 작은 길, 1 큰 길, 2 내 경로(지금 붙은 도로와 앞길).
 */
class MapSegments(val xy: FloatArray, val kind: ByteArray) {
    val count: Int get() = kind.size
}

class RoadCam(val cam: Camera, val alongM: Double)

class RoadView(
    /** 지금 올라탄 도로 세그먼트의 진행 방향(도). GPS 방향보다 안정적이라 기하 판단의 기준으로 쓴다. */
    val bearingDeg: Double,
    /** OSM 제한속도(km/h). 모르면 0. */
    val maxspeedKmh: Int,
    /** 앞길 위의 카메라. alongM은 도로를 따라 간 거리. 가까운 것부터. */
    val ahead: List<RoadCam>,
    /** 비슷한 점수의 도로가 둘이라 둘 다 따라간 경우. */
    val ambiguous: Boolean,
    val distM: Double,
    /** OSM 도로 이름(없으면 ref, 그것도 없으면 ""). */
    val roadName: String = "",
) {
    /** 화면용: "동부간선도로 · 북동 · 지도 제한 80". */
    fun describe(): String {
        val dir = listOf("북", "북동", "동", "남동", "남", "남서", "서", "북서")[((bearingDeg + 22.5) / 45.0).toInt() % 8]
        return listOfNotNull(roadName.ifEmpty { "이름 없는 길" }, dir, if (maxspeedKmh > 0) "지도 제한 $maxspeedKmh" else null, if (ambiguous) "갈림 모호" else null).joinToString(" · ")
    }
}

/** 한 타일의 도로망. 노드 좌표는 1e7 단위 정수. */
class RoadTile(
    val nodeLat: IntArray,
    val nodeLon: IntArray,
    /** wayStart[w]..wayStart[w+1] 가 wayNodes 안의 노드 인덱스 범위. 크기 wayCount+1. */
    val wayStart: IntArray,
    val wayNodes: IntArray,
    /** bit0-3 등급(0 motorway … 12 tertiary_link, 13 기타), bit4 일방통행(노드 순서 방향), bit5 교량, bit6 터널. */
    val wayFlags: ByteArray,
    /** km/h, 0 모름. */
    val wayMaxspeed: ByteArray,
    /** way → names 인덱스(0 = 이름 없음). */
    val wayName: IntArray,
    val names: Array<String>,
    /** 둘 이상의 길이 만나는 노드(오름차순)와, 각 노드에 닿는 (way<<11 | 노드 위치) 목록. */
    val junctionNodes: IntArray,
    val junctionOffsets: IntArray,
    val junctionEntries: IntArray,
    val minLat: Double = -90.0, val minLon: Double = -180.0, val maxLat: Double = 90.0, val maxLon: Double = 180.0,
) {
    val wayCount: Int get() = wayStart.size - 1
    fun lat(n: Int) = nodeLat[n] / 1e7
    fun lon(n: Int) = nodeLon[n] / 1e7
    fun oneway(w: Int) = (wayFlags[w].toInt() and 0x10) != 0
    fun roadClass(w: Int) = wayFlags[w].toInt() and 0x0f
    fun maxspeed(w: Int) = wayMaxspeed[w].toInt() and 0xff
    fun name(w: Int) = names[wayName[w]]
    fun nodeCount(w: Int) = wayStart[w + 1] - wayStart[w]
    fun node(w: Int, i: Int) = wayNodes[wayStart[w] + i]

    class Attached(val cam: Camera, val way: Int, val seg: Int, val t: Double, val side: Int, val distM: Double)

    private val cell = 0.005
    private val grid = HashMap<Long, IntArray>()
    private val camsByWay = HashMap<Int, MutableList<Attached>>()
    private val attached = HashMap<Camera, MutableList<Attached>>()
    var attachedCount = 0
        private set

    init {
        val tmp = HashMap<Long, MutableList<Int>>()
        for (w in 0 until wayCount) {
            for (i in 0 until nodeCount(w) - 1) {
                val a = node(w, i); val b = node(w, i + 1)
                val la0 = cellOf(min(lat(a), lat(b))); val la1 = cellOf(max(lat(a), lat(b)))
                val lo0 = cellOf(min(lon(a), lon(b))); val lo1 = cellOf(max(lon(a), lon(b)))
                for (x in la0..la1) for (y in lo0..lo1) tmp.getOrPut(key(x, y)) { ArrayList(4) }.add(pack(w, i))
            }
        }
        for ((k, v) in tmp) grid[k] = v.toIntArray()
    }

    private fun cellOf(v: Double) = floor(v / cell).toInt()
    private fun key(a: Int, b: Int) = (a.toLong() shl 32) or (b.toLong() and 0xffffffffL)
    private fun pack(w: Int, i: Int) = (w shl 11) or i
    private fun wayOf(p: Int) = p ushr 11
    private fun segOf(p: Int) = p and 0x7ff

    /** 반경 안 셀의 세그먼트(packed). 거리는 호출자가 다시 잰다. */
    fun segmentsNear(lat: Double, lon: Double, radiusM: Double): IntArray {
        val dLat = radiusM / 111_000.0
        val dLon = radiusM / (111_000.0 * cos(Math.toRadians(lat)))
        var n = 0
        val lists = ArrayList<IntArray>()
        for (a in cellOf(lat - dLat)..cellOf(lat + dLat))
            for (b in cellOf(lon - dLon)..cellOf(lon + dLon))
                grid[key(a, b)]?.let { lists += it; n += it.size }
        val out = IntArray(n); var k = 0
        for (l in lists) { System.arraycopy(l, 0, out, k, l.size); k += l.size }
        return out
    }

    /** 세그먼트에 점을 내린 결과. */
    class Proj(val way: Int, val seg: Int, val t: Double, val distM: Double, val crossM: Double, val bearingDeg: Double, val lenM: Double)

    fun project(packed: Int, lat: Double, lon: Double): Proj {
        val w = wayOf(packed); val i = segOf(packed)
        val a = node(w, i); val b = node(w, i + 1)
        val (ax, ay) = Geo.toLocal(lat, lon, lat(a), lon(a))
        val (bx, by) = Geo.toLocal(lat, lon, lat(b), lon(b))
        val dx = bx - ax; val dy = by - ay
        val len2 = dx * dx + dy * dy
        val t = if (len2 <= 0.0) 0.0 else ((-ax) * dx + (-ay) * dy) / len2
        val tc = t.coerceIn(0.0, 1.0)
        val cx = ax + tc * dx; val cy = ay + tc * dy
        val bearing = (Math.toDegrees(atan2(dx, dy)) + 360.0) % 360.0
        // 점(원점)이 세그먼트 진행 방향의 오른쪽이면 +
        val len = sqrt(len2)
        val cross = if (len <= 0.0) 0.0 else (-(ax) * dy - (-(ay)) * dx) / len
        return Proj(w, i, tc, sqrt(cx * cx + cy * cy), cross, bearing, len)
    }

    fun segLen(w: Int, i: Int): Double {
        val a = node(w, i); val b = node(w, i + 1)
        return Geo.distance(lat(a), lon(a), lat(b), lon(b))
    }

    fun segBearing(w: Int, i: Int): Double {
        val a = node(w, i); val b = node(w, i + 1)
        return Geo.bearing(lat(a), lon(a), lat(b), lon(b))
    }

    fun junctionEntries(nodeIdx: Int): IntArray? {
        var lo = 0; var hi = junctionNodes.size - 1
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            val v = junctionNodes[mid]
            if (v == nodeIdx) return junctionEntries.copyOfRange(junctionOffsets[mid], junctionOffsets[mid + 1])
            if (v < nodeIdx) lo = mid + 1 else hi = mid - 1
        }
        return null
    }

    // ---- 카메라 부착

    /** 등급이 낮은 길은 조금 불리하게(카메라 좌표가 큰길 옆 이면도로에 더 가까울 때 큰길을 고르도록). */
    private fun classPenalty(w: Int) = when (roadClass(w)) { 6, 7 -> 6.0; 5 -> 3.0; 8, 9, 10, 11, 12 -> 2.0; else -> 0.0 }

    fun attachAll(cams: List<Camera>) {
        for (c in cams) if (c.lat in minLat..maxLat && c.lon in minLon..maxLon) attach(c)
    }

    /**
     * 카메라를 가장 가까운 도로(30 m 안)에 붙인다. 방향을 아는 카메라(도로공사)는 방향이 맞는 도로에만.
     * 중앙분리된 길(일방통행 way)에서 카메라가 왼쪽(중앙분리대 쪽)에 있고 25 m 안에 반대 방향 일방통행 way가 있으면
     * 좌표가 중앙에 찍힌 것으로 보고 양쪽에 다 붙인다(놓치는 쪽보다 불필요한 감속이 낫다).
     */
    fun attach(cam: Camera) {
        val segs = segmentsNear(cam.lat, cam.lon, ATTACH_MAX_M)
        var best: Proj? = null; var bestScore = Double.MAX_VALUE; var bestSide = 0
        for (p in segs) {
            val pr = project(p, cam.lat, cam.lon)
            if (pr.distM > ATTACH_MAX_M) continue
            var side: Int
            if (cam.bearingDeg != null) {
                side = when {
                    Geo.angleDiff(cam.bearingDeg, pr.bearingDeg) <= 45.0 -> 1
                    !oneway(pr.way) && Geo.angleDiff(cam.bearingDeg, (pr.bearingDeg + 180.0) % 360.0) <= 45.0 -> -1
                    else -> continue
                }
            } else side = if (oneway(pr.way)) 1 else if (pr.crossM > SIDE_MIN_M) 1 else if (pr.crossM < -SIDE_MIN_M) -1 else 0
            val score = pr.distM + classPenalty(pr.way)
            if (score < bestScore) { best = pr; bestScore = score; bestSide = side }
        }
        val b = best ?: return
        add(Attached(cam, b.way, b.seg, b.t, bestSide, b.distM))
        if (oneway(b.way) && b.crossM < -1.5 && cam.bearingDeg == null) {
            // 중앙분리대 쪽: 반대 방향 차로에도
            var twin: Proj? = null
            for (p in segs) {
                val pr = project(p, cam.lat, cam.lon)
                if (pr.way == b.way || pr.distM > MEDIAN_TWIN_M || !oneway(pr.way)) continue
                if (Geo.angleDiff(pr.bearingDeg, b.bearingDeg) < 150.0) continue
                if (twin == null || pr.distM < twin.distM) twin = pr
            }
            twin?.let { add(Attached(cam, it.way, it.seg, it.t, 1, it.distM)) }
        }
    }

    private fun add(a: Attached) {
        camsByWay.getOrPut(a.way) { ArrayList(2) }.add(a)
        val list = attached.getOrPut(a.cam) { ArrayList(1) }
        if (list.isEmpty()) attachedCount++
        list.add(a)
    }

    fun attachments(cam: Camera): List<Attached> = attached[cam] ?: emptyList()
    fun isAttached(cam: Camera) = attached.containsKey(cam)

    /** 붙은 카메라가 courseDeg 방향 차량을 단속하는가. 안 붙었으면 null. */
    fun servesHeading(cam: Camera, courseDeg: Double): Boolean? {
        val list = attached[cam] ?: return null
        return list.any { a ->
            val fwd = segBearing(a.way, a.seg)
            when (a.side) {
                1 -> Geo.angleDiff(courseDeg, fwd) <= SERVE_MAX_DEG
                -1 -> Geo.angleDiff(courseDeg, (fwd + 180.0) % 360.0) <= SERVE_MAX_DEG
                else -> Geo.angleDiff(courseDeg, fwd) <= SERVE_MAX_DEG || Geo.angleDiff(courseDeg, (fwd + 180.0) % 360.0) <= SERVE_MAX_DEG
            }
        }
    }

    // ---- 스냅

    class Snap(val way: Int, val seg: Int, val t: Double, val dir: Int, val distM: Double, val bearingDeg: Double, val score: Double)

    /** 위치·진행 방향에 맞는 도로. 점수 = 거리 + 0.25×각도차 − 연속성 보너스. 같은 way는 가장 좋은 세그먼트만. */
    fun snap(lat: Double, lon: Double, courseDeg: Double, prevWays: Set<Int>): List<Snap> {
        val best = HashMap<Int, Snap>()
        for (p in segmentsNear(lat, lon, SNAP_MAX_M + 5)) {
            val pr = project(p, lat, lon)
            if (pr.distM > SNAP_MAX_M) continue
            val dirs = if (oneway(pr.way)) intArrayOf(1) else intArrayOf(1, -1)
            for (d in dirs) {
                val b = if (d == 1) pr.bearingDeg else (pr.bearingDeg + 180.0) % 360.0
                val ad = Geo.angleDiff(courseDeg, b)
                if (ad > SNAP_MAX_DEG) continue
                val score = pr.distM + 0.25 * ad - (if (pr.way in prevWays) CONTINUITY_BONUS_M else 0.0)
                val cur = best[pr.way]
                if (cur == null || score < cur.score) best[pr.way] = Snap(pr.way, pr.seg, pr.t, d, pr.distM, b, score)
            }
        }
        if (best.isEmpty()) return emptyList()
        val sorted = best.values.sortedBy { it.score }
        val first = sorted[0]
        if (first.way in prevWays || sorted.size == 1) return listOf(first)
        val second = sorted[1]
        return if (second.score - first.score <= AMBIGUOUS_M) listOf(first, second) else listOf(first)
    }

    // ---- 앞길 따라가기

    private class State(val way: Int, val dir: Int, val nodeIdx: Int, val distM: Double, val inBearing: Double)

    /**
     * 스냅 지점에서 진행 방향으로 lookaheadM까지 길을 따라간다. 교차로에서는 들어온 방향과 MAX_TURN_DEG 안으로
     * 꺾이는 모든 갈래를 따라간다(직진 우선이 아니라 "크게 꺾지 않는 길 전부"). 지나는 way를 reach에, 길 위 카메라를 out에.
     */
    fun walk(s: Snap, lookaheadM: Double, out: MutableList<RoadCam>, reach: MutableSet<Int>) {
        val visited = HashSet<Int>()
        val queue = ArrayDeque<State>()
        visited.add(s.way * 2 + (if (s.dir == 1) 0 else 1))
        reach.add(s.way)
        // 시작 세그먼트의 남은 부분
        val len0 = segLen(s.way, s.seg)
        val remain = if (s.dir == 1) (1 - s.t) * len0 else s.t * len0
        camsByWay[s.way]?.forEach { a ->
            if (a.seg == s.seg && sideOk(a.side, s.dir)) {
                val ahead = if (s.dir == 1) a.t > s.t else a.t < s.t
                if (ahead) out += RoadCam(a.cam, abs(a.t - s.t) * len0)
            }
        }
        val firstNode = if (s.dir == 1) s.seg + 1 else s.seg
        queue.addLast(State(s.way, s.dir, firstNode, remain, s.bearingDeg))

        while (queue.isNotEmpty()) {
            val st = queue.removeFirst()
            var k = st.nodeIdx
            var dist = st.distM
            var inB = st.inBearing
            val n = nodeCount(st.way)
            while (dist <= lookaheadM) {
                // 교차로: 다른 way로 갈라지기
                junctionEntries(node(st.way, k))?.let { entries ->
                    for (e in entries) {
                        val w2 = wayOf(e); val i2 = segOf(e)
                        if (w2 == st.way) continue
                        val n2 = nodeCount(w2)
                        val dirs = if (oneway(w2)) intArrayOf(1) else intArrayOf(1, -1)
                        for (d2 in dirs) {
                            if (d2 == 1 && i2 >= n2 - 1) continue
                            if (d2 == -1 && i2 <= 0) continue
                            val outB = if (d2 == 1) segBearing(w2, i2) else (segBearing(w2, i2 - 1) + 180.0) % 360.0
                            if (Geo.angleDiff(inB, outB) > MAX_TURN_DEG) continue
                            if (!visited.add(w2 * 2 + (if (d2 == 1) 0 else 1))) continue
                            reach.add(w2)
                            queue.addLast(State(w2, d2, i2, dist, outB))
                        }
                    }
                }
                // 같은 way에서 다음 노드로
                val next = k + st.dir
                if (next < 0 || next >= n) break
                val seg = min(k, next)
                val len = segLen(st.way, seg)
                camsByWay[st.way]?.forEach { a ->
                    if (a.seg == seg && sideOk(a.side, st.dir)) {
                        val along = dist + (if (st.dir == 1) a.t else 1 - a.t) * len
                        if (along <= lookaheadM) out += RoadCam(a.cam, along)
                    }
                }
                inB = if (st.dir == 1) segBearing(st.way, seg) else (segBearing(st.way, seg) + 180.0) % 360.0
                dist += len
                k = next
            }
        }
    }

    private fun sideOk(side: Int, dir: Int) = side == 0 || side == dir

    companion object {
        const val ATTACH_MAX_M = 30.0
        const val SIDE_MIN_M = 2.0
        const val MEDIAN_TWIN_M = 25.0
        const val SERVE_MAX_DEG = 60.0
        const val SNAP_MAX_M = 35.0
        const val SNAP_MAX_DEG = 45.0
        const val CONTINUITY_BONUS_M = 8.0
        const val AMBIGUOUS_M = 10.0
        const val MAX_TURN_DEG = 50.0

        /** tools/osm2roads.py 가 쓴 타일 바이너리를 읽는다. */
        fun parse(bytes: ByteArray): RoadTile {
            val r = VarReader(bytes)
            require(r.u8() == 'K'.code && r.u8() == 'R'.code && r.u8() == 'R'.code && r.u8() == 'T'.code) { "타일 형식이 아닙니다" }
            val ver = r.u8()
            require(ver == 2) { "지원하지 않는 타일 버전 $ver — 앱과 도로망 파일을 같은 시기 것으로 맞추세요" }
            val minLat = r.svar() / 1e7; val minLon = r.svar() / 1e7; val maxLat = r.svar() / 1e7; val maxLon = r.svar() / 1e7
            val nodeCount = r.uvar(); val wayCount = r.uvar()
            val nodeLat = IntArray(nodeCount); val nodeLon = IntArray(nodeCount)
            var la = 0L; var lo = 0L
            for (i in 0 until nodeCount) { la += r.svar(); lo += r.svar(); nodeLat[i] = la.toInt(); nodeLon[i] = lo.toInt() }
            val nameCount = r.uvar()
            val names = Array(nameCount) { val len = r.uvar(); val str = String(bytes, r.pos, len, Charsets.UTF_8); r.pos += len; str }
            val wayStart = IntArray(wayCount + 1)
            val wayFlags = ByteArray(wayCount); val wayMaxspeed = ByteArray(wayCount); val wayName = IntArray(wayCount)
            val nodes = ArrayList<Int>()
            for (w in 0 until wayCount) {
                wayFlags[w] = r.u8().toByte(); wayMaxspeed[w] = r.u8().toByte()
                wayName[w] = r.uvar().coerceIn(0, nameCount - 1)
                val cnt = r.uvar()
                wayStart[w] = nodes.size
                var prev = 0L
                for (i in 0 until cnt) { prev += if (i == 0) r.uvar().toLong() else r.svar(); nodes += prev.toInt() }
            }
            wayStart[wayCount] = nodes.size
            val jc = r.uvar()
            val junctionNodes = IntArray(jc); val junctionOffsets = IntArray(jc + 1)
            val entries = ArrayList<Int>()
            var prevNode = 0L
            for (j in 0 until jc) {
                prevNode += r.uvar(); junctionNodes[j] = prevNode.toInt()
                junctionOffsets[j] = entries.size
                val ec = r.uvar()
                var prevE = 0L
                for (i in 0 until ec) { prevE += if (i == 0) r.uvar().toLong() else r.svar(); entries += prevE.toInt() }
            }
            junctionOffsets[jc] = entries.size
            return RoadTile(nodeLat, nodeLon, wayStart, nodes.toIntArray(), wayFlags, wayMaxspeed, wayName, names, junctionNodes, junctionOffsets, entries.toIntArray(), minLat, minLon, maxLat, maxLon)
        }
    }

    private class VarReader(val b: ByteArray) {
        var pos = 0
        fun u8(): Int = b[pos++].toInt() and 0xff
        fun uvar(): Int {
            var shift = 0; var v = 0L
            while (true) {
                val x = u8()
                v = v or ((x and 0x7f).toLong() shl shift)
                if (x and 0x80 == 0) return v.toInt()
                shift += 7
            }
        }
        /** zigzag */
        fun svar(): Long {
            var shift = 0; var v = 0L
            while (true) {
                val x = u8()
                v = v or ((x and 0x7f).toLong() shl shift)
                if (x and 0x80 == 0) break
                shift += 7
            }
            return (v ushr 1) xor -(v and 1)
        }
    }
}

/** 타일 zip 전체. 현재 위치의 타일만 메모리에 두고(최근 3개 캐시), 타일이 바뀌면 카메라를 다시 붙인다. */
class RoadNet(private val zip: ZipFile, private var cams: List<Camera> = emptyList()) : RoadMap {
    val info: String = zip.getEntry("index.txt")?.let { e -> zip.getInputStream(e).bufferedReader().readText().trim() } ?: ""
    val tileCount: Int = zip.entries().asSequence().count { it.name.startsWith("t") && it.name.endsWith(".bin") }

    private val cache = LinkedHashMap<Long, RoadTile?>()
    private var current: RoadTile? = null
    private var currentKey = Long.MIN_VALUE
    private var prevWays: Set<Int> = emptySet()
    /** 마지막으로 읽은 타일 이름과 걸린 시간(기록용). */
    var lastLoadNote: String = ""
        private set
    var onTileLoaded: ((String) -> Unit)? = null

    fun setCameras(list: List<Camera>) {
        cams = list
        cache.clear(); current = null; currentKey = Long.MIN_VALUE
    }

    private fun tileKey(lat: Double, lon: Double): Long {
        val a = floor(lat / TILE_LAT).toInt(); val b = floor(lon / TILE_LON).toInt()
        return (a.toLong() shl 32) or (b.toLong() and 0xffffffffL)
    }

    /** 위치가 든 타일을 현재 타일로. 없는 타일(바다·데이터 밖)은 null. */
    fun ensure(lat: Double, lon: Double): RoadTile? {
        val k = tileKey(lat, lon)
        if (k == currentKey) return current
        val t = if (cache.containsKey(k)) cache[k] else {
            val a = floor(lat / TILE_LAT).toInt(); val b = floor(lon / TILE_LON).toInt()
            val name = "t${a}_$b.bin"
            val t0 = System.currentTimeMillis()
            val loaded = zip.getEntry(name)?.let { e ->
                val tile = RoadTile.parse(zip.getInputStream(e).use { it.readBytes() })
                tile.attachAll(cams)
                tile
            }
            lastLoadNote = if (loaded == null) "도로망 타일 없음 ($name)"
                else "도로망 타일 $name: 길 ${loaded.wayCount}개, 카메라 ${loaded.attachedCount}개 도로에 붙임 (${System.currentTimeMillis() - t0}ms)"
            onTileLoaded?.invoke(lastLoadNote)
            cache[k] = loaded
            while (cache.size > 3) cache.remove(cache.keys.first())
            loaded
        }
        current = t; currentKey = k; prevWays = emptySet()
        return t
    }

    override fun view(lat: Double, lon: Double, courseDeg: Double, lookaheadM: Double): RoadView? {
        val tile = ensure(lat, lon) ?: return null
        val snaps = tile.snap(lat, lon, courseDeg, prevWays)
        if (snaps.isEmpty()) { prevWays = emptySet(); return null }
        val out = ArrayList<RoadCam>()
        val reach = HashSet<Int>()
        tile.walk(snaps[0], lookaheadM, out, reach)
        prevWays = reach
        if (snaps.size > 1) tile.walk(snaps[1], lookaheadM, out, HashSet())
        val byCam = HashMap<Camera, RoadCam>()
        for (rc in out) { val cur = byCam[rc.cam]; if (cur == null || rc.alongM < cur.alongM) byCam[rc.cam] = rc }
        val s = snaps[0]
        return RoadView(s.bearingDeg, tile.maxspeed(s.way), byCam.values.sortedBy { it.alongM }, snaps.size > 1, s.distM, tile.name(s.way))
    }

    override fun snapshot(lat: Double, lon: Double, radiusM: Double): MapSegments? {
        val tile = current ?: return null
        val packed = tile.segmentsNear(lat, lon, radiusM)
        val seen = HashSet<Int>(packed.size * 2)
        val xy = ArrayList<Float>(packed.size * 4)
        val kind = ArrayList<Byte>(packed.size)
        for (p in packed) {
            if (!seen.add(p)) continue
            val w = p ushr 11; val i = p and 0x7ff
            val a = tile.node(w, i); val b = tile.node(w, i + 1)
            val (ax, ay) = Geo.toLocal(lat, lon, tile.lat(a), tile.lon(a))
            val (bx, by) = Geo.toLocal(lat, lon, tile.lat(b), tile.lon(b))
            xy += ax.toFloat(); xy += ay.toFloat(); xy += bx.toFloat(); xy += by.toFloat()
            val cls = tile.roadClass(w)
            kind += when { w in prevWays -> 2; cls <= 4 || cls in 8..12 -> 1; else -> 0 }.toByte()
        }
        return MapSegments(xy.toFloatArray(), kind.toByteArray())
    }

    override fun isAttached(cam: Camera): Boolean = current?.isAttached(cam) ?: false
    override fun servesHeading(cam: Camera, courseDeg: Double): Boolean? = current?.servesHeading(cam, courseDeg)

    fun close() = runCatching { zip.close() }

    companion object {
        const val TILE_LAT = 0.2
        const val TILE_LON = 0.25

        fun open(file: File, cams: List<Camera> = emptyList()): RoadNet = RoadNet(ZipFile(file), cams)

        /** zip인지(도로망 파일인지) 빠르게 확인. */
        fun looksLikeRoads(file: File): Boolean = runCatching { ZipFile(file).use { it.getEntry("index.txt") != null } }.getOrDefault(false)
    }
}
