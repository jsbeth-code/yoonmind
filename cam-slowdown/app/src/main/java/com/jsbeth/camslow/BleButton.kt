package com.jsbeth.camslow

import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.content.Context
import android.os.ParcelUuid
import org.json.JSONObject
import java.util.concurrent.Executors

/**
 * 캡처한 S3XY 버튼 광고 패킷을 그대로 재현하는 프로필.
 * 실제 버튼 프로토콜은 비공개라, CaptureActivity로 기록한 값을 여기에 채운다. 예:
 * {
 *   "manufacturerId": 1234,          // 제조사 ID(10진수)
 *   "serviceUuid": "",               // 광고에 서비스 UUID가 있었다면
 *   "includeName": false,
 *   "click": ["0102AABB"],           // 한 번 클릭 때 나온 제조사 데이터(16진수), 여러 프레임 가능
 *   "doubleClick": ["0103AABB"],
 *   "frameMs": 250,                  // 프레임당 송출 시간
 *   "gapMs": 900,                    // 누름과 누름 사이 간격
 *   "counterByte": -1                // 누를 때마다 1씩 올라가는 바이트 위치(없으면 -1)
 * }
 */
data class BleProfile(
    val manufacturerId: Int,
    val serviceUuid: String,
    val includeName: Boolean,
    val click: List<ByteArray>,
    val doubleClick: List<ByteArray>,
    val frameMs: Long,
    val gapMs: Long,
    val counterByte: Int,
) {
    companion object {
        fun parse(json: String): BleProfile {
            val o = JSONObject(json)
            fun frames(key: String): List<ByteArray> {
                val a = o.getJSONArray(key)
                return (0 until a.length()).map { hex(a.getString(it)) }
            }
            return BleProfile(
                manufacturerId = o.getInt("manufacturerId"),
                serviceUuid = o.optString("serviceUuid", ""),
                includeName = o.optBoolean("includeName", false),
                click = frames("click"),
                doubleClick = frames("doubleClick"),
                frameMs = o.optLong("frameMs", 250),
                gapMs = o.optLong("gapMs", 900),
                counterByte = o.optInt("counterByte", -1),
            )
        }

        fun hex(s: String): ByteArray {
            val c = s.replace(" ", "").replace(":", "").replace("0x", "")
            require(c.length % 2 == 0) { "16진수 길이가 홀수: $s" }
            return ByteArray(c.length / 2) { c.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
        }
    }
}

/** 폰을 가상 S3XY 버튼처럼 광고시킨다. 누름 요청은 순서대로 하나씩 처리한다. */
class BleButtonEmulator(private val ctx: Context) {
    private val worker = Executors.newSingleThreadExecutor()
    private var counter = 0

    enum class Press { CLICK, DOUBLE }

    fun press(kind: Press, times: Int, log: (String) -> Unit) {
        val json = Prefs.bleProfile(ctx)
        if (json.isBlank()) { log("BLE 프로필이 비어 있음 — 설정에서 캡처 값을 넣어야 합니다"); return }
        val profile = try { BleProfile.parse(json) } catch (e: Exception) {
            log("BLE 프로필 오류: ${e.message}"); return
        }
        worker.execute {
            repeat(times) { i ->
                val frames = if (kind == Press.CLICK) profile.click else profile.doubleClick
                counter = (counter + 1) and 0xff
                for (f in frames) sendFrame(profile, f, log)
                log("BLE ${if (kind == Press.CLICK) "클릭" else "더블클릭"} ${i + 1}/$times 송출")
                Thread.sleep(profile.gapMs)
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun sendFrame(p: BleProfile, payload: ByteArray, log: (String) -> Unit) {
        val adv = (ctx.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager)
            .adapter?.bluetoothLeAdvertiser
        if (adv == null) { log("블루투스 광고 불가(블루투스 꺼짐?)"); return }
        val data = payload.copyOf()
        if (p.counterByte in data.indices) data[p.counterByte] = counter.toByte()
        val settings = AdvertiseSettings.Builder()
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
            .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_HIGH)
            .setConnectable(false)
            .build()
        val b = AdvertiseData.Builder()
            .setIncludeDeviceName(p.includeName)
            .addManufacturerData(p.manufacturerId, data)
        if (p.serviceUuid.isNotBlank()) b.addServiceUuid(ParcelUuid.fromString(p.serviceUuid))
        val cb = object : AdvertiseCallback() {
            override fun onStartFailure(errorCode: Int) { log("광고 시작 실패 code=$errorCode") }
        }
        try {
            adv.startAdvertising(settings, b.build(), cb)
            Thread.sleep(p.frameMs)
            adv.stopAdvertising(cb)
        } catch (e: SecurityException) {
            log("블루투스 권한 없음")
        }
    }
}
