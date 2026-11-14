package com.jsbeth.camslow

import android.content.Context
import android.content.SharedPreferences
import java.io.File

/** FSD 오른쪽 휠 속도 프로필 5단계. 휠 아래 = 느린 쪽. (한국 FSD 기준: 나무늘보·컴포트·스탠다드·신속주행·Mad Max) */
enum class Profile(val label: String) {
    SLOTH("나무늘보"), COMFORT("컴포트"), STANDARD("스탠다드"), HURRY("신속주행"), MAD_MAX("Mad Max");

    companion object {
        fun byName(n: String?, default: Profile) = values().firstOrNull { it.name == n } ?: default
        val labels get() = values().map { it.label }
        /** 고르기 목록용: 맨 위 Mad Max ~ 맨 아래 나무늘보(휠 방향과 같게). */
        val topDown get() = values().reversed()
    }
}

/** 감속 시작 시점 고르기. AUTO/EARLY/LATE는 고급 숫자 세 개를 정해진 값으로 바꾼다. */
enum class Timing(val label: String, val decel: Double, val reaction: Double, val margin: Double) {
    AUTO("자동 — 지금 속도와 제한속도로 계산", 0.7, 4.0, 150.0),
    EARLY("이르게 (자동보다 약 150m 먼저)", 0.5, 5.0, 250.0),
    LATE("늦게 (자동보다 약 100m 늦게)", 1.0, 3.0, 80.0),
    FIXED("카메라 N m 앞에서, 속도와 상관없이", 0.7, 4.0, 150.0);

    companion object { fun byName(n: String?) = values().firstOrNull { it.name == n } ?: AUTO }
}

/**
 * 안내 소리를 어디로 낼지.
 *  - SPEAKER: 알람 채널. 폰 스피커로 나오고 진동 모드·차 블루투스 연결과 무관하게 항상 들린다(기본).
 *  - CAR_CALL: 통화 채널. 안내 몇 초 동안 전화처럼 차가 받아 차 스피커로 튼다(음악은 잠시 멈춤). 연결에 1초쯤 걸린다. 실험용.
 *  - MEDIA: 미디어 채널. 차 미디어 소스가 "블루투스(폰)"일 때만 차에서 들린다(아니면 아무 데서도 안 남).
 */
enum class VoiceRoute(val label: String, val sub: String) {
    SPEAKER("폰 스피커 (알람 채널)", "진동 모드·차 연결과 상관없이 항상 폰에서 남. 기본"),
    CAR_CALL("차 스피커 (통화 채널, 실험)", "전화처럼 차가 받아서 틂. 1초쯤 늦고 음악이 잠시 멈춤"),
    MEDIA("미디어 채널", "차 소스가 블루투스(폰)일 때만 차에서 들림");

    companion object { fun byName(n: String?) = values().firstOrNull { it.name == n } ?: SPEAKER }
}

/** 테슬라 목적지 확인 방식. 확인 한 번에 Worker가 vehicle_data를 부른다(유료 API). */
enum class TeslaPoll(val label: String) {
    ALWAYS("감시 중 2분마다"),
    MOVING("움직일 때만 2분마다 (3분 넘게 서 있으면 쉼)"),
    MANUAL("자동 확인 안 함 — [↻ 목적지 확인]을 누를 때만");

    companion object { fun byName(n: String?) = values().firstOrNull { it.name == n } ?: MOVING }
}

/** 카카오 경로 종류(테슬라 목적지로 안내할 때). 무료 = 추천 + 유료도로 회피. */
enum class RouteOpt(val label: String, val short: String) {
    RECOMMEND("추천", "추천"), TIME("시간 우선", "시간우선"), FREE("무료도로", "무료"), DISTANCE("최단거리", "최단");

    companion object { fun byName(n: String?) = values().firstOrNull { it.name == n } ?: RECOMMEND }
}

/** 설정 저장소. 바꾸는 즉시 저장한다. 기본값은 EngineConfig와 같다. */
object Prefs {
    private fun sp(ctx: Context): SharedPreferences = ctx.getSharedPreferences("camslow", Context.MODE_PRIVATE)
    private fun SharedPreferences.f(key: String, d: Double) = getFloat(key, d.toFloat()).toDouble()
    private fun put(ctx: Context, block: SharedPreferences.Editor.() -> Unit) = sp(ctx).edit().apply(block).apply()

    // ---- 프로필
    fun usualProfile(ctx: Context) = profile(ctx, "usualProfile2", "usualProfile", Profile.HURRY)
    fun slowProfile(ctx: Context) = profile(ctx, "slowProfile2", "slowProfile", Profile.COMFORT)
    fun setUsualProfile(ctx: Context, p: Profile) = put(ctx) { putString("usualProfile2", p.name) }
    fun setSlowProfile(ctx: Context, p: Profile) = put(ctx) { putString("slowProfile2", p.name) }

    /**
     * 저장값 읽기. 4단계 시절(~v0.10.7) 맨 아래 SLOTH는 화면에 "컴포트"로 보였으므로 한 번만 COMFORT로 옮긴다.
     * 아주 예전 번호 저장값(0~4)은 5단계 순서 그대로다.
     */
    private fun profile(ctx: Context, key: String, oldKey: String, default: Profile): Profile {
        val p = sp(ctx)
        if (!p.getBoolean("profile5", false)) {
            p.edit().apply {
                for (k in listOf("usualProfile2", "slowProfile2")) if (p.getString(k, null) == Profile.SLOTH.name) putString(k, Profile.COMFORT.name)
                putBoolean("profile5", true)
            }.commit()
        }
        p.getString(key, null)?.let { return Profile.byName(it, default) }
        if (!p.contains(oldKey)) return default
        return Profile.values().getOrNull(p.getInt(oldKey, -1)) ?: default
    }

