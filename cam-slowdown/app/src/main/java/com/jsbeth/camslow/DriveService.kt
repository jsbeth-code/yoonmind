package com.jsbeth.camslow

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.speech.tts.TextToSpeech
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 화면과 서비스가 공유하는 상태. 모든 접근은 메인 스레드에서. */
object DriveState {
    var running = false
    var simulating = false
    var status: EngineStatus? = null
    val log = ArrayDeque<String>()
    private val listeners = mutableSetOf<() -> Unit>()
    private val main = Handler(Looper.getMainLooper())
    private val fmt = SimpleDateFormat("HH:mm:ss", Locale.KOREA)

    fun addListener(l: () -> Unit) { listeners += l }
    fun removeListener(l: () -> Unit) { listeners -= l }
    fun changed() = main.post { listeners.toList().forEach { it() } }

    fun log(msg: String) = main.post {
        log.addFirst("${fmt.format(Date())} $msg")
        while (log.size > 80) log.removeLast()
        listeners.toList().forEach { it() }
    }
}

/** 감속·복귀 신호 출력: 음성 안내 + (설정 시) 가상 S3XY 버튼. */
class Outputs(private val ctx: Context) {
    private var tts: TextToSpeech? = null
    private val tone = ToneGenerator(AudioManager.STREAM_NOTIFICATION, 90)
    val ble = BleButtonEmulator(ctx)

    init {
        tts = TextToSpeech(ctx) { st -> if (st == TextToSpeech.SUCCESS) tts?.language = Locale.KOREAN }
    }

    fun down(cam: Camera, distM: Double) {
        val msg = "카메라 ${distM.toInt()}m 앞, 제한 ${cam.limitKmh}. 감속"
        DriveState.log("▼ $msg (${cam.name})")
        if (Prefs.alertsOn(ctx)) {
            tone.startTone(ToneGenerator.TONE_PROP_BEEP2, 300)
            tts?.speak("과속 카메라, 제한속도 ${cam.limitKmh}. 감속합니다", TextToSpeech.QUEUE_FLUSH, null, "down")
        }
        if (Prefs.bleOn(ctx)) ble.press(BleButtonEmulator.Press.CLICK, Prefs.downPresses(ctx)) { DriveState.log(it) }
    }

    fun up(reason: String) {
        DriveState.log("▲ 복귀 ($reason)")
        if (Prefs.alertsOn(ctx)) {
            tone.startTone(ToneGenerator.TONE_PROP_ACK, 200)
            tts?.speak("복귀합니다", TextToSpeech.QUEUE_FLUSH, null, "up")
        }
        if (Prefs.bleOn(ctx)) ble.press(BleButtonEmulator.Press.DOUBLE, Prefs.upPresses(ctx)) { DriveState.log(it) }
    }

    fun shutdown() { tts?.shutdown(); tone.release() }
}

class DriveService : Service(), LocationListener {

    companion object {
        const val ACTION_START = "start"
        const val ACTION_SIMULATE = "simulate"
        const val ACTION_STOP = "stop"
        private const val CHANNEL = "drive"
        private const val NOTI_ID = 1

        fun send(ctx: Context, action: String) {
            val i = Intent(ctx, DriveService::class.java).setAction(action)
            if (action == ACTION_STOP) ctx.startService(i) else ctx.startForegroundService(i)
        }
    }

