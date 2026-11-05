package com.jsbeth.camslow

import org.junit.Assert.assertEquals
import org.junit.Test

class TurnTest {
    @Test fun codeNames() {
        assertEquals(TurnType.RIGHT, TurnType.fromCodeName("KNRGCode_RightTurn"))
        assertEquals(TurnType.LEFT, TurnType.fromCodeName("KNRGCode_LeftTurn"))
        assertEquals(TurnType.UTURN, TurnType.fromCodeName("KNRGCode_UTurn"))
        assertEquals(TurnType.STRAIGHT, TurnType.fromCodeName("KNRGCode_Straight"))
        assertEquals(TurnType.SLIGHT_RIGHT, TurnType.fromCodeName("KNRGCode_Direction_1oclock"))
        assertEquals(TurnType.SLIGHT_LEFT, TurnType.fromCodeName("KNRGCode_Direction_11oclock"))
        assertEquals(TurnType.EXIT_RIGHT, TurnType.fromCodeName("KNRGCode_OutHighway_Right"))
        assertEquals(TurnType.ARRIVE, TurnType.fromCodeName("KNRGCode_Goal"))
        assertEquals(TurnType.ROTARY, TurnType.fromCodeName("KNRGCode_Rotary_3oclock"))
        assertEquals(TurnType.OTHER, TurnType.fromCodeName(null))
        // KNSDK 1.12.7 실제 이름
        assertEquals(TurnType.SLIGHT_LEFT, TurnType.fromCodeName("KNRGCode_Direction_11"))
        assertEquals(TurnType.STRAIGHT, TurnType.fromCodeName("KNRGCode_Direction_12"))
        assertEquals(TurnType.RIGHT, TurnType.fromCodeName("KNRGCode_Direction_3"))
        assertEquals(TurnType.SLIGHT_LEFT, TurnType.fromCodeName("KNRGCode_LeftDirection"))
        assertEquals(TurnType.EXIT_RIGHT, TurnType.fromCodeName("KNRGCode_RightOutHighway"))
        assertEquals(TurnType.EXIT, TurnType.fromCodeName("KNRGCode_OutCityway"))
        assertEquals(TurnType.ENTER, TurnType.fromCodeName("KNRGCode_LeftInHighway"))
        assertEquals(TurnType.ROTARY, TurnType.fromCodeName("KNRGCode_RotaryDirection_3"))
        assertEquals(TurnType.VIA, TurnType.fromCodeName("KNRGCode_Via"))
    }

    @Test fun lines() {
        assertEquals("우회전 · 신반포로 방면", TurnInfo(TurnType.RIGHT, 620, "신반포로").line())
        assertEquals("좌회전 · 잠실 방면", TurnInfo(TurnType.LEFT, 620, "잠실 방면").line())
        assertEquals("직진", TurnInfo(TurnType.STRAIGHT, 620, " ").line())
        assertEquals("620 m", TurnInfo.dist(627))
        assertEquals("1.2 km", TurnInfo.dist(1234))
    }
}
