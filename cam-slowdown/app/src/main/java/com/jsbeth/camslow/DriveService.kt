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
    /** 마지막 실제 GPS fix 시각(elapsedRealtime). 0이면 아직 없음. */
    @Volatile var lastFixAt = 0L
    @Volatile var lastAccM: Double? = null
    /** 추측 항법으로 가고 있는 중. */
    @Volatile var drActive = false
    @Volatile var zoneCount = 0
    /** 화면 지도용 데이터. 매 fix마다 갱신. */
    var map: MapData? = null
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
    val pressGapMs: Long,
    val renameAdapter: Boolean,
) {
    companion object {
        fun load(ctx: Context) = OutputSettings(
            alerts = Prefs.alertsOn(ctx), ble = Prefs.bleOn(ctx),
            usual = Prefs.usualProfile(ctx), slow = Prefs.slowProfile(ctx),
            downPresses = Prefs.downPresses(ctx), upPresses = Prefs.upPresses(ctx),
            autoRestore = Prefs.autoRestore(ctx), pressGapMs = Prefs.pressGapMs(ctx), renameAdapter = Prefs.renameAdapter(ctx),
        )
    }

    /** BLE로 감속 클릭을 다 보내는 데 걸리는 시간(초). BLE가 꺼져 있으면 0. */
    fun bleDownSeconds(): Double = if (ble) S3xyButton.pressSeconds(downPresses, pressGapMs) else 0.0

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
        val zone = cam.code == ZoneCsv.CODE
        DriveState.log(if (zone) "▼ 어린이보호구역 진입. 감속 → ${s.slow.label} (${cam.name})"
            else "▼ 카메라 ${distM.toInt()}m 앞, 제한 ${cam.limitKmh}. 감속 → ${s.slow.label} (${cam.name})")
        if (s.alerts) {
            tone.startTone(ToneGenerator.TONE_PROP_BEEP2, 300)
            tts?.speak(if (zone) "어린이보호구역. 감속합니다" else "과속 카메라, 제한속도 ${cam.limitKmh}. 감속합니다", TextToSpeech.QUEUE_FLUSH, null, "down")
        }
        if (s.ble) S3xyButton.press(S3xyButton.Press.SINGLE, s.downPresses, s.pressGapMs)
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
        if (s.ble) S3xyButton.press(S3xyButton.Press.DOUBLE, s.upPresses, s.pressGapMs)
    }

    fun shutdown() { tts?.shutdown(); tone.release() }
}

class DriveService : Service(), LocationListener {

