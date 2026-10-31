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
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
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
    /** 차에 보내 둔 것으로 아는 프로필. 시작 때 평소 프로필, 감속 신호 뒤 내릴 프로필, 복귀 뒤 평소 프로필. 감시가 꺼져 있으면 null. */
    @Volatile var carProfile: Profile? = null
    /** 마지막 감속·복귀 신호 시각(elapsedRealtime). 0이면 없음. 화면에 "복귀 완료"를 잠깐 보여 주는 데 쓴다. */
    @Volatile var lastDownAt = 0L
    @Volatile var lastUpAt = 0L
    /** 과속 경고 중이면 제한 대비 초과 km/h와 그 제한속도(화면 깜빡임용). */
    @Volatile var overspeedKmh: Int? = null
    @Volatile var overLimit: Int? = null
    /** 서비스 음성 엔진 상태. */
    @Volatile var ttsStatus = ""
    /** 마지막 실제 GPS fix 시각(elapsedRealtime). 0이면 아직 없음. */
    @Volatile var lastFixAt = 0L
    @Volatile var lastAccM: Double? = null
    /** 추측 항법으로 가고 있는 중. */
    @Volatile var drActive = false
    @Volatile var zoneCount = 0
    /** 화면 지도용 데이터. 매 fix마다 갱신. */
    var map: MapData? = null
    /** 메인 화면이 앞에 보이는 중(그때는 오버레이 창을 내린다). */
    @Volatile var activityVisible = false
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
    val route: VoiceRoute,
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
            alerts = Prefs.alertsOn(ctx), route = Prefs.voiceRoute(ctx), ble = Prefs.bleOn(ctx),
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
        append(if (ble) " (BLE 켬" else " (BLE 꺼짐 — 음성만")
        append(if (alerts) ", 소리: ${route.label})" else ", 소리 끔)")
    }
}

/**
 * 감속·복귀 신호 출력: 효과음 + 음성 안내 + (설정 시) 가상 S3XY 버튼.
 *
 * 소리 경로(VoiceRoute):
 *  - 알람 채널: 폰 스피커. 안드로이드는 블루투스가 붙어 있어도 알람은 폰에서 낸다. 진동 모드에도 난다.
 *  - 통화 채널: SCO(전화 음성) 링크를 잠깐 열어 차가 통화처럼 받게 한다. 열리는 데 ~1초. 말이 끝나면 닫는다.
 *  - 미디어 채널: 예전 방식. 차가 폰을 재생 소스로 쓰고 있을 때만 들린다.
 * logInit: 서비스가 만든 것만 음성 엔진 상태를 기록에 남긴다(화면마다 만드는 것은 조용히).
 */
class Outputs(ctx: Context, private val logInit: Boolean = false) {
    private val app = ctx.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private val am: AudioManager? = app.getSystemService(AudioManager::class.java)
    private var tts: TextToSpeech? = null
    @Volatile var ttsStatus: String = "음성 엔진 준비 중"
        private set
    private var tone: ToneGenerator? = null
    private var toneStream = -1
    @Volatile var settings: OutputSettings = OutputSettings.load(ctx)
        private set
    private var scoActive = false
    private val scoStop = Runnable { stopSco() }
    /** 마지막 감속 신호로 차를 내려 둔 프로필. 복귀 클릭 수는 그때 실제로 내린 만큼으로 계산한다(그 사이 설정이 바뀌어도). */
    @Volatile private var sentDown: Profile? = null

    companion object {
        /** 통화 채널이 열리고 소리가 나기까지 기다리는 시간(ms). */
        private const val SCO_DELAY_MS = 1000L
        /** 말이 끝나지 않아도 통화 채널을 닫는 최대 시간(ms). */
        private const val SCO_MAX_MS = 15_000L
    }

