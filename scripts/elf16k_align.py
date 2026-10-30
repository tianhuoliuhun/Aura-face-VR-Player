"""把 4KB 对齐的预编译 .so 重排为 16KB 段对齐（Android 15+ 要求）。

原理：**完全不改任何 p_vaddr**，只在各 PT_LOAD 段起点前插入零填充，让
    p_offset % 16384 == p_vaddr % 16384
并把 p_align 提到 16384。

因为虚拟地址一律不变，`.dynamic` 里的 DT_* 绝对地址、符号表、重定位项
（r_offset 均为 vaddr）全部保持有效 —— 加载后的内存布局与原文件逐字节一致，
唯一变化是这些字节在文件里的位置。

用法：
    python elf16k.py --check  <so>...        # 只报告
    python elf16k.py --apply  <so>...        # 就地改写（调用方负责先备份）
"""
import struct
import sys

PT_LOAD = 1
PAGE = 16384


def parse(d):
    if d[:4] != b"\x7fELF":
        raise ValueError("not ELF")
    if d[4] != 2:
        raise ValueError("only ELF64 supported")
    e_phoff = struct.unpack_from("<Q", d, 0x20)[0]
    e_shoff = struct.unpack_from("<Q", d, 0x28)[0]
    e_phentsize, e_phnum = struct.unpack_from("<HH", d, 0x36)
    e_shentsize, e_shnum = struct.unpack_from("<HH", d, 0x3A)
    phs = []
    for i in range(e_phnum):
        o = e_phoff + i * e_phentsize
        p_type = struct.unpack_from("<I", d, o)[0]
        p_offset, p_vaddr = struct.unpack_from("<QQ", d, o + 8)
        p_filesz = struct.unpack_from("<Q", d, o + 32)[0]
        p_align = struct.unpack_from("<Q", d, o + 48)[0]
        phs.append(dict(idx=i, hdr_off=o, type=p_type,
                        offset=p_offset, vaddr=p_vaddr,
                        filesz=p_filesz, align=p_align))
    shs = []
    for i in range(e_shnum):
        o = e_shoff + i * e_shentsize
        sh_offset = struct.unpack_from("<Q", d, o + 0x18)[0]
        shs.append(dict(idx=i, hdr_off=o, offset=sh_offset))
    return dict(e_phoff=e_phoff, e_shoff=e_shoff,
                e_phentsize=e_phentsize, e_phnum=e_phnum,
                e_shentsize=e_shentsize, e_shnum=e_shnum,
                phs=phs, shs=shs)


def plan(d):
    """算出每个 PT_LOAD 的新文件偏移与需要在各起点前插入的字节数。"""
    info = parse(d)
    loads = sorted((p for p in info["phs"] if p["type"] == PT_LOAD),
                   key=lambda p: p["offset"])
    inserts = []          # [(orig_offset, delta)]
    prev_end = 0
    for p in loads:
        want_mod = p["vaddr"] % PAGE
        cand = max(p["offset"], prev_end)
        cand += (want_mod - cand % PAGE) % PAGE
        delta = cand - p["offset"]
        inserts.append((p["offset"], delta))
        p["new_offset"] = cand
        prev_end = cand + p["filesz"]
    return info, loads, inserts


def remap(x, inserts):
    """原文件位置 -> 新文件位置（累加所有发生在 x 之前或恰在 x 处的插入）。"""
    return x + sum(delta for off, delta in inserts if off <= x and delta)


def pad_len(n):
    return n.hex()


def convert(d):
    info, loads, inserts = plan(d)
    total = sum(delta for _, delta in inserts)
    if total == 0 and all(p["align"] >= PAGE for p in loads):
        return d, "already"

    # ELF header / program header table 必须原地不动，否则改表本身要先搬自己。
    # 本项目所有待处理库的第一个 LOAD 都是 off=0、vaddr=0（天然对齐，delta=0），
    # 所以这条断言恒成立；不成立说明结构特殊，宁可报错也不硬改。
    if remap(0, inserts) != 0 or remap(info["e_phoff"], inserts) != info["e_phoff"]:
        raise ValueError("首段需平移，本工具不支持该布局")

    # 1) 重建文件：在插入点处塞零
    points = sorted((off, delta) for off, delta in inserts if delta)
    out = bytearray()
    cur = 0
    for off, delta in points:
        out += d[cur:off]
        out += b"\x00" * delta
        cur = off
    out += d[cur:]

    # 2) patch program headers：PT_LOAD 用已算好的最终偏移；其余段按插入点累加平移
    load_new = {p["idx"]: p["new_offset"] for p in loads}
    for p in info["phs"]:
        new_off = load_new.get(p["idx"], remap(p["offset"], inserts))
        struct.pack_into("<Q", out, p["hdr_off"] + 8, new_off)
        if p["type"] == PT_LOAD and p["align"] < PAGE:
            struct.pack_into("<Q", out, p["hdr_off"] + 48, PAGE)

    # 3) patch section headers 与 e_shoff（不参与加载，但保证 readelf/工具链自洽）
    #    ⚠️ section header 表本身也在文件里，插入 padding 后它整体后移 →
    #    写入位置必须用 remap 后的位置，否则会写到旧位置（readelf 会报
    #    "SHT_DYNAMIC and PT_DYNAMIC disagree"）。
    if info["e_shoff"]:
        struct.pack_into("<Q", out, 0x28, remap(info["e_shoff"], inserts))
    for s in info["shs"]:
        new_hdr = remap(s["hdr_off"], inserts)
        if s["offset"]:
            struct.pack_into("<Q", out, new_hdr + 0x18, remap(s["offset"], inserts))

    return bytes(out), f"patched (+{total} B)"


def verify(d):
    info = parse(d)
    loads = [p for p in info["phs"] if p["type"] == PT_LOAD]
    bad = []
    for p in loads:
        if p["align"] < PAGE:
            bad.append(f"align={p['align']}")
        if (p["offset"] % PAGE) != (p["vaddr"] % PAGE):
            bad.append(f"cong off=0x{p['offset']:x} va=0x{p['vaddr']:x}")
    return bad


def main():
    mode = sys.argv[1]
    files = sys.argv[2:]
    for path in files:
        d = open(path, "rb").read()
        if mode == "--check":
            bad = verify(d)
            print(f"{path}: {'OK' if not bad else 'NEEDS FIX -> ' + '; '.join(bad)}")
            continue
        new, status = convert(d)
        if status == "already":
            print(f"{path}: 已对齐，跳过")
            continue
        bad = verify(new)
        if bad:
            print(f"{path}: ❌ 补丁后仍不合规 {bad}（未写盘）")
            continue
        if mode == "--dry":
            info, loads, inserts = plan(d)
            print(f"{path}: {status}")
            for p in loads:
                print(f"    LOAD off 0x{p['offset']:x} -> 0x{p['new_offset']:x}")
            continue
        with open(path, "wb") as f:
            f.write(new)
        print(f"{path}: ✅ {status}，{len(d):,} -> {len(new):,} B")


if __name__ == "__main__":
    main()