    companion object {
        const val ACTION_START = "start"
        /** CSV를 새로 불러온 뒤 등, 감시 중이어도 엔진을 다시 만든다. */
        const val ACTION_RELOAD = "reload"
        const val ACTION_SIMULATE = "simulate"
        const val ACTION_STOP = "stop"
        /** 차 블루투스가 끊김 → 잠시 뒤 자동 정지 예약. 다시 붙으면 취소. */
        const val ACTION_CAR_DISCONNECTED = "car_disconnected"
        const val ACTION_CAR_CONNECTED = "car_connected"
        /** 블루투스 끊김 뒤 자동 정지까지 기다리는 시간(ms). 잠깐 끊겼다 붙는 경우를 거른다. */
        private const val AUTO_STOP_DELAY_MS = 60_000L
        private const val CHANNEL = "drive"
        private const val NOTI_ID = 1
        /** GPS fix가 이 시간(ms) 넘게 없으면 경고. */
        private const val GPS_STALE_MS = 15_000L
        /** 화면 지도에 담을 반경(m). 지도는 앞 500 m를 보여 준다. */
        private const val MAP_RADIUS_M = 700.0

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
    // 추측 항법: 마지막 실제 fix
    private var lastFix: Fix? = null
    private var roads: RoadNet? = null
    private var drLat = 0.0
    private var drLon = 0.0
    private var drDistM = 0.0
    private var drOn = false
    private var drMaxM = 2000.0
    private val drRunnable = object : Runnable {
        override fun run() { deadReckonStep(); main.postDelayed(this, 1_000) }
    }
    private val watchdog = object : Runnable {
        override fun run() { checkGps(); main.postDelayed(this, 5_000) }
    }
    private var autoStopPending = false
    private val autoStopRunnable = Runnable {
        autoStopPending = false
        DriveState.log("차 연결 끊김 — 자동 정지")
        stopAll(); stopSelf()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        outputs = Outputs(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> { stopAll(); stopSelf(); return START_NOT_STICKY }
            ACTION_CAR_DISCONNECTED -> {
                if (DriveState.running && !DriveState.simulating) {
                    main.removeCallbacks(autoStopRunnable)
                    autoStopPending = true
                    main.postDelayed(autoStopRunnable, AUTO_STOP_DELAY_MS)
                    DriveState.log("차 연결 끊김 — ${AUTO_STOP_DELAY_MS / 1000}초 안에 다시 안 붙으면 자동 정지")
                } else if (!DriveState.running) stopSelf()
                return START_STICKY
            }
            ACTION_CAR_CONNECTED -> {
                if (autoStopPending) { autoStopPending = false; main.removeCallbacks(autoStopRunnable); DriveState.log("차 다시 연결됨 — 자동 정지 취소") }
                if (!DriveState.running) stopSelf()
                return START_STICKY
            }
            ACTION_SIMULATE -> { if (!goForeground()) return START_NOT_STICKY; startSimulation() }
            ACTION_RELOAD -> { if (!goForeground()) return START_NOT_STICKY; startGps() }
            else -> {
                if (intent == null) DriveState.log("시스템이 서비스를 다시 시작함 — 감속 상태는 잊었습니다. 휠을 확인하세요")
                if (!goForeground()) return START_NOT_STICKY
                main.removeCallbacks(autoStopRunnable); autoStopPending = false
                if (DriveState.running && !DriveState.simulating && engine != null) {
                    DriveState.log("이미 감시 중"); return START_STICKY
                }
                if (intent?.getStringExtra("source") == "bluetooth") DriveState.log("차 블루투스 연결 — 자동 시작")
                AutoStart.cancelNotification(this)
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
        drOn = Prefs.deadReckoning(this)
        drMaxM = Prefs.deadReckonMaxM(this).toDouble()
        if (out.ble) S3xyButton.start(this, { DriveState.log(it) }, out.renameAdapter)
        roads?.close(); roads = null
        Thread {
            val idx = CameraRepo.load(this)
            val zones = CameraRepo.loadZones(this)
            val net = if (cfg.useRoads) CameraRepo.openRoads(this, idx.cameras) else null
            main.post {
                if (seq != loadSeq) { net?.close(); return@post }
                net?.onTileLoaded = { msg -> DriveState.log(msg) }
                roads = net
                val e = SlowdownEngine(idx, cfg, zones, net)
                engine = e
                DriveState.zoneCount = zones.cameras.size
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
            DriveState.lastFixAt = 0L
            gpsWarned = false
            lastFix = null; DriveState.drActive = false
            main.postDelayed(watchdog, 5_000)
            main.removeCallbacks(drRunnable); main.postDelayed(drRunnable, 1_000)
            val s = outputs.settings
            DriveState.log("주행 감시 시작 — 카메라 ${e.cameraCount}개, ${s.describe()}" + (if (e.hasRoads) ", 도로망 맵매칭" else ", 도로망 없음(GPS 방향 판단)"))
            if (e.cameraCount == 0) { DriveState.log("카메라 데이터가 없습니다 — CSV를 불러오세요"); outputs.say("카메라 데이터가 없습니다") }
            if (e.config.schoolZoneOn && e.zoneCount == 0) DriveState.log("어린이보호구역 감속이 켜져 있지만 보호구역 파일이 없습니다")
            if (s.ble && !S3xyButton.ready) DriveState.log("BLE 켜짐, 커맨더 대기 중 (${S3xyButton.status}) — 커맨더가 붙어야 신호가 나갑니다")
            notify("GPS 대기")
        }
    }

    override fun onLocationChanged(loc: Location) {
        lastFixAt = SystemClock.elapsedRealtime()
        DriveState.lastFixAt = lastFixAt
        val acc = if (loc.hasAccuracy()) loc.accuracy.toDouble() else null
        DriveState.lastAccM = acc
        if (gpsWarned || DriveState.drActive) { gpsWarned = false; DriveState.drActive = false; DriveState.log("GPS 복구") }
        val speed = if (loc.hasSpeed()) loc.speed.toDouble() else 0.0
        val course = if (loc.hasBearing() && speed >= 3.0) loc.bearing.toDouble() else null
        val fix = Fix(loc.latitude, loc.longitude, speed, course, acc)
        lastFix = fix
        handle(fix)
    }

    /**
     * 추측 항법: 실제 GPS가 3초 넘게 끊기면 마지막 속도·방향으로 1초마다 위치를 추정해 엔진에 넣는다.
     * 엔진은 추정 위치로 감속만 하고(놓치지 않기 위해) 통과·복귀는 실제 GPS가 돌아온 뒤에 판단한다.
     * 최대 거리(기본 2 km)를 넘기면 멈춘다.
     */
    private fun deadReckonStep() {
        if (!drOn || !DriveState.running || DriveState.simulating) return
        val e = engine ?: return
        val f = lastFix ?: return
        val course = f.courseDeg ?: return
        val silent = SystemClock.elapsedRealtime() - lastFixAt
        if (silent < 3_000 || f.speedMps < 5.0) return
        if (!DriveState.drActive) {
            DriveState.drActive = true
            drLat = f.lat; drLon = f.lon; drDistM = 0.0
            // 끊긴 시점까지의 시간만큼 먼저 전진
            val (la, lo) = Geo.offset(drLat, drLon, course, f.speedMps * silent / 1000.0)
            drLat = la; drLon = lo; drDistM += f.speedMps * silent / 1000.0
            val next = e.status.nextDistM?.toInt()
            DriveState.log("GPS 끊김 — 추측 항법 시작 (${f.speedMps.times(3.6).toInt()}km/h, 최대 ${drMaxM.toInt()}m)")
            outputs.say(if (next != null) "GPS 끊김. ${next}미터 앞 카메라, 추측으로 감속합니다" else "GPS 끊김, 추측 항법")
        } else {
            if (drDistM >= drMaxM) return
            val (la, lo) = Geo.offset(drLat, drLon, course, f.speedMps)
            drLat = la; drLon = lo; drDistM += f.speedMps
        }
        handle(Fix(drLat, drLon, f.speedMps, course, 5.0, estimated = true))
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
        DriveState.map = buildMap(e, fix, st)
        DriveState.changed()
        val text = buildString {
            append(if (st.slowed) "감속 중" else "대기")
            st.nextDistM?.let { append(" · 다음 카메라 ${it.toInt()}m (${st.nextCamera?.limitKmh})") }
            st.road?.let { if (it.startsWith("도로 미확인")) append(" · 도로 미확인") }
        }
        notify(text)
    }

    /** 지도에 그릴 것: 내 주변 도로 조각(도로망이 있을 때), 카메라(앞 시야·목표 표시), 감속 신호 거리. */
    private fun buildMap(e: SlowdownEngine, fix: Fix, st: EngineStatus): MapData {
        val course = st.courseDeg ?: fix.courseDeg ?: DriveState.map?.courseDeg ?: 0.0
        val segs = roads?.snapshot(fix.lat, fix.lon, MAP_RADIUS_M)
        val aheadSet = st.ahead.associateBy { it.cam }
        val cams = e.camerasNear(fix.lat, fix.lon, MAP_RADIUS_M).map { c ->
            val (east, north) = Geo.toLocal(fix.lat, fix.lon, c.lat, c.lon)
            val state = when { st.target == c -> 2; aheadSet.containsKey(c) -> 1; else -> 0 }
            MapCam(east.toFloat(), north.toFloat(), c.limitKmh, state)
        }
        return MapData(course, segs, cams, st.triggerM?.toFloat(), st.slowed, fix.estimated)
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
        main.removeCallbacks(drRunnable)
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
        main.removeCallbacks(drRunnable)
        DriveState.drActive = false
        main.removeCallbacks(autoStopRunnable); autoStopPending = false
        stopSimulation()
        getSystemService(LocationManager::class.java).removeUpdates(this)
        loadSeq++ // 읽는 중이던 엔진은 버린다
        if (!DriveState.running) return
        if (engine?.isSlowed == true) {
            DriveState.log("정지 — 감속 상태에서 멈췄습니다. 필요하면 휠로 직접 복귀하세요")
            outputs.say("감속 상태에서 정지했습니다. 휠을 확인하세요")
        }
        engine?.reset()
        roads?.close(); roads = null
        DriveState.running = false
        DriveState.status = null
        DriveState.map = null
        if (S3xyButton.running && !S3xyButton.pairingMode) S3xyButton.stop()
        DriveState.log("주행 감시 정지")
        DriveState.changed()
    }

    override fun onDestroy() {
        stopAll()
        outputs.shutdown()
        super.onDestroy()
    }
}
