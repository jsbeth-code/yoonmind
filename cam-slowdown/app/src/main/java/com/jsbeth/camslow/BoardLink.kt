package com.jsbeth.camslow

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import java.util.UUID
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit

/**
 * v0.15.0 보드 경유: 폰이 커맨더 버튼 노릇을 하는 대신, 주소가 고정인 ESP32 보드(cam-slowdown-board)가 커맨더 버튼이 되고
 * 폰은 보드에 접속해 "내림/올림 N번"만 보낸다. 보드는 실제로 나간 누름 수를 돌려준다.
 * 프로토콜은 보드 src/main.cpp 머리말과 같다(서비스 c5a1f000-…, 명령 c5a1f001, 결과 c5a1f002).
 * 보드는 페어링한 폰 하나만 받는다: 보드 BOOT 3초 → 1분 창 → [보드 페어링]으로 받은 토큰(16바이트)을 저장해 접속마다 인사로 보낸다.
 */
@SuppressLint("MissingPermission")
object BoardLink {
    private val SERVICE: UUID = UUID.fromString("c5a1f000-3b7e-4c2a-9f61-2d8e7b40a1c5")
    private val CMD: UUID = UUID.fromString("c5a1f001-3b7e-4c2a-9f61-2d8e7b40a1c5")
    private val EVT: UUID = UUID.fromString("c5a1f002-3b7e-4c2a-9f61-2d8e7b40a1c5")
    private val CCCD: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

    private var app: Context? = null
    @Volatile private var log: (String) -> Unit = {}
    private val main = Handler(Looper.getMainLooper())

    /** 감시(또는 설정 시험)가 보드 연결을 원하는 중. */
    @Volatile var active = false
        private set
    @Volatile private var gatt: BluetoothGatt? = null
    @Volatile private var cmdChar: BluetoothGattCharacteristic? = null
    /** 폰↔보드: 블루투스 연결 + 서비스 찾음. */
    @Volatile private var linkUp = false
    /** 보드가 이 폰의 토큰을 받아들임. */
    @Volatile private var authed = false
    @Volatile private var authRejected = false
    @Volatile private var oldFirmware = false
    /** 보드↔커맨더: 0 없음, 1 접속만(준비 전), 2 준비. */
    @Volatile private var cmdState = 0

    val ready: Boolean get() = active && linkUp && authed && cmdState == 2

    val status: String
        get() {
            val ctx = app
            return when {
                !active -> "꺼짐"
                ctx != null && Prefs.boardAddr(ctx).isEmpty() -> "보드 페어링 안 됨 — 설정 ⑥ [보드 페어링]"
                oldFirmware -> "보드 프로그램이 옛 버전 — 보드에 새 프로그램을 넣으세요"
                !linkUp -> "보드 찾는 중 — 보드 전원(차 USB)·거리 확인"
                authRejected -> "보드가 이 폰을 모름 — 보드 BOOT 3초 후 설정 ⑥ [보드 페어링]"
                !authed -> "보드 인증 중"
                cmdState == 2 -> "보드 경유 · 커맨더 준비"
                cmdState == 1 -> "보드 연결됨 · 커맨더 접속만(준비 전)"
                else -> "보드 연결됨 · 커맨더 없음(차가 켜졌는지·엔하우토에 보드가 등록됐는지)"
            }
        }

    // ---- 쓰기(작업 스레드에서 하나씩) ----
    private val writeDone = Semaphore(0)
    @Volatile private var writeStatus = 0
    private val writeLock = Any()

    private fun writeCmd(bytes: ByteArray): Int {
        val g = gatt ?: return -1
        val c = cmdChar ?: return -1
        synchronized(writeLock) {
            writeDone.drainPermits()
            writeStatus = -1
            val ok = try {
                if (Build.VERSION.SDK_INT >= 33) g.writeCharacteristic(c, bytes, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT) == 0
                else {
                    @Suppress("DEPRECATION") c.value = bytes
                    c.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                    @Suppress("DEPRECATION") g.writeCharacteristic(c)
                }
            } catch (e: Exception) { log("보드에 쓰기 오류: ${e.message}"); false }
            if (!ok) return -1
            if (!writeDone.tryAcquire(3, TimeUnit.SECONDS)) return -2
            return writeStatus
        }
    }

