package com.jsbeth.camslow

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TeslaDestTest {

    @Test fun parsesDestination() {
        val r = TeslaDest.parse("""{"online":true,"destination":{"name":"잠실 롯데월드몰","lat":37.5126,"lon":127.1025,"minutes":18,"km":12.4}}""")
        val d = (r as TeslaDest.Reply.Online).dest!!
        assertEquals("잠실 롯데월드몰", d.name)
        assertEquals(37.5126, d.lat, 1e-9)
        assertEquals(127.1025, d.lon, 1e-9)
        assertEquals(18.0, d.minutes!!, 1e-9)
        assertEquals(12.4, d.km!!, 1e-9)
    }

    @Test fun offlineAndNoDestinationAndError() {
        assertTrue(TeslaDest.parse("""{"online":false}""") is TeslaDest.Reply.Offline)
        assertNull((TeslaDest.parse("""{"online":true,"destination":null}""") as TeslaDest.Reply.Online).dest)
        assertEquals("login required", (TeslaDest.parse("""{"error":"login required"}""") as TeslaDest.Reply.Error).message)
        assertTrue(TeslaDest.parse("<html>") is TeslaDest.Reply.Error)
        // 좌표 0,0은 목적지 없음으로
        assertNull((TeslaDest.parse("""{"online":true,"destination":{"name":"","lat":0,"lon":0,"minutes":null,"km":null}}""") as TeslaDest.Reply.Online).dest)
    }

    @Test fun escapesAndUnicode() {
        val r = TeslaDest.parse("""{ "online" : true , "destination" : { "name" : "A \"B\" 잠실", "lat" : -1.5e1, "lon" : 2 } }""")
        val d = (r as TeslaDest.Reply.Online).dest!!
        assertEquals("A \"B\" 잠실", d.name)
        assertEquals(-15.0, d.lat, 1e-9)
        assertNull(d.minutes)
    }

    @Test fun changeDetection() {
        val a = TeslaDest.Dest("집", 37.5, 127.0, 10.0, 5.0)
        assertFalse(TeslaDest.changed(null, null))
        assertTrue(TeslaDest.changed(null, a))
        assertTrue(TeslaDest.changed(a, null))
        // 남은 시간만 바뀜 → 같은 목적지
        assertFalse(TeslaDest.changed(a, a.copy(minutes = 8.0, km = 4.0)))
        // 100 m 움직임 → 같은 목적지, 500 m → 바뀜
        assertFalse(TeslaDest.changed(a, a.copy(lat = 37.5009)))
        assertTrue(TeslaDest.changed(a, a.copy(lat = 37.5045)))
        assertTrue(TeslaDest.changed(a, a.copy(name = "회사")))
    }

    @Test fun maskHidesSession() {
        assertEquals("a1b2••••", TeslaDest.mask("a1b2c3d4e5f6a7b8c9d0e1f2"))
        assertEquals("••••", TeslaDest.mask("abc"))
        assertEquals("37.5000, 127.0000", TeslaDest.label(TeslaDest.Dest(" ", 37.5, 127.0, null, null)))
    }
}
