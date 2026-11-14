package com.jsbeth.camslow

import android.Manifest
import android.annotation.SuppressLint
import android.app.PendingIntent
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build

/**
 * v0.16.0 보드가 켜지면(= 차가 깨면, 보드는 차 USB 전원) 감시 자동 시작.
 * 앱이 꺼져 있어도 되도록 저장된 보드 주소로 BLE 검색을 PendingIntent로 등록한다(시스템이 보드 광고를 보면 BoardScanReceiver를 깨움).
 * 감시 중엔 검색을 풀고, 감시가 꺼지면 다시 등록한다. 폰을 다시 켜거나 앱을 업데이트하면 BoardBootReceiver가 다시 등록한다.
 *
 * 같은 주행에서 운전자가 직접 끈 감시가 곧바로 다시 켜지지 않게: 감시를 켜면 "무장"을 풀고(boardArmed = false),
 * 보드가 GAP_MS 넘게 안 보였다가 다시 보일 때(보드가 꺼졌다 켜짐)나 "보드 끊기고 60초 자동 정지"로 꺼졌을 때만 다시 무장한다.
 * 검색은 모든 광고(ALL_MATCHES, 되면 10초씩 묶어 받음)로 받고 마지막으로 본 시각(boardSeenAt)으로 공백을 잰다.
 */
object BoardScan {
    const val ACTION = "com.jsbeth.camslow.BOARD_SEEN"
    private const val REQ = 41
    /** 보드가 이만큼 넘게 안 보였다가 다시 보이면 새로 켜진 것으로 본다(차가 깸). 배경 검색은 결과가 띄엄띄엄 올 수 있어 넉넉히. */
    const val GAP_MS = 3 * 60_000L

    /** 설정상 쓰는지: 커맨더 사용 + 보드 경유 + 보드 페어링 + 스위치. */
    fun wanted(ctx: Context): Boolean =
        Prefs.boardAutoStart(ctx) && Prefs.bleOn(ctx) && Prefs.viaBoard(ctx) && Prefs.boardAddr(ctx).isNotEmpty()

    fun hasScanPermission(ctx: Context): Boolean =
        Build.VERSION.SDK_INT < 31 || ctx.checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED

