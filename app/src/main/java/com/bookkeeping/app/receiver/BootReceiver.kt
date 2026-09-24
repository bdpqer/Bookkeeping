package com.bookkeeping.app.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import com.bookkeeping.app.BookkeepingApp
import com.bookkeeping.app.service.NotificationCaptureService

/**
 * 开机自启：系统启动后尽快拉起进程，让 NotificationCaptureService
 * 和 SmsContentObserver 恢复工作。
 *
 * 注意：NotificationListenerService 的绑定状态存在 SecureSettings，
 * 开机后系统会自动重新 bind 我们（只要用户权限没关）。
 * 这里主要是为了确保 ContentObserver 和前台通知尽快就绪。
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return

        Log.i(BookkeepingApp.TAG, "🚀 BootReceiver: 开机完成，拉起服务")

        // 重启通知监听前台服务（它会通过 onStartCommand 调 refreshRules）
        val serviceIntent = Intent(context, NotificationCaptureService::class.java).apply {
            action = "com.bookkeeping.app.REFRESH_RULES"
        }
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(serviceIntent)
            } else {
                context.startService(serviceIntent)
            }
        } catch (e: Exception) {
            Log.e(BookkeepingApp.TAG, "BootReceiver startService failed", e)
        }
    }
}
