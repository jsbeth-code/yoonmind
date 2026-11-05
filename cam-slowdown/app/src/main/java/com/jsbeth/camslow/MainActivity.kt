package com.jsbeth.camslow

import android.Manifest
import androidx.appcompat.app.AppCompatActivity
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Shader
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.ShapeDrawable
import android.graphics.drawable.shapes.RectShape
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.text.SpannableStringBuilder
import android.text.TextUtils
import android.text.style.ForegroundColorSpan
import android.text.style.RelativeSizeSpan
import android.transition.ChangeBounds
import android.transition.TransitionManager
import android.view.Gravity
import android.view.MotionEvent
import android.view.ViewConfiguration
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import android.app.Dialog
import com.kakaomobility.knsdk.ui.view.KNNaviView

/**
 * 메인 화면(v0.10): 지도 전체 화면(카카오맵, 안 되면 간이 지도) 위에 떠 있는 카드.
 *  - 위: 회전 카드(남색, 경로가 있을 때만) → 카메라 카드(상태·거리·속도·제한 표지·게이지·아래 짙은 줄, 카카오 끊김이면 줄무늬).
 *  - 오른쪽: ⚙ 설정 · ◎ 현재 위치.
 *  - 아래 판: 접힘 = 목적지 줄 + "평소 → 카메라 앞 ▾"(누르면 작은 창에서 바꿈) + ◎ + 작은 끄기. 위로 쓸면 펼침, 아래로 쓸면 접힘. 펼침 = 프로필 칩, 감시 끄기, 점검·기록 접기 상자, 도구.
 *  - 폴드처럼 넓으면(600dp 이상) 지도는 왼쪽, 오른쪽에 카드와 아래 판 내용을 세로로.
 */
/** v0.11.3: 카카오내비 화면(KNNaviView)이 AppCompatActivity를 요구해서 바꿈(테마도 AppCompat). */
class MainActivity : AppCompatActivity() {

    private lateinit var t: Theme
    private lateinit var jua: Typeface
    private lateinit var outputs: Outputs
    private lateinit var mapPane: MapPane
    // v0.11 카카오내비 화면(감시 중·카카오 연결 때). 없으면 기존 지도(mapPane).
    private var naviView: KNNaviView? = null
    private var naviFailed = false
    private var naviFailedAt = 0L
    /** 직접 다시 붙이기: 감속·구간 유지 중에도 미루지 않는다. */
    private var naviForce = false
    private var naviMissingSince = 0L
    private var naviMissingSaid = false
    private lateinit var mapFrame: FrameLayout
    private lateinit var cardsBox: LinearLayout
    private var panelView: View? = null
    private var recenterChip: View? = null
    private var panelH = 0
    private var resumed = false
    private var routeDialog: Dialog? = null
    private var routeDialogInfo: TextView? = null
    private var routeChooser: RouteChooser? = null
    // 카카오 화면일 때 쓰는 작은 알림(필요할 때만): 거리·제한·감속 상태
    private lateinit var pill: LinearLayout
    private lateinit var pillDist: TextView
    private lateinit var pillSign: TextView
    private lateinit var pillSub: TextView
    // 회전 카드
    private lateinit var turnCard: LinearLayout
    private lateinit var turnArrow: TextView
    private lateinit var turnDist: TextView
    private lateinit var turnLine: TextView
    private lateinit var turnNext: TextView
    // 카메라 카드
    private lateinit var camCard: LinearLayout
    private lateinit var camTop: LinearLayout
    private lateinit var statusView: TextView
    private lateinit var distView: TextView
    private lateinit var lineView: TextView
    private lateinit var speedView: TextView
    private lateinit var speedLabel: TextView
    private lateinit var signView: TextView
    private lateinit var gaugeView: GaugeView
    private lateinit var bottomView: TextView
    private lateinit var stripeView: TextView
    // 아래 판
    private lateinit var sheet: LinearLayout
    private lateinit var destRow: LinearLayout
    private lateinit var destName: TextView
    private lateinit var destSub: TextView
    private lateinit var destEta: TextView
    private lateinit var destCheck: TextView
    private lateinit var collapsedRow: LinearLayout
    private lateinit var profileSummary: TextView
    private lateinit var smallToggle: TextView
    private lateinit var expanded: LinearLayout
    private lateinit var usualChip: TextView
    private lateinit var slowChip: TextView
    private lateinit var releaseBtn: TextView
    private lateinit var toggleBtn: TextView
    private lateinit var nowView: TextView
    private lateinit var checkHead: TextView
    private lateinit var checkView: TextView
    private lateinit var logHead: TextView
    private lateinit var logView: TextView
    private var wide = false
    private var bigScreen = false
    private var sheetOpen = false
    private var logExpanded = false
    private var checkExpanded = false
    private var builtNight = false
    private val listener = { render() }
    private val main = Handler(Looper.getMainLooper())
    /** 과속 경고 중 0.5초마다 깜빡임. */
    private var blinkOn = false
    private var blinkPosted = false
    private val blinkRunnable = Runnable {
        blinkPosted = false
        if (DriveState.running && DriveState.overspeedKmh != null) { blinkOn = !blinkOn; render() }
    }
    private val tick = object : Runnable { override fun run() { render(); main.postDelayed(this, 1_000) } }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    private fun dpf(v: Float) = v * resources.displayMetrics.density

    override fun onCreate(savedInstanceState: Bundle?) {
        if (Theme.current(this).night) setTheme(androidx.appcompat.R.style.Theme_AppCompat_NoActionBar) else setTheme(androidx.appcompat.R.style.Theme_AppCompat_Light_NoActionBar)
        super.onCreate(savedInstanceState)
        outputs = Outputs(this)
        build()
        askPermissions()
        // 다시 만들 때(테마 변경 등)는 처음 받은 인텐트를 또 처리하지 않는다(딥링크 확인창·자동 시작 반복 방지)
        if (savedInstanceState == null) handleIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        if (intent?.getBooleanExtra(AutoStart.EXTRA_AUTOSTART, false) == true) startDrive()
        // 테슬라 로그인 뒤 fsdslow://tesla?session=…
        val session = TeslaLink.sessionFrom(intent?.data) ?: return
        val done = {
            TeslaLink.accept(this, session)
            Toast.makeText(this, "테슬라 연결됨", Toast.LENGTH_LONG).show()
            if (DriveState.running && !DriveState.simulating) DriveService.send(this, DriveService.ACTION_TESLA_CHECK)
            startActivity(Intent(this, SettingsActivity::class.java))
        }
        // 이 앱에서 "테슬라 연결"을 누른 직후가 아니면 묻는다(다른 앱·웹페이지가 남의 세션을 심는 것을 막기 위해)
        if (TeslaLink.loginRecent(this)) done()
        else AlertDialog.Builder(this)
            .setTitle("테슬라 연결 링크")
            .setMessage("테슬라 연결 링크를 받았습니다(세션 ${TeslaDest.mask(session)}).\n설정 ⑨에서 직접 '테슬라 연결'을 누른 게 아니라면 취소하세요.")
            .setPositiveButton("연결") { _, _ -> done() }
            .setNegativeButton("취소", null).show()
    }

    // ---- 화면 만들기 (코드로)
    private fun rounded(color: Int, r: Float, stroke: Int? = null) = GradientDrawable().apply {
        cornerRadius = dpf(r); setColor(color); if (stroke != null) setStroke(dp(1), stroke)
    }

    private fun tv(size: Float, color: Int, bold: Boolean = false, font: Typeface? = null) = TextView(this).apply {
        textSize = size; setTextColor(color); includeFontPadding = false
        typeface = font ?: if (bold) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
    }

