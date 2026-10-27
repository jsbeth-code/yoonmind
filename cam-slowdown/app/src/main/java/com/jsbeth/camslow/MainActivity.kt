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
import android.view.WindowManager
import android.widget.Button
import android.widget.TextView
import android.widget.Toast

class MainActivity : Activity() {

    private lateinit var band: TextView
    private lateinit var distView: TextView
    private lateinit var lineView: TextView
    private lateinit var nameView: TextView
    private lateinit var trigView: TextView
    private lateinit var speedView: TextView
    private lateinit var checkView: TextView
    private lateinit var toggleBtn: Button
    private lateinit var logHead: TextView
    private lateinit var logView: TextView
    private lateinit var ui: Ui
    private lateinit var outputs: Outputs
    private var logExpanded = false
    private val listener = { render() }
    private val main = Handler(Looper.getMainLooper())
    private val tick = object : Runnable { override fun run() { render(); main.postDelayed(this, 1_000) } }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        outputs = Outputs(this)
        ui = Ui(this)
        band = ui.band()
        distView = ui.text("", 44f, bold = true)
        lineView = ui.text("", 15f)
        nameView = ui.text("", 13f, color = Ui.MUTED)
        trigView = ui.text("", 13f)
        speedView = ui.text("", 13f, color = Ui.MUTED)
        checkView = ui.text("", 13f)
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
        menu.add(0, M_TEST_DOWN, 5, "테스트: 감속 신호")
        menu.add(0, M_TEST_UP, 6, "테스트: 복귀 신호")
        menu.add(0, M_SIM, 7, "가상 주행 테스트 (주차 중)")
        menu.add(0, M_PAIR, 8, "커맨더 페어링 모드 켜기/끄기")
        menu.add(0, M_SCAN, 9, "BLE 스캔 (진단용)")
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            M_SETTINGS -> startActivity(Intent(this, SettingsActivity::class.java))
            M_CSV -> pickCsv(REQ_CSV)
            M_KEC_SECTIONS -> pickCsv(REQ_KEC_SECTIONS)
            M_KEC_POINTS -> pickCsv(REQ_KEC_POINTS)
            M_ZONES -> pickCsv(REQ_ZONES)
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

        // ---- 문제 목록
        val problems = ArrayList<String>()
        if (running && !sim) {
            if (age == null && st == null) problems += "GPS 대기 중"
            else if (age != null && age >= 15) problems += if (DriveState.drActive) "GPS 없음 ${age}초 · 추측 항법" else "GPS 없음 ${age}초"
            if (camCount == 0) problems += "카메라 데이터 없음"
            if (out.ble && !S3xyButton.ready) problems += "커맨더 연결 안 됨"
        }

        // ---- 상태 띠
        when {
            !running -> ui.setBand(band, Ui.GREY, "○ 꺼짐",
                if (autoOn) "감시 켜기를 누르거나, ${Prefs.autoDeviceName(this).ifEmpty { "차" }}에 연결되면 자동 시작" else "감시 켜기를 누르세요")
            sim -> ui.setBand(band, Ui.BLUE, "가상 주행 중", st?.note ?: "준비")
            st?.slowed == true -> ui.setBand(band, Ui.BLUE, "▼ 감속 중",
                (st.zone?.let { "어린이보호구역 · " } ?: "") + "${out.slow.label}로 내림 · " +
                    (if (out.autoRestore) (st.target?.let { t -> if (t.kind == CamKind.SECTION_START) "구간 종점 지나 ${cfg.restoreAfterM.toInt()}m 뒤 복귀" else "통과 후 ${cfg.restoreAfterM.toInt()}m에 복귀" } ?: "벗어나면 복귀") else "복귀는 직접"))
            problems.isNotEmpty() -> ui.setBand(band, Ui.ORANGE, "● 작동 중 · 문제", problems.joinToString(" · "))
            else -> ui.setBand(band, Ui.GREEN, "● 작동 중",
                "GPS 정상 · " + (if (out.ble) "커맨더 준비" else "음성만") + " · ${out.usual.label} → ${out.slow.label}")
        }

        // ---- 다음 카메라
        if (!running) {
            distView.text = ""; lineView.text = ""; nameView.text = ""; trigView.text = ""; speedView.text = ""
        } else if (st == null) {
            distView.text = "GPS 대기 중"; distView.textSize = 24f
            lineView.text = "위치를 잡으면 카메라를 찾기 시작합니다"; nameView.text = ""; trigView.text = ""; speedView.text = ""
        } else {
            val c = st.nextCamera
            if (c != null) {
                distView.textSize = 44f
                distView.text = "${"%,d".format(st.nextDistM?.toInt() ?: 0)} m"
                lineView.text = buildString {
                    append("다음 카메라 · 제한 ${c.limitKmh}")
                    if (c.kind == CamKind.SECTION_START) append(" · 구간단속 ${if (c.sectionLenM > 0) "%.1fkm".format(c.sectionLenM / 1000.0) else "길이 모름"}")
                    if (c.bearingDeg != null) append(" · 방향 확인됨")
                }
                nameView.text = c.name
                val d = SlowdownEngine(CameraIndex(emptyList()), cfg).triggerDistance(st.speedKmh / 3.6, c.limitKmh)
                trigView.text = if (st.slowed) st.note else "감속 신호 ${d.toInt()}m 앞에서 (지금 속도 기준)"
            } else {
                distView.textSize = 24f
                distView.text = if (st.estimated) "추측 항법 중" else "2km 안에 카메라 없음"
                lineView.text = st.zone?.let { "어린이보호구역: $it" } ?: ""; nameView.text = ""; trigView.text = st.note
            }
            speedView.text = "현재 ${st.speedKmh.toInt()} km/h" + (if (st.estimated) " (추정)" else "")
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
        val cmdLine = when {
            !out.ble -> "–  커맨더 안 씀 (음성만)"
            S3xyButton.ready -> "✔  커맨더 연결됨 · 준비"
            else -> "✖  커맨더 연결 안 됨 (${S3xyButton.status})"
        }
        val autoLine = if (!autoOn) "–  자동 시작 꺼짐" else {
            val missing = AutoStart.missing(this)
            if (missing.isEmpty()) "✔  자동 시작: ${Prefs.autoDeviceName(this)} 연결 시" else "✖  자동 시작에 필요: ${missing.joinToString(", ")}"
        }
        val zoneLine = if (!Prefs.schoolZoneOn(this)) null else when (zoneCount) {
            null -> "–  보호구역 읽는 중"; 0 -> "✖  어린이보호구역 파일 없음 — 메뉴에서 불러오기"; else -> "✔  어린이보호구역 ${zoneCount}곳 · 반경 ${Prefs.schoolZoneRadiusM(this)}m"
        }
        checkView.text = listOfNotNull(gpsLine, dataLine, cmdLine, autoLine, zoneLine).joinToString("\n")

        // ---- 버튼·화면
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
    }
}
