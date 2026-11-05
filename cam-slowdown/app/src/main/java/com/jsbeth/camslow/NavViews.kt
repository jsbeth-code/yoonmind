package com.jsbeth.camslow

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Typeface
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import com.kakao.vectormap.KakaoMap
import com.kakao.vectormap.KakaoMapReadyCallback
import com.kakao.vectormap.KakaoMapSdk
import com.kakao.vectormap.LatLng
import com.kakao.vectormap.MapLifeCycleCallback
import com.kakao.vectormap.MapView
import com.kakao.vectormap.camera.CameraUpdateFactory
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/**
 * 카메라 카드의 접근 게이지. 왼쪽 끝 = 카메라 500 m 앞, 오른쪽 끝 = 카메라(제한속도 표지).
 * 내 차 점(▶)이 오른쪽으로 다가가고 남은 막대가 줄어든다. 감속 신호 지점은 점선 눈금 + "감속 신호".
 */
class GaugeView(ctx: Context) : View(ctx) {
    var gauge: CamCard.Gauge? = null
        set(v) { field = v; invalidate() }
    var limit: Int? = null
        set(v) { field = v; invalidate() }
    var display: Typeface = Typeface.DEFAULT_BOLD
    private val d = ctx.resources.displayMetrics.density
    private fun dp(v: Float) = v * d
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)

    override fun onMeasure(w: Int, h: Int) = setMeasuredDimension(MeasureSpec.getSize(w), dp(44f).toInt())

    override fun onDraw(c: Canvas) {
        val g = gauge ?: return
        val w = width.toFloat(); val cy = height - dp(16f)
        val signR = dp(15f)
        val x0 = dp(2f); val x1 = w - signR * 2 - dp(4f)
        val carX = x0 + (x1 - x0) * g.carFrac
        p.style = Paint.Style.FILL; p.pathEffect = null
        // 지나온 부분(흐린 흰색)
        p.color = 0x55FFFFFF; c.drawRoundRect(x0, cy - dp(4f), x1, cy + dp(4f), dp(4f), dp(4f), p)
        // 남은 막대
        p.color = g.remainColor; c.drawRoundRect(carX, cy - dp(4f), x1 + dp(4f), cy + dp(4f), dp(4f), dp(4f), p)
        // 감속 신호 눈금
        g.triggerFrac?.let { f ->
            val tx = x0 + (x1 - x0) * f
            p.color = Color.WHITE; p.style = Paint.Style.STROKE; p.strokeWidth = dp(2f)
            p.pathEffect = DashPathEffect(floatArrayOf(dp(3f), dp(3f)), 0f)
            c.drawLine(tx, cy - dp(12f), tx, cy + dp(12f), p)
            p.pathEffect = null; p.style = Paint.Style.FILL
            p.textSize = dp(11f); p.textAlign = Paint.Align.CENTER; p.typeface = Typeface.DEFAULT
            c.drawText("감속 신호", tx.coerceIn(dp(26f), x1 - dp(26f)), cy - dp(16f), p)
        }
        // 내 차
        p.color = Color.WHITE; c.drawCircle(carX, cy, dp(11f), p)
        p.color = 0xFF2F6FE4.toInt()
        val tri = Path().apply { moveTo(carX - dp(3.5f), cy - dp(5.5f)); lineTo(carX + dp(5.5f), cy); lineTo(carX - dp(3.5f), cy + dp(5.5f)); close() }
        c.drawPath(tri, p)
        // 카메라 제한 표지
        val sx = w - signR - dp(1f)
        p.color = CamCard.RED; c.drawCircle(sx, cy, signR, p)
        p.color = Color.WHITE; c.drawCircle(sx, cy, signR - dp(3.5f), p)
        p.color = 0xFF222222.toInt(); p.textSize = dp(12f); p.typeface = display; p.textAlign = Paint.Align.CENTER
        c.drawText(limit?.toString() ?: "", sx, cy + dp(4.5f), p)
    }
}

