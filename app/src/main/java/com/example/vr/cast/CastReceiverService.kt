package com.example.vr.cast

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import org.jupnp.UpnpService
import org.jupnp.UpnpServiceImpl
import org.jupnp.android.AndroidUpnpServiceConfiguration
import org.jupnp.model.meta.LocalDevice

/**
 * v2.4.58：**投屏接收的前台服务** —— DLNA/UPnP-AV MediaRenderer 的运行宿主。
 *
 * ## 为什么必须是前台服务
 * SSDP 的发现依赖「周期性组播心跳（`NOTIFY`）」与「随时响应 `M-SEARCH`」。
 * 若跑在后台，Android 会冻结/回收进程 → 手机会**搜不到这台设备**，
 * 或者投屏中途断连。前台服务（常驻通知）才能稳定提供该能力。
 *
 * ## 生命周期
 * - 设置页开关打开 → `startForegroundService` → 本服务启动：
 *   1) 申请 **MulticastLock**（不申请收不到组播，是 DLNA「发现不了」的头号原因）
 *   2) 启动 jUPnP `UpnpService`（Android 专用配置）
 *   3) 组装并注册 `MediaRenderer:1` 设备（见 [MediaRendererDeviceFactory]）
 * - 开关关闭 → `stopService` → `onDestroy` 反注册设备、`shutdown` 栈、释放锁
 */
class CastReceiverService : Service() {

    companion object {
        private const val TAG = "CastReceiver"
        private const val CHANNEL_ID = "cast_receiver"
        private const val NOTIFICATION_ID = 41001

        /** 手机端「投屏」列表里显示的设备名 */
        const val FRIENDLY_NAME = "Aura VR Player"

        fun start(context: Context) {
            val i = Intent(context, CastReceiverService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(i)
            } else {
                context.startService(i)
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, CastReceiverService::class.java))
        }
    }

    private var upnpService: UpnpService? = null
    private var localDevice: LocalDevice? = null
    private var multicastLock: WifiManager.MulticastLock? = null

    override fun onCreate() {
        super.onCreate()
        // RenderingControlService 由 jUPnP 反射实例化、拿不到构造注入 → Context 走全局持有
        CastEnv.appContext = applicationContext
        startAsForeground()
        acquireMulticastLock()
        bootUpnp()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        unregisterDevice()
        releaseMulticastLock()
        super.onDestroy()
    }

    // ------------------------------------------------------------------ 前台通知

    private fun startAsForeground() {
        createChannelIfNeeded()
        val notification: Notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(applicationInfo.icon)
            .setContentTitle("投屏接收已开启")
            .setContentText("同一 Wi-Fi 下的设备可在投屏列表中看到「$FRIENDLY_NAME」")
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ServiceCompat.startForeground(
                this, NOTIFICATION_ID, notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK,
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun createChannelIfNeeded() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (nm.getNotificationChannel(CHANNEL_ID) != null) return
        nm.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                "投屏接收",
                NotificationManager.IMPORTANCE_LOW,
            ).apply { description = "DLNA/UPnP 投屏接收服务保活" },
        )
    }

    // -------------------------------------------------------------------- 组播锁

    /**
     * Android 默认**过滤组播报文**：不持有 MulticastLock 时，
     * SSDP 的 `NOTIFY` 发不出去、`M-SEARCH` 也收不到 —— 表现为「设备搜不到」。
     */
    private fun acquireMulticastLock() {
        try {
            val wm = applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
            val lock = wm?.createMulticastLock("aura_cast_receiver")?.apply {
                setReferenceCounted(false)
                acquire()
            }
            multicastLock = lock
            Log.i(TAG, "MulticastLock acquired")
        } catch (t: Throwable) {
            Log.w(TAG, "获取 MulticastLock 失败（组播可能不可用）", t)
        }
    }

    private fun releaseMulticastLock() {
        try {
            multicastLock?.takeIf { it.isHeld }?.release()
        } catch (_: Throwable) {
        }
        multicastLock = null
    }

    // -------------------------------------------------------------------- UPnP

    private fun bootUpnp() {
        try {
            val service = UpnpServiceImpl(AndroidUpnpServiceConfiguration())
            service.startup()
            val device = MediaRendererDeviceFactory.create(FRIENDLY_NAME)
            service.registry.addDevice(device)
            upnpService = service
            localDevice = device
            Log.i(TAG, "MediaRenderer 已注册：$FRIENDLY_NAME（UDN=${device.identity.udn}）")
        } catch (t: Throwable) {
            Log.e(TAG, "启动 UPnP 栈失败（投屏接收不可用）", t)
        }
    }

    private fun unregisterDevice() {
        try {
            val d = localDevice
            if (d != null) upnpService?.registry?.removeDevice(d)
        } catch (t: Throwable) {
            Log.w(TAG, "反注册设备失败", t)
        }
        try {
            upnpService?.shutdown()
        } catch (t: Throwable) {
            Log.w(TAG, "关闭 UPnP 栈失败", t)
        }
        localDevice = null
        upnpService = null
        Log.i(TAG, "投屏接收已停止")
    }
}
