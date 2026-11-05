package com.jsbeth.camslow

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.PowerManager

/**
 * 설정한 차(블루투스 기기)가 연결되면 주행 감시를 자동으로 시작하고, 끊기면(선택) 잠시 뒤 정지한다.
 * 앱이 꺼져 있어도 시스템이 이 리시버를 깨운다(ACL_CONNECTED는 백그라운드 실행 제한의 예외 브로드캐스트).
 */
class BluetoothReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        if (!Prefs.autoStart(ctx)) return
        val want = Prefs.autoDeviceAddress(ctx)
        if (want.isBlank()) return
        @Suppress("DEPRECATION")
        val device: BluetoothDevice? = if (Build.VERSION.SDK_INT >= 33)
            intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
        else intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
        val addr = device?.address ?: return
        if (!addr.equals(want, ignoreCase = true)) return
        when (intent.action) {
            BluetoothDevice.ACTION_ACL_CONNECTED -> AutoStart.carConnected(ctx)
            BluetoothDevice.ACTION_ACL_DISCONNECTED -> AutoStart.carDisconnected(ctx)
        }
    }
}

object AutoStart {
    private const val CHANNEL = "auto"
    private const val NOTI_ID = 2
    const val EXTRA_AUTOSTART = "autostart"

    /** 자동 시작에 필요한데 아직 안 된 것들. 비어 있으면 백그라운드에서 바로 시작할 수 있다. */
    fun missing(ctx: Context): List<String> {
        val out = ArrayList<String>()
        fun has(p: String) = ctx.checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED
        if (!has(Manifest.permission.ACCESS_FINE_LOCATION)) out += "위치 권한"
        if (Build.VERSION.SDK_INT >= 29 && !has(Manifest.permission.ACCESS_BACKGROUND_LOCATION)) out += "위치 '항상 허용'"
        if (Build.VERSION.SDK_INT >= 31 && !has(Manifest.permission.BLUETOOTH_CONNECT)) out += "블루투스 권한"
        if (Build.VERSION.SDK_INT >= 31 && !isBatteryExempt(ctx)) out += "배터리 최적화 제외"
        return out
    }

    fun isBatteryExempt(ctx: Context): Boolean =
        ctx.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(ctx.packageName)

    fun carConnected(ctx: Context) {
        if (DriveState.running) {
            // 이미 감시 중(연결이 잠깐 끊겼다 붙은 경우): 예약된 자동 정지만 취소
            try { ctx.startService(Intent(ctx, DriveService::class.java).setAction(DriveService.ACTION_CAR_CONNECTED)) } catch (_: Exception) {}
            return
        }
        val missing = missing(ctx)
        if (missing.isEmpty()) {
            try {
                ctx.startForegroundService(
                    Intent(ctx, DriveService::class.java).setAction(DriveService.ACTION_START).putExtra("source", "bluetooth"),
                )
                return
            } catch (e: Exception) {
                // Android 12+: 백그라운드에서 포그라운드 서비스 시작이 막힌 경우(ForegroundServiceStartNotAllowedException)
                DriveState.log("자동 시작 실패: ${e.javaClass.simpleName}")
            }
        }
        notifyTapToStart(ctx, missing)
    }

    fun carDisconnected(ctx: Context) {
        if (!Prefs.autoStop(ctx) || !DriveState.running) return
        try { ctx.startService(Intent(ctx, DriveService::class.java).setAction(DriveService.ACTION_CAR_DISCONNECTED)) } catch (_: Exception) {}
    }

    /** 바로 시작하지 못할 때: 탭하면 시작되는 알림(알림 탭은 사용자 조작이라 포그라운드 서비스 시작이 허용된다). */
    private fun notifyTapToStart(ctx: Context, missing: List<String>) {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "차 연결 자동 시작", NotificationManager.IMPORTANCE_HIGH))
        val open = PendingIntent.getActivity(
            ctx, 1, Intent(ctx, MainActivity::class.java).putExtra(EXTRA_AUTOSTART, true)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val text = if (missing.isEmpty()) "탭하면 카메라 감시를 시작합니다"
            else "탭해서 시작. 자동 시작하려면 설정에서 ${missing.joinToString(", ")} 필요"
        nm.notify(NOTI_ID, Notification.Builder(ctx, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_menu_compass)
            .setContentTitle("차 연결됨 — FSD 감속")
            .setContentText(text)
            .setStyle(Notification.BigTextStyle().bigText(text))
            .setContentIntent(open)
            .setAutoCancel(true)
            .build())
    }

    fun cancelNotification(ctx: Context) = ctx.getSystemService(NotificationManager::class.java).cancel(NOTI_ID)
}