    fun extraDownClicks(ctx: Context) = sp(ctx).getInt("extraDown", 1)
    fun setExtraDownClicks(ctx: Context, v: Int) = put(ctx) { putInt("extraDown", v.coerceIn(0, 4)) }
    fun autoRestore(ctx: Context) = sp(ctx).getBoolean("autoRestore", true)
    fun setAutoRestore(ctx: Context, v: Boolean) = put(ctx) { putBoolean("autoRestore", v) }

    /** 감속 시 클릭 횟수(휠 아래). 내릴 프로필이 맨 아래(나무늘보)면 유실 대비 여분을 더한다(바닥에선 추가 클릭이 무시됨). */
    fun downPresses(ctx: Context): Int {
        val steps = usualProfile(ctx).ordinal - slowProfile(ctx).ordinal
        val extra = if (slowProfile(ctx) == Profile.values().first()) extraDownClicks(ctx) else 0
        return (steps + extra).coerceAtLeast(0)
    }
    /** 복귀 시 더블클릭 횟수(휠 위). 정확히 내린 단수만큼만(더 보내면 평소보다 빨라진다). */
    fun upPresses(ctx: Context) = (usualProfile(ctx).ordinal - slowProfile(ctx).ordinal).coerceAtLeast(0)

    // ---- 감속 시작 시점
    fun timing(ctx: Context) = Timing.byName(sp(ctx).getString("timing", null))
    fun setTiming(ctx: Context, t: Timing) = put(ctx) {
        putString("timing", t.name)
        if (t != Timing.FIXED) { putFloat("decel", t.decel.toFloat()); putFloat("reaction", t.reaction.toFloat()); putFloat("margin", t.margin.toFloat()) }
    }
    fun fixedTriggerM(ctx: Context) = sp(ctx).getInt("fixedTriggerM", 400)
    fun setFixedTriggerM(ctx: Context, v: Int) = put(ctx) { putInt("fixedTriggerM", v.coerceIn(50, 3000)) }
    fun decel(ctx: Context) = sp(ctx).f("decel", 0.7)
    fun setDecel(ctx: Context, v: Double) = put(ctx) { putFloat("decel", v.coerceIn(0.2, 3.0).toFloat()) }
    fun reaction(ctx: Context) = sp(ctx).f("reaction", 4.0)
    fun setReaction(ctx: Context, v: Double) = put(ctx) { putFloat("reaction", v.coerceIn(0.0, 15.0).toFloat()) }
    fun margin(ctx: Context) = sp(ctx).f("margin", 150.0)
    fun setMargin(ctx: Context, v: Double) = put(ctx) { putFloat("margin", v.coerceIn(0.0, 1000.0).toFloat()) }
    fun restoreAfter(ctx: Context) = sp(ctx).f("restoreAfter", 100.0)
    fun setRestoreAfter(ctx: Context, v: Double) = put(ctx) { putFloat("restoreAfter", v.coerceIn(0.0, 2000.0).toFloat()) }

    // ---- 카메라 선별
    fun minLimitKmh(ctx: Context) = sp(ctx).getInt("minLimit", 0)
    fun setMinLimitKmh(ctx: Context, v: Int) = put(ctx) { putInt("minLimit", v.coerceIn(0, 130)) }
    fun skipIfBelowLimit(ctx: Context) = sp(ctx).getBoolean("skipBelow", false)
    fun setSkipIfBelowLimit(ctx: Context, v: Boolean) = put(ctx) { putBoolean("skipBelow", v) }
    fun includeSignal(ctx: Context) = sp(ctx).getBoolean("includeSignal", true)
    fun setIncludeSignal(ctx: Context, v: Boolean) = put(ctx) { putBoolean("includeSignal", v) }
    /** 카카오가 알려 주는 이동식 과속카메라도 감속에 쓸지(기본 끔 — 단속 상자가 대부분 비어 있음). */
    fun includeMobile(ctx: Context) = sp(ctx).getBoolean("includeMobile", false)
    fun setIncludeMobile(ctx: Context, v: Boolean) = put(ctx) { putBoolean("includeMobile", v) }
    fun cityLeftTol(ctx: Context) = sp(ctx).f("cityLeft", 8.0)
    fun setCityLeftTol(ctx: Context, v: Double) = put(ctx) { putFloat("cityLeft", v.coerceIn(3.0, 60.0).toFloat()) }
    fun cityRightTol(ctx: Context) = sp(ctx).f("cityRight", 25.0)
    fun setCityRightTol(ctx: Context, v: Double) = put(ctx) { putFloat("cityRight", v.coerceIn(5.0, 150.0).toFloat()) }
    fun hwyLeftTol(ctx: Context) = sp(ctx).f("hwyLeft", 15.0)
    fun setHwyLeftTol(ctx: Context, v: Double) = put(ctx) { putFloat("hwyLeft", v.coerceIn(3.0, 60.0).toFloat()) }
    fun hwyRightTol(ctx: Context) = sp(ctx).f("hwyRight", 60.0)
    fun setHwyRightTol(ctx: Context, v: Double) = put(ctx) { putFloat("hwyRight", v.coerceIn(5.0, 150.0).toFloat()) }
    /** 도로공사 구간에 경찰청 제한속도를 못 찾았을 때 쓰는 값. */
    fun kecLimitKmh(ctx: Context) = sp(ctx).getInt("kecLimit", 100)
    fun setKecLimitKmh(ctx: Context, v: Int) = put(ctx) { putInt("kecLimit", v.coerceIn(30, 130)) }
    /** 도로망(OSM) 맵매칭으로 옆 도로·반대편 카메라 거르기. 도로망 파일이 없으면 자연히 안 쓴다. */
    fun useRoads(ctx: Context) = sp(ctx).getBoolean("useRoads", true)
    fun setUseRoads(ctx: Context, v: Boolean) = put(ctx) { putBoolean("useRoads", v) }
    /** 속도 타당성: 제한속도의 이 배수 이상으로 달리면 다른 도로의 카메라로 봄. 0이면 끔. */
    fun fastRatio(ctx: Context) = sp(ctx).f("fastRatio", 1.5)
    fun setFastRatio(ctx: Context, v: Double) = put(ctx) { putFloat("fastRatio", (if (v <= 0) 0.0 else v.coerceIn(1.1, 3.0)).toFloat()) }
    /** 제한 40 이하(좁은 길) 카메라의 오른쪽 허용폭. */
    fun narrowRightTol(ctx: Context) = sp(ctx).f("narrowRight", 15.0)
    fun setNarrowRightTol(ctx: Context, v: Double) = put(ctx) { putFloat("narrowRight", v.coerceIn(5.0, 150.0).toFloat()) }
    /** 길이도 짝 종점도 모르는 구간단속의 최대 유지 거리(m). */
    fun maxSectionHoldM(ctx: Context) = sp(ctx).getInt("maxHold", 10_000)
    fun setMaxSectionHoldM(ctx: Context, v: Int) = put(ctx) { putInt("maxHold", v.coerceIn(1000, 30_000)) }

