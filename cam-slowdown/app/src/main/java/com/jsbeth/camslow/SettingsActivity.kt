package com.jsbeth.camslow

import android.Manifest
import android.app.Activity
import android.bluetooth.BluetoothManager
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.TextView
import android.widget.Toast

class SettingsActivity : Activity() {
    private lateinit var autoStatus: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val ui = Ui(this)
        val c = Prefs.engine(this)

        ui.text("프로필 (FSD v14 오른쪽 휠 = 속도 프로필)", 18f, bold = true)
        val usual = ui.choice("평소 프로필 (복귀 목표)", Profile.labels, Prefs.usualProfile(this).ordinal)
        val slow = ui.choice("카메라 앞에서 내릴 프로필", Profile.labels, Prefs.slowProfile(this).ordinal)
        val extra = ui.number("여분 클릭 (내릴 프로필이 Sloth일 때만, 클릭 유실 대비. 바닥에선 추가 클릭이 무시됨)", Prefs.extraDownClicks(this).toString(), decimal = false)
        val autoRestore = ui.check("카메라 통과 후 자동으로 평소 프로필로 올리기 (끄면 음성만, 휠로 직접)", Prefs.autoRestore(this))
        ui.text("클릭 수는 두 프로필의 차이로 정해진다. 예: Hurry→Sloth = 아래 3번(+여분), 복귀 위 3번. 복귀는 정확히 내린 만큼만 올린다.", 12f)

        ui.text("감속 시작 시점", 18f, bold = true)
        val fixed = ui.number("고정 거리(m) — 카메라 이만큼 앞에서 신호. 0이면 아래 공식으로 속도별 자동 계산", c.fixedTriggerM.toString(), decimal = false)
        ui.text("자동 계산 = (현재속도² − 제한속도²)/(2×감속도) + 속도×반응시간 + 여유. BLE를 켜면 클릭 송출 시간만큼 둘 다 더 일찍 시작한다.", 12f)
        val decel = ui.number("감속도 m/s² (작을수록 일찍·천천히 내림)", c.decelMps2.toString())
        val reaction = ui.number("반응 여유 시간(초)", c.reactionS.toString())
        val margin = ui.number("추가 여유 거리(m)", c.marginM.toString())
        val restore = ui.number("통과 후 복귀 거리(m)", c.restoreAfterM.toString())

        ui.text("카메라 선별", 18f, bold = true)
        val minLimit = ui.number("이 제한속도 미만 카메라 무시 (80 → 고속도로만, 0 → 전부)", c.minLimitKmh.toString(), decimal = false)
        val skip = ui.check("이미 제한속도 이하로 달리면 신호 생략", c.skipIfBelowLimit)
        val includeSignal = ui.check("신호 단속으로 표시된 카메라(단속구분 2)도 포함 — 대부분 신호·과속 겸용", c.includeSignalCams)
        val leftTol = ui.number("진행 방향 왼쪽 허용폭(m) — 작을수록 반대 차로 카메라를 잘 거르지만 넓은 도로에서 놓칠 수 있음", c.leftTolM.toString())
        val rightTol = ui.number("진행 방향 오른쪽 허용폭(m)", c.rightTolM.toString())
        val kecLimit = ui.number("도로공사 구간단속 제한속도 (그 데이터엔 제한속도가 없음)", Prefs.kecLimitKmh(this).toString(), decimal = false)

        ui.text("차 블루투스 연결 시 자동 시작", 18f, bold = true)
        val autoOn = ui.check("아래 기기가 연결되면 주행 감시 자동 시작", Prefs.autoStart(this))
        val devices = bondedDevices()
        val labels = listOf("(선택 안 함)") + devices.map { (addr, name) -> "$name  $addr" }
        val cur = devices.indexOfFirst { it.first.equals(Prefs.autoDeviceAddress(this), ignoreCase = true) }
        val device = ui.choice("차 블루투스 기기 (페어링된 목록)", labels, if (cur >= 0) cur + 1 else 0)
        val autoStop = ui.check("연결이 끊기고 60초 지나면 자동 정지", Prefs.autoStop(this))
        autoStatus = ui.text("", 12f)
        ui.row(
            ui.button("위치 '항상 허용' 요청") { requestBackgroundLocation() },
            ui.button("배터리 최적화 제외 요청") { requestBatteryExemption() },
        )
        ui.text("앱이 꺼진 상태에서 GPS를 받으려면 위치를 '항상 허용'으로, Android 12 이상에서 백그라운드에서 감시를 띄우려면 배터리 최적화 제외가 필요하다. " +
            "둘 중 하나라도 없으면 연결 때 '탭해서 시작' 알림만 뜬다.", 12f)

        ui.text("출력", 18f, bold = true)
        val alerts = ui.check("음성·효과음 안내", Prefs.alertsOn(this))
        val ble = ui.check("가상 S3XY 버튼 신호 보내기 (BLE)", Prefs.bleOn(this))
        val profile = ui.multiline("BLE 프로필 (JSON — 캡처 결과로 채움)", Prefs.bleProfile(this))

