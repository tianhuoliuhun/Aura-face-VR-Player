package com.example

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 资源可读性冒烟测试（Robolectric）。
 *
 * ⚠️ 原先这里断言 `app_name == "美颜VR播放器"` —— 应用改名后该断言即失效
 *    （实际为 "Aura美颜VR播放器" / "Aura face VR Player"，且取值随 locale 变化）。
 *    这类「把会变的文案硬编码进测试」的写法注定腐烂：
 *    ① 改名必挂 ② Robolectric 的 locale 会影响取到哪个 values/ 目录 ③ 挂了对功能毫无指示意义。
 *
 * 改为验证**行为**而非**具体文案**：资源存在、可读取、且非空。
 * 这才是这个冒烟测试真正想保证的事。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ExampleRobolectricTest {

  @Test
  fun `read string from context`() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val appName = context.getString(R.string.app_name)
    // 只断言「拿到了非空的字符串」，不绑定具体文案（文案会随版本与 locale 变化）
    assertFalse("app_name 不应为空", appName.isBlank())
    assertTrue("app_name 应包含品牌名 Aura", appName.contains("Aura"))
  }
}