    // ---- 어린이보호구역
    fun schoolZoneOn(ctx: Context) = sp(ctx).getBoolean("zoneOn", false)
    fun setSchoolZoneOn(ctx: Context, v: Boolean) = put(ctx) { putBoolean("zoneOn", v) }
    fun schoolZoneRadiusM(ctx: Context) = sp(ctx).getInt("zoneRadius", 300)
    fun setSchoolZoneRadiusM(ctx: Context, v: Int) = put(ctx) { putInt("zoneRadius", v.coerceIn(50, 1000)) }

    // ---- GPS·추측 항법
    fun deadReckoning(ctx: Context) = sp(ctx).getBoolean("deadReckon", true)
    fun setDeadReckoning(ctx: Context, v: Boolean) = put(ctx) { putBoolean("deadReckon", v) }
    fun deadReckonMaxM(ctx: Context) = sp(ctx).getInt("deadReckonMax", 2000)
    fun setDeadReckonMaxM(ctx: Context, v: Int) = put(ctx) { putInt("deadReckonMax", v.coerceIn(200, 10_000)) }
    fun maxAccM(ctx: Context) = sp(ctx).f("maxAcc", 40.0)
    fun setMaxAccM(ctx: Context, v: Double) = put(ctx) { putFloat("maxAcc", v.coerceIn(10.0, 200.0).toFloat()) }

    // ---- 화면
    /** 화면 테마: AUTO = 폰 다크 모드 따라감, DAY = 항상 낮(파스텔), NIGHT = 항상 밤(네온). */
    fun theme(ctx: Context) = sp(ctx).getString("theme", "AUTO") ?: "AUTO"
    fun setTheme(ctx: Context, v: String) = put(ctx) { putString("theme", v) }
    fun mapOn(ctx: Context) = sp(ctx).getBoolean("mapOn", true)
    fun setMapOn(ctx: Context, v: Boolean) = put(ctx) { putBoolean("mapOn", v) }
    /** 다른 앱 위에 알약 창. "다른 앱 위에 표시" 권한이 있어야 뜬다. */
    // 9 테슬라 내비 목적지 따라가기 (v0.10). 세션 키는 무작위 문자열이고 테슬라 토큰은 Worker에만 있다. 화면·기록엔 앞 4자리만.
    fun teslaFollow(ctx: Context) = sp(ctx).getBoolean("teslaFollow", false)
    /** 테슬라 목적지 자동 확인 방식(기본: 움직일 때만). */
    fun teslaPoll(ctx: Context) = TeslaPoll.byName(sp(ctx).getString("teslaPoll", null))
    fun setTeslaPoll(ctx: Context, v: TeslaPoll) = put(ctx) { putString("teslaPoll", v.name) }
    /**
     * v0.13.7 메인 화면 알약 위치. mode: "big"(폴더 펼침) / "small"(접음·일반 폰).
     * 값은 화면 안 남는 공간에 대한 비율(0~1, 왼쪽·위 = 0). last = 마지막에 옮긴 자리, fixed = 시작 위치로 고정한 자리.
     */
    private fun pillGet(ctx: Context, key: String): Pair<Float, Float>? {
        val x = sp(ctx).getFloat("${key}X", -1f); val y = sp(ctx).getFloat("${key}Y", -1f)
        return if (x < 0f || y < 0f) null else x to y
    }
    fun pillLast(ctx: Context, mode: String) = pillGet(ctx, "pillLast_$mode")
    fun setPillLast(ctx: Context, mode: String, x: Float, y: Float) = put(ctx) { putFloat("pillLast_${mode}X", x); putFloat("pillLast_${mode}Y", y) }
    fun clearPillLast(ctx: Context) = put(ctx) { for (m in listOf("big", "small")) { remove("pillLast_${m}X"); remove("pillLast_${m}Y"); remove("pillFixed_${m}X"); remove("pillFixed_${m}Y") } }
    fun pillFixed(ctx: Context, mode: String) = pillGet(ctx, "pillFixed_$mode")
    fun setPillFixed(ctx: Context, mode: String, x: Float, y: Float) = put(ctx) { putFloat("pillFixed_${mode}X", x); putFloat("pillFixed_${mode}Y", y) }
    fun clearPillFixed(ctx: Context, mode: String) = put(ctx) { remove("pillFixed_${mode}X"); remove("pillFixed_${mode}Y") }
    /** v0.13.9 카메라 알림 카드 자리(옮기면 바로 고정). mode 같은 뜻, 값은 지도 안 남는 공간 비율. */
    fun camCardPos(ctx: Context, mode: String) = pillGet(ctx, "camCard_$mode")
    fun setCamCardPos(ctx: Context, mode: String, x: Float, y: Float) = put(ctx) { putFloat("camCard_${mode}X", x); putFloat("camCard_${mode}Y", y) }
    fun clearCamCardPos(ctx: Context) = put(ctx) { for (m in listOf("big", "small")) { remove("camCard_${m}X"); remove("camCard_${m}Y") } }

