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
import android.bluetooth.le.AdvertisingSet
import android.bluetooth.le.AdvertisingSetCallback
import android.bluetooth.le.AdvertisingSetParameters
import android.bluetooth.le.AdvertiseData
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Handler
import android.os.SystemClock
import android.os.Looper
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
    private val main = Handler(Looper.getMainLooper())
    private const val BOND_DELAY_MS = 400L
    /** 본딩 진행·결과를 기록에 남긴다(커맨더 연결 문제 진단용). */
    private val bondReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            val dev: BluetoothDevice = (if (Build.VERSION.SDK_INT >= 33) i.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
                else @Suppress("DEPRECATION") i.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)) ?: return
            if (dev.address != device?.address) return
            val st = i.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE, BluetoothDevice.BOND_NONE)
            val prev = i.getIntExtra(BluetoothDevice.EXTRA_PREVIOUS_BOND_STATE, BluetoothDevice.BOND_NONE)
            log(when {
                st == BluetoothDevice.BOND_BONDED -> "본딩 됨 — 커맨더 핸드셰이크 대기"
                st == BluetoothDevice.BOND_BONDING -> "본딩 진행 중"
                prev == BluetoothDevice.BOND_BONDING -> "본딩 실패(거절·시간 초과) — [페어링하기]로 다시"
                else -> "본딩 상태: 안 됨"
            })
        }
    }
    private var bondReceiverOn = false

    /** 알림을 구독했던 커맨더 주소(본딩 기기의 CCCD 유지용). */
    private fun subscribedAddrs(): Set<String> =
        app?.getSharedPreferences("s3xy", 0)?.getStringSet("subscribed", emptySet()) ?: emptySet()
    private fun saveSubscribed(addr: String, on: Boolean) {
        val sp = app?.getSharedPreferences("s3xy", 0) ?: return
        val set = HashSet(sp.getStringSet("subscribed", emptySet()) ?: emptySet())
        if (on) set += addr else set -= addr
        sp.edit().putStringSet("subscribed", set).apply()
    }
    private val sent = Semaphore(0)
    /** 마지막 알림의 실제 전송 결과(onNotificationSent). */
    @Volatile private var lastSentStatus = BluetoothGatt.GATT_SUCCESS
    /** 알림 결과: 보냄 / 실패 / 확인 못 함(300 ms 안에 전송 결과가 안 옴). */
    private const val SENT = 1
    private const val FAILED = 0
    private const val UNKNOWN = -1
    /** 커맨더인지 아직 확인 안 된 접속(구독 기록이 없을 때 본딩된 첫 접속). 쓰기가 오면 확정·저장. */
    @Volatile private var provisional = false
    /** 저장된 커맨더가 붙자마자(2.5초 안) 끊기는 일이 2분 안에 2번 — 페어링 키가 어긋난 것으로 보고 다시 페어링이 필요하다고 알린다. */
    @Volatile var repairNeeded = false
        private set
    private var connectAt = 0L
    private val quickDrops = ArrayList<Long>()
    /** 주행과 무관하게 페어링을 위해 켜 둔 상태. 서비스가 끝나도 끄지 않는다.
     *  v0.14.4: 커맨더가 붙으면(감시 중) 또는 10분 지나면 저절로 끝난다 — 예전엔 안 끝나서 감시를 껐다 켜도 버튼이 안 지워졌다. */
    @Volatile var pairingMode = false
        private set
    private val pairingTimeout = Runnable {
        if (!pairingMode) return@Runnable
        pairingMode = false
        log("페어링 모드 자동 끝(10분)")
        // 감시 중이 아니고 버튼 유지도 아니면 끈다(유지 설정이면 같은 주소를 지키려고 남긴다)
        val keep = app?.let { Prefs.bleOn(it) && Prefs.keepButton(it) } == true
        if (server != null && !DriveState.running && !keep) stop()
    }
    fun beginPairing() {
        pairingMode = true
        main.removeCallbacks(pairingTimeout); main.postDelayed(pairingTimeout, 10 * 60_000L)
    }
    private fun endPairing(reason: String) {
        if (!pairingMode || !DriveState.running) return
        pairingMode = false; main.removeCallbacks(pairingTimeout)
        log("페어링 모드 끝($reason)")
    }
    /** 보내는 중인 누름 묶음의 진행: (보낸 수, 전체). 다 보내면 null. 화면이 "클릭 2/3"을 보여 주는 데 쓴다. */
    @Volatile var progress: Pair<Int, Int>? = null
    @Volatile var progressKind: Press? = null

    val running: Boolean get() = server != null
    /** 확인 전(임시) 커맨더는 준비로 치지 않는다 — 워치 같은 다른 본딩 기기에도 전송 성공이 와서 클릭이 셈에 들어가기 때문. */
    val ready: Boolean get() = device != null && subscribed && !provisional
    /** 아무 기기도 접속해 있지 않은지(커맨더가 아예 안 찾아옴). */
    val idle: Boolean get() = server != null && device == null

    /** 커맨더가 안 올 때 광고를 다시 켠다. v0.14.4: 광고를 새로 만들지 않고 같은 광고를 다시 켠다(새로 만들면 폰 주소가 바뀌어 커맨더가 못 알아보는 것으로 추정). */
    fun readvertise() {
        if (server == null || device != null) return
        log("커맨더가 안 와서 같은 광고를 다시 켬(주소 유지)")
        reEnableAdvertising()
    }
    val status: String
        get() = when {
            server == null -> "꺼짐"
            ready -> "커맨더 연결됨 · 준비"
            device != null && provisional -> "커맨더인지 확인 중 — 확인 전엔 신호 안 보냄(안 풀리면 [페어링하기] 후 enhauto에서 버튼 다시 추가)"
            device != null -> "커맨더 접속됨 · 버튼 준비 안 됨(안 풀리면 [페어링하기] 후 enhauto에서 버튼 다시 추가)"
            advertising -> "ENH_BTN 광고 중 · 커맨더 대기"
            else -> "광고 안 됨"
        }

    /** 클릭 times번을 보내는 데 걸리는 시간(초). 감속 거리 계산에 더한다. */
    fun pressSeconds(times: Int, gapMs: Long): Double = times * (gapMs + 60) / 1000.0

    /** v0.14.4: 광고는 광고 묶음(AdvertisingSet) 하나로 켜고, 끊긴 뒤·커맨더가 안 올 때는 그 묶음을 다시 켠다(새로 만들지 않음). */
    @Volatile private var advSet: AdvertisingSet? = null
    /** 진단: 버튼을 켠 뒤 광고를 새로 만든 횟수·같은 광고를 다시 켠 횟수. */
    @Volatile private var advCreated = 0
    @Volatile private var advReEnabled = 0
    private val advCb = object : AdvertisingSetCallback() {
        override fun onAdvertisingSetStarted(set: AdvertisingSet?, txPower: Int, status: Int) {
            if (status == AdvertisingSetCallback.ADVERTISE_SUCCESS && set != null) {
                advSet = set; advertising = true; advCreated++
                log("ENH_BTN 광고 시작(새 광고 ${advCreated}번째) — 커맨더에서 [Add S3XY Button]")
            } else { advSet = null; advertising = false; log("ENH_BTN 광고 실패 code=$status") }
        }
        override fun onAdvertisingEnabled(set: AdvertisingSet?, enable: Boolean, status: Int) {
            if (!enable) return
            if (status == AdvertisingSetCallback.ADVERTISE_SUCCESS) advertising = true
            else log("같은 광고 다시 켜기 결과 code=$status")
        }
        override fun onAdvertisingSetStopped(set: AdvertisingSet?) { if (set == advSet) { advSet = null; advertising = false } }
    }

    /** 같은 광고 묶음을 다시 켠다. 묶음이 없으면 새로 만든다(이때는 주소가 바뀔 수 있음 — 기록에 남김). */
    private fun reEnableAdvertising() {
        val a = app?.getSystemService(BluetoothManager::class.java)?.adapter ?: return
        if (server == null) return
        val set = advSet
        if (set != null) {
            try { set.enableAdvertising(true, 0, 0); advReEnabled++; return } catch (e: Exception) { log("같은 광고 다시 켜기 오류: ${e.message} — 새로 만듦") }
        }
        log("광고 묶음이 없어 새로 만듦(폰 주소가 바뀔 수 있음)")
        startAdvertising(a)
    }

    /** 진단: 폰 블루투스 켜짐/꺼짐, 저장된 커맨더와의 블루투스 링크 연결/끊김. 동작은 바꾸지 않고 기록만. */
    private val diagReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            when (i.action) {
                BluetoothAdapter.ACTION_STATE_CHANGED -> when (i.getIntExtra(BluetoothAdapter.EXTRA_STATE, -1)) {
                    BluetoothAdapter.STATE_OFF -> { advertising = false; advSet = null; log("⚠ 폰 블루투스 꺼짐 — 버튼(광고·GATT)이 같이 꺼졌을 수 있음") }
                    BluetoothAdapter.STATE_ON -> log("폰 블루투스 켜짐")
                }
                BluetoothDevice.ACTION_ACL_CONNECTED, BluetoothDevice.ACTION_ACL_DISCONNECTED -> {
                    val dev: BluetoothDevice = (if (Build.VERSION.SDK_INT >= 33) i.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
                        else @Suppress("DEPRECATION") i.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)) ?: return
                    if (dev.address !in subscribedAddrs()) return
                    log("커맨더 블루투스 링크 " + (if (i.action == BluetoothDevice.ACTION_ACL_CONNECTED) "연결" else "끊김") + " ${dev.address}")
                }
            }
        }
    }
    private var diagReceiverOn = false

    /** v0.14.3: 버튼(GATT 서버·광고)을 새로 켠 시각(elapsedRealtime). 0 = 꺼짐. 새로 켠 뒤 커맨더가 안 오면 빨리 다시 등록 안내. */
    @Volatile var startedAt = 0L
        private set

    /** v0.14.3 조사: 폰에 커맨더 본딩(정식 페어링)이 남아 있는지 기록. 버튼을 새로 켤 때, 감시 시작 때, 커맨더가 1분 넘게 안 올 때. */
    fun logBond(reason: String) {
        val adapter = app?.getSystemService(BluetoothManager::class.java)?.adapter ?: return
        runCatching {
            val known = subscribedAddrs()
            val bondedNow = adapter.bondedDevices.orEmpty().filter { it.address in known }.map { it.address }
            log("커맨더 본딩 확인($reason): 저장된 커맨더 ${known.size}개, 폰에 본딩 남음 ${bondedNow.size}개" +
                (if (known.isNotEmpty()) " (${known.joinToString { a -> a + if (a in bondedNow) " 본딩" else " 없음" }})" else "") +
                (if (startedAt > 0L) " · 버튼 켠 지 ${(SystemClock.elapsedRealtime() - startedAt) / 60_000}분" else "") +
                " · 블루투스 ${if (adapter.isEnabled) "켜짐" else "꺼짐"}, 이름 ${adapter.name}, " +
                (if (advertising) "광고 중" else "광고 안 됨") + "(새 광고 ${advCreated}번·다시 켬 ${advReEnabled}번)" +
                ", 페어링 모드 ${if (pairingMode) "켬" else "끔"}, 유지 설정 ${if (app?.let { Prefs.keepButton(it) } == true) "켬" else "끔"}")
        }.onFailure { log("커맨더 본딩 확인($reason) 실패: ${it.javaClass.simpleName}") }
    }

    @Synchronized
    fun start(context: Context, logger: (String) -> Unit, renameAdapter: Boolean): Boolean {
        log = logger
        if (server != null) { logBond("감시 시작 · 버튼 유지 중"); return true }
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
            if (!bondReceiverOn) {
                try { ctx.registerReceiver(bondReceiver, IntentFilter(BluetoothDevice.ACTION_BOND_STATE_CHANGED)); bondReceiverOn = true } catch (_: Exception) {}
            }
            if (!diagReceiverOn) {
                try {
                    ctx.registerReceiver(diagReceiver, IntentFilter().apply {
                        addAction(BluetoothAdapter.ACTION_STATE_CHANGED)
                        addAction(BluetoothDevice.ACTION_ACL_CONNECTED)
                        addAction(BluetoothDevice.ACTION_ACL_DISCONNECTED)
                    })
                    diagReceiverOn = true
                } catch (_: Exception) {}
            }
            advCreated = 0; advReEnabled = 0
            // v0.13.3 조사: 앱을 다시 켜면 커맨더가 안 붙는 원인 확인용 — 폰 쪽에 커맨더 본딩(정식 페어링)이 남아 있는지 기록
            startedAt = SystemClock.elapsedRealtime()
            logBond("버튼 새로 켬")
            startAdvertising(adapter)
            return true
        } catch (e: SecurityException) {
            log("블루투스 권한 없음: ${e.message}"); stop(); return false
        }
    }

    private fun startAdvertising(adapter: BluetoothAdapter) {
        val adv = adapter.bluetoothLeAdvertiser ?: run { log("이 폰은 BLE 광고를 지원하지 않음"); return }
        val params = AdvertisingSetParameters.Builder()
            .setLegacyMode(true)
            .setConnectable(true)
            .setScannable(true)
            .setInterval(AdvertisingSetParameters.INTERVAL_LOW)
            .setTxPowerLevel(AdvertisingSetParameters.TX_POWER_HIGH)
            .build()
        val data = AdvertiseData.Builder().setIncludeDeviceName(true).build()
        val scanResp = AdvertiseData.Builder().addServiceUuid(ParcelUuid(SERVICE_UUID)).build()
        // 남아 있는 광고 묶음이 있으면 먼저 정리(두 개가 겹치지 않게)
        try { adv.stopAdvertisingSet(advCb) } catch (_: Exception) {}
        advSet = null
        try { adv.startAdvertisingSet(params, data, scanResp, null, null, advCb) } catch (e: Exception) { log("광고 시작 오류: ${e.message}") }
    }

    @Synchronized
    fun stop() {
        startedAt = 0L
        pairingMode = false
        main.removeCallbacks(pairingTimeout)
        try {
            app?.getSystemService(BluetoothManager::class.java)?.adapter?.let { a ->
                a.bluetoothLeAdvertiser?.stopAdvertisingSet(advCb)
                if (renamed) { previousName?.let { a.setName(it) }; renamed = false }
            }
        } catch (_: Exception) {}
        advertising = false; advSet = null
        try { server?.close() } catch (_: Exception) {}
        server = null; notifyChar = null; device = null; subscribed = false; provisional = false
        if (bondReceiverOn) { try { app?.unregisterReceiver(bondReceiver) } catch (_: Exception) {}; bondReceiverOn = false }
        if (diagReceiverOn) { try { app?.unregisterReceiver(diagReceiver) } catch (_: Exception) {}; diagReceiverOn = false }
        log("ENH_BTN 끔")
    }

    /** 커맨더만 쓰는 버튼 특성에 써 온 기기 → 커맨더로 확정(임시 가정이었으면 저장). */
    private fun confirmCommander(dev: BluetoothDevice) {
        if (device?.address != dev.address) return
        if (provisional) { provisional = false; saveSubscribed(dev.address, true); log("커맨더 확인됨 — 저장") }
        repairNeeded = false; quickDrops.clear()
        endPairing("커맨더 확인됨")
    }

    private fun bondName(state: Int) = when (state) {
        BluetoothDevice.BOND_BONDED -> "됨"; BluetoothDevice.BOND_BONDING -> "진행 중"; else -> "안 됨"
    }

    private fun hex(b: ByteArray) = b.joinToString(" ") { "%02X".format(it) }

    private val callback = object : BluetoothGattServerCallback() {
        override fun onConnectionStateChange(dev: BluetoothDevice, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                // 이 콜백은 폰의 모든 LE 접속(워치·이어버드·폰키 등)에 불린다. 이미 준비된 커맨더가 있으면 다른 기기는 무시한다.
                val cur = device
                if (cur != null && subscribed && !provisional && cur.address != dev.address) {
                    log("다른 BLE 기기 접속 ${dev.address} — 커맨더가 아니므로 무시"); return
                }
                // 본딩된 기기의 알림 구독(CCCD)은 다시 접속해도 유지되는 것이 BLE 규칙이라, 커맨더는 재접속 때 구독을 다시 쓰지 않는다.
                // → 구독했던 커맨더(저장된 주소)면 그대로 살린다.
                // 저장된 주소가 아예 없으면(v0.10.7 이전에 붙인 커맨더) 본딩된 접속을 임시로 커맨더로 보되, 커맨더가 무엇이든 써 오면 그때 저장한다.
                val known = subscribedAddrs()
                val bonded = dev.bondState == BluetoothDevice.BOND_BONDED
                // 임시 가정한 기기가 있을 때: 본딩된 새 접속이면 그쪽으로 바꾼다(커맨더는 차 시동 때 새로 붙음), 본딩 안 된 건 무시
                if (cur != null && subscribed && cur.address != dev.address && !bonded) {
                    log("다른 BLE 기기 접속 ${dev.address} — 무시"); return
                }
                device = dev
                provisional = bonded && dev.address !in known && known.isEmpty()
                subscribed = bonded && (dev.address in known || provisional)
                log("BLE 접속 ${dev.address} (본딩 ${bondName(dev.bondState)})" +
                    when { subscribed && !provisional -> " — 커맨더, 이전 구독 유지, 버튼 준비"; provisional -> " — 커맨더로 가정(확인 전)"; else -> "" })
                if (subscribed && !provisional) endPairing("커맨더 붙음")
                // 실제 버튼(ESP32 예제도)은 접속하자마자 버튼 쪽에서 보안(본딩)을 요청한다. 커맨더는 이걸 기다리다 몇 초 뒤 끊는다.
                // 그래서 본딩이 안 돼 있으면 폰이 먼저 본딩을 요청한다 → 폰에 페어링 요청 창(승인).
                // 단, 엉뚱한 기기에 페어링 창이 뜨지 않도록 [페어링하기]를 눌렀거나 아직 커맨더가 하나도 없을 때만.
                connectAt = SystemClock.elapsedRealtime()
                // 저장된 커맨더(주소가 늘 같음)인데 본딩이 풀렸으면 [페어링하기] 없이 바로 다시 페어링을 요청한다
                if (!bonded && dev.address in known && !pairingMode) log("저장된 커맨더인데 페어링이 풀림 — 다시 페어링을 요청합니다(폰 승인 창)")
                if (!bonded && !pairingMode && known.isNotEmpty() && dev.address !in known) log("본딩 안 된 기기 — 커맨더를 새로 붙이려면 설정에서 [페어링하기]")
                else if (!bonded) main.postDelayed({
                    try {
                        if (device?.address == dev.address && dev.bondState == BluetoothDevice.BOND_NONE) {
                            val ok = dev.createBond()
                            log(if (ok) "본딩 요청 보냄 — 폰에 뜨는 페어링 요청을 승인하세요" else "본딩 요청 실패")
                        }
                    } catch (e: SecurityException) { log("본딩 요청 권한 없음: ${e.message}") }
                }, BOND_DELAY_MS)
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                if (device?.address != dev.address) return
                device = null; subscribed = false; provisional = false
                log("커맨더 연결 끊김")
                val nowT = SystemClock.elapsedRealtime()
                if (dev.address in subscribedAddrs() && nowT - connectAt < 2_500) {
                    quickDrops.add(nowT); quickDrops.removeAll { nowT - it > 120_000 }
                    if (quickDrops.size >= 2 && !repairNeeded) {
                        repairNeeded = true
                        log("⚠ 커맨더가 붙자마자 끊기기를 반복 — 페어링 키가 어긋난 듯. enhauto에서 버튼 삭제 후 [페어링하기]로 다시 추가")
                    }
                }
                // 접속 중엔 광고가 멈추므로 다시 켠다. v0.14.4: 새로 만들지 않고 같은 광고를 다시 켠다(주소 유지 — 추정)
                if (server != null) reEnableAdvertising()
            }
        }

        override fun onCharacteristicReadRequest(dev: BluetoothDevice, requestId: Int, offset: Int, ch: BluetoothGattCharacteristic) {
            val v = if (ch.uuid == ID_UUID) BUTTON_ID else byteArrayOf(0)
            if (ch.uuid == ID_UUID) confirmCommander(dev) // 버튼 ID를 읽는 건 커맨더뿐
            server?.sendResponse(dev, requestId, BluetoothGatt.GATT_SUCCESS, offset, v.copyOfRange(min(offset, v.size), v.size))
        }

        override fun onCharacteristicWriteRequest(
            dev: BluetoothDevice, requestId: Int, ch: BluetoothGattCharacteristic,
            preparedWrite: Boolean, responseNeeded: Boolean, offset: Int, value: ByteArray?,
        ) {
            if (responseNeeded) server?.sendResponse(dev, requestId, BluetoothGatt.GATT_SUCCESS, offset, value)
            if (ch.uuid != ID_UUID || value == null || value.isEmpty()) return
            log("커맨더 명령 ${hex(value)}")
            confirmCommander(dev)
            when {
                value.size == 1 && value[0] == 0xB6.toByte() ->
                    worker.execute { if (notify(byteArrayOf(0xC7.toByte(), 0x00, 0x01))) log("핸드셰이크 응답 — 커맨더가 버튼으로 인식") }
                value.size == 1 && value[0] == 0xA1.toByte() -> { log("커맨더가 연결 해제(페어링 삭제) 요청"); saveSubscribed(dev.address, false); server?.cancelConnection(dev) }
                value.size == 4 && value[0] == 0xA4.toByte() ->
                    worker.execute { notify(byteArrayOf(0xA4.toByte(), 0x00, value[1], value[2])) }
            }
        }

        override fun onDescriptorWriteRequest(
            dev: BluetoothDevice, requestId: Int, desc: BluetoothGattDescriptor,
            preparedWrite: Boolean, responseNeeded: Boolean, offset: Int, value: ByteArray?,
        ) {
            if (desc.uuid == CCCD_UUID && value != null) {
                if (device?.address != dev.address) device = dev
                provisional = false
                subscribed = value.isNotEmpty() && (value[0].toInt() and 1) != 0
                saveSubscribed(dev.address, subscribed)
                log(if (subscribed) "알림 구독됨 — 버튼 준비" else "알림 구독 해제")
                if (subscribed) { repairNeeded = false; quickDrops.clear(); endPairing("알림 구독됨") }
            }
            if (responseNeeded) server?.sendResponse(dev, requestId, BluetoothGatt.GATT_SUCCESS, offset, value)
        }

        override fun onDescriptorReadRequest(dev: BluetoothDevice, requestId: Int, offset: Int, desc: BluetoothGattDescriptor) {
            val v = if (desc.uuid == CCCD_UUID) (if (subscribed) byteArrayOf(1, 0) else byteArrayOf(0, 0)) else byteArrayOf()
            server?.sendResponse(dev, requestId, BluetoothGatt.GATT_SUCCESS, offset, v)
        }

        override fun onNotificationSent(dev: BluetoothDevice, status: Int) { lastSentStatus = status; sent.release() }
    }

    /**
     * 알림 한 개를 보내고 실제 전송 결과(onNotificationSent)를 최대 300 ms 기다린다. 작업 스레드에서만 부른다.
     * 요청이 받아들여진 것만으로는 "보냄"으로 치지 않는다 — 링크가 끊기는 순간엔 요청은 성공해도 전송은 실패한다.
     */
    private fun send(bytes: ByteArray): Int {
        val s = server ?: return FAILED
        val d = device ?: return FAILED
        val c = notifyChar ?: return FAILED
        if (!subscribed) return FAILED
        sent.drainPermits()
        lastSentStatus = BluetoothGatt.GATT_SUCCESS
        val ok = try {
            if (Build.VERSION.SDK_INT >= 33) s.notifyCharacteristicChanged(d, c, false, bytes) == BluetoothStatusCodes.SUCCESS
            else { @Suppress("DEPRECATION") c.value = bytes; @Suppress("DEPRECATION") s.notifyCharacteristicChanged(d, c, false) }
        } catch (e: Exception) { log("알림 전송 오류: ${e.message}"); false }
        if (!ok) return FAILED
        if (!sent.tryAcquire(300, TimeUnit.MILLISECONDS)) {
            // 늦게 오는 이 알림의 결과가 다음 알림 결과로 읽히지 않게 조금 더 기다려 흡수한다(오면 버림).
            sent.tryAcquire(1000, TimeUnit.MILLISECONDS)
            return UNKNOWN
        }
        return if (lastSentStatus == BluetoothGatt.GATT_SUCCESS) SENT else FAILED
    }

    private fun notify(bytes: ByteArray): Boolean = send(bytes) == SENT

    /** 알림 여러 개를 5 ms 간격으로. 하나라도 실패면 FAILED, 확인 못 한 게 있으면 UNKNOWN. */
    private fun sendAll(vararg parts: ByteArray): Int {
        var worst = SENT
        for ((i, p) in parts.withIndex()) {
            if (i > 0) Thread.sleep(5)
            val r = send(p)
            if (r == FAILED) return FAILED
            if (r < worst) worst = r
        }
        return worst
    }

    /** 버튼 누름 times번. 누름 사이 gapMs. 순서대로 하나씩 보낸다. */
    fun press(kind: Press, times: Int, gapMs: Long, onDone: ((sent: Int, asked: Int) -> Unit)? = null) =
        press(kind, { times }, gapMs, onDone)

    /**
     * 누를 횟수를 보낼 차례(작업 스레드)에 정한다 — 앞서 넣은 감속 클릭이 실제로 몇 번 나갔는지 본 뒤 복귀 횟수를 정하기 위해.
     * onDone(실제로 보낸 수, 보내려던 수)은 작업 스레드에서 부른다. 커맨더가 준비 안 됐으면 0.
     */
    fun press(kind: Press, timesOf: () -> Int, gapMs: Long, onDone: ((sent: Int, asked: Int) -> Unit)? = null) {
        worker.execute {
            val times = timesOf().coerceAtLeast(0)
            var sentCount = 0
            if (times == 0) { onDone?.invoke(0, 0); return@execute }
            if (!ready) { log((if (provisional) "커맨더 확인 전이라" else "커맨더가 연결·구독되지 않아") + " ${kind.label} 못 보냄 (상태: $status)"); onDone?.invoke(0, times); return@execute }
            progressKind = kind; progress = 0 to times
            try {
                for (i in 0 until times) {
                    val r = when (kind) {
                        Press.SINGLE -> sendAll(byteArrayOf(0x01), byteArrayOf(0x00), byteArrayOf(0xC1.toByte(), 0x01), byteArrayOf(0x00))
                        Press.DOUBLE -> sendAll(byteArrayOf(0xC1.toByte(), 0x02))
                        Press.LONG -> sendAll(byteArrayOf(0xC3.toByte(), 0x01))
                    }
                    // 확인 못 한 클릭은 차를 더 빠르게 보는 쪽으로 센다: 감속(SINGLE)은 안 나간 것으로(복귀에서 덜 올림),
                    // 복귀(DOUBLE)는 나간 것으로(다음 [지금 복귀]에서 더 올리지 않음). 감속 쪽은 확인 못 하면 거기서 멈춘다.
                    val counted = r == SENT || (r == UNKNOWN && kind != Press.SINGLE)
                    if (counted) { sentCount = i + 1; progress = (i + 1) to times }
                    log(when (r) {
                        SENT -> "BLE ${kind.label} ${i + 1}/$times 송출"
                        UNKNOWN -> "BLE ${kind.label} ${i + 1}/$times 전송 확인 못 함" + (if (counted) " — 나간 것으로 봄" else " — 안 나간 것으로 봄")
                        else -> "BLE ${kind.label} ${i + 1}/$times 실패 (상태: $status)"
                    })
                    if (!counted) break
                    Thread.sleep(gapMs)
                }
            } finally { progress = null; progressKind = null }
            onDone?.invoke(sentCount, times)
        }
    }

}
