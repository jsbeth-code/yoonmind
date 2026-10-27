package com.jsbeth.camslow

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.TextView
import android.widget.Toast

class MainActivity : Activity() {

    private lateinit var stateView: TextView
    private lateinit var nextView: TextView
    private lateinit var setupView: TextView
    private lateinit var dataView: TextView
    private lateinit var logView: TextView
    private lateinit var outputs: Outputs
    private val listener = { render() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        outputs = Outputs(this)
        val ui = Ui(this)
        stateView = ui.text("대기", 30f, bold = true)
        nextView = ui.text("", 18f)
        setupView = ui.text("", 13f)
        dataView = ui.text("", 13f)
        ui.row(
            ui.button("주행 시작") { startDrive() },
            ui.button("정지") { DriveService.send(this, DriveService.ACTION_STOP) },
        )
        ui.button("카메라 데이터 불러오기 (경찰청 전국 CSV)") { pickCsv(REQ_CSV) }
        ui.row(
            ui.button("도로공사 구간단속 CSV") { pickCsv(REQ_KEC_SECTIONS) },
            ui.button("도로공사 이정좌표 CSV") { pickCsv(REQ_KEC_POINTS) },
        )
        ui.row(
            ui.button("테스트: 감속 신호") {
                outputs.refresh()
                outputs.down(Camera(0.0, 0.0, 100, CamKind.SPOT, 0, "테스트"), 300.0)
            },
            ui.button("테스트: 복귀 신호") { outputs.refresh(); outputs.up("테스트", force = true) },
        )
        ui.button("가상 주행 테스트 (주차 중 — 끝나면 감시도 꺼짐)") {
            if (hasLocationPermission()) DriveService.send(this, DriveService.ACTION_SIMULATE) else askPermissions()
        }
        ui.button("커맨더 페어링 모드 (ENH_BTN 광고 켜기/끄기)") { togglePairing() }
        ui.row(
            ui.button("설정") { startActivity(Intent(this, SettingsActivity::class.java)) },
            ui.button("BLE 스캔 (진단용)") { startActivity(Intent(this, CaptureActivity::class.java)) },
        )
        ui.text("기록", 13f, bold = true)
        logView = ui.text("", 12f)
        ui.show()
        askPermissions()
        if (intent?.getBooleanExtra(AutoStart.EXTRA_AUTOSTART, false) == true) startDrive()
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        if (intent?.getBooleanExtra(AutoStart.EXTRA_AUTOSTART, false) == true) startDrive()
    }

    private fun pickCsv(req: Int) = startActivityForResult(
        Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*"), req,
    )

    /** 주행과 무관하게 ENH_BTN 광고를 켜 둔다. 커맨더 앱에서 버튼을 추가할 때 쓴다. */
    private fun togglePairing() {
        if (S3xyButton.running && S3xyButton.pairingMode) {
            S3xyButton.stop(); render(); return
        }
        if (S3xyButton.running) { Toast.makeText(this, "주행 감시가 켜 둔 상태입니다 — 이미 광고 중", Toast.LENGTH_SHORT).show(); return }
        val ok = S3xyButton.start(this, { DriveState.log(it) }, Prefs.renameAdapter(this))
        S3xyButton.pairingMode = ok
        Toast.makeText(this, if (ok) "ENH_BTN 광고 중 — 커맨더 앱에서 Add S3XY Button" else "시작 실패 — 기록을 보세요", Toast.LENGTH_LONG).show()
        render()
    }

    private fun hasLocationPermission() =
        checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

    private fun startDrive() {
        if (!hasLocationPermission()) {
            Toast.makeText(this, "위치 권한을 먼저 허용하세요", Toast.LENGTH_LONG).show()
            askPermissions(); return
        }
        if (CameraRepo.cachedCount() == 0) {
            Toast.makeText(this, "카메라 데이터가 없습니다 — CSV를 먼저 불러오세요", Toast.LENGTH_LONG).show(); return
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
        Thread {
            val summary = CameraRepo.summary(this)
            runOnUiThread { dataView.text = summary; render() }
        }.start()
    }

    override fun onPause() {
        DriveState.removeListener(listener)
        super.onPause()
    }

    override fun onDestroy() {
        outputs.shutdown()
        super.onDestroy()
    }

    private fun render() {
        val st = DriveState.status
        stateView.text = when {
            !DriveState.running -> "꺼짐"
            st == null -> if (DriveState.simulating) "가상 주행 준비" else "GPS 대기"
            st.slowed -> "▼ 감속 중"
            else -> "대기"
        }
        nextView.text = if (st == null) "" else buildString {
            append("현재 ${st.speedKmh.toInt()} km/h · ${st.note}\n")
            val c = st.nextCamera
            if (c != null) {
                append("다음 카메라 ${st.nextDistM?.toInt()}m · 제한 ${c.limitKmh}")
                if (c.kind == CamKind.SECTION_START) append(" · 구간단속 ${if (c.sectionLenM > 0) "${c.sectionLenM}m" else "길이 모름"}")
                append("\n${c.name}")
                DriveState.config?.let { cfg ->
                    val d = SlowdownEngine(CameraIndex(emptyList()), cfg).triggerDistance(st.speedKmh / 3.6, c.limitKmh)
                    append("\n감속 신호 시작: ${d.toInt()}m 앞 (지금 속도 기준)")
                }
            } else append("2km 안에 카메라 없음")
        }
        val out = DriveState.outputs ?: outputs.settings
        val cfg = DriveState.config ?: Prefs.engine(this)
        setupView.text = buildString {
            append(out.describe())
            append("\n감속 시작: ")
            append(if (cfg.fixedTriggerM > 0) "카메라 ${cfg.fixedTriggerM}m 앞(고정)" else "속도별 자동(감속도 ${cfg.decelMps2}, 반응 ${cfg.reactionS}s, 여유 ${cfg.marginM.toInt()}m)")
            if (cfg.bleLatencyS > 0) append(" + BLE 송출 ${"%.1f".format(cfg.bleLatencyS)}s")
            append(" · 통과 후 ${cfg.restoreAfterM.toInt()}m")
            if (out.ble || S3xyButton.running) append("\n가상 버튼: ${S3xyButton.status}")
        }
        logView.text = DriveState.log.joinToString("\n")
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        val uri = data?.data
        if (requestCode !in listOf(REQ_CSV, REQ_KEC_SECTIONS, REQ_KEC_POINTS) || resultCode != RESULT_OK || uri == null) return
        dataView.text = "불러오는 중..."
        Thread {
            val msg = try {
                val bytes = contentResolver.openInputStream(uri)!!.use { it.readBytes() }
                val note = when (requestCode) {
                    REQ_KEC_SECTIONS -> CameraRepo.importKecSections(this, bytes)
                    REQ_KEC_POINTS -> CameraRepo.importKecPoints(this, bytes)
                    else -> "카메라 ${CameraRepo.import(this, bytes)}개"
                }
                note + "\n" + CameraRepo.summary(this)
            } catch (e: Exception) {
                "불러오기 실패: ${e.message}"
            }
            runOnUiThread {
                dataView.text = msg
                Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
                if (DriveState.running && !DriveState.simulating) DriveService.send(this, DriveService.ACTION_RELOAD)
            }
        }.start()
    }

    companion object {
        private const val REQ_CSV = 10
        private const val REQ_KEC_SECTIONS = 11
        private const val REQ_KEC_POINTS = 12
    }
}
