package com.jsbeth.camslow

import android.content.Context
import java.io.File

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
            restoreAfterM = p.getFloat("restoreAfter", d.restoreAfterM.toFloat()).toDouble(),
            minLimitKmh = p.getInt("minLimit", d.minLimitKmh),
            skipIfBelowLimit = p.getBoolean("skipBelow", d.skipIfBelowLimit),
        )
    }

    fun saveEngine(ctx: Context, c: EngineConfig) {
        sp(ctx).edit()
            .putFloat("decel", c.decelMps2.toFloat())
            .putFloat("reaction", c.reactionS.toFloat())
            .putFloat("margin", c.marginM.toFloat())
            .putFloat("restoreAfter", c.restoreAfterM.toFloat())
            .putInt("minLimit", c.minLimitKmh)
            .putBoolean("skipBelow", c.skipIfBelowLimit)
            .apply()
    }

    /** 감속 시 클릭 횟수(휠 아래). v14 속도 프로필 기준 4번이면 어디서든 Sloth까지 내려간다. */
    fun downPresses(ctx: Context) = sp(ctx).getInt("downPresses", 4)
    /** 복귀 시 더블클릭 횟수(휠 위). Sloth에서 Hurry로 돌아가려면 3. */
    fun upPresses(ctx: Context) = sp(ctx).getInt("upPresses", 3)
    fun alertsOn(ctx: Context) = sp(ctx).getBoolean("alerts", true)
    fun bleOn(ctx: Context) = sp(ctx).getBoolean("ble", false)
    fun bleProfile(ctx: Context) = sp(ctx).getString("bleProfile", "") ?: ""

    fun saveOutputs(ctx: Context, down: Int, up: Int, alerts: Boolean, ble: Boolean, profile: String) {
        sp(ctx).edit()
            .putInt("downPresses", down).putInt("upPresses", up)
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
        cached = CameraIndex(list)
        return list.size
    }

    fun load(ctx: Context): CameraIndex {
        cached?.let { return it }
        val f = file(ctx)
        val idx = CameraIndex(if (f.exists()) CameraCsv.parse(f.readText()) else emptyList())
        cached = idx
        return idx
    }

    fun count(ctx: Context): Int = load(ctx).cameras.size
}
