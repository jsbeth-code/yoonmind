package com.jsbeth.camslow

import android.content.Context
import java.io.File

/** FSD v14 속도 프로필. 오른쪽 휠 한 칸 = 한 단계. */
enum class Profile(val label: String) {
    SLOTH("Sloth(나무늘보)"), CHILL("Chill"), STANDARD("Standard"), HURRY("Hurry"), MAD_MAX("Mad Max");

    companion object {
        fun of(i: Int) = values().getOrElse(i) { HURRY }
        val labels get() = values().map { it.label }
    }
}

/** 설정 저장소. 기본값은 EngineConfig와 같다. */
object Prefs {
    private fun sp(ctx: Context) = ctx.getSharedPreferences("camslow", Context.MODE_PRIVATE)

    fun engine(ctx: Context): EngineConfig {
        val p = sp(ctx)
        val d = EngineConfig()
        return d.copy(
            decelMps2 = p.getFloat("decel", d.decelMps2.toFloat()).toDouble(),
            reactionS = p.getFloat("reaction", d.reactionS.toFloat()).toDouble(),
            marginM = p.getFloat("margin", d.marginM.toFloat()).toDouble(),
            fixedTriggerM = p.getInt("fixedTrigger", d.fixedTriggerM),
            restoreAfterM = p.getFloat("restoreAfter", d.restoreAfterM.toFloat()).toDouble(),
            minLimitKmh = p.getInt("minLimit", d.minLimitKmh),
            skipIfBelowLimit = p.getBoolean("skipBelow", d.skipIfBelowLimit),
            leftTolM = p.getFloat("leftTol", d.leftTolM.toFloat()).toDouble(),
            rightTolM = p.getFloat("rightTol", d.rightTolM.toFloat()).toDouble(),
            includeSignalCams = p.getBoolean("includeSignal", d.includeSignalCams),
        )
    }

    fun saveEngine(ctx: Context, c: EngineConfig) {
        sp(ctx).edit()
            .putFloat("decel", c.decelMps2.toFloat())
            .putFloat("reaction", c.reactionS.toFloat())
            .putFloat("margin", c.marginM.toFloat())
            .putInt("fixedTrigger", c.fixedTriggerM)
            .putFloat("restoreAfter", c.restoreAfterM.toFloat())
            .putInt("minLimit", c.minLimitKmh)
            .putBoolean("skipBelow", c.skipIfBelowLimit)
            .putFloat("leftTol", c.leftTolM.toFloat())
            .putFloat("rightTol", c.rightTolM.toFloat())
            .putBoolean("includeSignal", c.includeSignalCams)
            .apply()
    }

    /** 평소 달리는(복귀할) 프로필. */
    fun usualProfile(ctx: Context) = Profile.of(sp(ctx).getInt("usualProfile", Profile.HURRY.ordinal))
    /** 카메라 앞에서 내려갈 프로필. */
    fun slowProfile(ctx: Context) = Profile.of(sp(ctx).getInt("slowProfile", Profile.SLOTH.ordinal))
    /** 감속 프로필이 Sloth(맨 아래)일 때만 더 보내는 여분 클릭. 바닥에선 추가 클릭이 무시되므로 유실 대비. */
    fun extraDownClicks(ctx: Context) = sp(ctx).getInt("extraDown", 1)
    /** 카메라 통과 후 자동으로 프로필을 올릴지. 끄면 음성으로만 알리고 운전자가 휠로 직접 올린다. */
    fun autoRestore(ctx: Context) = sp(ctx).getBoolean("autoRestore", true)

    /** 감속 시 클릭 횟수(휠 아래). */
    fun downPresses(ctx: Context): Int {
        val steps = usualProfile(ctx).ordinal - slowProfile(ctx).ordinal
        val extra = if (slowProfile(ctx) == Profile.SLOTH) extraDownClicks(ctx) else 0
        return (steps + extra).coerceAtLeast(0)
    }
    /** 복귀 시 더블클릭 횟수(휠 위). 정확히 내려간 단수만큼만 올린다(더 보내면 평소보다 빨라진다). */
    fun upPresses(ctx: Context) = (usualProfile(ctx).ordinal - slowProfile(ctx).ordinal).coerceAtLeast(0)

