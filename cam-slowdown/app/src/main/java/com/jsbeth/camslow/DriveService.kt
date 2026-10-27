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
import android.os.SystemClock
import android.speech.tts.TextToSpeech
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 화면과 서비스가 공유하는 상태. 모든 접근은 메인 스레드에서. */
object DriveState {
    var running = false
    var simulating = false
    var status: EngineStatus? = null
    /** 지금 쓰는 엔진 설정(화면에 감속 시작 거리를 보여 주기 위해). */
    var config: EngineConfig? = null
    var outputs: OutputSettings? = null
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

/**
 * 출력 설정 스냅샷. 주행 시작 때 떠서 끝날 때까지 쓴다.
 * (주행 중 설정을 바꾸면 Down은 옛 값·Up은 새 값으로 나가는 식의 반쪽 적용을 막는다.)
 */
data class OutputSettings(
    val alerts: Boolean,
    val ble: Boolean,
    val usual: Profile,
    val slow: Profile,
    val downPresses: Int,
    val upPresses: Int,
    val autoRestore: Boolean,
    val profileJson: String,
) {
    companion object {
        fun load(ctx: Context) = OutputSettings(
            alerts = Prefs.alertsOn(ctx), ble = Prefs.bleOn(ctx),
            usual = Prefs.usualProfile(ctx), slow = Prefs.slowProfile(ctx),
            downPresses = Prefs.downPresses(ctx), upPresses = Prefs.upPresses(ctx),
            autoRestore = Prefs.autoRestore(ctx), profileJson = Prefs.bleProfile(ctx),
        )
    }

    val profile: BleProfile? get() = if (!ble || profileJson.isBlank()) null else runCatching { BleProfile.parse(profileJson) }.getOrNull()

    /** BLE로 감속 클릭을 다 보내는 데 걸리는 시간(초). BLE가 꺼져 있거나 프로필이 없으면 0. */
    fun bleDownSeconds(): Double = profile?.pressesSeconds(BleButtonEmulator.Press.CLICK, downPresses) ?: 0.0

    fun describe(): String = buildString {
        append("${usual.label} → ${slow.label}: 클릭 ${downPresses}회")
        append(if (autoRestore) " / 복귀 더블클릭 ${upPresses}회" else " / 자동 복귀 안 함")
        append(if (ble) " (BLE 켬)" else " (BLE 꺼짐 — 음성만)")
    }
}

/** 감속·복귀 신호 출력: 음성 안내 + (설정 시) 가상 S3XY 버튼. */
class Outputs(private val ctx: Context) {
    private var tts: TextToSpeech? = null
    private val tone = ToneGenerator(AudioManager.STREAM_NOTIFICATION, 90)
    val ble = BleButtonEmulator(ctx)
    @Volatile var settings: OutputSettings = OutputSettings.load(ctx)
        private set

    init {
        tts = TextToSpeech(ctx) { st -> if (st == TextToSpeech.SUCCESS) tts?.language = Locale.KOREAN }
    }

    /** 설정을 다시 읽는다. 서비스는 주행 시작 때만 부른다. */
    fun refresh(): OutputSettings { settings = OutputSettings.load(ctx); return settings }

    fun say(text: String) {
        if (settings.alerts) tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "say")
    }

    fun down(cam: Camera, distM: Double) {
        val s = settings
        DriveState.log("▼ 카메라 ${distM.toInt()}m 앞, 제한 ${cam.limitKmh}. 감속 → ${s.slow.label} (${cam.name})")
        if (s.alerts) {
            tone.startTone(ToneGenerator.TONE_PROP_BEEP2, 300)
            tts?.speak("과속 카메라, 제한속도 ${cam.limitKmh}. 감속합니다", TextToSpeech.QUEUE_FLUSH, null, "down")
        }
        if (s.ble) ble.press(BleButtonEmulator.Press.CLICK, s.downPresses, s.profileJson) { DriveState.log(it) }
    }

