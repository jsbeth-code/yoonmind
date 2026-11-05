package com.jsbeth.camslow

import android.app.Activity
import android.app.Dialog
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import com.kakao.vectormap.KakaoMap
import com.kakao.vectormap.KakaoMapReadyCallback
import com.kakao.vectormap.KakaoMapSdk
import com.kakao.vectormap.LatLng
import com.kakao.vectormap.MapLifeCycleCallback
import com.kakao.vectormap.MapView
import com.kakao.vectormap.camera.CameraUpdateFactory
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.max

/**
 * v0.12: 목적지가 들어오면 카카오내비처럼 지도에서 경로를 보고 고르는 화면.
 * 위: 카카오맵 위에 종류별 경로선(고른 것 파랑, 나머지 회색)과 "추천 20분" 이름표. 아래: 경로 목록(거리·요금·시간)과 [➦].
 * 아무것도 안 누르면 시간이 지나 지금 경로(설정의 기본값)로 계속한다.
 */
class RouteChooser(
    private val act: Activity,
    private val destName: String,
    private val choices: List<KakaoSafety.RouteChoice>,
    current: RouteOpt,
    private val onPick: (RouteOpt?) -> Unit,
) {
    private var selected = choices.indexOfFirst { it.opt == current }.coerceAtLeast(0)
    private val dlg = Dialog(act, android.R.style.Theme_Black_NoTitleBar_Fullscreen)
    private var mapView: MapView? = null
    private var kmap: KakaoMap? = null
    private val dens = act.resources.displayMetrics.density
    private fun dp(v: Int) = (v * dens).toInt()
    private val cards = ArrayList<LinearLayout>()
    private lateinit var info: TextView
    private val overlay = object : View(act) {
        private val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND }
        private val tp = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 13 * dens; typeface = Typeface.DEFAULT_BOLD }
        private val bp = Paint(Paint.ANTI_ALIAS_FLAG)
        override fun onDraw(c: Canvas) {
            val m = kmap ?: return
            fun pt(la: Double, lo: Double) = runCatching { m.toScreenPoint(LatLng.from(la, lo)) }.getOrNull()
            val order = choices.indices.sortedBy { if (it == selected) 1 else 0 } // 고른 것을 맨 위에
            for (i in order) {
                val line = choices[i].line ?: continue
                val path = Path(); var started = false
                var k = 0
                while (k + 1 < line.size) {
                    val s = pt(line[k], line[k + 1]); k += 2
                    s ?: continue
                    if (!started) { path.moveTo(s.x.toFloat(), s.y.toFloat()); started = true } else path.lineTo(s.x.toFloat(), s.y.toFloat())
                }
                val sel = i == selected
                p.color = if (sel) 0xFF1B4FC4.toInt() else 0xFF6E6E6E.toInt(); p.strokeWidth = (if (sel) 11 else 9) * dens; c.drawPath(path, p)
                p.color = if (sel) 0xFF2F6FE4.toInt() else 0xFFA8A8A8.toInt(); p.strokeWidth = (if (sel) 8 else 6) * dens; c.drawPath(path, p)
            }
            // 이름표: 경로 가운데쯤
            for (i in order) {
                val ch = choices[i]; val line = ch.line ?: continue
                val n = line.size / 2; if (n < 2) continue
                val at = (n * (0.35 + 0.12 * i)).toInt().coerceIn(0, n - 1)
                val s = pt(line[2 * at], line[2 * at + 1]) ?: continue
                val label = "${ch.opt.label} ${mins(ch.timeS)}"
                val w = tp.measureText(label) + 16 * dens; val h = 24 * dens
                val sel = i == selected
                bp.color = if (sel) 0xFF2F6BE0.toInt() else Color.WHITE
                c.drawRoundRect(s.x - w / 2, s.y - h - 6 * dens, s.x + w / 2, s.y - 6 * dens, 8 * dens, 8 * dens, bp)
                tp.color = if (sel) Color.WHITE else 0xFF222222.toInt()
                c.drawText(label, s.x - w / 2 + 8 * dens, s.y - 6 * dens - 7 * dens, tp)
            }
            if (!paused) postInvalidateDelayed(120) // 지도를 손으로 움직여도 따라오게(초당 약 8번)
        }
    }

    fun show() {
        dlg.requestWindowFeature(Window.FEATURE_NO_TITLE)
        val root = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(0xFFF3F0E8.toInt()) }
        // 위: 현위치 → 목적지
        root.addView(TextView(act).apply {
            text = "현위치  →  $destName"; textSize = 16f; gravity = Gravity.CENTER; setTextColor(0xFF222222.toInt())
            setPadding(dp(16), dp(14), dp(16), dp(14)); setBackgroundColor(Color.WHITE)
        })
        // 가운데: 지도
        val mapBox = FrameLayout(act)
        root.addView(mapBox, LinearLayout.LayoutParams(-1, 0, 1f))
        startMap(mapBox)
        // 아래: 경로 목록
        val sheet = LinearLayout(act).apply {
            orientation = LinearLayout.VERTICAL; setPadding(dp(12), dp(10), dp(12), dp(12))
            background = GradientDrawable().apply { setColor(0xFF222222.toInt()); cornerRadii = floatArrayOf(dp(20).toFloat(), dp(20).toFloat(), dp(20).toFloat(), dp(20).toFloat(), 0f, 0f, 0f, 0f) }
        }
        choices.forEachIndexed { i, ch -> sheet.addView(card(i, ch), LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) }) }
        info = TextView(act).apply { textSize = 12f; setTextColor(0xFFAAAAAA.toInt()); gravity = Gravity.CENTER; setPadding(0, dp(8), 0, 0) }
        sheet.addView(info)
        sheet.addView(TextView(act).apply {
            text = "이대로 계속"; textSize = 15f; setTextColor(0xFF7DA2FF.toInt()); gravity = Gravity.CENTER; setPadding(0, dp(10), 0, dp(2))
            setOnClickListener { finish(null) }
        })
        root.addView(sheet)
        dlg.setContentView(root)
        dlg.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        dlg.setOnCancelListener { finish(null) }
        dlg.show()
        refresh()
    }

    /** 남은 시간 표시(1초마다 MainActivity가 부름). */
    fun tick(secs: Int) {
        if (::info.isInitialized) info.text = "${secs}초 뒤 '${choices.getOrNull(selected)?.opt?.label ?: "지금 경로"}'(으)로 계속 · 경로를 누르면 지도에서 강조"
    }

    val showing: Boolean get() = dlg.isShowing
    private var paused = false
    /** 화면이 가려질 때·돌아올 때(MainActivity onPause/onResume). */
    fun pause() { paused = true; runCatching { mapView?.pause() } }
    fun resume() { paused = false; runCatching { mapView?.resume() }; overlay.invalidate() }

    fun dismiss() {
        runCatching { mapView?.finish() }; mapView = null; kmap = null
        if (dlg.isShowing) runCatching { dlg.dismiss() }
    }

    private var finished = false
    private fun finish(o: RouteOpt?) {
        if (finished) return
        finished = true
        dismiss()
        onPick(o)
    }

    private fun card(i: Int, ch: KakaoSafety.RouteChoice): LinearLayout {
        val row = LinearLayout(act).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(dp(14), dp(10), dp(10), dp(10))
            setOnClickListener { selected = i; refresh() }
        }
        val col = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL }
        col.addView(TextView(act).apply {
            textSize = 15f; setTextColor(Color.WHITE)
            text = "${ch.opt.label}  ${km(ch.distM)}  ·  " + (if (ch.cost > 0) "유료 %,d원".format(ch.cost) else "무료")
        })
        col.addView(TextView(act).apply { textSize = 24f; setTextColor(Color.WHITE); typeface = Typeface.DEFAULT_BOLD; text = mins(ch.timeS) })
        row.addView(col, LinearLayout.LayoutParams(0, -2, 1f))
        row.addView(TextView(act).apply {
            text = "➦"; textSize = 22f; gravity = Gravity.CENTER; setTextColor(Color.WHITE)
            setOnClickListener { finish(ch.opt) }
        }, LinearLayout.LayoutParams(dp(54), dp(54)))
        cards += row
        return row
    }

    private fun refresh() {
        cards.forEachIndexed { i, row ->
            val sel = i == selected
            row.background = GradientDrawable().apply {
                cornerRadius = dp(14).toFloat(); setColor(0xFF2D2D2D.toInt()); if (sel) setStroke(dp(2), 0xFF3B6CF0.toInt())
            }
            (row.getChildAt(1) as? TextView)?.background = GradientDrawable().apply {
                cornerRadius = dp(12).toFloat(); setColor(if (sel) 0xFF2F6BE0.toInt() else 0xFF555555.toInt())
            }
        }
        overlay.invalidate()
    }

    private fun startMap(box: FrameLayout) {
        val key = BuildConfig.KAKAO_NATIVE_APP_KEY
        if (key.isBlank()) return
        try {
            if (!KakaoMapSdk.isInitialized()) KakaoMapSdk.init(act.applicationContext, key)
            val mv = MapView(act)
            mapView = mv
            box.addView(mv, FrameLayout.LayoutParams(-1, -1))
            box.addView(overlay, FrameLayout.LayoutParams(-1, -1))
            mv.start(object : MapLifeCycleCallback() {
                override fun onMapDestroy() { kmap = null }
                override fun onMapError(e: Exception) { }
            }, object : KakaoMapReadyCallback() {
                override fun onMapReady(map: KakaoMap) {
                    box.post { kmap = map; fit(map, box); overlay.invalidate() }
                }
            })
        } catch (_: Throwable) { }
    }

    /** 모든 경로선이 들어오게 가운데·확대를 맞춘다. */
    private fun fit(m: KakaoMap, box: View) {
        var minLa = 90.0; var maxLa = -90.0; var minLo = 180.0; var maxLo = -180.0
        for (ch in choices) { val l = ch.line ?: continue; var k = 0
            while (k + 1 < l.size) { minLa = minOf(minLa, l[k]); maxLa = maxOf(maxLa, l[k]); minLo = minOf(minLo, l[k + 1]); maxLo = maxOf(maxLo, l[k + 1]); k += 2 } }
        if (minLa > maxLa) return
        val cLa = (minLa + maxLa) / 2; val cLo = (minLo + maxLo) / 2
        // 화면 크기에 맞는 확대: 줌 z에서 1픽셀 ≈ 156543·cos(위도)/2^z m (Web 메르카토르)
        val spanM = max((maxLa - minLa) * 111_000.0, (maxLo - minLo) * 111_000.0 * cos(Math.toRadians(cLa))) * 1.35 + 300
        val px = max(1, minOf(box.width, box.height)).toDouble() / dens * 2 // 대략 dp의 2배를 픽셀로
        val z = (ln(156543.0 * cos(Math.toRadians(cLa)) * px / spanM) / ln(2.0)).toInt().coerceIn(6, 17)
        runCatching { m.moveCamera(CameraUpdateFactory.newCenterPosition(LatLng.from(cLa, cLo), z)) }
    }

    companion object {
        fun mins(s: Int): String { val m = (s + 59) / 60; return if (m >= 60) "${m / 60}시간 ${m % 60}분" else "${m}분" }
        fun km(m: Int): String = if (m >= 1000) "%.1fkm".format(m / 1000.0) else "${m}m"
    }
}
