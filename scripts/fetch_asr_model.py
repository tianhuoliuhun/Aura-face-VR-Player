#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
（可选）把内置 ASR 模型（Dolphin，多语言 CTC int8）预置到 app/src/main/assets/dolphin/。

⚠️ v2.6.11 起**默认不再需要本脚本**
--------------------------------
ASR 模型已从 APK 移除，改为安装后首次使用语音识别时由 App 按需下载
（ModelScope 按文件、支持断点续传）。因此 clone 下来直接编译即可得到完整可用的 APK，
安装包也因此小约 100MB。

只有在需要「离线安装、装完即用 ASR」（内网分发 / 无网部署）时，才跑本脚本把模型
预置回 assets —— 预置后 APK 会重新增大约 100MB，且优先级高于下载版。

用法
----
    python scripts/fetch_asr_model.py            # 缺失才下载（推荐）
    python scripts/fetch_asr_model.py --force    # 强制重新下载
    python scripts/fetch_asr_model.py --check    # 只检查是否已就绪，不下载

模型来源：sherpa-onnx 官方 Dolphin base CTC 多语言版（40 东方语言 + 22 中文方言，
int8 量化，自带语种识别）—— 与 App 运行时下载的是同一份权重。
"""

import argparse
import os
import sys
import urllib.request

REPO = "csukuangfj/sherpa-onnx-dolphin-base-ctc-multi-lang-int8-2025-04-02"
BASE = "https://hf-mirror.com/%s/resolve/main" % REPO

# 目标文件：(文件名, 预期最小字节数)。只做下限校验，避免上游换包导致误判失败。
# 与 Kotlin 侧 AsrExtModels 里的 minBytes 保持一致（90MB / 400KB）。
FILES = [
    ("model.int8.onnx", 90 * 1024 * 1024),   # 实际约 103.7MB
    ("tokens.txt", 400 * 1024),              # 实际约 0.5MB
]

# assets 目录：相对仓库根，必须与 SherpaAsrManager.DOLPHIN_ASSET_DIR（"dolphin"）一致
ASSET_DIR = os.path.join("app", "src", "main", "assets", "dolphin")


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
    ap = argparse.ArgumentParser(description="（可选）预置 Dolphin ASR 模型到 assets")
    ap.add_argument("--force", action="store_true", help="强制重新下载")
    ap.add_argument("--check", action="store_true", help="只检查是否就绪，不下载")
    args = ap.parse_args()

    print("ASR 模型预置目录：%s" % os.path.join(_repo_root(), ASSET_DIR))
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
        print("\n模型已预置 —— 构建出的 APK 将内置该模型（装完即用，不联网下载）。")
        print("⚠️ 不需要内置时，删掉 app/src/main/assets/dolphin/ 即可恢复精简包。")
        return 0
    print("\n模型未预置（不影响构建）—— App 会在首次使用语音识别时按需下载。")
    return 1


if __name__ == "__main__":
    sys.exit(main())