    init {
        tts = TextToSpeech(app) { st ->
            if (st == TextToSpeech.SUCCESS) {
                val r = try { tts?.setLanguage(Locale.KOREAN) ?: TextToSpeech.ERROR } catch (e: Exception) { TextToSpeech.ERROR }
                ttsStatus = when (r) {
                    TextToSpeech.LANG_MISSING_DATA, TextToSpeech.LANG_NOT_SUPPORTED -> "한국어 음성 없음 — 폰 설정 > 일반 > 텍스트 음성 변환에서 한국어 음성을 설치하세요"
                    TextToSpeech.ERROR -> "음성 엔진 오류"
                    else -> "음성 엔진 준비됨 (한국어)"
                }
                applyRoute()
                tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(utteranceId: String?) {}
                    override fun onDone(utteranceId: String?) { main.post { if (scoActive) { main.removeCallbacks(scoStop); main.postDelayed(scoStop, 500) } } }
                    @Deprecated("Deprecated in Java")
                    override fun onError(utteranceId: String?) { onDone(utteranceId) }
                })
            } else ttsStatus = "음성 엔진 초기화 실패 — 폰에 TTS 엔진(삼성·구글)이 있는지 확인"
            if (logInit) { DriveState.ttsStatus = ttsStatus; DriveState.log(ttsStatus) }
        }
    }

    /** 설정을 다시 읽는다. 서비스는 주행 시작 때만 부른다. */
    fun refresh(): OutputSettings { settings = OutputSettings.load(app); applyRoute(); return settings }

    private fun usageOf(route: VoiceRoute) = when (route) {
        VoiceRoute.SPEAKER -> AudioAttributes.USAGE_ALARM
        VoiceRoute.CAR_CALL -> AudioAttributes.USAGE_VOICE_COMMUNICATION
        VoiceRoute.MEDIA -> AudioAttributes.USAGE_MEDIA
    }

    private fun streamOf(route: VoiceRoute) = when (route) {
        VoiceRoute.SPEAKER -> AudioManager.STREAM_ALARM
        VoiceRoute.CAR_CALL -> AudioManager.STREAM_VOICE_CALL
        VoiceRoute.MEDIA -> AudioManager.STREAM_MUSIC
    }

    private fun applyRoute() {
        try {
            tts?.setAudioAttributes(AudioAttributes.Builder().setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).setUsage(usageOf(settings.route)).build())
        } catch (_: Exception) {}
    }

    private fun toneFor(route: VoiceRoute): ToneGenerator? {
        val st = streamOf(route)
        if (tone == null || toneStream != st) {
            try { tone?.release() } catch (_: Exception) {}
            tone = try { ToneGenerator(st, 90) } catch (_: RuntimeException) { null }
            toneStream = st
        }
        return tone
    }

    /** 효과음 뒤에 말한다. 통화 채널이면 먼저 링크를 열고 1초 기다린다(못 열면 폰 스피커로). */
    private fun announce(toneType: Int, toneMs: Int, text: String, id: String) {
        val s = settings
        if (!s.alerts) return
        var route = s.route
        if (route == VoiceRoute.CAR_CALL && !startSco()) {
            DriveState.log("통화 채널을 못 열어 폰 스피커로 냅니다")
            route = VoiceRoute.SPEAKER
        }
        val r = route
        val play = Runnable {
            try {
                tts?.setAudioAttributes(AudioAttributes.Builder().setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).setUsage(usageOf(r)).build())
                // durationMs 0은 "멈출 때까지"라는 뜻이라 효과음이 없으면 아예 부르지 않는다
                if (toneMs > 0) toneFor(r)?.startTone(toneType, toneMs)
            } catch (_: Exception) {}
            main.postDelayed({
                val res = tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, id) ?: TextToSpeech.ERROR
                if (res != TextToSpeech.SUCCESS) DriveState.log("음성 출력 실패 — $ttsStatus")
            }, toneMs + 50L)
        }
        if (r == VoiceRoute.CAR_CALL) main.postDelayed(play, SCO_DELAY_MS) else play.run()
    }

    fun say(text: String) {
        if (!settings.alerts) return
        announce(ToneGenerator.TONE_PROP_BEEP, 0, text, "say")
    }

    /** 과속 경고음 한 번(짧은 삐삐). 통화 채널 설정이어도 SCO를 열지 않고 폰 스피커로(자주 울리므로). */
    fun beep() {
        val s = settings
        if (!s.alerts) return
        val r = if (s.route == VoiceRoute.CAR_CALL) VoiceRoute.SPEAKER else s.route
        try { toneFor(r)?.startTone(ToneGenerator.TONE_CDMA_ALERT_CALL_GUARD, 160) } catch (_: Exception) {}
    }

    /** 통화 채널(SCO)을 연다. 이미 열려 있으면 닫기 예약만 미룬다. */
    @Suppress("DEPRECATION")
    private fun startSco(): Boolean {
        val a = am ?: return false
        try {
            main.removeCallbacks(scoStop)
            if (!scoActive) {
                if (Build.VERSION.SDK_INT >= 31) {
                    val dev = a.availableCommunicationDevices.firstOrNull { it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO } ?: return false
                    a.mode = AudioManager.MODE_IN_COMMUNICATION
                    if (!a.setCommunicationDevice(dev)) { a.mode = AudioManager.MODE_NORMAL; return false }
                } else {
                    if (!a.isBluetoothScoAvailableOffCall) return false
                    a.mode = AudioManager.MODE_IN_COMMUNICATION
                    a.startBluetoothSco()
                    a.isBluetoothScoOn = true
                }
                scoActive = true
            }
            main.postDelayed(scoStop, SCO_MAX_MS)
            return true
        } catch (e: Exception) {
            DriveState.log("통화 채널 열기 실패: ${e.message}")
            return false
        }
    }

    @Suppress("DEPRECATION")
    private fun stopSco() {
        if (!scoActive) return
        scoActive = false
        val a = am ?: return
        try {
            if (Build.VERSION.SDK_INT >= 31) a.clearCommunicationDevice() else { a.isBluetoothScoOn = false; a.stopBluetoothSco() }
            a.mode = AudioManager.MODE_NORMAL
        } catch (_: Exception) {}
    }

    /** 설정 화면의 "안내 소리 테스트". */
    fun test() {
        refresh()
        DriveState.log("안내 소리 테스트 (${settings.route.label}) — $ttsStatus")
        if (!settings.alerts) { DriveState.log("음성·효과음이 꺼져 있습니다"); return }
        announce(ToneGenerator.TONE_PROP_BEEP2, 300, "안내 소리 테스트입니다. 이 소리가 들리면 설정이 맞습니다", "test")
    }

    fun down(cam: Camera, distM: Double) {
        val s = settings
        val zone = cam.code == ZoneCsv.CODE
        DriveState.log(if (zone) "▼ 어린이보호구역 진입. 감속 → ${s.slow.label} (${cam.name})"
            else "▼ 카메라 ${distM.toInt()}m 앞, 제한 ${cam.limitKmh}. 감속 → ${s.slow.label} (${cam.name})")
        DriveState.carProfile = s.slow
        DriveState.lastDownAt = SystemClock.elapsedRealtime()
        sentDown = s.slow
        announce(ToneGenerator.TONE_PROP_BEEP2, 300, if (zone) "어린이보호구역. 감속합니다" else "과속 카메라, 제한속도 ${cam.limitKmh}. 감속합니다", "down")
        if (s.ble) S3xyButton.press(S3xyButton.Press.SINGLE, s.downPresses, s.pressGapMs)
    }

    /** 구간단속 시점을 지나 유지에 들어감. */
    fun hold(cam: Camera, holdM: Double, lengthKnown: Boolean, midEntry: Boolean = false) {
        val km = "%.1f".format(holdM / 1000.0)
        if (midEntry) {
            DriveState.log("구간단속 중간 진입 — 끝까지 약 ${km}km 유지 (${cam.name})")
            announce(ToneGenerator.TONE_PROP_BEEP, 150, "구간단속 중입니다. 끝까지 ${km} 킬로미터 유지합니다", "hold")
            return
        }
        DriveState.log(if (lengthKnown) "구간단속 시점 통과 — 종점까지 약 ${km}km 유지 (${cam.name})"
            else "구간단속 시점 통과 — 길이 모름, 종점 카메라까지(최대 ${km}km) 유지 (${cam.name})")
        announce(ToneGenerator.TONE_PROP_BEEP, 150, if (lengthKnown) "구간단속 시작. 종점까지 ${km} 킬로미터 유지합니다" else "구간단속 시작. 종점 카메라까지 유지합니다", "hold")
    }

    /** force: 테스트 버튼·직접 복귀처럼 자동 복귀 설정과 무관하게 복귀 신호를 보낼 때. */
    fun up(reason: String, force: Boolean = false) {
        val s = settings
        if (!s.autoRestore && !force) {
            DriveState.log("△ 카메라 통과 ($reason) — 자동 복귀 꺼짐, 휠로 직접 올리세요")
            announce(ToneGenerator.TONE_PROP_ACK, 200, "카메라 통과. 직접 복귀하세요", "up")
            return
        }
        // 올릴 단수 = 지금 평소 프로필 − 실제로 내려 둔 프로필 (감속 중에 평소 프로필을 바꿨어도 정확히 거기로 간다)
        val from = sentDown ?: s.slow
        val presses = (s.usual.ordinal - from.ordinal).coerceAtLeast(0)
        DriveState.log("▲ 복귀 → ${s.usual.label} ($reason)" + (if (from != s.slow) " · ${from.label}에서 ${presses}번" else ""))
        DriveState.carProfile = s.usual
        DriveState.lastUpAt = SystemClock.elapsedRealtime()
        sentDown = null
        announce(ToneGenerator.TONE_PROP_ACK, 200, "복귀합니다", "up")
        if (s.ble) S3xyButton.press(S3xyButton.Press.DOUBLE, presses, s.pressGapMs)
    }

    fun shutdown() {
        main.removeCallbacks(scoStop); stopSco()
        try { tts?.shutdown() } catch (_: Exception) {}
        try { tone?.release() } catch (_: Exception) {}
        tone = null
    }
}