    /** 보드가 쓰기를 거절한 GATT 상태(쓰기 금지·인증·암호화 부족) — 명령이 보드에 안 들어감. */
    private val REJECTED = setOf(3, 5, 15)

    // ---- 누름 ----
    private val pressDone = Semaphore(0)
    @Volatile private var pressSeq = 0
    @Volatile private var pressConfirmed = 0
    @Volatile private var pressResult: IntArray? = null // [나간 수, 요청 수, 사유]
    @Volatile private var onPressProgress: ((Int) -> Unit)? = null

    /**
     * 보드에 누름 times번을 맡기고 실제로 나간 수를 돌려준다(작업 스레드에서만 부른다).
     * 결과가 안 오면(폰↔보드가 끊김) S3xyButton과 같은 쪽으로 센다: 감속(클릭)은 확인된 것만, 복귀(더블클릭)는 다 나간 것으로 —
     * 보드는 폰이 끊겨도 받은 누름을 끝까지 커맨더에 보낸다.
     */
    fun press(kind: S3xyButton.Press, times: Int, gapMs: Long, onProgress: (Int) -> Unit): Int {
        if (!ready) return 0
        val k = when (kind) { S3xyButton.Press.SINGLE -> 1; S3xyButton.Press.DOUBLE -> 2; else -> return 0 }
        val n = times.coerceIn(1, 20)
        val gap = gapMs.coerceIn(0, 3000).toInt()
        val seq = (pressSeq + 1) and 0xFF
        pressSeq = seq; pressConfirmed = 0; pressResult = null; onPressProgress = onProgress
        pressDone.drainPermits()
        val w = writeCmd(byteArrayOf(0x10, k.toByte(), n.toByte(), (gap and 0xFF).toByte(), (gap shr 8).toByte(), seq.toByte()))
        if (w != 0) {
            onPressProgress = null
            // 복귀는 보드가 분명히 안 받은 경우(폰에서 못 보냄 -1, 보드가 쓰기 거절 3·5·15)만 0으로 센다.
            // 그 밖(응답 없음, 133 같은 끊김 오류)은 보드가 받아 끝까지 올렸을 수 있으니 나간 것으로 본다(차를 더 빠르게 보는 쪽).
            if (kind == S3xyButton.Press.DOUBLE && w != -1 && w !in REJECTED) { log("보드에 복귀 요청 전달 확인 못 함(${if (w == -2) "응답 없음" else "오류 $w"}) — 나간 것으로 봄"); return n }
            log("보드에 ${kind.label} 요청 못 보냄(${if (w == -2) "응답 없음" else "오류 $w"})")
            return 0
        }
        val waitMs = n * (gap + 200L) + 3_000L
        val got = pressDone.tryAcquire(waitMs, TimeUnit.MILLISECONDS)
        onPressProgress = null
        val r = pressResult
        if (got && r != null) {
            if (r[2] != 0) log("보드: ${kind.label} ${r[0]}/${r[1]} — " + when (r[2]) { 1 -> "이 폰이 인증 안 됨"; 2 -> "커맨더 준비 안 됨"; else -> "잘못된 명령" })
            return r[0]
        }
        return if (kind == S3xyButton.Press.SINGLE) {
            log("보드 결과 못 받음 — 확인된 감속 클릭 ${pressConfirmed}번만 셈"); pressConfirmed
        } else {
            log("보드 결과 못 받음 — 복귀 클릭은 다 나간 것으로 봄"); n
        }
    }

    // ---- 연결 ----
    @Synchronized
    fun start(ctx: Context, logger: (String) -> Unit) {
        log = logger
        app = ctx.applicationContext
        if (active) return
        active = true
        quiet = false
        lastOkAt = android.os.SystemClock.elapsedRealtime()
        connect()
        main.removeCallbacks(watchdog); main.postDelayed(watchdog, 15_000)
    }

    @Synchronized
    fun stop() {
        if (!active) return
        active = false
        main.removeCallbacks(reconnect); main.removeCallbacks(watchdog)
        closeGatt()
        log("보드 연결 끔")
    }

    private fun closeGatt() {
        val g = gatt
        gatt = null; cmdChar = null; linkUp = false; authed = false; cmdState = 0
        try { g?.disconnect() } catch (_: Exception) {}
        try { g?.close() } catch (_: Exception) {}
    }

    private val reconnect = Runnable { if (active) connect() }

