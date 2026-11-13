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
    /** 테슬라 내비 목적지(감시 중 2분마다 갱신). null이면 목적지 없음. */
    @Volatile var teslaDest: TeslaDest.Dest? = null
    /** 커맨더가 안 붙어 다시 등록 안내 창을 띄울 시각(0 = 없음). 화면이 보이면 한 번 띄운다. */
    @Volatile var cmdHelpAt = 0L
    /** 최근 앱 목록에서 앱을 밀어 닫으면 알약 창을 숨긴다(감시는 계속). 앱을 다시 열면 풀린다. */
    @Volatile var overlaySuppressed = false
    /** 화면(MainActivity)에서 감시 서비스의 음성으로 말할 때(같은 TTS 순서로). 서비스가 있을 때만. */
    @Volatile var say: ((String) -> Unit)? = null
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
    /** 유실 대비 여분 클릭 수(감속은 맨 아래로 내릴 때만, 복귀는 평소가 맨 위일 때만). */
    val extraClicks: Int,
) {
    companion object {
        fun load(ctx: Context) = OutputSettings(
            alerts = Prefs.alertsOn(ctx), route = Prefs.voiceRoute(ctx), ble = Prefs.bleOn(ctx),
            usual = Prefs.usualProfile(ctx), slow = Prefs.slowProfile(ctx),
            downPresses = Prefs.downPresses(ctx), upPresses = Prefs.upPresses(ctx),
            autoRestore = Prefs.autoRestore(ctx), pressGapMs = Prefs.pressGapMs(ctx), renameAdapter = Prefs.renameAdapter(ctx),
            extraClicks = Prefs.extraDownClicks(ctx),
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
            if (logInit) KakaoSafety.ourSpeaking = { runCatching { tts?.isSpeaking == true }.getOrDefault(false) }
        }
    }

    /** 설정을 다시 읽는다. 서비스는 주행 시작 때만 부른다. */
    /** keepBle: 감시 중 설정 바로 적용 때 — 커맨더 사용 여부는 감시를 다시 켤 때만 바꾼다(감속 중에 끄면 복귀가 조용히 사라짐). */
    fun refresh(keepBle: Boolean = false): OutputSettings {
        val ble = settings.ble
        settings = OutputSettings.load(app).let { if (keepBle) it.copy(ble = ble) else it }
        applyRoute(); return settings
    }

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
            KakaoSafety.voiceUsage = usageOf(if (settings.route == VoiceRoute.CAR_CALL) VoiceRoute.SPEAKER else settings.route)
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
            // v0.12.9: 카카오 음성이 나오는 중이면 끝날 때까지(최대 6초) 기다렸다 말한다 — 서로 끊지 않게
            val waitStart = SystemClock.elapsedRealtime()
            val speakNow = object : Runnable {
                override fun run() {
                    if (KakaoSafety.voicePlaying && SystemClock.elapsedRealtime() - waitStart < 6_000) { main.postDelayed(this, 200); return }
                    // v0.13.3: 앞의 우리 안내를 끊지 않고 이어서 말한다("감속합니다"가 다음 안내에 잘리지 않게)
                    val res = tts?.speak(text, TextToSpeech.QUEUE_ADD, null, id) ?: TextToSpeech.ERROR
                    if (res != TextToSpeech.SUCCESS) DriveState.log("음성 출력 실패 — $ttsStatus")
                }
            }
            main.postDelayed(speakNow, toneMs + 50L)
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

    /**
     * 차가 실제로 있다고 보는 프로필 — 커맨더 작업 스레드에서 실제로 나간 클릭으로만 바뀐다(감시 시작 때 평소 프로필).
     * null이면 모름(감시 밖의 테스트 버튼). 감속·복귀 클릭 수는 모두 보낼 차례에 이것으로 계산한다.
     */
    @Volatile private var carNow: Profile? = null

    /** 감시 시작·평소 프로필 변경 때: 차가 이 프로필에 있다고 본다. */
    fun resetCar(p: Profile) { carNow = p }

    private fun showCar(p: Profile) = main.post { DriveState.carProfile = p }

    fun down(cam: Camera, distM: Double) {
        val s = settings
        val zone = cam.code == ZoneCsv.CODE
        DriveState.log(if (zone) "▼ 어린이보호구역 진입. 감속 → ${s.slow.label} (${cam.name})"
            else "▼ 카메라 ${distM.toInt()}m 앞, 제한 ${cam.limitKmh}. 감속 → ${s.slow.label} (${cam.name})")
        DriveState.carProfile = s.slow
        DriveState.lastDownAt = SystemClock.elapsedRealtime()
        // v0.13.3: 카메라·제한속도·보호구역 안내는 카카오가 한다 — 우리는 "감속합니다"만(늘)
        // v0.14.0: 앞의 삐 소리 없앰(카카오 음성과 겹침) — 말만
        announce(ToneGenerator.TONE_PROP_BEEP2, 0, "감속합니다", "down")
        if (!s.ble) return
        // 실제로 나간 클릭 수만큼만 내려갔다고 본다(커맨더가 끊겨 못 보냈는데 복귀에서 올리면 평소보다 빨라진다)
        val extra = (s.downPresses - (s.usual.ordinal - s.slow.ordinal)).coerceAtLeast(0)
        var base = s.usual
        var full = 0
        S3xyButton.press(S3xyButton.Press.SINGLE, {
            base = carNow ?: s.usual
            full = (base.ordinal - s.slow.ordinal).coerceAtLeast(0)
            if (full == 0) 0 else full + extra
        }, s.pressGapMs) { sent, asked ->
            val steps = minOf(sent, full)
            val now = Profile.values()[base.ordinal - steps]
            carNow = now
            showCar(now)
            if (steps < full) main.post {
                DriveState.log("⚠ 감속 클릭 ${sent}/${asked}만 나감 — 차는 ${now.label}로 봄. 복귀는 그만큼만 올림")
                say(if (sent == 0) "감속 신호를 못 보냈습니다. 직접 줄이세요" else "감속 신호가 일부만 나갔습니다")
            }
        }
    }

    /** 구간단속 시점을 지나 유지에 들어감. */
    // v0.13.3: 구간단속 시작·유지 음성은 없앰(카카오가 안내) — 기록만
    fun hold(cam: Camera, holdM: Double, lengthKnown: Boolean, midEntry: Boolean = false) {
        val km = "%.1f".format(holdM / 1000.0)
        if (midEntry) {
            DriveState.log("구간단속 중간 진입 — 끝까지 약 ${km}km 유지 (${cam.name})")
            return
        }
        DriveState.log(if (lengthKnown) "구간단속 시점 통과 — 종점까지 약 ${km}km 유지 (${cam.name})"
            else "구간단속 시점 통과 — 길이 모름, 종점 카메라까지(최대 ${km}km) 유지 (${cam.name})")
    }

    /** force: 테스트 버튼·직접 복귀처럼 자동 복귀 설정과 무관하게 복귀 신호를 보낼 때. */
    fun up(reason: String, force: Boolean = false) {
        val s = settings
        if (!s.autoRestore && !force) {
            DriveState.log("△ 카메라 통과 ($reason) — 자동 복귀 꺼짐, 휠로 직접 올리세요")
            announce(ToneGenerator.TONE_PROP_ACK, 0, "카메라 통과. 직접 복귀하세요", "up")
            return
        }
        // 올릴 단수 = 평소 프로필 − 차가 실제로 있다고 보는 프로필. 앞의 클릭이 아직 나가는 중이면 끝난 뒤(같은 작업 순서) 계산한다.
        // 모르면(감시 밖 테스트 버튼) 내릴 프로필에 있다고 본다.
        DriveState.log("▲ 복귀 → ${s.usual.label} ($reason)")
        DriveState.carProfile = s.usual
        DriveState.lastUpAt = SystemClock.elapsedRealtime()
        announce(ToneGenerator.TONE_PROP_ACK, 0, "복귀합니다", "up")
        if (!s.ble) { if (carNow != null) carNow = s.usual; return }
        var base = s.slow
        // 평소 프로필이 맨 위(Mad Max)면 복귀에도 여분 클릭을 더한다 — 맨 위에선 추가 클릭이 무시되므로 안전하고,
        // 클릭 하나가 유실돼 한 단 아래(신속주행)에 머무는 일을 막는다(감속의 나무늘보 여분과 같은 원리)
        val top = s.usual == Profile.values().last()
        val extraUp = if (top) s.extraClicks.coerceAtLeast(0) else 0
        S3xyButton.press(S3xyButton.Press.DOUBLE, {
            base = carNow ?: s.slow
            val n = (s.usual.ordinal - base.ordinal).coerceAtLeast(0)
            if (n == 0) 0 else n + extraUp
        }, s.pressGapMs) { sent, asked ->
            val now = Profile.values()[(base.ordinal + sent).coerceAtMost(s.usual.ordinal)]
            carNow = now
            showCar(now)
            // 올릴 클릭이 없었음 = 앱은 차가 이미 평소에 있다고 봄(감속 클릭이 안 나갔던 경우 등). 운전자가 직접 내렸다면 직접 올려야 한다.
            if (asked == 0) main.post {
                DriveState.log("⚠ 복귀 클릭 없음 — 앱은 차가 이미 ${s.usual.label}에 있다고 봄. 직접 내렸다면 휠로 올리세요")
                say("복귀 신호는 보내지 않았습니다. 직접 내렸다면 휠로 올리세요")
            } else if (now != s.usual) main.post {
                DriveState.log("⚠ 복귀 클릭 ${sent}/${asked}만 나감 — 차는 ${now.label}로 봄. [지금 복귀]로 나머지만 올림")
                say("복귀 신호가 다 나가지 않았습니다")
            }
        }
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
        /** 테슬라 목적지를 지금 확인(설정 화면의 버튼·연결 직후). */
        const val ACTION_TESLA_CHECK = "tesla_check"
        /** 블루투스 끊김 뒤 자동 정지까지 기다리는 시간(ms). 잠깐 끊겼다 붙는 경우를 거른다. */
        private const val AUTO_STOP_DELAY_MS = 60_000L
        /** 커맨더가 이 시간 넘게 준비 안 되면 음성 경고. */
        private const val CMD_WARN_MS = 60_000L
        /** 이만큼 넘게 서 있으면 "움직일 때만" 방식에서 테슬라 확인을 쉰다. */
        private const val PARK_MS = 3 * 60_000L
        private const val CHANNEL = "drive"
        private const val NOTI_ID = 1
        /** GPS fix가 이 시간(ms) 넘게 없으면 경고. */
        private const val GPS_STALE_MS = 15_000L
        /** 화면 지도에 담을 반경(m). 지도는 앞 500 m를 보여 준다. */
        private const val MAP_RADIUS_M = 700.0

        fun send(ctx: Context, action: String) {
            val i = Intent(ctx, DriveService::class.java).setAction(action)
            // 이미 떠 있는 서비스에 보내는 명령은 startService로(포그라운드 승격 의무가 없다)
            if (action == ACTION_STOP || action == ACTION_RELEASE || action == ACTION_SETTINGS_CHANGED || action == ACTION_TESLA_CHECK) ctx.startService(i) else ctx.startForegroundService(i)
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
    private var kakaoLostAt = 0L
    private var kakaoLostSaid = false
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
        override fun run() { checkGps(); checkCommander(); KakaoSafety.ensureDelegates(); main.postDelayed(this, 5_000) }
    }
    /** 커맨더 감시: 감시 시작(또는 연결이 끊긴) 뒤 60초 넘게 커맨더가 준비 안 되면 한 번 말한다. 다시 붙으면 알린다. */
    private var cmdLostAt = 0L
    private var cmdWarned = false
    private var cmdWarnAt = 0L
    private var repairSaid = false
    private var lastReadvertise = 0L
    private var cmdHelpShown = false
    private fun checkCommander() {
        if (DriveState.simulating || !outputs.settings.ble) { cmdLostAt = 0L; cmdWarned = false; return }
        val now = SystemClock.elapsedRealtime()
        if (S3xyButton.repairNeeded && !repairSaid) {
            repairSaid = true
            outputs.say("커맨더 다시 페어링이 필요합니다. 엔하우토에서 버튼을 다시 추가하세요")
        } else if (!S3xyButton.repairNeeded) repairSaid = false
        if (S3xyButton.ready) {
            if (cmdWarned) { DriveState.log("커맨더 연결됨 — 감속 신호를 보낼 수 있습니다"); outputs.say("커맨더 연결됨") }
            cmdLostAt = 0L; cmdWarned = false; return
        }
        if (cmdLostAt == 0L) { cmdLostAt = now; return }
        // 1분·4분·…(3분마다) 아무것도 안 붙어 있으면 같은 광고를 다시 켠다(v0.14.4: 새로 만들지 않음 — 주소 유지)
        if (S3xyButton.idle && now - cmdLostAt >= CMD_WARN_MS && now - lastReadvertise >= 180_000L) {
            lastReadvertise = now; S3xyButton.readvertise()
        }
        val repeatMs = Prefs.cmdWarnRepeatMin(this) * 60_000L
        if (cmdWarned && repeatMs > 0 && now - cmdWarnAt >= repeatMs) {
            cmdWarnAt = now
            DriveState.log("⚠ 커맨더 아직 연결 안 됨 (${S3xyButton.status})")
            outputs.say("커맨더가 아직 연결되지 않았습니다")
        }
        // 광고를 다시 켜고도 2분 넘게 아무것도 안 오면 화면에 다시 등록 안내(한 번)
        // v0.14.3: 버튼을 새로 켠 뒤(재부팅·업데이트·앱 새로 시작, 10분 안)면 1분 만에 — 이때는 다시 추가해야 붙는 일이 많다
        val freshButton = S3xyButton.startedAt > 0L && now - S3xyButton.startedAt <= 10 * 60_000L
        if (cmdWarned && S3xyButton.idle && !cmdHelpShown && now - cmdLostAt >= (if (freshButton) CMD_WARN_MS else 3 * CMD_WARN_MS)) {
            cmdHelpShown = true; DriveState.cmdHelpAt = System.currentTimeMillis(); DriveState.changed()
        }
        if (!cmdWarned && now - cmdLostAt >= CMD_WARN_MS) {
            cmdWarned = true; cmdWarnAt = now
            DriveState.log("⚠ 커맨더가 1분 넘게 연결되지 않음 (${S3xyButton.status}) — 감속 신호가 나가지 않습니다. " +
                if (S3xyButton.viaBoard) "보드 전원·보드 불빛을 확인하세요" else "계속되면 [페어링하기] 후 enhauto에서 버튼 다시 추가")
            outputs.say("커맨더가 연결되지 않았습니다. 감속 신호가 나가지 않습니다")
            S3xyButton.logBond("커맨더 1분 넘게 안 옴")
        }
    }
    /** 테슬라 목적지: 마지막으로 카카오에 적용한 것과 오류 기록(같은 오류는 한 번만). */
    private var appliedDest: TeslaDest.Dest? = null
    private var teslaErrSaid: String? = null
    private var teslaOfflineSaid = false
    /** 테슬라 확인이 연달아 실패·차 잠든 횟수(간격을 2→5→10분으로 늘림). */
    private var teslaMiss = 0
    /** 마지막으로 움직인(시속 7 km 이상) 시각. "움직일 때만" 확인 방식에 쓴다. */
    private var lastMovingAt = 0L
    private var teslaParked = false
    private val teslaPoll = object : Runnable {
        override fun run() {
            val mode = Prefs.teslaPoll(this@DriveService)
            val parked = mode == TeslaPoll.MOVING && SystemClock.elapsedRealtime() - lastMovingAt > PARK_MS
            if (parked != teslaParked) {
                teslaParked = parked
                if (parked && appliedDest != null) DriveState.log("테슬라: ${PARK_MS / 60_000}분 넘게 정차 — 목적지 확인 쉼(움직이면 다시)")
            }
            if (mode != TeslaPoll.MANUAL && !parked) checkTesla()
            main.postDelayed(this, when { teslaMiss >= 3 -> 600_000L; teslaMiss == 2 -> 300_000L; else -> TeslaLink.POLL_MS })
        }
    }
    private var autoStopPending = false
    private val autoStopRunnable = Runnable {
        autoStopPending = false
        DriveState.log("차 연결 끊김 — 자동 정지")
        stopAll(); stopAfterSpeech()
    }

    /** 마지막 정지가 감속 상태였는지(이번 stopAll에서). 경고 음성이 끝까지 나오도록 서비스를 늦게 끝낸다. */
    private var stoppedSlowed = false

    /** 안내 음성이 끝날 시간을 준 뒤 서비스를 끝낸다. 그 사이 다시 켰으면 그대로 둔다. */
    private fun stopAfterSpeech() {
        // "감속 상태에서 정지했습니다. 휠을 확인하세요"는 4초쯤, 통화 채널이면 연결에 1초 더 — 넉넉히 기다린다
        val wait = if (stoppedSlowed) 9_000L else 3_000L
        main.postDelayed({ if (!DriveState.running) idleOrStop() }, wait)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        outputs = Outputs(this, logInit = true)
        DriveState.say = { t -> outputs.say(t) }
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
                } else if (!DriveState.running) idleOrStop()
                return START_STICKY
            }
            ACTION_SETTINGS_CHANGED -> {
                val e = engine
                if (DriveState.running && e != null) {
                    val prevUsual = DriveState.outputs?.usual
                    val out = outputs.refresh(keepBle = true)
                    if (Prefs.bleOn(this) != out.ble) DriveState.log("커맨더 사용 변경은 감시를 다시 켜면 적용됩니다")
                    if (out.ble && S3xyButton.running && Prefs.viaBoard(this) != S3xyButton.viaBoard) DriveState.log("커맨더 연결 방식(폰 직접/보드 경유) 변경은 감시를 다시 켜면 적용됩니다")
                    if (!DriveState.simulating) dropDestIfOff()
                    val cfg = Prefs.engine(this).copy(bleLatencyS = out.bleDownSeconds())
                    e.config = cfg
                    DriveState.config = cfg
                    DriveState.outputs = out
                    // 감속 중이 아니면 차가 (새) 평소 프로필에 있다고 본다. 감속 중이면 복귀 때 내린 만큼 올려 새 평소 프로필로 간다.
                    // 단, 자동 복귀를 꺼 둬 아직 내린 프로필로 남아 있으면 덮지 않는다([지금 복귀]가 사라지지 않게)
                    val car = DriveState.carProfile
                    if (!e.isSlowed && (car == null || car == prevUsual)) { DriveState.carProfile = out.usual; outputs.resetCar(out.usual) }
                    DriveState.log("설정 바로 적용 — ${out.describe()}")
                    DriveState.changed()
                } else if (!DriveState.running) idleOrStop()
                return START_STICKY
            }
            ACTION_CAR_DISCONNECTED -> {
                if (DriveState.running && !DriveState.simulating) {
                    main.removeCallbacks(autoStopRunnable)
                    autoStopPending = true
                    main.postDelayed(autoStopRunnable, AUTO_STOP_DELAY_MS)
                    DriveState.log("차 연결 끊김 — ${AUTO_STOP_DELAY_MS / 1000}초 안에 다시 안 붙으면 자동 정지")
                } else if (!DriveState.running) idleOrStop()
                return START_STICKY
            }
            ACTION_CAR_CONNECTED -> {
                if (autoStopPending) { autoStopPending = false; main.removeCallbacks(autoStopRunnable); DriveState.log("차 다시 연결됨 — 자동 정지 취소") }
                if (!DriveState.running) idleOrStop()
                return START_STICKY
            }
            ACTION_TESLA_CHECK -> {
                if (DriveState.running && !DriveState.simulating) checkTesla(force = true) else if (!DriveState.running) idleOrStop()
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

    /** 감시를 꺼도 커맨더 버튼을 계속 켜 둘지(설정 ⑥, 커맨더 사용 중일 때). */
    private fun keepButton() = Prefs.bleOn(this) && Prefs.keepButton(this) && !Prefs.viaBoard(this) // 보드 경유는 유지할 게 없음(보드 주소가 고정)

    /** 감시가 꺼진 뒤: 커맨더 버튼을 유지하면 작은 알림으로 서비스를 남기고, 아니면 끝낸다. */
    private fun idleOrStop() {
        if (keepButton() && S3xyButton.running) {
            try {
                val nm = getSystemService(NotificationManager::class.java)
                nm.createNotificationChannel(NotificationChannel(CHANNEL, "주행 감시", NotificationManager.IMPORTANCE_LOW))
                startForeground(NOTI_ID, notification("커맨더 버튼 유지 중 · 감시 꺼짐 (설정 ⑥에서 끌 수 있음)"))
                return
            } catch (e: Exception) {
                DriveState.log("커맨더 버튼 유지 실패: ${e.javaClass.simpleName} — 버튼 끔")
                S3xyButton.stop()
            }
        } else if (S3xyButton.running && !S3xyButton.pairingMode) S3xyButton.stop()
        stopForeground(STOP_FOREGROUND_REMOVE); stopSelf()
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
        val sp = getSharedPreferences("drive", 0)
        if (engine?.isSlowed == true || sp.getBoolean("stoppedSlowed", false)) {
            // 앱은 차 프로필을 읽지 못하므로 평소 프로필에 있다고 보고 시작한다 — 실제로 낮게 남아 있으면 운전자가 맞춰야 한다
            DriveState.log("주의: 지난번 감속 상태에서 정지 — 앱은 평소 프로필로 보고 시작합니다. 휠을 평소 프로필로 맞췄는지 확인하세요")
            outputs.say("지난번 감속 상태에서 멈췄습니다. 휠을 확인하세요")
            sp.edit().putBoolean("stoppedSlowed", false).apply()
        }
        engine = null
        val out = outputs.refresh()
        val cfg = Prefs.engine(this).let { c -> c.copy(bleLatencyS = out.bleDownSeconds()) }
        DriveState.config = cfg
        DriveState.outputs = out
        DriveState.carProfile = out.usual
        outputs.resetCar(out.usual)
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
            cmdLostAt = 0L; cmdWarned = false; cmdHelpShown = false
            startedAt = SystemClock.elapsedRealtime(); firstFixSeen = false; kakaoLive = null; kakaoFailSaid = false; kakaoLostAt = 0L; kakaoLostSaid = false
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
            UpdateCheck.maybeCheck(this)
            // 테슬라 내비 목적지(설정 9번): 2분마다
            appliedDest = null; teslaErrSaid = null; teslaOfflineSaid = false; DriveState.teslaDest = null
            teslaMiss = 0; teslaParked = false; lastMovingAt = SystemClock.elapsedRealtime()
            main.removeCallbacks(teslaPoll)
            // 감시 중에 테슬라를 연결·켜도 따라가도록 늘 돈다(꺼져 있거나 연결 전이면 checkTesla가 건너뜀)
            main.postDelayed(teslaPoll, 3_000)
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

    /** 테슬라 목적지를 읽어 바뀌었으면 카카오 경로 안내를 그 목적지로(없어졌으면 안전 운행으로). */
    /** 목적지 따라가기를 껐거나 테슬라 연결을 끊었으면, 적용해 둔 목적지 경로를 풀고 목적지 없이 감시로. */
    private fun dropDestIfOff(): Boolean {
        if (Prefs.teslaFollow(this) && TeslaLink.connected(this)) return false
        if (appliedDest != null || DriveState.teslaDest != null) {
            appliedDest = null; DriveState.teslaDest = null
            KakaoSafety.setDestination(null)
            DriveState.log("목적지 따라가기 꺼짐/테슬라 연결 끊김 — 목적지 없이 감시")
            DriveState.changed()
        }
        return true
    }

    private fun checkTesla(force: Boolean = false) {
        if (!DriveState.running || DriveState.simulating) return
        if (dropDestIfOff() && !(force && TeslaLink.connected(this))) return
        TeslaLink.fetch(this) { r ->
            if (!DriveState.running || DriveState.simulating) return@fetch
            if (force) DriveState.log("테슬라 목적지 확인: " + when (r) {
                is TeslaDest.Reply.Offline -> "차가 자는 중"
                is TeslaDest.Reply.Online -> r.dest?.let { TeslaDest.label(it) } ?: "내비 목적지 없음"
                is TeslaDest.Reply.Error -> "실패 — ${r.message}"
            })
            teslaMiss = if (r is TeslaDest.Reply.Online) 0 else teslaMiss + 1
            when (r) {
                is TeslaDest.Reply.Offline -> if (!teslaOfflineSaid) { teslaOfflineSaid = true; DriveState.log("테슬라: 차가 자는 중 — 목적지 확인 건너뜀") }
                is TeslaDest.Reply.Error -> if (teslaErrSaid != r.message) { teslaErrSaid = r.message; DriveState.log("테슬라 목적지 확인 실패: ${r.message}") }
                is TeslaDest.Reply.Online -> {
                    teslaErrSaid = null; teslaOfflineSaid = false
                    val d = if (Prefs.teslaFollow(this)) r.dest else null
                    DriveState.teslaDest = d
                    if (TeslaDest.changed(appliedDest, d)) {
                        appliedDest = d
                        if (d != null) {
                            val name = TeslaDest.label(d)
                            DriveState.log("테슬라 목적지: $name")
                            outputs.say("목적지 $name, 경로 안내 시작")
                        } else DriveState.log("테슬라 목적지 없음 — 목적지 없이 감시")
                        KakaoSafety.setDestination(d)
                    }
                }
            }
            DriveState.changed()
        }
    }

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
        if (!fix.estimated && fix.speedMps > 2.0) {
            lastMovingAt = SystemClock.elapsedRealtime()
            // 정차로 쉬던 테슬라 확인을 다시 움직이면 곧바로
            if (teslaParked) { teslaParked = false; main.removeCallbacks(teslaPoll); main.postDelayed(teslaPoll, 1_000) }
        }
        if (!fix.estimated && !DriveState.simulating) KakaoSafety.setHere(fix.lat, fix.lon)
        // 카카오 안전 운행이 신선하면 그 앞 카메라로, 아니면 내장 판단(가상 주행은 늘 내장)
        val slowNow = !fix.estimated && fix.speedMps * 3.6 < 10.0
        e.external = if (DriveState.simulating) null else KakaoSafety.feed(Prefs.includeMobile(this), slowNow)
        for (ev in e.update(fix)) when (ev) {
            is EngineEvent.Down -> outputs.down(ev.camera, ev.distM)
            is EngineEvent.Up -> outputs.up(ev.reason)
            is EngineEvent.Hold -> outputs.hold(ev.camera, ev.holdM, ev.lengthKnown, ev.midEntry)
        }
        val st = e.status
        DriveState.status = st
        val now = SystemClock.elapsedRealtime()
        // v0.13.3: 카메라 접근 안내·"감속하세요"·과속 경고음은 내지 않는다(카카오 내비가 안내) — 화면의 과속 표시만 계산
        val secLimit = if (st.slowed && st.holdKind == "구간단속") st.target?.limitKmh else null
        alerts.update(now, st.nextCamera, st.nextDistM, st.speedKmh, secLimit)
        DriveState.overspeedKmh = alerts.overspeedKmh; DriveState.overLimit = alerts.overLimit
        // 감시가 "제대로" 시작됐는지 음성으로: GPS를 늦게 잡았을 때, 카카오가 붙거나 끊길 때
        if (!DriveState.simulating && !fix.estimated) {
            if (!firstFixSeen) {
                firstFixSeen = true
                if (now - startedAt > 4_000) { DriveState.log("GPS 잡힘 — 감시 시작"); outputs.say("GPS 잡힘. 감시를 시작합니다") }
            }
            // 목적지 전환으로 카카오 안내를 다시 시작하는 동안은 끊김·재연결을 알리지 않는다(전환 뒤 상태로 한 번만)
            if (KakaoSafety.hasKey && !KakaoSafety.switching) {
                val live = e.external != null
                val prev = kakaoLive
                // v0.13.3: 짧은 끊김(신호 대기·잠깐)은 기록만, 30초 넘게 이어질 때만 말한다. 다시 연결도 말했던 때만.
                when {
                    live && prev == null -> { DriveState.log("카카오 연결 — 카카오로 판단"); outputs.say("카카오 연결됨") }
                    live && prev == false -> {
                        DriveState.log("카카오 다시 연결" + (if (kakaoLostAt > 0) " (${(now - kakaoLostAt) / 1000}초 만)" else ""))
                        if (kakaoLostSaid) outputs.say("카카오 다시 연결됨")
                        kakaoLostAt = 0L; kakaoLostSaid = false
                    }
                    !live && prev == true -> {
                        kakaoLostAt = now; kakaoLostSaid = false
                        DriveState.log("카카오 끊김 — 내장 데이터로 판단 (속도 ${(fix.speedMps * 3.6).toInt()} km/h, 카카오 마지막 정보 ${KakaoSafety.ageS() ?: -1}초 전)")
                    }
                    !live && prev == false && !kakaoLostSaid && kakaoLostAt > 0 && now - kakaoLostAt > 30_000 -> {
                        kakaoLostSaid = true
                        DriveState.log("카카오 끊김 30초 넘음 (${KakaoSafety.status})")
                        outputs.say("카카오 끊김. 내장 데이터로 판단합니다")
                    }
                    !live && prev == null && !kakaoFailSaid && now - startedAt > 20_000 -> {
                        kakaoFailSaid = true; kakaoLostAt = now; kakaoLostSaid = true
                        DriveState.log("카카오 연결 안 됨 — 내장 데이터로 판단 (${KakaoSafety.status})")
                        outputs.say("카카오 연결 안 됨. 내장 데이터로 판단합니다")
                    }
                }
                if (live || prev != null || kakaoFailSaid) kakaoLive = live
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
            MapCam(east.toFloat(), north.toFloat(), c.limitKmh, state, c.lat, c.lon)
        }
        return MapData(course, segs, cams, st.triggerM?.toFloat(), st.slowed, fix.estimated, inSection = st.holdKind == "구간단속", lat = fix.lat, lon = fix.lon)
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
        main.postDelayed({ if (!DriveState.running) idleOrStop() }, 4_000)
    }

    private fun stopSimulation() {
        simRunnable?.let { main.removeCallbacks(it) }
        simRunnable = null
        DriveState.simulating = false
    }

    /** quiet = 운전자가 앱을 밀어 닫았을 때: "감시를 끕니다"는 말하지 않는다(감속 상태 경고는 안전상 그대로). */
    private fun stopAll(quiet: Boolean = false) {
        main.removeCallbacks(watchdog)
        main.removeCallbacks(drRunnable)
        main.removeCallbacks(teslaPoll); appliedDest = null; DriveState.teslaDest = null
        DriveState.drActive = false
        main.removeCallbacks(autoStopRunnable); autoStopPending = false
        stopSimulation()
        getSystemService(LocationManager::class.java).removeUpdates(this)
        KakaoSafety.stop()
        loadSeq++ // 읽는 중이던 엔진은 버린다
        if (!DriveState.running) return
        val wasSlowed = engine?.isSlowed == true
        stoppedSlowed = wasSlowed
        // 다음에 감시를 켤 때(서비스가 새로 떠도) 알리도록 남겨 둔다
        getSharedPreferences("drive", 0).edit().putBoolean("stoppedSlowed", wasSlowed).apply()
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
        if (S3xyButton.running && !S3xyButton.pairingMode && !keepButton()) S3xyButton.stop()
        else if (S3xyButton.running) DriveState.log("커맨더 버튼 남김(" + (if (S3xyButton.pairingMode) "페어링 모드" else "유지 설정") + ")")
        DriveState.log("주행 감시 정지")
        if (!wasSlowed && !quiet) outputs.say("감시를 끕니다")
        DriveState.changed()
    }

    /**
     * 앱을 최근 목록에서 밀어 닫음(v0.10.10): 감시를 끄고 서비스도 내린다 — 안 쓸 때 "GPS 꺼졌습니다" 같은 음성이 계속 나지 않게.
     * 차 블루투스 자동 시작은 리시버라 그대로 동작한다(다음 연결 때 다시 켜짐).
     */
    override fun onTaskRemoved(rootIntent: Intent?) {
        if (DriveState.running) DriveState.log("앱을 닫음 — 감시 정지 (차 블루투스 자동 시작은 그대로)")
        DriveState.overlaySuppressed = false
        OverlayWindow.hide()
        stopAll(quiet = true)
        stopAfterSpeech()
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        DriveState.say = null
        stopAll()
        outputs.shutdown()
        super.onDestroy()
    }
}
