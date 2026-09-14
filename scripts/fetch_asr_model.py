#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
拉取内置 ASR 模型（SenseVoice-Small, CPU int8）到 app/src/main/assets/sense-voice/。

为什么需要这个脚本
-------------------
v2.0.127 起 ASR 模型内置进 APK（不再运行时下载），但 model.int8.onnx 有 228MB，
超过 GitHub 单文件 100MB 的硬限制，因此**不纳入 git**（见 .gitignore）。
clone 仓库后构建前，先跑一次本脚本把模型拉到本地即可，产物仍是「开箱即用」的内置版。

用法
----
    python scripts/fetch_asr_model.py            # 缺失才下载（推荐）
    python scripts/fetch_asr_model.py --force    # 强制重新下载
    python scripts/fetch_asr_model.py --check    # 只检查是否已就绪，不下载

模型来源：sherpa-onnx 官方 SenseVoice-Small（zh/en/ja/ko/yue，int8 量化，自带标点）
"""

import argparse
import os
import sys
import urllib.request

REPO = "csukuangfj/sherpa-onnx-sense-voice-zh-en-ja-ko-yue-2024-07-17"
BASE = "https://hf-mirror.com/%s/resolve/main" % REPO

# 目标文件：(文件名, 预期最小字节数)。只做下限校验，避免上游换包导致误判失败。
FILES = [
    ("model.int8.onnx", 200 * 1024 * 1024),  # 实际约 228MB
    ("tokens.txt", 100 * 1024),              # 实际约 0.3MB
]

# assets 目录：相对仓库根，与 .gitignore 中的路径保持一致
ASSET_DIR = os.path.join("app", "src", "main", "assets", "sense-voice")


def _repo_root() -> str:
    """定位仓库根（本脚本位于 <root>/scripts/）。"""
    return os.path.dirname(os.path.dirname(os.path.abspath(__file__)))


def _human(n: int) -> str:
    return "%.1f MB" % (n / 1048576.0)


def download(name: str, min_bytes: int, force: bool) -> bool:
    """下载单个文件，支持断点续传。返回 True 表示文件已就绪。"""
    dest_dir = os.path.join(_repo_root(), ASSET_DIR)
    os.makedirs(dest_dir, exist_ok=True)
    dest = os.path.join(dest_dir, name)

    if os.path.exists(dest) and not force:
        size = os.path.getsize(dest)
        if size >= min_bytes:
            print("  [跳过] %s 已存在（%s）" % (name, _human(size)))
            return True
        print("  [续传] %s 不完整（%s），继续下载……" % (name, _human(size)))

    url = "%s/%s" % (BASE, name)
    tmp = dest + ".part"
    resume = os.path.getsize(tmp) if os.path.exists(tmp) and not force else 0
    if force and os.path.exists(tmp):
        os.remove(tmp)

    req = urllib.request.Request(url, headers={"User-Agent": "Mozilla/5.0"})
    if resume:
        req.add_header("Range", "bytes=%d-" % resume)

    try:
        with urllib.request.urlopen(req, timeout=60) as resp:
            # 断点续传时服务端返回 206；返回 200 说明不支持续传，需从头写
            if resume and resp.status != 206:
                resume = 0
            mode = "ab" if resume else "wb"
            total = int(resp.headers.get("Content-Length", 0)) + resume
            done = resume
            with open(tmp, mode) as f:
                while True:
                    chunk = resp.read(262144)
                    if not chunk:
                        break
                    f.write(chunk)
                    done += len(chunk)
                    if total:
                        pct = done * 100.0 / total
                        sys.stdout.write("\r  下载 %s ... %5.1f%% (%s / %s)"
                                         % (name, pct, _human(done), _human(total)))
                        sys.stdout.flush()
                    else:
                        sys.stdout.write("\r  下载 %s ... %s" % (name, _human(done)))
                        sys.stdout.flush()
        sys.stdout.write("\n")
    except Exception as e:  # noqa: BLE001 - 网络错误统一降级为友好提示
        print("\n  [失败] %s：%s" % (name, e))
        print("         若 hf-mirror.com 不可达，可手动下载后放入 %s：" % dest_dir)
        print("         https://huggingface.co/%s" % REPO)
        return False

    size = os.path.getsize(tmp)
    if size < min_bytes:
        print("  [失败] %s 体积异常（%s < 预期 %s），已删除请重试"
              % (name, _human(size), _human(min_bytes)))
        os.remove(tmp)
        return False

    if os.path.exists(dest):
        os.remove(dest)
    os.rename(tmp, dest)
    print("  [完成] %s（%s）" % (name, _human(size)))
    return True


def main() -> int:
    ap = argparse.ArgumentParser(description="拉取内置 SenseVoice ASR 模型")
    ap.add_argument("--force", action="store_true", help="强制重新下载")
    ap.add_argument("--check", action="store_true", help="只检查是否就绪，不下载")
    args = ap.parse_args()

    print("内置 ASR 模型目录：%s" % os.path.join(_repo_root(), ASSET_DIR))
    if args.check:
        ok = True
        for name, min_bytes in FILES:
            p = os.path.join(_repo_root(), ASSET_DIR, name)
            if os.path.exists(p) and os.path.getsize(p) >= min_bytes:
                print("  [就绪] %s（%s）" % (name, _human(os.path.getsize(p))))
            else:
                print("  [缺失] %s —— 请运行 python scripts/fetch_asr_model.py" % name)
                ok = False
        return 0 if ok else 1

    ok = all(download(n, s, args.force) for n, s in FILES)
    if ok:
        print("\n模型就绪，可直接用 Gradle 构建（APK 将内置该模型，开箱即用）。")
        return 0
    print("\n模型未就绪。缺少 model.int8.onnx 时仍可构建，但离线字幕会退回下载模式。")
    return 1


if __name__ == "__main__":
    sys.exit(main())
