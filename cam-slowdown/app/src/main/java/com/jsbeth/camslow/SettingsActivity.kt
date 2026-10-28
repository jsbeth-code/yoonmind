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
import android.view.View
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast

/** 질문 7개로 끝나는 기본 설정 + 접힌 고급 설정. 바꾸는 즉시 저장된다. */
class SettingsActivity : Activity() {
    private lateinit var ui: Ui
    private lateinit var outputs: Outputs
    private lateinit var resultView: TextView
    private lateinit var cmdStatus: TextView
    private lateinit var permStatus: TextView
    private lateinit var zoneStatus: TextView
    private lateinit var ttsStatus: TextView
    private lateinit var overlayStatus: TextView
    private var fixedEdit: EditText? = null
    private var limitEdit: EditText? = null
    private val listener = { refreshStatus() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        outputs = Outputs(this)
        ui = Ui(this)
        actionBar?.setDisplayHomeAsUpEnabled(true)
        if (DriveState.running) ui.banner("감시 중입니다. 바꾼 설정은 다음에 켤 때부터 적용됩니다.")

        // 1·2 프로필
        ui.question(1, "평소 어떤 프로필로 달리나요?")
        ui.choice("", Profile.labels, Prefs.usualProfile(this).ordinal) { i -> Prefs.setUsualProfile(this, Profile.values()[i]); showResult() }
        ui.question(2, "카메라 앞에서는 어디까지 내릴까요?")
        ui.choice("", Profile.labels, Prefs.slowProfile(this).ordinal) { i -> Prefs.setSlowProfile(this, Profile.values()[i]); showResult() }
        resultView = ui.text("", 13f).apply {
            val p = ui.dp(8); setPadding(ui.dp(10), p, ui.dp(10), p)
            background = android.graphics.drawable.GradientDrawable().apply { setColor(0x1A009688); cornerRadius = ui.dp(4).toFloat() }
        }
        showResult()

        // 3 복귀
        ui.question(3, "카메라를 지나면 다시 올릴까요?")
        ui.switchRow("자동으로 평소 프로필로 올리기", "끄면 \"카메라 통과\" 음성만 나오고 휠은 직접 올립니다", Prefs.autoRestore(this)) { Prefs.setAutoRestore(this, it) }

        // 4 시점
        ui.question(4, "얼마나 미리 줄일까요?")
        val timings = Timing.values()
        ui.radioGroup(timings.map { it.label }, Prefs.timing(this).ordinal) { i ->
            Prefs.setTiming(this, timings[i]); fixedEdit?.visibility = if (timings[i] == Timing.FIXED) View.VISIBLE else View.GONE
        }
        fixedEdit = ui.numberRow("카메라 앞 거리(m)", "속도와 상관없이 이 거리에서 신호", Prefs.fixedTriggerM(this).toString(), false) { Prefs.setFixedTriggerM(this, it.toInt()) }
        fixedEdit?.visibility = if (Prefs.timing(this) == Timing.FIXED) View.VISIBLE else View.GONE
        ui.hint("자동 = 제동거리 + 반응거리 + 여유. 110→100 카메라면 약 390m 앞. 커맨더를 쓰면 클릭 보내는 시간만큼 더 일찍.")

        // 5 카메라 선별
        ui.question(5, "어떤 카메라에 반응할까요?")
        val minLimit = Prefs.minLimitKmh(this)
        val sel = when (minLimit) { 0 -> 0; 80 -> 1; else -> 2 }
        ui.radioGroup(listOf("모든 과속 카메라", "고속도로·자동차전용도로만 (제한속도 80 이상)", "제한속도가 아래 값 이상인 것만"), sel) { i ->
            when (i) { 0 -> Prefs.setMinLimitKmh(this, 0); 1 -> Prefs.setMinLimitKmh(this, 80); else -> limitEdit?.text?.toString()?.toIntOrNull()?.let { Prefs.setMinLimitKmh(this, it) } }
            limitEdit?.visibility = if (i == 2) View.VISIBLE else View.GONE
            CameraRepo.invalidate()
        }
        limitEdit = ui.numberRow("제한속도 기준(km/h)", "", (if (sel == 2) minLimit else 60).toString(), false) { Prefs.setMinLimitKmh(this, it.toInt()) }
        limitEdit?.visibility = if (sel == 2) View.VISIBLE else View.GONE
        ui.switchRow("교차로 신호 카메라도 포함", "대부분 과속도 같이 단속합니다", Prefs.includeSignal(this)) { Prefs.setIncludeSignal(this, it) }
        ui.switchRow("도로망(OSM)으로 옆 도로·반대편 카메라 거르기", "메뉴에서 kr-roads.zip을 넣어야 동작. 내 도로를 따라 앞에 있는 카메라만 봅니다", Prefs.useRoads(this)) { Prefs.setUseRoads(this, it) }
        ui.switchRow("이미 제한속도 이하로 달리면 신호 생략", "", Prefs.skipIfBelowLimit(this)) { Prefs.setSkipIfBelowLimit(this, it) }

        // 6 차와 연결
        ui.question(6, "차와 연결")
        ui.group {
            switchRow("S3XY 커맨더로 차 휠 조작", "끄면 음성 안내만", Prefs.bleOn(this@SettingsActivity)) { Prefs.setBleOn(this@SettingsActivity, it); refreshStatus() }
            cmdStatus = text("", 13f)
            row(
                button("페어링하기") { togglePairing() },
                button("감속 신호 테스트") { outputs.refresh(); outputs.down(Camera(0.0, 0.0, 100, CamKind.SPOT, 0, "테스트"), 300.0) },
                button("복귀 신호 테스트") { outputs.refresh(); outputs.up("테스트", force = true) },
            )
            hint("페어링: 위 버튼으로 ENH_BTN 광고를 켜고, enhauto 앱 Buttons → Add S3XY Button → 폰에 뜨는 페어링 요청 승인. 그 뒤 클릭 = 오른쪽 휠 아래, 더블클릭 = 오른쪽 휠 위로 지정. 차 프로필 화면을 보며 테스트 버튼으로 단수를 세어 보세요.")
            switchRow("차 블루투스에 연결되면 자동 시작", "끊기고 60초 지나면 자동 정지", Prefs.autoStart(this@SettingsActivity)) { Prefs.setAutoStart(this@SettingsActivity, it); refreshStatus() }
            val devices = bondedDevices()
            val labels = listOf("(차 고르기)") + devices.map { (addr, name) -> "$name  $addr" }
            val cur = devices.indexOfFirst { it.first.equals(Prefs.autoDeviceAddress(this@SettingsActivity), ignoreCase = true) }
            choice("", labels, if (cur >= 0) cur + 1 else 0) { i ->
                val d = devices.getOrNull(i - 1)
                Prefs.setAutoDevice(this@SettingsActivity, d?.first ?: "", d?.second ?: ""); refreshStatus()
            }
            switchRow("연결이 끊기고 60초 지나면 자동 정지", "", Prefs.autoStop(this@SettingsActivity)) { Prefs.setAutoStop(this@SettingsActivity, it) }
            permStatus = text("", 13f)
            row(
                button("위치 '항상 허용' 요청") { requestBackgroundLocation() },
                button("배터리 최적화 제외 요청") { requestBatteryExemption() },
            )
        }

        // 7 안내
        ui.question(7, "안내·화면")
        ui.switchRow("음성·효과음", "", Prefs.alertsOn(this)) { Prefs.setAlertsOn(this, it) }
        ui.text("소리를 어디로 낼까요?", 14f)
        val routes = VoiceRoute.values()
        ui.radioGroup(routes.map { "${it.label}\n${it.sub}" }, Prefs.voiceRoute(this).ordinal) { i -> Prefs.setVoiceRoute(this, routes[i]) }
        ui.hint("테슬라는 미디어 소스가 블루투스(폰)일 때만 폰의 미디어 소리를 틀어 줍니다. 그래서 기본은 폰 스피커(알람 채널)입니다. 차 스피커(통화 채널)는 차에서 테스트 버튼으로 직접 들어 보고 고르세요.")
        ui.row(ui.button("안내 소리 테스트") { outputs.test(); refreshStatus() })
        ttsStatus = ui.text("", 13f)
        ui.switchRow("감시 중 화면 켜 두기", "거치대에 올려 두고 볼 때", Prefs.keepScreenOn(this)) { Prefs.setKeepScreenOn(this, it) }
        val themes = listOf("AUTO", "DAY", "NIGHT")
        ui.radioGroup(listOf("낮·밤 자동 (폰 다크 모드를 따라감 — 폰 설정에서 일몰 예약 가능)", "항상 낮 (파스텔)", "항상 밤 (네온)"), themes.indexOf(Prefs.theme(this)).coerceAtLeast(0)) { i -> Prefs.setTheme(this, themes[i]) }
        ui.switchRow("메인 화면에 간이 지도", "내 도로·앞길·카메라를 그린다. 끄면 숫자만", Prefs.mapOn(this)) { Prefs.setMapOn(this, it) }
        ui.switchRow("다른 앱 위에 알약 창", "감시 중 다른 앱을 쓰면 거리·속도·제한속도를 작은 창으로. 끌어서 옮기고, 옆으로 밀면 가장자리 탭으로, 탭하면 앱 열기", Prefs.overlayOn(this)) { Prefs.setOverlayOn(this, it); refreshStatus() }
        overlayStatus = ui.text("", 13f)
        ui.row(ui.button("'다른 앱 위에 표시' 권한 요청") { OverlayWindow.requestPermission(this) })

        // 8 어린이보호구역
        ui.question(8, "어린이보호구역에서 감속")
        ui.switchRow("보호구역 시설 반경 안에 들어가면 내리기", "카메라가 없어도. 메뉴에서 '어린이보호구역 CSV'를 넣어야 동작", Prefs.schoolZoneOn(this)) { Prefs.setSchoolZoneOn(this, it); refreshStatus() }
        ui.numberRow("반경(m)", "법적 보호구역은 시설 정문 300m 안. 큰길 오탐이 잦으면 줄이세요", Prefs.schoolZoneRadiusM(this).toString(), false) { Prefs.setSchoolZoneRadiusM(this, it.toInt()) }
        zoneStatus = ui.text("", 13f)

        // 고급
        ui.collapsible("고급 설정") {
            hint("보통은 건드릴 필요 없습니다. 괄호 안이 기본값.")
            numberRow("감속도 m/s²", "작을수록 일찍, 천천히 줄임 (0.7)", Prefs.decel(this@SettingsActivity).toString(), true) { Prefs.setDecel(this@SettingsActivity, it) }
            numberRow("반응 여유 시간(초)", "신호 뒤 차가 줄이기 시작할 때까지 (4)", Prefs.reaction(this@SettingsActivity).toString(), true) { Prefs.setReaction(this@SettingsActivity, it) }
            numberRow("추가 여유 거리(m)", "계산한 거리에 더함 (150)", Prefs.margin(this@SettingsActivity).toInt().toString(), false) { Prefs.setMargin(this@SettingsActivity, it) }
            numberRow("통과 후 복귀 거리(m)", "카메라 지나고 이만큼 가서 올림 (100)", Prefs.restoreAfter(this@SettingsActivity).toInt().toString(), false) { Prefs.setRestoreAfter(this@SettingsActivity, it) }
            hint("허용폭은 도로망에 안 붙은 카메라를 GPS 방향으로 판단할 때만 쓴다. 거리 비례분(왼쪽 1.5%, 오른쪽 3~5%)이 더해진다.")
            numberRow("도심 왼쪽 허용폭(m)", "제한 80 미만 카메라. 작을수록 반대 차로를 잘 거름 (8)", Prefs.cityLeftTol(this@SettingsActivity).toInt().toString(), false) { Prefs.setCityLeftTol(this@SettingsActivity, it) }
            numberRow("도심 오른쪽 허용폭(m)", "(25)", Prefs.cityRightTol(this@SettingsActivity).toInt().toString(), false) { Prefs.setCityRightTol(this@SettingsActivity, it) }
            numberRow("제한 40 이하 오른쪽 허용폭(m)", "어린이보호구역·이면도로 카메라. 옆 큰길에서 잡히지 않도록 좁게 (15)", Prefs.narrowRightTol(this@SettingsActivity).toInt().toString(), false) { Prefs.setNarrowRightTol(this@SettingsActivity, it) }
            numberRow("고속도로 왼쪽 허용폭(m)", "제한 80 이상이면서 방향을 모르는 카메라 (15)", Prefs.hwyLeftTol(this@SettingsActivity).toInt().toString(), false) { Prefs.setHwyLeftTol(this@SettingsActivity, it) }
            numberRow("고속도로 오른쪽 허용폭(m)", "(60)", Prefs.hwyRightTol(this@SettingsActivity).toInt().toString(), false) { Prefs.setHwyRightTol(this@SettingsActivity, it) }
            numberRow("속도 타당성 배수", "제한속도의 이 배수 이상으로 달리면 다른 도로의 카메라로 봄. 0이면 끔 (1.5)", Prefs.fastRatio(this@SettingsActivity).toString(), true) { Prefs.setFastRatio(this@SettingsActivity, it) }
            numberRow("도로공사 구간 제한속도", "경찰청 데이터에서 못 찾았을 때 (100)", Prefs.kecLimitKmh(this@SettingsActivity).toString(), false) { Prefs.setKecLimitKmh(this@SettingsActivity, it.toInt()); CameraRepo.invalidate() }
            numberRow("길이 모르는 구간 최대 유지(m)", "길이도 짝 종점도 없는 구간단속 시점을 지난 뒤 (10000)", Prefs.maxSectionHoldM(this@SettingsActivity).toString(), false) { Prefs.setMaxSectionHoldM(this@SettingsActivity, it.toInt()) }
            numberRow("여분 클릭", "나무늘보까지 내릴 때만, 유실 대비 (1)", Prefs.extraDownClicks(this@SettingsActivity).toString(), false) { Prefs.setExtraDownClicks(this@SettingsActivity, it.toInt()); showResult() }
            numberRow("누름 사이 간격(ms)", "커맨더가 한 번씩 처리할 시간 (400)", Prefs.pressGapMs(this@SettingsActivity).toString(), false) { Prefs.setPressGapMs(this@SettingsActivity, it.toInt()) }
            switchRow("폰 블루투스 이름을 ENH_BTN으로", "실제 버튼 이름. 커맨더가 못 찾을 때만 끔", Prefs.renameAdapter(this@SettingsActivity)) { Prefs.setRenameAdapter(this@SettingsActivity, it) }
            switchRow("터널에서 추측 항법", "GPS 끊기면 마지막 속도·방향으로 추정. 감속만 하고 복귀는 GPS 복귀 후", Prefs.deadReckoning(this@SettingsActivity)) { Prefs.setDeadReckoning(this@SettingsActivity, it) }
            numberRow("추측 항법 최대 거리(m)", "이보다 길게 끊기면 포기 (2000)", Prefs.deadReckonMaxM(this@SettingsActivity).toString(), false) { Prefs.setDeadReckonMaxM(this@SettingsActivity, it.toInt()) }
            numberRow("GPS 정확도 기준(m)", "이보다 나쁜 위치는 무시 (40)", Prefs.maxAccM(this@SettingsActivity).toInt().toString(), false) { Prefs.setMaxAccM(this@SettingsActivity, it) }
            button("고급 설정 기본값으로") { Prefs.resetAdvanced(this@SettingsActivity); CameraRepo.invalidate(); recreate() }
        }
        ui.show()
    }

