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

    fun alertsOn(ctx: Context) = sp(ctx).getBoolean("alerts", true)
    fun bleOn(ctx: Context) = sp(ctx).getBoolean("ble", false)
    fun bleProfile(ctx: Context) = sp(ctx).getString("bleProfile", "") ?: ""

    fun saveOutputs(
        ctx: Context, usual: Profile, slow: Profile, extraDown: Int, autoRestore: Boolean,
        alerts: Boolean, ble: Boolean, profile: String,
    ) {
        sp(ctx).edit()
            .putInt("usualProfile", usual.ordinal).putInt("slowProfile", slow.ordinal)
            .putInt("extraDown", extraDown).putBoolean("autoRestore", autoRestore)
            .putBoolean("alerts", alerts).putBoolean("ble", ble)
            .putString("bleProfile", profile)
            .apply()
    }
}

/** 불러온 카메라 CSV를 앱 내부에 UTF-8로 보관한다. */
object CameraRepo {
    private fun file(ctx: Context) = File(ctx.filesDir, "cameras.csv")

    @Volatile private var cached: CameraIndex? = null

    fun import(ctx: Context, bytes: ByteArray): Int {
        val text = CameraCsv.decode(bytes)
        val list = CameraCsv.parse(text)
        require(list.isNotEmpty()) { "카메라를 하나도 읽지 못했습니다" }
        file(ctx).writeText(text)
        synchronized(this) { cached = CameraIndex(list) }
        return list.size
    }

    /** 파일 파싱은 수 초 걸릴 수 있다. 메인 스레드에서 부르지 말 것. 동시에 두 번 파싱하지 않도록 잠근다. */
    fun load(ctx: Context): CameraIndex {
        cached?.let { return it }
        synchronized(this) {
            cached?.let { return it }
            val f = file(ctx)
            val idx = CameraIndex(if (f.exists()) CameraCsv.parse(f.readText()) else emptyList())
            cached = idx
            return idx
        }
    }

    fun count(ctx: Context): Int = load(ctx).cameras.size

    /** 이미 읽어 둔 게 있으면 그 개수, 아니면 null(메인 스레드에서 파싱을 막기 위해 기다리지 않는다). */
    fun cachedCount(): Int? = cached?.cameras?.size

    /** 요약: 전체 / 구간 시점 / 신호 전용. */
    fun summary(ctx: Context): String {
        val cams = load(ctx).cameras
        if (cams.isEmpty()) return "카메라 데이터 없음 — CSV를 불러오세요"
        val sec = cams.count { it.kind == CamKind.SECTION_START }
        val sig = cams.count { it.signalOnly }
        return "카메라 ${cams.size}개 로드됨 (구간단속 시점 ${sec}, 신호 표시 ${sig})"
    }
}
