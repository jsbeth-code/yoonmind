package com.jsbeth.camslow

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattServer
import android.bluetooth.BluetoothGattServerCallback
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothStatusCodes
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.content.Context
import android.os.Build
import android.os.ParcelUuid
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import kotlin.math.min

/**
 * 폰을 S3XY 버튼(BLE 이름 "ENH_BTN")처럼 만든다.
 * 실제 버튼은 GATT 서버(peripheral)이고, 커맨더가 중앙(central)으로 접속해 본딩(LE Secure Connections, Just Works)한 뒤
 * 알림을 구독하고, 버튼은 누름을 알림 패킷으로 보낸다. 광고 패킷을 흉내 내는 방식으로는 커맨더가 반응하지 않는다.
 *
 * 프로토콜(UUID·바이트)은 Beat-YT/s3xy-virtual-button (MIT, ESP32로 Gen2 커맨더와 동작 확인)에서 가져왔다:
 *  - 서비스 3d46, 알림 특성 3d50(READ|NOTIFY + CCCD), ID 특성 3d49(READ|WRITE, 암호화 필요, 10바이트 ID)
 *  - 커맨더 → ID 쓰기: B6 = 핸드셰이크(응답 C7 00 01), A1 = 연결 해제 요청, A4 xx yy zz = 이름표 (응답 A4 00 xx yy)
 *  - 버튼 → 알림: 클릭 = 01, 00, C1 01, 00 / 더블클릭 = C1 02 / 길게 = C3 01
 *
 * 폰의 BLE 주소는 수시로 바뀌는 랜덤 주소지만, 본딩 때 식별 키(IRK)를 주고받으므로 커맨더는 재접속할 수 있다.
 */
@SuppressLint("MissingPermission")
object S3xyButton {
    val SERVICE_UUID: UUID = UUID.fromString("00003d46-87d2-479e-7e45-8551415a6de1")
    val NOTIFY_UUID: UUID = UUID.fromString("00003d50-87d2-479e-7e45-8551415a6de1")
    val ID_UUID: UUID = UUID.fromString("00003d49-87d2-479e-7e45-8551415a6de1")
    val CCCD_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
    const val NAME = "ENH_BTN"
    private val BUTTON_ID = "CAMSLOWBTN".toByteArray(Charsets.US_ASCII) // 실제 버튼처럼 10바이트

    enum class Press(val label: String) { SINGLE("클릭"), DOUBLE("더블클릭"), LONG("길게") }

    private var app: Context? = null
    @Volatile private var log: (String) -> Unit = {}
    private var server: BluetoothGattServer? = null
    private var notifyChar: BluetoothGattCharacteristic? = null
    @Volatile private var device: BluetoothDevice? = null
    @Volatile private var subscribed = false
    @Volatile private var advertising = false
    private var renamed = false
    private var previousName: String? = null
    private val worker = Executors.newSingleThreadExecutor()
    private val sent = Semaphore(0)
    /** 주행과 무관하게 페어링을 위해 켜 둔 상태. 서비스가 끝나도 끄지 않는다. */
    @Volatile var pairingMode = false

    val running: Boolean get() = server != null
    val ready: Boolean get() = device != null && subscribed
    val status: String
        get() = when {
            server == null -> "꺼짐"
            ready -> "커맨더 연결됨 · 준비"
            device != null -> "커맨더 연결됨 · 구독 대기(페어링 확인 필요할 수 있음)"
            advertising -> "ENH_BTN 광고 중 · 커맨더 대기"
            else -> "광고 안 됨"
        }

    /** 클릭 times번을 보내는 데 걸리는 시간(초). 감속 거리 계산에 더한다. */
    fun pressSeconds(times: Int, gapMs: Long): Double = times * (gapMs + 60) / 1000.0

    private val advCb = object : AdvertiseCallback() {
        override fun onStartSuccess(settingsInEffect: AdvertiseSettings?) { advertising = true; log("ENH_BTN 광고 시작 — 커맨더에서 [Add S3XY Button]") }
        override fun onStartFailure(errorCode: Int) { advertising = false; log("ENH_BTN 광고 실패 code=$errorCode") }
    }