    override fun onOptionsItemSelected(item: android.view.MenuItem): Boolean {
        if (item.itemId == android.R.id.home) { finish(); return true }
        return super.onOptionsItemSelected(item)
    }

    override fun onResume() { super.onResume(); DriveState.addListener(listener); refreshStatus() }
    override fun onPause() { DriveState.removeListener(listener); super.onPause() }
    override fun onDestroy() { outputs.shutdown(); super.onDestroy() }

    private fun showResult() {
        val u = Prefs.usualProfile(this); val s = Prefs.slowProfile(this)
        resultView.text = if (s.ordinal >= u.ordinal) "⚠ 내릴 프로필이 평소 프로필보다 낮아야 합니다. 지금은 신호를 보내지 않습니다."
            else "휠 아래 ${u.ordinal - s.ordinal}번" + (if (s == Profile.SLOTH && Prefs.extraDownClicks(this) > 0) "(+여분 ${Prefs.extraDownClicks(this)}번)" else "") +
                "으로 ${s.label}까지 내리고, 지나면 휠 위 ${u.ordinal - s.ordinal}번으로 ${u.label}로 돌아옵니다."
    }

    private fun refreshStatus() {
        cmdStatus.text = if (!Prefs.bleOn(this)) "–  커맨더 안 씀 (음성만)"
            else if (S3xyButton.ready) "✔  커맨더 연결됨 · 버튼 준비" else "✖  ${S3xyButton.status}"
        val missing = AutoStart.missing(this)
        permStatus.text = if (!Prefs.autoStart(this)) "–  자동 시작 꺼짐"
            else if (Prefs.autoDeviceAddress(this).isEmpty()) "✖  차를 골라야 합니다"
            else if (missing.isEmpty()) "✔  자동 시작 조건 모두 충족" else "✖  아직 필요: ${missing.joinToString(", ")}"
        val zones = CameraRepo.cachedZoneCount()
        zoneStatus.text = if (!Prefs.schoolZoneOn(this)) "" else when (zones) { null -> "보호구역 파일 확인 중"; 0 -> "✖  보호구역 파일 없음 — 메인 메뉴에서 '어린이보호구역 CSV'"; else -> "✔  보호구역 ${zones}곳" }
        val t = DriveState.ttsStatus.ifEmpty { outputs.ttsStatus }
        ttsStatus.text = (if (t.contains("준비됨")) "✔  " else "✖  ") + t
        overlayStatus.text = if (!Prefs.overlayOn(this)) "–  알약 창 꺼짐" else if (OverlayWindow.canDraw(this)) "✔  권한 있음 — 감시 중 앱을 벗어나면 뜹니다" else "✖  '다른 앱 위에 표시' 권한이 없습니다. 아래 버튼으로 허용하세요"
    }

    private fun togglePairing() {
        if (S3xyButton.running && S3xyButton.pairingMode) { S3xyButton.stop(); refreshStatus(); return }
        if (S3xyButton.running) { Toast.makeText(this, "이미 광고 중", Toast.LENGTH_SHORT).show(); return }
        val ok = S3xyButton.start(this, { DriveState.log(it) }, Prefs.renameAdapter(this))
        S3xyButton.pairingMode = ok
        Toast.makeText(this, if (ok) "ENH_BTN 광고 중 — enhauto 앱에서 Add S3XY Button" else "시작 실패 — 블루투스를 확인하세요", Toast.LENGTH_LONG).show()
        refreshStatus()
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
