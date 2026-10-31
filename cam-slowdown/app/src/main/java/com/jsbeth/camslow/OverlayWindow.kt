package com.jsbeth.camslow

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
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
 * 옆으로 밀면 그쪽 가장자리에 폭 30dp 탭(거리·제한 표지·상태 점)으로 붙고, 탭을 누르면 원래 자리로 펼쳐진다. 접힘도 기억.
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
    /** 지금 떠 있는 모양: "" 알약, "L"/"R" 가장자리 탭. */
    private var builtDock = ""

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
        val dock = Prefs.overlayDock(app)
        if (root == null || theme?.night != t.night || builtDock != dock) { hide(); build(app, t, dock) }
        render(app, t)
    }

    fun hide() {
        val r = root ?: return
        try { wm?.removeView(r) } catch (_: Exception) {}
        root = null; dist = null; line = null; speed = null; sign = null; state = null; dot = null
    }

    private fun dp(ctx: Context, v: Int) = (v * ctx.resources.displayMetrics.density).toInt()
    private fun screenW(ctx: Context) = ctx.resources.displayMetrics.widthPixels

    private fun build(ctx: Context, t: Theme, dock: String) {
        val jua = Theme.display(ctx)
        val docked = dock.isNotEmpty()
        val card = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            elevation = dp(ctx, 8).toFloat()
            if (docked) {
                setPadding(dp(ctx, 3), dp(ctx, 7), dp(ctx, 3), dp(ctx, 7))
                val r = dp(ctx, 12).toFloat()
                background = GradientDrawable().apply {
                    setColor(t.bg); setStroke(dp(ctx, 2), t.chipLine)
                    // 화면 안쪽만 둥글게
                    cornerRadii = if (dock == "R") floatArrayOf(r, r, 0f, 0f, 0f, 0f, r, r) else floatArrayOf(0f, 0f, r, r, r, r, 0f, 0f)
                }
            } else {
                setPadding(dp(ctx, 12), dp(ctx, 8), dp(ctx, 10), dp(ctx, 8))
                background = GradientDrawable().apply { cornerRadius = dp(ctx, 18).toFloat(); setColor(t.bg); setStroke(dp(ctx, 2), t.chipLine) }
            }
        }
        if (docked) {
            // 얇은 탭: 거리(두 줄) · 작은 표지 · 상태 점
            dist = TextView(ctx).apply { textSize = 11f; typeface = jua; setTextColor(t.fg); includeFontPadding = false; gravity = Gravity.CENTER; maxLines = 2; setLineSpacing(0f, 0.95f) }
            card.addView(dist)
            sign = TextView(ctx).apply {
                textSize = 9f; typeface = jua; setTextColor(t.signFg); gravity = Gravity.CENTER; includeFontPadding = false
                background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(t.signBg); setStroke(dp(ctx, 3), t.sign) }
            }
            card.addView(sign, LinearLayout.LayoutParams(dp(ctx, 22), dp(ctx, 22)).apply { topMargin = dp(ctx, 4) })
            dot = View(ctx).apply { background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(t.bandOn) } }
            card.addView(dot, LinearLayout.LayoutParams(dp(ctx, 7), dp(ctx, 7)).apply { topMargin = dp(ctx, 5) })
        } else {
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
            card.addView(top, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
            val bottom = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(0, dp(ctx, 6), 0, 0) }
            dot = View(ctx).apply { background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(t.bandOn) } }
            bottom.addView(dot, LinearLayout.LayoutParams(dp(ctx, 8), dp(ctx, 8)).apply { rightMargin = dp(ctx, 5) })
            state = TextView(ctx).apply { textSize = 10.5f; setTextColor(t.fg); maxLines = 1 }
            bottom.addView(state, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            card.addView(bottom, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        }

        val width = if (docked) dp(ctx, 30) else dp(ctx, 190)
        val params = WindowManager.LayoutParams(
            width, WindowManager.LayoutParams.WRAP_CONTENT,
            if (Build.VERSION.SDK_INT >= 26) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY else @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = if (dock == "R") screenW(ctx) - width else if (dock == "L") 0 else Prefs.overlayX(ctx)
            y = Prefs.overlayY(ctx)
        }
        // 끌기: 손가락을 따라 움직인다. 거의 안 움직인 짧은 터치 = 탭.
        //  알약: 탭 = 앱 열기, 옆으로 밀기(가로 60dp 넘게, 세로보다 2배 이상) = 그쪽 가장자리 탭으로 접기.
        //  탭(접힘): 위아래로만 움직이고, 탭 = 알약으로 펼치기.
        card.setOnTouchListener(object : View.OnTouchListener {
            var sx = 0f; var sy = 0f; var ox = 0; var oy = 0; var moved = false
            override fun onTouch(v: View, e: MotionEvent): Boolean {
                when (e.actionMasked) {
                    MotionEvent.ACTION_DOWN -> { sx = e.rawX; sy = e.rawY; ox = params.x; oy = params.y; moved = false }
                    MotionEvent.ACTION_MOVE -> {
                        val dx = (e.rawX - sx).toInt(); val dy = (e.rawY - sy).toInt()
                        if (abs(dx) > dp(ctx, 6) || abs(dy) > dp(ctx, 6)) moved = true
                        if (moved) {
                            if (!docked) params.x = (ox + dx).coerceIn(-width / 2, screenW(ctx) - width / 2)
                            params.y = (oy + dy).coerceAtLeast(0)
                            try { wm?.updateViewLayout(v, params) } catch (_: Exception) {}
                        }
                    }
                    MotionEvent.ACTION_UP -> {
                        val dx = e.rawX - sx; val dy = e.rawY - sy
                        if (!moved) {
                            if (docked) { Prefs.setOverlayDock(ctx, ""); sync(ctx) }
                            else ctx.startActivity(Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP))
                        } else if (!docked && abs(dx) > dp(ctx, 60) && abs(dx) > 2 * abs(dy)) {
                            // 옆으로 밀기 → 가장자리 탭. 펼칠 때 돌아올 자리는 밀기 전 자리
                            Prefs.setOverlayPos(ctx, ox, params.y)
                            Prefs.setOverlayDock(ctx, if (dx > 0) "R" else "L"); sync(ctx)
                        } else if (!docked && (params.x < -width / 4 || params.x > screenW(ctx) - width * 3 / 4)) {
                            // 화면 밖으로 끌어냄 → 그쪽 가장자리 탭
                            Prefs.setOverlayPos(ctx, ox, params.y)
                            Prefs.setOverlayDock(ctx, if (params.x > 0) "R" else "L"); sync(ctx)
                        } else Prefs.setOverlayPos(ctx, if (docked) Prefs.overlayX(ctx) else params.x, params.y)
                        v.performClick()
                    }
                }
                return true
            }
        })
        val manager = ctx.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        try { manager.addView(card, params) } catch (e: Exception) { DriveState.log("오버레이 창 실패: ${e.message}"); return }
        wm = manager; root = card; lp = params; theme = t; builtDock = dock
    }

    private fun render(ctx: Context, t: Theme) {
        val st = DriveState.status
        val out = DriveState.outputs ?: OutputSettings.load(ctx)
        val next = st?.nextCamera
        val slowed = st?.slowed == true
        val age = if (DriveState.lastFixAt == 0L) null else (SystemClock.elapsedRealtime() - DriveState.lastFixAt) / 1000
        val sinceUp = if (DriveState.lastUpAt == 0L) Long.MAX_VALUE else SystemClock.elapsedRealtime() - DriveState.lastUpAt
        val docked = builtDock.isNotEmpty()
        val over = DriveState.overspeedKmh
        val blink = over != null && (SystemClock.elapsedRealtime() / 1000) % 2 == 0L
        (root?.background as? GradientDrawable)?.apply {
            setStroke(dp(ctx, if (over != null) 4 else 2), if (over != null) 0xFFE5484D.toInt() else if (slowed) t.bandSlow else t.chipLine)
            setColor(if (blink) 0xFFFFE3E3.toInt() else t.bg)
        }
        if (st == null) {
            dist?.text = if (docked) "GPS\n대기" else "GPS 대기"; line?.text = "위치 잡는 중"; speed?.text = "–"; sign?.text = "–"
        } else {
            val d = st.nextDistM?.toInt() ?: 0
            dist?.text = if (next == null) (if (docked) "카메\n없음" else "카메라 없음")
                else if (docked) (if (d >= 1000) "%.1f\nkm".format(d / 1000.0) else "$d\nm") else "${"%,d".format(d)} m"
            line?.text = st.road?.takeIf { !it.startsWith("도로 미확인") }?.substringBefore(" · 지도") ?: (if (next != null) "다음 카메라" else "2km 안에 없음")
            speed?.text = "${st.speedKmh.toInt()}"
            val limit = (st.target ?: next)?.limitKmh ?: (if (st.zone != null) 30 else null)
            speed?.setTextColor(if (over != null || (limit != null && st.speedKmh > limit + 5)) 0xFFE5484D.toInt() else t.fg)
            sign?.text = limit?.toString() ?: "–"
        }
        state?.text = when {
            over != null -> "⚠ 과속 +$over"
            DriveState.simulating -> "가상 주행 중"
            age != null && age >= 15 -> "GPS 없음 ${age}초"
            slowed && st?.holdKind == "구간단속" -> "구간단속 유지 · ${km((st.holdRemainM ?: 0.0) - (DriveState.config?.restoreAfterM ?: 100.0))} 남음"
            slowed && st?.holdKind == "통과" -> "통과 · ${st.holdRemainM?.toInt() ?: 0}m 뒤 복귀"
            slowed -> "▼ ${out.slow.label}로 내림"
            sinceUp < 10_000 -> "▲ ${out.usual.label} 복귀 완료"
            next != null -> "작동 중 · ${(st?.triggerM ?: 0.0).toInt()}m 앞에서 신호"
            else -> "작동 중 · ${out.usual.label} 유지"
        }
        (dot?.background as? GradientDrawable)?.setColor(when {
            age != null && age >= 15 -> t.bandProblem
            slowed -> t.bandSlow
            else -> t.bandOn
        })
    }

    private fun km(m: Double) = if (m >= 950) "%.1fkm".format(m / 1000.0) else "${m.coerceAtLeast(0.0).toInt()}m"
}
