package com.jsbeth.camslow

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ApproachAlertsTest {
    private val cam = Camera(37.5, 127.0, 50, CamKind.SPOT, 0, "반포대로", "1")

    /** 600 m 앞에서 kmh로 카메라까지. 각 출력과 그때 남은 거리. */
    private fun run(a: ApproachAlerts, kmh: Double, from: Double = 600.0): List<Pair<ApproachAlerts.Out, Double>> {
        val out = ArrayList<Pair<ApproachAlerts.Out, Double>>()
        var d = from; var t = 0L
        val step = kmh / 3.6 / 4   // 0.25초마다
        while (d > -20) {
            a.update(t, if (d >= 0) cam else null, if (d >= 0) d else null, kmh, null).forEach { out += it to d }
            d -= step; t += 250
        }
        return out
    }

    private fun says(o: List<Pair<ApproachAlerts.Out, Double>>) = o.mapNotNull { (it.first as? ApproachAlerts.Out.Say)?.text }
    private fun beeps(o: List<Pair<ApproachAlerts.Out, Double>>) = o.filter { it.first is ApproachAlerts.Out.Beep }.map { it.second }

    @Test fun underLimit_onlyApproachAnnouncement_noBeep() {
        val o = run(ApproachAlerts(), 55.0)   // 50 + 7 이하
        assertEquals(listOf("500미터 앞, 제한속도 50 과속카메라입니다"), says(o))
        assertTrue(beeps(o).isEmpty())
    }

    @Test fun speeding_distanceCallsAndFasterBeeps() {
        val o = run(ApproachAlerts(), 62.0)
        assertEquals(listOf("500미터 앞, 제한속도 50 과속카메라입니다", "300미터, 감속하세요", "100미터, 감속하세요"), says(o))
        val b = beeps(o)
        val far = b.count { it > 300 }; val mid = b.count { it in 100.0..300.0 }; val near = b.count { it < 100 }
        // 62 km/h ≈ 17 m/s: 500→300 약 11.6초(2초마다 ≈6), 300→100 약 11.6초(1초마다 ≈12), 100→0 약 5.8초(0.5초마다 ≈12)
        assertTrue("far $far", far in 5..7); assertTrue("mid $mid", mid in 11..13); assertTrue("near $near", near in 11..13)
        assertTrue(b.none { it < 0 })   // 지나면 멈춤
    }

    @Test fun distanceAlways_saysEvenWhenNotSpeeding() {
        val o = run(ApproachAlerts(distanceAlways = true), 45.0)
        assertEquals(listOf("500미터 앞, 제한속도 50 과속카메라입니다", "300미터", "100미터"), says(o))
    }

    @Test fun firstSeenClose_announcesActualDistanceOnce() {
        val o = run(ApproachAlerts(), 45.0, from = 260.0)
        assertEquals(listOf("250미터 앞, 제한속도 50 과속카메라입니다"), says(o))
    }

    @Test fun announceOff_noApproachVoice() {
        assertTrue(says(run(ApproachAlerts(announce = false), 45.0)).isEmpty())
    }

    @Test fun section_beepsEvery2s_whenOverSectionLimit() {
        val a = ApproachAlerts()
        var beeps = 0
        for (i in 0 until 40) beeps += a.update(i * 250L, null, null, 90.0, 80).count { it is ApproachAlerts.Out.Beep }
        assertEquals(5, beeps)   // 10초 동안 2초마다
        assertEquals(10, a.overspeedKmh)
        a.update(20_000, null, null, 85.0, 80)
        assertNull(a.overspeedKmh)
    }
}
