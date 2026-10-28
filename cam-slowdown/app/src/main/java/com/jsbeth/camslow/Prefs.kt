package com.jsbeth.camslow

import android.content.Context
import android.content.SharedPreferences
import java.io.File

/** FSD 오른쪽 휠 속도 프로필 4단계. 휠 아래 = 느린 쪽. (한국 FSD 기준: 나무늘보·스탠다드·신속주행·Mad Max) */
enum class Profile(val label: String) {
    SLOTH("나무늘보"), STANDARD("스탠다드"), HURRY("신속주행"), MAD_MAX("Mad Max");

    companion object {
        fun byName(n: String?, default: Profile) = values().firstOrNull { it.name == n } ?: default
        val labels get() = values().map { it.label }
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

/** 설정 저장소. 바꾸는 즉시 저장한다. 기본값은 EngineConfig와 같다. */
object Prefs {
    private fun sp(ctx: Context): SharedPreferences = ctx.getSharedPreferences("camslow", Context.MODE_PRIVATE)
    private fun SharedPreferences.f(key: String, d: Double) = getFloat(key, d.toFloat()).toDouble()
    private fun put(ctx: Context, block: SharedPreferences.Editor.() -> Unit) = sp(ctx).edit().apply(block).apply()

    // ---- 프로필
    fun usualProfile(ctx: Context) = profile(ctx, "usualProfile2", "usualProfile", Profile.HURRY)
    fun slowProfile(ctx: Context) = profile(ctx, "slowProfile2", "slowProfile", Profile.SLOTH)
    fun setUsualProfile(ctx: Context, p: Profile) = put(ctx) { putString("usualProfile2", p.name) }
    fun setSlowProfile(ctx: Context, p: Profile) = put(ctx) { putString("slowProfile2", p.name) }

    /** 예전 5단계(Sloth·Chill·Standard·Hurry·Mad Max) 번호로 저장된 값은 4단계로 옮긴다. */
    private fun profile(ctx: Context, key: String, oldKey: String, default: Profile): Profile {
        val p = sp(ctx)
        p.getString(key, null)?.let { return Profile.byName(it, default) }
        if (!p.contains(oldKey)) return default
        return when (p.getInt(oldKey, -1)) {
            0 -> Profile.SLOTH; 1, 2 -> Profile.STANDARD; 3 -> Profile.HURRY; 4 -> Profile.MAD_MAX; else -> default
        }
    }

    fun extraDownClicks(ctx: Context) = sp(ctx).getInt("extraDown", 1)
    fun setExtraDownClicks(ctx: Context, v: Int) = put(ctx) { putInt("extraDown", v.coerceIn(0, 4)) }
    fun autoRestore(ctx: Context) = sp(ctx).getBoolean("autoRestore", true)
    fun setAutoRestore(ctx: Context, v: Boolean) = put(ctx) { putBoolean("autoRestore", v) }

    /** 감속 시 클릭 횟수(휠 아래). 내릴 프로필이 맨 아래(나무늘보)면 유실 대비 여분을 더한다(바닥에선 추가 클릭이 무시됨). */
    fun downPresses(ctx: Context): Int {
        val steps = usualProfile(ctx).ordinal - slowProfile(ctx).ordinal
        val extra = if (slowProfile(ctx) == Profile.SLOTH) extraDownClicks(ctx) else 0
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

    // ---- 출력·연결
    fun alertsOn(ctx: Context) = sp(ctx).getBoolean("alerts", true)
    fun setAlertsOn(ctx: Context, v: Boolean) = put(ctx) { putBoolean("alerts", v) }
    fun voiceRoute(ctx: Context) = VoiceRoute.byName(sp(ctx).getString("voiceRoute", null))
    fun setVoiceRoute(ctx: Context, v: VoiceRoute) = put(ctx) { putString("voiceRoute", v.name) }
    fun keepScreenOn(ctx: Context) = sp(ctx).getBoolean("keepScreen", true)
    fun setKeepScreenOn(ctx: Context, v: Boolean) = put(ctx) { putBoolean("keepScreen", v) }
    fun bleOn(ctx: Context) = sp(ctx).getBoolean("ble", false)
    fun setBleOn(ctx: Context, v: Boolean) = put(ctx) { putBoolean("ble", v) }
    fun pressGapMs(ctx: Context) = sp(ctx).getInt("pressGap", 400).toLong()
    fun setPressGapMs(ctx: Context, v: Int) = put(ctx) { putInt("pressGap", v.coerceIn(100, 3000)) }
    fun renameAdapter(ctx: Context) = sp(ctx).getBoolean("renameAdapter", true)
    fun setRenameAdapter(ctx: Context, v: Boolean) = put(ctx) { putBoolean("renameAdapter", v) }

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

    /** 도로망을 연다(zip은 열어 두고 타일은 필요할 때 읽는다). 없거나 깨졌으면 null. 카메라는 나중에 setCameras로. */
    fun openRoads(ctx: Context, cams: List<Camera>): RoadNet? {
        val f = roadsFile(ctx)
        if (!f.exists()) { roadsInfo = ""; return null }
        return try {
            val net = RoadNet.open(f, cams)
            roadsInfo = roadsInfoOf(net.info)
            net
        } catch (e: Exception) { roadsInfo = "도로망 파일 오류: ${e.message}"; null }
    }

    /** 설정이 바뀌어 다시 만들어야 할 때. */
    fun invalidate() { synchronized(this) { cached = null; cachedZones = null } }

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