class DriveService : Service(), LocationListener {

    companion object {
        const val ACTION_START = "start"
        /** CSV를 새로 불러온 뒤 등, 감시 중이어도 엔진을 다시 만든다. */
        const val ACTION_RELOAD = "reload"
        const val ACTION_SIMULATE = "simulate"
        const val ACTION_STOP = "stop"
        /** 운전자가 "지금 복귀"를 누름: 감속 상태를 풀고 복귀 신호를 보낸다. */
        const val ACTION_RELEASE = "release"
        /** 메인 화면에서 설정(평소 프로필)을 바꿈: 감시 중이어도 바로 적용한다. */
        const val ACTION_SETTINGS_CHANGED = "settings_changed"
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
            // 이미 떠 있는 서비스에 보내는 명령은 startService로(포그라운드 승격 의무가 없다)
            if (action == ACTION_STOP || action == ACTION_RELEASE || action == ACTION_SETTINGS_CHANGED) ctx.startService(i) else ctx.startForegroundService(i)
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
    /** 접근 안내·과속 경고음. */
    private val alerts = ApproachAlerts()
    private var startedAt = 0L
    private var firstFixSeen = false
    /** 카카오가 지금 판단에 쓰이는지(null = 이번 감시에서 아직 모름). 바뀔 때 음성으로 알린다. */
    private var kakaoLive: Boolean? = null
    private var kakaoFailSaid = false
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
        stopAll(); stopAfterSpeech()
    }

