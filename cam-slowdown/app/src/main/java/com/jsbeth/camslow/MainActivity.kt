package com.jsbeth.camslow

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.text.SpannableString
import android.text.style.ForegroundColorSpan
import android.view.Gravity
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

/**
 * 메인 화면(파스텔 스티커 / 야간 네온): 색 띠 → 다음 카메라 거리·현재 속도·제한 표지 → 간이 지도 → 프로필 두 칸 → [지금 복귀] → 감시 켜기/끄기
 * → 지금 도로 → 점검표 → 기록. 테마는 Theme.current()가 정하고, 설정에서 바꾸면 돌아올 때 다시 만든다.
 */
class MainActivity : Activity() {

    private lateinit var t: Theme
    private lateinit var jua: Typeface
    private lateinit var band: TextView
    private lateinit var distView: TextView
    private lateinit var lineView: TextView
    private lateinit var speedView: TextView
    private lateinit var speedLabel: TextView
    private lateinit var signView: TextView
    private lateinit var signLabel: TextView
    private lateinit var subView: TextView
    private lateinit var map: MiniMapView
    private lateinit var usualChip: TextView
    private lateinit var slowChip: TextView
    private lateinit var releaseBtn: TextView
    private lateinit var toggleBtn: TextView
    private lateinit var nowView: TextView
    private lateinit var checkView: TextView
    private lateinit var logHead: TextView
    private lateinit var logView: TextView
    private lateinit var outputs: Outputs
    private var logExpanded = false
    private var builtNight = false
    private val listener = { render() }
    private val main = Handler(Looper.getMainLooper())
    private val tick = object : Runnable { override fun run() { render(); main.postDelayed(this, 1_000) } }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        // 밤에는 시스템 테마도 어둡게(앱바 메뉴·대화상자가 따라오도록). onCreate 전에 정해야 해서 바뀌면 recreate().
        if (Theme.current(this).night) setTheme(android.R.style.Theme_Material)
        super.onCreate(savedInstanceState)
        outputs = Outputs(this)
        build()
        askPermissions()
        if (intent?.getBooleanExtra(AutoStart.EXTRA_AUTOSTART, false) == true) startDrive()
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        if (intent?.getBooleanExtra(AutoStart.EXTRA_AUTOSTART, false) == true) startDrive()
    }

    // ---- 화면 만들기 (코드로)
    private fun build() {
        t = Theme.current(this)
        builtNight = t.night
        jua = Theme.display(this)
        window.setBackgroundDrawable(ColorDrawable(t.bg))
        actionBar?.setBackgroundDrawable(ColorDrawable(t.appbar))
        actionBar?.title = SpannableString(getString(R.string.app_name)).apply { setSpan(ForegroundColorSpan(t.fg), 0, length, 0) }

        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val lp = { top: Int, bottom: Int -> LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(top); bottomMargin = dp(bottom) } }

        // 색 띠
        band = TextView(this).apply {
            textSize = 13f; setTextColor(Color.WHITE)
            setPadding(dp(16), dp(10), dp(16), dp(10))
            background = GradientDrawable().apply { setColor(t.bandOff) }
        }
        root.addView(band, lp(0, 0))

        // 거리 · 현재 속도 · 제한 표지
        val top = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(dp(16), dp(10), dp(16), dp(0)) }
        val left = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        distView = TextView(this).apply { textSize = 38f; typeface = jua; setTextColor(t.fg); includeFontPadding = false }
        lineView = TextView(this).apply { textSize = 13f; setTextColor(t.fg) }
        left.addView(distView); left.addView(lineView)
        top.addView(left, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        val mid = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL; setPadding(dp(8), 0, dp(12), 0) }
        speedView = TextView(this).apply { textSize = 32f; typeface = jua; setTextColor(t.fg); includeFontPadding = false; gravity = Gravity.CENTER }
        speedLabel = TextView(this).apply { text = "현재 km/h"; textSize = 10f; setTextColor(t.muted); gravity = Gravity.CENTER }
        mid.addView(speedView); mid.addView(speedLabel)
        top.addView(mid)
        val right = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL }
        signView = TextView(this).apply {
            textSize = 21f; typeface = jua; setTextColor(t.signFg); gravity = Gravity.CENTER; includeFontPadding = false
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(t.signBg); setStroke(dp(6), t.sign) }
        }
        right.addView(signView, LinearLayout.LayoutParams(dp(54), dp(54)))
        signLabel = TextView(this).apply { text = "제한"; textSize = 10f; setTextColor(t.muted); gravity = Gravity.CENTER }
        right.addView(signLabel)
        top.addView(right)
        root.addView(top)
        subView = TextView(this).apply { textSize = 12f; setTextColor(t.muted); setPadding(dp(16), dp(2), dp(16), dp(8)) }
        root.addView(subView)

        // 지도
        map = MiniMapView(this).apply { theme = t; display = jua }
        if (Prefs.mapOn(this)) root.addView(map, lp(0, 0))

        // 프로필 두 칸
        val prof = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(dp(16), dp(10), dp(16), dp(2)) }
        usualChip = chip { pickProfile(usual = true) }
        slowChip = chip { pickProfile(usual = false) }
        prof.addView(usualChip, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        prof.addView(TextView(this).apply { text = "→"; textSize = 20f; setTextColor(t.muted); setPadding(dp(8), 0, dp(8), 0) })
        prof.addView(slowChip, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        root.addView(prof)

        // 지금 복귀 (감속 중이거나 차가 평소 프로필이 아닐 때만)
        releaseBtn = bigButton(t.bandProblem) { DriveService.send(this, DriveService.ACTION_RELEASE) }
        root.addView(releaseBtn, lp(8, 0).apply { leftMargin = dp(16); rightMargin = dp(16) })

        // 감시 켜기/끄기
        toggleBtn = bigButton(t.toggleOn) { if (DriveState.running) DriveService.send(this@MainActivity, DriveService.ACTION_STOP) else startDrive() }
        root.addView(toggleBtn, lp(8, 4).apply { leftMargin = dp(16); rightMargin = dp(16) })

        nowView = TextView(this).apply { textSize = 12f; setTextColor(t.muted); setPadding(dp(16), dp(4), dp(16), dp(6)) }
        root.addView(nowView)

        checkView = TextView(this).apply { textSize = 12.5f; setTextColor(t.fg); setPadding(dp(16), dp(4), dp(16), dp(4)); setLineSpacing(0f, 1.25f) }
        root.addView(checkView)

        logHead = TextView(this).apply { textSize = 13f; typeface = Typeface.DEFAULT_BOLD; setTextColor(t.fg); setPadding(dp(16), dp(10), dp(16), dp(2)); setOnClickListener { logExpanded = !logExpanded; render() } }
        root.addView(logHead)
        logView = TextView(this).apply { textSize = 12f; setTextColor(t.fg); setPadding(dp(16), 0, dp(16), dp(20)) }
        root.addView(logView)

        setContentView(ScrollView(this).apply { addView(root); setBackgroundColor(t.bg) })
        render()
    }

    private fun bigButton(color: Int, onClick: () -> Unit) = TextView(this).apply {
        textSize = 19f; typeface = jua; setTextColor(Color.WHITE); gravity = Gravity.CENTER
        setPadding(dp(16), dp(13), dp(16), dp(13))
        background = GradientDrawable().apply { cornerRadius = dp(14).toFloat(); setColor(color) }
        setOnClickListener { onClick() }
    }

    private fun chip(onClick: () -> Unit) = TextView(this).apply {
        textSize = 15f; typeface = jua; setTextColor(t.fg); gravity = Gravity.CENTER; setLineSpacing(0f, 1.1f)
        setPadding(dp(8), dp(6), dp(8), dp(6))
        background = GradientDrawable().apply { cornerRadius = dp(10).toFloat(); setColor(t.chipBg); setStroke(dp(2), t.chipLine) }
        setOnClickListener { onClick() }
    }

    private fun chipText(v: TextView, label: String, value: String, highlight: Boolean) {
        v.text = android.text.SpannableStringBuilder().apply {
            append(label); setSpan(android.text.style.RelativeSizeSpan(0.62f), 0, label.length, 0); setSpan(ForegroundColorSpan(t.muted), 0, label.length, 0)
            append("\n").append("$value ▾")
        }
        (v.background as GradientDrawable).setStroke(dp(2), if (highlight) t.bandSlow else t.chipLine)
    }

    /**
     * 프로필 고르기. 설정 1·2번과 같은 값. 평소 프로필은 감시 중이어도 바로 적용된다(서비스가 설정을 다시 읽음).
     * 감속 중이 아니면 차가 새 평소 프로필에 있다고 보고, 감속 중이면 복귀 때 내린 만큼 올려 새 평소 프로필로 간다.
     */
    private fun pickProfile(usual: Boolean) {
        val cur = if (usual) Prefs.usualProfile(this) else Prefs.slowProfile(this)
        AlertDialog.Builder(this)
            .setTitle(if (usual) "평소 프로필 (차가 지금 달리는 것과 같게)" else "카메라 앞에서 내릴 프로필")
            .setSingleChoiceItems(Profile.labels.toTypedArray(), cur.ordinal) { d, i ->
                val p = Profile.values()[i]
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
            }
            .setNegativeButton("취소", null).show()
    }

    // ---- 오른쪽 위 메뉴: "설정"은 항상 보이고 나머지 도구는 ⋮ 안에
    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menu.add(0, M_SETTINGS, 0, "설정").setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS or MenuItem.SHOW_AS_ACTION_WITH_TEXT)
        menu.add(0, M_CSV, 1, "카메라 데이터 (경찰청 전국 CSV)")
        menu.add(0, M_KEC_SECTIONS, 2, "도로공사 구간단속 CSV")
        menu.add(0, M_KEC_POINTS, 3, "도로공사 이정좌표 CSV")
        menu.add(0, M_ZONES, 4, "어린이보호구역 CSV")
        menu.add(0, M_ROADS, 5, "도로망 데이터 (OSM kr-roads.zip)")
        menu.add(0, M_TEST_SOUND, 6, "테스트: 안내 소리")
        menu.add(0, M_TEST_DOWN, 7, "테스트: 감속 신호")
        menu.add(0, M_TEST_UP, 8, "테스트: 복귀 신호")
        menu.add(0, M_SIM, 9, "가상 주행 테스트 (주차 중)")
        menu.add(0, M_PAIR, 10, "커맨더 페어링 모드 켜기/끄기")
        menu.add(0, M_SCAN, 11, "BLE 스캔 (진단용)")
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            M_SETTINGS -> startActivity(Intent(this, SettingsActivity::class.java))
            M_CSV -> pickCsv(REQ_CSV)
            M_KEC_SECTIONS -> pickCsv(REQ_KEC_SECTIONS)
            M_KEC_POINTS -> pickCsv(REQ_KEC_POINTS)
            M_ZONES -> pickCsv(REQ_ZONES)
            M_ROADS -> pickCsv(REQ_ROADS)
            M_TEST_SOUND -> outputs.test()
            M_TEST_DOWN -> { outputs.refresh(); outputs.down(Camera(0.0, 0.0, 100, CamKind.SPOT, 0, "테스트"), 300.0) }
            M_TEST_UP -> { outputs.refresh(); outputs.up("테스트", force = true) }
            M_SIM -> if (hasLocationPermission()) DriveService.send(this, DriveService.ACTION_SIMULATE) else askPermissions()
            M_PAIR -> togglePairing()
            M_SCAN -> startActivity(Intent(this, CaptureActivity::class.java))
            else -> return super.onOptionsItemSelected(item)
        }
        return true
    }

    /** 주행과 무관하게 ENH_BTN 광고를 켜 둔다. 커맨더 앱에서 버튼을 추가할 때 쓴다. */
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
        // 설정에서 테마·지도 표시를 바꿨으면 다시 만든다
        if (Theme.current(this).night != builtNight) { recreate(); return }
        if ((map.parent != null) != Prefs.mapOn(this)) build()
        DriveState.addListener(listener)
        outputs.refresh()
        render()
        main.post(tick)
        Thread {
            CameraRepo.load(this); CameraRepo.loadZones(this)
            runOnUiThread { render() }
        }.start()
    }

    override fun onPause() {
        main.removeCallbacks(tick)
        DriveState.removeListener(listener)
        super.onPause()
    }

    override fun onDestroy() {
        outputs.shutdown()
        super.onDestroy()
    }

    private fun gpsAgeS(): Long? = if (DriveState.lastFixAt == 0L) null else (SystemClock.elapsedRealtime() - DriveState.lastFixAt) / 1000

    private fun km(m: Double) = if (m >= 950) "%.1fkm".format(m / 1000.0) else "${m.toInt()}m"

    private fun setBand(color: Int, big: String, sub: String) {
        (band.background as GradientDrawable).setColor(color)
        band.text = android.text.SpannableStringBuilder().apply {
            append(big); setSpan(android.text.style.RelativeSizeSpan(1.3f), 0, big.length, 0); setSpan(FontSpan(jua), 0, big.length, 0)
            if (sub.isNotEmpty()) append("\n").append(sub)
        }
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
        val sinceUp = if (DriveState.lastUpAt == 0L) Long.MAX_VALUE else SystemClock.elapsedRealtime() - DriveState.lastUpAt
        val next = st?.nextCamera
        val restore = cfg.restoreAfterM.toInt()

        // ---- 문제 목록
        val problems = ArrayList<String>()
        if (running && !sim) {
            if (camCount == 0) problems += "카메라 데이터 없음"
            if (out.ble && !S3xyButton.ready) problems += "커맨더 연결 안 됨"
        }

        // ---- 상태 띠 (내리는 중·유지·복귀 완료·GPS 없음은 감속 중이어도 주황)
        when {
            !running -> setBand(t.bandOff, "○ 꺼짐",
                if (autoOn) "감시 켜기를 누르거나, ${Prefs.autoDeviceName(this).ifEmpty { "차" }}에 연결되면 자동 시작" else "감시 켜기를 누르세요")
            sim -> setBand(t.bandSlow, "가상 주행 중", st?.note ?: "준비")
            st == null -> setBand(t.bandProblem, "GPS 대기 중", "위치를 잡으면 카메라를 찾기 시작합니다")
            gpsLost -> setBand(t.bandProblem, if (slowed) "GPS 없음 ${age}초 · ${out.slow.label} 유지 중" else "GPS 없음 ${age}초",
                if (DriveState.drActive) "추측 항법: 감속만 하고 복귀는 GPS가 돌아온 뒤에" else "카메라를 감지하지 못합니다")
            slowed && prog != null && progKind == S3xyButton.Press.SINGLE ->
                setBand(t.bandSlow, "▼ ${out.slow.label}로 내리는 중", "휠 아래 클릭 ${prog.first}/${prog.second} 보냄 · 커맨더 응답 대기")
            slowed && st.holdKind == "구간단속" -> {
                val remain = st.holdRemainM ?: 0.0
                val len = st.target?.sectionLenM ?: 0
                setBand(t.bandSlow, "${out.slow.label} 유지 · 구간단속 중",
                    (if (len > 0) "종점까지 ${km(remain - cfg.restoreAfterM)} 남음" else "길이 모름 · 종점 카메라까지(최대 ${km(remain)})") +
                        (if (out.autoRestore) " · 종점 지나 ${restore}m 뒤 ${out.usual.label}으로 복귀" else " · 복귀는 직접"))
            }
            slowed && st.target == null && st.zone != null ->
                setBand(t.bandSlow, "${out.slow.label} 유지 · 어린이보호구역", "${st.zone} · 반경 밖으로 나가면 복귀")
            slowed && st.holdKind == "통과" ->
                setBand(t.bandSlow, "${out.slow.label} 유지 · 카메라 통과", "${st.holdRemainM?.toInt() ?: 0}m 뒤 " + (if (out.autoRestore) "${out.usual.label}으로 복귀" else "복귀는 직접"))
            slowed -> setBand(t.bandSlow, "▼ ${out.slow.label}로 내림",
                (st.target?.let { tg -> "카메라 ${tg.name.take(28)} · " } ?: "") +
                    (if (st.target?.kind == CamKind.SECTION_START) "구간단속 시점 · 종점까지 유지" else if (out.autoRestore) "통과 후 ${restore}m에 복귀" else "복귀는 직접"))
            prog != null && progKind == S3xyButton.Press.DOUBLE ->
                setBand(t.bandOn, "▲ ${out.usual.label}으로 복귀 중", "휠 위 더블클릭 ${prog.first}/${prog.second} 보냄")
            car != null && car != out.usual -> setBand(t.bandProblem, "${car.label} 상태 · 복귀는 직접",
                "자동 복귀가 꺼져 있습니다. 휠을 올리거나 [지금 복귀]를 누르세요")
            sinceUp < 10_000 -> setBand(t.bandOn, "▲ ${out.usual.label}으로 복귀 완료",
                if (next != null) "다음 카메라 ${km(st.nextDistM ?: 0.0)}" else "2km 안에 카메라 없음")
            problems.isNotEmpty() -> setBand(t.bandProblem, "● 작동 중 · 문제", problems.joinToString(" · "))
            next != null -> setBand(t.bandOn, "● 작동 중 · 대기",
                "${(st.triggerM ?: 0.0).toInt()}m 앞에서 신호 · ${out.usual.label} → ${out.slow.label}" + (if (out.ble) ", 클릭 ${out.downPresses}번" else " (음성만)"))
            else -> setBand(t.bandOn, "● 작동 중",
                "2km 안에 카메라 없음 · " + (if (out.ble) "커맨더 준비" else "음성만") + " · ${out.usual.label} 유지")
        }

        // ---- 거리 · 속도 · 제한
        val limitCam = st?.target ?: next
        val limit = when {
            st == null -> null
            st.target == null && st.zone != null -> 30
            else -> limitCam?.limitKmh
        }
        signLabel.text = when {
            st?.holdKind == "구간단속" -> "구간 제한"
            st?.target != null -> "이 카메라"
            st?.target == null && st?.zone != null -> "보호구역"
            else -> "제한"
        }
        speedLabel.text = if (st?.estimated == true) "현재 km/h (추정)" else "현재 km/h"
        if (!running) {
            distView.text = "–"; lineView.text = "감시 꺼짐"; speedView.text = "–"; signView.text = "–"; subView.text = ""
        } else if (st == null) {
            distView.text = "GPS 대기"; lineView.text = "위치를 잡으면 카메라를 찾기 시작합니다"; speedView.text = "–"; signView.text = "–"; subView.text = ""
        } else {
            speedView.text = "${st.speedKmh.toInt()}"
            speedView.setTextColor(if (limit != null && st.speedKmh > limit + 5) t.cam else t.fg)
            signView.text = limit?.toString() ?: "–"
            if (next != null) {
                val dist = st.nextDistM ?: 0.0
                distView.text = "${"%,d".format(dist.toInt())} m"
                lineView.text = buildString {
                    append("다음 카메라")
                    if (next.kind == CamKind.SECTION_START) append(" · 구간단속 ${if (next.sectionLenM > 0) "%.1fkm".format(next.sectionLenM / 1000.0) else "길이 모름"}")
                    if (next.name.isNotEmpty()) append(" · ").append(next.name.take(18))
                }
                val trig = st.triggerM ?: 0.0
                subView.text = when {
                    slowed && st.target === next -> "이 카메라에 감속 신호를 보냈습니다 · ${st.note}"
                    slowed -> "감속 유지 중 · 다음 카메라는 지나면서 이어서 봅니다"
                    dist <= trig -> "신호 지점 안 · 곧 감속 신호"
                    else -> "감속 신호 ${trig.toInt()}m 앞에서 (지금 속도 기준)"
                }
            } else {
                distView.text = if (st.estimated) "추측 항법" else "카메라 없음"
                lineView.text = st.zone?.let { "어린이보호구역: $it" } ?: "2km 안에 카메라 없음"
                subView.text = st.note
            }
        }

        // ---- 지도
        map.data = if (running) DriveState.map else null
        map.caption = st?.road?.takeIf { !it.startsWith("도로 미확인") }?.substringBefore(" · 지도") ?: ""

        // ---- 프로필·버튼
        val usual = Prefs.usualProfile(this); val slow = Prefs.slowProfile(this)
        chipText(usualChip, if (running && car != null && car != usual) "평소 (차: ${car.label})" else "평소", usual.label, highlight = running && car == usual)
        chipText(slowChip, "카메라 앞", slow.label, highlight = running && car != null && car == slow && slow != usual)
        val showRelease = running && !sim && (slowed || (car != null && car != out.usual))
        releaseBtn.visibility = if (showRelease) View.VISIBLE else View.GONE
        releaseBtn.text = "▲ 지금 복귀 → ${out.usual.label}"
        toggleBtn.text = if (running) "■ 감시 끄기" else "▶ 감시 켜기"
        (toggleBtn.background as GradientDrawable).setColor(if (running) t.toggleOff else t.toggleOn)
        if (running && Prefs.keepScreenOn(this)) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        // ---- 지금 도로
        nowView.text = when {
            !running -> ""
            st?.road != null -> "지금: ${st.road}" + (DriveState.lastAccM?.let { " · GPS 정확도 ${it.toInt()}m" } ?: "")
            else -> DriveState.lastAccM?.let { "GPS 정확도 ${it.toInt()}m" } ?: ""
        }

        // ---- 점검표
        val gpsLine = when {
            !running -> "–  GPS: 켜면 확인"
            sim -> "–  GPS: 가상 주행 중"
            age == null -> "✖  GPS 아직 없음"
            age >= 15 -> "✖  GPS ${age}초째 없음" + (if (DriveState.drActive) " · 추측 항법 중" else "")
            else -> "✔  GPS ${age}초 전" + (DriveState.lastAccM?.let { " · 정확도 ${it.toInt()}m" } ?: "")
        }
        val dataLine = when (camCount) {
            null -> "–  카메라 데이터 읽는 중"
            0 -> "✖  카메라 데이터 없음 — 메뉴에서 경찰청 전국 CSV"
            else -> "✔  ${CameraRepo.summaryLine}"
        }
        val roadsLine = when {
            !Prefs.useRoads(this) -> if (CameraRepo.hasRoads(this)) "–  도로망 꺼짐(설정) — GPS 방향으로 판단" else null
            !CameraRepo.hasRoads(this) -> "✖  도로망 없음 — 메뉴에서 kr-roads.zip (옆 도로·반대편 오탐 거름)"
            running && !sim && st?.road != null -> (if (st.road.startsWith("도로 미확인")) "△  " else "✔  ") + st.road
            else -> "✔  도로망 ${CameraRepo.roadsInfo.ifEmpty { "있음" }}"
        }
        val cmdLine = when {
            !out.ble -> "–  커맨더 안 씀 (음성만)"
            S3xyButton.ready -> "✔  커맨더 연결됨 · 준비"
            else -> "✖  커맨더 연결 안 됨 (${S3xyButton.status})"
        }
        val soundLine = if (!out.alerts) "–  소리 끔" else "–  소리: " + when (out.route) {
            VoiceRoute.SPEAKER -> "폰 스피커"; VoiceRoute.CAR_CALL -> "차 (통화 채널)"; VoiceRoute.MEDIA -> "미디어 채널"
        }
        val autoLine = if (!autoOn) "–  자동 시작 꺼짐" else {
            val missing = AutoStart.missing(this)
            if (missing.isEmpty()) "✔  자동 시작: ${Prefs.autoDeviceName(this)} 연결 시" else "✖  자동 시작에 필요: ${missing.joinToString(", ")}"
        }
        val zoneLine = if (!Prefs.schoolZoneOn(this)) null else when (zoneCount) {
            null -> "–  보호구역 읽는 중"; 0 -> "✖  어린이보호구역 파일 없음 — 메뉴에서 불러오기"; else -> "✔  어린이보호구역 ${zoneCount}곳 · 반경 ${Prefs.schoolZoneRadiusM(this)}m"
        }
        checkView.text = listOfNotNull(gpsLine, dataLine, roadsLine, cmdLine, soundLine, autoLine, zoneLine).joinToString("\n")

        // ---- 기록
        val lines = DriveState.log
        logHead.text = if (logExpanded) "기록   접기 ▴" else "기록   전체 보기 ›"
        logView.text = (if (logExpanded) lines else lines.take(5)).joinToString("\n")
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
        private const val M_ROADS = 110
        private const val M_TEST_SOUND = 111
    }
}