    /** 차 블루투스 연결 시 자동 시작. */
    fun autoStart(ctx: Context) = sp(ctx).getBoolean("autoStart", false)
    /** 연결이 끊기면 잠시 뒤 자동 정지. */
    fun autoStop(ctx: Context) = sp(ctx).getBoolean("autoStop", true)
    fun autoDeviceAddress(ctx: Context) = sp(ctx).getString("autoDevice", "") ?: ""
    fun autoDeviceName(ctx: Context) = sp(ctx).getString("autoDeviceName", "") ?: ""
    fun saveAuto(ctx: Context, on: Boolean, stop: Boolean, address: String, name: String) {
        sp(ctx).edit().putBoolean("autoStart", on).putBoolean("autoStop", stop)
            .putString("autoDevice", address).putString("autoDeviceName", name).apply()
    }

    /** 도로공사 구간단속 데이터엔 제한속도가 없어 이 값을 쓴다. */
    fun kecLimitKmh(ctx: Context) = sp(ctx).getInt("kecLimit", 100)
    fun saveKecLimit(ctx: Context, v: Int) = sp(ctx).edit().putInt("kecLimit", v).apply()

    fun alertsOn(ctx: Context) = sp(ctx).getBoolean("alerts", true)
    fun bleOn(ctx: Context) = sp(ctx).getBoolean("ble", false)
    /** 버튼 누름 사이 간격(ms). 커맨더가 한 번씩 처리할 시간. */
    fun pressGapMs(ctx: Context) = sp(ctx).getInt("pressGap", 400).toLong()
    /** 광고할 때 폰 블루투스 이름을 ENH_BTN으로 바꿀지(실제 버튼 이름. 끄면 되돌린다). */
    fun renameAdapter(ctx: Context) = sp(ctx).getBoolean("renameAdapter", true)

    fun saveOutputs(
        ctx: Context, usual: Profile, slow: Profile, extraDown: Int, autoRestore: Boolean,
        alerts: Boolean, ble: Boolean, pressGapMs: Int, renameAdapter: Boolean,
    ) {
        sp(ctx).edit()
            .putInt("usualProfile", usual.ordinal).putInt("slowProfile", slow.ordinal)
            .putInt("extraDown", extraDown).putBoolean("autoRestore", autoRestore)
            .putBoolean("alerts", alerts).putBoolean("ble", ble)
            .putInt("pressGap", pressGapMs).putBoolean("renameAdapter", renameAdapter)
            .apply()
    }
}

/** 불러온 CSV들을 앱 내부에 UTF-8로 보관한다. 경찰청 전국 데이터 + (선택) 도로공사 구간단속·이정좌표. */
object CameraRepo {
    private fun file(ctx: Context) = File(ctx.filesDir, "cameras.csv")
    private fun kecSectionsFile(ctx: Context) = File(ctx.filesDir, "kec_sections.csv")
    private fun kecPointsFile(ctx: Context) = File(ctx.filesDir, "kec_points.csv")

    @Volatile private var cached: CameraIndex? = null
    /** 마지막 로드 때 도로공사 데이터 처리 결과(경고 포함). */
    @Volatile var kecNote: String = ""
        private set

    fun import(ctx: Context, bytes: ByteArray): Int {
        val text = CameraCsv.decode(bytes)
        val list = CameraCsv.parse(text)
        require(list.isNotEmpty()) { "카메라를 하나도 읽지 못했습니다" }
        file(ctx).writeText(text)
        synchronized(this) { cached = null }
        return list.size
    }

