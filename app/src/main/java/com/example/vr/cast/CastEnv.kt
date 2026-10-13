package com.example.vr.cast

import android.content.Context

/**
 * v2.4.58：给 **由 jUPnP 反射实例化**的服务实现（如
 * [com.example.vr.cast.RenderingControlService]）提供全局的应用 Context。
 *
 * ## 为什么需要它
 * `DefaultServiceManager(LocalService<T>, Class<T>)` 会按 `Class<T>` 反射 new 出服务实现，
 * 因此服务类**必须无参构造**，拿不到构造注入的 Context。
 * 这里由 [CastReceiverService] 在启动时写入 `applicationContext`（整个接收端生命周期内有效，
 * 且 applicationContext 不会泄漏 Activity）。
 */
object CastEnv {

    @Volatile
    var appContext: Context? = null

    /** 供 Java 服务类调用 */
    @JvmStatic
    fun context(): Context? = appContext
}
