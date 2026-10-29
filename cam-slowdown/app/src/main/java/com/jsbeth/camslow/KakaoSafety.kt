package com.jsbeth.camslow

import android.app.Application
import android.os.Handler
import android.os.Looper
import com.kakaomobility.knsdk.KNLanguageType
import com.kakaomobility.knsdk.KNRoutePriority
import com.kakaomobility.knsdk.KNSDK
import com.kakaomobility.knsdk.guidance.knguidance.KNGuidance
import com.kakaomobility.knsdk.guidance.knguidance.KNGuidance_LocationGuideDelegate
import com.kakaomobility.knsdk.guidance.knguidance.KNGuidance_SafetyGuideDelegate
import com.kakaomobility.knsdk.guidance.knguidance.KNGuidance_VoiceGuideDelegate
import com.kakaomobility.knsdk.guidance.knguidance.locationguide.KNGuide_Location
import com.kakaomobility.knsdk.guidance.knguidance.safetyguide.KNGuide_Safety
import com.kakaomobility.knsdk.guidance.knguidance.safetyguide.objects.KNSafety
import com.kakaomobility.knsdk.guidance.knguidance.safetyguide.objects.KNSafetyCode
import com.kakaomobility.knsdk.guidance.knguidance.safetyguide.objects.KNSafety_Camera
import com.kakaomobility.knsdk.guidance.knguidance.voiceguide.KNGuide_Voice
import java.util.UUID

/**
 * 카카오내비 SDK(KNSDK)의 "안전 운행"(목적지 없는 주행) 정보를 받는다(v0.9: 감속 판단 1순위).
 * 신선한 동안(FRESH_MS 안에 갱신) feed()가 엔진에 앞 카메라를 주고, 끊기면 null → 엔진은 내장(경찰청 데이터) 판단.
 *
 * - 앱 키는 빌드 때 GitHub Secrets(KAKAO_NATIVE_APP_KEY)에서 BuildConfig로 들어온다. 비어 있으면 아무것도 안 한다.
 * - 카카오 음성·과속 경고음은 끈다(우리 안내와 겹치지 않게).
 * - 카카오 데이터는 약관상 저장·재배포하지 않는다(메모리에만, 주행이 끝나면 버림).
 * - SDK가 없거나 기기에서 못 읽어도 앱이 죽지 않도록 전부 예외를 잡는다.
 */
object KakaoSafety : KNGuidance_SafetyGuideDelegate, KNGuidance_LocationGuideDelegate, KNGuidance_VoiceGuideDelegate {

    /** 앞길의 카카오 단속카메라 하나(WGS84). */
    class Cam(val id: String, val lat: Double, val lon: Double, val distM: Int, val limitKmh: Int?, val kind: String, val road: String)

    @Volatile var status: String = "카카오 꺼짐"
        private set
    @Volatile var cams: List<Cam> = emptyList()
        private set
    @Volatile var updatedAt = 0L
        private set

    private val main = Handler(Looper.getMainLooper())
    private var installed = false
    private var ready = false
    private var running = false
    private var guidance: KNGuidance? = null
    private var log: (String) -> Unit = {}
    private val logged = HashSet<String>()

    val hasKey: Boolean get() = BuildConfig.KAKAO_NATIVE_APP_KEY.isNotBlank()
    /** 지금 켜져 있는지(감시 중 연결 시도 포함). */
    val active: Boolean get() = running

    /** 마지막 갱신 뒤 지난 초. 받은 적이 없으면 null. */
    fun ageS(): Long? = if (updatedAt == 0L) null else (System.currentTimeMillis() - updatedAt) / 1000