    /** force: 테스트 버튼처럼 자동 복귀 설정과 무관하게 복귀 신호를 보낼 때. */
    fun up(reason: String, force: Boolean = false) {
        val s = settings
        if (!s.autoRestore && !force) {
            DriveState.log("△ 카메라 통과 ($reason) — 자동 복귀 꺼짐, 휠로 직접 올리세요")
            if (s.alerts) {
                tone.startTone(ToneGenerator.TONE_PROP_ACK, 200)
                tts?.speak("카메라 통과. 직접 복귀하세요", TextToSpeech.QUEUE_FLUSH, null, "up")
            }
            return
        }
        DriveState.log("▲ 복귀 → ${s.usual.label} ($reason)")
        if (s.alerts) {
            tone.startTone(ToneGenerator.TONE_PROP_ACK, 200)
            tts?.speak("복귀합니다", TextToSpeech.QUEUE_FLUSH, null, "up")
        }
        if (s.ble) ble.press(BleButtonEmulator.Press.DOUBLE, s.upPresses, s.profileJson) { DriveState.log(it) }
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
        /** GPS fix가 이 시간(ms) 넘게 없으면 경고. */
        private const val GPS_STALE_MS = 15_000L

        fun send(ctx: Context, action: String) {
            val i = Intent(ctx, DriveService::class.java).setAction(action)
            if (action == ACTION_STOP) ctx.startService(i) else ctx.startForegroundService(i)
        }
    }

    /** 카메라 데이터를 읽는 동안은 null. 그동안 들어온 fix는 버린다. */
    private var engine: SlowdownEngine? = null
    private lateinit var outputs: Outputs
    private val main = Handler(Looper.getMainLooper())
    private var simRunnable: Runnable? = null
    private var loadSeq = 0
    private var lastFixAt = 0L
    private var gpsWarned = false
    private val watchdog = object : Runnable {
        override fun run() { checkGps(); main.postDelayed(this, 5_000) }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        outputs = Outputs(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> { stopAll(); stopSelf(); return START_NOT_STICKY }
            ACTION_SIMULATE -> { if (!goForeground()) return START_NOT_STICKY; startSimulation() }
            else -> {
                if (intent == null) DriveState.log("시스템이 서비스를 다시 시작함 — 감속 상태는 잊었습니다. 휠을 확인하세요")
                if (!goForeground()) return START_NOT_STICKY
                startGps()
            }
        }
        return START_STICKY
    }

