package com.jsbeth.camslow

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Handler
import android.os.Looper
import java.net.HttpURLConnection
import java.net.URL

/**
 * 테슬라 목적지 중계 Worker(tools/tesla-worker.js, https://fsd-tesla.jsbeth.workers.dev)와 통신.
 *  - 연결: 브라우저로 /auth/start → 테슬라 로그인 → Worker가 fsdslow://tesla?session=… 로 앱을 연다(또는 세션 키를 붙여 넣음).
 *  - 확인: GET /dest (Authorization: Bearer <세션 키>). 차가 자면 {"online":false} — 깨우지 않는다.
 * 세션 키는 Prefs에만 두고 화면·기록엔 앞 4자리만 보인다.
 */
object TeslaLink {
    const val BASE = "https://fsd-tesla.jsbeth.workers.dev"
    /** 감시 중 확인 간격. */
    const val POLL_MS = 120_000L
    private const val LOGIN_WINDOW_MS = 15 * 60_000L

    private val main = Handler(Looper.getMainLooper())

    fun connected(ctx: Context) = Prefs.teslaSession(ctx).isNotBlank()

    fun openLogin(ctx: Context) {
        ctx.getSharedPreferences("tesla", 0).edit().putLong("loginAt", System.currentTimeMillis()).apply()
        ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("$BASE/auth/start")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    /** 이 앱에서 "테슬라 연결"을 누른 지 15분 안인지(그때 온 링크만 묻지 않고 받는다). */
    fun loginRecent(ctx: Context): Boolean =
        System.currentTimeMillis() - ctx.getSharedPreferences("tesla", 0).getLong("loginAt", 0L) in 0..LOGIN_WINDOW_MS

    /** fsdslow://tesla?session=… 에서 세션 키를 꺼낸다. 형식이 아니면 null. */
    fun sessionFrom(uri: Uri?): String? {
        if (uri == null || uri.scheme != "fsdslow" || uri.host != "tesla") return null
        val s = uri.getQueryParameter("session")?.trim().orEmpty()
        return s.takeIf { valid(it) }
    }

    fun accept(ctx: Context, s: String) {
        Prefs.setTeslaSession(ctx, s)
        ctx.getSharedPreferences("tesla", 0).edit().putLong("loginAt", 0L).apply()
        DriveState.log("테슬라 연결됨 (세션 ${TeslaDest.mask(s)})")
    }

    /** Worker가 만드는 세션 키: 16진 48자. 붙여 넣기 실수(공백·따옴표)를 거른다. */
    fun valid(s: String) = s.length in 16..128 && s.all { it.isLetterOrDigit() }

    /** 폰의 세션 키를 지우고, 중계 서버에도 그 세션(테슬라 토큰)을 지우라고 알린다(/auth/logout — 서버가 지원할 때). */
    fun disconnect(ctx: Context) {
        val session = Prefs.teslaSession(ctx)
        Prefs.setTeslaSession(ctx, "")
        Prefs.setTeslaLast(ctx, "연결 끊김")
        if (session.isBlank()) return
        Thread {
            val msg = try {
                val c = URL("$BASE/auth/logout").openConnection() as HttpURLConnection
                c.requestMethod = "POST"; c.connectTimeout = 10_000; c.readTimeout = 15_000
                c.setRequestProperty("Authorization", "Bearer $session")
                val code = c.responseCode; c.disconnect()
                when (code) { in 200..299 -> "서버의 테슬라 토큰도 지움"; 404 -> "서버가 아직 로그아웃을 지원하지 않음(Worker 업데이트 필요)"; else -> "서버 로그아웃 실패 HTTP $code" }
            } catch (e: Exception) { "서버 로그아웃 실패: ${e.javaClass.simpleName}" }
            main.post { DriveState.log("테슬라 연결 끊김 — $msg") }
        }.start()
    }

    /** 진행 중인 /dest 요청에 결과를 기다리는 쪽들(겹친 요청은 하나로 합친다 — 토큰 갱신 경합 방지). */
    private val waiting = ArrayList<(TeslaDest.Reply) -> Unit>()

    /** 백그라운드에서 /dest를 부르고 메인 스레드로 결과를 준다. 메인 스레드에서 부른다. */
    fun fetch(ctx: Context, done: (TeslaDest.Reply) -> Unit) {
        val session = Prefs.teslaSession(ctx)
        if (session.isBlank()) { done(TeslaDest.Reply.Error("연결 안 됨")); return }
        waiting += done
        if (waiting.size > 1) return
        Thread {
            val reply = try {
                val c = URL("$BASE/dest").openConnection() as HttpURLConnection
                c.connectTimeout = 10_000; c.readTimeout = 20_000
                c.setRequestProperty("Authorization", "Bearer $session")
                val code = c.responseCode
                val body = (if (code in 200..299) c.inputStream else c.errorStream)?.bufferedReader()?.use { it.readText() }.orEmpty()
                c.disconnect()
                val r = TeslaDest.parse(body)
                if (code == 401 && r !is TeslaDest.Reply.Error) TeslaDest.Reply.Error("로그인 필요") else r
            } catch (e: Exception) {
                TeslaDest.Reply.Error("통신 실패: ${e.javaClass.simpleName}")
            }
            main.post {
                val note = when (reply) {
                    is TeslaDest.Reply.Offline -> "차가 자는 중"
                    is TeslaDest.Reply.Online -> reply.dest?.let { "목적지: ${TeslaDest.label(it)}" } ?: "목적지 없음"
                    is TeslaDest.Reply.Error -> "오류: ${reply.message}"
                }
                Prefs.setTeslaLast(ctx, note)
                val all = ArrayList(waiting); waiting.clear()
                for (d in all) d(reply)
            }
        }.start()
    }
}