    /** 카카오내비 화면 마지막 실패(앱을 다시 켜도 점검에 보이게). 성공하면 지움. */
    fun naviErr(ctx: Context) = sp(ctx).getString("naviErr", "") ?: ""
    fun setNaviErr(ctx: Context, v: String) = put(ctx) { putString("naviErr", v) }
    /** 커맨더 미연결 음성 경고 반복 간격(분). 0 = 한 번만. */
    fun cmdWarnRepeatMin(ctx: Context) = sp(ctx).getInt("cmdWarnRepeatMin", 0)
    fun setCmdWarnRepeatMin(ctx: Context, v: Int) = put(ctx) { putInt("cmdWarnRepeatMin", v) }
    /** 목적지가 들어올 때 기본 경로 종류(작은 창에서 10초 안에 바꿀 수 있음). */
    fun routeOpt(ctx: Context) = RouteOpt.byName(sp(ctx).getString("routeOpt", null))
    fun setRouteOpt(ctx: Context, v: RouteOpt) = put(ctx) { putString("routeOpt", v.name) }

    /** v0.13.3: 마지막으로 경로 고르기를 띄운 목적지와 고른 경로 종류(감시를 껐다 켜도 3시간 동안 같은 목적지면 다시 묻지 않음). */
    fun askedDest(ctx: Context): Pair<TeslaDest.Dest, RouteOpt>? {
        val p = sp(ctx)
        val at = p.getLong("askedAt", 0L)
        if (at == 0L || System.currentTimeMillis() - at > 3 * 3600_000L) return null
        val d = TeslaDest.Dest(p.getString("askedName", "") ?: "", p.getFloat("askedLat", 0f).toDouble(), p.getFloat("askedLon", 0f).toDouble(), null, null)
        return d to RouteOpt.byName(p.getString("askedOpt", null))
    }
    fun setAskedDest(ctx: Context, d: TeslaDest.Dest, opt: RouteOpt) = put(ctx) {
        putLong("askedAt", System.currentTimeMillis()); putString("askedName", d.name)
        putFloat("askedLat", d.lat.toFloat()); putFloat("askedLon", d.lon.toFloat()); putString("askedOpt", opt.name)
    }
    fun setAskedOpt(ctx: Context, opt: RouteOpt) = put(ctx) { putString("askedOpt", opt.name) }
    fun setTeslaFollow(ctx: Context, v: Boolean) = put(ctx) { putBoolean("teslaFollow", v) }
    fun teslaSession(ctx: Context) = sp(ctx).getString("teslaSession", "") ?: ""
    fun setTeslaSession(ctx: Context, v: String) = put(ctx) { putString("teslaSession", v.trim()) }
    /** 마지막 확인 시각(System.currentTimeMillis)과 결과 글(목적지 이름 등). */
    fun teslaLastCheck(ctx: Context) = sp(ctx).getLong("teslaLastCheck", 0L)
    fun teslaLastNote(ctx: Context) = sp(ctx).getString("teslaLastNote", "") ?: ""
    fun setTeslaLast(ctx: Context, note: String) = put(ctx) { putLong("teslaLastCheck", System.currentTimeMillis()); putString("teslaLastNote", note) }

    fun overlayOn(ctx: Context) = sp(ctx).getBoolean("overlayOn", true)
    fun setOverlayOn(ctx: Context, v: Boolean) = put(ctx) { putBoolean("overlayOn", v) }
    fun overlayX(ctx: Context) = sp(ctx).getInt("overlayX", 24)
    fun overlayY(ctx: Context) = sp(ctx).getInt("overlayY", 160)
    fun setOverlayPos(ctx: Context, x: Int, y: Int) = put(ctx) { putInt("overlayX", x); putInt("overlayY", y) }
    /** 알약을 옆으로 밀어 가장자리 탭으로 접은 상태: "" 알약, "L" 왼쪽, "R" 오른쪽. */
    fun overlayDock(ctx: Context) = sp(ctx).getString("overlayDock", "") ?: ""
    fun setOverlayDock(ctx: Context, v: String) = put(ctx) { putString("overlayDock", v) }

