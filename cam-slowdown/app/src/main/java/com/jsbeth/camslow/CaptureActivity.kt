package com.jsbeth.camslow

import android.annotation.SuppressLint
import android.app.Activity
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import android.widget.TextView

/**
 * S3XY 버튼이 누를 때 내보내는 BLE 광고를 기록한다.
 * 버튼을 폰 바로 옆에 두고 [클릭 표시] 누른 뒤 실제 버튼 클릭, [더블 표시] 누른 뒤 더블클릭…을 몇 번 반복.
 * 결과를 [공유]로 보내 주면 BLE 프로필을 만들 수 있다.
 */
class CaptureActivity : Activity() {
    private val lines = ArrayList<String>()
    private lateinit var view: TextView
    private lateinit var rssiMin: android.widget.EditText
    private var scanning = false
    private val t0 = SystemClock.elapsedRealtime()
    private val lastPayload = HashMap<String, String>()

    private val cb = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, r: ScanResult) = record(r)
        override fun onBatchScanResults(results: MutableList<ScanResult>) = results.forEach { record(it) }
        override fun onScanFailed(errorCode: Int) = add("스캔 실패 code=$errorCode")
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val ui = Ui(this)
        ui.text("S3XY 버튼 신호 캡처", 20f, bold = true)
        ui.text("버튼을 폰에 붙여 두고 스캔 시작 → [클릭 표시] 누르고 버튼 1번 클릭 → [더블 표시] 누르고 더블클릭. " +
            "각각 3~5번 반복 후 [공유]로 보내 주세요. 신호가 너무 많으면 RSSI 기준을 -45 정도로 올리세요.", 13f)
        rssiMin = ui.number("RSSI 기준 (이보다 약한 신호 무시)", "-55", decimal = false)
        ui.row(
            ui.button("스캔 시작") { start() },
            ui.button("정지") { stop() },
        )
        ui.row(
            ui.button("클릭 표시") { add("===== 지금 클릭 =====") },
            ui.button("더블 표시") { add("===== 지금 더블클릭 =====") },
            ui.button("길게 표시") { add("===== 지금 길게 누름 =====") },
        )
        ui.row(
            ui.button("공유") { share() },
            ui.button("지우기") { lines.clear(); lastPayload.clear(); view.text = "" },
        )
        view = ui.text("", 11f)
        view.typeface = android.graphics.Typeface.MONOSPACE
        ui.show()
    }

    private fun scanner() = (getSystemService(BLUETOOTH_SERVICE) as BluetoothManager).adapter?.bluetoothLeScanner

    @SuppressLint("MissingPermission")
    private fun start() {
        if (scanning) return
        val s = scanner() ?: run { add("블루투스를 켜 주세요"); return }
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .setLegacy(false)
            .build()
        try { s.startScan(null, settings, cb); scanning = true; add("스캔 시작") }
        catch (e: SecurityException) { add("블루투스 스캔 권한 없음") }
    }

    @SuppressLint("MissingPermission")
    private fun stop() {
        if (!scanning) return
        try { scanner()?.stopScan(cb) } catch (_: SecurityException) {}
        scanning = false
        add("스캔 정지")
    }

    @SuppressLint("MissingPermission")
    private fun record(r: ScanResult) {
        val min = rssiMin.text.toString().toIntOrNull() ?: -55
        if (r.rssi < min) return
        val rec = r.scanRecord ?: return
        val raw = rec.bytes?.let { trimZeros(it) } ?: return
        val addr = r.device.address
        val payload = hex(raw)
        // 같은 기기가 같은 내용을 반복 광고하면 한 번만 적는다(바뀔 때가 핵심)
        if (lastPayload[addr] == payload) return
        lastPayload[addr] = payload
        val mfr = buildString {
            val m = rec.manufacturerSpecificData
            for (i in 0 until m.size()) append(" mfr[${m.keyAt(i)}]=${hex(m.valueAt(i))}")
        }
        val name = try { r.device.name ?: rec.deviceName ?: "" } catch (_: SecurityException) { "" }
        val svc = rec.serviceUuids?.joinToString(",") ?: ""
        add("${addr} rssi=${r.rssi} name=$name conn=${r.isConnectable} svc=$svc$mfr\n    raw=$payload")
    }

    private fun trimZeros(b: ByteArray): ByteArray {
        var n = b.size
        while (n > 0 && b[n - 1] == 0.toByte()) n--
        return b.copyOf(n)
    }

    private fun hex(b: ByteArray) = b.joinToString("") { "%02X".format(it) }

    private fun add(s: String) {
        val t = (SystemClock.elapsedRealtime() - t0) / 1000.0
        lines += "%8.2f %s".format(t, s)
        if (lines.size > 3000) lines.subList(0, 500).clear()
        view.text = lines.takeLast(60).reversed().joinToString("\n")
    }

    private fun share() {
        val text = lines.joinToString("\n")
        startActivity(Intent.createChooser(
            Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text), "캡처 공유",
        ))
    }

    override fun onDestroy() { stop(); super.onDestroy() }
}