/**
 * 지도 판: 카카오맵(인터넷이 있고 지도 로드에 성공하면) 위에 경로선(남은 길 파랑, 지나온 길 회색)·카메라·감속 신호 점선·내 차를 그린다.
 * 키가 없거나, 시작 때 인터넷이 없거나, 지도 오류가 나면 기존 간이 지도(MiniMapView)로 바꾼다.
 * 지도 회전 단위(라디안/도·부호)는 SDK 버전에 따라 다를 수 있어, 그린 결과로 맞는지 확인해 자동으로 고른다.
 */
class MapPane(ctx: Context, private val theme: Theme, display: Typeface, private val log: (String) -> Unit) : FrameLayout(ctx) {
    val mini = MiniMapView(ctx).apply { this.theme = this@MapPane.theme; this.display = display; fillHeight = true }
    private var mapView: MapView? = null
    private var kmap: KakaoMap? = null
    private val overlay = Overlay(ctx)
    /** true면 카카오맵, false면 간이 지도. */
    var usingKakao = false
        private set
    /** 새 GPS 위치(새 객체)일 때만 지도를 옮긴다 — 화면을 1초마다·기록마다 다시 그릴 때는 옮기지 않는다. */
    var data: MapData? = null
        set(v) { if (v === field) return; field = v; push() }
    /** 경로선(위도, 경도 번갈아). */
    var route: DoubleArray? = null
        set(v) { if (v !== field) routeSplit = null; field = v; overlay.invalidate() }
    /** 경로선 위 내 위치(지나온 부분 회색). 앞으로만 나아가고, 새 경로면 처음부터. */
    private var routeSplit: RouteProgress.Split? = null
    var caption: String
        get() = mini.caption
        set(v) { mini.caption = v }
    /** 지도 위 위쪽 카드가 덮는 높이(px). 내 차를 그 아래 남은 영역의 아래쪽에 둔다. */
    var topInset = 0
    /** 지도 아래쪽을 덮는 판의 높이(px). 내 차는 카드와 판 사이 빈 곳의 가운데보다 조금 아래. */
    var bottomInset = 0
    private var zoom = ZOOM_DEFAULT

