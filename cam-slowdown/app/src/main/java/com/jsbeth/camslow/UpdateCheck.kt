package com.jsbeth.camslow

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import java.net.HttpURLConnection
import java.net.URL

/**
 * 업데이트 알림(v0.10): 앱을 열 때·감시 시작 때(30분에 한 번) GitHub 릴리스 cam-slowdown-latest를 확인한다.
 * CI가 릴리스 파일 build.txt와 릴리스 본문에 "build: <versionCode> <커밋>"을 적는다. build.txt(내려받기 주소라 횟수 제한 없음)를
 * 먼저 보고, 없거나 실패하면 GitHub API(로그인 없이 IP당 시간 60회 — 통신사 공용 IP에선 막히기 쉬움)로.
 * 실패하면 이유를 기록에 남기고 5분 뒤 다시 확인할 수 있게 한다. 그 빌드가 이 앱보다 새것이면 알림을 띄우고,
 * 알림을 누르면 APK 설치 링크(브라우저가 내려받음)를 연다. 같은 빌드는 한 번만 알린다.
 */
object UpdateCheck {
    const val API = "https://api.github.com/repos/jsbeth-code/yoonmind/releases/tags/cam-slowdown-latest"
    const val APK = "https://github.com/jsbeth-code/yoonmind/releases/download/cam-slowdown-latest/cam-slowdown.apk"
    private const val CHANNEL = "update"
    private const val NOTI_ID = 7
    const val BUILD_TXT = "https://github.com/jsbeth-code/yoonmind/releases/download/cam-slowdown-latest/build.txt"
    private const val EVERY_MS = 30 * 60_000L
    private const val RETRY_MS = 5 * 60_000L

    data class Build(val code: Int, val sha: String)

    /** 릴리스 본문에서 "build: 19 83705e1…"을 찾는다. */
    fun parseBody(body: String?): Build? {
        val m = Regex("""build:\s*(\d+)\s+([0-9a-fA-F]{7,40})""").find(body ?: return null) ?: return null
        return Build(m.groupValues[1].toInt(), m.groupValues[2].lowercase())
    }

    /** 릴리스 빌드가 설치된 것보다 새것인지. versionCode가 크거나, 같아도 커밋이 다르면(같은 버전의 다음 빌드) 새것. */
    fun isNewer(rel: Build, myCode: Int, mySha: String): Boolean = when {
        rel.code > myCode -> true
        rel.code < myCode -> false
        mySha.isBlank() -> false // 로컬 빌드는 커밋을 모름
        else -> !rel.sha.startsWith(mySha.lowercase().take(7)) && !mySha.lowercase().startsWith(rel.sha)
    }

    fun maybeCheck(ctx: Context) {
        val app = ctx.applicationContext
        val sp = app.getSharedPreferences("update", 0)
        val now = System.currentTimeMillis()
        if (now - sp.getLong("checkedAt", 0L) < EVERY_MS) return
        // 실패해도 곧바로 다시 몰리지 않게 일단 RETRY_MS 뒤로 미뤄 두고, 성공하면 EVERY_MS로
        sp.edit().putLong("checkedAt", now - EVERY_MS + RETRY_MS).apply()
        Thread {
            val errs = mutableListOf<String>()
            val rel = fetch(BUILD_TXT, null, errs) { parseBody(it) }
                ?: fetch(API, "application/vnd.github+json", errs) { parseBody((MiniJson.parse(it) as? Map<*, *>)?.get("body") as? String) }
            if (rel == null) { DriveState.log("업데이트 확인 실패: ${errs.joinToString(" / ")} — 5분 뒤 다시"); return@Thread }
            sp.edit().putLong("checkedAt", System.currentTimeMillis()).apply()
            try {
                if (!isNewer(rel, BuildConfig.VERSION_CODE, BuildConfig.GIT_SHA)) return@Thread
                if (sp.getString("notified", "") == rel.sha) return@Thread
                sp.edit().putString("notified", rel.sha).apply()
                notify(app, rel)
                DriveState.log("새 빌드 있음 (버전 코드 ${rel.code}, ${rel.sha.take(7)}) — 알림을 눌러 설치")
            } catch (_: Exception) {}
        }.start()
    }

    sealed class Result {
        data class Newer(val rel: Build) : Result()
        object Latest : Result()
        data class Failed(val why: String) : Result()
    }

    /** 설정의 [업데이트 확인] 버튼: 30분 제한 없이 지금 확인하고, 결과를 메인 스레드로 돌려준다. */
    fun checkNow(ctx: Context, done: (Result) -> Unit) {
        val app = ctx.applicationContext
        val main = android.os.Handler(android.os.Looper.getMainLooper())
        Thread {
            val errs = mutableListOf<String>()
            val rel = fetch(BUILD_TXT, null, errs) { parseBody(it) }
                ?: fetch(API, "application/vnd.github+json", errs) { parseBody((MiniJson.parse(it) as? Map<*, *>)?.get("body") as? String) }
            val r = when {
                rel == null -> Result.Failed(errs.joinToString(" / "))
                isNewer(rel, BuildConfig.VERSION_CODE, BuildConfig.GIT_SHA) -> Result.Newer(rel)
                else -> Result.Latest
            }
            if (rel != null) app.getSharedPreferences("update", 0).edit().putLong("checkedAt", System.currentTimeMillis()).apply()
            if (r is Result.Failed) DriveState.log("업데이트 확인 실패: ${r.why}")
            main.post { done(r) }
        }.start()
    }

    /** 주소에서 글을 받아 빌드 정보를 읽는다. 실패하면 이유를 errs에 더하고 null. */
    private fun fetch(url: String, accept: String?, errs: MutableList<String>, parse: (String) -> Build?): Build? = try {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 10_000; c.readTimeout = 15_000
        accept?.let { c.setRequestProperty("Accept", it) }
        val code = c.responseCode
        if (code != 200) {
            val left = c.getHeaderField("X-RateLimit-Remaining")
            c.disconnect()
            errs += "${if (url == API) "API" else "build.txt"} HTTP $code" + (if (code == 403 && left == "0") "(횟수 제한)" else "")
            null
        } else {
            val text = c.inputStream.bufferedReader().use { it.readText() }
            c.disconnect()
            parse(text) ?: run { errs += "${if (url == API) "API" else "build.txt"} 형식 모름"; null }
        }
    } catch (e: Exception) {
        errs += "${if (url == API) "API" else "build.txt"} ${e.javaClass.simpleName}"
        null
    }

    private fun notify(ctx: Context, rel: Build) {
        val nm = ctx.getSystemService(NotificationManager::class.java) ?: return
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "앱 업데이트", NotificationManager.IMPORTANCE_DEFAULT))
        val open = PendingIntent.getActivity(ctx, 3, Intent(Intent.ACTION_VIEW, Uri.parse(APK)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val n = Notification.Builder(ctx, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle("FSD 감속 새 버전")
            .setContentText("누르면 설치 파일을 받습니다 (빌드 ${rel.sha.take(7)})")
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        runCatching { nm.notify(NOTI_ID, n) }
    }
}