    // ---- 출력·연결
    /** 카메라 500 m 앞 "500미터 앞, 제한속도 50 과속카메라입니다". */
    fun approachVoice(ctx: Context) = sp(ctx).getBoolean("approachVoice", true)
    fun setApproachVoice(ctx: Context, v: Boolean) = put(ctx) { putBoolean("approachVoice", v) }
    /** 300 m·100 m 거리 안내를 늘(꺼져 있으면 과속일 때만). */
    fun distanceVoiceAlways(ctx: Context) = sp(ctx).getBoolean("distanceVoiceAlways", false)
    fun setDistanceVoiceAlways(ctx: Context, v: Boolean) = put(ctx) { putBoolean("distanceVoiceAlways", v) }
    fun overspeedBeep(ctx: Context) = sp(ctx).getBoolean("overspeedBeep", true)
    fun setOverspeedBeep(ctx: Context, v: Boolean) = put(ctx) { putBoolean("overspeedBeep", v) }
    /** 제한속도보다 이만큼(km/h) 넘으면 과속 경고. */
    fun overspeedMargin(ctx: Context) = sp(ctx).getInt("overspeedMargin", 7)
    fun setOverspeedMargin(ctx: Context, v: Int) = put(ctx) { putInt("overspeedMargin", v.coerceIn(0, 40)) }
    fun alertsOn(ctx: Context) = sp(ctx).getBoolean("alerts", true)
    fun setAlertsOn(ctx: Context, v: Boolean) = put(ctx) { putBoolean("alerts", v) }
    fun voiceRoute(ctx: Context) = VoiceRoute.byName(sp(ctx).getString("voiceRoute", null))
    fun setVoiceRoute(ctx: Context, v: VoiceRoute) = put(ctx) { putString("voiceRoute", v.name) }
    fun keepScreenOn(ctx: Context) = sp(ctx).getBoolean("keepScreen", true)
    fun setKeepScreenOn(ctx: Context, v: Boolean) = put(ctx) { putBoolean("keepScreen", v) }
    fun bleOn(ctx: Context) = sp(ctx).getBoolean("ble", false)
    fun setBleOn(ctx: Context, v: Boolean) = put(ctx) { putBoolean("ble", v) }
    /**
     * 누름 사이 간격. 기본 1000 ms(v0.10.6): 400 ms에선 FSD가 연속 클릭 중 일부를 놓쳐(Mad Max→스탠다드가 신속주행에서 멈춤)
     * 프로필이 덜 바뀌었다. 예전 기본값 400으로 저장돼 있으면 한 번만 새 기본값으로 옮긴다(그 뒤 일부러 넣은 400은 그대로).
     */
    fun pressGapMs(ctx: Context): Long {
        val p = sp(ctx)
        if (!p.getBoolean("pressGap1000", false)) {
            p.edit().apply { if (p.getInt("pressGap", PRESS_GAP_DEFAULT) == 400) putInt("pressGap", PRESS_GAP_DEFAULT); putBoolean("pressGap1000", true) }.commit()
        }
        return p.getInt("pressGap", PRESS_GAP_DEFAULT).toLong()
    }
    const val PRESS_GAP_DEFAULT = 1000
    fun setPressGapMs(ctx: Context, v: Int) = put(ctx) { putInt("pressGap", v.coerceIn(100, 3000)) }
    /** 감시를 꺼도 커맨더 버튼(BLE 광고·GATT)을 계속 켜 둔다 — 다시 켜면 폰 블루투스 주소가 바뀌어 커맨더가 못 찾는 문제 때문(v0.12.7). */
    fun keepButton(ctx: Context) = sp(ctx).getBoolean("keepButton", true)
    fun setKeepButton(ctx: Context, v: Boolean) = put(ctx) { putBoolean("keepButton", v) }
    /** v0.15.0 커맨더 연결: false = 폰 직접(폰이 ENH_BTN), true = 보드 경유(ESP32 보드가 ENH_BTN, 폰은 보드에 신호만). */
    fun viaBoard(ctx: Context) = sp(ctx).getBoolean("viaBoard", false)
    fun setViaBoard(ctx: Context, v: Boolean) = put(ctx) { putBoolean("viaBoard", v) }
    /** 보드 주소와 보드가 준 토큰(16바이트 hex). 토큰은 화면·기록에 내지 않는다. */
    fun boardAddr(ctx: Context) = sp(ctx).getString("boardAddr", "") ?: ""
    fun boardToken(ctx: Context) = sp(ctx).getString("boardToken", "") ?: ""
    fun setBoard(ctx: Context, addr: String, token: String) = put(ctx) { putString("boardAddr", addr); putString("boardToken", token) }
    /** v0.16.0 보드 프로그램 버전(보드가 04에 답한 것)과 릴리스 최신 버전(커밋 앞 7자). 빈 글 = 모름. */
    fun boardFwVer(ctx: Context) = sp(ctx).getString("boardFwVer", "") ?: ""
    fun setBoardFwVer(ctx: Context, v: String) = put(ctx) { putString("boardFwVer", v) }
    fun boardFwLatest(ctx: Context) = sp(ctx).getString("boardFwLatest", "") ?: ""
    fun setBoardFwLatest(ctx: Context, v: String) = put(ctx) { putString("boardFwLatest", v) }
    /** v0.16.0 보드가 켜지면(차가 깨면) 자동 시작(보드 경유일 때만). */
    fun boardAutoStart(ctx: Context) = sp(ctx).getBoolean("boardAutoStart", true)
    fun setBoardAutoStart(ctx: Context, v: Boolean) = put(ctx) { putBoolean("boardAutoStart", v) }
    /** 보드 자동 시작 무장: true면 다음에 보드가 보이면 바로 시작. 감시를 직접 끄면 false — 보드가 한동안 안 보였다가 다시 보여야 시작. */
    fun boardArmed(ctx: Context) = sp(ctx).getBoolean("boardArmed", true)
    fun setBoardArmed(ctx: Context, v: Boolean) = put(ctx) { putBoolean("boardArmed", v) }
    /** 마지막으로 보드를 본(연결·검색) 시각(벽시계 ms). */
    fun boardSeenAt(ctx: Context) = sp(ctx).getLong("boardSeenAt", 0L)
    fun setBoardSeenAt(ctx: Context, v: Long) = put(ctx) { putLong("boardSeenAt", v) }
    fun renameAdapter(ctx: Context) = sp(ctx).getBoolean("renameAdapter", true)
    fun setRenameAdapter(ctx: Context, v: Boolean) = put(ctx) { putBoolean("renameAdapter", v) }