    /** 마지막으로 정상(인증됨)이었거나 새로 접속을 시작한 시각. */
    @Volatile private var lastOkAt = 0L
    @Volatile private var quiet = false
    /** 1분 넘게 인증까지 못 가면(인사 실패·서비스 못 찾음·블루투스 껐다 켬 등으로 멈춤) 연결을 처음부터 다시 한다. */
    private val watchdog = object : Runnable {
        override fun run() {
            if (!active) return
            val now = android.os.SystemClock.elapsedRealtime()
            if (linkUp && authed) lastOkAt = now
            else if (now - lastOkAt > 60_000) { quiet = true; lastOkAt = now; connect() }
            main.postDelayed(this, 15_000)
        }
    }

    private fun connect() {
        val ctx = app ?: return
        val addr = Prefs.boardAddr(ctx)
        if (addr.isEmpty()) { if (!quiet) log("보드 경유: 보드 페어링이 안 됨 — 설정 ⑥ [보드 페어링]"); DriveState.changed(); return }
        val adapter = ctx.getSystemService(BluetoothManager::class.java)?.adapter
        if (adapter == null || !adapter.isEnabled) { log("블루투스가 꺼져 있음 — 10초 뒤 다시"); main.postDelayed(reconnect, 10_000); return }
        closeGatt()
        if (!quiet) { authRejected = false; oldFirmware = false }
        try {
            val dev = adapter.getRemoteDevice(addr)
            gatt = dev.connectGatt(ctx, true, callback, BluetoothDevice.TRANSPORT_LE)
            if (!quiet) log("보드 찾는 중 ${addr}")
        } catch (e: Exception) {
            log("보드 접속 오류: ${e.message} — 10초 뒤 다시"); main.postDelayed(reconnect, 10_000)
        }
    }

    private val worker = java.util.concurrent.Executors.newSingleThreadExecutor()

    /** 서비스를 찾은 뒤: 결과 알림 켜고 인사(토큰). 암호화(본딩) 전이면 쓰기가 막히므로 몇 번 다시 한다. */
    private fun setup(g: BluetoothGatt) = worker.execute {
        val svc = g.getService(SERVICE)
        if (svc == null) { oldFirmware = true; if (!quiet) log("보드에 폰 서비스가 없음 — 보드 프로그램이 옛 버전(1단계)"); DriveState.changed(); return@execute }
        val cmd = svc.getCharacteristic(CMD); val evt = svc.getCharacteristic(EVT)
        if (cmd == null || evt == null) { oldFirmware = true; log("보드 서비스 모양이 다름 — 보드 프로그램 확인"); return@execute }
        oldFirmware = false
        cmdChar = cmd
        g.setCharacteristicNotification(evt, true)
        evt.getDescriptor(CCCD)?.let { d ->
            synchronized(writeLock) {
                writeDone.drainPermits()
                val v = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                val ok = try {
                    if (Build.VERSION.SDK_INT >= 33) g.writeDescriptor(d, v) == 0
                    else { @Suppress("DEPRECATION") d.value = v; @Suppress("DEPRECATION") g.writeDescriptor(d) }
                } catch (_: Exception) { false }
                if (ok) writeDone.tryAcquire(3, TimeUnit.SECONDS)
                Unit
            }
        }
        linkUp = true
        DriveState.changed()
        val ctx = app ?: return@execute
        val tok = hexToBytes(Prefs.boardToken(ctx))
        if (tok == null || tok.size != 16) { authRejected = true; if (!quiet) log("보드 토큰 없음 — 보드 BOOT 3초 후 설정 ⑥ [보드 페어링]"); DriveState.changed(); return@execute }
        for (attempt in 1..6) {
            if (gatt !== g || !active) return@execute
            val w = writeCmd(byteArrayOf(0x01) + tok)
            if (w == 0) return@execute // 결과는 알림(81)으로
            if (!quiet) log("보드 인사 실패(${w}) — 암호화 기다렸다 다시 ${attempt}/6")
            Thread.sleep(5_000)
        }
        if (!quiet) log("⚠ 보드와 암호화가 안 됨 — 보드에서 페어링을 지웠다면 폰 블루투스 설정에서 ENH_BTN '기기 삭제' 후 보드 BOOT 3초 → [보드 페어링]. 1분마다 다시 시도")
    }

