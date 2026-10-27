package com.jsbeth.camslow

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.TextView
import android.widget.Toast

/**
 * 메인 화면(v0.6): 위에서부터
 *  현재 속도 | 제한속도 표지판 → 프로필 4칸(파란 칸 = 차에 보내 둔 프로필) → 동작 띠(대기/내리는 중/유지 중/복귀 완료)
 *  → 다음 카메라 거리·막대(빨간 눈금 = 신호 지점) → 점검 칩 → [지금 복귀] [감시 켜기/끄기] → 기록
 */
class MainActivity : Activity() {

    private lateinit var ui: Ui
    private lateinit var gauges: Ui.Gauges
    private lateinit var profileChips: List<TextView>
    private lateinit var band: TextView
    private lateinit var bandBar: TrackBar
    private lateinit var distView: TextView
    private lateinit var infoView: TextView
    private lateinit var nextBar: TrackBar
    private lateinit var hintView: TextView
    private lateinit var chips: FlowLayout
    private lateinit var releaseBtn: Button
    private lateinit var toggleBtn: Button
    private lateinit var logHead: TextView
    private lateinit var logView: TextView
    private lateinit var outputs: Outputs
    private var logExpanded = false
    private val listener = { render() }
    private val main = Handler(Looper.getMainLooper())
    private val tick = object : Runnable { override fun run() { render(); main.postDelayed(this, 1_000) } }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        outputs = Outputs(this)
        ui = Ui(this)
        gauges = ui.gauges()
        profileChips = ui.profileRow()
        band = ui.band()
        bandBar = ui.trackBar(8)
        val (d, i) = ui.bigRow(); distView = d; infoView = i
        nextBar = ui.trackBar(10)
        hintView = ui.text("", 12f, color = Ui.MUTED)
        chips = ui.chips()
        releaseBtn = ui.bigButton("▲ 지금 복귀", Ui.ORANGE) { DriveService.send(this, DriveService.ACTION_RELEASE) }
        toggleBtn = ui.bigButton("감시 켜기", Ui.TEAL) { if (DriveState.running) DriveService.send(this, DriveService.ACTION_STOP) else startDrive() }
        logHead = ui.text("기록   전체 보기 ›", 13f, bold = true).apply { setOnClickListener { logExpanded = !logExpanded; render() } }
        logView = ui.text("", 12f)
        ui.show()
        askPermissions()
        if (intent?.getBooleanExtra(AutoStart.EXTRA_AUTOSTART, false) == true) startDrive()
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        if (intent?.getBooleanExtra(AutoStart.EXTRA_AUTOSTART, false) == true) startDrive()
    }

    // ---- 오른쪽 위 메뉴: "설정"은 항상 보이고 나머지 도구는 ⋮ 안에
    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menu.add(0, M_SETTINGS, 0, "설정").setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS or MenuItem.SHOW_AS_ACTION_WITH_TEXT)
        menu.add(0, M_CSV, 1, "카메라 데이터 (경찰청 전국 CSV)")
        menu.add(0, M_KEC_SECTIONS, 2, "도로공사 구간단속 CSV")
        menu.add(0, M_KEC_POINTS, 3, "도로공사 이정좌표 CSV")
        menu.add(0, M_ZONES, 4, "어린이보호구역 CSV")
        menu.add(0, M_TEST_SOUND, 5, "테스트: 안내 소리")
        menu.add(0, M_TEST_DOWN, 6, "테스트: 감속 신호")
        menu.add(0, M_TEST_UP, 7, "테스트: 복귀 신호")
        menu.add(0, M_SIM, 8, "가상 주행 테스트 (주차 중)")
        menu.add(0, M_PAIR, 9, "커맨더 페어링 모드 켜기/끄기")
        menu.add(0, M_SCAN, 10, "BLE 스캔 (진단용)")
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            M_SETTINGS -> startActivity(Intent(this, SettingsActivity::class.java))
            M_CSV -> pickCsv(REQ_CSV)
            M_KEC_SECTIONS -> pickCsv(REQ_KEC_SECTIONS)
            M_KEC_POINTS -> pickCsv(REQ_KEC_POINTS)
            M_ZONES -> pickCsv(REQ_ZONES)
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
        val lookahead = cfg.lookaheadM

        // ---- 계기: 속도, 제한속도
        val limitCam = st?.target ?: next
        val limit = when {
            st == null -> null
            st.target == null && st.zone != null -> 30
            else -> limitCam?.limitKmh
        }
        val speed = if (!running || st == null) null else st.speedKmh.toInt()
        gauges.speedLabel.text = if (st?.estimated == true) "현재 속도 (추정)" else "현재 속도"
        ui.setSpeed(gauges, speed, when { speed == null -> 0xFF9E9E9E.toInt(); limit != null && speed > limit -> Ui.ORANGE; else -> Ui.GREEN })
        gauges.limitLabel.text = when {
            st?.holdKind == "구간단속" -> "구간 제한"
            st?.target != null -> "이 카메라 제한"
            st?.target == null && st?.zone != null -> "보호구역 제한"
            else -> "다음 카메라 제한"
        }
        ui.setSign(gauges, limit)

        // ---- 프로필 4칸
        for ((i, v) in profileChips.withIndex()) {
            val p = Profile.values()[i]
            ui.setProfileChip(v, cur = running && car == p, usual = p == out.usual)
        }

        // ---- 문제 목록
        val problems = ArrayList<String>()
        if (running && !sim) {
            if (gpsLost) problems += if (DriveState.drActive) "GPS 없음 ${age}초 · 추측 항법" else "GPS 없음 ${age}초"
            if (camCount == 0) problems += "카메라 데이터 없음"
            if (out.ble && !S3xyButton.ready) problems += "커맨더 연결 안 됨"
        }

        // ---- 동작 띠 + 막대
        var bar: Triple<Float, Float?, Int>? = null
        val restore = cfg.restoreAfterM.toInt()
        when {
            !running -> ui.setBand(band, Ui.GREY, "○ 꺼짐",
                if (autoOn) "감시 켜기를 누르거나, ${Prefs.autoDeviceName(this).ifEmpty { "차" }}에 연결되면 자동 시작" else "감시 켜기를 누르세요")
            sim -> ui.setBand(band, Ui.BLUE, "가상 주행 중", st?.note ?: "준비")
            st == null -> ui.setBand(band, Ui.ORANGE, "GPS 대기 중", "위치를 잡으면 카메라를 찾기 시작합니다")
            gpsLost -> ui.setBand(band, Ui.ORANGE, if (slowed) "GPS 없음 ${age}초 · ${out.slow.label} 유지 중" else "GPS 없음 ${age}초",
                if (DriveState.drActive) "추측 항법: 감속만 하고 복귀는 GPS가 돌아온 뒤에" else "카메라를 감지하지 못합니다")
            slowed && prog != null && progKind == S3xyButton.Press.SINGLE -> {
                ui.setBand(band, Ui.BLUE, "▼ ${out.slow.label}로 내리는 중", "휠 아래 클릭 ${prog.first}/${prog.second} 보냄 · 커맨더 응답 대기")
                bar = Triple(prog.first.toFloat() / prog.second.coerceAtLeast(1), null, 0xFFFFFFFF.toInt())
            }
            slowed && st.holdKind == "구간단속" -> {
                val remain = st.holdRemainM ?: 0.0
                val len = st.target?.sectionLenM ?: 0
                ui.setBand(band, Ui.BLUE, "${out.slow.label} 유지 · 구간단속 중",
                    (if (len > 0) "종점까지 ${km(remain - cfg.restoreAfterM)} 남음" else "길이 모름 · 종점 카메라까지(최대 ${km(remain)})") +
                        (if (out.autoRestore) " · 종점 지나 ${restore}m 뒤 ${out.usual.label}으로 복귀" else " · 복귀는 직접"))
                if (len > 0) bar = Triple((1.0 - (remain - cfg.restoreAfterM) / len).toFloat().coerceIn(0f, 1f), null, 0xFFFFFFFF.toInt())
            }
            slowed && st.target == null && st.zone != null ->
                ui.setBand(band, Ui.BLUE, "${out.slow.label} 유지 · 어린이보호구역", "${st.zone} · 반경 밖으로 나가면 복귀")
            slowed && st.holdKind == "통과" ->
                ui.setBand(band, Ui.BLUE, "${out.slow.label} 유지 · 카메라 통과", "${st.holdRemainM?.toInt() ?: 0}m 뒤 " + (if (out.autoRestore) "${out.usual.label}으로 복귀" else "복귀는 직접"))
            slowed -> ui.setBand(band, Ui.BLUE, "▼ ${out.slow.label}로 내림",
                (st.target?.let { t -> "카메라 ${t.name.take(28)} · " } ?: "") +
                    (if (st.target?.kind == CamKind.SECTION_START) "구간단속 시점 · 종점까지 유지" else if (out.autoRestore) "통과 후 ${restore}m에 복귀" else "복귀는 직접"))
            prog != null && progKind == S3xyButton.Press.DOUBLE -> {
                ui.setBand(band, Ui.GREEN, "▲ ${out.usual.label}으로 복귀 중", "휠 위 더블클릭 ${prog.first}/${prog.second} 보냄")
                bar = Triple(prog.first.toFloat() / prog.second.coerceAtLeast(1), null, 0xFFFFFFFF.toInt())
            }
            car != null && car != out.usual -> ui.setBand(band, Ui.ORANGE, "${car.label} 상태 · 복귀는 직접",
                "자동 복귀가 꺼져 있습니다. 휠을 올리거나 [지금 복귀]를 누르세요")
            sinceUp < 10_000 -> ui.setBand(band, Ui.GREEN, "▲ ${out.usual.label}으로 복귀 완료",
                (if (next != null) "다음 카메라 ${km(st.nextDistM ?: 0.0)}" else "2km 안에 카메라 없음"))
            problems.isNotEmpty() -> ui.setBand(band, Ui.ORANGE, "● 작동 중 · 문제", problems.joinToString(" · "))
            next != null -> ui.setBand(band, Ui.GREEN, "● 작동 중 · 대기",
                "${(st.triggerM ?: 0.0).toInt()}m 앞에서 신호 · ${out.usual.label} → ${out.slow.label}" + (if (out.ble) ", 클릭 ${out.downPresses}번" else " (음성만)"))
            else -> ui.setBand(band, Ui.GREEN, "● 작동 중",
                "2km 안에 카메라 없음 · " + (if (out.ble) "커맨더 준비" else "음성만") + " · ${out.usual.label} 유지")
        }
        if (bar != null) { bandBar.visibility = View.VISIBLE; bandBar.set(bar.first, bar.second, bar.third) } else bandBar.visibility = View.GONE

        // ---- 다음 카메라
        if (!running || st == null) {
            distView.text = if (running) "GPS 대기" else "—"; infoView.text = if (running) "" else "감시가 꺼져 있습니다"
            nextBar.visibility = View.GONE; hintView.text = ""
        } else if (next != null) {
            val dist = st.nextDistM ?: 0.0
            distView.text = android.text.SpannableStringBuilder().apply {
                append("%,d".format(dist.toInt())); append(" m"); setSpan(android.text.style.RelativeSizeSpan(0.45f), length - 2, length, 0)
            }
            infoView.text = buildString {
                append(next.name.take(40))
                append("\n제한 ${next.limitKmh}")
                if (next.kind == CamKind.SECTION_START) append(" · 구간단속 ${if (next.sectionLenM > 0) "%.1fkm".format(next.sectionLenM / 1000.0) else "길이 모름"}")
                if (next.bearingDeg != null) append(" · 방향 확인됨")
            }
            val trig = st.triggerM ?: 0.0
            nextBar.visibility = View.VISIBLE
            nextBar.set((1.0 - dist / lookahead).toFloat().coerceIn(0f, 1f), if (trig in 1.0..lookahead) (1.0 - trig / lookahead).toFloat() else null,
                if (slowed) Ui.BLUE else Ui.GREEN)
            hintView.text = when {
                slowed && st.target === next -> "이 카메라에 감속 신호를 보냈습니다"
                slowed -> "감속 유지 중 · 다음 카메라는 지나면서 이어서 봅니다"
                dist <= trig -> "신호 지점 안 · 곧 감속 신호"
                else -> "빨간 눈금 = 신호를 보낼 지점 (지금 속도면 ${trig.toInt()}m 앞)"
            }
        } else {
            distView.text = "—"
            infoView.text = if (st.estimated) "추측 항법 중 · 2km 안에 카메라 없음" else "2km 안에 카메라 없음"
            nextBar.visibility = View.GONE
            hintView.text = st.zone?.let { "어린이보호구역: $it" } ?: ""
        }

        // ---- 점검 칩
        chips.removeAllViews()
        when {
            !running -> ui.chip(chips, "GPS: 켜면 확인", Ui.ChipState.NEUTRAL)
            sim -> ui.chip(chips, "GPS 가상", Ui.ChipState.NEUTRAL)
            age == null -> ui.chip(chips, "GPS 아직 없음", Ui.ChipState.BAD)
            age >= 15 -> ui.chip(chips, "GPS ${age}초 없음" + (if (DriveState.drActive) " · 추측 항법" else ""), Ui.ChipState.BAD)
            else -> ui.chip(chips, "GPS ${age}초" + (DriveState.lastAccM?.let { " · ${it.toInt()}m" } ?: ""), Ui.ChipState.OK)
        }
        when (camCount) {
            null -> ui.chip(chips, "데이터 읽는 중", Ui.ChipState.NEUTRAL)
            0 -> ui.chip(chips, "카메라 데이터 없음", Ui.ChipState.BAD)
            else -> ui.chip(chips, CameraRepo.summaryLine, Ui.ChipState.OK)
        }
        when {
            !out.ble -> ui.chip(chips, "커맨더 안 씀 (음성만)", Ui.ChipState.NEUTRAL)
            S3xyButton.ready -> ui.chip(chips, "커맨더 준비", Ui.ChipState.OK)
            else -> ui.chip(chips, "커맨더 연결 안 됨", Ui.ChipState.BAD)
        }
        ui.chip(chips, if (!out.alerts) "소리 끔" else when (out.route) {
            VoiceRoute.SPEAKER -> "소리: 폰 스피커"; VoiceRoute.CAR_CALL -> "소리: 차(통화 채널)"; VoiceRoute.MEDIA -> "소리: 미디어 채널"
        }, Ui.ChipState.NEUTRAL)
        if (!autoOn) ui.chip(chips, "자동 시작 꺼짐", Ui.ChipState.NEUTRAL) else {
            val missing = AutoStart.missing(this)
            if (missing.isEmpty()) ui.chip(chips, "자동 시작: ${Prefs.autoDeviceName(this)}", Ui.ChipState.OK)
            else ui.chip(chips, "자동 시작에 필요: ${missing.joinToString(", ")}", Ui.ChipState.BAD)
        }
        if (Prefs.schoolZoneOn(this)) when (zoneCount) {
            null -> ui.chip(chips, "보호구역 읽는 중", Ui.ChipState.NEUTRAL)
            0 -> ui.chip(chips, "보호구역 파일 없음", Ui.ChipState.BAD)
            else -> ui.chip(chips, "보호구역 ${zoneCount}곳 · ${Prefs.schoolZoneRadiusM(this)}m", Ui.ChipState.OK)
        }

        // ---- 버튼·화면
        val showRelease = running && !sim && (slowed || (car != null && car != out.usual))
        releaseBtn.visibility = if (showRelease) View.VISIBLE else View.GONE
        ui.setButton(releaseBtn, "▲ 지금 복귀 → ${out.usual.label}", Ui.ORANGE)
        if (running) ui.setButton(toggleBtn, "감시 끄기", Ui.RED) else ui.setButton(toggleBtn, "감시 켜기", Ui.TEAL)
        if (running && Prefs.keepScreenOn(this)) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        // ---- 기록
        val lines = DriveState.log
        logHead.text = if (logExpanded) "기록   접기 ▴" else "기록   전체 보기 ›"
        logView.text = (if (logExpanded) lines else lines.take(5)).joinToString("\n")
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        val uri = data?.data
        if (requestCode !in listOf(REQ_CSV, REQ_KEC_SECTIONS, REQ_KEC_POINTS, REQ_ZONES) || resultCode != RESULT_OK || uri == null) return
        Toast.makeText(this, "불러오는 중…", Toast.LENGTH_SHORT).show()
        Thread {
            val msg = try {
                val bytes = contentResolver.openInputStream(uri)!!.use { it.readBytes() }
                val note = when (requestCode) {
                    REQ_KEC_SECTIONS -> CameraRepo.importKecSections(this, bytes)
                    REQ_KEC_POINTS -> CameraRepo.importKecPoints(this, bytes)
                    REQ_ZONES -> CameraRepo.importZones(this, bytes)
                    else -> "카메라 ${CameraRepo.import(this, bytes)}개"
                }
                note + "\n" + CameraRepo.summary(this)
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
        private const val M_TEST_SOUND = 110
    }
}