    private fun build() {
        t = Theme.current(this)
        builtNight = t.night
        jua = Theme.display(this)
        window.setBackgroundDrawable(ColorDrawable(t.bg))
        // v0.13: 폴더를 펼쳐도(큰 화면) 접었을 때와 같은 방식 — 지도 전체, 오른쪽 아래 한 줄 판(쓸어 올리면 메뉴)
        bigScreen = resources.configuration.screenWidthDp >= 600
        wide = false
        val cards = buildCards()
        val panel = buildPanel()
        mapPane = MapPane(this, t, jua) { DriveState.log(it) }

        mapFrame = FrameLayout(this)
        cardsBox = cards
        mapFrame.addView(mapPane, FrameLayout.LayoutParams(-1, -1))
        pill = buildPill()
        // v0.13: 오른쪽 가운데 — 카카오 회전 안내·교차로 그림(위), 속도·제한 표지(왼쪽), 차로 안내·아래 막대(아래)를 피함
        mapFrame.addView(pill, FrameLayout.LayoutParams(-2, -2, Gravity.CENTER_VERTICAL or Gravity.END).apply { rightMargin = dp(10); topMargin = dp(40) })
        if (wide) {
            val right = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(12), dp(12), dp(12), dp(12)) }
            right.addView(cards)
            right.addView(panel, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10) })
            sheetOpen = true
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setBackgroundColor(t.bg) }
            row.addView(mapFrame, LinearLayout.LayoutParams(0, -1, 1.4f))
            row.addView(ScrollView(this).apply { addView(right) }, LinearLayout.LayoutParams(0, -1, 1f))
            setContentView(row)
        } else {
            val root = FrameLayout(this).apply { setBackgroundColor(t.bg) }
            root.addView(mapFrame, FrameLayout.LayoutParams(-1, -1))
            root.addView(cards, FrameLayout.LayoutParams(-1, -2, Gravity.TOP or Gravity.END).apply { leftMargin = dp(10); rightMargin = dp(10); topMargin = dp(10) })
            root.addView(panel, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM or Gravity.END))
            panelView = panel
            setContentView(root)
            applyScreenLayout()
            // 카드가 덮는 높이만큼 지도 중심을 아래로
            cards.addOnLayoutChangeListener { v, _, _, _, _, _, _, _, _ -> mapPane.topInset = v.bottom }
            panel.addOnLayoutChangeListener { v, _, _, _, _, _, _, _, _ ->
                if (!sheetOpen) { mapPane.bottomInset = v.height; if (panelH != v.height) { panelH = v.height; if (naviView != null) main.post { layoutForNavi(true) } } }
            }
        }
        render()
    }

    private fun buildCards(): LinearLayout {
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        // 회전 카드
        turnCard = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(10), dp(12), dp(10)); background = rounded(CamCard.NAVY, 18f); elevation = dpf(6f)
        }
        turnArrow = tv(40f, Color.WHITE, bold = true).apply { gravity = Gravity.CENTER }
        turnCard.addView(turnArrow, LinearLayout.LayoutParams(dp(52), -2))
        val tcol = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(8), 0, dp(8), 0) }
        turnDist = tv(30f, Color.WHITE, font = jua)
        turnLine = tv(14f, 0xFFDDE3F5.toInt()).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END; setPadding(0, dp(4), 0, 0) }
        tcol.addView(turnDist); tcol.addView(turnLine)
        turnCard.addView(tcol, LinearLayout.LayoutParams(0, -2, 1f))
        turnNext = tv(13f, Color.WHITE).apply {
            gravity = Gravity.CENTER; setPadding(dp(10), dp(6), dp(10), dp(6)); background = rounded(0xFF2E3A5C.toInt(), 12f)
        }
        turnCard.addView(turnNext)
        col.addView(turnCard, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(8) })

        // 카메라 카드
        camCard = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; elevation = dpf(6f); clipToOutline = true }
        camTop = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(10), dp(14), dp(6)) }
        statusView = tv(14f, Color.WHITE, font = jua)
        camTop.addView(statusView)
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val left = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        distView = tv(40f, Color.WHITE, font = jua).apply { setPadding(0, dp(4), 0, dp(2)) }
        lineView = tv(14f, Color.WHITE).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END }
        left.addView(distView); left.addView(lineView)
        row.addView(left, LinearLayout.LayoutParams(0, -2, 1f))
        val mid = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL; setPadding(dp(6), 0, dp(10), 0) }
        speedView = tv(36f, Color.WHITE, font = jua).apply { gravity = Gravity.CENTER }
        speedLabel = tv(11f, 0xDDFFFFFF.toInt()).apply { text = "km/h"; gravity = Gravity.CENTER }
        mid.addView(speedView); mid.addView(speedLabel)
        row.addView(mid)
        signView = tv(22f, 0xFF222222.toInt(), font = jua).apply {
            gravity = Gravity.CENTER
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(Color.WHITE); setStroke(dp(6), CamCard.RED) }
        }
        row.addView(signView, LinearLayout.LayoutParams(dp(58), dp(58)))
        camTop.addView(row)
        gaugeView = GaugeView(this).apply { display = jua }
        camTop.addView(gaugeView, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(2) })
        camCard.addView(camTop)
        bottomView = tv(14f, Color.WHITE).apply { setPadding(dp(16), dp(9), dp(16), dp(10)); maxLines = 1; ellipsize = TextUtils.TruncateAt.END }
        camCard.addView(bottomView, LinearLayout.LayoutParams(-1, -2))
        // 카카오 끊김(B안): 맨 아래 줄무늬
        stripeView = tv(13.5f, 0xFF6A3A10.toInt(), bold = true).apply {
            text = "⚠ 카카오 없음 — 옆길·반대편 카메라 오탐 가능"
            setPadding(dp(16), dp(8), dp(16), dp(9)); background = stripes()
        }
        camCard.addView(stripeView, LinearLayout.LayoutParams(-1, -2))
        col.addView(camCard, LinearLayout.LayoutParams(-1, -2))
        return col
    }

    /** 카카오 화면 위 작은 알림: [제한 표지] 296 m / ▼ 스탠다드로 내리는 중. 카메라 일이 있을 때만 보인다. */
    private fun buildPill(): LinearLayout {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(10), dp(8), dp(16), dp(8)); elevation = dpf(8f); visibility = View.GONE
        }
        pillSign = tv(17f, 0xFF222222.toInt(), font = jua).apply {
            gravity = Gravity.CENTER
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(Color.WHITE); setStroke(dp(4), CamCard.RED) }
        }
        row.addView(pillSign, LinearLayout.LayoutParams(dp(38), dp(38)).apply { rightMargin = dp(8) })
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        pillDist = tv(22f, Color.WHITE, font = jua)
        pillSub = tv(12f, Color.WHITE).apply { maxLines = 2; ellipsize = TextUtils.TruncateAt.END; maxWidth = dp(190) }
        col.addView(pillDist); col.addView(pillSub)
        row.addView(col)
        return row
    }

    /** 주황·연노랑 사선 줄무늬 배경. */
    private fun stripes(): ShapeDrawable {
        val n = dp(14)
        val bmp = Bitmap.createBitmap(n, n, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        c.drawColor(0xFFFFF1DE.toInt())
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFDD9AE.toInt(); strokeWidth = n / 3f }
        c.drawLine(-n.toFloat(), n * 2f, n * 2f, -n.toFloat(), p)
        c.drawLine(0f, n * 2f, n * 2f, 0f, p); c.drawLine(-n.toFloat(), n.toFloat(), n.toFloat(), -n.toFloat(), p)
        return ShapeDrawable(RectShape()).apply { paint.shader = BitmapShader(bmp, Shader.TileMode.REPEAT, Shader.TileMode.REPEAT) }
    }

    private fun buildPanel(): LinearLayout {
        sheet = (if (wide) LinearLayout(this) else SwipeSheet()).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(6), dp(14), dp(12))
            background = if (wide) rounded(t.chipBg, 18f, t.line) else GradientDrawable().apply {
                setColor(t.bg); cornerRadii = floatArrayOf(dpf(22f), dpf(22f), dpf(22f), dpf(22f), 0f, 0f, 0f, 0f)
            }
            elevation = dpf(10f)
        }
        if (!wide) {
            // 손잡이(누르면 펼침/접힘)
            val handle = FrameLayout(this).apply { setPadding(0, dp(4), 0, dp(8)); setOnClickListener { setSheet(!sheetOpen) } }
            handle.addView(View(this).apply { background = rounded(t.line, 3f) }, FrameLayout.LayoutParams(dp(44), dp(5), Gravity.CENTER))
            sheet.addView(handle, LinearLayout.LayoutParams(-1, dp(20)))
        }
        // 목적지 줄
        destRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(0, dp(2), 0, dp(8)) }
        val tIcon = tv(22f, 0xFFE31937.toInt(), font = jua).apply { text = "T"; gravity = Gravity.CENTER; background = rounded(0xFF17171A.toInt(), 10f) }
        destRow.addView(tIcon, LinearLayout.LayoutParams(dp(40), dp(40)))
        val dcol = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(12), 0, dp(8), 0) }
        destName = tv(17f, t.fg, font = jua).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END }
        destSub = tv(12.5f, t.muted).apply { setPadding(0, dp(3), 0, 0); maxLines = 1; ellipsize = TextUtils.TruncateAt.END }
        dcol.addView(destName); dcol.addView(destSub)
        destRow.addView(dcol, LinearLayout.LayoutParams(0, -2, 1f))
        destEta = tv(13f, t.muted).apply { gravity = Gravity.END }
        destRow.addView(destEta)
        // 목적지 지금 확인(테슬라 내비에 찍은 직후 2분을 기다리지 않게)
        destCheck = tv(13f, t.fg, font = jua).apply {
            // v0.12.8: 자주 써서 접힌 줄에도 — 작은 동그라미 ↻
            text = "↻"; textSize = 19f; gravity = Gravity.CENTER
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(t.chipBg); setStroke(dp(1), t.chipLine) }
            setOnClickListener { checkDestNow() }
        }
        destRow.addView(destCheck, LinearLayout.LayoutParams(dp(40), dp(40)).apply { leftMargin = dp(8) })
        // v0.12.1: 접힌 판은 한 줄 — 목적지·상태 점·도착 시각·[끄기]
        smallToggle = tv(15f, Color.WHITE, font = jua).apply {
            setPadding(dp(16), dp(10), dp(16), dp(10)); background = rounded(t.toggleOff, 14f)
            setOnClickListener { toggleDrive() }
        }
        destRow.addView(smallToggle, LinearLayout.LayoutParams(-2, -2).apply { leftMargin = dp(8) })
        destRow.setOnClickListener { if (!wide) setSheet(!sheetOpen) }
        sheet.addView(destRow)

        // 접힘 줄: "Mad Max → 컴포트 ▴" + 작은 끄기
        collapsedRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        profileSummary = tv(15f, t.fg, font = jua).apply {
            setPadding(dp(14), dp(10), dp(14), dp(10)); background = rounded(t.chipBg, 12f, t.chipLine)
            setOnClickListener { showProfilePicker() }
        }
        collapsedRow.addView(profileSummary)
        collapsedRow.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f))
        // 접혀 있을 때도 지도 내 위치로
        recenterChip = tv(17f, t.fg, font = jua).apply {
            text = "◎"; gravity = Gravity.CENTER; setPadding(dp(14), dp(8), dp(14), dp(8))
            background = rounded(t.chipBg, 12f, t.chipLine); setOnClickListener { recenterMap() }
        }
        collapsedRow.addView(recenterChip, LinearLayout.LayoutParams(-2, -2).apply { rightMargin = dp(8) })
        sheet.addView(collapsedRow)

        // 펼침 내용
        expanded = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val prof = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(0, dp(6), 0, dp(2)) }
        usualChip = chip { pickProfile(usual = true) }
        slowChip = chip { pickProfile(usual = false) }
        prof.addView(usualChip, LinearLayout.LayoutParams(0, -2, 1f))
        prof.addView(tv(20f, t.muted).apply { text = "→"; setPadding(dp(8), 0, dp(8), 0) })
        prof.addView(slowChip, LinearLayout.LayoutParams(0, -2, 1f))
        expanded.addView(prof)
        releaseBtn = bigButton(t.bandProblem) { DriveService.send(this, DriveService.ACTION_RELEASE) }
        expanded.addView(releaseBtn, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
        toggleBtn = bigButton(t.toggleOn) { toggleDrive() }
        expanded.addView(toggleBtn, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
        nowView = tv(12f, t.muted).apply { setPadding(dp(2), dp(6), dp(2), dp(2)) }
        expanded.addView(nowView)

        // 점검·기록: 평소엔 한 줄로 접어 두고 누르면 펼친다
        fun foldBox(): LinearLayout = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; background = rounded(t.chipBg, 12f, t.chipLine) }
        val headStyle: TextView.() -> Unit = { textSize = 13f; setTextColor(t.fg); setPadding(dp(12), dp(10), dp(12), dp(10)); maxLines = 1; ellipsize = TextUtils.TruncateAt.END }
        val checkBox = foldBox()
        checkHead = TextView(this).apply { headStyle(); setOnClickListener { checkExpanded = !checkExpanded; render() } }
        checkView = TextView(this).apply { textSize = 12.5f; setTextColor(t.fg); setPadding(dp(12), 0, dp(12), dp(10)); setLineSpacing(0f, 1.25f) }
        checkBox.addView(checkHead); checkBox.addView(checkView)
        expanded.addView(checkBox, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
        val logBox = foldBox()
        logHead = TextView(this).apply { headStyle(); setOnClickListener { logExpanded = !logExpanded; render() } }
        logView = TextView(this).apply { textSize = 12f; setTextColor(t.fg); setPadding(dp(12), 0, dp(12), dp(10)) }
        logBox.addView(logHead); logBox.addView(logView)
        expanded.addView(logBox, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
        // 데이터·테스트 도구(예전 ⋮ 메뉴)
        // 설정·지도 맞추기(예전엔 지도 위 떠 있는 버튼) — 아래 판을 펼쳤을 때만 보인다
        val btnRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, dp(8), 0, 0) }
        fun smallBtn(label: String, onClick: () -> Unit) = tv(14f, t.fg, font = jua).apply {
            text = label; gravity = Gravity.CENTER; setPadding(dp(10), dp(11), dp(10), dp(11))
            background = rounded(t.chipBg, 12f, t.chipLine); setOnClickListener { onClick() }
        }
        btnRow.addView(smallBtn("⚙ 설정") { startActivity(Intent(this, SettingsActivity::class.java)) }, LinearLayout.LayoutParams(0, -2, 1f).apply { rightMargin = dp(8) })
        btnRow.addView(smallBtn("◎ 지도 내 위치로") { recenterMap() },
            LinearLayout.LayoutParams(0, -2, 1f))
        expanded.addView(btnRow)
        // v0.13.4: 카카오 화면이 멈추거나(간이 지도로 바뀜) 이상할 때 바로 다시 붙인다
        expanded.addView(smallBtn("↻ 카카오 화면 다시 붙이기") { reattachNavi() }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
        val tools = tv(13f, t.muted).apply { text = "데이터·테스트 도구 ⋮"; setPadding(dp(4), dp(12), dp(4), dp(4)) }
        tools.setOnClickListener { showTools(it) }
        expanded.addView(tools)
        if (wide) sheet.addView(expanded) else {
            // 펼친 내용이 길면(기록 펼침) 화면의 60%까지만, 나머지는 스크롤
            val sv = object : ScrollView(this) {
                override fun onMeasure(w: Int, h: Int) {
                    val max = (resources.displayMetrics.heightPixels * 0.6f).toInt()
                    super.onMeasure(w, MeasureSpec.makeMeasureSpec(max, MeasureSpec.AT_MOST))
                }
            }
            sv.addView(expanded)
            sheet.addView(sv)
            (sheet as SwipeSheet).scroller = sv
        }
        return sheet
    }

    private fun recenterMap() {
        if (naviView != null) { Toast.makeText(this, "카카오 화면: 지도를 손으로 움직이면 아래에 [현위치로] 버튼이 뜹니다", Toast.LENGTH_SHORT).show(); return }
        mapPane.recenter()
        Toast.makeText(this, if (mapPane.usingKakao) "지도를 내 위치·기본 확대로 맞췄습니다 (손으로 움직여도 5초 뒤 저절로 돌아옵니다)" else "간이 지도는 늘 내 위치 기준입니다", Toast.LENGTH_SHORT).show()
    }

    /** 아래 판 펼침/접힘을 스르륵(높이 변화 애니메이션). */
    private fun setSheet(open: Boolean) {
        if (wide || open == sheetOpen) return
        sheet.animate().cancel(); sheet.translationY = 0f
        (sheet.parent as? ViewGroup)?.let { TransitionManager.beginDelayedTransition(it, ChangeBounds().setDuration(160)) }
        sheetOpen = open
        render()
    }

    /**
     * 손가락을 따라 움직이는 아래 판(v0.10.15). 위로 끌면 펼친 판이 아래에서 따라 올라오고, 아래로 끌면 따라 내려간다.
     * 놓을 때 30% 넘게 끌었거나 그 방향으로 튕기면 끝까지, 아니면 제자리로 돌아간다.
     * 펼친 높이로 바꾼 뒤 translationY로 아래로 밀어 두고 손가락만큼 줄인다(판은 화면 아래에 붙어 있음).
     * 펼친 상태에서 안쪽 목록이 스크롤돼 있으면(맨 위가 아니면) 목록 스크롤이 먼저.
     */
    private inner class SwipeSheet : LinearLayout(this@MainActivity) {
        var scroller: View? = null
        private val slop = ViewConfiguration.get(this@MainActivity).scaledTouchSlop
        private val flingV = dpf(400f) // px/s
        private var downX = 0f; private var downY = 0f; private var inScroller = false
        private var dragging = false; private var opening = false
        /** 펼친 높이 − 접힌 높이. 펼치며 끌 때는 레이아웃이 끝나야 안다(0 = 아직 모름). */
        private var delta = 0f
        private var lastDy = 0f
        /** 접으며 끌기 시작할 때의 위치(접히는 애니메이션 도중에 다시 잡으면 그 자리부터). */
        private var dragBase = 0f
        private var lastY = 0f; private var lastT = 0L; private var vy = 0f
        private var closedH = 0
        private var resetOnLayout = false

        init {
            addOnLayoutChangeListener { _, _, top, _, bottom, _, oTop, _, oBottom ->
                val h = bottom - top
                if (!sheetOpen) closedH = h
                if (dragging && opening && delta == 0f && sheetOpen && h > closedH && closedH > 0) {
                    delta = (h - closedH).toFloat()
                    translationY = (delta + lastDy).coerceIn(0f, delta)
                }
                if (resetOnLayout && h != oBottom - oTop) { resetOnLayout = false; translationY = 0f }
            }
        }

        private fun wantDrag(e: MotionEvent): Boolean {
            val dy = e.rawY - downY; val dx = e.rawX - downX
            if (kotlin.math.abs(dy) < slop || kotlin.math.abs(dy) < kotlin.math.abs(dx) * 1.5f) return false
            return if (dy < 0) !sheetOpen
                else sheetOpen && !(inScroller && scroller?.canScrollVertically(-1) == true)
        }

        private fun track(e: MotionEvent) {
            val t = e.eventTime
            if (t > lastT) vy = vy * 0.3f + 0.7f * (e.rawY - lastY) * 1000f / (t - lastT)
            lastY = e.rawY; lastT = t
        }

        private fun startDrag() {
            animate().cancel()
            dragging = true
            opening = !sheetOpen
            lastDy = 0f
            if (opening) {
                delta = 0f
                sheetOpen = true
                render() // 펼친 높이로 — 레이아웃이 끝나면 delta만큼 아래로 밀어 둔다(접힌 모습 그대로 시작)
            } else {
                delta = (height - closedH).toFloat().coerceAtLeast(0f)
                dragBase = translationY.coerceIn(0f, delta)
            }
        }

        private fun move(dy: Float) {
            lastDy = dy
            if (delta <= 0f) return
            translationY = if (opening) (delta + dy).coerceIn(0f, delta) else (dragBase + dy).coerceIn(0f, delta)
        }

        private fun finish() {
            dragging = false
            if (delta <= 0f) { translationY = 0f; return }
            val open = when {
                vy < -flingV -> true
                vy > flingV -> false
                else -> translationY < delta * (if (opening) 0.7f else 0.3f)
            }
            val dur = 160L
            if (open) animate().translationY(0f).setDuration(dur).start()
            else animate().translationY(delta).setDuration(dur).withEndAction {
                resetOnLayout = true
                sheetOpen = false
                render()
                post { if (resetOnLayout) { resetOnLayout = false; translationY = 0f } }
            }.start()
        }

        override fun onInterceptTouchEvent(e: MotionEvent): Boolean {
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = e.rawX; downY = e.rawY; dragging = false
                    lastY = e.rawY; lastT = e.eventTime; vy = 0f
                    inScroller = scroller?.let { e.y >= it.top && e.y < it.bottom } == true
                }
                MotionEvent.ACTION_MOVE -> { track(e); if (wantDrag(e)) { startDrag(); move(e.rawY - downY); return true } }
            }
            return false
        }

        override fun onTouchEvent(e: MotionEvent): Boolean {
            when (e.actionMasked) {
                MotionEvent.ACTION_MOVE -> {
                    track(e)
                    if (!dragging && wantDrag(e)) startDrag()
                    if (dragging) move(e.rawY - downY)
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> if (dragging) finish()
            }
            return true
        }
    }

    /** 접힌 판의 "평소 → 카메라 앞" 칩: 판을 펼치지 않고 작은 창에서 두 프로필을 바꾼다. */
    private fun showProfilePicker() {
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(dp(18), dp(12), dp(18), dp(4)) }
        lateinit var u: TextView
        lateinit var s: TextView
        fun refresh() {
            chipText(u, "평소", Prefs.usualProfile(this).label, false)
            chipText(s, "카메라 앞", Prefs.slowProfile(this).label, false)
        }
        u = chip { pickProfile(usual = true) { refresh() } }
        s = chip { pickProfile(usual = false) { refresh() } }
        row.addView(u, LinearLayout.LayoutParams(0, -2, 1f))
        row.addView(tv(20f, t.muted).apply { text = "→"; setPadding(dp(8), 0, dp(8), 0) })
        row.addView(s, LinearLayout.LayoutParams(0, -2, 1f))
        refresh()
        AlertDialog.Builder(this).setTitle("프로필").setView(row).setPositiveButton("닫기", null).show()
    }

    /** 테슬라 목적지를 지금 확인. 감시 중이면 서비스가 확인·적용(결과는 기록), 아니면 확인만 하고 결과를 토스트로. */
    private fun checkDestNow() {
        when {
            !TeslaLink.connected(this) -> { Toast.makeText(this, "테슬라 연결이 필요합니다 — 설정 ⑨", Toast.LENGTH_LONG).show(); startActivity(Intent(this, SettingsActivity::class.java)); return }
            !Prefs.teslaFollow(this) -> { Toast.makeText(this, "설정 ⑨ '목적지 따라가기'가 꺼져 있습니다", Toast.LENGTH_LONG).show(); startActivity(Intent(this, SettingsActivity::class.java)); return }
        }
        Toast.makeText(this, "테슬라 목적지 확인 중…", Toast.LENGTH_SHORT).show()
        if (DriveState.running && !DriveState.simulating) { DriveService.send(this, DriveService.ACTION_TESLA_CHECK); return }
        TeslaLink.fetch(this) { r ->
            val msg = when (r) {
                is TeslaDest.Reply.Offline -> "차가 자는 중 — 차를 깨운 뒤 다시"
                is TeslaDest.Reply.Online -> r.dest?.let { "목적지: ${TeslaDest.label(it)} (감시를 켜면 경로 안내)" } ?: "차는 깨어 있지만 내비 목적지 없음"
                is TeslaDest.Reply.Error -> "확인 실패: ${r.message}"
            }
            DriveState.log("테슬라 목적지 확인: $msg")
            Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
            render()
        }
    }

    private fun toggleDrive() {
        if (DriveState.running) DriveService.send(this, DriveService.ACTION_STOP) else startDrive()
    }

    private fun bigButton(color: Int, onClick: () -> Unit) = TextView(this).apply {
        textSize = 19f; typeface = jua; setTextColor(Color.WHITE); gravity = Gravity.CENTER
        setPadding(dp(16), dp(13), dp(16), dp(13))
        background = GradientDrawable().apply { cornerRadius = dpf(14f); setColor(color) }
        setOnClickListener { onClick() }
    }

    private fun chip(onClick: () -> Unit) = TextView(this).apply {
        textSize = 15f; typeface = jua; setTextColor(t.fg); gravity = Gravity.CENTER; setLineSpacing(0f, 1.1f)
        setPadding(dp(8), dp(6), dp(8), dp(6))
        background = GradientDrawable().apply { cornerRadius = dpf(10f); setColor(t.chipBg); setStroke(dp(2), t.chipLine) }
        setOnClickListener { onClick() }
    }

    private fun chipText(v: TextView, label: String, value: String, highlight: Boolean) {
        v.text = SpannableStringBuilder().apply {
            append(label); setSpan(RelativeSizeSpan(0.62f), 0, label.length, 0); setSpan(ForegroundColorSpan(t.muted), 0, label.length, 0)
            append("\n").append("$value ▾")
        }
        (v.background as GradientDrawable).setStroke(dp(2), if (highlight) t.bandSlow else t.chipLine)
    }

    /**
     * 프로필 고르기. 설정 1·2번과 같은 값. 평소 프로필은 감시 중이어도 바로 적용된다(서비스가 설정을 다시 읽음).
     * 감속 중이 아니면 차가 새 평소 프로필에 있다고 보고, 감속 중이면 복귀 때 내린 만큼 올려 새 평소 프로필로 간다.
     */
    private fun pickProfile(usual: Boolean, after: (() -> Unit)? = null) {
        val cur = if (usual) Prefs.usualProfile(this) else Prefs.slowProfile(this)
        AlertDialog.Builder(this)
            .setTitle(if (usual) "평소 프로필 (차가 지금 달리는 것과 같게)" else "카메라 앞에서 내릴 프로필")
            .setSingleChoiceItems(Profile.topDown.map { it.label }.toTypedArray(), Profile.topDown.indexOf(cur)) { d, i ->
                val p = Profile.topDown[i]
                d.dismiss()
                val other = if (usual) Prefs.slowProfile(this) else Prefs.usualProfile(this)
                if (usual && p.ordinal <= other.ordinal) { Toast.makeText(this, "평소 프로필은 내릴 프로필(${other.label})보다 높아야 합니다", Toast.LENGTH_LONG).show(); return@setSingleChoiceItems }
                if (!usual && p.ordinal >= other.ordinal) { Toast.makeText(this, "내릴 프로필은 평소 프로필(${other.label})보다 낮아야 합니다", Toast.LENGTH_LONG).show(); return@setSingleChoiceItems }
                if (p == cur) return@setSingleChoiceItems
                if (usual) Prefs.setUsualProfile(this, p) else Prefs.setSlowProfile(this, p)
                outputs.refresh()
                if (DriveState.running) DriveService.send(this, DriveService.ACTION_SETTINGS_CHANGED)
                Toast.makeText(this, "${Prefs.usualProfile(this).label} → ${Prefs.slowProfile(this).label}: 휠 아래 ${Prefs.downPresses(this)}번 ↔ 위 ${Prefs.upPresses(this)}번" + (if (DriveState.running) " · 바로 적용" else ""), Toast.LENGTH_SHORT).show()
                render()
                after?.invoke()
            }
            .setNegativeButton("취소", null).show()
    }

    // ---- 데이터·테스트 도구(아래 판 "도구 ⋮"). 설정은 지도 위 ⚙ 버튼.
    private fun showTools(anchor: View) {
        val pm = PopupMenu(this, anchor)
        val m = pm.menu
        m.add(0, M_HELP, 0, "도움말 (처음 설치·문제 해결)")
        m.add(0, M_SETTINGS, 0, "설정")
        m.add(0, M_CSV, 1, "카메라 데이터 (경찰청 전국 CSV)")
        m.add(0, M_KEC_SECTIONS, 2, "도로공사 구간단속 CSV")
        m.add(0, M_KEC_POINTS, 3, "도로공사 이정좌표 CSV")
        m.add(0, M_ZONES, 4, "어린이보호구역 CSV")
        m.add(0, M_ROADS, 5, "도로망 데이터 (OSM kr-roads.zip)")
        m.add(0, M_TEST_SOUND, 6, "테스트: 안내 소리")
        m.add(0, M_TEST_DOWN, 7, "테스트: 감속 신호")
        m.add(0, M_TEST_UP, 8, "테스트: 복귀 신호")
        m.add(0, M_SIM, 9, "가상 주행 테스트 (주차 중)")
        m.add(0, M_PAIR, 10, "커맨더 페어링 모드 켜기/끄기")
        m.add(0, M_SCAN, 11, "BLE 스캔 (진단용)")
        pm.setOnMenuItemClickListener { onTool(it.itemId); true }
        pm.show()
    }

    private fun onTool(id: Int) {
        when (id) {
            M_SETTINGS -> startActivity(Intent(this, SettingsActivity::class.java))
            M_CSV -> pickCsv(REQ_CSV)
            M_KEC_SECTIONS -> pickCsv(REQ_KEC_SECTIONS)
            M_KEC_POINTS -> pickCsv(REQ_KEC_POINTS)
            M_ZONES -> pickCsv(REQ_ZONES)
            M_ROADS -> pickCsv(REQ_ROADS)
            M_TEST_SOUND -> outputs.test()
            M_TEST_DOWN -> { if (DriveState.running) Toast.makeText(this, "감시 중엔 테스트할 수 없습니다 — 감시를 끄고 하세요 (앱이 아는 차 프로필이 어긋남)", Toast.LENGTH_LONG).show() else { outputs.refresh(); outputs.down(Camera(0.0, 0.0, 100, CamKind.SPOT, 0, "테스트"), 300.0) } }
            M_TEST_UP -> { if (DriveState.running) Toast.makeText(this, "감시 중엔 테스트할 수 없습니다 — 감시를 끄고 하세요 (앱이 아는 차 프로필이 어긋남)", Toast.LENGTH_LONG).show() else { outputs.refresh(); outputs.up("테스트", force = true) } }
            M_SIM -> if (hasLocationPermission()) DriveService.send(this, DriveService.ACTION_SIMULATE) else askPermissions()
            M_PAIR -> togglePairing()
            M_SCAN -> startActivity(Intent(this, CaptureActivity::class.java))
            M_HELP -> startActivity(Intent(this, HelpActivity::class.java))
        }
    }

    /** 주행과 무관하게 ENH_BTN 광고를 켜 둔다. 커맨더 앱에서 버튼을 추가할 때 쓴다. */
    /** 커맨더가 안 붙을 때 다시 등록 안내(감시 중 한 번). [페어링 모드 켜기]를 누르면 낯선 기기와도 페어링을 받는다. */
    private var cmdHelpSeen = 0L
    private fun maybeShowCmdHelp(running: Boolean) {
        val at = DriveState.cmdHelpAt
        if (!running || !resumed || at == 0L || at == cmdHelpSeen || S3xyButton.ready) return
        cmdHelpSeen = at
        AlertDialog.Builder(this).setTitle("커맨더가 안 붙습니다")
            .setMessage("앱을 다시 켜면 폰 블루투스 주소가 바뀌어 커맨더가 폰을 못 알아볼 때가 있습니다.\n\n" +
                "차를 세운 뒤:\n1. 아래 [페어링 모드 켜기]\n2. enhauto 앱 → Buttons → Add S3XY Button\n3. 폰에 뜨는 페어링 요청 [승인]\n4. (필요하면) 버튼 기능: 클릭 = 크루징 속도 내림, 더블클릭 = 올림\n\n" +
                "설정 ⑥ \"감시를 꺼도 커맨더 버튼 유지\"를 켜 두면 이런 일이 줄어듭니다.")
            .setPositiveButton("페어링 모드 켜기") { _, _ -> S3xyButton.pairingMode = true; DriveState.log("페어링 모드 켬 — enhauto에서 Add S3XY Button"); render() }
            .setNegativeButton("닫기", null)
            .show()
    }

    private fun togglePairing() {
        if (S3xyButton.running && S3xyButton.pairingMode) { S3xyButton.stop(); render(); return }
        if (S3xyButton.running) { Toast.makeText(this, "주행 감시가 켜 둔 상태입니다 — 이미 광고 중", Toast.LENGTH_SHORT).show(); return }
        val ok = S3xyButton.start(this, { DriveState.log(it) }, Prefs.renameAdapter(this))
        S3xyButton.pairingMode = ok
        Toast.makeText(this, if (ok) "ENH_BTN 광고 중 — 커맨더 앱에서 Add S3XY Button" else "시작 실패 — 기록을 보세요", Toast.LENGTH_LONG).show()
        render()
    }

    private fun pickCsv(req: Int) = startActivityForResult(
        Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*"), req,
    )

    private fun hasLocationPermission() =
        checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

    private fun startDrive() {
        if (!hasLocationPermission()) {
            Toast.makeText(this, "위치 권한을 먼저 허용하세요", Toast.LENGTH_LONG).show()
            askPermissions(); return
        }
        if (CameraRepo.cachedCount() == 0) {
            Toast.makeText(this, "카메라 데이터가 없습니다 — 메뉴에서 경찰청 전국 CSV를 먼저 불러오세요", Toast.LENGTH_LONG).show(); return
        }
        DriveService.send(this, DriveService.ACTION_START)
    }

    private fun askPermissions() {
        val want = mutableListOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
        if (Build.VERSION.SDK_INT >= 31) want += listOf(
            Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_ADVERTISE,
            Manifest.permission.BLUETOOTH_CONNECT,
        )
        if (Build.VERSION.SDK_INT >= 33) want += Manifest.permission.POST_NOTIFICATIONS
        val missing = want.filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isNotEmpty()) requestPermissions(missing.toTypedArray(), 1)
    }

    override fun onResume() {
        super.onResume()
        // 설정에서 테마를 바꿨으면 다시 만든다
        if (Theme.current(this).night != builtNight) { recreate(); return }
        resumed = true
        if (naviView == null) mapPane.resume()
        routeChooser?.resume()
        DriveState.activityVisible = true
        DriveState.overlaySuppressed = false
        OverlayWindow.hide()
        DriveState.addListener(listener)
        outputs.refresh()
        render()
        main.post(tick)
        UpdateCheck.maybeCheck(this)
        Thread {
            CameraRepo.load(this); CameraRepo.loadZones(this)
            runOnUiThread { render() }
        }.start()
    }

    override fun onPause() {
        main.removeCallbacks(tick)
        DriveState.removeListener(listener)
        DriveState.activityVisible = false
        resumed = false
        mapPane.pause()
        routeChooser?.pause()
        // 앱이 뒤로 가면 알약 창을 띄운다(감시 중일 때만)
        main.postDelayed({ OverlayWindow.sync(this) }, 300)
        super.onPause()
    }

    override fun onDestroy() {
        naviView?.let { KakaoSafety.detachView(it) }
        naviView = null
        routeDialog?.dismiss(); routeDialog = null
        routeChooser?.dismiss(); routeChooser = null
        mapPane.destroy()
        outputs.shutdown()
        super.onDestroy()
    }

    private fun gpsAgeS(): Long? = if (DriveState.lastFixAt == 0L) null else (SystemClock.elapsedRealtime() - DriveState.lastFixAt) / 1000

    private fun km(m: Double) = if (m >= 950) "%.1fkm".format(m / 1000.0) else "${m.toInt()}m"

    /** 접는 칸 머리줄: "점검  ✔ 모두 정상   ▾" (펼쳐 있으면 ▴). */
    private fun foldHead(title: String, summary: String, color: Int, open: Boolean): CharSequence = android.text.SpannableStringBuilder().apply {
        append(title); setSpan(android.text.style.StyleSpan(Typeface.BOLD), 0, length, 0)
        append("   ")
        val s = length
        append(if (open) "" else summary)
        setSpan(ForegroundColorSpan(color), s, length, 0)
        append(if (open) "접기 ▴" else "  ▾")
    }

    /** "다음 카메라 · …" 뒤에 출처 표시(노란 "카카오" / 주황 "내장"). 카카오 키가 없는 빌드면 표시 안 함. */
    private fun withSource(text: String, kakao: Boolean): CharSequence = android.text.SpannableStringBuilder(text).apply {
        if (!KakaoSafety.hasKey) return@apply
        append("  ")
        val start = length
        append(if (kakao) " 카카오 " else " 내장 ")
        setSpan(android.text.style.BackgroundColorSpan(if (kakao) 0xFFFEE500.toInt() else 0xFFFFE1C4.toInt()), start, length, 0)
        setSpan(ForegroundColorSpan(if (kakao) 0xFF3A2F00.toInt() else 0xFF8A4B12.toInt()), start, length, 0)
        setSpan(android.text.style.RelativeSizeSpan(0.85f), start, length, 0)
        setSpan(android.text.style.StyleSpan(Typeface.BOLD), start, length, 0)
    }

    /** v0.13.4: [카카오 화면 다시 붙이기] — 지금 화면을 떼고 바로 새로 붙인다(감속·구간 유지 중이어도 기다리지 않음). */
    private fun reattachNavi() {
        val running = DriveState.running; val sim = DriveState.simulating
        if (!running || sim) { Toast.makeText(this, "감시 중일 때만 붙일 수 있습니다", Toast.LENGTH_SHORT).show(); return }
        if (!KakaoSafety.sdkReady) {
            DriveState.log("카카오 화면 다시 붙이기(직접) — 카카오 연결 대기 중이라 연결되면 붙임")
            Toast.makeText(this, "카카오 연결 대기 중 — 연결되면 붙습니다", Toast.LENGTH_LONG).show()
            naviFailed = false
            return
        }
        DriveState.log("카카오 화면 다시 붙이기(직접)" + if (naviView == null) " — 화면 없음 상태에서" else "")
        naviView?.let { detachNavi(it) }
        naviFailed = false
        naviForce = true
        updateNavi(running, sim)
        naviForce = false
        setSheet(false)
    }

    private fun detachNavi(v: KNNaviView) {
        KakaoSafety.detachView(v)
        mapFrame.removeView(v)
        naviView = null
        mapPane.visibility = View.VISIBLE; if (resumed) mapPane.resume()
        layoutForNavi(false)
    }

    /** 감시 중이고 카카오가 준비되면 카카오내비 화면을 붙이고, 아니면 떼고 기존 지도로. */
    private fun updateNavi(running: Boolean, sim: Boolean) {
        if (!running) { naviFailed = false; naviMissingSince = 0L; naviMissingSaid = false } // 감시를 다시 켜면 카카오 화면을 다시 시도
        // 카카오 쪽에서 화면을 버렸으면(화면으로 시작 실패) 기존 지도로
        if (naviView != null && KakaoSafety.naviView !== naviView) {
            naviFailed = true; naviFailedAt = SystemClock.elapsedRealtime()
            DriveState.log("카카오내비 화면이 멈춤 — 기존 지도로 (1분 뒤 다시 붙임)")
            Prefs.setNaviErr(this, "화면으로 안내 시작 실패")
        }
        // v0.13.3: 실패해도 감시를 껐다 켤 때까지 안 붙던 것 → 1분마다 다시 붙여 본다
        if (naviFailed && SystemClock.elapsedRealtime() - naviFailedAt > 60_000) naviFailed = false
        val want = running && !sim && KakaoSafety.sdkReady && !naviFailed
        val v = naviView
        // 붙일 때 안내를 다시 시작하므로(몇 초 카카오 판단 공백) 감속 중·구간 유지 중이면 끝난 뒤에 붙인다.
        // v0.13.3: 다가가는 중(감속 전)에는 미루지 않는다(도심에서 화면이 늦게 붙던 것)
        val st = DriveState.status
        val busy = st != null && (st.slowed || st.holdKind != null)
        // 카카오는 연결됐는데 화면이 1분 넘게 안 붙어 있으면(미루는 동안 제외) 한 번 알린다
        val now = SystemClock.elapsedRealtime()
        val missing = running && !sim && KakaoSafety.sdkReady && v == null && !busy
        if (!missing) naviMissingSince = 0L
        else if (naviMissingSince == 0L) naviMissingSince = now
        else if (!naviMissingSaid && now - naviMissingSince > 60_000) {
            naviMissingSaid = true
            DriveState.log("⚠ 카카오내비 화면이 1분 넘게 안 붙음 (${Prefs.naviErr(this).ifEmpty { "이유 모름" }}) — 1분마다 다시 시도")
            DriveState.say?.invoke("카카오 화면이 안 붙었습니다")
        }
        if (v != null && naviMissingSaid) naviMissingSaid = false
        if (want && v == null && (!busy || naviForce)) {
            try {
                val nv = KNNaviView(this)
                nv.useDarkMode = t.night
                nv.useJcImg = true      // 교차로 확대 그림
                nv.sndVolume = 1f
                mapFrame.addView(nv, FrameLayout.LayoutParams(-1, -1))
                pill.bringToFront()
                naviView = nv
                mapPane.visibility = View.GONE; mapPane.pause()
                KakaoSafety.attachView(nv)
                layoutForNavi(true)
                DriveState.log("카카오내비 화면 켬")
                Prefs.setNaviErr(this, "")
            } catch (e: Throwable) {
                naviFailed = true; naviFailedAt = SystemClock.elapsedRealtime()
                naviView?.let { KakaoSafety.detachView(it); mapFrame.removeView(it) }
                naviView = null
                mapPane.visibility = View.VISIBLE; if (resumed) mapPane.resume()
                DriveState.log("카카오내비 화면 실패 — 기존 지도로: ${e.javaClass.simpleName} ${e.message ?: ""}")
                Prefs.setNaviErr(this, "${e.javaClass.simpleName} ${e.message ?: ""}".take(120))
            }
        } else if (!want && v != null) {
            detachNavi(v)
        }
    }

    /** 카카오 화면: 지도는 아래 판 위까지(카카오 아래 막대가 보이게), 카메라 카드는 아래 판 바로 위. 기존 지도: 카드는 위. */
    private fun layoutForNavi(on: Boolean) {
        if (wide) return
        (mapFrame.layoutParams as? FrameLayout.LayoutParams)?.let { lp ->
            // 큰 화면은 판이 오른쪽 아래에 떠 있으니 지도는 전체 높이
            val bm = if (on && !bigScreen) panelH else 0
            if (lp.bottomMargin != bm) { lp.bottomMargin = bm; mapFrame.layoutParams = lp }
        }
        // v0.13.4: 큰 화면(폴더 펼침)은 판을 카카오 아래 줄(도착 시간 줄) 바로 위에 띄워 그 줄을 가리지 않는다
        val pv = panelView ?: return
        (pv.layoutParams as? FrameLayout.LayoutParams)?.let { lp ->
            val bm = if (on && bigScreen) dp(KAKAO_BAR_DP) else 0
            if (lp.bottomMargin != bm) { lp.bottomMargin = bm; pv.layoutParams = lp }
        }
    }

    /**
     * v0.13.4: 화면 크기에 맞춰 판·카드 배치. 큰 화면(폴더 펼침)은 판을 화면 오른쪽 반 너비로.
     * 폴더를 접고 펼쳐도 화면을 다시 만들지 않고(매니페스트 configChanges) 이것만 다시 부른다 — 카카오 화면·경로가 그대로 남는다.
     */
    private fun applyScreenLayout() {
        if (wide) return
        bigScreen = resources.configuration.screenWidthDp >= 600
        (cardsBox.layoutParams as? FrameLayout.LayoutParams)?.let { lp -> lp.width = if (bigScreen) dp(440) else -1; cardsBox.layoutParams = lp }
        panelView?.let { pv ->
            (pv.layoutParams as? FrameLayout.LayoutParams)?.let { lp ->
                lp.width = if (bigScreen) dp(resources.configuration.screenWidthDp / 2) - dp(12) else -1
                lp.rightMargin = if (bigScreen) dp(12) else 0
                pv.layoutParams = lp
            }
        }
        layoutForNavi(naviView != null)
    }

    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        val was = bigScreen
        applyScreenLayout()
        if (was != bigScreen) DriveState.log(if (bigScreen) "폴더 펼침 — 화면 배치만 바꿈" else "폴더 접음 — 화면 배치만 바꿈")
        render()
    }

    /** 새 목적지 경로가 시작되면 10초 동안 경로 종류 고르기 창. 안 누르면 그대로(설정의 기본값). */
    private fun updateRouteChooser(running: Boolean) {
        val left = KakaoSafety.askRouteUntil - System.currentTimeMillis()
        if (!running || left <= 0 || !resumed) {
            routeDialog?.dismiss(); routeDialog = null
            routeChooser?.dismiss(); routeChooser = null
            return
        }
        // 카메라 가까이·감속·구간 유지 중이면 전체 화면 창이 카메라 알림을 가리므로, 지나간 뒤에 띄운다(대기 시간 연장)
        val stB = DriveState.status
        val busy = stB != null && (stB.slowed || stB.holdKind != null || (stB.nextDistM ?: 9e9) < 1_500 || S3xyButton.progress != null)
        if (busy) {
            routeChooser?.dismiss(); routeChooser = null
            routeDialog?.dismiss(); routeDialog = null
            // 출발한 지 오래돼 이미 그 경로로 가고 있으면 늦게 띄우지 않는다(실차: 같은 목적지 가는 중 한참 뒤에 뜸)
            if (System.currentTimeMillis() - KakaoSafety.askSince > 45_000) {
                KakaoSafety.askRouteUntil = 0L; DriveState.log("경로 고르기 — 카메라가 이어져 못 띄우고 지금 경로로 계속")
            } else KakaoSafety.askRouteUntil = System.currentTimeMillis() + KakaoSafety.ASK_ROUTE_MS
            return
        }
        val secs = ((left + 999) / 1000).toInt()
        // 경로 후보를 받았으면 지도에서 보고 고르는 화면
        val ch = KakaoSafety.choices
        if (ch.isNotEmpty()) {
            val rc = routeChooser
            if (rc != null && rc.showing) { rc.tick(secs); return }
            if (rc == null) {
                val name = DriveState.teslaDest?.let { TeslaDest.label(it) } ?: "목적지"
                val n = RouteChooser(this, name, ch, KakaoSafety.routeOpt) { o ->
                    routeChooser = null
                    if (o != null) KakaoSafety.chooseRoute(o) else KakaoSafety.askRouteUntil = 0L
                }
                routeChooser = n
                DriveState.log("경로 고르기 화면 — $name")
                n.show(); n.tick(secs)
            }
            return
        }
        val cur = KakaoSafety.routeOpt
        routeDialog?.let { routeDialogInfo?.text = "${secs}초 뒤 '${cur.label}'(으)로 계속"; return }
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(18), dp(8), dp(18), dp(6)) }
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        lateinit var dlg: Dialog
        for (o in RouteOpt.values()) {
            row.addView(tv(16f, if (o == cur) Color.WHITE else t.fg, font = jua).apply {
                text = o.short; gravity = Gravity.CENTER; setPadding(dp(6), dp(14), dp(6), dp(14))
                background = rounded(if (o == cur) CamCard.NAVY else t.chipBg, 12f, t.chipLine)
                setOnClickListener { KakaoSafety.chooseRoute(o); dlg.dismiss() }
            }, LinearLayout.LayoutParams(0, -2, 1f).apply { leftMargin = dp(3); rightMargin = dp(3) })
        }
        box.addView(row)
        val info = tv(13f, t.muted).apply { setPadding(dp(4), dp(10), 0, 0); text = "${secs}초 뒤 '${cur.label}'(으)로 계속" }
        box.addView(info)
        dlg = AlertDialog.Builder(this).setTitle("경로 종류 · ${DriveState.teslaDest?.let { TeslaDest.label(it) } ?: "목적지"}").setView(box)
            .setNegativeButton("이대로") { _, _ -> KakaoSafety.askRouteUntil = 0L }
            .setOnCancelListener { KakaoSafety.askRouteUntil = 0L }
            .create()
        dlg.setOnDismissListener { if (routeDialog === dlg) { routeDialog = null; routeDialogInfo = null } }
        routeDialog = dlg; routeDialogInfo = info
        dlg.show()
    }

    private fun render() {
        val running = DriveState.running
        val sim = DriveState.simulating
        val st = DriveState.status
        val out = DriveState.outputs ?: outputs.settings
        val cfg = DriveState.config ?: Prefs.engine(this)
        val camCount = CameraRepo.cachedCount()
        val zoneCount = CameraRepo.cachedZoneCount()
        val age = gpsAgeS()
        val autoOn = Prefs.autoStart(this)
        val slowed = st?.slowed == true
        val gpsLost = running && !sim && age != null && age >= 15
        val car = DriveState.carProfile
        val prog = S3xyButton.progress
        val progKind = S3xyButton.progressKind
        val next = st?.nextCamera
        val restore = cfg.restoreAfterM.toInt()
        // 카카오 키가 있는데 지금 판단이 카카오가 아니면 "예비 판단"(끊김·연결 실패)
        val kakaoDown = running && !sim && st != null && KakaoSafety.hasKey && !st.external && !KakaoSafety.switching
        val over = if (running) DriveState.overspeedKmh else null
        if (over != null && !blinkPosted) { blinkPosted = true; main.postDelayed(blinkRunnable, 500) }
        if (over == null) blinkOn = false
        val following = running && KakaoSafety.following
        val usual = Prefs.usualProfile(this); val slow = Prefs.slowProfile(this)

        // ---- 회전 카드: 경로 안내 중이고 다음 회전을 알 때만
        updateNavi(running, sim)
        val navi = naviView != null
        val turn = if (following && !navi) KakaoSafety.turn else null
        turnCard.visibility = if (turn != null) View.VISIBLE else View.GONE
        if (turn != null) {
            turnArrow.text = turn.type.arrow
            turnDist.text = TurnInfo.dist(turn.distM)
            turnLine.text = turn.line()
            val nt = KakaoSafety.nextTurn
            turnNext.visibility = if (nt != null) View.VISIBLE else View.GONE
            if (nt != null) turnNext.text = "그다음\n${nt.type.arrow} ${TurnInfo.dist(nt.distM)}"
        }

        // ---- 카메라 카드
        val mode = CamCard.mode(running, slowed, over != null, kakaoDown || (running && !sim && gpsLost))
        val color = CamCard.color(mode, blinkOn)
        camCard.background = rounded(color, 18f)
        bottomView.setBackgroundColor(CamCard.darker(color))
        stripeView.visibility = if (kakaoDown) View.VISIBLE else View.GONE
        statusView.text = when {
            !running -> "○ 꺼짐 · " + (if (autoOn) "${Prefs.autoDeviceName(this).ifEmpty { "차" }}에 연결되면 자동 시작" else "아래 [감시 켜기]")
            sim -> "가상 주행 중"
            st == null -> "GPS 대기 중"
            gpsLost -> "● GPS 없음 ${age}초" + (if (DriveState.drActive) " · 추측 항법" else "")
            slowed && prog != null && progKind == S3xyButton.Press.SINGLE -> "▼ ${out.slow.label}로 내리는 중 · 클릭 ${prog.first}/${prog.second}"
            prog != null && progKind == S3xyButton.Press.DOUBLE -> "▲ ${out.usual.label}으로 복귀 중"
            else -> CamCard.statusLine(mode, following, out.slow.label, DriveState.overLimit, over)
        }
        val limitCam = st?.target ?: next
        val limit = when {
            st == null -> null
            st.target == null && st.zone != null -> 30
            st.holdKind == "구간단속" -> st.target?.limitKmh
            else -> limitCam?.limitKmh
        }
        signView.text = limit?.toString() ?: "–"
        speedLabel.text = if (st?.estimated == true) "km/h (추정)" else "km/h"
        val midTarget = st?.target?.takeIf { it.kind == CamKind.SECTION_END && st.holdKind == "구간단속" }
        var holdText: String? = null
        if (!running || st == null) {
            distView.text = if (!running) "–" else "GPS 대기"
            lineView.text = if (!running) "감시 꺼짐" else "위치를 잡으면 카메라를 찾기 시작합니다"
            speedView.text = "–"
        } else {
            speedView.text = "${st.speedKmh.toInt()}"
            when {
                st.holdKind == "구간단속" -> {
                    val remain = ((st.holdRemainM ?: 0.0) - cfg.restoreAfterM).coerceAtLeast(0.0)
                    distView.text = if (remain >= 950) "%.1f km".format(remain / 1000.0) else "${remain.toInt()} m"
                    lineView.text = withSource("구간단속 끝까지" + ((midTarget ?: st.target)?.roadName?.takeIf { it.isNotEmpty() }?.let { " · ${it.take(14)}" } ?: ""), st.external)
                    holdText = "구간단속 중 · 평균속도 유지" + (if (out.autoRestore) " · 끝 지나 ${restore}m 뒤 ${out.usual.label}" else " · 복귀는 직접")
                }
                slowed && st.holdKind == "통과" -> {
                    distView.text = "통과"
                    lineView.text = withSource("카메라 지남", st.external)
                    holdText = "${st.holdRemainM?.toInt() ?: 0} m 뒤 " + (if (out.autoRestore) "${out.usual.label}로 복귀" else "복귀는 직접")
                }
                next != null -> {
                    distView.text = "${"%,d".format((st.nextDistM ?: 0.0).toInt())} m"
                    lineView.text = withSource(buildString {
                        append("다음 카메라")
                        if (next.kind == CamKind.SECTION_START) append(" · 구간단속")
                        val road = next.roadName.ifEmpty { next.name }
                        if (road.isNotEmpty()) append(" · ").append(road.take(16))
                    }, st.external)
                }
                st.target == null && st.zone != null -> {
                    distView.text = "보호구역"
                    lineView.text = st.zone
                    holdText = "반경 밖으로 나가면 복귀"
                }
                else -> {
                    distView.text = if (st.estimated) "추측 항법" else "카메라 없음"
                    lineView.text = "2 km 안에 카메라 없음"
                }
            }
        }
        // 과속: 속도를 큰 빨간 상자로 깜빡임
        if (over != null) {
            speedView.textSize = 46f
            speedView.setPadding(dp(12), dp(2), dp(12), dp(4))
            speedView.background = GradientDrawable().apply {
                cornerRadius = dpf(14f)
                if (blinkOn) { setColor(Color.WHITE); setStroke(dp(3), CamCard.RED) } else { setColor(CamCard.RED_DARK); setStroke(dp(3), Color.WHITE) }
            }
            speedView.setTextColor(if (blinkOn) CamCard.RED else Color.WHITE)
        } else {
            speedView.textSize = 36f; speedView.setPadding(0, 0, 0, 0); speedView.background = null; speedView.setTextColor(Color.WHITE)
        }
        val showGauge = running && st != null && next != null && st.holdKind == null && (st.nextDistM ?: 9e9) <= CamCard.RANGE_M + 1
        gaugeView.visibility = if (showGauge) View.VISIBLE else View.GONE
        if (showGauge) { gaugeView.gauge = CamCard.gauge(st?.nextDistM, st?.triggerM, slowed); gaugeView.limit = next?.limitKmh }
        bottomView.text = when {
            !running -> "${usual.label} → ${slow.label}" + (if (out.ble) "" else " · 음성만")
            gpsLost -> if (DriveState.drActive) "추측 항법: 감속만 하고 복귀는 GPS가 돌아온 뒤에" else "GPS가 없어 카메라를 감지하지 못합니다"
            car != null && car != out.usual && !slowed -> "${car.label} 상태 · 아래 판 [지금 복귀]로 올리기"
            else -> CamCard.bottomLine(mode, st?.nextDistM?.takeIf { next != null }, st?.triggerM, out.usual.label, out.slow.label, out.autoRestore, holdText)
        }

        // 카카오 화면일 때는 큰 카드를 숨기고, 카메라 일이 있을 때만 작은 알림(평소엔 카카오가 속도·안내를 보여 줌)
        val camNear = st != null && next != null && st.holdKind == null && (st.nextDistM ?: 9e9) <= CamCard.RANGE_M + 1
        val camJob = st != null && (slowed || st.holdKind != null || over != null || prog != null || camNear ||
            (st.target == null && st.zone != null) || (car != null && car != out.usual))
        // 큰 카드는 감시 중이고 카카오 화면이 없을 때(예비 지도)만. 꺼져 있을 때 첫 화면엔 안 띄움
        cardsBox.visibility = if (navi || !running) View.GONE else View.VISIBLE
        val showPill = navi && running && (camJob || gpsLost)
        pill.visibility = if (showPill) View.VISIBLE else View.GONE
        if (showPill) {
            // 감속 시작 지점에 가까워지거나 클릭을 보내는 중이면 1초마다 반짝
            val nearTrigger = camNear && !slowed && st?.triggerM != null && (st.nextDistM ?: 9e9) <= (st.triggerM ?: 0.0) + 300
            val flash = (nearTrigger || prog != null || over != null) && (SystemClock.uptimeMillis() / 1000) % 2 == 0L
            val base = color
            pill.background = rounded(if (flash) CamCard.darker(base, 0.7f) else base, 22f)
            pillSign.visibility = if (limit != null && camJob) View.VISIBLE else View.GONE
            pillSign.text = limit?.toString() ?: ""
            pillDist.visibility = if (camJob || gpsLost) View.VISIBLE else View.GONE
            pillDist.text = when {
                gpsLost && !camJob -> "GPS 없음"
                over != null -> "${st?.speedKmh?.toInt() ?: 0} km/h 과속"
                else -> distView.text
            }
            val sub = when {
                prog != null && progKind == S3xyButton.Press.SINGLE -> "▼ ${out.slow.label}로 내리는 중 · ${prog.first}/${prog.second}"
                prog != null && progKind == S3xyButton.Press.DOUBLE -> "▲ ${out.usual.label}으로 복귀 중"
                camJob || gpsLost -> bottomView.text.toString()
                else -> ""
            }
            pillSub.text = listOf(sub, if (kakaoDown) "⚠ 카카오 끊김 · 내장 판단" else "").filter { it.isNotEmpty() }.joinToString("\n")
        }
        recenterChip?.visibility = if (navi) View.GONE else View.VISIBLE
        updateRouteChooser(running)
        maybeShowCmdHelp(running)

        // ---- 지도
        mapPane.data = if (running) DriveState.map else null
        mapPane.route = if (following) KakaoSafety.routeLine else null
        mapPane.caption = st?.road?.takeIf { !it.startsWith("도로 미확인") }?.substringBefore(" · 지도") ?: ""

        // ---- 아래 판: 목적지 줄
        val dest = if (running) DriveState.teslaDest else null
        val teslaOn = Prefs.teslaFollow(this) && TeslaLink.connected(this)
        if (dest != null) {
            destName.text = TeslaDest.label(dest)
            destSub.text = "테슬라 내비 목적지" + (dest.km?.let { " · %.1f km".format(it) } ?: "") +
                (if (!following) (if (kakaoDown) " · 경로는 카카오 연결 후" else " · 경로 찾는 중") else "")
            destEta.text = dest.minutes?.let { m ->
                val eta = java.util.Calendar.getInstance().apply { add(java.util.Calendar.MINUTE, m.toInt()) }
                SpannableStringBuilder().apply {
                    val hm = "%d:%02d".format(eta.get(java.util.Calendar.HOUR_OF_DAY), eta.get(java.util.Calendar.MINUTE))
                    append(hm); setSpan(RelativeSizeSpan(1.5f), 0, hm.length, 0); setSpan(ForegroundColorSpan(t.fg), 0, hm.length, 0)
                    append("\n${m.toInt()}분 남음")
                }
            } ?: ""
        } else {
            destName.text = "목적지 없음 · 카메라만 감시"
            destSub.text = when {
                !teslaOn -> "테슬라 목적지 따라가기 꺼짐 (설정 ⑨)"
                running -> "마지막 확인: " + Prefs.teslaLastNote(this).ifEmpty { "아직" } + " · ↻로 바로 확인"
                else -> "감시를 켜면 테슬라 목적지를 확인합니다"
            }
            destEta.text = ""
        }

        // 감시 중: 목적지 아래 줄은 상태 점(● 카카오 ● 커맨더) — 초록 정상, 노랑·빨강 문제
        if (running && !sim) {
            val okG = 0xFF2E9D62.toInt(); val warnY = 0xFFE0A020.toInt(); val badR = 0xFFD93025.toInt()
            val kakaoState = when { kakaoDown -> "카카오 끊김" to warnY; following -> "카카오 경로" to okG; else -> "카카오" to okG }
            val cmdState = when { !out.ble -> "음성만" to t.muted; S3xyButton.ready -> "커맨더" to okG; else -> "커맨더 없음" to badR }
            destSub.text = SpannableStringBuilder().apply {
                for ((i, st2) in listOf(kakaoState, cmdState).withIndex()) {
                    if (i > 0) append("   ")
                    val a = length; append("● "); setSpan(ForegroundColorSpan(st2.second), a, a + 1, 0)
                    val b = length; append(st2.first); if (st2.second == badR || st2.second == warnY) setSpan(ForegroundColorSpan(st2.second), b, length, 0)
                }
            }
        }

        // ---- 아래 판: 접힘/펼침
        val open = wide || sheetOpen
        // 접힘: 한 줄(목적지·상태·도착·끄기). 프로필 고르기·↻ 목적지 확인은 펼쳤을 때만
        collapsedRow.visibility = View.GONE
        smallToggle.visibility = if (open) View.GONE else View.VISIBLE
        destCheck.visibility = View.VISIBLE
        expanded.visibility = if (open) View.VISIBLE else View.GONE
        profileSummary.text = "${usual.label} → ${slow.label} ▾"
        smallToggle.text = if (running) "■" else "▶ 켜기"
        (smallToggle.background as GradientDrawable).setColor(if (running) t.toggleOff else t.toggleOn)

        chipText(usualChip, if (running && car != null && car != usual) "평소 (차: ${car.label})" else "평소", usual.label, highlight = running && car == usual)
        chipText(slowChip, "카메라 앞", slow.label, highlight = running && car != null && car == slow && slow != usual)
        val showRelease = running && !sim && (slowed || (car != null && car != out.usual))
        releaseBtn.visibility = if (showRelease) View.VISIBLE else View.GONE
        releaseBtn.text = "▲ 지금 복귀 → ${out.usual.label}"
        toggleBtn.text = if (running) "■ 감시 끄기" else "▶ 감시 켜기"
        (toggleBtn.background as GradientDrawable).setColor(if (running) t.toggleOff else t.toggleOn)
        if (running && Prefs.keepScreenOn(this)) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        nowView.text = when {
            !running -> ""
            st?.road != null -> "지금: ${st.road}" + (DriveState.lastAccM?.let { " · GPS 정확도 ${it.toInt()}m" } ?: "")
            else -> DriveState.lastAccM?.let { "GPS 정확도 ${it.toInt()}m" } ?: ""
        }
        nowView.visibility = if (nowView.text.isNullOrEmpty()) View.GONE else View.VISIBLE

        // ---- 점검표
        val problems = ArrayList<String>()
        if (running && !sim && out.ble && !S3xyButton.ready) problems += "커맨더 준비 안 됨"
        val gpsLine = when {
            !running -> "–  GPS: 켜면 확인"
            sim -> "–  GPS: 가상 주행 중"
            age == null -> "✖  GPS 아직 없음"
            age >= 15 -> "✖  GPS ${age}초째 없음" + (if (DriveState.drActive) " · 추측 항법 중" else "")
            else -> "✔  GPS ${age}초 전" + (DriveState.lastAccM?.let { " · 정확도 ${it.toInt()}m" } ?: "")
        }
        val dataLine = when (camCount) {
            null -> "–  카메라 데이터 읽는 중"
            0 -> "✖  카메라 데이터 없음 — 도구 ⋮에서 경찰청 전국 CSV"
            else -> "✔  ${CameraRepo.summaryLine}"
        }
        val roadsLine = when {
            !Prefs.useRoads(this) -> if (CameraRepo.hasRoads(this)) "–  도로망 꺼짐(설정) — GPS 방향으로 판단" else null
            !CameraRepo.hasRoads(this) -> "✖  도로망 없음 — 도구 ⋮에서 kr-roads.zip (옆 도로·반대편 오탐 거름)"
            running && !sim && st?.road != null -> (if (st.road.startsWith("도로 미확인")) "△  " else "✔  ") + st.road
            else -> "✔  도로망 ${CameraRepo.roadsInfo.ifEmpty { "있음" }}"
        }
        val cmdLine = when {
            !out.ble -> "–  커맨더 안 씀 (음성만)"
            S3xyButton.ready -> "✔  커맨더 연결됨 · 준비"
            else -> "✖  커맨더: ${S3xyButton.status}"
        }
        val soundLine = if (!out.alerts) "–  소리 끔" else "–  소리: " + when (out.route) {
            VoiceRoute.SPEAKER -> "폰 스피커"; VoiceRoute.CAR_CALL -> "차 (통화 채널)"; VoiceRoute.MEDIA -> "미디어 채널"
        }
        val overlayLine = when {
            !Prefs.overlayOn(this) -> "–  다른 앱 위 알약 창 꺼짐"
            OverlayWindow.canDraw(this) -> "✔  다른 앱 위 알약 창 (감시 중 앱을 벗어나면 뜸)"
            else -> "✖  알약 창에 '다른 앱 위에 표시' 권한 필요 — 설정 7번"
        }
        val autoLine = if (!autoOn) "–  자동 시작 꺼짐" else {
            val missing = AutoStart.missing(this)
            if (missing.isEmpty()) "✔  자동 시작: ${Prefs.autoDeviceName(this)} 연결 시" else "✖  자동 시작에 필요: ${missing.joinToString(", ")}"
        }
        val zoneLine = if (!Prefs.schoolZoneOn(this)) null else when (zoneCount) {
            null -> "–  보호구역 읽는 중"; 0 -> "✖  어린이보호구역 파일 없음 — 도구 ⋮에서 불러오기"; else -> "✔  어린이보호구역 ${zoneCount}곳 · 반경 ${Prefs.schoolZoneRadiusM(this)}m"
        }
        val kakaoAge = KakaoSafety.ageS()
        val kakaoLine = when {
            !KakaoSafety.hasKey -> "–  카카오 안전 운행: 앱 키 없음(빌드)"
            !running || sim -> "–  카카오: 켜면 연결"
            st?.external == true -> "✔  ${KakaoSafety.status} · ${kakaoAge ?: 0}초 전 · 앞 카메라 ${st.ahead.size}"
            kakaoAge != null -> "✖  카카오 끊김 ${kakaoAge}초 — 터널·통신 확인 · 내장 데이터로 판단"
            else -> "△  ${KakaoSafety.status}"
        }
        val naviErr = Prefs.naviErr(this)
        val running0 = DriveState.running
        val naviLine = when {
            naviView != null -> "✔  카카오내비 화면 켜짐"
            naviErr.isNotEmpty() -> "✖  카카오내비 화면 실패: $naviErr"
            !running0 -> "–  카카오내비 화면: 감시를 켜면 나옴"
            !KakaoSafety.sdkReady -> "–  카카오내비 화면: 카카오 연결 대기"
            else -> "–  카카오내비 화면: 감속·구간 유지 끝나면 켬"
        }
        val mapLine = if (mapPane.usingKakao) "✔  지도: 카카오맵" else "–  지도: 간이 지도" + (if (!KakaoSafety.hasKey) "(앱 키 없음)" else "(인터넷·지도 로드 확인)")
        val teslaLine = when {
            !Prefs.teslaFollow(this) -> "–  테슬라 목적지 따라가기 꺼짐"
            !TeslaLink.connected(this) -> "✖  테슬라 연결 필요 — 설정 ⑨"
            else -> "✔  테슬라 연결됨 · " + Prefs.teslaLastNote(this).ifEmpty { "확인 전" }
        }
        val checks = listOfNotNull(gpsLine, kakaoLine, naviLine, mapLine, teslaLine, dataLine, roadsLine, cmdLine, soundLine, overlayLine, autoLine, zoneLine) +
            problems.map { "✖  $it" }.filter { p -> !(p.contains("커맨더") && cmdLine.startsWith("✖")) }
        checkView.text = checks.joinToString("\n")
        checkView.visibility = if (checkExpanded) View.VISIBLE else View.GONE
        val bad = checks.filter { it.startsWith("✖") }
        checkHead.text = foldHead("점검",
            if (bad.isEmpty()) "✔ 모두 정상" else bad.first().replace(Regex("^✖\\s+"), "✖ ") + (if (bad.size > 1) " 외 ${bad.size - 1}" else ""),
            if (bad.isEmpty()) t.bandOn else t.bandProblem, checkExpanded)

        // ---- 기록
        val lines = DriveState.log
        logHead.text = foldHead("기록", lines.firstOrNull() ?: "아직 없음", t.muted, logExpanded)
        logView.text = lines.joinToString("\n")
        logView.visibility = if (logExpanded) View.VISIBLE else View.GONE
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        val uri = data?.data
        if (requestCode !in listOf(REQ_CSV, REQ_KEC_SECTIONS, REQ_KEC_POINTS, REQ_ZONES, REQ_ROADS) || resultCode != RESULT_OK || uri == null) return
        Toast.makeText(this, "불러오는 중…", Toast.LENGTH_SHORT).show()
        Thread {
            val msg = try {
                if (requestCode == REQ_ROADS) {
                    contentResolver.openInputStream(uri)!!.use { CameraRepo.importRoads(this, it) }
                } else {
                    val bytes = contentResolver.openInputStream(uri)!!.use { it.readBytes() }
                    val note = when (requestCode) {
                        REQ_KEC_SECTIONS -> CameraRepo.importKecSections(this, bytes)
                        REQ_KEC_POINTS -> CameraRepo.importKecPoints(this, bytes)
                        REQ_ZONES -> CameraRepo.importZones(this, bytes)
                        else -> "카메라 ${CameraRepo.import(this, bytes)}개"
                    }
                    note + "\n" + CameraRepo.summary(this)
                }
            } catch (e: Exception) {
                "불러오기 실패: ${e.message}"
            }
            runOnUiThread {
                DriveState.log(msg)
                Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
                render()
                if (DriveState.running && !DriveState.simulating) DriveService.send(this, DriveService.ACTION_RELOAD)
            }
        }.start()
    }

    companion object {
        private const val REQ_CSV = 10
        private const val REQ_KEC_SECTIONS = 11
        private const val REQ_KEC_POINTS = 12
        private const val REQ_ZONES = 13
        private const val REQ_ROADS = 14
        private const val M_SETTINGS = 100
        private const val M_CSV = 101
        private const val M_KEC_SECTIONS = 102
        private const val M_KEC_POINTS = 103
        private const val M_ZONES = 104
        private const val M_TEST_DOWN = 105
        private const val M_TEST_UP = 106
        private const val M_SIM = 107
        private const val M_PAIR = 108
        private const val M_SCAN = 109
        /** 카카오내비 화면 아래 줄(도착 시간·남은 거리) 높이(dp) — 큰 화면에서 판을 이 위에 띄운다. 실제 높이를 몰라 어림값. */
        private const val KAKAO_BAR_DP = 72
        private const val M_ROADS = 110
        private const val M_TEST_SOUND = 111
        private const val M_HELP = 112
    }
}