    @Synchronized
    fun start(context: Context, logger: (String) -> Unit, renameAdapter: Boolean): Boolean {
        log = logger
        if (server != null) return true
        val ctx = context.applicationContext
        app = ctx
        val manager = ctx.getSystemService(BluetoothManager::class.java)
        val adapter = manager?.adapter
        if (adapter == null || !adapter.isEnabled) { log("블루투스가 꺼져 있음"); return false }
        try {
            val s = manager.openGattServer(ctx, callback) ?: run { log("GATT 서버 열기 실패"); return false }
            server = s
            val service = BluetoothGattService(SERVICE_UUID, BluetoothGattService.SERVICE_TYPE_PRIMARY)
            val n = BluetoothGattCharacteristic(
                NOTIFY_UUID,
                BluetoothGattCharacteristic.PROPERTY_READ or BluetoothGattCharacteristic.PROPERTY_NOTIFY,
                BluetoothGattCharacteristic.PERMISSION_READ,
            )
            n.addDescriptor(BluetoothGattDescriptor(CCCD_UUID, BluetoothGattDescriptor.PERMISSION_READ or BluetoothGattDescriptor.PERMISSION_WRITE))
            val id = BluetoothGattCharacteristic(
                ID_UUID,
                BluetoothGattCharacteristic.PROPERTY_READ or BluetoothGattCharacteristic.PROPERTY_WRITE,
                BluetoothGattCharacteristic.PERMISSION_READ_ENCRYPTED or BluetoothGattCharacteristic.PERMISSION_WRITE_ENCRYPTED,
            )
            service.addCharacteristic(n)
            service.addCharacteristic(id)
            notifyChar = n
            if (!s.addService(service)) { log("GATT 서비스 등록 실패"); stop(); return false }
            if (renameAdapter && adapter.name != NAME) {
                previousName = adapter.name
                renamed = adapter.setName(NAME)
                log(if (renamed) "폰 블루투스 이름을 ENH_BTN으로 바꿈(끄면 되돌림)" else "폰 블루투스 이름 변경 실패 — 커맨더가 못 찾을 수 있음")
            }
            startAdvertising(adapter)
            return true
        } catch (e: SecurityException) {
            log("블루투스 권한 없음: ${e.message}"); stop(); return false
        }
    }

