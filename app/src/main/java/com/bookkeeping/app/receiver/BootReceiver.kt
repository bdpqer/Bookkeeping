package com.bookkeeping.app.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import com.bookkeeping.app.BookkeepingApp
import com.bookkeeping.app.service.NotificationCaptureService

/**
 * 自启：系统启动或 App 自身被更新后，尽快拉起进程，让 NotificationCaptureService
 * 和 SmsContentObserver 恢复工作。
 *
 * 注意：NotificationListenerService 的绑定状态存在 SecureSettings，
 * 开机后系统会自动重新 bind 我们（只要用户权限没关）。
 * 这里主要是为了确保 ContentObserver 和前台通知尽快就绪。
 *
 * ⚠️ 必须同时监听 MY_PACKAGE_REPLACED：App 更新（应用商店自动更新 / 手动装包）时
 * 进程会被杀掉，而系统**不会**再发 BOOT_COMPLETED，只发「包被替换」。
 * 若只处理开机广播，更新后到下次开机之间自动记账静默停摆，
 * 且设置页「通知监听已开启」仍显示正常，用户无从察觉。
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        // 变量名不能叫 action：下面 Intent.apply { action = ... } 会命中这个局部变量
        val act = intent.action
        if (act != Intent.ACTION_BOOT_COMPLETED && act != Intent.ACTION_MY_PACKAGE_REPLACED) return

        Log.i(BookkeepingApp.TAG, "🚀 BootReceiver: $act，拉起服务")

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
