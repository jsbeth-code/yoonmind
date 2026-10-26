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
        dataView = ui.text("", 13f)
        ui.row(
            ui.button("주행 시작") { DriveService.send(this, DriveService.ACTION_START) },
            ui.button("정지") { DriveService.send(this, DriveService.ACTION_STOP) },
        )
        ui.button("카메라 데이터 불러오기 (CSV)") {
            startActivityForResult(
                Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*"),
                REQ_CSV,
            )
        }
        ui.row(
            ui.button("테스트: 감속 신호") {
                outputs.down(Camera(0.0, 0.0, 100, CamKind.SPOT, 0, "테스트"), 300.0)
            },
            ui.button("테스트: 복귀 신호") { outputs.up("테스트") },
        )
        ui.button("가상 주행 테스트 (주차 중)") { DriveService.send(this, DriveService.ACTION_SIMULATE) }
        ui.row(
            ui.button("설정") { startActivity(Intent(this, SettingsActivity::class.java)) },
            ui.button("버튼 신호 캡처") { startActivity(Intent(this, CaptureActivity::class.java)) },
        )
        ui.text("기록", 13f, bold = true)
        logView = ui.text("", 12f)
        ui.show()
        askPermissions()
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
        Thread {
            val n = CameraRepo.count(this)
            runOnUiThread { dataView.text = if (n == 0) "카메라 데이터 없음 — CSV를 불러오세요" else "카메라 ${n}개 로드됨"; render() }
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
            st == null -> "GPS 대기"
            st.slowed -> "▼ 감속 중"
            else -> "대기"
        }
        nextView.text = if (st == null) "" else buildString {
            append("현재 ${st.speedKmh.toInt()} km/h · ${st.note}\n")
            val c = st.nextCamera
            if (c != null) append("다음 카메라 ${st.nextDistM?.toInt()}m · 제한 ${c.limitKmh}\n${c.name}")
            else append("2km 안에 카메라 없음")
        }
        logView.text = DriveState.log.joinToString("\n")
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        val uri = data?.data
        if (requestCode != REQ_CSV || resultCode != RESULT_OK || uri == null) return
        dataView.text = "불러오는 중..."
        Thread {
            val msg = try {
                val bytes = contentResolver.openInputStream(uri)!!.use { it.readBytes() }
                "카메라 ${CameraRepo.import(this, bytes)}개 로드됨"
            } catch (e: Exception) {
                "불러오기 실패: ${e.message}"
            }
            runOnUiThread {
                dataView.text = msg
                Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
                if (DriveState.running && !DriveState.simulating) DriveService.send(this, DriveService.ACTION_START)
            }
        }.start()
    }

    companion object { private const val REQ_CSV = 10 }
}