    private fun startAdvertising(adapter: BluetoothAdapter) {
        val adv = adapter.bluetoothLeAdvertiser ?: run { log("이 폰은 BLE 광고를 지원하지 않음"); return }
        val settings = AdvertiseSettings.Builder()
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
            .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_HIGH)
            .setConnectable(true)
            .setTimeout(0)
            .build()
        val data = AdvertiseData.Builder().setIncludeDeviceName(true).build()
        val scanResp = AdvertiseData.Builder().addServiceUuid(ParcelUuid(SERVICE_UUID)).build()
        try { adv.startAdvertising(settings, data, scanResp, advCb) } catch (e: Exception) { log("광고 시작 오류: ${e.message}") }
    }

    @Synchronized
    fun stop() {
        pairingMode = false
        try {
            app?.getSystemService(BluetoothManager::class.java)?.adapter?.let { a ->
                a.bluetoothLeAdvertiser?.stopAdvertising(advCb)
                if (renamed) { previousName?.let { a.setName(it) }; renamed = false }
            }
        } catch (_: Exception) {}
        advertising = false
        try { server?.close() } catch (_: Exception) {}
        server = null; notifyChar = null; device = null; subscribed = false
        log("ENH_BTN 끔")
    }

    private fun bondName(state: Int) = when (state) {
        BluetoothDevice.BOND_BONDED -> "됨"; BluetoothDevice.BOND_BONDING -> "진행 중"; else -> "안 됨"
    }

    private fun hex(b: ByteArray) = b.joinToString(" ") { "%02X".format(it) }

    private val callback = object : BluetoothGattServerCallback() {
        override fun onConnectionStateChange(dev: BluetoothDevice, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                device = dev; subscribed = false
                log("커맨더 접속 ${dev.address} (본딩 ${bondName(dev.bondState)})")
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                if (device?.address == dev.address) { device = null; subscribed = false }
                log("커맨더 연결 끊김")
                // 접속 중엔 광고가 멈추는 폰이 있어 껐다가 다시 켠다
                app?.getSystemService(BluetoothManager::class.java)?.adapter?.let { a ->
                    if (server != null) { try { a.bluetoothLeAdvertiser?.stopAdvertising(advCb) } catch (_: Exception) {}; startAdvertising(a) }
                }
            }
        }

        override fun onCharacteristicReadRequest(dev: BluetoothDevice, requestId: Int, offset: Int, ch: BluetoothGattCharacteristic) {
            val v = if (ch.uuid == ID_UUID) BUTTON_ID else byteArrayOf(0)
            server?.sendResponse(dev, requestId, BluetoothGatt.GATT_SUCCESS, offset, v.copyOfRange(min(offset, v.size), v.size))
        }

        override fun onCharacteristicWriteRequest(
            dev: BluetoothDevice, requestId: Int, ch: BluetoothGattCharacteristic,
            preparedWrite: Boolean, responseNeeded: Boolean, offset: Int, value: ByteArray?,
        ) {
            if (responseNeeded) server?.sendResponse(dev, requestId, BluetoothGatt.GATT_SUCCESS, offset, value)
            if (ch.uuid != ID_UUID || value == null || value.isEmpty()) return
            log("커맨더 명령 ${hex(value)}")
            when {
                value.size == 1 && value[0] == 0xB6.toByte() ->
                    worker.execute { if (notify(byteArrayOf(0xC7.toByte(), 0x00, 0x01))) log("핸드셰이크 응답 — 커맨더가 버튼으로 인식") }
                value.size == 1 && value[0] == 0xA1.toByte() -> { log("커맨더가 연결 해제(페어링 삭제) 요청"); server?.cancelConnection(dev) }
                value.size == 4 && value[0] == 0xA4.toByte() ->
                    worker.execute { notify(byteArrayOf(0xA4.toByte(), 0x00, value[1], value[2])) }
            }
        }

        override fun onDescriptorWriteRequest(
            dev: BluetoothDevice, requestId: Int, desc: BluetoothGattDescriptor,
            preparedWrite: Boolean, responseNeeded: Boolean, offset: Int, value: ByteArray?,
        ) {
            if (desc.uuid == CCCD_UUID && value != null) {
                subscribed = value.isNotEmpty() && (value[0].toInt() and 1) != 0
                log(if (subscribed) "알림 구독됨 — 버튼 준비" else "알림 구독 해제")
            }
            if (responseNeeded) server?.sendResponse(dev, requestId, BluetoothGatt.GATT_SUCCESS, offset, value)
        }

        override fun onDescriptorReadRequest(dev: BluetoothDevice, requestId: Int, offset: Int, desc: BluetoothGattDescriptor) {
            val v = if (desc.uuid == CCCD_UUID) (if (subscribed) byteArrayOf(1, 0) else byteArrayOf(0, 0)) else byteArrayOf()
            server?.sendResponse(dev, requestId, BluetoothGatt.GATT_SUCCESS, offset, v)
        }

        override fun onNotificationSent(dev: BluetoothDevice, status: Int) { sent.release() }
    }

    /** 알림 한 개를 보내고 전송 완료까지(최대 300 ms) 기다린다. 작업 스레드에서만 부른다. */
    private fun notify(bytes: ByteArray): Boolean {
        val s = server ?: return false
        val d = device ?: return false
        val c = notifyChar ?: return false
        if (!subscribed) return false
        sent.drainPermits()
        val ok = try {
            if (Build.VERSION.SDK_INT >= 33) s.notifyCharacteristicChanged(d, c, false, bytes) == BluetoothStatusCodes.SUCCESS
            else { @Suppress("DEPRECATION") c.value = bytes; @Suppress("DEPRECATION") s.notifyCharacteristicChanged(d, c, false) }
        } catch (e: Exception) { log("알림 전송 오류: ${e.message}"); false }
        if (ok) sent.tryAcquire(300, TimeUnit.MILLISECONDS)
        return ok
    }

    /** 버튼 누름 times번. 누름 사이 gapMs. 순서대로 하나씩 보낸다. */
    fun press(kind: Press, times: Int, gapMs: Long) {
        worker.execute {
            if (!ready) { log("커맨더가 연결·구독되지 않아 ${kind.label} 못 보냄 (상태: $status)"); return@execute }
            repeat(times) { i ->
                val ok = when (kind) {
                    Press.SINGLE -> notify(byteArrayOf(0x01)) && gap(5) && notify(byteArrayOf(0x00)) && gap(5) &&
                        notify(byteArrayOf(0xC1.toByte(), 0x01)) && gap(5) && notify(byteArrayOf(0x00))
                    Press.DOUBLE -> notify(byteArrayOf(0xC1.toByte(), 0x02))
                    Press.LONG -> notify(byteArrayOf(0xC3.toByte(), 0x01))
                }
                log(if (ok) "BLE ${kind.label} ${i + 1}/$times 송출" else "BLE ${kind.label} ${i + 1}/$times 실패 (상태: $status)")
                if (!ok) return@execute
                Thread.sleep(gapMs)
            }
        }
    }

    private fun gap(ms: Long): Boolean { Thread.sleep(ms); return true }
}
