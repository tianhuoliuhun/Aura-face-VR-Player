# -*- coding: utf-8 -*-
"""
从 Gradle 缓存 POM 提取依赖许可证，生成 assets/licenses.json（应用内开源许可页面数据源）。
用法: python gen_licenses.py
输入: app/build/deps.txt (由 gradlew :app:dumpDependencies 生成)
输出: app/src/main/assets/licenses.json
"""
import json, os, re, sys, glob, datetime

# 仓库根 = 本脚本所在目录的上一级（scripts/gen_licenses.py -> 仓库根）
REPO = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
DEPS = os.path.join(REPO, "app", "build", "deps.txt")
OUT = os.path.join(REPO, "app", "src", "main", "assets", "licenses.json")
GRADLE_CACHE = os.path.join(os.environ["USERPROFILE"], ".gradle", "caches", "modules-2", "files-2.1")

# 兜底映射：POM 里没有 license 元数据的坐标 -> 已知许可证
FALLBACK = {
    "androidx.*": "Apache-2.0",
    "com.android.*": "Apache-2.0",
    "com.google.*": "Apache-2.0",
    "org.jetbrains*": "Apache-2.0",
    "org.jetbrains.kotlin*": "Apache-2.0",
    "org.jetbrains.kotlinx*": "Apache-2.0",
    "com.squareup.*": "Apache-2.0",
    "io.github.kyant0:backdrop": "Apache-2.0",
    "com.alphacephei:vosk-android": "Apache-2.0",
    "eu.agno3.jcifs:jcifs-ng": "LGPL-2.1-or-later",
    "net.java.dev.jna:jna": "LGPL-2.1-or-later OR Apache-2.0",
    "org.bouncycastle:*": "MIT",
    "javax.inject:javax.inject": "Apache-2.0",
    "org.slf4j:slf4j-api": "MIT",
    "com.google.code.findbugs:jsr305": "Apache-2.0",
    "org.jspecify:jspecify": "Apache-2.0",
    "org.checkerframework:*": "MIT",
    "org.jetbrains:annotations": "Apache-2.0",
    "com.google.auto.value:*": "Apache-2.0",
    "com.google.code.gson:*": "Apache-2.0",
    "com.google.errorprone:*": "Apache-2.0",
    "com.google.j2objc:*": "Apache-2.0",
    "com.google.guava:*": "Apache-2.0",
    "com.google.protobuf:*": "BSD-3-Clause",
    "com.google.flogger:*": "Apache-2.0",
    "com.google.firebase:*": "Apache-2.0",
    "com.google.android.datatransport:*": "Apache-2.0",
}

# 非 Gradle 分发资源（打包进 APK 但不在依赖树里）
EXTRA = [
    {"name": "MiSans (可变字体 mi_sans_vf.ttf)", "license": "小米 MiSans 字体授权（免费商用）", "note": "由小米设计；可免费商用，但禁止修改字体文件、禁止单独分发字体本身"},
    {"name": "OPPO Sans (oppo_sans_4_0.ttf)", "license": "OPPO Sans 字体授权（免费商用）", "note": "OPPO 官方免费商用字体；禁止修改、需保留版权声明"},
    {"name": "MediaPipe Face Landmarker 模型 (face_landmarker.task)", "license": "Apache-2.0", "note": "Google MediaPipe 预训练模型，随 tasks-vision 库分发"},
    {"name": "Vosk 语音识别模型（运行时下载）", "license": "Apache-2.0", "note": "vosk-model-* 系列模型由 alphacephei.com 提供，Apache 2.0"},
    {"name": "内置 12 款 3D LUT 调色预设 (assets/luts/*.cube)", "license": "本项目自研生成资源", "note": "由自研 numpy 脚本（generate_lut_collection.py）程序化生成；生成记录存于作者本地，已按授权要求去除品牌字样" },
    {"name": "bing-translate-api（必应翻译免费端点，参考实现）", "license": "MIT（参考库许可）", "note": "字幕翻译的必应免费端点（cn.bing.com/ttranslatev3）实现思路参考 plainheart/bing-translate-api（https://github.com/plainheart/bing-translate-api，MIT）；本应用为自研 Kotlin HTTP 实现（SubtitleTranslator.kt），未直接引入该 npm 库。注意：免费网页端点非官方接口，可能随时失效或触发风控" },
]

def glob_fallback(group, name):
    """按通配前缀查兜底映射"""
    gid = f"{group}:{name}"
    for pat, lic in FALLBACK.items():
        if pat.endswith(":*"):
            if glob.fnmatch.fnmatch(group, pat[:-2] + "*"):
                return lic
        elif "*" in pat:
            if glob.fnmatch.fnmatch(gid, pat):
                return lic
    return None

def find_pom(group, name, version):
    """在 gradle 缓存中查找 pom 文件"""
    base = os.path.join(GRADLE_CACHE, group, name, version)
    if not os.path.isdir(base):
        return None
    for h in os.listdir(base):
        p = os.path.join(base, h, f"{name}-{version}.pom")
        if os.path.exists(p):
            return p
    return None

def parse_pom_license(pom_path):
    """从 POM 提取许可证名/URL 列表"""
    try:
        text = open(pom_path, encoding="utf-8", errors="replace").read()
    except Exception:
        return []
    # 粗解析 <licenses> 块
    m = re.search(r"<licenses>(.*?)</licenses>", text, re.S)
    if not m:
        return []
    block = m.group(1)
    out = []
    for lm in re.finditer(r"<license>(.*?)</license>", block, re.S):
        b = lm.group(1)
        nm = re.search(r"<name>(.*?)</name>", b, re.S)
        url = re.search(r"<url>(.*?)</url>", b, re.S)
        out.append({
            "name": nm.group(1).strip() if nm else "",
            "url": url.group(1).strip() if url else "",
        })
    return out

def main():
    if not os.path.exists(DEPS):
        print("缺少 deps.txt，请先运行: gradlew :app:dumpDependencies")
        sys.exit(1)
    coords = [l.strip() for l in open(DEPS, encoding="utf-8") if l.strip() and ":" in l]
    # 去掉本地模块
    coords = [c for c in coords if not c.startswith("My Application")]
    print(f"依赖坐标: {len(coords)}")

    items = []
    missing_pom = []
    for c in sorted(coords):
        group, name, version = c.split(":", 2)
        lic_names = []
        lic_urls = []
        pom = find_pom(group, name, version)
        if pom:
            for l in parse_pom_license(pom):
                if l["name"] and l["name"] not in lic_names:
                    lic_names.append(l["name"])
                if l["url"] and l["url"] not in lic_urls:
                    lic_urls.append(l["url"])
        if not lic_names:
            fb = glob_fallback(group, name)
            if fb:
                lic_names = [fb]
            else:
                missing_pom.append(c)
                lic_names = ["详见 POM（未提取到许可证元数据）"]
        items.append({
            "group": group, "name": name, "version": version,
            "license": " / ".join(lic_names) if lic_names else "见 POM",
            "url": " ".join(lic_urls[:2]),
        })

    if missing_pom:
        print("未找到 POM 或未提取到许可证（已用兜底/占位）:")
        for m in missing_pom:
            print("  -", m)

    data = {
        "generated": datetime.date.today().isoformat(),
        "count": len(items) + len(EXTRA),
        "items": items + EXTRA,
    }
    os.makedirs(os.path.dirname(OUT), exist_ok=True)
    with open(OUT, "w", encoding="utf-8") as f:
        json.dump(data, f, ensure_ascii=False, indent=2)
    print(f"已生成: {OUT} ({len(items) + len(EXTRA)} 项)")

if __name__ == "__main__":
    main()