    /** 안내 음성이 끝날 시간을 준 뒤 서비스를 끝낸다. 그 사이 다시 켰으면 그대로 둔다. */
    private fun stopAfterSpeech() {
        main.postDelayed({ if (!DriveState.running) { stopForeground(STOP_FOREGROUND_REMOVE); stopSelf() } }, 3_000)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        outputs = Outputs(this, logInit = true)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> { stopAll(); stopAfterSpeech(); return START_NOT_STICKY }
            ACTION_RELEASE -> {
                val e = engine
                if (DriveState.running && e != null) {
                    // 감속 중이면 상태를 풀고 복귀 신호. 자동 복귀를 꺼 두어 내린 채로 남아 있을 때도 복귀 신호를 보낸다.
                    if (e.release() || DriveState.carProfile != outputs.settings.usual) outputs.up("직접 복귀", force = true)
                    else DriveState.log("복귀할 감속 상태가 아닙니다")
                    DriveState.status = e.status
                    DriveState.changed()
                } else if (!DriveState.running) stopSelf()
                return START_STICKY
            }
            ACTION_SETTINGS_CHANGED -> {
                val e = engine
                if (DriveState.running && e != null) {
                    val out = outputs.refresh()
                    val cfg = Prefs.engine(this).copy(bleLatencyS = out.bleDownSeconds())
                    e.config = cfg
                    DriveState.config = cfg
                    DriveState.outputs = out
                    // 감속 중이 아니면 차가 (새) 평소 프로필에 있다고 본다. 감속 중이면 복귀 때 내린 만큼 올려 새 평소 프로필로 간다.
                    if (!e.isSlowed) DriveState.carProfile = out.usual
                    DriveState.log("설정 바로 적용 — ${out.describe()}")
                    DriveState.changed()
                } else if (!DriveState.running) stopSelf()
                return START_STICKY
            }
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
            .setContentTitle("FSD 감속")
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
        DriveState.carProfile = out.usual
        drOn = Prefs.deadReckoning(this)
        drMaxM = Prefs.deadReckonMaxM(this).toDouble()
        if (out.ble) S3xyButton.start(this, { DriveState.log(it) }, out.renameAdapter)
        roads?.close(); roads = null
        Thread {
            val idx = CameraRepo.load(this)
            val zones = CameraRepo.loadZones(this)
            val net = if (cfg.useRoads) CameraRepo.openRoads(this, idx.cameras) else null
            // 도로망이 설치장소 글로 방향을 붙인 목록을 엔진에도 준다(GPS 기하 판단에서도 반대 방향을 거르도록)
            val index = if (net != null) CameraIndex(net.cameras) else idx
            main.post {
                if (seq != loadSeq) { net?.close(); return@post }
                net?.onTileLoaded = { msg -> DriveState.log(msg) }
                roads = net
                val e = SlowdownEngine(index, cfg, zones, net)
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
            startedAt = SystemClock.elapsedRealtime(); firstFixSeen = false; kakaoLive = null; kakaoFailSaid = false
            alerts.reset()
            alerts.margin = Prefs.overspeedMargin(this); alerts.announce = Prefs.approachVoice(this)
            alerts.distanceAlways = Prefs.distanceVoiceAlways(this); alerts.beepOn = Prefs.overspeedBeep(this)
            main.postDelayed(watchdog, 5_000)
            main.removeCallbacks(drRunnable); main.postDelayed(drRunnable, 1_000)
            val s = outputs.settings
            DriveState.log("주행 감시 시작 — 카메라 ${e.cameraCount}개, ${s.describe()}" + (if (e.hasRoads) ", 도로망 맵매칭" else ", 도로망 없음(GPS 방향 판단)"))
            if (e.hasRoads && CameraRepo.directionNote.isNotEmpty()) DriveState.log("카메라 ${CameraRepo.directionNote}")
            if (e.cameraCount == 0) { DriveState.log("카메라 데이터가 없습니다 — CSV를 불러오세요"); outputs.say("카메라 데이터가 없습니다") }
            else outputs.say("감시 시작. 카메라 ${"%,d".format(e.cameraCount)}개" + (if (e.hasRoads) ", 도로망 있음" else ", 도로망 없음"))
            if (e.config.schoolZoneOn && e.zoneCount == 0) DriveState.log("어린이보호구역 감속이 켜져 있지만 보호구역 파일이 없습니다")
            if (s.ble && !S3xyButton.ready) DriveState.log("BLE 켜짐, 커맨더 대기 중 (${S3xyButton.status}) — 커맨더가 붙어야 신호가 나갑니다")
            // 카카오내비 안전 운행(시험): 연결 결과와 앞 카메라를 기록에만 남긴다
            KakaoSafety.start(application) { msg -> DriveState.log(msg) }
            notify("GPS 대기")
        }
    }

    override fun onLocationChanged(loc: Location) {
        lastFixAt = SystemClock.elapsedRealtime()
        DriveState.lastFixAt = lastFixAt
        val acc = if (loc.hasAccuracy()) loc.accuracy.toDouble() else null
        DriveState.lastAccM = acc
        if (gpsWarned || DriveState.drActive) {
            if (gpsWarned) outputs.say("GPS 다시 잡힘")
            gpsWarned = false; DriveState.drActive = false; DriveState.log("GPS 복구")
        }
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
        // 카카오 안전 운행이 신선하면 그 앞 카메라로, 아니면 내장 판단(가상 주행은 늘 내장)
        e.external = if (DriveState.simulating) null else KakaoSafety.feed()
        for (ev in e.update(fix)) when (ev) {
            is EngineEvent.Down -> outputs.down(ev.camera, ev.distM)
            is EngineEvent.Up -> outputs.up(ev.reason)
            is EngineEvent.Hold -> outputs.hold(ev.camera, ev.holdM, ev.lengthKnown, ev.midEntry)
        }
        val st = e.status
        DriveState.status = st
        val now = SystemClock.elapsedRealtime()
        // 접근 안내·과속 경고음 (방금 "감속합니다"를 말했으면 거리 안내는 건너뜀)
        val secLimit = if (st.slowed && st.holdKind == "구간단속") st.target?.limitKmh else null
        for (o in alerts.update(now, st.nextCamera, st.nextDistM, st.speedKmh, secLimit)) when (o) {
            is ApproachAlerts.Out.Say -> if (now - DriveState.lastDownAt > 2_500) { DriveState.log("안내: ${o.text}"); outputs.say(o.text) }
            ApproachAlerts.Out.Beep -> outputs.beep()
        }
        DriveState.overspeedKmh = alerts.overspeedKmh; DriveState.overLimit = alerts.overLimit
        // 감시가 "제대로" 시작됐는지 음성으로: GPS를 늦게 잡았을 때, 카카오가 붙거나 끊길 때
        if (!DriveState.simulating && !fix.estimated) {
            if (!firstFixSeen) {
                firstFixSeen = true
                if (now - startedAt > 4_000) { DriveState.log("GPS 잡힘 — 감시 시작"); outputs.say("GPS 잡힘. 감시를 시작합니다") }
            }
            if (KakaoSafety.hasKey) {
                val live = e.external != null
                val prev = kakaoLive
                when {
                    live && prev != true -> { DriveState.log(if (prev == null) "카카오 연결 — 카카오로 판단" else "카카오 다시 연결"); outputs.say(if (prev == null) "카카오 연결됨" else "카카오 다시 연결됨") }
                    !live && prev == true -> { DriveState.log("카카오 끊김 — 내장 데이터로 판단"); outputs.say("카카오 끊김. 내장 데이터로 판단합니다") }
                    !live && prev != true && !kakaoFailSaid && now - startedAt > 20_000 -> {
                        kakaoFailSaid = true
                        DriveState.log("카카오 연결 안 됨 — 내장 데이터로 판단 (${KakaoSafety.status})")
                        outputs.say("카카오 연결 안 됨. 내장 데이터로 판단합니다")
                    }
                }
                kakaoLive = live
            }
        }
        DriveState.map = buildMap(e, fix, st)
        DriveState.changed()
        OverlayWindow.sync(this)
        val text = buildString {
            append(if (st.slowed) "감속 중" else "대기")
            st.holdRemainM?.let { append(" · ${st.holdKind} ${it.toInt()}m 남음") }
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
        // 카카오 판단 중엔 카카오가 준 내 방향 카메라만 그린다(경찰청 빈 원은 안 그림)
        val source = if (st.external) st.ahead.map { it.cam } else e.camerasNear(fix.lat, fix.lon, MAP_RADIUS_M)
        val cams = source.map { c ->
            val (east, north) = Geo.toLocal(fix.lat, fix.lon, c.lat, c.lon)
            val state = when { st.target == c -> 2; aheadSet.containsKey(c) -> 1; else -> 0 }
            MapCam(east.toFloat(), north.toFloat(), c.limitKmh, state)
        }
        return MapData(course, segs, cams, st.triggerM?.toFloat(), st.slowed, fix.estimated, inSection = st.holdKind == "구간단속")
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
        DriveState.carProfile = null
        OverlayWindow.hide()
        DriveState.log("가상 주행 끝 — 실제 주행은 [감시 켜기]를 누르세요")
        outputs.say("가상 주행 끝. 실제 주행은 감시 켜기를 누르세요")
        DriveState.changed()
        notify("가상 주행 끝 — 감시 꺼짐")
        // 음성이 끝날 시간을 준 뒤 종료. 그 사이 [감시 켜기]를 눌렀으면 그대로 둔다.
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
        KakaoSafety.stop()
        loadSeq++ // 읽는 중이던 엔진은 버린다
        if (!DriveState.running) return
        val wasSlowed = engine?.isSlowed == true
        if (wasSlowed) {
            DriveState.log("정지 — 감속 상태에서 멈췄습니다. 필요하면 휠로 직접 복귀하세요")
            outputs.say("감속 상태에서 정지했습니다. 휠을 확인하세요")
        }
        engine?.reset()
        roads?.close(); roads = null
        DriveState.running = false
        DriveState.status = null
        DriveState.overspeedKmh = null; DriveState.overLimit = null
        DriveState.map = null
        DriveState.carProfile = null
        OverlayWindow.hide()
        if (S3xyButton.running && !S3xyButton.pairingMode) S3xyButton.stop()
        DriveState.log("주행 감시 정지")
        if (!wasSlowed) outputs.say("감시를 끕니다")
        DriveState.changed()
    }

    override fun onDestroy() {
        stopAll()
        outputs.shutdown()
        super.onDestroy()
    }
}
