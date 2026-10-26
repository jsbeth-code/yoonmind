package com.jsbeth.camslow

import android.app.Activity
import android.os.Bundle
import android.widget.Toast

class SettingsActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val ui = Ui(this)
        val c = Prefs.engine(this)
        ui.text("감속 타이밍", 18f, bold = true)
        val decel = ui.number("감속도 m/s² (작을수록 일찍·천천히 내림)", c.decelMps2.toString())
        val reaction = ui.number("반응 여유 시간(초)", c.reactionS.toString())
        val margin = ui.number("추가 여유 거리(m)", c.marginM.toString())
        val restore = ui.number("통과 후 복귀 거리(m)", c.restoreAfterM.toString())
        val minLimit = ui.number("이 제한속도 미만 카메라 무시 (80 → 고속도로만, 0 → 전부)", c.minLimitKmh.toString(), decimal = false)
        val skip = ui.check("이미 제한속도 이하로 달리면 신호 생략", c.skipIfBelowLimit)

        ui.text("출력", 18f, bold = true)
        val alerts = ui.check("음성·효과음 안내", Prefs.alertsOn(this))
        val ble = ui.check("가상 S3XY 버튼 신호 보내기 (BLE)", Prefs.bleOn(this))
        val down = ui.number("감속 시 클릭 횟수 (휠 아래)", Prefs.downPresses(this).toString(), decimal = false)
        val up = ui.number("복귀 시 더블클릭 횟수 (휠 위)", Prefs.upPresses(this).toString(), decimal = false)
        val profile = ui.multiline("BLE 프로필 (JSON — 캡처 결과로 채움)", Prefs.bleProfile(this))

        ui.button("저장") {
            try {
                Prefs.saveEngine(this, c.copy(
                    decelMps2 = decel.text.toString().toDouble(),
                    reactionS = reaction.text.toString().toDouble(),
                    marginM = margin.text.toString().toDouble(),
                    restoreAfterM = restore.text.toString().toDouble(),
                    minLimitKmh = minLimit.text.toString().toInt(),
                    skipIfBelowLimit = skip.isChecked,
                ))
                val json = profile.text.toString().trim()
                if (json.isNotEmpty()) BleProfile.parse(json) // 형식 검증
                Prefs.saveOutputs(this, down.text.toString().toInt(), up.text.toString().toInt(),
                    alerts.isChecked, ble.isChecked, json)
                Toast.makeText(this, "저장됨 — 주행 중이면 정지 후 다시 시작하세요", Toast.LENGTH_LONG).show()
                finish()
            } catch (e: Exception) {
                Toast.makeText(this, "입력 오류: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }
        ui.show()
    }
}
