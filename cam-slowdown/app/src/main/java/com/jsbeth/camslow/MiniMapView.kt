package com.jsbeth.camslow

import android.content.Context
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.view.View
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/** 지도에 그릴 카메라. east/north는 내 위치 기준 m. state: 0 다른 길(흐리게), 1 앞 시야, 2 지금 목표. */
class MapCam(val east: Float, val north: Float, val limitKmh: Int, val state: Int)

/** 매 fix마다 서비스가 만드는 지도 데이터. */
class MapData(
    val courseDeg: Double,
    val segs: MapSegments?,
    val cams: List<MapCam>,
    val triggerM: Float?,
    val slowed: Boolean,
    val estimated: Boolean,
    /** 구간단속 유지 중이면 지도 왼쪽 위에 "구간단속 중". */
    val inSection: Boolean = false,
)

/**
 * 간이 지도. 진행 방향이 위, 내 차는 아래 1/4 지점, 앞 500 m가 보인다.
 * 도로망 조각(있으면) → 내 경로 강조 → 감속 신호 지점 점선 → 카메라 → 내 차(위에서 본 모델 X) → N 화살표·눈금.
 * 외부 라이브러리 없이 Canvas로만 그린다. 1초 GPS마다 invalidate.
 */
class MiniMapView(ctx: Context) : View(ctx) {
    var theme: Theme = Theme.DAY
        set(v) { field = v; invalidate() }
    var data: MapData? = null
        set(v) { field = v; invalidate() }
    /** 오른쪽 아래 글: 지금 도로 이름 등. */
    var caption: String = ""
        set(v) { field = v; invalidate() }
    var display: Typeface = Typeface.DEFAULT_BOLD

