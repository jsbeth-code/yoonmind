package com.jsbeth.camslow

/**
 * 테슬라 내비 목적지(중계 Worker tools/tesla-worker.js의 GET /dest 응답)와 바뀜 판단. 안드로이드 의존성 없음 — 단위 테스트로 검증.
 *
 * 응답 예: {"online":true,"destination":{"name":"잠실 롯데월드몰","lat":37.51,"lon":127.10,"minutes":18,"km":12.4}}
 *          {"online":false} · {"online":true,"destination":null} · {"error":"login required"}
 */
object TeslaDest {

    data class Dest(val name: String, val lat: Double, val lon: Double, val minutes: Double?, val km: Double?)

    sealed class Reply {
        /** 차가 자는 중(깨우지 않음) — 이번엔 건너뛴다. */
        object Offline : Reply()
        /** 차는 깨어 있고 목적지가 있거나(dest) 없다(null). */
        data class Online(val dest: Dest?) : Reply()
        data class Error(val message: String) : Reply()
    }

    fun parse(body: String): Reply {
        val root = try { MiniJson.parse(body) } catch (e: Exception) { return Reply.Error("응답을 읽지 못함") }
        if (root !is Map<*, *>) return Reply.Error("응답 형식 이상")
        (root["error"] as? String)?.let { return Reply.Error(it) }
        if (root["online"] == false) return Reply.Offline
        if (root["online"] != true) return Reply.Error("응답 형식 이상")
        val d = root["destination"] as? Map<*, *> ?: return Reply.Online(null)
        val lat = (d["lat"] as? Number)?.toDouble() ?: return Reply.Online(null)
        val lon = (d["lon"] as? Number)?.toDouble() ?: return Reply.Online(null)
        if (lat !in -90.0..90.0 || lon !in -180.0..180.0 || (lat == 0.0 && lon == 0.0)) return Reply.Online(null)
        return Reply.Online(Dest((d["name"] as? String)?.trim().orEmpty(), lat, lon, (d["minutes"] as? Number)?.toDouble(), (d["km"] as? Number)?.toDouble()))
    }

    /** 세션 키를 화면·기록에 보일 때: 앞 4자리만. */
    fun mask(session: String): String = if (session.length <= 4) "••••" else session.take(4) + "••••"

    /**
     * 목적지가 "바뀌었는지": 이름이 다르거나 좌표가 200 m 넘게 움직였으면 바뀜. 없어진 것도 바뀜.
     * (테슬라는 남은 시간·거리만 계속 바뀌므로 그것은 무시한다.)
     */
    fun changed(old: Dest?, new: Dest?): Boolean = when {
        old == null && new == null -> false
        old == null || new == null -> true
        old.name != new.name -> true
        else -> Geo.distance(old.lat, old.lon, new.lat, new.lon) > 200.0
    }

    /** 표시용 이름(이름이 비면 좌표). */
    fun label(d: Dest): String = d.name.ifBlank { "%.4f, %.4f".format(d.lat, d.lon) }
}

/** 아주 작은 JSON 파서(객체 → Map, 배열 → List, 숫자 → Double, true/false/null). */
object MiniJson {
    fun parse(s: String): Any? {
        val p = P(s); p.ws(); val v = p.value(); p.ws()
        if (p.i != s.length) throw IllegalArgumentException("trailing")
        return v
    }

    private class P(val s: String) {
        var i = 0
        fun ws() { while (i < s.length && s[i].isWhitespace()) i++ }
        fun value(): Any? {
            ws()
            if (i >= s.length) throw IllegalArgumentException("eof")
            return when (s[i]) {
                '{' -> obj()
                '[' -> arr()
                '"' -> str()
                't' -> lit("true", true)
                'f' -> lit("false", false)
                'n' -> lit("null", null)
                else -> num()
            }
        }
        fun lit(w: String, v: Any?): Any? { if (!s.startsWith(w, i)) throw IllegalArgumentException("lit"); i += w.length; return v }
        fun num(): Double {
            val st = i
            while (i < s.length && (s[i].isDigit() || s[i] in "+-.eE")) i++
            return s.substring(st, i).toDouble()
        }
        fun str(): String {
            i++ // "
            val b = StringBuilder()
            while (true) {
                if (i >= s.length) throw IllegalArgumentException("str")
                val c = s[i++]
                when (c) {
                    '"' -> return b.toString()
                    '\\' -> {
                        val e = s[i++]
                        when (e) {
                            'n' -> b.append('\n'); 't' -> b.append('\t'); 'r' -> b.append('\r'); 'b' -> b.append('\b'); 'f' -> b.append('\u000c')
                            'u' -> { b.append(s.substring(i, i + 4).toInt(16).toChar()); i += 4 }
                            else -> b.append(e)
                        }
                    }
                    else -> b.append(c)
                }
            }
        }
        fun obj(): Map<String, Any?> {
            i++
            val m = LinkedHashMap<String, Any?>()
            ws(); if (s[i] == '}') { i++; return m }
            while (true) {
                ws(); val k = str(); ws()
                if (s[i++] != ':') throw IllegalArgumentException(":")
                m[k] = value(); ws()
                when (s[i++]) { ',' -> continue; '}' -> return m; else -> throw IllegalArgumentException("obj") }
            }
        }
        fun arr(): List<Any?> {
            i++
            val l = ArrayList<Any?>()
            ws(); if (s[i] == ']') { i++; return l }
            while (true) {
                l += value(); ws()
                when (s[i++]) { ',' -> continue; ']' -> return l; else -> throw IllegalArgumentException("arr") }
            }
        }
    }
}