    private fun handleEvt(v: ByteArray) {
        if (v.isEmpty()) return
        when (v[0].toInt() and 0xFF) {
            0x81 -> {
                authed = v.size >= 2 && v[1].toInt() == 1
                authRejected = !authed
                if (authed) { quiet = false; lastOkAt = android.os.SystemClock.elapsedRealtime() }
                if (authed || !quiet) log(if (authed) "보드 연결·인증됨" else "⚠ 보드가 이 폰을 거절 — 보드 BOOT 3초 후 설정 ⑥ [보드 페어링]")
                DriveState.changed()
            }
            0x83 -> if (v.size >= 2) {
                val s = v[1].toInt() and 0xFF
                if (s != cmdState) log("보드↔커맨더: " + when (s) { 2 -> "준비"; 1 -> "접속만(준비 전)"; else -> "없음" })
                cmdState = s
                DriveState.changed()
            }
            0x90 -> if (v.size >= 4 && (v[1].toInt() and 0xFF) == pressSeq) {
                pressConfirmed = v[2].toInt() and 0xFF
                onPressProgress?.invoke(pressConfirmed)
            }
            0x91 -> if (v.size >= 5 && (v[1].toInt() and 0xFF) == pressSeq) {
                pressResult = intArrayOf(v[2].toInt() and 0xFF, v[3].toInt() and 0xFF, v[4].toInt() and 0xFF)
                pressDone.release()
            }
        }
    }