    private fun pending(ctx: Context): PendingIntent {
        val i = Intent(ctx, BoardScanReceiver::class.java).setAction(ACTION)
        // 시스템이 검색 결과를 넣어야 하므로 Android 12+에선 MUTABLE(명시적 대상이라 안전)
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0)
        return PendingIntent.getBroadcast(ctx, REQ, i, flags)
    }

    /**
     * 감시가 꺼져 있고 설정이 맞으면 검색을 (다시) 등록하고, 아니면 푼다. why가 있으면 기록에 남긴다.
     */
    @SuppressLint("MissingPermission")
    fun update(context: Context, why: String? = null) {
        val ctx = context.applicationContext
        val want = wanted(ctx) && !DriveState.running
        val adapter = runCatching { ctx.getSystemService(BluetoothManager::class.java)?.adapter }.getOrNull()
        val scanner = runCatching { adapter?.takeIf { it.isEnabled }?.bluetoothLeScanner }.getOrNull()
        if (adapter == null || scanner == null) { if (want && why != null) DriveState.log("보드 자동 시작: 블루투스가 꺼져 있어 검색 등록 못 함"); return }
        if (!hasScanPermission(ctx)) { if (want && why != null) DriveState.log("보드 자동 시작: 블루투스 검색 권한이 없음 — 앱을 열어 권한 허용"); return }
        val pi = pending(ctx)
        try { scanner.stopScan(pi) } catch (_: Exception) {}
        Prefs.setBoardScanRegAt(ctx, 0L)
        if (!want) { if (why != null && wanted(ctx)) DriveState.log("보드 자동 시작 검색 쉼($why)"); return }
        val addr = Prefs.boardAddr(ctx).uppercase()
        if (!BluetoothAdapter.checkBluetoothAddress(addr)) return
        val filter = ScanFilter.Builder().setDeviceAddress(addr).build()
        val b = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_POWER)
            .setCallbackType(ScanSettings.CALLBACK_TYPE_ALL_MATCHES)
        if (runCatching { adapter.isOffloadedScanBatchingSupported }.getOrDefault(false)) b.setReportDelay(10_000)
        val r = try { scanner.startScan(listOf(filter), b.build(), pi) } catch (e: Exception) { DriveState.log("보드 자동 시작 검색 등록 오류: ${e.javaClass.simpleName}"); return }
        if (r == 0) Prefs.setBoardScanRegAt(ctx, System.currentTimeMillis())
        if (why != null || r != 0) DriveState.log(if (r == 0) "보드 자동 시작 검색 등록(${why ?: "다시"})" else "보드 자동 시작 검색 등록 실패(코드 $r)")
    }

    /** v0.16.1 블루투스 기기 연결·끊김 때: 5분 안에 등록했으면 그대로 둔다(검색 시작·중지를 30초에 5번 넘게 하면 안드로이드가 막음). */
    fun refresh(context: Context) {
        val now = System.currentTimeMillis()
        val at = Prefs.boardScanRegAt(context.applicationContext)
        if (at != 0L && now >= at && now - at < 5 * 60_000L) return
        update(context)
    }

    /** 감시 중 보드가 연결돼 있을 때·검색에 보일 때: 마지막으로 본 시각(쓰기를 줄이려고 10초에 한 번). */
    fun noteSeen(ctx: Context, minGapMs: Long = 10_000) {
        val now = System.currentTimeMillis()
        val last = Prefs.boardSeenAt(ctx)
        if (now - last >= minGapMs || now < last) Prefs.setBoardSeenAt(ctx, now)
    }

    /** 검색에 보드가 보임. 무장돼 있거나 한동안(GAP_MS) 안 보였던 뒤면 감시를 시작한다. */
    fun seen(ctx: Context) {
        val now = System.currentTimeMillis()
        val last = Prefs.boardSeenAt(ctx)
        noteSeen(ctx)
        if (DriveState.running || BoardLink.pairing) return
        if (!wanted(ctx)) return
        val fresh = last == 0L || now - last > GAP_MS || now < last
        if (!Prefs.boardArmed(ctx) && !fresh) return
        Prefs.setBoardArmed(ctx, false) // 이번 켜짐에선 한 번만(권한 부족 알림이 되풀이되지 않게)
        DriveState.log("보드가 켜짐(차 깸) — 자동 시작")
        AutoStart.startDrive(ctx, "board")
    }
}

/** 보드 검색 결과(PendingIntent). 시스템만 보낸다(exported=false). */
class BoardScanReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        if (intent.action != BoardScan.ACTION) return
        val err = intent.getIntExtra(BluetoothLeScanner.EXTRA_ERROR_CODE, 0)
        if (err != 0) { DriveState.log("보드 자동 시작 검색 오류 $err"); return }
        @Suppress("DEPRECATION")
        val results: List<ScanResult> = (if (Build.VERSION.SDK_INT >= 33)
            intent.getParcelableArrayListExtra(BluetoothLeScanner.EXTRA_LIST_SCAN_RESULT, ScanResult::class.java)
        else intent.getParcelableArrayListExtra<ScanResult>(BluetoothLeScanner.EXTRA_LIST_SCAN_RESULT)) ?: return
        val addr = Prefs.boardAddr(ctx)
        if (addr.isEmpty() || results.none { it.device?.address.equals(addr, ignoreCase = true) }) return
        BoardScan.seen(ctx)
    }
}

/** 폰을 다시 켜거나 앱을 업데이트하면 보드 검색을 다시 등록한다(PendingIntent 검색은 재부팅·블루투스 재시작에 남지 않음). */
class BoardBootReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED -> BoardScan.update(ctx)
        }
    }
}