    /** 도로공사 「구간단속현황」 CSV. 형식만 검사하고 저장한다. 이정 좌표가 있어야 카메라가 만들어진다. */
    fun importKecSections(ctx: Context, bytes: ByteArray): String {
        val text = CameraCsv.decode(bytes)
        val n = KecCsv.parseSections(text).size
        require(n > 0) { "구간을 하나도 읽지 못했습니다" }
        kecSectionsFile(ctx).writeText(text)
        synchronized(this) { cached = null }
        return "구간단속 ${n}건 저장" + if (kecPointsFile(ctx).exists()) "" else " — 이정 좌표 CSV도 불러와야 적용됩니다"
    }

    /** 도로공사 「도로중심선_이정_좌표」 CSV. */
    fun importKecPoints(ctx: Context, bytes: ByteArray): String {
        val text = CameraCsv.decode(bytes)
        val routes = KecCsv.parsePoints(text)
        val n = routes.values.sumOf { it.size }
        require(n > 0) { "이정 좌표를 하나도 읽지 못했습니다" }
        kecPointsFile(ctx).writeText(text)
        synchronized(this) { cached = null }
        return "이정 좌표 ${n}개(노선 ${routes.keys.count { !it.startsWith("#") }}개) 저장" +
            if (kecSectionsFile(ctx).exists()) "" else " — 구간단속현황 CSV도 불러와야 적용됩니다"
    }

    fun hasKec(ctx: Context) = kecSectionsFile(ctx).exists() && kecPointsFile(ctx).exists()

    /** 설정(도로공사 제한속도 등)이 바뀌어 다시 만들어야 할 때. */
    fun invalidate() { synchronized(this) { cached = null } }

    /** 파일 파싱은 수 초 걸릴 수 있다. 메인 스레드에서 부르지 말 것. 동시에 두 번 파싱하지 않도록 잠근다. */
    fun load(ctx: Context): CameraIndex {
        cached?.let { return it }
        synchronized(this) {
            cached?.let { return it }
            val f = file(ctx)
            val police = if (f.exists()) CameraCsv.parse(f.readText()) else emptyList()
            val all = if (hasKec(ctx)) {
                try {
                    val sections = KecCsv.parseSections(kecSectionsFile(ctx).readText())
                    val routes = KecCsv.parsePoints(kecPointsFile(ctx).readText())
                    val (kec, warn) = KecCsv.build(sections, routes, Prefs.kecLimitKmh(ctx))
                    kecNote = "도로공사 구간 ${kec.size / 2}건 적용" +
                        (if (warn.isNotEmpty()) ", 건너뜀 ${warn.size}건: ${warn.take(3).joinToString("; ")}" else "")
                    KecCsv.merge(police, kec)
                } catch (e: Exception) {
                    kecNote = "도로공사 데이터 오류: ${e.message}"
                    police
                }
            } else { kecNote = ""; police }
            val idx = CameraIndex(all)
            cached = idx
            return idx
        }
    }

    fun count(ctx: Context): Int = load(ctx).cameras.size

    /** 이미 읽어 둔 게 있으면 그 개수, 아니면 null(메인 스레드에서 파싱을 막기 위해 기다리지 않는다). */
    fun cachedCount(): Int? = cached?.cameras?.size

    /** 요약: 전체 / 구간 시점 / 신호 전용 / 도로공사. */
    fun summary(ctx: Context): String {
        val cams = load(ctx).cameras
        if (cams.isEmpty()) return "카메라 데이터 없음 — CSV를 불러오세요"
        val sec = cams.count { it.kind == CamKind.SECTION_START }
        val sig = cams.count { it.signalOnly }
        val kec = cams.count { it.fromKec && it.kind == CamKind.SECTION_START }
        return "카메라 ${cams.size}개 로드됨 (구간단속 시점 ${sec}, 신호 표시 ${sig}" +
            (if (kec > 0) ", 도로공사 방향 있는 구간 $kec" else "") + ")" +
            (if (kecNote.isNotEmpty()) "\n$kecNote" else "")
    }
}
