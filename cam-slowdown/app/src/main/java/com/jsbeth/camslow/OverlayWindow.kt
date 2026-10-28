package com.jsbeth.camslow

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.SystemClock
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.math.abs

/**
 * 다른 앱 위에 떠 있는 알약 창. 감시 중이고 앱 화면이 뒤에 있을 때만 보인다("다른 앱 위에 표시" 권한 필요).
 *  [ 1,480 m          85   (80) ]
 *  [ 동부간선로        km/h       ]
 *  [ ● 작동 중 · 290m 앞에서 신호  ]
 * 끌어서 옮기고(자리 기억), 탭하면 앱을 연다. 감속 중엔 테두리가 파랗게.
 */
@SuppressLint("StaticFieldLeak")
object OverlayWindow {
    private var wm: WindowManager? = null
    private var root: LinearLayout? = null
    private var lp: WindowManager.LayoutParams? = null
    private var dist: TextView? = null
    private var line: TextView? = null
    private var speed: TextView? = null
    private var sign: TextView? = null
    private var state: TextView? = null
    private var dot: View? = null
    private var theme: Theme? = null

    val showing: Boolean get() = root != null

    fun canDraw(ctx: Context): Boolean = Settings.canDrawOverlays(ctx)

    /** 설정의 "권한 요청" 버튼: 시스템의 "다른 앱 위에 표시" 화면으로. */
    fun requestPermission(ctx: Context) {
        ctx.startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, android.net.Uri.parse("package:${ctx.packageName}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    /** 조건에 맞으면 띄우고(이미 떠 있으면 내용만 갱신), 아니면 내린다. 메인 스레드에서. */
    fun sync(ctx: Context) {
        val app = ctx.applicationContext
        val want = Prefs.overlayOn(app) && DriveState.running && !DriveState.activityVisible && canDraw(app)
        if (!want) { hide(); return }
        val t = Theme.current(app)
        if (root == null || theme?.night != t.night) { hide(); build(app, t) }
        render(app, t)
    }

    fun hide() {
        val r = root ?: return
        try { wm?.removeView(r) } catch (_: Exception) {}
        root = null; dist = null; line = null; speed = null; sign = null; state = null; dot = null
    }

    private fun dp(ctx: Context, v: Int) = (v * ctx.resources.displayMetrics.density).toInt()

    private fun build(ctx: Context, t: Theme) {
        val jua = Theme.display(ctx)
        val card = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(ctx, 12), dp(ctx, 8), dp(ctx, 10), dp(ctx, 8))
            background = GradientDrawable().apply { cornerRadius = dp(ctx, 18).toFloat(); setColor(t.bg); setStroke(dp(ctx, 2), t.chipLine) }
            elevation = dp(ctx, 8).toFloat()
        }
        val top = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val left = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        dist = TextView(ctx).apply { textSize = 22f; typeface = jua; setTextColor(t.fg); includeFontPadding = false }
        line = TextView(ctx).apply { textSize = 10.5f; setTextColor(t.muted); maxLines = 1 }
        left.addView(dist); left.addView(line)
        top.addView(left, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        val mid = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL; setPadding(dp(ctx, 8), 0, dp(ctx, 10), 0) }
        speed = TextView(ctx).apply { textSize = 22f; typeface = jua; setTextColor(t.fg); includeFontPadding = false; gravity = Gravity.CENTER }
        mid.addView(speed); mid.addView(TextView(ctx).apply { text = "km/h"; textSize = 9f; setTextColor(t.muted); gravity = Gravity.CENTER })
        top.addView(mid)
        sign = TextView(ctx).apply {
            textSize = 15f; typeface = jua; setTextColor(t.signFg); gravity = Gravity.CENTER; includeFontPadding = false
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(t.signBg); setStroke(dp(ctx, 5), t.sign) }
        }
        top.addView(sign, LinearLayout.LayoutParams(dp(ctx, 40), dp(ctx, 40)))
        card.addView(top)
        val bottom = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(ctx, 6), 0, 0)
        }
        dot = View(ctx).apply { background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(t.bandOn) } }
        bottom.addView(dot, LinearLayout.LayoutParams(dp(ctx, 8), dp(ctx, 8)).apply { rightMargin = dp(ctx, 5) })
        state = TextView(ctx).apply { textSize = 10.5f; setTextColor(t.fg); maxLines = 1 }
        bottom.addView(state, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        card.addView(bottom)

        val params = WindowManager.LayoutParams(
            dp(ctx, 190), WindowManager.LayoutParams.WRAP_CONTENT,
            if (Build.VERSION.SDK_INT >= 26) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY else @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = Prefs.overlayX(ctx); y = Prefs.overlayY(ctx)
        }
        // 끌기: 손가락을 따라 움직이고, 거의 안 움직였으면 탭 = 앱 열기
        card.setOnTouchListener(object : View.OnTouchListener {
            var sx = 0f; var sy = 0f; var ox = 0; var oy = 0; var moved = false
            override fun onTouch(v: View, e: MotionEvent): Boolean {
                when (e.actionMasked) {
                    MotionEvent.ACTION_DOWN -> { sx = e.rawX; sy = e.rawY; ox = params.x; oy = params.y; moved = false }
                    MotionEvent.ACTION_MOVE -> {
                        val dx = (e.rawX - sx).toInt(); val dy = (e.rawY - sy).toInt()
                        if (abs(dx) > dp(ctx, 6) || abs(dy) > dp(ctx, 6)) moved = true
                        if (moved) { params.x = (ox + dx).coerceAtLeast(0); params.y = (oy + dy).coerceAtLeast(0); try { wm?.updateViewLayout(v, params) } catch (_: Exception) {} }
                    }
                    MotionEvent.ACTION_UP -> {
                        if (moved) Prefs.setOverlayPos(ctx, params.x, params.y)
                        else ctx.startActivity(Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP))
                        v.performClick()
                    }
                }
                return true
            }
        })
        val manager = ctx.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        try { manager.addView(card, params) } catch (e: Exception) { DriveState.log("오버레이 창 실패: ${e.message}"); return }
        wm = manager; root = card; lp = params; theme = t
    }

    private fun render(ctx: Context, t: Theme) {
        val st = DriveState.status
        val out = DriveState.outputs ?: OutputSettings.load(ctx)
        val next = st?.nextCamera
        val slowed = st?.slowed == true
        val age = if (DriveState.lastFixAt == 0L) null else (SystemClock.elapsedRealtime() - DriveState.lastFixAt) / 1000
        val sinceUp = if (DriveState.lastUpAt == 0L) Long.MAX_VALUE else SystemClock.elapsedRealtime() - DriveState.lastUpAt
        (root?.background as? GradientDrawable)?.setStroke(dp(ctx, 2), if (slowed) t.bandSlow else t.chipLine)
        if (st == null) {
            dist?.text = "GPS 대기"; line?.text = "위치 잡는 중"; speed?.text = "–"; sign?.text = "–"
        } else {
            dist?.text = if (next != null) "${"%,d".format(st.nextDistM?.toInt() ?: 0)} m" else "카메라 없음"
            line?.text = st.road?.takeIf { !it.startsWith("도로 미확인") }?.substringBefore(" · 지도") ?: (if (next != null) "다음 카메라" else "2km 안에 없음")
            speed?.text = "${st.speedKmh.toInt()}"
            val limit = (st.target ?: next)?.limitKmh ?: (if (st.zone != null) 30 else null)
            speed?.setTextColor(if (limit != null && st.speedKmh > limit + 5) t.cam else t.fg)
            sign?.text = limit?.toString() ?: "–"
        }
        val text = when {
            DriveState.simulating -> "가상 주행 중"
            age != null && age >= 15 -> "GPS 없음 ${age}초"
            slowed && st?.holdKind == "구간단속" -> "구간단속 유지 · ${km((st.holdRemainM ?: 0.0) - (DriveState.config?.restoreAfterM ?: 100.0))} 남음"
            slowed && st?.holdKind == "통과" -> "통과 · ${st.holdRemainM?.toInt() ?: 0}m 뒤 복귀"
            slowed -> "▼ ${out.slow.label}로 내림"
            sinceUp < 10_000 -> "▲ ${out.usual.label} 복귀 완료"
            next != null -> "작동 중 · ${(st?.triggerM ?: 0.0).toInt()}m 앞에서 신호"
            else -> "작동 중 · ${out.usual.label} 유지"
        }
        state?.text = text
        (dot?.background as? GradientDrawable)?.setColor(when {
            age != null && age >= 15 -> t.bandProblem
            slowed -> t.bandSlow
            else -> t.bandOn
        })
    }

    private fun km(m: Double) = if (m >= 950) "%.1fkm".format(m / 1000.0) else "${m.coerceAtLeast(0.0).toInt()}m"
}
