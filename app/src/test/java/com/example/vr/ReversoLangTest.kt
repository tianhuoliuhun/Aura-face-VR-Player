package com.example.vr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v2.6.11：Reverso 引擎的语言码映射与源语言判定单测。
 *
 * 为什么值得单独测：Reverso 用的是 **ISO 639-2 三字母码**，与项目其它引擎
 * （两字母码 / 自拼 `zh-CN`）**不是同一套**，而且 `from` 是**必填项**
 * （API 不接受 `auto`）—— 这两点任何一个错了，都是「HTTP 200 但译错方向」的静默故障：
 *  - 简繁混用（`chi` ↔ `cht`）→ 简体片全出繁体；
 *  - 源语言判错（中文当英文发）→ 返回乱译，但仍会被写进缓存（**永久命中错内容**）。
 *
 * ⚠️ 被测函数全部在 `SubtitleTranslator.Companion` 里（纯函数，无 Context 依赖）。
 */
class ReversoLangTest {

    private fun toLang(code: String) = SubtitleTranslator.mapReversoLang(code)
    private fun detect(text: String) = SubtitleTranslator.detectReversoSourceLang(text)

    // ===================================================================
    // 一、目标语言映射：三字母码 + 简繁必须分开
    // ===================================================================

    @Test
    fun `简体中文映射为 chi`() {
        assertEquals("chi", toLang("zh"))
    }

    @Test
    fun `繁体中文映射为 cht`() {
        assertEquals("cht", toLang("zh-TW"))
    }

    /** 简繁是两套码，绝不能落到同一个值 —— 这类「同一门语言两个码」最容易写错 */
    @Test
    fun `简繁中文不得映射到同一个码`() {
        assertNotEquals(toLang("zh"), toLang("zh-TW"))
    }

    @Test
    fun `其余目标语言映射为三字母码`() {
        assertEquals("eng", toLang("en"))
        assertEquals("jpn", toLang("ja"))
        assertEquals("kor", toLang("ko"))
        assertEquals("fra", toLang("fr"))
        assertEquals("ger", toLang("de"))
        assertEquals("spa", toLang("es"))
        assertEquals("rus", toLang("ru"))
    }

    /** 未登记的语言码兜底为 eng，且不能返回空串（空 from/to 会被上游 400） */
    @Test
    fun `未登记的语言码回退为 eng 且不为空`() {
        assertEquals("eng", toLang("th"))
        assertEquals("eng", toLang(""))
    }

    /**
     * 防「同一份数据两处登记」：`TranslationTargetLanguage` 里每个 code
     * 都必须能映射出一个**三字母**结果，否则等于新语言静默不可用。
     */
    @Test
    fun `全部目标语言都有可用的 Reverso 码`() {
        for (lang in TranslationTargetLanguage.values()) {
            val mapped = toLang(lang.code)
            assertEquals("语言 ${lang.code} 的映射不是三字母码：$mapped", 3, mapped.length)
        }
    }

    // ===================================================================
    // 二、源语言判定
    // ===================================================================

    @Test
    fun `英文判定为 eng`() {
        assertEquals("eng", detect("The problem of hallucinations in large language models"))
    }

    @Test
    fun `中文判定为 chi`() {
        assertEquals("chi", detect("大语言模型的幻觉问题仍然很难解决"))
    }

    /** 日文常含大量汉字，必须先看假名 —— 否则会被误判成中文 */
    @Test
    fun `日文判定为 jpn 而不是 chi`() {
        assertEquals("jpn", detect("これはテストです。大規模言語モデルの話"))
    }

    @Test
    fun `韩文判定为 kor`() {
        assertEquals("kor", detect("대규모 언어 모델의 환각 문제"))
    }

    @Test
    fun `俄文判定为 rus`() {
        assertEquals("rus", detect("Проблема галлюцинаций в больших языковых моделях"))
    }

    /** 特征字符占比 < 15% → 视为英文，避免「英文句子夹一个中文词」被整句当中文 */
    @Test
    fun `英文里夹少量中文仍判为 eng`() {
        assertEquals("eng", detect("This movie has a nice picture and 画面 quality"))
    }

    @Test
    fun `空串与纯数字回退为 eng`() {
        assertEquals("eng", detect(""))
        assertEquals("eng", detect("12345 !!! ---"))
    }

    /**
     * 与目标语言相同时上层会直接返回原文、不发请求 —— 这里固化「中译中」这条判据，
     * 防止以后有人把 detect 改到能返回 chi-TW 之类的值而破坏该短路。
     */
    @Test
    fun `中文源与中文目标相同`() {
        assertTrue(detect("这是一句中文字幕") == toLang("zh"))
    }
}
