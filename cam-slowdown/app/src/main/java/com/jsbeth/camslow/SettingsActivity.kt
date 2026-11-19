package com.jsbeth.camslow

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
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
    companion object {
        /** 설정을 연 버튼의 화면 위치 [x, y, w, h] — 그 자리에서 퍼지듯 열린다. */
        const val EXTRA_FROM = "from"
        private const val ANIM_MS = 250L
    }
    private lateinit var ui: Ui
    private lateinit var outputs: Outputs
    private lateinit var cmdStatus: TextView
    private lateinit var permStatus: TextView
    private lateinit var zoneStatus: TextView
    private lateinit var ttsStatus: TextView
    private lateinit var overlayStatus: TextView
    private lateinit var teslaStatus: TextView
    private lateinit var teslaConnectBox: android.widget.LinearLayout
    private lateinit var teslaConnectedRow: android.widget.LinearLayout
    private var teslaKeyEdit: EditText? = null
    private var fixedEdit: EditText? = null
    private var limitEdit: EditText? = null
    private val listener = { refreshStatus() }

    override fun onCreate(savedInstanceState: Bundle?) {
        // v0.16.8 아래 3/4 시트(위 1/4엔 메인 화면이 비침). 밤이면 어두운 색
        val night = Theme.current(this).night
        setTheme(if (night) R.style.SettingsSheetDark else R.style.SettingsSheet)
        super.onCreate(savedInstanceState)
        outputs = Outputs(this)
        ui = Ui(this).apply { dark = night }
        if (DriveState.running) ui.banner("감시 중입니다. 바꾼 설정은 다음에 켤 때부터 적용됩니다.")
        ui.row(ui.button("도움말 — 처음 설치·커맨더 연결·문제 해결") { startActivity(android.content.Intent(this, HelpActivity::class.java)) })
        // ①② 프로필은 메인 화면 카드 메뉴에서 고른다(v0.16.8 설정에서 뺌)

        // 3 복귀
        ui.section("3", "지나면 복귀", { if (Prefs.autoRestore(this)) "자동으로 평소 프로필로 올림" else "직접 올림(음성만)" }) {
            ui.switchRow("자동으로 평소 프로필로 올리기", "끄면 \"카메라 통과\" 음성만 나오고 휠은 직접 올립니다", Prefs.autoRestore(this)) { Prefs.setAutoRestore(this, it) }

        }

        // 4 시점
        ui.section("4", "미리 줄이기", { timingSummary() }) {
            val timings = Timing.values()
            ui.radioGroup(timings.map { it.label }, Prefs.timing(this).ordinal) { i ->
                Prefs.setTiming(this, timings[i]); fixedEdit?.visibility = if (timings[i] == Timing.FIXED) View.VISIBLE else View.GONE
            }
            fixedEdit = ui.numberRow("카메라 앞 거리(m)", "속도와 상관없이 이 거리에서 신호", Prefs.fixedTriggerM(this).toString(), false) { Prefs.setFixedTriggerM(this, it.toInt()) }
            fixedEdit?.visibility = if (Prefs.timing(this) == Timing.FIXED) View.VISIBLE else View.GONE
            ui.hint("자동 = 제동거리 + 반응거리 + 여유. 110→100 카메라면 약 390m 앞. 커맨더를 쓰면 클릭 보내는 시간만큼 더 일찍.")

        }

        // 5 카메라 선별
        ui.section("5", "반응할 카메라", { cameraSummary() }) {
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
            ui.switchRow("이동식 과속카메라도 포함", "카카오가 알려 주는 이동식 단속 지점에서도 내림. 상자가 비어 있는 때가 많아 기본은 끔", Prefs.includeMobile(this)) { Prefs.setIncludeMobile(this, it) }
            ui.switchRow("도로망(OSM)으로 옆 도로·반대편 카메라 거르기", "데이터·도구에서 kr-roads.zip을 넣어야 동작. 내 도로를 따라 앞에 있는 카메라만 봅니다", Prefs.useRoads(this)) { Prefs.setUseRoads(this, it) }
            ui.switchRow("이미 제한속도 이하로 달리면 신호 생략", "", Prefs.skipIfBelowLimit(this)) { Prefs.setSkipIfBelowLimit(this, it) }

        }

        // 6 차와 연결
        ui.section("6", "차와 연결", { carSummary() }) {
            ui.group {
                switchRow("S3XY 커맨더로 차 휠 조작", "끄면 음성 안내만", Prefs.bleOn(this@SettingsActivity)) { Prefs.setBleOn(this@SettingsActivity, it); BoardScan.update(this@SettingsActivity, "설정"); refreshStatus() }
                text("커맨더 연결", 14f, bold = true)
                radioGroup(listOf("폰 직접 (폰이 커맨더 버튼)", "보드 경유 (ESP32 보드가 커맨더 버튼, 폰은 보드에 신호)"), if (Prefs.viaBoard(this@SettingsActivity)) 1 else 0) { i ->
                    Prefs.setViaBoard(this@SettingsActivity, i == 1)
                    BoardScan.update(this@SettingsActivity, "설정")
                    if (DriveState.running) Toast.makeText(this@SettingsActivity, "감시를 다시 켜면 적용됩니다", Toast.LENGTH_SHORT).show()
                    refreshStatus()
                }
                cmdStatus = text("", 13f)
                row(button("보드 페어링") { pairBoard() })
                hint("보드 경유: 보드 BOOT 3초(파란 불 두 번씩 깜빡) 뒤 엔하우토에 버튼으로 등록(클릭 = 크루징 속도 내림, 더블클릭 = 올림)하고, 보드 BOOT를 3초 넘게 눌러 파란 불이 두 번씩 깜빡이면(1분) [보드 페어링]. 폰에 페어링 요청이 뜨면 승인. 한 번만 하면 됩니다(새로 페어링하면 이전 폰은 못 씀). 아래 [페어링하기]·버튼 유지는 폰 직접일 때만 씁니다.")
                row(
                    button("페어링하기") { togglePairing() },
                    button("감속 신호 테스트") { if (DriveState.running) Toast.makeText(this@SettingsActivity, "감시 중엔 테스트할 수 없습니다 — 감시를 끄고 하세요 (앱이 아는 차 프로필이 어긋남)", Toast.LENGTH_LONG).show() else { if (!boardReadyForTest()) return@button; outputs.refresh(); outputs.down(Camera(0.0, 0.0, 100, CamKind.SPOT, 0, "테스트"), 300.0) } },
                    button("복귀 신호 테스트") { if (DriveState.running) Toast.makeText(this@SettingsActivity, "감시 중엔 테스트할 수 없습니다 — 감시를 끄고 하세요 (앱이 아는 차 프로필이 어긋남)", Toast.LENGTH_LONG).show() else { if (!boardReadyForTest()) return@button; outputs.refresh(); outputs.up("테스트", force = true) } },
                )
                hint("페어링: 위 버튼으로 ENH_BTN 광고를 켜고, enhauto 앱 Buttons → Add S3XY Button → 폰에 뜨는 페어링 요청 승인. 그 뒤 enhauto에서 이 버튼의 클릭 = 크루징 속도 내림, 더블클릭 = 크루징 속도 올림으로 지정(FSD에선 프로필 한 칸 내림/올림). 연결되면 [페어링하기]를 다시 눌러 끕니다. 차 프로필 화면을 보며 테스트 버튼으로 단수를 세어 보세요.")
                switchRow("감시를 꺼도 커맨더 버튼 유지", "앱을 다시 켜면 폰 블루투스 주소가 바뀌어 커맨더가 못 찾는 문제 때문에 켜 둡니다. 작은 알림이 남고 배터리를 조금 더 씁니다", Prefs.keepButton(this@SettingsActivity)) { Prefs.setKeepButton(this@SettingsActivity, it) }
                hint("커맨더가 1분 넘게 연결되지 않으면 음성 경고")
                val repeats = listOf(0, 1, 3)
                radioGroup(listOf("경고 한 번만", "1분마다 반복", "3분마다 반복"), repeats.indexOf(Prefs.cmdWarnRepeatMin(this@SettingsActivity)).coerceAtLeast(0)) { i ->
                    Prefs.setCmdWarnRepeatMin(this@SettingsActivity, repeats[i])
                }
                switchRow("차 블루투스에 연결되면 자동 시작", "끊기고 60초 지나면 자동 정지", Prefs.autoStart(this@SettingsActivity)) { Prefs.setAutoStart(this@SettingsActivity, it); refreshStatus() }
                val devices = bondedDevices()
                val labels = listOf("(차 고르기)") + devices.map { (addr, name) -> "$name  $addr" }
                val cur = devices.indexOfFirst { it.first.equals(Prefs.autoDeviceAddress(this@SettingsActivity), ignoreCase = true) }
                choice("", labels, if (cur >= 0) cur + 1 else 0) { i ->
                    val d = devices.getOrNull(i - 1)
                    Prefs.setAutoDevice(this@SettingsActivity, d?.first ?: "", d?.second ?: ""); refreshStatus()
                }
                switchRow("보드가 켜지면 자동 시작(보드 경유)", "보드는 차가 깨어 있을 때만 켜집니다 — 앱이 꺼져 있어도 보드가 보이면 감시 시작. 직접 끄면 보드가 꺼졌다 켜질 때까지 다시 켜지 않음. 보드 페어링·보드 경유일 때만", Prefs.boardAutoStart(this@SettingsActivity)) {
                    Prefs.setBoardAutoStart(this@SettingsActivity, it); BoardScan.update(this@SettingsActivity, "설정"); refreshStatus()
                }
                switchRow("연결이 끊기고 60초 지나면 자동 정지", "차 블루투스 또는 (보드 자동 시작이면) 보드 연결", Prefs.autoStop(this@SettingsActivity)) { Prefs.setAutoStop(this@SettingsActivity, it) }
                permStatus = text("", 13f)
                row(
                    button("위치 '항상 허용' 요청") { requestBackgroundLocation() },
                    button("배터리 최적화 제외 요청") { requestBatteryExemption() },
                )
            }

        }

        // 7 안내
        ui.section("7", "안내·화면", { guideSummary() }) {
            ui.text("간단 모드 (폰 화면을 끄고 달릴 때)", 14f)
            val simpleModes = listOf("AUTO", "ON", "OFF")
            ui.radioGroup(listOf("화면이 꺼지면 자동 (기본)", "항상", "끔"), simpleModes.indexOf(Prefs.simpleMode(this)).coerceAtLeast(0)) { i ->
                Prefs.setSimpleMode(this, simpleModes[i]); notifyDrive()
            }
            ui.text("간단 모드 들어갈 때 알림", 14f)
            val annModes = listOf("ONCE", "EVERY", "OFF")
            ui.radioGroup(listOf("한 번 주행에 처음 한 번만 (기본)", "화면을 끌 때마다", "끔"), annModes.indexOf(Prefs.simpleAnnounce(this)).coerceAtLeast(0)) { i -> Prefs.setSimpleAnnounce(this, annModes[i]) }
            ui.hint("간단 모드: 카카오 내비 음성을 끄고, 앱이 과속 카메라 500 m·200 m 앞 안내와 감속·복귀, 연결·목적지 같은 안전 안내만 말합니다. 감속·복귀 신호는 그대로 나갑니다.")
            ui.switchRow("음성·효과음", "끄면 카카오 길안내 음성까지 모두 무음. 크기는 메인 화면 메뉴의 막대(카카오·앱 따로)", Prefs.alertsOn(this)) { Prefs.setAlertsOn(this, it); KakaoSafety.applyVolume() }
            ui.numberRow("과속 기준(km/h)", "제한속도보다 이만큼 넘으면 화면 속도를 빨갛게 (7). 과속 음성·경고음은 카카오 내비가 함", Prefs.overspeedMargin(this).toString(), false) { Prefs.setOverspeedMargin(this, it.toInt()) }
            ui.text("소리를 어디로 낼까요?", 14f)
            val routes = VoiceRoute.values()
            ui.radioGroup(routes.map { "${it.label}\n${it.sub}" }, Prefs.voiceRoute(this).ordinal) { i -> Prefs.setVoiceRoute(this, routes[i]) }
            ui.hint("테슬라는 미디어 소스가 블루투스(폰)일 때만 폰의 미디어 소리를 틀어 줍니다. 그래서 기본은 폰 스피커(알람 채널)입니다. 차 스피커(통화 채널)는 차에서 테스트 버튼으로 직접 들어 보고 고르세요.")
            ui.row(ui.button("안내 소리 테스트") { outputs.test(); refreshStatus() })
            ttsStatus = ui.text("", 13f)
            ui.switchRow("교통정보 표시", "카카오 화면에 막히는 길을 색으로. 바꾸면 다음 감시 시작이나 경로가 새로 잡힐 때부터", Prefs.kakaoTraffic(this)) { Prefs.setKakaoTraffic(this, it) }
            ui.switchRow("감시 중 화면 켜 두기", "거치대에 올려 두고 볼 때", Prefs.keepScreenOn(this)) { Prefs.setKeepScreenOn(this, it) }
            val themes = listOf("AUTO", "DAY", "NIGHT")
            ui.radioGroup(listOf("낮·밤 자동 (폰 다크 모드를 따라감 — 폰 설정에서 일몰 예약 가능)", "항상 낮 (파스텔)", "항상 밤 (네온)"), themes.indexOf(Prefs.theme(this)).coerceAtLeast(0)) { i -> Prefs.setTheme(this, themes[i]) }
            ui.switchRow("메인 화면에 간이 지도", "내 도로·앞길·카메라를 그린다. 끄면 숫자만", Prefs.mapOn(this)) { Prefs.setMapOn(this, it) }
            ui.switchRow("다른 앱 위에 알약 창", "감시 중 다른 앱을 쓰면 거리·속도·제한속도를 작은 창으로. 끌어서 옮기고, 옆으로 밀면 가장자리 탭으로, 탭하면 앱 열기", Prefs.overlayOn(this)) { Prefs.setOverlayOn(this, it); refreshStatus() }
            overlayStatus = ui.text("", 13f)
            ui.row(ui.button("'다른 앱 위에 표시' 권한 요청") { OverlayWindow.requestPermission(this) })
            // v0.14.0 메인 화면 카드: 옮긴 자리에서 다음에도 시작(고정 질문 없음)
            ui.text("메인 화면 카드 위치", 15f, bold = true)
            ui.row(ui.button("카드 처음 자리로") { Prefs.clearPillLast(this); Toast.makeText(this, "앱을 다시 열면 카카오 위 파란 줄 오른쪽에서 시작합니다", Toast.LENGTH_LONG).show() })
            ui.hint("카드(목적지·카카오·커맨더)는 길게 눌러 끌어 옮기면 그 자리를 기억해 다음에도 거기서 시작합니다(펼친·접은 화면 따로).")
            ui.row(ui.button("카메라 카드 위치 처음대로") { Prefs.clearCamCardPos(this); Toast.makeText(this, "카메라 카드를 처음 자리로 돌렸습니다", Toast.LENGTH_SHORT).show() })
            ui.hint("카메라 알림 카드(카메라 앞에서만 뜨는 카드)도 길게 눌러 끌어 옮길 수 있고, 놓으면 그 자리로 고정됩니다(펼친·접은 화면 따로).")

        }

        // 8 어린이보호구역
        ui.section("8", "어린이보호구역", { if (Prefs.schoolZoneOn(this)) "켬 · 반경 ${Prefs.schoolZoneRadiusM(this)}m" else "끔" }) {
            ui.switchRow("보호구역 시설 반경 안에 들어가면 내리기", "카메라가 없어도. 데이터·도구에서 '어린이보호구역 CSV'를 넣어야 동작", Prefs.schoolZoneOn(this)) { Prefs.setSchoolZoneOn(this, it); refreshStatus() }
            ui.numberRow("반경(m)", "법적 보호구역은 시설 정문 300m 안. 큰길 오탐이 잦으면 줄이세요", Prefs.schoolZoneRadiusM(this).toString(), false) { Prefs.setSchoolZoneRadiusM(this, it.toInt()) }
            zoneStatus = ui.text("", 13f)

        }

        // 9 테슬라 내비 목적지
        ui.section("9", "테슬라 목적지", { if (!TeslaLink.connected(this)) "연결 안 됨" else if (Prefs.teslaFollow(this)) "따라가기 켬 · 연결됨" else "따라가기 꺼짐 · 연결됨" }) {
            ui.hint("테슬라 내비에 목적지를 찍으면 카카오가 그 경로로 안내하고 경로 위 카메라만 봅니다(갈림길 오탐 감소). 확인 방식은 아래에서 고릅니다(확인 한 번마다 테슬라 유료 API를 부름). 차가 자면 깨우지 않고 건너뛰고, 연달아 실패·잠들면 간격을 5·10분으로 늘립니다. 길안내·카메라 음성은 카카오가, 감속·복귀 음성은 이 앱이 합니다.")
            ui.switchRow("목적지 따라가기", "끄면 지금처럼 목적지 없이(안전 운행)", Prefs.teslaFollow(this)) { Prefs.setTeslaFollow(this, it); refreshStatus(); notifyDrive() }
            ui.radioGroup(TeslaPoll.values().map { it.label }, Prefs.teslaPoll(this).ordinal) { i -> Prefs.setTeslaPoll(this, TeslaPoll.values()[i]) }
            ui.hint("목적지가 들어오면 이 경로로 시작하고, 지도에서 경로를 보고 고르는 화면이 15초 동안 뜹니다(안 누르면 이 기본 경로).")
            ui.radioGroup(RouteOpt.values().map { "기본 경로: " + it.label }, Prefs.routeOpt(this).ordinal) { i -> Prefs.setRouteOpt(this, RouteOpt.values()[i]) }
            teslaStatus = ui.text("", 13f)
            teslaConnectBox = ui.group {
                row(button("테슬라 연결") { TeslaLink.openLogin(this@SettingsActivity) })
                teslaKeyEdit = multiline("로그인 뒤 앱으로 안 돌아오면, 브라우저에 보이는 세션 키를 붙여 넣으세요", "").apply { minLines = 2 }
                row(button("세션 키 저장") { saveTeslaKey() })
            }
            teslaConnectedRow = ui.row(
                ui.button("지금 목적지 확인") { checkTeslaNow() },
                ui.button("연결 끊기") { TeslaLink.disconnect(this); refreshStatus(); notifyDrive() },
            )
            ui.hint("테슬라 토큰은 중계 서버에만 있고 이 폰에는 세션 키만 저장됩니다. 차 화면에 위치 공유 아이콘이 보일 수 있습니다.")

        }

        // 10 업데이트
        ui.section("10", "앱 업데이트", { "지금 v${BuildConfig.VERSION_NAME}" }) {
            ui.hint("지금 v${BuildConfig.VERSION_NAME} (빌드 ${BuildConfig.VERSION_CODE}" + (if (BuildConfig.GIT_SHA.isNotBlank()) ", ${BuildConfig.GIT_SHA.take(7)}" else "") + "). 앱을 열 때·감시 시작 때 30분에 한 번 자동으로도 확인합니다.")
            ui.row(ui.button("업데이트 확인") { checkUpdateNow() })

        }

        // 고급
        ui.section("…", "고급 설정", { "보통은 건드릴 필요 없음" }) {
            ui.hint("보통은 건드릴 필요 없습니다. 괄호 안이 기본값.")
            ui.numberRow("감속도 m/s²", "작을수록 일찍, 천천히 줄임 (0.7)", Prefs.decel(this@SettingsActivity).toString(), true) { Prefs.setDecel(this@SettingsActivity, it) }
            ui.numberRow("반응 여유 시간(초)", "신호 뒤 차가 줄이기 시작할 때까지 (4)", Prefs.reaction(this@SettingsActivity).toString(), true) { Prefs.setReaction(this@SettingsActivity, it) }
            ui.numberRow("추가 여유 거리(m)", "계산한 거리에 더함 (150)", Prefs.margin(this@SettingsActivity).toInt().toString(), false) { Prefs.setMargin(this@SettingsActivity, it) }
            ui.numberRow("통과 후 복귀 거리(m)", "카메라 지나고 이만큼 가서 올림 (100)", Prefs.restoreAfter(this@SettingsActivity).toInt().toString(), false) { Prefs.setRestoreAfter(this@SettingsActivity, it) }
            ui.hint("허용폭은 도로망에 안 붙은 카메라를 GPS 방향으로 판단할 때만 쓴다. 거리 비례분(왼쪽 1.5%, 오른쪽 3~5%)이 더해진다.")
            ui.numberRow("도심 왼쪽 허용폭(m)", "제한 80 미만 카메라. 작을수록 반대 차로를 잘 거름 (8)", Prefs.cityLeftTol(this@SettingsActivity).toInt().toString(), false) { Prefs.setCityLeftTol(this@SettingsActivity, it) }
            ui.numberRow("도심 오른쪽 허용폭(m)", "(25)", Prefs.cityRightTol(this@SettingsActivity).toInt().toString(), false) { Prefs.setCityRightTol(this@SettingsActivity, it) }
            ui.numberRow("제한 40 이하 오른쪽 허용폭(m)", "어린이보호구역·이면도로 카메라. 옆 큰길에서 잡히지 않도록 좁게 (15)", Prefs.narrowRightTol(this@SettingsActivity).toInt().toString(), false) { Prefs.setNarrowRightTol(this@SettingsActivity, it) }
            ui.numberRow("고속도로 왼쪽 허용폭(m)", "제한 80 이상이면서 방향을 모르는 카메라 (15)", Prefs.hwyLeftTol(this@SettingsActivity).toInt().toString(), false) { Prefs.setHwyLeftTol(this@SettingsActivity, it) }
            ui.numberRow("고속도로 오른쪽 허용폭(m)", "(60)", Prefs.hwyRightTol(this@SettingsActivity).toInt().toString(), false) { Prefs.setHwyRightTol(this@SettingsActivity, it) }
            ui.numberRow("속도 타당성 배수", "제한속도의 이 배수 이상으로 달리면 다른 도로의 카메라로 봄. 0이면 끔 (1.5)", Prefs.fastRatio(this@SettingsActivity).toString(), true) { Prefs.setFastRatio(this@SettingsActivity, it) }
            ui.numberRow("도로공사 구간 제한속도", "경찰청 데이터에서 못 찾았을 때 (100)", Prefs.kecLimitKmh(this@SettingsActivity).toString(), false) { Prefs.setKecLimitKmh(this@SettingsActivity, it.toInt()); CameraRepo.invalidate() }
            ui.numberRow("길이 모르는 구간 최대 유지(m)", "길이도 짝 종점도 없는 구간단속 시점을 지난 뒤 (10000)", Prefs.maxSectionHoldM(this@SettingsActivity).toString(), false) { Prefs.setMaxSectionHoldM(this@SettingsActivity, it.toInt()) }
            ui.numberRow("여분 클릭", "맨 아래(나무늘보)까지 내릴 때만, 유실 대비 (1)", Prefs.extraDownClicks(this@SettingsActivity).toString(), false) { Prefs.setExtraDownClicks(this@SettingsActivity, it.toInt()) }
            ui.numberRow("누름 사이 간격(ms)", "차가 클릭을 하나씩 받아들일 시간. 프로필이 덜 바뀌면 늘리세요 (1000)", Prefs.pressGapMs(this@SettingsActivity).toString(), false) { Prefs.setPressGapMs(this@SettingsActivity, it.toInt()) }
            ui.switchRow("폰 블루투스 이름을 ENH_BTN으로", "실제 버튼 이름. 커맨더가 못 찾을 때만 끔", Prefs.renameAdapter(this@SettingsActivity)) { Prefs.setRenameAdapter(this@SettingsActivity, it) }
            ui.switchRow("터널에서 추측 항법", "GPS 끊기면 마지막 속도·방향으로 추정. 감속만 하고 복귀는 GPS 복귀 후", Prefs.deadReckoning(this@SettingsActivity)) { Prefs.setDeadReckoning(this@SettingsActivity, it) }
            ui.numberRow("추측 항법 최대 거리(m)", "이보다 길게 끊기면 포기 (2000)", Prefs.deadReckonMaxM(this@SettingsActivity).toString(), false) { Prefs.setDeadReckonMaxM(this@SettingsActivity, it.toInt()) }
            ui.numberRow("GPS 정확도 기준(m)", "이보다 나쁜 위치는 무시 (40)", Prefs.maxAccM(this@SettingsActivity).toInt().toString(), false) { Prefs.setMaxAccM(this@SettingsActivity, it) }
            ui.button("고급 설정 기본값으로") { Prefs.resetAdvanced(this@SettingsActivity); CameraRepo.invalidate(); recreate() }
        }

        // v0.16.8 데이터·도구(예전 메뉴의 "데이터·테스트 도구 ⋮"). 파일 고르기·가상 주행은 메인 화면이 처리한다.
        ui.section("⇩", "데이터·도구", { CameraRepo.cachedCount()?.let { if (it > 0) "카메라 ${it}개" else "카메라 데이터 없음" } ?: "카메라 데이터·도로망 넣기 · 가상 주행 · BLE 스캔" }) {
            ui.hint("처음 설치하거나 데이터를 새로 받을 때 넣습니다. 누르면 파일 고르는 화면이 뜹니다.")
            ui.row(ui.button("카메라 데이터 (경찰청 전국 CSV)") { openTool("csv") })
            ui.row(ui.button("도로공사 구간단속 CSV") { openTool("kec_sections") }, ui.button("도로공사 이정좌표 CSV") { openTool("kec_points") })
            ui.row(ui.button("어린이보호구역 CSV") { openTool("zones") }, ui.button("도로망 (OSM kr-roads.zip)") { openTool("roads") })
            ui.row(ui.button("가상 주행 테스트 (주차 중)") { openTool("sim") }, ui.button("BLE 스캔 (진단용)") { openTool("scan") })
        }
        buildSheet()
        swipeSlop = ui.dp(100).toFloat()
    }

    // ---- v0.16.0 오른쪽으로 쓸면 닫기(스크롤·막대·입력칸·스위치 조작과 겹치지 않게 터치 단계에서 판별)
    private var swipeSlop = 0f
    private var swX = 0f; private var swY = 0f
    private var swTrack = false
    override fun dispatchTouchEvent(e: android.view.MotionEvent): Boolean {
        when (e.actionMasked) {
            android.view.MotionEvent.ACTION_DOWN -> { swX = e.rawX; swY = e.rawY; swTrack = !touchesControl(window.decorView, e.rawX.toInt(), e.rawY.toInt()) }
            android.view.MotionEvent.ACTION_POINTER_DOWN -> swTrack = false
            android.view.MotionEvent.ACTION_MOVE -> if (swTrack) {
                val dx = e.rawX - swX; val dy = kotlin.math.abs(e.rawY - swY)
                if (dy > ui.dp(24) && dy > kotlin.math.abs(dx)) swTrack = false // 세로 스크롤
                else if (dx > swipeSlop && dx > dy * 2) {
                    swTrack = false
                    // 아래 화면에는 취소를 보내(누르던 버튼이 눌리지 않게) 닫는다
                    val cancel = android.view.MotionEvent.obtain(e).apply { action = android.view.MotionEvent.ACTION_CANCEL }
                    super.dispatchTouchEvent(cancel); cancel.recycle()
                    close(toRight = true)
                    return true
                }
            }
        }
        return super.dispatchTouchEvent(e)
    }

    /** 이 점 아래에 가로로 끄는 조작(막대·입력칸·스위치·목록 고르기)이 있는지. */
    private fun touchesControl(v: View, x: Int, y: Int): Boolean {
        if (v.visibility != View.VISIBLE) return false
        val loc = IntArray(2); v.getLocationOnScreen(loc)
        if (x < loc[0] || y < loc[1] || x >= loc[0] + v.width || y >= loc[1] + v.height) return false
        if (v is android.widget.AbsSeekBar || v is EditText || v is android.widget.Switch || v is android.widget.Spinner) return true
        if (v is android.view.ViewGroup) for (i in 0 until v.childCount) if (touchesControl(v.getChildAt(i), x, y)) return true
        return false
    }

    /** 감시 중이면 서비스가 설정을 다시 읽게(목적지 따라가기 끔·연결 끊기를 바로 반영). */
    private fun notifyDrive() { if (DriveState.running) DriveService.send(this, DriveService.ACTION_SETTINGS_CHANGED) }

    private fun checkUpdateNow() {
        Toast.makeText(this, "확인 중…", Toast.LENGTH_SHORT).show()
        UpdateCheck.checkNow(this) { r ->
            if (isFinishing || isDestroyed) return@checkNow
            when (r) {
                is UpdateCheck.Result.Latest -> Toast.makeText(this, "최신 버전입니다", Toast.LENGTH_LONG).show()
                is UpdateCheck.Result.Failed -> Toast.makeText(this, "확인 실패: ${r.why}", Toast.LENGTH_LONG).show()
                is UpdateCheck.Result.Newer -> android.app.AlertDialog.Builder(this)
                    .setTitle("새 빌드 있음")
                    .setMessage("빌드 ${r.rel.code} (${r.rel.sha.take(7)}). 설치 파일을 받을까요?")
                    .setPositiveButton("받기") { _, _ ->
                        startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(UpdateCheck.APK)))
                    }
                    .setNegativeButton("나중에", null)
                    .show()
            }
        }
    }

    // ---- v0.16.8 아래 3/4 시트: 위 여백 누르기·손잡이 끌어내리기·오른쪽으로 쓸기로 닫힘, 누른 버튼 자리에서 퍼지듯 열림
    private lateinit var scrim: View
    private lateinit var sheet: android.widget.LinearLayout
    private var closing = false
    private var dragY = 0f

    private fun buildSheet() {
        val night = ui.dark
        val frame = android.widget.FrameLayout(this)
        scrim = View(this).apply { setBackgroundColor(0x47000000); setOnClickListener { close() } }
        frame.addView(scrim, android.widget.FrameLayout.LayoutParams(-1, -1))
        val r = ui.dp(22).toFloat()
        sheet = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            background = android.graphics.drawable.GradientDrawable().apply {
                cornerRadii = floatArrayOf(r, r, r, r, 0f, 0f, 0f, 0f); setColor(if (night) 0xFF1C1F26.toInt() else 0xFFF5F6F8.toInt())
            }
            elevation = ui.dp(16).toFloat()
            isClickable = true // 시트 안 빈 곳을 눌러도 닫히지 않게
        }
        // 손잡이(여기서 시작해 아래로 끌 때만 닫힘 — 목록은 스크롤만)
        val handle = android.widget.FrameLayout(this).apply {
            addView(View(this@SettingsActivity).apply {
                background = android.graphics.drawable.GradientDrawable().apply { cornerRadius = ui.dp(2).toFloat(); setColor(if (night) 0xFF4A505C.toInt() else 0xFFC7CCD4.toInt()) }
            }, android.widget.FrameLayout.LayoutParams(ui.dp(40), ui.dp(4), android.view.Gravity.CENTER))
        }
        sheet.addView(handle, android.widget.LinearLayout.LayoutParams(-1, ui.dp(24)))
        val head = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.HORIZONTAL; gravity = android.view.Gravity.CENTER_VERTICAL
            setPadding(ui.dp(20), 0, ui.dp(8), ui.dp(6))
            addView(TextView(this@SettingsActivity).apply { text = "설정"; textSize = 20f; setTypeface(typeface, android.graphics.Typeface.BOLD) },
                android.widget.LinearLayout.LayoutParams(0, -2, 1f))
            addView(TextView(this@SettingsActivity).apply {
                text = "✕"; textSize = 18f; gravity = android.view.Gravity.CENTER; setTextColor(ui.muted)
                contentDescription = "닫기"; setOnClickListener { close() }
            }, android.widget.LinearLayout.LayoutParams(ui.dp(44), ui.dp(44)))
        }
        sheet.addView(head)
        ui.root.setPadding(ui.dp(12), 0, ui.dp(12), ui.dp(28))
        val scroll = android.widget.ScrollView(this).apply { addView(ui.root) }
        sheet.addView(scroll, android.widget.LinearLayout.LayoutParams(-1, 0, 1f))
        val drag = View.OnTouchListener { _, e ->
            when (e.actionMasked) {
                android.view.MotionEvent.ACTION_DOWN -> { dragY = e.rawY; sheet.animate().cancel(); true }
                android.view.MotionEvent.ACTION_MOVE -> { sheet.translationY = (e.rawY - dragY).coerceAtLeast(0f); true }
                android.view.MotionEvent.ACTION_UP, android.view.MotionEvent.ACTION_CANCEL -> {
                    if (sheet.translationY > ui.dp(90)) close(down = true) else sheet.animate().translationY(0f).setDuration(150).start()
                    true
                }
                else -> false
            }
        }
        handle.setOnTouchListener(drag)
        head.setOnTouchListener(drag)
        val h = (resources.displayMetrics.heightPixels * 0.75f).toInt()
        frame.addView(sheet, android.widget.FrameLayout.LayoutParams(-1, h, android.view.Gravity.BOTTOM))
        setContentView(frame)
        frame.post { animateIn() }
    }

    private fun fromRect(): IntArray? = intent.getIntArrayExtra(EXTRA_FROM)?.takeIf { it.size == 4 && it[2] > 0 && it[3] > 0 }

    /** 누른 버튼 자리에서 퍼지듯(약 0.25초). 버튼 자리를 모르면 아래에서 올라온다. */
    private fun animateIn() {
        if (sheet.width == 0 || sheet.height == 0) return
        scrim.alpha = 0f
        scrim.animate().alpha(1f).setDuration(ANIM_MS).start()
        val from = fromRect()
        val interp = android.view.animation.DecelerateInterpolator()
        if (from != null) {
            pivotTo(from)
            sheet.alpha = 0f
            sheet.animate().scaleX(1f).scaleY(1f).alpha(1f).setDuration(ANIM_MS).setInterpolator(interp).start()
        } else {
            sheet.translationY = sheet.height.toFloat()
            sheet.animate().translationY(0f).setDuration(ANIM_MS).setInterpolator(interp).start()
        }
    }

    private fun pivotTo(from: IntArray) {
        val loc = IntArray(2); sheet.getLocationOnScreen(loc)
        sheet.pivotX = from[0] + from[2] / 2f - loc[0]
        sheet.pivotY = from[1] + from[3] / 2f - loc[1] - sheet.translationY
        sheet.scaleX = (from[2].toFloat() / sheet.width).coerceIn(0.05f, 1f)
        sheet.scaleY = (from[3].toFloat() / sheet.height).coerceIn(0.05f, 1f)
    }

    /** 닫기: 버튼 자리로 줄어들며(손잡이로 내렸으면 아래로, 오른쪽으로 쓸었으면 오른쪽으로) 사라진다. */
    private fun close(down: Boolean = false, toRight: Boolean = false) {
        if (closing) return
        closing = true
        currentFocus?.clearFocus() // 입력 중인 숫자는 칠 때마다 이미 저장됨
        if (!::sheet.isInitialized) { finishNow(); return }
        sheet.animate().cancel()
        scrim.animate().alpha(0f).setDuration(ANIM_MS).start()
        val from = fromRect()
        val a = sheet.animate().setDuration(ANIM_MS).setInterpolator(android.view.animation.AccelerateInterpolator())
        when {
            toRight -> a.translationX(sheet.width.toFloat())
            down || from == null -> a.translationY(sheet.height.toFloat())
            else -> {
                val sx = (from[2].toFloat() / sheet.width).coerceIn(0.05f, 1f); val sy = (from[3].toFloat() / sheet.height).coerceIn(0.05f, 1f)
                val loc = IntArray(2); sheet.getLocationOnScreen(loc)
                sheet.pivotX = from[0] + from[2] / 2f - loc[0]; sheet.pivotY = from[1] + from[3] / 2f - loc[1]
                a.scaleX(sx).scaleY(sy).alpha(0f)
            }
        }
        a.withEndAction { finishNow() }.start()
    }

    private fun finishNow() {
        finish()
        @Suppress("DEPRECATION") overridePendingTransition(0, 0)
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() { close() }

    /** 데이터·도구: 메인 화면이 파일 고르기·가상 주행·BLE 스캔을 처리한다. */
    private fun openTool(key: String) {
        startActivity(Intent(this, MainActivity::class.java).putExtra(MainActivity.EXTRA_TOOL, key).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP))
        finishNow()
    }

    private fun timingSummary(): String = when (Prefs.timing(this)) {
        Timing.AUTO -> "자동(속도에 맞춰)"; Timing.EARLY -> "이르게"; Timing.LATE -> "늦게"
        Timing.FIXED -> "카메라 ${Prefs.fixedTriggerM(this)}m 앞"
    }

    private fun cameraSummary(): String {
        val lim = Prefs.minLimitKmh(this)
        return (if (lim == 0) "모든 과속" else "제한 $lim 이상") +
            (if (Prefs.includeSignal(this)) " · 신호 포함" else "") + (if (Prefs.includeMobile(this)) " · 이동식 포함" else " · 이동식 제외")
    }

    private fun carSummary(): String {
        if (!Prefs.bleOn(this)) return "커맨더 안 씀 (음성만)"
        val via = Prefs.viaBoard(this)
        return (if (via) "보드 경유" else "폰 직접") +
            (if (via && Prefs.boardAutoStart(this)) " · 보드로 자동 시작" else "") +
            (if (Prefs.autoStart(this)) " · 차 블루투스로 자동 시작" else "") +
            (if (S3xyButton.ready) " · 준비됨" else "")
    }

    private fun guideSummary(): String {
        val simple = when (Prefs.simpleMode(this)) { "ON" -> "간단 모드 항상"; "OFF" -> "간단 모드 끔"; else -> "간단 모드 자동" }
        return simple + (if (Prefs.alertsOn(this)) "" else " · 소리 끔") + " · 교통정보 " + (if (Prefs.kakaoTraffic(this)) "켬" else "끔")
    }

    override fun onResume() {
        super.onResume(); DriveState.addListener(listener); refreshStatus()
        // 이 시트가 떠 있는 동안은 앱 화면이 보이는 것으로(알약 창을 띄우지 않음)
        DriveState.activityVisible = true; OverlayWindow.hide()
    }
    override fun onPause() {
        DriveState.removeListener(listener)
        DriveState.activityVisible = false
        val app = applicationContext
        window.decorView.postDelayed({ OverlayWindow.sync(app) }, 300)
        super.onPause()
    }
    override fun onDestroy() {
        outputs.shutdown()
        // 시험하려고 켠 보드 연결은 감시 중이 아니면 끈다
        if (!DriveState.running && BoardLink.active) S3xyButton.stop()
        BoardScan.update(this)
        super.onDestroy()
    }

    private fun refreshStatus() {
        ui.refreshSummaries()
        cmdStatus.text = if (!Prefs.bleOn(this)) "–  커맨더 안 씀 (음성만)"
            else if (S3xyButton.ready) (if (S3xyButton.viaBoard) "✔  보드 경유 · 커맨더 준비" else "✔  커맨더 연결됨 · 버튼 준비")
            else if (Prefs.viaBoard(this) && !S3xyButton.viaBoard)
                (if (Prefs.boardAddr(this).isEmpty()) "✖  보드 경유 · 보드 페어링 안 됨 — [보드 페어링]" else "–  보드 경유 · 보드 ${Prefs.boardAddr(this)} 페어링됨 — 감시를 켜면 연결")
            else "✖  ${S3xyButton.status}"
        val missing = AutoStart.missing(this)
        permStatus.text = if (!Prefs.autoStart(this)) "–  자동 시작 꺼짐"
            else if (Prefs.autoDeviceAddress(this).isEmpty()) "✖  차를 골라야 합니다"
            else if (missing.isEmpty()) "✔  자동 시작 조건 모두 충족" else "✖  아직 필요: ${missing.joinToString(", ")}"
        // v0.16.0 보드 자동 시작 상태
        val boardMissing = missing + (if (!BoardScan.hasScanPermission(this)) listOf("블루투스 검색 권한") else emptyList())
        permStatus.append("\n" + when {
            !Prefs.boardAutoStart(this) -> "–  보드 자동 시작 꺼짐"
            !Prefs.bleOn(this) || !Prefs.viaBoard(this) -> "–  보드 자동 시작: 커맨더 연결이 '보드 경유'일 때만"
            Prefs.boardAddr(this).isEmpty() -> "✖  보드 자동 시작: 보드 페어링 필요"
            boardMissing.isEmpty() -> "✔  보드가 켜지면 자동 시작"
            else -> "✖  보드 자동 시작에 아직 필요: ${boardMissing.joinToString(", ")}"
        })
        val zones = CameraRepo.cachedZoneCount()
        zoneStatus.text = if (!Prefs.schoolZoneOn(this)) "" else when (zones) { null -> "보호구역 파일 확인 중"; 0 -> "✖  보호구역 파일 없음 — 데이터·도구에서 '어린이보호구역 CSV'"; else -> "✔  보호구역 ${zones}곳" }
        val t = DriveState.ttsStatus.ifEmpty { outputs.ttsStatus }
        ttsStatus.text = (if (t.contains("준비됨")) "✔  " else "✖  ") + t
        refreshTesla()
        overlayStatus.text = if (!Prefs.overlayOn(this)) "–  알약 창 꺼짐" else if (OverlayWindow.canDraw(this)) "✔  권한 있음 — 감시 중 앱을 벗어나면 뜹니다" else "✖  '다른 앱 위에 표시' 권한이 없습니다. 아래 버튼으로 허용하세요"
    }

    private fun refreshTesla() {
        val on = TeslaLink.connected(this)
        teslaConnectBox.visibility = if (on) View.GONE else View.VISIBLE
        teslaConnectedRow.visibility = if (on) View.VISIBLE else View.GONE
        val last = Prefs.teslaLastCheck(this)
        val ago = if (last == 0L) "아직 확인 안 함" else "마지막 확인 " + ((System.currentTimeMillis() - last) / 60_000).let { if (it < 1) "방금" else "${it}분 전" }
        val note = Prefs.teslaLastNote(this)
        teslaStatus.text = when {
            !on -> "–  테슬라 연결 안 됨"
            else -> "✔  테슬라 연결됨 (세션 ${TeslaDest.mask(Prefs.teslaSession(this))})" +
                (if (!Prefs.teslaFollow(this)) " · 따라가기 꺼짐" else "") + "\n$ago" + (if (note.isNotEmpty()) " · $note" else "")
        }
        teslaStatus.setTextColor(if (on) 0xFF2E8B63.toInt() else 0xFF8C7F78.toInt())
    }

    private fun saveTeslaKey() {
        val s = teslaKeyEdit?.text?.toString()?.trim().orEmpty()
        if (!TeslaLink.valid(s)) { Toast.makeText(this, "세션 키 형식이 아닙니다 — 브라우저에 보이는 긴 영문·숫자를 그대로 붙여 넣으세요", Toast.LENGTH_LONG).show(); return }
        TeslaLink.accept(this, s)
        teslaKeyEdit?.setText("")
        refreshStatus()
        checkTeslaNow()
    }

    private fun checkTeslaNow() {
        teslaStatus.text = "확인 중…"
        // 감시 중이면 서비스가 확인하고 목적지를 바로 적용한다(결과는 기록·상태로). 아니면 여기서 확인만.
        if (DriveState.running && !DriveState.simulating) { DriveService.send(this, DriveService.ACTION_TESLA_CHECK); return }
        TeslaLink.fetch(this) { r ->
            if (r is TeslaDest.Reply.Error) Toast.makeText(this, "테슬라: ${r.message}", Toast.LENGTH_LONG).show()
            refreshStatus()
        }
    }

    /** 보드 경유 시험: 감시가 꺼져 있으면 보드 연결부터 켠다(설정 화면을 닫으면 끔). */
    private fun boardReadyForTest(): Boolean {
        if (!Prefs.viaBoard(this) || !Prefs.bleOn(this)) return true
        if (!S3xyButton.running) {
            S3xyButton.start(this, { DriveState.log(it) }, false)
            Toast.makeText(this, "보드에 연결하는 중 — 상태 줄이 '커맨더 준비'가 되면 다시 누르세요", Toast.LENGTH_LONG).show()
            return false
        }
        if (!S3xyButton.ready) { Toast.makeText(this, "아직 준비 안 됨: ${S3xyButton.status}", Toast.LENGTH_LONG).show(); return false }
        return true
    }

    private fun pairBoard() {
        AlertDialog.Builder(this).setTitle("보드 페어링")
            .setMessage("1. 보드가 전원에 꽂혀 있고 폰 가까이 있는지 확인\n2. 보드 BOOT 버튼을 3초 넘게 누름 → 파란 불이 두 번씩 깜빡임(1분 동안)\n3. 아래 [찾기] → 폰에 페어링 요청이 뜨면 승인\n\n10~30초 걸립니다.")
            .setPositiveButton("찾기") { _, _ ->
                Toast.makeText(this, "보드 찾는 중…", Toast.LENGTH_SHORT).show()
                BoardLink.pair(this, { DriveState.log(it) }) { ok, msg ->
                    runOnUiThread {
                        if (ok) { Prefs.setViaBoard(applicationContext, true); BoardScan.update(applicationContext, "보드 페어링") }
                        // 기다리는 동안 설정 화면을 나갔거나 돌렸으면 창 대신 짧은 알림(닫힌 화면에 창을 띄우면 앱이 죽음)
                        if (isFinishing || isDestroyed) { Toast.makeText(applicationContext, (if (ok) "보드 페어링 됨" else "보드 페어링 실패") + " — 기록을 보세요", Toast.LENGTH_LONG).show(); return@runOnUiThread }
                        AlertDialog.Builder(this).setTitle(if (ok) "보드 페어링 됨" else "보드 페어링 실패").setMessage(msg + if (ok) "\n\n커맨더 연결을 '보드 경유'로 바꿨습니다. 감시를 다시 켜면 적용됩니다." else "")
                            .setPositiveButton("확인") { _, _ -> recreate() }.show()
                    }
                }
            }
            .setNegativeButton("취소", null).show()
    }

    private fun togglePairing() {
        if (Prefs.viaBoard(this)) { Toast.makeText(this, "보드 경유 중입니다 — [보드 페어링]을 쓰세요", Toast.LENGTH_LONG).show(); return }
        if (S3xyButton.running && S3xyButton.pairingMode) { S3xyButton.stop(); refreshStatus(); return }
        if (S3xyButton.running) { Toast.makeText(this, "이미 광고 중", Toast.LENGTH_SHORT).show(); return }
        val ok = S3xyButton.start(this, { DriveState.log(it) }, Prefs.renameAdapter(this))
        if (ok) S3xyButton.beginPairing()
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