    private lateinit var engine: SlowdownEngine
    private lateinit var outputs: Outputs
    private val main = Handler(Looper.getMainLooper())
    private var simRunnable: Runnable? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        outputs = Outputs(this)
        engine = SlowdownEngine(CameraRepo.load(this), Prefs.engine(this))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> { stopAll(); stopSelf(); return START_NOT_STICKY }
            ACTION_SIMULATE -> { goForeground(); startSimulation() }
            else -> { goForeground(); startGps() }
        }
        return START_STICKY
    }

    private fun goForeground() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "주행 감시", NotificationManager.IMPORTANCE_LOW))
        startForeground(NOTI_ID, notification("카메라 감시 중"))
    }

    private fun notification(text: String): Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE,
        )
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_menu_compass)
            .setContentTitle("카메라 감속")
            .setContentText(text)
            .setContentIntent(open)
            .setOngoing(true)
            .build()
    }

    private fun refreshEngine() {
        engine = SlowdownEngine(CameraRepo.load(this), Prefs.engine(this))
    }

    @SuppressLint("MissingPermission")
    private fun startGps() {
        stopSimulation()
        refreshEngine()
        val lm = getSystemService(LocationManager::class.java)
        try {
            lm.removeUpdates(this)
            lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000L, 0f, this, Looper.getMainLooper())
        } catch (e: SecurityException) {
            DriveState.log("위치 권한 없음"); return
        }
        DriveState.running = true
        DriveState.log("주행 감시 시작 (카메라 ${CameraRepo.count(this)}개)")
    }

    override fun onLocationChanged(loc: Location) {
        val speed = if (loc.hasSpeed()) loc.speed.toDouble() else 0.0
        val course = if (loc.hasBearing() && speed >= 3.0) loc.bearing.toDouble() else null
        handle(Fix(loc.latitude, loc.longitude, speed, course))
    }

    override fun onProviderEnabled(provider: String) {}
    override fun onProviderDisabled(provider: String) { DriveState.log("GPS 꺼짐") }
    @Deprecated("Deprecated in Java")
    override fun onStatusChanged(provider: String?, status: Int, extras: android.os.Bundle?) {}

    private fun handle(fix: Fix) {
        for (e in engine.update(fix)) when (e) {
            is EngineEvent.Down -> outputs.down(e.camera, e.distM)
            is EngineEvent.Up -> outputs.up(e.reason)
        }
        val st = engine.status
        DriveState.status = st
        DriveState.changed()
        val text = buildString {
            append(if (st.slowed) "감속 중" else "대기")
            st.nextDistM?.let { append(" · 다음 카메라 ${it.toInt()}m (${st.nextCamera?.limitKmh})") }
        }
        getSystemService(NotificationManager::class.java).notify(NOTI_ID, notification(text))
    }

    /**
     * 가상 주행: 가장 가까운 카메라를 향해 2km 남쪽에서 시속 100km로 북진.
     * 주차 상태에서 음성·BLE 신호가 제대로 나가는지 확인하는 용도.
     */
    @SuppressLint("MissingPermission")
    private fun startSimulation() {
        val lm = getSystemService(LocationManager::class.java)
        lm.removeUpdates(this)
        stopSimulation()
        refreshEngine()
        val idx = CameraRepo.load(this)
        if (idx.cameras.isEmpty()) { DriveState.log("카메라 데이터가 없습니다"); return }
        val here = try { lm.getLastKnownLocation(LocationManager.GPS_PROVIDER) } catch (e: SecurityException) { null }
        val cam = here?.let { h -> idx.cameras.minByOrNull { Geo.distance(h.latitude, h.longitude, it.lat, it.lon) } }
            ?: idx.cameras.first()
        val speed = 100 / 3.6
        val mPerDegLat = 111_195.0
        val mPerDegLon = mPerDegLat * Math.cos(Math.toRadians(cam.lat))
        var north = -2000.0
        DriveState.simulating = true
        DriveState.running = true
        DriveState.log("가상 주행 시작 → ${cam.name} (제한 ${cam.limitKmh})")
        val r = object : Runnable {
            override fun run() {
                // 카메라를 진행 방향 오른쪽 8m에 두도록 서쪽으로 비켜 달린다
                handle(Fix(cam.lat + north / mPerDegLat, cam.lon - 8.0 / mPerDegLon, speed, 0.0))
                north += speed
                if (north < 1000) main.postDelayed(this, 1000) else {
                    DriveState.log("가상 주행 끝"); DriveState.simulating = false
                }
            }
        }
        simRunnable = r
        main.post(r)
    }

    private fun stopSimulation() {
        simRunnable?.let { main.removeCallbacks(it) }
        simRunnable = null
        DriveState.simulating = false
    }

    private fun stopAll() {
        if (!DriveState.running) return
        stopSimulation()
        getSystemService(LocationManager::class.java).removeUpdates(this)
        if (engine.isSlowed) DriveState.log("정지 — 감속 상태에서 멈췄습니다. 필요하면 휠로 직접 복귀하세요")
        engine.reset()
        DriveState.running = false
        DriveState.status = null
        DriveState.log("주행 감시 정지")
    }

    override fun onDestroy() {
        stopAll()
        outputs.shutdown()
        super.onDestroy()
    }
}