    // ---- v0.16.0 소리 크기(알람 등 소리 채널 음량 안에서의 비율, 0~100, 0 = 끔)
    fun kakaoVolume(ctx: Context) = sp(ctx).getInt("kakaoVolume", 100).coerceIn(0, 100)
    fun setKakaoVolume(ctx: Context, v: Int) = put(ctx) { putInt("kakaoVolume", v.coerceIn(0, 100)) }
    fun appVolume(ctx: Context) = sp(ctx).getInt("appVolume", 100).coerceIn(0, 100)
    fun setAppVolume(ctx: Context, v: Int) = put(ctx) { putInt("appVolume", v.coerceIn(0, 100)) }

    // ---- 자동 시작
    fun autoStart(ctx: Context) = sp(ctx).getBoolean("autoStart", false)
    fun setAutoStart(ctx: Context, v: Boolean) = put(ctx) { putBoolean("autoStart", v) }
    fun autoStop(ctx: Context) = sp(ctx).getBoolean("autoStop", true)
    fun setAutoStop(ctx: Context, v: Boolean) = put(ctx) { putBoolean("autoStop", v) }
    fun autoDeviceAddress(ctx: Context) = sp(ctx).getString("autoDevice", "") ?: ""
    fun autoDeviceName(ctx: Context) = sp(ctx).getString("autoDeviceName", "") ?: ""
    fun setAutoDevice(ctx: Context, address: String, name: String) = put(ctx) { putString("autoDevice", address); putString("autoDeviceName", name) }

    /** 고급 숫자들을 기본값으로. */
    fun resetAdvanced(ctx: Context) {
        val keepFixed = timing(ctx) == Timing.FIXED
        put(ctx) {
            for (k in listOf("decel", "reaction", "margin", "restoreAfter", "cityLeft", "cityRight", "hwyLeft", "hwyRight", "maxAcc", "narrowRight")) remove(k)
            for (k in listOf("kecLimit", "extraDown", "pressGap", "deadReckonMax", "zoneRadius", "fastRatio", "maxHold")) remove(k)
            remove("renameAdapter"); remove("deadReckon"); remove("useRoads")
            if (!keepFixed) putString("timing", Timing.AUTO.name)
        }
    }

    fun engine(ctx: Context): EngineConfig {
        val d = EngineConfig()
        val timing = timing(ctx)
        return d.copy(
            decelMps2 = decel(ctx), reactionS = reaction(ctx), marginM = margin(ctx),
            fixedTriggerM = if (timing == Timing.FIXED) fixedTriggerM(ctx) else 0,
            restoreAfterM = restoreAfter(ctx),
            minLimitKmh = minLimitKmh(ctx), skipIfBelowLimit = skipIfBelowLimit(ctx), includeSignalCams = includeSignal(ctx),
            cityLeftTolM = cityLeftTol(ctx), cityRightTolM = cityRightTol(ctx),
            hwyLeftTolM = hwyLeftTol(ctx), hwyRightTolM = hwyRightTol(ctx),
            narrowRightTolM = narrowRightTol(ctx), maxSectionHoldM = maxSectionHoldM(ctx).toDouble(),
            maxAccM = maxAccM(ctx),
            schoolZoneOn = schoolZoneOn(ctx), schoolZoneRadiusM = schoolZoneRadiusM(ctx).toDouble(),
            useRoads = useRoads(ctx), fastRatio = fastRatio(ctx),
        )
    }
}

/** 불러온 CSV들을 앱 내부에 UTF-8로 보관한다. 경찰청 전국 + (선택) 도로공사 구간단속·이정좌표 + (선택) 어린이보호구역. */
object CameraRepo {
    private fun file(ctx: Context) = File(ctx.filesDir, "cameras.csv")
    private fun kecSectionsFile(ctx: Context) = File(ctx.filesDir, "kec_sections.csv")
    private fun kecPointsFile(ctx: Context) = File(ctx.filesDir, "kec_points.csv")
    private fun zonesFile(ctx: Context) = File(ctx.filesDir, "zones.csv")
    private fun roadsFile(ctx: Context) = File(ctx.filesDir, "roads.zip")