    private val density = ctx.resources.displayMetrics.density
    private fun dp(v: Float) = v * density
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG)
    private val lineBuf = FloatArray(4096)

    /** true면 주어진 높이를 다 쓴다(폴드 두 열 화면의 왼쪽 열). false면 가로폭 × 0.95. */
    var fillHeight = false

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        val h = MeasureSpec.getSize(heightMeasureSpec)
        if (fillHeight && MeasureSpec.getMode(heightMeasureSpec) == MeasureSpec.EXACTLY && h > 0) setMeasuredDimension(w, h)
        else setMeasuredDimension(w, (w * 0.95f).toInt())
    }

    override fun onDraw(c: Canvas) {
        val t = theme
        val w = width.toFloat(); val h = height.toFloat()
        c.drawColor(t.mapBg)
        val d = data
        val carX = w / 2; val carY = h * 0.76f
        val scale = (h * 0.72f) / 500f // px per m
        val course = (d?.courseDeg ?: 0.0).toFloat()

        if (d == null) {
            text.color = t.muted; text.textSize = dp(14f); text.textAlign = Paint.Align.CENTER; text.typeface = Typeface.DEFAULT
            c.drawText("감시를 켜면 지도가 나옵니다", w / 2, h / 2, text)
            drawCar(c, carX, carY, t)
            return
        }

        // 세계 → 화면: 내 위치를 원점으로, 진행 방향을 위로
        c.save()
        c.translate(carX, carY)
        c.rotate(-course) // (동,북)→(x,−y)로 뒤집은 뒤 진행 방향이 위를 보도록 반대로 돌린다
        c.scale(scale, -scale)
        val segs = d.segs
        if (segs != null) {
            val behindCut = -40f // 차 뒤 40 m 밖 경로는 일반 길로
            for (pass in 0..2) {
                stroke.color = when (pass) { 0 -> t.roadMinor; 1 -> t.road; else -> if (d.slowed) t.pathSlow else t.path }
                stroke.strokeWidth = (when (pass) { 0 -> dp(5f); 1 -> dp(11f); else -> dp(12f) }) / scale
                stroke.pathEffect = null
                if (pass == 2 && t.glow) stroke.maskFilter = BlurMaskFilter(dp(6f) / scale, BlurMaskFilter.Blur.SOLID) else stroke.maskFilter = null
                var n = 0
                for (i in 0 until segs.count) {
                    var k = segs.kind[i].toInt()
                    if (k == 2) {
                        // 경로 중 차 뒤쪽 조각은 큰 길로 그린다
                        val a = along(segs.xy[i * 4], segs.xy[i * 4 + 1], course); val b = along(segs.xy[i * 4 + 2], segs.xy[i * 4 + 3], course)
                        if (a < behindCut && b < behindCut) k = 1
                    }
                    if (k != pass) continue
                    if (n + 4 > lineBuf.size) { c.drawLines(lineBuf, 0, n, stroke); n = 0 }
                    lineBuf[n] = segs.xy[i * 4]; lineBuf[n + 1] = segs.xy[i * 4 + 1]; lineBuf[n + 2] = segs.xy[i * 4 + 2]; lineBuf[n + 3] = segs.xy[i * 4 + 3]
                    n += 4
                }
                if (n > 0) c.drawLines(lineBuf, 0, n, stroke)
            }
            stroke.maskFilter = null
        }
        c.restore()

        // 감속 신호 지점: 진행 방향 앞 triggerM에 가로 점선
        val trig = d.triggerM
        if (trig != null && trig > 0 && trig * scale < carY - dp(20f)) {
            val y = carY - trig * scale
            stroke.color = t.dash; stroke.strokeWidth = dp(2.5f); stroke.pathEffect = DashPathEffect(floatArrayOf(dp(8f), dp(7f)), 0f)
            c.drawLine(carX - dp(42f), y, carX + dp(42f), y, stroke)
            stroke.pathEffect = null
            text.color = t.dash; text.textSize = dp(11f); text.textAlign = Paint.Align.RIGHT; text.typeface = display
            c.drawText("감속 신호 ${trig.toInt()} m", carX - dp(48f), y + dp(4f), text)
        }

        // 카메라
        val cr = Math.toRadians(course.toDouble())
        for (cam in d.cams) {
            // (동,북) → 진행 방향 기준 (오른쪽, 앞)
            val right = cam.east * cos(cr).toFloat() - cam.north * sin(cr).toFloat()
            val ahead = cam.east * sin(cr).toFloat() + cam.north * cos(cr).toFloat()
            val x = carX + right * scale; val y = carY - ahead * scale
            if (x < -dp(20f) || x > w + dp(20f) || y < -dp(20f) || y > h + dp(20f)) continue
            val mine = cam.state >= 1
            if (cam.state == 2 && trig != null && ahead <= trig) {
                val pulse = dp(16f) + dp(7f) * sin(System.currentTimeMillis() / 180.0).toFloat()
                stroke.color = t.cam; stroke.strokeWidth = dp(2.5f); stroke.alpha = 170
                c.drawCircle(x, y, pulse, stroke); stroke.alpha = 255
                postInvalidateDelayed(60)
            }
            if (mine) {
                if (t.glow) { fill.maskFilter = BlurMaskFilter(dp(8f), BlurMaskFilter.Blur.OUTER); fill.color = t.cam; c.drawCircle(x, y, dp(11f), fill); fill.maskFilter = null }
                fill.color = t.cam; c.drawCircle(x, y, dp(11f), fill)
                fill.color = t.camLens; c.drawCircle(x, y, dp(4.5f), fill)
            } else {
                stroke.color = t.camOff; stroke.strokeWidth = dp(2.5f); c.drawCircle(x, y, dp(9f), stroke)
                fill.color = t.camOff; c.drawCircle(x, y, dp(3.5f), fill)
            }
            fill.color = Color.WHITE; fill.alpha = 200; c.drawCircle(x - dp(1.5f), y - dp(1.5f), dp(1.5f), fill); fill.alpha = 255
            text.color = if (mine) t.cam else t.camOff; text.textSize = dp(13f); text.typeface = display
            text.textAlign = if (right >= 0) Paint.Align.LEFT else Paint.Align.RIGHT
            c.drawText("${cam.limitKmh}", x + (if (right >= 0) dp(16f) else -dp(16f)), y + dp(5f), text)
        }

        drawCar(c, carX, carY, t)

        // N 화살표: 북쪽 = 화면 위에서 −course 만큼 돌린 방향
        c.save(); c.translate(w - dp(22f), dp(26f)); c.rotate(-course)
        fill.color = t.mapText
        val p = android.graphics.Path().apply { moveTo(0f, -dp(9f)); lineTo(dp(4.5f), dp(6f)); lineTo(0f, dp(3f)); lineTo(-dp(4.5f), dp(6f)); close() }
        c.drawPath(p, fill); c.restore()
        text.color = t.mapText; text.textSize = dp(10f); text.textAlign = Paint.Align.CENTER; text.typeface = Typeface.DEFAULT_BOLD
        c.drawText("N", w - dp(22f), dp(46f), text)
        // 눈금 100 m
        fill.color = t.mapText; c.drawRect(dp(12f), h - dp(14f), dp(12f) + 100 * scale, h - dp(12f), fill)
        text.textAlign = Paint.Align.LEFT; text.typeface = Typeface.DEFAULT
        c.drawText("100 m", dp(12f), h - dp(18f), text)
        if (caption.isNotEmpty()) { text.textAlign = Paint.Align.RIGHT; c.drawText(caption, w - dp(12f), h - dp(14f), text) }
        if (d.inSection) {
            val label = "구간단속 중"
            text.textSize = dp(11f); text.typeface = Typeface.DEFAULT_BOLD; text.textAlign = Paint.Align.LEFT
            val tw = text.measureText(label)
            fill.color = t.pathSlow; fill.alpha = 40
            c.drawRoundRect(dp(10f), dp(10f), dp(10f) + tw + dp(14f), dp(30f), dp(8f), dp(8f), fill); fill.alpha = 255
            text.color = t.pathSlow; c.drawText(label, dp(17f), dp(24f), text)
        }
        if (d.estimated) { text.textAlign = Paint.Align.CENTER; text.color = t.dash; text.textSize = dp(11f); c.drawText("추측 항법 중", w / 2, dp(18f), text) }
        if (segs == null) { text.textAlign = Paint.Align.CENTER; text.color = t.muted; text.textSize = dp(10f); c.drawText("도로망 없음 — 카메라만 표시", w / 2, dp(18f), text) }
    }

    private fun along(east: Float, north: Float, courseDeg: Float): Float {
        val cr = Math.toRadians(courseDeg.toDouble())
        return east * sin(cr).toFloat() + north * cos(cr).toFloat()
    }

    /** 위에서 본 모델 X: 흰 몸체, 앞유리부터 이어지는 까만 파노라마 지붕, 사이드 미러, 헤드·테일라이트, 보닛 T. */
    private fun drawCar(c: Canvas, cx: Float, cy: Float, t: Theme) {
        val lc = dp(44f); val wc = dp(23f)
        c.save(); c.translate(cx, cy)
        if (t.glow) { fill.maskFilter = BlurMaskFilter(dp(10f), BlurMaskFilter.Blur.OUTER); fill.color = 0x995DE6D2.toInt(); c.drawRoundRect(RectF(-wc / 2, -lc / 2, wc / 2, lc / 2), dp(8f), dp(8f), fill); fill.maskFilter = null }
        fill.color = 0x38000000; c.drawRoundRect(RectF(-wc / 2 + dp(2f), -lc / 2 + dp(3f), wc / 2 + dp(2f), lc / 2 + dp(3f)), dp(8f), dp(8f), fill)
        fill.color = t.car; c.drawRoundRect(RectF(-wc / 2, -lc / 2, wc / 2, lc / 2), dp(8f), dp(8f), fill)
        stroke.color = t.carLine; stroke.strokeWidth = dp(1.2f); c.drawRoundRect(RectF(-wc / 2, -lc / 2, wc / 2, lc / 2), dp(8f), dp(8f), stroke)
        // 사이드 미러
        fill.color = t.car
        c.drawRoundRect(RectF(-wc / 2 - dp(3.5f), -lc / 2 + dp(12f), -wc / 2, -lc / 2 + dp(17f)), dp(1.5f), dp(1.5f), fill)
        c.drawRoundRect(RectF(wc / 2, -lc / 2 + dp(12f), wc / 2 + dp(3.5f), -lc / 2 + dp(17f)), dp(1.5f), dp(1.5f), fill)
        // 앞유리 + 파노라마 지붕
        fill.color = t.carGlass; c.drawRoundRect(RectF(-wc / 2 + dp(3f), -lc / 2 + dp(8f), wc / 2 - dp(3f), lc / 2 - dp(11f)), dp(5f), dp(5f), fill)
        fill.color = 0x2EFFFFFF; c.drawRoundRect(RectF(-wc / 2 + dp(5f), -lc / 2 + dp(10f), -wc / 2 + dp(8f), lc / 2 - dp(14f)), dp(1.5f), dp(1.5f), fill)
        // 뒷유리
        fill.color = t.carGlass; c.drawRoundRect(RectF(-wc / 2 + dp(4f), lc / 2 - dp(9f), wc / 2 - dp(4f), lc / 2 - dp(5f)), dp(2f), dp(2f), fill)
        // 헤드라이트·테일라이트
        fill.color = 0xFFFFF2B8.toInt()
        c.drawRoundRect(RectF(-wc / 2 + dp(2f), -lc / 2 + dp(1.5f), -wc / 2 + dp(9f), -lc / 2 + dp(4.5f)), dp(1.5f), dp(1.5f), fill)
        c.drawRoundRect(RectF(wc / 2 - dp(9f), -lc / 2 + dp(1.5f), wc / 2 - dp(2f), -lc / 2 + dp(4.5f)), dp(1.5f), dp(1.5f), fill)
        fill.color = 0xFFFF3B30.toInt()
        c.drawRoundRect(RectF(-wc / 2 + dp(2f), lc / 2 - dp(4.5f), -wc / 2 + dp(9f), lc / 2 - dp(1.5f)), dp(1.5f), dp(1.5f), fill)
        c.drawRoundRect(RectF(wc / 2 - dp(9f), lc / 2 - dp(4.5f), wc / 2 - dp(2f), lc / 2 - dp(1.5f)), dp(1.5f), dp(1.5f), fill)
        // 보닛 T
        fill.color = t.carLine
        c.drawRect(-dp(2.5f), -lc / 2 + dp(4f), dp(2.5f), -lc / 2 + dp(5.4f), fill)
        c.drawRect(-dp(0.7f), -lc / 2 + dp(4f), dp(0.7f), -lc / 2 + dp(7.5f), fill)
        c.restore()
    }
}
