package com.jsbeth.camslow

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CamCardTest {

    @Test fun modePriority() {
        assertEquals(CamCard.Mode.OFF, CamCard.mode(false, true, true, true))
        assertEquals(CamCard.Mode.OVER, CamCard.mode(true, true, true, true))
        assertEquals(CamCard.Mode.SLOWING, CamCard.mode(true, true, false, true))
        assertEquals(CamCard.Mode.KAKAO_DOWN, CamCard.mode(true, false, false, true))
        assertEquals(CamCard.Mode.NORMAL, CamCard.mode(true, false, false, false))
        assertEquals(CamCard.GREEN, CamCard.color(CamCard.Mode.NORMAL))
        assertEquals(CamCard.BLUE, CamCard.color(CamCard.Mode.SLOWING))
        assertEquals(CamCard.RED, CamCard.color(CamCard.Mode.OVER, blinkOn = false))
    }

    @Test fun gaugeMovesAndChangesColor() {
        // 시안: 480 m 흰색, 250 m(신호 183 m) 노랑, 90 m 감속 중 빨강
        val far = CamCard.gauge(480.0, 183.0, slowed = false)!!
        assertEquals(0.04f, far.carFrac, 1e-4f)
        assertEquals(CamCard.BAR_WHITE, far.remainColor)
        assertEquals(1f - 183f / 500f, far.triggerFrac!!, 1e-4f)
        assertEquals(CamCard.BAR_YELLOW, CamCard.gauge(250.0, 183.0, false)!!.remainColor)
        assertEquals(CamCard.BAR_RED, CamCard.gauge(90.0, 183.0, true)!!.remainColor)
        // 신호 지점을 지났는데 아직 감속 이벤트 전이어도 빨강
        assertEquals(CamCard.BAR_RED, CamCard.gauge(170.0, 183.0, false)!!.remainColor)
        // 500 m 밖: 차는 왼쪽 끝, 신호 눈금이 범위 밖이면 없음
        val out = CamCard.gauge(1200.0, 650.0, false)!!
        assertEquals(0f, out.carFrac, 1e-6f)
        assertNull(out.triggerFrac)
        assertNull(CamCard.gauge(null, 100.0, false))
    }

    @Test fun bottomLines() {
        assertEquals("67 m 더 가면 감속 · Mad Max → 컴포트",
            CamCard.bottomLine(CamCard.Mode.NORMAL, 250.0, 183.0, "Mad Max", "컴포트", true))
        assertEquals("카메라 지나면 Mad Max로 복귀",
            CamCard.bottomLine(CamCard.Mode.SLOWING, 90.0, 183.0, "Mad Max", "컴포트", true))
        assertEquals("183 m 앞에서 감속 · 카카오 연결 안 됨",
            CamCard.bottomLine(CamCard.Mode.KAKAO_DOWN, 250.0, 183.0, "Mad Max", "컴포트", true))
        assertEquals("속도를 줄이세요", CamCard.bottomLine(CamCard.Mode.OVER, 250.0, 183.0, "A", "B", true))
        assertEquals("2 km 안에 카메라 없음 · A 유지", CamCard.bottomLine(CamCard.Mode.NORMAL, null, null, "A", "B", true))
        assertEquals("구간 끝까지 유지", CamCard.bottomLine(CamCard.Mode.SLOWING, 90.0, 183.0, "A", "B", true, holdText = "구간 끝까지 유지"))
        assertEquals("● 작동 중 · 경로 따라감", CamCard.statusLine(CamCard.Mode.NORMAL, true, "컴포트", null, null))
    }
}