    private val callback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            if (g !== gatt) return
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                log("보드 블루투스 연결")
                main.postDelayed({ if (g === gatt) try { g.discoverServices() } catch (_: Exception) {} }, 300)
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                val was = linkUp
                linkUp = false; authed = false; cmdState = 0; cmdChar = null
                pressDone.release()
                if (was || !quiet) log("보드 연결 끊김" + (if (status != 0) " (status $status)" else "") + (if (active) " — 다시 찾음" else ""))
                if (was) quiet = false
                if (was) DriveState.changed()
                if (active) { closeGatt(); main.removeCallbacks(reconnect); main.postDelayed(reconnect, 3_000) }
            }
        }
        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) { if (g === gatt) setup(g) }
        override fun onCharacteristicWrite(g: BluetoothGatt, c: BluetoothGattCharacteristic, status: Int) { writeStatus = status; writeDone.release() }
        override fun onDescriptorWrite(g: BluetoothGatt, d: BluetoothGattDescriptor, status: Int) { writeStatus = status; writeDone.release() }
        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic, value: ByteArray) { if (c.uuid == EVT) handleEvt(value) }
        @Deprecated("API 33 미만")
        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic) {
            if (Build.VERSION.SDK_INT >= 33 || c.uuid != EVT) return
            @Suppress("DEPRECATION") val v = c.value ?: return
            handleEvt(v)
        }
    }

    // ---- 페어링(설정 ⑥ [보드 페어링]) ----

    /**
     * ENH_BTN을 10초 찾아, 폰 서비스가 있는 보드에 페어링을 요청한다(보드 BOOT 3초로 창이 열려 있어야 함).
     * 성공하면 주소·토큰을 저장한다. done(성공, 사람이 읽을 결과)은 작업 스레드에서 부른다.
     */
    @Volatile var pairing = false
        private set

    fun pair(context: Context, logger: (String) -> Unit, done: (Boolean, String) -> Unit) {
        val ctx = context.applicationContext
        synchronized(this) { if (pairing) { done(false, "이미 보드를 찾는 중입니다"); return }; pairing = true }
        log = logger; app = ctx
        Thread {
          try {
            val wasActive = active
            if (wasActive) stop()
            val r = try { pairBlocking(ctx) } catch (e: SecurityException) { false to "블루투스 권한이 없습니다" } catch (e: Exception) { false to "오류: ${e.message}" }
            log("보드 페어링: ${r.second}")
            if (wasActive) start(ctx, log)
            done(r.first, r.second)
          } finally { pairing = false }
        }.start()
    }

    private fun pairBlocking(ctx: Context): Pair<Boolean, String> {
        val adapter = ctx.getSystemService(BluetoothManager::class.java)?.adapter
        if (adapter == null || !adapter.isEnabled) return false to "블루투스가 꺼져 있습니다"
        val scanner = adapter.bluetoothLeScanner ?: return false to "이 폰은 BLE 검색을 못 합니다"
        val found = LinkedHashMap<String, Pair<BluetoothDevice, Int>>()
        val scanCb = object : ScanCallback() {
            override fun onScanResult(type: Int, r: ScanResult) {
                synchronized(found) { found[r.device.address] = r.device to r.rssi }
            }
        }
        log("보드 페어링: ENH_BTN 찾는 중(최대 10초)")
        scanner.startScan(listOf(ScanFilter.Builder().setDeviceName(S3xyButton.NAME).build()),
            ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build(), scanCb)
        // 찾으면 1.5초만 더 보고 끝낸다(늘 10초를 다 쓰면 보드 페어링 창 1분을 넘길 수 있음)
        val t0 = android.os.SystemClock.elapsedRealtime()
        var firstAt = 0L
        while (true) {
            Thread.sleep(250)
            val now = android.os.SystemClock.elapsedRealtime()
            if (firstAt == 0L && synchronized(found) { found.isNotEmpty() }) firstAt = now
            if ((firstAt > 0L && now - firstAt >= 1_500) || now - t0 >= 10_000) break
        }
        try { scanner.stopScan(scanCb) } catch (_: Exception) {}
        val list = synchronized(found) { found.values.sortedByDescending { it.second }.map { it.first } }
        if (list.isEmpty()) return false to "ENH_BTN을 못 찾았습니다 — 보드 전원이 켜져 있고 가까이 있는지 확인"
        var refused = false
        for (dev in list) {
            // 찾은 직후 첫 접속이 1~2초 만에 실패하는 일이 잦다(실차 2026-11-13) — 같은 기기에 3번까지 다시
            for (attempt in 1..3) {
                val r = tryPair(ctx, dev)
                if (r == 1) return true to "보드(${dev.address})와 페어링 됨"
                if (r == 2) { refused = true; break }
                if (r == 3) break // 폰 서비스가 없는 ENH_BTN(다른 버튼)
                if (attempt < 3) { log("보드 페어링: 접속 실패 — 다시 ${attempt + 1}/3"); Thread.sleep(1_000) }
            }
        }
        return false to if (refused) "보드가 거절 — 보드 프로그램이 옛 버전이면 새 프로그램을 넣은 뒤, 보드 BOOT 3초(파란 불 두 번씩 깜빡) → 바로 [찾기]"
            else "찾은 ENH_BTN ${list.size}개 중 페어링할 수 있는 FSD 감속 보드가 없습니다. 보드에 2단계 프로그램이 들어갔는지, BOOT 3초로 창을 열었는지 확인. 보드 페어링을 지운 적이 있으면 폰 블루투스 설정에서 ENH_BTN '기기 삭제' 후 다시"
    }

    /** 1 = 페어링 됨, 2 = 보드가 거절, 3 = 폰 서비스 없음(보드 아님), 0 = 접속·통신 실패(어디서인지 기록). */
    private fun tryPair(ctx: Context, dev: BluetoothDevice): Int {
        val connected = Semaphore(0); val discovered = Semaphore(0); val wrote = Semaphore(0); val reply = Semaphore(0)
        var wStatus = -1
        var answer: ByteArray? = null
        var linkOk = false
        var connStatus = 0
        // 토큰(82 + 16바이트)을 한 번 받으면 뒤에 오는 거절(82)로 덮지 않는다.
        // 승인 뒤 안드로이드가 막혔던 페어링 요청을 스스로 다시 보내고 앱도 다시 보내면, 보드는 첫 요청에 토큰을 주고 둘째는 거절한다(실차 2026-11-13).
        fun keepAnswer(v: ByteArray) { synchronized(this) { if ((answer?.size ?: 0) < 17) { answer = v } } }
        val cb = object : BluetoothGattCallback() {
            override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
                connStatus = status
                if (newState == BluetoothProfile.STATE_CONNECTED) { linkOk = true; connected.release() }
                else if (newState == BluetoothProfile.STATE_DISCONNECTED) { linkOk = false; connected.release(); discovered.release(); wrote.release(); reply.release() }
            }
            override fun onServicesDiscovered(g: BluetoothGatt, status: Int) { discovered.release() }
            override fun onCharacteristicWrite(g: BluetoothGatt, c: BluetoothGattCharacteristic, status: Int) { wStatus = status; wrote.release() }
            override fun onDescriptorWrite(g: BluetoothGatt, d: BluetoothGattDescriptor, status: Int) { wStatus = status; wrote.release() }
            override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic, value: ByteArray) {
                if (value.isNotEmpty() && (value[0].toInt() and 0xFF) == 0x82) { keepAnswer(value); reply.release() }
            }
            @Deprecated("API 33 미만")
            override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic) {
                if (Build.VERSION.SDK_INT >= 33) return
                @Suppress("DEPRECATION") val v = c.value ?: return
                if (v.isNotEmpty() && (v[0].toInt() and 0xFF) == 0x82) { keepAnswer(v); reply.release() }
            }
        }
        val g = dev.connectGatt(ctx, false, cb, BluetoothDevice.TRANSPORT_LE) ?: return 0
        try {
            if (!connected.tryAcquire(10, TimeUnit.SECONDS) || !linkOk) { log("보드 페어링: 접속 안 됨(status $connStatus)"); return 0 }
            Thread.sleep(300)
            g.discoverServices()
            if (!discovered.tryAcquire(10, TimeUnit.SECONDS) || !linkOk) { log("보드 페어링: 서비스 찾기 실패(status $connStatus)"); return 0 }
            val svc = g.getService(SERVICE) ?: run { log("보드 페어링: ${dev.address}에 폰 서비스 없음 — 다른 ENH_BTN이거나 보드 프로그램이 옛 버전"); return 3 }
            val cmd = svc.getCharacteristic(CMD) ?: return 3
            val evt = svc.getCharacteristic(EVT) ?: return 3
            log("보드 페어링: FSD 감속 보드 찾음 ${dev.address}")
            g.setCharacteristicNotification(evt, true)
            evt.getDescriptor(CCCD)?.let { d ->
                val v = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                if (Build.VERSION.SDK_INT >= 33) g.writeDescriptor(d, v) else { @Suppress("DEPRECATION") d.value = v; @Suppress("DEPRECATION") g.writeDescriptor(d) }
                wrote.tryAcquire(3, TimeUnit.SECONDS)
            }
            // 명령 특성은 암호화가 필요하다 — 처음엔 폰에 페어링 요청 창이 뜬다(승인). 승인될 때까지 몇 번 다시 쓴다.
            for (attempt in 1..8) {
                wrote.drainPermits(); wStatus = -1
                val req = byteArrayOf(0x02)
                val ok = if (Build.VERSION.SDK_INT >= 33) g.writeCharacteristic(cmd, req, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT) == 0
                    else { @Suppress("DEPRECATION") cmd.value = req; cmd.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT; @Suppress("DEPRECATION") g.writeCharacteristic(cmd) }
                if (ok && wrote.tryAcquire(5, TimeUnit.SECONDS) && wStatus == 0) break
                if (attempt == 1) log("보드 페어링: 폰에 뜨는 페어링 요청을 승인하세요")
                // 승인이 늦어 쓰기 결과가 늦게 와도 보드는 이미 받았을 수 있다 — 다시 보내기 전에 답을 기다린다(02를 두 번 보내면 두 번째가 거절로 덮음)
                if (reply.tryAcquire(3, TimeUnit.SECONDS)) { reply.release(); break }
                if (attempt == 8) return 0
            }
            if (!reply.tryAcquire(5, TimeUnit.SECONDS)) { log("보드 페어링: 보드 답 없음"); return 0 }
            // 거절이 먼저 보이면 토큰이 뒤따라올 수 있으니 조금 더 기다린다
            if ((answer?.size ?: 0) < 17) reply.tryAcquire(2, TimeUnit.SECONDS)
            val a = answer ?: return 0
            if (a.size < 17) return 2
            Prefs.setBoard(ctx, dev.address, bytesToHex(a.copyOfRange(1, 17)))
            return 1
        } finally {
            try { g.disconnect() } catch (_: Exception) {}
            try { g.close() } catch (_: Exception) {}
        }
    }

    private fun bytesToHex(b: ByteArray) = b.joinToString("") { "%02x".format(it) }
    private fun hexToBytes(s: String): ByteArray? {
        if (s.length % 2 != 0 || s.isEmpty()) return null
        return try { ByteArray(s.length / 2) { s.substring(it * 2, it * 2 + 2).toInt(16).toByte() } } catch (_: Exception) { null }
    }
}
