package com.jsbeth.camslow

import android.content.Context
import android.content.res.Configuration
import android.graphics.Typeface
import android.text.TextPaint
import android.text.style.MetricAffectingSpan

/** 글의 일부에 글꼴을 입히는 스팬(TypefaceSpan(Typeface)은 API 28부터라 직접). */
class FontSpan(private val tf: Typeface) : MetricAffectingSpan() {
    override fun updateDrawState(p: TextPaint) { p.typeface = tf }
    override fun updateMeasureState(p: TextPaint) { p.typeface = tf }
}

/**
 * 화면 팔레트. 낮 = 파스텔 스티커(크림 바탕, 베이지 길, 민트 내 길, 복숭아 카메라), 밤 = 네온(남색 바탕, 청록 내 길, 빨간 불빛).
 * 설정 "화면"에서 자동(폰 다크 모드 따라감)·항상 낮·항상 밤을 고른다.
 */
class Theme(
    val night: Boolean,
    val bg: Int, val fg: Int, val muted: Int, val appbar: Int, val line: Int,
    val bandOff: Int, val bandOn: Int, val bandProblem: Int, val bandSlow: Int,
    val mapBg: Int, val road: Int, val roadMinor: Int, val path: Int, val pathSlow: Int,
    val cam: Int, val camLens: Int, val camOff: Int, val mapText: Int, val dash: Int,
    val car: Int, val carLine: Int, val carGlass: Int, val glow: Boolean,
    val sign: Int, val signBg: Int, val signFg: Int, val chipBg: Int, val chipLine: Int,
    val toggleOn: Int, val toggleOff: Int,
) {
    companion object {
        val DAY = Theme(
            night = false,
            bg = 0xFFFFF8F1.toInt(), fg = 0xFF4A3F3A.toInt(), muted = 0xFF8C7F78.toInt(), appbar = 0xFFFFE3CF.toInt(), line = 0xFFEAD9CC.toInt(),
            bandOff = 0xFFBFB5AE.toInt(), bandOn = 0xFF6FBF9A.toInt(), bandProblem = 0xFFF0A868.toInt(), bandSlow = 0xFF6C8EF5.toInt(),
            mapBg = 0xFFFDF1E6.toInt(), road = 0xFFE9D6C4.toInt(), roadMinor = 0xFFF1E4D6.toInt(), path = 0xFF58C4A0.toInt(), pathSlow = 0xFF7A9BFF.toInt(),
            cam = 0xFFF27D7D.toInt(), camLens = 0xFF5A3B3B.toInt(), camOff = 0xFFC9BDB4.toInt(), mapText = 0xFF6B5A52.toInt(), dash = 0xFF7A9BFF.toInt(),
            car = 0xFFFFFFFF.toInt(), carLine = 0xFF9A8A80.toInt(), carGlass = 0xFF3B3B46.toInt(), glow = false,
            sign = 0xFFF27D7D.toInt(), signBg = 0xFFFFFFFF.toInt(), signFg = 0xFF222222.toInt(), chipBg = 0xFFFFFFFF.toInt(), chipLine = 0xFFE9D6C4.toInt(),
            toggleOn = 0xFF58C4A0.toInt(), toggleOff = 0xFFF27D7D.toInt(),
        )
        val NIGHT = Theme(
            night = true,
            bg = 0xFF0F1626.toInt(), fg = 0xFFE8EEFC.toInt(), muted = 0xFF9AA6C2.toInt(), appbar = 0xFF141D33.toInt(), line = 0xFF2C3A5C.toInt(),
            bandOff = 0xFF3A4358.toInt(), bandOn = 0xFF1E8F6E.toInt(), bandProblem = 0xFFB86E1E.toInt(), bandSlow = 0xFF2F6FDE.toInt(),
            mapBg = 0xFF0B1222.toInt(), road = 0xFF2A3550.toInt(), roadMinor = 0xFF1F2840.toInt(), path = 0xFF5DE6D2.toInt(), pathSlow = 0xFF6AA0FF.toInt(),
            cam = 0xFFFF5C5C.toInt(), camLens = 0xFF2A0C0C.toInt(), camOff = 0xFF4B5670.toInt(), mapText = 0xFFAAB6CF.toInt(), dash = 0xFF6AA0FF.toInt(),
            car = 0xFFF4F7FF.toInt(), carLine = 0xFFAAB6CF.toInt(), carGlass = 0xFF1A2238.toInt(), glow = true,
            sign = 0xFFFF5C5C.toInt(), signBg = 0xFF0F1626.toInt(), signFg = 0xFFFFFFFF.toInt(), chipBg = 0xFF141D33.toInt(), chipLine = 0xFF2C3A5C.toInt(),
            toggleOn = 0xFF1E8F6E.toInt(), toggleOff = 0xFFD7263D.toInt(),
        )

        fun current(ctx: Context): Theme = when (Prefs.theme(ctx)) {
            "DAY" -> DAY
            "NIGHT" -> NIGHT
            else -> if (ctx.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES) NIGHT else DAY
        }

        /** 둥근 제목 글꼴(Jua, SIL OFL). 못 읽으면 기본 굵은 글꼴. */
        fun display(ctx: Context): Typeface =
            runCatching { ctx.resources.getFont(R.font.jua) }.getOrDefault(Typeface.DEFAULT_BOLD)
    }
}