    @Volatile private var cached: CameraIndex? = null
    @Volatile private var cachedZones: CameraIndex? = null
    /** 마지막 로드 때 도로공사 데이터 처리 결과(경고 포함). */
    @Volatile var kecNote: String = ""
        private set
    /** 화면 점검표용 요약. 로드 뒤 채워진다. */
    @Volatile var summaryLine: String = ""
        private set

    fun import(ctx: Context, bytes: ByteArray): Int {
        val text = CameraCsv.decode(bytes)
        val list = CameraCsv.parse(text)
        require(list.isNotEmpty()) { "카메라를 하나도 읽지 못했습니다" }
        file(ctx).writeText(text)
        invalidate()
        return list.size
    }

    /** 도로공사 「구간단속현황」 CSV. 형식만 검사하고 저장한다. 이정 좌표가 있어야 카메라가 만들어진다. */
    fun importKecSections(ctx: Context, bytes: ByteArray): String {
        val text = CameraCsv.decode(bytes)
        val n = KecCsv.parseSections(text).size
        require(n > 0) { "구간을 하나도 읽지 못했습니다" }
        kecSectionsFile(ctx).writeText(text)
        invalidate()
        return "구간단속 ${n}건 저장" + if (kecPointsFile(ctx).exists()) "" else " — 이정 좌표 CSV도 불러와야 적용됩니다"
    }

    /** 도로공사 「도로중심선_이정_좌표」 CSV. */
    fun importKecPoints(ctx: Context, bytes: ByteArray): String {
        val text = CameraCsv.decode(bytes)
        val routes = KecCsv.parsePoints(text)
        val n = routes.values.sumOf { it.size }
        require(n > 0) { "이정 좌표를 하나도 읽지 못했습니다" }
        kecPointsFile(ctx).writeText(text)
        invalidate()
        return "이정 좌표 ${n}개(노선 ${routes.keys.count { !it.startsWith("#") }}개) 저장" +
            if (kecSectionsFile(ctx).exists()) "" else " — 구간단속현황 CSV도 불러와야 적용됩니다"
    }

    /** 「전국어린이보호구역표준데이터」 CSV. */
    fun importZones(ctx: Context, bytes: ByteArray): String {
        val text = CameraCsv.decode(bytes)
        val list = ZoneCsv.parse(text)
        require(list.isNotEmpty()) { "보호구역을 하나도 읽지 못했습니다" }
        zonesFile(ctx).writeText(text)
        invalidate()
        return "어린이보호구역 ${list.size}곳 저장" + if (Prefs.schoolZoneOn(ctx)) "" else " — 설정에서 '어린이보호구역 감속'을 켜야 동작합니다"
    }

    fun hasKec(ctx: Context) = kecSectionsFile(ctx).exists() && kecPointsFile(ctx).exists()
    fun hasZones(ctx: Context) = zonesFile(ctx).exists()
    fun hasRoads(ctx: Context) = roadsFile(ctx).exists()

    /**
     * 도로망 kr-roads.zip(tools/osm2roads.py 산출물). 수십 MB라 스트림으로 복사한다. 형식만 확인하고 저장.
     * 돌려주는 글은 index.txt 요약.
     */
    fun importRoads(ctx: Context, input: java.io.InputStream): String {
        val tmp = File(ctx.filesDir, "roads.zip.tmp")
        tmp.outputStream().use { out -> input.copyTo(out) }
        if (!RoadNet.looksLikeRoads(tmp)) { tmp.delete(); throw IllegalArgumentException("도로망 파일(kr-roads.zip)이 아닙니다 — 릴리스 페이지에서 받은 zip을 그대로 넣으세요") }
        val net = RoadNet.open(tmp)
        val info = net.info; val tiles = net.tileCount
        net.close()
        require(tiles > 0) { "타일이 하나도 없습니다" }
        tmp.renameTo(roadsFile(ctx))
        roadsInfo = roadsInfoOf(info)
        return "도로망 저장: 타일 ${tiles}개, $roadsInfo (${"%.1f".format(roadsFile(ctx).length() / 1e6)} MB)"
    }

    private fun roadsInfoOf(index: String): String {
        val kv = index.lines().mapNotNull { l -> l.split("=", limit = 2).takeIf { it.size == 2 }?.let { it[0].trim() to it[1].trim() } }.toMap()
        return listOfNotNull(kv["date"]?.let { "OSM $it" }, kv["ways"]?.let { "길 ${"%,d".format(it.toLongOrNull() ?: 0)}개" }, kv["classes"]?.let { if (it == "major") "주요 도로만" else null }).joinToString(" · ")
    }

    /** 점검표용 도로망 요약. 파일이 없으면 "". */
    @Volatile var roadsInfo: String = ""
        private set

