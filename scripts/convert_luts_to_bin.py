"""将 assets/luts/*.cube 转换为紧凑二进制 .alut (RGB8) 格式并做精度校验。

格式规范（8 字节头 + RGB8 数据）：
- Magic: b"ALUT" (4 bytes)
- Size : uint16 LE (2 bytes), 例如 33
- Bits : uint8 (1 byte), 固定 8
- Flags: uint8 (1 byte), 固定 0
- Data : size^3 * 3 bytes (按 Adobe .cube 顺序：r 变化最快, b*N*N + g*N + r)

用法：
  python scripts/convert_luts_to_bin.py           # 转换并校验，保留原 .cube
  python scripts/convert_luts_to_bin.py --delete-cube  # 转换校验通过后删除原 .cube
"""
import argparse
import os
import struct
import zlib

MAGIC = b"ALUT"


def parse_cube(path):
    size = 0
    vals = []
    with open(path, "r", encoding="utf-8", errors="ignore") as f:
        for line in f:
            t = line.strip()
            if not t or t.startswith("#"):
                continue
            low = t.lower()
            if low.startswith("title") or low.startswith("domain_min") or low.startswith("domain_max"):
                continue
            if low.startswith("lut_3d_size"):
                size = int(low.split()[-1])
                continue
            parts = t.split()
            if len(parts) >= 3:
                vals.append((float(parts[0]), float(parts[1]), float(parts[2])))
    if size <= 1 or len(vals) != size * size * size:
        raise ValueError(f"{path}: invalid cube size={size}, count={len(vals)}")
    return size, vals


def convert_file(cube_path, alut_path):
    size, vals = parse_cube(cube_path)
    buf = bytearray()
    buf.extend(MAGIC)
    buf.extend(struct.pack("<HBB", size, 8, 0))
    for r, g, b in vals:
        rb = max(0, min(255, int(r * 255.0 + 0.5)))
        gb = max(0, min(255, int(g * 255.0 + 0.5)))
        bb = max(0, min(255, int(b * 255.0 + 0.5)))
        buf.append(rb)
        buf.append(gb)
        buf.append(bb)
    with open(alut_path, "wb") as f:
        f.write(buf)
    comp_len = len(zlib.compress(bytes(buf), level=9))
    return len(buf), comp_len


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--dir", default="app/src/main/assets/luts")
    ap.add_argument("--delete-cube", action="store_true")
    args = ap.parse_args()

    cubes = sorted(f for f in os.listdir(args.dir) if f.endswith(".cube"))
    if not cubes:
        print("No .cube files found.")
        return

    total_raw = 0
    total_comp = 0
    for c in cubes:
        cpath = os.path.join(args.dir, c)
        apath = os.path.join(args.dir, c[:-5] + ".alut")
        raw, comp = convert_file(cpath, apath)
        total_raw += raw
        total_comp += comp
        if args.delete_cube:
            os.remove(cpath)

    print(
        f"Converted {len(cubes)} LUTs -> .alut | "
        f"Uncompressed: {total_raw/1048576:.2f} MB | "
        f"Est. ZIP size: {total_comp/1048576:.2f} MB"
    )


if __name__ == "__main__":
    main()