    /** 엔진에 줄 앞 카메라. 신선하지 않으면 null(내장 판단으로). 이동식·보호구역은 뺀다. */
    fun feed(): List<SlowdownEngine.ExternalCam>? {
        if (!running || updatedAt == 0L || System.currentTimeMillis() - updatedAt > FRESH_MS) return null
        return cams.mapNotNull { c ->
            val limit = c.limitKmh ?: return@mapNotNull null
            if (c.distM < 0) return@mapNotNull null
            val kind = when (c.kind) {
                "구간단속 시작" -> CamKind.SECTION_START
                "구간단속 끝" -> CamKind.SECTION_END
                "이동식 과속카메라", "어린이보호구역" -> return@mapNotNull null
                else -> CamKind.SPOT
            }
            val name = listOf(c.road, c.kind).filter { it.isNotEmpty() }.joinToString(" ")
            SlowdownEngine.ExternalCam(
                Camera(c.lat, c.lon, limit, kind, 0, name, CODE, roadName = c.road, placeText = "kakao:${c.id}"),
                c.distM.toDouble())
        }
    }

    const val CODE = "KAKAO"
    /** 이보다 오래 갱신이 없으면 끊긴 것으로 본다. */
    const val FRESH_MS = 5_000L

    /** 주행 감시 시작 때. 메인 스레드에서 부른다. */
    fun start(app: Application, logger: (String) -> Unit) {
        log = logger
        if (!hasKey) { status = "카카오 앱 키 없음(빌드에 안 들어감)"; log(status); return }
        if (running) return
        running = true
        logged.clear(); cams = emptyList(); updatedAt = 0L
        try {
            if (!installed) {
                KNSDK.install(app, "${app.filesDir}/kakao")
                installed = true
            }
            if (ready) { startGuidance(); return }
            status = "카카오 연결 중…"; log(status)
            val prefs = app.getSharedPreferences("kakao", 0)
            val userKey = prefs.getString("user", null) ?: UUID.randomUUID().toString().also { prefs.edit().putString("user", it).apply() }
            KNSDK.initializeWithAppKey(BuildConfig.KAKAO_NATIVE_APP_KEY, BuildConfig.VERSION_NAME, userKey,
                aLangType = KNLanguageType.KNLanguageType_KOREAN, aCompletion = { error ->
                    main.post {
                        if (error != null) {
                            val detail = listOfNotNull(error.msg, error.tagMsg).firstOrNull { it.isNotBlank() }
                            status = "카카오 연결 실패 ${error.code}" + (detail?.let { " · $it" } ?: "")
                            log(status + hint(error.code.toString()))
                            running = false
                        } else {
                            ready = true
                            log("카카오 인증 성공")
                            if (running) startGuidance()
                        }
                    }
                })
        } catch (e: Throwable) {
            status = "카카오 SDK 오류: ${e.javaClass.simpleName} ${e.message ?: ""}"; log(status); running = false
        }
    }

    private fun hint(code: String): String = when {
        code.contains("C103") || code.contains("103") -> " — 카카오디벨로퍼스의 패키지명·키 해시를 확인하세요"
        code.contains("C302") || code.contains("302") || code.contains("401") || code.contains("403") -> " — 길찾기 SDK 권한이 없을 수 있습니다(데브톡 신청)"
        else -> ""
    }

    private fun startGuidance() {
        try {
            val g = KNSDK.sharedGuidance() ?: run { status = "카카오 안내 객체 없음"; log(status); running = false; return }
            guidance = g
            g.safetyGuideDelegate = this
            g.locationGuideDelegate = this
            g.voiceGuideDelegate = this
            g.useBackgroundUpdate = true
            g.judgeOverSpeedAlert = { _, _, _, _, _ -> false }
            g.startWithTrip(null, KNRoutePriority.KNRoutePriority_Recommand, 0)
            status = "카카오 안전 운행 시작 · 정보 대기"; log(status)
        } catch (e: Throwable) {
            status = "카카오 안전 운행 시작 실패: ${e.javaClass.simpleName} ${e.message ?: ""}"; log(status); running = false
        }
    }

    /** 주행 감시 정지 때. */
    fun stop() {
        if (!running && guidance == null) return
        running = false
        guidance?.let { g ->
            runCatching { g.stop() }
            runCatching { g.safetyGuideDelegate = null; g.locationGuideDelegate = null; g.voiceGuideDelegate = null }
        }
        guidance = null
        cams = emptyList(); logged.clear()
        status = "카카오 꺼짐"
    }

