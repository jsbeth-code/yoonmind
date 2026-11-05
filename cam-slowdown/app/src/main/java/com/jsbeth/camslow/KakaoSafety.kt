package com.jsbeth.camslow

import android.app.Application
import android.os.Handler
import android.os.Looper
import com.kakaomobility.knsdk.KNLanguageType
import com.kakaomobility.knsdk.common.objects.KNPOI
import com.kakaomobility.knsdk.guidance.knguidance.KNGuidance_RouteGuideDelegate
import com.kakaomobility.knsdk.guidance.knguidance.routeguide.KNGuide_Route
import com.kakaomobility.knsdk.guidance.knguidance.routeguide.objects.KNDirection
import com.kakaomobility.knsdk.trip.kntrip.KNTrip
import com.kakaomobility.knsdk.trip.kntrip.knroute.KNRoute
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
import com.kakaomobility.knsdk.KNRouteAvoidOption
import com.kakaomobility.knsdk.common.objects.KNError
import com.kakaomobility.knsdk.guidance.knguidance.KNGuideRouteChangeReason
import com.kakaomobility.knsdk.guidance.knguidance.KNGuideState
import com.kakaomobility.knsdk.guidance.knguidance.KNGuidance_CitsGuideDelegate
import com.kakaomobility.knsdk.guidance.knguidance.KNGuidance_GuideStateDelegate
import com.kakaomobility.knsdk.guidance.knguidance.citsguide.KNGuide_Cits
import com.kakaomobility.knsdk.guidance.knguidance.common.KNLocation
import com.kakaomobility.knsdk.guidance.knguidance.routeguide.objects.KNMultiRouteInfo
import com.kakaomobility.knsdk.ui.view.KNNaviView
import com.kakaomobility.knsdk.ui.view.KNNaviView_GuideStateDelegate
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
object KakaoSafety : KNGuidance_SafetyGuideDelegate, KNGuidance_LocationGuideDelegate, KNGuidance_VoiceGuideDelegate, KNGuidance_RouteGuideDelegate,
    KNGuidance_GuideStateDelegate, KNGuidance_CitsGuideDelegate {

    // ---- v0.11 카카오내비 화면(KNNaviView). 안내(KNGuidance)는 늘 여기서 돌고, 화면이 붙어 있으면 SDK 콜백을 화면에도 넘긴다.
    /** 메인 화면에 붙은 카카오내비 화면. 없으면(앱이 뒤에 있음·화면 없음) 안내만 돈다. */
    @Volatile var naviView: KNNaviView? = null
        private set
    /** initWithGuidance까지 끝난 화면. 이 화면에만 콜백을 넘긴다. */
    @Volatile private var initedView: KNNaviView? = null
    private var lastHereAt = 0L
    /** 지금 경로 종류. 새 목적지가 들어오면 설정의 기본값으로 시작한다. */
    @Volatile var routeOpt: RouteOpt = RouteOpt.RECOMMEND
        private set
    /** 경로 종류 고르기 창을 이 시각(System.currentTimeMillis)까지 띄운다. 0이면 안 띄움. */
    @Volatile var askRouteUntil = 0L
    /** 경로 후보를 받아 고르기를 시작한 시각. 카메라 때문에 너무 오래 미뤄지면(45초) 안 띄운다. */
    @Volatile var askSince = 0L
    /** 경로 고르기 화면에 보여 줄 후보(종류별 시간·거리·요금·경로선). 비어 있으면 간단한 창. */
    class RouteChoice(val opt: RouteOpt, val distM: Int, val timeS: Int, val cost: Int, val line: DoubleArray?)
    @Volatile var choices: List<RouteChoice> = emptyList()
        private set
    private var previewSeq = 0
    private var askedDest: TeslaDest.Dest? = null
    /** 도착·안내 종료로 끝난 목적지 — 테슬라가 같은 목적지를 계속 줘도 다시 안내하지 않는다. */
    private var endedDest: TeslaDest.Dest? = null
    private var curTrip: KNTrip? = null
    private var app: Application? = null
    /** SDK 인증이 끝나 안내가 돌 수 있는지. */
    val sdkReady: Boolean get() = ready && running && guidance != null

    /** 앞길의 카카오 단속카메라 하나(WGS84). */
    class Cam(val id: String, val lat: Double, val lon: Double, val distM: Int, val limitKmh: Int?, val kind: String, val road: String)

    @Volatile var status: String = "카카오 꺼짐"
        private set
    @Volatile var cams: List<Cam> = emptyList()
        private set
    @Volatile var updatedAt = 0L
        private set

    // ---- v0.10 목적지 경로 안내(테슬라 내비 목적지). 목적지가 없으면 지금처럼 안전 운행.
    /** 경로 안내 중인 목적지. null이면 안전 운행(목적지 없음). */
    @Volatile var destination: TeslaDest.Dest? = null
        private set
    /** 카카오가 그 목적지로 경로를 잡아 안내 중인지. */
    @Volatile var following = false
        private set
    /** 다음 회전·그다음 회전(경로 안내 중일 때만). */
    @Volatile var turn: TurnInfo? = null
        private set
    @Volatile var nextTurn: TurnInfo? = null
        private set
    /** 경로선(위도, 경도 번갈아). */
    @Volatile var routeLine: DoubleArray? = null
        private set
    private var routeRef: KNRoute? = null
    /** 목적지 전환으로 안내를 다시 시작하는 중(이 시각까지). 그동안 끊김 음성을 내지 않고 엔진은 내장 판단으로. */
    @Volatile private var switchingUntil = 0L
    val switching: Boolean get() = System.currentTimeMillis() < switchingUntil
    /** 경로 만들기 연속 실패 수(재시도 간격 1 → 2 → 5분). */
    private var tripFails = 0
    private var tripSeq = 0

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

    /** 엔진에 줄 앞 카메라. 신선하지 않으면 null(내장 판단으로). 보호구역은 빼고, 이동식은 includeMobile일 때만. */
    fun feed(includeMobile: Boolean = false, slow: Boolean = false): List<SlowdownEngine.ExternalCam>? {
        // v0.13.3: 서 있거나 아주 천천히 갈 때(신호 대기) 카카오가 갱신을 안 보내 5초 만에 "끊김"이 되던 것 → 이때는 1분까지 기다린다
        if (!running || updatedAt == 0L || System.currentTimeMillis() - updatedAt > (if (slow) SLOW_FRESH_MS else FRESH_MS)) return null
        if (following && offRoute) return null
        var lastStartLimit: Int? = null
        return cams.mapNotNull { c ->
            if (c.kind == "구간단속 시작" && c.limitKmh != null) lastStartLimit = c.limitKmh
            // 구간 끝은 제한속도가 없어도 넘긴다(끝을 알아야 유지를 끝냄) — 앞의 시작 제한, 없으면 0
            val limit = c.limitKmh ?: (if (c.kind == "구간단속 끝") (lastStartLimit ?: 0) else return@mapNotNull null)
            if (c.distM < 0) return@mapNotNull null
            val kind = when (c.kind) {
                "구간단속 시작" -> CamKind.SECTION_START
                "구간단속 끝" -> CamKind.SECTION_END
                "이동식 과속카메라" -> if (includeMobile) CamKind.SPOT else return@mapNotNull null
                "어린이보호구역" -> return@mapNotNull null
                else -> CamKind.SPOT
            }
            val name = listOf(c.road, c.kind).filter { it.isNotEmpty() }.joinToString(" ")
            SlowdownEngine.ExternalCam(
                Camera(c.lat, c.lon, limit, kind, 0, name, CODE, roadName = c.road, placeText = "kakao:${c.id}"),
                c.distM.toDouble())
        }
    }

    const val CODE = SlowdownEngine.EXTERNAL_CODE
    /** 목적지 전환 유예(ms): 이 안에 카카오가 다시 정보를 주면 끊김으로 알리지 않는다. */
    const val SWITCH_GRACE_MS = 20_000L
    private val RETRY_MS = longArrayOf(60_000L, 120_000L, 300_000L)
    /** 이보다 오래 갱신이 없으면 끊긴 것으로 본다. */
    const val FRESH_MS = 5_000L
    /** 서 있거나 10 km/h 아래일 때는 이만큼까지 끊김으로 안 본다. */
    const val SLOW_FRESH_MS = 60_000L
    /** 경로 종류 고르기 창을 띄우는 시간. */
    const val ASK_ROUTE_MS = 15_000L

    /** 주행 감시 시작 때. 메인 스레드에서 부른다. */
    fun start(app: Application, logger: (String) -> Unit) {
        log = logger
        this.app = app
        routeOpt = Prefs.routeOpt(app)
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
            setDelegates(g)
            g.useBackgroundUpdate = true
            g.judgeOverSpeedAlert = { _, _, _, _, _ -> false }
            val d = destination
            if (d != null && lastLat != 0.0) { startTrip(g, d); return }
            begin(g, null)
            status = "카카오 안전 운행 시작 · 정보 대기"; log(status)
        } catch (e: Throwable) {
            status = "카카오 안전 운행 시작 실패: ${e.javaClass.simpleName} ${e.message ?: ""}"; log(status); running = false
        }
    }

    /** 카카오 화면이 안내의 콜백을 가져갔으면 다시 우리에게(우리가 화면에 넘긴다). DriveService가 5초마다 부른다. */
    fun ensureDelegates() {
        val g = guidance ?: return
        if (!running) return
        val taken = g.safetyGuideDelegate !== this || g.locationGuideDelegate !== this || g.routeGuideDelegate !== this ||
            g.voiceGuideDelegate !== this || g.guideStateDelegate !== this || g.citsGuideDelegate !== this
        if (taken) {
            if (!delegateNoted) { delegateNoted = true; log("카카오 화면이 안내 콜백을 가져감 — 다시 연결(화면에는 우리가 넘김)") }
            setDelegates(g)
        }
    }
    private var delegateNoted = false

    private fun setDelegates(g: KNGuidance) {
        g.safetyGuideDelegate = this
        g.locationGuideDelegate = this
        g.voiceGuideDelegate = this
        g.routeGuideDelegate = this
        g.guideStateDelegate = this
        g.citsGuideDelegate = this
    }

    private fun prio(o: RouteOpt) = when (o) {
        RouteOpt.TIME -> KNRoutePriority.KNRoutePriority_Time
        RouteOpt.DISTANCE -> KNRoutePriority.KNRoutePriority_Distance
        else -> KNRoutePriority.KNRoutePriority_Recommand
    }
    private fun avoid(o: RouteOpt) = if (o == RouteOpt.FREE) KNRouteAvoidOption.KNRouteAvoidOption_Fare.value else 0

    /** 안내 시작(trip이 null이면 안전 운행). 화면이 붙어 있으면 화면을 통해 시작한다(화면이 안내를 그린다). */
    private fun begin(g: KNGuidance, trip: KNTrip?) {
        curTrip = trip
        lastSelfStop = System.currentTimeMillis() // 시작이 앞 안내를 끝내며 보내는 "끝" 알림은 무시
        beganAt = lastSelfStop
        val v = naviView
        if (v != null) {
            try {
                v.initWithGuidance(g, trip, prio(routeOpt), avoid(routeOpt))
                initedView = v
                setDelegates(g) // 화면이 바꿨을 수 있으니 다시
                // 화면이 나중에 콜백을 가져가거나 안내를 안 시작하면: 다시 붙이고, 그래도 정보가 없으면 직접 시작
                val started = beganAt
                main.postDelayed({ ensureDelegates() }, 1_000)
                main.postDelayed({
                    // GPS가 잡혀 있는데도(시작 뒤 실제 위치를 받음) 카카오 정보가 없을 때만
                    if (running && guidance === g && beganAt == started && updatedAt < started && lastHereAt > started) {
                        log("카카오 화면으로 시작했지만 정보가 안 옴 — 안내를 직접 시작")
                        ensureDelegates()
                        lastSelfStop = System.currentTimeMillis(); beganAt = lastSelfStop // 재시작의 "끝" 알림을 도착으로 보지 않게
                        runCatching { g.startWithTrip(trip, prio(routeOpt), avoid(routeOpt)) }
                    }
                }, 6_000)
                return
            } catch (e: Throwable) {
                log("카카오 화면 시작 실패: ${e.javaClass.simpleName} ${e.message ?: ""} — 화면 없이 안내")
                naviView = null
            }
        }
        g.startWithTrip(trip, prio(routeOpt), avoid(routeOpt))
    }

    /** 메인 화면이 카카오내비 화면을 붙인다. 안내가 돌고 있으면 화면으로 다시 시작한다(지금 위치에서 새 경로). 메인 스레드. */
    fun attachView(v: KNNaviView) {
        naviView = v
        runCatching { v.guideStateDelegate = viewGuideState }
        val g = guidance ?: return
        if (!running || !ready) return
        cams = emptyList(); updatedAt = 0L
        switchingUntil = System.currentTimeMillis() + SWITCH_GRACE_MS
        val d = destination
        val wasFollowing = following
        try {
            selfStop(g)
            turn = null; nextTurn = null; routeLine = null; routeRef = null; following = false
            if (d != null && wasFollowing && lastLat != 0.0) startTrip(g, d, ask = false) else begin(g, null)
        } catch (e: Throwable) { log("카카오 화면 연결 실패: ${e.javaClass.simpleName}") }
    }

    /** 메인 화면이 사라질 때. 안내는 계속 돈다. */
    fun detachView(v: KNNaviView) {
        if (naviView === v) naviView = null
        if (initedView === v) initedView = null
    }

    /** 새 목적지: 안내는 기본 종류로 이미 시작했고, 따로 종류별 경로를 받아 고르기 화면에 보여 준다. 메인 스레드. */
    private fun previewRoutes(d: TeslaDest.Dest) {
        val seq = ++previewSeq
        val opts = listOf(RouteOpt.RECOMMEND, RouteOpt.TIME, RouteOpt.FREE, RouteOpt.DISTANCE)
        val got = ArrayList<RouteChoice>()
        fun done() {
            if (seq != previewSeq || !running) return
            choices = opts.mapNotNull { o -> got.firstOrNull { it.opt == o } }
            askRouteUntil = System.currentTimeMillis() + ASK_ROUTE_MS
            askSince = System.currentTimeMillis()
            if (choices.isEmpty()) log("경로 후보를 못 받음 — 간단한 고르기 창")
        }
        try {
            KNSDK.makeTripWithStart(poi("현재 위치", lastLat, lastLon), poi(TeslaDest.label(d), d.lat, d.lon), null) { err, trip ->
                main.post {
                    if (seq != previewSeq) return@post
                    if (err != null || trip == null) { done(); return@post }
                    fun next(i: Int) {
                        if (i >= opts.size || seq != previewSeq) { done(); return }
                        val o = opts[i]
                        try {
                            trip.routeWithPriority(prio(o), avoid(o)) { e, routes ->
                                main.post {
                                    val r = routes?.firstOrNull()
                                    if (e == null && r != null) runCatching { got += RouteChoice(o, r.totalDist, r.totalTime, r.totalCost, polyline(r)) }
                                    next(i + 1)
                                }
                            }
                        } catch (t: Throwable) { next(i + 1) }
                    }
                    next(0)
                }
            }
        } catch (t: Throwable) { done() }
    }

    /** 고르기 창에서 경로 종류를 골랐다. 지금과 다르면 지금 위치에서 그 종류로 다시 찾는다. 메인 스레드. */
    fun chooseRoute(o: RouteOpt) {
        askRouteUntil = 0L; choices = emptyList(); previewSeq++
        app?.let { Prefs.setAskedOpt(it, o) }
        if (o == routeOpt) return
        routeOpt = o
        val g = guidance ?: return
        val d = destination ?: return
        if (!running || !ready) return
        log("경로 종류: ${o.label}로 다시 찾음")
        selfStop(g)
        cams = emptyList(); updatedAt = 0L
        switchingUntil = System.currentTimeMillis() + SWITCH_GRACE_MS
        turn = null; nextTurn = null; routeLine = null; routeRef = null; following = false
        try { startTrip(g, d, ask = false) } catch (e: Throwable) { log("경로 다시 찾기 실패: ${e.javaClass.simpleName}") }
    }

    /** 안내가 끝남(도착·화면에서 안내 종료) → 목적지 없이 안전 운행으로. */
    private fun guideEnded(why: String) {
        if (!running) return
        val g = guidance ?: return
        // 우리가 경로를 바꾸려고 멈춘 것(3초 안)이면 무시
        if (System.currentTimeMillis() - lastSelfStop < 3_000) return
        // 경로를 찾는 중(우리가 멈춘 뒤 새 경로 대기)이면 늦게 온 "끝" 알림으로 본다
        if (tripPending) return
        if (restartPosted) return
        restartPosted = true
        val arrived = following && destination != null
        log("카카오 안내 끝($why) — " + (if (arrived) "목적지 없이 감시" else "안전 운행 다시 시작"))
        if (arrived) endedDest = destination
        following = false; turn = null; nextTurn = null; routeLine = null; routeRef = null
        val seq = ++tripSeq
        main.post {
            restartPosted = false
            if (running && guidance === g && seq == tripSeq) runCatching { begin(g, null) }
        }
    }
    private var restartPosted = false
    private var lastSelfStop = 0L
    private var beganAt = 0L
    private var lastRevive = 0L
    /** 카카오 음성이 실제로 나간 마지막 시각(willPlayVoiceGuide). */
    @Volatile private var lastVoiceAt = 0L
    /** 최근 30초 안에 카카오 음성이 실제로 나갔는지 — 이때만 우리 카메라 접근 안내를 줄인다. */
    val voiceRecent: Boolean get() = running && System.currentTimeMillis() - lastVoiceAt < 30_000

    /** 카카오 정보가 1분 넘게 안 오면(콜백이 끊김) 5분에 한 번 안내를 다시 붙인다. setHere(실제 GPS)에서 부른다. */
    private fun reviveIfStale(now: Long) {
        val g = guidance ?: return
        if (!running || !ready || tripPending || switching) return
        val last = if (updatedAt > 0) updatedAt else beganAt
        if (last == 0L || now - last < 60_000 || now - lastRevive < 300_000) return
        lastRevive = now
        log("카카오 정보가 1분 넘게 없음 — 안내를 다시 붙입니다")
        val d = destination; val wasFollowing = following
        try {
            selfStop(g)
            setDelegates(g)
            turn = null; nextTurn = null; routeLine = null; routeRef = null; following = false
            if (d != null && wasFollowing && d !== endedDest) startTrip(g, d, ask = false) else begin(g, null)
        } catch (e: Throwable) { log("카카오 다시 붙이기 실패: ${e.javaClass.simpleName}") }
    }
    /** 경로를 바꾸려고 안내를 멈춘다(그 끝 알림은 "안내 끝"으로 보지 않음). */
    private fun selfStop(g: KNGuidance) { lastSelfStop = System.currentTimeMillis(); runCatching { g.stop() } }

    private val viewGuideState = object : KNNaviView_GuideStateDelegate {
        override fun naviViewGuideState(state: KNGuideState) = Unit
        override fun naviViewGuideEnded() = guideEnded("화면")
    }

    /** 주행 감시 정지 때. */
    fun stop() {
        if (!running && guidance == null) return
        running = false
        guidance?.let { g ->
            runCatching { g.stop() }
            runCatching { g.safetyGuideDelegate = null; g.locationGuideDelegate = null; g.voiceGuideDelegate = null; g.routeGuideDelegate = null; g.guideStateDelegate = null; g.citsGuideDelegate = null }
        }
        guidance = null
        tripSeq++
        destination = null; following = false; turn = null; nextTurn = null; routeLine = null; routeRef = null
        switchingUntil = 0L; tripFails = 0; tripPending = false
        askRouteUntil = 0L; askedDest = null; endedDest = null; curTrip = null; choices = emptyList(); previewSeq++
        cams = emptyList(); logged.clear()
        status = "카카오 꺼짐"
    }

    /** 마지막으로 받은 내 위치(경로 출발점). DriveService가 GPS마다 넣는다. */
    @Volatile private var lastLat = 0.0
    @Volatile private var lastLon = 0.0
    private var lastTripTry = 0L
    @Volatile private var tripPending = false
    /** 경로선 위 진행(경로 이탈 판단용). 경로선이 바뀌면 처음부터. */
    private var progRoute: DoubleArray? = null
    private var progSeg = 0
    private var offMisses = 0
    /** 경로 안내 중 경로에서 벗어남(3초 연속 60 m 밖) — 재탐색이 끝날 때까지 카카오 목록(옛 경로 위 카메라)을 쓰지 않는다. */
    @Volatile var offRoute = false
        private set

    fun setHere(lat: Double, lon: Double) {
        lastLat = lat; lastLon = lon
        lastHereAt = System.currentTimeMillis()
        val r = routeLine
        if (following && r != null) {
            if (r !== progRoute) { progRoute = r; progSeg = 0; offMisses = 0; offRoute = false }
            val sp = RouteProgress.locate(r, lat, lon, progSeg) ?: RouteProgress.locate(r, lat, lon, progSeg, Double.MAX_VALUE)
            if (sp != null) {
                progSeg = sp.seg; offMisses = 0
                if (offRoute) { offRoute = false; log("카카오 경로로 돌아옴 — 카카오 판단") }
            } else if (++offMisses >= 3 && !offRoute) {
                offRoute = true; log("카카오 경로 벗어남 — 재탐색까지 내장 판단")
            }
        } else if (offRoute) { offRoute = false; offMisses = 0 }
        // 목적지는 있는데 아직 경로가 없으면(위치를 늦게 잡음·경로 실패) 1분에 한 번 다시 시도
        val now = System.currentTimeMillis()
        reviveIfStale(now)
        val wait = RETRY_MS[(tripFails - 1).coerceIn(0, RETRY_MS.size - 1)]
        if (running && ready && destination != null && destination !== endedDest && !following && (!tripPending || now - lastTripTry > 60_000) && now - lastTripTry > wait) setDestination(destination)
    }

    /**
     * 목적지를 바꾼다(테슬라 내비 목적지). null이면 목적지 없이 안전 운행으로 돌아간다. 메인 스레드에서 부른다.
     * 경로 안내 중에도 카카오 음성은 계속 끈다(shouldPlayVoiceGuide = false).
     */
    fun setDestination(d: TeslaDest.Dest?) {
        if (d !== destination) endedDest = null
        destination = d
        val g = guidance
        if (!running || !ready || g == null) return
        try {
            selfStop(g)
            // 멈춘 안내의 거리(더 이상 줄지 않음)를 엔진에 주지 않도록 바로 비운다 → 전환 동안은 내장 판단
            cams = emptyList(); updatedAt = 0L
            switchingUntil = System.currentTimeMillis() + SWITCH_GRACE_MS
            turn = null; nextTurn = null; routeLine = null; routeRef = null; following = false
            if (d == null) {
                tripSeq++
                askRouteUntil = 0L
                begin(g, null)
                status = "카카오 안전 운행(목적지 없음)"; log(status)
            } else startTrip(g, d)
        } catch (e: Throwable) {
            status = "카카오 경로 전환 실패: ${e.javaClass.simpleName}"; log(status)
        }
    }

    private fun poi(name: String, lat: Double, lon: Double): KNPOI {
        val k = KNSDK.convertWGS84ToKATEC(lon, lat)
        // (이름, KATEC x, y, 주소) — 나머지는 기본값
        return KNPOI(name, k.x.toInt(), k.y.toInt(), name)
    }

    /** 지금 위치 → 목적지 경로를 만들어 안내를 시작한다. 실패하면 안전 운행으로. */
    private fun startTrip(g: KNGuidance, d: TeslaDest.Dest, ask: Boolean = true) {
        val seq = ++tripSeq
        // 새 목적지면 설정의 기본 경로 종류로 시작하고, 시작 뒤 10초 동안 고르기 창
        // v0.13.3: 감시를 껐다 켜면 askedDest가 비어 같은 목적지인데 다시 묻던 것 → 저장해 둔 것(3시간)과도 비교하고, 고른 경로 종류를 이어 쓴다
        if (ask && askedDest == null) app?.let { a ->
            Prefs.askedDest(a)?.let { (sd, so) -> if (!TeslaDest.changed(sd, d)) { askedDest = d; routeOpt = so; log("같은 목적지(최근 3시간 안에 고름) — 다시 묻지 않고 ${so.label}로") } }
        }
        val fresh = ask && TeslaDest.changed(askedDest, d)
        if (fresh) app?.let { routeOpt = Prefs.routeOpt(it); Prefs.setAskedDest(it, d, routeOpt) }
        if (lastLat == 0.0) { begin(g, null); status = "카카오 안전 운행 · 위치 대기(경로는 위치를 잡은 뒤)"; log(status); return }
        lastTripTry = System.currentTimeMillis()
        tripPending = true
        status = "카카오 경로 찾는 중 → ${TeslaDest.label(d)}"; log(status)
        KNSDK.makeTripWithStart(poi("현재 위치", lastLat, lastLon), poi(TeslaDest.label(d), d.lat, d.lon), null) { err, trip ->
            main.post {
                if (seq == tripSeq) tripPending = false
                if (seq != tripSeq || !running) return@post
                try {
                    if (err != null || trip == null) {
                        tripFails++
                        status = "카카오 경로 실패 ${err?.code ?: ""} — 안전 운행으로 (${RETRY_MS[(tripFails - 1).coerceIn(0, RETRY_MS.size - 1)] / 60_000}분 뒤 다시)"; log(status)
                        begin(g, null)
                        return@post
                    }
                    begin(g, trip)
                    following = true; tripFails = 0
                    status = "카카오 경로 안내 중 → ${TeslaDest.label(d)} (${routeOpt.label})"; log(status)
                    if (fresh) { askedDest = d; choices = emptyList(); previewRoutes(d) }
                } catch (e: Throwable) {
                    status = "카카오 경로 시작 실패: ${e.javaClass.simpleName}"; log(status)
                }
            }
        }
    }

    override fun guidanceDidUpdateRouteGuide(guidance: KNGuidance, routeGuide: KNGuide_Route) {
        fwd { it.guidanceDidUpdateRouteGuide(guidance, routeGuide) }
        if (!running) return
        refreshRoute(guidance, routeGuide)
    }

    private fun refreshRoute(g: KNGuidance, rg: KNGuide_Route?) {
        if (!following || g.trip == null) { turn = null; nextTurn = null; routeLine = null; return }
        runCatching {
            val here = g.locationGuide?.location
            fun info(dir: KNDirection?): TurnInfo? {
                dir ?: return null
                val dist = here?.distToLocation(dir.location)?.toInt() ?: -1
                if (dist < 0) return null
                val toward = dir.directionNames?.firstOrNull { !it.isNullOrBlank() } ?: dir.nodeName ?: ""
                return TurnInfo(TurnType.fromCodeName(dir.rgCode?.name), dist, toward)
            }
            if (rg != null) { turn = info(rg.curDirection); nextTurn = info(rg.nextDirection) }
        }
        // 경로선: 경로가 바뀌었을 때만 다시 읽는다(재탐색 포함)
        runCatching {
            val r = g.routesOnGuide?.firstOrNull()
            if (r != null && r !== routeRef) {
                routeRef = r
                routeLine = polyline(r)
            }
        }
    }

    /** routePolylineWGS84(): 점마다 {x: 경도, y: 위도} 같은 맵. 키 이름이 버전마다 달라 lat/lng·x/y 둘 다 본다. */
    private fun polyline(r: KNRoute): DoubleArray? {
        val pts = r.routePolylineWGS84() ?: return null
        val out = DoubleArray(pts.size * 2); var n = 0
        for (m in pts) {
            val keys = m.keys.associateBy { it.lowercase() }
            val la = (keys["lat"] ?: keys["latitude"] ?: keys["y"])?.let { m[it] }?.toDouble() ?: continue
            val lo = (keys["lng"] ?: keys["lon"] ?: keys["longitude"] ?: keys["x"])?.let { m[it] }?.toDouble() ?: continue
            out[n++] = la; out[n++] = lo
        }
        if (n < 4) { if (pts.isNotEmpty()) log("카카오 경로선 형식 모름: ${pts.first().keys}"); return null }
        return out.copyOf(n)
    }

    // ---- SDK 콜백

    override fun guidanceDidUpdateSafetyGuide(guidance: KNGuidance, safetyGuide: KNGuide_Safety?) {
        fwd { it.guidanceDidUpdateSafetyGuide(guidance, safetyGuide) }
        if (!running) return
        refresh(guidance, safetyGuide?.safetiesOnGuide?.toList().orEmpty())
    }

    override fun guidanceDidUpdateAroundSafeties(guidance: KNGuidance, safeties: List<KNSafety>?) {
        fwd { it.guidanceDidUpdateAroundSafeties(guidance, safeties) }
    }

    override fun guidanceDidUpdateLocation(guidance: KNGuidance, locationGuide: KNGuide_Location) {
        fwd { it.guidanceDidUpdateLocation(guidance, locationGuide) }
        if (!running) return
        refresh(guidance, guidance.safetyGuide?.safetiesOnGuide?.toList().orEmpty())
        if (following) refreshRoute(guidance, guidance.routeGuide)
    }

    /** v0.11: 카카오 음성(길안내·카메라 안내)을 낸다. 화면이 있으면 화면이 정하고, 없으면 안내가 직접 낸다. 과속 경고음은 우리 것(judgeOverSpeedAlert = false). */
    override fun shouldPlayVoiceGuide(guidance: KNGuidance, voiceGuide: KNGuide_Voice, strings: MutableList<ByteArray>): Boolean {
        // v0.11.7: 카카오가 직접 내면 소리가 안 나와서(실차) 음성 데이터를 우리 소리 경로(알람 채널 등)로 직접 튼다
        val data = runCatching { voiceGuide.data }.getOrNull()?.takeIf { it.isNotEmpty() }?.map { it.copyOf() }
        if (!voiceNoted) { voiceNoted = true; main.post { log("카카오 음성 첫 안내: 조각 ${data?.size ?: 0}개 · 화면 ${if (naviView != null) "있음" else "없음"}") } }
        // 직접 재생은 메인 스레드에서 차례로. 한 번 실패한 형식이면 그 뒤로는 카카오에 맡긴다.
        if (data != null && !voiceFailed && app != null) { main.post { playVoice(data) }; return false }
        val v = naviView?.takeIf { it === initedView } ?: return true
        return try { v.shouldPlayVoiceGuide(guidance, voiceGuide, strings) } catch (e: Throwable) { true }
    }
    private var voiceNoted = false
    @Volatile private var voiceFailed = false
    private var voiceFileSeq = 0
    /** 우리 소리 경로(설정 7의 채널). DriveService가 넣는다. */
    @Volatile var voiceUsage = android.media.AudioAttributes.USAGE_ALARM
    private val voiceQueue = ArrayDeque<ByteArray>()
    private var voicePlayer: android.media.MediaPlayer? = null

    /** 카카오 음성이 지금 나오는 중(우리 안내가 기다린다). */
    val voicePlaying: Boolean get() = runCatching { voicePlayer?.isPlaying == true }.getOrDefault(false) || voiceQueue.isNotEmpty()
    /** 우리 앱(TTS)이 말하는 중인지 — DriveService가 넣는다. 말하는 중이면 카카오 음성이 기다린다. */
    @Volatile var ourSpeaking: () -> Boolean = { false }

    /** 카카오 음성 조각을 차례로 튼다(메인 스레드). 우리 안내가 말하는 중이면 끝날 때까지(최대 5초) 기다린다. */
    private fun playVoice(chunks: List<ByteArray>, waitedMs: Long = 0) {
        val a = app ?: return
        if (waitedMs < 5_000 && runCatching { ourSpeaking() }.getOrDefault(false)) {
            main.postDelayed({ playVoice(chunks, waitedMs + 200) }, 200); return
        }
        voiceQueue.clear(); voiceQueue.addAll(chunks)
        runCatching { voicePlayer?.release() }; voicePlayer = null
        playNext(a)
    }
    private fun playNext(a: Application): Boolean {
        val bytes = voiceQueue.removeFirstOrNull() ?: return true
        return try {
            val f = java.io.File(a.cacheDir, "kn_voice_${(voiceFileSeq++) % 8}.bin").apply { writeBytes(bytes) }
            val mp = android.media.MediaPlayer()
            mp.setAudioAttributes(android.media.AudioAttributes.Builder().setUsage(voiceUsage)
                .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SPEECH).build())
            mp.setDataSource(f.absolutePath)
            mp.setOnCompletionListener { runCatching { it.release() }; if (voicePlayer === it) voicePlayer = null; playNext(a) }
            mp.prepare(); mp.start()
            voicePlayer = mp
            lastVoiceAt = System.currentTimeMillis()
            true
        } catch (e: Throwable) {
            if (!voiceFailed) { voiceFailed = true; log("카카오 음성 직접 재생 실패: ${e.javaClass.simpleName} ${e.message ?: ""} — 다음부터 카카오에 맡김") }
            voiceQueue.clear()
            false
        }
    }
    override fun willPlayVoiceGuide(guidance: KNGuidance, voiceGuide: KNGuide_Voice) {
        lastVoiceAt = System.currentTimeMillis()
        fwd { it.willPlayVoiceGuide(guidance, voiceGuide) }
    }
    override fun didFinishPlayVoiceGuide(guidance: KNGuidance, voiceGuide: KNGuide_Voice) { fwd { it.didFinishPlayVoiceGuide(guidance, voiceGuide) } }

    override fun didUpdateCitsGuide(guidance: KNGuidance, citsGuide: KNGuide_Cits) { fwd { it.didUpdateCitsGuide(guidance, citsGuide) } }

    override fun guidanceGuideStarted(guidance: KNGuidance) { fwd { it.guidanceGuideStarted(guidance) } }
    override fun guidanceCheckingRouteChange(guidance: KNGuidance) { fwd { it.guidanceCheckingRouteChange(guidance) } }
    override fun guidanceRouteUnchanged(guidance: KNGuidance) { fwd { it.guidanceRouteUnchanged(guidance) } }
    override fun guidanceRouteUnchangedWithError(guidance: KNGuidance, error: KNError) { fwd { it.guidanceRouteUnchangedWithError(guidance, error) } }
    override fun guidanceOutOfRoute(guidance: KNGuidance) { fwd { it.guidanceOutOfRoute(guidance) } }
    override fun guidanceRouteChanged(guidance: KNGuidance, fromRoute: KNRoute, fromLocation: KNLocation, toRoute: KNRoute, toLocation: KNLocation, changeReason: KNGuideRouteChangeReason) {
        fwd { it.guidanceRouteChanged(guidance) }
    }
    override fun guidanceGuideEnded(guidance: KNGuidance) {
        fwd { it.guidanceGuideEnded(guidance) }
        guideEnded("도착")
    }
    override fun guidanceDidUpdateRoutes(guidance: KNGuidance, routes: List<KNRoute>, multiRouteInfo: KNMultiRouteInfo?) {
        fwd { it.guidanceDidUpdateRoutes(guidance, routes, multiRouteInfo) }
    }
    override fun guidanceDidUpdateIndoorRoute(guidance: KNGuidance, route: KNRoute?) {
        fwd { it.guidanceDidUpdateIndoorRoute(guidance, route) }
    }

    /** 화면이 붙어 있으면 SDK 콜백을 화면에도 넘긴다. 화면 쪽 오류로 감속 판단이 멈추지 않게 예외는 삼킨다(한 번만 기록). */
    private var fwdErr = false
    private inline fun fwd(block: (KNNaviView) -> Unit) {
        val v = naviView ?: return
        if (initedView !== v) return // 화면이 아직 안내로 초기화되기 전(넘기면 lateinit 오류)
        try { block(v) } catch (e: Throwable) {
            if (!fwdErr) { fwdErr = true; log("카카오 화면 갱신 오류: ${e.javaClass.simpleName} ${e.message ?: ""}") }
        }
    }

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
        switchingUntil = 0L
        if (status.startsWith("카카오 안전 운행 시작")) status = "카카오 안전 운행 받는 중"
        else if (status.startsWith("카카오 경로 안내 중")) status = "카카오 경로 따라감 · " + (destination?.let { TeslaDest.label(it) } ?: "")
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