    /**
     * 도로망을 연다(zip은 열어 두고 타일은 필요할 때 읽는다). 없거나 깨졌으면 null.
     * 열면서 zip의 OSM 지명(places.tsv)으로 카메라 설치장소 글의 "A→B"·"X방향"을 풀어 단속 방향을 붙인다(CamDirection).
     * 방향이 붙은 카메라 목록은 net.cameras — 엔진도 이 목록을 써야 GPS 기하 판단에도 방향이 적용된다.
     */
    fun openRoads(ctx: Context, cams: List<Camera>): RoadNet? {
        val f = roadsFile(ctx)
        if (!f.exists()) { roadsInfo = ""; directionNote = ""; return null }
        return try {
            val net = RoadNet.open(f)
            roadsInfo = roadsInfoOf(net.info)
            val t0 = System.currentTimeMillis()
            // 같은 카메라 목록·같은 도로망이면 다시 풀지 않는다(지명 사전 읽기 수 초)
            val key = Triple(System.identityHashCode(cams), f.length(), f.lastModified())
            val res = resolvedCache?.takeIf { it.first == key }?.second
                ?: CamDirection.assign(cams, net.gazetteer(CamDirection.wantedNames(cams))).also { resolvedCache = key to it }
            net.setCameras(res.cameras)
            directionNote = (if (net.hasPlaces) res.describe() else "지명 사전 없음(옛 도로망 파일) — " + res.describe()) + " (${System.currentTimeMillis() - t0}ms)"
            net
        } catch (e: Exception) { roadsInfo = "도로망 파일 오류: ${e.message}"; directionNote = ""; null }
    }

    /** 기록용: 글·코드 전파로 방향을 붙인 결과. */
    @Volatile var directionNote: String = ""
        private set
    private var resolvedCache: Pair<Triple<Int, Long, Long>, CamDirection.Result>? = null

    /** 설정이 바뀌어 다시 만들어야 할 때. */
    fun invalidate() { synchronized(this) { cached = null; cachedZones = null; resolvedCache = null } }

    /** 파일 파싱은 수 초 걸릴 수 있다. 메인 스레드에서 부르지 말 것. 동시에 두 번 파싱하지 않도록 잠근다. */
    fun load(ctx: Context): CameraIndex {
        cached?.let { return it }
        synchronized(this) {
            cached?.let { return it }
            val f = file(ctx)
            var police = if (f.exists()) CameraCsv.parse(f.readText()) else emptyList()
            var all = police
            if (hasKec(ctx)) {
                try {
                    val sections = KecCsv.parseSections(kecSectionsFile(ctx).readText())
                    val routes = KecCsv.parsePoints(kecPointsFile(ctx).readText())
                    val (kecRaw, warn) = KecCsv.build(sections, routes, Prefs.kecLimitKmh(ctx))
                    val kec = KecCsv.inheritLimits(kecRaw, police, Prefs.kecLimitKmh(ctx))
                    police = KecCsv.assignBearings(police, routes)
                    val directed = police.count { it.bearingDeg != null }
                    kecNote = "도로공사 구간 ${kec.size / 2}건 적용, 고속도로 카메라 ${directed}개에 방향 부여(중심선 기준)" +
                        (if (warn.isNotEmpty()) ", 건너뜀 ${warn.size}건: ${warn.take(3).joinToString("; ")}" else "")
                    all = KecCsv.merge(police, kec)
                } catch (e: Exception) {
                    kecNote = "도로공사 데이터 오류: ${e.message}"
                    all = police
                }
            } else kecNote = ""
            val idx = CameraIndex(all)
            cached = idx
            summaryLine = summaryOf(idx.cameras)
            return idx
        }
    }

    fun loadZones(ctx: Context): CameraIndex {
        cachedZones?.let { return it }
        synchronized(this) {
            cachedZones?.let { return it }
            val f = zonesFile(ctx)
            val idx = CameraIndex(if (f.exists()) runCatching { ZoneCsv.parse(f.readText()) }.getOrDefault(emptyList()) else emptyList())
            cachedZones = idx
            return idx
        }
    }

    fun count(ctx: Context): Int = load(ctx).cameras.size

    /** 이미 읽어 둔 게 있으면 그 개수, 아니면 null(메인 스레드에서 파싱을 막기 위해 기다리지 않는다). */
    fun cachedCount(): Int? = cached?.cameras?.size
    fun cachedZoneCount(): Int? = cachedZones?.cameras?.size

    private fun summaryOf(cams: List<Camera>): String {
        if (cams.isEmpty()) return "카메라 데이터 없음"
        val kec = cams.count { it.fromKec && it.kind == CamKind.SECTION_START }
        return "카메라 ${"%,d".format(cams.size)}개" + (if (kec > 0) " · 도로공사 구간 $kec" else "")
    }

    /** 요약: 전체 / 구간 시점 / 신호 전용 / 도로공사 / 보호구역. */
    fun summary(ctx: Context): String {
        val cams = load(ctx).cameras
        val zones = loadZones(ctx).cameras.size
        if (cams.isEmpty()) return "카메라 데이터 없음 — 메뉴에서 경찰청 전국 CSV를 불러오세요"
        val sec = cams.count { it.kind == CamKind.SECTION_START }
        val secDir = cams.count { it.kind == CamKind.SECTION_START && it.bearingDeg != null }
        val sig = cams.count { it.signalOnly }
        val kec = cams.count { it.fromKec && it.kind == CamKind.SECTION_START }
        return "카메라 ${cams.size}개 로드됨 (구간단속 시점 ${sec}, 그중 방향 아는 것 ${secDir}, 신호 표시 ${sig}" +
            (if (kec > 0) ", 도로공사 구간 $kec" else "") + ")" +
            (if (zones > 0) "\n어린이보호구역 ${zones}곳" else "") +
            (if (kecNote.isNotEmpty()) "\n$kecNote" else "") +
            (if (hasRoads(ctx)) "\n도로망: " + roadsInfo.ifEmpty { "있음" } else "")
    }
}