    private fun goForeground(): Boolean {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "주행 감시", NotificationManager.IMPORTANCE_LOW))
        return try {
            startForeground(NOTI_ID, notification("카메라 감시 준비"))
            true
        } catch (e: Exception) {
            // Android 14: 위치 권한 없이 location 타입 포그라운드 서비스를 띄우면 SecurityException
            DriveState.log("감시 시작 실패: ${e.javaClass.simpleName} ${e.message ?: ""} — 위치 권한을 확인하세요")
            stopSelf()
            false
        }
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

    private fun notify(text: String) =
        getSystemService(NotificationManager::class.java).notify(NOTI_ID, notification(text))

    /**
     * 설정 스냅샷을 뜨고 카메라 데이터를 백그라운드에서 읽는다(처음엔 수 초). 준비되면 onReady.
     * 감속 상태에서 다시 시작하면 복귀 신호가 영영 안 나가므로 운전자에게 알린다.
     */
    private fun loadEngine(onReady: (SlowdownEngine) -> Unit) {
        val seq = ++loadSeq
        if (engine?.isSlowed == true) {
            DriveState.log("주의: 감속 상태에서 다시 시작 — 복귀 신호는 나가지 않습니다. 휠을 확인하세요")
            outputs.say("감속 상태였습니다. 휠을 확인하세요")
        }
        engine = null
        val out = outputs.refresh()
        val cfg = Prefs.engine(this).let { c -> c.copy(bleLatencyS = out.bleDownSeconds()) }
        DriveState.config = cfg
        DriveState.outputs = out
        Thread {
            val idx = CameraRepo.load(this)
            main.post {
                if (seq != loadSeq) return@post
                val e = SlowdownEngine(idx, cfg)
                engine = e
                onReady(e)
            }
        }.start()
    }

    @SuppressLint("MissingPermission")
    private fun startGps() {
        stopSimulation()
        val lm = getSystemService(LocationManager::class.java)
        lm.removeUpdates(this)
        main.removeCallbacks(watchdog)
        DriveState.running = true
        DriveState.status = null
        DriveState.log("카메라 데이터 읽는 중…")
        DriveState.changed()
        loadEngine { e ->
            try {
                lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000L, 0f, this, Looper.getMainLooper())
            } catch (ex: SecurityException) {
                DriveState.log("위치 권한 없음"); stopAll(); stopSelf(); return@loadEngine
            }
            lastFixAt = SystemClock.elapsedRealtime()
            gpsWarned = false
            main.postDelayed(watchdog, 5_000)
            val s = outputs.settings
            DriveState.log("주행 감시 시작 — 카메라 ${e.cameraCount}개, ${s.describe()}")
            if (e.cameraCount == 0) { DriveState.log("카메라 데이터가 없습니다 — CSV를 불러오세요"); outputs.say("카메라 데이터가 없습니다") }
            if (s.ble && s.profile == null) DriveState.log("BLE가 켜져 있지만 프로필이 없거나 잘못됨 — 신호가 나가지 않습니다")
            if (s.ble && s.profile?.payloadsDiffer == false) DriveState.log("주의: 클릭과 더블클릭 페이로드가 같음 — gapMs를 길게 두지 않으면 클릭이 더블클릭으로 읽힐 수 있음")
            notify("GPS 대기")
        }
    }

    override fun onLocationChanged(loc: Location) {
        lastFixAt = SystemClock.elapsedRealtime()
        if (gpsWarned) { gpsWarned = false; DriveState.log("GPS 복구") }
        val speed = if (loc.hasSpeed()) loc.speed.toDouble() else 0.0
        val course = if (loc.hasBearing() && speed >= 3.0) loc.bearing.toDouble() else null
        val acc = if (loc.hasAccuracy()) loc.accuracy.toDouble() else null
        handle(Fix(loc.latitude, loc.longitude, speed, course, acc))
    }

    override fun onProviderEnabled(provider: String) {}
    override fun onProviderDisabled(provider: String) { DriveState.log("GPS 꺼짐"); outputs.say("GPS가 꺼졌습니다") }
    @Deprecated("Deprecated in Java")
    override fun onStatusChanged(provider: String?, status: Int, extras: android.os.Bundle?) {}

    private fun checkGps() {
        if (!DriveState.running || DriveState.simulating) return
        val silent = SystemClock.elapsedRealtime() - lastFixAt
        if (silent >= GPS_STALE_MS && !gpsWarned) {
            gpsWarned = true
            DriveState.log("GPS 신호 없음 ${silent / 1000}초 — 카메라를 감지하지 못합니다")
            outputs.say("GPS 신호 없음")
            notify("GPS 신호 없음")
        }
    }

    private fun handle(fix: Fix) {
        val e = engine ?: return
        for (ev in e.update(fix)) when (ev) {
            is EngineEvent.Down -> outputs.down(ev.camera, ev.distM)
            is EngineEvent.Up -> outputs.up(ev.reason)
        }
        val st = e.status
        DriveState.status = st
        DriveState.changed()
        val text = buildString {
            append(if (st.slowed) "감속 중" else "대기")
            st.nextDistM?.let { append(" · 다음 카메라 ${it.toInt()}m (${st.nextCamera?.limitKmh})") }
        }
        notify(text)
    }

    /**
     * 가상 주행: 가장 가까운 일반 카메라를 향해 2km 남쪽에서 시속 100km로 북진.
     * 주차 상태에서 음성·BLE 신호가 제대로 나가는지 확인하는 용도. 끝나면 감시도 끝난다(실제 주행은 다시 시작할 것).
     */
    @SuppressLint("MissingPermission")
    private fun startSimulation() {
        val lm = getSystemService(LocationManager::class.java)
        lm.removeUpdates(this)
        main.removeCallbacks(watchdog)
        stopSimulation()
        DriveState.simulating = true
        DriveState.running = true
        DriveState.status = null
        loadEngine { e ->
            val spots = e.cameras.filter { it.kind == CamKind.SPOT }
            if (spots.isEmpty()) { DriveState.log("카메라 데이터가 없습니다"); finishSimulation(); return@loadEngine }
            val here = try { lm.getLastKnownLocation(LocationManager.GPS_PROVIDER) } catch (ex: SecurityException) { null }
            val cam = here?.let { h -> spots.minByOrNull { Geo.distance(h.latitude, h.longitude, it.lat, it.lon) } }
                ?: spots.first()
            val speed = 100 / 3.6
            val mPerDegLat = 111_195.0
            val mPerDegLon = mPerDegLat * Math.cos(Math.toRadians(cam.lat))
            var north = -2000.0
            DriveState.log("가상 주행 시작 → ${cam.name} (제한 ${cam.limitKmh}), ${outputs.settings.describe()}")
            val r = object : Runnable {
                override fun run() {
                    // 카메라를 진행 방향 오른쪽 8m에 두도록 서쪽으로 비켜 달린다
                    handle(Fix(cam.lat + north / mPerDegLat, cam.lon - 8.0 / mPerDegLon, speed, 0.0, 5.0))
                    north += speed
                    if (north < 1000 && DriveState.simulating) main.postDelayed(this, 1000) else finishSimulation()
                }
            }
            simRunnable = r
            main.post(r)
        }
    }

    private fun finishSimulation() {
        if (!DriveState.simulating) return
        stopSimulation()
        if (engine?.isSlowed == true) DriveState.log("가상 주행이 감속 상태로 끝남 — 휠로 직접 복귀하세요")
        engine?.reset()
        DriveState.running = false
        DriveState.status = null
        DriveState.log("가상 주행 끝 — 실제 주행은 [주행 시작]을 누르세요")
        outputs.say("가상 주행 끝. 실제 주행은 주행 시작을 누르세요")
        DriveState.changed()
        notify("가상 주행 끝 — 감시 꺼짐")
        // 음성이 끝날 시간을 준 뒤 종료. 그 사이 [주행 시작]을 눌렀으면 그대로 둔다.
        main.postDelayed({ if (!DriveState.running) { stopForeground(STOP_FOREGROUND_REMOVE); stopSelf() } }, 4_000)
    }

    private fun stopSimulation() {
        simRunnable?.let { main.removeCallbacks(it) }
        simRunnable = null
        DriveState.simulating = false
    }

    private fun stopAll() {
        main.removeCallbacks(watchdog)
        stopSimulation()
        getSystemService(LocationManager::class.java).removeUpdates(this)
        loadSeq++ // 읽는 중이던 엔진은 버린다
        if (!DriveState.running) return
        if (engine?.isSlowed == true) {
            DriveState.log("정지 — 감속 상태에서 멈췄습니다. 필요하면 휠로 직접 복귀하세요")
            outputs.say("감속 상태에서 정지했습니다. 휠을 확인하세요")
        }
        engine?.reset()
        DriveState.running = false
        DriveState.status = null
        DriveState.log("주행 감시 정지")
        DriveState.changed()
    }

    override fun onDestroy() {
        stopAll()
        outputs.shutdown()
        super.onDestroy()
    }
}