        ui.button("저장") {
            try {
                val usualP = Profile.of(usual.selectedItemPosition)
                val slowP = Profile.of(slow.selectedItemPosition)
                require(slowP.ordinal < usualP.ordinal) { "내릴 프로필은 평소 프로필보다 낮아야 합니다" }
                val extraN = extra.text.toString().toInt()
                require(extraN in 0..4) { "여분 클릭은 0~4" }
                val cfg = c.copy(
                    decelMps2 = decel.text.toString().toDouble(),
                    reactionS = reaction.text.toString().toDouble(),
                    marginM = margin.text.toString().toDouble(),
                    fixedTriggerM = fixed.text.toString().toInt(),
                    restoreAfterM = restore.text.toString().toDouble(),
                    minLimitKmh = minLimit.text.toString().toInt(),
                    skipIfBelowLimit = skip.isChecked,
                    leftTolM = leftTol.text.toString().toDouble(),
                    rightTolM = rightTol.text.toString().toDouble(),
                    includeSignalCams = includeSignal.isChecked,
                )
                require(cfg.decelMps2 in 0.2..3.0) { "감속도는 0.2~3.0" }
                require(cfg.reactionS in 0.0..15.0) { "반응 시간은 0~15초" }
                require(cfg.marginM in 0.0..1000.0) { "여유 거리는 0~1000m" }
                require(cfg.fixedTriggerM in 0..3000) { "고정 거리는 0~3000m" }
                require(cfg.restoreAfterM in 0.0..2000.0) { "복귀 거리는 0~2000m" }
                require(cfg.minLimitKmh in 0..130) { "제한속도 기준은 0~130" }
                require(cfg.leftTolM in 5.0..60.0) { "왼쪽 허용폭은 5~60m" }
                require(cfg.rightTolM in 5.0..150.0) { "오른쪽 허용폭은 5~150m" }
                val kecLimitN = kecLimit.text.toString().toInt()
                require(kecLimitN in 30..130) { "도로공사 제한속도는 30~130" }
                val json = profile.text.toString().trim()
                if (json.isNotEmpty()) BleProfile.parse(json) // 형식 검증
                require(!(ble.isChecked && json.isEmpty())) { "BLE를 켜려면 프로필(JSON)이 필요합니다" }
                val pick = device.selectedItemPosition - 1
                val (addr, name) = if (pick in devices.indices) devices[pick] else "" to ""
                require(!(autoOn.isChecked && addr.isEmpty())) { "자동 시작하려면 차 블루투스 기기를 고르세요" }
                val kecChanged = kecLimitN != Prefs.kecLimitKmh(this)
                Prefs.saveEngine(this, cfg)
                Prefs.saveKecLimit(this, kecLimitN)
                Prefs.saveOutputs(this, usualP, slowP, extraN, autoRestore.isChecked, alerts.isChecked, ble.isChecked, json)
                Prefs.saveAuto(this, autoOn.isChecked, autoStop.isChecked, addr, name)
                if (kecChanged) CameraRepo.invalidate()
                val msg = if (DriveState.running) "저장됨 — 주행 중엔 적용되지 않습니다. [정지] 후 [주행 시작]을 다시 누르세요"
                    else "저장됨"
                Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
                finish()
            } catch (e: Exception) {
                Toast.makeText(this, "입력 오류: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }
        ui.show()
    }

    override fun onResume() {
        super.onResume()
        val missing = AutoStart.missing(this)
        autoStatus.text = if (missing.isEmpty()) "자동 시작 조건: 모두 충족 ✔"
            else "자동 시작에 아직 필요한 것: ${missing.joinToString(", ")}"
    }

    /** 페어링된 블루투스 기기 (주소, 이름). 권한이 없으면 빈 목록. */
    private fun bondedDevices(): List<Pair<String, String>> = try {
        (getSystemService(BLUETOOTH_SERVICE) as BluetoothManager).adapter?.bondedDevices
            ?.map { it.address to (it.name ?: "(이름 없음)") }?.sortedBy { it.second } ?: emptyList()
    } catch (_: SecurityException) { emptyList() }

    private fun requestBackgroundLocation() {
        if (Build.VERSION.SDK_INT < 29) { Toast.makeText(this, "이 안드로이드 버전은 필요 없음", Toast.LENGTH_SHORT).show(); return }
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            Toast.makeText(this, "먼저 위치 권한을 허용하세요", Toast.LENGTH_LONG).show(); return
        }
        // Android 11+에서는 설정 화면이 열린다. 거기서 '항상 허용'을 고른다.
        requestPermissions(arrayOf(Manifest.permission.ACCESS_BACKGROUND_LOCATION), 2)
    }

    private fun requestBatteryExemption() {
        if (AutoStart.isBatteryExempt(this)) { Toast.makeText(this, "이미 제외되어 있음", Toast.LENGTH_SHORT).show(); return }
        try {
            startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName")))
        } catch (_: Exception) {
            startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
        }
    }
}