    // ---- SDK 콜백

    override fun guidanceDidUpdateSafetyGuide(guidance: KNGuidance, safetyGuide: KNGuide_Safety?) {
        if (!running) return
        refresh(guidance, safetyGuide?.safetiesOnGuide?.toList().orEmpty())
    }

    override fun guidanceDidUpdateAroundSafeties(guidance: KNGuidance, safeties: List<KNSafety>?) = Unit

    override fun guidanceDidUpdateLocation(guidance: KNGuidance, locationGuide: KNGuide_Location) {
        if (!running) return
        refresh(guidance, guidance.safetyGuide?.safetiesOnGuide?.toList().orEmpty())
    }

    /** 카카오 음성은 내지 않는다(우리 안내가 말한다). */
    override fun shouldPlayVoiceGuide(guidance: KNGuidance, voiceGuide: KNGuide_Voice, strings: MutableList<ByteArray>): Boolean = false
    override fun willPlayVoiceGuide(guidance: KNGuidance, voiceGuide: KNGuide_Voice) = Unit
    override fun didFinishPlayVoiceGuide(guidance: KNGuidance, voiceGuide: KNGuide_Voice) = Unit

    private fun refresh(g: KNGuidance, list: List<KNSafety>) {
        val out = ArrayList<Cam>()
        for (s in list) {
            runCatching {
                if (s.passed) return@runCatching
                val kind = kindOf(s.code) ?: return@runCatching
                val p = s.location.pos
                val wgs = KNSDK.convertKATECToWGS84(p.x.toInt(), p.y.toInt())
                val dist = g.locationGuide?.location?.distToLocation(s.location)?.toInt() ?: -1
                val limit = (s as? KNSafety_Camera)?.speedLimit?.takeIf { it in 10..130 }
                out += Cam("${s.safetyId}", wgs.y, wgs.x, dist, limit, kind, s.location.roadName ?: "")
            }
        }
        out.sortBy { if (it.distM < 0) Int.MAX_VALUE else it.distM }
        cams = out
        updatedAt = System.currentTimeMillis()
        if (status.startsWith("카카오 안전 운행 시작")) status = "카카오 안전 운행 받는 중"
        for (c in out) if (logged.add(c.id)) {
            log("카카오: ${c.kind}" + (c.limitKmh?.let { " 제한 $it" } ?: "") + " · ${if (c.distM >= 0) "${c.distM}m 앞" else "거리 모름"}" +
                (if (c.road.isNotEmpty()) " · ${c.road}" else "") + " (%.5f, %.5f)".format(c.lat, c.lon))
        }
    }

    private fun kindOf(code: KNSafetyCode): String? = when (code) {
        KNSafetyCode.KNSafetyCode_SpeedViolationCamera, KNSafetyCode.KNSafetyCode_BoxedSpeedViolationCamera -> "과속카메라"
        KNSafetyCode.KNSafetyCode_SignalAndSpeedViolationCamera -> "신호·과속카메라"
        KNSafetyCode.KNSafetyCode_SpeedViolationSectionInCamera -> "구간단속 시작"
        KNSafetyCode.KNSafetyCode_SpeedViolationSectionOutCamera -> "구간단속 끝"
        KNSafetyCode.KNSafetyCode_SpeedViolationBackwardCamera, KNSafetyCode.KNSafetyCode_SignalAndSpeedViolationBackwardCamera -> "후면 과속카메라"
        KNSafetyCode.KNSafetyCode_MovableSpeedViolationCamera -> "이동식 과속카메라"
        KNSafetyCode.KNSafetyCode_BuslaneAndSpeedViolationCamera, KNSafetyCode.KNSafetyCode_LaneAndSpeedViolationCamera -> "과속 겸용 카메라"
        KNSafetyCode.KNSafetyCode_ChildrenProtectionZone -> "어린이보호구역"
        else -> null
    }
}