    init {
        addView(mini, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        tryKakao(ctx)
    }

    private fun online(ctx: Context): Boolean = try {
        val cm = ctx.getSystemService(ConnectivityManager::class.java)
        cm.getNetworkCapabilities(cm.activeNetwork)?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
    } catch (_: Exception) { false }

    private fun tryKakao(ctx: Context) {
        val key = BuildConfig.KAKAO_NATIVE_APP_KEY
        if (key.isBlank()) return
        if (!online(ctx)) { log("인터넷 없음 — 간이 지도"); return }
        try {
            if (!KakaoMapSdk.isInitialized()) KakaoMapSdk.init(ctx.applicationContext, key)
            val mv = MapView(ctx)
            mapView = mv
            addView(mv, 0, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
            mv.start(object : MapLifeCycleCallback() {
                override fun onMapDestroy() { kmap = null }
                override fun onMapError(e: Exception) { post { fallback("카카오맵 오류: ${e.javaClass.simpleName} ${e.message ?: ""}") } }
            }, object : KakaoMapReadyCallback() {
                override fun onMapReady(map: KakaoMap) {
                    post {
                        kmap = map
                        usingKakao = true
                        mini.visibility = View.GONE
                        if (overlay.parent == null) addView(overlay, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
                        push()
                    }
                }
                override fun getZoomLevel(): Int = ZOOM_DEFAULT
            })
        } catch (e: Throwable) {
            fallback("카카오맵 시작 실패: ${e.javaClass.simpleName}")
        }
    }

    private fun fallback(why: String) {
        log("$why — 간이 지도로 표시")
        usingKakao = false; kmap = null
        mapView?.let { runCatching { it.finish() }; removeView(it) }
        mapView = null
        removeView(overlay)
        mini.visibility = View.VISIBLE
        mini.data = data
    }

    fun resume() { runCatching { mapView?.resume() } }
    fun pause() { runCatching { mapView?.pause() } }
    fun destroy() { removeCallbacks(resumeFollow); removeCallbacks(redraw); runCatching { mapView?.finish() }; mapView = null; kmap = null }

    /** ◎ 버튼: 기본 확대로 내 위치에 다시 맞춘다. */
    /** ◎ 버튼: 손으로 움직인 것을 풀고 기본 확대로 내 위치에 다시 맞춘다. */
    fun recenter() { browseUntil = 0L; removeCallbacks(resumeFollow); zoom = ZOOM_DEFAULT; push() }

    // ---- 손으로 지도를 움직이면 따라가기를 잠시 멈춘다. 마지막 손길 5초 뒤(또는 ◎) 내 위치·기본 확대로 돌아간다.
    private var browseUntil = 0L
    private val browsing get() = SystemClock.elapsedRealtime() < browseUntil
    private val resumeFollow = Runnable { browseUntil = 0L; zoom = ZOOM_DEFAULT; push() }
    /** 둘러보는 동안 지도가 움직이면(밀기·관성) 위에 그린 경로·카메라를 따라 그린다. */
    private val redraw = object : Runnable {
        override fun run() { if (browsing) { overlay.invalidate(); postDelayed(this, 100) } }
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        if (usingKakao) {
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE, MotionEvent.ACTION_POINTER_DOWN, MotionEvent.ACTION_UP -> {
                    val wasBrowsing = browsing
                    browseUntil = SystemClock.elapsedRealtime() + BROWSE_MS
                    removeCallbacks(resumeFollow); postDelayed(resumeFollow, BROWSE_MS)
                    if (!wasBrowsing) { removeCallbacks(redraw); post(redraw) }
                }
            }
        }
        return super.dispatchTouchEvent(ev)
    }

    // 회전 값 후보: 진행 방향 c(도)에 대해 지도에 넘길 값. 그린 결과로 확인해 틀리면 다음 후보로.
    private val rotations: List<(Double) -> Double> = listOf(
        { c -> Math.toRadians(c) }, { c -> -Math.toRadians(c) }, { c -> c }, { c -> -c },
    )
    private val rotPrefs = ctx.getSharedPreferences("map", 0)
    /** 확인된 회전 방식(저장됨). 한 번 확인되면 더 바꾸지 않는다(주행 중 지도가 흔들리지 않게). */
    private var rotIdx = rotPrefs.getInt("rotIdx", 0)
    private var rotLocked = rotPrefs.getBoolean("rotLocked", false)
    private var rotMiss = 0
    private var rotHit = 0

    private fun push() {
        val d = data
        val m = kmap
        if (!usingKakao || m == null) { mini.data = d; return }
        if (d == null || (d.lat == 0.0 && d.lon == 0.0)) { overlay.invalidate(); return }
        // 운전자가 지도를 둘러보는 중이면 옮기지 않고 위 그림만 새 위치로
        if (browsing) { overlay.invalidate(); return }
        try {
            // 차를 카드 아래 남은 영역의 아래쪽(약 75%)에 둔다
            val h = height
            // 카드 아래 ~ 아래 판 위의 빈 곳에서 60% 높이(앞을 더 보이게)
            val free = (h - topInset - bottomInset).coerceAtLeast(h / 3)
            val carY = topInset + (free * 0.6f).toInt()
            val padTop = (2 * carY - h).coerceAtLeast(0)
            val padBottom = (h - 2 * carY).coerceAtLeast(0)
            m.setPadding(0, padTop, 0, padBottom)
            m.moveCamera(CameraUpdateFactory.newCenterPosition(LatLng.from(d.lat, d.lon), zoom))
            m.moveCamera(CameraUpdateFactory.rotateTo(rotations[rotIdx](d.courseDeg)))
            checkRotation(m, d)
        } catch (e: Throwable) {
            fallback("카카오맵 갱신 실패: ${e.javaClass.simpleName}")
            return
        }
        overlay.invalidate()
    }

    /** 앞 200 m 지점이 화면 위쪽(진행 방향이 위)에 그려지는지 본다. 3번 연속 30° 넘게 어긋나면 다음 회전 방식으로. */
    private fun checkRotation(m: KakaoMap, d: MapData) {
        // 북쪽·남쪽 근처로 달릴 때는 후보끼리 구별이 안 돼(±가 같아짐) 확인하지 않는다
        val c = ((d.courseDeg % 360) + 360) % 360
        if (rotLocked || c < 30 || c > 330 || c in 150.0..210.0) return
        postDelayed({
            val a = runCatching { m.toScreenPoint(LatLng.from(d.lat, d.lon)) }.getOrNull() ?: return@postDelayed
            val (la, lo) = Geo.offset(d.lat, d.lon, d.courseDeg, 200.0)
            val b = runCatching { m.toScreenPoint(LatLng.from(la, lo)) }.getOrNull() ?: return@postDelayed
            val dx = (b.x - a.x).toDouble(); val dy = (b.y - a.y).toDouble()
            if (hypot(dx, dy) < 5) return@postDelayed
            val err = Math.toDegrees(atan2(dx, -dy))
            if (abs(err) > 30) {
                rotHit = 0
                if (++rotMiss >= 3) { rotIdx = (rotIdx + 1) % rotations.size; rotMiss = 0; log("지도 회전 방식 조정 (${rotIdx + 1})") }
            } else {
                rotMiss = 0
                // 진행 방향이 서로 다른 3번 연속 맞으면 고정·저장
                if (++rotHit >= 3 && !rotLocked) {
                    rotLocked = true
                    rotPrefs.edit().putInt("rotIdx", rotIdx).putBoolean("rotLocked", true).apply()
                    log("지도 회전 방식 확인됨 (${rotIdx + 1})")
                }
            }
        }, 900)
    }

    /** 카카오맵 위 그림층. 위경도를 지도 화면 좌표로 바꿔 그린다. */
    private inner class Overlay(ctx: Context) : View(ctx) {
        private val dd = ctx.resources.displayMetrics.density
        private fun dp(v: Float) = v * dd
        private val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND }
        private val car = MiniMapView(ctx).apply { this.theme = this@MapPane.theme }

        override fun onDraw(c: Canvas) {
            val m = kmap ?: return
            val d = data ?: return
            fun pt(lat: Double, lon: Double): android.graphics.Point? = runCatching { m.toScreenPoint(LatLng.from(lat, lon)) }.getOrNull()
            // 경로선
            // 경로선: 내 위치에서 나눠 지나온 쪽은 회색, 남은 쪽은 파랑
            route?.let { r ->
                if (r.size >= 4) {
                    // 화면을 떠난 사이 3 km 넘게 갔으면 앞쪽 전체에서 다시 찾는다(앞으로만)
                    val from = routeSplit?.seg ?: 0
                    val sp = RouteProgress.locate(r, d.lat, d.lon, from) ?: RouteProgress.locate(r, d.lat, d.lon, from, Double.MAX_VALUE) ?: routeSplit
                    routeSplit = sp
                    fun build(from: Int, to: Int, head: RouteProgress.Split?, tail: RouteProgress.Split?): Path {
                        val path = Path(); var started = false
                        fun add(lat: Double, lon: Double) {
                            val s = pt(lat, lon) ?: return
                            if (!started) { path.moveTo(s.x.toFloat(), s.y.toFloat()); started = true } else path.lineTo(s.x.toFloat(), s.y.toFloat())
                        }
                        head?.let { add(it.lat, it.lon) }
                        for (k in from..to) add(r[2 * k], r[2 * k + 1])
                        tail?.let { add(it.lat, it.lon) }
                        return path
                    }
                    val last = r.size / 2 - 1
                    p.style = Paint.Style.STROKE; p.pathEffect = null
                    if (sp != null) {
                        val done = build(0, sp.seg, null, sp)
                        p.color = 0xFF8E949B.toInt(); p.strokeWidth = dp(11f); c.drawPath(done, p)
                        p.color = 0xFFB4B9BF.toInt(); p.strokeWidth = dp(8f); c.drawPath(done, p)
                    }
                    val left = if (sp != null) build(sp.seg + 1, last, sp, null) else build(0, last, null, null)
                    p.color = 0xFF1B4FC4.toInt(); p.strokeWidth = dp(11f); c.drawPath(left, p)
                    p.color = 0xFF2F6FE4.toInt(); p.strokeWidth = dp(8f); c.drawPath(left, p)
                }
            }
            val me = pt(d.lat, d.lon) ?: return
            val (ala, alo) = Geo.offset(d.lat, d.lon, d.courseDeg, 50.0)
            val ahead = pt(ala, alo)
            val ang = if (ahead != null && hypot((ahead.x - me.x).toDouble(), (ahead.y - me.y).toDouble()) > 3)
                atan2((ahead.x - me.x).toDouble(), -(ahead.y - me.y).toDouble()) else 0.0
            // 감속 신호 점선(진행 방향에 수직)
            d.triggerM?.takeIf { it > 0 }?.let { tm ->
                val (tla, tlo) = Geo.offset(d.lat, d.lon, d.courseDeg, tm.toDouble())
                pt(tla, tlo)?.let { t ->
                    val ux = cos(ang).toFloat(); val uy = sin(ang).toFloat()
                    p.style = Paint.Style.STROKE; p.color = 0xFF4A7BF0.toInt(); p.strokeWidth = dp(3f)
                    p.pathEffect = DashPathEffect(floatArrayOf(dp(9f), dp(7f)), 0f)
                    c.drawLine(t.x - ux * dp(70f), t.y - uy * dp(70f), t.x + ux * dp(70f), t.y + uy * dp(70f), p)
                    p.pathEffect = null; p.style = Paint.Style.FILL; p.textSize = dp(14f); p.typeface = Typeface.DEFAULT_BOLD; p.textAlign = Paint.Align.LEFT
                    c.drawText("감속 신호", t.x + ux * dp(76f), t.y + uy * dp(76f) + dp(5f), p)
                }
            }
            // 카메라
            for (cam in d.cams) {
                if (cam.lat == 0.0 && cam.lon == 0.0) continue
                val s = pt(cam.lat, cam.lon) ?: continue
                val x = s.x.toFloat(); val y = s.y.toFloat()
                if (x < -dp(30f) || y < -dp(30f) || x > width + dp(30f) || y > height + dp(30f)) continue
                val mine = cam.state >= 1
                p.style = Paint.Style.FILL
                p.color = if (mine) 0x55E5484D else 0x33999999; c.drawCircle(x, y, dp(17f), p)
                p.color = Color.WHITE; c.drawCircle(x, y, dp(12f), p)
                p.color = if (mine) CamCard.RED else 0xFFAAAAAA.toInt(); c.drawCircle(x, y, dp(9f), p)
                p.color = 0xFF3A2222.toInt(); c.drawCircle(x, y, dp(4f), p)
                p.color = if (mine) CamCard.RED else 0xFF999999.toInt(); p.textSize = dp(17f); p.typeface = Typeface.DEFAULT_BOLD; p.textAlign = Paint.Align.LEFT
                c.drawText("${cam.limitKmh}", x + dp(20f), y + dp(6f), p)
            }
            // 내 차
            c.save(); c.rotate(Math.toDegrees(ang).toFloat(), me.x.toFloat(), me.y.toFloat())
            car.drawCar(c, me.x.toFloat(), me.y.toFloat(), theme)
            c.restore()
        }
    }

    companion object {
        const val ZOOM_DEFAULT = 16
        /** 손으로 움직인 뒤 따라가기로 돌아가기까지(ms). */
        const val BROWSE_MS = 5_000L
    }
}
